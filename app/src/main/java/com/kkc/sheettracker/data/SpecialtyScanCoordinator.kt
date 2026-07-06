package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.SpecialtyJob
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.SpecialtyScanSnapshot
import com.kkc.sheettracker.data.models.SpecialtyScanState
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class SpecialtyScanCoordinator(
    private val repository: SpecialtyRepository,
    private val scanJobsProvider: (() -> List<SpecialtyJob>) = repository::scanJobs,
    private val onBeforeIdleTransition: (() -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong(0L)
    private val refreshLock = Any()

    @Volatile private var lastStalenessSignature: Long? = null
    @Volatile private var refreshInFlight = false
    @Volatile private var pendingRefresh = false
    @Volatile private var pendingForce = false
    @Volatile private var pendingReason: RefreshReason = RefreshReason.APP_START

    private val _state = MutableStateFlow(
        SpecialtyScanState(
            status = ScanStatus.IDLE,
            snapshot = SpecialtyScanSnapshot(basePath = repository.currentBasePath())
        )
    )
    val state: StateFlow<SpecialtyScanState> = _state.asStateFlow()

    fun updateBasePath(path: String) {
        repository.updateBaseDir(File(path))
        lastStalenessSignature = null
    }

    fun refresh(reason: RefreshReason, force: Boolean = false) {
        synchronized(refreshLock) {
            pendingRefresh = true
            pendingForce = pendingForce || force
            pendingReason = reason
            if (refreshInFlight) return
            refreshInFlight = true
        }

        scope.launch {
            while (true) {
                val (runReason, runForce) = synchronized(refreshLock) {
                    val reasonToRun = pendingReason
                    val forceToRun = pendingForce
                    pendingRefresh = false
                    pendingForce = false
                    reasonToRun to forceToRun
                }
                runRefresh(runReason, runForce)

                val shouldContinue = synchronized(refreshLock) {
                    if (pendingRefresh) {
                        true
                    } else {
                        onBeforeIdleTransition?.invoke()
                        if (pendingRefresh) {
                            true
                        } else {
                            refreshInFlight = false
                            false
                        }
                    }
                }
                if (!shouldContinue) {
                    break
                }
            }
        }
    }

    /** Re-projects one job from the repository and emits an updated SpecialtyScanState. */
    fun updateJobInState(folderName: String) {
        scope.launch {
            val updatedJob = repository.getUpdatedJob(folderName) ?: return@launch
            val current = _state.value
            val existingJobs = current.snapshot.jobs
            val newJobs = if (existingJobs.any { it.folderName == folderName }) {
                existingJobs.map { if (it.folderName == folderName) updatedJob else it }
            } else {
                existingJobs + updatedJob
            }
            _state.value = current.copy(
                snapshot = current.snapshot.copy(
                    generation = generation.incrementAndGet(),
                    jobs = newJobs
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

    private fun runRefresh(reason: RefreshReason, force: Boolean) {
        val previous = _state.value
        _state.value = previous.copy(
            status = ScanStatus.LOADING,
            errorMessage = null,
            lastRefreshReason = reason
        )

        try {
            val started = System.currentTimeMillis()
            val basePath = repository.currentBasePath()
            // Lightweight signature: only checks cache_static.json mtimes (one stat per job).
            // Skips the (potentially expensive) scanJobsProvider() call entirely when nothing
            // on disk has changed, instead of computing the full job list just to discard it.
            val currentSignature = computeLightStalenessSignature(File(basePath))
            val unchangedByStaleness = !force &&
                currentSignature == lastStalenessSignature &&
                previous.status == ScanStatus.READY &&
                previous.snapshot.basePath == basePath &&
                previous.snapshot.jobs.isNotEmpty()

            if (unchangedByStaleness) {
                _state.value = previous.copy(
                    status = ScanStatus.READY,
                    errorMessage = null,
                    lastRefreshReason = reason
                )
                return
            }

            // User pressed Refresh: deep-scan all jobs (full staleness check + re-parse) so newer
            // on-disk files not yet in cache_static.json appear. Auto refreshes stay cache-only.
            if (reason == RefreshReason.USER_REFRESH) {
                UnifiedMetadataEngineRegistry.getOrCreate(
                    baseDir = File(basePath),
                    isDebugBuild = BuildConfig.DEBUG
                ).deepScanAllJobs()
            }
            val jobs = scanJobsProvider()
            lastStalenessSignature = currentSignature
            val unchanged = !force &&
                previous.status == ScanStatus.READY &&
                previous.snapshot.basePath == basePath &&
                previous.snapshot.jobs == jobs

            if (unchanged) {
                _state.value = previous.copy(
                    status = ScanStatus.READY,
                    errorMessage = null,
                    lastRefreshReason = reason
                )
                return
            }

            _state.value = SpecialtyScanState(
                status = ScanStatus.READY,
                snapshot = SpecialtyScanSnapshot(
                    generation = generation.incrementAndGet(),
                    basePath = basePath,
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
                errorMessage = e.message ?: "Specialty refresh failed",
                lastRefreshReason = reason
            )
        }
    }
}
