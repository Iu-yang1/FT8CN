package com.bg7yoz.ft8cn.satellite

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class SatellitePassPlanCacheTest {
    @Test
    fun reusesPlanWithinWindowAndRefreshesAfterExpiry() {
        val propagator = Sgp4OrbitPropagatorTest.testPropagator()
        val observer = ObserverPosition(40.0, -75.0)
        val cache = SatellitePassPlanCache(cacheDurationMillis = 60_000L)
        val start = propagator.record.epochUtcMillis

        val first = cache.load(propagator, observer, start)
        val cached = cache.load(propagator, observer, start + 30_000L)
        val refreshed = cache.load(propagator, observer, start + 61_000L)

        assertSame(first, cached)
        assertNotSame(first, refreshed)
    }
}
