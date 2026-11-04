package com.kkc.sheettracker.ui.detail

import com.kkc.sheettracker.ui.components.KKCPillAction
import com.kkc.sheettracker.ui.components.KKCPillActionRow
import com.kkc.sheettracker.ui.components.rememberJobPacketActions
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
import androidx.compose.material.icons.filled.Refresh
import com.kkc.sheettracker.ui.components.PrintDocumentsModal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.kkc.sheettracker.data.cnc.CncMachine
import com.kkc.sheettracker.data.cnc.CncMachines
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.ui.components.rememberForegroundMixFiles
import com.kkc.sheettracker.ui.components.mixCatalogSnapshot
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.kkc.sheettracker.ui.viewer.liveViewerOrder
import com.kkc.sheettracker.ui.viewer.ViewerOrder
import com.kkc.sheettracker.data.ClockInState
import com.kkc.sheettracker.ui.components.ClockInButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.kkc.sheettracker.data.models.JobPdfRef
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.mixservice.ViewerMixSelection
import com.kkc.sheettracker.data.mixservice.statusCountsForPages
import com.kkc.sheettracker.data.mixservice.shouldShowPendingBadPartAction
import com.kkc.sheettracker.ui.components.CountStatusChip
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import com.kkc.sheettracker.ui.components.PageStatusBar
import com.kkc.sheettracker.ui.components.ProgressCard
import com.kkc.sheettracker.ui.components.icons.ActionPrintIcon
import com.kkc.sheettracker.ui.components.icons.ActionMixesIcon
import com.kkc.sheettracker.ui.components.icons.ReferenceAssemblyIcon
import com.kkc.sheettracker.ui.components.icons.ReferenceDeliverySheetIcon
import com.kkc.sheettracker.ui.components.icons.ReferencePlansIcon
import com.kkc.sheettracker.ui.components.icons.ReferencePullsIcon
import com.kkc.sheettracker.ui.components.icons.ReferenceView3DIcon
import com.kkc.sheettracker.ui.specialty.CompactSpecialtySection
import com.kkc.sheettracker.ui.specialty.SpecialtySurfaceMode
import com.kkc.sheettracker.ui.theme.KKCThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

private const val DETAIL_PARITY_TAG = "KKC_APP_STATE_PARITY_DETAIL"

private data class LegacyMaterialProgress(
    val countsByPdfFilename: Map<String, StatusCounts> = emptyMap(),
    val pendingBadPartsByPdfFilename: Map<String, Int> = emptyMap()
)

internal enum class JobDetailLoadState {
    LOADING,
    AVAILABLE,
    UNAVAILABLE
}

internal fun jobDetailLoadState(hasResolved: Boolean, hasJob: Boolean): JobDetailLoadState = when {
    !hasResolved -> JobDetailLoadState.LOADING
    hasJob -> JobDetailLoadState.AVAILABLE
    else -> JobDetailLoadState.UNAVAILABLE
}

internal data class JobDetailLoadKey(
    val jobFolderName: String,
    val retryAttempt: Int
)

/**
 * Detail data is independent of the jobs-list generation. Tracker events can refresh that list
 * while a worker is viewing a sheet; using its generation as a Compose key cancelled this load
 * before it could complete.
 */
internal fun jobDetailLoadKey(
    jobFolderName: String,
    retryAttempt: Int,
    @Suppress("UNUSED_PARAMETER") scanGeneration: Long
): JobDetailLoadKey = JobDetailLoadKey(jobFolderName, retryAttempt)

internal enum class JobDetailCatalogStatus {
    FRESH,
    STALE,
    UNAVAILABLE,
}

internal fun jobDetailCatalogStatus(
    enabled: Boolean,
    state: CatalogState?,
): JobDetailCatalogStatus = if (!enabled) JobDetailCatalogStatus.FRESH else when (state) {
    is CatalogState.Live, CatalogState.NoFolder -> JobDetailCatalogStatus.FRESH
    is CatalogState.Offline -> JobDetailCatalogStatus.STALE
    else -> JobDetailCatalogStatus.UNAVAILABLE
}

private data class JobDetailLoadResult(
    val job: Job?,
    val hasResolved: Boolean
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
    onMaterialClick: (Material, Int, ViewerMixSelection?) -> Unit,
    onOpenReferenceDocument: (ReferenceDocType, Int) -> Unit,
    onOpenJobPacketDocument: (JobPdfRef) -> Unit,
    onOpenThreeD: () -> Unit,
    onOpenMixes: () -> Unit = {},
    onBack: () -> Unit,
    isClockedInHere: Boolean = false,
    onClockIn: (jobNumber: String, jobName: String) -> Unit = { _, _ -> },
    onLeaveWhileClockedIn: () -> Unit = {},
    onSubmitPendingBadParts: ((Material) -> Unit)? = null,
    tabletId: String,
    archiveClientFactory: suspend () -> ArchiveLifecycleClient?,
    onArchiveCompleted: () -> Unit,
    loadMixCatalog: Boolean = true,
    /** Mix catalogs are read from this machine; callers without machine context keep Red. */
    cncMachine: CncMachine = CncMachines.scm,
    clockInState: ClockInState? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val app = LocalContext.current.applicationContext as com.kkc.sheettracker.KKCApplication
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
    var loadAttempt by rememberSaveable(jobFolderName) { mutableStateOf(0) }
    val jobLoadKey = jobDetailLoadKey(
        jobFolderName = jobFolderName,
        retryAttempt = loadAttempt,
        scanGeneration = scanState.snapshot.generation
    )
    val jobLoadResult by produceState(
        initialValue = JobDetailLoadResult(job = null, hasResolved = false),
        unifiedEngine,
        jobLoadKey
    ) {
        val loadedJob = withContext(Dispatchers.IO) {
            unifiedEngine.getCncSnapshot(jobFolderName)?.job
        }
        value = JobDetailLoadResult(job = loadedJob, hasResolved = true)
    }
    val job = jobLoadResult.job
    val jobLoadState = jobDetailLoadState(jobLoadResult.hasResolved, job != null)
    val stores = remember(job?.materials?.map { it.materialName }, jobFolderName, loadMixCatalog, cncMachine) {
        if (loadMixCatalog) job?.materials.orEmpty().associate { material ->
            material.materialName to app.mixFilesStores.store(cncMachine, jobFolderName, material.materialName)
        } else emptyMap() // Archived snapshots never query the live machine.
    }
    val catalogStates = rememberForegroundMixFiles(jobFolderName, stores)
    val catalogScope = rememberCoroutineScope()
    val materialEntries = job?.materials.orEmpty().flatMap { material ->
        jobDetailMixRows(material, catalogStates[material.materialName].mixCatalogSnapshot()) { SheetStatus.NOT_STARTED }
            .map { JobDetailMaterialEntry(material, it.title, it.selection) }
    }.sortedBy { entry ->
        when {
            entry.material.metadata?.remakeLabel != null -> 0
            entry.material.metadata?.miscLabel != null -> 1
            else -> 2
        }
    }
    val retryJob = {
        scanCoordinator.refreshJobOnOpen(jobFolderName)
        loadAttempt += 1
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
    var hasPullsSheet by remember(jobFolderName) { mutableStateOf(false) }
    var hasAssemblySheet by remember(jobFolderName) { mutableStateOf(false) }
    var hasPlansElevations by remember(jobFolderName) { mutableStateOf(false) }
    var hasThreeDAssets by remember(jobFolderName) { mutableStateOf(false) }
    LaunchedEffect(jobFolderName) {
        withContext(Dispatchers.IO) {
            val catalog = jobRepository.getJobPdfCatalog(jobFolderName)
            hasDeliverySheet = catalog.deliverySheet != null
            hasPullsSheet = catalog.pullsSheet != null
            hasAssemblySheet = jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.ASSEMBLY)
            hasPlansElevations = jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.PLANS_ELEVATIONS)
            hasThreeDAssets = jobRepository.hasThreeDAssets(jobFolderName)
        }
    }
    val listState = rememberLazyListState()
    var suppressLeavePrompt by remember { mutableStateOf(false) }
    val jobPacketActions = rememberJobPacketActions(jobRepository, jobFolderName) { document ->
        suppressLeavePrompt = true
        onOpenJobPacketDocument(document)
    }
    var showPrintDialog by remember { mutableStateOf(false) }
    var showArchiveActionSheet by remember(jobFolderName) { mutableStateOf(false) }

    var legacyPageStatuses by remember(jobFolderName) { mutableStateOf<Map<String, Map<Int, SheetStatus>>>(emptyMap()) }

    LaunchedEffect(job, progressVersion, useAppState, appMaterialsByKey) {
        val currentJob = job ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val statuses = mutableMapOf<String, Map<Int, SheetStatus>>()
            val fallbackMaterials = currentJob.materials.filter { material ->
                !useAppState ||
                    appMaterialsByKey[com.kkc.sheettracker.data.models.JobMaterialKey(jobFolderName, material.pdfFilename)] == null
            }
            for (material in fallbackMaterials) {
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
                        job?.folderName ?: if (jobLoadState == JobDetailLoadState.LOADING) "Loading..." else jobFolderName,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showPrintDialog = true }) {
                        Icon(ActionPrintIcon, contentDescription = "Print")
                    }
                    IconButton(onClick = retryJob) {
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
        if (jobLoadState == JobDetailLoadState.LOADING) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (jobLoadState == JobDetailLoadState.UNAVAILABLE) {
            JobUnavailableContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onBack = onBack,
                onRetry = retryJob
            )
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
                    val referenceActions = buildList {
                        if (hasAssemblySheet) {
                            add(KKCPillAction("Assembly", {
                                suppressLeavePrompt = true
                                onOpenReferenceDocument(ReferenceDocType.ASSEMBLY, 1)
                            }, ReferenceAssemblyIcon))
                        }
                        if (hasPlansElevations) {
                            add(KKCPillAction("Plans & Elevations", {
                                suppressLeavePrompt = true
                                onOpenReferenceDocument(ReferenceDocType.PLANS_ELEVATIONS, 1)
                            }, ReferencePlansIcon))
                        }
                        if (hasDeliverySheet) {
                            add(KKCPillAction("Cover", {
                                suppressLeavePrompt = true
                                onOpenReferenceDocument(ReferenceDocType.DELIVERY_SHEETS, 1)
                            }, ReferenceDeliverySheetIcon))
                        }
                        if (hasPullsSheet) {
                            add(KKCPillAction("Pulls", {
                                suppressLeavePrompt = true
                                onOpenReferenceDocument(ReferenceDocType.PULLS, 1)
                            }, ReferencePullsIcon))
                        }
                        if (hasThreeDAssets) {
                            add(KKCPillAction("3D", {
                                suppressLeavePrompt = true
                                onOpenThreeD()
                            }, ReferenceView3DIcon))
                        }
                        add(KKCPillAction("Manage code", { onOpenMixes() }, ActionMixesIcon))
                    }
                    KKCPillActionRow(
                        actions = referenceActions,
                        trailingActions = jobPacketActions,
                        fillWidth = true,
                        wrap = true
                    )
                }

                item(key = "specialty-compact-section") {
                    val specialtyScanState by specialtyStateStore.scanState.collectAsState()
                    val specialtyProgressVersion by specialtyStateStore.progressVersion.collectAsState()
                    // See SpecialtyJobDetailScreen for why this must not run synchronously on the main thread.
                    val resolvedItems by produceState(
                        initialValue = emptyList<SpecialtyResolvedItem>(),
                        key1 = specialtyScanState.snapshot.generation,
                        key2 = specialtyProgressVersion,
                        key3 = jobFolderName
                    ) {
                        value = withContext(Dispatchers.IO) { specialtyStateStore.getResolvedItems(jobFolderName) }
                    }
                    val hasSpecialty = remember(resolvedItems) {
                        com.kkc.sheettracker.ui.specialty.buildSpecialtySectionRows(
                            resolvedItems,
                            SpecialtySurfaceMode.CNC
                        ).isNotEmpty()
                    }
                    if (hasSpecialty) {
                        CompactSpecialtySection(
                            jobFolderName = jobFolderName,
                            specialtyStateStore = specialtyStateStore,
                            mode = SpecialtySurfaceMode.CNC,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                items(materialEntries, key = { entry ->
                    "${entry.material.pdfFilename}|${entry.mixSelection?.name.orEmpty()}"
                }) { entry ->
                    val material = entry.material
                    val mixSelection = entry.mixSelection
                    val liveState = catalogStates[material.materialName]
                    val catalogStatus = jobDetailCatalogStatus(loadMixCatalog, liveState)
                    val catalog = liveState.mixCatalogSnapshot()
                    val statusColors = KKCThemeColors.statusColors
                    val appMaterialModel: MaterialUiModel? = appMaterialsByKey[com.kkc.sheettracker.data.models.JobMaterialKey(jobFolderName, material.pdfFilename)]
                    val baseCounts = if (useAppState && appMaterialModel != null) {
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
                    val statusByPage = if (useAppState && appMaterialModel != null) {
                        trackablePages.zip(appMaterialModel.pageStatuses)
                            .associate { (page, snapshot) -> page to snapshot.status }
                    } else {
                        legacyPageStatuses[material.pdfFilename].orEmpty()
                    }
                    val mixRow = jobDetailMixRows(material, catalog) { statusByPage[it] ?: SheetStatus.NOT_STARTED }
                        .firstOrNull { it.selection == mixSelection }
                    val displayedPages = mixSelection?.let {
                        (liveViewerOrder(material, catalog, it.mixFile) as? ViewerOrder.Mix)?.pages.orEmpty()
                    } ?: trackablePages
                    val counts = if (mixSelection == null) {
                        baseCounts
                    } else {
                        statusCountsForPages(displayedPages, statusByPage)
                    }
                    val fraction = if (counts.total <= 0) 0f
                    else counts.complete.toFloat() / counts.total.toFloat()
                    val pendingBadPartCount = if (useAppState && appMaterialModel != null) {
                        appMaterialModel.pendingBadPartCount
                    } else legacyMaterialProgress.pendingBadPartsByPdfFilename[material.pdfFilename] ?: 0
                    // Tinted only while sheets remain open, matching the dashboard rails.
                    val isIncomplete = counts.complete + counts.reNested < counts.total
                    val tintColor = when {
                        !isIncomplete -> null
                        material.metadata?.remakeLabel != null -> statusColors.remakeBg
                        material.metadata?.miscLabel != null -> statusColors.miscBg
                        else -> null
                    }
                    ProgressCard(
                        title = entry.title,
                        tintColor = tintColor,
                        subtitle = buildList {
                            add("${counts.complete}/${counts.total} complete")
                            if (mixRow?.interrupted == true) add("Interrupted")
                            if (mixRow?.error == true) add("Mix file error")
                            if ((mixRow?.unmatched ?: 0) > 0) add("${mixRow?.unmatched} unmatched")
                            if (mixRow?.needsRefresh == true) add("Needs refresh")
                        }.joinToString(" · "),
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
                            if (
                                shouldShowPendingBadPartAction(mixSelection, pendingBadPartCount) &&
                                onSubmitPendingBadParts != null
                            ) {
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
                            val startPage = if (mixSelection == null) {
                                trackablePages.firstOrNull() ?: 1
                            } else {
                                displayedPages.firstOrNull() ?: trackablePages.firstOrNull() ?: 1
                            }
                            suppressLeavePrompt = true
                            onMaterialClick(material, startPage, mixSelection)
                        }
                    ) {
                        PageStatusBar(
                            pageCount = counts.total,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp),
                            getStatus = { page ->
                                val physicalPage = displayedPages.getOrNull((page - 1).coerceAtLeast(0)) ?: page
                                statusByPage[physicalPage] ?: SheetStatus.NOT_STARTED
                            }
                        )
                    }
                    when (catalogStatus) {
                        JobDetailCatalogStatus.STALE -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (catalog != null) "Offline · showing saved mix order." else "Mix order unavailable; showing natural sheet order.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    catalogScope.launch { stores[material.materialName]?.refreshNow() }
                                }) { Text("Retry") }
                            }
                        }
                        JobDetailCatalogStatus.UNAVAILABLE -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (liveState is CatalogState.Outdated) "The CNC mix service needs updating; showing natural sheet order." else "Mix order unavailable; showing natural sheet order.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    catalogScope.launch { stores[material.materialName]?.refreshNow() }
                                }) { Text("Retry") }
                            }
                        }
                        JobDetailCatalogStatus.FRESH -> Unit
                    }
                }
            }
        }
    }
    if (showPrintDialog) {
        PrintDocumentsModal(
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

@Composable
private fun JobUnavailableContent(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "This job is no longer available.",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "It may have been removed, archived, or is still syncing.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("Back to jobs") }
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
