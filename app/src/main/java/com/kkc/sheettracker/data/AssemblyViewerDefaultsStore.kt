package com.kkc.sheettracker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.assemblyViewerDefaultsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "assembly_viewer_defaults"
)

private object AssemblyViewerDefaultsKeys {
    val layout = stringPreferencesKey("layout")
    val firstPane = stringPreferencesKey("first_pane")
    val secondPane = stringPreferencesKey("second_pane")
    val hideUiOnOpen = booleanPreferencesKey("hide_ui_on_open")
}

class AssemblyViewerDefaultsStore(
    private val dataStore: DataStore<Preferences>,
) {
    val defaults: Flow<AssemblyViewerDefaults> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs ->
            AssemblyViewerDefaults(
                layout = prefs[AssemblyViewerDefaultsKeys.layout]
                    ?.let { runCatching { AssemblyViewLayout.valueOf(it) }.getOrNull() }
                    ?: AssemblyViewLayout.SPLIT,
                firstPane = prefs[AssemblyViewerDefaultsKeys.firstPane]
                    ?.let { runCatching { AssemblyPaneView.valueOf(it) }.getOrNull() }
                    ?: AssemblyPaneView.PLANS,
                secondPane = prefs[AssemblyViewerDefaultsKeys.secondPane]
                    ?.let { runCatching { AssemblyPaneView.valueOf(it) }.getOrNull() }
                    ?: AssemblyPaneView.ASSEMBLY,
                hideUiOnOpen = prefs[AssemblyViewerDefaultsKeys.hideUiOnOpen] ?: false,
            )
        }

    suspend fun current(): AssemblyViewerDefaults = defaults.first()

    suspend fun setLayout(layout: AssemblyViewLayout) {
        dataStore.edit { it[AssemblyViewerDefaultsKeys.layout] = layout.name }
    }

    suspend fun setFirstPane(view: AssemblyPaneView) {
        dataStore.edit { it[AssemblyViewerDefaultsKeys.firstPane] = view.name }
    }

    suspend fun setSecondPane(view: AssemblyPaneView) {
        dataStore.edit { it[AssemblyViewerDefaultsKeys.secondPane] = view.name }
    }

    suspend fun setHideUiOnOpen(value: Boolean) {
        dataStore.edit { it[AssemblyViewerDefaultsKeys.hideUiOnOpen] = value }
    }

    companion object {
        fun create(context: Context): AssemblyViewerDefaultsStore =
            AssemblyViewerDefaultsStore(context.assemblyViewerDefaultsDataStore)
    }
}
