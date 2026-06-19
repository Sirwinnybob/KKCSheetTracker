package com.kkc.sheettracker.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SpecialtyStateStoreTest {
    private val jobFolderName = "1234 - Test Job"

    @Test
    fun setItemCompletionKey_customMultiStation_persistsStationKeysIndependently() = runBlocking {
        val baseDir = Files.createTempDirectory("specialty-state-store-test").toFile()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "item-custom",
                      "name": "Custom Multi",
                      "cabinetNumbers": ["C1"],
                      "category": "CUSTOM",
                      "stations": ["CNC", "SAW"]
                    }
                  ]
                }
            """.trimIndent()
        )

        val progressStore = SpecialtyProgressStore(baseDir = baseDir, tabletId = "tablet-local")
        val repository = SpecialtyRepository(baseDir = baseDir, progressStore = progressStore)
        val scanCoordinator = SpecialtyScanCoordinator(repository)
        val sheetRipStore = SheetRipProgressStore(baseDir = baseDir)
        val stateStore = SpecialtyStateStore(
            specialtyScanCoordinator = scanCoordinator,
            specialtyProgressStore = progressStore,
            sheetRipProgressStore = sheetRipStore,
            tabletItemsStore = TabletSpecialtyItemsStore(baseDir, "test-tablet")
        )

        stateStore.setItemCompletionKey(
            jobFolderName = jobFolderName,
            itemId = "item-custom",
            completionKey = "CNC",
            completed = true
        )

        var resolved = progressStore.loadResolvedItems(jobFolderName).first()
        assertFalse(resolved.isComplete)
        assertTrue(resolved.completionByKey["CNC"]?.completed == true)
        assertFalse(resolved.completionByKey["SAW"]?.completed == true)

        stateStore.setItemCompletionKey(
            jobFolderName = jobFolderName,
            itemId = "item-custom",
            completionKey = "SAW",
            completed = true
        )

        resolved = progressStore.loadResolvedItems(jobFolderName).first()
        assertTrue(resolved.isComplete)
        assertTrue(resolved.completionByKey["CNC"]?.completed == true)
        assertTrue(resolved.completionByKey["SAW"]?.completed == true)

        stateStore.setItemCompletionKey(
            jobFolderName = jobFolderName,
            itemId = "item-custom",
            completionKey = "CNC",
            completed = false
        )

        resolved = progressStore.loadResolvedItems(jobFolderName).first()
        assertFalse(resolved.isComplete)
        assertFalse(resolved.completionByKey["CNC"]?.completed == true)
        assertTrue(resolved.completionByKey["SAW"]?.completed == true)
    }

    @Test
    fun setItemCompletion_toOrder_writesSingleItemCompletionKey() = runBlocking {
        val baseDir = Files.createTempDirectory("specialty-state-store-test").toFile()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "item-order",
                      "name": "To Order",
                      "cabinetNumbers": ["C1"],
                      "category": "TO_ORDER",
                      "stations": ["CNC", "SAW", "ASSEMBLY"]
                    }
                  ]
                }
            """.trimIndent()
        )

        val progressStore = SpecialtyProgressStore(baseDir = baseDir, tabletId = "tablet-local")
        val repository = SpecialtyRepository(baseDir = baseDir, progressStore = progressStore)
        val scanCoordinator = SpecialtyScanCoordinator(repository)
        val sheetRipStore = SheetRipProgressStore(baseDir = baseDir)
        val stateStore = SpecialtyStateStore(
            specialtyScanCoordinator = scanCoordinator,
            specialtyProgressStore = progressStore,
            sheetRipProgressStore = sheetRipStore,
            tabletItemsStore = TabletSpecialtyItemsStore(baseDir, "test-tablet")
        )

        stateStore.setItemCompletion(
            jobFolderName = jobFolderName,
            itemId = "item-order",
            completed = true
        )

        val resolved = progressStore.loadResolvedItems(jobFolderName).first()
        assertTrue(resolved.isComplete)
        assertTrue(resolved.completionByKey[SpecialtyProgressStore.ITEM_COMPLETION_KEY]?.completed == true)
        assertTrue(resolved.completionByKey.keys == setOf(SpecialtyProgressStore.ITEM_COMPLETION_KEY))
    }

    @Test
    fun refreshJobOnOpen_invalidatesResolvedCacheBeforeBackgroundRefresh() = runBlocking {
        val baseDir = Files.createTempDirectory("specialty-state-store-test").toFile()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "item-1",
                      "name": "Original",
                      "category": "CUSTOM",
                      "stations": ["CNC"]
                    }
                  ]
                }
            """.trimIndent()
        )

        val progressStore = SpecialtyProgressStore(baseDir = baseDir, tabletId = "tablet-local")
        val repository = SpecialtyRepository(baseDir = baseDir, progressStore = progressStore)
        val scanCoordinator = SpecialtyScanCoordinator(repository)
        val stateStore = SpecialtyStateStore(
            specialtyScanCoordinator = scanCoordinator,
            specialtyProgressStore = progressStore,
            sheetRipProgressStore = SheetRipProgressStore(baseDir = baseDir),
            tabletItemsStore = TabletSpecialtyItemsStore(baseDir, "test-tablet")
        )

        assertEquals(1, stateStore.getResolvedItems(jobFolderName).size)
        val versionBefore = stateStore.progressVersion.value

        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "item-1",
                      "name": "Original",
                      "category": "CUSTOM",
                      "stations": ["CNC"]
                    },
                    {
                      "id": "item-2",
                      "name": "Fresh Item",
                      "category": "CUSTOM",
                      "stations": ["SAW"]
                    }
                  ]
                }
            """.trimIndent()
        )

        stateStore.refreshJobOnOpen(jobFolderName)

        assertTrue(stateStore.progressVersion.value > versionBefore)
        assertEquals(2, stateStore.getResolvedItems(jobFolderName).size)
    }

    private fun writeSpecialtyItems(baseDir: File, jobFolderName: String, body: String) {
        val file = File(baseDir, "$jobFolderName/.metadata/admin/specialty_items.json")
        file.parentFile?.mkdirs()
        file.writeText(body)
    }
}
