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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.kkc.sheettracker.ui.components.animateEntrance
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalFocusManager
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration
import com.kkc.sheettracker.ui.components.PinButton
import com.kkc.sheettracker.ui.components.RefreshIconButton
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.data.UiPreferencesStore
import android.content.res.Configuration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.AdminSyncClient
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.JobBoardEdit
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.HardwoodsScanCoordinator
import com.kkc.sheettracker.data.JobBoardRequestStore
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProductionOrderRequestStore
import com.kkc.sheettracker.data.DeliveryScheduleRequestStore
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodJob
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import com.kkc.sheettracker.data.models.DeliverySchedulePickerJob
import com.kkc.sheettracker.data.models.JobLabel
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.DeliveryScheduleRepository
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.ui.admin.JobLabelEditorNavBarControls
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.JobBoardGrid
import com.kkc.sheettracker.ui.components.JobBoardItem
import com.kkc.sheettracker.ui.components.TopBarClock
import com.kkc.sheettracker.ui.components.mergeActiveReorder
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
    basePath: String,
    tabletId: String,
    isDebugBuild: Boolean,
    pinnedFolderNames: List<String> = emptyList(),
    onTogglePin: (folderName: String, isCurrentlyPinned: Boolean) -> Unit = { _, _ -> },
    onJobClick: (HardwoodJob) -> Unit,
    onOpenHardwoodsChange: (jobFolderName: String, docType: HardwoodDocType, rowId: String) -> Unit,
    onViewCoverSheet: (HardwoodJob) -> Unit,
    onView3D: (HardwoodJob) -> Unit,
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
    var boardView by rememberSaveable { mutableStateOf(uiPrefs.getBoardView("hardwoods")) }
    var expandedJobs by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selectedHistoryJob by rememberSaveable { mutableStateOf<String?>(null) }
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
            navBarDeco.owner = "jobs_hardwoods"
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
        } else if (navBarDeco.owner == "jobs_hardwoods") {
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
            if (navBarDeco.owner == "jobs_hardwoods") {
                if (!navBarDeco.keepSearchDeco) {
                    navBarDeco.searchDecoration = null
                }
                navBarDeco.owner = ""
            }
        }
    }

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

    val filtered = remember(jobs, query.text, sortByName) {
        val queryStr = query.text
        val base = if (queryStr.isBlank()) jobs else jobs.filter {
            it.jobNumber.contains(queryStr, ignoreCase = true) ||
                it.jobName.contains(queryStr, ignoreCase = true) ||
                it.folderName.contains(queryStr, ignoreCase = true)
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

    // Builds the per-job UI states. The placeholder pass (resolveCounts = false) needs only the
    // job model already in memory, so cards render at their final size/shape immediately; the
    // background pass resolves the tracker-file-backed counts (rip totals, skip overlays, badges)
    // and swaps them in. Mirrors JobBrowserScreen's buildJobUiStates so Hardwoods doesn't sit on
    // an empty list while the heavier board-stock computation runs.
    val buildHardwoodsUiStates: (Boolean) -> List<HardwoodsJobItemUiState> = { resolveCounts ->
        filtered.map { job ->
            if (!resolveCounts) {
                HardwoodsJobItemUiState(
                    job = job,
                    counts = HardwoodStatusCounts(),
                    docCount = 0,
                    docSegments = emptyList(),
                    availableDocTypes = emptySet()
                )
            } else {
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
    }

    val hardwoodsUiStates by produceState(
        initialValue = buildHardwoodsUiStates(false),
        filtered, scanState.snapshot.generation, progressVersion, scanState.snapshot.basePath
    ) {
        value = withContext(Dispatchers.IO) { buildHardwoodsUiStates(true) }
    }

    val positionMap = remember(filtered) {
        filtered.mapIndexed { i, job -> job.folderName to (i + 1) }.toMap()
    }
    val pinnedUiStates = remember(pinnedFolderNames, hardwoodsUiStates) {
        pinnedFolderNames.mapNotNull { folder -> hardwoodsUiStates.find { it.job.folderName == folder } }
    }
    val activeUiStates = remember(hardwoodsUiStates) { hardwoodsUiStates.filter { it.job.boardSection == 0 } }
    val pendingUiStates = remember(hardwoodsUiStates) { hardwoodsUiStates.filter { it.job.boardSection == 1 } }
    val activeUiStatesByFolder = remember(activeUiStates) { activeUiStates.associateBy { it.job.folderName } }

    val activeOrder = remember(scanState.snapshot.generation) {
        mutableStateListOf(*activeUiStates.map { it.job.folderName }.toTypedArray())
    }
    val dragOffset = if (pinnedUiStates.isNotEmpty()) pinnedUiStates.size + 2 else 0
    val listState = rememberLazyListState()
    val saveScope = rememberCoroutineScope()
    val requestStore = remember(basePath) { ProductionOrderRequestStore(File(basePath)) }
    val jobBoardRequestStore = remember(basePath) { JobBoardRequestStore(File(basePath)) }
    val deliveryScheduleRequestStore = remember(basePath) { DeliveryScheduleRequestStore(File(basePath)) }
    val adminSyncConfig = remember { AdminSyncConfig.create(context) }
    val adminSyncServerUrl by produceState<String?>(initialValue = null, adminSyncConfig) {
        value = adminSyncConfig.getServerUrl()
    }
    val adminSyncClient = remember(adminSyncServerUrl) { adminSyncServerUrl?.let { AdminSyncClient(it) } }
    val deliveryPickerJobs = remember(filtered) {
        filtered.map {
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
            original = filtered,
            reorderedActiveFolderNames = activeOrder,
            boardSectionOf = { it.boardSection },
            folderNameOf = { it.folderName }
        )
        saveScope.launch {
            withContext(Dispatchers.IO) { requestStore.writeRequest(newOrder, tabletId) }
        }
    }

    var editingLabelsFor by remember { mutableStateOf<HardwoodJob?>(null) }
    var allLabels by remember { mutableStateOf<List<JobLabel>>(emptyList()) }
    LaunchedEffect(basePath, scanState.snapshot.generation) {
        allLabels = withContext(Dispatchers.IO) {
            runCatching {
                UnifiedMetadataEngineRegistry.getOrCreate(File(basePath), isDebugBuild).listAllLabels()
            }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(Unit) {
        scanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = false)
    }

    Scaffold(
        topBar = {
            KKCTopAppBar(
                title = {
                    Text(
                        "KKC Dashboard - Hardwoods",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                
                actions = {
                    RefreshIconButton(
                        loading = scanState.status == ScanStatus.LOADING,
                        onClick = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }
                    )
                    IconButton(
                        onClick = {
                            boardView = !boardView
                            uiPrefs.setBoardView("hardwoods", boardView)
                        },
                        enabled = !sortByName && !adminMode
                    ) {
                        Icon(
                            imageVector = if (boardView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = if (boardView) "List View" else "Board View"
                        )
                    }
                    TopBarClock()
                },
                )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            SortToggleBar(sortByName = sortByName, onSortChange = { if (!adminMode) sortByName = it })
            Text(
                text = if (query.text.isBlank()) {
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
                showWhenEmpty = adminMode,
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = listBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (pinnedUiStates.isNotEmpty()) {
                        item(key = "pinned_header") {
                            Text(
                                "Pinned",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                        itemsIndexed(pinnedUiStates, key = { _, uiState -> "pinned_${uiState.job.folderName}" }) { index, uiState ->
                            val job = uiState.job
                            val counts = uiState.counts
                            val docSegments = uiState.docSegments
                            val pos = positionMap[job.folderName]
                            val label = if (pos != null) "$pos of ${filtered.size}" else null
                            ProgressCard(
                                modifier = Modifier.animateEntrance(index, initialLoadComplete.value),
                                title = job.folderName,
                                useBounceClick = true,
                                titleContent = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = job.jobNumber,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            ),
                                            maxLines = 1
                                        )
                                        if (job.jobName.isNotBlank()) {
                                            Text(
                                                text = "– ${job.jobName}",
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Medium
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                },
                                subtitle = "${counts.donePieces}/${counts.effectiveTotalPieces} done",
                                fraction = counts.completionFraction,
                                expanded = false,
                                onToggleExpanded = {},
                                segmentedStatusCounts = counts.toStatusCounts(),
                                materialSegments = docSegments,
                                showBottomProgressBar = true,
                                showExpandToggle = false,
                                headerActions = {
                                    if (label != null) {
                                        StatusChip(
                                            text = label,
                                            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    job.labels.forEach { lbl ->
                                        StatusChip(
                                            text = lbl.name,
                                            backgroundColor = parseJobLabelColor(lbl.colorHex),
                                            contentColor = Color.White
                                        )
                                    }
                                    val isPinned = job.folderName in pinnedFolderNames
                                    PinButton(isPinned = isPinned, onClick = { onTogglePin(job.folderName, isPinned) })
                                },
                                onClick = { onJobClick(job) }
                            )
                        }
                        item(key = "pinned_divider") {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                    itemsIndexed(activeOrder, key = { _, folderName -> folderName }) { index, activeFolderName ->
                        val uiState = activeUiStatesByFolder[activeFolderName]
                        if (uiState != null) {
                            ReorderableItem(reorderState, key = activeFolderName) {
                                val job = uiState.job
                                val badge = badgeCache[job.folderName]
                                val counts = uiState.counts
                                val docCount = uiState.docCount
                                val docSegments = uiState.docSegments
                                val availableDocTypes = uiState.availableDocTypes
                                val subtitle = "${counts.donePieces}/${counts.effectiveTotalPieces} done"

                                ProgressCard(
                                    modifier = Modifier.animateEntrance(index + pinnedUiStates.size, initialLoadComplete.value),
                                    title = job.folderName,
                            titleContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = job.jobNumber,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        ),
                                        maxLines = 1
                                    )
                                    if (job.jobName.isNotBlank()) {
                                        Text(
                                            text = "– ${job.jobName}",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Medium
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            },
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
                                val isPinned = job.folderName in pinnedFolderNames
                                PinButton(isPinned = isPinned, onClick = { onTogglePin(job.folderName, isPinned) })
                                if (adminMode) {
                                    IconButton(onClick = {
                                        editingLabelsFor = if (editingLabelsFor?.folderName == job.folderName) null else job
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
                        itemsIndexed(pendingUiStates, key = { _, uiState -> "pending_${uiState.job.folderName}" }) { index, uiState ->
                            val job = uiState.job
                            val badge = badgeCache[job.folderName]
                            val counts = uiState.counts
                            val docCount = uiState.docCount
                            val docSegments = uiState.docSegments
                            val availableDocTypes = uiState.availableDocTypes
                            val subtitle = "${counts.donePieces}/${counts.effectiveTotalPieces} done"

                            ProgressCard(
                                modifier = Modifier.animateEntrance(index + pinnedUiStates.size + activeUiStates.size, initialLoadComplete.value),
                                title = job.folderName,
                                titleContent = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = job.jobNumber,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            ),
                                            maxLines = 1
                                        )
                                        if (job.jobName.isNotBlank()) {
                                            Text(
                                                text = "– ${job.jobName}",
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Medium
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                },
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
                                    val isPinned = job.folderName in pinnedFolderNames
                                    PinButton(isPinned = isPinned, onClick = { onTogglePin(job.folderName, isPinned) })
                                    if (adminMode) {
                                        IconButton(onClick = {
                                        editingLabelsFor = if (editingLabelsFor?.folderName == job.folderName) null else job
                                    }) {
                                            Icon(Icons.Filled.Sell, contentDescription = "Edit Labels")
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
                            val applied = adminSyncClient?.applyJobBoardEdits(
                                listOf(JobBoardEdit(folderName = labelEditJob.folderName, labelIds = newIds)),
                                tabletId
                            ) ?: false
                            if (!applied) {
                                withContext(Dispatchers.IO) {
                                    jobBoardRequestStore.queueLabelEdit(labelEditJob.folderName, newIds, tabletId)
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
                                    jobBoardRequestStore.queueBoardSectionEdit(labelEditJob.folderName, newSection, tabletId)
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
