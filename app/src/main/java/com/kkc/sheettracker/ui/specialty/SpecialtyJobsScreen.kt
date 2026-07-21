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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.kkc.sheettracker.ui.components.animateEntrance
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalFocusManager
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import com.kkc.sheettracker.data.models.SpecialtyJobCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.ui.components.PinButton
import com.kkc.sheettracker.ui.components.RefreshIconButton
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import com.kkc.sheettracker.data.UiPreferencesStore
import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.DeliveryScheduleRequestStore
import com.kkc.sheettracker.data.JobBoardRequestStore
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProductionOrderRequestStore
import com.kkc.sheettracker.data.SpecialtyScanCoordinator
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.models.JobLabel
import com.kkc.sheettracker.data.models.DeliverySchedulePickerJob
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.StationProgress
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.DeliveryScheduleRepository
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.ui.admin.JobLabelEditorNavBarControls
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.JobBoardGrid
import com.kkc.sheettracker.ui.components.JobBoardItem
import com.kkc.sheettracker.ui.components.TopBarClock
import com.kkc.sheettracker.ui.components.mergeActiveReorder
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.SortToggleBar
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.components.parseJobLabelColor
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File
import com.kkc.sheettracker.ui.components.DeliveryScheduleBanner
import com.kkc.sheettracker.ui.components.deliveryJobHighlight
import com.kkc.sheettracker.ui.components.DeliveryScheduleDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecialtyJobsScreen(
    specialtyScanCoordinator: SpecialtyScanCoordinator,
    specialtyStateStore: SpecialtyStateStore,
    jobRepository: JobRepository,
    deliveryScheduleRepository: DeliveryScheduleRepository,
    basePath: String,
    tabletId: String,
    isDebugBuild: Boolean,
    pinnedFolderNames: List<String> = emptyList(),
    onTogglePin: (folderName: String, isCurrentlyPinned: Boolean) -> Unit = { _, _ -> },
    onJobClick: (com.kkc.sheettracker.data.models.SpecialtyJobCard) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    active: Boolean = true
) {
    val serverGridCols = remember { jobRepository.getBoardGridColumns() }
    val orientation = LocalConfiguration.current.orientation
    val gridCols = if (orientation == Configuration.ORIENTATION_LANDSCAPE) serverGridCols
                   else minOf(serverGridCols, 3)
    val context = LocalContext.current
    val uiPrefs = remember { UiPreferencesStore(context) }
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var sortByName by rememberSaveable { mutableStateOf(false) }
    var boardView by rememberSaveable { mutableStateOf(uiPrefs.getBoardView("specialty")) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    val initialLoadComplete = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(600)
        initialLoadComplete.value = true
    }
    val adminMode by AdminModeController.enabled.collectAsState()
    LaunchedEffect(adminMode) {
        if (adminMode) {
            query = TextFieldValue("")
            sortByName = false
            boardView = false
        }
    }
    LaunchedEffect(sortByName) { if (sortByName) boardView = false }

    val navBarDeco = LocalNavBarDecoration.current
    val listBottomPadding = if (navBarDeco.searchDecoration != null) 172.dp else 112.dp
    val focusManager = LocalFocusManager.current
    val currentQuery = query
    SideEffect {
        if (active) {
            navBarDeco.owner = "jobs_specialty"
            navBarDeco.searchDecoration = NavBarSearchDecoration(
                searchTextValue    = currentQuery,
                onSearchTextChange = { query = it },
                onGo               = { focusManager.clearFocus() },
                isPartsEnabled     = false,
                onParts            = {},
                contextLine        = if (currentQuery.text.isNotBlank())
                                       "Filtering jobs by \"${currentQuery.text}\"" else "",
                placeholder        = "Search jobs...",
                showParts          = false,
                onScan             = null
            )
        } else if (navBarDeco.owner == "jobs_specialty") {
            // TabLayer keeps this screen mounted when the Jobs tab loses focus (no
            // dispose), so the active→false transition must clear ownership itself —
            // DisposableEffect below only catches real composition removal.
            if (!navBarDeco.keepSearchDeco) {
                navBarDeco.searchDecoration = null
            }
            navBarDeco.owner = ""
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (navBarDeco.owner == "jobs_specialty") {
                if (!navBarDeco.keepSearchDeco) {
                    navBarDeco.searchDecoration = null
                }
                navBarDeco.owner = ""
            }
        }
    }

    val scanState by specialtyStateStore.scanState.collectAsState()
    val progressVersion by specialtyStateStore.progressVersion.collectAsState()
    val deliverySchedule = remember(scanState.snapshot.generation) {
        deliveryScheduleRepository.fetchSchedule()
    }
    // deriveJobCards() is a pure in-memory transform over already-resolved job state (no file
    // I/O), so it's safe to compute synchronously here. This matters beyond perf: activeOrder
    // below reseeds in the same composition frame keyed on scanState.snapshot.generation. An
    // async produceState with an emptyList() initial value left activeOrder reseeded from an
    // empty set on every fresh composition (e.g. LegacySingleStackNavigation fully recreates this
    // screen on each tab switch), so returning from another tab showed only the Pending section
    // until a manual refresh bumped the generation again.
    val cards = remember(scanState.snapshot.generation, progressVersion, sortByName) {
        val all = specialtyStateStore.deriveJobCards()
        if (sortByName) {
            all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
        } else {
            all // already in production order from listJobs
        }
    }
    val filteredCards = remember(cards, query.text) {
        val queryStr = query.text
        if (queryStr.isBlank()) cards
        else cards.filter { card ->
            card.jobNumber.contains(queryStr, ignoreCase = true) ||
                card.jobName.contains(queryStr, ignoreCase = true) ||
                card.folderName.contains(queryStr, ignoreCase = true)
        }
    }

    val positionMap = remember(filteredCards) {
        filteredCards.mapIndexed { i, card -> card.folderName to (i + 1) }.toMap()
    }
    val pinnedCards = remember(pinnedFolderNames, filteredCards) {
        pinnedFolderNames.mapNotNull { folder -> filteredCards.find { it.folderName == folder } }
    }
    val activeCards = remember(filteredCards) { filteredCards.filter { it.boardSection == 0 } }
    val pendingCards = remember(filteredCards) { filteredCards.filter { it.boardSection == 1 } }
    val activeCardsByFolder = remember(activeCards) { activeCards.associateBy { it.folderName } }

    val activeOrder = remember(scanState.snapshot.generation) {
        mutableStateListOf(*activeCards.map { it.folderName }.toTypedArray())
    }
    val dragOffset = if (pinnedCards.isNotEmpty()) pinnedCards.size + 2 else 0
    val listState = rememberLazyListState()

    // Deep-link target set by DeliveryScheduleBanner's onJobSelected (tap a delivery job in the
    // expanded day-strip). pendingScrollTarget drives the scroll+highlight LaunchedEffect below;
    // highlightedFolderName drives the temporary visual tint on the matching row.
    var pendingScrollTarget by remember { mutableStateOf<String?>(null) }
    var highlightedFolderName by remember { mutableStateOf<String?>(null) }

    // Absolute LazyColumn item index for each folderName, replicating the exact item {...} /
    // itemsIndexed(...) emission order below: optional "pinned_header" + pinned rows + optional
    // "pinned_divider", then the active-order rows, then optional "pending_header" + pending rows.
    val lazyIndexByFolderName = remember(pinnedCards, activeOrder.toList(), pendingCards) {
        val map = mutableMapOf<String, Int>()
        var idx = 0
        if (pinnedCards.isNotEmpty()) {
            idx += 1 // "pinned_header" item
            pinnedCards.forEach { card ->
                map[card.folderName] = idx
                idx += 1
            }
            idx += 1 // "pinned_divider" item
        }
        activeOrder.forEach { folderName ->
            map[folderName] = idx
            idx += 1
        }
        if (pendingCards.isNotEmpty()) {
            idx += 1 // "pending_header" item
            pendingCards.forEach { card ->
                map[card.folderName] = idx
                idx += 1
            }
        }
        map
    }

    LaunchedEffect(pendingScrollTarget, filteredCards) {
        val target = pendingScrollTarget ?: return@LaunchedEffect
        val idx = lazyIndexByFolderName[target]
        if (idx != null) {
            listState.animateScrollToItem(idx)
            highlightedFolderName = target
            pendingScrollTarget = null
        } else {
            pendingScrollTarget = null // not found (stale/deleted job) - give up cleanly
        }
    }
    // Separate effect keyed only on the highlight itself — NOT on filteredCards. Keying the
    // clear-timer on the list too meant any unrelated background refresh mid-highlight (badge
    // load, scan tick) cancelled this coroutine before delay() completed, permanently orphaning
    // highlightedFolderName since pendingScrollTarget was already null by then.
    LaunchedEffect(highlightedFolderName) {
        val target = highlightedFolderName ?: return@LaunchedEffect
        delay(3000)
        if (highlightedFolderName == target) highlightedFolderName = null
    }

    val saveScope = rememberCoroutineScope()
    val requestStore = remember(basePath) { ProductionOrderRequestStore(File(basePath)) }
    val jobBoardRequestStore = remember(basePath) { JobBoardRequestStore(File(basePath)) }
    val deliveryScheduleRequestStore = remember(basePath) { DeliveryScheduleRequestStore(File(basePath)) }
    val deliveryPickerJobs = remember(filteredCards) {
        filteredCards.map {
            DeliverySchedulePickerJob(
                folderName = it.folderName,
                jobNumber = it.jobNumber,
                description = it.jobName
            )
        }
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val f = from.index - dragOffset
        val t = to.index - dragOffset
        if (f in activeOrder.indices && t in activeOrder.indices) {
            activeOrder.add(t, activeOrder.removeAt(f))
        }
    }
    val saveActiveOrder = {
        val newOrder = mergeActiveReorder(
            original = filteredCards,
            reorderedActiveFolderNames = activeOrder,
            boardSectionOf = { it.boardSection },
            folderNameOf = { it.folderName }
        )
        saveScope.launch {
            withContext(Dispatchers.IO) { requestStore.writeRequest(newOrder, tabletId) }
        }
    }

    var editingLabelsFor by remember { mutableStateOf<SpecialtyJobCard?>(null) }
    var allLabels by remember { mutableStateOf<List<JobLabel>>(emptyList()) }
    LaunchedEffect(basePath, scanState.snapshot.generation) {
        allLabels = withContext(Dispatchers.IO) {
            runCatching {
                UnifiedMetadataEngineRegistry.getOrCreate(File(basePath), isDebugBuild).listAllLabels()
            }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(Unit) {
        specialtyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = false)
    }

    Scaffold(
        topBar = {
            KKCTopAppBar(
                title = {
                    Text(
                        "KKC Dashboard - Specialty",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                
                
                actions = {
                    RefreshIconButton(
                        loading = scanState.status == ScanStatus.LOADING,
                        onClick = { specialtyScanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }
                    )
                    IconButton(
                        onClick = {
                            boardView = !boardView
                            uiPrefs.setBoardView("specialty", boardView)
                        },
                        enabled = !sortByName && !adminMode
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
            Text(
                text = if (query.text.isBlank()) {
                    "${filteredCards.size} jobs"
                } else {
                    "Showing ${filteredCards.size} of ${cards.size} jobs"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            SortToggleBar(sortByName = sortByName, onSortChange = { if (!adminMode) sortByName = it })

            DeliveryScheduleBanner(
                schedule = deliverySchedule,
                isAdminMode = adminMode,
                onEditRequested = { showScheduleDialog = true },
                showWhenEmpty = adminMode,
                onJobSelected = { folderName ->
                    query = TextFieldValue("")
                    boardView = false
                    pendingScrollTarget = folderName
                },
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = listBottomPadding),
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
                            itemsIndexed(pinnedCards, key = { _, card -> "pinned_${card.folderName}" }) { index, card ->
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
                                val isHighlighted = highlightedFolderName == card.folderName
                                ProgressCard(
                                    modifier = Modifier.animateEntrance(index, initialLoadComplete.value)
                                        .deliveryJobHighlight(active = isHighlighted),
                                    title = card.folderName,
                                    subtitle = "${card.completedItems}/${card.totalItems} complete",
                                    useBounceClick = true,
                                    titleContent = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = card.jobNumber,
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontSize = 18.sp,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold
                                                ),
                                                maxLines = 1
                                            )
                                            if (card.jobName.isNotBlank()) {
                                                Text(
                                                    text = "– ${card.jobName}",
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontSize = 16.sp,
                                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    },
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
                        itemsIndexed(activeOrder, key = { _, folderName -> folderName }) { index, activeFolderName ->
                                val card = activeCardsByFolder[activeFolderName]
                                if (card != null) {
                                    ReorderableItem(reorderState, key = activeFolderName) {
                                        val statusCounts = remember(card.totalItems, card.completedItems) {
                                            StatusCounts(
                                                total = card.totalItems.coerceAtLeast(0),
                                                complete = card.completedItems.coerceIn(0, card.totalItems.coerceAtLeast(0)),
                                                bad = 0,
                                                skipped = 0,
                                                notStarted = (card.totalItems - card.completedItems).coerceAtLeast(0)
                                            )
                                        }
                                        val isHighlighted = highlightedFolderName == card.folderName
                                        ProgressCard(
                                            modifier = Modifier.animateEntrance(index + pinnedCards.size, initialLoadComplete.value)
                                                .deliveryJobHighlight(active = isHighlighted),
                                            title = card.folderName,
                                            useBounceClick = true,
                                            titleContent = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = card.jobNumber,
                                                        style = MaterialTheme.typography.titleMedium.copy(
                                                            fontSize = 18.sp,
                                                            fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold
                                                        ),
                                                        maxLines = 1
                                                    )
                                                    if (card.jobName.isNotBlank()) {
                                                        Text(
                                                            text = "– ${card.jobName}",
                                                            style = MaterialTheme.typography.titleMedium.copy(
                                                                fontSize = 16.sp,
                                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                                            ),
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 1,
                                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                }
                                            },
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
                                    if (adminMode) {
                                        IconButton(onClick = {
                                            editingLabelsFor = if (editingLabelsFor?.folderName == card.folderName) null else card
                                        }) {
                                            Icon(Icons.Filled.Sell, contentDescription = "Edit Labels")
                                        }
                                        IconButton(
                                            modifier = Modifier.draggableHandle(
                                                onDragStopped = { saveActiveOrder() }
                                            ),
                                            onClick = {}
                                        ) {
                                            Icon(Icons.Filled.DragHandle, contentDescription = "Reorder")
                                        }
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
                        }
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
                            itemsIndexed(pendingCards, key = { _, card -> "pending_${card.folderName}" }) { index, card ->
                                val statusCounts = remember(card.totalItems, card.completedItems) {
                                    StatusCounts(
                                        total = card.totalItems.coerceAtLeast(0),
                                        complete = card.completedItems.coerceIn(0, card.totalItems.coerceAtLeast(0)),
                                        bad = 0,
                                        skipped = 0,
                                        notStarted = (card.totalItems - card.completedItems).coerceAtLeast(0)
                                    )
                                }
                                val isHighlighted = highlightedFolderName == card.folderName
                                ProgressCard(
                                    modifier = Modifier.animateEntrance(index + pinnedCards.size + activeOrder.size, initialLoadComplete.value)
                                        .deliveryJobHighlight(active = isHighlighted),
                                    title = card.folderName,
                                    useBounceClick = true,
                                    titleContent = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = card.jobNumber,
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontSize = 18.sp,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold
                                                ),
                                                maxLines = 1
                                            )
                                            if (card.jobName.isNotBlank()) {
                                                Text(
                                                    text = "– ${card.jobName}",
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontSize = 16.sp,
                                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    },
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
                                        if (adminMode) {
                                            IconButton(onClick = {
                                            editingLabelsFor = if (editingLabelsFor?.folderName == card.folderName) null else card
                                        }) {
                                                Icon(Icons.Filled.Sell, contentDescription = "Edit Labels")
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
    }

    if (showScheduleDialog) {
        DeliveryScheduleDialog(
            schedule = deliverySchedule,
            onDismiss = { showScheduleDialog = false },
            isAdminMode = adminMode,
            availableJobs = deliveryPickerJobs,
            onQueueSlotEdit = { slot, jobs ->
                saveScope.launch {
                    withContext(Dispatchers.IO) {
                        deliveryScheduleRequestStore.queueSlotEdit(slot, jobs, tabletId)
                    }
                }
            },
            onQueueReset = {
                saveScope.launch {
                    withContext(Dispatchers.IO) {
                        deliveryScheduleRequestStore.queueReset(tabletId)
                    }
                }
            }
        )
    }

    val labelEditJob = editingLabelsFor
    DisposableEffect(navBarDeco) {
        onDispose { navBarDeco.extendedControls = null }
    }
    SideEffect {
        navBarDeco.extendedControls = if (labelEditJob != null) {
            {
                JobLabelEditorNavBarControls(
                    jobTitle = listOf(labelEditJob.jobNumber, labelEditJob.jobName)
                        .filter { it.isNotBlank() }.joinToString(" — ").ifBlank { labelEditJob.folderName },
                    allLabels = allLabels,
                    currentLabelIds = labelEditJob.labels.map { it.id }.toSet(),
                    isPendingDelivery = labelEditJob.boardSection == 1,
                    onToggleLabel = { label ->
                        val newIds = if (label.id in labelEditJob.labels.map { it.id }) {
                            labelEditJob.labels.filterNot { it.id == label.id }.map { it.id }
                        } else {
                            labelEditJob.labels.map { it.id } + label.id
                        }
                        saveScope.launch {
                            withContext(Dispatchers.IO) {
                                jobBoardRequestStore.queueLabelEdit(labelEditJob.folderName, newIds, tabletId)
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(labels = allLabels.filter { it.id in newIds })
                    },
                    onSetPendingDelivery = { pending ->
                        val newSection = if (pending) 1 else 0
                        saveScope.launch {
                            withContext(Dispatchers.IO) {
                                jobBoardRequestStore.queueBoardSectionEdit(labelEditJob.folderName, newSection, tabletId)
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(boardSection = newSection)
                    },
                    onDismiss = { editingLabelsFor = null }
                )
            }
        } else null
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
