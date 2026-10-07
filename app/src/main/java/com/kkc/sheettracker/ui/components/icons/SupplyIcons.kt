package com.kkc.sheettracker.ui.components.icons

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder

// Supply icons: subscribe bell, ordered box, photos, barcode scan, scanner flash, send.
// Style and helpers live in IconDsl.kt.

private val bell: PathBuilder.() -> Unit = {
    moveTo(12f, 3.5f)
    curveTo(8.7f, 3.5f, 6.5f, 6f, 6.5f, 9.5f)
    verticalLineTo(13.5f)
    lineTo(4.5f, 17f)
    horizontalLineTo(19.5f)
    lineTo(17.5f, 13.5f)
    verticalLineTo(9.5f)
    curveTo(17.5f, 6f, 15.3f, 3.5f, 12f, 3.5f)
    close()
}

private fun ImageVector.Builder.bellClapper() = line {
    moveTo(10f, 19.5f)
    arcTo(2f, 2f, 0f, false, false, 14f, 19.5f)
}

// ── Subscribe bell: outline when off, solid + ringing when on ───────────────────
private fun bellOff() = kkcIcon("SupplyBellOff") {
    line(pathBuilder = bell)
    bellClapper()
}

private fun bellOn() = kkcIcon("SupplyBellOn") {
    block(pathBuilder = bell)
    bellClapper()
    line(width = 1.6f) {
        moveTo(3f, 7.5f); curveTo(3f, 5.5f, 4f, 4f, 5f, 3f)
        moveTo(21f, 7.5f); curveTo(21f, 5.5f, 20f, 4f, 19f, 3f)
    }
}

private val box: PathBuilder.() -> Unit = {
    moveTo(3.5f, 8f); lineTo(12f, 4f); lineTo(20.5f, 8f)
    verticalLineTo(17f); lineTo(12f, 21f); lineTo(3.5f, 17f)
    close()
}

// ── Ordered: package box; on = solid box with a check cut out ───────────────────
private fun orderedOff() = kkcIcon("SupplyOrderedOff") {
    solid(DUOTONE, pathBuilder = box)
    line(width = 1.5f) {
        box()
        moveTo(3.5f, 8f); lineTo(12f, 12f); lineTo(20.5f, 8f)
        moveTo(12f, 12f); verticalLineTo(21f)
    }
}

private fun orderedOn() = kkcIcon("SupplyOrderedOn") {
    solid(fillType = PathFillType.EvenOdd) {
        box()
        // check mark hole
        moveTo(7.79f, 13.21f); lineTo(11f, 16.41f); lineTo(16.71f, 10.71f)
        lineTo(15.29f, 9.29f); lineTo(11f, 13.59f); lineTo(9.21f, 11.79f)
        close()
    }
    line(width = 1.5f, pathBuilder = box)
}

// ── Camera: body with a solid lens ──────────────────────────────────────────────
private fun camera() = kkcIcon("SupplyCamera") {
    val body: PathBuilder.() -> Unit = {
        moveTo(3f, 8.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 4.5f, 7f)
        horizontalLineTo(7.5f); lineTo(9f, 4.5f); horizontalLineTo(15f); lineTo(16.5f, 7f)
        horizontalLineTo(19.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 21f, 8.5f)
        verticalLineTo(18.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 19.5f, 20f)
        horizontalLineTo(4.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 3f, 18.5f)
        close()
    }
    solid(DUOTONE, pathBuilder = body)
    line(width = 1.5f, pathBuilder = body)
    solid { circle(12f, 13f, 3.5f) }
}

// ── Gallery: stacked photos, front one with a mountain + sun ────────────────────
private fun gallery() = kkcIcon("SupplyGallery") {
    line(width = 1.5f) { roundRect(6.5f, 3f, 21f, 15.5f, 1.25f) }
    solid(DUOTONE) { roundRect(3f, 7f, 17.5f, 20.5f, 1.25f) }
    line(width = 1.5f) { roundRect(3f, 7f, 17.5f, 20.5f, 1.25f) }
    solid {
        moveTo(3.5f, 19f); lineTo(8.5f, 13.5f); lineTo(11.5f, 16.5f); lineTo(13.5f, 14.5f)
        lineTo(17f, 18.5f); verticalLineTo(19.25f)
        arcTo(1.25f, 1.25f, 0f, false, true, 15.75f, 20.5f)
        horizontalLineTo(4.25f)
        close()
        circle(13.5f, 10.5f, 1.4f)
    }
}

// ── Barcode scan: barcode inside scan corners ───────────────────────────────────
private fun scan() = kkcIcon("SupplyScan") {
    line {
        moveTo(3f, 7.5f); verticalLineTo(4.5f); arcTo(1.5f, 1.5f, 0f, false, true, 4.5f, 3f); horizontalLineTo(7.5f)
        moveTo(16.5f, 3f); horizontalLineTo(19.5f); arcTo(1.5f, 1.5f, 0f, false, true, 21f, 4.5f); verticalLineTo(7.5f)
        moveTo(21f, 16.5f); verticalLineTo(19.5f); arcTo(1.5f, 1.5f, 0f, false, true, 19.5f, 21f); horizontalLineTo(16.5f)
        moveTo(7.5f, 21f); horizontalLineTo(4.5f); arcTo(1.5f, 1.5f, 0f, false, true, 3f, 19.5f); verticalLineTo(16.5f)
    }
    line(width = 1.4f) {
        for (x in listOf(6.5f, 9f, 11.5f, 13.5f, 15.5f, 17.5f)) {
            moveTo(x, 7.5f); verticalLineTo(16.5f)
        }
    }
}

private val bolt: PathBuilder.() -> Unit = {
    moveTo(13.5f, 2.5f); lineTo(5.5f, 13.5f); horizontalLineTo(11f)
    lineTo(9.5f, 21.5f); lineTo(18.5f, 9.5f); horizontalLineTo(12.5f)
    close()
}

// ── Scanner flash: solid bolt on, light slashed bolt off ────────────────────────
private fun flashOn() = kkcIcon("SupplyFlashOn") {
    block(width = 1.25f, pathBuilder = bolt)
}

private fun flashOff() = kkcIcon("SupplyFlashOff") {
    solid(DUOTONE, pathBuilder = bolt)
    line(width = 1.5f, pathBuilder = bolt)
    line { moveTo(3.5f, 3.5f); lineTo(20.5f, 20.5f) }
}

// ── Send: paper plane ───────────────────────────────────────────────────────────
private fun send() = kkcIcon("SupplySend") {
    val plane: PathBuilder.() -> Unit = {
        moveTo(3f, 11f); lineTo(21f, 3f); lineTo(14f, 21f); lineTo(11f, 13f); close()
    }
    solid(DUOTONE, pathBuilder = plane)
    line(width = 1.5f) {
        plane()
        moveTo(11f, 13f); lineTo(21f, 3f)
    }
}

val SupplyBellOffIcon: ImageVector by lazy { bellOff() }
val SupplyBellOnIcon: ImageVector by lazy { bellOn() }
val SupplyOrderedOffIcon: ImageVector by lazy { orderedOff() }
val SupplyOrderedOnIcon: ImageVector by lazy { orderedOn() }
val SupplyCameraIcon: ImageVector by lazy { camera() }
val SupplyGalleryIcon: ImageVector by lazy { gallery() }
val SupplyScanIcon: ImageVector by lazy { scan() }
val SupplyFlashOnIcon: ImageVector by lazy { flashOn() }
val SupplyFlashOffIcon: ImageVector by lazy { flashOff() }
val SupplySendIcon: ImageVector by lazy { send() }
