package com.kkc.sheettracker.ui.viewer

import com.kkc.sheettracker.data.cnc.CncMachine
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

/**
 * The mix a machine is running, found by the PGM it has loaded. Null unless exactly one mix on the machine lists
 * that PGM, so an unknown or shared PGM falls back to natural order rather than guessing. Machines without FRZ
 * support only ever order by the plain mix file.
 */
internal fun runningMixSelection(
    catalog: MixFilesCatalog?,
    marks: com.kkc.sheettracker.data.cnc.CncSheetMarks?,
    frzSupported: Boolean,
): com.kkc.sheettracker.data.mixservice.ViewerMixSelection? {
    val loadedName = when (val loaded = marks?.loaded) {
        is com.kkc.sheettracker.data.cnc.CncLoadedMark.Matched -> loaded.name
        is com.kkc.sheettracker.data.cnc.CncLoadedMark.Unmatched -> loaded.name
        null -> null
    }
    val stem = loadedName?.trim()?.substringBeforeLast('.', loadedName.trim())?.lowercase(java.util.Locale.ROOT)
    if (catalog == null || stem.isNullOrEmpty()) return null
    val matches = groupMixFiles(catalog).mapNotNull { group ->
        val file = (if (frzSupported) group.frz ?: group.mix else group.mix) ?: return@mapNotNull null
        val holds = file.entries.any { it.pgm.trim().substringBeforeLast('.').lowercase(java.util.Locale.ROOT) == stem }
        if (holds) com.kkc.sheettracker.data.mixservice.ViewerMixSelection(group.title, file.file) else null
    }
    return matches.singleOrNull()
}

/** A removed sheet advances through the previous displayed order, never numeric PDF order. */
internal fun retainedViewerPage(current: Int, previous: List<Int>, next: List<Int>, fallback: Int): Int {
    if (current in next) return current
    val oldIndex = previous.indexOf(current)
    if (oldIndex >= 0) previous.drop(oldIndex + 1).firstOrNull { it in next }?.let { return it }
    return next.firstOrNull() ?: fallback
}

/** A machine without FRZ support (Blue SCM) never orders by an FRZ, even from a restored or deep-linked route. */
internal fun viewerOrderForState(material: Material, state: CatalogState?, mixFile: String?, frzSupported: Boolean = true,
    machineName: String? = null): ViewerOrder = when {
    mixFile == null -> ViewerOrder.Natural(null)
    !frzSupported && mixFile.endsWith(".frz", ignoreCase = true) ->
        ViewerOrder.Natural("${machineName ?: "This CNC"} doesn't run FRZ files; showing natural sheet order.")
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

/** "Red SCM and Blue SCM" for a material whose folder is on several machines. */
internal fun machineChoiceLabel(machines: List<CncMachine>): String = "On " + machines.joinToString(" and ") { it.displayName }

/** Prefixes the owning machine to the viewer's mix notice, so mix context always names its CNC. */
internal fun viewerMixNotice(notice: String?, machine: CncMachine?): String? = when {
    machine == null -> notice
    notice == null -> machine.displayName
    else -> "${machine.displayName} · $notice"
}
