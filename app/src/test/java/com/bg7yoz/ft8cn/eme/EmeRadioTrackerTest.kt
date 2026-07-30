package com.bg7yoz.ft8cn.eme

import com.bg7yoz.ft8cn.core.radio.FakeRadioController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmeRadioTrackerTest {
    @Test
    fun appliesDopplerAndRestoresOriginalFrequenciesWithoutPtt() = runBlocking {
        val radio = FakeRadioController().also {
            it.connect(1).getOrThrow()
            it.setFrequency(144_120_000L, 144_120_000L).getOrThrow()
        }
        val tracker = EmeRadioTracker(radio)

        assertTrue(tracker.start(EmeRadioTrackingPolicy(restoreFrequencyOnStop = true)).isSuccess)
        assertTrue(tracker.apply(EmeFrequencyTarget(1_000L, 144_119_850L, 144_120_150L), 1_000L).getOrThrow())
        assertEquals(144_119_850L, radio.state.value.rxFrequencyHz)
        assertEquals(144_120_150L, radio.state.value.txFrequencyHz)
        assertFalse(radio.state.value.transmitting)

        assertTrue(tracker.stop().isSuccess)
        assertEquals(144_120_000L, radio.state.value.rxFrequencyHz)
        assertEquals(144_120_000L, radio.state.value.txFrequencyHz)
        assertFalse(radio.state.value.transmitting)
    }

    @Test
    fun frequencyFailureRestoresOriginalState() = runBlocking {
        val radio = FakeRadioController().also {
            it.connect(1).getOrThrow()
            it.setFrequency(432_065_000L, 432_065_000L).getOrThrow()
        }
        val tracker = EmeRadioTracker(radio)
        tracker.start().getOrThrow()
        radio.failNext("frequency")

        assertTrue(tracker.apply(EmeFrequencyTarget(2_000L, 432_064_900L, 432_065_100L), 2_000L).isFailure)
        assertEquals(432_065_000L, radio.state.value.rxFrequencyHz)
        assertEquals(432_065_000L, radio.state.value.txFrequencyHz)
    }

    @Test
    fun policyRejectsUnsafeCorrectionAndLowElevation() = runBlocking {
        val radio = FakeRadioController().also {
            it.connect(1).getOrThrow()
            it.setFrequency(144_120_000L, 144_120_000L).getOrThrow()
        }
        val tracker = EmeRadioTracker(radio)
        val policy = EmeRadioTrackingPolicy(
            maximumCorrectionHz = 500.0,
            minimumElevationDegrees = 10.0,
        )
        tracker.start(policy).getOrThrow()

        assertTrue(
            tracker.apply(
                EmeFrequencyTarget(1_000L, 144_119_400L, 144_120_600L),
                nowUtcMillis = 1_000L,
                receiveCorrectionHz = -600.0,
                transmitCorrectionHz = 600.0,
                elevationDegrees = 30.0,
            ).isFailure,
        )
        tracker.start(policy).getOrThrow()
        assertTrue(
            tracker.apply(
                EmeFrequencyTarget(2_000L, 144_119_900L, 144_120_100L),
                nowUtcMillis = 2_000L,
                receiveCorrectionHz = -100.0,
                transmitCorrectionHz = 100.0,
                elevationDegrees = 5.0,
            ).isFailure,
        )
        assertEquals(144_120_000L, radio.state.value.rxFrequencyHz)
    }

    @Test
    fun policyRateLimitsCatUpdates() = runBlocking {
        val radio = FakeRadioController().also {
            it.connect(1).getOrThrow()
            it.setFrequency(144_120_000L, 144_120_000L).getOrThrow()
        }
        val tracker = EmeRadioTracker(radio)
        tracker.start(EmeRadioTrackingPolicy(updateIntervalMillis = 10_000L)).getOrThrow()

        assertTrue(tracker.apply(EmeFrequencyTarget(1_000L, 144_119_900L, 144_120_100L), 1_000L).getOrThrow())
        assertFalse(tracker.apply(EmeFrequencyTarget(5_000L, 144_119_800L, 144_120_200L), 5_000L).getOrThrow())
        assertTrue(tracker.apply(EmeFrequencyTarget(11_000L, 144_119_800L, 144_120_200L), 11_000L).getOrThrow())
    }
}
