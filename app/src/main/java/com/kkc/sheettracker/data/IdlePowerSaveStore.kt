package com.kkc.sheettracker.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.idlePowerSaveDataStore by preferencesDataStore(name = "screensaver_settings")

data class IdlePowerSaveConfig(
    val enabled: Boolean = true,
    val idleTimeoutSeconds: Int = 300,
    val syncthingPauseTimeoutSeconds: Int = 1800
)

internal const val MIN_IDLE_TIMEOUT_SECONDS = 5

internal fun sanitizeIdlePowerSaveConfig(config: IdlePowerSaveConfig): IdlePowerSaveConfig {
    return config.copy(
        idleTimeoutSeconds = config.idleTimeoutSeconds.coerceAtLeast(MIN_IDLE_TIMEOUT_SECONDS),
        syncthingPauseTimeoutSeconds = config.syncthingPauseTimeoutSeconds
            .coerceAtLeast(MIN_IDLE_TIMEOUT_SECONDS)
    )
}

class IdlePowerSaveStore(private val context: Context) {

    private val dataStore = context.idlePowerSaveDataStore

    val configFlow: Flow<IdlePowerSaveConfig> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            sanitizeIdlePowerSaveConfig(IdlePowerSaveConfig(
                enabled = prefs[KEY_ENABLED] ?: true,
                idleTimeoutSeconds = prefs[KEY_IDLE_TIMEOUT_SECONDS] ?: 300,
                syncthingPauseTimeoutSeconds = prefs[KEY_SYNCTHING_PAUSE_TIMEOUT_SECONDS] ?: 1800
            ))
        }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_ENABLED] = enabled }
    }

    suspend fun setIdleTimeoutSeconds(seconds: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_IDLE_TIMEOUT_SECONDS] = seconds.coerceAtLeast(MIN_IDLE_TIMEOUT_SECONDS)
        }
    }

    suspend fun setSyncthingPauseTimeoutSeconds(seconds: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_SYNCTHING_PAUSE_TIMEOUT_SECONDS] = seconds.coerceAtLeast(MIN_IDLE_TIMEOUT_SECONDS)
        }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        private val KEY_IDLE_TIMEOUT_SECONDS = intPreferencesKey("idle_timeout_seconds")
        private val KEY_SYNCTHING_PAUSE_TIMEOUT_SECONDS = intPreferencesKey("syncthing_pause_timeout_seconds")
    }
}
