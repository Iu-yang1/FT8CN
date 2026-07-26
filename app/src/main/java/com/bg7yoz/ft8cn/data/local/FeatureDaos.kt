package com.bg7yoz.ft8cn.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}
