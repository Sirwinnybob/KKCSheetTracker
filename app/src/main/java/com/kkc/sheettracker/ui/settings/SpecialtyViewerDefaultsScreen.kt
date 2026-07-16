package com.kkc.sheettracker.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.SpecialtyViewerDefaults
import com.kkc.sheettracker.data.SpecialtyViewerDefaultsStore
import com.kkc.sheettracker.data.specialtyViewerSectionOptions
import com.kkc.sheettracker.data.specialtyViewerStationLabel
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpecialtyViewerDefaultsScreen(
    store: SpecialtyViewerDefaultsStore,
    onBack: () -> Unit,
) {
    val defaults by store.defaults.collectAsState(initial = SpecialtyViewerDefaults())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            KKCTopAppBar(
                title = {
                    Text(
                        "Specialty Viewer Defaults",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                
                
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                SectionLabel("Station Order")
            }
            item {
                Text(
                    "Controls the checklist section order in specialty job view.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            itemsIndexed(
                items = defaults.stationOrder,
                key = { _, station -> "station-${station.name}" }
            ) { index, station ->
                StationOrderRow(
                    station = station,
                    canMoveUp = index > 0,
                    canMoveDown = index < defaults.stationOrder.lastIndex,
                    onMoveUp = {
                        scope.launch {
                            store.setStationOrder(defaults.stationOrder.move(index, index - 1))
                        }
                    },
                    onMoveDown = {
                        scope.launch {
                            store.setStationOrder(defaults.stationOrder.move(index, index + 1))
                        }
                    },
                    modifier = Modifier.animateItem()
                )
            }

            item {
                SectionLabel("Expanded By Default")
            }
            item {
                Text(
                    "Choose which sections start expanded when the specialty viewer opens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            itemsIndexed(
                items = specialtyViewerSectionOptions(defaults.stationOrder),
                key = { _, section -> "section-${section.id}" }
            ) { _, section ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(section.label, style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = section.id in defaults.expandedSectionIds,
                        onCheckedChange = { expanded ->
                            scope.launch { store.setSectionExpanded(section.id, expanded) }
                        },
                    )
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun StationOrderRow(
    station: SpecialtyStation,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            specialtyViewerStationLabel(station),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
            }
        }
    }
}

private fun List<SpecialtyStation>.move(fromIndex: Int, toIndex: Int): List<SpecialtyStation> {
    val mutable = toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex, item)
    return mutable
}
