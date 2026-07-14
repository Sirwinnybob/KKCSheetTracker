package com.kkc.sheettracker.data

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SupplyBarcodeStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun makeStore(): SupplyBarcodeStore {
        return SupplyBarcodeStore(tmp.root.absolutePath, SupplyRepository(tmp.root.absolutePath))
    }

    @Test
    fun lookupReturnsNullWhenBarcodesJsonMissing() {
        val store = makeStore()
        assertNull(store.lookup("any-barcode"))
    }

    @Test
    fun linkWritesBarcodesJsonAndItemMirror() {
        val basePath = tmp.root.absolutePath
        val itemsDir = File(basePath, ".supply/items").also { it.mkdirs() }
        val stored = com.kkc.sheettracker.data.models.StoredSupplyItem(
            id = "i1", categoryId = "c1", name = "Bolts", notes = null,
            fields = emptyMap(), customFields = emptyMap(), attachmentIds = emptyList(),
            barcodes = emptyList(), createdAt = "", updatedAt = ""
        )
        File(itemsDir, "i1.json").writeText(Gson().toJson(stored))

        val store = makeStore()
        store.linkSync("barcode-abc", "i1")

        assertEquals("i1", store.lookup("barcode-abc"))
        val index = Gson().fromJson(
            File(basePath, ".supply/barcodes.json").readText(),
            Map::class.java
        )
        assertEquals("i1", index["barcode-abc"])
        val item = Gson().fromJson(File(itemsDir, "i1.json").readText(),
            com.kkc.sheettracker.data.models.StoredSupplyItem::class.java)
        assertTrue(item.barcodes.contains("barcode-abc"))
    }

    @Test
    fun unlinkRemovesBarcodeFromIndexAndItem() {
        val basePath = tmp.root.absolutePath
        val supplyDir = File(basePath, ".supply").also { it.mkdirs() }
        val itemsDir = File(basePath, ".supply/items").also { it.mkdirs() }
        File(supplyDir, "barcodes.json").writeText("""{"barcode-abc":"i1"}""")
        val stored = com.kkc.sheettracker.data.models.StoredSupplyItem(
            id = "i1", categoryId = "c1", name = "Bolts", notes = null,
            fields = emptyMap(), customFields = emptyMap(), attachmentIds = emptyList(),
            barcodes = listOf("barcode-abc"), createdAt = "", updatedAt = ""
        )
        File(itemsDir, "i1.json").writeText(Gson().toJson(stored))

        val store = makeStore()
        store.unlinkSync("barcode-abc")

        assertNull(store.lookup("barcode-abc"))
        val index = Gson().fromJson(
            File(basePath, ".supply/barcodes.json").readText(),
            Map::class.java
        )
        assertFalse(index.containsKey("barcode-abc"))
    }

    @Test
    fun syncConflictFileIsIgnoredInIndex() {
        val basePath = tmp.root.absolutePath
        val supplyDir = File(basePath, ".supply").also { it.mkdirs() }
        File(supplyDir, "barcodes.json").writeText("""{"barcode-real":"i1"}""")
        // Sync conflict file must not be read as the index
        File(supplyDir, "barcodes.sync-conflict-20260101.json").writeText("""{"barcode-stale":"i2"}""")

        val store = makeStore()
        assertEquals("i1", store.lookup("barcode-real"))
        assertNull(store.lookup("barcode-stale"))
    }

    @Test
    fun lookupReturnsNullWhenBarcodesJsonIsMalformed() {
        val basePath = tmp.root.absolutePath
        val supplyDir = File(basePath, ".supply").also { it.mkdirs() }
        File(supplyDir, "barcodes.json").writeText("""{not valid json""")

        val store = makeStore()

        assertNull(store.lookup("any-barcode"))
    }

    @Test
    fun setScanModeUpdatesScanModeStateFlowValue() {
        val store = makeStore()

        assertEquals(ScanMode.Idle, store.scanMode.value)

        store.setScanMode(ScanMode.Global)
        assertEquals(ScanMode.Global, store.scanMode.value)

        store.setScanMode(ScanMode.Item("item-42"))
        assertEquals(ScanMode.Item("item-42"), store.scanMode.value)
    }

    @Test
    fun setPickPendingBarcodeUpdatesPickPendingBarcodeStateFlowValue() {
        val store = makeStore()

        assertNull(store.pickPendingBarcode.value)

        store.setPickPendingBarcode("barcode-xyz")
        assertEquals("barcode-xyz", store.pickPendingBarcode.value)

        store.setPickPendingBarcode(null)
        assertNull(store.pickPendingBarcode.value)
    }

    @Test
    fun clearPickModeResetsPickPendingBarcodeAndScanMode() {
        val store = makeStore()
        store.setPickPendingBarcode("barcode-xyz")
        store.setScanMode(ScanMode.Item("item-42"))

        store.clearPickMode()

        assertNull(store.pickPendingBarcode.value)
        assertEquals(ScanMode.Idle, store.scanMode.value)
    }
}
