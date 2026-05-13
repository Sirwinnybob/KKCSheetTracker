package com.kkc.sheettracker.ui.hardwoods

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.kkc.sheettracker.data.models.BoardStockRow
import com.kkc.sheettracker.data.models.BoardStockSource
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodRowProgress
import java.io.File
import java.math.BigDecimal
import java.util.Locale
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

internal fun applySkippedPartRowsToBoardStockRows(
    rows: List<BoardStockRow>,
    index: HardwoodCutlistIndex?,
    rowProgressMap: Map<Pair<String, String>, HardwoodRowProgress>
): List<BoardStockRow> {
    if (rows.isEmpty() || index == null || rowProgressMap.isEmpty()) return rows

    val skippedFeetByKey = mutableMapOf<String, Double>()
    val remainingFeetByKey = mutableMapOf<String, Double>()
    index.documents.forEach { doc ->
        val source = when (doc.docType) {
            HardwoodDocType.FACE_FRAME_CUT_LIST -> BoardStockSource.FRAME
            HardwoodDocType.NAILER_CUT_LIST -> BoardStockSource.NAILER
            HardwoodDocType.DOOR_CUT_LIST -> BoardStockSource.DOOR
            HardwoodDocType.DOOR_LIST -> null
        } ?: return@forEach

        doc.rows.forEach { row ->
            val state = rowProgressMap[doc.docType.name to row.rowId] ?: return@forEach
            if (!state.skipped) return@forEach

            val material = row.material?.trim().orEmpty()
            if (material.isBlank()) return@forEach
            val width = parseDimensionToken(row.width) ?: return@forEach
            val lengthInches = parseDimensionToken(row.length) ?: return@forEach
            val qty = row.qty.coerceAtLeast(0)
            if (qty <= 0) return@forEach

            val skippedFeet = (lengthInches * qty.toDouble()) / 12.0
            if (skippedFeet <= 0.0) return@forEach
            val key = boardStockKey(material, width, source)
            if (state.skipped) {
                skippedFeetByKey[key] = (skippedFeetByKey[key] ?: 0.0) + skippedFeet
            } else {
                remainingFeetByKey[key] = (remainingFeetByKey[key] ?: 0.0) + skippedFeet
            }
        }
    }

    if (skippedFeetByKey.isEmpty() && remainingFeetByKey.isEmpty()) return rows

    return rows.mapNotNull { row ->
        val key = boardStockKey(row.material, row.normalizedWidth, row.source)
        val remainingFeetFromRows = remainingFeetByKey[key]
        val adjustedFeet = when {
            // When we can derive remaining footage from unskipped rows, use that as source of truth.
            remainingFeetFromRows != null -> remainingFeetFromRows
            else -> {
                val skippedFeet = skippedFeetByKey[key] ?: 0.0
                (row.totalFeet - skippedFeet).coerceAtLeast(0.0)
            }
        }
        val adjustedRips = ceil(adjustedFeet / 10.0).toInt()
        if (adjustedRips <= 0) return@mapNotNull null
        row.copy(totalFeet = adjustedFeet, neededRips = adjustedRips)
    }
}

private fun boardStockKey(material: String, width: Double, source: BoardStockSource): String {
    val materialKey = material.trim().replace(Regex("""\s+"""), " ").uppercase(Locale.US)
    val widthKey = BigDecimal.valueOf(if (width == -0.0) 0.0 else width).stripTrailingZeros().toPlainString()
    return "$materialKey|$widthKey|${source.name}"
}

private fun parseDimensionToken(raw: String): Double? {
    val text = raw.trim().replace("\"", "")
    if (text.isEmpty()) return null
    text.toDoubleOrNull()?.let { return it }

    val mixed = Regex("""^(\d+)\s+(\d+)\s*/\s*(\d+)$""").matchEntire(text)
    if (mixed != null) {
        val whole = mixed.groupValues[1].toDoubleOrNull() ?: return null
        val num = mixed.groupValues[2].toDoubleOrNull() ?: return null
        val den = mixed.groupValues[3].toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
        return whole + (num / den)
    }

    val frac = Regex("""^(\d+)\s*/\s*(\d+)$""").matchEntire(text)
    if (frac != null) {
        val num = frac.groupValues[1].toDoubleOrNull() ?: return null
        val den = frac.groupValues[2].toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
        return num / den
    }

    val dashMixed = Regex("""^(\d+)-(\d+)\s*/\s*(\d+)$""").matchEntire(text)
    if (dashMixed != null) {
        val whole = dashMixed.groupValues[1].toDoubleOrNull() ?: return null
        val num = dashMixed.groupValues[2].toDoubleOrNull() ?: return null
        val den = dashMixed.groupValues[3].toDoubleOrNull()?.takeIf { it != 0.0 } ?: return null
        return whole + (num / den)
    }

    return null
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
