package com.bg7yoz.ft8cn.data.logbook

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LotwSecurityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun signedTq8IsValidatedStoredByHashAndDeduplicated() {
        val bytes = signedTq8Bytes()
        val store = SignedTq8ArtifactStore(temporaryFolder.newFolder("signed"))
        val first = store.import(ByteArrayInputStream(bytes))
        val second = store.import(ByteArrayInputStream(bytes))
        assertEquals(first.sha256, second.sha256)
        assertEquals(first.file.canonicalFile, second.file.canonicalFile)
        assertEquals(1, first.validation.qsoFields.size)
        assertEquals("JA6RJK", first.validation.qsoFields.single()["CALL"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsignedAdifCompressedAsGzipIsRejected() {
        val unsigned = gzip("<ADIF_VER:5>3.1.5<EOH><CALL:4>W1AW<EOR>")
        Tq8StructureValidator.validate(ByteArrayInputStream(unsigned), unsigned.size.toLong())
    }

    @Test
    fun officialUploadCommentsAreParsedAndMalformedResponsesFailClosed() {
        val accepted = LotwUploadResponseParser.parse(
            "<html><!-- .UPL. accepted --><!-- .UPLMESSAGE. File queued for processing --></html>",
        )
        assertTrue(accepted.accepted)
        assertEquals("File queued for processing", accepted.message)
        val rejected = LotwUploadResponseParser.parse(
            "<!-- .UPL. rejected --><!-- .UPLMESSAGE. Invalid signature -->",
        )
        assertFalse(rejected.accepted)
        assertTrue(runCatching { LotwUploadResponseParser.parse("<html>OK</html>") }.isFailure)
    }

    private fun signedTq8Bytes(): ByteArray = gzip(
        "<TQSL_IDENT:5>2.8.6" +
            "<Rec_Type:5>tCERT<CERTIFICATE:4>QUJD<EOR>" +
            "<Rec_Type:8>tCONTACT<CALL:6>JA6RJK<QSO_DATE:8>20260728<TIME_ON:6>120000" +
            "<FREQ:6>14.074<MODE:3>FT8<STATION_CALLSIGN:6>BG7YOZ" +
            "<SIGNDATA:4>abcd<SIGN_LOTW_1.0:4>efgh<EOR>",
    )

    private fun gzip(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).bufferedWriter(Charsets.US_ASCII).use { it.write(text) }
        return output.toByteArray()
    }
}
