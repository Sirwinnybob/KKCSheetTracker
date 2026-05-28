package com.kkc.sheettracker.ui.browser

import android.util.Log
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
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ViewList
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.DeliveryScheduleRepository
import com.kkc.sheettracker.data.HardwoodsRepository
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.JobUiModel
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.DeliveryScheduleDialog
import com.kkc.sheettracker.ui.components.DeliveryScheduleWidget
import com.kkc.sheettracker.ui.components.JobBoardGrid
import com.kkc.sheettracker.ui.components.JobBoardItem
import com.kkc.sheettracker.ui.components.MaterialSegmentData
import com.kkc.sheettracker.ui.components.CountStatusChip
import com.kkc.sheettracker.ui.components.HardwoodsRevisionHistorySheet
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.SortToggleBar
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    onJobClick: (Job) -> Unit,
    onOpenHardwoodsChange: (jobFolderName: String, docType: HardwoodDocType, rowId: String) -> Unit,
    onViewCoverSheet: (Job) -> Unit,
    onView3D: (Job) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortByName by rememberSaveable { mutableStateOf(false) }
    var boardView by rememberSaveable { mutableStateOf(false) }
    var selectedHistoryJob by rememberSaveable { mutableStateOf<String?>(null) }
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

    val jobUiStates = remember(filteredJobs, scanState.snapshot.generation, progressVersion, useAppState, appJobModelsByFolder) {
        filteredJobs.map { job ->
            val appModel = appJobModelsByFolder[job.folderName]
            val counts = if (useAppState && appModel != null) {
                appModel.counts
            } else {
                progressStore.getJobStatusCounts(job.folderName, job.materials)
            }
            val fraction = if (useAppState && appModel != null) {
                appModel.completionFraction
            } else if (counts.total <= 0) 0f
            else counts.complete.toFloat() / counts.total.toFloat()
            val materialSegments = if (useAppState && appModel != null) {
                appModel.materials.map { material ->
                    MaterialSegmentData(
                        materialName = material.materialName,
                        counts = material.counts
                    )
                }
            } else {
                job.materials.map { material ->
                    MaterialSegmentData(
                        materialName = material.materialName,
                        counts = progressStore.getMaterialStatusCounts(job.folderName, material)
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
                title = { Text("KKC Sheet Tracker") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }) { Icon(Icons.Default.Refresh, "Refresh") }
                    IconButton(
                        onClick = { boardView = !boardView },
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
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Filter jobs by number or name...") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )
            SortToggleBar(
                sortByName = sortByName,
                onSortChange = { sortByName = it }
            )
            Text(
                text = if (searchQuery.isBlank()) {
                    "${jobs.size} jobs"
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
                        items = filteredJobs.map { JobBoardItem(it.folderName, it.jobNumber, it.jobName) },
                        jobRepository = jobRepository,
                        onItemClick = { boardItem ->
                            filteredJobs.find { it.folderName == boardItem.folderName }
                                ?.let { onJobClick(it) }
                        },
                        modifier = Modifier.fillMaxSize(),
                        scanGeneration = scanState.snapshot.generation
                    )
                } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(jobUiStates, key = { it.job.folderName }) { uiState ->
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
                        )
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

    BackHandler(enabled = showScheduleDialog) {
        showScheduleDialog = false
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
