package com.bg7yoz.ft8cn.core.radio

import com.bg7yoz.ft8cn.ui.ToastMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * 将旧同步发射回调接入统一 radio transaction。
 * 方法只有在 CAT/PTT 已执行并读回后才返回成功，不再把“命令入队”当作成功。
 */
class RadioTransmitBridge(
    private val controller: RadioController,
    private val coordinator: RadioTransactionCoordinator,
    private val scope: CoroutineScope,
) : Closeable {
    private val leaseLock = Any()
    private var activeLease: TransmitLease? = null

    fun beginTransmit(
        plan: FrequencyPlan,
        watchdogMillis: Long,
        txDelayMillis: Long,
    ): Boolean = synchronized(leaseLock) {
        if (activeLease != null || !controller.state.value.connected) return@synchronized false
        coordinator.arm()
        val result = runBlocking(Dispatchers.IO) {
            coordinator.beginTransmit(plan, watchdogMillis, txDelayMillis)
        }
        result.onSuccess { activeLease = it }.onFailure {
            ToastMessage.show("Hamlib 发射准备失败：${it.message}")
        }
        result.isSuccess
    }

    /** 等待 PTT lead time，并在音频启动前再次确认 PTT 与 generation。 */
    fun awaitAudioReady(): Boolean {
        val lease = synchronized(leaseLock) { activeLease } ?: return false
        val elapsedNanos = System.nanoTime() - lease.pttConfirmedAtNanos
        val remainingNanos = TimeUnit.MILLISECONDS.toNanos(lease.txDelayMillis) - elapsedNanos
        if (remainingNanos > 0L) {
            try {
                TimeUnit.NANOSECONDS.sleep(remainingNanos)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                abortTransmit("等待 PTT lead time 时取消")
                return false
            }
        }
        val result = runBlocking(Dispatchers.IO) { coordinator.markAudioActive(lease) }
        if (result.isFailure) {
            abortTransmit("音频启动前 PTT 复核失败")
            ToastMessage.show("Hamlib PTT 复核失败：${result.exceptionOrNull()?.message}")
        }
        return result.isSuccess
    }

    fun finishTransmit(reason: String = "发射完成"): Boolean {
        val lease = synchronized(leaseLock) { activeLease } ?: return false
        val result = runBlocking(Dispatchers.IO) { coordinator.endTransmit(lease, reason) }
        synchronized(leaseLock) {
            if (activeLease?.id == lease.id) activeLease = null
        }
        result.onFailure { ToastMessage.show("Hamlib PTT 撤销失败：${it.message}") }
        return result.isSuccess
    }

    fun abortTransmit(reason: String): Boolean {
        val result = runBlocking(Dispatchers.IO) { coordinator.stopAll(reason) }
        synchronized(leaseLock) { activeLease = null }
        result.onFailure { ToastMessage.show("Hamlib 紧急停止失败：${it.message}") }
        return result.isSuccess
    }

    /** 旧调用兼容入口；新发射代码应使用 begin/awaitAudioReady/finish 三段式事务。 */
    fun requestPtt(enabled: Boolean): Boolean {
        if (!enabled) return finishTransmit()
        val state = controller.state.value
        if (!state.connected || state.rxFrequencyHz <= 0L) return false
        return beginTransmit(
            FrequencyPlan(
                rxDialFrequencyHz = state.rxFrequencyHz,
                txRfFrequencyHz = state.rxFrequencyHz,
                strategy = SplitStrategy.NONE,
                requestedAudioOffsetHz = 0,
            ),
            watchdogMillis = DEFAULT_WATCHDOG_MILLIS,
            txDelayMillis = 0L,
        )
    }

    /** 非发射调频仍在有界应用作用域执行，失败会给出可见错误。 */
    fun requestFrequency(rxFrequencyHz: Long, txFrequencyHz: Long = rxFrequencyHz): Boolean {
        if (!controller.state.value.connected || rxFrequencyHz <= 0L || txFrequencyHz <= 0L) return false
        scope.launch(Dispatchers.IO) {
            controller.setFrequency(rxFrequencyHz, txFrequencyHz).onFailure {
                ToastMessage.show("Hamlib 调频失败：${it.message}")
            }
        }
        return true
    }

    fun emergencyStop(): Boolean = abortTransmit("紧急停止")

    override fun close() {
        abortTransmit("发射桥关闭")
    }

    private companion object {
        const val DEFAULT_WATCHDOG_MILLIS = 30_000L
    }
}
