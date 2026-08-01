package com.bg7yoz.ft8cn.eme

import com.bg7yoz.ft8cn.core.radio.FrequencyUpdateLimiter
import com.bg7yoz.ft8cn.core.radio.RadioTransactionCoordinator
import com.bg7yoz.ft8cn.core.radio.RadioState
import com.bg7yoz.ft8cn.core.radio.TimedFrequencyTarget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.roundToLong

data class EmeFrequencyTarget(
    val generatedUtcMillis: Long,
    val rxFrequencyHz: Long,
    val txFrequencyHz: Long,
)

data class EmeRadioTrackingPolicy(
    val maximumCorrectionHz: Double = 5_000.0,
    val minimumElevationDegrees: Double = 0.0,
    val updateIntervalMillis: Long = 10_000L,
    val allowWhileTransmitting: Boolean = false,
    val restoreFrequencyOnStop: Boolean = false,
) {
    init {
        require(maximumCorrectionHz in 0.0..1_000_000.0)
        require(minimumElevationDegrees in -10.0..90.0)
        require(updateIntervalMillis in 1_000L..60_000L)
    }
}

/** 月面 Doppler 只调整频率，不控制 PTT；失败始终回滚，正常停止是否恢复由策略控制。 */
class EmeRadioTracker(
    private val coordinator: RadioTransactionCoordinator,
    private val limiter: FrequencyUpdateLimiter = FrequencyUpdateLimiter(minimumStepHz = 1L),
    private val readbackToleranceHz: Long = 10L,
) {
    private val mutex = Mutex()
    private var initialState: RadioState? = null
    private var activePolicy: EmeRadioTrackingPolicy? = null
    private var lastAppliedAtUtcMillis = Long.MIN_VALUE

    suspend fun start(policy: EmeRadioTrackingPolicy = EmeRadioTrackingPolicy()): Result<Unit> = mutex.withLock {
        runCatching {
            check(initialState == null) { "EME 跟踪已经启动" }
            val state = coordinator.snapshotIdleState().getOrThrow()
            check(state.connected) { "电台未连接" }
            check(policy.allowWhileTransmitting || !state.transmitting) { "PTT 期间禁止启动 EME 跟踪" }
            check(state.rxFrequencyHz > 0 && state.txFrequencyHz > 0) { "电台读回频率无效" }
            initialState = state
            activePolicy = policy
            lastAppliedAtUtcMillis = Long.MIN_VALUE
            limiter.reset()
        }
    }

    @Suppress("LongParameterList")
    suspend fun apply(
        target: EmeFrequencyTarget,
        nowUtcMillis: Long,
        receiveCorrectionHz: Double = 0.0,
        transmitCorrectionHz: Double = 0.0,
        elevationDegrees: Double = 90.0,
    ): Result<Boolean> = mutex.withLock {
        try {
            check(initialState != null) { "EME 跟踪尚未启动" }
            val policy = checkNotNull(activePolicy) { "EME 跟踪策略不可用" }
            check(elevationDegrees >= policy.minimumElevationDegrees) { "月面低于最低跟踪仰角" }
            check(abs(receiveCorrectionHz) <= policy.maximumCorrectionHz &&
                abs(transmitCorrectionHz) <= policy.maximumCorrectionHz
            ) { "Doppler 修正超过安全上限" }
            check(policy.allowWhileTransmitting || !coordinator.radioState.value.transmitting) {
                "PTT 期间暂停 EME 调频"
            }
            if (lastAppliedAtUtcMillis != Long.MIN_VALUE &&
                nowUtcMillis - lastAppliedAtUtcMillis < policy.updateIntervalMillis
            ) {
                return@withLock Result.success(false)
            }
            val timed = TimedFrequencyTarget(target.generatedUtcMillis, target.rxFrequencyHz, target.txFrequencyHz)
            if (!limiter.shouldApply(timed, nowUtcMillis)) return@withLock Result.success(false)
            val readback = coordinator.setIdleFrequency(target.rxFrequencyHz, target.txFrequencyHz).getOrThrow()
            check(abs(readback.rxFrequencyHz - target.rxFrequencyHz) <= readbackToleranceHz) {
                "EME RX 频率读回不一致"
            }
            check(abs(readback.txFrequencyHz - target.txFrequencyHz) <= readbackToleranceHz) {
                "EME TX 频率读回不一致"
            }
            limiter.markApplied(timed, nowUtcMillis)
            lastAppliedAtUtcMillis = nowUtcMillis
            Result.success(true)
        } catch (failure: Exception) {
            try {
                restoreInitial()
            } catch (restoreFailure: Exception) {
                failure.addSuppressed(restoreFailure)
            }
            initialState = null
            activePolicy = null
            lastAppliedAtUtcMillis = Long.MIN_VALUE
            limiter.reset()
            Result.failure(failure)
        }
    }

    suspend fun stop(): Result<Unit> = mutex.withLock {
        runCatching {
            if (activePolicy?.restoreFrequencyOnStop == true) {
                restoreInitial()
            }
            initialState = null
            activePolicy = null
            lastAppliedAtUtcMillis = Long.MIN_VALUE
            limiter.reset()
        }
    }

    private suspend fun restoreInitial() {
        val state = initialState ?: return
        coordinator.setIdleFrequency(state.rxFrequencyHz, state.txFrequencyHz).getOrThrow()
    }

    companion object {
        fun target(
            utcMillis: Long,
            baseFrequencyHz: Long,
            plan: EmeDopplerCalculator.CorrectionPlan,
        ): EmeFrequencyTarget {
            require(baseFrequencyHz > 0L)
            return EmeFrequencyTarget(
                generatedUtcMillis = utcMillis,
                rxFrequencyHz = (baseFrequencyHz + plan.receiveCorrectionHz).roundToLong(),
                txFrequencyHz = (baseFrequencyHz + plan.transmitCorrectionHz).roundToLong(),
            )
        }
    }
}
