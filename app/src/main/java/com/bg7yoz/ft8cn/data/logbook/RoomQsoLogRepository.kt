package com.bg7yoz.ft8cn.data.logbook

import com.bg7yoz.ft8cn.core.model.FtxMode
import com.bg7yoz.ft8cn.data.local.QsoDao
import com.bg7yoz.ft8cn.data.local.QsoEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomQsoLogRepository(private val qsoDao: QsoDao) : QsoLogRepository {
    override fun observeRecent(limit: Int): Flow<List<QsoRecord>> {
        require(limit in 1..1_000) { "查询数量必须在 1..1000" }
        return qsoDao.observeRecent(limit).map { records -> records.map { it.toRecord() } }
    }

    override suspend fun upsert(record: QsoRecord): Long = qsoDao.upsert(record.toEntity())

    override suspend fun findByStableId(stableId: String): QsoRecord? =
        qsoDao.findByStableId(stableId)?.toRecord()

    private fun QsoRecord.toEntity() = QsoEntity(
        id = id,
        stableId = stableId,
        startedUtcMillis = startedUtcMillis,
        endedUtcMillis = endedUtcMillis,
        mode = mode.name,
        submode = null,
        stationCall = stationCall,
        stationGrid = stationGrid,
        dxCall = dxCall,
        dxGrid = dxGrid,
        frequencyHz = frequencyHz,
        reportSent = reportSent,
        reportReceived = reportReceived,
        propagationMode = null,
        satelliteName = null,
    )

    private fun QsoEntity.toRecord() = QsoRecord(
        id = id,
        stableId = stableId,
        startedUtcMillis = startedUtcMillis,
        endedUtcMillis = endedUtcMillis,
        mode = FtxMode.valueOf(mode),
        stationCall = stationCall,
        stationGrid = stationGrid,
        dxCall = dxCall,
        dxGrid = dxGrid,
        frequencyHz = frequencyHz,
        reportSent = reportSent,
        reportReceived = reportReceived,
    )
}
