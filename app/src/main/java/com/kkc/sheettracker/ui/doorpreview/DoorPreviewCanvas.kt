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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.cvjob.DoorMaterialRef
import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.DoorHingeSide
import com.kkc.sheettracker.data.cvjob.DoorPoint
import com.kkc.sheettracker.data.cvjob.DoorPreviewData
import com.kkc.sheettracker.data.cvjob.DoorRect
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
import kotlin.math.hypot

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
    colors: DoorPreviewColors = doorPreviewColors(),
    selectedPartRect: DoorRect? = null,
    /** Corners of the selected piece when it is not a rectangle (an angled door); wins over the rect. */
    selectedPartPolygon: List<DoorPoint>? = null
) {
    val selectionColor = MaterialTheme.colorScheme.primary
    val textures = rememberDoorTextureImages(listOf(drawing))
    Canvas(modifier) {
        drawDoor(drawing, colors, padding.toPx(), showHiddenLines, textures)
        if (selectedPartPolygon != null) {
            val fit = DoorPreviewFit.of(drawing.width, drawing.height, size.width, size.height, padding.toPx())
            val path = polygonPath(fit, selectedPartPolygon)
            drawPath(path, selectionColor.copy(alpha = 0.14f))
            drawPath(path, selectionColor, style = Stroke(2.dp.toPx()))
        } else selectedPartRect?.let { selected ->
            val fit = DoorPreviewFit.of(drawing.width, drawing.height, size.width, size.height, padding.toPx())
            val bounds = fit.rect(selected)
            val topLeft = Offset(bounds[0], bounds[1])
            val selectedSize = Size(bounds[2] - bounds[0], bounds[3] - bounds[1])
            drawRect(selectionColor.copy(alpha = 0.14f), topLeft, selectedSize)
            drawRect(selectionColor, topLeft, selectedSize, style = Stroke(2.dp.toPx()))
        }
    }
}

private fun polygonPath(fit: DoorPreviewFit, points: List<DoorPoint>): Path = Path().apply {
    points.forEachIndexed { index, p ->
        if (index == 0) moveTo(fit.x(p.x), fit.y(p.y)) else lineTo(fit.x(p.x), fit.y(p.y))
    }
    close()
}

internal fun DrawScope.drawDoor(drawing: DoorDrawing, colors: DoorPreviewColors, padding: Float, showHiddenLines: Boolean,
    textures: DoorTextureImages = emptyMap()) {
    // An angled door whose outline could not be proven is not painted at all.
    if (drawing.refused) return
    val fit = DoorPreviewFit.of(drawing.width, drawing.height, size.width, size.height, padding)
    val hairline = 1.dp.toPx().coerceAtMost(fit.length(0.12)).coerceAtLeast(0.5f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f)

    fun box(rect: DoorRect): Pair<Offset, Size> {
        val r = fit.rect(rect)
        return Offset(r[0], r[1]) to Size(r[2] - r[0], r[3] - r[1])
    }

    // Paint the slab/base face from its assigned material; missing images stay white.
    val (doorTopLeft, doorSize) = box(DoorRect(0.0, 0.0, drawing.width, drawing.height))
    val shadowOffset = 2.dp.toPx().coerceAtMost(padding)
    val faceOutline = drawing.outline?.let { polygonPath(fit, it) }
        ?: Path().apply { addRect(Rect(doorTopLeft, doorSize)) }
    val vertical = Offset(0f, 1f)
    val horizontal = Offset(1f, 0f)
    translate(shadowOffset, shadowOffset) { drawPath(faceOutline, colors.shadow) }
    drawDoorMaterial(faceOutline, textures[drawing.faceMaterial], fit.scale,
        if (drawing.panelGrainVertical) vertical else horizontal)
    fun material(path: Path, ref: DoorMaterialRef?, grain: Offset) {
        drawDoorMaterial(path, ref?.let { textures[it] }, fit.scale, grain)
    }

    for ((index, shape) in drawing.shapes.withIndex()) {
        when (shape) {
            is DoorShape.Poly -> {
                val path = polygonPath(fit, shape.points)
                when (shape.role) {
                    DoorShapeRole.PANEL -> {
                        material(path, drawing.surfaceMaterials[index], if (drawing.panelGrainVertical) vertical else horizontal)
                    }
                    DoorShapeRole.STILE, DoorShapeRole.RAIL -> {
                        // Stiles run straight up; a rail's grain follows its sloped top edge (corners 3 -> 2).
                        val along = if (shape.role == DoorShapeRole.STILE || shape.points.size < 4) vertical else {
                            val a = shape.points[3]
                            val b = shape.points[2]
                            val dx = fit.x(b.x) - fit.x(a.x)
                            val dy = fit.y(b.y) - fit.y(a.y)
                            val length = hypot(dx, dy)
                            if (length > 0f) Offset(dx / length, dy / length) else horizontal
                        }
                        material(path, drawing.surfaceMaterials[index], along)
                        drawPath(path, colors.memberEdge, style = Stroke(hairline))
                    }
                    DoorShapeRole.OUTLINE -> drawPath(path, colors.outline, style = Stroke(hairline * 1.6f))
                    else -> Unit
                }
            }
            is DoorShape.Rect -> {
                val (topLeft, rectSize) = box(shape.rect)
                when (shape.role) {
                    DoorShapeRole.PANEL -> {
                        material(Path().apply { addRect(Rect(topLeft, rectSize)) }, drawing.surfaceMaterials[index],
                            if (shape.horizontalGrain?.not() ?: drawing.panelGrainVertical) vertical else horizontal)
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
                        material(Path().apply { addRect(Rect(topLeft, rectSize)) }, drawing.surfaceMaterials[index],
                            if (shape.role == DoorShapeRole.STILE) vertical else horizontal)
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

/** Plain-language decimal sizes for the expanded door. */
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
        g.layout?.let { layout ->
            add("${layout.panels.size} panel openings")
            layout.panels.forEachIndexed { index, placed ->
                placed.panel.opening?.let { add("Opening ${index+1}: ${f(it.width)} × ${f(it.height)}") }
            }
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
                val alongTop = hinges.side == DoorHingeSide.TOP
                val span = if (alongTop) g.width else g.height
                // CV positions start at bottom/left. Convert only the displayed distance;
                // drawing cup centres must keep the original bottom-left coordinates.
                val locations = hinges.positions.joinToString(", ") { position ->
                    val fromStart = position <= span / 2
                    val distance = if (fromStart) position else span - position
                    val reference = if (alongTop) {
                        if (fromStart) "left" else "right"
                    } else {
                        if (fromStart) "bottom" else "top"
                    }
                    "${f(distance)} from $reference"
                }
                add(
                    if (hinges.positions.isEmpty()) "Hinges on $edge, positions unknown"
                    else "Hinges on $edge at $locations"
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
