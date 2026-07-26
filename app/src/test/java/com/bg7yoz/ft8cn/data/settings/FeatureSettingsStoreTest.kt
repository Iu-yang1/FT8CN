package com.bg7yoz.ft8cn.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureSettingsStoreTest {
    @Test
    fun migrationAndUpdatesPersistSchemaAndSmallUiState() = runBlocking {
        val migrated = FeatureSettingsMigration.migrate(
            mutablePreferencesOf(FeatureSettingsStore.MODERN_UI_ENABLED to false),
        )
        val dataStore = FakePreferencesDataStore(migrated)
        val store = FeatureSettingsStore(dataStore)

        val initial = store.state.first()
        assertEquals(FeatureSettingsStore.CURRENT_SCHEMA_VERSION, initial.schemaVersion)
        assertFalse(initial.modernUiEnabled)

        store.setModernUiEnabled(true)
        store.setSelectedDestination("satellite")
        val updated = store.state.first { it.modernUiEnabled }

        assertTrue(updated.modernUiEnabled)
        assertEquals("satellite", updated.selectedDestination)
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
