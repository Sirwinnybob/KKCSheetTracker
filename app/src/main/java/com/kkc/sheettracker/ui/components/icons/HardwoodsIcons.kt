package com.kkc.sheettracker.ui.components.icons

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder

// Hardwoods job-detail + cut-list icons (cut list types, rip sources, reference docs, print).
// Single state, duotone. Style and helpers live in IconDsl.kt.

// ── Rip: board with a table-saw blade rising through it ─────────────────────────
private fun rip() = kkcIcon("HardwoodsRip") {
    // top half of a 9-tooth blade, center (14.5, 11.5)
    block(width = 0.75f) {
        moveTo(9.5f, 11.5f)
        lineTo(8.38f, 10.53f); lineTo(9.8f, 9.79f)
        lineTo(9.08f, 8.49f); lineTo(10.67f, 8.29f)
        lineTo(10.43f, 6.82f); lineTo(12f, 7.17f)
        lineTo(12.28f, 5.71f); lineTo(13.63f, 6.58f)
        lineTo(14.39f, 5.3f); lineTo(15.37f, 6.58f)
        lineTo(16.52f, 5.64f); lineTo(17f, 7.17f)
        lineTo(18.4f, 6.68f); lineTo(18.33f, 8.29f)
        lineTo(19.81f, 8.31f); lineTo(19.2f, 9.79f)
        lineTo(20.59f, 10.32f); lineTo(19.5f, 11.5f)
        close()
    }
    solid(DUOTONE) { roundRect(2.5f, 11.5f, 21.5f, 17.5f, 1f) }
    line { roundRect(2.5f, 11.5f, 21.5f, 17.5f, 1f) }
    // rip line ahead of the blade
    line(width = 1.75f) {
        moveTo(5f, 14.5f); horizontalLineTo(6.5f)
        moveTo(8.5f, 14.5f); horizontalLineTo(10f)
    }
}

// ── Face frame: solid frame with light drawer + door openings (Jobs cabinet layout) ──
private fun faceFrame() = kkcIcon("HardwoodsFaceFrame") {
    val openings: PathBuilder.() -> Unit = {
        moveTo(6f, 6f); horizontalLineTo(18f); verticalLineTo(8.5f); horizontalLineTo(6f); close()
        moveTo(6f, 11f); horizontalLineTo(11f); verticalLineTo(18f); horizontalLineTo(6f); close()
        moveTo(13f, 11f); horizontalLineTo(18f); verticalLineTo(18f); horizontalLineTo(13f); close()
    }
    solid(fillType = PathFillType.EvenOdd) {
        roundRect(3f, 3f, 21f, 21f, 2f)
        openings()
    }
    solid(DUOTONE, pathBuilder = openings)
}

// ── Nailer: strip with nail heads ───────────────────────────────────────────────
private fun nailer() = kkcIcon("HardwoodsNailer") {
    solid(DUOTONE) { roundRect(2.5f, 8.5f, 21.5f, 15.5f, 1f) }
    line { roundRect(2.5f, 8.5f, 21.5f, 15.5f, 1f) }
    solid {
        circle(6.5f, 12f, 1.4f)
        circle(12f, 12f, 1.4f)
        circle(17.5f, 12f, 1.4f)
    }
}

// ── Door cut list: exploded door — full-length stiles, rails between, panel center ──
private fun doorParts() = kkcIcon("HardwoodsDoorParts") {
    solid {
        // stiles run full length
        roundRect(3.5f, 2f, 5.75f, 22f, 0.6f)
        roundRect(18.25f, 2f, 20.5f, 22f, 0.6f)
        // rails fit between the stiles
        roundRect(7f, 2.5f, 17f, 4.75f, 0.6f)
        roundRect(7f, 19.25f, 17f, 21.5f, 0.6f)
    }
    // panel: light fill only, no border
    solid(DUOTONE) { roundRect(7f, 6f, 17f, 18f, 0.75f) }
}

// ── Closet rod: solid clothes hanger ────────────────────────────────────────────
private fun closetRod() = kkcIcon("HardwoodsClosetRod") {
    line {
        moveTo(9.5f, 7f)
        arcTo(2.5f, 2.5f, 0f, true, true, 12f, 9.5f)
        verticalLineTo(11.5f)
    }
    solid {
        moveTo(12f, 11f)
        lineTo(21f, 18.5f)
        arcTo(1f, 1f, 0f, false, true, 20.4f, 20f)
        horizontalLineTo(3.6f)
        arcTo(1f, 1f, 0f, false, true, 3f, 18.5f)
        close()
    }
}

// ── Board stock / hardwoods progress: lumber pile, bottom board solid ───────────
private fun boardStock() = kkcIcon("HardwoodsBoardStock") {
    val upper: PathBuilder.() -> Unit = {
        roundRect(6.5f, 4.5f, 17.5f, 8.5f, 1f)
        roundRect(4.5f, 10.5f, 19.5f, 14.5f, 1f)
    }
    solid(DUOTONE, pathBuilder = upper)
    line(pathBuilder = upper)
    block { roundRect(2.5f, 16.5f, 21.5f, 20.5f, 1f) }
}

// ── Specialty / stock-custom: two sparkles ──────────────────────────────────────
private fun specialty() = kkcIcon("HardwoodsSpecialty") {
    block(width = 1f) {
        moveTo(10f, 6f)
        quadTo(10f, 13f, 17f, 13f)
        quadTo(10f, 13f, 10f, 20f)
        quadTo(10f, 13f, 3f, 13f)
        quadTo(10f, 13f, 10f, 6f)
        close()
        moveTo(18f, 3f)
        quadTo(18f, 6.5f, 21.5f, 6.5f)
        quadTo(18f, 6.5f, 18f, 10f)
        quadTo(18f, 6.5f, 14.5f, 6.5f)
        quadTo(18f, 6.5f, 18f, 3f)
        close()
    }
}

// ── Assembly reference: exploded cabinet — top lifted off, side panel pulled out ──
private fun assembly() = kkcIcon("ReferenceAssembly") {
    // lifted top panel
    block(width = 1.5f) {
        moveTo(4.5f, 6.5f); lineTo(8.5f, 3f); horizontalLineTo(18.5f); lineTo(14.5f, 6.5f); close()
    }
    // cabinet front (Jobs icon layout)
    val front: PathBuilder.() -> Unit = {
        moveTo(4.5f, 10f); horizontalLineTo(14.5f); verticalLineTo(20f); horizontalLineTo(4.5f); close()
    }
    solid(DUOTONE, pathBuilder = front)
    line(pathBuilder = front)
    line(width = 1.5f) {
        moveTo(4.5f, 13.5f); horizontalLineTo(14.5f)
        moveTo(9.5f, 13.5f); verticalLineTo(20f)
        moveTo(8.5f, 11.75f); horizontalLineTo(10.5f)
        moveTo(8f, 16f); verticalLineTo(17.5f)
        moveTo(11f, 16f); verticalLineTo(17.5f)
    }
    // open top rim of the box
    line {
        moveTo(4.5f, 10f); lineTo(8.5f, 6.5f); horizontalLineTo(18.5f); lineTo(14.5f, 10f)
    }
    // side panel pulled away to the right
    val side: PathBuilder.() -> Unit = {
        moveTo(17.5f, 10.5f); lineTo(21f, 7.5f); verticalLineTo(17f); lineTo(17.5f, 20f); close()
    }
    solid(DUOTONE, pathBuilder = side)
    line(pathBuilder = side)
}

// ── Plans & elevations: drawing sheet with a dimensioned two-cabinet elevation ──
private fun plans() = kkcIcon("ReferencePlans") {
    solid(DUOTONE) { roundRect(2.5f, 4f, 21.5f, 20f, 1.5f) }
    line { roundRect(2.5f, 4f, 21.5f, 20f, 1.5f) }
    line(width = 1.5f) {
        // dimension line with end ticks
        moveTo(6f, 7.5f); horizontalLineTo(18f)
        moveTo(6f, 6.5f); verticalLineTo(8.5f)
        moveTo(18f, 6.5f); verticalLineTo(8.5f)
        roundRect(6f, 10.5f, 11.5f, 17f, 0.4f)
        roundRect(12.5f, 10.5f, 18f, 17f, 0.4f)
    }
}

// ── Delivery: box truck ─────────────────────────────────────────────────────────
private fun delivery() = kkcIcon("ReferenceDelivery") {
    solid(DUOTONE) { roundRect(2f, 5.5f, 14f, 16.5f, 1.25f) }
    line {
        moveTo(14f, 16.5f)
        verticalLineTo(5.5f)
        horizontalLineTo(3.25f)
        arcTo(1.25f, 1.25f, 0f, false, false, 2f, 6.75f)
        verticalLineTo(15.25f)
        arcTo(1.25f, 1.25f, 0f, false, false, 3.25f, 16.5f)
        horizontalLineTo(4.5f)
        moveTo(14f, 16.5f); horizontalLineTo(9f)
        // cab
        moveTo(14f, 8.5f)
        horizontalLineTo(17.5f)
        lineTo(21f, 12f)
        verticalLineTo(16.5f)
        horizontalLineTo(19.5f)
        moveTo(17.5f, 8.5f); verticalLineTo(12f); horizontalLineTo(21f)
    }
    block {
        circle(6.75f, 17.25f, 2f)
        circle(17.25f, 17.25f, 2f)
    }
}

// ── Pulls: bar pull mounted on a door edge ──────────────────────────────────────
private fun pulls() = kkcIcon("ReferencePulls") {
    solid(DUOTONE) { roundRect(2.5f, 15f, 21.5f, 20.5f, 1f) }
    line { roundRect(2.5f, 15f, 21.5f, 20.5f, 1f) }
    line(width = 2.5f) {
        moveTo(7f, 10f); verticalLineTo(15f)
        moveTo(17f, 10f); verticalLineTo(15f)
    }
    block { roundRect(3.5f, 6.5f, 20.5f, 10f, 1.75f) }
}

// ── View 3D: isometric box ──────────────────────────────────────────────────────
private fun view3d() = kkcIcon("ReferenceView3D") {
    block {
        moveTo(12f, 2.5f); lineTo(20.5f, 7f); lineTo(12f, 11.5f); lineTo(3.5f, 7f); close()
    }
    solid(DUOTONE) {
        moveTo(3.5f, 7f); lineTo(12f, 11.5f); verticalLineTo(21.5f); lineTo(3.5f, 17f); close()
    }
    line {
        moveTo(12f, 2.5f); lineTo(20.5f, 7f); verticalLineTo(17f); lineTo(12f, 21.5f)
        lineTo(3.5f, 17f); verticalLineTo(7f); close()
        moveTo(3.5f, 7f); lineTo(12f, 11.5f); lineTo(20.5f, 7f)
        moveTo(12f, 11.5f); verticalLineTo(21.5f)
    }
}

// ── Print: printer with a page coming out ───────────────────────────────────────
private fun print() = kkcIcon("ActionPrint") {
    solid(DUOTONE) { roundRect(3f, 9f, 21f, 17f, 1.5f) }
    line {
        moveTo(7f, 17f)
        horizontalLineTo(4.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 3f, 15.5f)
        verticalLineTo(10.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 4.5f, 9f)
        horizontalLineTo(19.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 21f, 10.5f)
        verticalLineTo(15.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 19.5f, 17f)
        horizontalLineTo(17f)
        moveTo(7f, 9f); verticalLineTo(3.5f); horizontalLineTo(17f); verticalLineTo(9f)
    }
    block { roundRect(7f, 13.5f, 17f, 21f, 0.75f) }
    solid { circle(17.5f, 12f, 0.9f) }
}

val HardwoodsRipIcon: ImageVector by lazy { rip() }
val HardwoodsFaceFrameIcon: ImageVector by lazy { faceFrame() }
val HardwoodsNailerIcon: ImageVector by lazy { nailer() }
val HardwoodsDoorPartsIcon: ImageVector by lazy { doorParts() }
val HardwoodsClosetRodIcon: ImageVector by lazy { closetRod() }
val HardwoodsBoardStockIcon: ImageVector by lazy { boardStock() }
val HardwoodsSpecialtyIcon: ImageVector by lazy { specialty() }
val ReferenceAssemblyIcon: ImageVector by lazy { assembly() }
val ReferencePlansIcon: ImageVector by lazy { plans() }
val ReferenceDeliveryIcon: ImageVector by lazy { delivery() }
val ReferencePullsIcon: ImageVector by lazy { pulls() }
val ReferenceView3DIcon: ImageVector by lazy { view3d() }
val ActionPrintIcon: ImageVector by lazy { print() }
