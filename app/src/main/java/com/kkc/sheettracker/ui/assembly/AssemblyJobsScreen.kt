package com.kkc.sheettracker.ui.assembly

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.kkc.sheettracker.ui.components.animateEntrance
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Sell
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalFocusManager
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.data.UiPreferencesStore
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.ui.components.PinButton
import com.kkc.sheettracker.ui.components.RefreshIconButton
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.AssemblyScanCoordinator
import com.kkc.sheettracker.data.AssemblyStateStore
import com.kkc.sheettracker.data.DeliveryScheduleRequestStore
import com.kkc.sheettracker.data.HardwoodsProgressStore
import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.JobBoardRequestStore
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProductionOrderRequestStore
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodRevisionHistory
import com.kkc.sheettracker.data.models.AssemblyJobCard
import com.kkc.sheettracker.data.models.DeliverySchedulePickerJob
import com.kkc.sheettracker.data.models.JobLabel
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.ui.admin.JobLabelEditorNavBarControls
import com.kkc.sheettracker.ui.components.JobBoardGrid
import com.kkc.sheettracker.ui.components.JobBoardItem
import com.kkc.sheettracker.ui.components.TopBarClock
import com.kkc.sheettracker.ui.components.mergeActiveReorder
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.HardwoodsRevisionHistorySheet
import com.kkc.sheettracker.ui.components.SortToggleBar
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.components.parseJobLabelColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

import com.kkc.sheettracker.data.DeliveryScheduleRepository
import com.kkc.sheettracker.ui.components.DeliveryScheduleWidget
import com.kkc.sheettracker.ui.components.DeliveryScheduleDialog
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration

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
    basePath: String,
    tabletId: String,
    isDebugBuild: Boolean,
    specialtyProgressVersionHint: Long = 0L,
    pinnedFolderNames: List<String> = emptyList(),
    onTogglePin: (folderName: String, isCurrentlyPinned: Boolean) -> Unit = { _, _ -> },
    onJobClick: (AssemblyJobCard) -> Unit,
    onOpenHardwoodsChange: (jobFolderName: String, docType: HardwoodDocType, rowId: String) -> Unit,
    onViewCoverSheet: (AssemblyJobCard) -> Unit,
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
    var boardView by rememberSaveable { mutableStateOf(uiPrefs.getBoardView("assembly")) }
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
            navBarDeco.owner = "jobs_assembly"
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
        } else if (navBarDeco.owner == "jobs_assembly") {
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
            if (navBarDeco.owner == "jobs_assembly") {
                if (!navBarDeco.keepSearchDeco) {
                    navBarDeco.searchDecoration = null
                }
                navBarDeco.owner = ""
            }
        }
    }
    val scanState by assemblyScanCoordinator.state.collectAsState()
    val cncProgressVersion by progressStore.progressVersion.collectAsState()
    val hardwoodProgressVersion by hardwoodsProgressStore.progressVersion.collectAsState()
    val specialtyScanState by specialtyStateStore.scanState.collectAsState()
    val specialtyProgressVersion by specialtyStateStore.progressVersion.collectAsState()
    val deliverySchedule = remember(scanState.snapshot.generation) {
        deliveryScheduleRepository.fetchSchedule()
    }
    // Cleared on each new scan generation; populated async per-item to avoid blocking composition.
    val badgeCache = remember(scanState.snapshot.generation) { mutableStateMapOf<String, AssemblyJobBadgeState>() }

    // deriveJobCards() builds CNC + hardwood progress indexes from tracker files on a cache miss,
    // so the real pass must not run synchronously in composition (main thread). The placeholder
    // pass (resolveCounts = false) only touches the assembly job list already in memory, so the
    // list renders at its final size immediately instead of sitting empty until tracker I/O
    // resolves (mirrors JobBrowserScreen/HardwoodsJobsScreen's instant-placeholder pattern).
    val allCards by produceState(
        initialValue = assemblyStateStore.deriveJobCards(resolveCounts = false),
        scanState.snapshot.generation, cncProgressVersion, hardwoodProgressVersion
    ) {
        value = withContext(Dispatchers.IO) { assemblyStateStore.deriveJobCards() }
    }
    // deriveJobCards() is a pure in-memory transform (no file I/O) — safe synchronously, and
    // avoids a spurious "Specialty: scanning..." flash to zero every time this screen is
    // recreated (e.g. tab switches under LegacySingleStackNavigation).
    val specialtyCards = remember(
        specialtyScanState.snapshot.generation, specialtyProgressVersion, specialtyProgressVersionHint
    ) {
        specialtyStateStore.deriveJobCards()
    }
    val specialtySummary = remember(specialtyCards) {
        val total = specialtyCards.sumOf { it.totalItems }
        val complete = specialtyCards.sumOf { it.completedItems }
        complete to total
    }
    val filtered = remember(allCards, query.text, sortByName) {
        val queryStr = query.text
        val base = if (queryStr.isBlank()) {
            allCards
        } else {
            allCards.filter { card ->
                card.jobNumber.contains(queryStr, ignoreCase = true) ||
                    card.jobName.contains(queryStr, ignoreCase = true)
            }
        }
        if (sortByName) {
            base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
        } else {
            base // already in production order
        }
    }

    val assemblyUiStates = remember(filtered, scanState.snapshot.generation, hardwoodProgressVersion) {
        filtered.map { card ->
            val cncCounts = card.toCncStatusCounts()
            val hardwoodCounts = card.toHardwoodsStatusCounts()
            val combinedCounts = combineCounts(cncCounts, hardwoodCounts)
            val subtitle = "CNC ${card.cncSummary.completedSheets}/${card.cncSummary.totalSheets} • " +
                "Hardwoods ${card.hardwoodsSummary.donePieces}/${card.hardwoodsSummary.totalPieces}"
            AssemblyJobItemUiState(
                card = card,
                cncCounts = cncCounts,
                hardwoodCounts = hardwoodCounts,
                combinedCounts = combinedCounts,
                subtitle = subtitle
            )
        }
    }

    val positionMap = remember(filtered) {
        filtered.mapIndexed { i, card -> card.folderName to (i + 1) }.toMap()
    }
    val pinnedUiStates = remember(pinnedFolderNames, assemblyUiStates) {
        pinnedFolderNames.mapNotNull { folder -> assemblyUiStates.find { it.card.folderName == folder } }
    }
    val activeUiStates = remember(assemblyUiStates) { assemblyUiStates.filter { it.card.boardSection == 0 } }
    val pendingUiStates = remember(assemblyUiStates) { assemblyUiStates.filter { it.card.boardSection == 1 } }
    val activeUiStatesByFolder = remember(activeUiStates) { activeUiStates.associateBy { it.card.folderName } }

    // Keyed on the active folder-name SET, not scanState.snapshot.generation: allCards merges in
    // CNC's and Hardwoods' coordinator data (via deriveJobCards), and those coordinators' own
    // scans can finish resolving well after Assembly's own generation last bumped. Keying on
    // generation left this frozen at whatever (possibly still-empty) set existed at that moment,
    // so the active section stayed permanently blank until a manual refresh bumped generation
    // again. Keying on the set itself reseeds exactly when the resolved job set actually changes,
    // while staying stable across pure local drag-reorders (which don't change the set).
    val activeFolderSet = remember(activeUiStates) { activeUiStates.map { it.card.folderName }.toSet() }
    val activeOrder = remember(activeFolderSet) {
        mutableStateListOf(*activeUiStates.map { it.card.folderName }.toTypedArray())
    }
    val dragOffset = if (pinnedUiStates.isNotEmpty()) pinnedUiStates.size + 2 else 0
    val listState = rememberLazyListState()
    val saveScope = rememberCoroutineScope()
    val requestStore = remember(basePath) { ProductionOrderRequestStore(File(basePath)) }
    val jobBoardRequestStore = remember(basePath) { JobBoardRequestStore(File(basePath)) }
    val deliveryScheduleRequestStore = remember(basePath) { DeliveryScheduleRequestStore(File(basePath)) }
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

    var editingLabelsFor by remember { mutableStateOf<AssemblyJobCard?>(null) }
    var allLabels by remember { mutableStateOf<List<JobLabel>>(emptyList()) }
    LaunchedEffect(basePath, scanState.snapshot.generation) {
        allLabels = withContext(Dispatchers.IO) {
            runCatching {
                UnifiedMetadataEngineRegistry.getOrCreate(File(basePath), isDebugBuild).listAllLabels()
            }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(Unit) {
        assemblyScanCoordinator.refresh(RefreshReason.APP_FOREGROUND, force = false)
    }

    Scaffold(
        topBar = {
            KKCTopAppBar(
                title = {
                    Text(
                        "KKC Dashboard - Assembly",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                
                
                actions = {
                    RefreshIconButton(
                        loading = scanState.status == ScanStatus.LOADING,
                        onClick = { assemblyScanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }
                    )
                    IconButton(
                        onClick = {
                            boardView = !boardView
                            uiPrefs.setBoardView("assembly", boardView)
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
                    "${filtered.size} jobs"
                } else {
                    "Showing ${filtered.size} of ${allCards.size} jobs"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            SortToggleBar(sortByName = sortByName, onSortChange = { if (!adminMode) sortByName = it })
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
                            itemsIndexed(pinnedUiStates, key = { _, uiState -> "pinned_${uiState.card.folderName}" }) { index, uiState ->
                                val card = uiState.card
                                val badge = badgeCache[card.folderName]
                                val cncCounts = uiState.cncCounts
                                val hardwoodCounts = uiState.hardwoodCounts
                                val combinedCounts = uiState.combinedCounts
                                val pos = positionMap[card.folderName]
                                val label = if (pos != null) "$pos of ${filtered.size}" else null
                                ProgressCard(
                                    modifier = Modifier.animateEntrance(index, initialLoadComplete.value),
                                    title = card.folderName,
                                    subtitle = uiState.subtitle,
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
                                    fraction = if (combinedCounts.total <= 0) 0f else combinedCounts.complete.toFloat() / combinedCounts.total.toFloat(),
                                    expanded = false,
                                    onToggleExpanded = {},
                                    onClick = { onJobClick(card) },
                                    hidePrimaryProgressBar = true,
                                    showExpandToggle = false,
                                    headerActions = {
                                        if (label != null) {
                                            StatusChip(
                                                text = label,
                                                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                        card.labels.forEach { lbl ->
                                            StatusChip(
                                                text = lbl.name,
                                                backgroundColor = parseJobLabelColor(lbl.colorHex),
                                                contentColor = Color.White
                                            )
                                        }
                                        if (badge?.hasDeliverySheet == true) {
                                            FilterChip(selected = false, onClick = { onViewCoverSheet(card) }, label = { Text("Cover Sheet") })
                                        }
                                        val isPinned = card.folderName in pinnedFolderNames
                                        PinButton(isPinned = isPinned, onClick = { onTogglePin(card.folderName, isPinned) })
                                    },
                                    inlineContent = {
                                        DualModeProgressBars(cncCounts = cncCounts, hardwoodCounts = hardwoodCounts)
                                    }
                                )
                            }
                            item(key = "pinned_divider") {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                        itemsIndexed(activeOrder, key = { _, it -> it }) { index, activeFolderName ->
                        val uiState = activeUiStatesByFolder[activeFolderName]
                        if (uiState != null) {
                        ReorderableItem(reorderState, key = activeFolderName) {
                            val card = uiState.card
                            val badge = badgeCache[card.folderName]
                            LaunchedEffect(card.folderName, scanState.snapshot.generation) {
                                if (badgeCache.containsKey(card.folderName)) return@LaunchedEffect
                                badgeCache[card.folderName] = withContext(Dispatchers.IO) {
                                    AssemblyJobBadgeState(
                                        hasDeliverySheet = jobRepository.getJobPdfCatalog(card.folderName).deliverySheet != null,
                                        hasHistory = hardwoodsRepository.loadHardwoodsRevisionHistory(card.folderName) != null
                                    )
                                }
                            }
                            val cncCounts = uiState.cncCounts
                            val hardwoodCounts = uiState.hardwoodCounts
                            val combinedCounts = uiState.combinedCounts
                            val subtitle = uiState.subtitle
                            ProgressCard(
                                modifier = Modifier.animateEntrance(index + pinnedUiStates.size, initialLoadComplete.value),
                                title = card.folderName,
                                subtitle = subtitle,
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
                                    if (badge?.hasDeliverySheet == true) {
                                        FilterChip(
                                            selected = false,
                                            onClick = { onViewCoverSheet(card) },
                                            label = { Text("Cover Sheet") }
                                        )
                                    }
                                    if (badge?.hasHistory == true) {
                                        TextButton(onClick = { selectedHistoryJob = card.folderName }) {
                                            Text("History")
                                        }
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
                                    DualModeProgressBars(
                                        cncCounts = cncCounts,
                                        hardwoodCounts = hardwoodCounts
                                    )
                                }
                            )
                        }
                        }
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
                            itemsIndexed(pendingUiStates, key = { _, uiState -> "pending_${uiState.card.folderName}" }) { index, uiState ->
                                val card = uiState.card
                                val badge = badgeCache[card.folderName]
                                LaunchedEffect(card.folderName, scanState.snapshot.generation) {
                                    if (badgeCache.containsKey(card.folderName)) return@LaunchedEffect
                                    badgeCache[card.folderName] = withContext(Dispatchers.IO) {
                                        AssemblyJobBadgeState(
                                            hasDeliverySheet = jobRepository.getJobPdfCatalog(card.folderName).deliverySheet != null,
                                            hasHistory = hardwoodsRepository.loadHardwoodsRevisionHistory(card.folderName) != null
                                        )
                                    }
                                }
                                val cncCounts = uiState.cncCounts
                                val hardwoodCounts = uiState.hardwoodCounts
                                val combinedCounts = uiState.combinedCounts
                                val subtitle = uiState.subtitle
                                ProgressCard(
                                    modifier = Modifier.animateEntrance(index + pinnedUiStates.size + activeUiStates.size, initialLoadComplete.value),
                                    title = card.folderName,
                                    subtitle = subtitle,
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
                                        if (badge?.hasDeliverySheet == true) {
                                            FilterChip(
                                                selected = false,
                                                onClick = { onViewCoverSheet(card) },
                                                label = { Text("Cover Sheet") }
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

data class AssemblyJobBadgeState(val hasDeliverySheet: Boolean, val hasHistory: Boolean)

data class AssemblyJobItemUiState(
    val card: AssemblyJobCard,
    val cncCounts: StatusCounts,
    val hardwoodCounts: StatusCounts,
    val combinedCounts: StatusCounts,
    val subtitle: String
)
