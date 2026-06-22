package com.kkc.sheettracker.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.kkc.sheettracker.data.models.SpecialtyStation
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpecialtyViewerDefaultsStoreTest {
    private val specialtyStations = specialtyViewerStations()

    @Test
    fun `defaults fall back to enum order and all sections expanded`() = runBlocking {
        val store = createStore()

        val defaults = store.current()

        assertEquals(specialtyStations, defaults.stationOrder)
        assertEquals(defaultSpecialtyViewerExpandedSectionIds(), defaults.expandedSectionIds)
    }

    @Test
    fun `saved reordered station list is preserved`() = runBlocking {
        val store = createStore()
        val reordered = listOf(
            SpecialtyStation.ASSEMBLY,
            SpecialtyStation.CNC,
            SpecialtyStation.HARDWOODS,
            SpecialtyStation.SAW,
            SpecialtyStation.EDGE_BANDER,
            SpecialtyStation.SPECIALTY,
        )

        store.setStationOrder(reordered)

        assertEquals(reordered, store.current().stationOrder)
    }

    @Test
    fun `saved expanded sections are preserved including stable ids`() = runBlocking {
        val store = createStore()
        val expanded = linkedSetOf(
            SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS,
            SpecialtyStation.CNC.name,
            SPECIALTY_VIEWER_SECTION_ID_OTHER,
        )

        store.setExpandedSectionIds(expanded)

        assertEquals(expanded, store.current().expandedSectionIds)
    }

    @Test
    fun `setSectionExpanded adds and removes section ids`() = runBlocking {
        val store = createStore()

        store.setExpandedSectionIds(setOf(SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS))
        store.setSectionExpanded(SpecialtyStation.CNC.name, expanded = true)
        store.setSectionExpanded(SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS, expanded = false)

        assertEquals(setOf(SpecialtyStation.CNC.name), store.current().expandedSectionIds)
    }

    @Test
    fun `setSectionExpanded merges atomically across concurrent updates`() = runBlocking {
        val store = createStore()

        store.setExpandedSectionIds(emptySet())
        awaitAll(
            async { store.setSectionExpanded(SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS, expanded = true) },
            async { store.setSectionExpanded(SpecialtyStation.CNC.name, expanded = true) },
        )

        assertEquals(
            setOf(SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS, SpecialtyStation.CNC.name),
            store.current().expandedSectionIds
        )
    }

    @Test
    fun `partial and corrupt prefs fall back safely`() = runBlocking {
        val (store, dataStore) = createStoreWithDataStore()

        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("station_order")] = "CNC,SAW"
            prefs[stringSetPreferencesKey("expanded_section_ids")] = setOf(
                SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS,
                "bogus",
                SPECIALTY_VIEWER_SECTION_ID_OTHER,
            )
        }

        val defaults = store.current()

        assertEquals(specialtyStations, defaults.stationOrder)
        assertEquals(
            linkedSetOf(
                SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS,
                SPECIALTY_VIEWER_SECTION_ID_OTHER,
            ),
            defaults.expandedSectionIds
        )
    }

    @Test
    fun `defaults exclude delivery station because specialty detail filters it out`() = runBlocking {
        val store = createStore()

        val defaults = store.current()

        assertFalse(SpecialtyStation.DELIVERY in defaults.stationOrder)
        assertFalse(SpecialtyStation.DELIVERY.name in defaults.expandedSectionIds)
    }

    private fun createStore(): SpecialtyViewerDefaultsStore = createStoreWithDataStore().first

    private fun createStoreWithDataStore(): Pair<SpecialtyViewerDefaultsStore, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>> {
        val testDir = createTempDirectory("specialty-viewer-defaults-${UUID.randomUUID()}").toFile()
        testDir.deleteOnExit()
        val testFile = File(testDir, "datastore.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { testFile }
        )
        return SpecialtyViewerDefaultsStore(dataStore) to dataStore
    }
}
