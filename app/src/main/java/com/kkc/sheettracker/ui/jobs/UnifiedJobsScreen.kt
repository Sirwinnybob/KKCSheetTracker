package com.kkc.sheettracker.ui.jobs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.AdminSyncClient
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.DeliveryScheduleEditRequest
import com.kkc.sheettracker.data.DeliveryScheduleRepository
import com.kkc.sheettracker.data.DeliveryScheduleRequestStore
import com.kkc.sheettracker.data.DeliveryScheduleSlotEdit
import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.JobBoardEdit
import com.kkc.sheettracker.data.JobBoardRequestStore
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProductionOrderRequestStore
import com.kkc.sheettracker.data.UiPreferencesStore
import com.kkc.sheettracker.data.models.DeliverySchedulePickerJob
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.JobLabel
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.ui.admin.JobLabelEditorNavBarControls
import com.kkc.sheettracker.ui.components.DeliveryScheduleBanner
import com.kkc.sheettracker.ui.components.DeliveryScheduleDialog
import com.kkc.sheettracker.ui.components.deliveryJobHighlight
import com.kkc.sheettracker.ui.components.HardwoodsRevisionHistorySheet
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import com.kkc.sheettracker.ui.components.JobBoardItem
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration
import com.kkc.sheettracker.ui.components.RefreshIconButton
import com.kkc.sheettracker.ui.components.TopBarClock
import com.kkc.sheettracker.ui.components.animateEntrance
import com.kkc.sheettracker.ui.components.mergeActiveReorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

private fun sanitizeModeTitle(modeName: String): String {
    val clean = modeName.lowercase().removePrefix("jobs_").removePrefix("jobs")
    return when (clean) {
        "cnc" -> "CNC"
        "hardwoods" -> "Hardwoods"
        "assembly" -> "Assembly"
        "specialty" -> "Specialty"
        else -> clean.split("_").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedJobsScreen(
    spec: UnifiedJobsSpec,
    jobRepository: JobRepository,
    deliveryScheduleRepository: DeliveryScheduleRepository,
    basePath: String,
    tabletId: String,
    isDebugBuild: Boolean,
    pinnedFolderNames: List<String> = emptyList(),
    onTogglePin: (folderName: String, isCurrentlyPinned: Boolean) -> Unit = { _, _ -> },
    onJobClick: (UnifiedJobUiModel) -> Unit,
    onOpenHardwoodsChange: ((folderName: String, docType: HardwoodDocType, rowId: String) -> Unit)? = null,
    onSearchClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
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
    var boardView by rememberSaveable { mutableStateOf(uiPrefs.getBoardView(spec.modeName.lowercase())) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var selectedHistoryJob by remember { mutableStateOf<String?>(null) }
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
    val ownerId = "jobs_unified_${spec.modeName.lowercase()}"
    SideEffect {
        if (active) {
            navBarDeco.owner = ownerId
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
        } else if (navBarDeco.owner == ownerId) {
            if (!navBarDeco.keepSearchDeco) {
                navBarDeco.searchDecoration = null
            }
            navBarDeco.owner = ""
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (navBarDeco.owner == ownerId) {
                if (!navBarDeco.keepSearchDeco) {
                    navBarDeco.searchDecoration = null
                }
                navBarDeco.owner = ""
            }
        }
    }

    val scanStatus by spec.scanStatus.collectAsState()
    val scanGeneration by spec.scanGeneration.collectAsState()
    val progressVersion by spec.progressVersion.collectAsState()
    
    var deliverySchedule by remember(scanGeneration) {
        mutableStateOf(deliveryScheduleRepository.fetchSchedule())
    }

    val badgeCache = remember(scanGeneration) { mutableStateMapOf<String, Set<JobBadge>>() }

    val cards = remember(scanGeneration, progressVersion, sortByName) {
        val all = spec.deriveJobCards()
        if (sortByName) {
            all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
        } else {
            all
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

    val pinnedCards = remember(pinnedFolderNames, filteredCards) {
        pinnedFolderNames.mapNotNull { folder -> filteredCards.find { it.folderName == folder } }
    }
    val activeCards = remember(filteredCards) { filteredCards.filter { it.boardSection == 0 } }
    val pendingCards = remember(filteredCards) { filteredCards.filter { it.boardSection == 1 } }
    val activeCardsByFolder = remember(activeCards) { activeCards.associateBy { it.folderName } }

    val activeOrder = remember(scanGeneration) {
        mutableStateListOf(*activeCards.map { it.folderName }.toTypedArray())
    }
    val dragOffset = 2 + if (pinnedCards.isNotEmpty()) pinnedCards.size + 2 else 0
    val listState = rememberLazyListState()

    // "Jump to job" from a tapped delivery — scroll the list to the matching card and highlight
    // it. Index map mirrors the LazyColumn's exact item emission order below (pinned header +
    // pinned cards + divider, then activeOrder, then pending header + pending cards).
    var pendingScrollTarget by remember { mutableStateOf<String?>(null) }
    var highlightedFolderName by remember { mutableStateOf<String?>(null) }
    val lazyIndexByFolderName = remember(pinnedCards, activeOrder.toList(), pendingCards) {
        val map = mutableMapOf<String, Int>()
        var idx = 0
        if (pinnedCards.isNotEmpty()) {
            idx += 1 // pinned_header
            pinnedCards.forEach { card -> map.putIfAbsent(card.folderName, idx); idx += 1 }
            idx += 1 // pinned_divider
        }
        activeOrder.forEach { folderName -> map.putIfAbsent(folderName, idx); idx += 1 }
        if (pendingCards.isNotEmpty()) {
            idx += 1 // pending_header
            pendingCards.forEach { card -> map.putIfAbsent(card.folderName, idx); idx += 1 }
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
    val deliveryScheduleRequestStore = remember(basePath) { DeliveryScheduleRequestStore(File(basePath)) }
    val adminSyncConfig = remember { AdminSyncConfig.create(context) }
    val adminSyncServerUrl by produceState<String?>(initialValue = null, adminSyncConfig) {
        value = adminSyncConfig.getServerUrl()
    }
    val adminSyncClient = remember(adminSyncServerUrl) { adminSyncServerUrl?.let { AdminSyncClient(it) } }
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
            original = filteredCards.map { com.kkc.sheettracker.data.models.Job(folderName = it.folderName, jobNumber = it.jobNumber, jobName = it.jobName, boardSection = it.boardSection) },
            reorderedActiveFolderNames = activeOrder,
            boardSectionOf = { it.boardSection },
            folderNameOf = { it.folderName }
        )
        saveScope.launch {
            withContext(Dispatchers.IO) { requestStore.writeRequest(newOrder, tabletId) }
        }
    }

    var editingLabelsFor by remember { mutableStateOf<UnifiedJobUiModel?>(null) }
    var allLabels by remember { mutableStateOf<List<JobLabel>>(emptyList()) }
    LaunchedEffect(basePath, scanGeneration) {
        allLabels = withContext(Dispatchers.IO) {
            runCatching {
                UnifiedMetadataEngineRegistry.getOrCreate(File(basePath), isDebugBuild).listAllLabels()
            }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(Unit) {
        spec.refresh(RefreshReason.APP_FOREGROUND, force = false)
    }

    Scaffold(
        topBar = {
            KKCTopAppBar(
                title = {
                    Text(
                        "KKC Dashboard - ${sanitizeModeTitle(spec.modeName)}",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    RefreshIconButton(
                        loading = scanStatus == ScanStatus.LOADING,
                        onClick = { spec.refresh(RefreshReason.USER_REFRESH, force = true) }
                    )
                    IconButton(
                        onClick = { if (!adminMode) sortByName = !sortByName },
                        enabled = !adminMode
                    ) {
                        Icon(
                            imageVector = if (sortByName) Icons.Default.SortByAlpha else Icons.AutoMirrored.Filled.Sort,
                            contentDescription = if (sortByName) "Sort: A–Z Name" else "Sort: Production Order"
                        )
                    }
                    IconButton(
                        onClick = {
                            if (!sortByName) {
                                boardView = !boardView
                                uiPrefs.setBoardView(spec.modeName.lowercase(), boardView)
                            }
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
            DeliveryScheduleBanner(
                schedule = deliverySchedule,
                isAdminMode = adminMode,
                onEditRequested = { showScheduleDialog = true },
                showWhenEmpty = adminMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                onJobSelected = { folderName ->
                    query = TextFieldValue("")
                    boardView = false
                    pendingScrollTarget = folderName
                }
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
                    scanStatus == ScanStatus.LOADING && cards.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    filteredCards.isEmpty() -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = listBottomPadding),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item(key = "header_job_count") {
                                Text(
                                    text = if (query.text.isBlank()) {
                                        "${filteredCards.size} jobs"
                                    } else {
                                        "Showing ${filteredCards.size} of ${cards.size} jobs"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            item(key = "header_deliveries_widget") {
                                DeliveryScheduleWidget(
                                    schedule = deliverySchedule,
                                    onTap = { showScheduleDialog = true },
                                    showWhenEmpty = adminMode,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                )
                            }

                            item(key = "empty_msg") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No jobs found")
                                }
                            }
                        }
                    }
                    isBoardView -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(gridCols),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = listBottomPadding),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item(key = "header_job_count", span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = if (query.text.isBlank()) {
                                        "${filteredCards.size} jobs"
                                    } else {
                                        "Showing ${filteredCards.size} of ${cards.size} jobs"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            item(key = "header_deliveries_widget", span = { GridItemSpan(maxLineSpan) }) {
                                DeliveryScheduleWidget(
                                    schedule = deliverySchedule,
                                    onTap = { showScheduleDialog = true },
                                    showWhenEmpty = adminMode,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                )
                            }

                            itemsIndexed(activeCards, key = { _, card -> card.folderName }) { index, card ->
                                val loadedBadges = badgeCache[card.folderName]
                                LaunchedEffect(card.folderName, scanGeneration) {
                                    if (!badgeCache.containsKey(card.folderName)) {
                                        badgeCache[card.folderName] = spec.resolveBadges(card.folderName)
                                    }
                                }
                                UnifiedJobCard(
                                    modifier = Modifier.animateItem(),
                                    model = card.copy(
                                        badges = card.badges + (loadedBadges ?: emptySet<JobBadge>()),
                                        onCardClick = { onJobClick(card) },
                                        onHistoryClick = { folder -> selectedHistoryJob = folder }
                                    ),
                                    adminMode = adminMode,
                                    sortByName = sortByName,
                                    onTogglePin = { onTogglePin(card.folderName, !card.isPinned) },
                                    onEditLabels = { editingLabelsFor = card }
                                )
                            }
                            
                            if (pendingCards.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    SectionHeader("Pending Delivery")
                                }
                                itemsIndexed(pendingCards, key = { _, card -> "pending_${card.folderName}" }) { index, card ->
                                    val loadedBadges = badgeCache[card.folderName]
                                    LaunchedEffect(card.folderName, scanGeneration) {
                                        if (!badgeCache.containsKey(card.folderName)) {
                                            badgeCache[card.folderName] = spec.resolveBadges(card.folderName)
                                        }
                                    }
                                    UnifiedJobCard(
                                        modifier = Modifier.animateItem(),
                                        model = card.copy(
                                            badges = card.badges + (loadedBadges ?: emptySet<JobBadge>()),
                                            onCardClick = { onJobClick(card) },
                                            onHistoryClick = { folder -> selectedHistoryJob = folder }
                                        ),
                                        adminMode = adminMode,
                                        sortByName = sortByName,
                                        onTogglePin = { onTogglePin(card.folderName, !card.isPinned) },
                                        onEditLabels = { editingLabelsFor = card }
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = listBottomPadding),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item(key = "header_job_count") {
                                Text(
                                    text = if (query.text.isBlank()) {
                                        "${filteredCards.size} jobs"
                                    } else {
                                        "Showing ${filteredCards.size} of ${cards.size} jobs"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            item(key = "header_deliveries_widget") {
                                DeliveryScheduleWidget(
                                    schedule = deliverySchedule,
                                    onTap = { showScheduleDialog = true },
                                    showWhenEmpty = adminMode,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                )
                            }

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
                                    val loadedBadges = badgeCache[card.folderName]
                                    LaunchedEffect(card.folderName, scanGeneration) {
                                        if (!badgeCache.containsKey(card.folderName)) {
                                            badgeCache[card.folderName] = spec.resolveBadges(card.folderName)
                                        }
                                    }
                                    UnifiedJobCard(
                                        modifier = Modifier.animateEntrance(index, initialLoadComplete.value)
                                            .deliveryJobHighlight(active = highlightedFolderName == card.folderName),
                                        model = card.copy(
                                            badges = card.badges + (loadedBadges ?: emptySet<JobBadge>()),
                                            onCardClick = { onJobClick(card) },
                                            onHistoryClick = { folder -> selectedHistoryJob = folder }
                                        ),
                                        adminMode = adminMode,
                                        sortByName = sortByName,
                                        onTogglePin = { onTogglePin(card.folderName, true) },
                                        onEditLabels = { editingLabelsFor = if (editingLabelsFor?.folderName == card.folderName) null else card }
                                    )
                                }
                                item(key = "pinned_divider") {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                }
                            }
                            itemsIndexed(activeOrder, key = { _, folderName -> folderName }) { index, activeFolderName ->
                                val card = activeCardsByFolder[activeFolderName]
                                if (card != null) {
                                    val loadedBadges = badgeCache[card.folderName]
                                    LaunchedEffect(card.folderName, scanGeneration) {
                                        if (!badgeCache.containsKey(card.folderName)) {
                                            badgeCache[card.folderName] = spec.resolveBadges(card.folderName)
                                        }
                                    }
                                    ReorderableItem(reorderState, key = activeFolderName) {
                                        UnifiedJobCard(
                                            modifier = Modifier.animateEntrance(index, initialLoadComplete.value)
                                                .deliveryJobHighlight(active = highlightedFolderName == card.folderName),
                                            model = card.copy(
                                                badges = card.badges + (loadedBadges ?: emptySet<JobBadge>()),
                                                onCardClick = { onJobClick(card) },
                                                onHistoryClick = { folder -> selectedHistoryJob = folder }
                                            ),
                                            adminMode = adminMode,
                                            sortByName = sortByName,
                                            onTogglePin = { onTogglePin(card.folderName, false) },
                                            onEditLabels = { editingLabelsFor = if (editingLabelsFor?.folderName == card.folderName) null else card },
                                            dragModifier = if (adminMode) Modifier.draggableHandle(onDragStopped = { saveActiveOrder() }) else Modifier
                                        )
                                    }
                                }
                            }

                            if (pendingCards.isNotEmpty()) {
                                item(key = "pending_header") {
                                    SectionHeader("Pending Delivery")
                                }
                                itemsIndexed(pendingCards, key = { _, card -> "pending_${card.folderName}" }) { index, card ->
                                    val loadedBadges = badgeCache[card.folderName]
                                    LaunchedEffect(card.folderName, scanGeneration) {
                                        if (!badgeCache.containsKey(card.folderName)) {
                                            badgeCache[card.folderName] = spec.resolveBadges(card.folderName)
                                        }
                                    }
                                    UnifiedJobCard(
                                        modifier = Modifier.animateEntrance(index, initialLoadComplete.value)
                                            .deliveryJobHighlight(active = highlightedFolderName == card.folderName),
                                        model = card.copy(
                                            badges = card.badges + (loadedBadges ?: emptySet<JobBadge>()),
                                            onCardClick = { onJobClick(card) },
                                            onHistoryClick = { folder -> selectedHistoryJob = folder }
                                        ),
                                        adminMode = adminMode,
                                        sortByName = sortByName,
                                        onTogglePin = { onTogglePin(card.folderName, !card.isPinned) },
                                        onEditLabels = { editingLabelsFor = if (editingLabelsFor?.folderName == card.folderName) null else card }
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
        val hardwoodsRepo = remember(basePath) { HardwoodsRepository(File(basePath)) }
        val history = remember(scanGeneration, progressVersion, historyJob) {
            hardwoodsRepo.loadHardwoodsRevisionHistory(historyJob)
        }
        HardwoodsRevisionHistorySheet(
            jobFolderName = historyJob,
            history = history,
            onOpenRow = { docTypeRaw, rowId ->
                val docType = runCatching { HardwoodDocType.valueOf(docTypeRaw) }.getOrNull()
                if (docType != null && onOpenHardwoodsChange != null) {
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
            onDismiss = { showScheduleDialog = false },
            isAdminMode = adminMode,
            availableJobs = deliveryPickerJobs,
            onQueueSlotEdit = { slot, jobs ->
                saveScope.launch {
                    val applied = adminSyncClient?.applyDeliverySchedule(
                        DeliveryScheduleEditRequest(
                            tabletId = tabletId,
                            requestedAt = java.time.Instant.now().toString(),
                            slotEdits = listOf(
                                DeliveryScheduleSlotEdit(slot = slot.trim().lowercase(), jobs = jobs.take(3))
                            )
                        )
                    )
                    if (applied != null) {
                        deliverySchedule = applied
                    } else {
                        withContext(Dispatchers.IO) {
                            deliveryScheduleRequestStore.queueSlotEdit(slot, jobs, tabletId)
                        }
                    }
                }
            },
            onQueueReset = {
                saveScope.launch {
                    val applied = adminSyncClient?.applyDeliverySchedule(
                        DeliveryScheduleEditRequest(
                            tabletId = tabletId,
                            requestedAt = java.time.Instant.now().toString(),
                            resetAll = true
                        )
                    )
                    if (applied != null) {
                        deliverySchedule = applied
                    } else {
                        withContext(Dispatchers.IO) {
                            deliveryScheduleRequestStore.queueReset(tabletId)
                        }
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
                            val applied = adminSyncClient?.applyJobBoardEdits(
                                listOf(JobBoardEdit(folderName = labelEditJob.folderName, labelIds = newIds)),
                                tabletId
                            ) ?: false
                            if (!applied) {
                                withContext(Dispatchers.IO) {
                                    JobBoardRequestStore(File(basePath)).queueLabelEdit(labelEditJob.folderName, newIds, tabletId)
                                }
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(labels = allLabels.filter { it.id in newIds })
                    },
                    onSetPendingDelivery = { pending ->
                        val newSection = if (pending) 1 else 0
                        saveScope.launch {
                            val applied = adminSyncClient?.applyJobBoardEdits(
                                listOf(JobBoardEdit(folderName = labelEditJob.folderName, boardSection = newSection)),
                                tabletId
                            ) ?: false
                            if (!applied) {
                                withContext(Dispatchers.IO) {
                                    JobBoardRequestStore(File(basePath)).queueBoardSectionEdit(
                                        folderName = labelEditJob.folderName,
                                        boardSection = newSection,
                                        tabletId = tabletId
                                    )
                                }
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
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
