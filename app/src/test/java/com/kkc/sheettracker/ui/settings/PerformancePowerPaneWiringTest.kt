package com.kkc.sheettracker.ui.settings

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformancePowerPaneWiringTest {

    private val pane =
        SourceFiles.mainSource("com/kkc/sheettracker/ui/settings/panes/PerformancePowerPane.kt").readText()

    @Test
    fun lowEndModeForcesTheDocumentedDefaults() {
        assertTrue(pane.contains("uiPreferencesStore.setAnimationsEnabled(false)"))
        assertTrue(pane.contains("uiPreferencesStore.setShadowsEnabled(false)"))
        assertTrue(pane.contains("uiPreferencesStore.setBlurEnabled(false)"))
        assertTrue(pane.contains("uiPreferencesStore.setLazyLoadingEnabled(true)"))
    }

    @Test
    fun idleTimeoutDebounceNeverWritesAnUnchangedValue() {
        assertTrue(pane.contains("if (seconds == idleConfig.idleTimeoutSeconds) return@LaunchedEffect"))
    }

    @Test
    fun syncthingPauseSettingStaysRemoved() {
        assertFalse(pane.contains("setSyncthingPauseTimeoutSeconds"))
    }
}
