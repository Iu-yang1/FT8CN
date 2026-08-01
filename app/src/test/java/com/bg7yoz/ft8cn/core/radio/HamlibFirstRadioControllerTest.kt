package com.bg7yoz.ft8cn.core.radio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HamlibFirstRadioControllerTest {
    @Test
    fun hamlibTakesPriorityAndLegacyFallbackStillUsesOneControllerContract() = runBlocking {
        val hamlib = FakeRadioController()
        val legacy = FakeRadioController()
        val controller = HamlibFirstRadioController(hamlib, legacy)

        legacy.connect(7).getOrThrow()
        assertTrue(controller.attachLegacy().isSuccess)
        assertTrue(controller.setFrequency(7_074_000).isSuccess)
        assertEquals(7_074_000, legacy.state.value.rxFrequencyHz)

        assertTrue(controller.connect(42).isSuccess)
        assertTrue(controller.setFrequency(14_074_000).isSuccess)
        assertEquals(14_074_000, hamlib.state.value.rxFrequencyHz)
        assertEquals(7_074_000, legacy.state.value.rxFrequencyHz)
        controller.disconnect()
    }

    @Test
    fun emergencyStopDropsPttOnEveryConnectedBackend() = runBlocking {
        val hamlib = FakeRadioController()
        val legacy = FakeRadioController()
        val controller = HamlibFirstRadioController(hamlib, legacy)
        legacy.connect(7).getOrThrow()
        controller.attachLegacy().getOrThrow()
        legacy.setPtt(true).getOrThrow()
        hamlib.connect(42).getOrThrow()
        hamlib.setPtt(true).getOrThrow()

        assertTrue(controller.emergencyStop().isSuccess)

        assertFalse(hamlib.state.value.transmitting)
        assertFalse(legacy.state.value.transmitting)
    }
}
