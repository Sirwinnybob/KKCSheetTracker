package com.kkc.sheettracker.ui.settings

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsChromeWiringTest {

    private val chrome = SourceFiles.mainSource("com/kkc/sheettracker/ui/settings/SettingsChrome.kt").readText()

    @Test
    fun saveableFieldReseedsFromSavedValueAndSurvivesRotation() {
        // Keyed on savedValue: an IP flow emitting after first frame replaces the text.
        // rememberSaveable: unsaved edits survive rotation.
        assertTrue(chrome.contains("rememberSaveable(savedValue)"))
    }

    @Test
    fun modeTilesUseTheSharedHeightRule() {
        assertTrue(chrome.contains("modeTileHeight("))
    }

    @Test
    fun noShadowBleedPatterns() {
        assertFalse("CLAUDE.md: never Surface(shadowElevation)", chrome.contains("shadowElevation"))
        assertTrue("cards use kkcCardDepth", chrome.contains("kkcCardDepth("))
    }

    @Test
    fun workModeArtIsTheSingleLogoSwapPoint() {
        val art = SourceFiles.mainSource("com/kkc/sheettracker/ui/settings/WorkModeArt.kt").readText()
        assertTrue(art.contains("fun workModeLogo(mode: WorkMode): ModeLogo"))
        assertTrue(chrome.contains("workModeLogo("))
    }
}
