package com.kkc.sheettracker.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.EmployeeDirectory
import com.kkc.sheettracker.data.TimecardServerConfig
import com.kkc.sheettracker.data.UiPreferencesStore
import com.kkc.sheettracker.navigation.WorkMode
import com.kkc.sheettracker.sync.SyncthingServiceStatus
import com.kkc.sheettracker.sync.SyncthingStatusUiState
import com.kkc.sheettracker.ui.components.AdminPasswordDialog
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.theme.KKCThemeCatalog
import com.kkc.sheettracker.ui.theme.KKCThemeRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

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
    onThemeChanged: (Boolean) -> Unit,
    onFollowSystemThemeChanged: (Boolean) -> Unit = {},
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
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
) {
    val adminMode by AdminModeController.enabled.collectAsState()
    var showAdminDialog by remember { mutableStateOf(false) }
    var editTabletId by remember { mutableStateOf(tabletId) }
    var editBasePath by remember { mutableStateOf(basePath) }
    var editSyncthingApiKey by remember(syncthingApiKey) { mutableStateOf(syncthingApiKey) }
    var editEmployeeName by remember(employeeName) { mutableStateOf(employeeName) }
    var employeeNameDirty by remember { mutableStateOf(false) }
    var employeeNameSaved by remember { mutableStateOf(false) }
    var employeeDropdownExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { EmployeeDirectory.refresh(File(basePath)) }
    val employeeRecords by EmployeeDirectory.recordsFlow.collectAsState()
    val allEmployees = remember(employeeRecords) { employeeRecords.map { it.pin to it.name } }
    val filteredEmployees = remember(editEmployeeName) {
        if (editEmployeeName.isBlank()) emptyList()
        else allEmployees.filter { (id, name) ->
            name.contains(editEmployeeName, ignoreCase = true) ||
            id.contains(editEmployeeName, ignoreCase = true)
        }
    }
    var tabletIdDirty by remember { mutableStateOf(false) }
    var basePathDirty by remember { mutableStateOf(false) }
    var syncthingApiKeyDirty by remember { mutableStateOf(false) }
    var tabletSaved by remember { mutableStateOf(false) }
    var basePathSaved by remember { mutableStateOf(false) }
    var syncthingApiKeySaved by remember { mutableStateOf(false) }
    val currentServerIp by timecardConfig.serverIpFlow.collectAsState(initial = null)
    var editServerIp by remember(currentServerIp) { mutableStateOf(currentServerIp ?: "") }
    var serverIpDirty by remember(currentServerIp) { mutableStateOf(false) }
    var serverIpSaved by remember { mutableStateOf(false) }

    val currentAdminSyncIp by adminSyncConfig.serverIpFlow.collectAsState(initial = null)
    var editAdminSyncIp by remember(currentAdminSyncIp) { mutableStateOf(currentAdminSyncIp ?: "") }
    var adminSyncIpDirty by remember(currentAdminSyncIp) { mutableStateOf(false) }
    var adminSyncIpSaved by remember { mutableStateOf(false) }

    var themeDropdownExpanded by remember { mutableStateOf(false) }
    val timecardScope = rememberCoroutineScope()

    LaunchedEffect(tabletSaved) {
        if (tabletSaved) {
            delay(1600)
            tabletSaved = false
        }
    }
    LaunchedEffect(basePathSaved) {
        if (basePathSaved) {
            delay(1600)
            basePathSaved = false
        }
    }
    LaunchedEffect(syncthingApiKeySaved) {
        if (syncthingApiKeySaved) {
            delay(1600)
            syncthingApiKeySaved = false
        }
    }
    LaunchedEffect(employeeNameSaved) {
        if (employeeNameSaved) {
            delay(1600)
            employeeNameSaved = false
        }
    }
    LaunchedEffect(serverIpSaved) {
        if (serverIpSaved) {
            delay(1600)
            serverIpSaved = false
        }
    }
    LaunchedEffect(adminSyncIpSaved) {
        if (adminSyncIpSaved) {
            delay(1600)
            adminSyncIpSaved = false
        }
    }

    Scaffold(
        topBar = {
            KKCTopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleMedium
                    )
                },


                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Work Mode ────────────────────────────────────────────────
            Text("Work Mode", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WorkModeIconTile(
                    label = "CNC",
                    isSelected = workMode == WorkMode.CNC,
                    onClick = { onWorkModeChanged(WorkMode.CNC) },
                    modifier = Modifier.weight(1f)
                )
                WorkModeIconTile(
                    label = "Hardwoods",
                    isSelected = workMode == WorkMode.HARDWOODS,
                    onClick = { onWorkModeChanged(WorkMode.HARDWOODS) },
                    modifier = Modifier.weight(1f)
                )
                WorkModeIconTile(
                    label = "Assembly",
                    isSelected = workMode == WorkMode.ASSEMBLY,
                    onClick = { onWorkModeChanged(WorkMode.ASSEMBLY) },
                    modifier = Modifier.weight(1f)
                )
                WorkModeIconTile(
                    label = "Specialty",
                    isSelected = workMode == WorkMode.SPECIALTY,
                    onClick = { onWorkModeChanged(WorkMode.SPECIALTY) },
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Appearance ───────────────────────────────────────────────
            SettingsCard(title = "Appearance") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Follow System Theme", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = followSystemTheme,
                        onCheckedChange = onFollowSystemThemeChanged
                    )
                }

                if (!followSystemTheme) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Dark Mode", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = darkThemeOverride,
                            onCheckedChange = onThemeChanged
                        )
                    }
                }

                if (isDarkTheme) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Use Standard Sheets", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Load light mode PDFs instead of dark mode in viewer pages.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = useStandardSheets,
                            onCheckedChange = onUseStandardSheetsChanged
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Continuous Scroll", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Scroll reference PDFs page-to-page instead of tapping through them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = continuousScrollDefault,
                        onCheckedChange = onContinuousScrollDefaultChanged
                    )
                }

                var scrollPreviewLabelOnly by remember { mutableStateOf(uiPreferencesStore.getScrollPreviewLabelOnly()) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Label-only scroll preview", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Show just the sheet label while dragging the scrollbar, instead of page thumbnails.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = scrollPreviewLabelOnly,
                        onCheckedChange = {
                            scrollPreviewLabelOnly = it
                            uiPreferencesStore.setScrollPreviewLabelOnly(it)
                        }
                    )
                }

                HorizontalDivider()

                ExposedDropdownMenuBox(
                    expanded = themeDropdownExpanded,
                    onExpandedChange = { themeDropdownExpanded = it }
                ) {
                    val selectedId = themeCatalog.overrideThemeId
                    val selectedThemeName = selectedId
                        ?.let { id -> themeCatalog.themes.firstOrNull { it.id == id }?.name }
                        ?: themeCatalog.activeTheme.name
                    OutlinedTextField(
                        value = selectedThemeName,
                        onValueChange = {},
                        label = { Text("This tablet") },
                        supportingText = {
                            Text(if (selectedId == null) "Using fleet default" else "Applies only to this tablet")
                        },
                        colors = filledFieldColors(),
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        readOnly = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = themeDropdownExpanded,
                        onDismissRequest = { themeDropdownExpanded = false }
                    ) {
                        themeCatalog.themes.forEach { theme ->
                            DropdownMenuItem(
                                text = { Text(theme.name) },
                                onClick = {
                                    onThemeFollowSyncedDefaultChanged(false)
                                    onThemeOverrideChanged(theme.id)
                                    themeDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    Button(
                        onClick = {
                            onThemeOverrideChanged(null)
                            onThemeFollowSyncedDefaultChanged(true)
                        },
                        enabled = themeCatalog.overrideThemeId != null,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Use Fleet Default")
                    }
                    OutlinedButton(
                        onClick = onThemeCatalogReload,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Reload Themes")
                    }
                }

                themeCatalog.loadMessages.forEach { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                themeCatalog.invalidThemes.forEach { invalid ->
                    Text(
                        "${invalid.filename}: ${invalid.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAssemblyViewerDefaults() }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Assembly viewer defaults", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Layout, panes, fullscreen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenAssemblyViewerDefaults,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Open", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSpecialtyViewerDefaults() }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Specialty viewer defaults", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Station order, expanded sections",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenSpecialtyViewerDefaults,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Open", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // ── Performance ────────────────────────────────────────────────
            SettingsCard(title = "Performance") {
                var lowEndMode by remember { mutableStateOf(uiPreferencesStore.getLowEndMode()) }
                var animationsEnabled by remember { mutableStateOf(uiPreferencesStore.getAnimationsEnabled()) }
                var shadowsEnabled by remember { mutableStateOf(uiPreferencesStore.getShadowsEnabled()) }
                var blurEnabled by remember { mutableStateOf(uiPreferencesStore.getBlurEnabled()) }
                var lazyLoadingEnabled by remember { mutableStateOf(uiPreferencesStore.getLazyLoadingEnabled()) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Low-end device mode", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = lowEndMode,
                        onCheckedChange = { enabled ->
                            lowEndMode = enabled
                            uiPreferencesStore.setLowEndMode(enabled)
                            if (enabled) {
                                animationsEnabled = false
                                shadowsEnabled = false
                                blurEnabled = false
                                lazyLoadingEnabled = true
                                uiPreferencesStore.setAnimationsEnabled(false)
                                uiPreferencesStore.setShadowsEnabled(false)
                                uiPreferencesStore.setBlurEnabled(false)
                                uiPreferencesStore.setLazyLoadingEnabled(true)
                            }
                        }
                    )
                }

                if (lowEndMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ToggleRow(
                            label = "Animations",
                            checked = animationsEnabled,
                            onCheckedChange = {
                                animationsEnabled = it
                                uiPreferencesStore.setAnimationsEnabled(it)
                            },
                            subtitle = "Spring/tween transitions, animated content size"
                        )
                        ToggleRow(
                            label = "Shadows",
                            checked = shadowsEnabled,
                            onCheckedChange = {
                                shadowsEnabled = it
                                uiPreferencesStore.setShadowsEnabled(it)
                            },
                            subtitle = "Card/button elevation shadows"
                        )
                        ToggleRow(
                            label = "Frosted glass / blur",
                            checked = blurEnabled,
                            onCheckedChange = {
                                blurEnabled = it
                                uiPreferencesStore.setBlurEnabled(it)
                            },
                            subtitle = "hazeEffect() backgrounds, blur modifiers"
                        )
                        ToggleRow(
                            label = "Lazy data loading",
                            checked = lazyLoadingEnabled,
                            onCheckedChange = {
                                lazyLoadingEnabled = it
                                uiPreferencesStore.setLazyLoadingEnabled(it)
                            },
                            subtitle = "Paginate job/supply lists, defer heavy loads"
                        )
                    }
                }
            }

            // ── Tablet ───────────────────────────────────────────────────
            SettingsCard(title = "Tablet") {
                ExposedDropdownMenuBox(
                    expanded = employeeDropdownExpanded && filteredEmployees.isNotEmpty(),
                    onExpandedChange = { employeeDropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = editEmployeeName,
                        onValueChange = {
                            editEmployeeName = it
                            employeeNameDirty = it.trim() != employeeName.trim()
                            employeeDropdownExpanded = it.isNotBlank()
                        },
                        label = { Text("Your Name / PIN") },
                        supportingText = { Text("Used for auto-login to the Hours Tracker. Leave blank to be prompted each time.") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    if (filteredEmployees.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = employeeDropdownExpanded,
                            onDismissRequest = { employeeDropdownExpanded = false }
                        ) {
                            filteredEmployees.forEach { (_, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        editEmployeeName = name
                                        employeeNameDirty = name.trim() != employeeName.trim()
                                        employeeDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (employeeNameDirty || employeeNameSaved) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (employeeNameDirty) {
                            Button(
                                onClick = {
                                    onEmployeeNameChanged(editEmployeeName.trim())
                                    employeeNameDirty = false
                                    employeeNameSaved = true
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Save Name")
                            }
                        }
                        if (employeeNameSaved) {
                            Text(
                                "Saved",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = editTabletId,
                    onValueChange = {
                        editTabletId = it
                        tabletIdDirty = it.trim() != tabletId.trim()
                    },
                    label = { Text("Tablet ID") },
                    supportingText = { Text("Used for progress file naming. Must be unique per tablet.") },
                    colors = filledFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                if (tabletIdDirty || tabletSaved) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (tabletIdDirty) {
                            Button(
                                onClick = {
                                    onTabletIdChanged(editTabletId.trim())
                                    tabletIdDirty = false
                                    tabletSaved = true
                                },
                                enabled = editTabletId.trim().isNotBlank(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Save Tablet ID")
                            }
                        }
                        if (tabletSaved) {
                            Text(
                                "Saved",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // ── Data Source ──────────────────────────────────────────────
            SettingsCard(title = "Data Source") {
                OutlinedTextField(
                    value = editBasePath,
                    onValueChange = {
                        editBasePath = it
                        basePathDirty = it.trim() != basePath.trim()
                    },
                    label = { Text("Ready Jobs Folder Path") },
                    supportingText = { Text("Path to the synced Ready Jobs folder on this tablet.") },
                    colors = filledFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                if (basePathDirty || basePathSaved) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (basePathDirty) {
                            Button(
                                onClick = {
                                    onBasePathChanged(editBasePath.trim())
                                    basePathDirty = false
                                    basePathSaved = true
                                },
                                enabled = editBasePath.trim().isNotBlank(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Save Path (app will restart)")
                            }
                        }
                        if (basePathSaved) {
                            Text(
                                "Saved",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // ── Syncthing ────────────────────────────────────────────────
            SettingsCard(title = "Syncthing") {
                OutlinedTextField(
                    value = editSyncthingApiKey,
                    onValueChange = {
                        editSyncthingApiKey = it
                        syncthingApiKeyDirty = it.trim() != syncthingApiKey.trim()
                    },
                    label = { Text("Syncthing API Key") },
                    supportingText = { Text("Used for localhost API checks at 127.0.0.1:8384.") },
                    colors = filledFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password
                    )
                )

                if (syncthingApiKeyDirty || syncthingApiKeySaved) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (syncthingApiKeyDirty) {
                            Button(
                                onClick = {
                                    onSyncthingApiKeySave(editSyncthingApiKey.trim())
                                    syncthingApiKeyDirty = false
                                    syncthingApiKeySaved = true
                                },
                                enabled = editSyncthingApiKey.trim().isNotBlank(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Save API Key")
                            }
                        }
                        if (syncthingApiKeySaved) {
                            Text(
                                "Saved",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                SettingsStatusBadge(
                    status = syncthingStatus.status
                )

                syncthingStatus.lastCheckedAtMs?.let { checkedAt ->
                    Text(
                        text = "Last check: ${formatStatusTime(checkedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                syncthingStatus.lastStartAttemptAtMs?.let { startedAt ->
                    Text(
                        text = "Last restart attempt: ${formatStatusTime(startedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onSyncthingCheckNow,
                        shape = RoundedCornerShape(8.dp),
                        enabled = syncthingApiKey.isNotBlank()
                    ) {
                        Text("Check Now")
                    }
                    Button(
                        onClick = onSyncthingStartNow,
                        shape = RoundedCornerShape(8.dp),
                        enabled = syncthingApiKey.isNotBlank()
                    ) {
                        Text("Start Now")
                    }
                }
            }

            // ── Timeclock ────────────────────────────────────────────────
            SettingsCard(title = "Timeclock") {
                OutlinedTextField(
                    value = editServerIp,
                    onValueChange = {
                        editServerIp = it
                        serverIpDirty = (it.trim() != (currentServerIp ?: ""))
                    },
                    label = { Text("Server IP address") },
                    placeholder = { Text("Auto (mDNS discovery)") },
                    supportingText = { Text("Leave blank to use automatic discovery. Enter an IP to skip mDNS.") },
                    colors = filledFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (serverIpSaved) {
                        Text(
                            "Saved",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            timecardScope.launch {
                                timecardConfig.setManualIp(editServerIp.ifBlank { null })
                            }
                            serverIpDirty = false
                            serverIpSaved = true
                        },
                        enabled = serverIpDirty
                    ) {
                        Text("Save")
                    }
                }
            }

            // ── Admin Sync ───────────────────────────────────────────────
            SettingsCard(title = "Hours Tracker Admin Sync") {
                OutlinedTextField(
                    value = editAdminSyncIp,
                    onValueChange = {
                        editAdminSyncIp = it
                        adminSyncIpDirty = (it.trim() != (currentAdminSyncIp ?: ""))
                    },
                    label = { Text("Hours Tracker server IP address") },
                    placeholder = { Text("Not configured (fast path disabled)") },
                    supportingText = { Text("Enables instant job order / job board / delivery schedule sync. Leave blank to always use the existing (slower) sync mechanism.") },
                    colors = filledFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (adminSyncIpSaved) {
                        Text(
                            "Saved",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            timecardScope.launch {
                                adminSyncConfig.setManualIp(editAdminSyncIp.ifBlank { null })
                            }
                            adminSyncIpDirty = false
                            adminSyncIpSaved = true
                        },
                        enabled = adminSyncIpDirty
                    ) {
                        Text("Save")
                    }
                }
            }

            // ── About ────────────────────────────────────────────────────
            SettingsCard(title = "About") {
                Text(
                    "KKC Sheet Tracker v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isDebugBuild) {
                    TextButton(onClick = onReinstallLatest) {
                        Text("Reinstall Latest Debug APK")
                    }
                }
            }

            // ── Admin ────────────────────────────────────────────────────
            SettingsCard(title = "Admin") {
                if (!adminMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Admin", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Unlock advanced controls",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { showAdminDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Unlock", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } else {
                    Text(
                        "Admin mode is ON",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        "The supply \"To Order\" tab is visible, and the Jobs tab shows a reorder control.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    TextButton(onClick = { AdminModeController.setEnabled(false) }) {
                        Text("Lock admin")
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

// ── Private composables ─────────────────────────────────────────────────────

@Composable
private fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val lowEnd = LocalLowEndMode.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (lowEnd.shadowsDisabled) 0.dp else 2.dp, RoundedCornerShape(12.dp), clip = false)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (lowEnd.shadowsDisabled) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun WorkModeIconTile(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lowEnd = LocalLowEndMode.current
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val textColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (isSelected) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (!isSelected) Modifier.border(1.dp, borderColor, RoundedCornerShape(12.dp))
                else Modifier
            )
            .shadow(if (lowEnd.shadowsDisabled) 0.dp else if (isSelected) 0.dp else 1.dp, RoundedCornerShape(12.dp), clip = false)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Placeholder icon slot — replace with custom SVG/Image icon later
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                // Icon placeholder
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
        }
    }
}

@Composable
private fun SettingsStatusBadge(status: SyncthingServiceStatus) {
    val (bgColor, text) = when (status) {
        SyncthingServiceStatus.CHECKING -> MaterialTheme.colorScheme.tertiaryContainer to "Checking"
        SyncthingServiceStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer to "Running"
        SyncthingServiceStatus.NOT_RUNNING -> MaterialTheme.colorScheme.errorContainer to "Not running"
        SyncthingServiceStatus.START_FAILED -> MaterialTheme.colorScheme.errorContainer to "Start failed"
        SyncthingServiceStatus.API_KEY_REQUIRED -> MaterialTheme.colorScheme.tertiaryContainer to "API key required"
    }
    val textColor = when (status) {
        SyncthingServiceStatus.CHECKING -> MaterialTheme.colorScheme.onTertiaryContainer
        SyncthingServiceStatus.RUNNING -> MaterialTheme.colorScheme.onPrimaryContainer
        SyncthingServiceStatus.NOT_RUNNING -> MaterialTheme.colorScheme.onErrorContainer
        SyncthingServiceStatus.START_FAILED -> MaterialTheme.colorScheme.onErrorContainer
        SyncthingServiceStatus.API_KEY_REQUIRED -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = "Status: $text",
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun filledFieldColors(): TextFieldColors {
    val containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    return OutlinedTextFieldDefaults.colors(
        unfocusedContainerColor = containerColor,
        unfocusedBorderColor = Color.Transparent,
        focusedContainerColor = containerColor,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ── Private helper functions ────────────────────────────────────────────────

private fun formatStatusTime(timestampMs: Long): String {
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestampMs))
}