package com.bg7yoz.ft8cn.data.logbook

import com.bg7yoz.ft8cn.core.model.FtxMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

enum class LotwStatus {
    LOCAL,
    PENDING_SIGN,
    SIGNED,
    UPLOADING,
    ACCEPTED,
    REJECTED,
    CONFIRMED;

    fun canTransitionTo(next: LotwStatus): Boolean = next == this || when (this) {
        LOCAL -> next == PENDING_SIGN
        PENDING_SIGN -> next == SIGNED || next == LOCAL
        SIGNED -> next == UPLOADING || next == PENDING_SIGN
        UPLOADING -> next == ACCEPTED || next == REJECTED || next == SIGNED
        ACCEPTED -> next == CONFIRMED
        REJECTED -> next == PENDING_SIGN
        CONFIRMED -> false
    }
}

data class QsoRecord(
    val id: Long = 0,
    val stableId: String,
    val startedUtcMillis: Long,
    val endedUtcMillis: Long,
    val mode: FtxMode,
    val stationCall: String,
    val stationGrid: String,
    val dxCall: String,
    val dxGrid: String,
    val frequencyHz: Long,
    val reportSent: String,
    val reportReceived: String,
    val submode: String? = null,
    val propagationMode: String? = null,
    val satelliteName: String? = null,
    val satelliteMode: String? = null,
    val lotwStatus: LotwStatus = LotwStatus.LOCAL,
    val lotwLastError: String? = null,
    val updatedUtcMillis: Long = endedUtcMillis,
)

interface QsoLogRepository {
    fun observeRecent(limit: Int): Flow<List<QsoRecord>>
    suspend fun listAll(): List<QsoRecord>
    suspend fun listExportPage(offset: Int, limit: Int): List<QsoRecord>
    suspend fun upsert(record: QsoRecord): Long
    suspend fun findByStableId(stableId: String): QsoRecord?
    suspend fun delete(id: Long): Int
    suspend fun updateLotwStatus(
        stableIds: Collection<String>,
        status: LotwStatus,
        error: String? = null,
        updatedUtcMillis: Long = System.currentTimeMillis(),
    )
}

class FakeQsoLogRepository : QsoLogRepository {
    private val records = mutableListOf<QsoRecord>()
    private val state = MutableStateFlow<List<QsoRecord>>(emptyList())

    override fun observeRecent(limit: Int): Flow<List<QsoRecord>> {
        require(limit in 1..1_000) { "查询数量必须在 1..1000 范围内" }
        return state.map { it.take(limit) }
    }

    override suspend fun listAll(): List<QsoRecord> = records.sortedByDescending { it.startedUtcMillis }

    override suspend fun listExportPage(offset: Int, limit: Int): List<QsoRecord> {
        require(offset >= 0) { "分页偏移不能为负数" }
        require(limit in 1..1_000) { "分页数量必须在 1..1000 范围内" }
        return records.sortedWith(compareBy<QsoRecord> { it.startedUtcMillis }.thenBy { it.stableId })
            .drop(offset)
            .take(limit)
    }

    override suspend fun upsert(record: QsoRecord): Long {
        val index = records.indexOfFirst { it.stableId == record.stableId }
        val saved = record.copy(id = if (index >= 0) records[index].id else (records.size + 1).toLong())
        if (index >= 0) records[index] = saved else records += saved
        state.value = records.sortedByDescending { it.startedUtcMillis }
        return saved.id
    }

    override suspend fun findByStableId(stableId: String): QsoRecord? =
        records.firstOrNull { it.stableId == stableId }

    override suspend fun delete(id: Long): Int {
        val removed = records.removeAll { it.id == id }
        state.value = records.sortedByDescending { it.startedUtcMillis }
        return if (removed) 1 else 0
    }

    override suspend fun updateLotwStatus(
        stableIds: Collection<String>,
        status: LotwStatus,
        error: String?,
        updatedUtcMillis: Long,
    ) {
        val wanted = stableIds.toSet()
        records.replaceAll { record ->
            if (record.stableId !in wanted) {
                record
            } else {
                require(record.lotwStatus.canTransitionTo(status)) {
                    "非法 LoTW 状态迁移：${record.lotwStatus} -> $status"
                }
                record.copy(lotwStatus = status, lotwLastError = error, updatedUtcMillis = updatedUtcMillis)
            }
        }
        state.value = records.sortedByDescending { it.startedUtcMillis }
    }
}
