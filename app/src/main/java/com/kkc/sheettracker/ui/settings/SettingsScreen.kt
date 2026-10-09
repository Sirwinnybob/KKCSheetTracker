package com.kkc.sheettracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.EmployeeDirectory
import com.kkc.sheettracker.data.IdlePowerSaveStore
import com.kkc.sheettracker.data.TimecardServerConfig
import com.kkc.sheettracker.data.UiPreferencesStore
import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.sync.SyncthingStatusUiState
import com.kkc.sheettracker.ui.components.AdminPasswordDialog
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import com.kkc.sheettracker.ui.components.icons.SettingsUpdatesSelected
import com.kkc.sheettracker.ui.settings.panes.AdminPane
import com.kkc.sheettracker.ui.settings.panes.LookAndFeelPane
import com.kkc.sheettracker.ui.settings.panes.MePane
import com.kkc.sheettracker.ui.settings.panes.PerformancePowerPane
import com.kkc.sheettracker.ui.settings.panes.SyncNetworkPane
import com.kkc.sheettracker.ui.settings.panes.TabletDataPane
import com.kkc.sheettracker.ui.settings.panes.UpdatesAboutPane
import com.kkc.sheettracker.ui.settings.panes.ViewersPane
import com.kkc.sheettracker.ui.theme.KKCThemeCatalog
import com.kkc.sheettracker.ui.theme.KKCThemeRepository
import com.kkc.sheettracker.ui.theme.LocalKKCStatusColors
import com.kkc.sheettracker.update.ExternalAppUpdate
import java.io.File

/** Clearance below the rail and pane so their last rows scroll above the floating navbar. */
private val NavbarClearance = 160.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    followSystemTheme: Boolean = true,
    darkThemeOverride: Boolean = false,
    workMode: WorkMode,
    flexibleModeEnabled: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    onFollowSystemThemeChanged: (Boolean) -> Unit = {},
    onWorkModeChanged: (WorkMode) -> Unit,
    onFlexibleModeChanged: (Boolean) -> Unit,
    onReinstallLatest: () -> Unit,
    /** Re-scans for app updates; run each time Settings opens. */
    onCheckForUpdates: () -> Unit = {},
    onTabletIdChanged: (String) -> Unit,
    onBasePathChanged: (String) -> Unit,
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit,
    onBack: () -> Unit,
    employeeName: String,
    onEmployeeNameChanged: (String) -> Unit,
    useStandardSheets: Boolean = false,
    onUseStandardSheetsChanged: (Boolean) -> Unit = {},
    continuousScrollDefault: Boolean = false,
    onContinuousScrollDefaultChanged: (Boolean) -> Unit = {},
    timecardConfig: TimecardServerConfig,
    adminSyncConfig: AdminSyncConfig,
    themeCatalog: KKCThemeCatalog = KKCThemeRepository.builtInCatalog(),
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit = {},
    onThemeOverrideChanged: (String?) -> Unit = {},
    onThemeCatalogReload: () -> Unit = {},
    onOpenAssemblyViewerDefaults: () -> Unit = {},
    onOpenSpecialtyViewerDefaults: () -> Unit = {},
    uiPreferencesStore: UiPreferencesStore,
    idlePowerSaveStore: IdlePowerSaveStore,
    pendingSelfUpdate: File? = null,
    pendingExternalUpdates: List<ExternalAppUpdate> = emptyList(),
    onInstallSelfUpdate: () -> Unit = {},
    onInstallExternalUpdate: (ExternalAppUpdate) -> Unit = {},
    onInstallAll: () -> Unit = {},
) {
    val adminMode by AdminModeController.enabled.collectAsState()
    var showAdminDialog by remember { mutableStateOf(false) }

    val updateCount = pendingUpdateCount(pendingSelfUpdate != null, pendingExternalUpdates.size)
    var section by rememberSaveable { mutableStateOf(initialSection(updateCount > 0)) }

    // Checks that run each time Settings opens.
    LaunchedEffect(Unit) {
        val signature = updatesSignature(
            pendingSelfUpdate?.name,
            pendingExternalUpdates.map { it.appName to it.versionName }
        )
        section = sectionOnOpen(section, signature, SettingsUpdatesJump.lastJumpedSignature)
        if (signature != null) SettingsUpdatesJump.lastJumpedSignature = signature
        EmployeeDirectory.refresh(File(basePath))
        onCheckForUpdates()
    }
    val wideChips = showWideChips(LocalConfiguration.current.screenWidthDp.toFloat())

    Scaffold(
        topBar = {
            KKCTopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val (tone, syncLabel) = syncChip(syncthingStatus.status)
                        StatusChip(syncLabel, onClick = { section = SettingsSection.SYNC_NETWORK }, dot = syncDotColor(tone))
                        if (updateCount > 0) {
                            val updateColor = LocalKKCStatusColors.current.skipBg
                            StatusChip(
                                updatesChipLabel(updateCount),
                                onClick = { section = SettingsSection.UPDATES_ABOUT },
                                container = updateColor,
                                content = badgeContentColor(updateColor),
                                icon = SettingsUpdatesSelected
                            )
                        }
                        if (adminMode) {
                            StatusChip(
                                "Admin ON",
                                onClick = { section = SettingsSection.ADMIN },
                                container = MaterialTheme.colorScheme.tertiaryContainer,
                                content = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        if (wideChips) {
                            StatusChip("Tablet $tabletId", onClick = { section = SettingsSection.TABLET_DATA })
                            StatusChip("v${BuildConfig.VERSION_NAME}", onClick = { section = SettingsSection.UPDATES_ABOUT })
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(top = 4.dp)
        ) {
            WorkModeRow(
                workMode = workMode,
                onWorkModeChanged = onWorkModeChanged,
                flexibleModeEnabled = flexibleModeEnabled,
                onFlexibleModeChanged = onFlexibleModeChanged
            )
            Row(
                // Takes whatever height the pinned mode row leaves; rail and pane scroll inside it.
                modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SettingsRail(
                    selected = section,
                    onSelect = { section = it },
                    updatesBadge = updateCount,
                    bottomClearance = NavbarClearance,
                    modifier = Modifier.fillMaxHeight()
                )
                // New scroll state per section so switching always starts at the top.
                val paneScroll = key(section) { rememberScrollState() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(paneScroll)
                        // Inset inside the scroll viewport so card shadows are not clipped at its edges.
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = NavbarClearance),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SectionHeader(section)
                    when (section) {
                        SettingsSection.LOOK_AND_FEEL -> LookAndFeelPane(
                            isDarkTheme = isDarkTheme,
                            followSystemTheme = followSystemTheme,
                            darkThemeOverride = darkThemeOverride,
                            onFollowSystemThemeChanged = onFollowSystemThemeChanged,
                            onThemeChanged = onThemeChanged,
                            themeCatalog = themeCatalog,
                            onThemeFollowSyncedDefaultChanged = onThemeFollowSyncedDefaultChanged,
                            onThemeOverrideChanged = onThemeOverrideChanged,
                            onThemeCatalogReload = onThemeCatalogReload
                        )
                        SettingsSection.VIEWERS -> ViewersPane(
                            isDarkTheme = isDarkTheme,
                            useStandardSheets = useStandardSheets,
                            onUseStandardSheetsChanged = onUseStandardSheetsChanged,
                            continuousScrollDefault = continuousScrollDefault,
                            onContinuousScrollDefaultChanged = onContinuousScrollDefaultChanged,
                            uiPreferencesStore = uiPreferencesStore,
                            onOpenAssemblyViewerDefaults = onOpenAssemblyViewerDefaults,
                            onOpenSpecialtyViewerDefaults = onOpenSpecialtyViewerDefaults
                        )
                        SettingsSection.ME -> MePane(
                            employeeName = employeeName,
                            onEmployeeNameChanged = onEmployeeNameChanged
                        )
                        SettingsSection.UPDATES_ABOUT -> UpdatesAboutPane(
                            pendingSelfUpdate = pendingSelfUpdate,
                            pendingExternalUpdates = pendingExternalUpdates,
                            onInstallSelfUpdate = onInstallSelfUpdate,
                            onInstallExternalUpdate = onInstallExternalUpdate,
                            onInstallAll = onInstallAll,
                            onCheckForUpdates = onCheckForUpdates,
                            isDebugBuild = isDebugBuild,
                            onReinstallLatest = onReinstallLatest
                        )
                        SettingsSection.TABLET_DATA -> TabletDataPane(
                            tabletId = tabletId,
                            onTabletIdChanged = onTabletIdChanged,
                            basePath = basePath,
                            onBasePathChanged = onBasePathChanged
                        )
                        SettingsSection.SYNC_NETWORK -> SyncNetworkPane(
                            syncthingApiKey = syncthingApiKey,
                            syncthingStatus = syncthingStatus,
                            onSyncthingApiKeySave = onSyncthingApiKeySave,
                            onSyncthingCheckNow = onSyncthingCheckNow,
                            onSyncthingStartNow = onSyncthingStartNow,
                            timecardConfig = timecardConfig,
                            adminSyncConfig = adminSyncConfig
                        )
                        SettingsSection.PERFORMANCE_POWER -> PerformancePowerPane(
                            uiPreferencesStore = uiPreferencesStore,
                            idlePowerSaveStore = idlePowerSaveStore
                        )
                        SettingsSection.ADMIN -> AdminPane(
                            adminMode = adminMode,
                            onUnlockRequested = { showAdminDialog = true }
                        )
                    }
                }
            }
        }
    }

    if (showAdminDialog) {
        AdminPasswordDialog(
            onUnlocked = {
                AdminModeController.setEnabled(true)
                showAdminDialog = false
            },
            onDismiss = { showAdminDialog = false }
        )
    }
}
