package com.kkc.sheettracker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.pinnedJobsDataStore by preferencesDataStore(name = "pinned_jobs")

class PinnedJobsStore(private val context: Context) {

    private val dataStore = context.pinnedJobsDataStore

    /** Ordered list of pinned folderNames, newest-pin first. */
    val pinnedFolderNames: Flow<List<String>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            prefs[KEY]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        }

    suspend fun pin(folderName: String) {
        dataStore.edit { prefs ->
            val cur = prefs[KEY]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            if (folderName !in cur) {
                prefs[KEY] = (listOf(folderName) + cur).joinToString("\n")
            }
        }
    }

    suspend fun unpin(folderName: String) {
        dataStore.edit { prefs ->
            val cur = prefs[KEY]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            prefs[KEY] = (cur - folderName).joinToString("\n")
        }
    }

    suspend fun toggle(folderName: String, isCurrentlyPinned: Boolean) {
        if (isCurrentlyPinned) unpin(folderName) else pin(folderName)
    }

    companion object {
        private val KEY = stringPreferencesKey("pinned_folder_names")

        fun create(context: Context) = PinnedJobsStore(context)
    }
}
