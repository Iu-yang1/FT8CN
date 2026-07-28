package com.bg7yoz.ft8cn.data.logbook

import com.bg7yoz.ft8cn.core.model.FtxMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdifCodecTest {
    @Test
    fun roundTripUsesAdif315ModeMappingsAndSatelliteFields() {
        val records = listOf(
            record(FtxMode.FT8, 14_074_000, "JA6RJK"),
            record(FtxMode.FT4, 14_080_000, "W1AW"),
            record(FtxMode.Q65, 144_120_000, "K1JT").copy(
                propagationMode = "SAT",
                satelliteName = "IO-117",
                satelliteMode = "U/V",
            ),
        )
        val text = AdifCodec.export(records)
        assertTrue(text.contains("<ADIF_VER:5>3.1.5"))
        assertTrue(text.contains("<MODE:3>FT8"))
        assertTrue(text.contains("<MODE:4>MFSK<SUBMODE:3>FT4"))
        assertTrue(text.contains("<MODE:4>MFSK<SUBMODE:3>Q65"))
        assertTrue(text.contains("<PROP_MODE:3>SAT<SAT_NAME:6>IO-117<SAT_MODE:3>U/V"))

        val imported = AdifCodec.import(text)
        assertEquals(0, imported.rejectedRecords)
        assertEquals(setOf(FtxMode.FT8, FtxMode.FT4, FtxMode.Q65), imported.records.map { it.mode }.toSet())
        assertEquals(setOf(14_074_000L, 14_080_000L, 144_120_000L), imported.records.map { it.frequencyHz }.toSet())
        assertEquals("IO-117", imported.records.single { it.mode == FtxMode.Q65 }.satelliteName)
    }

    @Test
    fun parserIsBoundedAndRejectsMalformedOrUnsupportedRecordsWithoutCrashing() {
        val text = "Unicode 前缀不会进入字段" +
            "<ADIF_VER:5>3.1.5<EOH>" +
            "<CALL:6>JA6RJK<QSO_DATE:8>20260728<TIME_ON:6>120000" +
            "<FREQ:6>14.074<MODE:4>NOPE<EOR>" +
            "<BROKEN:not-a-number>x<EOR>"
        val result = AdifCodec.import(text)
        assertEquals(0, result.records.size)
        assertEquals(1, result.rejectedRecords)
        assertFalse(result.warnings.isEmpty())
    }

    @Test
    fun importerAcceptsFourDigitTimeAndLegacyDirectFt4ButExporterCanonicalizes() {
        val text = "<ADIF_VER:5>3.1.5<EOH>" +
            "<CALL:4>W1AW<QSO_DATE:8>20260728<TIME_ON:4>1234" +
            "<FREQ:5>14.08<MODE:3>FT4<EOR>"
        val imported = AdifCodec.import(text)
        assertEquals(1, imported.records.size)
        assertEquals(FtxMode.FT4, imported.records.single().mode)
        assertTrue(AdifCodec.export(imported.records).contains("<MODE:4>MFSK<SUBMODE:3>FT4"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun exporterRejectsNonAsciiCriticalFields() {
        AdifCodec.export(listOf(record(FtxMode.FT8, 14_074_000, "测试1")))
    }

    private fun record(mode: FtxMode, frequencyHz: Long, dxCall: String): QsoRecord = QsoRecord(
        stableId = "$mode-$dxCall",
        startedUtcMillis = 1_775_000_000_000,
        endedUtcMillis = 1_775_000_015_000,
        mode = mode,
        stationCall = "BG7YOZ",
        stationGrid = "OL79",
        dxCall = dxCall,
        dxGrid = "PM53",
        frequencyHz = frequencyHz,
        reportSent = "-10",
        reportReceived = "-12",
    )
}
