package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.CabinetFront
import com.kkc.sheettracker.data.cvjob.DoorHingeSide
import kotlin.math.hypot

/** Measurements use the published cup center in door inches, with CV's bottom-left origin. */
internal fun hingeDistanceLines(drawing: DoorDrawing, side: DoorHingeSide, cup: DoorShape.HingeCup): List<String> =
    when (side) {
        DoorHingeSide.LEFT, DoorHingeSide.RIGHT -> listOf(
            "${formatDoorInches(drawing.height - cup.center.y)} in from Top",
            "${formatDoorInches(cup.center.y)} in from Bottom"
        )
        DoorHingeSide.TOP -> listOf(
            "${formatDoorInches(cup.center.x)} in from Left",
            "${formatDoorInches(drawing.width - cup.center.x)} in from Right"
        )
        DoorHingeSide.NONE, DoorHingeSide.OTHER -> emptyList()
    }

/** Match drawPlacedDoor's inset and centered fit, including any difference in front/door sizes. */
internal fun cabinetDoorFit(fit: CabinetFit, front: CabinetFront, drawing: DoorDrawing): DoorPreviewFit {
    val bounds = fit.rect(front.rect)
    val local = DoorPreviewFit.of(drawing.width, drawing.height, bounds[2] - bounds[0], bounds[3] - bounds[1], 0f)
    return local.copy(offsetX = local.offsetX + bounds[0], offsetY = local.offsetY + bounds[1])
}

/** Hit only drawn cups, using a screen-space touch target that stays usable at every zoom level. */
internal fun hitDoorHinge(
    drawing: DoorDrawing, fit: DoorPreviewFit, viewportWidth: Float, viewportHeight: Float,
    x: Float, y: Float, zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f,
    touchRadiusPx: Float = 24f
): DoorShape.HingeCup? {
    if (drawing.refused || viewportWidth <= 0f || viewportHeight <= 0f || zoom <= 0f || fit.scale <= 0f ||
        touchRadiusPx < 0f || !fit.doorHeight.isFinite() ||
        !listOf(viewportWidth, viewportHeight, x, y, zoom, panX, panY, touchRadiusPx,
            fit.scale, fit.offsetX, fit.offsetY).all { it.isFinite() } ||
        x !in 0f..viewportWidth || y !in 0f..viewportHeight) return null

    return drawing.shapes.filterIsInstance<DoorShape.HingeCup>().mapNotNull { cup ->
        val centerX = (fit.x(cup.center.x) - viewportWidth / 2) * zoom + viewportWidth / 2 + panX
        val centerY = (fit.y(cup.center.y) - viewportHeight / 2) * zoom + viewportHeight / 2 + panY
        val distance = hypot(x - centerX, y - centerY)
        val radius = maxOf(touchRadiusPx, fit.length(cup.radius) * zoom)
        if (distance <= radius) cup to distance else null
    }.minByOrNull { it.second }?.first
}
