package com.kkc.sheettracker.ui.settings

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPanesWiringTest {

    private val screen = SourceFiles.mainSource("com/kkc/sheettracker/ui/settings/SettingsScreen.kt").readText()

    @Test
    fun everySectionDispatchesToItsPane() {
        val panes = mapOf(
            SettingsSection.LOOK_AND_FEEL to "LookAndFeelPane(",
            SettingsSection.VIEWERS to "ViewersPane(",
            SettingsSection.ME to "MePane(",
            SettingsSection.UPDATES_ABOUT to "UpdatesAboutPane(",
            SettingsSection.TABLET_DATA to "TabletDataPane(",
            SettingsSection.SYNC_NETWORK to "SyncNetworkPane(",
            SettingsSection.PERFORMANCE_POWER to "PerformancePowerPane(",
            SettingsSection.ADMIN to "AdminPane(",
        )
        assertTrue("every section mapped", panes.keys == SettingsSection.entries.toSet())
        panes.forEach { (section, call) ->
            assertTrue("missing branch for $section", screen.contains("SettingsSection.${section.name} -> $call"))
        }
    }

    @Test
    fun publicSignatureIsUnchanged() {
        val params = listOf(
            "tabletId: String,",
            "basePath: String,",
            "isDebugBuild: Boolean,",
            "isDarkTheme: Boolean,",
            "followSystemTheme: Boolean = true,",
            "darkThemeOverride: Boolean = false,",
            "workMode: WorkMode,",
            "flexibleModeEnabled: Boolean,",
            "onThemeChanged: (Boolean) -> Unit,",
            "onFollowSystemThemeChanged: (Boolean) -> Unit = {},",
            "onWorkModeChanged: (WorkMode) -> Unit,",
            "onFlexibleModeChanged: (Boolean) -> Unit,",
            "onReinstallLatest: () -> Unit,",
            "onCheckForUpdates: () -> Unit = {},",
            "onTabletIdChanged: (String) -> Unit,",
            "onBasePathChanged: (String) -> Unit,",
            "syncthingApiKey: String,",
            "syncthingStatus: SyncthingStatusUiState,",
            "onSyncthingApiKeySave: (String) -> Unit,",
            "onSyncthingCheckNow: () -> Unit,",
            "onSyncthingStartNow: () -> Unit,",
            "onBack: () -> Unit,",
            "employeeName: String,",
            "onEmployeeNameChanged: (String) -> Unit,",
            "useStandardSheets: Boolean = false,",
            "onUseStandardSheetsChanged: (Boolean) -> Unit = {},",
            "continuousScrollDefault: Boolean = false,",
            "onContinuousScrollDefaultChanged: (Boolean) -> Unit = {},",
            "timecardConfig: TimecardServerConfig,",
            "adminSyncConfig: AdminSyncConfig,",
            "themeCatalog: KKCThemeCatalog = KKCThemeRepository.builtInCatalog(),",
            "onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit = {},",
            "onThemeOverrideChanged: (String?) -> Unit = {},",
            "onThemeCatalogReload: () -> Unit = {},",
            "onOpenAssemblyViewerDefaults: () -> Unit = {},",
            "onOpenSpecialtyViewerDefaults: () -> Unit = {},",
            "uiPreferencesStore: UiPreferencesStore,",
            "idlePowerSaveStore: IdlePowerSaveStore,",
            "pendingSelfUpdate: File? = null,",
            "pendingExternalUpdates: List<ExternalAppUpdate> = emptyList(),",
            "onInstallSelfUpdate: () -> Unit = {},",
            "onInstallExternalUpdate: (ExternalAppUpdate) -> Unit = {},",
            "onInstallAll: () -> Unit = {},",
        )
        params.forEach { assertTrue("SettingsScreen lost parameter `$it`", screen.contains(it)) }
    }

    @Test
    fun openEffectsAndSectionStateArePreserved() {
        assertTrue(screen.contains("EmployeeDirectory.refresh(File(basePath))"))
        assertTrue(screen.contains("onCheckForUpdates()"))
        assertTrue("section survives rotation", screen.contains("rememberSaveable"))
        assertTrue(screen.contains("initialSection("))
        assertTrue(screen.contains("AdminPasswordDialog("))
    }

    @Test
    fun oldSingleScrollHelpersAreGone() {
        listOf("SettingsCard(", "WorkModeIconTile(", "filledFieldColors(").forEach {
            assertFalse("$it should be removed", screen.contains(it))
        }
    }
}
