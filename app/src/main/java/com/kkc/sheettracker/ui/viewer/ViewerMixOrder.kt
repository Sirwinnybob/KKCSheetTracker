package com.kkc.sheettracker.ui.viewer

import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.ui.components.mixCatalogSnapshot

sealed interface ViewerOrder {
    data class Mix(val pages: List<Int>, val unmatched: List<String>) : ViewerOrder
    data class Natural(val reason: String?) : ViewerOrder
}

internal fun liveViewerOrder(material: Material, catalog: MixFilesCatalog?, mixFile: String): ViewerOrder {
    if (catalog == null) return ViewerOrder.Natural("mix order unavailable")
    val file = catalog.files.firstOrNull { it.file.equals(mixFile, ignoreCase = true) && it.kind != MixFileKind.REVISION }
        ?: return ViewerOrder.Natural("mix was renamed or deleted")
    val items = matchEntries(file.entries, sheetPages(material.metadata?.pages.orEmpty()))
    return ViewerOrder.Mix(viewerPageOrder(items), items.filterIsInstance<MixItem.Unmatched>().map { it.pgm })
}

/** A removed sheet advances through the previous displayed order, never numeric PDF order. */
internal fun retainedViewerPage(current: Int, previous: List<Int>, next: List<Int>, fallback: Int): Int {
    if (current in next) return current
    val oldIndex = previous.indexOf(current)
    if (oldIndex >= 0) previous.drop(oldIndex + 1).firstOrNull { it in next }?.let { return it }
    return next.firstOrNull() ?: fallback
}

internal fun viewerOrderForState(material: Material, state: CatalogState?, mixFile: String?): ViewerOrder = when {
    mixFile == null -> ViewerOrder.Natural(null)
    state is CatalogState.Outdated -> ViewerOrder.Natural("The CNC mix service needs updating; showing natural sheet order.")
    else -> liveViewerOrder(material, state.mixCatalogSnapshot(), mixFile)
}

internal fun Material.visibleSheetPages(): List<Int> {
    val metadataPages = metadata?.pages.orEmpty()
    val visibleFromMetadata = metadataPages
        .filterNot { it.hiddenInApp || it.trackingExcluded || it.isPartListContinuation }
        .mapNotNull { md ->
            val p = md.pageNumber
            p.takeIf { it in 1..pageCount }
        }
        .distinct()
        .sorted()
    return if (visibleFromMetadata.isNotEmpty()) visibleFromMetadata else (1..pageCount).toList()
}
