package com.kkc.sheettracker.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SheetRipProgressStoreTest {
    private val jobFolderName = "1234 - Test Job"

    @Test
    fun loadDone_nonExistentFile_returnsEmptyMap() {
        val baseDir = Files.createTempDirectory("sheet-rip-test").toFile()
        val store = SheetRipProgressStore(baseDir)

        val doneMap = store.loadDone(jobFolderName)
        assertTrue(doneMap.isEmpty())
    }

    @Test
    fun setDone_atomicWrite_savesAndLoadsDoneStates() = runBlocking {
        val baseDir = Files.createTempDirectory("sheet-rip-test").toFile()
        val store = SheetRipProgressStore(baseDir)

        store.setDone(jobFolderName, "item-1", true)
        store.setDone(jobFolderName, "item-2", false)
        store.setDone(jobFolderName, "item-3", true)

        val doneMap = store.loadDone(jobFolderName)
        assertEquals(3, doneMap.size)
        assertTrue(doneMap["item-1"] == true)
        assertFalse(doneMap["item-2"] == true)
        assertTrue(doneMap["item-3"] == true)

        // Overwrite item-1
        store.setDone(jobFolderName, "item-1", false)
        val updatedMap = store.loadDone(jobFolderName)
        assertFalse(updatedMap["item-1"] == true)
    }

    @Test
    fun setDone_rejectsLateCompletionAfterNewerDecrementProjection() = runBlocking {
        val baseDir = Files.createTempDirectory("sheet-rip-test").toFile()
        val store = SheetRipProgressStore(baseDir)

        store.setDone(jobFolderName, "item-1", true, projectionRevision = 1)
        store.setDone(jobFolderName, "item-1", false, projectionRevision = 2)
        store.setDone(jobFolderName, "item-1", true, projectionRevision = 1)

        assertFalse(store.loadDone(jobFolderName)["item-1"] == true)
    }
}
