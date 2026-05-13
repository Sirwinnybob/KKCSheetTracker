package com.kkc.sheettracker.data

import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.models.AssemblyJob
import com.kkc.sheettracker.data.models.AssemblyScanSnapshot
import com.kkc.sheettracker.data.models.AssemblyScanState
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
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

        return baseDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && File(it, "CNC").isDirectory }
            ?.mapNotNull { jobDir ->
                runCatching {
                    val deploymentGate = DeploymentGateRules.evaluate(jobDir, isDebugBuild = BuildConfig.DEBUG)
                    if (!deploymentGate.includeJob) return@runCatching null
                    val parsed = parseJobFolderName(jobDir.name) ?: return@runCatching null
                    val sheetIndex = jobRepository.getCabinetSheetIndex(jobDir.name)
                    AssemblyJob(
                        folderName = jobDir.name,
                        jobNumber = parsed.jobNumber,
                        jobName = parsed.jobName,
                        cabinetSheetIndex = sheetIndex,
                        hiddenFromProduction = deploymentGate.hiddenFromProduction
                    )
                }.getOrNull()
            }
            ?.sortedWith { a, b ->
                val numberCmp = compareJobNumbersDesc(a.jobNumber, b.jobNumber)
                if (numberCmp != 0) numberCmp else a.folderName.compareTo(b.folderName, ignoreCase = true)
            }
            ?.toList()
            ?: emptyList()
    }
}
