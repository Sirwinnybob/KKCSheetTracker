package com.kkc.sheettracker.ui.doorpreview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.DoorHingeSide
import com.kkc.sheettracker.data.cvjob.DoorPreviewData
import com.kkc.sheettracker.data.cvjob.DoorRect
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme

@Immutable
data class DoorPreviewColors(
    val member: Color,
    val memberGrain: Color,
    val memberEdge: Color,
    val panel: Color,
    val panelGrain: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val outline: Color,
    val hidden: Color,
    val hinge: Color,
    val hingeFill: Color,
    val shadow: Color
)

/** Wood tones that read as a door in both themes; hinge cups use the theme's primary color. */
@Composable
fun doorPreviewColors(): DoorPreviewColors {
    val primary = MaterialTheme.colorScheme.primary
    return if (LocalKKCIsDarkTheme.current) {
        DoorPreviewColors(
            member = Color(0xFF8A6C47),
            memberGrain = Color(0xFF735737),
            memberEdge = Color(0xFF4A3824),
            panel = Color(0xFF9C7F58),
            panelGrain = Color(0xFF866A46),
            glassTop = Color(0xFF5C8EA8),
            glassBottom = Color(0xFF3C6A82),
            outline = Color(0xFFEADBC6),
            hidden = Color(0xFFEADBC6).copy(alpha = 0.55f),
            hinge = primary,
            hingeFill = Color(0xFF22252A).copy(alpha = 0.85f),
            shadow = Color.Black.copy(alpha = 0.35f)
        )
    } else {
        DoorPreviewColors(
            member = Color(0xFFDDC197),
            memberGrain = Color(0xFFC4A375),
            memberEdge = Color(0xFF8A6A43),
            panel = Color(0xFFEBD8B6),
            panelGrain = Color(0xFFD9C29A),
            glassTop = Color(0xFFD4EDF9),
            glassBottom = Color(0xFF9FCDE6),
            outline = Color(0xFF4A3826),
            hidden = Color(0xFF6B5640).copy(alpha = 0.75f),
            hinge = primary,
            hingeFill = Color.White.copy(alpha = 0.9f),
            shadow = Color.Black.copy(alpha = 0.12f)
        )
    }
}

/** Paints a [DoorDrawing] scaled to fit, door bottom at the bottom of the canvas. */
@Composable
fun DoorPreviewCanvas(
    drawing: DoorDrawing,
    modifier: Modifier = Modifier,
    padding: Dp = 6.dp,
    showHiddenLines: Boolean = true,
    colors: DoorPreviewColors = doorPreviewColors()
) {
    Canvas(modifier) {
        drawDoor(drawing, colors, padding.toPx(), showHiddenLines)
    }
}

private fun DrawScope.drawDoor(drawing: DoorDrawing, colors: DoorPreviewColors, padding: Float, showHiddenLines: Boolean) {
    val fit = DoorPreviewFit.of(drawing.width, drawing.height, size.width, size.height, padding)
    val hairline = 1.dp.toPx().coerceAtMost(fit.length(0.12)).coerceAtLeast(0.5f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f)

    fun box(rect: DoorRect): Pair<Offset, Size> {
        val r = fit.rect(rect)
        return Offset(r[0], r[1]) to Size(r[2] - r[0], r[3] - r[1])
    }

    // Soft drop shadow and the door's face: a slab (or an outline-only door) reads as solid wood.
    val (doorTopLeft, doorSize) = box(DoorRect(0.0, 0.0, drawing.width, drawing.height))
    val shadowOffset = 2.dp.toPx().coerceAtMost(padding)
    drawRect(colors.shadow, doorTopLeft + Offset(shadowOffset, shadowOffset), doorSize)
    drawRect(colors.panel, doorTopLeft, doorSize)
    drawGrain(doorTopLeft, doorSize, vertical = true, color = colors.panelGrain, spacing = fit.length(2.5), stroke = hairline)

    for (shape in drawing.shapes) {
        when (shape) {
            is DoorShape.Rect -> {
                val (topLeft, rectSize) = box(shape.rect)
                when (shape.role) {
                    DoorShapeRole.PANEL -> {
                        drawRect(colors.panel, topLeft, rectSize)
                        drawGrain(topLeft, rectSize, vertical = true, color = colors.panelGrain, spacing = fit.length(2.0), stroke = hairline)
                    }
                    DoorShapeRole.GLASS -> {
                        drawRect(
                            Brush.verticalGradient(listOf(colors.glassTop, colors.glassBottom), topLeft.y, topLeft.y + rectSize.height),
                            topLeft,
                            rectSize
                        )
                        val glint = Color.White.copy(alpha = 0.55f)
                        val w = rectSize.width
                        val h = rectSize.height
                        drawLine(glint, topLeft + Offset(w * 0.18f, h * 0.30f), topLeft + Offset(w * 0.42f, h * 0.12f), hairline * 1.5f)
                        drawLine(glint, topLeft + Offset(w * 0.24f, h * 0.40f), topLeft + Offset(w * 0.58f, h * 0.15f), hairline * 1.5f)
                    }
                    DoorShapeRole.STILE, DoorShapeRole.RAIL -> {
                        drawRect(colors.member, topLeft, rectSize)
                        drawGrain(
                            topLeft, rectSize,
                            vertical = shape.role == DoorShapeRole.STILE,
                            color = colors.memberGrain,
                            spacing = fit.length(0.6),
                            stroke = hairline
                        )
                        drawRect(colors.memberEdge, topLeft, rectSize, style = Stroke(hairline))
                    }
                    DoorShapeRole.HIDDEN_PANEL_EDGE, DoorShapeRole.TENON -> if (showHiddenLines) {
                        drawRect(colors.hidden, topLeft, rectSize, style = Stroke(hairline, pathEffect = dash))
                    }
                    DoorShapeRole.OUTLINE ->
                        drawRect(colors.outline, topLeft, rectSize, style = Stroke(hairline * 1.6f))
                }
            }
            is DoorShape.HingeCup -> {
                val center = Offset(fit.x(shape.center.x), fit.y(shape.center.y))
                val radius = fit.length(shape.radius).coerceAtLeast(1.5.dp.toPx())
                drawCircle(colors.hingeFill, radius, center)
                drawCircle(colors.hinge, radius, center, style = Stroke(hairline * 1.6f))
                drawCircle(colors.hinge, radius * 0.22f, center)
            }
        }
    }
}

/** Evenly spaced grain lines along the piece, clipped to it. */
private fun DrawScope.drawGrain(topLeft: Offset, size: Size, vertical: Boolean, color: Color, spacing: Float, stroke: Float) {
    if (spacing < 3f || size.width <= 0f || size.height <= 0f) return
    clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) {
        val across = if (vertical) size.width else size.height
        var offset = spacing / 2
        var index = 0
        while (offset < across) {
            // A slight lean on alternate lines so the grain does not look ruled.
            val lean = if (index % 2 == 0) spacing * 0.15f else -spacing * 0.1f
            if (vertical) {
                drawLine(color, Offset(topLeft.x + offset, topLeft.y), Offset(topLeft.x + offset + lean, topLeft.y + size.height), stroke)
            } else {
                drawLine(color, Offset(topLeft.x, topLeft.y + offset), Offset(topLeft.x + size.width, topLeft.y + offset + lean), stroke)
            }
            offset += spacing
            index++
        }
    }
}

@Composable
internal fun DoorWarningBadge(count: Int, modifier: Modifier = Modifier) {
    val color = KKCThemeColors.statusColors.bad
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = "$count door data warning${if (count == 1) "" else "s"}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(
            if (count == 1) " Check" else " $count checks",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/** Plain-language sizes for the expanded door, shop fractions where the size is on the 1/64" grid. */
internal fun doorDetailLines(preview: DoorPreviewData, drawing: DoorDrawing): List<String> {
    val g = preview.geometry
    fun f(v: Double) = formatDoorInches(v)
    return buildList {
        add("Size ${f(g.width)} × ${f(g.height)}" + (g.thickness?.let { " × ${f(it)}" } ?: ""))
        g.frame?.let { frame ->
            add("Stiles ${f(frame.leftStile)} / ${f(frame.rightStile)}, rails ${f(frame.topRail)} top / ${f(frame.bottomRail)} bottom")
            frame.tenon?.let { add("Tenons ${f(it)}") }
            if (frame.material.isNotBlank()) add("Frame: ${frame.material}")
        }
        g.panel?.let { panel ->
            panel.opening?.let { add("Opening ${f(it.width)} × ${f(it.height)}") }
            if (panel.insetX != null || panel.insetY != null) {
                add("Panel in groove ${panel.insetX?.let(::f) ?: "?"} sides, ${panel.insetY?.let(::f) ?: "?"} top/bottom")
            }
            add("Panel: ${listOf(panel.kind, panel.material).filter { it.isNotBlank() }.joinToString(", ")}")
        }
        val hinges = g.hinges
        val hasCups = drawing.shapes.any { it is DoorShape.HingeCup }
        when (hinges.side) {
            DoorHingeSide.LEFT, DoorHingeSide.RIGHT, DoorHingeSide.TOP -> if (!hasCups) add("No hinges") else {
                val edge = hinges.side.name.lowercase()
                val from = if (hinges.side == DoorHingeSide.TOP) "from left" else "from bottom"
                add(
                    if (hinges.positions.isEmpty()) "Hinges on $edge, positions unknown"
                    else "Hinges on $edge at ${hinges.positions.joinToString(", ") { f(it) }} $from"
                )
            }
            DoorHingeSide.NONE -> add("No hinges")
            DoorHingeSide.OTHER -> Unit
        }
        addAll(drawing.notes)
    }
}

/** Null when there is nothing to explain (still loading, or there are doors to show). */
internal fun doorPreviewStatusMessage(previews: CvJobDoorPreviews?, doorCount: Int): String? = when (previews) {
    null -> null
    CvJobDoorPreviews.NoJobData -> "No door data: job data has not been published from CV Cutlist Query."
    CvJobDoorPreviews.NoDoorGeometry -> "No door data: job data is from an older CV Cutlist Query. Publish job data again."
    is CvJobDoorPreviews.Unreadable -> "Door data unavailable: job data could not be read."
    is CvJobDoorPreviews.Loaded -> if (doorCount == 0) "No doors in the job data." else null
}
