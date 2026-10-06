package com.kkc.sheettracker.ui.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

// Specialty job-detail station + action icons. Single state, duotone.
// Style and helpers live in IconDsl.kt.

// ── CNC: spindle with a router bit over a sheet ─────────────────────────────────
private fun cnc() = kkcIcon("StationCnc") {
    solid {
        roundRect(8f, 2f, 16f, 8f, 1f)
        moveTo(10f, 8.5f); horizontalLineTo(14f); verticalLineTo(11.5f); horizontalLineTo(10f); close()
        // bit
        moveTo(10.5f, 11.5f); horizontalLineTo(13.5f); lineTo(12.5f, 16f); horizontalLineTo(11.5f); close()
    }
    solid(DUOTONE) { roundRect(2.5f, 17.5f, 21.5f, 21.5f, 1f) }
    line {
        moveTo(12f, 16.5f)
        verticalLineTo(17.5f)
    }
}

// ── Edge banding: spiral roll whose tail runs onto a solid edge ─────────────────
// Restyle of the sheet viewer's original hand-drawn banding marker.
private fun edgeBander() = kkcIcon("StationEdgeBander") {
    solid(DUOTONE) { circle(7.5f, 8.5f, 5f) }
    line(width = 1.6f) {
        moveTo(6.5f, 8.5f)
        arcTo(1f, 1f, 0f, false, true, 8.5f, 8.5f)
        arcTo(2f, 2f, 0f, false, true, 4.5f, 8.5f)
        arcTo(3f, 3f, 0f, false, true, 10.5f, 8.5f)
        arcTo(4f, 4f, 0f, false, true, 2.5f, 8.5f)
        arcTo(5f, 5f, 0f, false, true, 12.5f, 8.5f)
        curveTo(12.5f, 13.5f, 14f, 15.5f, 17f, 15.5f)
        horizontalLineTo(21.5f)
    }
    // faded panel with the solid banding along its top edge
    solid(DUOTONE) {
        moveTo(2.5f, 16.25f)
        horizontalLineTo(21.5f)
        verticalLineTo(20.75f)
        arcTo(0.75f, 0.75f, 0f, false, true, 20.75f, 21.5f)
        horizontalLineTo(3.25f)
        arcTo(0.75f, 0.75f, 0f, false, true, 2.5f, 20.75f)
        close()
    }
    solid { roundRect(2.5f, 14.75f, 21.5f, 16.5f, 0.5f) }
}

// ── Sheet rips: sheet with dashed rip lines ─────────────────────────────────────
private fun sheetRips() = kkcIcon("StationSheetRips") {
    solid(DUOTONE) { roundRect(2.5f, 5f, 21.5f, 19f, 1.5f) }
    line { roundRect(2.5f, 5f, 21.5f, 19f, 1.5f) }
    line(width = 1.5f) {
        for (y in listOf(9.7f, 14.3f)) {
            var x = 4.5f
            while (x < 20f) {
                moveTo(x, y); horizontalLineTo(x + 1f)
                x += 3f
            }
        }
    }
}

// ── Saw: top-down sliding table saw (SCM nova) ──────────────────────────────────
// Main table with the guard arm from the back post down to the blade,
// sliding carriage across the middle, outrigger table and crosscut fence below.
private fun saw() = kkcIcon("StationSaw") {
    // main table
    solid(DUOTONE) { roundRect(6f, 3f, 18.5f, 9.5f, 0.75f) }
    // sliding carriage
    solid(DUOTONE) { roundRect(2f, 11f, 22f, 13.75f, 0.75f) }
    line(width = 1.25f) { roundRect(2f, 11f, 22f, 13.75f, 0.75f) }
    // outrigger table
    solid(DUOTONE) { roundRect(5.75f, 15.5f, 11.75f, 19.5f, 0.5f) }
    line(width = 1.25f) { roundRect(5.75f, 15.5f, 11.75f, 19.5f, 0.5f) }
    solid {
        // crosscut fence
        roundRect(2.75f, 13.75f, 4.5f, 22f, 0.6f)
        // guard post
        circle(12f, 2f, 1.25f)
    }
    // guard arm from the back post, down to the blade
    line(width = 1.75f) {
        moveTo(12f, 2.25f)
        verticalLineTo(9.75f)
    }
    // blade (stands in for the guard too) at the carriage edge
    line(width = 1.75f) {
        moveTo(9f, 9.75f)
        horizontalLineTo(15f)
    }
}

// ── Split view: tablet split top (PDF) / bottom (list) ──────────────────────────
private fun splitView() = kkcIcon("ActionSplitView") {
    solid {
        moveTo(3f, 12f)
        verticalLineTo(4.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 4.5f, 3f)
        horizontalLineTo(19.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 21f, 4.5f)
        verticalLineTo(12f)
        close()
    }
    solid(DUOTONE) {
        moveTo(3f, 12f)
        horizontalLineTo(21f)
        verticalLineTo(19.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 19.5f, 21f)
        horizontalLineTo(4.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 3f, 19.5f)
        close()
    }
    line { roundRect(3f, 3f, 21f, 21f, 1.5f) }
}

val StationCncIcon: ImageVector by lazy { cnc() }
val StationEdgeBanderIcon: ImageVector by lazy { edgeBander() }
val StationSawIcon: ImageVector by lazy { saw() }
val StationSheetRipsIcon: ImageVector by lazy { sheetRips() }
val ActionSplitViewIcon: ImageVector by lazy { splitView() }
