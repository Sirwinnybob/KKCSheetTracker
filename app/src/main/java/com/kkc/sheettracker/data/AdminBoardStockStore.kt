package com.kkc.sheettracker.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkc.sheettracker.data.models.AdminBoardStockItem
import java.io.File

/**
 * Reads the admin-managed board stock list for a job.
 * File lives at: {baseDir}/{jobFolderName}/.metadata/admin/board_stock.json
 * This is separate from the hardwoods rip-cut board stock in .metadata/hardwoods/.
 */
fun loadAdminBoardStock(baseDir: File, jobFolderName: String): List<AdminBoardStockItem> {
    val file = File(baseDir, "$jobFolderName/.metadata/admin/board_stock.json")
    if (!file.exists() || !file.isFile) return emptyList()
    return runCatching {
        val root = JsonParser.parseString(file.readText()) as? JsonObject ?: return emptyList()
        val entries = root.getAsJsonArray("items") ?: return emptyList()
        entries.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id       = obj.get("id")?.asString?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name     = obj.get("name")?.asString?.trim().orEmpty()
            val material = obj.get("material")?.asString?.trim().orEmpty()
            val feet     = obj.get("feet")?.asDouble ?: 0.0
            AdminBoardStockItem(
                id        = id,
                material  = material,
                name      = name,
                feet      = feet,
                createdAt = obj.get("createdAt")?.asString.orEmpty(),
                createdBy = obj.get("createdBy")?.asString.orEmpty()
            )
        }
    }.getOrElse { emptyList() }
}
