package com.bg7yoz.ft8cn.data.logbook

import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.core.model.FtxMode
import com.bg7yoz.ft8cn.log.QSLRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacyQsoPersistenceTest {
    @Test
    fun mapsCompletedFt4ContactWithoutReadingMutableModeLater() {
        val legacy = QSLRecord(
            1_775_000_000_000L,
            1_775_000_007_500L,
            "bg7yoz",
            "ol79",
            "ja6rjk",
            "pm53",
            -10,
            -12,
            "FT4",
            14_080_000L,
            1_500,
        )

        val mapped = LegacyQsoPersistence.toRecord(
            legacy,
            GeneralVariables.OPERATING_PROFILE_NORMAL,
        )
        assertEquals(FtxMode.FT4, mapped.mode)
        assertEquals("FT4", mapped.submode)
        assertEquals(14_080_000L, mapped.frequencyHz)
        assertEquals("BG7YOZ", mapped.stationCall)
        assertEquals("JA6RJK", mapped.dxCall)
        assertNull(mapped.propagationMode)
    }

    @Test
    fun marksQ65EmeContactButDoesNotInventSatelliteMetadata() {
        val legacy = QSLRecord(
            1_775_000_000_000L,
            1_775_000_060_000L,
            "BG7YOZ",
            "OL79",
            "K1JT",
            "FN20",
            -20,
            -24,
            "Q65",
            144_120_000L,
            1_000,
        )
        val eme = LegacyQsoPersistence.toRecord(
            legacy,
            GeneralVariables.OPERATING_PROFILE_Q65_EME,
        )
        assertEquals(FtxMode.Q65, eme.mode)
        assertEquals("EME", eme.propagationMode)

        val satelliteWithoutTarget = LegacyQsoPersistence.toRecord(
            legacy,
            GeneralVariables.OPERATING_PROFILE_SATELLITE_FT4,
        )
        assertNull(satelliteWithoutTarget.propagationMode)
        assertNull(satelliteWithoutTarget.satelliteName)
    }
}
