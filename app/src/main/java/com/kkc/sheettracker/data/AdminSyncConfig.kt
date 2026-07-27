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

private val Context.adminSyncDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "admin_sync_config"
)

// Hours Tracker's Docker container listens on 5002 internally but publishes the service on
// 47821 for LAN clients (docker-compose.yml: `47821:5002`).
private const val ADMIN_SYNC_PORT = 47821
private const val DEFAULT_ADMIN_SYNC_IP = "192.168.1.15"

private object AdminSyncConfigKeys {
    val serverIp = stringPreferencesKey("server_ip")
}

/** Pure so it's testable without a DataStore/Context. Null input or blank IP -> null URL. */
internal fun buildAdminSyncUrl(manualIp: String?): String? =
    manualIp?.trim()?.takeIf { it.isNotBlank() }?.let { "http://$it:$ADMIN_SYNC_PORT" }

/**
 * Manual-IP config for the Hours Tracker backend's direct-write admin-sync endpoints
 * (production order / job board / delivery schedule fast path). Unlike timeclock's
 * TimecardServerConfig, there is no mDNS auto-discovery here — Hours Tracker does not advertise
 * an mDNS service, so an admin must set this IP once in Settings for the fast path to be used.
 * Leaving it unset means AdminSyncClient calls are always skipped in favor of the existing
 * per-tablet request-file fallback (see ProductionOrderRequestStore, JobBoardRequestStore,
 * DeliveryScheduleRequestStore).
 */
class AdminSyncConfig(private val dataStore: DataStore<Preferences>) {

    val serverIpFlow: Flow<String?> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs -> prefs[AdminSyncConfigKeys.serverIp]?.takeIf { it.isNotBlank() } ?: DEFAULT_ADMIN_SYNC_IP }

    suspend fun getManualIp(): String? =
        dataStore.data
            .map { prefs -> prefs[AdminSyncConfigKeys.serverIp]?.takeIf { it.isNotBlank() } ?: DEFAULT_ADMIN_SYNC_IP }
            .first()

    suspend fun setManualIp(ip: String?) {
        dataStore.edit { prefs ->
            if (ip.isNullOrBlank()) prefs.remove(AdminSyncConfigKeys.serverIp)
            else prefs[AdminSyncConfigKeys.serverIp] = ip.trim()
        }
    }

    /** Returns "http://<ip>:47821", or null if no IP has been configured yet. */
    suspend fun getServerUrl(): String? = buildAdminSyncUrl(getManualIp())

    companion object {
        fun create(context: Context): AdminSyncConfig = AdminSyncConfig(context.adminSyncDataStore)
    }
}
