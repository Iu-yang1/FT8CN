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
    val bandHz: Long = -1L,
    val sessionGeneration: Long = 0L,
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
 * 解码器可能在 early/full/deep 阶段重复回调同一时隙。本类保证同一会话每个
 * 时隙最多接受一次状态跃迁、最多认领一次自动发射，但不改变任何解码参数。
 */
class OperatingAutomationController @JvmOverloads constructor(
    private val maxConsecutiveCq: Int = DEFAULT_MAX_CONSECUTIVE_CQ,
    private val cqBackoffSlots: Int = DEFAULT_CQ_BACKOFF_SLOTS,
) {
    init {
        require(maxConsecutiveCq in 1..MAX_CONFIGURED_SLOTS) {
            "maxConsecutiveCq 必须在 1..$MAX_CONFIGURED_SLOTS 范围内"
        }
        require(cqBackoffSlots in 1..MAX_CONFIGURED_SLOTS) {
            "cqBackoffSlots 必须在 1..$MAX_CONFIGURED_SLOTS 范围内"
        }
    }

    private val mutableState = MutableStateFlow(AutomationState())

    val state: StateFlow<AutomationState> = mutableState.asStateFlow()

    @Synchronized
    fun armSession(
        signalMode: Int,
        targetCall: String?,
        currentSlot: Long,
        currentFunctionOrder: Int,
        bandHz: Long = 0L,
    ): Boolean {
        if (!isSupportedMode(signalMode) || currentFunctionOrder !in 1..6 || bandHz < 0L) {
            abortCurrentSession("自动通联参数无效或模式不是 FT8/FT4")
            return false
        }

        val normalizedTarget = normalizeTarget(targetCall)
        val previous = mutableState.value
        if (previous.armed &&
            previous.signalMode == signalMode &&
            previous.bandHz == bandHz &&
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
            bandHz = bandHz,
            sessionGeneration = nextGeneration(previous.sessionGeneration),
            armed = true,
            currentFunctionOrder = currentFunctionOrder,
            nextEligibleCqSlot = currentSlot,
        )
        return true
    }

    @Synchronized
    fun stopSession(reason: String?) {
        val current = mutableState.value
        if (!current.armed && !current.transmitting && current.phase == AutomationPhase.ABORTED) return
        mutableState.value = current.copy(
            phase = AutomationPhase.ABORTED,
            sessionGeneration = nextGeneration(current.sessionGeneration),
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
        bandHz: Long = 0L,
    ): Boolean {
        val current = mutableState.value
        if (!matchesSession(current, signalMode, bandHz, targetCall)) return false
        if (slot <= current.lastAcceptedSlot || incomingOrder !in 1..5) return false

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
    fun tryClaimTransmit(
        signalMode: Int,
        slot: Long,
        functionOrder: Int,
        bandHz: Long = 0L,
    ): Boolean {
        val current = mutableState.value
        if (!current.armed || current.signalMode != signalMode || current.bandHz != bandHz) return false
        if (functionOrder !in 1..6) return false
        if (slot <= current.lastTransmitSlot || current.transmitting) return false
        if (functionOrder == 6 && slot < current.nextEligibleCqSlot) return false

        var consecutiveCq = if (functionOrder == 6) current.consecutiveCqCount + 1 else 0
        var nextEligibleSlot = current.nextEligibleCqSlot
        if (functionOrder == 6 && consecutiveCq >= maxConsecutiveCq) {
            nextEligibleSlot = checkedSlotAdd(slot, cqBackoffSlots.toLong())
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
        bandHz: Long,
        sessionGeneration: Long,
    ) {
        val current = mutableState.value
        if (current.signalMode != signalMode ||
            current.bandHz != bandHz ||
            current.sessionGeneration != sessionGeneration ||
            current.lastTransmitSlot != slot
        ) return
        mutableState.value = current.copy(
            phase = if (completed) AutomationPhase.COMPLETE else phaseForFunctionOrder(functionOrder),
            transmitting = false,
            currentFunctionOrder = functionOrder,
        )
    }

    @Synchronized
    fun recordNoReplySlot(signalMode: Int, slot: Long, bandHz: Long = 0L) {
        val current = mutableState.value
        if (!current.armed ||
            current.signalMode != signalMode ||
            current.bandHz != bandHz ||
            slot <= current.lastNoReplySlot
        ) return
        mutableState.value = current.copy(
            slotsWithoutReply = if (current.slotsWithoutReply == Int.MAX_VALUE) {
                Int.MAX_VALUE
            } else {
                current.slotsWithoutReply + 1
            },
            lastNoReplySlot = slot,
        )
    }

    @Synchronized
    fun resetToCq(signalMode: Int, currentSlot: Long, bandHz: Long = 0L) {
        val current = mutableState.value
        if (!current.armed || current.signalMode != signalMode || current.bandHz != bandHz) return
        mutableState.value = current.copy(
            phase = AutomationPhase.CALLING,
            targetCall = "CQ",
            sessionGeneration = nextGeneration(current.sessionGeneration),
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

    @Synchronized
    fun currentSessionGeneration(): Long = mutableState.value.sessionGeneration

    @Synchronized
    fun isSessionCurrent(
        signalMode: Int,
        targetCall: String?,
        bandHz: Long,
        sessionGeneration: Long,
    ): Boolean = matchesSession(mutableState.value, signalMode, bandHz, targetCall) &&
        mutableState.value.sessionGeneration == sessionGeneration

    private fun abortCurrentSession(reason: String) {
        val current = mutableState.value
        mutableState.value = current.copy(
            phase = AutomationPhase.ABORTED,
            sessionGeneration = nextGeneration(current.sessionGeneration),
            armed = false,
            transmitting = false,
            reason = reason,
        )
    }

    private fun matchesSession(
        current: AutomationState,
        signalMode: Int,
        bandHz: Long,
        targetCall: String?,
    ): Boolean = current.armed &&
        current.signalMode == signalMode &&
        current.bandHz == bandHz &&
        current.targetCall == normalizeTarget(targetCall)

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

    private fun nextGeneration(current: Long): Long = if (current == Long.MAX_VALUE) 1L else current + 1L

    companion object {
        const val DEFAULT_MAX_CONSECUTIVE_CQ = 6
        const val DEFAULT_CQ_BACKOFF_SLOTS = 2
        private const val MAX_CONFIGURED_SLOTS = 100
    }
}
