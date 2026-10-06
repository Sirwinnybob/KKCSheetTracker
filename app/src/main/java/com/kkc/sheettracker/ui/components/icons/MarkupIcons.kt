package com.kkc.sheettracker.ui.components.icons

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder

// Pen-toolbar (PDF markup) icons. Single state, duotone; tools lean bottom-left like a
// hand holding them. Style and helpers live in IconDsl.kt.

// ── Pen: pencil with solid tip and cap ──────────────────────────────────────────
private fun pen() = kkcIcon("MarkupPen") {
    val body: PathBuilder.() -> Unit = {
        moveTo(17.22f, 3.82f); lineTo(20.18f, 6.78f); lineTo(9.18f, 17.78f); lineTo(6.22f, 14.82f); close()
    }
    solid(DUOTONE, pathBuilder = body)
    line(width = 1.5f, pathBuilder = body)
    block(width = 1.25f) {
        // tip
        moveTo(6.22f, 14.82f); lineTo(9.18f, 17.78f); lineTo(3.5f, 20.5f); close()
        // cap
        moveTo(18.62f, 2.42f); lineTo(21.58f, 5.38f); lineTo(20.18f, 6.78f); lineTo(17.22f, 3.82f); close()
    }
}

// ── Highlighter: chisel marker over a highlight swath ───────────────────────────
private fun highlighter() = kkcIcon("MarkupHighlighter") {
    val body: PathBuilder.() -> Unit = {
        moveTo(17.38f, 1.38f); lineTo(21.62f, 5.62f); lineTo(13.12f, 14.12f); lineTo(8.88f, 9.88f); close()
    }
    solid(DUOTONE, pathBuilder = body)
    line(width = 1.5f, pathBuilder = body)
    block(width = 1.25f) {
        moveTo(8.88f, 9.88f); lineTo(13.12f, 14.12f); lineTo(8.94f, 15.46f); lineTo(7.54f, 14.06f); close()
    }
    solid(DUOTONE) { roundRect(2.5f, 18f, 14.5f, 21.5f, 0.75f) }
}

// ── Eraser: block eraser, solid sleeve end, light rubber end ────────────────────
private fun eraser() = kkcIcon("MarkupEraser") {
    block(width = 1.25f) {
        moveTo(16.88f, 2.88f); lineTo(21.12f, 7.12f); lineTo(15.12f, 13.12f); lineTo(10.88f, 8.88f); close()
    }
    val rubber: PathBuilder.() -> Unit = {
        moveTo(10.88f, 8.88f); lineTo(15.12f, 13.12f); lineTo(11.12f, 17.12f); lineTo(6.88f, 12.88f); close()
    }
    solid(DUOTONE, pathBuilder = rubber)
    line(width = 1.5f, pathBuilder = rubber)
    line {
        moveTo(11f, 21f)
        horizontalLineTo(21f)
    }
}

// ── Undo: curved back arrow ─────────────────────────────────────────────────────
private fun undo() = kkcIcon("MarkupUndo") {
    line {
        moveTo(8.5f, 5f); lineTo(4f, 9.5f); lineTo(8.5f, 14f)
        moveTo(4.5f, 9.5f)
        horizontalLineTo(14.5f)
        arcTo(5.5f, 5.5f, 0f, false, true, 14.5f, 20.5f)
        horizontalLineTo(10f)
    }
}

// ── Clear all: marked-up page with a round clear badge (X cut out) ──────────────
private fun clear() = kkcIcon("MarkupClear") {
    solid(DUOTONE) { roundRect(3.5f, 2.5f, 15.5f, 18.5f, 1.5f) }
    line(width = 1.5f) {
        roundRect(3.5f, 2.5f, 15.5f, 18.5f, 1.5f)
        // scribbles
        moveTo(6f, 8f)
        curveTo(7.5f, 5.5f, 9f, 10.5f, 10.5f, 8f)
        reflectiveCurveTo(13f, 5.5f, 13f, 8f)
        moveTo(6f, 13f)
        curveTo(7.5f, 10.5f, 9f, 15.5f, 10.5f, 13f)
    }
    solid(fillType = PathFillType.EvenOdd) {
        circle(18f, 18f, 4f)
        moveTo(18.74f, 16.27f); lineTo(19.73f, 17.26f); lineTo(18.99f, 18f)
        lineTo(19.73f, 18.74f); lineTo(18.74f, 19.73f); lineTo(18f, 18.99f)
        lineTo(17.26f, 19.73f); lineTo(16.27f, 18.74f); lineTo(17.01f, 18f)
        lineTo(16.27f, 17.26f); lineTo(17.26f, 16.27f); lineTo(18f, 17.01f)
        close()
    }
}

// ── Finger draw: fingertip touch point trailing a stroke ────────────────────────
private fun fingerDraw() = kkcIcon("MarkupFingerDraw") {
    line {
        moveTo(3.5f, 20.5f)
        curveTo(6f, 20.5f, 6f, 15f, 9f, 15f)
        reflectiveCurveTo(12f, 18.5f, 14.5f, 15f)
    }
    solid(DUOTONE) { circle(16f, 10f, 5f) }
    solid { circle(16f, 10f, 2.5f) }
}

val MarkupPenIcon: ImageVector by lazy { pen() }
val MarkupHighlighterIcon: ImageVector by lazy { highlighter() }
val MarkupEraserIcon: ImageVector by lazy { eraser() }
val MarkupUndoIcon: ImageVector by lazy { undo() }
val MarkupClearIcon: ImageVector by lazy { clear() }
val MarkupFingerDrawIcon: ImageVector by lazy { fingerDraw() }
