package com.kkc.sheettracker.ui.doorpreview

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.cvjob.*

internal const val CABINET_GHOST_ALPHA = 0.45f
internal const val CABINET_OTHER_DOOR_ALPHA = 0.72f
internal fun ghostColor(base: Color): Color = base.copy(alpha = CABINET_GHOST_ALPHA)

private fun cabinetPath(fit: CabinetFit, points: List<DoorPoint>) = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(fit.x(p.x), fit.y(p.y)) else lineTo(fit.x(p.x), fit.y(p.y)) }
    close()
}
private fun rectanglePoints(r: DoorRect) = listOf(DoorPoint(r.x, r.y), DoorPoint(r.x + r.width, r.y),
    DoorPoint(r.x + r.width, r.y + r.height), DoorPoint(r.x, r.y + r.height))

/** Reuse the complete door renderer, fading its finished image instead of individual paints. */
private fun DrawScope.drawPlacedDoor(fit: CabinetFit, front: CabinetFront, drawing: DoorDrawing,
    colors: DoorPreviewColors, showHiddenLines: Boolean, translucentPaint: Paint? = null) {
    val r = fit.rect(front.rect)
    inset(r[0], r[1], size.width - r[2], size.height - r[3]) {
        val canvas = drawContext.canvas
        if (translucentPaint != null) canvas.saveLayer(Rect(0f, 0f, size.width, size.height), translucentPaint)
        try {
            drawDoor(drawing, colors, padding = 0f, showHiddenLines = showHiddenLines)
        } finally {
            if (translucentPaint != null) canvas.restore()
        }
    }
}

/** All proven doors retain their construction and grain; the selected door stays opaque. */
@Composable
internal fun CabinetFrontCanvas(layout: CabinetFrontLayout, selectedDoorId: String,
    selectedDrawing: DoorDrawing, selectedMember: CabinetFrameMember?, modifier: Modifier,
    padding: Dp = 24.dp, showHiddenLines: Boolean = true, colors: DoorPreviewColors = doorPreviewColors(),
    frontShape: (CabinetFront) -> FrontShape = { FrontShape.Rect(it.rect) },
    doorDrawings: Map<String, DoorDrawing> = emptyMap(), selectedPart: DoorPartRegion? = null) {
    val ghost = ghostColor(MaterialTheme.colorScheme.onSurfaceVariant)
    val selection = MaterialTheme.colorScheme.primary
    val translucentPaint = remember { Paint().apply { alpha = CABINET_OTHER_DOOR_ALPHA } }
    Canvas(modifier) {
        val fit = cabinetFit(layout, size.width, size.height, padding.toPx(), frontShape)
        if (fit.scale <= 0f) return@Canvas
        val hairline = Stroke(0f)
        drawPath(cabinetPath(fit, cabinetFaceOutline(layout)), colors.outline, style = hairline)
        layout.frameMembers.forEach { member ->
            val path = cabinetPath(fit, member.polygon ?: rectanglePoints(member.rect))
            drawPath(path, ghost)
            drawPath(path, colors.outline, style = hairline)
        }
        layout.fronts.filter { it.doorId != selectedDoorId }.forEach { front ->
            val shape = frontShape(front)
            if (shape == FrontShape.Blank) return@forEach
            val drawing = doorDrawings[front.doorId]
            if (drawing != null && !drawing.refused) {
                drawPlacedDoor(fit, front, drawing, colors, showHiddenLines, translucentPaint)
                return@forEach
            }
            // An inert front without door data has no construction detail to invent.
            val points = when (shape) {
                is FrontShape.Rect -> rectanglePoints(shape.rect)
                is FrontShape.Outline -> shape.points
                FrontShape.Blank -> return@forEach
            }
            val path = cabinetPath(fit, points)
            drawPath(path, ghost)
            drawPath(path, colors.outline, style = hairline)
        }
        layout.fronts.singleOrNull { it.doorId == selectedDoorId }?.let { front ->
            if (frontShape(front) != FrontShape.Blank && !selectedDrawing.refused) {
                drawPlacedDoor(fit, front, selectedDrawing, colors, showHiddenLines)
            }
        }
        selectedMember?.let { member ->
            val path = cabinetPath(fit, member.polygon ?: rectanglePoints(member.rect))
            drawPath(path, selection.copy(alpha = 0.14f))
            drawPath(path, selection, style = Stroke(2.dp.toPx()))
        }
        selectedPart?.let { part ->
            val path = cabinetPath(fit, part.polygon ?: rectanglePoints(part.rect))
            drawPath(path, selection.copy(alpha = 0.14f))
            drawPath(path, selection, style = Stroke(2.dp.toPx()))
        }
    }
}
