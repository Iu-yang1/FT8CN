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
    ],
    version = 2,
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

        fun create(context: Context): Ft8cnFeatureDatabase = Room.databaseBuilder(
            context.applicationContext,
            Ft8cnFeatureDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(MIGRATION_1_2).build()
    }
}
