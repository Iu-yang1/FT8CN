package com.bg7yoz.ft8cn.core.radio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class FrequencyPlan(
    val rxDialFrequencyHz: Long,
    val txRfFrequencyHz: Long,
    val strategy: SplitStrategy,
    val requestedAudioOffsetHz: Int,
    val cleanAudioMinimumHz: Int = 1_000,
    val cleanAudioMaximumHz: Int = 2_000,
)

data class ResolvedFrequencyPlan(
    val rxDialFrequencyHz: Long,
    val txDialFrequencyHz: Long,
    val txRfFrequencyHz: Long,
    val txAudioOffsetHz: Int,
    val strategy: SplitStrategy,
)

data class TransmitLease(
    val id: Long,
    val txAudioOffsetHz: Int,
    val strategy: SplitStrategy,
)

data class RadioSafetyState(
    val armed: Boolean = false,
    val transmitting: Boolean = false,
    val activeLeaseId: Long? = null,
    val lastStopReason: String = "",
)

/** PTT、split 和 Fake It 的事务边界，任何异常都先撤销 PTT，再恢复频率。 */
class RadioTransactionCoordinator(
    private val controller: RadioController,
    private val maximumTransmitMillis: Long = 30_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {
    private val mutex = Mutex()
    private val leaseIds = AtomicLong(0L)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ft8cn-ptt-watchdog").apply { isDaemon = true }
    }
    private val mutableSafetyState = MutableStateFlow(RadioSafetyState())
    private var activeSession: ActiveSession? = null
    private var watchdogFuture: ScheduledFuture<*>? = null
    val safetyState: StateFlow<RadioSafetyState> = mutableSafetyState.asStateFlow()

    fun arm() {
        mutableSafetyState.value = mutableSafetyState.value.copy(armed = true, lastStopReason = "")
    }

    suspend fun disarm(reason: String = "用户停止") {
        stopAll(reason)
        mutableSafetyState.value = mutableSafetyState.value.copy(armed = false)
    }

    suspend fun beginTransmit(plan: FrequencyPlan): Result<TransmitLease> = mutex.withLock {
        runCatching {
            check(mutableSafetyState.value.armed) { "电台尚未 armed" }
            check(activeSession == null) { "已有发射事务进行中" }
            val before = controller.refreshState().getOrThrow()
            check(before.connected) { "电台未连接" }
            check(!before.transmitting) { "电台已经处于 PTT 状态" }
            val resolved = resolveFrequencyPlan(plan)
            try {
                when (resolved.strategy) {
                    SplitStrategy.NONE -> controller.setFrequency(
                        resolved.rxDialFrequencyHz,
                        resolved.rxDialFrequencyHz,
                    ).getOrThrow()
                    SplitStrategy.RIG_SPLIT -> controller.setFrequency(
                        resolved.rxDialFrequencyHz,
                        resolved.txRfFrequencyHz,
                    ).getOrThrow()
                    SplitStrategy.FAKE_IT -> controller.setFrequency(
                        resolved.txDialFrequencyHz,
                        resolved.txDialFrequencyHz,
                    ).getOrThrow()
                }
                controller.setPtt(true).getOrThrow()
            } catch (failure: Exception) {
                rollback(before)
                throw failure
            }

            val lease = TransmitLease(
                id = leaseIds.incrementAndGet(),
                txAudioOffsetHz = resolved.txAudioOffsetHz,
                strategy = resolved.strategy,
            )
            activeSession = ActiveSession(lease, before)
            mutableSafetyState.value = mutableSafetyState.value.copy(
                transmitting = true,
                activeLeaseId = lease.id,
                lastStopReason = "",
            )
            scheduleWatchdog(lease)
            lease
        }
    }

    suspend fun endTransmit(lease: TransmitLease, reason: String = "发射完成"): Result<Unit> = mutex.withLock {
        val session = activeSession
            ?: return@withLock Result.failure(IllegalStateException("没有活动发射事务"))
        if (session.lease.id != lease.id) {
            return@withLock Result.failure(IllegalArgumentException("发射 lease 已过期"))
        }
        watchdogFuture?.cancel(false)
        watchdogFuture = null
        val result = stopAndRestore(session.before)
        activeSession = null
        mutableSafetyState.value = mutableSafetyState.value.copy(
            transmitting = false,
            activeLeaseId = null,
            lastStopReason = reason,
        )
        result
    }

    suspend fun stopAll(reason: String): Result<Unit> = mutex.withLock {
        watchdogFuture?.cancel(false)
        watchdogFuture = null
        val result = stopAndRestore(activeSession?.before)
        activeSession = null
        mutableSafetyState.value = mutableSafetyState.value.copy(
            transmitting = false,
            activeLeaseId = null,
            lastStopReason = reason,
        )
        result
    }

    override fun close() {
        watchdogFuture?.cancel(true)
        watchdog.shutdownNow()
        // Closeable 不是 suspend API，必须同步等待 PTT 撤销后才能取消作用域。
        runBlocking(Dispatchers.IO) { stopAll("控制器关闭") }
        scope.cancel()
    }

    private fun resolveFrequencyPlan(plan: FrequencyPlan): ResolvedFrequencyPlan {
        require(plan.rxDialFrequencyHz > 0 && plan.txRfFrequencyHz > 0)
        require(plan.cleanAudioMinimumHz in 100..3_000)
        require(plan.cleanAudioMaximumHz in plan.cleanAudioMinimumHz..3_000)
        val audioOffset = when (plan.strategy) {
            SplitStrategy.FAKE_IT -> plan.requestedAudioOffsetHz.coerceIn(
                plan.cleanAudioMinimumHz,
                plan.cleanAudioMaximumHz,
            )
            else -> plan.requestedAudioOffsetHz
        }
        require(audioOffset in 0..3_000) { "TX audio offset must remain in 0..3000 Hz" }
        val txDial = when (plan.strategy) {
            SplitStrategy.FAKE_IT -> plan.txRfFrequencyHz - audioOffset
            SplitStrategy.RIG_SPLIT -> plan.txRfFrequencyHz
            SplitStrategy.NONE -> plan.rxDialFrequencyHz
        }
        require(txDial > 0)
        return ResolvedFrequencyPlan(
            rxDialFrequencyHz = plan.rxDialFrequencyHz,
            txDialFrequencyHz = txDial,
            txRfFrequencyHz = plan.txRfFrequencyHz,
            txAudioOffsetHz = audioOffset,
            strategy = plan.strategy,
        )
    }

    private suspend fun rollback(before: RadioState) {
        controller.emergencyStop()
        restoreState(before)
    }

    private suspend fun stopAndRestore(before: RadioState?): Result<Unit> = runCatching {
        controller.emergencyStop().getOrThrow()
        if (before != null) restoreState(before).getOrThrow()
    }

    private suspend fun restoreState(before: RadioState): Result<Unit> {
        if (before.rxFrequencyHz <= 0 || before.txFrequencyHz <= 0) return Result.success(Unit)
        return controller.setFrequency(before.rxFrequencyHz, before.txFrequencyHz)
    }

    private fun scheduleWatchdog(lease: TransmitLease) {
        watchdogFuture?.cancel(false)
        watchdogFuture = watchdog.schedule(
            {
                scope.launch { endTransmit(lease, "PTT watchdog 超时") }
            },
            maximumTransmitMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private data class ActiveSession(
        val lease: TransmitLease,
        val before: RadioState,
    )
}
