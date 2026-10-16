package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.CabinetFrameMember
import com.kkc.sheettracker.data.cvjob.CabinetFront
import com.kkc.sheettracker.data.cvjob.CabinetFrontLayout
import com.kkc.sheettracker.data.cvjob.DoorPoint
import com.kkc.sheettracker.data.cvjob.DoorRect
import com.kkc.sheettracker.data.cvjob.FrontShape

/** Include reported placement bounds and actual outlines; an overlay can pass any face edge. */
internal fun cabinetBounds(layout: CabinetFrontLayout, frontShape: (CabinetFront) -> FrontShape = { FrontShape.Rect(it.rect) }): DoorRect {
    val rects = listOf(DoorRect(0.0, 0.0, layout.faceWidth, layout.faceHeight)) + layout.frameMembers.map { it.rect } + layout.fronts.flatMap { front ->
        val shape = frontShape(front)
        listOf(front.rect) + if (shape is FrontShape.Outline && shape.points.isNotEmpty()) listOf(boundsOf(shape.points)) else emptyList()
    }
    val left = rects.minOf { it.x }; val bottom = rects.minOf { it.y }
    return DoorRect(left, bottom, rects.maxOf { it.x + it.width } - left, rects.maxOf { it.y + it.height } - bottom)
}

internal data class CabinetFit(val scale: Float, val offsetX: Float, val offsetY: Float, val boundsTop: Double, val boundsLeft: Double) {
    fun x(x: Double) = offsetX + ((x - boundsLeft) * scale).toFloat()
    fun y(y: Double) = offsetY + ((boundsTop - y) * scale).toFloat()
    fun rect(r: DoorRect) = floatArrayOf(x(r.x), y(r.y + r.height), x(r.x + r.width), y(r.y))
}

internal fun cabinetFit(layout: CabinetFrontLayout, canvasWidth: Float, canvasHeight: Float, padding: Float,
    frontShape: (CabinetFront) -> FrontShape = { FrontShape.Rect(it.rect) }): CabinetFit {
    val b = cabinetBounds(layout, frontShape)
    val usableWidth = (canvasWidth - 2 * padding).coerceAtLeast(0f)
    val usableHeight = (canvasHeight - 2 * padding).coerceAtLeast(0f)
    val scale = minOf(usableWidth / b.width, usableHeight / b.height).toFloat().takeIf { it.isFinite() && it > 0f } ?: 0f
    return CabinetFit(scale, (canvasWidth - b.width.toFloat() * scale) / 2, (canvasHeight - b.height.toFloat() * scale) / 2, b.y + b.height, b.x)
}

internal sealed interface CabinetTarget {
    data class Part(val region: DoorPartRegion) : CabinetTarget
    data class Front(val front: CabinetFront) : CabinetTarget
    data class Member(val member: CabinetFrameMember) : CabinetTarget
}
private fun contains(rect: DoorRect, x: Double, y: Double) = x >= rect.x && x <= rect.x + rect.width && y >= rect.y && y <= rect.y + rect.height

/** Invert the same centre-anchored zoom/pan as the fullscreen canvas, then flip pixels to CV y-up. */
internal fun hitCabinetTarget(layout: CabinetFrontLayout, fit: CabinetFit, viewportWidth: Float, viewportHeight: Float,
    x: Float, y: Float, zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f,
    frontShape: (CabinetFront) -> FrontShape = { FrontShape.Rect(it.rect) },
    doorParts: List<DoorPartRegion> = emptyList()): CabinetTarget? {
    if (!listOf(viewportWidth, viewportHeight, x, y, zoom, panX, panY, fit.scale, fit.offsetX, fit.offsetY).all { it.isFinite() }
        || !fit.boundsTop.isFinite() || !fit.boundsLeft.isFinite() || fit.scale <= 0 || zoom <= 0
        || viewportWidth <= 0 || viewportHeight <= 0 || x !in 0f..viewportWidth || y !in 0f..viewportHeight) return null
    val unzoomedX = (x - viewportWidth / 2 - panX) / zoom + viewportWidth / 2
    val unzoomedY = (y - viewportHeight / 2 - panY) / zoom + viewportHeight / 2
    val cx = (unzoomedX - fit.offsetX) / fit.scale + fit.boundsLeft
    val cy = fit.boundsTop - (unzoomedY - fit.offsetY) / fit.scale
    doorParts.firstOrNull { region ->
        region.polygon?.let { pointInDoorPolygon(cx, cy, it) } ?: contains(region.rect, cx, cy)
    }?.let { return CabinetTarget.Part(it) }
    layout.fronts.firstOrNull { front ->
        when (val shape = frontShape(front)) {
            is FrontShape.Rect -> contains(shape.rect, cx, cy)
            is FrontShape.Outline -> pointInDoorPolygon(cx, cy, shape.points)
            FrontShape.Blank -> false
        }
    }?.let { return CabinetTarget.Front(it) }
    return layout.frameMembers.firstOrNull { member ->
        member.polygon?.let { pointInDoorPolygon(cx, cy, it) } ?: contains(member.rect, cx, cy)
    }?.let { CabinetTarget.Member(it) }
}

/** The face edge is proved by its two stile polygons; rectangular cabinets use the face bounds. */
internal fun cabinetFaceOutline(layout: CabinetFrontLayout): List<DoorPoint> {
    val left = layout.frameMembers.singleOrNull { it.code == "LS" }?.polygon
    val right = layout.frameMembers.singleOrNull { it.code == "RS" }?.polygon
    return if (layout.slope != null && left != null && right != null) {
        listOf(left[0], right[1], right[2], left[3])
    } else listOf(DoorPoint(0.0, 0.0), DoorPoint(layout.faceWidth, 0.0),
        DoorPoint(layout.faceWidth, layout.faceHeight), DoorPoint(0.0, layout.faceHeight))
}
