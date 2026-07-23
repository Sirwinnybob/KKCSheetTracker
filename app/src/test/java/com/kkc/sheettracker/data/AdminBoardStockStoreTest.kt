package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AdminBoardStockStoreTest {

    private fun writeBoardStock(json: String): File {
        val baseDir = Files.createTempDirectory("admin-board-stock-test").toFile()
        val metaDir = File(baseDir, "123 - Test Job/.metadata/admin").apply { mkdirs() }
        File(metaDir, "board_stock.json").writeText(json)
        return baseDir
    }

    @Test
    fun loadAdminBoardStock_parsesMoldingIdAndType() {
        val baseDir = writeBoardStock(
            """
            {"schemaVersion": 1, "items": [
              {"id": "x1", "material": "PG", "name": "3 1/4\" Flat", "type": "crown", "moldingId": "Crown:151", "feet": 80.0}
            ]}
            """.trimIndent()
        )

        val items = loadAdminBoardStock(baseDir, "123 - Test Job")

        assertEquals(1, items.size)
        assertEquals("Crown:151", items[0].moldingId)
        assertEquals("crown", items[0].type)
    }

    @Test
    fun loadAdminBoardStock_handlesMissingMoldingIdAndType() {
        val baseDir = writeBoardStock(
            """
            {"schemaVersion": 1, "items": [
              {"id": "x1", "material": "Maple", "name": "Face frame stock", "feet": 20.0}
            ]}
            """.trimIndent()
        )

        val items = loadAdminBoardStock(baseDir, "123 - Test Job")

        assertEquals(1, items.size)
        assertNull(items[0].moldingId)
        assertNull(items[0].type)
    }

    @Test
    fun loadAdminBoardStock_handlesExplicitJsonNullMoldingIdWithoutDroppingTheWholeFile() {
        val baseDir = writeBoardStock(
            """
            {"schemaVersion": 1, "items": [
              {"id": "x1", "material": "PG", "name": "3 1/4\" Flat", "type": "crown", "moldingId": "Crown:151", "feet": 80.0},
              {"id": "x2", "material": "Maple", "name": "Toe Skins", "type": null, "moldingId": null, "feet": 110.0}
            ]}
            """.trimIndent()
        )

        val items = loadAdminBoardStock(baseDir, "123 - Test Job")

        assertEquals(2, items.size)
        assertEquals("Crown:151", items[0].moldingId)
        assertNull(items[1].moldingId)
        assertNull(items[1].type)
    }

    @Test
    fun loadAdminBoardStock_handlesExplicitJsonNullModeWithoutDroppingTheWholeFile() {
        val baseDir = writeBoardStock(
            """
            {"schemaVersion": 1, "items": [
              {"id": "x1", "material": "PG", "name": "3 1/4\" Flat", "mode": "bd_ft", "feet": 80.0},
              {"id": "x2", "material": "Maple", "name": "Toe Skins", "mode": null, "feet": 110.0}
            ]}
            """.trimIndent()
        )

        val items = loadAdminBoardStock(baseDir, "123 - Test Job")

        assertEquals(2, items.size)
        assertEquals("bd_ft", items[0].mode)
        assertEquals("bd_ft", items[1].mode)
    }

    @Test
    fun loadAdminBoardStock_handlesExplicitJsonNullCreatedAtWithoutDroppingTheWholeFile() {
        val baseDir = writeBoardStock(
            """
            {"schemaVersion": 1, "items": [
              {"id": "x1", "material": "PG", "name": "3 1/4\" Flat", "createdAt": "2026-01-01", "feet": 80.0},
              {"id": "x2", "material": "Maple", "name": "Toe Skins", "createdAt": null, "feet": 110.0}
            ]}
            """.trimIndent()
        )

        val items = loadAdminBoardStock(baseDir, "123 - Test Job")

        assertEquals(2, items.size)
        assertEquals("2026-01-01", items[0].createdAt)
        assertEquals("", items[1].createdAt)
    }

    @Test
    fun loadAdminBoardStock_handlesExplicitJsonNullCreatedByWithoutDroppingTheWholeFile() {
        val baseDir = writeBoardStock(
            """
            {"schemaVersion": 1, "items": [
              {"id": "x1", "material": "PG", "name": "3 1/4\" Flat", "createdBy": "Winston", "feet": 80.0},
              {"id": "x2", "material": "Maple", "name": "Toe Skins", "createdBy": null, "feet": 110.0}
            ]}
            """.trimIndent()
        )

        val items = loadAdminBoardStock(baseDir, "123 - Test Job")

        assertEquals(2, items.size)
        assertEquals("Winston", items[0].createdBy)
        assertEquals("", items[1].createdBy)
    }
}
