package com.bg7yoz.ft8cn.data.logbook

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bg7yoz.ft8cn.core.FeatureAppGraph
import com.bg7yoz.ft8cn.data.local.LotwUploadDao
import com.bg7yoz.ft8cn.data.local.LotwUploadJobEntity
import java.io.File
import java.security.MessageDigest
import java.util.Locale

enum class LotwExecutionResult { SUCCESS, RETRY, FAILURE }

class LotwUploadCoordinator(
    private val uploadDao: LotwUploadDao,
    private val qsoRepository: QsoLogRepository,
    private val transport: LotwUploadTransport,
    private val privateRoot: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(jobId: Long): LotwExecutionResult {
        val job = uploadDao.findById(jobId) ?: return LotwExecutionResult.FAILURE
        val state = runCatching { LotwJobState.valueOf(job.state) }.getOrNull()
            ?: return LotwExecutionResult.FAILURE
        if (state != LotwJobState.SIGNED && state != LotwJobState.UPLOADING) {
            return if (state == LotwJobState.ACCEPTED || state == LotwJobState.REJECTED) {
                LotwExecutionResult.SUCCESS
            } else {
                LotwExecutionResult.FAILURE
            }
        }
        val artifact = job.signedArtifactPath?.let(::File) ?: return failPermanently(job, "缺少 TQ8 路径")
        val expectedSha = job.signedArtifactSha256 ?: return failPermanently(job, "缺少 TQ8 SHA256")
        if (!isPrivateArtifact(artifact) || !artifact.isFile) return failPermanently(job, "TQ8 路径越界或不存在")
        if (sha256(artifact) != expectedSha) return failPermanently(job, "TQ8 文件校验值变化")
        runCatching { Tq8StructureValidator.validate(artifact) }
            .onFailure { return failPermanently(job, it.message ?: "TQ8 结构无效") }

        val ids = job.decodeStableIds()
        val uploading = job.copy(
            state = LotwJobState.UPLOADING.name,
            updatedUtcMillis = nowMillis(),
            attemptCount = job.attemptCount + 1,
            lastError = null,
        )
        uploadDao.update(uploading)
        ids.forEach { transitionIfPossible(it, LotwStatus.UPLOADING, null) }

        return try {
            val response = transport.upload(artifact)
            val terminalState = if (response.accepted) LotwJobState.ACCEPTED else LotwJobState.REJECTED
            val qsoState = if (response.accepted) LotwStatus.ACCEPTED else LotwStatus.REJECTED
            uploadDao.update(uploading.copy(
                state = terminalState.name,
                updatedUtcMillis = nowMillis(),
                responseMessage = response.message,
                lastError = if (response.accepted) null else response.message,
            ))
            ids.forEach { transitionIfPossible(it, qsoState, if (response.accepted) null else response.message) }
            LotwExecutionResult.SUCCESS
        } catch (error: Exception) {
            val message = error.message.orEmpty().replace(Regex("[\\r\\n\\t]+"), " ").take(512)
            uploadDao.update(uploading.copy(
                state = LotwJobState.SIGNED.name,
                updatedUtcMillis = nowMillis(),
                lastError = message,
                nextAttemptUtcMillis = nowMillis() + retryDelayMillis(uploading.attemptCount),
            ))
            ids.forEach { transitionIfPossible(it, LotwStatus.SIGNED, message) }
            LotwExecutionResult.RETRY
        }
    }

    private suspend fun transitionIfPossible(stableId: String, target: LotwStatus, error: String?) {
        val current = qsoRepository.findByStableId(stableId) ?: return
        if (current.lotwStatus.canTransitionTo(target)) {
            qsoRepository.updateLotwStatus(listOf(stableId), target, error, nowMillis())
        }
    }

    private suspend fun failPermanently(job: LotwUploadJobEntity, reason: String): LotwExecutionResult {
        uploadDao.update(job.copy(
            state = LotwJobState.REJECTED.name,
            updatedUtcMillis = nowMillis(),
            lastError = reason.take(512),
        ))
        job.decodeStableIds().forEach { transitionIfPossible(it, LotwStatus.REJECTED, reason) }
        return LotwExecutionResult.FAILURE
    }

    private fun isPrivateArtifact(file: File): Boolean = runCatching {
        file.canonicalPath.startsWith(privateRoot.canonicalPath + File.separator)
    }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private fun retryDelayMillis(attempt: Int): Long =
        (10_000L shl (attempt.coerceIn(1, 12) - 1)).coerceAtMost(6L * 60L * 60L * 1_000L)
}

class LotwUploadWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getLong(KEY_JOB_ID, -1L)
        if (jobId <= 0) return Result.failure()
        val graph = FeatureAppGraph.from(applicationContext)
        val coordinator = LotwUploadCoordinator(
            graph.database.lotwUploadDao(),
            graph.qsoLogRepository,
            HttpsLotwUploadTransport(),
            applicationContext.noBackupFilesDir.resolve("lotw/signed"),
        )
        return when (coordinator.execute(jobId)) {
            LotwExecutionResult.SUCCESS -> Result.success()
            LotwExecutionResult.RETRY -> Result.retry()
            LotwExecutionResult.FAILURE -> Result.failure()
        }
    }

    companion object {
        const val KEY_JOB_ID = "lotw_job_id"
    }
}
