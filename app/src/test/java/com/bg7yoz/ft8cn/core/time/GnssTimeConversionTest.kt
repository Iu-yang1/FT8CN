package com.bg7yoz.ft8cn.core.time

import org.junit.Assert.assertEquals
import org.junit.Test

class GnssTimeConversionTest {
    @Test
    fun convertsGpsEpochAndLeapSecondsToUtc() {
        val oneDayNanos = 86_400_000_000_000L
        val utc = AndroidGnssTimeDiscipline.gnssClockToUtcMillis(
            timeNanos = oneDayNanos,
            fullBiasNanos = 0L,
            biasNanos = 0.0,
            leapSeconds = 18,
        )

        assertEquals(316_051_182_000.0, utc, 0.001)
    }

    @Test
    fun removesFullAndFractionalHardwareBias() {
        val utc = AndroidGnssTimeDiscipline.gnssClockToUtcMillis(
            timeNanos = 1_000_000_000L,
            fullBiasNanos = -2_000_000_000L,
            biasNanos = 500_000.0,
            leapSeconds = 0,
        )

        assertEquals(315_964_802_999.5, utc, 0.001)
    }
}
