package com.kkc.sheettracker.ui.components.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder

// Library (Standards hub) tile icons. Tiles have no selected state, so they always use the
// duotone look. Style and helpers live in IconDsl.kt.

// ── Molding Library: 2 1/4" Colonial crown cross-section ────────────────────────
// Traced from Y:\Ready Jobs\.metadata\moldings\Crown\163.xml (bead / cove / bead on a
// sprung back), scaled to fill the icon. The profile XML's arc2d <arc> point is a point ON the
// arc (3-point arc), not its center — radii here are the file's 1/2", 3/4", 1/4" curves.
private fun molding() = kkcIcon("LibraryMolding") {
    block(width = 1.5f) {
        moveTo(3.5f, 20.5f)
        lineTo(3.5f, 17.667f)
        lineTo(16.722f, 3.5f)
        lineTo(20.5f, 3.5f)
        lineTo(20.5f, 6.333f)
        lineTo(18.67f, 6.791f)
        arcTo(3.778f, 3.778f, 0f, false, false, 16.036f, 9.165f)
        lineTo(14.844f, 12.444f)
        arcTo(5.667f, 5.667f, 0f, false, true, 11.225f, 15.911f)
        lineTo(9.403f, 16.486f)
        lineTo(9.403f, 17.431f)
        lineTo(8.092f, 17.669f)
        arcTo(1.889f, 1.889f, 0f, false, false, 6.572f, 19.189f)
        lineTo(6.333f, 20.5f)
        close()
    }
}

// ── Door Profiles: raised-panel door ────────────────────────────────────────────
private fun door() = kkcIcon("LibraryDoor") {
    solid(DUOTONE) { roundRect(5f, 2f, 19f, 22f, 1.5f) }
    line { roundRect(5f, 2f, 19f, 22f, 1.5f) }
    line(width = 1.5f) { roundRect(8f, 5f, 16f, 19f, 0.5f) }
    // raised field
    solid { roundRect(10f, 7.5f, 14f, 16.5f, 0.5f) }
    // bevels from panel corners to field corners
    line(width = 1.25f) {
        moveTo(8f, 5f); lineTo(10f, 7.5f)
        moveTo(16f, 5f); lineTo(14f, 7.5f)
        moveTo(8f, 19f); lineTo(10f, 16.5f)
        moveTo(16f, 19f); lineTo(14f, 16.5f)
    }
}

// ── KKC Standards: clipboard checklist ──────────────────────────────────────────
private fun standards() = kkcIcon("LibraryStandards") {
    solid(DUOTONE) { roundRect(5f, 4f, 19f, 22f, 2f) }
    line { roundRect(5f, 4f, 19f, 22f, 2f) }
    // clip
    block(width = 1.5f) { roundRect(9f, 2f, 15f, 6f, 1f) }
    // checkmarks
    line(width = 1.5f) {
        moveTo(7.75f, 11.25f); lineTo(9f, 12.5f); lineTo(11f, 10.25f)
        moveTo(7.75f, 16.25f); lineTo(9f, 17.5f); lineTo(11f, 15.25f)
    }
    line {
        moveTo(13f, 11.5f); horizontalLineTo(16.5f)
        moveTo(13f, 16.5f); horizontalLineTo(16.5f)
    }
}

// ── Safety / SDS: GHS hazard diamond ────────────────────────────────────────────
private fun safety() = kkcIcon("LibrarySafety") {
    val diamond: PathBuilder.() -> Unit = {
        moveTo(12f, 2.5f)
        lineTo(21.5f, 12f)
        lineTo(12f, 21.5f)
        lineTo(2.5f, 12f)
        close()
    }
    solid(DUOTONE, diamond)
    line(pathBuilder = diamond)
    line(width = 2.5f) {
        moveTo(12f, 7.5f)
        verticalLineTo(12.75f)
    }
    solid { circle(12f, 16.25f, 1.35f) }
}

// ── Archive: banker's box with a solid lid ──────────────────────────────────────
private fun archive() = kkcIcon("LibraryArchive") {
    solid(DUOTONE) { roundRect(4.5f, 9f, 19.5f, 21f, 1.5f) }
    line { roundRect(4.5f, 9f, 19.5f, 21f, 1.5f) }
    block { roundRect(3f, 4f, 21f, 9f, 1.5f) }
    // handle slot
    line {
        moveTo(10f, 13.5f)
        horizontalLineTo(14f)
    }
}

val LibraryMoldingIcon: ImageVector by lazy { molding() }
val LibraryDoorIcon: ImageVector by lazy { door() }
val LibraryStandardsIcon: ImageVector by lazy { standards() }
val LibrarySafetyIcon: ImageVector by lazy { safety() }
val LibraryArchiveIcon: ImageVector by lazy { archive() }
