package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.DoorPreviewData
import java.util.Locale

internal enum class DoorAssemblySort(val label: String) {
    CABINET("Cabinet #"),
    DOOR_NAME("Door name"),
    LARGEST_FIRST("Largest first"),
    SMALLEST_FIRST("Smallest first")
}

private val sortTextChunks = Regex("\\d+|\\D+")

/** Numeric runs sort by value (2 before 10); cabinet suffixes keep their natural position. */
private fun compareDoorSortText(left: String, right: String): Int {
    val a = left.trim().lowercase(Locale.US)
    val b = right.trim().lowercase(Locale.US)
    if (a.isBlank() || b.isBlank()) return compareValues(a.isBlank(), b.isBlank())
    val aChunks = sortTextChunks.findAll(a).map { it.value }.toList()
    val bChunks = sortTextChunks.findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(aChunks.size, bChunks.size)) {
        val x = aChunks[i]
        val y = bChunks[i]
        val result = if (x.first().isDigit() && y.first().isDigit()) {
            val xn = x.trimStart('0').ifEmpty { "0" }
            val yn = y.trimStart('0').ifEmpty { "0" }
            compareValues(xn.length, yn.length).takeIf { it != 0 } ?: xn.compareTo(yn)
        } else x.compareTo(y)
        if (result != 0) return result
    }
    return aChunks.size.compareTo(bChunks.size)
}

private val cabinetDoorOrder = Comparator<DoorPreviewData> { a, b -> compareDoorSortText(a.cabinet, b.cabinet) }
    .then(Comparator { a, b -> compareDoorSortText(a.label, b.label) })
    .then(Comparator { a, b -> compareDoorSortText(a.doorId, b.doorId) })
    .thenBy { it.doorId }

/** Visible loaded doors; size order follows the cutlists' width-then-height convention. */
internal fun doorAssemblyDoors(
    previews: CvJobDoorPreviews?,
    sort: DoorAssemblySort = DoorAssemblySort.CABINET
): List<DoorPreviewData> {
    val loaded = previews as? CvJobDoorPreviews.Loaded ?: return emptyList()
    val order = when (sort) {
        DoorAssemblySort.CABINET -> cabinetDoorOrder
        DoorAssemblySort.DOOR_NAME -> Comparator<DoorPreviewData> { a, b -> compareDoorSortText(a.label, b.label) }
            .then(cabinetDoorOrder)
        DoorAssemblySort.LARGEST_FIRST -> compareByDescending<DoorPreviewData> { it.geometry.width }
            .thenByDescending { it.geometry.height }.then(cabinetDoorOrder)
        DoorAssemblySort.SMALLEST_FIRST -> compareBy<DoorPreviewData> { it.geometry.width }
            .thenBy { it.geometry.height }.then(cabinetDoorOrder)
    }
    return loaded.doors.sortedWith(order)
}
