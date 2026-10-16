package com.kkc.sheettracker.ui.doorpreview

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.cvjob.*

internal const val CABINET_GHOST_ALPHA = 0.45f
internal fun ghostColor(base: Color): Color = base.copy(alpha = CABINET_GHOST_ALPHA)

private fun cabinetPath(fit: CabinetFit, points: List<DoorPoint>) = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(fit.x(p.x), fit.y(p.y)) else lineTo(fit.x(p.x), fit.y(p.y)) }
    close()
}
private fun rectanglePoints(r: DoorRect) = listOf(DoorPoint(r.x, r.y), DoorPoint(r.x + r.width, r.y),
    DoorPoint(r.x + r.width, r.y + r.height), DoorPoint(r.x, r.y + r.height))

/** The cabinet is context; only the selected door carries construction detail or grain. */
@Composable
internal fun CabinetFrontCanvas(layout: CabinetFrontLayout, selectedDoorId: String,
    selectedDrawing: DoorDrawing, selectedMember: CabinetFrameMember?, modifier: Modifier,
    padding: Dp = 24.dp, showHiddenLines: Boolean = true, colors: DoorPreviewColors = doorPreviewColors(),
    frontShape: (CabinetFront) -> FrontShape = { FrontShape.Rect(it.rect) }) {
    val ghost = ghostColor(MaterialTheme.colorScheme.onSurfaceVariant)
    val selection = MaterialTheme.colorScheme.primary
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
            val points = when (val shape = frontShape(front)) {
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
                val r = fit.rect(front.rect)
                inset(r[0], r[1], size.width - r[2], size.height - r[3]) {
                    drawDoor(selectedDrawing, colors, padding = 0f, showHiddenLines = showHiddenLines)
                }
            }
        }
        selectedMember?.let { member ->
            val path = cabinetPath(fit, member.polygon ?: rectanglePoints(member.rect))
            drawPath(path, selection.copy(alpha = 0.14f))
            drawPath(path, selection, style = Stroke(2.dp.toPx()))
        }
    }
}
