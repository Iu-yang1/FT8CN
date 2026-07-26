package com.bg7yoz.ft8cn.core.radio

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTransactionCoordinatorTest {
    @Test
    fun fakeItClampsAudioOffsetAndRestoresDialAfterTransmit() = runBlocking {
        val radio = connectedRadio()
        val coordinator = RadioTransactionCoordinator(radio)
        coordinator.arm()

        val lease = coordinator.beginTransmit(
            FrequencyPlan(
                rxDialFrequencyHz = 14_074_000,
                txRfFrequencyHz = 14_075_500,
                strategy = SplitStrategy.FAKE_IT,
                requestedAudioOffsetHz = 2_500,
            ),
        ).getOrThrow()

        assertEquals(2_000, lease.txAudioOffsetHz)
        assertEquals(14_073_500, radio.state.value.rxFrequencyHz)
        assertTrue(radio.state.value.transmitting)
        assertTrue(coordinator.endTransmit(lease).isSuccess)
        assertEquals(14_074_000, radio.state.value.rxFrequencyHz)
        assertFalse(radio.state.value.transmitting)
        coordinator.close()
    }

    @Test
    fun pttFailureRollsBackFrequencyAndLeavesNoActiveLease() = runBlocking {
        val radio = connectedRadio()
        val coordinator = RadioTransactionCoordinator(radio)
        coordinator.arm()
        radio.failNext("ptt")

        val result = coordinator.beginTransmit(
            FrequencyPlan(14_074_000, 14_076_000, SplitStrategy.RIG_SPLIT, 1_500),
        )

        assertTrue(result.isFailure)
        assertEquals(14_074_000, radio.state.value.rxFrequencyHz)
        assertEquals(14_074_000, radio.state.value.txFrequencyHz)
        assertFalse(radio.state.value.transmitting)
        assertFalse(coordinator.safetyState.value.transmitting)
        coordinator.close()
    }

    @Test
    fun watchdogDropsPttAndRestoresFrequency() = runBlocking {
        val radio = connectedRadio()
        val coordinator = RadioTransactionCoordinator(radio, maximumTransmitMillis = 40)
        coordinator.arm()
        coordinator.beginTransmit(
            FrequencyPlan(14_074_000, 14_076_000, SplitStrategy.RIG_SPLIT, 1_500),
        ).getOrThrow()

        repeat(50) {
            if (!radio.state.value.transmitting) return@repeat
            delay(10)
        }

        assertFalse(radio.state.value.transmitting)
        assertEquals(14_074_000, radio.state.value.txFrequencyHz)
        assertEquals("PTT watchdog 超时", coordinator.safetyState.value.lastStopReason)
        coordinator.close()
    }

    private suspend fun connectedRadio(): FakeRadioController = FakeRadioController().also {
        it.connect(1).getOrThrow()
        it.setFrequency(14_074_000).getOrThrow()
        it.setMode(RadioMode.DATA_USB, 3_000).getOrThrow()
    }
}
