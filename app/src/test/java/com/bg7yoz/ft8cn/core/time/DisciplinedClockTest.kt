package com.bg7yoz.ft8cn.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisciplinedClockTest {
    @Test
    fun trustedSampleAnchorsUtcToMonotonicTimeAndIgnoresWallJump() {
        val source = MutableTimeSource(wallMillis = 1_000_000L)
        val clock = SystemDisciplinedClock(source)
        assertFalse(clock.snapshot().healthy)

        assertTrue(
            clock.submitSample(
                ClockSample(
                    utcMillis = 1_000_120.0,
                    monotonicNanos = source.monoNanos,
                    uncertaintyMillis = 10.0,
                    source = ClockSource.NTP,
                ),
            ),
        )
        assertTrue(clock.automaticTransmitAllowed())

        source.advance(5_000L)
        assertEquals(1_005_120L, clock.snapshot().utcMillis)
        source.wallMillis += 3_600_000L
        assertEquals(1_005_120L, clock.snapshot().utcMillis)
    }

    @Test
    fun trustedClockRejectsLargeOutlierAndEntersHoldover() {
        val source = MutableTimeSource(wallMillis = 10_000L)
        val policy = ClockHealthPolicy(holdoverAfterMillis = 1_000L, maximumSampleAgeMillis = 5_000L)
        val clock = SystemDisciplinedClock(source, policy)
        assertTrue(clock.submitSample(sample(source, 10_010.0)))

        source.advance(2_000L)
        assertFalse(clock.submitSample(sample(source, 50_000.0)))
        assertEquals(ClockSource.HOLDOVER, clock.snapshot().source)

        source.advance(4_000L)
        assertFalse(clock.snapshot().healthy)
        assertFalse(clock.automaticTransmitAllowed())
    }

    @Test
    fun untrustedSystemClockReanchorsAfterWallClockJump() {
        val source = MutableTimeSource(wallMillis = 1_000L)
        val clock = SystemDisciplinedClock(source)
        source.advance(100L)
        source.wallMillis += 10_000L

        val refreshed = clock.refresh()

        assertEquals(source.wallMillis, refreshed.utcMillis)
        assertFalse(refreshed.healthy)
        assertTrue(refreshed.detail.contains("跳变"))
    }

    private fun sample(source: MutableTimeSource, utcMillis: Double) = ClockSample(
        utcMillis = utcMillis,
        monotonicNanos = source.monoNanos,
        uncertaintyMillis = 5.0,
        source = ClockSource.NTP,
    )

    private class MutableTimeSource(
        var wallMillis: Long,
        var monoNanos: Long = 0L,
    ) : MonotonicTimeSource {
        override fun elapsedRealtimeNanos(): Long = monoNanos

        override fun wallClockMillis(): Long = wallMillis

        fun advance(millis: Long) {
            wallMillis += millis
            monoNanos += millis * 1_000_000L
        }
    }
}
