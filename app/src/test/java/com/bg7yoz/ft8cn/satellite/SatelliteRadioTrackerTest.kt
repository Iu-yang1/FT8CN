package com.bg7yoz.ft8cn.satellite

import com.bg7yoz.ft8cn.core.radio.FakeRadioController
import com.bg7yoz.ft8cn.core.radio.RadioMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteRadioTrackerTest {
    @Test
    fun tracksAosToLosAndRestoresOriginalFrequencies() = runBlocking {
        val radio = connectedRadio()
        val tracker = SatelliteRadioTracker(radio)
        assertTrue(tracker.start().isSuccess)
        val target = SatelliteFrequencyTarget(1_000, 145_799_000, 435_010_000, -1_000.0, 10_000.0, 2_000.0)
        assertEquals(true, tracker.apply(target, 1_000).getOrThrow())
        assertEquals(145_799_000, radio.state.value.rxFrequencyHz)
        assertTrue(tracker.stop("LOS").isSuccess)
        assertEquals(145_800_000, radio.state.value.rxFrequencyHz)
        assertEquals(435_000_000, radio.state.value.txFrequencyHz)
        assertFalse(radio.state.value.transmitting)
    }

    @Test
    fun staleTargetIsIgnoredAndSetFailureRollsBack() = runBlocking {
        val radio = connectedRadio()
        val tracker = SatelliteRadioTracker(radio)
        tracker.start().getOrThrow()
        val stale = SatelliteFrequencyTarget(1_000, 145_799_000, 435_010_000, 0.0, 0.0, 0.0)
        assertFalse(tracker.apply(stale, 5_000).getOrThrow())
        radio.failNext("frequency")
        val fresh = stale.copy(generatedUtcMillis = 6_000)
        assertTrue(tracker.apply(fresh, 6_000).isFailure)
        assertEquals(145_800_000, radio.state.value.rxFrequencyHz)
        assertEquals(435_000_000, radio.state.value.txFrequencyHz)
    }

    private suspend fun connectedRadio(): FakeRadioController = FakeRadioController().also {
        it.connect(1).getOrThrow()
        it.setFrequency(145_800_000, 435_000_000).getOrThrow()
        it.setMode(RadioMode.DATA_USB, 3_000).getOrThrow()
    }
}
