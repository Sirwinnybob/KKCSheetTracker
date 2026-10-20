package com.kkc.sheettracker.viewer3d

/** Fixed orthographic front projection used by native compact wing thumbnails. */
data class WingThumbnailBounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)
data class WingThumbnailPoint(val x: Double, val y: Double)

data class WingThumbnailViewport(val scale: Double, val offsetX: Double, val offsetY: Double) {
    fun project(point: WingThumbnailPoint) =
        WingThumbnailPoint(offsetX + point.x * scale, offsetY - point.y * scale)
}

/** One physical scale on both axes; the remaining space centers the entire plan view. */
fun fitWingThumbnail(
    bounds: WingThumbnailBounds,
    width: Double,
    height: Double,
    padding: Double
): WingThumbnailViewport {
    val extentX = (bounds.maxX - bounds.minX).coerceAtLeast(0.001)
    val extentY = (bounds.maxY - bounds.minY).coerceAtLeast(0.001)
    val scale = minOf((width - padding * 2).coerceAtLeast(0.0) / extentX,
        (height - padding * 2).coerceAtLeast(0.0) / extentY)
    return WingThumbnailViewport(
        scale,
        (width - extentX * scale) / 2 - bounds.minX * scale,
        (height - extentY * scale) / 2 + bounds.maxY * scale
    )
}
data class WingThumbnailFace(
    val path: String,
    val points: List<WingThumbnailPoint>,
    val depth: Double,
    val materialId: String?,
    val grainAxis: String?
)
data class WingThumbnailProjection(val bounds: WingThumbnailBounds, val faces: List<WingThumbnailFace>)

/**
 * Views the exported X/Z plane; painter order is back-to-front by the measured world Y extent.
 * It deliberately consumes the already-composed scene rather than interpreting raw CV nodes again.
 */
fun projectWingThumbnail(scene: WingScenePayload): WingThumbnailProjection {
    val faces = scene.parts.map { part ->
        val b = part.bounds
        WingThumbnailFace(
            path = part.path,
            points = listOf(
                WingThumbnailPoint(b.minX, b.minZ), WingThumbnailPoint(b.maxX, b.minZ),
                WingThumbnailPoint(b.maxX, b.maxZ), WingThumbnailPoint(b.minX, b.maxZ)
            ),
            depth = b.maxY,
            materialId = part.material?.materialId,
            grainAxis = part.material?.grainAxis
        )
    }.sortedByDescending { it.depth }
    return WingThumbnailProjection(
        WingThumbnailBounds(scene.bounds.minX, scene.bounds.minZ, scene.bounds.maxX, scene.bounds.maxZ),
        faces
    )
}
