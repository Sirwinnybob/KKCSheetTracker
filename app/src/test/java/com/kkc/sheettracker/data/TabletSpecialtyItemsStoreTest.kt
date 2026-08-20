package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.TabletSpecialtyItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TabletSpecialtyItemsStoreTest {
    private val jobFolderName = "1234 - Test Job"

    @Test
    fun mutations_readOnly_doNotCreateSidecar() = runBlocking {
        val baseDir = Files.createTempDirectory("tablet-specialty-read-only-test").toFile()
        val store = TabletSpecialtyItemsStore(baseDir, "tablet-local", readOnly = true)
        val sidecar = File(baseDir, "$jobFolderName/.metadata/admin/tablet_items_tablet-local.json")

        store.saveItem(jobFolderName, testItem)
        assertNoSidecar(sidecar)

        store.deleteItem(jobFolderName, "tablet:item-1")
        assertNoSidecar(sidecar)

        store.deleteItemTombstone(jobFolderName, "tablet:item-1")
        assertNoSidecar(sidecar)
    }

    private fun assertNoSidecar(sidecar: File) {
        assertFalse(sidecar.exists())
        assertFalse(sidecar.parentFile?.exists() == true)
    }

    private val testItem = TabletSpecialtyItem(
        id = "item-1",
        name = "Custom Item",
        category = SpecialtyItemCategory.CUSTOM,
        stations = listOf(SpecialtyStation.SPECIALTY),
        createdAt = "2026-08-20T00:00:00Z",
        createdByDevice = "tablet-local"
    )
}
