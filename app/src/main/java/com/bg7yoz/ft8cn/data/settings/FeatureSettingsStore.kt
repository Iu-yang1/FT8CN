package com.bg7yoz.ft8cn.data.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class FeatureSettings(
    val schemaVersion: Int = FeatureSettingsStore.CURRENT_SCHEMA_VERSION,
    val modernUiEnabled: Boolean = false,
    val selectedDestination: String = "call",
)

class FeatureSettingsStore(private val dataStore: DataStore<Preferences>) {
    val state: Flow<FeatureSettings> = dataStore.data.map { preferences ->
        FeatureSettings(
            schemaVersion = preferences[SCHEMA_VERSION] ?: 0,
            modernUiEnabled = preferences[MODERN_UI_ENABLED] ?: false,
            selectedDestination = preferences[SELECTED_DESTINATION] ?: "call",
        )
    }

    suspend fun setModernUiEnabled(enabled: Boolean) {
        dataStore.edit { it[MODERN_UI_ENABLED] = enabled }
    }

    suspend fun setSelectedDestination(route: String) {
        require(route.isNotBlank()) { "路由不能为空" }
        dataStore.edit { it[SELECTED_DESTINATION] = route }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val FILE_NAME = "feature_settings.preferences_pb"

        internal val SCHEMA_VERSION = intPreferencesKey("schema_version")
        internal val MODERN_UI_ENABLED = booleanPreferencesKey("modern_ui_enabled")
        internal val SELECTED_DESTINATION = stringPreferencesKey("selected_destination")

        fun create(context: Context, scope: CoroutineScope): FeatureSettingsStore {
            val dataStore = PreferenceDataStoreFactory.create(
                migrations = listOf(FeatureSettingsMigration),
                scope = scope,
                produceFile = { context.applicationContext.preferencesDataStoreFile(FILE_NAME) },
            )
            return FeatureSettingsStore(dataStore)
        }
    }
}

object FeatureSettingsMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[FeatureSettingsStore.SCHEMA_VERSION] ?: 0) < FeatureSettingsStore.CURRENT_SCHEMA_VERSION

    override suspend fun migrate(currentData: Preferences): Preferences {
        val migrated = mutablePreferencesOf()
        currentData[FeatureSettingsStore.MODERN_UI_ENABLED]?.let {
            migrated[FeatureSettingsStore.MODERN_UI_ENABLED] = it
        }
        currentData[FeatureSettingsStore.SELECTED_DESTINATION]?.let {
            migrated[FeatureSettingsStore.SELECTED_DESTINATION] = it
        }
        migrated[FeatureSettingsStore.SCHEMA_VERSION] = FeatureSettingsStore.CURRENT_SCHEMA_VERSION
        if (migrated[FeatureSettingsStore.MODERN_UI_ENABLED] == null) {
            migrated[FeatureSettingsStore.MODERN_UI_ENABLED] = false
        }
        if (migrated[FeatureSettingsStore.SELECTED_DESTINATION] == null) {
            migrated[FeatureSettingsStore.SELECTED_DESTINATION] = "call"
        }
        return migrated
    }

    override suspend fun cleanUp() = Unit
}
