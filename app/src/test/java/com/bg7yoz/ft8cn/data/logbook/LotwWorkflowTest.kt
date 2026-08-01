package com.bg7yoz.ft8cn.data.logbook

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bg7yoz.ft8cn.data.local.Ft8cnFeatureDatabase
import com.bg7yoz.ft8cn.data.local.LotwUploadJobEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LotwWorkflowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: Ft8cnFeatureDatabase
    private lateinit var repository: RoomQsoLogRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, Ft8cnFeatureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomQsoLogRepository(database.qsoDao())
    }

    @After
    fun tearDown() {
        database.close()
        context.noBackupFilesDir.resolve("lotw/signed").deleteRecursively()
    }

    @Test
    fun signedImportIsIdempotentAndQueuesOnlyAuditableQso() = runBlocking {
        val scheduler = RecordingScheduler()
        val workflow = LotwWorkflow(context, repository, database.lotwUploadDao(), scheduler) { 10_000L }
        val bytes = signedTq8Bytes()
        val first = workflow.importSignedTq8AndSchedule(ByteArrayInputStream(bytes))
        val second = workflow.importSignedTq8AndSchedule(ByteArrayInputStream(bytes))

        assertEquals(first.jobId, second.jobId)
        assertEquals(first.artifactSha256, second.artifactSha256)
        assertTrue(second.reusedExistingJob)
        assertEquals(1, database.lotwUploadDao().pending().size)
        assertEquals(listOf(first.jobId, first.jobId), scheduler.jobIds)
        val qso = repository.listAll().single()
        assertEquals(LotwStatus.SIGNED, qso.lotwStatus)
        assertEquals("JA6RJK", qso.dxCall)
    }

    @Test
    fun coordinatorPersistsAcceptedRejectedAndRetryStates() = runBlocking {
        assertCoordinatorOutcome(
            transport = { LotwServerResponse(true, "File queued for processing") },
            expectedResult = LotwExecutionResult.SUCCESS,
            expectedJobState = LotwJobState.ACCEPTED,
            expectedQsoState = LotwStatus.ACCEPTED,
        )
        resetDatabase()
        assertCoordinatorOutcome(
            transport = { LotwServerResponse(false, "Invalid signature") },
            expectedResult = LotwExecutionResult.SUCCESS,
            expectedJobState = LotwJobState.REJECTED,
            expectedQsoState = LotwStatus.REJECTED,
        )
        resetDatabase()
        assertCoordinatorOutcome(
            transport = { throw java.io.IOException("network unavailable") },
            expectedResult = LotwExecutionResult.RETRY,
            expectedJobState = LotwJobState.SIGNED,
            expectedQsoState = LotwStatus.SIGNED,
        )
    }

    @Test
    fun externalSigningExportPagesAcrossEntireRepository() = runBlocking {
        val workflow = LotwWorkflow(context, repository, database.lotwUploadDao(), RecordingScheduler()) { 30_000L }
        repeat(305) { index ->
            repository.upsert(
                QsoRecord(
                    stableId = "stable-${index.toString().padStart(4, '0')}",
                    startedUtcMillis = 1_775_000_000_000L + index * 15_000L,
                    endedUtcMillis = 1_775_000_015_000L + index * 15_000L,
                    mode = com.bg7yoz.ft8cn.core.model.FtxMode.FT8,
                    stationCall = "BG7YOZ",
                    stationGrid = "OL79",
                    dxCall = "K1A$index",
                    dxGrid = "FN20",
                    frequencyHz = 14_074_000,
                    reportSent = "-10",
                    reportReceived = "-12",
                ),
            )
        }

        val output = StringWriter()
        assertTrue(workflow.hasExternalSigningCandidates(pageSize = 37))
        assertEquals(305, workflow.writeAllForExternalSigning(output, pageSize = 37))
        val parsed = AdifCodec.import(output.toString())
        assertEquals(305, parsed.records.size)
        assertEquals(0, parsed.rejectedRecords)
        assertEquals("K1A304", parsed.records.maxByOrNull { it.startedUtcMillis }?.dxCall)
        assertTrue(repository.listAll().all { it.lotwStatus == LotwStatus.PENDING_SIGN })
    }

    private suspend fun assertCoordinatorOutcome(
        transport: (File) -> LotwServerResponse,
        expectedResult: LotwExecutionResult,
        expectedJobState: LotwJobState,
        expectedQsoState: LotwStatus,
    ) {
        val root = context.noBackupFilesDir.resolve("lotw/signed")
        val artifact = SignedTq8ArtifactStore(root).import(ByteArrayInputStream(signedTq8Bytes()))
        val record = AdifCodec.fieldsToQso(artifact.validation.qsoFields.single()).copy(lotwStatus = LotwStatus.SIGNED)
        repository.upsert(record)
        val jobId = database.lotwUploadDao().enqueue(
            LotwUploadJobEntity(
                idempotencyKey = artifact.sha256,
                createdUtcMillis = 1,
                updatedUtcMillis = 1,
                state = LotwJobState.SIGNED.name,
                attemptCount = 0,
                lastError = null,
                signedArtifactPath = artifact.file.absolutePath,
                signedArtifactSha256 = artifact.sha256,
                qsoStableIds = record.stableId,
            ),
        )
        val coordinator = LotwUploadCoordinator(
            database.lotwUploadDao(),
            repository,
            object : LotwUploadTransport {
                override fun upload(signedTq8: File): LotwServerResponse = transport(signedTq8)
            },
            root,
        ) { 20_000L }
        assertEquals(expectedResult, coordinator.execute(jobId))
        assertEquals(expectedJobState.name, database.lotwUploadDao().findById(jobId)?.state)
        assertEquals(expectedQsoState, repository.findByStableId(record.stableId)?.lotwStatus)
        assertNotNull(database.lotwUploadDao().findById(jobId))
    }

    private fun resetDatabase() {
        database.clearAllTables()
        context.noBackupFilesDir.resolve("lotw/signed").deleteRecursively()
    }

    private class RecordingScheduler : LotwWorkScheduler {
        val jobIds = mutableListOf<Long>()
        override fun schedule(jobId: Long) {
            jobIds += jobId
        }
    }

    private fun signedTq8Bytes(): ByteArray {
        val text = "<TQSL_IDENT:5>2.8.6" +
            "<Rec_Type:5>tCERT<CERTIFICATE:4>QUJD<EOR>" +
            "<Rec_Type:8>tCONTACT<CALL:6>JA6RJK<QSO_DATE:8>20260728<TIME_ON:6>120000" +
            "<FREQ:6>14.074<MODE:3>FT8<STATION_CALLSIGN:6>BG7YOZ" +
            "<SIGNDATA:4>abcd<SIGN_LOTW_1.0:4>efgh<EOR>"
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).bufferedWriter(Charsets.US_ASCII).use { it.write(text) }
        return output.toByteArray()
    }
}
