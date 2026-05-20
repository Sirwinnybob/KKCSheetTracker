package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.SpecialtyScanCoordinator
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.StationProgress
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.StatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecialtyJobsScreen(
    specialtyScanCoordinator: SpecialtyScanCoordinator,
    specialtyStateStore: SpecialtyStateStore,
    onJobClick: (com.kkc.sheettracker.data.models.SpecialtyJobCard) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val scanState by specialtyStateStore.scanState.collectAsState()
    val progressVersion by specialtyStateStore.progressVersion.collectAsState()
    val cards = remember(scanState.snapshot.generation, progressVersion) {
        specialtyStateStore.deriveJobCards()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
    }
    val filteredCards = remember(cards, query) {
        if (query.isBlank()) {
            cards
        } else {
            cards.filter { card ->
                card.jobNumber.contains(query, ignoreCase = true) ||
                    card.jobName.contains(query, ignoreCase = true) ||
                    card.folderName.contains(query, ignoreCase = true)
            }
        }
    }

    LaunchedEffect(Unit) {
        specialtyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KKC Sheet Tracker - Specialty") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = { specialtyScanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Filter jobs by number or name...") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )
            Text(
                text = if (query.isBlank()) {
                    "${cards.size} jobs"
                } else {
                    "Showing ${filteredCards.size} of ${cards.size} jobs"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            when {
                scanState.status == ScanStatus.LOADING && cards.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                filteredCards.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No jobs found")
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredCards, key = { it.folderName }) { card ->
                            val statusCounts = remember(card.totalItems, card.completedItems) {
                                StatusCounts(
                                    total = card.totalItems.coerceAtLeast(0),
                                    complete = card.completedItems.coerceIn(0, card.totalItems.coerceAtLeast(0)),
                                    bad = 0,
                                    skipped = 0,
                                    notStarted = (card.totalItems - card.completedItems).coerceAtLeast(0)
                                )
                            }
                            ProgressCard(
                                title = card.folderName,
                                subtitle = "${card.completedItems}/${card.totalItems} complete",
                                fraction = card.completionFraction,
                                expanded = false,
                                onToggleExpanded = {},
                                onClick = { onJobClick(card) },
                                showBottomProgressBar = true,
                                segmentedStatusCounts = statusCounts,
                                showExpandToggle = false,
                                headerActions = {
                                    if (card.hiddenFromProduction) {
                                        StatusChip(
                                            text = "Hidden in Production",
                                            backgroundColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                },
                                inlineContent = {
                                    when {
                                        card.totalItems <= 0 -> {
                                            Text(
                                                text = "No specialty checklist items yet",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        card.stationProgress.isNotEmpty() -> {
                                            StationProgressBars(card.stationProgress)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StationProgressBars(stationProgress: List<StationProgress>) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        stationProgress.forEach { sp ->
            val fraction = if (sp.total <= 0) 0f else sp.completed.toFloat() / sp.total.toFloat()
            val barColor = stationBarColor(sp.station)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${sp.station} · ${sp.completed}/${sp.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(110.dp)
                )
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp),
                    color = barColor,
                    trackColor = barColor.copy(alpha = 0.20f)
                )
            }
        }
    }
}

private fun stationBarColor(station: String): Color = when (station.uppercase()) {
    "SAW" -> Color(0xFFD97706)
    "ASSEMBLY", "ASSM" -> Color(0xFF2563EB)
    "HARDWOODS", "HW" -> Color(0xFF16A34A)
    "SPECIALTY", "SPEC" -> Color(0xFF7C3AED)
    "CNC" -> Color(0xFF6366F1)
    "EDGE_BANDER", "EDGE" -> Color(0xFF0891B2)
    else -> Color(0xFF6366F1)
}
