package com.bg7yoz.ft8cn.data.logbook

import com.bg7yoz.ft8cn.core.model.FtxMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

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
)

interface QsoLogRepository {
    fun observeRecent(limit: Int): Flow<List<QsoRecord>>
    suspend fun upsert(record: QsoRecord): Long
    suspend fun findByStableId(stableId: String): QsoRecord?
}

class FakeQsoLogRepository : QsoLogRepository {
    private val records = mutableListOf<QsoRecord>()
    private val state = MutableStateFlow<List<QsoRecord>>(emptyList())

    override fun observeRecent(limit: Int): Flow<List<QsoRecord>> = state

    override suspend fun upsert(record: QsoRecord): Long {
        val index = records.indexOfFirst { it.stableId == record.stableId }
        val saved = record.copy(id = if (index >= 0) records[index].id else (records.size + 1).toLong())
        if (index >= 0) records[index] = saved else records += saved
        state.value = records.sortedByDescending { it.startedUtcMillis }
        return saved.id
    }

    override suspend fun findByStableId(stableId: String): QsoRecord? =
        records.firstOrNull { it.stableId == stableId }
}
