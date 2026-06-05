package com.kkc.sheettracker.ui.assembly

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.data.UiPreferencesStore
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AssemblyScanCoordinator
import com.kkc.sheettracker.data.AssemblyStateStore
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodRevisionHistory
import com.kkc.sheettracker.data.models.AssemblyJobCard
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.JobBoardGrid
import com.kkc.sheettracker.ui.components.JobBoardItem
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.HardwoodsRevisionHistorySheet
import com.kkc.sheettracker.ui.components.SortToggleBar
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.components.parseJobLabelColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.kkc.sheettracker.data.DeliveryScheduleRepository
import com.kkc.sheettracker.ui.components.DeliveryScheduleWidget
import com.kkc.sheettracker.ui.components.DeliveryScheduleDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssemblyJobsScreen(
    assemblyScanCoordinator: AssemblyScanCoordinator,
    assemblyStateStore: AssemblyStateStore,
    hardwoodsRepository: HardwoodsRepository,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    hardwoodsProgressStore: HardwoodsProgressStore,
    specialtyStateStore: SpecialtyStateStore,
    deliveryScheduleRepository: DeliveryScheduleRepository,
    specialtyProgressVersionHint: Long = 0L,
    onJobClick: (AssemblyJobCard) -> Unit,
    onOpenHardwoodsChange: (jobFolderName: String, docType: HardwoodDocType, rowId: String) -> Unit,
    onViewCoverSheet: (AssemblyJobCard) -> Unit,
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
    var boardView by rememberSaveable { mutableStateOf(uiPrefs.getBoardView("assembly")) }
    var selectedHistoryJob by rememberSaveable { mutableStateOf<String?>(null) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    LaunchedEffect(sortByName) { if (sortByName) boardView = false }
    val scanState by assemblyScanCoordinator.state.collectAsState()
    val cncProgressVersion by progressStore.progressVersion.collectAsState()
    val hardwoodProgressVersion by hardwoodsProgressStore.progressVersion.collectAsState()
    val specialtyScanState by specialtyStateStore.scanState.collectAsState()
    val specialtyProgressVersion by specialtyStateStore.progressVersion.collectAsState()
    val deliverySchedule = remember(scanState.snapshot.generation) {
        deliveryScheduleRepository.fetchSchedule()
    }

    val allCards = remember(scanState.snapshot.generation, cncProgressVersion, hardwoodProgressVersion) {
        assemblyStateStore.deriveJobCards()
    }
    val specialtyCards = remember(specialtyScanState.snapshot.generation, specialtyProgressVersion, specialtyProgressVersionHint) {
        specialtyStateStore.deriveJobCards()
    }
    val specialtySummary = remember(specialtyCards) {
        val total = specialtyCards.sumOf { it.totalItems }
        val complete = specialtyCards.sumOf { it.completedItems }
        complete to total
    }
    val filtered = remember(allCards, query, sortByName) {
        val base = if (query.isBlank()) {
            allCards
        } else {
            allCards.filter {
                it.jobNumber.contains(query, ignoreCase = true) ||
                    it.jobName.contains(query, ignoreCase = true) ||
                    it.folderName.contains(query, ignoreCase = true)
            }
        }
        if (sortByName) {
            base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
        } else {
            base // already in production order from listJobs
        }
    }

    val assemblyUiStates = remember(filtered, scanState.snapshot.generation, hardwoodProgressVersion) {
        filtered.map { card ->
            val hasDeliverySheet = jobRepository.getJobPdfCatalog(card.folderName).deliverySheet != null
            val hasHistory = hardwoodsRepository.loadHardwoodsRevisionHistory(card.folderName) != null
            val cncCounts = card.toCncStatusCounts()
            val hardwoodCounts = card.toHardwoodsStatusCounts()
            val combinedCounts = combineCounts(cncCounts, hardwoodCounts)
            val subtitle = "CNC ${card.cncSummary.completedSheets}/${card.cncSummary.totalSheets} • " +
                "Hardwoods ${card.hardwoodsSummary.donePieces}/${card.hardwoodsSummary.totalPieces}"
            AssemblyJobItemUiState(
                card = card,
                hasDeliverySheet = hasDeliverySheet,
                hasHistory = hasHistory,
                cncCounts = cncCounts,
                hardwoodCounts = hardwoodCounts,
                combinedCounts = combinedCounts,
                subtitle = subtitle
            )
        }
    }

    LaunchedEffect(Unit) {
        assemblyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KKC Sheet Tracker - Assembly") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                windowInsets = WindowInsets.statusBars,
                actions = {
                    IconButton(onClick = { assemblyScanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    IconButton(
                        onClick = {
                            boardView = !boardView
                            uiPrefs.setBoardView("assembly", boardView)
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
            Text(
                text = if (query.isBlank()) {
                    "${filtered.size} jobs"
                } else {
                    "Showing ${filtered.size} of ${allCards.size} jobs"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            SortToggleBar(sortByName = sortByName, onSortChange = { sortByName = it })
            Text(
                text = when (specialtyScanState.status) {
                    ScanStatus.LOADING -> "Specialty: scanning..."
                    ScanStatus.ERROR -> "Specialty: ${specialtyScanState.errorMessage ?: "scan failed"}"
                    else -> {
                        val (complete, total) = specialtySummary
                        "Specialty: $complete / $total items complete across ${specialtyCards.size} jobs"
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (specialtyScanState.status == ScanStatus.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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
            when {
                scanState.status == ScanStatus.LOADING && allCards.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                filtered.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No jobs found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                isBoardView -> {
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
                }
                else -> {
                    val activeUiStates  = assemblyUiStates.filter { it.card.boardSection == 0 }
                    val pendingUiStates = assemblyUiStates.filter { it.card.boardSection == 1 }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(activeUiStates, key = { "active_${it.card.folderName}" }) { uiState ->
                            val card = uiState.card
                            val hasDeliverySheet = uiState.hasDeliverySheet
                            val hasHistory = uiState.hasHistory
                            val cncCounts = uiState.cncCounts
                            val hardwoodCounts = uiState.hardwoodCounts
                            val combinedCounts = uiState.combinedCounts
                            val subtitle = uiState.subtitle
                            ProgressCard(
                                title = card.folderName,
                                subtitle = subtitle,
                                fraction = if (combinedCounts.total <= 0) 0f else combinedCounts.complete.toFloat() / combinedCounts.total.toFloat(),
                                expanded = false,
                                onToggleExpanded = {},
                                onClick = { onJobClick(card) },
                                hidePrimaryProgressBar = true,
                                showExpandToggle = false,
                                headerActions = {
                                    card.labels.forEach { label ->
                                        StatusChip(
                                            text = label.name,
                                            backgroundColor = parseJobLabelColor(label.colorHex),
                                            contentColor = Color.White
                                        )
                                    }
                                    if (sortByName) {
                                        val pos = card.lineupPosition
                                        if (pos != null) {
                                            StatusChip(
                                                text = "#$pos",
                                                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    if (card.hiddenFromProduction) {
                                        StatusChip(
                                            text = "Hidden in Production",
                                            backgroundColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    if (hasDeliverySheet) {
                                        FilterChip(
                                            selected = false,
                                            onClick = { onViewCoverSheet(card) },
                                            label = { Text("Cover Sheet") }
                                        )
                                    }
                                    if (hasHistory) {
                                        TextButton(onClick = { selectedHistoryJob = card.folderName }) {
                                            Text("History")
                                        }
                                    }
                                },
                                inlineContent = {
                                    DualModeProgressBars(
                                        cncCounts = cncCounts,
                                        hardwoodCounts = hardwoodCounts
                                    )
                                }
                            )
                        }
                        if (pendingUiStates.isNotEmpty()) {
                            item(key = "pending_header") {
                                Text(
                                    text = "Pending Delivery",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(horizontal = 4.dp, vertical = 8.dp)
                                )
                            }
                            items(pendingUiStates, key = { "pending_${it.card.folderName}" }) { uiState ->
                                val card = uiState.card
                                val hasDeliverySheet = uiState.hasDeliverySheet
                                val hasHistory = uiState.hasHistory
                                val cncCounts = uiState.cncCounts
                                val hardwoodCounts = uiState.hardwoodCounts
                                val combinedCounts = uiState.combinedCounts
                                val subtitle = uiState.subtitle
                                ProgressCard(
                                    title = card.folderName,
                                    subtitle = subtitle,
                                    fraction = if (combinedCounts.total <= 0) 0f else combinedCounts.complete.toFloat() / combinedCounts.total.toFloat(),
                                    expanded = false,
                                    onToggleExpanded = {},
                                    onClick = { onJobClick(card) },
                                    hidePrimaryProgressBar = true,
                                    showExpandToggle = false,
                                    headerActions = {
                                        card.labels.forEach { label ->
                                            StatusChip(
                                                text = label.name,
                                                backgroundColor = parseJobLabelColor(label.colorHex),
                                                contentColor = Color.White
                                            )
                                        }
                                        if (card.hiddenFromProduction) {
                                            StatusChip(
                                                text = "Hidden in Production",
                                                backgroundColor = MaterialTheme.colorScheme.errorContainer,
                                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                        if (hasDeliverySheet) {
                                            FilterChip(
                                                selected = false,
                                                onClick = { onViewCoverSheet(card) },
                                                label = { Text("Cover Sheet") }
                                            )
                                        }
                                    },
                                    inlineContent = {
                                        DualModeProgressBars(
                                            cncCounts = cncCounts,
                                            hardwoodCounts = hardwoodCounts
                                        )
                                    }
                                )
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
        val history by produceState<HardwoodRevisionHistory?>(
            initialValue = null,
            key1 = scanState.snapshot.generation,
            key2 = hardwoodProgressVersion,
            key3 = historyJob
        ) {
            value = withContext(Dispatchers.IO) {
                hardwoodsRepository.loadHardwoodsRevisionHistory(historyJob)
            }
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

private fun AssemblyJobCard.toCncStatusCounts(): StatusCounts {
    return normalizeCounts(
        total = cncSummary.totalSheets,
        complete = cncSummary.completedSheets,
        bad = cncSummary.badPartsSheets,
        skipped = cncSummary.skippedSheets
    )
}

@Composable
private fun DualModeProgressBars(
    cncCounts: StatusCounts,
    hardwoodCounts: StatusCounts
) {
    val cncColor = Color(0xFF2B6CB0)
    val hardwoodDone = Color(0xFF2F855A)
    val hardwoodBad = Color(0xFFC05621)
    val hardwoodSkipped = Color(0xFF718096)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "CNC",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = {
                    if (cncCounts.total <= 0) 0f else cncCounts.complete.toFloat() / cncCounts.total.toFloat()
                },
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(8.dp),
                color = cncColor,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Hardwoods",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SegmentedHardwoodsBar(
                counts = hardwoodCounts,
                doneColor = hardwoodDone,
                badColor = hardwoodBad,
                skippedColor = hardwoodSkipped
            )
        }
    }
}

@Composable
private fun SegmentedHardwoodsBar(
    counts: StatusCounts,
    doneColor: Color,
    badColor: Color,
    skippedColor: Color
) {
    val total = counts.total.coerceAtLeast(0)
    val done = counts.complete.coerceIn(0, total)
    val bad = counts.bad.coerceIn(0, (total - done).coerceAtLeast(0))
    val skipped = counts.skipped.coerceIn(0, (total - done - bad).coerceAtLeast(0))
    val remaining = (total - done - bad - skipped).coerceAtLeast(0)

    if (total <= 0) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(999.dp)
        ) {}
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(999.dp))
    ) {
        if (done > 0) {
            Surface(
                modifier = Modifier
                    .weight(done.toFloat())
                    .fillMaxSize(),
                color = doneColor
            ) {}
        }
        if (bad > 0) {
            Surface(
                modifier = Modifier
                    .weight(bad.toFloat())
                    .fillMaxSize(),
                color = badColor
            ) {}
        }
        if (skipped > 0) {
            Surface(
                modifier = Modifier
                    .weight(skipped.toFloat())
                    .fillMaxSize(),
                color = skippedColor
            ) {}
        }
        if (remaining > 0) {
            Surface(
                modifier = Modifier
                    .weight(remaining.toFloat())
                    .fillMaxSize(),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            ) {}
        }
    }
}

private fun AssemblyJobCard.toHardwoodsStatusCounts(): StatusCounts {
    return normalizeCounts(
        total = hardwoodsSummary.totalPieces,
        complete = hardwoodsSummary.donePieces,
        bad = hardwoodsSummary.badPieces,
        skipped = hardwoodsSummary.skippedPieces
    )
}

private fun combineCounts(a: StatusCounts, b: StatusCounts): StatusCounts {
    val total = a.total + b.total
    val complete = a.complete + b.complete
    val bad = a.bad + b.bad
    val skipped = a.skipped + b.skipped
    val notStarted = (total - complete - bad - skipped).coerceAtLeast(0)
    return StatusCounts(
        total = total,
        complete = complete,
        bad = bad,
        skipped = skipped,
        notStarted = notStarted
    )
}

private fun normalizeCounts(total: Int, complete: Int, bad: Int, skipped: Int): StatusCounts {
    val safeTotal = total.coerceAtLeast(0)
    val safeComplete = complete.coerceIn(0, safeTotal)
    val remainingAfterComplete = (safeTotal - safeComplete).coerceAtLeast(0)
    val safeBad = bad.coerceIn(0, remainingAfterComplete)
    val remainingAfterBad = (remainingAfterComplete - safeBad).coerceAtLeast(0)
    val safeSkipped = skipped.coerceIn(0, remainingAfterBad)
    val notStarted = (safeTotal - safeComplete - safeBad - safeSkipped).coerceAtLeast(0)
    return StatusCounts(
        total = safeTotal,
        complete = safeComplete,
        bad = safeBad,
        skipped = safeSkipped,
        notStarted = notStarted
    )
}

data class AssemblyJobItemUiState(
    val card: AssemblyJobCard,
    val hasDeliverySheet: Boolean,
    val hasHistory: Boolean,
    val cncCounts: StatusCounts,
    val hardwoodCounts: StatusCounts,
    val combinedCounts: StatusCounts,
    val subtitle: String
)
