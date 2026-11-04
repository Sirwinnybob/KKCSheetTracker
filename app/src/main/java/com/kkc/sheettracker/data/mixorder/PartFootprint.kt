package com.kkc.sheettracker.data.mixorder

import com.kkc.sheettracker.data.models.Part
import kotlin.math.cos
import kotlin.math.sin

const val SMALL_PART_IN = 6.0
const val EDGE_BAND_IN = 3.0

/**
 * Axis-aligned bounds of a placed part on the sheet, in inches.
 * Sheet coordinates: x runs along the sheet length, y along the sheet height.
 */
data class Footprint(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

/**
 * Bounds of the part after rotating its local rect `[0,width] x [0,length]`
 * counter-clockwise by `sheetPlacement.angle` degrees about the placement origin.
 * Returns null when the part has no sheet placement.
 */
fun Part.footprint(): Footprint? {
    val placement = sheetPlacement ?: return null
    val radians = Math.toRadians(placement.angle)
    val cosA = cos(radians)
    val sinA = sin(radians)
    val corners = listOf(
        0.0 to 0.0,
        width to 0.0,
        width to length,
        0.0 to length
    )
    val xs = corners.map { (lx, ly) -> placement.x + lx * cosA - ly * sinA }
    val ys = corners.map { (lx, ly) -> placement.y + lx * sinA + ly * cosA }
    return Footprint(
        minX = xs.min(),
        minY = ys.min(),
        maxX = xs.max(),
        maxY = ys.max()
    )
}

fun Part.isSmall(): Boolean = width < SMALL_PART_IN || length < SMALL_PART_IN

/**
 * True when any side of the footprint is within [band] inches of the sheet edge
 * (x = 0, y = 0, x = sheetLengthX, or y = sheetHeightY). Exactly [band] away counts.
 */
fun Footprint.onEdge(
    sheetLengthX: Double,
    sheetHeightY: Double,
    band: Double = EDGE_BAND_IN
): Boolean =
    minX <= band ||
        minY <= band ||
        maxX >= sheetLengthX - band ||
        maxY >= sheetHeightY - band
