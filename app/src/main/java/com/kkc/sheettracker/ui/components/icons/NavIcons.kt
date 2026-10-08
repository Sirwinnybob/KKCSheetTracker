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

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import kotlin.math.cos
import kotlin.math.sin

// Bottom-navbar icons. Style and helpers live in IconDsl.kt.
//   Unselected: outline only. Selected: duotone.

// ── Dashboard: 2×2 tiles, bottom-right tile is a progress ring ──────────────────
private fun dashboard(selected: Boolean) = kkcIcon("NavDashboard.${state(selected)}") {
    val tiles: PathBuilder.() -> Unit = {
        roundRect(3f, 3f, 10f, 10f, 1.5f)
        roundRect(14f, 3f, 21f, 10f, 1.5f)
        roundRect(3f, 14f, 10f, 21f, 1.5f)
    }
    if (selected) {
        block(pathBuilder = tiles)
        line(alpha = DUOTONE) { circle(17.5f, 17.5f, 3.5f) }
    } else {
        line(pathBuilder = tiles)
    }
    // 3/4 progress arc, clockwise from 12 o'clock to 9 o'clock
    line {
        moveTo(17.5f, 14f)
        arcTo(3.5f, 3.5f, 0f, true, true, 14f, 17.5f)
    }
}

// ── Jobs: base cabinet front — drawer over a pair of doors ──────────────────────
private fun jobs(selected: Boolean) = kkcIcon("NavJobs.${state(selected)}") {
    if (selected) solid(DUOTONE) { roundRect(3f, 3f, 21f, 21f, 2f) }
    line {
        roundRect(3f, 3f, 21f, 21f, 2f)
        // drawer / door split
        moveTo(3f, 9f); horizontalLineTo(21f)
        moveTo(12f, 9f); verticalLineTo(21f)
        // drawer pull
        moveTo(10f, 6f); horizontalLineTo(14f)
        // door pulls
        moveTo(9f, 13f); verticalLineTo(15.5f)
        moveTo(15f, 13f); verticalLineTo(15.5f)
    }
}

// ── Search: magnifier ───────────────────────────────────────────────────────────
private fun search(selected: Boolean) = kkcIcon("NavSearch.${state(selected)}") {
    if (selected) {
        solid(DUOTONE) { circle(10.5f, 10.5f, 6.5f) }
        // lens glint
        line(width = 1.5f) {
            moveTo(7.5f, 10.5f)
            arcTo(3f, 3f, 0f, false, true, 10.5f, 7.5f)
        }
    }
    line { circle(10.5f, 10.5f, 6.5f) }
    line(width = 2.5f) {
        moveTo(15.5f, 15.5f)
        lineTo(20.5f, 20.5f)
    }
}

// ── Hours: clock; selected fills the 15-minute wedge the hub rounds to ──────────
private fun hours(selected: Boolean) = kkcIcon("NavHours.${state(selected)}") {
    if (selected) {
        solid(DUOTONE) { circle(12f, 12f, 9f) }
        block {
            moveTo(12f, 12f)
            verticalLineTo(5.5f)
            arcTo(6.5f, 6.5f, 0f, false, true, 18.5f, 12f)
            close()
        }
    }
    line { circle(12f, 12f, 9f) }
    if (!selected) {
        line {
            moveTo(12f, 7f)
            verticalLineTo(12f)
            horizontalLineTo(16f)
        }
    }
}

// ── Timeclock: original RTC card layout — swipe strip, numpad, action keys ──────
private fun timeclock(selected: Boolean) = kkcIcon("NavTimeclock.${state(selected)}") {
    if (selected) solid(DUOTONE) { roundRect(3f, 2f, 21f, 22f, 2f) }
    line { roundRect(3f, 2f, 21f, 22f, 2f) }
    line(width = 4f) {
        moveTo(6.5f, 6.5f)
        lineTo(15.5f, 6.5f)
    }
    solid {
        for (cy in listOf(12f, 15f, 18f)) {
            for (cx in listOf(6.5f, 9.5f, 12.5f)) circle(cx, cy, 1f)
        }
        // action button column
        circle(16.5f, 12f, 1.5f)
        circle(16.5f, 18f, 1.5f)
    }
}

// ── Supply: forklift carrying a load ────────────────────────────────────────────
private fun supply(selected: Boolean) = kkcIcon("NavSupply.${state(selected)}") {
    val body: PathBuilder.() -> Unit = { roundRect(2.5f, 11f, 13f, 16.5f, 1.5f) }
    val cab: PathBuilder.() -> Unit = {
        moveTo(5f, 11f)
        verticalLineTo(5f)
        horizontalLineTo(9.5f)
        lineTo(12.5f, 11f)
    }
    val wheels: PathBuilder.() -> Unit = {
        circle(5.5f, 18.5f, 2f)
        circle(11f, 18.5f, 2f)
    }
    val load: PathBuilder.() -> Unit = { roundRect(17.5f, 13f, 21.5f, 18f, 1f) }

    if (selected) {
        solid(DUOTONE) {
            body()
            cab(); close()
        }
        block(pathBuilder = wheels)
        block(pathBuilder = load)
    } else {
        line(pathBuilder = wheels)
        line(pathBuilder = load)
    }
    line(pathBuilder = body)
    line(pathBuilder = cab)
    // mast + forks
    line {
        moveTo(15.5f, 3.5f)
        verticalLineTo(20f)
        horizontalLineTo(21.5f)
    }
}

// ── Library: binder with lines of text on the cover ─────────────────────────────
private fun library(selected: Boolean) = kkcIcon("NavLibrary.${state(selected)}") {
    if (selected) {
        solid(DUOTONE) { roundRect(4f, 2.5f, 20f, 21.5f, 2f) }
        // solid spine
        solid {
            moveTo(6f, 2.5f)
            horizontalLineTo(8f)
            verticalLineTo(21.5f)
            horizontalLineTo(6f)
            arcTo(2f, 2f, 0f, false, true, 4f, 19.5f)
            verticalLineTo(4.5f)
            arcTo(2f, 2f, 0f, false, true, 6f, 2.5f)
            close()
        }
    }
    line {
        roundRect(4f, 2.5f, 20f, 21.5f, 2f)
        moveTo(8f, 2.5f)
        verticalLineTo(21.5f)
    }
    // text lines, last one short
    line {
        moveTo(11.5f, 7.5f); horizontalLineTo(16.5f)
        moveTo(11.5f, 11f); horizontalLineTo(16.5f)
        moveTo(11.5f, 14.5f); horizontalLineTo(16.5f)
        moveTo(11.5f, 18f); horizontalLineTo(14.5f)
    }
}

// ── Settings: 8-tooth gear ──────────────────────────────────────────────────────
private fun PathBuilder.gear() {
    val c = 12f
    val outer = 9f
    val inner = 6.8f
    fun pt(r: Float, deg: Float): Pair<Float, Float> {
        val rad = Math.toRadians(deg.toDouble())
        return (c + r * cos(rad)).toFloat() to (c + r * sin(rad)).toFloat()
    }
    val (sx, sy) = pt(inner, -14f)
    moveTo(sx, sy)
    for (i in 0 until 8) {
        val t = i * 45f
        pt(outer, t - 8f).let { (x, y) -> lineTo(x, y) }
        pt(outer, t + 8f).let { (x, y) -> arcTo(outer, outer, 0f, false, true, x, y) }
        pt(inner, t + 14f).let { (x, y) -> lineTo(x, y) }
        pt(inner, t + 31f).let { (x, y) -> arcTo(inner, inner, 0f, false, true, x, y) }
    }
    close()
}

private fun settings(selected: Boolean) = kkcIcon("NavSettings.${state(selected)}") {
    if (selected) solid(DUOTONE) { gear() }
    line { gear() }
    line { circle(12f, 12f, 3f) }
}

// ── Calculator: display over operator keys ──────────────────────────────────────
private fun calculator(selected: Boolean) = kkcIcon("NavCalculator.${state(selected)}") {
    if (selected) {
        solid(DUOTONE) { roundRect(5f, 2f, 19f, 22f, 2.5f) }
        solid { roundRect(8f, 5f, 16f, 8.5f, 1f) }
    }
    line { roundRect(5f, 2f, 19f, 22f, 2.5f) }
    line(width = 1.5f) {
        if (!selected) roundRect(8f, 5f, 16f, 8.5f, 1f)
        // +
        moveTo(7.5f, 13.5f); horizontalLineTo(10.5f)
        moveTo(9f, 12f); verticalLineTo(15f)
        // −
        moveTo(13.5f, 13.5f); horizontalLineTo(16.5f)
        // ×
        moveTo(7.9f, 16.9f); lineTo(10.1f, 19.1f)
        moveTo(10.1f, 16.9f); lineTo(7.9f, 19.1f)
        // =
        moveTo(13.5f, 16.8f); horizontalLineTo(16.5f)
        moveTo(13.5f, 19.2f); horizontalLineTo(16.5f)
    }
}

private fun state(selected: Boolean) = if (selected) "selected" else "unselected"

val NavDashboardSelected: ImageVector by lazy { dashboard(true) }
val NavDashboardUnselected: ImageVector by lazy { dashboard(false) }
val NavJobsSelected: ImageVector by lazy { jobs(true) }
val NavJobsUnselected: ImageVector by lazy { jobs(false) }
val NavSearchSelected: ImageVector by lazy { search(true) }
val NavSearchUnselected: ImageVector by lazy { search(false) }
val NavHoursSelected: ImageVector by lazy { hours(true) }
val NavHoursUnselected: ImageVector by lazy { hours(false) }
val NavTimeclockSelected: ImageVector by lazy { timeclock(true) }
val NavTimeclockUnselected: ImageVector by lazy { timeclock(false) }
val NavSupplySelected: ImageVector by lazy { supply(true) }
val NavSupplyUnselected: ImageVector by lazy { supply(false) }
val NavLibrarySelected: ImageVector by lazy { library(true) }
val NavLibraryUnselected: ImageVector by lazy { library(false) }
val NavSettingsSelected: ImageVector by lazy { settings(true) }
val NavSettingsUnselected: ImageVector by lazy { settings(false) }
val NavCalculatorSelected: ImageVector by lazy { calculator(true) }
val NavCalculatorUnselected: ImageVector by lazy { calculator(false) }
