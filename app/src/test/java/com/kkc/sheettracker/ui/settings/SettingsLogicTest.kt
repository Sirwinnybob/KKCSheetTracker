package com.kkc.sheettracker.ui.settings

import androidx.compose.ui.graphics.Color
import com.kkc.sheettracker.sync.SyncthingServiceStatus
import com.kkc.sheettracker.ui.theme.BuiltInKKCThemeTokens
import com.kkc.sheettracker.ui.theme.KKCThemeCatalog
import com.kkc.sheettracker.ui.theme.KKCThemeDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsLogicTest {

    private fun theme(id: String, name: String, category: String = "custom") =
        KKCThemeDefinition(id = id, name = name, version = 1, category = category, tokens = BuiltInKKCThemeTokens)

    private val default = theme("kkc-default", "KKC Default")
    private val forest = theme("kkc-forest", "KKC Forest")
    private val chiefs = theme("nfl-chiefs", "Kansas City Chiefs", "nfl")

    private fun catalog(active: KKCThemeDefinition = default, override: String? = null) = KKCThemeCatalog(
        themes = listOf(default, forest, chiefs),
        activeTheme = active,
        syncedDefaultThemeId = null,
        invalidThemes = emptyList(),
        loadMessages = emptyList(),
        followSyncedDefault = override == null,
        overrideThemeId = override
    )

    @Test
    fun syncChipMapsEveryStatus() {
        assertEquals(ChipTone.OK to "Sync running", syncChip(SyncthingServiceStatus.RUNNING))
        assertEquals(ChipTone.WARN to "Sync checking", syncChip(SyncthingServiceStatus.CHECKING))
        assertEquals(ChipTone.WARN to "Sync paused", syncChip(SyncthingServiceStatus.PAUSED))
        assertEquals(ChipTone.WARN to "Sync key needed", syncChip(SyncthingServiceStatus.API_KEY_REQUIRED))
        assertEquals(ChipTone.BAD to "Sync stopped", syncChip(SyncthingServiceStatus.NOT_RUNNING))
        assertEquals(ChipTone.BAD to "Sync failed", syncChip(SyncthingServiceStatus.START_FAILED))
    }

    @Test
    fun updateCountAndLabel() {
        assertEquals(0, pendingUpdateCount(false, 0))
        assertEquals(3, pendingUpdateCount(true, 2))
        assertEquals("1 update", updatesChipLabel(1))
        assertEquals("3 updates", updatesChipLabel(3))
    }

    @Test
    fun saveButtonHiddenWhenUnchangedIgnoringSurroundingSpaces() {
        assertEquals(SaveButtonState(visible = false, enabled = false), saveButtonState("CNC-2 ", "CNC-2", allowBlank = false))
        assertEquals(SaveButtonState(visible = true, enabled = true), saveButtonState("CNC-3", "CNC-2", allowBlank = false))
    }

    @Test
    fun whitespaceOnlyValueCannotBeSavedUnlessBlankAllowed() {
        assertEquals(SaveButtonState(visible = true, enabled = false), saveButtonState("   ", "CNC-2", allowBlank = false))
        assertEquals(SaveButtonState(visible = true, enabled = true), saveButtonState("   ", "10.0.0.5", allowBlank = true))
    }

    @Test
    fun blankIpIsStoredAsNull() {
        assertNull(storedIpOrNull(""))
        assertNull(storedIpOrNull("   "))
        assertEquals("10.0.0.5", storedIpOrNull(" 10.0.0.5 "))
    }

    @Test
    fun selectedThemeIsOverrideWhenPresentElseActive() {
        assertEquals("kkc-forest", selectedThemeId(catalog(override = "kkc-forest")))
        assertEquals("kkc-default", selectedThemeId(catalog(override = null)))
    }

    @Test
    fun overrideMissingFromCatalogFallsBackToActiveTheme() {
        assertEquals("kkc-default", selectedThemeId(catalog(override = "deleted-theme")))
    }

    @Test
    fun nflCardShowsActiveTeamName() {
        assertEquals("NFL team…", nflCardLabel(catalog(override = "kkc-forest")))
        assertEquals("Kansas City Chiefs", nflCardLabel(catalog(override = "nfl-chiefs")))
        assertEquals("Kansas City Chiefs", nflCardLabel(catalog(active = chiefs, override = null)))
    }

    @Test
    fun swatchUsesPaletteForMode() {
        assertEquals(BuiltInKKCThemeTokens.light.primary to BuiltInKKCThemeTokens.light.secondary, themeSwatch(default, dark = false))
        assertEquals(BuiltInKKCThemeTokens.dark.primary to BuiltInKKCThemeTokens.dark.secondary, themeSwatch(default, dark = true))
    }

    @Test
    fun swatchFallsBackToPrimaryWhenThemeHasNoSecondary() {
        val tokens = BuiltInKKCThemeTokens.copy(light = BuiltInKKCThemeTokens.light.copy(secondary = null))
        val flat = KKCThemeDefinition(id = "flat", name = "Flat", version = 1, tokens = tokens)
        assertEquals(tokens.light.primary to tokens.light.primary, themeSwatch(flat, dark = false))
    }

    @Test
    fun swatchThemesAreTheCustomThemes() {
        assertEquals(listOf("kkc-default", "kkc-forest"), customSwatchThemes(catalog()).map { it.id })
    }

    @Test
    fun swatchThemesFallBackToActiveWhenThereAreNoCustomThemes() {
        val onlyNfl = catalog().copy(themes = listOf(chiefs), activeTheme = default)
        assertEquals(listOf("kkc-default"), customSwatchThemes(onlyNfl).map { it.id })
    }

    @Test
    fun customThemesStayWhenAnNflThemeIsActive() {
        assertEquals(listOf("kkc-default", "kkc-forest"), customSwatchThemes(catalog(active = chiefs)).map { it.id })
    }

    @Test
    fun activeNflThemeIsNotDuplicatedAsAFallbackSwatch() {
        // The NFL card already shows the active team; a fallback swatch would show it twice.
        val onlyNfl = catalog().copy(themes = listOf(chiefs), activeTheme = chiefs)
        assertEquals(emptyList<String>(), customSwatchThemes(onlyNfl).map { it.id })
    }

    @Test
    fun badgeTextContrastsWithItsBackground() {
        assertEquals(Color.Black, badgeContentColor(Color(0xFFFFB74D)))
        assertEquals(Color.White, badgeContentColor(Color(0xFF1E3A5F)))
    }
}
