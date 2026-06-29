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
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class AssemblyScanCoordinator(
    initialBaseDir: File,
    private val jobRepository: JobRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong(0L)

    @Volatile
    private var baseDir: File = initialBaseDir
    @Volatile
    private var unifiedEngine: UnifiedMetadataEngine = UnifiedMetadataEngineRegistry.getOrCreate(
        baseDir = initialBaseDir,
        isDebugBuild = BuildConfig.DEBUG
    )

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
    }

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
                val jobs = scanAssemblyJobs()
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
        val (jobInfos, needsDeepLoad) = unifiedEngine.listJobsFromCacheOnly()
        val jobs = jobInfos.mapNotNull { info ->
            runCatching {
                unifiedEngine.getAssemblySnapshot(info.folderName)?.job
                    ?.copy(
                        lineupPosition = info.lineupPosition,
                        labels = info.labels,
                        hiddenFromProduction = info.hiddenFromProduction,
                        isPending = info.isPending,
                        boardSection = info.boardSection
                    )
            }.getOrNull()
        }
        if (needsDeepLoad.isNotEmpty()) {
            scope.launch {
                needsDeepLoad.forEach { folderName ->
                    if (unifiedEngine.refreshJobDeep(folderName)) updateJobInState(folderName)
                }
            }
        }
        return jobs
    }

    /** Re-projects one job from the engine's in-memory cache and emits an updated AssemblyScanState. */
    fun updateJobInState(folderName: String) {
        scope.launch {
            val info = unifiedEngine.getMergedJobInfo(folderName) ?: return@launch
            val updatedJob = unifiedEngine.getAssemblySnapshot(folderName)?.job
                ?.copy(
                    lineupPosition = info.lineupPosition,
                    labels = info.labels,
                    hiddenFromProduction = info.hiddenFromProduction,
                    isPending = info.isPending,
                    boardSection = info.boardSection
                ) ?: return@launch
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
            val changed = unifiedEngine.refreshJobDeep(folderName)
            if (changed) updateJobInState(folderName)
        }
    }
}
