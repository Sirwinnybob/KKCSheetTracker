package com.kkc.sheettracker.data

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.PartSearchEntry
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanIssue
import com.kkc.sheettracker.data.models.ScanSnapshot
import com.kkc.sheettracker.data.models.ScanSnapshotState
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.data.unified.UnifiedPdfPageCountResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class ScanCoordinator(
    initialBaseDir: File,
    private val jobRepository: JobRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val generation = AtomicLong(0L)
    private val pendingLock = Any()

    @Volatile private var baseDir: File = initialBaseDir
    @Volatile
    var unifiedEngine: UnifiedMetadataEngine = UnifiedMetadataEngineRegistry.getOrCreate(
        baseDir = initialBaseDir,
        isDebugBuild = BuildConfig.DEBUG,
        pdfPageCounter = ::countPdfPagesForEngine
    )
    @Volatile private var lastStalenessSignature: Long? = null
    @Volatile private var refreshInFlight = false
    @Volatile private var pendingRefresh = false
    @Volatile private var pendingForce = false
    @Volatile private var pendingReason: RefreshReason = RefreshReason.APP_START

    private val _state = MutableStateFlow(
        ScanSnapshotState(
            status = ScanStatus.IDLE,
            snapshot = ScanSnapshot(basePath = initialBaseDir.absolutePath)
        )
    )
    val state: StateFlow<ScanSnapshotState> = _state.asStateFlow()

    init {
        jobRepository.attachScanCoordinator(this)
    }

    fun refresh(reason: RefreshReason, force: Boolean = false) {
        synchronized(pendingLock) {
            pendingRefresh = true
            pendingForce = pendingForce || force
            pendingReason = reason
            if (refreshInFlight) return
            refreshInFlight = true
        }

        scope.launch {
            while (true) {
                val (runForce, runReason) = synchronized(pendingLock) {
                    val f = pendingForce
                    val r = pendingReason
                    pendingRefresh = false
                    pendingForce = false
                    f to r
                }
                runRefresh(runReason, runForce)

                val hasPending = synchronized(pendingLock) {
                    pendingRefresh
                }
                if (!hasPending) {
                    synchronized(pendingLock) { refreshInFlight = false }
                    break
                }
            }
        }
    }

    fun invalidate() {
        lastStalenessSignature = null
    }

    fun updateBasePath(path: String) {
        baseDir = File(path)
        jobRepository.updateBaseDir(baseDir)
        unifiedEngine = UnifiedMetadataEngineRegistry.getOrCreate(
            baseDir = baseDir,
            isDebugBuild = BuildConfig.DEBUG,
            pdfPageCounter = ::countPdfPagesForEngine
        )
        unifiedEngine.updateBasePath(baseDir.absolutePath)
        invalidate()
        refresh(RefreshReason.BASE_PATH_CHANGED, force = true)
    }

    fun currentSnapshotJobs(): List<Job> = state.value.snapshot.jobs

    fun currentSearchIndex(): List<PartSearchEntry> {
        return unifiedEngine.getCachedJobInfos().flatMap { info ->
            unifiedEngine.getCachedCncSearchIndex(info.folderName)
                ?: unifiedEngine.getCncSnapshot(info.folderName)?.searchIndex.orEmpty()
        }
    }

    private suspend fun runRefresh(reason: RefreshReason, force: Boolean) {
        refreshMutex.withLock {
            val startedAt = System.currentTimeMillis()
            val previous = _state.value
            _state.value = previous.copy(
                status = ScanStatus.LOADING,
                errorMessage = null,
                lastRefreshReason = reason
            )

            try {
                // Lightweight signature: only checks cache_static.json mtimes (one file per job).
                // Full per-file staleness checks are deferred to refreshJobOnOpen().
                val currentSignature = computeLightStalenessSignature()
                val unchanged = !force &&
                    currentSignature == lastStalenessSignature &&
                    previous.snapshot.basePath == baseDir.absolutePath &&
                    previous.snapshot.generation > 0

                if (unchanged) {
                    _state.value = previous.copy(
                        status = ScanStatus.READY,
                        errorMessage = null,
                        lastRefreshReason = reason
                    )
                    return
                }

                val (jobs, searchIndex, issues, needsDeepLoad) = scanJobsFromCacheOnly()
                val nextSnapshot = ScanSnapshot(
                    generation = generation.incrementAndGet(),
                    basePath = baseDir.absolutePath,
                    jobs = jobs,
                    searchIndex = searchIndex,
                    issues = issues,
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis()
                )

                lastStalenessSignature = currentSignature
                _state.value = ScanSnapshotState(
                    status = ScanStatus.READY,
                    snapshot = nextSnapshot,
                    errorMessage = null,
                    lastRefreshReason = reason
                )

                // Jobs load on per-tap via getCncSnapshot() in detail/viewer screens.
                // No background deep-load needed — generating cache_index.json from RJW
                // already makes every deployed job visible in the list.
            } catch (e: Exception) {
                Log.e("KKC_SCAN", "runRefresh EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
                _state.value = previous.copy(
                    status = ScanStatus.ERROR,
                    errorMessage = e.message ?: "Unknown scan failure",
                    lastRefreshReason = reason
                )
            }
        }
    }

    private fun scanJobsFromCacheOnly(): ScanResult {
        if (!baseDir.exists() || !baseDir.isDirectory)
            return ScanResult(emptyList(), emptyList(), emptyList(), emptyList())
        val (_, needsDeepLoad) = unifiedEngine.listJobsFromCacheIndex()
        // List screen reads cache_index.json directly via rememberCncJobsSpec.
        // Full cache_static.json loads on per-job tap via getCncSnapshot().
        // Background deep load populates snapshot.jobs via updateJobsInState().
        return ScanResult(emptyList(), emptyList(), emptyList(), needsDeepLoad)
    }

    private data class ScanResult(
        val jobs: List<Job>,
        val searchIndex: List<PartSearchEntry>,
        val issues: List<ScanIssue>,
        val needsDeepLoad: List<String>
    )

    private data class JobReprojection(
        val folderName: String,
        val job: Job,
        val searchIndex: List<PartSearchEntry>
    )

    /** Re-projects one job from the engine's in-memory cache and emits an updated ScanState. */
    fun updateJobInState(folderName: String) = updateJobsInState(listOf(folderName))

    /**
     * Re-projects a batch of jobs from the engine's in-memory cache in a single state emission.
     * Filtering the search index once over all changed folders (instead of once per folder) keeps
     * a deep-load batch at O(M) rather than O(K·M), and emits a single StateFlow update.
     */
    fun updateJobsInState(folderNames: List<String>) {
        if (folderNames.isEmpty()) return
        scope.launch {
            val updates = folderNames.distinct().mapNotNull { folderName ->
                val info = unifiedEngine.getJobInfo(folderName)
                    ?: unifiedEngine.getMergedJobInfo(folderName)
                    ?: return@mapNotNull null
                val snapshot = unifiedEngine.getCncSnapshot(folderName) ?: return@mapNotNull null
                JobReprojection(
                    folderName = folderName,
                    job = snapshot.job.copy(
                        lineupPosition = info.lineupPosition,
                        labels = info.labels,
                        hiddenFromProduction = info.hiddenFromProduction,
                        isPending = info.isPending,
                        boardSection = info.boardSection
                    ),
                    searchIndex = snapshot.searchIndex
                )
            }
            if (updates.isEmpty()) return@launch

            val current = _state.value
            val existingJobs = current.snapshot.jobs
            val updatedJobByFolder = updates.associateBy({ it.folderName }, { it.job })
            val existingFolders = existingJobs.mapTo(HashSet()) { it.folderName }
            val replacedJobs = existingJobs.map { updatedJobByFolder[it.folderName] ?: it }
            val appendedJobs = updates.filter { it.folderName !in existingFolders }.map { it.job }
            val updatedJobs = replacedJobs + appendedJobs

            val changedFolders = updatedJobByFolder.keys
            val updatedSearch = current.snapshot.searchIndex
                .filter { it.jobFolderName !in changedFolders } + updates.flatMap { it.searchIndex }

            _state.value = current.copy(
                snapshot = current.snapshot.copy(
                    generation = generation.incrementAndGet(),
                    jobs = updatedJobs,
                    searchIndex = updatedSearch
                )
            )
        }
    }

    /**
     * Phase-2: triggered when a job is opened. Runs the full staleness check for this one
     * job in the background; updates state only if the data actually changed.
     */
    fun refreshJobOnOpen(folderName: String) {
        scope.launch {
            val changed = unifiedEngine.refreshJobDeep(folderName)
            if (changed) updateJobInState(folderName)
        }
    }

    fun refreshJobsDeep(folderNames: Collection<String>) {
        if (folderNames.isEmpty()) return
        scope.launch {
            val changed = folderNames.distinct().filter { unifiedEngine.refreshJobDeep(it) }
            updateJobsInState(changed)
        }
    }

    private fun countPdfPagesForEngine(pdfFile: File): UnifiedPdfPageCountResult {
        return try {
            val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val count = renderer.pageCount
            renderer.close()
            fd.close()
            UnifiedPdfPageCountResult(pageCount = count)
        } catch (e: Exception) {
            UnifiedPdfPageCountResult(pageCount = 0, errorDetail = e.message)
        }
    }

    /**
     * Lightweight staleness check: only examines each job's cache_static.json mtime.
     * Full per-file staleness is deferred to refreshJobOnOpen() for the job being viewed.
     */
    private fun computeLightStalenessSignature(): Long = computeLightStalenessSignature(baseDir)
}
