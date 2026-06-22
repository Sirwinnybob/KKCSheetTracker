package com.kkc.sheettracker.ui.assembly

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AssemblyScanCoordinator
import com.kkc.sheettracker.data.AssemblyStateStore
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.models.AssemblyJobCard
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.theme.KKCAlpha
import com.kkc.sheettracker.ui.theme.KKCSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssemblyDashboardScreen(
    assemblyScanCoordinator: AssemblyScanCoordinator,
    assemblyStateStore: AssemblyStateStore,
    progressStore: ProgressStore,
    hardwoodsProgressStore: HardwoodsProgressStore,
    specialtyStateStore: SpecialtyStateStore,
    specialtyProgressVersionHint: Long = 0L,
    onNavigateToJobs: () -> Unit
) {
    val scanState by assemblyScanCoordinator.state.collectAsState()
    val cncProgressVersion by progressStore.progressVersion.collectAsState()
    val hardwoodProgressVersion by hardwoodsProgressStore.progressVersion.collectAsState()
    val specialtyScanState by specialtyStateStore.scanState.collectAsState()
    val specialtyProgressVersion by specialtyStateStore.progressVersion.collectAsState()

    val cards = remember(scanState.snapshot.generation, cncProgressVersion, hardwoodProgressVersion) {
        assemblyStateStore.deriveJobCards()
    }
    val specialtyCards = remember(specialtyScanState.snapshot.generation, specialtyProgressVersion, specialtyProgressVersionHint) {
        specialtyStateStore.deriveJobCards()
    }
    val specialtySummary = remember(specialtyCards) {
        val totalSpecialtyItems = specialtyCards.sumOf { it.totalItems }
        val completedSpecialtyItems = specialtyCards.sumOf { it.completedItems }
        Triple(specialtyCards.size, completedSpecialtyItems, totalSpecialtyItems)
    }

    LaunchedEffect(Unit) {
        assemblyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.headerBackground(),
                title = {
                    Text(
                        "Assembly",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                windowInsets = WindowInsets.statusBars,
                actions = {
                    IconButton(onClick = { assemblyScanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        val gradientEndPx = with(LocalDensity.current) { 300.dp.toPx() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = KKCAlpha.gradientAccentTop),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = gradientEndPx
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
            val totalJobs = cards.size
            val totalCabinets = remember(cards) {
                cards.sumOf { card ->
                    val index = assemblyStateStore.getCabinetSheetIndex(card.folderName)
                    val cabinets = index?.documents?.assembly?.virtualCombined?.cabinetToPages
                        ?.takeIf { it.isNotEmpty() }
                        ?: index?.documents?.assembly?.cabinetToPages
                        ?: emptyMap()
                    cabinets.keys.size
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KKCSpacing.screenHorizontal, vertical = KKCSpacing.m),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(KKCSpacing.cardPaddingCompact), verticalArrangement = Arrangement.spacedBy(KKCSpacing.xxs)) {
                    Text("Overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Jobs: $totalJobs", style = MaterialTheme.typography.bodyMedium)
                    Text("Cabinets Indexed: $totalCabinets", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onNavigateToJobs) {
                        Text("Open Jobs")
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Arrow Forward icon")
                    }
                }
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KKCSpacing.screenHorizontal, vertical = KKCSpacing.xs),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(KKCSpacing.cardPaddingCompact), verticalArrangement = Arrangement.spacedBy(KKCSpacing.xxs)) {
                    Text("Specialty", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    when (specialtyScanState.status) {
                        ScanStatus.LOADING -> {
                            Text("Scanning specialty items...", style = MaterialTheme.typography.bodyMedium)
                        }
                        ScanStatus.ERROR -> {
                            Text(
                                specialtyScanState.errorMessage ?: "Specialty scan failed",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {
                            val (specialtyJobCount, completedSpecialtyItems, totalSpecialtyItems) = specialtySummary
                            Text("Jobs with specialty: $specialtyJobCount", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Completed: $completedSpecialtyItems / $totalSpecialtyItems items",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (specialtyJobCount == 0) {
                                Text(
                                    "No specialty items found for current jobs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            when {
                scanState.status == ScanStatus.LOADING && cards.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                cards.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No assembly jobs found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = KKCSpacing.listContentHorizontal, top = KKCSpacing.inCardSpacing, end = KKCSpacing.listContentHorizontal, bottom = 112.dp),
                        verticalArrangement = Arrangement.spacedBy(KKCSpacing.listItemSpacing)
                    ) {
                        items(cards, key = { it.folderName }) { card ->
                            AssemblyJobCardView(card = card)
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun AssemblyJobCardView(card: AssemblyJobCard) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(KKCSpacing.cardPaddingCompact), verticalArrangement = Arrangement.spacedBy(KKCSpacing.m)) {
            Text(
                "${card.jobNumber} - ${card.jobName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KKCSpacing.l)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KKCSpacing.xxs)) {
                    Text("CNC", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${card.cncSummary.completedSheets}/${card.cncSummary.totalSheets} sheets",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { card.cncSummary.completionFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KKCSpacing.xxs)) {
                    Text("Hardwoods", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${card.hardwoodsSummary.donePieces}/${card.hardwoodsSummary.totalPieces} pieces",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { card.hardwoodsSummary.completionFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (!card.hasBothModes) {
                Text(
                    "Missing one production mode for this job.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
