package com.kkc.sheettracker.ui.components.doorcutlist

import com.kkc.sheettracker.data.models.CabinetPageDetail
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import java.util.Locale

/**
 * Data layer for the generated Door Panel Cut List print. Pure Kotlin (no android.*), so every
 * rule here is covered by plain JUnit. Rows come from .metadata/hardwoods/cutlist_index.json in
 * CV order; rooms come from .metadata/cabinet_sheet_index.json.
 */

const val UNASSIGNED_ROOM_KEY = "Unassigned"

data class CabinetCount(val cabinet: String, val count: Int)

private val CABINET_TOKEN = Regex("""^\s*(.+?)\s*(?:\(\s*(\d+)\s*\))?\s*$""")
private val ROOM_NUMBER = Regex("""#\s*(\d+)""")
private val ROOM_LABEL = Regex("""^Room\s*#\s*\d+\s*\((.*)\)\s*$""", RegexOption.IGNORE_CASE)

/** CV cabinet text → (cabinet, count) pairs: "9 (3), 11 (2), 13" → [9×3, 11×2, 13×1]. */
fun parseCabinetText(raw: String): List<CabinetCount> =
    raw.split(',').mapNotNull { token ->
        val match = CABINET_TOKEN.find(token) ?: return@mapNotNull null
        val cabinet = match.groupValues[1].trim()
        if (cabinet.isEmpty()) return@mapNotNull null
        CabinetCount(cabinet, match.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1)
    }

fun formatCabinets(counts: List<CabinetCount>): String =
    counts.joinToString(", ") { if (it.count > 1) "${it.cabinet} (${it.count})" else it.cabinet }

/** Numbered rooms ("Room #N (...)") by N, then other labels alphabetically, Unassigned last. */
val roomKeyComparator: Comparator<String> = compareBy<String>(
    { key ->
        when {
            key == UNASSIGNED_ROOM_KEY -> 2
            ROOM_NUMBER.containsMatchIn(key) -> 0
            else -> 1
        }
    },
    { key -> ROOM_NUMBER.find(key)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
    { key -> key.uppercase(Locale.US) }
)

/** "Room #3 (VANITIES)" → "VANITIES"; anything else is shown as-is. */
fun roomDisplayName(key: String): String =
    ROOM_LABEL.find(key.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: key.trim()

/**
 * Cabinet → CV room label. First room found wins, scanning in priority order:
 * combined assembly (FF + FL sets), each assembly source, top-level assembly (first PDF only),
 * then Plans & Elevations. Pages with a blank room are skipped.
 */
fun buildCabinetRoomMap(index: CabinetSheetIndex?): Map<String, String> {
    if (index == null) return emptyMap()
    // Gson can leave these null despite non-null Kotlin types; keep the safe calls.
    val assembly = index.documents?.assembly
    val pageSets: List<Map<String, CabinetPageDetail>> = buildList {
        add(assembly?.virtualCombined?.pageDetails.orEmpty())
        assembly?.sources.orEmpty().forEach { add(it.pageDetails.orEmpty()) }
        add(assembly?.pageDetails.orEmpty())
        add(index.documents?.plansElevations?.pageDetails.orEmpty())
    }
    val result = linkedMapOf<String, String>()
    pageSets.forEach { pages ->
        pages.values.forEach { page ->
            val room = page.room?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            page.cabinets.orEmpty().forEach { cabinet ->
                val key = cabinet.trim()
                if (key.isNotEmpty()) result.putIfAbsent(key, room)
            }
        }
    }
    return result
}
