package com.kkc.sheettracker.ui.assembly

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AssemblyStateStore
import com.kkc.sheettracker.data.models.AssemblyCncSummary
import com.kkc.sheettracker.data.models.AssemblyHardwoodsSummary
import com.kkc.sheettracker.data.models.AssemblyJobCard
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.ui.components.ProgressState
import com.kkc.sheettracker.ui.components.StatusBorderedCard
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssemblyDashboardScreen(
    assemblyStateStore: AssemblyStateStore,
    onNavigateToJobs: () -> Unit,
    onOpenJob: (folderName: String) -> Unit
) {
    val scanState by assemblyStateStore.scanState.collectAsState()
    val jobCards by assemblyStateStore.jobCards.collectAsState()
    val loading = scanState.status == ScanStatus.LOADING && jobCards.isEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assembly") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = { assemblyStateStore.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AssemblyOverviewCard(jobCards = jobCards, onNavigateToJobs = onNavigateToJobs)
            }

            items(jobCards, key = { it.folderName }) { card ->
                AssemblyJobCard(card = card, onClick = { onOpenJob(card.folderName) })
            }
        }
    }
}

@Composable
private fun AssemblyOverviewCard(
    jobCards: List<AssemblyJobCard>,
    onNavigateToJobs: () -> Unit
) {
    val totalCncSheets = jobCards.sumOf { it.cncSummary.totalSheets }
    val doneCncSheets = jobCards.sumOf { it.cncSummary.completedSheets }
    val totalPieces = jobCards.sumOf { it.hardwoodsSummary.totalPieces }
    val donePieces = jobCards.sumOf { it.hardwoodsSummary.donePieces }

    Card(
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${jobCards.size} job${if (jobCards.size != 1) "s" else ""}")

            if (totalCncSheets > 0) {
                OverviewProgressRow(
                    label = "CNC",
                    done = doneCncSheets,
                    total = totalCncSheets,
                    unit = "sheets"
                )
            }
            if (totalPieces > 0) {
                OverviewProgressRow(
                    label = "Hardwoods",
                    done = donePieces,
                    total = totalPieces,
                    unit = "pieces"
                )
            }

            Button(onClick = onNavigateToJobs) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                Text("  Open Jobs")
            }
        }
    }
}

@Composable
private fun OverviewProgressRow(label: String, done: Int, total: Int, unit: String) {
    val fraction = if (total > 0) done.toFloat() / total.toFloat() else 0f
    val pct = (fraction * 100f).roundToInt()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$done/$total $unit  $pct%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        WideProgressBar(fraction = fraction)
    }
}

@Composable
internal fun AssemblyJobCard(card: AssemblyJobCard, onClick: () -> Unit) {
    val cardStatus = assemblyJobCardStatus(card)
    StatusBorderedCard(
        status = cardStatus,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "${card.jobNumber} — ${card.jobName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            if (card.hasBothModes) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CncProgressPanel(summary = card.cncSummary, modifier = Modifier.weight(1f))
                    HardwoodsProgressPanel(summary = card.hardwoodsSummary, modifier = Modifier.weight(1f))
                }
            } else if (card.cncSummary.totalSheets > 0) {
                CncProgressPanel(summary = card.cncSummary, modifier = Modifier.fillMaxWidth())
            } else if (card.hardwoodsSummary.totalPieces > 0) {
                HardwoodsProgressPanel(summary = card.hardwoodsSummary, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CncProgressPanel(summary: AssemblyCncSummary, modifier: Modifier = Modifier) {
    val fraction = summary.completionFraction
    val pct = (fraction * 100f).roundToInt()
    ProgressPanel(
        title = "CNC",
        count = "${summary.completedSheets}/${summary.totalSheets} sheets",
        fraction = fraction,
        pct = pct,
        hasBad = summary.badPartsSheets > 0,
        modifier = modifier
    )
}

@Composable
private fun HardwoodsProgressPanel(summary: AssemblyHardwoodsSummary, modifier: Modifier = Modifier) {
    val fraction = summary.completionFraction
    val pct = (fraction * 100f).roundToInt()
    ProgressPanel(
        title = "Hardwoods",
        count = "${summary.donePieces}/${summary.totalPieces} pieces",
        fraction = fraction,
        pct = pct,
        hasBad = summary.badPieces > 0,
        modifier = modifier
    )
}

@Composable
private fun ProgressPanel(
    title: String,
    count: String,
    fraction: Float,
    pct: Int,
    hasBad: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = KKCThemeColors.statusColors
    val barColor = when {
        hasBad -> colors.bad
        fraction >= 1f -> colors.completeBorder
        fraction > 0f -> colors.inProgressBorder
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = count,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WideProgressBar(
                    fraction = fraction,
                    fillColor = barColor,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$pct%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WideProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    fillColor: androidx.compose.ui.graphics.Color = KKCThemeColors.statusColors.inProgressBorder
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val safeFraction = fraction.coerceIn(0f, 1f)
    val actualFill = if (safeFraction >= 1f) KKCThemeColors.statusColors.completeBorder else fillColor

    Box(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(trackColor)
    ) {
        if (safeFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(safeFraction)
                    .fillMaxHeight()
                    .background(actualFill)
            )
        }
    }
}

private fun assemblyJobCardStatus(card: AssemblyJobCard): SheetStatus {
    val cncBad = card.cncSummary.badPartsSheets > 0
    val hwBad = card.hardwoodsSummary.badPieces > 0
    if (cncBad || hwBad) return SheetStatus.HAS_BAD_PARTS

    val cncFraction = card.cncSummary.completionFraction
    val hwFraction = card.hardwoodsSummary.completionFraction
    val hasCnc = card.cncSummary.totalSheets > 0
    val hasHw = card.hardwoodsSummary.totalPieces > 0

    val overallFraction = when {
        hasCnc && hasHw -> (cncFraction + hwFraction) / 2f
        hasCnc -> cncFraction
        hasHw -> hwFraction
        else -> 0f
    }

    return when {
        overallFraction >= 1f -> SheetStatus.COMPLETE
        overallFraction > 0f -> SheetStatus.IN_PROGRESS
        else -> SheetStatus.NOT_STARTED
    }
}
