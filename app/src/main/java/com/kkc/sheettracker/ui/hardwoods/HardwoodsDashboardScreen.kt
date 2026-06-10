package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HardwoodJobSummary
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.ui.components.ProgressPill
import com.kkc.sheettracker.ui.components.ProgressState
import com.kkc.sheettracker.ui.components.StatusBorderedCard
import com.kkc.sheettracker.ui.theme.KKCAlpha
import com.kkc.sheettracker.ui.theme.KKCSpacing
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import kotlin.math.roundToInt

private data class HardwoodJobDashboardEntry(
    val summary: HardwoodJobSummary,
    val ripsDone: Int,
    val ripsTotal: Int
)

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

    val summaries by produceState(
        initialValue = emptyList<HardwoodJobDashboardEntry>(),
        key1 = jobs,
        key2 = progressVersion,
    ) {
        value = withContext(Dispatchers.IO) {
            jobs.map { job ->
                val base = progressStore.summarizeJob(job)
                val totalsDoneMap = progressStore.getTotalsRip10DoneMap(job.folderName)
                val rowProgressMap = progressStore.getRowProgressMap(job.folderName)
                val boardRows = applySkippedPartRowsToBoardStockRows(
                    rows = buildBoardStockRows(scanState.snapshot.basePath, job.folderName, job.index),
                    index = job.index,
                    rowProgressMap = rowProgressMap
                )
                val boardTotal = boardRows.sumOf { it.neededRips.coerceAtLeast(0) }
                val boardDone = boardRows.sumOf { row ->
                    val key = progressStore.makeBoardStockTallyKey(row.material, row.normalizedWidth, row.source.name)
                    (totalsDoneMap[key] ?: 0).coerceIn(0, row.neededRips.coerceAtLeast(0))
                }
                HardwoodJobDashboardEntry(
                    summary = base.copy(
                        counts = HardwoodStatusCounts(
                            totalPieces = base.counts.totalPieces + boardTotal,
                            donePieces = base.counts.donePieces + boardDone,
                            badPieces = base.counts.badPieces,
                            skippedPieces = base.counts.skippedPieces
                        )
                    ),
                    ripsDone = boardDone,
                    ripsTotal = boardTotal
                )
            }
        }
    }
    val totalCounts = summaries.fold(HardwoodStatusCounts()) { acc, entry ->
        HardwoodStatusCounts(
            totalPieces = acc.totalPieces + entry.summary.counts.totalPieces,
            donePieces = acc.donePieces + entry.summary.counts.donePieces,
            badPieces = acc.badPieces + entry.summary.counts.badPieces,
            skippedPieces = acc.skippedPieces + entry.summary.counts.skippedPieces
        )
    }

    val statusColors = KKCThemeColors.statusColors
    var showBadModal by rememberSaveable { mutableStateOf(false) }
    var showSkippedModal by rememberSaveable { mutableStateOf(false) }
    val recentJobs by produceState(
        initialValue = emptyList<Pair<HardwoodJob, Long>>(),
        key1 = jobs,
        key2 = progressVersion,
    ) {
        value = withContext(Dispatchers.IO) {
            jobs.map { job -> job to progressStore.getLocalLastTouchedAtMs(job.folderName) }
                .filter { (_, ms) -> ms > 0L }
                .sortedByDescending { (_, ms) -> ms }
                .take(5)
        }
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(KKCSpacing.screenHorizontal),
                contentPadding = PaddingValues(bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(KKCSpacing.listItemSpacing)
            ) {
                item {
                    HardwoodsOverviewCard(
                        jobs = jobs,
                        totalCounts = totalCounts,
                        onNavigateToJobs = onNavigateToJobs,
                        onBadClick = { showBadModal = true },
                        onSkippedClick = { showSkippedModal = true }
                    )
                }

                item {
                    HardwoodsQualityAlertCard(
                        badPieces = totalCounts.badPieces,
                        skippedPieces = totalCounts.skippedPieces
                    )
                }

                if (recentJobs.isNotEmpty()) {
                    item {
                        HardwoodsRecentJobsCard(
                            recentJobs = recentJobs,
                            onOpenJob = onOpenJob
                        )
                    }
                }

                items(summaries.take(8), key = { it.summary.job.folderName }) { entry ->
                    HardwoodsJobCard(
                        entry = entry,
                        onClick = { onOpenJob(entry.summary.job) }
                    )
                }
            }
        }
    }

    if (showBadModal) {
        HardwoodsFlaggedPartsSheet(
            title = "Bad Pieces",
            summaries = summaries.map { it.summary },
            countSelector = { it.badPieces },
            accentColor = statusColors.bad,
            onDismiss = { showBadModal = false }
        )
    }
    if (showSkippedModal) {
        HardwoodsFlaggedPartsSheet(
            title = "Skipped Pieces",
            summaries = summaries.map { it.summary },
            countSelector = { it.skippedPieces },
            accentColor = statusColors.skip,
            onDismiss = { showSkippedModal = false }
        )
    }
}

// ── Overview card ──────────────────────────────────────────────────────────────

@Composable
private fun HardwoodsOverviewCard(
    jobs: List<HardwoodJob>,
    totalCounts: HardwoodStatusCounts,
    onNavigateToJobs: () -> Unit,
    onBadClick: () -> Unit,
    onSkippedClick: () -> Unit
) {
    val colors = KKCThemeColors.statusColors
    val completionFraction = totalCounts.completionFraction
    val heroTint = if (isSystemInDarkTheme()) KKCAlpha.cardHeroTint else KKCAlpha.lightCardHeroTint
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = heroTint)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp,
            pressedElevation = 6.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KKCSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(KKCSpacing.sheetItemSpacing)
        ) {
            // Top row: circular progress + progress text details + action button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KKCSpacing.screenHorizontal),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(88.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { completionFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 8.dp,
                        color = colors.completeBorder,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round
                    )
                    Text(
                        "${(completionFraction * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(KKCSpacing.textLineGap)
                ) {
                    Text(
                        "Overall Progress",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${totalCounts.donePieces} of ${totalCounts.totalPieces} pieces",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${jobs.size} job${if (jobs.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = onNavigateToJobs,
                    modifier = Modifier.padding(start = KKCSpacing.inCardSpacing)
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "List icon")
                    Spacer(Modifier.width(KKCSpacing.tightSpacing))
                    Text("Open Jobs")
                }
            }

            // Bottom row: 3 stat cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KKCSpacing.m)
            ) {
                HardwoodsStatCard(
                    label = "Done Pieces",
                    value = "${totalCounts.donePieces}",
                    color = colors.complete,
                    modifier = Modifier.weight(1f),
                    onClick = null
                )
                HardwoodsStatCard(
                    label = "Bad Pieces",
                    value = "${totalCounts.badPieces}",
                    color = colors.bad,
                    modifier = Modifier.weight(1f),
                    onClick = if (totalCounts.badPieces > 0) onBadClick else null
                )
                HardwoodsStatCard(
                    label = "Skipped",
                    value = "${totalCounts.skippedPieces}",
                    color = colors.skip,
                    modifier = Modifier.weight(1f),
                    onClick = if (totalCounts.skippedPieces > 0) onSkippedClick else null
                )
            }
        }
    }
}

@Composable
private fun HardwoodsStatCard(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KKCSpacing.cardPaddingSmall),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(KKCSpacing.textLineGap))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Quality alert ──────────────────────────────────────────────────────────────

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
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KKCSpacing.cardPaddingCompact),
            verticalArrangement = Arrangement.spacedBy(KKCSpacing.tightSpacing)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(KKCSpacing.m),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (hasIssues) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = "Status icon",
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

// ── Recent jobs ────────────────────────────────────────────────────────────────

@Composable
private fun HardwoodsRecentJobsCard(
    recentJobs: List<Pair<HardwoodJob, Long>>,
    onOpenJob: (HardwoodJob) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(KKCSpacing.cardPaddingSmall)) {
            Text(
                "Recent Jobs (This Tablet)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = KKCSpacing.inCardSpacing)
            )
            recentJobs.forEachIndexed { index, (job, ms) ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = KKCAlpha.dividerSubtle)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenJob(job) }
                        .padding(vertical = KKCSpacing.m, horizontal = KKCSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            buildString {
                                if (job.jobNumber.isNotBlank()) {
                                    append(job.jobNumber)
                                    append(" - ")
                                }
                                append(job.jobName.ifBlank { job.folderName })
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            formatHardwoodRelativeTime(ms),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Open job",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ── Job cards ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HardwoodsJobCard(
    entry: HardwoodJobDashboardEntry,
    onClick: () -> Unit
) {
    val colors = KKCThemeColors.statusColors
    val summary = entry.summary
    val counts = summary.counts
    val status = hardwoodSummaryStatus(counts)
    val progressColor = when (status) {
        SheetStatus.HAS_BAD_PARTS -> colors.bad
        SheetStatus.SKIPPED -> colors.skipBorder
        SheetStatus.COMPLETE -> colors.completeBorder
        SheetStatus.IN_PROGRESS -> colors.inProgressBorder
        SheetStatus.NOT_STARTED -> colors.notStarted
    }

    StatusBorderedCard(
        status = status,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(KKCSpacing.cardPaddingCompact),
            verticalArrangement = Arrangement.spacedBy(KKCSpacing.inCardSpacing)
        ) {
            // Job number + name inline: "XXX - Job Name"
            Text(
                buildString {
                    if (summary.job.jobNumber.isNotBlank()) {
                        append(summary.job.jobNumber)
                        append(" - ")
                    }
                    append(summary.job.jobName.ifBlank { summary.job.folderName })
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Progress bar
            LinearProgressIndicator(
                progress = { counts.completionFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = KKCAlpha.outlineTrack)
            )

            // Doc type pills — DOOR_LIST excluded (no cut quantities); Rips sourced from board stock
            val presentDocs = summary.documents.filter {
                it.counts.totalPieces > 0 && it.docType != HardwoodDocType.DOOR_LIST
            }
            val showPills = presentDocs.isNotEmpty() || entry.ripsTotal > 0
            if (showPills) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(KKCSpacing.listItemSpacing),
                    verticalArrangement = Arrangement.spacedBy(KKCSpacing.inCardSpacing),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    presentDocs.forEach { docSummary ->
                        val docState = when {
                            docSummary.counts.donePieces >= docSummary.counts.totalPieces && docSummary.counts.totalPieces > 0 -> ProgressState.COMPLETE
                            docSummary.counts.donePieces > 0 || docSummary.counts.skippedPieces > 0 -> ProgressState.IN_PROGRESS
                            else -> ProgressState.NOT_STARTED
                        }
                        val docLabel = when (docSummary.docType) {
                            HardwoodDocType.FACE_FRAME_CUT_LIST -> "Face Frame"
                            HardwoodDocType.NAILER_CUT_LIST -> "Nailers"
                            HardwoodDocType.DOOR_CUT_LIST -> "Doors"
                            HardwoodDocType.DOOR_LIST -> "" // excluded above, never reached
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(84.dp)
                        ) {
                            ProgressPill(
                                done = docSummary.counts.donePieces,
                                total = docSummary.counts.totalPieces,
                                state = docState
                            )
                            Spacer(Modifier.height(KKCSpacing.xxs))
                            Text(
                                docLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Rips pill — board stock progress
                    if (entry.ripsTotal > 0) {
                        val ripsState = when {
                            entry.ripsDone >= entry.ripsTotal -> ProgressState.COMPLETE
                            entry.ripsDone > 0 -> ProgressState.IN_PROGRESS
                            else -> ProgressState.NOT_STARTED
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(84.dp)
                        ) {
                            ProgressPill(
                                done = entry.ripsDone,
                                total = entry.ripsTotal,
                                state = ripsState
                            )
                            Spacer(Modifier.height(KKCSpacing.xxs))
                            Text(
                                "Rips",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Piece count summary
            Row(horizontalArrangement = Arrangement.spacedBy(KKCSpacing.tightSpacing)) {
                Text(
                    "${counts.donePieces}/${counts.totalPieces} pieces",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (counts.badPieces > 0) {
                    Text(
                        "• ${counts.badPieces} bad",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.bad
                    )
                }
                if (counts.skippedPieces > 0) {
                    Text(
                        "• ${counts.skippedPieces} skipped",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.skip
                    )
                }
            }
        }
    }
}

// ── Flagged parts modal sheet ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HardwoodsFlaggedPartsSheet(
    title: String,
    summaries: List<HardwoodJobSummary>,
    countSelector: (HardwoodStatusCounts) -> Int,
    accentColor: Color,
    onDismiss: () -> Unit
) {
    val jobsWithIssues = summaries.filter { countSelector(it.counts) > 0 }
    val totalIssues = jobsWithIssues.sumOf { countSelector(it.counts) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = KKCSpacing.screenHorizontal, vertical = KKCSpacing.inCardSpacing),
            verticalArrangement = Arrangement.spacedBy(KKCSpacing.inCardSpacing)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (jobsWithIssues.isEmpty()) "None found."
                else "$totalIssues pieces across ${jobsWithIssues.size} job${if (jobsWithIssues.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(KKCSpacing.xxs))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(KKCSpacing.inCardSpacing)) {
                items(jobsWithIssues, key = { it.job.folderName }) { summary ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(KKCSpacing.cardPaddingSmall),
                            verticalArrangement = Arrangement.spacedBy(KKCSpacing.xxs)
                        ) {
                            Text(
                                summary.job.jobName.ifBlank { summary.job.folderName },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            summary.documents
                                .filter { countSelector(it.counts) > 0 }
                                .forEach { docSummary ->
                                    val docName = when (docSummary.docType) {
                                        HardwoodDocType.FACE_FRAME_CUT_LIST -> "Face Frame Cut List"
                                        HardwoodDocType.NAILER_CUT_LIST -> "Nailer Cut List"
                                        HardwoodDocType.DOOR_CUT_LIST -> "Door Cut List"
                                        HardwoodDocType.DOOR_LIST -> "Door List"
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            docName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            "${countSelector(docSummary.counts)} pieces",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = accentColor,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                        }
                    }
                }
            }
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun hardwoodSummaryStatus(counts: HardwoodStatusCounts): SheetStatus {
    return when {
        counts.badPieces > 0 -> SheetStatus.HAS_BAD_PARTS
        counts.skippedPieces > 0 && counts.donePieces <= 0 -> SheetStatus.SKIPPED
        counts.totalPieces > 0 && counts.donePieces >= counts.totalPieces -> SheetStatus.COMPLETE
        counts.donePieces > 0 || counts.skippedPieces > 0 -> SheetStatus.IN_PROGRESS
        else -> SheetStatus.NOT_STARTED
    }
}

private fun formatHardwoodRelativeTime(ms: Long): String {
    if (ms <= 0L) return ""
    val diffMs = System.currentTimeMillis() - ms
    val minutes = diffMs / 60_000
    val hours = diffMs / 3_600_000
    val days = diffMs / 86_400_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
}
