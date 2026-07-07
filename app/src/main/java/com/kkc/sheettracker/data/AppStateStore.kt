package com.kkc.sheettracker.data

import android.util.Log
import com.kkc.sheettracker.data.models.AppDerivationStatus
import com.kkc.sheettracker.data.models.AppUiState
import com.kkc.sheettracker.data.models.DashboardRecentMaterialItem
import com.kkc.sheettracker.data.models.DashboardUiModel
import com.kkc.sheettracker.data.models.JobMaterialKey
import com.kkc.sheettracker.data.models.JobUiModel
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.MaterialUiModel
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.SheetStatusKey
import com.kkc.sheettracker.data.models.SheetStatusSnapshot
import com.kkc.sheettracker.data.models.StatusCounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val APP_STATE_TAG = "KKC_APP_STATE"
private const val DASHBOARD_RECENT_LIMIT = 4

class AppStateStore(
    private val scanCoordinator: ScanCoordinator,
    private val progressStore: ProgressStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recomputeIntents = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    private val _uiState = MutableStateFlow(AppUiState())
    private val _dashboardUiModel = MutableStateFlow(DashboardUiModel())
    private val _jobUiModels = MutableStateFlow<List<JobUiModel>>(emptyList())
    private val _materialUiModels = MutableStateFlow<Map<JobMaterialKey, MaterialUiModel>>(emptyMap())
    private val _sheetStatusSnapshots = MutableStateFlow<Map<SheetStatusKey, SheetStatusSnapshot>>(emptyMap())

    private val _lastProgressVersion = MutableStateFlow(0L)
    val lastProgressVersion: StateFlow<Long> = _lastProgressVersion.asStateFlow()

    private val _activeJobFolderName = MutableStateFlow<String?>(null)

    fun notifyJobFocus(folderName: String?) {
        _activeJobFolderName.value = folderName
    }

    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    val dashboardUiModel: StateFlow<DashboardUiModel> = _dashboardUiModel.asStateFlow()
    val jobUiModels: StateFlow<List<JobUiModel>> = _jobUiModels.asStateFlow()
    val materialUiModels: StateFlow<Map<JobMaterialKey, MaterialUiModel>> = _materialUiModels.asStateFlow()
    val sheetStatusSnapshots: StateFlow<Map<SheetStatusKey, SheetStatusSnapshot>> = _sheetStatusSnapshots.asStateFlow()

    init {
        recomputeIntents.tryEmit(System.currentTimeMillis())
        monitorProgressBursts()
        startDerivation()
    }

    fun requestRecompute() {
        recomputeIntents.tryEmit(System.currentTimeMillis())
    }

    @OptIn(FlowPreview::class)
    private fun startDerivation() {
        scope.launch {
            combine(
                scanCoordinator.state,
                progressStore.progressVersion
                    .onStart { emit(progressStore.progressVersion.value) }
                    .debounce { if (_activeJobFolderName.value != null) 3_000L else 120L },
                recomputeIntents.onStart { emit(System.currentTimeMillis()) }
            ) { scanState, progressVersion, _ ->
                Triple(scanState, progressVersion, System.currentTimeMillis())
            }
                .distinctUntilChangedBy { (scanState, progressVersion, _) ->
                    // Suppress redundant derive runs when invalidations resolve to the same state.
                    "${scanState.snapshot.generation}|$progressVersion|${scanState.status}"
                }
                .collectLatest { (scanState, progressVersion, _) ->
                _lastProgressVersion.value = progressVersion
                val derivingStartedAt = System.currentTimeMillis()
                _uiState.value = _uiState.value.copy(
                    status = AppDerivationStatus.DERIVING,
                    isRefreshing = true,
                    scanGeneration = scanState.snapshot.generation,
                    progressVersion = progressVersion,
                    scanIssues = scanState.snapshot.issues,
                    errorMessage = scanState.errorMessage
                )

                try {
                    val derivation = withContext(Dispatchers.Default) {
                        derive(scanState.snapshot.jobs)
                    }
                    emitDistinctSnapshots(derivation)

                    _uiState.value = AppUiState(
                        status = AppDerivationStatus.READY,
                        isRefreshing = scanState.status == com.kkc.sheettracker.data.models.ScanStatus.LOADING,
                        scanGeneration = scanState.snapshot.generation,
                        progressVersion = progressVersion,
                        lastUpdatedAt = System.currentTimeMillis(),
                        scanIssues = scanState.snapshot.issues,
                        errorMessage = scanState.errorMessage
                    )

                    Log.i(
                        APP_STATE_TAG,
                        "derive_done duration_ms=${System.currentTimeMillis() - derivingStartedAt} jobs=${scanState.snapshot.jobs.size} generation=${scanState.snapshot.generation} progress_version=$progressVersion"
                    )
                } catch (e: Exception) {
                    Log.e(APP_STATE_TAG, "derive_failed", e)
                    _uiState.value = AppUiState(
                        status = AppDerivationStatus.ERROR,
                        isRefreshing = false,
                        scanGeneration = scanState.snapshot.generation,
                        progressVersion = progressVersion,
                        lastUpdatedAt = System.currentTimeMillis(),
                        scanIssues = scanState.snapshot.issues,
                        errorMessage = e.message ?: "Derivation error"
                    )
                }
                }
        }
    }

    private fun monitorProgressBursts() {
        scope.launch {
            var burstCount = 0
            var lastEventAt = 0L
            progressStore.progressVersion.collect { version ->
                val now = System.currentTimeMillis()
                burstCount = if (now - lastEventAt <= 300L) burstCount + 1 else 1
                lastEventAt = now
                if (burstCount >= 3) {
                    Log.d(
                        APP_STATE_TAG,
                        "progress_burst count=$burstCount latest_version=$version"
                    )
                }
            }
        }
    }

    private data class DerivationOutput(
        val dashboard: DashboardUiModel,
        val jobs: List<JobUiModel>,
        val materialsByKey: Map<JobMaterialKey, MaterialUiModel>,
        val sheetStatuses: Map<SheetStatusKey, SheetStatusSnapshot>
    )

    private fun derive(jobs: List<com.kkc.sheettracker.data.models.Job>): DerivationOutput {
        val sheetStatusMap = LinkedHashMap<SheetStatusKey, SheetStatusSnapshot>()
        val materialMap = LinkedHashMap<JobMaterialKey, MaterialUiModel>()
        val jobModels = ArrayList<JobUiModel>(jobs.size)
        val recentInProgressMaterials = mutableListOf<DashboardRecentMaterialItem>()
        val incompleteRemakeMaterials = mutableListOf<DashboardRecentMaterialItem>()

        var totalSheets = 0
        var completedSheets = 0
        var badPartsSheets = 0
        var skippedSheets = 0

        for (job in jobs) {
            progressStore.pruneLocalStateForJob(job.folderName, job.materials)
            val lastTouchesByPdf = progressStore.getMaterialLastTouches(job.folderName)

            val materialModels = mutableListOf<MaterialUiModel>()
            var jobComplete = 0
            var jobBad = 0
            var jobSkipped = 0
            var jobNotStarted = 0
            var jobTotal = 0

            for (material in job.materials) {
                val materialDerivation = deriveMaterial(
                    jobFolderName = job.folderName,
                    material = material
                )
                val materialUiModel = materialDerivation.uiModel
                materialModels += materialUiModel
                materialMap[JobMaterialKey(job.folderName, material.pdfFilename)] = materialUiModel
                sheetStatusMap.putAll(materialDerivation.sheetStatuses)

                jobComplete += materialUiModel.counts.complete
                jobBad += materialUiModel.counts.bad
                jobSkipped += materialUiModel.counts.skipped
                jobNotStarted += materialUiModel.counts.notStarted
                jobTotal += materialUiModel.counts.total

                val touch = lastTouchesByPdf[material.pdfFilename]
                if (touch != null) {
                    val counts = materialUiModel.counts
                    val isPartiallyWorked = isRecentInProgressMaterial(counts)
                    if (isPartiallyWorked) {
                        val visiblePages = trackablePages(material)
                        val clampedPage = nearestTrackablePage(touch.page, visiblePages)
                        val nextIncompletePage = nextIncompletePage(
                            trackablePages = visiblePages,
                            pageStatusByNumber = materialDerivation.pageStatusByNumber,
                            fallbackPage = clampedPage
                        )
                        val pageMeta = material.metadata?.pages?.firstOrNull { it.pageNumber == nextIncompletePage }
                            ?: material.metadata?.pages?.getOrNull((nextIncompletePage - 1).coerceAtLeast(0))
                        recentInProgressMaterials += DashboardRecentMaterialItem(
                            jobFolderName = job.folderName,
                            jobNumber = job.jobNumber,
                            materialName = material.materialName,
                            pdfFilename = material.pdfFilename,
                            fileFingerprint = material.fileFingerprint,
                            lastTouchedPage = clampedPage,
                            nextIncompletePage = nextIncompletePage,
                            lastTouchedAtMs = touch.touchedAtMs,
                            counts = counts,
                            completionFraction = materialUiModel.completionFraction,
                            thumbnailPath = pageMeta?.thumbnailPath
                        )
                    }
                }

                val remakeLabel = material.metadata?.remakeLabel
                if (remakeLabel != null && materialUiModel.counts.complete < materialUiModel.counts.total) {
                    val visiblePages = trackablePages(material)
                    val nextIncompletePage = nextIncompletePage(
                        trackablePages = visiblePages,
                        pageStatusByNumber = materialDerivation.pageStatusByNumber,
                        fallbackPage = visiblePages.firstOrNull() ?: 1
                    )
                    val pageMeta = material.metadata.pages.firstOrNull { it.pageNumber == nextIncompletePage }
                        ?: material.metadata.pages.getOrNull((nextIncompletePage - 1).coerceAtLeast(0))
                    incompleteRemakeMaterials += DashboardRecentMaterialItem(
                        jobFolderName = job.folderName,
                        jobNumber = job.jobNumber,
                        materialName = material.materialName,
                        pdfFilename = material.pdfFilename,
                        fileFingerprint = material.fileFingerprint,
                        lastTouchedPage = nextIncompletePage,
                        nextIncompletePage = nextIncompletePage,
                        lastTouchedAtMs = 0L,
                        counts = materialUiModel.counts,
                        completionFraction = materialUiModel.completionFraction,
                        thumbnailPath = pageMeta?.thumbnailPath
                    )
                }
            }

            totalSheets += jobTotal
            completedSheets += jobComplete
            badPartsSheets += jobBad
            skippedSheets += jobSkipped

            jobModels += JobUiModel(
                folderName = job.folderName,
                jobNumber = job.jobNumber,
                jobName = job.jobName,
                counts = StatusCounts(
                    total = jobTotal,
                    complete = jobComplete,
                    bad = jobBad,
                    skipped = jobSkipped,
                    notStarted = jobNotStarted
                ),
                completionFraction = if (jobTotal > 0) jobComplete.toFloat() / jobTotal.toFloat() else 0f,
                materials = materialModels
            )
        }

        val dashboardModel = DashboardUiModel(
            totalJobs = jobs.size,
            totalSheets = totalSheets,
            completedSheets = completedSheets,
            badPartsSheets = badPartsSheets,
            skippedSheets = skippedSheets,
            recentInProgressMaterials = recentInProgressMaterials
                .sortedByDescending { it.lastTouchedAtMs }
                .take(DASHBOARD_RECENT_LIMIT),
            incompleteRemakeMaterials = incompleteRemakeMaterials
                .sortedWith(compareBy({ it.jobFolderName }, { it.materialName }))
                .take(DASHBOARD_RECENT_LIMIT)
        )

        return DerivationOutput(
            dashboard = dashboardModel,
            jobs = jobModels,
            materialsByKey = materialMap,
            sheetStatuses = sheetStatusMap
        )
    }

    private data class MaterialDerivation(
        val uiModel: MaterialUiModel,
        val sheetStatuses: Map<SheetStatusKey, SheetStatusSnapshot>,
        val pageStatusByNumber: Map<Int, SheetStatusSnapshot>
    )

    private fun deriveMaterial(
        jobFolderName: String,
        material: Material
    ): MaterialDerivation {
        val fileFingerprint = material.fileFingerprint
        val visiblePages = trackablePages(material)
        val pageStatuses = ArrayList<SheetStatusSnapshot>(visiblePages.size)
        val keyedStatuses = LinkedHashMap<SheetStatusKey, SheetStatusSnapshot>()
        val pageStatusByNumber = LinkedHashMap<Int, SheetStatusSnapshot>()

        var complete = 0
        var bad = 0
        var skipped = 0
        var notStarted = 0
        var reNestedCount = 0

        for (page in visiblePages) {
            val status = progressStore.getSheetStatus(
                jobFolderName = jobFolderName,
                pdfFilename = material.pdfFilename,
                page = page,
                fileFingerprint = fileFingerprint
            )
            val committedBadCount = progressStore.getBadParts(
                jobFolderName = jobFolderName,
                pdfFilename = material.pdfFilename,
                page = page,
                fileFingerprint = fileFingerprint,
                includeDraft = false
            ).size
            val hasDraftBadParts = progressStore.getDraftBadParts(
                jobFolderName = jobFolderName,
                pdfFilename = material.pdfFilename,
                page = page,
                fileFingerprint = fileFingerprint
            ).isNotEmpty()

            val snapshot = SheetStatusSnapshot(
                status = status,
                committedBadCount = committedBadCount,
                hasDraftBadParts = hasDraftBadParts
            )
            pageStatuses += snapshot
            pageStatusByNumber[page] = snapshot
            keyedStatuses[SheetStatusKey(jobFolderName, material.pdfFilename, page, fileFingerprint)] = snapshot

            when (status) {
                SheetStatus.HAS_BAD_PARTS -> {
                    complete++
                    bad++
                }
                SheetStatus.SKIPPED -> skipped++
                SheetStatus.COMPLETE -> complete++
                SheetStatus.RE_NESTED -> reNestedCount++
                else -> notStarted++
            }
        }

        val total = visiblePages.size - reNestedCount
        val counts = StatusCounts(
            total = total,
            complete = complete,
            bad = bad,
            skipped = skipped,
            notStarted = notStarted
        )

        val pendingBadPartCount = progressStore.getPendingBadPartsForMaterial(
            jobFolderName = jobFolderName,
            pdfFilename = material.pdfFilename,
            fileFingerprint = fileFingerprint
        )

        return MaterialDerivation(
            uiModel = MaterialUiModel(
                pdfFilename = material.pdfFilename,
                materialName = material.materialName,
                counts = counts,
                completionFraction = if (total > 0) complete.toFloat() / total.toFloat() else 0f,
                pageStatuses = pageStatuses,
                pendingBadPartCount = pendingBadPartCount
            ),
            sheetStatuses = keyedStatuses,
            pageStatusByNumber = pageStatusByNumber
        )
    }

    private fun trackablePages(material: Material): List<Int> {
        val metadataPages = material.metadata?.pages.orEmpty()
        val visibleFromMetadata = metadataPages
            .filterNot { it.hiddenInApp || it.trackingExcluded || it.isPartListContinuation }
            .mapNotNull { page ->
                val p = page.pageNumber
                p.takeIf { it in 1..material.pageCount }
            }
            .distinct()
            .sorted()
        return if (visibleFromMetadata.isNotEmpty()) visibleFromMetadata else (1..material.pageCount).toList()
    }

    private fun nextIncompletePage(
        trackablePages: List<Int>,
        pageStatusByNumber: Map<Int, SheetStatusSnapshot>,
        fallbackPage: Int
    ): Int {
        return trackablePages.firstOrNull { page ->
            when (pageStatusByNumber[page]?.status ?: SheetStatus.NOT_STARTED) {
                SheetStatus.NOT_STARTED, SheetStatus.IN_PROGRESS -> true
                SheetStatus.COMPLETE, SheetStatus.SKIPPED, SheetStatus.HAS_BAD_PARTS, SheetStatus.RE_NESTED -> false
            }
        } ?: fallbackPage
    }

    private fun nearestTrackablePage(targetPage: Int, pages: List<Int>): Int {
        if (pages.isEmpty()) return targetPage.coerceAtLeast(1)
        if (targetPage in pages) return targetPage
        return pages.minByOrNull { kotlin.math.abs(it - targetPage) } ?: pages.first()
    }

    private fun emitDistinctSnapshots(output: DerivationOutput) {
        if (_dashboardUiModel.value != output.dashboard) {
            _dashboardUiModel.value = output.dashboard
        }
        if (_jobUiModels.value != output.jobs) {
            _jobUiModels.value = output.jobs
        }
        if (_materialUiModels.value != output.materialsByKey) {
            _materialUiModels.value = output.materialsByKey
        }
        if (_sheetStatusSnapshots.value != output.sheetStatuses) {
            _sheetStatusSnapshots.value = output.sheetStatuses
        }
    }
}
