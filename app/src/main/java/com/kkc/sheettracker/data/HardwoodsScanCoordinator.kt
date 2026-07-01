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
import java.util.concurrent.atomic.AtomicLong
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import java.io.File

class HardwoodsScanCoordinator(
    private val repository: HardwoodsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong(0L)

    private val _state = MutableStateFlow(
        HardwoodScanState(
            status = ScanStatus.IDLE,
            snapshot = HardwoodScanSnapshot()
        )
    )
    val state: StateFlow<HardwoodScanState> = _state.asStateFlow()

    fun refresh(reason: RefreshReason, force: Boolean = false) {
        scope.launch {
            val previous = _state.value
            _state.value = previous.copy(
                status = ScanStatus.LOADING,
                errorMessage = null,
                lastRefreshReason = reason
            )
            try {
                val started = System.currentTimeMillis()
                // User pressed Refresh: deep-scan all jobs (full staleness check + re-parse) so
                // newer on-disk files not yet in cache_static.json appear. Auto refreshes stay fast.
                if (reason == RefreshReason.USER_REFRESH) {
                    UnifiedMetadataEngineRegistry.getOrCreate(
                        baseDir = File(repository.currentBasePath()),
                        isDebugBuild = BuildConfig.DEBUG
                    ).deepScanAllJobs()
                }
                val jobs = repository.scanJobs()
                val search = repository.buildSearchIndex(jobs)
                _state.value = HardwoodScanState(
                    status = ScanStatus.READY,
                    snapshot = HardwoodScanSnapshot(
                        generation = generation.incrementAndGet(),
                        basePath = repository.currentBasePath(),
                        jobs = jobs,
                        searchIndex = search,
                        startedAt = started,
                        completedAt = System.currentTimeMillis()
                    ),
                    errorMessage = null,
                    lastRefreshReason = reason
                )
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
}
