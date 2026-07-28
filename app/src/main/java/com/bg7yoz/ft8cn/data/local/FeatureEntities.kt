package com.bg7yoz.ft8cn.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "qso_records",
    indices = [Index(value = ["stableId"], unique = true), Index(value = ["startedUtcMillis"])],
)
data class QsoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stableId: String,
    val startedUtcMillis: Long,
    val endedUtcMillis: Long,
    val mode: String,
    val submode: String?,
    val stationCall: String,
    val stationGrid: String,
    val dxCall: String,
    val dxGrid: String,
    val frequencyHz: Long,
    val reportSent: String,
    val reportReceived: String,
    val lotwStatus: String = "LOCAL",
    val propagationMode: String?,
    val satelliteName: String?,
)

@Entity(tableName = "station_profiles", indices = [Index(value = ["name"], unique = true)])
data class StationProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val callsign: String,
    val grid: String,
    val latitudeDegrees: Double?,
    val longitudeDegrees: Double?,
    val altitudeMeters: Double?,
)

@Entity(tableName = "radio_profiles", indices = [Index(value = ["name"], unique = true)])
data class RadioProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val modelId: Int,
    val transport: String,
    val endpoint: String,
    val baudRate: Int,
    val fakeItEnabled: Boolean,
)

@Entity(tableName = "automation_history", indices = [Index(value = ["utcMillis"])])
data class AutomationHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val utcMillis: Long,
    val mode: String,
    val previousState: String,
    val nextState: String,
    val reason: String,
    val targetCall: String,
)

@Entity(
    tableName = "lotw_upload_jobs",
    indices = [Index(value = ["idempotencyKey"], unique = true), Index(value = ["state"])],
)
data class LotwUploadJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val idempotencyKey: String,
    val createdUtcMillis: Long,
    val updatedUtcMillis: Long,
    val state: String,
    val attemptCount: Int,
    val lastError: String?,
)

@Entity(tableName = "satellites", indices = [Index(value = ["catalogNumber"], unique = true)])
data class SatelliteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val catalogNumber: Int,
    val name: String,
    val favorite: Boolean,
    val updatedUtcMillis: Long,
)

@Entity(
    tableName = "tle_records",
    indices = [Index(value = ["satelliteCatalogNumber", "epochUtcMillis"], unique = true)],
)
data class TleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val satelliteCatalogNumber: Int,
    val epochUtcMillis: Long,
    val line1: String,
    val line2: String,
    val source: String,
    val fetchedUtcMillis: Long,
)

@Entity(
    tableName = "transponders",
    indices = [Index(value = ["satelliteCatalogNumber", "name"], unique = true)],
)
data class TransponderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val satelliteCatalogNumber: Int,
    val name: String,
    val uplinkLowHz: Long?,
    val uplinkHighHz: Long?,
    val downlinkLowHz: Long?,
    val downlinkHighHz: Long?,
    val mode: String,
    val inverted: Boolean,
)

@Entity(tableName = "satellite_source_metadata")
data class SatelliteSourceMetadataEntity(
    @PrimaryKey val sourceKey: String,
    val etag: String?,
    val lastModified: String?,
    val lastAttemptUtcMillis: Long,
    val lastSuccessUtcMillis: Long,
    val nextEligibleUtcMillis: Long,
    val payloadSha256: String?,
    val lastError: String?,
)
