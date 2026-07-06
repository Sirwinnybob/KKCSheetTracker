package com.kkc.sheettracker.ui.browser

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.data.UiPreferencesStore
import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.DeliveryScheduleRepository
import com.kkc.sheettracker.data.DeliveryScheduleRequestStore
import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.JobBoardRequestStore
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProductionOrderRequestStore
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.DeliverySchedulePickerJob
import com.kkc.sheettracker.data.models.JobLabel
import com.kkc.sheettracker.data.models.JobUiModel
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.ui.admin.JobLabelEditorNavBarControls
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.DeliveryScheduleDialog
import com.kkc.sheettracker.ui.components.DeliveryScheduleWidget
import com.kkc.sheettracker.ui.components.JobBoardGrid
import com.kkc.sheettracker.ui.components.TopBarClock
import com.kkc.sheettracker.ui.components.JobBoardItem
import com.kkc.sheettracker.ui.components.MaterialSegmentData
import com.kkc.sheettracker.ui.components.CountStatusChip
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.components.HardwoodsRevisionHistorySheet
import com.kkc.sheettracker.ui.components.mergeActiveReorder
import com.kkc.sheettracker.ui.components.PinButton
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.SortToggleBar
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.components.parseJobLabelColor
import com.kkc.sheettracker.ui.components.RefreshIconButton
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

private const val JOBS_PARITY_TAG = "KKC_APP_STATE_PARITY_JOBS"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobBrowserScreen(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    hardwoodsRepository: HardwoodsRepository,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    deliveryScheduleRepository: DeliveryScheduleRepository,
    appStateFlags: AppStateFeatureFlags,
    basePath: String,
    tabletId: String,
    isDebugBuild: Boolean,
    pinnedFolderNames: List<String> = emptyList(),
    onTogglePin: (folderName: String, isCurrentlyPinned: Boolean) -> Unit = { _, _ -> },
    onJobClick: (Job) -> Unit,
    onOpenHardwoodsChange: (jobFolderName: String, docType: HardwoodDocType, rowId: String) -> Unit,
    onViewCoverSheet: (Job) -> Unit,
    onView3D: (Job) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val serverGridCols = remember { jobRepository.getBoardGridColumns() }
    val orientation = LocalConfiguration.current.orientation
    val gridCols = if (orientation == Configuration.ORIENTATION_LANDSCAPE) serverGridCols
                   else minOf(serverGridCols, 3)
    val context = LocalContext.current
    val uiPrefs = remember { UiPreferencesStore(context) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortByName by rememberSaveable { mutableStateOf(false) }
    var boardView by rememberSaveable { mutableStateOf(uiPrefs.getBoardView("jobs")) }
    var selectedHistoryJob by rememberSaveable { mutableStateOf<String?>(null) }
    val adminMode by AdminModeController.enabled.collectAsState()
    LaunchedEffect(adminMode) {
        if (adminMode) {
            searchQuery = ""
            sortByName = false
            boardView = false
        }
    }
    LaunchedEffect(sortByName) { if (sortByName) boardView = false }
    val listState = rememberLazyListState()
    val scanState by scanCoordinator.state.collectAsState()
    val progressVersion by progressStore.progressVersion.collectAsState()
    val appJobModels by appStateStore.jobUiModels.collectAsState()
    val appUiState by appStateStore.uiState.collectAsState()
    val appFlags = remember(appStateFlags) { appStateFlags.snapshot() }
    val deliverySchedule = remember(scanState.snapshot.generation) {
        deliveryScheduleRepository.fetchSchedule()
    }
    var showScheduleDialog by remember { mutableStateOf(false) }
    // Persists badge data across LazyColumn item recycling — prevents height shift on re-scroll
    val badgeCache = remember(scanState.snapshot.generation) { mutableStateMapOf<String, JobBadgeState>() }
    val useAppState = appFlags.jobsEnabled
    val jobs = scanState.snapshot.jobs
    val isLoading = scanState.status == ScanStatus.LOADING && jobs.isEmpty()
    val appJobModelsByFolder = remember(appJobModels) { appJobModels.associateBy { it.folderName } }

    LaunchedEffect(scanState.snapshot.generation) {
        withContext(Dispatchers.IO) {
            jobs.forEach { progressStore.pruneLocalStateForJob(it.folderName, it.materials) }
        }
    }

    val filteredJobs = remember(jobs, searchQuery, sortByName, progressVersion) {
        val base = if (searchQuery.isBlank()) {
            jobs
        } else {
            jobs.filter { job ->
                job.jobNumber.contains(searchQuery, ignoreCase = true) ||
                    job.jobName.contains(searchQuery, ignoreCase = true)
            }
        }
        if (sortByName) {
            base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
        } else {
            base // already in production order from listJobs
        }
    }
    val activeJobs  = remember(filteredJobs) { filteredJobs.filter { it.boardSection == 0 } }
    val pendingJobs = remember(filteredJobs) { filteredJobs.filter { it.boardSection == 1 } }
    val positionMap = remember(filteredJobs) {
        filteredJobs.mapIndexed { i, job -> job.folderName to (i + 1) }.toMap()
    }

    // Builds the per-job UI states. On the appState fast path counts come from the in-memory
    // model (cheap), so they are always populated. On the legacy/fallback path the status counts
    // require tracker-file I/O on a cache miss; [resolveCounts] gates that disk work so it runs
    // only on the background pass. The placeholder pass uses zero counts — the card renders at its
    // final size either way (the 8dp progress bar + Done/Bad/Skip chips + one segment per material
    // are present regardless), so only the bar fill / numbers animate in once counts resolve.
    val buildJobUiStates: (Boolean) -> List<JobBrowserItemUiState> = { resolveCounts ->
        filteredJobs.map { job ->
            val appModel = appJobModelsByFolder[job.folderName]
            val counts = if (useAppState && appModel != null) {
                appModel.counts
            } else if (resolveCounts) {
                progressStore.getJobStatusCounts(job.folderName, job.materials)
            } else {
                StatusCounts()
            }
            val fraction = if (useAppState && appModel != null) {
                appModel.completionFraction
            } else if (counts.total <= 0) 0f
            else counts.complete.toFloat() / counts.total.toFloat()
            val materialSegments = if (useAppState && appModel != null) {
                appModel.materials.map { material ->
                    val jobMaterial = job.materials.find { it.pdfFilename == material.pdfFilename }
                    MaterialSegmentData(
                        materialName = material.materialName,
                        counts = material.counts,
                        isRemake = jobMaterial?.metadata?.remakeLabel != null
                    )
                }
            } else {
                job.materials.map { material ->
                    MaterialSegmentData(
                        materialName = material.materialName,
                        counts = if (resolveCounts) progressStore.getMaterialStatusCounts(job.folderName, material) else StatusCounts(),
                        isRemake = material.metadata?.remakeLabel != null
                    )
                }
            }
            JobBrowserItemUiState(
                job = job,
                counts = counts,
                completionFraction = fraction,
                materialSegments = materialSegments,
                hasDeliverySheet = null,
                hasThreeDAssets = null,
                revisionCount = null
            )
        }
    }

    // First frame: appState counts populated, fallback counts zeroed (cards already at final size).
    // Background: resolve fallback counts off the main thread, then swap in. produceState keeps the
    // previous populated value across key changes, so refreshes never flash back to empty.
    val jobUiStates by produceState(
        initialValue = buildJobUiStates(false),
        filteredJobs, scanState.snapshot.generation, progressVersion, useAppState, appJobModelsByFolder
    ) {
        value = withContext(Dispatchers.IO) { buildJobUiStates(true) }
    }

    val pinnedUiStates = remember(pinnedFolderNames, jobUiStates) {
        pinnedFolderNames.mapNotNull { folder -> jobUiStates.find { it.job.folderName == folder } }
    }
    val activeUiStates = remember(jobUiStates) { jobUiStates.filter { it.job.boardSection == 0 } }
    val pendingUiStates = remember(jobUiStates) { jobUiStates.filter { it.job.boardSection == 1 } }
    val activeUiStatesByFolder = remember(activeUiStates) { activeUiStates.associateBy { it.job.folderName } }

    // Local drag order for the Active section — seeded once per scan generation, mutated locally
    // by drag, independent of unrelated recompositions (badge/progress updates) within that
    // generation. Reset only when a fresh scan actually changes the underlying job set.
    val activeOrder = remember(scanState.snapshot.generation) {
        mutableStateListOf(*activeUiStates.map { it.job.folderName }.toTypedArray())
    }
    val dragOffset = if (pinnedUiStates.isNotEmpty()) pinnedUiStates.size + 2 else 0
    val saveScope = rememberCoroutineScope()
    val requestStore = remember(basePath) { ProductionOrderRequestStore(File(basePath)) }
    val jobBoardRequestStore = remember(basePath) { JobBoardRequestStore(File(basePath)) }
    val deliveryScheduleRequestStore = remember(basePath) { DeliveryScheduleRequestStore(File(basePath)) }
    val deliveryPickerJobs = remember(filteredJobs) {
        filteredJobs.map {
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
            original = filteredJobs,
            reorderedActiveFolderNames = activeOrder,
            boardSectionOf = { it.boardSection },
            folderNameOf = { it.folderName }
        )
        saveScope.launch {
            withContext(Dispatchers.IO) { requestStore.writeRequest(newOrder, tabletId) }
        }
    }

    var editingLabelsFor by remember { mutableStateOf<Job?>(null) }
    var allLabels by remember { mutableStateOf<List<JobLabel>>(emptyList()) }
    LaunchedEffect(basePath, scanState.snapshot.generation) {
        allLabels = withContext(Dispatchers.IO) {
            runCatching {
                UnifiedMetadataEngineRegistry.getOrCreate(File(basePath), isDebugBuild).listAllLabels()
            }.getOrDefault(emptyList())
        }
    }

    if (com.kkc.sheettracker.BuildConfig.DEBUG) {
        LaunchedEffect(useAppState, scanState.snapshot.generation, progressVersion, appUiState.scanGeneration, appUiState.progressVersion) {
            if (!appFlags.shadowEnabled) return@LaunchedEffect

            val mismatch = jobs.firstOrNull { job ->
                val app = appJobModelsByFolder[job.folderName] ?: return@firstOrNull true
                val legacy = progressStore.getJobStatusCounts(job.folderName, job.materials)
                app.counts != legacy
            }

            if (mismatch != null) {
                Log.w(
                    JOBS_PARITY_TAG,
                    "mismatch folder=${mismatch.folderName} appGen=${appUiState.scanGeneration} legacyGen=${scanState.snapshot.generation} appProgress=${appUiState.progressVersion} legacyProgress=$progressVersion"
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.headerBackground(),
                title = {
                    Text(
                        "KKC Dashboard - CNC",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                windowInsets = WindowInsets.statusBars,
                actions = {
                    RefreshIconButton(
                        loading = scanState.status == ScanStatus.LOADING,
                        onClick = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }
                    )
                    IconButton(
                        onClick = {
                            boardView = !boardView
                            uiPrefs.setBoardView("jobs", boardView)
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
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Filter jobs by number or name...") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                enabled = !adminMode
            )
            SortToggleBar(
                sortByName = sortByName,
                onSortChange = { if (!adminMode) sortByName = it }
            )
            Text(
                text = if (searchQuery.isBlank()) {
                    "${filteredJobs.size} jobs"
                } else {
                    "Showing ${filteredJobs.size} of ${jobs.size} jobs"
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
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (filteredJobs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No jobs found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (isBoardView) {
                    JobBoardGrid(
                        items = activeJobs.map { JobBoardItem(it.folderName, it.jobNumber, it.jobName, it.labels) },
                        pendingItems = pendingJobs.map { JobBoardItem(it.folderName, it.jobNumber, it.jobName, it.labels) },
                        jobRepository = jobRepository,
                        onItemClick = { boardItem ->
                            filteredJobs.find { it.folderName == boardItem.folderName }
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
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 112.dp),
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
                        items(pinnedUiStates, key = { "pinned_${it.job.folderName}" }) { uiState ->
                            val pos = positionMap[uiState.job.folderName]
                            val label = if (pos != null) "$pos of ${filteredJobs.size}" else null
                            JobBrowserRow(
                                uiState = uiState,
                                scanGeneration = scanState.snapshot.generation,
                                badgeCache = badgeCache,
                                jobRepository = jobRepository,
                                hardwoodsRepository = hardwoodsRepository,
                                onJobClick = onJobClick,
                                onViewCoverSheet = onViewCoverSheet,
                                onView3D = onView3D,
                                onHistoryClick = { selectedHistoryJob = it },
                                sortByName = sortByName,
                                pinnedFolderNames = pinnedFolderNames,
                                onTogglePin = onTogglePin,
                                positionLabel = label,
                            )
                        }
                        item(key = "pinned_divider") {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                    items(activeOrder, key = { it }) { folderName ->
                        val uiState = activeUiStatesByFolder[folderName]
                        if (uiState != null) {
                            ReorderableItem(reorderState, key = folderName) {
                                JobBrowserRow(
                                    uiState = uiState,
                                    scanGeneration = scanState.snapshot.generation,
                                    badgeCache = badgeCache,
                                    jobRepository = jobRepository,
                                    hardwoodsRepository = hardwoodsRepository,
                                    onJobClick = onJobClick,
                                    onViewCoverSheet = onViewCoverSheet,
                                    onView3D = onView3D,
                                    onHistoryClick = { selectedHistoryJob = it },
                                    sortByName = sortByName,
                                    pinnedFolderNames = pinnedFolderNames,
                                    onTogglePin = onTogglePin,
                                    adminMode = adminMode,
                                    onEditLabels = {
                                        editingLabelsFor = if (editingLabelsFor?.folderName == uiState.job.folderName) null else uiState.job
                                    },
                                    dragHandleModifier = Modifier.draggableHandle(
                                        onDragStopped = { saveActiveOrder() }
                                    )
                                )
                            }
                        }
                    }
                    if (pendingUiStates.isNotEmpty()) {
                        item(key = "pending_header") {
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
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
                        items(pendingUiStates, key = { "pending_${it.job.folderName}" }) { uiState ->
                            JobBrowserRow(
                                uiState = uiState,
                                scanGeneration = scanState.snapshot.generation,
                                badgeCache = badgeCache,
                                jobRepository = jobRepository,
                                hardwoodsRepository = hardwoodsRepository,
                                onJobClick = onJobClick,
                                onViewCoverSheet = onViewCoverSheet,
                                onView3D = onView3D,
                                onHistoryClick = { selectedHistoryJob = it },
                                sortByName = sortByName,
                                pinnedFolderNames = pinnedFolderNames,
                                onTogglePin = onTogglePin,
                                adminMode = adminMode,
                                onEditLabels = {
                                    editingLabelsFor = if (editingLabelsFor?.folderName == uiState.job.folderName) null else uiState.job
                                },
                            )
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

    BackHandler(enabled = showScheduleDialog) {
        showScheduleDialog = false
    }

    val navBarDeco = LocalNavBarDecoration.current
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
}

data class JobBrowserItemUiState(
    val job: Job,
    val counts: StatusCounts,
    val completionFraction: Float,
    val materialSegments: List<MaterialSegmentData>,
    val hasDeliverySheet: Boolean?,   // null = not yet loaded
    val hasThreeDAssets: Boolean?,    // null = not yet loaded
    val revisionCount: Int?,          // null = not yet loaded; 0 = no history
)

private data class JobBadgeState(
    val hasDeliverySheet: Boolean?,
    val hasThreeDAssets: Boolean?,
    val revisionCount: Int?,
)

@Composable
private fun JobBrowserRow(
    uiState: JobBrowserItemUiState,
    scanGeneration: Long,
    badgeCache: MutableMap<String, JobBadgeState>,
    jobRepository: JobRepository,
    hardwoodsRepository: HardwoodsRepository,
    onJobClick: (Job) -> Unit,
    onViewCoverSheet: (Job) -> Unit,
    onView3D: (Job) -> Unit,
    onHistoryClick: (String) -> Unit,
    sortByName: Boolean,
    pinnedFolderNames: List<String> = emptyList(),
    onTogglePin: (String, Boolean) -> Unit = { _, _ -> },
    positionLabel: String? = null,
    adminMode: Boolean = false,
    onEditLabels: (() -> Unit)? = null,
    dragHandleModifier: Modifier? = null,
) {
    val job = uiState.job

    // Read from the screen-level cache so state survives LazyColumn item recycling.
    // On first entry: null (loading). On re-entry after scroll: immediate cached value.
    val badges: JobBadgeState? = badgeCache[job.folderName]

    LaunchedEffect(job.folderName, scanGeneration) {
        if (badgeCache.containsKey(job.folderName)) return@LaunchedEffect
        badgeCache[job.folderName] = withContext(Dispatchers.IO) {
            val hasDelivery = jobRepository.getJobPdfCatalog(job.folderName).deliverySheet != null
            val has3D = jobRepository.hasThreeDAssets(job.folderName)
            val history = hardwoodsRepository.loadHardwoodsRevisionHistory(job.folderName)
            JobBadgeState(
                hasDeliverySheet = hasDelivery,
                hasThreeDAssets = has3D,
                revisionCount = history?.revisions?.size ?: 0,
            )
        }
    }

    val counts = uiState.counts
    val statusColors = KKCThemeColors.statusColors

    ProgressCard(
        title = job.folderName,
        subtitle = "${counts.complete}/${counts.total} complete",
        fraction = uiState.completionFraction,
        expanded = false,
        segmentedStatusCounts = counts,
        materialSegments = uiState.materialSegments,
        showExpandToggle = false,
        headerActions = {
            if (positionLabel != null) {
                StatusChip(
                    text = positionLabel,
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
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
            CountStatusChip(
                label = "Done",
                count = counts.complete,
                color = statusColors.completeBorder,
                forceFilled = counts.total > 0 && counts.complete >= counts.total
            )
            CountStatusChip("Bad", counts.bad, statusColors.bad)
            CountStatusChip("Skip", counts.skipped, statusColors.skipBorder)
            // Always render so the row is always TextButton-height (40dp) — prevents
            // animateContentSize from firing when History first becomes available
            val hasHistory = badges?.revisionCount?.let { it > 0 } ?: false
            TextButton(
                onClick = { onHistoryClick(job.folderName) },
                enabled = hasHistory,
                modifier = Modifier.alpha(if (hasHistory) 1f else 0f)
            ) {
                Text("History")
            }
            val isPinned = job.folderName in pinnedFolderNames
            PinButton(isPinned = isPinned, onClick = { onTogglePin(job.folderName, isPinned) })
            if (adminMode) {
                if (onEditLabels != null) {
                    IconButton(onClick = onEditLabels) {
                        Icon(Icons.Filled.Sell, contentDescription = "Edit Labels")
                    }
                }
                if (dragHandleModifier != null) {
                    IconButton(modifier = dragHandleModifier, onClick = {}) {
                        Icon(Icons.Filled.DragHandle, contentDescription = "Reorder")
                    }
                }
            }
        },
        inlineContent = {
            // Fixed height so all cards are the same size regardless of badge load state
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (badges?.hasDeliverySheet == true) {
                    FilterChip(
                        selected = false,
                        onClick = { onViewCoverSheet(job) },
                        label = { Text("Cover Sheet") }
                    )
                }
                if (badges?.hasThreeDAssets == true) {
                    FilterChip(
                        selected = false,
                        onClick = { onView3D(job) },
                        label = { Text("View 3D") }
                    )
                }
            }
        },
        onToggleExpanded = {},
        onClick = { onJobClick(job) }
    )
}
