package com.bg7yoz.ft8cn.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureSettingsStoreTest {
    @Test
    fun migrationAndUpdatesPersistSchemaAndSmallUiState() = runBlocking {
        val migrated = FeatureSettingsMigration.migrate(
            mutablePreferencesOf(),
        )
        val dataStore = FakePreferencesDataStore(migrated)
        val store = FeatureSettingsStore(dataStore)

        val initial = store.state.first()
        assertEquals(FeatureSettingsStore.CURRENT_SCHEMA_VERSION, initial.schemaVersion)
        assertTrue(initial.gnssTimeEnabled)
        assertEquals("decode", initial.selectedDestination)

        store.setSelectedDestination("satellite")
        store.setGnssTimeEnabled(false)
        store.setRigctldProfile("192.0.2.10", 4532)
        store.setEmeMode(true, 1)
        store.setQ65Configuration(4, 300)
        store.setEmeBaseFrequency(432_065_000L)
        val updated = store.state.first {
            it.selectedDestination == "satellite" && !it.gnssTimeEnabled
        }

        assertEquals("satellite", updated.selectedDestination)
        assertEquals("192.0.2.10", updated.rigctldHost)
        assertTrue(updated.emeModeEnabled)
        assertEquals(1, updated.previousFtxMode)
        assertEquals(4, updated.q65Submode)
        assertEquals(300, updated.q65TrPeriodSeconds)
        assertEquals(432_065_000L, updated.emeBaseFrequencyHz)
    }

    @Test
    fun normalFtxModeSurvivesUnrelatedUiStateUpdates() = runBlocking {
        val migrated = FeatureSettingsMigration.migrate(mutablePreferencesOf())
        val store = FeatureSettingsStore(FakePreferencesDataStore(migrated))

        store.setPreviousFtxMode(1)
        store.setSelectedDestination("settings")
        store.setGnssTimeEnabled(false)

        val updated = store.state.first { it.selectedDestination == "settings" }
        assertEquals(1, updated.previousFtxMode)
        assertEquals(false, updated.emeModeEnabled)
        assertEquals(false, updated.satelliteModeEnabled)
    }

    private class FakePreferencesDataStore(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
