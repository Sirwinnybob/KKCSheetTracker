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
    val naturalSet = naturalOrder.toSet()
    val rows = buildManageCodeRows(pages).filter { it.pageNumber in naturalSet }
    val ordered = applyExistingOrder(rows, mixPrograms).map { it.pageNumber }
    val orderedSet = ordered.toSet()
    val unresolved = naturalOrder.filter { it !in orderedSet }
    return ordered + unresolved
}
