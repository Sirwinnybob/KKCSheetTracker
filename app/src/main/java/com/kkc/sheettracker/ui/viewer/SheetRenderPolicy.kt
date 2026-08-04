package com.kkc.sheettracker.ui.viewer

internal enum class SheetDiagramSource {
    SIDECAR_THUMBNAIL,
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
