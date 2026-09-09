package com.kkc.sheettracker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.hiddenMaterialsVisibilityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "hidden_materials_visibility"
)

private object HiddenMaterialsVisibilityKeys {
    val showHiddenHardwoods = booleanPreferencesKey("show_hidden_hardwoods")
    val showHiddenSpecialty = booleanPreferencesKey("show_hidden_specialty")
}

/**
 * Per-tablet "show hidden materials" toggle for the cutlist screen, one independent boolean per
 * mode (Hardwoods/Specialty) -- matches the feature's mode segregation, so toggling one screen's
 * visibility has no effect on the other's.
 */
class HiddenMaterialsVisibilityPreferencesStore(private val dataStore: DataStore<Preferences>) {

    fun showHiddenFlow(mode: HiddenMaterialsMode): Flow<Boolean> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs -> prefs[keyFor(mode)] ?: false }

    suspend fun showHidden(mode: HiddenMaterialsMode): Boolean = showHiddenFlow(mode).first()

    suspend fun setShowHidden(mode: HiddenMaterialsMode, value: Boolean) {
        dataStore.edit { it[keyFor(mode)] = value }
    }

    private fun keyFor(mode: HiddenMaterialsMode) = when (mode) {
        HiddenMaterialsMode.HARDWOODS -> HiddenMaterialsVisibilityKeys.showHiddenHardwoods
        HiddenMaterialsMode.SPECIALTY -> HiddenMaterialsVisibilityKeys.showHiddenSpecialty
    }

    companion object {
        fun create(context: Context): HiddenMaterialsVisibilityPreferencesStore =
            HiddenMaterialsVisibilityPreferencesStore(context.hiddenMaterialsVisibilityDataStore)
    }
}
