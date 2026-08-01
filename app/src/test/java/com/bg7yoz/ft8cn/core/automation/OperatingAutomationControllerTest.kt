package com.bg7yoz.ft8cn.core.automation

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatingAutomationControllerTest {
    private val band20m = 14_074_000L

    @Test
    fun earlyAndFullCallbacksAdvanceOnlyOncePerSlot() {
        val controller = OperatingAutomationController()
        assertTrue(controller.armSession(0, "BG5JSU", 100, 1, band20m))

        assertTrue(controller.tryAcceptIncoming(0, 101, 2, "BG5JSU", band20m))
        assertFalse(controller.tryAcceptIncoming(0, 101, 2, "BG5JSU", band20m))

        assertEquals(3, controller.state.value.currentFunctionOrder)
        assertEquals(AutomationPhase.ROGER, controller.state.value.phase)
        assertEquals(101, controller.state.value.lastAcceptedSlot)
    }

    @Test
    fun automaticTransmitCanBeClaimedOnlyOncePerSlot() {
        val controller = OperatingAutomationController()
        controller.armSession(1, "JA6RJK", 200, 3, band20m)

        assertTrue(controller.tryClaimTransmit(1, 201, 3, band20m))
        assertFalse(controller.tryClaimTransmit(1, 201, 3, band20m))
        val generation = controller.currentSessionGeneration()
        controller.markTransmitFinished(1, 201, 3, false, band20m, generation)
        assertTrue(controller.tryClaimTransmit(1, 202, 3, band20m))
    }

    @Test
    fun modeBandOrTargetMismatchCannotConsumeAStaleCallback() {
        val controller = OperatingAutomationController()
        controller.armSession(0, "BG5JSU", 300, 2, band20m)

        assertFalse(controller.tryAcceptIncoming(1, 301, 2, "BG5JSU", band20m))
        assertFalse(controller.tryAcceptIncoming(0, 301, 2, "JA6RJK", band20m))
        assertFalse(controller.tryAcceptIncoming(0, 301, 2, "BG5JSU", 7_074_000L))
        assertEquals(Long.MIN_VALUE, controller.state.value.lastAcceptedSlot)

        controller.armSession(1, "JA6RJK", 400, 1, 7_047_500L)
        assertEquals(1, controller.state.value.signalMode)
        assertEquals(7_047_500L, controller.state.value.bandHz)
        assertEquals("JA6RJK", controller.state.value.targetCall)
        assertTrue(controller.tryAcceptIncoming(1, 401, 1, "JA6RJK", 7_047_500L))
    }

    @Test
    fun qsoProgressionEndsInCompleteAfterSignoffTransmit() {
        val controller = OperatingAutomationController()
        controller.armSession(0, "JA6RJK", 500, 1, band20m)

        assertTrue(controller.tryAcceptIncoming(0, 501, 1, "JA6RJK", band20m))
        assertEquals(AutomationPhase.REPORT, controller.state.value.phase)
        assertTrue(controller.tryAcceptIncoming(0, 502, 2, "JA6RJK", band20m))
        assertEquals(AutomationPhase.ROGER, controller.state.value.phase)
        assertTrue(controller.tryAcceptIncoming(0, 503, 3, "JA6RJK", band20m))
        assertEquals(AutomationPhase.SIGNOFF, controller.state.value.phase)
        assertTrue(controller.tryClaimTransmit(0, 504, 5, band20m))
        controller.markTransmitFinished(
            0,
            504,
            5,
            true,
            band20m,
            controller.currentSessionGeneration(),
        )

        assertEquals(AutomationPhase.COMPLETE, controller.state.value.phase)
        assertFalse(controller.state.value.transmitting)
    }

    @Test
    fun cqBurstUsesConfiguredBackoffWithoutChangingDecoderWork() {
        val controller = OperatingAutomationController(maxConsecutiveCq = 2, cqBackoffSlots = 2)
        controller.armSession(0, "CQ", 600, 6, band20m)

        assertTrue(controller.tryClaimTransmit(0, 600, 6, band20m))
        controller.markTransmitFinished(0, 600, 6, false, band20m, controller.currentSessionGeneration())
        assertTrue(controller.tryClaimTransmit(0, 601, 6, band20m))
        controller.markTransmitFinished(0, 601, 6, false, band20m, controller.currentSessionGeneration())
        assertFalse(controller.tryClaimTransmit(0, 602, 6, band20m))
        assertTrue(controller.tryClaimTransmit(0, 603, 6, band20m))
    }

    @Test
    fun defaultCqLimitCannotBeDisabled() {
        val controller = OperatingAutomationController()
        controller.armSession(0, "CQ", 800, 6, band20m)

        repeat(OperatingAutomationController.DEFAULT_MAX_CONSECUTIVE_CQ) { offset ->
            val slot = 800L + offset
            assertTrue(controller.tryClaimTransmit(0, slot, 6, band20m))
            controller.markTransmitFinished(
                0,
                slot,
                6,
                false,
                band20m,
                controller.currentSessionGeneration(),
            )
        }
        assertFalse(controller.tryClaimTransmit(0, 806, 6, band20m))
        assertTrue(controller.tryClaimTransmit(0, 807, 6, band20m))

        var rejectedZeroLimit = false
        try {
            OperatingAutomationController(maxConsecutiveCq = 0)
        } catch (_: IllegalArgumentException) {
            rejectedZeroLimit = true
        }
        assertTrue(rejectedZeroLimit)
    }

    @Test
    fun staleTransmitCompletionCannotMutateANewSession() {
        val controller = OperatingAutomationController()
        controller.armSession(0, "BG5JSU", 900, 2, band20m)
        assertTrue(controller.tryClaimTransmit(0, 901, 2, band20m))
        val oldGeneration = controller.currentSessionGeneration()

        controller.armSession(0, "JA6RJK", 901, 1, band20m)
        val newGeneration = controller.currentSessionGeneration()
        assertNotEquals(oldGeneration, newGeneration)
        controller.markTransmitFinished(0, 901, 2, true, band20m, oldGeneration)

        assertEquals("JA6RJK", controller.state.value.targetCall)
        assertEquals(AutomationPhase.REPLYING, controller.state.value.phase)
        assertFalse(controller.state.value.transmitting)
    }

    @Test
    fun deterministicRandomEventsNeverClaimOneSessionSlotTwice() {
        repeat(1_000) { seed ->
            val random = Random(seed)
            val mode = seed and 1
            val band = if (mode == 0) 14_074_000L else 14_080_000L
            val controller = OperatingAutomationController(maxConsecutiveCq = 8, cqBackoffSlots = 1)
            val target = "T${seed.toString().padStart(4, '0')}"
            controller.armSession(mode, target, 1_000, 1, band)
            val claimedSlots = mutableSetOf<Long>()

            repeat(16) {
                val slot = 1_001L + random.nextInt(8)
                val order = random.nextInt(1, 7)
                val claimed = controller.tryClaimTransmit(mode, slot, order, band)
                if (claimed) {
                    assertTrue("seed=$seed slot=$slot", claimedSlots.add(slot))
                    controller.markTransmitFinished(
                        mode,
                        slot,
                        order,
                        false,
                        band,
                        controller.currentSessionGeneration(),
                    )
                }
                assertFalse(controller.tryClaimTransmit(mode xor 1, slot + 20, order, band))
                assertFalse(controller.tryClaimTransmit(mode, slot + 20, order, band + 1))
            }
        }
    }

    @Test
    fun noReplyAndStopAreIdempotent() {
        val controller = OperatingAutomationController()
        controller.armSession(1, "BG5JSU", 700, 2, band20m)
        controller.recordNoReplySlot(1, 701, band20m)
        controller.recordNoReplySlot(1, 701, band20m)
        assertEquals(1, controller.state.value.slotsWithoutReply)

        controller.stopSession("用户停止")
        val stoppedGeneration = controller.currentSessionGeneration()
        controller.stopSession("用户停止")
        assertEquals(stoppedGeneration, controller.currentSessionGeneration())
        assertFalse(controller.state.value.armed)
        assertEquals(AutomationPhase.ABORTED, controller.state.value.phase)
        assertFalse(controller.tryClaimTransmit(1, 702, 2, band20m))
    }
}
