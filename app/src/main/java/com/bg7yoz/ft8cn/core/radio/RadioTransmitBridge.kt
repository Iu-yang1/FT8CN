package com.bg7yoz.ft8cn.core.radio

import com.bg7yoz.ft8cn.ui.ToastMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 将旧发射器的同步回调串行转发到统一 Hamlib 控制器。
 * 音频线程只投递命令，不直接执行 CAT I/O；watchdog 覆盖最长 300 秒 Q65 周期。
 */
class RadioTransmitBridge(
    private val controller: RadioController,
    private val scope: CoroutineScope,
) {
    private val commands = Channel<Boolean>(Channel.UNLIMITED)
    private var watchdog: Job? = null
    @Volatile private var requestedPtt = false
    @Volatile private var ownsPtt = false

    init {
        scope.launch {
            for (enabled in commands) {
                val result = controller.setPtt(enabled)
                if (result.isSuccess) {
                    ownsPtt = enabled
                } else if (!enabled) {
                    ownsPtt = false
                }
                if (result.isFailure) {
                    controller.emergencyStop()
                    ToastMessage.show("Hamlib PTT 失败：${result.exceptionOrNull()?.message}")
                }
                updateWatchdog(enabled && result.isSuccess)
            }
        }
    }

    /** 返回 false 表示 Hamlib 未接管本次操作，调用方可以进入旧设备兼容回退。 */
    fun requestPtt(enabled: Boolean): Boolean {
        if (enabled) {
            if (!controller.state.value.connected) return false
            val accepted = commands.trySend(true).isSuccess
            if (accepted) requestedPtt = true
            return accepted
        }

        // 即使 CAT 已断开，也必须由同一桥尝试撤销先前接管的 PTT，不能落入另一套协议。
        val hadOwnership = requestedPtt || ownsPtt
        requestedPtt = false
        if (!hadOwnership && !controller.state.value.connected) return false
        return commands.trySend(false).isSuccess
    }

    /** 浮动频率表和旧操作页统一转发到当前 Hamlib 后端，未连接时由旧电台链路回退。 */
    fun requestFrequency(rxFrequencyHz: Long, txFrequencyHz: Long = rxFrequencyHz): Boolean {
        if (!controller.state.value.connected || rxFrequencyHz <= 0L || txFrequencyHz <= 0L) return false
        scope.launch {
            controller.setFrequency(rxFrequencyHz, txFrequencyHz).onFailure {
                ToastMessage.show("Hamlib 调频失败：${it.message}")
            }
        }
        return true
    }

    fun emergencyStop() {
        requestedPtt = false
        ownsPtt = false
        watchdog?.cancel()
        watchdog = null
        scope.launch { controller.emergencyStop() }
    }

    private fun updateWatchdog(enabled: Boolean) {
        watchdog?.cancel()
        watchdog = null
        if (!enabled) return
        watchdog = scope.launch {
            delay(MAXIMUM_PTT_MILLIS)
            controller.emergencyStop()
            ToastMessage.show("PTT watchdog 已强制停止超时发射")
        }
    }

    private companion object {
        const val MAXIMUM_PTT_MILLIS = 310_000L
    }
}
