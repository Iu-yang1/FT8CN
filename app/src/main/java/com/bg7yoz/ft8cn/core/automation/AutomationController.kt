package com.bg7yoz.ft8cn.core.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AutomationPhase {
    IDLE,
    ARMED,
    CALLING,
    REPLYING,
    REPORT,
    ROGER,
    SIGNOFF,
    COMPLETE,
    ABORTED,
}

data class AutomationState(
    val phase: AutomationPhase = AutomationPhase.IDLE,
    val targetCall: String = "",
    val signalMode: Int = -1,
    val armed: Boolean = false,
    val currentFunctionOrder: Int = 6,
    val slotsWithoutReply: Int = 0,
    val transmitting: Boolean = false,
    val lastAcceptedSlot: Long = Long.MIN_VALUE,
    val lastNoReplySlot: Long = Long.MIN_VALUE,
    val lastTransmitSlot: Long = Long.MIN_VALUE,
    val consecutiveCqCount: Int = 0,
    val nextEligibleCqSlot: Long = Long.MIN_VALUE,
    val reason: String? = null,
)

sealed interface AutomationIntent {
    data class Arm(val targetCall: String = "") : AutomationIntent
    object Stop : AutomationIntent
    data class SlotElapsed(val receivedReply: Boolean) : AutomationIntent
}

interface AutomationController {
    val state: StateFlow<AutomationState>
    suspend fun dispatch(intent: AutomationIntent)
}

class FakeAutomationController : AutomationController {
    private val mutableState = MutableStateFlow(AutomationState())

    override val state: StateFlow<AutomationState> = mutableState.asStateFlow()

    override suspend fun dispatch(intent: AutomationIntent) {
        mutableState.value = when (intent) {
            is AutomationIntent.Arm -> AutomationState(
                phase = AutomationPhase.ARMED,
                targetCall = intent.targetCall,
            )
            AutomationIntent.Stop -> mutableState.value.copy(
                phase = AutomationPhase.ABORTED,
                transmitting = false,
                reason = "用户停止",
            )
            is AutomationIntent.SlotElapsed -> if (intent.receivedReply) {
                mutableState.value.copy(phase = AutomationPhase.REPLYING, slotsWithoutReply = 0)
            } else {
                mutableState.value.copy(slotsWithoutReply = mutableState.value.slotsWithoutReply + 1)
            }
        }
    }
}

/**
 * FT8/FT4 自动通联的确定性门禁。
 *
 * 解码器可能在 early/full/deep 阶段重复回调同一时隙，本类保证同一会话每个
 * 时隙最多接受一次状态跃迁、最多认领一次自动发射。它不改变解码器参数，
 * 也不负责生成消息或控制 PTT。
 */
class OperatingAutomationController @JvmOverloads constructor(
    private val maxConsecutiveCq: Int = 0,
    private val cqBackoffSlots: Int = 1,
) {
    private val mutableState = MutableStateFlow(AutomationState())

    val state: StateFlow<AutomationState> = mutableState.asStateFlow()

    @Synchronized
    fun armSession(
        signalMode: Int,
        targetCall: String?,
        currentSlot: Long,
        currentFunctionOrder: Int,
    ): Boolean {
        if (!isSupportedMode(signalMode)) {
            mutableState.value = mutableState.value.copy(
                phase = AutomationPhase.ABORTED,
                armed = false,
                transmitting = false,
                reason = "自动通联仅支持 FT8/FT4",
            )
            return false
        }

        val normalizedTarget = normalizeTarget(targetCall)
        val previous = mutableState.value
        if (previous.armed &&
            previous.signalMode == signalMode &&
            previous.targetCall == normalizedTarget
        ) {
            mutableState.value = previous.copy(
                phase = phaseForFunctionOrder(currentFunctionOrder),
                currentFunctionOrder = currentFunctionOrder,
                reason = null,
            )
            return true
        }

        mutableState.value = AutomationState(
            phase = phaseForFunctionOrder(currentFunctionOrder),
            targetCall = normalizedTarget,
            signalMode = signalMode,
            armed = true,
            currentFunctionOrder = currentFunctionOrder,
            nextEligibleCqSlot = currentSlot,
        )
        return true
    }

    @Synchronized
    fun stopSession(reason: String?) {
        mutableState.value = mutableState.value.copy(
            phase = AutomationPhase.ABORTED,
            armed = false,
            transmitting = false,
            reason = reason?.takeIf { it.isNotBlank() } ?: "用户停止",
        )
    }

    @Synchronized
    fun tryAcceptIncoming(
        signalMode: Int,
        slot: Long,
        incomingOrder: Int,
        targetCall: String?,
    ): Boolean {
        val current = mutableState.value
        if (!current.armed || current.signalMode != signalMode) return false
        if (normalizeTarget(targetCall) != current.targetCall) return false
        if (slot <= current.lastAcceptedSlot) return false
        if (incomingOrder !in 1..5) return false

        val nextOrder = (incomingOrder + 1).coerceAtMost(6)
        mutableState.value = current.copy(
            phase = phaseForFunctionOrder(nextOrder),
            currentFunctionOrder = nextOrder,
            slotsWithoutReply = 0,
            lastAcceptedSlot = slot,
            lastNoReplySlot = slot,
            consecutiveCqCount = 0,
            nextEligibleCqSlot = slot,
            reason = null,
        )
        return true
    }

    @Synchronized
    fun tryClaimTransmit(signalMode: Int, slot: Long, functionOrder: Int): Boolean {
        val current = mutableState.value
        if (!current.armed || current.signalMode != signalMode) return false
        if (slot <= current.lastTransmitSlot || current.transmitting) return false
        if (functionOrder == 6 && slot < current.nextEligibleCqSlot) return false

        var consecutiveCq = if (functionOrder == 6) current.consecutiveCqCount + 1 else 0
        var nextEligibleSlot = current.nextEligibleCqSlot
        if (functionOrder == 6 && maxConsecutiveCq > 0 && consecutiveCq >= maxConsecutiveCq) {
            nextEligibleSlot = checkedSlotAdd(slot, cqBackoffSlots.coerceAtLeast(1).toLong())
            consecutiveCq = 0
        }

        mutableState.value = current.copy(
            phase = phaseForFunctionOrder(functionOrder),
            currentFunctionOrder = functionOrder,
            transmitting = true,
            lastTransmitSlot = slot,
            consecutiveCqCount = consecutiveCq,
            nextEligibleCqSlot = nextEligibleSlot,
            reason = null,
        )
        return true
    }

    @Synchronized
    fun markTransmitFinished(
        signalMode: Int,
        slot: Long,
        functionOrder: Int,
        completed: Boolean,
    ) {
        val current = mutableState.value
        if (current.signalMode != signalMode || current.lastTransmitSlot != slot) return
        mutableState.value = current.copy(
            phase = if (completed) AutomationPhase.COMPLETE else phaseForFunctionOrder(functionOrder),
            transmitting = false,
            currentFunctionOrder = functionOrder,
        )
    }

    @Synchronized
    fun recordNoReplySlot(signalMode: Int, slot: Long) {
        val current = mutableState.value
        if (!current.armed || current.signalMode != signalMode || slot <= current.lastNoReplySlot) return
        mutableState.value = current.copy(
            slotsWithoutReply = current.slotsWithoutReply + 1,
            lastNoReplySlot = slot,
        )
    }

    @Synchronized
    fun resetToCq(signalMode: Int, currentSlot: Long) {
        val current = mutableState.value
        if (!current.armed || current.signalMode != signalMode) return
        mutableState.value = current.copy(
            phase = AutomationPhase.CALLING,
            targetCall = "CQ",
            currentFunctionOrder = 6,
            transmitting = false,
            slotsWithoutReply = 0,
            lastAcceptedSlot = Long.MIN_VALUE,
            lastNoReplySlot = Long.MIN_VALUE,
            consecutiveCqCount = 0,
            nextEligibleCqSlot = currentSlot,
            reason = null,
        )
    }

    private fun isSupportedMode(mode: Int): Boolean = mode == 0 || mode == 1

    private fun normalizeTarget(targetCall: String?): String =
        targetCall?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "CQ"

    private fun phaseForFunctionOrder(order: Int): AutomationPhase = when (order) {
        1 -> AutomationPhase.REPLYING
        2 -> AutomationPhase.REPORT
        3 -> AutomationPhase.ROGER
        4, 5 -> AutomationPhase.SIGNOFF
        6 -> AutomationPhase.CALLING
        else -> AutomationPhase.ARMED
    }

    private fun checkedSlotAdd(slot: Long, delta: Long): Long =
        if (slot > Long.MAX_VALUE - delta) Long.MAX_VALUE else slot + delta
}
