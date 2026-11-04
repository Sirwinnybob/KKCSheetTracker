package com.kkc.sheettracker.data.mixorder

import com.kkc.sheettracker.data.mixfiles.sheetPages
import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.data.models.Part
import java.util.Locale

const val FEATURE_COUNT = 5

/**
 * Per-sheet cut-quality features used to rank mix orderings. [vector] lists them in
 * field order, so it always has [FEATURE_COUNT] entries.
 */
data class SheetFeatures(
    val smallEdge: Int,
    val smallInterior: Int,
    val smallEdgeArea: Double,
    val minDim: Double,
    val partCount: Int
) {
    fun vector(): DoubleArray = doubleArrayOf(
        smallEdge.toDouble(),
        smallInterior.toDouble(),
        smallEdgeArea,
        minDim,
        partCount.toDouble()
    )
}

/** One physical sheet: its head page, its PGMs (A before Z for double-sided) and its features. */
data class SmartSheet(val headPage: Int, val pgms: List<String>, val features: SheetFeatures)

val SmartSheet.key: String get() = "$headPage|${pgms.joinToString("+")}"

/**
 * Features for every sheet that has at least one PGM in [draftPgms] (compared lowercase),
 * in head-page order. Pages outside the draft are never inspected, so their missing data cannot
 * disqualify the draft. Returns null when any draft sheet cannot be qualified: dimensions are not
 * `[height, length]`, there are no parts, or some part lacks a sheet placement.
 *
 * Part-list continuation pages fold their parts into the head page they point at.
 */
fun smartSheets(pages: List<PageMetadata>, draftPgms: Set<String>): List<SmartSheet>? {
    val draft = draftPgms.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    val sheetPageByNumber = sheetPages(pages).associateBy { it.pageNumber }
    val pageByNumber = pages.associateBy { it.pageNumber }

    val headPages = sheetPageByNumber.values
        .filter { sheet -> sheet.pgmFiles.any { it.lowercase(Locale.ROOT) in draft } }
        .map { sheet ->
            val head = pageByNumber[sheet.pageNumber]?.continuationHeadPage
            if (sheet.continuation && head != null && head != sheet.pageNumber) head else sheet.pageNumber
        }
        .distinct()
        .sorted()

    return headPages.map { headPage ->
        val head = pageByNumber[headPage] ?: return null
        val pgms = sheetPageByNumber[headPage]?.pgmFiles ?: return null
        val dims = head.sheetDimensions?.takeIf { it.size == 2 } ?: return null
        val parts = head.parts + pages
            .filter { it.isPartListContinuation && it.pageNumber != headPage && it.continuationHeadPage == headPage }
            .sortedBy { it.pageNumber }
            .flatMap { it.parts }
        val features = sheetFeatures(parts, dims[0], dims[1]) ?: return null
        SmartSheet(headPage, pgms, features)
    }
}

// dims are [height(y), length(x)], matching PageMetadata.sheetDimensions.
private fun sheetFeatures(parts: List<Part>, heightY: Double, lengthX: Double): SheetFeatures? {
    if (parts.isEmpty()) return null
    var smallEdge = 0
    var smallInterior = 0
    var smallEdgeAreaSum = 0.0
    var minDim = Double.MAX_VALUE
    for (part in parts) {
        val footprint = part.footprint() ?: return null
        minDim = minOf(minDim, minOf(part.width, part.length))
        if (!part.isSmall()) continue
        if (footprint.onEdge(sheetLengthX = lengthX, sheetHeightY = heightY)) {
            smallEdge++
            smallEdgeAreaSum += part.width * part.length
        } else {
            smallInterior++
        }
    }
    val sheetArea = heightY * lengthX
    return SheetFeatures(
        smallEdge = smallEdge,
        smallInterior = smallInterior,
        smallEdgeArea = if (sheetArea > 0.0) smallEdgeAreaSum / sheetArea else 0.0,
        minDim = minDim,
        partCount = parts.size
    )
}
