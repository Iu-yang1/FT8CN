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
    val slotsWithoutReply: Int = 0,
    val transmitting: Boolean = false,
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
