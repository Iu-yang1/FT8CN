package com.bg7yoz.ft8cn.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        QsoEntity::class,
        StationProfileEntity::class,
        RadioProfileEntity::class,
        AutomationHistoryEntity::class,
        LotwUploadJobEntity::class,
        SatelliteEntity::class,
        TleEntity::class,
        TransponderEntity::class,
        SatelliteSourceMetadataEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class Ft8cnFeatureDatabase : RoomDatabase() {
    abstract fun qsoDao(): QsoDao
    abstract fun profileDao(): ProfileDao
    abstract fun automationDao(): AutomationDao
    abstract fun lotwUploadDao(): LotwUploadDao
    abstract fun satelliteDao(): SatelliteDao

    companion object {
        const val DATABASE_NAME = "ft8cn_features.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE qso_records ADD COLUMN lotwStatus TEXT NOT NULL DEFAULT 'LOCAL'",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS satellite_source_metadata (
                        sourceKey TEXT NOT NULL PRIMARY KEY,
                        etag TEXT,
                        lastModified TEXT,
                        lastAttemptUtcMillis INTEGER NOT NULL,
                        lastSuccessUtcMillis INTEGER NOT NULL,
                        nextEligibleUtcMillis INTEGER NOT NULL,
                        payloadSha256 TEXT,
                        lastError TEXT
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE qso_records ADD COLUMN satelliteMode TEXT")
                database.execSQL("ALTER TABLE qso_records ADD COLUMN lotwLastError TEXT")
                database.execSQL(
                    "ALTER TABLE qso_records ADD COLUMN updatedUtcMillis INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL("ALTER TABLE lotw_upload_jobs ADD COLUMN signedArtifactPath TEXT")
                database.execSQL("ALTER TABLE lotw_upload_jobs ADD COLUMN signedArtifactSha256 TEXT")
                database.execSQL(
                    "ALTER TABLE lotw_upload_jobs ADD COLUMN qsoStableIds TEXT NOT NULL DEFAULT ''",
                )
                database.execSQL("ALTER TABLE lotw_upload_jobs ADD COLUMN responseMessage TEXT")
                database.execSQL(
                    "ALTER TABLE lotw_upload_jobs ADD COLUMN nextAttemptUtcMillis INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun create(context: Context): Ft8cnFeatureDatabase = Room.databaseBuilder(
            context.applicationContext,
            Ft8cnFeatureDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
