package com.bg7yoz.ft8cn.satellite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class SatellitePassPredictorTest {
    @Test
    fun predictsOrderedBoundedPassesAcrossUtcDay() {
        val propagator = Sgp4OrbitPropagatorTest.testPropagator()
        val start = propagator.record.epochUtcMillis
        val passes = SatellitePassPredictor(
            propagator,
            ObserverPosition(40.0, -75.0),
            coarseStepMillis = 60_000L,
        ).predict(start, start + 48L * 60L * 60L * 1_000L, maximumPasses = 20)

        assertTrue(passes.isNotEmpty())
        assertTrue(passes.size <= 20)
        passes.forEach { pass ->
            assertTrue(pass.aosUtcMillis <= pass.tcaUtcMillis)
            assertTrue(pass.tcaUtcMillis <= pass.losUtcMillis)
            assertTrue(pass.maximumElevationDegrees >= 0.0)
            assertTrue(pass.aosAzimuthDegrees in 0.0..360.0)
            assertTrue(pass.losAzimuthDegrees in 0.0..360.0)
            assertFalse(pass.tleStale)
        }
        assertTrue(passes.zipWithNext().all { (first, second) -> first.losUtcMillis < second.aosUtcMillis })
    }

    @Test
    fun firstPassMatchesIndependentSkyfieldGolden() {
        val propagator = Sgp4OrbitPropagatorTest.testPropagator()
        val first = SatellitePassPredictor(
            propagator,
            ObserverPosition(40.0, -75.0),
            coarseStepMillis = 30_000L,
        ).predict(
            propagator.record.epochUtcMillis,
            propagator.record.epochUtcMillis + 6L * 60L * 60L * 1_000L,
        ).first()

        assertWithin(first.aosUtcMillis, "2000-06-27T19:17:45.079Z", 2_500L)
        assertWithin(first.tcaUtcMillis, "2000-06-27T19:39:26.255Z", 2_500L)
        assertWithin(first.losUtcMillis, "2000-06-27T20:06:13.304Z", 2_500L)
        assertTrue(abs(first.maximumElevationDegrees - 47.387480) < 0.15)
        assertTrue(abs(first.aosAzimuthDegrees - 281.586736) < 0.15)
        assertTrue(abs(first.losAzimuthDegrees - 140.509289) < 0.15)
    }

    @Test
    fun marksOldElementsWithoutSilentlySuppressingPasses() {
        val propagator = Sgp4OrbitPropagatorTest.testPropagator()
        val start = propagator.record.epochUtcMillis + 20L * 24L * 60L * 60L * 1_000L
        val passes = SatellitePassPredictor(
            propagator,
            ObserverPosition(0.0, 0.0),
            coarseStepMillis = 60_000L,
        ).predict(start, start + 24L * 60L * 60L * 1_000L)

        assertTrue(passes.isNotEmpty())
        assertTrue(passes.all(SatellitePass::tleStale))
    }

    @Test
    fun marksImplausiblyFutureElementsAsStale() {
        val propagator = Sgp4OrbitPropagatorTest.testPropagator()
        val start = propagator.record.epochUtcMillis - 20L * 24L * 60L * 60L * 1_000L
        val passes = SatellitePassPredictor(
            propagator,
            ObserverPosition(0.0, 0.0),
            coarseStepMillis = 60_000L,
        ).predict(start, start + 24L * 60L * 60L * 1_000L)

        assertTrue(passes.isNotEmpty())
        assertTrue(passes.all(SatellitePass::tleStale))
    }

    private fun assertWithin(actualMillis: Long, expectedIso: String, toleranceMillis: Long) {
        assertTrue(abs(actualMillis - Instant.parse(expectedIso).toEpochMilli()) <= toleranceMillis)
    }
}
