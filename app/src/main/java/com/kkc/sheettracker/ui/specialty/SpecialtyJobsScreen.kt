package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.ui.components.PinButton
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.data.UiPreferencesStore
import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.SpecialtyScanCoordinator
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.StationProgress
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.DeliveryScheduleRepository
import com.kkc.sheettracker.ui.components.JobBoardGrid
import com.kkc.sheettracker.ui.components.JobBoardItem
import com.kkc.sheettracker.ui.components.TopBarClock
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.SortToggleBar
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.components.parseJobLabelColor
import com.kkc.sheettracker.ui.components.DeliveryScheduleWidget
import com.kkc.sheettracker.ui.components.DeliveryScheduleDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecialtyJobsScreen(
    specialtyScanCoordinator: SpecialtyScanCoordinator,
    specialtyStateStore: SpecialtyStateStore,
    jobRepository: JobRepository,
    deliveryScheduleRepository: DeliveryScheduleRepository,
    pinnedFolderNames: List<String> = emptyList(),
    onTogglePin: (folderName: String, isCurrentlyPinned: Boolean) -> Unit = { _, _ -> },
    onJobClick: (com.kkc.sheettracker.data.models.SpecialtyJobCard) -> Unit,
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
    var boardView by rememberSaveable { mutableStateOf(uiPrefs.getBoardView("specialty")) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    LaunchedEffect(sortByName) { if (sortByName) boardView = false }
    val scanState by specialtyStateStore.scanState.collectAsState()
    val progressVersion by specialtyStateStore.progressVersion.collectAsState()
    val deliverySchedule = remember(scanState.snapshot.generation) {
        deliveryScheduleRepository.fetchSchedule()
    }
    val cards = remember(scanState.snapshot.generation, progressVersion, sortByName) {
        val all = specialtyStateStore.deriveJobCards()
        if (sortByName) {
            all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
        } else {
            all // already in production order from listJobs
        }
    }
    val filteredCards = remember(cards, query) {
        if (query.isBlank()) cards
        else cards.filter { card ->
            card.jobNumber.contains(query, ignoreCase = true) ||
                card.jobName.contains(query, ignoreCase = true) ||
                card.folderName.contains(query, ignoreCase = true)
        }
    }

    val positionMap = remember(filteredCards) {
        filteredCards.mapIndexed { i, card -> card.folderName to (i + 1) }.toMap()
    }
    val pinnedCards = remember(pinnedFolderNames, filteredCards) {
        pinnedFolderNames.mapNotNull { folder -> filteredCards.find { it.folderName == folder } }
    }

    LaunchedEffect(Unit) {
        specialtyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.headerBackground(),
                title = {
                    Text(
                        "KKC Dashboard - Specialty",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                windowInsets = WindowInsets.statusBars,
                actions = {
                    IconButton(onClick = { specialtyScanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(
                        onClick = {
                            boardView = !boardView
                            uiPrefs.setBoardView("specialty", boardView)
                        },
                        enabled = !sortByName
                    ) {
                        Icon(
                            imageVector = if (boardView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = if (boardView) "List View" else "Board View"
                        )
                    }
                    TopBarClock()
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
                    "${filteredCards.size} jobs"
                } else {
                    "Showing ${filteredCards.size} of ${cards.size} jobs"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            SortToggleBar(sortByName = sortByName, onSortChange = { sortByName = it })

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
                isBoardView -> {
                    val activeBoard  = filteredCards.filter { it.boardSection == 0 }
                    val pendingBoard = filteredCards.filter { it.boardSection == 1 }
                    JobBoardGrid(
                        items = activeBoard.map { JobBoardItem(it.folderName, it.jobNumber, it.jobName, it.labels) },
                        pendingItems = pendingBoard.map { JobBoardItem(it.folderName, it.jobNumber, it.jobName, it.labels) },
                        jobRepository = jobRepository,
                        onItemClick = { boardItem ->
                            filteredCards.find { it.folderName == boardItem.folderName }
                                ?.let { onJobClick(it) }
                        },
                        modifier = Modifier.fillMaxSize(),
                        columns = gridCols,
                        scanGeneration = scanState.snapshot.generation
                    )
                }
                else -> {
                    val activeCards  = filteredCards.filter { it.boardSection == 0 }
                    val pendingCards = filteredCards.filter { it.boardSection == 1 }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 112.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (pinnedCards.isNotEmpty()) {
                            item(key = "pinned_header") {
                                Text(
                                    "Pinned",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                                )
                            }
                            items(pinnedCards, key = { "pinned_${it.folderName}" }) { card ->
                                val statusCounts = remember(card.totalItems, card.completedItems) {
                                    StatusCounts(
                                        total = card.totalItems.coerceAtLeast(0),
                                        complete = card.completedItems.coerceIn(0, card.totalItems.coerceAtLeast(0)),
                                        bad = 0,
                                        skipped = 0,
                                        notStarted = (card.totalItems - card.completedItems).coerceAtLeast(0)
                                    )
                                }
                                val pos = positionMap[card.folderName]
                                val posLabel = if (pos != null) "$pos of ${filteredCards.size}" else null
                                ProgressCard(
                                    title = card.folderName,
                                    subtitle = "${card.completedItems}/${card.totalItems} complete",
                                    fraction = card.completionFraction,
                                    expanded = false,
                                    onToggleExpanded = {},
                                    onClick = { onJobClick(card) },
                                    showBottomProgressBar = false,
                                    hidePrimaryProgressBar = true,
                                    segmentedStatusCounts = statusCounts,
                                    showExpandToggle = false,
                                    headerActions = {
                                        if (posLabel != null) {
                                            StatusChip(
                                                text = posLabel,
                                                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                        card.labels.forEach { label ->
                                            StatusChip(
                                                text = label.name,
                                                backgroundColor = parseJobLabelColor(label.colorHex),
                                                contentColor = Color.White
                                            )
                                        }
                                        PinButton(isPinned = true, onClick = { onTogglePin(card.folderName, true) })
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
                                            else -> {
                                                val fraction = card.completionFraction.coerceIn(0f, 1f)
                                                LinearProgressIndicator(
                                                    progress = { fraction },
                                                    modifier = Modifier.fillMaxWidth().height(8.dp),
                                                    color = Color(0xFF7C3AED),
                                                    trackColor = Color(0xFF7C3AED).copy(alpha = 0.20f)
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                            item(key = "pinned_divider") {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                        items(activeCards, key = { "active_${it.folderName}" }) { card ->
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
                                showBottomProgressBar = false,
                                hidePrimaryProgressBar = true,
                                segmentedStatusCounts = statusCounts,
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
                                    val isPinned = card.folderName in pinnedFolderNames
                                    PinButton(isPinned = isPinned, onClick = { onTogglePin(card.folderName, isPinned) })
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
                                        else -> {
                                            // Items exist but no station tags — single overall bar
                                            val fraction = card.completionFraction.coerceIn(0f, 1f)
                                            LinearProgressIndicator(
                                                progress = { fraction },
                                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                                color = Color(0xFF7C3AED),
                                                trackColor = Color(0xFF7C3AED).copy(alpha = 0.20f)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                        if (pendingCards.isNotEmpty()) {
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
                            items(pendingCards, key = { "pending_${it.folderName}" }) { card ->
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
                                    showBottomProgressBar = false,
                                    hidePrimaryProgressBar = true,
                                    segmentedStatusCounts = statusCounts,
                                    showExpandToggle = false,
                                    headerActions = {
                                        card.labels.forEach { label ->
                                            StatusChip(
                                                text = label.name,
                                                backgroundColor = parseJobLabelColor(label.colorHex),
                                                contentColor = Color.White
                                            )
                                        }
                                        val isPinned = card.folderName in pinnedFolderNames
                                        PinButton(isPinned = isPinned, onClick = { onTogglePin(card.folderName, isPinned) })
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

    if (showScheduleDialog) {
        DeliveryScheduleDialog(
            schedule = deliverySchedule,
            onDismiss = { showScheduleDialog = false }
        )
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
                    text = "${stationDisplayName(sp.station)} · ${sp.completed}/${sp.total}",
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

private fun stationDisplayName(station: String): String = when (station.uppercase()) {
    "EDGE_BANDER" -> "EDGE BANDER"
    "HARDWOODS" -> "HARDWOODS"
    else -> station
}

private fun stationBarColor(station: String): Color = when (station.uppercase()) {
    "SAW" -> Color(0xFFD97706)
    "ASSEMBLY", "ASSM" -> Color(0xFF2563EB)
    "HARDWOODS", "HW" -> Color(0xFF16A34A)
    "SPECIALTY", "SPEC" -> Color(0xFF7C3AED)
    "CNC" -> Color(0xFF6366F1)
    "EDGE_BANDER", "EDGE" -> Color(0xFF0891B2)
    "DELIVERY" -> Color(0xFF16A34A)
    else -> Color(0xFF6366F1)
}
