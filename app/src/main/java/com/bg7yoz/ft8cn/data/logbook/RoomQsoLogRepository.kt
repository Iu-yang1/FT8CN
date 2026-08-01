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

    override suspend fun listAll(): List<QsoRecord> = qsoDao.listAll().map { it.toRecord() }

    override suspend fun listExportPage(offset: Int, limit: Int): List<QsoRecord> {
        require(offset >= 0) { "分页偏移不能为负数" }
        require(limit in 1..1_000) { "分页数量必须在 1..1000" }
        return qsoDao.listExportPage(offset, limit).map { it.toRecord() }
    }

    override suspend fun upsert(record: QsoRecord): Long {
        val current = qsoDao.findByStableId(record.stableId)
        val merged = record.copy(
            id = current?.id ?: record.id,
            lotwStatus = current?.lotwStatus?.let(LotwStatus::valueOf) ?: record.lotwStatus,
            lotwLastError = current?.lotwLastError ?: record.lotwLastError,
        )
        return qsoDao.upsert(merged.toEntity())
    }

    override suspend fun findByStableId(stableId: String): QsoRecord? =
        qsoDao.findByStableId(stableId)?.toRecord()

    override suspend fun delete(id: Long): Int = qsoDao.delete(id)

    override suspend fun updateLotwStatus(
        stableIds: Collection<String>,
        status: LotwStatus,
        error: String?,
        updatedUtcMillis: Long,
    ) {
        stableIds.distinct().forEach { stableId ->
            val current = qsoDao.findByStableId(stableId) ?: return@forEach
            val currentStatus = LotwStatus.valueOf(current.lotwStatus)
            require(currentStatus.canTransitionTo(status)) {
                "非法 LoTW 状态迁移：$currentStatus -> $status"
            }
            qsoDao.updateLotwStatus(stableId, status.name, error, updatedUtcMillis)
        }
    }

    private fun QsoRecord.toEntity() = QsoEntity(
        id = id,
        stableId = stableId,
        startedUtcMillis = startedUtcMillis,
        endedUtcMillis = endedUtcMillis,
        mode = mode.name,
        submode = submode,
        stationCall = stationCall,
        stationGrid = stationGrid,
        dxCall = dxCall,
        dxGrid = dxGrid,
        frequencyHz = frequencyHz,
        reportSent = reportSent,
        reportReceived = reportReceived,
        lotwStatus = lotwStatus.name,
        propagationMode = propagationMode,
        satelliteName = satelliteName,
        satelliteMode = satelliteMode,
        lotwLastError = lotwLastError,
        updatedUtcMillis = updatedUtcMillis,
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
        submode = submode,
        propagationMode = propagationMode,
        satelliteName = satelliteName,
        satelliteMode = satelliteMode,
        lotwStatus = runCatching { LotwStatus.valueOf(lotwStatus) }.getOrDefault(LotwStatus.LOCAL),
        lotwLastError = lotwLastError,
        updatedUtcMillis = updatedUtcMillis,
    )
}
