package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.PageMetadata

/**
 * Reorders [naturalOrder] (the page list `Material.visibleSheetPages()` already computed) to
 * follow [mixPrograms] where possible. Output always contains exactly the same set of pages as
 * [naturalOrder] — never introduces a page it didn't contain, and never drops one either, even
 * if [buildManageCodeRows] couldn't resolve a sheet file for it (e.g. blank sidecar metadata):
 * any such page is appended at the end, in its original [naturalOrder] position.
 */
fun reorderVisiblePages(pages: List<PageMetadata>, naturalOrder: List<Int>, mixPrograms: List<String>): List<Int> {
    if (mixPrograms.isEmpty()) return naturalOrder
    val ordered = pagesForMix(pages, naturalOrder, mixPrograms)
    val orderedSet = ordered.toSet()
    val unresolved = naturalOrder.filter { it !in orderedSet }
    return ordered + unresolved
}

/**
 * Maps a selected mix's PGM membership to only the corresponding visible physical pages.
 * Unlike [reorderVisiblePages], this intentionally omits natural pages not owned by the mix.
 */
fun pagesForMix(pages: List<PageMetadata>, naturalOrder: List<Int>, programs: List<String>): List<Int> {
    val allowed = naturalOrder.toSet()
    val pagesByProgram = buildManageCodeRows(pages)
        .filter { it.pageNumber in allowed }
        .flatMap { row -> row.pgmFiles.map { program -> program to row.pageNumber } }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })

    return programs.flatMap { program -> pagesByProgram[program].orEmpty() }
        .filter { it in allowed }
        .distinct()
}
