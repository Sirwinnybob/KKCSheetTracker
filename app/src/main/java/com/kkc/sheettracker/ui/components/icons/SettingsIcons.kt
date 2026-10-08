package com.kkc.sheettracker.ui.components.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder

// Settings rail section icons. Same rule as the navbar:
//   Unselected: outline only. Selected: duotone body; solid details stay solid in both states.
// Style and helpers live in IconDsl.kt.

private fun state(selected: Boolean) = if (selected) "selected" else "unselected"

// ── Look & Feel: paint palette with four solid paint wells ──────────────────────
private fun lookFeel(selected: Boolean) = kkcIcon("SettingsLookFeel.${state(selected)}") {
    val body: PathBuilder.() -> Unit = {
        moveTo(12f, 3f)
        curveTo(7f, 3f, 3f, 6.8f, 3f, 11.5f)
        reflectiveCurveTo(6.8f, 20f, 11.5f, 20f)
        curveTo(13f, 20f, 13.5f, 19f, 13f, 18f)
        curveTo(12.4f, 16.8f, 13.2f, 15.5f, 14.5f, 15.5f)
        horizontalLineTo(16.5f)
        curveTo(19f, 15.5f, 21f, 13.5f, 21f, 11f)
        curveTo(21f, 6.6f, 17f, 3f, 12f, 3f)
        close()
    }
    if (selected) solid(DUOTONE, pathBuilder = body)
    line(pathBuilder = body)
    solid {
        circle(7.5f, 11.5f, 1.3f)
        circle(9.5f, 7.3f, 1.3f)
        circle(14.5f, 7f, 1.3f)
        circle(17.3f, 10.5f, 1.3f)
    }
}

// ── Viewers: sheet with an eye ──────────────────────────────────────────────────
private fun viewers(selected: Boolean) = kkcIcon("SettingsViewers.${state(selected)}") {
    if (selected) solid(DUOTONE) { roundRect(4.5f, 2f, 19.5f, 22f, 2f) }
    line { roundRect(4.5f, 2f, 19.5f, 22f, 2f) }
    line { moveTo(8f, 6.5f); horizontalLineTo(13f) }
    line(width = 1.5f) {
        moveTo(7.5f, 14.5f)
        quadTo(12f, 9.5f, 16.5f, 14.5f)
        quadTo(12f, 19.5f, 7.5f, 14.5f)
        close()
    }
    solid { circle(12f, 14.5f, 1.6f) }
}

// ── Me: ID badge on a clip ──────────────────────────────────────────────────────
private fun me(selected: Boolean) = kkcIcon("SettingsMe.${state(selected)}") {
    if (selected) solid(DUOTONE) { roundRect(3.5f, 5f, 20.5f, 21f, 2f) }
    line { roundRect(3.5f, 5f, 20.5f, 21f, 2f) }
    block(width = 1.5f) { roundRect(10f, 2.5f, 14f, 6.5f, 1f) }
    line(width = 1.5f) {
        circle(9f, 12f, 2f)
        moveTo(6f, 17.5f)
        curveTo(6f, 15.8f, 7.3f, 14.8f, 9f, 14.8f)
        reflectiveCurveTo(12f, 15.8f, 12f, 17.5f)
    }
    line {
        moveTo(15f, 12f); horizontalLineTo(17.5f)
        moveTo(15f, 15.5f); horizontalLineTo(17.5f)
    }
}

// ── Updates & About: refresh loop with a download arrow ─────────────────────────
private fun updates(selected: Boolean) = kkcIcon("SettingsUpdates.${state(selected)}") {
    if (selected) solid(DUOTONE) { circle(12f, 12f, 8f) }
    line {
        moveTo(20f, 12f)
        arcTo(8f, 8f, 0f, true, true, 17.7f, 6.3f)
        moveTo(18.5f, 2.5f); verticalLineTo(6.5f); horizontalLineTo(14.5f)
        moveTo(12f, 7.5f); verticalLineTo(15.5f)
        moveTo(9f, 12.5f); lineTo(12f, 15.5f); lineTo(15f, 12.5f)
    }
}

// ── Tablet & Data: tablet with a folder in front ────────────────────────────────
private fun tabletData(selected: Boolean) = kkcIcon("SettingsTabletData.${state(selected)}") {
    line {
        moveTo(13f, 21f)
        horizontalLineTo(5f)
        arcTo(2f, 2f, 0f, false, true, 3f, 19f)
        verticalLineTo(5f)
        arcTo(2f, 2f, 0f, false, true, 5f, 3f)
        horizontalLineTo(13f)
        arcTo(2f, 2f, 0f, false, true, 15f, 5f)
        verticalLineTo(10f)
        moveTo(6.5f, 7f); horizontalLineTo(11.5f)
    }
    val folder: PathBuilder.() -> Unit = {
        moveTo(11f, 13f)
        horizontalLineTo(14f)
        lineTo(15.5f, 14.5f)
        horizontalLineTo(21f)
        verticalLineTo(21f)
        horizontalLineTo(11f)
        close()
    }
    if (selected) solid(DUOTONE, pathBuilder = folder)
    line(pathBuilder = folder)
}

// ── Sync & Network: cloud with up/down arrows ───────────────────────────────────
private fun syncNetwork(selected: Boolean) = kkcIcon("SettingsSyncNetwork.${state(selected)}") {
    val cloud: PathBuilder.() -> Unit = {
        moveTo(7f, 19f)
        horizontalLineTo(17.5f)
        arcTo(4f, 4f, 0f, false, false, 18.1f, 11f)
        arcTo(6f, 6f, 0f, false, false, 6.4f, 10.2f)
        arcTo(4.5f, 4.5f, 0f, false, false, 7f, 19f)
        close()
    }
    if (selected) solid(DUOTONE, pathBuilder = cloud)
    line(pathBuilder = cloud)
    line(width = 1.5f) {
        moveTo(10f, 16.5f); verticalLineTo(12f)
        moveTo(8.5f, 13.5f); lineTo(10f, 12f); lineTo(11.5f, 13.5f)
        moveTo(14f, 12f); verticalLineTo(16.5f)
        moveTo(12.5f, 15f); lineTo(14f, 16.5f); lineTo(15.5f, 15f)
    }
}

// ── Performance & Power: gauge with a solid hub ─────────────────────────────────
private fun performance(selected: Boolean) = kkcIcon("SettingsPerformance.${state(selected)}") {
    if (selected) solid(DUOTONE) {
        moveTo(3.5f, 17f)
        arcTo(9f, 9f, 0f, true, true, 20.5f, 17f)
        close()
    }
    line {
        moveTo(3.5f, 17f)
        arcTo(9f, 9f, 0f, true, true, 20.5f, 17f)
        moveTo(12f, 15.5f); lineTo(16f, 10f)
    }
    line(width = 1.5f) {
        moveTo(6f, 12.5f); horizontalLineTo(7.2f)
        moveTo(12f, 6.5f); verticalLineTo(7.7f)
        moveTo(18f, 12.5f); horizontalLineTo(16.8f)
        moveTo(7.8f, 8.3f); lineTo(8.7f, 9.2f)
    }
    solid { circle(12f, 15.5f, 1.8f) }
}

// ── Admin: shield with a keyhole ────────────────────────────────────────────────
private fun admin(selected: Boolean) = kkcIcon("SettingsAdmin.${state(selected)}") {
    val shield: PathBuilder.() -> Unit = {
        moveTo(12f, 2.5f)
        lineTo(19.5f, 5.5f)
        verticalLineTo(11f)
        curveTo(19.5f, 16f, 16.3f, 19.6f, 12f, 21.5f)
        curveTo(7.7f, 19.6f, 4.5f, 16f, 4.5f, 11f)
        verticalLineTo(5.5f)
        close()
    }
    if (selected) solid(DUOTONE, pathBuilder = shield)
    line(pathBuilder = shield)
    solid { circle(12f, 10.5f, 2f) }
    line { moveTo(12f, 12f); verticalLineTo(15.5f) }
}

val SettingsLookFeelSelected: ImageVector by lazy { lookFeel(true) }
val SettingsLookFeelUnselected: ImageVector by lazy { lookFeel(false) }
val SettingsViewersSelected: ImageVector by lazy { viewers(true) }
val SettingsViewersUnselected: ImageVector by lazy { viewers(false) }
val SettingsMeSelected: ImageVector by lazy { me(true) }
val SettingsMeUnselected: ImageVector by lazy { me(false) }
val SettingsUpdatesSelected: ImageVector by lazy { updates(true) }
val SettingsUpdatesUnselected: ImageVector by lazy { updates(false) }
val SettingsTabletDataSelected: ImageVector by lazy { tabletData(true) }
val SettingsTabletDataUnselected: ImageVector by lazy { tabletData(false) }
val SettingsSyncNetworkSelected: ImageVector by lazy { syncNetwork(true) }
val SettingsSyncNetworkUnselected: ImageVector by lazy { syncNetwork(false) }
val SettingsPerformanceSelected: ImageVector by lazy { performance(true) }
val SettingsPerformanceUnselected: ImageVector by lazy { performance(false) }
val SettingsAdminSelected: ImageVector by lazy { admin(true) }
val SettingsAdminUnselected: ImageVector by lazy { admin(false) }
