package com.kkc.sheettracker.data

import android.util.Log
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Polls each job's `.metadata/cache_static.json` mtime at a fixed interval.
 * When the server updates a cache file, the poller loads the new data into the engine's
 * in-memory cache and notifies all registered coordinators via [onJobCacheUpdated].
 *
 * Mirrors the lifecycle pattern of [TrackerChangeMonitor]: call [start] on ON_START
 * and [stop] on ON_STOP.
 */
class StaticCachePoller(
    baseDir: File,
    private val onJobCacheUpdated: (folderName: String) -> Unit,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS
) {
    @Volatile private var baseDir: File = baseDir
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mtimeSnapshot = ConcurrentHashMap<String, Long>()
    private var pollJob: Job? = null

    companion object {
        private const val TAG = "StaticCachePoller"
        const val POLL_INTERVAL_MS = 20_000L
    }

    fun start() {
        if (pollJob?.isActive == true) return
        val isFirstStart = mtimeSnapshot.isEmpty()
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
                delay(pollIntervalMs)
                checkForChanges()
            }
        }
        Log.d(TAG, "Started polling ${baseDir.absolutePath} every ${pollIntervalMs}ms")
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        Log.d(TAG, "Stopped")
    }

    /** Update base directory (e.g. when the user changes the base path in settings). */
    fun updateBaseDir(newBaseDir: File) {
        stop()
        mtimeSnapshot.clear()
        baseDir = newBaseDir
        start()
    }

    private fun snapshotAllMtimes() {
        val dirs = baseDir.listFiles() ?: return
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val cacheFile = File(dir, ".metadata/cache_static.json")
            mtimeSnapshot[dir.name] = if (cacheFile.isFile) cacheFile.lastModified() else 0L
        }
    }

    private fun checkForChanges() {
        if (!baseDir.exists() || !baseDir.isDirectory) return
        val dirs = baseDir.listFiles() ?: return
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val folderName = dir.name
            val cacheFile = File(dir, ".metadata/cache_static.json")
            val currentMtime = if (cacheFile.isFile) cacheFile.lastModified() else 0L
            val knownMtime = mtimeSnapshot[folderName]

            if (currentMtime != knownMtime) {
                mtimeSnapshot[folderName] = currentMtime
                if (currentMtime == 0L) continue // File was deleted — skip

                // Load new data into the engine's in-memory cache
                try {
                    val engine = UnifiedMetadataEngineRegistry.getOrCreate(
                        baseDir = baseDir,
                        isDebugBuild = BuildConfig.DEBUG
                    )
                    val loaded = engine.loadJobFromCacheFile(folderName)
                    if (loaded != null) {
                        Log.d(TAG, "Cache updated for $folderName — notifying coordinators")
                        onJobCacheUpdated(folderName)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to reload cache for $folderName: ${e.message}")
                }
            }
        }
    }
}
