package com.kkc.sheettracker.ui.hardwoods

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.data.UiPreferencesStore
import android.content.res.Configuration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.DeliveryScheduleRepository
import com.kkc.sheettracker.ui.components.JobBoardGrid
import com.kkc.sheettracker.ui.components.JobBoardItem
import com.kkc.sheettracker.ui.components.parseJobLabelColor
import com.kkc.sheettracker.ui.components.MaterialSegmentData
import com.kkc.sheettracker.ui.components.HardwoodsRevisionHistorySheet
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.components.SortToggleBar
import com.kkc.sheettracker.ui.components.StatusSummaryRow
import com.kkc.sheettracker.ui.components.DeliveryScheduleWidget
import com.kkc.sheettracker.ui.components.DeliveryScheduleDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwoodsJobsScreen(
    scanCoordinator: HardwoodsScanCoordinator,
    hardwoodsRepository: HardwoodsRepository,
    progressStore: HardwoodsProgressStore,
    jobRepository: JobRepository,
    deliveryScheduleRepository: DeliveryScheduleRepository,
    onJobClick: (HardwoodJob) -> Unit,
    onOpenHardwoodsChange: (jobFolderName: String, docType: HardwoodDocType, rowId: String) -> Unit,
    onViewCoverSheet: (HardwoodJob) -> Unit,
    onView3D: (HardwoodJob) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val serverGridCols = remember { jobRepository.getBoardGridColumns() }
    val orientation = LocalConfiguration.current.orientation
    val gridCols = if (orientation == Configuration.ORIENTATION_LANDSCAPE) serverGridCols
                   else minOf(serverGridCols, 3)
    val context = LocalContext.current
    val uiPrefs = remember { UiPreferencesStore(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortByName by rememberSaveable { mutableStateOf(false) }
    var boardView by rememberSaveable { mutableStateOf(uiPrefs.getBoardView("hardwoods")) }
    var expandedJobs by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selectedHistoryJob by rememberSaveable { mutableStateOf<String?>(null) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    LaunchedEffect(sortByName) { if (sortByName) boardView = false }
    val scanState by scanCoordinator.state.collectAsState()
    val progressVersion by progressStore.progressVersion.collectAsState()
    val jobs = scanState.snapshot.jobs
    val loading = scanState.status == ScanStatus.LOADING && jobs.isEmpty()
    val deliverySchedule = remember(scanState.snapshot.generation) {
        deliveryScheduleRepository.fetchSchedule()
    }

    // Badge cache: keyed by folderName, populated off the main thread.
    // Cleared on each new scan generation so stale data never lingers.
    val badgeCache = remember(scanState.snapshot.generation) { mutableStateMapOf<String, HardwoodsJobBadgeState>() }

    val filtered = remember(jobs, query, sortByName) {
        val base = if (query.isBlank()) jobs else jobs.filter {
            it.jobNumber.contains(query, ignoreCase = true) ||
                it.jobName.contains(query, ignoreCase = true) ||
                it.folderName.contains(query, ignoreCase = true)
        }
        if (sortByName) {
            base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
        } else {
            base // already in production order from listJobs
        }
    }

    // Load disk-I/O badge data per job off the main thread.
    filtered.forEach { job ->
        val generation = scanState.snapshot.generation
        LaunchedEffect(job.folderName, generation) {
            if (badgeCache.containsKey(job.folderName)) return@LaunchedEffect
            badgeCache[job.folderName] = withContext(Dispatchers.IO) {
                val hasDelivery = jobRepository.getJobPdfCatalog(job.folderName).deliverySheet != null
                val has3D = jobRepository.hasThreeDAssets(job.folderName)
                val history = hardwoodsRepository.loadHardwoodsRevisionHistory(job.folderName)
                HardwoodsJobBadgeState(hasDeliverySheet = hasDelivery, hasThreeDAssets = has3D, history = history)
            }
        }
    }

    val hardwoodsUiStates = remember(filtered, scanState.snapshot.generation, progressVersion, scanState.snapshot.basePath) {
        filtered.map { job ->
            val summary = progressStore.summarizeJob(job)
            val availableDocTypes = job.index?.documents
                .orEmpty()
                .filter { doc ->
                    doc.pdfFilename.isNotBlank() &&
                        jobRepository.getJobRootPdfFile(
                            jobFolderName = job.folderName,
                            pdfFilename = doc.pdfFilename,
                            preferDarkMode = false
                        ) != null
                }
                .map { it.docType }
                .toSet()
            val totalsDoneMap = progressStore.getTotalsRip10DoneMap(job.folderName)
            val rowProgressMap = progressStore.getRowProgressMap(job.folderName)
            val includedDocSummaries = summary.documents.filter {
                it.docType != com.kkc.sheettracker.data.models.HardwoodDocType.DOOR_LIST &&
                    it.docType in availableDocTypes
            }
            val includedCounts = includedDocSummaries.fold(HardwoodStatusCounts()) { acc, doc ->
                HardwoodStatusCounts(
                    totalPieces = acc.totalPieces + doc.counts.totalPieces,
                    donePieces = acc.donePieces + doc.counts.donePieces,
                    badPieces = acc.badPieces + doc.counts.badPieces,
                    skippedPieces = acc.skippedPieces + doc.counts.skippedPieces
                )
            }
            val boardStockRows = buildBoardStockRows(scanState.snapshot.basePath, job.folderName, job.index)
            val rows = applySkippedPartRowsToBoardStockRows(
                rows = boardStockRows,
                index = job.index,
                rowProgressMap = rowProgressMap
            )
            val boardStockTotal = rows.sumOf { row ->
                val materialSkippedKey = progressStore.makeBoardStockMaterialSkipKey(row.material)
                val lineSkippedKey = progressStore.makeBoardStockRipSkipKey(row.material, row.normalizedWidth, row.source.name)
                val skipped = (totalsDoneMap[materialSkippedKey] ?: 0) > 0 || (totalsDoneMap[lineSkippedKey] ?: 0) > 0
                if (skipped) 0 else row.neededRips.coerceAtLeast(0)
            }
            val boardStockDone = rows.sumOf { row ->
                val key = progressStore.makeBoardStockTallyKey(row.material, row.normalizedWidth, row.source.name)
                val materialSkippedKey = progressStore.makeBoardStockMaterialSkipKey(row.material)
                val lineSkippedKey = progressStore.makeBoardStockRipSkipKey(row.material, row.normalizedWidth, row.source.name)
                val skipped = (totalsDoneMap[materialSkippedKey] ?: 0) > 0 || (totalsDoneMap[lineSkippedKey] ?: 0) > 0
                if (skipped) 0 else (totalsDoneMap[key] ?: 0).coerceIn(0, row.neededRips.coerceAtLeast(0))
            }
            val boardStockCounts = HardwoodStatusCounts(totalPieces = boardStockTotal, donePieces = boardStockDone)
            val counts = HardwoodStatusCounts(
                totalPieces = includedCounts.totalPieces + boardStockCounts.totalPieces,
                donePieces = includedCounts.donePieces + boardStockCounts.donePieces,
                badPieces = includedCounts.badPieces,
                skippedPieces = includedCounts.skippedPieces
            )
            val docCount = includedDocSummaries.size
            val docSegments = includedDocSummaries.map {
                MaterialSegmentData(
                    materialName = it.docType.uiLabel(),
                    counts = it.counts.toStatusCounts()
                )
            } + MaterialSegmentData(
                materialName = "Rip Cut List",
                counts = boardStockCounts.toStatusCounts()
            )
            HardwoodsJobItemUiState(
                job = job,
                counts = counts,
                docCount = docCount,
                docSegments = docSegments,
                availableDocTypes = availableDocTypes
            )
        }
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
                    IconButton(
                        onClick = {
                            boardView = !boardView
                            uiPrefs.setBoardView("hardwoods", boardView)
                        },
                        enabled = !sortByName
                    ) {
                        Icon(
                            imageVector = if (boardView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = if (boardView) "List View" else "Board View"
                        )
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
            SortToggleBar(sortByName = sortByName, onSortChange = { sortByName = it })
            Text(
                text = if (query.isBlank()) {
                    "${filtered.size} jobs"
                } else {
                    "Showing ${filtered.size} of ${jobs.size} jobs"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            DeliveryScheduleWidget(
                schedule = deliverySchedule,
                onTap = { showScheduleDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            AnimatedContent(
                targetState = sortByName to boardView,
                transitionSpec = {
                    val dir = if (targetState.first) 1 else -1
                    slideInHorizontally { it * dir } togetherWith slideOutHorizontally { -it * dir }
                },
                label = "sort_anim"
            ) { (_, isBoardView) ->
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
            } else if (isBoardView) {
                val activeBoard  = filtered.filter { it.boardSection == 0 }
                val pendingBoard = filtered.filter { it.boardSection == 1 }
                JobBoardGrid(
                    items = activeBoard.map { JobBoardItem(it.folderName, it.jobNumber, it.jobName, it.labels) },
                    pendingItems = pendingBoard.map { JobBoardItem(it.folderName, it.jobNumber, it.jobName, it.labels) },
                    jobRepository = jobRepository,
                    onItemClick = { boardItem ->
                        filtered.find { it.folderName == boardItem.folderName }
                            ?.let { onJobClick(it) }
                    },
                    modifier = Modifier.fillMaxSize(),
                    columns = gridCols,
                    scanGeneration = scanState.snapshot.generation
                )
            } else {
                val activeUiStates  = hardwoodsUiStates.filter { it.job.boardSection == 0 }
                val pendingUiStates = hardwoodsUiStates.filter { it.job.boardSection == 1 }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(activeUiStates, key = { "active_${it.job.folderName}" }) { uiState ->
                        val job = uiState.job
                        val badge = badgeCache[job.folderName]
                        val counts = uiState.counts
                        val docCount = uiState.docCount
                        val docSegments = uiState.docSegments
                        val availableDocTypes = uiState.availableDocTypes
                        val subtitle = "${counts.donePieces}/${counts.effectiveTotalPieces} done"

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
                            headerActions = {
                                job.labels.forEach { label ->
                                    StatusChip(
                                        text = label.name,
                                        backgroundColor = parseJobLabelColor(label.colorHex),
                                        contentColor = Color.White
                                    )
                                }
                                if (sortByName) {
                                    val pos = job.lineupPosition
                                    if (pos != null) {
                                        StatusChip(
                                            text = "#$pos",
                                            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                                if (job.hiddenFromProduction) {
                                    StatusChip(
                                        text = "Hidden in Production",
                                        backgroundColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                if (badge?.history != null) {
                                    TextButton(onClick = { selectedHistoryJob = job.folderName }) {
                                        Text("History")
                                    }
                                }
                            },
                            inlineContent = {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (badge?.hasDeliverySheet == true) {
                                        FilterChip(
                                            selected = false,
                                            onClick = { onViewCoverSheet(job) },
                                            label = { Text("Cover Sheet") }
                                        )
                                    }
                                    if (badge?.hasThreeDAssets == true) {
                                        FilterChip(
                                            selected = false,
                                            onClick = { onView3D(job) },
                                            label = { Text("View 3D") }
                                        )
                                    }
                                }
                            },
                            onClick = { onJobClick(job) }
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusSummaryRow(counts.toStatusCounts())
                                Text(
                                    "Cutlists: $docCount + Rip Cut List",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    if (pendingUiStates.isNotEmpty()) {
                        item(key = "pending_header") {
                            Text(
                                text = "Pending Delivery",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(horizontal = 4.dp, vertical = 8.dp)
                            )
                        }
                        items(pendingUiStates, key = { "pending_${it.job.folderName}" }) { uiState ->
                            val job = uiState.job
                            val badge = badgeCache[job.folderName]
                            val counts = uiState.counts
                            val docCount = uiState.docCount
                            val docSegments = uiState.docSegments
                            val availableDocTypes = uiState.availableDocTypes
                            val subtitle = "${counts.donePieces}/${counts.effectiveTotalPieces} done"

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
                                headerActions = {
                                    job.labels.forEach { label ->
                                        StatusChip(
                                            text = label.name,
                                            backgroundColor = parseJobLabelColor(label.colorHex),
                                            contentColor = Color.White
                                        )
                                    }
                                    if (sortByName) {
                                        val pos = job.lineupPosition
                                        if (pos != null) {
                                            StatusChip(
                                                text = "#$pos",
                                                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    if (job.hiddenFromProduction) {
                                        StatusChip(
                                            text = "Hidden in Production",
                                            backgroundColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    if (badge?.history != null) {
                                        TextButton(onClick = { selectedHistoryJob = job.folderName }) {
                                            Text("History")
                                        }
                                    }
                                },
                                inlineContent = {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (badge?.hasDeliverySheet == true) {
                                            FilterChip(
                                                selected = false,
                                                onClick = { onViewCoverSheet(job) },
                                                label = { Text("Cover Sheet") }
                                            )
                                        }
                                        if (badge?.hasThreeDAssets == true) {
                                            FilterChip(
                                                selected = false,
                                                onClick = { onView3D(job) },
                                                label = { Text("View 3D") }
                                            )
                                        }
                                    }
                                },
                                onClick = { onJobClick(job) }
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StatusSummaryRow(counts.toStatusCounts())
                                    Text(
                                        "Cutlists: $docCount + Rip Cut List",
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
    }

    val historyJob = selectedHistoryJob
    if (historyJob != null) {
        val history = remember(scanState.snapshot.generation, progressVersion, historyJob) {
            hardwoodsRepository.loadHardwoodsRevisionHistory(historyJob)
        }
        HardwoodsRevisionHistorySheet(
            jobFolderName = historyJob,
            history = history,
            onOpenRow = { docTypeRaw, rowId ->
                val docType = runCatching { HardwoodDocType.valueOf(docTypeRaw) }.getOrNull()
                if (docType != null) {
                    selectedHistoryJob = null
                    onOpenHardwoodsChange(historyJob, docType, rowId)
                }
            },
            onDismiss = { selectedHistoryJob = null }
        )
    }

    if (showScheduleDialog) {
        DeliveryScheduleDialog(
            schedule = deliverySchedule,
            onDismiss = { showScheduleDialog = false }
        )
    }
}

internal fun HardwoodStatusCounts.toStatusCounts(): StatusCounts {
    val effectiveTotal = effectiveTotalPieces
    val complete = (donePieces + badPieces).coerceAtMost(effectiveTotal)
    val notStarted = (effectiveTotal - complete).coerceAtLeast(0)
    return StatusCounts(
        total = effectiveTotal,
        complete = complete,
        bad = badPieces,
        skipped = skippedPieces,
        notStarted = notStarted
    )
}

internal data class HardwoodsJobBadgeState(
    val hasDeliverySheet: Boolean,
    val hasThreeDAssets: Boolean,
    val history: com.kkc.sheettracker.data.models.HardwoodRevisionHistory?,
)

data class HardwoodsJobItemUiState(
    val job: HardwoodJob,
    val counts: com.kkc.sheettracker.data.models.HardwoodStatusCounts,
    val docCount: Int,
    val docSegments: List<MaterialSegmentData>,
    val availableDocTypes: Set<HardwoodDocType>
)
