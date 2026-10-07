package com.kkc.sheettracker.ui.components.icons

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector

// PDF viewer controls: scroll mode (shows the current mode), sheet list, fullscreen.
// Single state each, duotone. Style and helpers live in IconDsl.kt.

// ── Continuous scroll: a page in a strip, neighbours cut off above and below ────
private fun continuous() = kkcIcon("ViewerContinuous") {
    solid(DUOTONE) { roundRect(6f, 9f, 18f, 18f, 1f) }
    line(width = 1.5f) {
        roundRect(6f, 9f, 18f, 18f, 1f)
        moveTo(6f, 2f)
        verticalLineTo(5.5f)
        arcTo(1f, 1f, 0f, false, false, 7f, 6.5f)
        horizontalLineTo(17f)
        arcTo(1f, 1f, 0f, false, false, 18f, 5.5f)
        verticalLineTo(2f)
        moveTo(6f, 22f)
        verticalLineTo(21.5f)
        arcTo(1f, 1f, 0f, false, true, 7f, 20.5f)
        horizontalLineTo(17f)
        arcTo(1f, 1f, 0f, false, true, 18f, 21.5f)
        verticalLineTo(22f)
    }
}

// ── Single page: one page with flip arrows either side ──────────────────────────
private fun singlePage() = kkcIcon("ViewerSinglePage") {
    solid(DUOTONE) { roundRect(7f, 3f, 17f, 21f, 1.25f) }
    line(width = 1.5f) { roundRect(7f, 3f, 17f, 21f, 1.25f) }
    line {
        moveTo(4f, 10f); lineTo(2f, 12f); lineTo(4f, 14f)
        moveTo(20f, 10f); lineTo(22f, 12f); lineTo(20f, 14f)
    }
}

// ── Sheet list: a page with a dropdown chevron ──────────────────────────────────
private fun sheetList() = kkcIcon("ViewerSheetList") {
    solid(DUOTONE) { roundRect(3f, 2.5f, 16f, 19.5f, 1.25f) }
    line(width = 1.5f) {
        roundRect(3f, 2.5f, 16f, 19.5f, 1.25f)
        moveTo(6f, 7f); horizontalLineTo(13f)
        moveTo(6f, 10.5f); horizontalLineTo(13f)
        moveTo(6f, 14f); horizontalLineTo(10f)
    }
    line {
        moveTo(15.5f, 17f); lineTo(18.5f, 20f); lineTo(21.5f, 17f)
    }
}

// ── Fullscreen: corner brackets around a page ───────────────────────────────────
private fun fullscreen() = kkcIcon("ViewerFullscreen") {
    solid(DUOTONE) { roundRect(7f, 7f, 17f, 17f, 1f) }
    line {
        moveTo(3f, 8f); verticalLineTo(4f); arcTo(1f, 1f, 0f, false, true, 4f, 3f); horizontalLineTo(8f)
        moveTo(16f, 3f); horizontalLineTo(20f); arcTo(1f, 1f, 0f, false, true, 21f, 4f); verticalLineTo(8f)
        moveTo(21f, 16f); verticalLineTo(20f); arcTo(1f, 1f, 0f, false, true, 20f, 21f); horizontalLineTo(16f)
        moveTo(8f, 21f); horizontalLineTo(4f); arcTo(1f, 1f, 0f, false, true, 3f, 20f); verticalLineTo(16f)
    }
}

// ── Exit fullscreen: corners turned inward around a smaller page ────────────────
private fun exitFullscreen() = kkcIcon("ViewerExitFullscreen") {
    solid(DUOTONE) { roundRect(9f, 9f, 15f, 15f, 0.75f) }
    line {
        moveTo(3f, 7.5f); horizontalLineTo(6.5f); arcTo(1f, 1f, 0f, false, false, 7.5f, 6.5f); verticalLineTo(3f)
        moveTo(16.5f, 3f); verticalLineTo(6.5f); arcTo(1f, 1f, 0f, false, false, 17.5f, 7.5f); horizontalLineTo(21f)
        moveTo(21f, 16.5f); horizontalLineTo(17.5f); arcTo(1f, 1f, 0f, false, false, 16.5f, 17.5f); verticalLineTo(21f)
        moveTo(7.5f, 21f); verticalLineTo(17.5f); arcTo(1f, 1f, 0f, false, false, 6.5f, 16.5f); horizontalLineTo(3f)
    }
}

// ── CNC sheet: light sheet with solid nested parts ──────────────────────────────
private fun sheetNested() = kkcIcon("ViewerSheetNested") {
    solid(DUOTONE) { roundRect(2.5f, 5f, 21.5f, 19f, 1.25f) }
    line(width = 1.5f) { roundRect(2.5f, 5f, 21.5f, 19f, 1.25f) }
    solid {
        roundRect(4.5f, 7f, 10.5f, 11.5f, 0.4f)
        roundRect(11.5f, 7f, 15f, 17f, 0.4f)
        roundRect(16f, 7f, 19.5f, 11.5f, 0.4f)
        roundRect(4.5f, 12.5f, 10.5f, 17f, 0.4f)
        roundRect(16f, 12.5f, 19.5f, 17f, 0.4f)
    }
}

// ── Popup: window with an arrow out of the top right ────────────────────────────
private fun popup() = kkcIcon("ViewerPopup") {
    line {
        moveTo(11f, 4.5f); horizontalLineTo(5f)
        arcTo(1.5f, 1.5f, 0f, false, false, 3.5f, 6f); verticalLineTo(19f)
        arcTo(1.5f, 1.5f, 0f, false, false, 5f, 20.5f); horizontalLineTo(18f)
        arcTo(1.5f, 1.5f, 0f, false, false, 19.5f, 19f); verticalLineTo(13f)
        moveTo(14f, 3.5f); horizontalLineTo(20.5f); verticalLineTo(10f)
        moveTo(20.5f, 3.5f); lineTo(11f, 13f)
    }
}

// ── Re-nested: two sheets swapping places ───────────────────────────────────────
private fun reNested() = kkcIcon("ViewerReNested") {
    solid(DUOTONE) { roundRect(2.5f, 3f, 13.5f, 11f, 1f) }
    line(width = 1.5f) { roundRect(2.5f, 3f, 13.5f, 11f, 1f) }
    solid { roundRect(10.5f, 13f, 21.5f, 21f, 1f) }
    line(width = 1.6f) {
        moveTo(17f, 4.5f); curveTo(19.5f, 4.5f, 20.5f, 6f, 20.5f, 9f)
        moveTo(19f, 7.5f); lineTo(20.5f, 9f); lineTo(22f, 7.5f)
        moveTo(7f, 19.5f); curveTo(4.5f, 19.5f, 3.5f, 18f, 3.5f, 15f)
        moveTo(2f, 16.5f); lineTo(3.5f, 15f); lineTo(5f, 16.5f)
    }
}

// ── Skip: solid flag on a pole ──────────────────────────────────────────────────
private fun skipFlag() = kkcIcon("ViewerSkip") {
    line(width = 2.25f) { moveTo(5f, 21.5f); verticalLineTo(3f) }
    block(width = 1.25f) {
        moveTo(5f, 4f); horizontalLineTo(18.5f); lineTo(15.5f, 8.25f); lineTo(18.5f, 12.5f); horizontalLineTo(5f); close()
    }
}

// ── Done: light box / solid box with a check cut out ────────────────────────────
private fun doneOff() = kkcIcon("ViewerDoneOff") {
    solid(DUOTONE) { roundRect(3.5f, 3.5f, 20.5f, 20.5f, 3f) }
    line { roundRect(3.5f, 3.5f, 20.5f, 20.5f, 3f) }
}

private fun doneOn() = kkcIcon("ViewerDoneOn") {
    solid(fillType = PathFillType.EvenOdd) {
        roundRect(3.5f, 3.5f, 20.5f, 20.5f, 3f)
        moveTo(6.87f, 13.13f); lineTo(10.77f, 17.04f); lineTo(17.4f, 10.12f)
        lineTo(15.6f, 8.39f); lineTo(10.73f, 13.46f); lineTo(8.63f, 11.37f)
        close()
    }
    line { roundRect(3.5f, 3.5f, 20.5f, 20.5f, 3f) }
}

val ViewerSheetNestedIcon: ImageVector by lazy { sheetNested() }
val ViewerPopupIcon: ImageVector by lazy { popup() }
val ViewerReNestedIcon: ImageVector by lazy { reNested() }
val ViewerSkipIcon: ImageVector by lazy { skipFlag() }
val ViewerDoneOffIcon: ImageVector by lazy { doneOff() }
val ViewerDoneOnIcon: ImageVector by lazy { doneOn() }
val ViewerContinuousIcon: ImageVector by lazy { continuous() }
val ViewerSinglePageIcon: ImageVector by lazy { singlePage() }
val ViewerSheetListIcon: ImageVector by lazy { sheetList() }
val ViewerFullscreenIcon: ImageVector by lazy { fullscreen() }
val ViewerExitFullscreenIcon: ImageVector by lazy { exitFullscreen() }
