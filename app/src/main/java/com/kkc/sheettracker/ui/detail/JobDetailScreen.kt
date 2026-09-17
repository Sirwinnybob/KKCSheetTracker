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
import androidx.compose.material3.Surface
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
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.mixservice.MixCatalogFetchResult
import com.kkc.sheettracker.data.mixservice.MixCatalogRepository
import com.kkc.sheettracker.data.mixservice.MixCatalogSnapshot
import com.kkc.sheettracker.data.mixservice.MaterialMixEntry
import com.kkc.sheettracker.data.mixservice.activeMixRows
import com.kkc.sheettracker.data.mixservice.pagesForMix
import com.kkc.sheettracker.data.mixservice.ViewerMixSelection
import com.kkc.sheettracker.data.mixservice.statusCountsForPages
import com.kkc.sheettracker.data.mixservice.shouldShowPendingBadPartAction
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

internal data class JobDetailCatalogState(
    val snapshots: Map<String, MixCatalogSnapshot> = emptyMap(),
    val statuses: Map<String, JobDetailCatalogStatus> = emptyMap(),
)

/** Publishes the current cache classification before a refresh suspends for the network. */
internal fun jobDetailCatalogStateBeforeRefresh(
    previous: JobDetailCatalogState,
    materialName: String,
    cached: MixCatalogSnapshot?,
): JobDetailCatalogState {
    val retained = cached ?: previous.snapshots[materialName]
    if (retained == null) {
        return previous.copy(
            snapshots = previous.snapshots - materialName,
            statuses = previous.statuses + (materialName to JobDetailCatalogStatus.UNAVAILABLE),
        )
    }

    val previousStatus = previous.statuses[materialName]
    val pendingStatus = when {
        cached != null && previousStatus != JobDetailCatalogStatus.STALE -> JobDetailCatalogStatus.FRESH
        previousStatus != null -> previousStatus
        else -> JobDetailCatalogStatus.FRESH
    }
    return previous.copy(
        snapshots = previous.snapshots + (materialName to retained),
        statuses = previous.statuses + (materialName to pendingStatus),
    )
}

/** Keeps cached data visible when refresh fails, while exposing its recovery state to the UI. */
internal fun jobDetailCatalogStateAfterRefresh(
    previous: JobDetailCatalogState,
    materialName: String,
    cached: MixCatalogSnapshot?,
    refreshed: MixCatalogFetchResult,
): JobDetailCatalogState {
    val retained = cached ?: previous.snapshots[materialName]
    return when (refreshed) {
        is MixCatalogFetchResult.Success -> previous.copy(
            snapshots = previous.snapshots + (materialName to refreshed.snapshot),
            statuses = previous.statuses + (materialName to JobDetailCatalogStatus.FRESH),
        )
        MixCatalogFetchResult.NetworkError -> if (retained != null) {
            previous.copy(
                snapshots = previous.snapshots + (materialName to retained),
                statuses = previous.statuses + (materialName to JobDetailCatalogStatus.STALE),
            )
        } else {
            previous.copy(
                snapshots = previous.snapshots - materialName,
                statuses = previous.statuses + (materialName to JobDetailCatalogStatus.UNAVAILABLE),
            )
        }
    }
}

/**
 * An empty scoped selection is an unavailable active mix, never an unscoped viewer fallback.
 * A catalog that is unavailable (no cache, refresh failed) still opens: [catalogMaterialEntries]
 * already falls back to the unscoped default PDF page order in that case, so there is nothing to
 * block. Only a resolved-but-empty active mix selection blocks opening.
 */
internal fun canOpenCatalogMaterialEntry(
    entry: MaterialMixEntry,
    @Suppress("UNUSED_PARAMETER") catalogStatus: JobDetailCatalogStatus? = null,
): Boolean = entry.mixSelection?.pageOrder?.isNotEmpty() != false

/** Projects only active catalog ownership into the established viewer route model. */
internal fun catalogMaterialEntries(
    material: Material,
    catalog: MixCatalogSnapshot?
): List<MaterialMixEntry> {
    val naturalOrder = catalogTrackablePages(material)
    val projected = activeMixRows(material, catalog).map { row ->
        val activeMix = row.activeMix
        if (activeMix == null) {
            MaterialMixEntry(material = material, title = material.materialName, mixSelection = null)
        } else {
            val pageOrder = pagesForMix(
                pages = material.metadata?.pages.orEmpty(),
                naturalOrder = naturalOrder,
                programs = activeMix.programs,
            )
            // Preserve the active ownership row even when no visible page maps to it. The
            // empty selection is rendered as unavailable, never widened to all material pages.
            MaterialMixEntry(
                material = material,
                title = row.title,
                mixSelection = ViewerMixSelection(activeMix.name, pageOrder),
            )
        }
    }
    return projected.takeIf { entries -> entries.any { it.mixSelection != null } }
        ?: listOf(MaterialMixEntry(material = material, title = material.materialName, mixSelection = null))
}

private fun catalogTrackablePages(material: Material): List<Int> {
    val fromMetadata = material.metadata?.pages.orEmpty()
        .filterNot { it.hiddenInApp || it.trackingExcluded || it.isPartListContinuation }
        .mapNotNull { page -> page.pageNumber.takeIf { it in 1..material.pageCount } }
        .distinct()
        .sorted()
    return fromMetadata.ifEmpty { (1..material.pageCount).toList() }
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
    onOpenThreeD: () -> Unit,
    onOpenManageCode: () -> Unit = {},
    onBack: () -> Unit,
    isClockedInHere: Boolean = false,
    onClockIn: (jobNumber: String, jobName: String) -> Unit = { _, _ -> },
    onLeaveWhileClockedIn: () -> Unit = {},
    onSubmitPendingBadParts: ((Material) -> Unit)? = null,
    tabletId: String,
    archiveClientFactory: suspend () -> ArchiveLifecycleClient?,
    onArchiveCompleted: () -> Unit,
    mixCatalogRepository: MixCatalogRepository? = null,
    loadMixCatalog: Boolean = true,
    clockInState: ClockInState? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val app = LocalContext.current.applicationContext as com.kkc.sheettracker.KKCApplication
    // Archived jobs are self-contained snapshots. They must never consult the live process cache
    // or network catalog, which may have unrelated revisions (or be unavailable altogether).
    val catalogRepository = if (loadMixCatalog) {
        mixCatalogRepository ?: app.mixCatalogRepository
    } else {
        null
    }
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
    var catalogState by remember(jobFolderName) { mutableStateOf(JobDetailCatalogState()) }
    var catalogRetryMaterial by remember(jobFolderName) { mutableStateOf<String?>(null) }
    var catalogRetryAttempt by remember(jobFolderName) { mutableIntStateOf(0) }
    LaunchedEffect(job, jobFolderName, catalogRepository, catalogRetryAttempt) {
        val repository = catalogRepository ?: return@LaunchedEffect
        val currentJob = job ?: return@LaunchedEffect
        val requestedMaterial = catalogRetryMaterial
        val materialsToLoad = if (requestedMaterial == null) {
            currentJob.materials
        } else {
            currentJob.materials.filter { it.materialName == requestedMaterial }
        }
        materialsToLoad.forEach { material ->
            val cached = repository.cached(jobFolderName, material.materialName)
            if (!isActive) return@forEach
            catalogState = jobDetailCatalogStateBeforeRefresh(
                previous = catalogState,
                materialName = material.materialName,
                cached = cached,
            )
            val refreshed = repository.refresh(jobFolderName, material.materialName)
            catalogState = jobDetailCatalogStateAfterRefresh(
                previous = catalogState,
                materialName = material.materialName,
                cached = cached,
                refreshed = refreshed,
            )
        }
    }
    val mixCatalogs = catalogState.snapshots
    val materialEntries = remember(job?.materials, mixCatalogs) {
        job?.materials.orEmpty().flatMap { material ->
            catalogMaterialEntries(material, mixCatalogs[material.materialName])
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
                                Text("Assembly")
                            }
                        }
                        if (hasPlansElevations) {
                            Button(
                                onClick = {
                                    suppressLeavePrompt = true
                                    onOpenReferenceDocument(ReferenceDocType.PLANS_ELEVATIONS, 1)
                                }
                            ) {
                                Text("Plans & Elevations")
                            }
                        }
                        if (hasDeliverySheet) {
                            Button(
                                onClick = {
                                    suppressLeavePrompt = true
                                    onOpenReferenceDocument(ReferenceDocType.DELIVERY_SHEETS, 1)
                                }
                            ) {
                                Text("Delivery")
                            }
                        }
                        if (hasPullsSheet) {
                            Button(
                                onClick = {
                                    suppressLeavePrompt = true
                                    onOpenReferenceDocument(ReferenceDocType.PULLS, 1)
                                }
                            ) {
                                Text("Pulls")
                            }
                        }
                        if (hasThreeDAssets) {
                            Button(
                                onClick = {
                                    suppressLeavePrompt = true
                                    onOpenThreeD()
                                }
                            ) {
                                Text("3D")
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
                        var specialtyHeightPx by remember { mutableIntStateOf(0) }
                        val density = LocalDensity.current
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(com.kkc.sheettracker.ui.theme.KKCSpacing.s),
                            verticalAlignment = Alignment.Top
                        ) {
                            CompactSpecialtySection(
                                jobFolderName = jobFolderName,
                                specialtyStateStore = specialtyStateStore,
                                mode = SpecialtySurfaceMode.CNC,
                                modifier = Modifier
                                    .weight(0.75f)
                                    .onSizeChanged { specialtyHeightPx = it.height }
                            )
                            Surface(
                                onClick = { onOpenManageCode() },
                                modifier = Modifier
                                    .weight(0.25f)
                                    .then(
                                        if (specialtyHeightPx > 0) {
                                            Modifier.height(with(density) { specialtyHeightPx.toDp() })
                                        } else {
                                            Modifier
                                        }
                                    ),
                                shape = MaterialTheme.shapes.large,
                                tonalElevation = 3.dp,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = com.kkc.sheettracker.ui.theme.KKCSpacing.s),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Manage code",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
                        Surface(
                            onClick = { onOpenManageCode() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            tonalElevation = 3.dp,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = com.kkc.sheettracker.ui.theme.KKCSpacing.cardPaddingSmall, vertical = com.kkc.sheettracker.ui.theme.KKCSpacing.xl),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Manage code",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                items(materialEntries, key = { entry ->
                    "${entry.material.pdfFilename}|${entry.mixSelection?.name.orEmpty()}"
                }) { entry ->
                    val material = entry.material
                    val mixSelection = entry.mixSelection
                    val catalogStatus = catalogState.statuses[material.materialName]
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
                    val displayedPages = mixSelection?.pageOrder ?: trackablePages
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
                    ProgressCard(
                        title = entry.title,
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
                            if (canOpenCatalogMaterialEntry(entry, catalogStatus)) {
                                val startPage = if (mixSelection == null) {
                                    trackablePages.firstOrNull() ?: 1
                                } else {
                                    mixSelection.pageOrder.firstOrNull()
                                }
                                if (startPage != null) {
                                    suppressLeavePrompt = true
                                    onMaterialClick(material, startPage, mixSelection)
                                }
                            }
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
                    if (mixSelection != null && mixSelection.pageOrder.isEmpty()) {
                        Text(
                            text = "${mixSelection.name} has no visible sheets. Refresh the catalog or go back to jobs.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                catalogRetryMaterial = material.materialName
                                catalogRetryAttempt += 1
                            }) { Text("Retry catalog") }
                            TextButton(onClick = onBack) { Text("Back to jobs") }
                        }
                    }
                    when (catalogStatus) {
                        JobDetailCatalogStatus.STALE -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Showing saved mix information; catalog refresh failed.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    catalogRetryMaterial = material.materialName
                                    catalogRetryAttempt += 1
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
                                    "Mix catalog unavailable; try again before opening a mix.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    catalogRetryMaterial = material.materialName
                                    catalogRetryAttempt += 1
                                }) { Text("Retry") }
                            }
                        }
                        JobDetailCatalogStatus.FRESH, null -> Unit
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
