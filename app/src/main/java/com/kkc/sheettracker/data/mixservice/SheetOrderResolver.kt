package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.StatusCounts

data class ViewerMixSelection(
    val name: String,
    val pageOrder: List<Int>
)

data class MaterialMixEntry(
    val material: Material,
    val title: String,
    val mixSelection: ViewerMixSelection?
)

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

fun resolveMaterialMixEntries(
    materials: List<Material>,
    mixes: List<MixDefinition>?
): List<MaterialMixEntry> = materials.flatMap { material ->
    val materialMixes = mixes.orEmpty().filter { mix ->
        mix.name.isNotBlank() && mix.material.trim().equals(material.materialName.trim(), ignoreCase = true)
    }
    val naturalOrder = materialTrackablePages(material)
    val resolvedMixes = materialMixes.mapNotNull { mix ->
        val pageOrder = resolveMixPageOrder(
                pages = material.metadata?.pages.orEmpty(),
                naturalOrder = naturalOrder,
                mixPrograms = mix.programs
            )
        if (pageOrder.isEmpty()) {
            null
        } else {
            MaterialMixEntry(
                material = material,
                title = "${mix.name} - ${material.materialName}",
                mixSelection = ViewerMixSelection(mix.name, pageOrder)
            )
        }
    }
    resolvedMixes.ifEmpty {
        listOf(MaterialMixEntry(material, material.materialName, mixSelection = null))
    }
}

fun shouldShowPendingBadPartAction(
    mixSelection: ViewerMixSelection?,
    pendingCount: Int
): Boolean = mixSelection == null && pendingCount > 0

fun resolveViewerPageOrder(naturalOrder: List<Int>, selectedPages: List<Int>?): List<Int> {
    if (selectedPages == null) return naturalOrder
    val naturalPages = naturalOrder.toSet()
    return selectedPages.filter { it in naturalPages }.distinct()
}

fun statusCountsForPages(
    pages: List<Int>,
    statusByPage: Map<Int, SheetStatus>
): StatusCounts {
    var complete = 0
    var bad = 0
    var skipped = 0
    var notStarted = 0
    var reNested = 0
    pages.distinct().forEach { page ->
        when (statusByPage[page] ?: SheetStatus.NOT_STARTED) {
            SheetStatus.HAS_BAD_PARTS -> {
                complete += 1
                bad += 1
            }
            SheetStatus.COMPLETE -> complete += 1
            SheetStatus.RE_NESTED -> reNested += 1
            SheetStatus.SKIPPED -> skipped += 1
            else -> notStarted += 1
        }
    }
    return StatusCounts(
        total = pages.distinct().size,
        complete = complete,
        bad = bad,
        skipped = skipped,
        notStarted = notStarted,
        reNested = reNested
    )
}

private fun resolveMixPageOrder(
    pages: List<PageMetadata>,
    naturalOrder: List<Int>,
    mixPrograms: List<String>
): List<Int> {
    if (mixPrograms.isEmpty()) return emptyList()
    val naturalPages = naturalOrder.toSet()
    val rows = buildManageCodeRows(pages).filter { row ->
        row.pageNumber in naturalPages && row.editablePgm in mixPrograms
    }
    return applyExistingOrder(rows, mixPrograms).map { it.pageNumber }.distinct()
}

private fun materialTrackablePages(material: Material): List<Int> {
    val fromMetadata = material.metadata?.pages.orEmpty()
        .filterNot { it.hiddenInApp || it.trackingExcluded || it.isPartListContinuation }
        .mapNotNull { page -> page.pageNumber.takeIf { it in 1..material.pageCount } }
        .distinct()
        .sorted()
    return fromMetadata.ifEmpty { (1..material.pageCount).toList() }
}
