package com.kkc.sheettracker.ui.components.icons

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder

// Remaining app icons: layout toggles, form section headers, job card, safety, calculator,
// timeclock background picker, delivery schedule, status. Style and helpers live in IconDsl.kt.

// ── Layout: list rows / kanban columns ──────────────────────────────────────────
private fun viewList() = kkcIcon("ViewList") {
    solid(DUOTONE) { for (y in listOf(4f, 10f, 16f)) roundRect(3f, y, 21f, y + 4.5f, 1f) }
    solid { for (y in listOf(4f, 10f, 16f)) roundRect(3f, y, 7.5f, y + 4.5f, 1f) }
}

private fun viewKanban() = kkcIcon("ViewKanban") {
    solid(DUOTONE) { for (x in listOf(2.5f, 9.25f, 16f)) roundRect(x, 3f, x + 5.5f, 21f, 1f) }
    solid {
        roundRect(3.5f, 4f, 7f, 8f, 0.6f); roundRect(3.5f, 9f, 7f, 13f, 0.6f)
        roundRect(10.25f, 4f, 13.75f, 8f, 0.6f)
        roundRect(17f, 4f, 20.5f, 8f, 0.6f); roundRect(17f, 9f, 20.5f, 13f, 0.6f); roundRect(17f, 14f, 20.5f, 18f, 0.6f)
    }
}

// ── Form sections: info, routing, specs (ruler), notes ──────────────────────────
private fun formInfo() = kkcIcon("FormInfo") {
    solid(DUOTONE) { circle(12f, 12f, 9f) }
    line { circle(12f, 12f, 9f) }
    line(width = 2.5f) { moveTo(12f, 11f); verticalLineTo(16.5f) }
    solid { circle(12f, 7.75f, 1.4f) }
}

private fun formRouting() = kkcIcon("FormRouting") {
    line {
        moveTo(5.5f, 6.5f)
        horizontalLineTo(14.5f)
        arcTo(3.25f, 3.25f, 0f, false, true, 14.5f, 13f)
        horizontalLineTo(9.5f)
        arcTo(3.25f, 3.25f, 0f, false, false, 9.5f, 19.5f)
        horizontalLineTo(17f)
        moveTo(15.5f, 17f); lineTo(18f, 19.5f); lineTo(15.5f, 22f)
    }
    solid { circle(5f, 6.5f, 2.5f) }
}

private fun formSpecs() = kkcIcon("FormSpecs") {
    val ruler: PathBuilder.() -> Unit = {
        moveTo(3f, 15.5f); lineTo(15.5f, 3f); lineTo(21f, 8.5f); lineTo(8.5f, 21f); close()
    }
    solid(DUOTONE, pathBuilder = ruler)
    line(width = 1.5f) {
        ruler()
        // tick marks along the edge
        moveTo(6.25f, 12.25f); lineTo(8f, 14f)
        moveTo(9.25f, 9.25f); lineTo(10.5f, 10.5f)
        moveTo(12.25f, 6.25f); lineTo(14f, 8f)
        moveTo(7.75f, 10.75f); lineTo(8.75f, 11.75f)
        moveTo(10.75f, 7.75f); lineTo(11.75f, 8.75f)
    }
}

private fun formNotes() = kkcIcon("FormNotes") {
    solid(DUOTONE) { roundRect(4.5f, 4f, 19.5f, 21.5f, 1.5f) }
    line(width = 1.5f) {
        roundRect(4.5f, 4f, 19.5f, 21.5f, 1.5f)
        moveTo(8f, 10f); horizontalLineTo(16f)
        moveTo(8f, 13.5f); horizontalLineTo(16f)
        moveTo(8f, 17f); horizontalLineTo(13f)
    }
    // spiral binding
    line {
        moveTo(8f, 2.5f); verticalLineTo(5.5f)
        moveTo(12f, 2.5f); verticalLineTo(5.5f)
        moveTo(16f, 2.5f); verticalLineTo(5.5f)
    }
}

// ── Job card: label tag, drag grip ──────────────────────────────────────────────
private fun labelTag() = kkcIcon("JobLabels") {
    val tag: PathBuilder.() -> Unit = {
        moveTo(3f, 4.5f); verticalLineTo(11f); lineTo(12.5f, 20.5f); lineTo(20.5f, 12.5f); lineTo(11f, 3f)
        horizontalLineTo(4.5f)
        arcTo(1.5f, 1.5f, 0f, false, false, 3f, 4.5f)
        close()
    }
    solid(DUOTONE, pathBuilder = tag)
    line(width = 1.5f, pathBuilder = tag)
    solid { circle(7.5f, 7.5f, 1.6f) }
}

private fun dragGrip() = kkcIcon("DragGrip") {
    solid { for (x in listOf(9f, 15f)) for (y in listOf(6f, 12f, 18f)) circle(x, y, 1.6f) }
}

// ── Safety: padlock (keyhole cut out), key, SDS document ────────────────────────
private fun lock() = kkcIcon("SafetyLock") {
    line {
        moveTo(8f, 10.5f); verticalLineTo(7.5f)
        arcTo(4f, 4f, 0f, false, true, 16f, 7.5f)
        verticalLineTo(10.5f)
    }
    solid(fillType = PathFillType.EvenOdd) {
        moveTo(5.5f, 10.5f); horizontalLineTo(18.5f)
        arcTo(1f, 1f, 0f, false, true, 19.5f, 11.5f); verticalLineTo(20f)
        arcTo(1.5f, 1.5f, 0f, false, true, 18f, 21.5f); horizontalLineTo(6f)
        arcTo(1.5f, 1.5f, 0f, false, true, 4.5f, 20f); verticalLineTo(11.5f)
        arcTo(1f, 1f, 0f, false, true, 5.5f, 10.5f); close()
        circle(12f, 15f, 1.25f)
        moveTo(11.4f, 15.5f); horizontalLineTo(12.6f); lineTo(12.9f, 18.25f); horizontalLineTo(11.1f); close()
    }
}

private fun key() = kkcIcon("SafetyKey") {
    solid(DUOTONE) { circle(7.5f, 12f, 4.5f) }
    line {
        circle(7.5f, 12f, 4.5f)
        moveTo(12f, 12f); horizontalLineTo(21.5f); verticalLineTo(15.5f)
        moveTo(18f, 12f); verticalLineTo(14.5f)
    }
    solid { circle(7.5f, 12f, 1.4f) }
}

private fun sdsDocument() = kkcIcon("SafetySds") {
    val page: PathBuilder.() -> Unit = {
        moveTo(6f, 2.5f); horizontalLineTo(14f); lineTo(19f, 7.5f); verticalLineTo(20f)
        arcTo(1.5f, 1.5f, 0f, false, true, 17.5f, 21.5f); horizontalLineTo(6.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 5f, 20f); verticalLineTo(3.5f)
        arcTo(1f, 1f, 0f, false, true, 6f, 2.5f); close()
    }
    solid(DUOTONE, pathBuilder = page)
    line(width = 1.5f, pathBuilder = page)
    // hazard diamond
    solid { moveTo(12f, 9.5f); lineTo(16.5f, 14f); lineTo(12f, 18.5f); lineTo(7.5f, 14f); close() }
}

// ── Calculator history: clock with a back arrow ─────────────────────────────────
private fun history() = kkcIcon("CalcHistory") {
    line {
        moveTo(4f, 12f)
        arcTo(8f, 8f, 0f, true, false, 6.5f, 6.2f)
        moveTo(3.5f, 3.5f); verticalLineTo(7.5f); horizontalLineTo(7.5f)
        moveTo(12f, 8f); verticalLineTo(12f); lineTo(15f, 14f)
    }
}

// ── Timeclock background picker: color palette, video ───────────────────────────
private fun palette() = kkcIcon("BgColor") {
    val pal: PathBuilder.() -> Unit = {
        moveTo(12f, 3f)
        curveTo(6.5f, 3f, 3f, 6.75f, 3f, 11.5f)
        curveTo(3f, 16f, 6.5f, 20.5f, 11.5f, 20.5f)
        curveTo(13f, 20.5f, 13.5f, 19.5f, 13.5f, 18.5f)
        curveTo(13.5f, 17f, 12.5f, 16.75f, 12.5f, 15.5f)
        curveTo(12.5f, 14.5f, 13.25f, 13.75f, 14.5f, 13.75f)
        horizontalLineTo(17f)
        curveTo(19.5f, 13.75f, 21f, 12f, 21f, 10f)
        curveTo(21f, 6f, 17f, 3f, 12f, 3f)
        close()
    }
    solid(DUOTONE, pathBuilder = pal)
    line(width = 1.5f, pathBuilder = pal)
    solid {
        circle(7.5f, 11f, 1.4f)
        circle(10f, 7f, 1.4f)
        circle(15f, 7f, 1.4f)
    }
}

private fun video() = kkcIcon("BgVideo") {
    solid(DUOTONE) { roundRect(2.5f, 5f, 21.5f, 19f, 1.5f) }
    line(width = 1.5f) { roundRect(2.5f, 5f, 21.5f, 19f, 1.5f) }
    block(width = 1.25f) { moveTo(10f, 9f); lineTo(15f, 12f); lineTo(10f, 15f); close() }
}

// ── Delivery schedule: save, location pin, copy ─────────────────────────────────
private fun save() = kkcIcon("ActionSave") {
    val disk: PathBuilder.() -> Unit = {
        moveTo(4.5f, 3f); horizontalLineTo(16f); lineTo(20.5f, 7.5f); verticalLineTo(19.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 19f, 21f); horizontalLineTo(5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 3.5f, 19.5f); verticalLineTo(4f)
        arcTo(1f, 1f, 0f, false, true, 4.5f, 3f); close()
    }
    solid(DUOTONE, pathBuilder = disk)
    line(width = 1.5f, pathBuilder = disk)
    solid {
        roundRect(7f, 3f, 15f, 8f, 0.5f)
        roundRect(7f, 13f, 17f, 21f, 0.75f)
    }
}

private fun locationPin() = kkcIcon("LocationPin") {
    solid(fillType = PathFillType.EvenOdd) {
        moveTo(12f, 21.5f)
        curveTo(12f, 21.5f, 19f, 14.5f, 19f, 9.5f)
        arcTo(7f, 7f, 0f, false, false, 5f, 9.5f)
        curveTo(5f, 14.5f, 12f, 21.5f, 12f, 21.5f)
        close()
        circle(12f, 9.5f, 2.5f)
    }
}

private fun copy() = kkcIcon("ActionCopy") {
    line(width = 1.5f) { roundRect(8f, 3f, 20.5f, 17.5f, 1.5f) }
    solid(DUOTONE) { roundRect(3.5f, 7f, 16f, 21.5f, 1.5f) }
    line(width = 1.5f) { roundRect(3.5f, 7f, 16f, 21.5f, 1.5f) }
}

// ── Status: warning triangle / all-good check ───────────────────────────────────
private fun statusWarning() = kkcIcon("StatusWarning") {
    val tri: PathBuilder.() -> Unit = { moveTo(12f, 3f); lineTo(22f, 20.5f); horizontalLineTo(2f); close() }
    solid(DUOTONE, pathBuilder = tri)
    line(pathBuilder = tri)
    line(width = 2.5f) { moveTo(12f, 9.5f); verticalLineTo(14f) }
    solid { circle(12f, 17.25f, 1.3f) }
}

private fun statusOk() = kkcIcon("StatusOk") {
    solid(DUOTONE) { circle(12f, 12f, 9f) }
    line { circle(12f, 12f, 9f) }
    line(width = 2.25f) { moveTo(8f, 12.25f); lineTo(10.75f, 15f); lineTo(16f, 9.5f) }
}

val ViewListIcon: ImageVector by lazy { viewList() }
val ViewKanbanIcon: ImageVector by lazy { viewKanban() }
val FormInfoIcon: ImageVector by lazy { formInfo() }
val FormRoutingIcon: ImageVector by lazy { formRouting() }
val FormSpecsIcon: ImageVector by lazy { formSpecs() }
val FormNotesIcon: ImageVector by lazy { formNotes() }
val JobLabelsIcon: ImageVector by lazy { labelTag() }
val DragGripIcon: ImageVector by lazy { dragGrip() }
val SafetyLockIcon: ImageVector by lazy { lock() }
val SafetyKeyIcon: ImageVector by lazy { key() }
val SafetySdsIcon: ImageVector by lazy { sdsDocument() }
val CalcHistoryIcon: ImageVector by lazy { history() }
val BgColorIcon: ImageVector by lazy { palette() }
val BgVideoIcon: ImageVector by lazy { video() }
val ActionSaveIcon: ImageVector by lazy { save() }
val LocationPinIcon: ImageVector by lazy { locationPin() }
val ActionCopyIcon: ImageVector by lazy { copy() }
val StatusWarningIcon: ImageVector by lazy { statusWarning() }
val StatusOkIcon: ImageVector by lazy { statusOk() }
