package com.bg7yoz.ft8cn.core.time

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max

enum class ClockSource {
    SYSTEM,
    NTP,
    GNSS,
    HOLDOVER,
}

data class ClockSnapshot(
    val utcMillis: Long,
    val monotonicNanos: Long,
    val offsetMillis: Double,
    val driftPpm: Double,
    val uncertaintyMillis: Double,
    val source: ClockSource,
    val sampleAgeMillis: Long,
    val healthy: Boolean,
    val detail: String = "",
)

data class ClockSample(
    val utcMillis: Double,
    val monotonicNanos: Long,
    val uncertaintyMillis: Double,
    val source: ClockSource,
    val detail: String = "",
)

data class ClockHealthPolicy(
    val healthyUncertaintyMillis: Double = 1_000.0,
    val automaticTxUncertaintyMillis: Double = 500.0,
    val maximumSampleAgeMillis: Long = 30 * 60 * 1_000L,
    val holdoverAfterMillis: Long = 2 * 60 * 1_000L,
    val maximumDriftPpm: Double = 50.0,
)

interface MonotonicTimeSource {
    fun elapsedRealtimeNanos(): Long

    fun wallClockMillis(): Long
}

object AndroidMonotonicTimeSource : MonotonicTimeSource {
    override fun elapsedRealtimeNanos(): Long = try {
        SystemClock.elapsedRealtimeNanos()
    } catch (_: RuntimeException) {
        // Android local unit tests use an SDK stub; production always takes the branch above.
        System.nanoTime()
    }

    override fun wallClockMillis(): Long = System.currentTimeMillis()
}

interface DisciplinedClock {
    val state: StateFlow<ClockSnapshot>

    fun snapshot(): ClockSnapshot = state.value
}

/**
 * 在单调时钟上维护 UTC 锚点。外部 wall clock 的跳变不会直接改变正在运行的 slot。
 */
class SystemDisciplinedClock(
    private val timeSource: MonotonicTimeSource = AndroidMonotonicTimeSource,
    private val policy: ClockHealthPolicy = ClockHealthPolicy(),
) : DisciplinedClock {
    private val lock = Any()
    private var anchorMonotonicNanos = timeSource.elapsedRealtimeNanos()
    private var anchorUtcMillis = timeSource.wallClockMillis().toDouble()
    private var acceptedSampleMonotonicNanos = anchorMonotonicNanos
    private var acceptedSampleUtcMillis = anchorUtcMillis
    private var baseUncertaintyMillis = 5_000.0
    private var driftPpm = 0.0
    private var disciplinedSource = ClockSource.SYSTEM
    private var detail = "等待 NTP 或可信 GNSS 时间"

    private val mutableState = MutableStateFlow(snapshotLocked(anchorMonotonicNanos))
    override val state: StateFlow<ClockSnapshot> = mutableState.asStateFlow()

    override fun snapshot(): ClockSnapshot = synchronized(lock) {
        refreshLocked(timeSource.elapsedRealtimeNanos())
    }

    fun submitSample(sample: ClockSample): Boolean = synchronized(lock) {
        require(sample.source == ClockSource.NTP || sample.source == ClockSource.GNSS) {
            "时间纪律样本只能来自 NTP 或 GNSS"
        }
        if (!sample.utcMillis.isFinite() || !sample.uncertaintyMillis.isFinite()
            || sample.uncertaintyMillis < 0.0
        ) {
            return false
        }

        val nowMonotonic = timeSource.elapsedRealtimeNanos()
        val sampleAgeNanos = nowMonotonic - sample.monotonicNanos
        if (sampleAgeNanos < -100_000_000L || sampleAgeNanos > 60_000_000_000L) {
            return false
        }

        val predictedAtSample = utcAtLocked(sample.monotonicNanos)
        val residualMillis = sample.utcMillis - predictedAtSample
        val currentlyTrusted = disciplinedSource != ClockSource.SYSTEM
        if (currentlyTrusted && abs(residualMillis) > MAX_TRUSTED_SAMPLE_RESIDUAL_MS) {
            return false
        }

        val previousSampleMono = acceptedSampleMonotonicNanos
        val previousSampleUtc = acceptedSampleUtcMillis
        val elapsedSincePreviousMs = (sample.monotonicNanos - previousSampleMono) / 1_000_000.0
        if (currentlyTrusted && elapsedSincePreviousMs >= MIN_DRIFT_WINDOW_MS) {
            val utcElapsedMs = sample.utcMillis - previousSampleUtc
            val measuredPpm = ((utcElapsedMs / elapsedSincePreviousMs) - 1.0) * 1_000_000.0
            if (measuredPpm.isFinite() && abs(measuredPpm) <= MAX_MEASURED_DRIFT_PPM) {
                driftPpm = (driftPpm * 0.75 + measuredPpm * 0.25)
                    .coerceIn(-policy.maximumDriftPpm, policy.maximumDriftPpm)
            }
        }

        val correction = if (!currentlyTrusted) {
            residualMillis
        } else {
            residualMillis.coerceIn(-MAX_SLEW_STEP_MS, MAX_SLEW_STEP_MS) * SLEW_GAIN
        }
        val correctedAtSample = predictedAtSample + correction
        anchorMonotonicNanos = sample.monotonicNanos
        anchorUtcMillis = correctedAtSample
        acceptedSampleMonotonicNanos = sample.monotonicNanos
        acceptedSampleUtcMillis = sample.utcMillis
        baseUncertaintyMillis = max(sample.uncertaintyMillis, abs(residualMillis - correction))
        disciplinedSource = sample.source
        detail = sample.detail
        refreshLocked(nowMonotonic)
        true
    }

    /** 仅用于测试或检测系统 wall clock 被用户/网络突然调整。 */
    fun refresh(): ClockSnapshot = synchronized(lock) {
        val nowMono = timeSource.elapsedRealtimeNanos()
        if (disciplinedSource == ClockSource.SYSTEM) {
            val wallResidual = timeSource.wallClockMillis() - utcAtLocked(nowMono)
            if (abs(wallResidual) > WALL_CLOCK_JUMP_MS) {
                anchorMonotonicNanos = nowMono
                anchorUtcMillis = timeSource.wallClockMillis().toDouble()
                acceptedSampleMonotonicNanos = nowMono
                acceptedSampleUtcMillis = anchorUtcMillis
                baseUncertaintyMillis = max(5_000.0, abs(wallResidual))
                detail = "检测到系统时钟跳变，等待重新校准"
            }
        }
        refreshLocked(nowMono)
    }

    fun automaticTransmitAllowed(): Boolean {
        val current = snapshot()
        return current.healthy
            && current.source != ClockSource.SYSTEM
            && current.uncertaintyMillis <= policy.automaticTxUncertaintyMillis
            && current.sampleAgeMillis <= policy.maximumSampleAgeMillis
    }

    fun automaticTransmitBlockReason(): String {
        val current = snapshot()
        return when {
            current.source == ClockSource.SYSTEM -> "尚未取得可信 NTP/GNSS 时间"
            current.sampleAgeMillis > policy.maximumSampleAgeMillis -> "时间样本已过期"
            current.uncertaintyMillis > policy.automaticTxUncertaintyMillis ->
                "时间误差范围 ${current.uncertaintyMillis.toInt()}ms 超过自动发射门限"
            !current.healthy -> "应用 UTC 时钟状态不健康"
            else -> ""
        }
    }

    private fun refreshLocked(nowMonotonic: Long): ClockSnapshot {
        val snapshot = snapshotLocked(nowMonotonic)
        mutableState.value = snapshot
        return snapshot
    }

    private fun snapshotLocked(nowMonotonic: Long): ClockSnapshot {
        val ageMillis = max(0L, (nowMonotonic - acceptedSampleMonotonicNanos) / 1_000_000L)
        val driftGrowth = ageMillis * policy.maximumDriftPpm / 1_000_000.0
        val uncertainty = baseUncertaintyMillis + driftGrowth
        val effectiveSource = if (
            disciplinedSource != ClockSource.SYSTEM && ageMillis > policy.holdoverAfterMillis
        ) {
            ClockSource.HOLDOVER
        } else {
            disciplinedSource
        }
        val utc = utcAtLocked(nowMonotonic)
        val wall = timeSource.wallClockMillis()
        val healthy = disciplinedSource != ClockSource.SYSTEM
            && ageMillis <= policy.maximumSampleAgeMillis
            && uncertainty <= policy.healthyUncertaintyMillis
        return ClockSnapshot(
            utcMillis = utc.toLong(),
            monotonicNanos = nowMonotonic,
            offsetMillis = utc - wall,
            driftPpm = driftPpm,
            uncertaintyMillis = uncertainty,
            source = effectiveSource,
            sampleAgeMillis = ageMillis,
            healthy = healthy,
            detail = detail,
        )
    }

    private fun utcAtLocked(monotonicNanos: Long): Double {
        val elapsedMillis = (monotonicNanos - anchorMonotonicNanos) / 1_000_000.0
        return anchorUtcMillis + elapsedMillis * (1.0 + driftPpm / 1_000_000.0)
    }

    private companion object {
        const val MAX_TRUSTED_SAMPLE_RESIDUAL_MS = 2_000.0
        const val MAX_MEASURED_DRIFT_PPM = 500.0
        const val MIN_DRIFT_WINDOW_MS = 60_000.0
        const val MAX_SLEW_STEP_MS = 100.0
        const val SLEW_GAIN = 0.25
        const val WALL_CLOCK_JUMP_MS = 500.0
    }
}

class FakeDisciplinedClock(initial: ClockSnapshot) : DisciplinedClock {
    private val mutableState = MutableStateFlow(initial)

    override val state: StateFlow<ClockSnapshot> = mutableState.asStateFlow()

    fun update(snapshot: ClockSnapshot) {
        mutableState.value = snapshot
    }
}
