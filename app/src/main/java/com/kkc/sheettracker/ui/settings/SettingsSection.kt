package com.kkc.sheettracker.ui.settings

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Settings rail sections, in rail order. Advanced ones are grouped under an "Advanced" caption. */
enum class SettingsSection(val title: String, val subtitle: String, val isAdvanced: Boolean) {
    LOOK_AND_FEEL("Look & Feel", "Theme and light/dark for this tablet", false),
    VIEWERS("Viewers", "How sheets and references display", false),
    ME("Me", "Who is using this tablet", false),
    UPDATES_ABOUT("Updates & About", "App version and pending installs", false),
    TABLET_DATA("Tablet & Data", "Identity and data folder", true),
    SYNC_NETWORK("Sync & Network", "Syncthing, timeclock hub, Hours Tracker", true),
    PERFORMANCE_POWER("Performance & Power", "Low-end mode and idle power saving", true),
    ADMIN("Admin", "Advanced controls for the office", true),
}

/** Landscape cap so the rail still fits above the floating navbar; portrait tiles stay square. */
internal val MODE_TILE_MAX_HEIGHT: Dp = 168.dp
internal const val WIDE_CHIPS_MIN_WIDTH_DP = 1000f

internal fun initialSection(hasPendingUpdates: Boolean): SettingsSection =
    if (hasPendingUpdates) SettingsSection.UPDATES_ABOUT else SettingsSection.LOOK_AND_FEEL

internal fun showWideChips(widthDp: Float): Boolean = widthDp >= WIDE_CHIPS_MIN_WIDTH_DP

internal fun modeTileHeight(tileWidth: Dp): Dp = minOf(tileWidth, MODE_TILE_MAX_HEIGHT)
