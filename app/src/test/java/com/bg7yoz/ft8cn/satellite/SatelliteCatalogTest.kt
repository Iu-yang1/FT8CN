package com.bg7yoz.ft8cn.satellite

import com.bg7yoz.ft8cn.data.local.SatelliteSourceMetadataEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SatelliteCatalogTest {
    @Test
    fun parserAcceptsThreeLineAndTwoLineCatalogs() {
        val threeLine = "VANGUARD 1\n${Sgp4OrbitPropagatorTest.LINE_1}\n${Sgp4OrbitPropagatorTest.LINE_2}\n"
        val record = TleCatalogParser.parse(threeLine, "test", 123).single()
        assertEquals("VANGUARD 1", record.name)
        assertEquals(5, record.catalogNumber)

        val twoLine = "${Sgp4OrbitPropagatorTest.LINE_1}\n${Sgp4OrbitPropagatorTest.LINE_2}"
        assertEquals(5, TleCatalogParser.parse(twoLine, "test", 123).single().catalogNumber)
    }

    @Test
    fun parserSkipsOnlyDamagedRecordAndRejectsImplausibleFutureEpoch() {
        val valid = "VANGUARD 1\n${Sgp4OrbitPropagatorTest.LINE_1}\n${Sgp4OrbitPropagatorTest.LINE_2}"
        val damaged = "BROKEN\n1 invalid\n2 invalid"
        val fetched = Sgp4OrbitPropagatorTest.testPropagator().record.epochUtcMillis

        val records = TleCatalogParser.parse("$damaged\n$valid", "test", fetched)

        assertEquals(1, records.size)
        assertEquals(5, records.single().catalogNumber)
        assertTrue(TleCatalogParser.parse(valid, "test", fetched - 8L * 24L * 60L * 60L * 1_000L).isEmpty())
    }

    @Test
    fun celestrakUsesExplicitTleFormatAndConditionalHeaders() = runBlocking {
        var requestedUrl = ""
        var requestedHeaders = emptyMap<String, String>()
        val transport = SatelliteHttpTransport { url, headers ->
            requestedUrl = url
            requestedHeaders = headers
            SatelliteHttpResponse(304, emptyMap(), ByteArray(0))
        }
        val metadata = SatelliteSourceMetadataEntity(
            "celestrak:ham", "etag-1", "last-1", 0, 0, 0, null, null,
        )
        val result = CelesTrakCatalogClient(transport).fetchGroup("amateur", metadata)
        assertTrue(result === CatalogFetchResult.NotModified)
        assertTrue(requestedUrl.endsWith("GROUP=amateur&FORMAT=TLE"))
        assertEquals("etag-1", requestedHeaders["If-None-Match"])
        assertEquals("last-1", requestedHeaders["If-Modified-Since"])
    }

    @Test
    fun satNogsParserFiltersCatalogDeadAndInvalidFrequencies() {
        val payload = """
            [
              {"norad_cat_id":5,"description":"Linear","alive":true,
               "uplink_low":435100000,"uplink_high":435200000,
               "downlink_low":145900000,"downlink_high":146000000,
               "mode":"SSB","invert":true},
              {"norad_cat_id":5,"description":"Dead","alive":false},
              {"norad_cat_id":6,"description":"Other","alive":true}
            ]
        """.trimIndent()
        val transponder = SatNogsTransmitterParser.parse(payload, 5).single()
        assertEquals("Linear", transponder.name)
        assertTrue(transponder.inverted)
        assertEquals(435_100_000L, transponder.uplinkLowHz)
    }

    @Test
    fun satNogsRequestsLook4SatCompatibleActiveTransmitterSource() = runBlocking {
        var requestedUrl = ""
        val transport = SatelliteHttpTransport { url, _ ->
            requestedUrl = url
            SatelliteHttpResponse(200, emptyMap(), "[]".toByteArray())
        }

        SatNogsCatalogClient(transport).fetchTransmitters(5, null)

        assertTrue(requestedUrl.startsWith("https://db.satnogs.org/api/transmitters/"))
        assertTrue(requestedUrl.contains("format=json"))
        assertTrue(requestedUrl.contains("status=active"))
        assertTrue(requestedUrl.contains("satellite__norad_cat_id=5"))
    }

    @Test
    fun externallyCapturedOfficialCatalogsParseWhenProvided() {
        val tlePath = System.getenv("FT8CN_CELESTRAK_SNAPSHOT").orEmpty()
        val satNogsPath = System.getenv("FT8CN_SATNOGS_SNAPSHOT").orEmpty()
        assumeTrue(tlePath.isNotBlank() && satNogsPath.isNotBlank())
        val records = TleCatalogParser.parse(File(tlePath).readText(), "celestrak:amateur", 1)
        assertTrue(records.size >= 50)
        val transmitters = SatNogsTransmitterParser.parse(File(satNogsPath).readText(), 7530)
        assertTrue(transmitters.isNotEmpty())
    }
}
