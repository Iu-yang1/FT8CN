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
    fun automaticTransmitThresholdDependsOnMode() {
        val source = MutableTimeSource(wallMillis = 20_000L)
        val clock = SystemDisciplinedClock(source)
        assertTrue(
            clock.submitSample(
                sample(source, 20_000.0).copy(uncertaintyMillis = 600.0),
            ),
        )

        assertFalse(clock.automaticTransmitAllowed(AutomaticTransmitMode.FT4))
        assertFalse(clock.automaticTransmitAllowed(AutomaticTransmitMode.FT8))
        assertTrue(clock.automaticTransmitAllowed(AutomaticTransmitMode.Q65))
        assertTrue(
            clock.automaticTransmitBlockReason(AutomaticTransmitMode.FT4).contains("250ms"),
        )
    }

    @Test
    fun sustainedConsensusCanReacquireAfterLargeResidual() {
        val source = MutableTimeSource(wallMillis = 30_000L)
        val clock = SystemDisciplinedClock(source)
        assertTrue(clock.submitSample(sample(source, 30_000.0).copy(consensusMembers = 2)))
        source.advance(1_000L)
        val corrected = sample(source, 36_000.0).copy(consensusMembers = 2)

        assertFalse(clock.submitSample(corrected))
        assertFalse(clock.submitSample(corrected))
        assertTrue(clock.submitSample(corrected))
        assertEquals(36_000L, clock.snapshot().utcMillis)
        assertEquals("", clock.snapshot().lastRejectedReason)
    }

    @Test
    fun aSingleSourceCannotForceLargeResidualReacquisition() {
        val source = MutableTimeSource(wallMillis = 40_000L)
        val clock = SystemDisciplinedClock(source)
        assertTrue(clock.submitSample(sample(source, 40_000.0)))
        source.advance(1_000L)
        val hostile = sample(source, 51_000.0)

        repeat(4) { assertFalse(clock.submitSample(hostile)) }
        assertTrue(clock.snapshot().lastRejectedReason.contains("大残差"))
        assertEquals(41_000L, clock.nowMillis())
    }

    @Test
    fun highRateUtcReadsDoNotPublishHighRateUiState() {
        val source = MutableTimeSource(wallMillis = 50_000L)
        val clock = SystemDisciplinedClock(source)
        assertTrue(clock.submitSample(sample(source, 50_000.0)))
        source.advance(250L)

        assertEquals(50_250L, clock.nowMillis())
        assertEquals(50_000L, clock.state.value.utcMillis)
        assertEquals(50_250L, clock.refresh().utcMillis)
    }

    @Test
    fun backwardReacquisitionNeverMovesUtcBackwardAndBlocksAutomaticTx() {
        val source = MutableTimeSource(wallMillis = 100_000L)
        val clock = SystemDisciplinedClock(source)
        assertTrue(clock.submitSample(sample(source, 100_000.0).copy(consensusMembers = 2)))
        source.advance(1_000L)
        assertEquals(101_000L, clock.nowMillis())
        val backward = sample(source, 96_000.0).copy(consensusMembers = 2)

        assertFalse(clock.submitSample(backward))
        assertFalse(clock.submitSample(backward))
        assertTrue(clock.submitSample(backward))
        assertEquals(101_000L, clock.nowMillis())
        assertFalse(clock.automaticTransmitAllowed(AutomaticTransmitMode.Q65))
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
