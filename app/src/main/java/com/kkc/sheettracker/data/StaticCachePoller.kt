package com.kkc.sheettracker.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Polls each job's lightweight list inputs: `.metadata/cache_index.json` and
 * `.metadata/deployment_gate.json`. Full cache data is never opened here; detail/viewer
 * screens load it only after an operator selects a job.
 *
 * Mirrors the lifecycle pattern of [TrackerChangeMonitor]: call [start] on ON_START
 * and [stop] on ON_STOP.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StaticCachePoller(
    baseDir: File,
    private val onJobCacheUpdated: (folderName: String) -> Unit,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val intervalOverrideMs: StateFlow<Long?> = MutableStateFlow(null)
) {
    @Volatile private var baseDir: File = baseDir
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Combined index+gate mtime per job (see [combinedMtime]); flips on either list input.
    private val mtimeSnapshot = ConcurrentHashMap<String, Long>()
    private var pollJob: Job? = null
    private var intervalObserverJob: Job? = null
    private val intervalWake = Channel<Unit>(Channel.CONFLATED)

    companion object {
        private const val TAG = "StaticCachePoller"
        const val POLL_INTERVAL_MS = 20_000L
    }

    fun start() {
        if (pollJob?.isActive == true) return
        val isFirstStart = mtimeSnapshot.isEmpty()
        intervalObserverJob = scope.launch {
            intervalOverrideMs.drop(1).collect {
                intervalWake.trySend(Unit)
            }
        }
        pollJob = scope.launch {
            if (isFirstStart) {
                // First start: snapshot current mtimes so the first poll only fires on actual changes.
                snapshotAllMtimes()
            } else {
                // Resuming after a stop (screen timeout, backgrounding, etc.) — check immediately
                // against the snapshot taken before stopping, so a cache update that landed while
                // stopped is caught instead of being silently adopted as the new baseline.
                checkForChanges()
            }
            while (isActive) {
                val interrupted = select {
                    onTimeout(intervalOverrideMs.value ?: pollIntervalMs) { false }
                    intervalWake.onReceive { true }
                }
                if (interrupted) {
                    checkForChanges()
                    continue
                }
                checkForChanges()
            }
        }
        Log.d(TAG, "Started polling ${baseDir.absolutePath} every ${pollIntervalMs}ms")
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        intervalObserverJob?.cancel()
        intervalObserverJob = null
        Log.d(TAG, "Stopped")
    }

    /** Update base directory (e.g. when the user changes the base path in settings). */
    fun updateBaseDir(newBaseDir: File) {
        stop()
        mtimeSnapshot.clear()
        baseDir = newBaseDir
        start()
    }

    private fun indexMtimeOf(dir: File): Long {
        val indexFile = File(dir, ".metadata/cache_index.json")
        return if (indexFile.isFile) indexFile.lastModified() else 0L
    }

    private fun gateMtimeOf(dir: File): Long {
        val gateFile = File(dir, ".metadata/deployment_gate.json")
        return if (gateFile.isFile) gateFile.lastModified() else 0L
    }

    /** Fold index and gate mtimes into a single value; 0L means both are absent. */
    private fun combinedMtime(indexMtime: Long, gateMtime: Long): Long = indexMtime xor (gateMtime * 31L)

    private fun snapshotAllMtimes() {
        val dirs = baseDir.listFiles() ?: return
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val indexMtime = indexMtimeOf(dir)
            val gateMtime = gateMtimeOf(dir)
            mtimeSnapshot[dir.name] = combinedMtime(indexMtime, gateMtime)
        }
    }

    private fun checkForChanges() {
        if (!baseDir.exists() || !baseDir.isDirectory) return
        val dirs = baseDir.listFiles() ?: return
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val folderName = dir.name
            val indexMtime = indexMtimeOf(dir)
            val gateMtime = gateMtimeOf(dir)
            val currentMtime = combinedMtime(indexMtime, gateMtime)
            val knownMtime = mtimeSnapshot[folderName]

            if (currentMtime != knownMtime) {
                mtimeSnapshot[folderName] = currentMtime
                if (currentMtime == 0L) {
                    // Both cache and gate vanished — job deleted, skip.
                    continue
                }
                Log.d(TAG, "Jobs-list index or deployment gate changed for $folderName")
                onJobCacheUpdated(folderName)
            }
        }
    }
}
