package com.kkc.sheettracker.data

import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.models.AssemblyJob
import com.kkc.sheettracker.data.models.AssemblyScanSnapshot
import com.kkc.sheettracker.data.models.AssemblyScanState
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
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

class AssemblyScanCoordinator(
    initialBaseDir: File,
    private val jobRepository: JobRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val generation = AtomicLong(0L)
    private val pendingLock = Any()

    @Volatile
    private var baseDir: File = initialBaseDir
    @Volatile
    private var unifiedEngine: UnifiedMetadataEngine = UnifiedMetadataEngineRegistry.getOrCreate(
        baseDir = initialBaseDir,
        isDebugBuild = BuildConfig.DEBUG
    )
    @Volatile private var lastStalenessSignature: Long? = null
    @Volatile private var refreshInFlight = false
    @Volatile private var pendingRefresh = false
    @Volatile private var pendingForce = false
    @Volatile private var pendingReason: RefreshReason = RefreshReason.APP_START

    private val _state = MutableStateFlow(
        AssemblyScanState(
            status = ScanStatus.IDLE,
            snapshot = AssemblyScanSnapshot(basePath = initialBaseDir.absolutePath)
        )
    )
    val state: StateFlow<AssemblyScanState> = _state.asStateFlow()

    fun updateBasePath(path: String) {
        baseDir = File(path)
        jobRepository.updateBaseDir(baseDir)
        unifiedEngine = UnifiedMetadataEngineRegistry.getOrCreate(
            baseDir = baseDir,
            isDebugBuild = BuildConfig.DEBUG
        )
        unifiedEngine.updateBasePath(baseDir.absolutePath)
        lastStalenessSignature = null
    }

    /**
     * Coalesces overlapping refresh() calls into a single in-flight run instead of running two
     * full concurrent scans (mirrors ScanCoordinator/HardwoodsScanCoordinator).
     */
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

                val hasPending = synchronized(pendingLock) { pendingRefresh }
                if (!hasPending) {
                    synchronized(pendingLock) { refreshInFlight = false }
                    break
                }
            }
        }
    }

    private suspend fun runRefresh(reason: RefreshReason, force: Boolean) {
        refreshMutex.withLock {
            val previous = _state.value
            _state.value = previous.copy(
                status = ScanStatus.LOADING,
                errorMessage = null,
                lastRefreshReason = reason
            )

            try {
                val started = System.currentTimeMillis()
                // Lightweight signature: only checks cache_static.json mtimes (one stat per job).
                val currentSignature = computeLightStalenessSignature(baseDir)
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

                val jobs = scanAssemblyJobs()
                lastStalenessSignature = currentSignature
                _state.value = AssemblyScanState(
                    status = ScanStatus.READY,
                    snapshot = AssemblyScanSnapshot(
                        generation = generation.incrementAndGet(),
                        basePath = baseDir.absolutePath,
                        jobs = jobs,
                        startedAt = started,
                        completedAt = System.currentTimeMillis()
                    ),
                    errorMessage = null,
                    lastRefreshReason = reason
                )
            } catch (e: Exception) {
                _state.value = previous.copy(
                    status = ScanStatus.ERROR,
                    errorMessage = e.message ?: "Assembly refresh failed",
                    lastRefreshReason = reason
                )
            }
        }
    }

    private fun scanAssemblyJobs(): List<AssemblyJob> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        val (_, _) = unifiedEngine.listJobsFromCacheIndex()
        return emptyList()
    }

    /** Re-projects one job from the engine's in-memory cache and emits an updated AssemblyScanState. */
    fun updateJobInState(folderName: String) = updateJobsInState(listOf(folderName))

    /**
     * Re-projects a batch of jobs from the engine's in-memory cache in a single state emission.
     * Batching matters here: calling updateJobInState() once per folder in a loop would have each
     * call read-modify-write _state.value independently, racing when several jobs resolve around
     * the same time and silently dropping all but the last writer's update.
     */
    fun updateJobsInState(folderNames: List<String>) {
        if (folderNames.isEmpty()) return
        scope.launch {
            val updates = folderNames.distinct().mapNotNull { folderName ->
                val info = unifiedEngine.getMergedJobInfo(folderName) ?: return@mapNotNull null
                val job = unifiedEngine.getAssemblySnapshot(folderName)?.job
                    ?.copy(
                        lineupPosition = info.lineupPosition,
                        labels = info.labels,
                        hiddenFromProduction = info.hiddenFromProduction,
                        isPending = info.isPending,
                        boardSection = info.boardSection
                    ) ?: return@mapNotNull null
                folderName to job
            }
            if (updates.isEmpty()) return@launch

            val current = _state.value
            val existingJobs = current.snapshot.jobs
            val updatedJobByFolder = updates.toMap()
            val existingFolders = existingJobs.mapTo(HashSet()) { it.folderName }
            val replacedJobs = existingJobs.map { updatedJobByFolder[it.folderName] ?: it }
            val appendedJobs = updates.filter { it.first !in existingFolders }.map { it.second }
            val updatedJobs = replacedJobs + appendedJobs

            _state.value = current.copy(
                snapshot = current.snapshot.copy(
                    generation = generation.incrementAndGet(),
                    jobs = updatedJobs
                )
            )
        }
    }

    /** Phase-2: run full staleness check for this job in background; update state if changed. */
    fun refreshJobOnOpen(folderName: String) {
        scope.launch {
            val changed = unifiedEngine.refreshJobDeep(folderName)
            if (changed) updateJobInState(folderName)
        }
    }
}
