package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HardwoodScanSnapshot
import com.kkc.sheettracker.data.models.HardwoodScanState
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import java.io.File

class HardwoodsScanCoordinator(
    private val repository: HardwoodsRepository
) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val generation = AtomicLong(0L)
    private val pendingLock = Any()

    @Volatile private var lastStalenessSignature: Long? = null
    @Volatile private var refreshInFlight = false
    @Volatile private var pendingRefresh = false
    @Volatile private var pendingForce = false
    @Volatile private var pendingReason: RefreshReason = RefreshReason.APP_START

    private val _state = MutableStateFlow(
        HardwoodScanState(
            status = ScanStatus.IDLE,
            snapshot = HardwoodScanSnapshot()
        )
    )
    val state: StateFlow<HardwoodScanState> = _state.asStateFlow()

    private val _searchIndex = MutableStateFlow<List<com.kkc.sheettracker.data.models.HardwoodSearchEntry>>(emptyList())
    val searchIndex: StateFlow<List<com.kkc.sheettracker.data.models.HardwoodSearchEntry>> = _searchIndex.asStateFlow()
    private val _isSearchProjectionLoading = MutableStateFlow(false)
    val isSearchProjectionLoading: StateFlow<Boolean> = _isSearchProjectionLoading.asStateFlow()
    private val searchProjectionLock = Any()
    @Volatile private var searchProjectionGeneration: Long = -1L
    @Volatile private var searchProjectionInFlight = false

    /**
     * Coalesces overlapping refresh() calls into a single in-flight run (e.g. the app-start
     * foreground trigger and the Jobs screen's own LaunchedEffect firing back-to-back on cold
     * start) instead of running two full concurrent scans.
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

    fun close() {
        scopeJob.cancel()
    }

    /** Loads full hardwood row data only after the operator opens the Search screen. */
    fun loadSearchIndexOnSearchOpen() {
        val requestedGeneration = _state.value.snapshot.generation
        synchronized(searchProjectionLock) {
            if (searchProjectionInFlight || searchProjectionGeneration == requestedGeneration) return
            searchProjectionInFlight = true
            _isSearchProjectionLoading.value = true
        }
        scope.launch {
            var reloadForNewGeneration = false
            try {
                _searchIndex.value = repository.buildSearchIndexForSearchScreen()
                searchProjectionGeneration = requestedGeneration
            } finally {
                synchronized(searchProjectionLock) {
                    searchProjectionInFlight = false
                    _isSearchProjectionLoading.value = false
                    reloadForNewGeneration = _state.value.snapshot.generation != requestedGeneration
                }
            }
            if (reloadForNewGeneration) loadSearchIndexOnSearchOpen()
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
                val basePath = repository.currentBasePath()
                val baseDir = File(basePath)
                // Lightweight signature: only checks cache_static.json mtimes (one stat per job).
                val currentSignature = computeLightStalenessSignature(baseDir)
                val unchanged = !force &&
                    currentSignature == lastStalenessSignature &&
                    previous.snapshot.basePath == basePath &&
                    previous.snapshot.generation > 0

                if (unchanged) {
                    _state.value = previous.copy(
                        status = ScanStatus.READY,
                        errorMessage = null,
                        lastRefreshReason = reason
                    )
                    return
                }

                val result = repository.scanJobsFromCacheOnly()
                lastStalenessSignature = currentSignature
                _state.value = HardwoodScanState(
                    status = ScanStatus.READY,
                    snapshot = HardwoodScanSnapshot(
                        generation = generation.incrementAndGet(),
                        basePath = basePath,
                        jobs = result.jobs,
                        searchIndex = result.searchIndex,
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis()
                    ),
                    errorMessage = null,
                    lastRefreshReason = reason
                )

                // Jobs load on per-tap via HardwoodsJobDetailScreen. No background deep-load needed.
            } catch (e: Exception) {
                _state.value = previous.copy(
                    status = ScanStatus.ERROR,
                    errorMessage = e.message ?: "Hardwoods refresh failed",
                    lastRefreshReason = reason
                )
            }
        }
    }

    /** Re-projects one job from the engine's in-memory cache and emits an updated HardwoodScanState. */
    fun updateJobInState(folderName: String) = updateJobsInState(listOf(folderName))

    /**
     * Re-projects a batch of jobs from the engine's in-memory cache in a single state emission,
     * merging their search-index rows in too (mirrors ScanCoordinator.updateJobsInState).
     */
    fun updateJobsInState(folderNames: List<String>) {
        if (folderNames.isEmpty()) return
        scope.launch {
            val updates = folderNames.distinct().mapNotNull { folderName ->
                repository.getUpdatedJob(folderName)?.let { folderName to it }
            }
            if (updates.isEmpty()) return@launch

            val current = _state.value
            val existingJobs = current.snapshot.jobs
            val updatedJobByFolder = updates.toMap()
            val existingFolders = existingJobs.mapTo(HashSet()) { it.folderName }
            val replacedJobs = existingJobs.map { updatedJobByFolder[it.folderName] ?: it }
            val appendedJobs = updates.filter { it.first !in existingFolders }.map { it.second }
            val updatedJobs = replacedJobs + appendedJobs

            val changedFolders = updatedJobByFolder.keys
            val newSearchEntries = repository.buildSearchIndex(updates.map { it.second })
            val updatedSearch = current.snapshot.searchIndex
                .filter { it.jobFolderName !in changedFolders } + newSearchEntries

            _state.value = current.copy(
                snapshot = current.snapshot.copy(
                    generation = generation.incrementAndGet(),
                    jobs = updatedJobs,
                    searchIndex = updatedSearch
                )
            )
        }
    }

    /** Phase-2: run full staleness check for this job in background; update state if changed. */
    fun refreshJobOnOpen(folderName: String) {
        scope.launch {
            val engine = UnifiedMetadataEngineRegistry.getOrCreate(
                baseDir = File(repository.currentBasePath()),
                isDebugBuild = BuildConfig.DEBUG
            )
            val changed = engine.refreshJobDeep(folderName)
            if (changed) updateJobInState(folderName)
        }
    }
}
