package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.PageMetadata

/**
 * Reorders [naturalOrder] (the page list `Material.visibleSheetPages()` already computed) to
 * follow [mixPrograms] where possible, without ever introducing or dropping a page that
 * [naturalOrder] didn't already contain.
 */
fun reorderVisiblePages(pages: List<PageMetadata>, naturalOrder: List<Int>, mixPrograms: List<String>): List<Int> {
    if (mixPrograms.isEmpty()) return naturalOrder
    val naturalSet = naturalOrder.toSet()
    val rows = buildManageCodeRows(pages).filter { it.pageNumber in naturalSet }
    return applyExistingOrder(rows, mixPrograms).map { it.pageNumber }
}
