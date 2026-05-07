package com.kkc.sheettracker.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.navigation.WorkMode
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    workMode: WorkMode,
    onThemeChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onTabletIdChanged: (String) -> Unit,
    onBasePathChanged: (String) -> Unit,
    onBack: () -> Unit
) {
    var editTabletId by remember { mutableStateOf(tabletId) }
    var editBasePath by remember { mutableStateOf(basePath) }
    var tabletIdDirty by remember { mutableStateOf(false) }
    var basePathDirty by remember { mutableStateOf(false) }
    var tabletSaved by remember { mutableStateOf(false) }
    var basePathSaved by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsSection(title = "Appearance", accentColor = MaterialTheme.colorScheme.primary) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dark Mode", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = isDarkTheme,
                        onCheckedChange = onThemeChanged
                    )
                }

                Text("Work Mode", style = MaterialTheme.typography.bodyLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = workMode == WorkMode.CNC,
                        onClick = { onWorkModeChanged(WorkMode.CNC) },
                        label = { Text("CNC") }
                    )
                    FilterChip(
                        selected = workMode == WorkMode.HARDWOODS,
                        onClick = { onWorkModeChanged(WorkMode.HARDWOODS) },
                        label = { Text("Hardwoods") }
                    )
                    FilterChip(
                        selected = workMode == WorkMode.ASSEMBLY,
                        onClick = { onWorkModeChanged(WorkMode.ASSEMBLY) },
                        label = { Text("Assembly") }
                    )
                }
            }

            SettingsSection(
                title = "Tablet",
                accentColor = MaterialTheme.colorScheme.tertiary
            ) {
                OutlinedTextField(
                    value = editTabletId,
                    onValueChange = {
                        editTabletId = it
                        tabletIdDirty = it != tabletId
                    },
                    label = { Text("Tablet ID") },
                    supportingText = { Text("Used for progress file naming. Must be unique per tablet.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
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
                                shape = MaterialTheme.shapes.medium
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

            SettingsSection(
                title = "Data Source",
                accentColor = MaterialTheme.colorScheme.secondary
            ) {
                OutlinedTextField(
                    value = editBasePath,
                    onValueChange = {
                        editBasePath = it
                        basePathDirty = it != basePath
                    },
                    label = { Text("Ready Jobs Folder Path") },
                    supportingText = { Text("Path to the synced Ready Jobs folder on this tablet.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
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
                                shape = MaterialTheme.shapes.medium
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

            SettingsSection(
                title = "About",
                accentColor = MaterialTheme.colorScheme.outline
            ) {
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
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    accentColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp, bottom = 6.dp)
                .width(4.dp)
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.small)
                .background(accentColor)
        )
        Surface(
            tonalElevation = 1.dp,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }
}
