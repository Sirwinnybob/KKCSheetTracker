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
    private var unifiedEngine: UnifiedMetadataEngine = UnifiedMetadataEngineRegistry.getOrCreate(
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

    fun currentSearchIndex(): List<PartSearchEntry> = state.value.snapshot.searchIndex

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
                    previous.snapshot.jobs.isNotEmpty()

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

                // Background: deep-load any job folders that had no cache_static.json yet
                if (needsDeepLoad.isNotEmpty()) {
                    scope.launch {
                        needsDeepLoad.forEach { folderName ->
                            if (unifiedEngine.refreshJobDeep(folderName)) {
                                updateJobInState(folderName)
                            }
                        }
                    }
                }
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

    /**
     * Phase-1 scan: reads only cache_static.json for each job — no per-file staleness checks.
     * Populates the engine's in-memory cache so snapshot calls are instant.
     */
    private fun scanJobsFromCacheOnly(): ScanResult {
        if (!baseDir.exists() || !baseDir.isDirectory)
            return ScanResult(emptyList(), emptyList(), emptyList(), emptyList())
        val (jobInfos, needsDeepLoad) = unifiedEngine.listJobsFromCacheOnly()
        val jobs = mutableListOf<Job>()
        val search = mutableListOf<PartSearchEntry>()
        val issues = mutableListOf<ScanIssue>()
        jobInfos.forEach { info ->
            if (!File(baseDir, "${info.folderName}/CNC").isDirectory) return@forEach
            val snapshot = unifiedEngine.getCncSnapshot(info.folderName) ?: return@forEach
            jobs += snapshot.job.copy(lineupPosition = info.lineupPosition, labels = info.labels)
            search += snapshot.searchIndex
            issues += snapshot.issues
        }
        return ScanResult(jobs, search, issues, needsDeepLoad)
    }

    private data class ScanResult(
        val jobs: List<Job>,
        val searchIndex: List<PartSearchEntry>,
        val issues: List<ScanIssue>,
        val needsDeepLoad: List<String>
    )

    /** Re-projects one job from the engine's in-memory cache and emits an updated ScanState. */
    fun updateJobInState(folderName: String) {
        scope.launch {
            val snapshot = unifiedEngine.getCncSnapshot(folderName) ?: return@launch
            val current = _state.value
            val existingJobs = current.snapshot.jobs
            val updatedJob = snapshot.job
            val updatedJobs = if (existingJobs.any { it.folderName == folderName }) {
                existingJobs.map { if (it.folderName == folderName) updatedJob else it }
            } else {
                existingJobs + updatedJob
            }
            val updatedSearch = current.snapshot.searchIndex
                .filter { it.jobFolderName != folderName } + snapshot.searchIndex
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
    private fun computeLightStalenessSignature(): Long {
        if (!baseDir.exists() || !baseDir.isDirectory) return Long.MIN_VALUE
        var hash = 1125899906842597L
        fun mix(v: Long) { hash = (hash * 31L) xor v }
        val dirs = baseDir.listFiles() ?: return Long.MIN_VALUE
        mix(dirs.size.toLong())
        dirs.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            mix(dir.name.hashCode().toLong())
            val cacheFile = File(dir, ".metadata/cache_static.json")
            mix(if (cacheFile.isFile) cacheFile.lastModified() else 0L)
        }
        return hash
    }
}
