package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import java.util.Locale

private val MIXED_FRACTION_REGEX = Regex("""(-?\d+)\s+(\d+)\s*/\s*(\d+)""")
private val FRACTION_ONLY_REGEX = Regex("""(-?\d+)\s*/\s*(\d+)""")
private val DECIMAL_REGEX = Regex("""-?\d+(?:\.\d+)?""")
private val OPEN_PAREN_SPACING_REGEX = Regex("""\s*\(\s*""")
private val CLOSE_PAREN_SPACING_REGEX = Regex("""\s*\)\s*""")
private val CABINET_COUNT_REGEX = Regex("""^(\d{1,5})(?:\s*\(\s*(\d+)\s*\))?$""")

enum class HardwoodsRowSortMode {
    CutlistOrder,
    WidthDescLengthDesc,
    WidthAscLengthAsc
}

/** Groups for the Door Panels view only. */
enum class DoorPanelGroupMode {
    ByMaterial,
    ByCabinet,
    ByRoom
}

fun List<HardwoodCutlistRow>.sortedFor(mode: HardwoodsRowSortMode): List<HardwoodCutlistRow> {
    val comparator = when (mode) {
        HardwoodsRowSortMode.CutlistOrder -> cutlistOrderComparator()
        HardwoodsRowSortMode.WidthDescLengthDesc -> widthLengthComparator(descending = true)
        HardwoodsRowSortMode.WidthAscLengthAsc -> widthLengthComparator(descending = false)
    }
    return sortedWith(comparator)
}

fun cutlistOrderComparator(): Comparator<HardwoodCutlistRow> {
    return compareBy<HardwoodCutlistRow> { it.page }
        .thenBy { it.rowOrdinal }
        .thenBy { it.rowId }
}

fun widthLengthComparator(descending: Boolean): Comparator<HardwoodCutlistRow> {
    return Comparator { a, b ->
        val byWidth = compareDimensionText(a.width, b.width, descending)
        if (byWidth != 0) return@Comparator byWidth

        val byLength = compareDimensionText(a.length, b.length, descending)
        if (byLength != 0) return@Comparator byLength

        val byPage = a.page.compareTo(b.page)
        if (byPage != 0) return@Comparator byPage

        val byRowOrdinal = a.rowOrdinal.compareTo(b.rowOrdinal)
        if (byRowOrdinal != 0) return@Comparator byRowOrdinal

        a.rowId.compareTo(b.rowId)
    }
}

fun formatCabinetDisplay(rawCabinetText: String?, cabinets: List<String>): String {
    val raw = rawCabinetText?.trim().orEmpty()
    if (raw.isNotEmpty()) {
        parseCabinetCounts(raw)?.let { parsed ->
            if (parsed.isNotEmpty()) {
                return parsed.entries.joinToString(", ") { "${it.key}(${it.value})" }
            }
        }
        // Fallback to compact style if parsing fails.
        return raw
            .replace(OPEN_PAREN_SPACING_REGEX, "(")
            .replace(CLOSE_PAREN_SPACING_REGEX, ")")
            .replace(", ", ", ")
    }

    val fromList = cabinets
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(", ") { "$it(1)" }

    return if (fromList.isNotEmpty()) fromList else "None listed"
}

fun cutlistDimensionDisplay(row: HardwoodCutlistRow): String {
    val width = row.width.trim()
    val length = row.length.trim()
    return when {
        width.isNotEmpty() && length.isNotEmpty() -> "$width x $length"
        length.isNotEmpty() -> length
        width.isNotEmpty() -> width
        else -> ""
    }
}

private fun parseCabinetCounts(raw: String): LinkedHashMap<String, Int>? {
    val entries = LinkedHashMap<String, Int>()
    val chunks = raw.split(",")
    for (chunk in chunks) {
        val token = chunk.trim()
        if (token.isEmpty()) continue
        val m = CABINET_COUNT_REGEX.matchEntire(token) ?: return null
        val cab = m.groupValues[1]
        val qty = m.groupValues[2].toIntOrNull() ?: 1
        entries[cab] = (entries[cab] ?: 0) + qty
    }
    return entries
}

private fun compareDimensionText(a: String, b: String, descending: Boolean): Int {
    val ka = parseDimensionKey(a)
    val kb = parseDimensionKey(b)

    if (ka.numeric != null && kb.numeric != null) {
        val cmp = ka.numeric.compareTo(kb.numeric)
        if (cmp != 0) return if (descending) -cmp else cmp
    } else if (ka.numeric != null || kb.numeric != null) {
        // Numeric values sort ahead of non-numeric values.
        return if (ka.numeric != null) -1 else 1
    }

    val textCmp = ka.normalized.compareTo(kb.normalized)
    return if (descending) -textCmp else textCmp
}

private data class DimensionKey(
    val numeric: Double?,
    val normalized: String
)

private fun parseDimensionKey(value: String): DimensionKey {
    val normalized = value.trim().lowercase(Locale.US)
    return DimensionKey(
        numeric = parseDimensionNumber(normalized),
        normalized = normalized
    )
}

private fun parseDimensionNumber(normalized: String): Double? {
    if (normalized.isBlank()) return null

    val mixedFraction = MIXED_FRACTION_REGEX.find(normalized)
    if (mixedFraction != null) {
        val whole = mixedFraction.groupValues[1].toDoubleOrNull() ?: return null
        val numerator = mixedFraction.groupValues[2].toDoubleOrNull() ?: return null
        val denominator = mixedFraction.groupValues[3].toDoubleOrNull() ?: return null
        if (denominator == 0.0) return null
        return whole + (numerator / denominator)
    }

    val fractionOnly = FRACTION_ONLY_REGEX.find(normalized)
    if (fractionOnly != null) {
        val numerator = fractionOnly.groupValues[1].toDoubleOrNull() ?: return null
        val denominator = fractionOnly.groupValues[2].toDoubleOrNull() ?: return null
        if (denominator == 0.0) return null
        return numerator / denominator
    }

    return DECIMAL_REGEX.find(normalized)?.value?.toDoubleOrNull()
}
