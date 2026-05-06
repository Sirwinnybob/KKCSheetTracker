package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.ui.components.StatusBorderedCard
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwoodsDashboardScreen(
    scanCoordinator: HardwoodsScanCoordinator,
    progressStore: HardwoodsProgressStore,
    onNavigateToJobs: () -> Unit,
    onOpenJob: (HardwoodJob) -> Unit
) {
    val scanState by scanCoordinator.state.collectAsState()
    val progressVersion by progressStore.progressVersion.collectAsState()
    val jobs = scanState.snapshot.jobs
    val loading = scanState.status == ScanStatus.LOADING && jobs.isEmpty()

    val summaries = remember(jobs, progressVersion) {
        jobs.map { progressStore.summarizeJob(it) }
    }
    val totalCounts = summaries.fold(HardwoodStatusCounts()) { acc, item ->
        HardwoodStatusCounts(
            totalPieces = acc.totalPieces + item.counts.totalPieces,
            donePieces = acc.donePieces + item.counts.donePieces,
            badPieces = acc.badPieces + item.counts.badPieces,
            skippedPieces = acc.skippedPieces + item.counts.skippedPieces
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hardwoods Dashboard") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Jobs: ${jobs.size}")
                        Text("Pieces: ${totalCounts.donePieces}/${totalCounts.totalPieces} complete")
                        Text("Bad pieces: ${totalCounts.badPieces}")
                        Text("Skipped pieces: ${totalCounts.skippedPieces}")
                        Text("Completion: ${(totalCounts.completionFraction * 100f).roundToInt()}%")
                        Button(onClick = onNavigateToJobs) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                            Text("  Open Jobs")
                        }
                    }
                }
            }

            item {
                HardwoodsQualityAlertCard(
                    badPieces = totalCounts.badPieces,
                    skippedPieces = totalCounts.skippedPieces
                )
            }

            items(summaries.take(8), key = { it.job.folderName }) { summary ->
                StatusBorderedCard(
                    status = hardwoodSummaryStatus(summary.counts),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpenJob(summary.job) },
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(summary.job.folderName, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${summary.counts.donePieces}/${summary.counts.totalPieces} done • bad ${summary.counts.badPieces} • skipped ${summary.counts.skippedPieces}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

private fun hardwoodSummaryStatus(counts: HardwoodStatusCounts): SheetStatus {
    return when {
        counts.badPieces > 0 -> SheetStatus.HAS_BAD_PARTS
        counts.skippedPieces > 0 && counts.donePieces <= 0 -> SheetStatus.SKIPPED
        counts.totalPieces > 0 && counts.donePieces >= counts.totalPieces -> SheetStatus.COMPLETE
        counts.donePieces > 0 || counts.skippedPieces > 0 -> SheetStatus.IN_PROGRESS
        else -> SheetStatus.NOT_STARTED
    }
}

@Composable
private fun HardwoodsQualityAlertCard(
    badPieces: Int,
    skippedPieces: Int
) {
    val hasIssues = badPieces > 0 || skippedPieces > 0
    val status = when {
        badPieces > 0 -> SheetStatus.HAS_BAD_PARTS
        skippedPieces > 0 -> SheetStatus.SKIPPED
        else -> SheetStatus.COMPLETE
    }
    val accentColor = when (status) {
        SheetStatus.HAS_BAD_PARTS -> KKCThemeColors.statusColors.bad
        SheetStatus.SKIPPED -> KKCThemeColors.statusColors.skipBorder
        SheetStatus.COMPLETE -> KKCThemeColors.statusColors.completeBorder
        SheetStatus.NOT_STARTED -> KKCThemeColors.statusColors.notStarted
        SheetStatus.IN_PROGRESS -> KKCThemeColors.statusColors.inProgressBorder
    }
    StatusBorderedCard(
        status = status,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (hasIssues) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = accentColor
                )
                Text(
                    if (hasIssues) "Quality Alert" else "Quality Alert Clear",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                when {
                    badPieces > 0 && skippedPieces > 0 -> "$badPieces bad pieces and $skippedPieces skipped pieces need review"
                    badPieces > 0 -> "$badPieces bad pieces need review"
                    skippedPieces > 0 -> "$skippedPieces skipped pieces need review"
                    else -> "No active bad-piece or skipped-piece alerts."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
