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
            // null in JSON = admin marked NONE; absent or 0 = blank (filtered out in UI)
            val feetElement = obj.get("feet")
            val feet: Double? = if (feetElement == null || feetElement.isJsonNull) null
                                else feetElement.asDouble
            // Hide items with blank feet (0 or missing) — admin hasn't filled them in yet.
            // Items explicitly set to NONE (null) still show with a NONE label.
            if (feet != null && feet <= 0.0) return@mapNotNull null
            val mode      = obj.get("mode")?.asString?.trim()?.takeIf { it.isNotBlank() } ?: "bd_ft"
            val ripLength = obj.get("ripLength")?.takeIf { !it.isJsonNull }?.asInt?.takeIf { it == 8 || it == 10 } ?: 10
            val type = obj.get("type")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotBlank() }
            val moldingId = obj.get("moldingId")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotBlank() }
            AdminBoardStockItem(
                id        = id,
                material  = material,
                name      = name,
                feet      = feet,
                mode      = mode,
                ripLength = ripLength,
                createdAt = obj.get("createdAt")?.asString.orEmpty(),
                createdBy = obj.get("createdBy")?.asString.orEmpty(),
                moldingId = moldingId,
                type      = type
            )
        }
    }.getOrElse { emptyList() }
}
