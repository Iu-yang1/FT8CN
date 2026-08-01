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
    val roundTripDelayMillis: Double? = null,
    val consensusMembers: Int = 0,
    val lastRejectedReason: String = "",
)

data class ClockSample(
    val utcMillis: Double,
    val monotonicNanos: Long,
    val uncertaintyMillis: Double,
    val source: ClockSource,
    val detail: String = "",
    val roundTripDelayMillis: Double? = null,
    val consensusMembers: Int = 1,
)

data class ClockHealthPolicy(
    val healthyUncertaintyMillis: Double = 1_000.0,
    val ft4AutomaticTxUncertaintyMillis: Double = 250.0,
    val ft8AutomaticTxUncertaintyMillis: Double = 500.0,
    val q65AutomaticTxUncertaintyMillis: Double = 1_000.0,
    val maximumSampleAgeMillis: Long = 30 * 60 * 1_000L,
    val holdoverAfterMillis: Long = 2 * 60 * 1_000L,
    val maximumDriftPpm: Double = 50.0,
)

enum class AutomaticTransmitMode {
    FT4,
    FT8,
    Q65,
}

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
    private var roundTripDelayMillis: Double? = null
    private var consensusMembers = 0
    private var lastRejectedReason = ""
    private var reacquireResidualMillis: Double? = null
    private var reacquireSampleCount = 0
    private var reacquireFirstMonotonicNanos = Long.MIN_VALUE
    private var lastPublishedUtcMillis = anchorUtcMillis.toLong()

    private val mutableState = MutableStateFlow(snapshotLocked(anchorMonotonicNanos))
    override val state: StateFlow<ClockSnapshot> = mutableState.asStateFlow()

    override fun snapshot(): ClockSnapshot = synchronized(lock) {
        refreshLocked(timeSource.elapsedRealtimeNanos())
    }

    /** 高频时隙计算只读取 UTC，不向 StateFlow 推送 10ms 级状态。 */
    fun nowMillis(): Long = synchronized(lock) {
        monotonicUtcLocked(utcAtLocked(timeSource.elapsedRealtimeNanos()))
    }

    fun submitSample(sample: ClockSample): Boolean = synchronized(lock) {
        require(sample.source == ClockSource.NTP || sample.source == ClockSource.GNSS) {
            "时间纪律样本只能来自 NTP 或 GNSS"
        }
        if (!sample.utcMillis.isFinite() || !sample.uncertaintyMillis.isFinite()
            || sample.uncertaintyMillis < 0.0
        ) {
            return rejectLocked("样本数值无效", timeSource.elapsedRealtimeNanos())
        }

        val nowMonotonic = timeSource.elapsedRealtimeNanos()
        val sampleAgeNanos = nowMonotonic - sample.monotonicNanos
        if (sampleAgeNanos < -100_000_000L || sampleAgeNanos > 60_000_000_000L) {
            return rejectLocked("样本单调时间不在允许窗口内", nowMonotonic)
        }

        val predictedAtSample = utcAtLocked(sample.monotonicNanos)
        val residualMillis = sample.utcMillis - predictedAtSample
        val currentlyTrusted = disciplinedSource != ClockSource.SYSTEM
        var reacquiring = false
        if (currentlyTrusted && abs(residualMillis) > MAX_TRUSTED_SAMPLE_RESIDUAL_MS) {
            reacquiring = recordReacquireCandidateLocked(residualMillis, sample, nowMonotonic)
            if (!reacquiring) {
                return rejectLocked(
                    "可信时钟大残差 ${residualMillis.toInt()}ms，等待多源持续确认",
                    nowMonotonic,
                )
            }
        }

        val previousSampleMono = acceptedSampleMonotonicNanos
        val previousSampleUtc = acceptedSampleUtcMillis
        val elapsedSincePreviousMs = (sample.monotonicNanos - previousSampleMono) / 1_000_000.0
        if (currentlyTrusted && !reacquiring && elapsedSincePreviousMs >= MIN_DRIFT_WINDOW_MS) {
            val utcElapsedMs = sample.utcMillis - previousSampleUtc
            val measuredPpm = ((utcElapsedMs / elapsedSincePreviousMs) - 1.0) * 1_000_000.0
            if (measuredPpm.isFinite() && abs(measuredPpm) <= MAX_MEASURED_DRIFT_PPM) {
                driftPpm = (driftPpm * 0.75 + measuredPpm * 0.25)
                    .coerceIn(-policy.maximumDriftPpm, policy.maximumDriftPpm)
            }
        }

        val correction = if (!currentlyTrusted || reacquiring) {
            residualMillis
        } else {
            residualMillis.coerceIn(-MAX_SLEW_STEP_MS, MAX_SLEW_STEP_MS) * SLEW_GAIN
        }
        val correctedAtSample = predictedAtSample + correction
        anchorMonotonicNanos = sample.monotonicNanos
        anchorUtcMillis = correctedAtSample
        acceptedSampleMonotonicNanos = sample.monotonicNanos
        acceptedSampleUtcMillis = sample.utcMillis
        baseUncertaintyMillis = if (reacquiring) {
            max(REACQUIRE_INITIAL_UNCERTAINTY_MS, sample.uncertaintyMillis)
        } else {
            max(sample.uncertaintyMillis, abs(residualMillis - correction))
        }
        disciplinedSource = sample.source
        detail = sample.detail
        roundTripDelayMillis = sample.roundTripDelayMillis
        consensusMembers = sample.consensusMembers
        lastRejectedReason = ""
        clearReacquireCandidateLocked()
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

    fun automaticTransmitAllowed(mode: AutomaticTransmitMode = AutomaticTransmitMode.FT8): Boolean {
        val current = snapshot()
        return current.healthy
            && current.source != ClockSource.SYSTEM
            && current.uncertaintyMillis <= automaticTxThreshold(mode)
            && current.sampleAgeMillis <= policy.maximumSampleAgeMillis
    }

    fun automaticTransmitBlockReason(mode: AutomaticTransmitMode = AutomaticTransmitMode.FT8): String {
        val current = snapshot()
        val threshold = automaticTxThreshold(mode)
        return when {
            current.source == ClockSource.SYSTEM -> "尚未取得可信 NTP/GNSS 时间"
            current.sampleAgeMillis > policy.maximumSampleAgeMillis -> "时间样本已过期"
            current.uncertaintyMillis > threshold ->
                "${mode.name} 时间误差范围 ${current.uncertaintyMillis.toInt()}ms 超过 ${threshold.toInt()}ms 自动发射门限"
            !current.healthy -> "应用 UTC 时钟状态不健康"
            else -> ""
        }
    }

    private fun automaticTxThreshold(mode: AutomaticTransmitMode): Double = when (mode) {
        AutomaticTransmitMode.FT4 -> policy.ft4AutomaticTxUncertaintyMillis
        AutomaticTransmitMode.FT8 -> policy.ft8AutomaticTxUncertaintyMillis
        AutomaticTransmitMode.Q65 -> policy.q65AutomaticTxUncertaintyMillis
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
        val rawUtc = utcAtLocked(nowMonotonic)
        val utc = monotonicUtcLocked(rawUtc)
        val monotonicClampDebt = max(0.0, utc - rawUtc)
        val wall = timeSource.wallClockMillis()
        val effectiveUncertainty = uncertainty + monotonicClampDebt
        val healthy = disciplinedSource != ClockSource.SYSTEM
            && ageMillis <= policy.maximumSampleAgeMillis
            && effectiveUncertainty <= policy.healthyUncertaintyMillis
        return ClockSnapshot(
            utcMillis = utc,
            monotonicNanos = nowMonotonic,
            offsetMillis = (utc - wall).toDouble(),
            driftPpm = driftPpm,
            uncertaintyMillis = effectiveUncertainty,
            source = effectiveSource,
            sampleAgeMillis = ageMillis,
            healthy = healthy,
            detail = detail,
            roundTripDelayMillis = roundTripDelayMillis,
            consensusMembers = consensusMembers,
            lastRejectedReason = lastRejectedReason,
        )
    }

    private fun rejectLocked(reason: String, nowMonotonic: Long): Boolean {
        lastRejectedReason = reason
        refreshLocked(nowMonotonic)
        return false
    }

    /** 大偏差必须连续三次方向和幅度一致，避免单个异常样本重置时钟。 */
    private fun recordReacquireCandidateLocked(
        residualMillis: Double,
        sample: ClockSample,
        nowMonotonic: Long,
    ): Boolean {
        val previous = reacquireResidualMillis
        val expired = reacquireFirstMonotonicNanos == Long.MIN_VALUE ||
            nowMonotonic - reacquireFirstMonotonicNanos > REACQUIRE_WINDOW_NANOS
        val agrees = previous != null &&
            residualMillis * previous > 0.0 &&
            abs(residualMillis - previous) <= REACQUIRE_AGREEMENT_MS
        val trustedConsensus = sample.source == ClockSource.GNSS ||
            sample.consensusMembers >= MIN_REACQUIRE_CONSENSUS_MEMBERS
        if (expired || !agrees || !trustedConsensus) {
            reacquireResidualMillis = residualMillis
            reacquireSampleCount = 1
            reacquireFirstMonotonicNanos = nowMonotonic
            return false
        }
        reacquireResidualMillis = ((previous ?: residualMillis) + residualMillis) / 2.0
        reacquireSampleCount += 1
        return reacquireSampleCount >= REACQUIRE_REQUIRED_SAMPLES
    }

    private fun clearReacquireCandidateLocked() {
        reacquireResidualMillis = null
        reacquireSampleCount = 0
        reacquireFirstMonotonicNanos = Long.MIN_VALUE
    }

    /** UTC 不回退；向后的大校准会转化为不确定度债务，期间自动发射保持阻断。 */
    private fun monotonicUtcLocked(rawUtcMillis: Double): Long {
        val candidate = rawUtcMillis.toLong()
        if (candidate > lastPublishedUtcMillis) {
            lastPublishedUtcMillis = candidate
        }
        return lastPublishedUtcMillis
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
        const val REACQUIRE_REQUIRED_SAMPLES = 3
        const val MIN_REACQUIRE_CONSENSUS_MEMBERS = 2
        const val REACQUIRE_AGREEMENT_MS = 250.0
        const val REACQUIRE_WINDOW_NANOS = 60_000_000_000L
        const val REACQUIRE_INITIAL_UNCERTAINTY_MS = 2_000.0
    }
}

class FakeDisciplinedClock(initial: ClockSnapshot) : DisciplinedClock {
    private val mutableState = MutableStateFlow(initial)

    override val state: StateFlow<ClockSnapshot> = mutableState.asStateFlow()

    fun update(snapshot: ClockSnapshot) {
        mutableState.value = snapshot
    }
}
