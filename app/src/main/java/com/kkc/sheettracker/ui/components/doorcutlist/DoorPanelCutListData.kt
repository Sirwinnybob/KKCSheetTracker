package com.kkc.sheettracker.ui.components.doorcutlist

import com.google.gson.Gson
import com.kkc.sheettracker.data.filterDoorCutRowsToSheets
import com.kkc.sheettracker.data.loadHardwoodsCutlistIndexRawJson
import com.kkc.sheettracker.data.models.CabinetPageDetail
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.parseDoorCutUnitTypeMetadata
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

const val UNKNOWN_MATERIAL = "(No material)"

data class RoomGroup(val roomKey: String, val cabinets: List<CabinetCount>, val qty: Int)

/** One CV row with its cabinets resolved to rooms. [groups] is never empty, in room order. */
data class ResolvedCutListRow(
    val material: String,
    val qty: Int,
    val description: String,
    val width: String,
    val length: String,
    val cabinetText: String,
    val groups: List<RoomGroup>,
)

/**
 * Resolves a CV row's cabinets to rooms. A multi-room row is split by cabinet counts only when
 * those counts add up to the row qty; otherwise the counts cannot say which room gets which
 * pieces, so the row stays whole under Unassigned (keeping every piece and flagging the row).
 */
fun resolveRow(row: HardwoodCutlistRow, cabinetRooms: Map<String, String>): ResolvedCutListRow {
    // Gson can leave String/List fields null; keep the orEmpty() calls.
    val rawText = row.rawCabinetText.orEmpty()
    val counts = parseCabinetText(rawText).ifEmpty {
        row.cabinets.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.map { CabinetCount(it, 1) }
    }
    val byRoom = counts.groupBy { cabinetRooms[it.cabinet] ?: UNASSIGNED_ROOM_KEY }
    val groups = when (byRoom.size) {
        0 -> listOf(RoomGroup(UNASSIGNED_ROOM_KEY, emptyList(), row.qty))
        1 -> byRoom.entries.single().let { (room, cabs) -> listOf(RoomGroup(room, cabs, row.qty)) }
        else -> {
            val split = byRoom.keys.sortedWith(roomKeyComparator).map { room ->
                val cabs = byRoom.getValue(room)
                RoomGroup(room, cabs, cabs.sumOf { it.count })
            }
            if (split.sumOf { it.qty } == row.qty) split
            else listOf(RoomGroup(UNASSIGNED_ROOM_KEY, counts, row.qty))
        }
    }
    return ResolvedCutListRow(
        material = row.material?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_MATERIAL,
        qty = row.qty,
        description = row.description.orEmpty(),
        width = row.width.orEmpty(),
        length = row.length.orEmpty(),
        cabinetText = rawText.ifBlank { formatCabinets(counts) },
        groups = groups,
    )
}

/** Everything loaded once per print-sheet open. [rows] are sheet rows in CV order. */
data class DoorPanelCutListSource(
    val jobFolderName: String,
    val rows: List<ResolvedCutListRow>,
) {
    val materials: List<String> = rows.map { it.material }.distinct()
    val roomKeys: List<String> =
        rows.flatMap { r -> r.groups.map { it.roomKey } }.distinct().sortedWith(roomKeyComparator)
}

data class CutListSelection(
    val roomTags: Boolean,
    val materials: Set<String>,
    val rooms: Set<String>,
)

fun isDefaultUncheckedMaterial(material: String): Boolean = material.contains("MDF", ignoreCase = true)

fun defaultSelection(source: DoorPanelCutListSource): CutListSelection = CutListSelection(
    roomTags = false,
    materials = source.materials.filterNot(::isDefaultUncheckedMaterial).toSet(),
    rooms = source.roomKeys.toSet(),
)

data class MaterialOption(val material: String, val pieces: Int, val checked: Boolean)
data class RoomOption(val key: String, val displayName: String, val pieces: Int, val checked: Boolean)

private fun ResolvedCutListRow.qtyIn(rooms: Set<String>): Int =
    groups.filter { it.roomKey in rooms }.sumOf { it.qty }

/** Material checkbox rows; piece counts exclude unchecked rooms. */
fun materialOptions(source: DoorPanelCutListSource, selection: CutListSelection): List<MaterialOption> =
    source.materials.map { material ->
        MaterialOption(
            material = material,
            pieces = source.rows.filter { it.material == material }.sumOf { it.qtyIn(selection.rooms) },
            checked = material in selection.materials,
        )
    }

/** Room checkbox rows; piece counts exclude unchecked materials. */
fun roomOptions(source: DoorPanelCutListSource, selection: CutListSelection): List<RoomOption> =
    source.roomKeys.map { key ->
        RoomOption(
            key = key,
            displayName = roomDisplayName(key),
            pieces = source.rows.filter { it.material in selection.materials }.sumOf { it.qtyIn(setOf(key)) },
            checked = key in selection.rooms,
        )
    }

fun showRoomFilter(source: DoorPanelCutListSource): Boolean = source.roomKeys.size > 1

val ROOM_PALETTE: List<Int> = listOf(
    0xFFF6C85F, 0xFF9FD8A0, 0xFF8EC5F0, 0xFFF4A3A3, 0xFFC7A6EA,
    0xFF7FD3CF, 0xFFF7B077, 0xFFE9A6D2, 0xFFC9D66B, 0xFFB5B5E8,
).map { it.toInt() }

val UNASSIGNED_ROOM_COLOR: Int = 0xFFD9D9D9.toInt()

/**
 * Distinct color per room, shuffled per job so neighbouring jobs look different, but stable for
 * the same job on every tablet (String.hashCode and java.util.Random are both specified).
 */
fun assignRoomColors(roomKeys: List<String>, seed: String): Map<String, Int> {
    val palette = ROOM_PALETTE.toMutableList()
    java.util.Collections.shuffle(palette, java.util.Random(seed.hashCode().toLong()))
    val result = linkedMapOf<String, Int>()
    roomKeys.distinct()
        .filter { it != UNASSIGNED_ROOM_KEY }
        .sortedWith(roomKeyComparator)
        .forEachIndexed { i, key -> result[key] = palette[i % palette.size] }
    result[UNASSIGNED_ROOM_KEY] = UNASSIGNED_ROOM_COLOR
    return result
}

data class CutListRow(
    val qty: Int,
    val description: String,
    val width: String,
    val length: String,
    val cabinetText: String,
    /** Set only in Room Tags mode. */
    val roomKey: String?,
)

data class CutListMaterialSection(val material: String, val unitsLabel: String, val rows: List<CutListRow>)

data class CutListRoom(val key: String, val displayName: String, val color: Int)

data class DoorPanelCutListModel(
    val jobTitle: String,
    val dateText: String,
    val roomTags: Boolean,
    /** Checked room display names for the header note; empty when every room is checked. */
    val filteredRoomNames: List<String>,
    /** Room Tags only: rooms present in the output, room order, with their colors. */
    val rooms: List<CutListRoom>,
    val materials: List<CutListMaterialSection>,
) {
    val totalPieces: Int get() = materials.sumOf { section -> section.rows.sumOf { it.qty } }
    val canPrint: Boolean get() = materials.any { it.rows.isNotEmpty() }
}

fun cutListJobNumber(jobFolderName: String): String = jobFolderName.substringBefore(" - ").trim()

fun buildCutListModel(
    source: DoorPanelCutListSource,
    selection: CutListSelection,
    dateText: String,
): DoorPanelCutListModel {
    val sections = source.materials
        .filter { it in selection.materials }
        .mapNotNull { material ->
            val rows = source.rows
                .filter { it.material == material }
                .flatMap { outputRows(it, selection) }
            rows.takeIf { it.isNotEmpty() }?.let { CutListMaterialSection(material, "Sheet", it) }
        }
    val colors = assignRoomColors(source.roomKeys, source.jobFolderName)
    val usedRooms = if (selection.roomTags) {
        sections.flatMap { s -> s.rows.mapNotNull { it.roomKey } }
            .distinct()
            .sortedWith(roomKeyComparator)
            .map { CutListRoom(it, roomDisplayName(it), colors[it] ?: UNASSIGNED_ROOM_COLOR) }
    } else {
        emptyList()
    }
    val filteredRoomNames = if (source.roomKeys.all { it in selection.rooms }) {
        emptyList()
    } else {
        source.roomKeys.filter { it in selection.rooms }.map(::roomDisplayName)
    }
    return DoorPanelCutListModel(
        jobTitle = source.jobFolderName,
        dateText = dateText,
        roomTags = selection.roomTags,
        filteredRoomNames = filteredRoomNames,
        rooms = usedRooms,
        materials = sections,
    )
}

private fun outputRows(row: ResolvedCutListRow, selection: CutListSelection): List<CutListRow> {
    val kept = row.groups.filter { it.roomKey in selection.rooms }
    if (kept.isEmpty()) return emptyList()
    fun make(qty: Int, cabinetText: String, roomKey: String?) =
        CutListRow(qty, row.description, row.width, row.length, cabinetText, roomKey)
    return when {
        selection.roomTags -> kept.map { group ->
            val text = if (row.groups.size == 1) row.cabinetText else formatCabinets(group.cabinets)
            make(group.qty, text, group.roomKey)
        }
        kept.size == row.groups.size -> listOf(make(row.qty, row.cabinetText, null))
        else -> listOf(make(kept.sumOf { it.qty }, formatCabinets(kept.flatMap { it.cabinets }), null))
    }
}

private val cutListGson = Gson()

/**
 * Sheet-unit Door Cut List rows (same rule as the Specialty door panels screen) in CV order,
 * resolved to rooms. Null when there is nothing printable; callers hide the section.
 */
fun buildDoorPanelCutListSource(
    jobFolderName: String,
    rawCutlistIndexJson: String?,
    cabinetIndex: CabinetSheetIndex?,
): DoorPanelCutListSource? {
    if (rawCutlistIndexJson.isNullOrBlank()) return null
    val index = runCatching { cutListGson.fromJson(rawCutlistIndexJson, HardwoodCutlistIndex::class.java) }
        .getOrNull() ?: return null
    val doorDoc = index.documents.orEmpty().firstOrNull { it.docType == HardwoodDocType.DOOR_CUT_LIST }
        ?: return null
    val unitTypes = parseDoorCutUnitTypeMetadata(rawCutlistIndexJson)
    val sheetRows = filterDoorCutRowsToSheets(doorDoc.rows.orEmpty(), unitTypes)
        .sortedWith(compareBy({ it.page }, { it.rowOrdinal }))
    if (sheetRows.isEmpty()) return null
    val cabinetRooms = buildCabinetRoomMap(cabinetIndex)
    return DoorPanelCutListSource(jobFolderName, sheetRows.map { resolveRow(it, cabinetRooms) })
}

/** File I/O wrapper; call on Dispatchers.IO. */
fun loadDoorPanelCutListSource(
    basePath: String,
    jobFolderName: String,
    cabinetIndex: CabinetSheetIndex?,
): DoorPanelCutListSource? = buildDoorPanelCutListSource(
    jobFolderName = jobFolderName,
    rawCutlistIndexJson = loadHardwoodsCutlistIndexRawJson(basePath, jobFolderName),
    cabinetIndex = cabinetIndex,
)
