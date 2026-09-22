package com.kkc.sheettracker.ui.settings

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenPendingUpdatesWiringTest {

    @Test
    fun pendingUpdatesSectionIsWired() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/ui/settings/SettingsScreen.kt").readText()

        assertTrue("SettingsScreen must accept pendingSelfUpdate", source.contains("pendingSelfUpdate: File? = null"))
        assertTrue("SettingsScreen must accept pendingExternalUpdates", source.contains("pendingExternalUpdates: List<ExternalAppUpdate> = emptyList()"))
        assertTrue("SettingsScreen must accept onInstallSelfUpdate", source.contains("onInstallSelfUpdate: () -> Unit = {}"))
        assertTrue("SettingsScreen must accept onInstallExternalUpdate", source.contains("onInstallExternalUpdate: (ExternalAppUpdate) -> Unit = {}"))
        assertTrue("SettingsScreen must accept onInstallAll", source.contains("onInstallAll: () -> Unit = {}"))
        assertTrue("SettingsScreen must render a Pending Updates card", source.contains("SettingsCard(title = \"Pending Updates\")"))
        assertTrue("SettingsScreen must render an Update All button", source.contains("Update All"))
    }
}
