package com.kkc.sheettracker.ui.detail

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.MaterialUiModel
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.CountStatusChip
import com.kkc.sheettracker.ui.components.PageStatusBar
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DETAIL_PARITY_TAG = "KKC_APP_STATE_PARITY_DETAIL"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobDetailScreen(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    appStateFlags: AppStateFeatureFlags,
    jobFolderName: String,
    onMaterialClick: (Material, Int) -> Unit,
    onOpenReferenceDocument: (ReferenceDocType, Int) -> Unit,
    onOpenThreeD: () -> Unit,
    onBack: () -> Unit,
    isClockedInHere: Boolean = false,
    onClockIn: (jobNumber: String, jobName: String) -> Unit = { _, _ -> },
    onLeaveWhileClockedIn: () -> Unit = {}
) {
    val scanState by scanCoordinator.state.collectAsState()
    val progressVersion by progressStore.progressVersion.collectAsState()
    val appMaterialsByKey by appStateStore.materialUiModels.collectAsState()
    val appUiState by appStateStore.uiState.collectAsState()
    val appFlags = remember(appStateFlags) { appStateFlags.snapshot() }
    val useAppState = appFlags.detailEnabled
    val job = remember(scanState.snapshot.generation, jobFolderName) {
        scanState.snapshot.jobs.find { it.folderName == jobFolderName }
    }
    val hasDeliverySheet = remember(jobFolderName) {
        jobRepository.getJobPdfCatalog(jobFolderName).deliverySheet != null
    }
    val listState = rememberLazyListState()

    LaunchedEffect(scanState.snapshot.generation, jobFolderName) {
        withContext(Dispatchers.IO) {
            job?.let { progressStore.pruneLocalStateForJob(it.folderName, it.materials) }
        }
    }

    LaunchedEffect(jobFolderName, scanState.snapshot.generation, progressVersion, appUiState.scanGeneration, appUiState.progressVersion) {
        if (!appFlags.shadowEnabled) return@LaunchedEffect
        val currentJob = job ?: return@LaunchedEffect

        val mismatch = currentJob.materials.firstOrNull { material ->
            val appModel = appMaterialsByKey[com.kkc.sheettracker.data.models.JobMaterialKey(jobFolderName, material.pdfFilename)]
                ?: return@firstOrNull true
            val legacy = progressStore.getMaterialStatusCounts(jobFolderName, material)
            appModel.counts != legacy
        }

        if (mismatch != null) {
            Log.w(
                DETAIL_PARITY_TAG,
                "mismatch folder=$jobFolderName material=${mismatch.pdfFilename} appGen=${appUiState.scanGeneration} legacyGen=${scanState.snapshot.generation} appProgress=${appUiState.progressVersion} legacyProgress=$progressVersion"
            )
        }
    }

    val isClockedInHereState = isClockedInHere
    val onLeaveRef = onLeaveWhileClockedIn
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (isClockedInHereState) onLeaveRef() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(job?.folderName ?: "Loading...") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                windowInsets = WindowInsets.statusBars,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    val currentJob = job
                    if (currentJob != null) {
                        androidx.compose.material3.TextButton(
                            onClick = { onClockIn(currentJob.jobNumber, currentJob.jobName) },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = androidx.compose.ui.graphics.Color(0xFF38A169)
                            )
                        ) {
                            Text(
                                if (isClockedInHere) "● CLOCKED IN" else "CLOCK IN",
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (job == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "reference-doc-buttons") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onOpenReferenceDocument(ReferenceDocType.ASSEMBLY, 1) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("View Assembly")
                        }
                        Button(
                            onClick = { onOpenReferenceDocument(ReferenceDocType.PLANS_ELEVATIONS, 1) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("View Plans & Elevations")
                        }
                        if (hasDeliverySheet) {
                            Button(
                                onClick = { onOpenReferenceDocument(ReferenceDocType.DELIVERY_SHEETS, 1) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("View Cover Sheet")
                            }
                        }
                        Button(
                            onClick = onOpenThreeD,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("View 3D")
                        }
                    }
                }

                items(job.materials, key = { it.pdfFilename }) { material ->
                    val statusColors = KKCThemeColors.statusColors
                    val appMaterialModel: MaterialUiModel? = appMaterialsByKey[com.kkc.sheettracker.data.models.JobMaterialKey(jobFolderName, material.pdfFilename)]
                    val counts = if (useAppState && appMaterialModel != null) {
                        appMaterialModel.counts
                    } else remember(
                        progressVersion,
                        material.pdfFilename,
                        material.fileFingerprint,
                        material.pageCount,
                        material.metadata
                    ) {
                        progressStore.getMaterialStatusCounts(jobFolderName, material)
                    }
                    val trackablePages = remember(
                        progressVersion,
                        material.pdfFilename,
                        material.fileFingerprint,
                        material.pageCount,
                        material.metadata
                    ) {
                        progressStore.getMaterialTrackablePages(material)
                    }
                    val fraction = if (useAppState && appMaterialModel != null) {
                        appMaterialModel.completionFraction
                    } else if (counts.total <= 0) 0f
                    else counts.complete.toFloat() / counts.total.toFloat()
                    ProgressCard(
                        title = material.materialName,
                        subtitle = "${counts.complete}/${counts.total} complete",
                        fraction = fraction,
                        expanded = true,
                        segmentedStatusCounts = counts,
                        hidePrimaryProgressBar = true,
                        showExpandToggle = false,
                        headerActions = {
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
                        onClick = { onMaterialClick(material, trackablePages.firstOrNull() ?: 1) }
                    ) {
                        PageStatusBar(
                            pageCount = counts.total,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp),
                            getStatus = { page ->
                                if (useAppState && appMaterialModel != null) {
                                    appMaterialModel.pageStatuses
                                        .getOrNull((page - 1).coerceAtLeast(0))
                                        ?.status ?: SheetStatus.NOT_STARTED
                                } else {
                                    val physicalPage = trackablePages.getOrNull((page - 1).coerceAtLeast(0)) ?: page
                                    progressStore.getSheetStatus(
                                        jobFolderName,
                                        material.pdfFilename,
                                        physicalPage,
                                        material.fileFingerprint
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
