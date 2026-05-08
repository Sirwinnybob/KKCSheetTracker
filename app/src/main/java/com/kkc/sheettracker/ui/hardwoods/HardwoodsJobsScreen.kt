package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.MaterialSegmentData
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.StatusSummaryRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwoodsJobsScreen(
    scanCoordinator: HardwoodsScanCoordinator,
    progressStore: HardwoodsProgressStore,
    jobRepository: JobRepository,
    onJobClick: (HardwoodJob) -> Unit,
    onViewCoverSheet: (HardwoodJob) -> Unit,
    onView3D: (HardwoodJob) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var expandedJobs by rememberSaveable { mutableStateOf(setOf<String>()) }
    val scanState by scanCoordinator.state.collectAsState()
    val progressVersion by progressStore.progressVersion.collectAsState()
    val jobs = scanState.snapshot.jobs
    val loading = scanState.status == ScanStatus.LOADING && jobs.isEmpty()

    val filtered = remember(jobs, query) {
        val base = if (query.isBlank()) jobs else jobs.filter {
            it.jobNumber.contains(query, ignoreCase = true) ||
                it.jobName.contains(query, ignoreCase = true) ||
                it.folderName.contains(query, ignoreCase = true)
        }
        base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
    }

    LaunchedEffect(Unit) {
        scanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KKC Sheet Tracker - Hardwoods") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    IconButton(onClick = onSearchClick) { Icon(Icons.Default.Search, "Search") }
                    IconButton(onClick = onSettingsClick) { Icon(Icons.Default.Settings, "Settings") }
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

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No jobs found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filtered, key = { it.folderName }) { job ->
                        val hasDeliverySheet = remember(job.folderName) {
                            jobRepository.getJobPdfCatalog(job.folderName).deliverySheet != null
                        }
                        val summary = remember(progressVersion, job.index) {
                            progressStore.summarizeJob(job)
                        }
                        val totalsDoneMap = remember(progressVersion, job.folderName) {
                            progressStore.getTotalsRip10DoneMap(job.folderName)
                        }
                        val includedDocSummaries = summary.documents.filter { it.docType != com.kkc.sheettracker.data.models.HardwoodDocType.DOOR_LIST }
                        val includedCounts = includedDocSummaries.fold(HardwoodStatusCounts()) { acc, doc ->
                            HardwoodStatusCounts(
                                totalPieces = acc.totalPieces + doc.counts.totalPieces,
                                donePieces = acc.donePieces + doc.counts.donePieces,
                                badPieces = acc.badPieces + doc.counts.badPieces,
                                skippedPieces = acc.skippedPieces + doc.counts.skippedPieces
                            )
                        }
                        val boardStockCounts = remember(job.index, totalsDoneMap, scanState.snapshot.basePath) {
                            val rows = buildBoardStockRows(scanState.snapshot.basePath, job.folderName, job.index)
                            val total = rows.sumOf { it.neededRips.coerceAtLeast(0) }
                            val done = rows.sumOf { row ->
                                val key = progressStore.makeBoardStockTallyKey(row.material, row.normalizedWidth, row.source.name)
                                (totalsDoneMap[key] ?: 0).coerceIn(0, row.neededRips.coerceAtLeast(0))
                            }
                            HardwoodStatusCounts(totalPieces = total, donePieces = done)
                        }
                        val counts = HardwoodStatusCounts(
                            totalPieces = includedCounts.totalPieces + boardStockCounts.totalPieces,
                            donePieces = includedCounts.donePieces + boardStockCounts.donePieces,
                            badPieces = includedCounts.badPieces,
                            skippedPieces = includedCounts.skippedPieces
                        )
                        val docCount = includedDocSummaries.size
                        val subtitle = buildString {
                            append("${counts.donePieces}/${counts.totalPieces} done")
                            if (docCount < 3) append(" • ${3 - docCount} docs missing")
                        }
                        val docSegments = includedDocSummaries.map {
                            MaterialSegmentData(
                                materialName = it.docType.uiLabel(),
                                counts = it.counts.toStatusCounts()
                            )
                        } + MaterialSegmentData(
                            materialName = "Rip Cut List",
                            counts = boardStockCounts.toStatusCounts()
                        )

                        ProgressCard(
                            title = job.folderName,
                            subtitle = subtitle,
                            fraction = counts.completionFraction,
                            expanded = job.folderName in expandedJobs,
                            onToggleExpanded = {
                                expandedJobs = if (job.folderName in expandedJobs) {
                                    expandedJobs - job.folderName
                                } else {
                                    expandedJobs + job.folderName
                                }
                            },
                            segmentedStatusCounts = counts.toStatusCounts(),
                            materialSegments = docSegments,
                            showBottomProgressBar = true,
                            inlineContent = {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (hasDeliverySheet) {
                                        FilterChip(
                                            selected = false,
                                            onClick = { onViewCoverSheet(job) },
                                            label = { Text("Cover Sheet") }
                                        )
                                    }
                                    FilterChip(
                                        selected = false,
                                        onClick = { onView3D(job) },
                                        label = { Text("View 3D") }
                                    )
                                }
                            },
                            onClick = { onJobClick(job) }
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusSummaryRow(counts.toStatusCounts())
                                Text(
                                    "Cutlists: ${docCount}/3 + Rip Cut List",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun HardwoodStatusCounts.toStatusCounts(): StatusCounts {
    val complete = donePieces + badPieces
    val notStarted = (totalPieces - complete - skippedPieces).coerceAtLeast(0)
    return StatusCounts(
        total = totalPieces,
        complete = complete,
        bad = badPieces,
        skipped = skippedPieces,
        notStarted = notStarted
    )
}
