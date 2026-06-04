package com.kkc.sheettracker.data

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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
                val currentSignature = computeStalenessSignature(baseDir)
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

                val (jobs, searchIndex, issues) = scanJobsFromEngine()
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
            } catch (e: Exception) {
                _state.value = previous.copy(
                    status = ScanStatus.ERROR,
                    errorMessage = e.message ?: "Unknown scan failure",
                    lastRefreshReason = reason
                )
            }
        }
    }

    private fun scanJobsFromEngine(): Triple<List<Job>, List<PartSearchEntry>, List<ScanIssue>> {
        if (!baseDir.exists() || !baseDir.isDirectory) return Triple(emptyList(), emptyList(), emptyList())
        val jobs = mutableListOf<Job>()
        val search = mutableListOf<PartSearchEntry>()
        val issues = mutableListOf<ScanIssue>()
        unifiedEngine.listJobs().forEach { info ->
            if (!File(baseDir, "${info.folderName}/CNC").isDirectory) return@forEach
            val snapshot = unifiedEngine.getCncSnapshot(info.folderName) ?: return@forEach
            jobs += snapshot.job.copy(lineupPosition = info.lineupPosition, labels = info.labels)
            search += snapshot.searchIndex
            issues += snapshot.issues
        }
        // Preserve production order (as set by listJobs) rather than sorting by folder name
        return Triple(jobs, search, issues)
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

    private fun computeStalenessSignature(root: File): Long {
        if (!root.exists() || !root.isDirectory) return Long.MIN_VALUE

        var hash = 1125899906842597L
        fun mix(value: Long) {
            hash = (hash * 31L) xor value
        }
        val jobs = unifiedEngine.listJobs()
        mix(jobs.size.toLong())
        jobs.forEach { job ->
            mix(job.folderName.hashCode().toLong())
            val sig = unifiedEngine.getSignatures(job.folderName)
            mix(sig.staticSignature)
        }
        return hash
    }
}
