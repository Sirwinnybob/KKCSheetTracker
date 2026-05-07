package com.kkc.sheettracker.ui.hardwoods

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.kkc.sheettracker.data.models.BoardStockRow
import com.kkc.sheettracker.data.models.BoardStockSource
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodDocType
import java.io.File
import kotlin.math.ceil

internal fun buildBoardStockRows(
    basePath: String,
    jobFolderName: String,
    index: HardwoodCutlistIndex?
): List<BoardStockRow> {
    val aggregated = linkedMapOf<Triple<String, Double, BoardStockSource>, Double>()
    index?.documents.orEmpty().forEach { doc ->
        val source = when (doc.docType) {
            HardwoodDocType.FACE_FRAME_CUT_LIST -> BoardStockSource.FRAME
            HardwoodDocType.NAILER_CUT_LIST -> BoardStockSource.NAILER
            HardwoodDocType.DOOR_CUT_LIST -> BoardStockSource.DOOR
            HardwoodDocType.DOOR_LIST -> null
        } ?: return@forEach
        doc.totals.forEach { block ->
            val material = block.material.orEmpty().trim()
            val rowCount = maxOf(block.widthValues.size, block.lengthValues.size)
            for (i in 0 until rowCount) {
                val width = block.widthValues.getOrNull(i).orEmpty().trim().toDoubleOrNull() ?: continue
                val feet = block.lengthValues.getOrNull(i).orEmpty().trim().replace(",", "").toDoubleOrNull() ?: 0.0
                if (feet <= 0.0) continue
                val key = Triple(material, width, source)
                aggregated[key] = (aggregated[key] ?: 0.0) + feet
            }
        }
    }

    val rows = aggregated.map { (k, feet) ->
        BoardStockRow(
            stableKey = "board_stock|${k.first}|${k.second}|${k.third.name}",
            material = k.first,
            width = if (k.second % 1.0 == 0.0) k.second.toInt().toString() else k.second.toString(),
            normalizedWidth = k.second,
            source = k.third,
            sourceLabel = k.third.name,
            totalFeet = feet,
            neededRips = ceil(feet / 10.0).toInt()
        )
    }.toMutableList()

    rows += loadManualBoardStockRows(basePath, jobFolderName)
    return rows.sortedWith(
        compareBy<BoardStockRow, String>(String.CASE_INSENSITIVE_ORDER) { it.material }
            .thenByDescending { it.normalizedWidth }
            .thenBy {
                when (it.source) {
                    BoardStockSource.FRAME -> 0
                    BoardStockSource.NAILER -> 1
                    BoardStockSource.DOOR -> 2
                    BoardStockSource.MANUAL -> 3
                }
            }
    )
}

private fun loadManualBoardStockRows(basePath: String, jobFolderName: String): List<BoardStockRow> {
    val file = File(basePath, "$jobFolderName/.metadata/hardwoods/board_stock_manual.json")
    if (!file.exists() || !file.isFile) return emptyList()
    return try {
        val rootObj = Gson().fromJson(file.readText(), JsonObject::class.java)
        val entries = rootObj?.getAsJsonArray("entries") ?: JsonArray()
        entries.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val material = obj.get("material")?.asString?.trim().orEmpty()
            val width = obj.get("width")?.asString?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            val feet = obj.get("totalFeet")?.asDouble ?: return@mapNotNull null
            if (feet <= 0.0) return@mapNotNull null
            BoardStockRow(
                stableKey = "board_stock|$material|$width|MANUAL",
                material = material,
                width = if (width % 1.0 == 0.0) width.toInt().toString() else width.toString(),
                normalizedWidth = width,
                source = BoardStockSource.MANUAL,
                sourceLabel = BoardStockSource.MANUAL.name,
                totalFeet = feet,
                neededRips = ceil(feet / 10.0).toInt(),
                manualCategory = obj.get("category")?.asString,
                manualSubtype = obj.get("subtype")?.asString,
                notes = obj.get("notes")?.asString
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
