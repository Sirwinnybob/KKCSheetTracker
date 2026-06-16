package com.kkc.sheettracker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.timecardDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "timeclock_config"
)

private const val DEFAULT_HUB_IP = "192.168.1.15"

private object TimecardConfigKeys {
    val serverIp = stringPreferencesKey("server_ip")
    val cachedUrl = stringPreferencesKey("cached_server_url")
}

class TimecardServerConfig(
    private val dataStore: DataStore<Preferences>
) {

    val serverIpFlow: Flow<String?> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { prefs -> prefs[TimecardConfigKeys.serverIp]?.takeIf { it.isNotBlank() } ?: DEFAULT_HUB_IP }

    val cachedUrlFlow: Flow<String?> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { prefs -> prefs[TimecardConfigKeys.cachedUrl] }

    suspend fun getManualIp(): String? =
        dataStore.data
            .map { prefs -> prefs[TimecardConfigKeys.serverIp]?.takeIf { it.isNotBlank() } ?: DEFAULT_HUB_IP }
            .first()

    suspend fun setManualIp(ip: String?) {
        dataStore.edit { prefs ->
            if (ip.isNullOrBlank()) {
                prefs.remove(TimecardConfigKeys.serverIp)
            } else {
                prefs[TimecardConfigKeys.serverIp] = ip.trim()
            }
        }
    }

    suspend fun getCachedUrl(): String? =
        dataStore.data
            .map { prefs -> prefs[TimecardConfigKeys.cachedUrl] }
            .first()

    suspend fun setCachedUrl(url: String?) {
        dataStore.edit { prefs ->
            if (url.isNullOrBlank()) {
                prefs.remove(TimecardConfigKeys.cachedUrl)
            } else {
                prefs[TimecardConfigKeys.cachedUrl] = url
            }
        }
    }

    companion object {
        fun create(context: Context): TimecardServerConfig =
            TimecardServerConfig(context.timecardDataStore)
    }
}
