package com.kkc.sheettracker.ui.components.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Shared drawing helpers for the KKC custom icon family. One style so every set reads together:
//   - 24×24 viewport, 2-unit rounded strokes, live area kept inside 2..22.
//   - Outline = resting state. Duotone = body gets a translucent fill, hero detail goes solid.
// Paths are drawn in black; Icon(tint = …) recolors them, so every KKC theme works.
// Alpha survives the tint, which is what makes the duotone fill visible.

internal const val STROKE = 2f
internal const val DUOTONE = 0.3f

internal inline fun kkcIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(block).build()

internal fun ImageVector.Builder.line(
    width: Float = STROKE,
    alpha: Float = 1f,
    pathBuilder: PathBuilder.() -> Unit
) = path(
    stroke = SolidColor(Color.Black),
    strokeAlpha = alpha,
    strokeLineWidth = width,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = pathBuilder
)

internal fun ImageVector.Builder.solid(alpha: Float = 1f, pathBuilder: PathBuilder.() -> Unit) =
    path(fill = SolidColor(Color.Black), fillAlpha = alpha, pathBuilder = pathBuilder)

/** Fill + stroke of the same shape: a solid shape with the family's rounded edge. */
internal fun ImageVector.Builder.block(width: Float = STROKE, pathBuilder: PathBuilder.() -> Unit) = path(
    fill = SolidColor(Color.Black),
    stroke = SolidColor(Color.Black),
    strokeLineWidth = width,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = pathBuilder
)

internal fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
    moveTo(l + rad, t)
    horizontalLineTo(r - rad)
    arcTo(rad, rad, 0f, false, true, r, t + rad)
    verticalLineTo(b - rad)
    arcTo(rad, rad, 0f, false, true, r - rad, b)
    horizontalLineTo(l + rad)
    arcTo(rad, rad, 0f, false, true, l, b - rad)
    verticalLineTo(t + rad)
    arcTo(rad, rad, 0f, false, true, l + rad, t)
    close()
}

internal fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, true, true, cx + r, cy)
    arcTo(r, r, 0f, true, true, cx - r, cy)
    close()
}
