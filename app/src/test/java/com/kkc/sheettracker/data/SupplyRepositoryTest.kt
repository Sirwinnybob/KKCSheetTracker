package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kkc.sheettracker.data.models.SupplyCategory
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

    private fun createTempBasePath(): String {
        return Files.createTempDirectory("supply-repository-test").toFile().absolutePath
    }
}
