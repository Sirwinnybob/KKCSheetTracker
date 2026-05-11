package com.kkc.sheettracker.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val SYNCTHING_DATASTORE_NAME = "syncthing_settings"

private val Context.syncthingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SYNCTHING_DATASTORE_NAME
)

private object SyncthingPreferenceKeys {
    val apiKey = stringPreferencesKey("syncthing_api_key")
}

class DataStoreSyncthingPreferencesStore(
    private val dataStore: DataStore<Preferences>
) : SyncthingPreferencesStore {

    override val settings: Flow<SyncthingUserSettings> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { preferences ->
            SyncthingUserSettings(
                apiKey = preferences[SyncthingPreferenceKeys.apiKey].orEmpty()
            )
        }

    override suspend fun saveApiKey(apiKey: String) {
        dataStore.edit { preferences ->
            preferences[SyncthingPreferenceKeys.apiKey] = apiKey.trim()
        }
    }

    companion object {
        fun create(context: Context): DataStoreSyncthingPreferencesStore {
            return DataStoreSyncthingPreferencesStore(context.syncthingDataStore)
        }
    }
}
