package com.kkc.sheettracker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kkc.sheettracker.data.models.SpecialtyStation
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.specialtyViewerDefaultsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "specialty_viewer_defaults"
)

private object SpecialtyViewerDefaultsKeys {
    val stationOrder = stringPreferencesKey("station_order")
    val expandedSectionIds = stringSetPreferencesKey("expanded_section_ids")
}

class SpecialtyViewerDefaultsStore(
    private val dataStore: DataStore<Preferences>,
) {
    val defaults: Flow<SpecialtyViewerDefaults> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs ->
            SpecialtyViewerDefaults(
                stationOrder = sanitizeSpecialtyViewerStationOrder(
                    prefs[SpecialtyViewerDefaultsKeys.stationOrder]
                ),
                expandedSectionIds = sanitizeSpecialtyViewerExpandedSectionIds(
                    prefs[SpecialtyViewerDefaultsKeys.expandedSectionIds]
                ),
            )
        }

    suspend fun current(): SpecialtyViewerDefaults = defaults.first()

    suspend fun setStationOrder(stations: List<SpecialtyStation>) {
        dataStore.edit { prefs ->
            prefs[SpecialtyViewerDefaultsKeys.stationOrder] = stations.joinToString(",") { it.name }
        }
    }

    suspend fun setExpandedSectionIds(sectionIds: Set<String>) {
        dataStore.edit { prefs ->
            prefs[SpecialtyViewerDefaultsKeys.expandedSectionIds] = sectionIds
        }
    }

    suspend fun setSectionExpanded(sectionId: String, expanded: Boolean) {
        dataStore.edit { prefs ->
            val updated = sanitizeSpecialtyViewerExpandedSectionIds(
                prefs[SpecialtyViewerDefaultsKeys.expandedSectionIds]
            ).toMutableSet().apply {
                if (expanded) add(sectionId) else remove(sectionId)
            }
            prefs[SpecialtyViewerDefaultsKeys.expandedSectionIds] = updated
        }
    }

    companion object {
        fun create(context: Context): SpecialtyViewerDefaultsStore =
            SpecialtyViewerDefaultsStore(context.specialtyViewerDefaultsDataStore)
    }
}
