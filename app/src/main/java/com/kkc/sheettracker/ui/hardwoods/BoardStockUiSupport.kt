package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.models.BoardStockRow
import com.kkc.sheettracker.data.models.BoardStockSource
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodRowProgress
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.ceil

@Suppress("UNUSED_PARAMETER")
internal fun buildBoardStockRows(
    basePath: String,
    jobFolderName: String,
    index: HardwoodCutlistIndex?
): List<BoardStockRow> {
    val repository = HardwoodsRepository(java.io.File(basePath))
    return repository.loadBoardStock(jobFolderName)
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

