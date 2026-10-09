package com.kkc.sheettracker.ui.settings

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenPendingUpdatesWiringTest {

    @Test
    fun pendingUpdatesSectionIsWired() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/ui/settings/SettingsScreen.kt").readText()
        val pane = SourceFiles.mainSource("com/kkc/sheettracker/ui/settings/panes/UpdatesAboutPane.kt").readText()

        assertTrue("SettingsScreen must accept pendingSelfUpdate", source.contains("pendingSelfUpdate: File? = null"))
        assertTrue("SettingsScreen must accept pendingExternalUpdates", source.contains("pendingExternalUpdates: List<ExternalAppUpdate> = emptyList()"))
        assertTrue("SettingsScreen must accept onInstallSelfUpdate", source.contains("onInstallSelfUpdate: () -> Unit = {}"))
        assertTrue("SettingsScreen must accept onInstallExternalUpdate", source.contains("onInstallExternalUpdate: (ExternalAppUpdate) -> Unit = {}"))
        assertTrue("SettingsScreen must accept onInstallAll", source.contains("onInstallAll: () -> Unit = {}"))
        assertTrue("SettingsScreen must forward onInstallAll to the pane", source.contains("onInstallAll = onInstallAll"))
        assertTrue("Updates pane must render a Pending updates card", pane.contains("GroupCard(caption = \"Pending updates\")"))
        assertTrue("Updates pane must render an Update All button", pane.contains("Update All"))
    }
}
