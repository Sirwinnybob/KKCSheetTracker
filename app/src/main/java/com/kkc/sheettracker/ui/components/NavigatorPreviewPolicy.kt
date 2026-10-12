package com.kkc.sheettracker.ui.components

/** Only decode previews around the viewport; never sweep an entire PDF behind an open sheet. */
internal fun navigatorPreviewPages(rowPages: List<Int>, visibleRowIndices: List<Int>): List<Int> {
    if (rowPages.isEmpty()) return emptyList()
    val visible = visibleRowIndices.filter { it in rowPages.indices }
    if (visible.isEmpty()) return rowPages.take(6)
    return visible.flatMap { index ->
        (maxOf(0, index - 2)..minOf(rowPages.lastIndex, index + 2)).toList()
    }.distinct().sorted().map(rowPages::get)
}
