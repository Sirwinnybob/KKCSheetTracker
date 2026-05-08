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
}
