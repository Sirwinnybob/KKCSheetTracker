package com.kkc.sheettracker.data.mixservice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.first

private const val MIX_OPERATION_SESSIONS = "mix_operation_sessions"
private val Context.mixOperationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mix_operation_sessions",
)

interface MixOperationSessionStore {
    suspend fun load(): MixOperationSessionLoadResult
    suspend fun save(sessions: Map<String, ManageCodeSession>)
}

sealed interface MixOperationSessionLoadResult {
    data class Success(val sessions: Map<String, ManageCodeSession>) : MixOperationSessionLoadResult
    data class Failure(val message: String) : MixOperationSessionLoadResult
}

class DataStoreMixOperationSessionStore(
    private val dataStore: DataStore<Preferences>,
    private val gson: Gson = Gson(),
) : MixOperationSessionStore {
    override suspend fun load(): MixOperationSessionLoadResult {
        return try {
            val raw = dataStore.data.first()[stringPreferencesKey(MIX_OPERATION_SESSIONS)]
                ?: return MixOperationSessionLoadResult.Success(emptyMap())
            val stored = gson.fromJson(raw, StoredSessions::class.java)
                ?: return MixOperationSessionLoadResult.Failure("persisted session JSON is malformed")
            MixOperationSessionLoadResult.Success(stored.sessions)
        } catch (error: Exception) {
            MixOperationSessionLoadResult.Failure(
                error.message?.takeIf { it.isNotBlank() } ?: "persisted sessions could not be read"
            )
        }
    }

    override suspend fun save(sessions: Map<String, ManageCodeSession>) {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey(MIX_OPERATION_SESSIONS)] = gson.toJson(StoredSessions(sessions))
        }
    }

    private data class StoredSessions(val sessions: Map<String, ManageCodeSession> = emptyMap())
}

fun Context.mixOperationSessionStore(): MixOperationSessionStore =
    DataStoreMixOperationSessionStore(mixOperationDataStore)
