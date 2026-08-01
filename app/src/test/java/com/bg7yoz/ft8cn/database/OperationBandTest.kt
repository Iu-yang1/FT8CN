package com.bg7yoz.ft8cn.database

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OperationBandTest {
    @Test
    fun bundledFrequencyTableContainsFtxAndQ65Presets() {
        OperationBand.getInstance(ApplicationProvider.getApplicationContext())

        val frequencies = OperationBand.bandList.associateBy { it.band }
        assertTrue("频率表不应退化为单个当前频率", frequencies.size >= 55)
        assertEquals("20m", frequencies.getValue(14_074_000L).waveLength)
        assertEquals("20m FT4", frequencies.getValue(14_080_000L).waveLength)
        assertEquals("6m Q65", frequencies.getValue(50_275_000L).waveLength)
        assertEquals("2m Q65", frequencies.getValue(144_120_000L).waveLength)
        assertEquals("70cm Q65", frequencies.getValue(432_065_000L).waveLength)
        assertEquals("23cm Q65", frequencies.getValue(1_296_065_000L).waveLength)
    }
}
