package com.bg7yoz.ft8cn.core.time

import org.junit.Assert.assertEquals
import org.junit.Test

class DisciplinedSlotSchedulerTest {
    @Test
    fun ft8AndFt4UseProtocolSlotLengths() {
        val ft8 = DisciplinedSlotScheduler(SlotMode.FT8)
        val ft4 = DisciplinedSlotScheduler(SlotMode.FT4)

        assertEquals(15_000L, ft8.periodMillis)
        assertEquals(7_500L, ft4.periodMillis)
        assertEquals(1L, ft8.boundaryAt(15_001L).index)
        assertEquals(2L, ft4.boundaryAt(15_001L).index)
    }

    @Test
    fun q65PeriodsAndNegativeUtcUseFloorDivision() {
        for (period in listOf(15, 30, 60, 120, 300)) {
            val scheduler = DisciplinedSlotScheduler(SlotMode.Q65, period)
            assertEquals(period * 1_000L, scheduler.periodMillis)
            assertEquals(-1L, scheduler.boundaryAt(-1L).index)
            assertEquals(0L, scheduler.nextBoundaryAfter(-1L).index)
        }
    }

    @Test
    fun sequentialBoundariesNeverDuplicateOrSkip() {
        for (scheduler in listOf(
            DisciplinedSlotScheduler(SlotMode.FT8),
            DisciplinedSlotScheduler(SlotMode.FT4),
            DisciplinedSlotScheduler(SlotMode.Q65, 60),
        )) {
            var boundary = scheduler.boundaryAt(1_700_000_000_000L)
            repeat(1_000) {
                val next = scheduler.nextBoundaryAfter(boundary.startUtcMillis)
                assertEquals(boundary.index + 1, next.index)
                assertEquals(boundary.endUtcMillis, next.startUtcMillis)
                boundary = next
            }
        }
    }
}
