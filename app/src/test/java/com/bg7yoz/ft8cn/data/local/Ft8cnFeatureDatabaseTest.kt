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

    @Test
    fun migrationTwoToThreeAddsSatelliteCacheMetadata() {
        val versionTwo = helper(version = 2, onCreate = {})
        versionTwo.writableDatabase.execSQL("CREATE TABLE marker (value INTEGER NOT NULL)")
        versionTwo.writableDatabase.execSQL("INSERT INTO marker VALUES (7)")
        versionTwo.close()

        val versionThree = helper(version = 3, onCreate = {}) { database, oldVersion, newVersion ->
            assertEquals(2, oldVersion)
            assertEquals(3, newVersion)
            Ft8cnFeatureDatabase.MIGRATION_2_3.migrate(database)
        }
        versionThree.writableDatabase.query("SELECT value FROM marker").use {
            assertTrue(it.moveToFirst())
            assertEquals(7, it.getInt(0))
        }
        versionThree.writableDatabase.query("PRAGMA table_info(satellite_source_metadata)").use {
            val columns = mutableSetOf<String>()
            while (it.moveToNext()) columns += it.getString(1)
            assertTrue("sourceKey" in columns)
            assertTrue("payloadSha256" in columns)
            assertTrue("nextEligibleUtcMillis" in columns)
        }
        versionThree.close()
    }

    @Test
    fun migrationThreeToFourAddsAuditableLotwFieldsWithoutLosingRows() {
        val versionThree = helper(version = 3, onCreate = { database ->
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
                    lotwStatus TEXT NOT NULL,
                    propagationMode TEXT,
                    satelliteName TEXT
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE lotw_upload_jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    idempotencyKey TEXT NOT NULL,
                    createdUtcMillis INTEGER NOT NULL,
                    updatedUtcMillis INTEGER NOT NULL,
                    state TEXT NOT NULL,
                    attemptCount INTEGER NOT NULL,
                    lastError TEXT
                )
                """.trimIndent(),
            )
            database.execSQL(
                "INSERT INTO qso_records VALUES (1, 'keep-me', 1, 2, 'FT8', NULL, " +
                    "'BG7YOZ', 'OL79', 'JA6RJK', 'PM53', 14074000, '-10', '-12', " +
                    "'LOCAL', NULL, NULL)",
            )
            database.execSQL(
                "INSERT INTO lotw_upload_jobs VALUES (1, 'hash', 1, 1, 'PENDING_SIGN', 0, NULL)",
            )
        })
        versionThree.writableDatabase
        versionThree.close()

        val versionFour = helper(version = 4, onCreate = {}) { database, oldVersion, newVersion ->
            assertEquals(3, oldVersion)
            assertEquals(4, newVersion)
            Ft8cnFeatureDatabase.MIGRATION_3_4.migrate(database)
        }
        versionFour.writableDatabase.query(
            "SELECT stableId, satelliteMode, updatedUtcMillis FROM qso_records WHERE id = 1",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("keep-me", it.getString(0))
            assertTrue(it.isNull(1))
            assertEquals(0, it.getLong(2))
        }
        versionFour.writableDatabase.query(
            "SELECT idempotencyKey, qsoStableIds, nextAttemptUtcMillis FROM lotw_upload_jobs WHERE id = 1",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("hash", it.getString(0))
            assertEquals("", it.getString(1))
            assertEquals(0, it.getLong(2))
        }
        versionFour.close()
    }

    @Test
    fun catalogRefreshPreservesFavoriteFlag() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            context,
            Ft8cnFeatureDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = database.satelliteDao()
            dao.upsertSatellite(SatelliteEntity(catalogNumber = 5, name = "Old", favorite = true, updatedUtcMillis = 1))
            dao.saveTleCatalog(
                satellites = listOf(SatelliteEntity(catalogNumber = 5, name = "New", favorite = false, updatedUtcMillis = 2)),
                tles = listOf(TleEntity(
                    satelliteCatalogNumber = 5,
                    epochUtcMillis = 2,
                    line1 = "line1",
                    line2 = "line2",
                    source = "test",
                    fetchedUtcMillis = 2,
                )),
                metadata = SatelliteSourceMetadataEntity(
                    sourceKey = "test",
                    etag = null,
                    lastModified = null,
                    lastAttemptUtcMillis = 2,
                    lastSuccessUtcMillis = 2,
                    nextEligibleUtcMillis = 3,
                    payloadSha256 = "abc",
                    lastError = null,
                ),
            )
            val stored = requireNotNull(dao.findSatellite(5))
            assertEquals("New", stored.name)
            assertTrue(stored.favorite)
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
