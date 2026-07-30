package com.bg7yoz.ft8cn.core

import com.bg7yoz.ft8cn.FT8Common
import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.core.automation.AutomationIntent
import com.bg7yoz.ft8cn.core.automation.AutomationPhase
import com.bg7yoz.ft8cn.core.automation.FakeAutomationController
import com.bg7yoz.ft8cn.core.dsp.DecodeBatch
import com.bg7yoz.ft8cn.core.dsp.DecodeRequest
import com.bg7yoz.ft8cn.core.dsp.FakeDecoderCoordinator
import com.bg7yoz.ft8cn.core.dsp.FakeDopplerEngine
import com.bg7yoz.ft8cn.core.dsp.PcmChunkSource
import com.bg7yoz.ft8cn.core.dsp.DopplerRequest
import com.bg7yoz.ft8cn.core.model.DecodeStage
import com.bg7yoz.ft8cn.core.model.FeatureDestination
import com.bg7yoz.ft8cn.core.model.FtxMode
import com.bg7yoz.ft8cn.core.radio.FakeRadioController
import com.bg7yoz.ft8cn.core.time.ClockSnapshot
import com.bg7yoz.ft8cn.core.time.ClockSource
import com.bg7yoz.ft8cn.core.time.FakeDisciplinedClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreBoundariesTest {
    @Test
    fun protocolModesKeepIndependentSlotGeometry() {
        val originalSubmode = GeneralVariables.getQ65Submode()
        val originalPeriod = GeneralVariables.getQ65TrPeriodSeconds()
        try {
            assertEquals(15_000, FT8Common.getSlotTimeMillisecond(FT8Common.FT8_MODE))
            assertEquals(180_000, FT8Common.getSamplesPerSlot(FT8Common.FT8_MODE))
            assertEquals(12_300, FT8Common.getEarlyDecodeDurationMs(FT8Common.FT8_MODE))
            assertTrue(FT8Common.supportsEarlyDecodeStage(FT8Common.FT8_MODE))

            assertEquals(7_500, FT8Common.getSlotTimeMillisecond(FT8Common.FT4_MODE))
            assertEquals(90_000, FT8Common.getSamplesPerSlot(FT8Common.FT4_MODE))
            assertEquals(6_150, FT8Common.getEarlyDecodeDurationMs(FT8Common.FT4_MODE))
            assertTrue(FT8Common.supportsEarlyDecodeStage(FT8Common.FT4_MODE))

            GeneralVariables.setQ65Configuration(FT8Common.Q65_SUBMODE_A, 60)
            assertEquals(60_000, FT8Common.getSlotTimeMillisecond(FT8Common.Q65_MODE))
            assertEquals(720_000, FT8Common.getSamplesPerSlot(FT8Common.Q65_MODE))
            assertFalse(FT8Common.supportsEarlyDecodeStage(FT8Common.Q65_MODE))

            GeneralVariables.setQ65Configuration(FT8Common.Q65_SUBMODE_E, 300)
            assertEquals(300_000, FT8Common.getSlotTimeMillisecond(FT8Common.Q65_MODE))
            assertEquals(3_600_000, FT8Common.getSamplesPerSlot(FT8Common.Q65_MODE))
        } finally {
            GeneralVariables.setQ65Configuration(originalSubmode, originalPeriod)
        }
    }

    @Test
    fun featureRoutesAreStableAndUnique() {
        assertEquals(8, FeatureDestination.values().size)
        assertEquals(
            FeatureDestination.values().size,
            FeatureDestination.values().map { it.route }.toSet().size,
        )
        assertEquals("decode", FeatureDestination.DECODE.route)
    }

    @Test
    fun fakeClockPublishesImmutableSnapshot() {
        val initial = clockSnapshot(utcMillis = 1_000L, healthy = false)
        val clock = FakeDisciplinedClock(initial)
        val updated = clockSnapshot(utcMillis = 2_000L, healthy = true)

        clock.update(updated)

        assertEquals(updated, clock.snapshot())
        assertTrue(clock.state.value.healthy)
    }

    @Test
    fun decoderRequestKeepsItsFrequencyAndLiveSnapshot() = runTest {
        var observed: DecodeRequest? = null
        val decoder = FakeDecoderCoordinator { request ->
            observed = request
            DecodeBatch(request.requestId, emptyList(), 4)
        }
        val request = DecodeRequest(
            requestId = 7,
            triggerUtcMillis = 12_345,
            mode = FtxMode.FT4,
            stage = DecodeStage.EARLY,
            inputIsLive = true,
            qsoFrequencyHz = 1_500,
            txFrequencyHz = 1_800,
            source = EmptyPcmSource,
        )

        val batch = decoder.decode(request)

        assertEquals(7, batch.requestId)
        assertEquals(1_500, observed?.qsoFrequencyHz)
        assertEquals(1_800, observed?.txFrequencyHz)
        assertEquals(true, observed?.inputIsLive)
        assertNull(decoder.state.value.activeRequestId)
    }

    @Test
    fun fakeRadioRequiresConnectionAndTracksSplit() = runTest {
        val radio = FakeRadioController()
        assertTrue(radio.setFrequency(14_074_000).isFailure)

        radio.connect(42).getOrThrow()
        radio.setFrequency(14_074_000, 14_076_000).getOrThrow()

        assertTrue(radio.state.value.connected)
        assertTrue(radio.state.value.splitEnabled)
        assertEquals(14_076_000, radio.state.value.txFrequencyHz)
    }

    @Test
    fun automationStopAlwaysDropsTransmitState() = runTest {
        val automation = FakeAutomationController()
        automation.dispatch(AutomationIntent.Arm("BG7YOZ"))
        automation.dispatch(AutomationIntent.SlotElapsed(receivedReply = false))
        automation.dispatch(AutomationIntent.Stop)

        assertEquals(AutomationPhase.ABORTED, automation.state.value.phase)
        assertFalse(automation.state.value.transmitting)
    }

    @Test
    fun dopplerFakeUsesOppositeRxAndTxCorrection() {
        val target = FakeDopplerEngine(125.0).calculate(
            DopplerRequest(
                utcMillis = 100,
                nominalRxFrequencyHz = 144_120_000,
                nominalTxFrequencyHz = 144_120_000,
                radialVelocityMetersPerSecond = 0.0,
                uncertaintyHz = 2.0,
            ),
        )

        assertEquals(144_120_125, target.rxFrequencyHz)
        assertEquals(144_119_875, target.txFrequencyHz)
    }

    private fun clockSnapshot(utcMillis: Long, healthy: Boolean) = ClockSnapshot(
        utcMillis = utcMillis,
        monotonicNanos = utcMillis * 1_000_000,
        offsetMillis = 0.0,
        driftPpm = 0.0,
        uncertaintyMillis = if (healthy) 10.0 else 10_000.0,
        source = ClockSource.SYSTEM,
        sampleAgeMillis = 0,
        healthy = healthy,
    )

    private object EmptyPcmSource : PcmChunkSource {
        override val sampleRate: Int = 12_000
        override val sampleCount: Long = 0

        override fun read(
            offset: Long,
            destination: FloatArray,
            destinationOffset: Int,
            length: Int,
        ): Int = 0
    }
}
