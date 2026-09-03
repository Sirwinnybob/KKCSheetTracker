package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DashboardRecentMaterialItem
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.MaterialUiModel
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.SheetStatusSnapshot

/**
 * Pages a material actually surfaces for tracking (metadata-hidden, tracking-excluded,
 * and part-list-continuation pages are dropped). Falls back to every page in the PDF
 * when the material has no page metadata at all.
 */
internal fun trackablePages(material: Material): List<Int> {
    val metadataPages = material.metadata?.pages.orEmpty()
    val visibleFromMetadata = metadataPages
        .filterNot { it.hiddenInApp || it.trackingExcluded || it.isPartListContinuation }
        .mapNotNull { page ->
            val p = page.pageNumber
            p.takeIf { it in 1..material.pageCount }
        }
        .distinct()
        .sorted()
    return if (visibleFromMetadata.isNotEmpty()) visibleFromMetadata else (1..material.pageCount).toList()
}

/** First trackable page that is not yet complete/skipped/bad/re-nested, else [fallbackPage]. */
internal fun nextIncompletePage(
    trackablePages: List<Int>,
    pageStatusByNumber: Map<Int, SheetStatusSnapshot>,
    fallbackPage: Int
): Int {
    return trackablePages.firstOrNull { page ->
        when (pageStatusByNumber[page]?.status ?: SheetStatus.NOT_STARTED) {
            SheetStatus.NOT_STARTED, SheetStatus.IN_PROGRESS -> true
            SheetStatus.COMPLETE, SheetStatus.SKIPPED, SheetStatus.HAS_BAD_PARTS, SheetStatus.RE_NESTED -> false
        }
    } ?: fallbackPage
}

/**
 * Builds a dashboard card for a material tagged with a "remake" or "misc" label
 * (whichever [label] is supplied for), provided the material still has incomplete
 * work. Returns null when [label] is null (material isn't tagged) or the material
 * is already fully complete/re-nested — either case means it shouldn't surface on
 * the dashboard's incomplete-tagged-material rail.
 */
internal fun buildIncompleteTaggedMaterialItem(
    label: String?,
    job: Job,
    material: Material,
    materialUiModel: MaterialUiModel,
    pageStatusByNumber: Map<Int, SheetStatusSnapshot>
): DashboardRecentMaterialItem? {
    if (label == null) return null
    if ((materialUiModel.counts.complete + materialUiModel.counts.reNested) >= materialUiModel.counts.total) {
        return null
    }

    val visiblePages = trackablePages(material)
    val nextPage = nextIncompletePage(
        trackablePages = visiblePages,
        pageStatusByNumber = pageStatusByNumber,
        fallbackPage = visiblePages.firstOrNull() ?: 1
    )
    val pageMeta = material.metadata?.pages?.firstOrNull { it.pageNumber == nextPage }
        ?: material.metadata?.pages?.getOrNull((nextPage - 1).coerceAtLeast(0))

    return DashboardRecentMaterialItem(
        jobFolderName = job.folderName,
        jobNumber = job.jobNumber,
        materialName = material.materialName,
        pdfFilename = material.pdfFilename,
        fileFingerprint = material.fileFingerprint,
        lastTouchedPage = nextPage,
        nextIncompletePage = nextPage,
        lastTouchedAtMs = 0L,
        counts = materialUiModel.counts,
        completionFraction = materialUiModel.completionFraction,
        thumbnailPath = pageMeta?.thumbnailPath
    )
}
