package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kkc.sheettracker.data.models.SupplyCategory
import com.kkc.sheettracker.data.models.SupplyComment
import com.kkc.sheettracker.data.models.SupplyStatusRecord
import com.kkc.sheettracker.data.models.StoredSupplyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SupplyRepositoryTest {
    private val gson = Gson()

    @Test
    fun updateItemWritesAtomicallyWithNoLeftoverTempFile() {
        val basePath = createTempBasePath()
        val itemsDir = File(basePath, ".supply/items")
        itemsDir.mkdirs()
        val itemId = "item-1"
        val stored = StoredSupplyItem(
            id = itemId,
            categoryId = "cat-1",
            name = "Original Name",
            notes = null,
            fields = emptyMap(),
            customFields = emptyMap(),
            attachmentIds = emptyList(),
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z"
        )
        File(itemsDir, "$itemId.json").writeText(gson.toJson(stored))

        val repository = SupplyRepository(basePath)
        val result = repository.updateItem(
            itemId = itemId,
            name = "Updated Name",
            categoryId = "cat-2",
            notes = "some notes",
            fields = mapOf("qty" to "3")
        )

        assertNotNull(result)
        assertEquals("Updated Name", result?.name)
        assertEquals("cat-2", result?.categoryId)

        val itemFiles = itemsDir.listFiles().orEmpty().map { it.name }
        // The write must land as the final "<itemId>.json" with no ".tmp-*" artifact left
        // behind, and the file itself must contain fully-formed, parseable JSON (i.e. it was
        // never observed mid-write via a truncating writeText()).
        assertTrue(itemFiles.contains("$itemId.json"))
        assertTrue(itemFiles.none { it.contains(".tmp-") })

        val persisted = gson.fromJson(File(itemsDir, "$itemId.json").readText(), StoredSupplyItem::class.java)
        assertEquals("Updated Name", persisted.name)
        assertEquals("cat-2", persisted.categoryId)
        assertEquals("some notes", persisted.notes)
        assertEquals(mapOf("qty" to "3"), persisted.fields)
    }

    @Test
    fun updateItemReturnsNullWhenItemDoesNotExist() {
        val basePath = createTempBasePath()
        val repository = SupplyRepository(basePath)

        val result = repository.updateItem(
            itemId = "missing-item",
            name = "Name",
            categoryId = "cat-1",
            notes = null,
            fields = emptyMap()
        )

        assertEquals(null, result)
    }

    @Test
    fun createCategoryWritesAtomicallyWithNoLeftoverTempFile() {
        val basePath = createTempBasePath()
        val repository = SupplyRepository(basePath)

        val first = repository.createCategory("Sandpaper")
        val second = repository.createCategory("Glue")

        val supplyDir = File(basePath, ".supply")
        val categoriesFile = File(supplyDir, "categories.json")
        val supplyFiles = supplyDir.listFiles().orEmpty().map { it.name }

        // No ".tmp-*" artifact left behind, and the final file must be fully-formed,
        // parseable JSON containing both categories in insertion order.
        assertTrue(supplyFiles.contains("categories.json"))
        assertTrue(supplyFiles.none { it.contains(".tmp-") })

        val type = object : TypeToken<List<SupplyCategory>>() {}.type
        val persisted: List<SupplyCategory> = gson.fromJson(categoriesFile.readText(), type)
        assertEquals(2, persisted.size)
        assertEquals(first.id, persisted[0].id)
        assertEquals("Sandpaper", persisted[0].name)
        assertEquals(second.id, persisted[1].id)
        assertEquals("Glue", persisted[1].name)
    }

    @Test
    fun createCategoryAppendsToExistingCategoriesWithoutClobbering() {
        val basePath = createTempBasePath()
        val supplyDir = File(basePath, ".supply")
        supplyDir.mkdirs()
        val existing = listOf(SupplyCategory("existing-1", "Existing Category", 0))
        File(supplyDir, "categories.json").writeText(gson.toJson(existing))

        val repository = SupplyRepository(basePath)
        val created = repository.createCategory("New Category")

        val type = object : TypeToken<List<SupplyCategory>>() {}.type
        val persisted: List<SupplyCategory> = gson.fromJson(File(supplyDir, "categories.json").readText(), type)
        assertEquals(2, persisted.size)
        assertEquals("existing-1", persisted[0].id)
        assertEquals(created.id, persisted[1].id)
        assertEquals("New Category", persisted[1].name)
        assertEquals(1, created.position)
    }

    @Test
    fun createItem_persistsCustomFields() {
        val basePath = createTempBasePath()
        val repository = SupplyRepository(basePath)
        val cat = repository.createCategory("Blades")
        val created = repository.createItem(
            categoryId = cat.id,
            name = "Saw blade",
            notes = null,
            fields = mapOf("sku" to "SB-1"),
            customFields = mapOf("diameter" to "10in"),
            status = "IN STOCK",
            tabletId = "tablet-A"
        )
        val reloaded = repository.getItem(created.id)!!
        assertEquals("SB-1", reloaded.fields["sku"])
        assertEquals("10in", reloaded.customFields["diameter"])
    }

    @Test
    fun updateItem_setsCustomFieldsAndPreservesOrphans() {
        val basePath = createTempBasePath()
        val repository = SupplyRepository(basePath)
        val cat = repository.createCategory("Blades")
        val created = repository.createItem(
            categoryId = cat.id,
            name = "Saw blade",
            notes = null,
            fields = mapOf("sku" to "SB-1"),
            customFields = mapOf("diameter" to "10in", "legacyKerf" to "0.1"),
            tabletId = "tablet-A"
        )
        val updated = repository.updateItem(
            created.id,
            "Saw blade v2",
            cat.id,
            null,
            mapOf("sku" to "SB-2"),
            mapOf("diameter" to "12in", "legacyKerf" to "0.1")
        )!!
        assertEquals("SB-2", updated.fields["sku"])
        assertEquals("12in", updated.customFields["diameter"])
        assertEquals("0.1", updated.customFields["legacyKerf"])
    }

    // ── AUD-10: atomic status/comment writes and parsed-Instant ordering ────────

    private fun seedItem(basePath: String, id: String) {
        val itemsDir = File(basePath, ".supply/items").apply { mkdirs() }
        val stored = StoredSupplyItem(
            id = id, categoryId = "cat", name = "Widget", notes = null,
            createdAt = "2026-07-12T09:00:00Z", updatedAt = "2026-07-12T09:00:00Z"
        )
        File(itemsDir, "$id.json").writeText(gson.toJson(stored))
    }

    private fun seedStatus(basePath: String, id: String, tablet: String, status: String, at: String) {
        val statusDir = File(basePath, ".supply/status").apply { mkdirs() }
        File(statusDir, "$id.$tablet.json").writeText(gson.toJson(SupplyStatusRecord(status, "", at)))
    }

    @Test
    fun fractionalSecondStatusTimestampWinsOverWholeSecond() {
        // 10:00:00.500 is chronologically later than 10:00:00, but lexically "...00.500Z"
        // sorts BEFORE "...00Z". The later (fractional) status must win.
        val basePath = createTempBasePath()
        seedItem(basePath, "item1")
        seedStatus(basePath, "item1", "tabletA", "OUT", "2026-07-12T10:00:00Z")
        seedStatus(basePath, "item1", "tabletB", "IN STOCK", "2026-07-12T10:00:00.500Z")

        val item = SupplyRepository(basePath).getItems().single { it.id == "item1" }
        assertEquals("IN STOCK", item.status)
    }

    @Test
    fun laterWholeSecondStatusWinsOverEarlierFractional() {
        val basePath = createTempBasePath()
        seedItem(basePath, "item2")
        seedStatus(basePath, "item2", "tabletA", "LOW", "2026-07-12T10:00:00.500Z")
        seedStatus(basePath, "item2", "tabletB", "OUT", "2026-07-12T10:00:01Z")

        val item = SupplyRepository(basePath).getItems().single { it.id == "item2" }
        assertEquals("OUT", item.status)
    }

    @Test
    fun setStatusWritesAtomicallyWithNoLeftoverTempFile() {
        val basePath = createTempBasePath()
        seedItem(basePath, "item3")
        val repository = SupplyRepository(basePath)
        repository.setStatus("item3", "ORDERED", "chad", "tabletA")

        val statusDir = File(basePath, ".supply/status")
        assertTrue(statusDir.listFiles().orEmpty().none { it.name.contains(".tmp-") })
        assertEquals("ORDERED", repository.getItems().single { it.id == "item3" }.status)
    }

    @Test
    fun addCommentWritesAtomicallyAndReadsBack() {
        val basePath = createTempBasePath()
        seedItem(basePath, "item4")
        val repository = SupplyRepository(basePath)
        repository.addComment("item4", "chad", "hello", "tabletA")

        val commentDir = File(basePath, ".supply/comments/item4")
        assertTrue(commentDir.listFiles().orEmpty().none { it.name.contains(".tmp-") })
        val comments = repository.getComments("item4")
        assertEquals(1, comments.size)
        assertEquals("hello", comments.single().text)
    }

    @Test
    fun commentsOrderedByParsedInstant() {
        val basePath = createTempBasePath()
        seedItem(basePath, "item5")
        val commentDir = File(basePath, ".supply/comments/item5").apply { mkdirs() }
        File(commentDir, "a.json").writeText(
            gson.toJson(SupplyComment("a", "u", "first", "2026-07-12T10:00:00Z"))
        )
        File(commentDir, "b.json").writeText(
            gson.toJson(SupplyComment("b", "u", "later", "2026-07-12T10:00:00.500Z"))
        )

        assertEquals(listOf("a", "b"), SupplyRepository(basePath).getComments("item5").map { it.id })
    }

    // ── barcodes mirror field ───────────────────────────────────────────────────

    @Test
    fun storedSupplyItemDeserializesWithMissingBarcodesField() {
        // Old JSON on disk has no barcodes field — must deserialize cleanly as empty list
        val json = """{"id":"i1","categoryId":"c1","name":"Screws","notes":null,
            "fields":{},"customFields":{},"attachmentIds":[],"createdAt":"","updatedAt":""}"""
        val item = gson.fromJson(json, StoredSupplyItem::class.java)
        assertEquals(emptyList<String>(), item.barcodes)
    }

    @Test
    fun resolvedSupplyItemCarriesBarcodesFromStoredItem() {
        val basePath = createTempBasePath()
        val itemsDir = File(basePath, ".supply/items").also { it.mkdirs() }
        val stored = StoredSupplyItem(
            id = "i1", categoryId = "c1", name = "Screws", notes = null,
            fields = emptyMap(), customFields = emptyMap(), attachmentIds = emptyList(),
            barcodes = listOf("CODE128-ABC", "QR-XYZ"),
            createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z"
        )
        File(itemsDir, "i1.json").writeText(gson.toJson(stored))
        val result = SupplyRepository(basePath).getItem("i1")
        assertEquals(listOf("CODE128-ABC", "QR-XYZ"), result?.barcodes)
    }

    @Test
    fun updateItemBarcodesWritesBarcodesFieldAtomically() {
        val basePath = createTempBasePath()
        val itemsDir = File(basePath, ".supply/items").also { it.mkdirs() }
        val stored = StoredSupplyItem(
            id = "i1", categoryId = "c1", name = "Screws", notes = null,
            fields = emptyMap(), customFields = emptyMap(), attachmentIds = emptyList(),
            barcodes = emptyList(), createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z"
        )
        File(itemsDir, "i1.json").writeText(gson.toJson(stored))

        SupplyRepository(basePath).updateItemBarcodes("i1", listOf("ABC-123", "QR-456"))

        val persisted = gson.fromJson(File(itemsDir, "i1.json").readText(), StoredSupplyItem::class.java)
        assertEquals(listOf("ABC-123", "QR-456"), persisted.barcodes)
        assertTrue(itemsDir.listFiles().orEmpty().none { it.name.contains(".tmp-") })
    }

    @Test
    fun updateItemBarcodesReturnsNullWhenItemDoesNotExist() {
        val basePath = createTempBasePath()

        val result = SupplyRepository(basePath).updateItemBarcodes("missing-item", listOf("X"))

        assertEquals(null, result)
    }

    private fun createTempBasePath(): String {
        return Files.createTempDirectory("supply-repository-test").toFile().absolutePath
    }
}
