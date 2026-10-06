package com.kkc.sheettracker.ui.components.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder

// Dashboard stat-tile and card-header icons. Single state, always duotone.
// Style and helpers live in IconDsl.kt.

private val sheet: PathBuilder.() -> Unit = { roundRect(2.5f, 5f, 21.5f, 19f, 1.5f) }

private fun ImageVector.Builder.duotoneSheet() {
    solid(DUOTONE, pathBuilder = sheet)
    line(pathBuilder = sheet)
}

// ── Completed: sheet with a check ───────────────────────────────────────────────
private fun sheetCheck() = kkcIcon("DashboardSheetCheck") {
    duotoneSheet()
    line(width = 2.5f) {
        moveTo(7.5f, 12f)
        lineTo(10.5f, 15f)
        lineTo(16.5f, 9f)
    }
}

// ── Bad parts: cracked sheet ────────────────────────────────────────────────────
private fun sheetCrack() = kkcIcon("DashboardSheetCrack") {
    duotoneSheet()
    line {
        moveTo(13f, 5f)
        lineTo(10.5f, 9.5f)
        lineTo(13.5f, 12.5f)
        lineTo(10.5f, 16f)
        lineTo(11.5f, 19f)
    }
}

// ── Skipped: sheet with skip-forward ────────────────────────────────────────────
private fun sheetSkip() = kkcIcon("DashboardSheetSkip") {
    duotoneSheet()
    line {
        moveTo(7.5f, 9f); lineTo(10.5f, 12f); lineTo(7.5f, 15f)
        moveTo(12f, 9f); lineTo(15f, 12f); lineTo(12f, 15f)
        moveTo(17f, 9f); verticalLineTo(15f)
    }
}

// ── Remakes: sheet with a redo loop ─────────────────────────────────────────────
private fun sheetRemake() = kkcIcon("DashboardSheetRemake") {
    duotoneSheet()
    // 270° clockwise loop, gap at the top
    line {
        moveTo(14.65f, 9.35f)
        arcTo(3.75f, 3.75f, 0f, true, true, 9.35f, 9.35f)
    }
    block(width = 1f) {
        moveTo(10.48f, 8.22f)
        lineTo(10.06f, 10.9f)
        lineTo(7.8f, 8.64f)
        close()
    }
}

// ── Miscellaneous / custom: sheet with a sparkle ────────────────────────────────
private fun sheetMisc() = kkcIcon("DashboardSheetMisc") {
    duotoneSheet()
    block(width = 1f) {
        moveTo(12f, 7.5f)
        quadTo(12f, 12f, 16.5f, 12f)
        quadTo(12f, 12f, 12f, 16.5f)
        quadTo(12f, 12f, 7.5f, 12f)
        quadTo(12f, 12f, 12f, 7.5f)
        close()
    }
}

// ── Recent materials: stack of sheets, front one solid ──────────────────────────
private fun sheetStack() = kkcIcon("DashboardSheetStack") {
    line {
        // back sheet: top + right edges peeking out
        moveTo(8f, 8f)
        verticalLineTo(6f)
        arcTo(1.5f, 1.5f, 0f, false, true, 9.5f, 4.5f)
        horizontalLineTo(19.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 21f, 6f)
        verticalLineTo(12f)
        arcTo(1.5f, 1.5f, 0f, false, true, 19.5f, 13.5f)
        horizontalLineTo(18.5f)
        // middle sheet
        moveTo(5.5f, 11.5f)
        verticalLineTo(9.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 7f, 8f)
        horizontalLineTo(17f)
        arcTo(1.5f, 1.5f, 0f, false, true, 18.5f, 9.5f)
        verticalLineTo(15.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 17f, 17f)
        horizontalLineTo(16f)
    }
    block { roundRect(3f, 11.5f, 16f, 20.5f, 1.5f) }
}

// ── Quality review: magnifier with a check in the lens ──────────────────────────
private fun quality() = kkcIcon("DashboardQuality") {
    solid(DUOTONE) { circle(10.5f, 10.5f, 6.5f) }
    line { circle(10.5f, 10.5f, 6.5f) }
    line(width = 2.5f) {
        moveTo(15.5f, 15.5f)
        lineTo(20.5f, 20.5f)
    }
    line(width = 1.75f) {
        moveTo(7.75f, 10.75f)
        lineTo(9.75f, 12.75f)
        lineTo(13.25f, 9.25f)
    }
}

// ── Remaining: hourglass, sand settled at the bottom ────────────────────────────
private fun remaining() = kkcIcon("DashboardRemaining") {
    solid(DUOTONE) {
        moveTo(8f, 3f)
        verticalLineTo(6.5f)
        curveTo(8f, 9f, 12f, 10.5f, 12f, 12f)
        curveTo(12f, 13.5f, 8f, 15f, 8f, 17.5f)
        verticalLineTo(21f)
        horizontalLineTo(16f)
        verticalLineTo(17.5f)
        curveTo(16f, 15f, 12f, 13.5f, 12f, 12f)
        curveTo(12f, 10.5f, 16f, 9f, 16f, 6.5f)
        verticalLineTo(3f)
        close()
    }
    line {
        moveTo(8f, 3f)
        verticalLineTo(6.5f)
        curveTo(8f, 9f, 12f, 10.5f, 12f, 12f)
        curveTo(12f, 13.5f, 8f, 15f, 8f, 17.5f)
        verticalLineTo(21f)
        moveTo(16f, 3f)
        verticalLineTo(6.5f)
        curveTo(16f, 9f, 12f, 10.5f, 12f, 12f)
        curveTo(12f, 13.5f, 16f, 15f, 16f, 17.5f)
        verticalLineTo(21f)
        moveTo(6.5f, 3f); horizontalLineTo(17.5f)
        moveTo(6.5f, 21f); horizontalLineTo(17.5f)
    }
    block(width = 1f) {
        moveTo(9.25f, 20f)
        lineTo(12f, 16.5f)
        lineTo(14.75f, 20f)
        close()
    }
}

val DashboardSheetCheckIcon: ImageVector by lazy { sheetCheck() }
val DashboardSheetCrackIcon: ImageVector by lazy { sheetCrack() }
val DashboardSheetSkipIcon: ImageVector by lazy { sheetSkip() }
val DashboardSheetRemakeIcon: ImageVector by lazy { sheetRemake() }
val DashboardSheetMiscIcon: ImageVector by lazy { sheetMisc() }
val DashboardSheetStackIcon: ImageVector by lazy { sheetStack() }
val DashboardQualityIcon: ImageVector by lazy { quality() }
val DashboardRemainingIcon: ImageVector by lazy { remaining() }
