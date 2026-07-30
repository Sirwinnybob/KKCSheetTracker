package com.kkc.sheettracker.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.scannerSettingsDataStore by preferencesDataStore(name = "scanner_settings")

class ScannerSettingsStore(private val context: Context) {

    private val dataStore = context.scannerSettingsDataStore

    val torchOnFlow: Flow<Boolean> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[KEY_TORCH_ON] ?: true }

    suspend fun setTorchOn(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_TORCH_ON] = enabled }
    }

    companion object {
        private val KEY_TORCH_ON = booleanPreferencesKey("torch_on")
    }
}
