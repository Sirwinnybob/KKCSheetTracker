package com.kkc.sheettracker.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.kkc.sheettracker.sync.SyncthingServiceStatus
import com.kkc.sheettracker.ui.theme.KKCThemeCatalog
import com.kkc.sheettracker.ui.theme.KKCThemeDefinition

internal enum class ChipTone { OK, WARN, BAD }

internal fun syncChip(status: SyncthingServiceStatus): Pair<ChipTone, String> = when (status) {
    SyncthingServiceStatus.RUNNING -> ChipTone.OK to "Sync running"
    SyncthingServiceStatus.CHECKING -> ChipTone.WARN to "Sync checking"
    SyncthingServiceStatus.PAUSED -> ChipTone.WARN to "Sync paused"
    SyncthingServiceStatus.API_KEY_REQUIRED -> ChipTone.WARN to "Sync key needed"
    SyncthingServiceStatus.NOT_RUNNING -> ChipTone.BAD to "Sync stopped"
    SyncthingServiceStatus.START_FAILED -> ChipTone.BAD to "Sync failed"
}

internal fun pendingUpdateCount(hasSelfUpdate: Boolean, externalCount: Int): Int =
    (if (hasSelfUpdate) 1 else 0) + externalCount

internal fun updatesChipLabel(count: Int): String = if (count == 1) "1 update" else "$count updates"

internal data class SaveButtonState(val visible: Boolean, val enabled: Boolean)

/** Save shows once the trimmed edit differs from what's stored; blank saves only where allowed. */
internal fun saveButtonState(edit: String, saved: String, allowBlank: Boolean): SaveButtonState {
    val trimmed = edit.trim()
    val dirty = trimmed != saved.trim()
    return SaveButtonState(visible = dirty, enabled = dirty && (allowBlank || trimmed.isNotEmpty()))
}

/** Server IP fields: blank means "auto / not configured", which the configs store as null. */
internal fun storedIpOrNull(text: String): String? = text.trim().ifBlank { null }

internal fun selectedThemeId(catalog: KKCThemeCatalog): String =
    catalog.overrideThemeId?.takeIf { id -> catalog.themes.any { it.id == id } } ?: catalog.activeTheme.id

internal fun nflCardLabel(catalog: KKCThemeCatalog): String {
    val id = selectedThemeId(catalog)
    return footballTeamThemes(catalog.themes).firstOrNull { it.id == id }?.name ?: "NFL team…"
}

internal fun themeSwatch(theme: KKCThemeDefinition, dark: Boolean): Pair<Color, Color> {
    val palette = if (dark) theme.tokens.dark else theme.tokens.light
    // Single-color themes have no secondary; the swatch shows primary on both halves.
    return palette.primary to (palette.secondary ?: palette.primary)
}

/**
 * Themes shown as swatch cards. With no custom themes the active one stands in, unless it is an NFL
 * team — the NFL card already shows that, so a fallback card would show it twice.
 */
internal fun customSwatchThemes(catalog: KKCThemeCatalog): List<KKCThemeDefinition> {
    val custom = customThemes(catalog.themes)
    if (custom.isNotEmpty()) return custom
    val activeIsNfl = footballTeamThemes(catalog.themes).any { it.id == catalog.activeTheme.id }
    return if (activeIsNfl) emptyList() else listOf(catalog.activeTheme)
}

/** Black or white text, whichever reads on [background] (themes can recolor status fills). */
internal fun badgeContentColor(background: Color): Color =
    if (background.luminance() > 0.4f) Color.Black else Color.White
