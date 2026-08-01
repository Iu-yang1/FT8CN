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
import kotlin.math.abs

data class FrequencyPlan(
    val rxDialFrequencyHz: Long,
    val txRfFrequencyHz: Long,
    val strategy: SplitStrategy,
    val requestedAudioOffsetHz: Int,
    val cleanAudioMinimumHz: Int = 1_000,
    val cleanAudioMaximumHz: Int = 2_000,
    val mode: RadioMode = RadioMode.DATA_USB,
    val passbandHz: Int = 3_000,
    val txVfo: RadioVfo = RadioVfo.B,
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
    val generation: Long,
    val txAudioOffsetHz: Int,
    val strategy: SplitStrategy,
    val pttConfirmedAtNanos: Long,
    val txDelayMillis: Long,
)

enum class TransmitPhase {
    IDLE,
    ARMING,
    RADIO_CONFIGURED,
    PTT_CONFIRMED,
    AUDIO_ACTIVE,
    STOPPING,
    FAILED,
}

data class RadioSafetyState(
    val armed: Boolean = false,
    val transmitting: Boolean = false,
    val activeLeaseId: Long? = null,
    val generation: Long = 0L,
    val phase: TransmitPhase = TransmitPhase.IDLE,
    val lastStopReason: String = "",
)

/**
 * 统一管理调频、split/Fake It、PTT 读回、watchdog 与完整回滚。
 * 所有操作都在同一 Mutex 内串行，调用方只有拿到 lease 后才能启动音频。
 */
class RadioTransactionCoordinator(
    private val controller: RadioController,
    private val maximumTransmitMillis: Long = 30_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {
    private val mutex = Mutex()
    private val leaseIds = AtomicLong(0L)
    private val generations = AtomicLong(0L)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ft8cn-ptt-watchdog").apply { isDaemon = true }
    }
    private val mutableSafetyState = MutableStateFlow(RadioSafetyState())
    private var activeSession: ActiveSession? = null
    private var watchdogFuture: ScheduledFuture<*>? = null
    val safetyState: StateFlow<RadioSafetyState> = mutableSafetyState.asStateFlow()
    val radioState: StateFlow<RadioState> = controller.state

    fun arm() {
        mutableSafetyState.value = mutableSafetyState.value.copy(armed = true, lastStopReason = "")
    }

    /** 在事务锁内取得稳定读回，供 EME、卫星和手动调频建立快照。 */
    suspend fun snapshotIdleState(): Result<RadioState> = mutex.withLock {
        runCatching {
            check(activeSession == null && !mutableSafetyState.value.transmitting) { "发射期间禁止建立调频快照" }
            controller.refreshState().getOrThrow().also { state ->
                check(state.connected) { "电台未连接" }
                check(!state.transmitting) { "PTT 期间禁止调频" }
            }
        }
    }

    /**
     * 串行执行非发射调频并校验读回；任何部分失败都会恢复事务前状态。
     * 该入口与 PTT/Fake It 共用 mutex，避免卫星或 EME 在发射中改频。
     */
    suspend fun setIdleFrequency(rxFrequencyHz: Long, txFrequencyHz: Long = rxFrequencyHz): Result<RadioState> =
        mutex.withLock {
            var before: RadioState? = null
            try {
                require(rxFrequencyHz > 0L && txFrequencyHz > 0L) { "频率必须大于 0" }
                check(activeSession == null && !mutableSafetyState.value.transmitting) { "发射期间禁止调频" }
                before = controller.refreshState().getOrThrow()
                check(before.connected) { "电台未连接" }
                check(!before.transmitting) { "PTT 期间禁止调频" }
                controller.setFrequency(rxFrequencyHz, txFrequencyHz).getOrThrow()
                val readback = controller.refreshState().getOrThrow()
                check(abs(readback.rxFrequencyHz - rxFrequencyHz) <= FREQUENCY_READBACK_TOLERANCE_HZ) {
                    "RX 频率读回不一致"
                }
                check(abs(readback.txFrequencyHz - txFrequencyHz) <= FREQUENCY_READBACK_TOLERANCE_HZ) {
                    "TX 频率读回不一致"
                }
                Result.success(readback)
            } catch (failure: Throwable) {
                before?.let { state ->
                    restoreState(state).exceptionOrNull()?.let(failure::addSuppressed)
                }
                Result.failure(failure)
            }
        }

    suspend fun disarm(reason: String = "用户停止") {
        stopAll(reason)
        mutableSafetyState.value = mutableSafetyState.value.copy(armed = false)
    }

    suspend fun beginTransmit(
        plan: FrequencyPlan,
        watchdogMillis: Long = maximumTransmitMillis,
        txDelayMillis: Long = 0L,
    ): Result<TransmitLease> = mutex.withLock {
        runCatching {
            check(mutableSafetyState.value.armed) { "电台尚未进入 armed 状态" }
            check(activeSession == null) { "已有发射事务正在进行" }
            require(watchdogMillis in 1L..MAXIMUM_WATCHDOG_MILLIS) { "PTT watchdog 时长无效" }
            require(txDelayMillis in 0L..MAXIMUM_TX_DELAY_MILLIS) { "发射延迟无效" }

            val generation = generations.incrementAndGet()
            updatePhase(TransmitPhase.ARMING, generation = generation)
            val before = controller.refreshState().getOrThrow()
            check(before.connected) { "电台未连接" }
            check(!before.transmitting) { "电台已处于 PTT 状态" }
            val resolved = resolveFrequencyPlan(plan)

            try {
                configureRadio(plan, resolved, before)
                updatePhase(TransmitPhase.RADIO_CONFIGURED, generation = generation)
                controller.setPtt(true).getOrThrow()
                val pttReadback = controller.refreshState().getOrThrow()
                check(pttReadback.transmitting) { "PTT ON 读回未确认" }
            } catch (failure: Throwable) {
                rollback(before)
                updatePhase(TransmitPhase.FAILED, generation = generation, reason = failure.message.orEmpty())
                throw failure
            }

            val lease = TransmitLease(
                id = leaseIds.incrementAndGet(),
                generation = generation,
                txAudioOffsetHz = resolved.txAudioOffsetHz,
                strategy = resolved.strategy,
                pttConfirmedAtNanos = System.nanoTime(),
                txDelayMillis = txDelayMillis,
            )
            activeSession = ActiveSession(lease, before)
            mutableSafetyState.value = mutableSafetyState.value.copy(
                transmitting = true,
                activeLeaseId = lease.id,
                generation = generation,
                phase = TransmitPhase.PTT_CONFIRMED,
                lastStopReason = "",
            )
            scheduleWatchdog(lease, watchdogMillis)
            lease
        }
    }

    suspend fun markAudioActive(lease: TransmitLease): Result<Unit> = mutex.withLock {
        runCatching {
            val session = requireActiveSession(lease)
            val readback = controller.refreshState().getOrThrow()
            check(readback.transmitting) { "音频启动前 PTT 已释放" }
            check(session.lease.generation == mutableSafetyState.value.generation) { "发射 generation 已失效" }
            mutableSafetyState.value = mutableSafetyState.value.copy(phase = TransmitPhase.AUDIO_ACTIVE)
        }
    }

    suspend fun endTransmit(lease: TransmitLease, reason: String = "发射完成"): Result<Unit> = mutex.withLock {
        val session = runCatching { requireActiveSession(lease) }.getOrElse {
            return@withLock Result.failure(it)
        }
        stopSession(session, reason)
    }

    suspend fun stopAll(reason: String): Result<Unit> = mutex.withLock {
        val session = activeSession
        if (session == null) {
            watchdogFuture?.cancel(false)
            watchdogFuture = null
            val stopResult = controller.emergencyStop()
            mutableSafetyState.value = mutableSafetyState.value.copy(
                transmitting = false,
                activeLeaseId = null,
                phase = if (stopResult.isSuccess) TransmitPhase.IDLE else TransmitPhase.FAILED,
                lastStopReason = reason,
            )
            return@withLock stopResult
        }
        stopSession(session, reason)
    }

    override fun close() {
        runBlocking(Dispatchers.IO) { stopAll("控制器关闭") }
        watchdogFuture?.cancel(true)
        watchdog.shutdownNow()
        scope.cancel()
    }

    internal fun resolveFrequencyPlan(plan: FrequencyPlan): ResolvedFrequencyPlan {
        require(plan.rxDialFrequencyHz > 0L && plan.txRfFrequencyHz > 0L) { "频率必须大于 0" }
        require(plan.cleanAudioMinimumHz in 100..3_000)
        require(plan.cleanAudioMaximumHz in plan.cleanAudioMinimumHz..3_000)
        require(plan.passbandHz in 100..100_000) { "通带无效" }
        val audioOffset = when (plan.strategy) {
            SplitStrategy.FAKE_IT -> plan.requestedAudioOffsetHz.coerceIn(
                plan.cleanAudioMinimumHz,
                plan.cleanAudioMaximumHz,
            )
            else -> plan.requestedAudioOffsetHz
        }
        require(audioOffset in 0..3_000) { "TX 音频偏移必须位于 0..3000 Hz" }
        val txDial = when (plan.strategy) {
            SplitStrategy.NONE -> plan.rxDialFrequencyHz
            SplitStrategy.RIG_SPLIT,
            SplitStrategy.FAKE_IT,
            -> plan.txRfFrequencyHz - audioOffset
        }
        require(txDial > 0L) { "计算得到的 TX dial 无效" }
        return ResolvedFrequencyPlan(
            rxDialFrequencyHz = plan.rxDialFrequencyHz,
            txDialFrequencyHz = txDial,
            txRfFrequencyHz = plan.txRfFrequencyHz,
            txAudioOffsetHz = audioOffset,
            strategy = plan.strategy,
        )
    }

    private suspend fun configureRadio(
        plan: FrequencyPlan,
        resolved: ResolvedFrequencyPlan,
        before: RadioState,
    ) {
        if (before.capabilities.canSetMode) {
            controller.setMode(plan.mode, plan.passbandHz).getOrThrow()
        }
        when (resolved.strategy) {
            SplitStrategy.NONE -> {
                if (before.capabilities.canSplit) controller.setSplit(false, plan.txVfo).getOrThrow()
                controller.setFrequency(resolved.rxDialFrequencyHz, resolved.rxDialFrequencyHz).getOrThrow()
            }
            SplitStrategy.RIG_SPLIT -> {
                check(before.capabilities.canSplit) { "电台不支持 Rig Split" }
                controller.setFrequency(resolved.rxDialFrequencyHz, resolved.txDialFrequencyHz).getOrThrow()
                controller.setSplit(true, plan.txVfo).getOrThrow()
            }
            SplitStrategy.FAKE_IT -> {
                if (before.capabilities.canSplit) controller.setSplit(false, plan.txVfo).getOrThrow()
                controller.setFrequency(resolved.txDialFrequencyHz, resolved.txDialFrequencyHz).getOrThrow()
            }
        }
        val configured = controller.refreshState().getOrThrow()
        check(abs(configured.rxFrequencyHz - when (resolved.strategy) {
            SplitStrategy.FAKE_IT -> resolved.txDialFrequencyHz
            else -> resolved.rxDialFrequencyHz
        }) <= FREQUENCY_READBACK_TOLERANCE_HZ) { "RX 频率读回不一致" }
        val expectedTxDial = when (resolved.strategy) {
            SplitStrategy.NONE -> resolved.rxDialFrequencyHz
            else -> resolved.txDialFrequencyHz
        }
        check(abs(configured.txFrequencyHz - expectedTxDial) <= FREQUENCY_READBACK_TOLERANCE_HZ) {
            "TX 频率读回不一致"
        }
    }

    private suspend fun stopSession(session: ActiveSession, reason: String): Result<Unit> {
        watchdogFuture?.cancel(false)
        watchdogFuture = null
        updatePhase(TransmitPhase.STOPPING, generation = session.lease.generation, reason = reason)
        val result = stopAndRestore(session.before)
        activeSession = null
        mutableSafetyState.value = mutableSafetyState.value.copy(
            transmitting = false,
            activeLeaseId = null,
            phase = if (result.isSuccess) TransmitPhase.IDLE else TransmitPhase.FAILED,
            lastStopReason = reason,
        )
        return result
    }

    private fun requireActiveSession(lease: TransmitLease): ActiveSession {
        val session = activeSession ?: error("没有活动发射事务")
        require(session.lease.id == lease.id && session.lease.generation == lease.generation) {
            "发射 lease 已过期"
        }
        return session
    }

    private suspend fun rollback(before: RadioState) {
        controller.emergencyStop()
        restoreState(before)
    }

    private suspend fun stopAndRestore(before: RadioState): Result<Unit> = runCatching {
        controller.emergencyStop().getOrThrow()
        restoreState(before).getOrThrow()
    }

    private suspend fun restoreState(before: RadioState): Result<Unit> = runCatching {
        if (!before.connected) return@runCatching
        if (before.capabilities.canSetMode && before.passbandHz > 0) {
            controller.setMode(before.mode, before.passbandHz).getOrThrow()
        }
        if (before.capabilities.canSetVfo) {
            controller.setVfo(before.activeVfo).getOrThrow()
        }
        if (before.rxFrequencyHz > 0L && before.txFrequencyHz > 0L) {
            controller.setFrequency(before.rxFrequencyHz, before.txFrequencyHz).getOrThrow()
        }
        if (before.capabilities.canSplit) {
            controller.setSplit(before.splitEnabled, RadioVfo.B).getOrThrow()
        }
    }

    private fun scheduleWatchdog(lease: TransmitLease, watchdogMillis: Long) {
        watchdogFuture?.cancel(false)
        watchdogFuture = watchdog.schedule(
            { scope.launch { endTransmit(lease, "PTT watchdog 超时") } },
            watchdogMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun updatePhase(phase: TransmitPhase, generation: Long, reason: String = "") {
        mutableSafetyState.value = mutableSafetyState.value.copy(
            generation = generation,
            phase = phase,
            lastStopReason = reason,
        )
    }

    private data class ActiveSession(
        val lease: TransmitLease,
        val before: RadioState,
    )

    private companion object {
        const val MAXIMUM_WATCHDOG_MILLIS = 310_000L
        const val MAXIMUM_TX_DELAY_MILLIS = 5_000L
        const val FREQUENCY_READBACK_TOLERANCE_HZ = 10L
    }
}
