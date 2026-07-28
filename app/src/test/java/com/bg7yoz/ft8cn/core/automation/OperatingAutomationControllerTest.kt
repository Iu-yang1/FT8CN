package com.bg7yoz.ft8cn.core.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatingAutomationControllerTest {
    @Test
    fun earlyAndFullCallbacksAdvanceOnlyOncePerSlot() {
        val controller = OperatingAutomationController()
        assertTrue(controller.armSession(0, "BG5JSU", 100, 1))

        assertTrue(controller.tryAcceptIncoming(0, 101, 2, "BG5JSU"))
        assertFalse(controller.tryAcceptIncoming(0, 101, 2, "BG5JSU"))

        assertEquals(3, controller.state.value.currentFunctionOrder)
        assertEquals(AutomationPhase.ROGER, controller.state.value.phase)
        assertEquals(101, controller.state.value.lastAcceptedSlot)
    }

    @Test
    fun automaticTransmitCanBeClaimedOnlyOncePerSlot() {
        val controller = OperatingAutomationController()
        controller.armSession(1, "JA6RJK", 200, 3)

        assertTrue(controller.tryClaimTransmit(1, 201, 3))
        assertFalse(controller.tryClaimTransmit(1, 201, 3))
        controller.markTransmitFinished(1, 201, 3, false)
        assertTrue(controller.tryClaimTransmit(1, 202, 3))
    }

    @Test
    fun modeOrTargetMismatchCannotConsumeAStaleCallback() {
        val controller = OperatingAutomationController()
        controller.armSession(0, "BG5JSU", 300, 2)

        assertFalse(controller.tryAcceptIncoming(1, 301, 2, "BG5JSU"))
        assertFalse(controller.tryAcceptIncoming(0, 301, 2, "JA6RJK"))
        assertEquals(Long.MIN_VALUE, controller.state.value.lastAcceptedSlot)

        controller.armSession(1, "JA6RJK", 400, 1)
        assertEquals(1, controller.state.value.signalMode)
        assertEquals("JA6RJK", controller.state.value.targetCall)
        assertTrue(controller.tryAcceptIncoming(1, 401, 1, "JA6RJK"))
    }

    @Test
    fun qsoProgressionEndsInCompleteAfterSignoffTransmit() {
        val controller = OperatingAutomationController()
        controller.armSession(0, "JA6RJK", 500, 1)

        assertTrue(controller.tryAcceptIncoming(0, 501, 1, "JA6RJK"))
        assertEquals(AutomationPhase.REPORT, controller.state.value.phase)
        assertTrue(controller.tryAcceptIncoming(0, 502, 2, "JA6RJK"))
        assertEquals(AutomationPhase.ROGER, controller.state.value.phase)
        assertTrue(controller.tryAcceptIncoming(0, 503, 3, "JA6RJK"))
        assertEquals(AutomationPhase.SIGNOFF, controller.state.value.phase)
        assertTrue(controller.tryClaimTransmit(0, 504, 5))
        controller.markTransmitFinished(0, 504, 5, true)

        assertEquals(AutomationPhase.COMPLETE, controller.state.value.phase)
        assertFalse(controller.state.value.transmitting)
    }

    @Test
    fun cqBurstUsesConfiguredBackoffWithoutChangingDecoderWork() {
        val controller = OperatingAutomationController(maxConsecutiveCq = 2, cqBackoffSlots = 2)
        controller.armSession(0, "CQ", 600, 6)

        assertTrue(controller.tryClaimTransmit(0, 600, 6))
        controller.markTransmitFinished(0, 600, 6, false)
        assertTrue(controller.tryClaimTransmit(0, 601, 6))
        controller.markTransmitFinished(0, 601, 6, false)
        assertFalse(controller.tryClaimTransmit(0, 602, 6))
        assertTrue(controller.tryClaimTransmit(0, 603, 6))
    }

    @Test
    fun noReplyAndStopAreIdempotent() {
        val controller = OperatingAutomationController()
        controller.armSession(1, "BG5JSU", 700, 2)
        controller.recordNoReplySlot(1, 701)
        controller.recordNoReplySlot(1, 701)
        assertEquals(1, controller.state.value.slotsWithoutReply)

        controller.stopSession("用户停止")
        assertFalse(controller.state.value.armed)
        assertEquals(AutomationPhase.ABORTED, controller.state.value.phase)
        assertFalse(controller.tryClaimTransmit(1, 702, 2))
    }
}
