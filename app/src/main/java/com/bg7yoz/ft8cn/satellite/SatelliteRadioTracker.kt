package com.bg7yoz.ft8cn.satellite

import com.bg7yoz.ft8cn.core.radio.FrequencyUpdateLimiter
import com.bg7yoz.ft8cn.core.radio.RadioController
import com.bg7yoz.ft8cn.core.radio.RadioState
import com.bg7yoz.ft8cn.core.radio.TimedFrequencyTarget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * 卫星 CAT 跟踪事务。它只更新频率，不会 armed 或触发 PTT；失败时恢复进入跟踪前的频率。
 */
class SatelliteRadioTracker(
    private val controller: RadioController,
    private val limiter: FrequencyUpdateLimiter = FrequencyUpdateLimiter(),
    private val readbackToleranceHz: Long = 10L,
) {
    private val mutex = Mutex()
    private var initialState: RadioState? = null
    var lastStopReason: String = ""
        private set

    suspend fun start(): Result<Unit> = mutex.withLock {
        runCatching {
            check(initialState == null) { "卫星跟踪已经启动" }
            val state = controller.refreshState().getOrThrow()
            check(state.connected) { "电台未连接" }
            check(!state.transmitting) { "PTT 期间禁止启动卫星跟踪" }
            check(state.rxFrequencyHz > 0 && state.txFrequencyHz > 0) { "电台读回频率无效" }
            initialState = state
            lastStopReason = ""
            limiter.reset()
        }
    }

    suspend fun apply(target: SatelliteFrequencyTarget, nowUtcMillis: Long): Result<Boolean> = mutex.withLock {
        runCatching {
            check(initialState != null) { "卫星跟踪尚未启动" }
            val timed = TimedFrequencyTarget(
                target.generatedUtcMillis,
                target.rxFrequencyHz,
                target.txFrequencyHz,
            )
            if (!limiter.shouldApply(timed, nowUtcMillis)) return@runCatching false
            try {
                controller.setFrequency(target.rxFrequencyHz, target.txFrequencyHz).getOrThrow()
                val readback = controller.refreshState().getOrThrow()
                check(abs(readback.rxFrequencyHz - target.rxFrequencyHz) <= readbackToleranceHz)
                check(abs(readback.txFrequencyHz - target.txFrequencyHz) <= readbackToleranceHz)
                limiter.markApplied(timed, nowUtcMillis)
                true
            } catch (failure: Exception) {
                restoreInitial()
                initialState = null
                throw failure
            }
        }
    }

    suspend fun stop(reason: String = "卫星跟踪停止"): Result<Unit> = mutex.withLock {
        runCatching {
            restoreInitial()
            initialState = null
            limiter.reset()
            lastStopReason = reason.take(120)
        }.map { Unit }
    }

    private suspend fun restoreInitial() {
        val state = initialState ?: return
        controller.setFrequency(state.rxFrequencyHz, state.txFrequencyHz).getOrThrow()
    }
}
