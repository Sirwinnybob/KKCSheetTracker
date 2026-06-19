package com.kkc.sheettracker.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AssemblyPaneView
import com.kkc.sheettracker.data.AssemblyViewLayout
import com.kkc.sheettracker.data.AssemblyViewerDefaults
import com.kkc.sheettracker.data.AssemblyViewerDefaultsStore
import com.kkc.sheettracker.ui.components.headerGradientBrush
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssemblyViewerDefaultsScreen(
    store: AssemblyViewerDefaultsStore,
    onBack: () -> Unit,
) {
    val defaults by store.defaults.collectAsState(initial = AssemblyViewerDefaults())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.background(headerGradientBrush()),
                title = {
                    Text(
                        "Assembly Viewer Defaults",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                windowInsets = WindowInsets.statusBars,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionLabel("Layout")
            Column(Modifier.selectableGroup()) {
                LayoutRadio(
                    label = "Split view",
                    selected = defaults.layout == AssemblyViewLayout.SPLIT,
                    onSelect = { scope.launch { store.setLayout(AssemblyViewLayout.SPLIT) } },
                )
                LayoutRadio(
                    label = "Single view",
                    selected = defaults.layout == AssemblyViewLayout.SINGLE,
                    onSelect = { scope.launch { store.setLayout(AssemblyViewLayout.SINGLE) } },
                )
            }

            SectionLabel("Panes")
            when (defaults.layout) {
                AssemblyViewLayout.SPLIT -> {
                    PaneDropdown(
                        label = "Left pane",
                        current = defaults.firstPane,
                        onSelect = { scope.launch { store.setFirstPane(it) } },
                    )
                    PaneDropdown(
                        label = "Right pane",
                        current = defaults.secondPane,
                        onSelect = { scope.launch { store.setSecondPane(it) } },
                    )
                }
                AssemblyViewLayout.SINGLE -> {
                    PaneDropdown(
                        label = "View",
                        current = defaults.firstPane,
                        onSelect = { scope.launch { store.setFirstPane(it) } },
                    )
                }
            }

            SectionLabel("Display")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Open in fullscreen (hide UI)", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = defaults.hideUiOnOpen,
                    onCheckedChange = { value ->
                        scope.launch { store.setHideUiOnOpen(value) }
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun LayoutRadio(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.height(0.dp))
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun PaneDropdown(
    label: String,
    current: AssemblyPaneView,
    onSelect: (AssemblyPaneView) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { expanded = true }) {
            Text(current.displayName())
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AssemblyPaneView.values().forEach { view ->
                DropdownMenuItem(
                    text = { Text(view.displayName()) },
                    onClick = {
                        expanded = false
                        onSelect(view)
                    },
                )
            }
        }
    }
}

private fun AssemblyPaneView.displayName(): String = when (this) {
    AssemblyPaneView.ASSEMBLY -> "Assembly"
    AssemblyPaneView.PLANS -> "Plans"
    AssemblyPaneView.DELIVERY -> "Delivery"
    AssemblyPaneView.THREE_D -> "3D"
    AssemblyPaneView.CHECKLIST -> "Checklist"
}
