package com.bg7yoz.ft8cn.satellite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteDopplerPlannerTest {
    @Test
    fun recedingSatelliteLowersDownlinkAndRaisesUplink() {
        val target = SatelliteDopplerPlanner(radioStepHz = 5).plan(
            generatedUtcMillis = 1_000,
            rangeRateMetersPerSecond = 7_000.0,
            nominalDownlinkHz = 145_800_000,
            nominalUplinkHz = 435_000_000,
        )
        assertTrue(target.receiveDopplerHz < 0.0)
        assertTrue(target.transmitDopplerHz > 0.0)
        assertTrue(target.rxFrequencyHz < 145_800_000)
        assertTrue(target.txFrequencyHz > 435_000_000)
        assertEquals(0, target.rxFrequencyHz % 5)
        assertEquals(0, target.txFrequencyHz % 5)
    }

    @Test
    fun invertedLinearTransponderMirrorsPassbandPosition() {
        val planner = SatelliteDopplerPlanner()
        val normal = SatelliteTransponder(
            "normal", 435_100_000, 435_200_000, 145_900_000, 146_000_000, "SSB", false,
        )
        val inverted = normal.copy(name = "inverted", inverted = true)
        assertEquals(435_125_000, planner.mapDownlinkToUplink(145_925_000, normal))
        assertEquals(435_175_000, planner.mapDownlinkToUplink(145_925_000, inverted))
    }
}
