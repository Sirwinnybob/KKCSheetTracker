package com.kkc.sheettracker.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenMaterialsVisibilityPreferencesStoreTest {

    @Test
    fun `defaults to false for both modes`() = runBlocking {
        val store = createStore()

        assertFalse(store.showHidden(HiddenMaterialsMode.HARDWOODS))
        assertFalse(store.showHidden(HiddenMaterialsMode.SPECIALTY))
    }

    @Test
    fun `setShowHidden persists and reads back`() = runBlocking {
        val store = createStore()

        store.setShowHidden(HiddenMaterialsMode.HARDWOODS, true)

        assertTrue(store.showHidden(HiddenMaterialsMode.HARDWOODS))
    }

    @Test
    fun `the two modes are independent`() = runBlocking {
        val store = createStore()

        store.setShowHidden(HiddenMaterialsMode.HARDWOODS, true)

        assertTrue(store.showHidden(HiddenMaterialsMode.HARDWOODS))
        assertFalse(store.showHidden(HiddenMaterialsMode.SPECIALTY))
    }

    private fun createStore(): HiddenMaterialsVisibilityPreferencesStore {
        val testDir = createTempDirectory("hidden-materials-visibility-${UUID.randomUUID()}").toFile()
        testDir.deleteOnExit()
        val testFile = File(testDir, "datastore.preferences_pb")
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { testFile }
        )
        return HiddenMaterialsVisibilityPreferencesStore(dataStore)
    }
}
