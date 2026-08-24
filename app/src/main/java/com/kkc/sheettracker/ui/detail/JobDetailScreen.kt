package com.kkc.sheettracker.ui.detail

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import com.kkc.sheettracker.ui.components.PrintDocumentsBottomSheet
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.kkc.sheettracker.data.ClockInState
import com.kkc.sheettracker.ui.components.ClockInButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.data.AppStateFeatureFlags
import com.kkc.sheettracker.data.AppStateStore
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.ArchiveLifecycleClient
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.MaterialUiModel
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.CountStatusChip
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import com.kkc.sheettracker.ui.components.PageStatusBar
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.specialty.CompactSpecialtySection
import com.kkc.sheettracker.ui.specialty.SpecialtySurfaceMode
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val DETAIL_PARITY_TAG = "KKC_APP_STATE_PARITY_DETAIL"

private data class LegacyMaterialProgress(
    val countsByPdfFilename: Map<String, StatusCounts> = emptyMap(),
    val pendingBadPartsByPdfFilename: Map<String, Int> = emptyMap()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobDetailScreen(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    specialtyStateStore: SpecialtyStateStore,
    appStateFlags: AppStateFeatureFlags,
    jobFolderName: String,
    onMaterialClick: (Material, Int) -> Unit,
    onOpenReferenceDocument: (ReferenceDocType, Int) -> Unit,
    onOpenThreeD: () -> Unit,
    onBack: () -> Unit,
    isClockedInHere: Boolean = false,
    onClockIn: (jobNumber: String, jobName: String) -> Unit = { _, _ -> },
    onLeaveWhileClockedIn: () -> Unit = {},
    onSubmitPendingBadParts: ((Material) -> Unit)? = null,
    tabletId: String,
    archiveClientFactory: suspend () -> ArchiveLifecycleClient?,
    onArchiveCompleted: () -> Unit,
    clockInState: ClockInState? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val navBarDeco = LocalNavBarDecoration.current
    val adminEnabled by AdminModeController.enabled.collectAsState()
    LaunchedEffect(Unit) {
        navBarDeco.searchDecoration = null
        navBarDeco.keepSearchDeco = false
    }

    val scanState by scanCoordinator.state.collectAsState()
    val unifiedEngine = remember(scanState.snapshot.basePath) { UnifiedMetadataEngineRegistry.getOrCreate(File(scanState.snapshot.basePath), BuildConfig.DEBUG) }
    val progressVersion by progressStore.progressVersion.collectAsState()
    val appMaterialsByKey by appStateStore.materialUiModels.collectAsState()
    val appUiState by appStateStore.uiState.collectAsState()
    val appFlags = remember(appStateFlags) { appStateFlags.snapshot() }
    val useAppState = appFlags.detailEnabled
    val job by produceState<Job?>(
        initialValue = null,
        unifiedEngine,
        scanState.snapshot.generation,
        jobFolderName
    ) {
        value = withContext(Dispatchers.IO) {
            unifiedEngine.getCncSnapshot(jobFolderName)?.job
        }
    }
    // The legacy fallback remains available while AppState rolls out, but it must not cold-load
    // the tracker cache once per material during composition.
    val legacyMaterialProgress by produceState(
        initialValue = LegacyMaterialProgress(),
        job,
        jobFolderName,
        progressVersion,
        useAppState,
        appMaterialsByKey
    ) {
        val currentJob = job
        val fallbackMaterials = currentJob?.materials.orEmpty().filter { material ->
            !useAppState ||
                appMaterialsByKey[com.kkc.sheettracker.data.models.JobMaterialKey(jobFolderName, material.pdfFilename)] == null
        }
        value = withContext(Dispatchers.IO) {
            LegacyMaterialProgress(
                countsByPdfFilename = fallbackMaterials.associate { material ->
                    material.pdfFilename to progressStore.getMaterialStatusCounts(jobFolderName, material)
                },
                pendingBadPartsByPdfFilename = fallbackMaterials.associate { material ->
                    material.pdfFilename to progressStore.getPendingBadPartsForMaterial(
                        jobFolderName,
                        material.pdfFilename,
                        material.fileFingerprint
                    )
                }
            )
        }
    }
    // Document availability loaded async — avoids blocking the composition thread on I/O
    var hasDeliverySheet by remember(jobFolderName) { mutableStateOf(false) }
    var hasAssemblySheet by remember(jobFolderName) { mutableStateOf(false) }
    var hasPlansElevations by remember(jobFolderName) { mutableStateOf(false) }
    var hasThreeDAssets by remember(jobFolderName) { mutableStateOf(false) }
    LaunchedEffect(jobFolderName) {
        withContext(Dispatchers.IO) {
            hasDeliverySheet = jobRepository.getJobPdfCatalog(jobFolderName).deliverySheet != null
            hasAssemblySheet = jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.ASSEMBLY)
            hasPlansElevations = jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.PLANS_ELEVATIONS)
            hasThreeDAssets = jobRepository.hasThreeDAssets(jobFolderName)
        }
    }
    val listState = rememberLazyListState()
    var suppressLeavePrompt by remember { mutableStateOf(false) }
    var showPrintDialog by remember { mutableStateOf(false) }
    var showArchiveActionSheet by remember(jobFolderName) { mutableStateOf(false) }

    var legacyPageStatuses by remember(jobFolderName) { mutableStateOf<Map<String, Map<Int, SheetStatus>>>(emptyMap()) }

    LaunchedEffect(job, progressVersion, useAppState) {
        if (useAppState) return@LaunchedEffect
        val currentJob = job ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val statuses = mutableMapOf<String, Map<Int, SheetStatus>>()
            for (material in currentJob.materials) {
                val pages = progressStore.getMaterialTrackablePages(material)
                val materialStatuses = mutableMapOf<Int, SheetStatus>()
                for (physicalPage in pages) {
                    val status = progressStore.getSheetStatus(
                        jobFolderName,
                        material.pdfFilename,
                        physicalPage,
                        material.fileFingerprint
                    )
                    materialStatuses[physicalPage] = status
                }
                statuses[material.pdfFilename] = materialStatuses
            }
            legacyPageStatuses = statuses
        }
    }

    LaunchedEffect(scanState.snapshot.generation, jobFolderName) {
        withContext(Dispatchers.IO) {
            job?.let { progressStore.pruneLocalStateForJob(it.folderName, it.materials) }
        }
    }

    LaunchedEffect(jobFolderName, scanState.snapshot.generation, progressVersion, appUiState.scanGeneration, appUiState.progressVersion) {
        if (!appFlags.shadowEnabled) return@LaunchedEffect
        val currentJob = job ?: return@LaunchedEffect

        val mismatch = withContext(Dispatchers.IO) {
            currentJob.materials.firstOrNull { material ->
                val appModel = appMaterialsByKey[com.kkc.sheettracker.data.models.JobMaterialKey(jobFolderName, material.pdfFilename)]
                    ?: return@firstOrNull true
                val legacy = progressStore.getMaterialStatusCounts(jobFolderName, material)
                appModel.counts != legacy
            }
        }

        if (mismatch != null) {
            Log.w(
                DETAIL_PARITY_TAG,
                "mismatch folder=$jobFolderName material=${mismatch.pdfFilename} appGen=${appUiState.scanGeneration} legacyGen=${scanState.snapshot.generation} appProgress=${appUiState.progressVersion} legacyProgress=$progressVersion"
            )
        }
    }

    androidx.compose.runtime.DisposableEffect(isClockedInHere) {
        val shouldNotify = isClockedInHere
        val notifyFn = onLeaveWhileClockedIn
        onDispose { if (shouldNotify && !suppressLeavePrompt) notifyFn() }
    }

    val slowBoundsTransform = remember {
        BoundsTransform { _, _ ->
            tween(durationMillis = 300, easing = FastOutSlowInEasing)
        }
    }

    val sharedBoundsModifier = Modifier

    Scaffold(
        modifier = sharedBoundsModifier,
        topBar = {
            KKCTopAppBar(
                title = {
                    Text(
                        job?.folderName ?: "Loading...",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { scanCoordinator.refreshJobOnOpen(jobFolderName) }) {
                        Icon(Icons.Filled.Refresh, "Refresh job")
                    }
                    if (archiveActionVisible(adminEnabled = adminEnabled, sourceIsLive = true)) {
                        TextButton(onClick = { showArchiveActionSheet = true }) {
                            Text("Archive")
                        }
                    }
                    val currentJob = job
                    if (currentJob != null) {
                        if (clockInState != null) {
                            ClockInButton(
                                clockInState = clockInState,
                                isClockedInHere = isClockedInHere,
                                onClockInClick = { onClockIn(currentJob.jobNumber, currentJob.jobName) }
                            )
                        } else {
                            Button(
                                onClick = { onClockIn(currentJob.jobNumber, currentJob.jobName) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF38A169),
                                    contentColor = Color.White
                                )
                            ) {
                                Text(
                                    if (isClockedInHere) "● CLOCKED IN" else "CLOCK IN",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
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
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 162.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "reference-doc-buttons") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (hasAssemblySheet) {
                            Button(
                                onClick = {
                                    suppressLeavePrompt = true
                                    onOpenReferenceDocument(ReferenceDocType.ASSEMBLY, 1)
                                }
                            ) {
                                Text("View Assembly")
                            }
                        }
                        if (hasPlansElevations) {
                            Button(
                                onClick = {
                                    suppressLeavePrompt = true
                                    onOpenReferenceDocument(ReferenceDocType.PLANS_ELEVATIONS, 1)
                                }
                            ) {
                                Text("View Plans & Elevations")
                            }
                        }
                        if (hasDeliverySheet) {
                            Button(
                                onClick = {
                                    suppressLeavePrompt = true
                                    onOpenReferenceDocument(ReferenceDocType.DELIVERY_SHEETS, 1)
                                }
                            ) {
                                Text("View Cover Sheet")
                            }
                        }
                        if (hasThreeDAssets) {
                            Button(
                                onClick = {
                                    suppressLeavePrompt = true
                                    onOpenThreeD()
                                }
                            ) {
                                Text("View 3D")
                            }
                        }
                        Button(
                            onClick = { showPrintDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Print,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Print")
                        }
                    }
                }

                item(key = "specialty-compact-section") {
                    CompactSpecialtySection(
                        jobFolderName = jobFolderName,
                        specialtyStateStore = specialtyStateStore,
                        mode = SpecialtySurfaceMode.CNC
                    )
                }

                items(job?.materials.orEmpty(), key = { it.pdfFilename }) { material ->
                    val statusColors = KKCThemeColors.statusColors
                    val appMaterialModel: MaterialUiModel? = appMaterialsByKey[com.kkc.sheettracker.data.models.JobMaterialKey(jobFolderName, material.pdfFilename)]
                    val counts = if (useAppState && appMaterialModel != null) {
                        appMaterialModel.counts
                    } else legacyMaterialProgress.countsByPdfFilename[material.pdfFilename]
                        ?: StatusCounts(total = material.pageCount)
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
                    val pendingBadPartCount = if (useAppState && appMaterialModel != null) {
                        appMaterialModel.pendingBadPartCount
                    } else legacyMaterialProgress.pendingBadPartsByPdfFilename[material.pdfFilename] ?: 0
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
                            if (counts.bad > 0) {
                                CountStatusChip("Bad", counts.bad, statusColors.bad)
                            }
                            if (counts.skipped > 0) {
                                CountStatusChip("Skip", counts.skipped, statusColors.skipBorder)
                            }
                            if (pendingBadPartCount > 0 && onSubmitPendingBadParts != null) {
                                TextButton(
                                    onClick = { onSubmitPendingBadParts(material) },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = statusColors.bad
                                    )
                                ) {
                                    Text(
                                        text = "Report $pendingBadPartCount Bad Part${if (pendingBadPartCount == 1) "" else "s"}",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        },
                        onToggleExpanded = {},
                        onClick = {
                            suppressLeavePrompt = true
                            onMaterialClick(material, trackablePages.firstOrNull() ?: 1)
                        }
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
                                    legacyPageStatuses[material.pdfFilename]?.get(physicalPage) ?: SheetStatus.NOT_STARTED
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    if (showPrintDialog) {
        PrintDocumentsBottomSheet(
            jobFolderName = jobFolderName,
            jobRepository = jobRepository,
            onDismissRequest = { showPrintDialog = false }
        )
    }

    if (showArchiveActionSheet) {
        ArchiveLifecycleActionSheet(
            folderName = jobFolderName,
            adminEnabled = adminEnabled,
            tabletId = tabletId,
            clientFactory = archiveClientFactory,
            onCompleted = onArchiveCompleted,
            onDismiss = { showArchiveActionSheet = false },
        )
    }
}
