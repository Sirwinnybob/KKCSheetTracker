package com.kkc.sheettracker.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DataStoreSyncthingPreferencesStoreTest {

    @Test
    fun `saveApiKey persists key and emits updated settings`() = runBlocking {
        val testDir = createTempDirectory("syncthing-settings-${UUID.randomUUID()}").toFile()
        testDir.deleteOnExit()
        val testFile = File(testDir, "datastore.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { testFile }
        )
        val store = DataStoreSyncthingPreferencesStore(dataStore)

        store.saveApiKey("  my-key  ")
        val saved = store.settings
            .map { it.apiKey }
            .firstOrNull { it.isNotBlank() }

        assertEquals("my-key", saved)
    }

    @Test
    fun `settings defaults to empty api key`() = runBlocking {
        val testDir = createTempDirectory("syncthing-settings-${UUID.randomUUID()}").toFile()
        testDir.deleteOnExit()
        val testFile = File(testDir, "datastore.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { testFile }
        )
        val store = DataStoreSyncthingPreferencesStore(dataStore)

        val initial = store.settings.first()

        assertEquals("", initial.apiKey)
    }
}
