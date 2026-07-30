package com.bg7yoz.ft8cn.core.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Q65AutomationControllerTest {
    @Test
    fun oneShotClaimsOnlyConfiguredSequenceAndCompletes() {
        val controller = Q65AutomationController()
        assertTrue(controller.arm("  cq   bg7yoz  ol79 ", 1, false, 0, 60))

        assertFalse(controller.tryClaim(10, 0))
        assertTrue(controller.tryClaim(10, 1))
        assertFalse(controller.tryClaim(10, 1))
        controller.markTransmitFinished(10, true)

        val state = controller.state.value
        assertEquals(Q65AutomationPhase.COMPLETE, state.phase)
        assertEquals("CQ BG7YOZ OL79", state.message)
        assertEquals(1, state.transmitCount)
        assertFalse(state.armed)
    }

    @Test
    fun repeatingSequenceRearmsOnlyForANewerSlot() {
        val controller = Q65AutomationController()
        assertTrue(controller.arm("CQ TEST", 0, true, 4, 300))
        assertTrue(controller.tryClaim(20, 0))
        controller.markTransmitFinished(20, true)

        assertEquals(Q65AutomationPhase.ARMED, controller.state.value.phase)
        assertFalse(controller.tryClaim(20, 0))
        assertTrue(controller.tryClaim(21, 0))
    }

    @Test
    fun receiveOnlyAndFormalSubmodeBoundaryCannotTransmit() {
        val controller = Q65AutomationController()
        controller.receiveOnly(0, 15)
        assertFalse(controller.tryClaim(1, 0))

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            controller.arm("CQ", 0, false, 5, 60)
        }
    }

    @Test
    fun ft8Ft4AutomationAndQ65AutomationRemainIndependent() {
        val ftx = OperatingAutomationController()
        assertFalse(
            ftx.armSession(
                signalMode = 2,
                targetCall = "q65",
                currentSlot = 1,
                currentFunctionOrder = 6,
            ),
        )

        val q65 = Q65AutomationController()
        assertTrue(q65.arm("CQ EME", 0, false, 0, 60))
        assertTrue(q65.state.value.armed)
        assertFalse(ftx.state.value.armed)
    }
}
