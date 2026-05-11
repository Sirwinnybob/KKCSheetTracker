package com.kkc.sheettracker.sync

import kotlinx.coroutines.flow.Flow

data class SyncthingUserSettings(
    val apiKey: String = ""
)

interface SyncthingPreferencesStore {
    val settings: Flow<SyncthingUserSettings>
    suspend fun saveApiKey(apiKey: String)
}
