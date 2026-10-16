package com.kkc.sheettracker.ui.doorpreview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.hypot

/** Android shader matrix: image y follows the part's grain, x runs across it. */
internal fun doorTextureMatrix(imageWidth: Int, imageHeight: Int, bounds: Rect, grain: Offset,
    pixelsPerInch: Float, worldWidthInches: Double?, worldHeightInches: Double?, tile: Boolean): FloatArray {
    val length = hypot(grain.x, grain.y).coerceAtLeast(0.0001f)
    val along = grain / length
    val across = Offset(along.y, -along.x)
    val corners = listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight)
    fun project(point: Offset, axis: Offset) = point.x * axis.x + point.y * axis.y
    val acrossMin = corners.minOf { project(it, across) }
    val alongMin = corners.minOf { project(it, along) }
    val sized = tile && worldWidthInches != null && worldHeightInches != null
    val width = if (sized) (worldWidthInches * pixelsPerInch).toFloat()
        else corners.maxOf { project(it, across) } - acrossMin
    val height = if (sized) (worldHeightInches * pixelsPerInch).toFloat()
        else corners.maxOf { project(it, along) } - alongMin
    val sx = width / imageWidth.coerceAtLeast(1)
    val sy = height / imageHeight.coerceAtLeast(1)
    val origin = across * acrossMin + along * alongMin
    return floatArrayOf(across.x * sx, along.x * sy, origin.x,
        across.y * sx, along.y * sy, origin.y, 0f, 0f, 1f)
}
