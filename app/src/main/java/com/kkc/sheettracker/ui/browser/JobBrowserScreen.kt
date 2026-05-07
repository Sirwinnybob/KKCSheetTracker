package com.kkc.sheettracker.ui.browser

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.models.JobUiModel
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.MaterialSegmentData
import com.kkc.sheettracker.ui.components.CountStatusChip
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val JOBS_PARITY_TAG = "KKC_APP_STATE_PARITY_JOBS"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobBrowserScreen(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    appStateFlags: AppStateFeatureFlags,
    onJobClick: (Job) -> Unit,
    onViewCoverSheet: (Job) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scanState by scanCoordinator.state.collectAsState()
    val progressVersion by progressStore.progressVersion.collectAsState()
    val appJobModels by appStateStore.jobUiModels.collectAsState()
    val appUiState by appStateStore.uiState.collectAsState()
    val appFlags = remember(appStateFlags) { appStateFlags.snapshot() }
    val useAppState = appFlags.jobsEnabled
    val jobs = scanState.snapshot.jobs
    val isLoading = scanState.status == ScanStatus.LOADING && jobs.isEmpty()
    val appJobModelsByFolder = remember(appJobModels) { appJobModels.associateBy { it.folderName } }

    LaunchedEffect(scanState.snapshot.generation) {
        withContext(Dispatchers.IO) {
            jobs.forEach { progressStore.pruneLocalStateForJob(it.folderName, it.materials) }
        }
    }

    val filteredJobs = remember(jobs, searchQuery, progressVersion) {
        val base = if (searchQuery.isBlank()) {
            jobs
        } else {
            jobs.filter { job ->
                job.jobNumber.contains(searchQuery, ignoreCase = true) ||
                    job.jobName.contains(searchQuery, ignoreCase = true)
            }
        }
        base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
    }

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
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredJobs, key = { it.folderName }) { job ->
                        val hasDeliverySheet = remember(job.folderName) {
                            jobRepository.getJobPdfCatalog(job.folderName).deliverySheet != null
                        }
                        val statusColors = KKCThemeColors.statusColors
                        val appModel: JobUiModel? = appJobModelsByFolder[job.folderName]
                        val counts: StatusCounts = if (useAppState && appModel != null) {
                            appModel.counts
                        } else {
                            progressStore.getJobStatusCounts(job.folderName, job.materials)
                        }
                        val fraction = if (useAppState && appModel != null) {
                            appModel.completionFraction
                        } else if (counts.total <= 0) 0f
                        else counts.complete.toFloat() / counts.total.toFloat()
                        val materialSegments: List<MaterialSegmentData> = if (useAppState && appModel != null) {
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
                        ProgressCard(
                            title = job.folderName,
                            subtitle = "${counts.complete}/${counts.total} complete",
                            fraction = fraction,
                            expanded = false,
                            segmentedStatusCounts = counts,
                            materialSegments = materialSegments,
                            showExpandToggle = false,
                            headerActions = {
                                if (hasDeliverySheet) {
                                    FilterChip(
                                        selected = false,
                                        onClick = { onViewCoverSheet(job) },
                                        label = { Text("Cover Sheet") }
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
                            },
                            onToggleExpanded = {},
                            onClick = { onJobClick(job) }
                        )
                    }
                }
            }
        }
    }
}
