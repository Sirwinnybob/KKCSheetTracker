package com.kkc.sheettracker.ui.settings

/** Settings rail sections, in rail order. Advanced ones are grouped under an "Advanced" caption. */
internal enum class SettingsSection(val title: String, val subtitle: String, val isAdvanced: Boolean) {
    LOOK_AND_FEEL("Look & Feel", "Theme and light/dark for this tablet", false),
    VIEWERS("Viewers", "How sheets and references display", false),
    ME("Me", "Who is using this tablet", false),
    UPDATES_ABOUT("Updates & About", "App version and pending installs", false),
    TABLET_DATA("Tablet & Data", "Identity and data folder", true),
    SYNC_NETWORK("Sync & Network", "Syncthing, timeclock hub, Hours Tracker", true),
    PERFORMANCE_POWER("Performance & Power", "Low-end mode and idle power saving", true),
    ADMIN("Admin", "Advanced controls for the office", true),
}

internal fun initialSection(hasPendingUpdates: Boolean): SettingsSection =
    if (hasPendingUpdates) SettingsSection.UPDATES_ABOUT else SettingsSection.LOOK_AND_FEEL

/**
 * Section to show when Settings is (re)entered. Jumps to Updates once per distinct set of pending
 * updates ([updatesSignature] vs the set it last jumped for), so a new update is surfaced on the next
 * open, but coming back from a child screen (viewer defaults) doesn't keep yanking the user there.
 */
internal fun sectionOnOpen(
    current: SettingsSection,
    updatesSignature: String?,
    lastJumpedSignature: String?,
): SettingsSection =
    if (updatesSignature != null && updatesSignature != lastJumpedSignature) SettingsSection.UPDATES_ABOUT
    else current

/** Identifies the pending-update set; null when nothing is pending. */
internal fun updatesSignature(selfUpdateName: String?, externalUpdates: List<Pair<String, String>>): String? {
    if (selfUpdateName == null && externalUpdates.isEmpty()) return null
    return (listOfNotNull(selfUpdateName) + externalUpdates.map { (app, version) -> "$app@$version" })
        .sorted()
        .joinToString("|")
}

/** Process-level: which update set Settings already jumped to, surviving Settings leaving composition. */
internal object SettingsUpdatesJump {
    var lastJumpedSignature: String? = null
}
