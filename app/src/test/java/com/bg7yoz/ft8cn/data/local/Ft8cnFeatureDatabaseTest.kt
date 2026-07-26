package com.bg7yoz.ft8cn.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Ft8cnFeatureDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "feature-migration-test.db"

    @After
    fun cleanDatabase() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationOneToTwoAddsLotwStateWithoutLosingQso() {
        val versionOne = helper(version = 1, onCreate = { database ->
            database.execSQL(
                """
                CREATE TABLE qso_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    stableId TEXT NOT NULL,
                    startedUtcMillis INTEGER NOT NULL,
                    endedUtcMillis INTEGER NOT NULL,
                    mode TEXT NOT NULL,
                    submode TEXT,
                    stationCall TEXT NOT NULL,
                    stationGrid TEXT NOT NULL,
                    dxCall TEXT NOT NULL,
                    dxGrid TEXT NOT NULL,
                    frequencyHz INTEGER NOT NULL,
                    reportSent TEXT NOT NULL,
                    reportReceived TEXT NOT NULL,
                    propagationMode TEXT,
                    satelliteName TEXT
                )
                """.trimIndent(),
            )
        })
        versionOne.writableDatabase.execSQL(
            """
            INSERT INTO qso_records (
                stableId, startedUtcMillis, endedUtcMillis, mode, submode,
                stationCall, stationGrid, dxCall, dxGrid, frequencyHz,
                reportSent, reportReceived, propagationMode, satelliteName
            ) VALUES ('qso-1', 1, 2, 'FT8', NULL, 'BG7YOZ', 'OL79',
                'JA6RJK', 'PM53', 14074000, '-10', '-12', NULL, NULL)
            """.trimIndent(),
        )
        versionOne.close()

        val versionTwo = helper(version = 2, onCreate = {}) { database, oldVersion, newVersion ->
            assertEquals(1, oldVersion)
            assertEquals(2, newVersion)
            Ft8cnFeatureDatabase.MIGRATION_1_2.migrate(database)
        }
        val cursor = versionTwo.writableDatabase.query(
            "SELECT stableId, lotwStatus FROM qso_records WHERE stableId = 'qso-1'",
        )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("qso-1", it.getString(0))
            assertEquals("LOCAL", it.getString(1))
        }
        versionTwo.close()
    }

    @Test
    fun currentRoomSchemaReadsAndWritesQso() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            Ft8cnFeatureDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val id = database.qsoDao().upsert(
                QsoEntity(
                    stableId = "qso-current",
                    startedUtcMillis = 1,
                    endedUtcMillis = 2,
                    mode = "FT4",
                    submode = null,
                    stationCall = "BG7YOZ",
                    stationGrid = "OL79",
                    dxCall = "JA6RJK",
                    dxGrid = "PM53",
                    frequencyHz = 14_080_000,
                    reportSent = "-08",
                    reportReceived = "-10",
                    propagationMode = null,
                    satelliteName = null,
                ),
            )
            assertTrue(id > 0)
            assertEquals("LOCAL", database.qsoDao().findByStableId("qso-current")?.lotwStatus)
        } finally {
            database.close()
        }
    }

    private fun helper(
        version: Int,
        onCreate: (SupportSQLiteDatabase) -> Unit,
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> },
    ): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(database: SupportSQLiteDatabase) = onCreate(database)

            override fun onUpgrade(database: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                onUpgrade(database, oldVersion, newVersion)
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(callback)
                .build(),
        )
    }
}
