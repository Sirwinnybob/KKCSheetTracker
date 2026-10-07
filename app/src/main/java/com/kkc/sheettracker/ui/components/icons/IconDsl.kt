package com.kkc.sheettracker.ui.components.icons

// ═══════════════════════════════════════════════════════════════════════════════════════════
// KEEP IN SYNC — HOURS TRACKER MIRRORS THIS NAVBAR
// When KKC opens Hours Tracker (com.example.timecard) it sends this bar's resolved look as
// intent extras and Hours Tracker draws a copy of the FULL (labels shown) state of this bar.
// Mirror lives in C:\Scripts\Hours Tracker\AndroidApp\app\src\main\java\com\example\timecard\kkcnav\
//   KkcNavBar.kt          ← MorphingNavBar + MorphingNavIconRow (full state)
//   KkcNavBarModel.kt     ← labels, Calc-before-Hours slot order, badge rules, tint rules, alpha clamp
//   KkcNavIcons.kt        ← NavIcons.kt (verbatim copy, package changed)
//   KkcIconDsl.kt         ← IconDsl.kt (verbatim copy, package changed)
//   KkcNavTypography.kt   ← Type.kt InterFontFamily + labelSmall
//   KkcNavBarContract.kt  ← navigation/KkcNavBarContract.kt
// Values that MUST match: side margin 24dp, bottom gap 12dp, bar corner 20dp, row min height
// 44dp + padding 24x4dp, SpaceEvenly + weight(1f) slots, item padding 14x8dp, spacedBy 3dp,
// icon 22dp, Calc slot before Hours, tints (bold: frosted content / @0.8; else primary /
// onSurfaceVariant), selection bg (bold: gradient @0.55; else surfaceVariant; shape
// shapes.medium), badges (Supply = supply count, Library = safety count), Surface color
// (transparent under Haze, else frosted base @ alpha.coerceIn(0.5,0.95)), shadow 3dp (0 when
// shadows disabled or WebView blur suppressed), Haze style (blur coerceAtLeast 1dp), label
// style Inter Medium 11sp/16sp/0.5sp (Bold when selected), label maxLines 1 + ellipsis only
// when animations are on (none in low-end and for Calc), animation specs (NavSpringDp,
// snap() when low-end animations are off).
// Change any of these here → change the mirror in the SAME session. A new value the mirror
// needs goes through KkcNavBarContract and bumps VERSION in BOTH repos.
// Full rules: KKCSheetTracker CLAUDE.md "KKC navbar mirror (Hours Tracker)".
// ═══════════════════════════════════════════════════════════════════════════════════════════

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
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

/** Pass [PathFillType.EvenOdd] to punch inner shapes out of an outer one (e.g. frame openings). */
internal fun ImageVector.Builder.solid(
    alpha: Float = 1f,
    fillType: PathFillType = PathFillType.NonZero,
    pathBuilder: PathBuilder.() -> Unit
) = path(fill = SolidColor(Color.Black), fillAlpha = alpha, pathFillType = fillType, pathBuilder = pathBuilder)

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
