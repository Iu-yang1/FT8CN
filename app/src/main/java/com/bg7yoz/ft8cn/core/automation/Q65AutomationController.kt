package com.bg7yoz.ft8cn.core.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Q65AutomationPhase {
    IDLE,
    RECEIVE_ONLY,
    ARMED,
    TRANSMITTING,
    COMPLETE,
    STOPPED,
    ERROR,
}

data class Q65AutomationState(
    val phase: Q65AutomationPhase = Q65AutomationPhase.IDLE,
    val armed: Boolean = false,
    val repeat: Boolean = false,
    val message: String = "",
    val txSequence: Int = 0,
    val submode: Int = 0,
    val trPeriodSeconds: Int = 60,
    val lastClaimedSlot: Long = Long.MIN_VALUE,
    val transmitCount: Int = 0,
    val reason: String? = null,
)

/**
 * Q65 只负责固定 T/R 序列，不参与 FT8/FT4 的自动应答、CQ 队列和定向 CQ 决策。
 * 实际 PTT 与音频仍由既有串行发射链执行，本状态机仅对每个 Q65 时隙发放一次许可。
 */
class Q65AutomationController {
    private val mutableState = MutableStateFlow(Q65AutomationState())

    val state: StateFlow<Q65AutomationState> = mutableState.asStateFlow()

    @Synchronized
    fun receiveOnly(submode: Int, trPeriodSeconds: Int) {
        validateConfiguration(submode, trPeriodSeconds)
        mutableState.value = Q65AutomationState(
            phase = Q65AutomationPhase.RECEIVE_ONLY,
            submode = submode,
            trPeriodSeconds = trPeriodSeconds,
            reason = "仅接收",
        )
    }

    @Synchronized
    fun arm(
        message: String,
        txSequence: Int,
        repeat: Boolean,
        submode: Int,
        trPeriodSeconds: Int,
    ): Boolean {
        validateConfiguration(submode, trPeriodSeconds)
        val normalizedMessage = normalizeMessage(message)
        if (normalizedMessage.isEmpty() || txSequence !in 0..1) {
            mutableState.value = Q65AutomationState(
                phase = Q65AutomationPhase.ERROR,
                submode = submode,
                trPeriodSeconds = trPeriodSeconds,
                reason = if (normalizedMessage.isEmpty()) "Q65 报文为空" else "Q65 TX 序列无效",
            )
            return false
        }
        mutableState.value = Q65AutomationState(
            phase = Q65AutomationPhase.ARMED,
            armed = true,
            repeat = repeat,
            message = normalizedMessage,
            txSequence = txSequence,
            submode = submode,
            trPeriodSeconds = trPeriodSeconds,
            reason = if (repeat) "等待自动 TX 时隙" else "等待下一次 TX 时隙",
        )
        return true
    }

    @Synchronized
    fun tryClaim(slot: Long, sequence: Int): Boolean {
        val current = mutableState.value
        if (!current.armed || current.phase == Q65AutomationPhase.TRANSMITTING) return false
        if (sequence != current.txSequence || slot <= current.lastClaimedSlot) return false
        mutableState.value = current.copy(
            phase = Q65AutomationPhase.TRANSMITTING,
            lastClaimedSlot = slot,
            reason = "Q65 流式发射中",
        )
        return true
    }

    @Synchronized
    fun markTransmitFinished(slot: Long, succeeded: Boolean, failureReason: String? = null) {
        val current = mutableState.value
        if (current.lastClaimedSlot != slot) return
        if (!succeeded) {
            mutableState.value = current.copy(
                phase = Q65AutomationPhase.ERROR,
                armed = false,
                reason = failureReason?.takeIf(String::isNotBlank) ?: "Q65 发射失败",
            )
            return
        }
        val keepArmed = current.repeat
        mutableState.value = current.copy(
            phase = if (keepArmed) Q65AutomationPhase.ARMED else Q65AutomationPhase.COMPLETE,
            armed = keepArmed,
            transmitCount = current.transmitCount + 1,
            reason = if (keepArmed) "本轮完成，等待下一 TX 时隙" else "单次发射完成",
        )
    }

    @Synchronized
    fun stop(reason: String? = null) {
        val current = mutableState.value
        mutableState.value = current.copy(
            phase = Q65AutomationPhase.STOPPED,
            armed = false,
            reason = reason?.takeIf(String::isNotBlank) ?: "用户停止",
        )
    }

    private fun validateConfiguration(submode: Int, trPeriodSeconds: Int) {
        require(submode in 0..4) { "正式 Q65 子模式仅支持 A-E" }
        require(trPeriodSeconds in SUPPORTED_PERIODS) { "Q65 周期无效" }
    }

    private fun normalizeMessage(message: String): String = message
        .trim()
        .uppercase()
        .replace(WHITESPACE, " ")
        .take(MAXIMUM_MESSAGE_CHARS)

    private companion object {
        val SUPPORTED_PERIODS = setOf(15, 30, 60, 120, 300)
        val WHITESPACE = Regex("\\s+")
        const val MAXIMUM_MESSAGE_CHARS = 64
    }
}
