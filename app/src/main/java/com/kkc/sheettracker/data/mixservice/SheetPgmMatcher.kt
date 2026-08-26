package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.ui.viewer.inferSheetFiles

data class ManageCodeRow(
    val pageNumber: Int,
    val pgmFiles: List<String>,
    val editablePgm: String
)

fun buildManageCodeRows(pages: List<PageMetadata>): List<ManageCodeRow> =
    pages
        .filter { !it.trackingExcluded && !it.hiddenInApp && !it.isPartListContinuation }
        .sortedBy { it.pageNumber }
        .mapNotNull { page ->
            val files = orderedPgmFiles(inferSheetFiles(page))
            if (files.isEmpty()) null
            else ManageCodeRow(pageNumber = page.pageNumber, pgmFiles = files, editablePgm = files.last())
        }

// inferSheetFiles' single-sheetId fallback branch (SheetViewerScreen.kt) returns [Z, A] order,
// while its sidecar-array branch preserves whatever order the sidecar itself has (normally A
// then Z). This app's business rule is A always precedes Z and A is never independently
// editable, so this always re-sorts any 2-file result so the Z-suffixed stem is last,
// regardless of which inferSheetFiles branch produced it.
private fun orderedPgmFiles(stems: List<String>): List<String> {
    val files = stems.map { "$it.pgm" }
    if (files.size != 2) return files
    return files.sortedBy { file -> if (file.substringBeforeLast('.').endsWith("Z", ignoreCase = true)) 1 else 0 }
}

fun applyExistingOrder(rows: List<ManageCodeRow>, orderedPgms: List<String>): List<ManageCodeRow> {
    val indexOf = orderedPgms.withIndex().associate { (i, pgm) -> pgm to i }
    val (mapped, unmapped) = rows.partition { indexOf.containsKey(it.editablePgm) }
    return mapped.sortedBy { indexOf.getValue(it.editablePgm) } + unmapped
}
