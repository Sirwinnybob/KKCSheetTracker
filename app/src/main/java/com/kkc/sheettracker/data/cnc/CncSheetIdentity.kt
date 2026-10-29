package com.kkc.sheettracker.data.cnc

import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.PageMetadata

internal fun inferSheetFiles(pageMeta: PageMetadata?): List<String> {
    val fromSidecar = pageMeta?.sheetFiles?.filter { it.isNotBlank() }?.distinct().orEmpty()
    if (fromSidecar.isNotEmpty()) return fromSidecar
    val single = pageMeta?.sheetId?.trim().orEmpty()
    if (single.isBlank()) return emptyList()
    return when (single.lastOrNull()) {
        'Z' -> listOf(single, single.dropLast(1) + "A")
        'A' -> listOf(single.dropLast(1) + "Z", single)
        else -> listOf(single)
    }
}

internal fun Material.resolveHeadPage(page: Int): Int {
    val meta = metadata?.pages?.firstOrNull { it.pageNumber == page } ?: return page
    if (!(meta.hiddenInApp || meta.trackingExcluded || meta.isPartListContinuation)) return page
    val head = meta.continuationHeadPage ?: return page
    return if (head in 1..pageCount) head else page
}

