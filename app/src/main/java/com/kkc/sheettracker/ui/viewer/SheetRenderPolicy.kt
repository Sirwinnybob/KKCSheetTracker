package com.kkc.sheettracker.ui.viewer

internal enum class SheetDiagramSource {
    SIDECAR_THUMBNAIL,
    /** Splitter's full-size gray PNG ([com.kkc.sheettracker.data.models.PageMetadata.diagramPath]). */
    SIDECAR_DIAGRAM,
    FULL_EMBEDDED_IMAGE
}

internal enum class SheetRenderQuality(
    val scale: Float,
    val diagramSource: SheetDiagramSource
) {
    ADJACENT(0.5f, SheetDiagramSource.SIDECAR_THUMBNAIL),
    CURRENT(1f, SheetDiagramSource.FULL_EMBEDDED_IMAGE)
}

internal fun isRenderQualitySufficient(
    cachedScale: Float,
    requiredQuality: SheetRenderQuality
): Boolean = cachedScale >= requiredQuality.scale

/**
 * Prewarmed (adjacent) pages load the full diagram when the splitter wrote a sidecar PNG: that is
 * a cheap native decode, so the page shows sharp as soon as it scrolls in. Without one, full
 * quality means a PdfBox parse per page, so prewarm keeps the small thumbnail.
 */
internal fun effectiveDiagramSource(quality: SheetRenderQuality, diagramPath: String?): SheetDiagramSource =
    if (quality.diagramSource == SheetDiagramSource.SIDECAR_THUMBNAIL && !diagramPath.isNullOrBlank()) {
        SheetDiagramSource.SIDECAR_DIAGRAM
    } else {
        quality.diagramSource
    }

/**
 * Longest edge a decoded sheet diagram may keep. CNC diagrams embed 5100 x ~2560 JPEGs
 * (~52 MB as ARGB); tablet screens are ~2560 px wide, so extra pixels only cost memory.
 */
internal const val DIAGRAM_MAX_EDGE_PX = 2560

/** Diagram pixels per OCR-box pixel for a splitter sidecar diagram; 1:1 when the OCR width is unknown. */
internal fun sidecarDiagramSourceScale(bitmapWidth: Int, ocrImageWidth: Int?): Float =
    if (ocrImageWidth == null || ocrImageWidth <= 0) 1f else bitmapWidth.toFloat() / ocrImageWidth

/** Smallest integer decode subsampling that fits the image's long edge in [maxEdgePx]. */
internal fun diagramDecodeSubsampling(width: Int, height: Int, maxEdgePx: Int = DIAGRAM_MAX_EDGE_PX): Int {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxEdgePx) return 1
    var subsampling = 2
    while ((longEdge + subsampling - 1) / subsampling > maxEdgePx) subsampling++
    return subsampling
}
