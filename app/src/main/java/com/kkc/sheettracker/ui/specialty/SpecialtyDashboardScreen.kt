package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.ui.theme.KKCAlpha
import com.kkc.sheettracker.ui.theme.KKCSpacing
import com.kkc.sheettracker.data.completionKeysForItem
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.SpecialtyJob
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.StatusChip
import java.time.Instant

private data class SpecialtyInProgressItemUi(
    val key: String,
    val jobFolderName: String,
    val jobNumber: String,
    val itemName: String,
    val stationsLabel: String,
    val completedSteps: Int,
    val totalSteps: Int,
    val lastUpdatedAtRaw: String?,
    val lastUpdatedAtMillis: Long
)

@Composable
fun SpecialtyDashboardScreen(
    specialtyStateStore: SpecialtyStateStore,
    onNavigateToJobs: () -> Unit,
    onOpenJob: (String) -> Unit
) {
    val scanState by specialtyStateStore.scanState.collectAsState()
    val progressVersion by specialtyStateStore.progressVersion.collectAsState()
    val jobs = remember(scanState.snapshot.generation, progressVersion) {
        specialtyStateStore.getJobs()
    }

    val recentJobs = remember(jobs) { jobs.take(8) }
    val inProgressItems = remember(jobs) { buildRecentInProgressSpecialtyItems(jobs).take(16) }
    val totalItems = remember(jobs) { jobs.sumOf { it.totalItems } }
    val completedItems = remember(jobs) { jobs.sumOf { it.completedItems } }

    if (scanState.status == ScanStatus.LOADING && jobs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

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
        LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = KKCSpacing.listContentHorizontal, top = KKCSpacing.listContentVertical, end = KKCSpacing.listContentHorizontal, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(KKCSpacing.listItemSpacing)
    ) {
        item(key = "summary") {
            Surface(
                tonalElevation = 3.dp,
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(KKCSpacing.cardPaddingCompact),
                    verticalArrangement = Arrangement.spacedBy(KKCSpacing.inCardSpacing)
                ) {
                    Text(
                        text = "Specialty Overview",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(KKCSpacing.inCardSpacing)) {
                        StatusChip(
                            text = "Jobs ${jobs.size}",
                            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        StatusChip(
                            text = "Complete $completedItems/$totalItems",
                            backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Button(onClick = onNavigateToJobs) {
                        Text("Open Jobs")
                    }
                }
            }
        }

        item(key = "recent-jobs-header") {
            Text(
                text = "Recent Jobs",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (recentJobs.isEmpty()) {
            item(key = "recent-jobs-empty") {
                Text(
                    text = "No jobs found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(recentJobs, key = { it.folderName }) { job ->
                val subtitle = "${job.completedItems}/${job.totalItems} complete"
                ProgressCard(
                    title = job.folderName,
                    subtitle = subtitle,
                    fraction = job.completionFraction,
                    expanded = false,
                    onToggleExpanded = {},
                    onClick = { onOpenJob(job.folderName) },
                    showExpandToggle = false,
                    showBottomProgressBar = true,
                    inlineContent = {
                        if (job.totalItems <= 0) {
                            Text(
                                text = "No specialty checklist items yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        }

        item(key = "recent-items-header") {
            Text(
                text = "Recent In-Progress Specialty Items",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (inProgressItems.isEmpty()) {
            item(key = "recent-items-empty") {
                Text(
                    text = "No in-progress specialty items yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(inProgressItems, key = { it.key }) { item ->
                Surface(
                    tonalElevation = 3.dp,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenJob(item.jobFolderName) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(KKCSpacing.cardPaddingSmall),
                        verticalArrangement = Arrangement.spacedBy(KKCSpacing.textLineGap)
                    ) {
                        Text(
                            text = item.itemName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = item.jobFolderName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${item.completedSteps}/${item.totalSteps} steps complete • ${item.stationsLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!item.lastUpdatedAtRaw.isNullOrBlank()) {
                            Text(
                                text = "Last update: ${item.lastUpdatedAtRaw}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

private fun buildRecentInProgressSpecialtyItems(jobs: List<SpecialtyJob>): List<SpecialtyInProgressItemUi> {
    return jobs.flatMap { job ->
        job.resolvedItems.mapNotNull { resolved ->
            val completionKeys = completionKeysForItem(resolved.item)
            val totalSteps = completionKeys.size.coerceAtLeast(1)
            val completedSteps = completionKeys.count { key ->
                resolved.completionByKey[key]?.completed == true
            }
            if (completedSteps <= 0 || completedSteps >= totalSteps) return@mapNotNull null

            val stationsLabel = resolved.item.stations
                .joinToString(" • ") { station -> station.name.replace('_', ' ') }
                .ifBlank { "No station tags" }

            val latestTimestampRaw = completionKeys
                .mapNotNull { key ->
                    resolved.completionByKey[key]
                        ?.takeIf { it.completed }
                        ?.completedAt
                }
                .maxByOrNull { parseIsoInstantToMillis(it) ?: Long.MIN_VALUE }
            val latestTimestampMs = parseIsoInstantToMillis(latestTimestampRaw) ?: 0L

            SpecialtyInProgressItemUi(
                key = "${job.folderName}::${resolved.item.id}",
                jobFolderName = job.folderName,
                jobNumber = job.jobNumber,
                itemName = resolved.item.name,
                stationsLabel = stationsLabel,
                completedSteps = completedSteps,
                totalSteps = totalSteps,
                lastUpdatedAtRaw = latestTimestampRaw,
                lastUpdatedAtMillis = latestTimestampMs
            )
        }
    }.sortedWith(
        compareByDescending<SpecialtyInProgressItemUi> { it.lastUpdatedAtMillis }
            .thenByDescending { it.jobNumber }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.jobFolderName }
    )
}

private fun parseIsoInstantToMillis(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
}

