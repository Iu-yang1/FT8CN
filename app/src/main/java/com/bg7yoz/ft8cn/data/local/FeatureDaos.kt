package com.bg7yoz.ft8cn.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface QsoDao {
    @Query("SELECT * FROM qso_records ORDER BY startedUtcMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<QsoEntity>>

    @Query("SELECT * FROM qso_records WHERE stableId = :stableId LIMIT 1")
    suspend fun findByStableId(stableId: String): QsoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: QsoEntity): Long
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM station_profiles ORDER BY name")
    fun observeStations(): Flow<List<StationProfileEntity>>

    @Query("SELECT * FROM radio_profiles ORDER BY name")
    fun observeRadios(): Flow<List<RadioProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStation(profile: StationProfileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRadio(profile: RadioProfileEntity): Long
}

@Dao
interface AutomationDao {
    @Insert
    suspend fun append(history: AutomationHistoryEntity): Long

    @Query("SELECT * FROM automation_history ORDER BY utcMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AutomationHistoryEntity>>
}

@Dao
interface LotwUploadDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(job: LotwUploadJobEntity): Long

    @Query("SELECT * FROM lotw_upload_jobs WHERE state IN ('PENDING_SIGN', 'SIGNED', 'UPLOADING') ORDER BY createdUtcMillis")
    suspend fun pending(): List<LotwUploadJobEntity>
}

@Dao
interface SatelliteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSatellite(satellite: SatelliteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTle(tle: TleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransponder(transponder: TransponderEntity): Long

    @Query("SELECT * FROM satellites ORDER BY favorite DESC, name")
    fun observeSatellites(): Flow<List<SatelliteEntity>>

    @Query("SELECT * FROM satellites WHERE catalogNumber = :catalogNumber LIMIT 1")
    suspend fun findSatellite(catalogNumber: Int): SatelliteEntity?

    @Query("UPDATE satellites SET favorite = :favorite WHERE catalogNumber = :catalogNumber")
    suspend fun setFavorite(catalogNumber: Int, favorite: Boolean): Int

    @Query(
        "SELECT * FROM tle_records WHERE satelliteCatalogNumber = :catalogNumber " +
            "ORDER BY epochUtcMillis DESC LIMIT 1",
    )
    suspend fun latestTle(catalogNumber: Int): TleEntity?

    @Query("SELECT * FROM transponders WHERE satelliteCatalogNumber = :catalogNumber ORDER BY name")
    suspend fun transponders(catalogNumber: Int): List<TransponderEntity>

    @Query("DELETE FROM transponders WHERE satelliteCatalogNumber = :catalogNumber")
    suspend fun deleteTransponders(catalogNumber: Int)

    @Query("SELECT * FROM satellite_source_metadata WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun sourceMetadata(sourceKey: String): SatelliteSourceMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSourceMetadata(metadata: SatelliteSourceMetadataEntity)

    /** 目录更新不得用 REPLACE 覆盖用户收藏状态。 */
    @Transaction
    suspend fun saveTleCatalog(
        satellites: List<SatelliteEntity>,
        tles: List<TleEntity>,
        metadata: SatelliteSourceMetadataEntity,
    ) {
        satellites.forEach { incoming ->
            val current = findSatellite(incoming.catalogNumber)
            upsertSatellite(incoming.copy(
                id = current?.id ?: 0,
                name = incoming.name.ifBlank { current?.name ?: "NORAD ${incoming.catalogNumber}" },
                favorite = current?.favorite ?: false,
            ))
        }
        tles.forEach { upsertTle(it) }
        upsertSourceMetadata(metadata)
    }

    @Transaction
    suspend fun replaceTransponders(
        catalogNumber: Int,
        transponders: List<TransponderEntity>,
        metadata: SatelliteSourceMetadataEntity,
    ) {
        deleteTransponders(catalogNumber)
        transponders.forEach { upsertTransponder(it) }
        upsertSourceMetadata(metadata)
    }
}
