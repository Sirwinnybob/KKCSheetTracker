package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.DoorConstruction
import com.kkc.sheettracker.data.cvjob.DoorPart
import com.kkc.sheettracker.data.cvjob.DoorPoint
import com.kkc.sheettracker.data.cvjob.DoorPreviewData
import com.kkc.sheettracker.data.cvjob.DoorRect
import com.kkc.sheettracker.data.cvjob.DoorSlope
import com.kkc.sheettracker.data.cvjob.CabinetFrameMember

internal fun frameMemberRegion(member: CabinetFrameMember) = DoorPartRegion(
    member.part, member.rect, verticalLength = member.code in setOf("LS", "RS", "MS"),
    tenon = 0.0, polygon = member.polygon
)

/**
 * A visible face linked to its original cut-list row, including allowances and quantity.
 * [rect] is the finished visible face. [verticalLength] says which axis is the part's length
 * (true = up the door, false = across it, null = not a frame member); [tenon] is the hidden
 * extension at each end of that length axis.
 */
internal data class DoorPartRegion(
    val part: DoorPart,
    val rect: DoorRect,
    val verticalLength: Boolean? = null,
    val tenon: Double = 0.0,
    /** Corners of a piece that is not a rectangle (an angled door); [rect] is then its bounding box. */
    val polygon: List<DoorPoint>? = null
)

private const val SIZE_TOLERANCE = 0.001

/**
 * Lines for the tap panel: the published cut size, then the finished size taken straight from the
 * published finished geometry (never worked out from a guessed allowance). Final is left out when
 * it would exceed the cut size or the cut size cannot be read, so the two never contradict.
 */
internal fun doorPartSizeLines(region: DoorPartRegion): List<String> {
    val part = region.part
    val cutWidth = part.width.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    val cutLength = part.length.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    val lines = mutableListOf(
        if (part.width.isNotBlank() && part.length.isNotBlank()) "Cut size ${part.width} × ${part.length} in"
        else "Cut size unavailable"
    )
    // An angled piece has no single final width x length or visible opening to quote.
    if (region.polygon != null) return lines
    val vertical = region.verticalLength
    val rect = region.rect
    if (vertical != null) {
        val finalWidth = if (vertical) rect.width else rect.height
        val finalLength = if (vertical) rect.height else rect.width
        if (cutWidth != null && cutLength != null &&
            finalWidth <= cutWidth + SIZE_TOLERANCE && finalLength <= cutLength + SIZE_TOLERANCE
        ) {
            lines += "Final size ${formatDoorInches(finalWidth)} × ${formatDoorInches(finalLength)} in"
            if (region.tenon > 0.0) lines += "Tenons ${formatDoorInches(region.tenon)} at each end"
        }
    } else if (part.role == "panel") {
        lines += "Visible opening ${formatDoorInches(rect.width)} × ${formatDoorInches(rect.height)} in"
    }
    return lines
}

internal fun buildDoorPartRegions(door: DoorPreviewData, drawing: DoorDrawing): List<DoorPartRegion> {
    val geometry = door.geometry
    if (geometry.hasInvalidLayout) return emptyList()
    when (val slope = geometry.slope) {
        is DoorSlope.Verified -> {
            val parts = door.parts.associateBy { it.partId }
            return slope.members.mapNotNull { member ->
                parts[member.partId]?.let { DoorPartRegion(it, boundsOf(member.polygon), polygon = member.polygon) }
            }
        }
        is DoorSlope.Refused -> return emptyList()
        DoorSlope.None -> Unit
    }
    geometry.layout?.let { layout ->
        val parts = door.parts.associateBy { it.partId }
        return layout.members.mapNotNull { member ->
            parts[member.partId]?.let { DoorPartRegion(it, member.rect, member.vertical, member.tenon) }
        } + layout.panels.mapNotNull { panel ->
            val opening = panel.panel.opening ?: return@mapNotNull null
            parts[panel.partId]?.let { DoorPartRegion(it, opening) }
        }
    }
    fun region(role: String, rect: DoorRect, verticalLength: Boolean? = null, tenon: Double = 0.0): DoorPartRegion? =
        door.parts.singleOrNull { it.role == role }?.let { DoorPartRegion(it, rect, verticalLength, tenon) }

    return when (geometry.construction) {
        DoorConstruction.FIVE_PIECE -> drawing.shapes.filterIsInstance<DoorShape.Rect>().mapNotNull { shape ->
            when (shape.role) {
                DoorShapeRole.STILE ->
                    region(if (shape.rect.x == 0.0) "leftStile" else "rightStile", shape.rect, verticalLength = true)
                DoorShapeRole.RAIL ->
                    region(if (shape.rect.y == 0.0) "bottomRail" else "topRail", shape.rect,
                        verticalLength = false, tenon = geometry.frame?.tenon ?: 0.0)
                DoorShapeRole.PANEL, DoorShapeRole.GLASS -> region("panel", shape.rect)
                else -> null
            }
        }
        DoorConstruction.SLAB -> listOfNotNull(region("slab", DoorRect(0.0, 0.0, drawing.width, drawing.height)))
        DoorConstruction.OTHER -> emptyList()
    }
}

/** Undo the canvas's centre-anchored zoom/pan, then its inch-to-pixel fit (including the y flip). */
internal fun hitDoorPart(
    regions: List<DoorPartRegion>,
    fit: DoorPreviewFit,
    viewportWidth: Float,
    viewportHeight: Float,
    x: Float,
    y: Float,
    zoom: Float = 1f,
    panX: Float = 0f,
    panY: Float = 0f
): DoorPartRegion? {
    if (viewportWidth <= 0f || viewportHeight <= 0f || zoom <= 0f || fit.scale <= 0f ||
        !listOf(viewportWidth, viewportHeight, x, y, zoom, panX, panY, fit.scale).all { it.isFinite() } ||
        x !in 0f..viewportWidth || y !in 0f..viewportHeight) return null

    val unzoomedX = (x - viewportWidth / 2f - panX) / zoom + viewportWidth / 2f
    val unzoomedY = (y - viewportHeight / 2f - panY) / zoom + viewportHeight / 2f
    val doorX = (unzoomedX - fit.offsetX) / fit.scale
    val doorY = fit.doorHeight - (unzoomedY - fit.offsetY) / fit.scale
    return regions.firstOrNull { region ->
        val polygon = region.polygon
        if (polygon != null) {
            pointInDoorPolygon(doorX.toDouble(), doorY, polygon)
        } else {
            val r = region.rect
            doorX >= r.x && doorX <= r.x + r.width && doorY >= r.y && doorY <= r.y + r.height
        }
    }
}

/** Bounding box of a polygon, for the places that only need a rectangle. */
internal fun boundsOf(polygon: List<DoorPoint>): DoorRect {
    val minX = polygon.minOf { it.x }
    val minY = polygon.minOf { it.y }
    return DoorRect(minX, minY, polygon.maxOf { it.x } - minX, polygon.maxOf { it.y } - minY)
}

/** Even-odd ray cast; door inches, any winding. */
internal fun pointInDoorPolygon(x: Double, y: Double, polygon: List<DoorPoint>): Boolean {
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[j]
        if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}
