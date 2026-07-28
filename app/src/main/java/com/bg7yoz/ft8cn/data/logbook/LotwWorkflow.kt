package com.bg7yoz.ft8cn.data.logbook

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bg7yoz.ft8cn.data.local.LotwUploadDao
import com.bg7yoz.ft8cn.data.local.LotwUploadJobEntity
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow

enum class LotwJobState {
    PENDING_SIGN,
    SIGNED,
    UPLOADING,
    ACCEPTED,
    REJECTED,
}

interface LotwWorkScheduler {
    fun schedule(jobId: Long)
}

class AndroidLotwWorkScheduler(context: Context) : LotwWorkScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun schedule(jobId: Long) {
        val request = OneTimeWorkRequestBuilder<LotwUploadWorker>()
            .setInputData(workDataOf(LotwUploadWorker.KEY_JOB_ID to jobId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork("lotw-upload-$jobId", ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val TAG = "lotw-signed-upload"
    }
}

data class LotwQueueResult(
    val jobId: Long,
    val artifactSha256: String,
    val qsoCount: Int,
    val reusedExistingJob: Boolean,
)

data class AdifImportSummary(
    val imported: Int,
    val rejected: Int,
    val warnings: List<String>,
)

/** 本地日志、外部 TQSL 签名和 LoTW 队列之间的唯一业务入口。 */
class LotwWorkflow(
    context: Context,
    private val qsoRepository: QsoLogRepository,
    private val uploadDao: LotwUploadDao,
    private val scheduler: LotwWorkScheduler = AndroidLotwWorkScheduler(context),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val artifactStore = SignedTq8ArtifactStore(
        context.applicationContext.noBackupFilesDir.resolve("lotw/signed"),
    )

    fun observeJobs(limit: Int = 100): Flow<List<LotwUploadJobEntity>> {
        require(limit in 1..1_000) { "LoTW 任务查询上限必须在 1..1000" }
        return uploadDao.observeRecent(limit)
    }

    suspend fun importAdif(text: String): AdifImportSummary {
        val result = AdifCodec.import(text)
        result.records.forEach { qsoRepository.upsert(it) }
        return AdifImportSummary(result.records.size, result.rejectedRecords, result.warnings)
    }

    suspend fun exportAllAdif(): String = AdifCodec.export(qsoRepository.listAll())

    suspend fun exportForExternalSigning(stableIds: Collection<String>): String {
        val records = stableIds.distinct().mapNotNull { qsoRepository.findByStableId(it) }
        require(records.isNotEmpty()) { "没有可导出的 QSO" }
        records.forEach { record ->
            when (record.lotwStatus) {
                LotwStatus.LOCAL, LotwStatus.REJECTED -> qsoRepository.updateLotwStatus(
                    listOf(record.stableId),
                    LotwStatus.PENDING_SIGN,
                    updatedUtcMillis = nowMillis(),
                )
                LotwStatus.PENDING_SIGN -> Unit
                else -> error("QSO ${record.stableId} 当前状态 ${record.lotwStatus} 不需要重新签名")
            }
        }
        return AdifCodec.export(records)
    }

    suspend fun importSignedTq8AndSchedule(input: InputStream): LotwQueueResult {
        val artifact = artifactStore.import(input)
        val existing = uploadDao.findByIdempotencyKey(artifact.sha256)
        if (existing != null) {
            if (existing.state == LotwJobState.SIGNED.name || existing.state == LotwJobState.UPLOADING.name) {
                scheduler.schedule(existing.id)
            }
            return LotwQueueResult(
                existing.id,
                artifact.sha256,
                existing.decodeStableIds().size,
                reusedExistingJob = true,
            )
        }

        val records = artifact.validation.qsoFields.map(AdifCodec::fieldsToQso)
        require(records.isNotEmpty()) { "签名文件未包含可审计 QSO" }
        records.forEach { signedRecord ->
            val current = qsoRepository.findByStableId(signedRecord.stableId)
            if (current == null) qsoRepository.upsert(signedRecord)
            val stored = qsoRepository.findByStableId(signedRecord.stableId) ?: error("QSO 写入失败")
            when (stored.lotwStatus) {
                LotwStatus.LOCAL, LotwStatus.REJECTED -> qsoRepository.updateLotwStatus(
                    listOf(stored.stableId),
                    LotwStatus.PENDING_SIGN,
                    updatedUtcMillis = nowMillis(),
                )
                LotwStatus.PENDING_SIGN -> Unit
                LotwStatus.SIGNED, LotwStatus.UPLOADING -> Unit
                LotwStatus.ACCEPTED, LotwStatus.CONFIRMED -> error("签名文件包含已受理 QSO")
            }
            val refreshed = requireNotNull(qsoRepository.findByStableId(stored.stableId))
            if (refreshed.lotwStatus == LotwStatus.PENDING_SIGN) {
                qsoRepository.updateLotwStatus(
                    listOf(refreshed.stableId),
                    LotwStatus.SIGNED,
                    updatedUtcMillis = nowMillis(),
                )
            }
        }

        val now = nowMillis()
        val job = LotwUploadJobEntity(
            idempotencyKey = artifact.sha256,
            createdUtcMillis = now,
            updatedUtcMillis = now,
            state = LotwJobState.SIGNED.name,
            attemptCount = 0,
            lastError = null,
            signedArtifactPath = artifact.file.absolutePath,
            signedArtifactSha256 = artifact.sha256,
            qsoStableIds = records.map { it.stableId }.distinct().joinToString("\n"),
            responseMessage = null,
            nextAttemptUtcMillis = now,
        )
        val inserted = uploadDao.enqueue(job)
        val saved = if (inserted > 0) {
            requireNotNull(uploadDao.findById(inserted))
        } else {
            requireNotNull(uploadDao.findByIdempotencyKey(artifact.sha256))
        }
        scheduler.schedule(saved.id)
        return LotwQueueResult(saved.id, artifact.sha256, records.size, inserted <= 0)
    }
}

fun LotwUploadJobEntity.decodeStableIds(): List<String> =
    qsoStableIds.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
