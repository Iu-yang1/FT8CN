package com.bg7yoz.ft8cn.satellite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class Sgp4OrbitPropagatorTest {
    @Test
    fun valladoVerificationVectorMatchesAtEpochAndSixHours() {
        val propagator = testPropagator()
        val atEpoch = propagator.propagate(propagator.record.epochUtcMillis)
        assertVector(doubleArrayOf(7022.46529266, -1400.08296755, 0.03995155), atEpoch.positionKilometers, 1e-6)
        assertVector(doubleArrayOf(1.893841015, 6.405893759, 4.534807250), atEpoch.velocityKilometersPerSecond, 1e-9)

        val atSixHours = propagator.propagate(propagator.record.epochUtcMillis + 360L * 60_000L)
        assertVector(doubleArrayOf(-7154.03120202, -3783.17682504, -3536.19412294), atSixHours.positionKilometers, 1e-5)
        assertVector(doubleArrayOf(4.741887409, -4.151817765, -2.093935425), atSixHours.velocityKilometersPerSecond, 1e-8)
    }

    @Test
    fun parsingDoesNotChangeJvmDefaultTimezone() {
        val original = TimeZone.getDefault()
        val temporary = TimeZone.getTimeZone("Asia/Singapore")
        TimeZone.setDefault(temporary)
        try {
            testPropagator()
            assertEquals(temporary.id, TimeZone.getDefault().id)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun observationHasFiniteGroundTrackAndRangeRate() {
        val propagator = testPropagator()
        val observation = propagator.observe(
            ObserverPosition(40.0, -75.0, 100.0),
            propagator.record.epochUtcMillis,
        )
        assertTrue(observation.azimuthDegrees in 0.0..360.0)
        assertTrue(observation.elevationDegrees in -90.0..90.0)
        assertTrue(observation.rangeKilometers > 100.0)
        assertTrue(observation.rangeRateMetersPerSecond.isFinite())
        assertTrue(observation.subpointLatitudeDegrees in -90.0..90.0)
        assertTrue(observation.subpointLongitudeDegrees in -180.0..180.0)
    }

    @Test
    fun checksumAndLineMismatchAreRejected() {
        val badChecksum = LINE_1.dropLast(1) + if (LINE_1.last() == '0') '1' else '0'
        assertFails { Sgp4OrbitPropagator.parse("TEST", badChecksum, LINE_2) }
        assertFails { Sgp4OrbitPropagator.parse("TEST", LINE_1, LINE_2.replaceRange(2, 7, "00006")) }
    }

    private fun assertVector(expected: DoubleArray, actual: DoubleArray, tolerance: Double) {
        expected.indices.forEach { assertEquals(expected[it], actual[it], tolerance) }
    }

    private fun assertFails(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertNotEquals(null, failure)
    }

    companion object {
        const val LINE_1 = "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753"
        const val LINE_2 = "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667"

        fun testPropagator(): Sgp4OrbitPropagator = Sgp4OrbitPropagator.parse("VANGUARD 1", LINE_1, LINE_2)
    }
}
