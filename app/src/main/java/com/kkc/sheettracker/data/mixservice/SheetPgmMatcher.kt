package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.data.cnc.inferSheetFiles
import com.kkc.sheettracker.data.mixfiles.orderedPgmFiles

data class ManageCodeRow(
    val pageNumber: Int,
    val pgmFiles: List<String>,
    val editablePgm: String,
    val thumbnailPath: String?
)

fun buildManageCodeRows(pages: List<PageMetadata>): List<ManageCodeRow> =
    pages
        .filter { !it.trackingExcluded && !it.hiddenInApp && !it.isPartListContinuation }
        .sortedBy { it.pageNumber }
        .mapNotNull { page ->
            val files = orderedPgmFiles(inferSheetFiles(page))
            if (files.isEmpty()) null
            else ManageCodeRow(
                pageNumber = page.pageNumber,
                pgmFiles = files,
                editablePgm = files.last(),
                thumbnailPath = page.thumbnailPath
            )
        }

fun applyExistingOrder(rows: List<ManageCodeRow>, orderedPgms: List<String>): List<ManageCodeRow> {
    val indexOf = orderedPgms.withIndex().associate { (i, pgm) -> pgm to i }
    val (mapped, unmapped) = rows.partition { indexOf.containsKey(it.editablePgm) }
    return mapped.sortedBy { indexOf.getValue(it.editablePgm) } + unmapped
}
