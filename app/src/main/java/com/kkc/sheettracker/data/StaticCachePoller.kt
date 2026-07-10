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
    // Combined cache+gate mtime per job (see [combinedMtime]); flips on either a cache or gate change.
    private val mtimeSnapshot = ConcurrentHashMap<String, Long>()
    // Cache-only mtime per job, tracked separately so a gate-only change notifies without forcing a
    // cache_static.json reload (the cache file may not even exist for a gate-only change).
    private val cacheMtimeSnapshot = ConcurrentHashMap<String, Long>()
    @Volatile private var knownBoardMtime: Long = Long.MIN_VALUE
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
        cacheMtimeSnapshot.clear()
        knownBoardMtime = Long.MIN_VALUE
        baseDir = newBaseDir
        start()
    }

    private fun cacheMtimeOf(dir: File): Long {
        val cacheFile = File(dir, ".metadata/cache_static.json")
        return if (cacheFile.isFile) cacheFile.lastModified() else 0L
    }

    private fun gateMtimeOf(dir: File): Long {
        val gateFile = File(dir, ".metadata/deployment_gate.json")
        return if (gateFile.isFile) gateFile.lastModified() else 0L
    }

    /** Fold cache and gate mtimes into a single value; 0L means both are absent. */
    private fun combinedMtime(cacheMtime: Long, gateMtime: Long): Long = cacheMtime xor (gateMtime * 31L)

    private fun boardMtime(): Long {
        val boardFile = File(baseDir, "job_board.json")
        return if (boardFile.isFile) boardFile.lastModified() else 0L
    }

    private fun snapshotAllMtimes() {
        knownBoardMtime = boardMtime()
        val dirs = baseDir.listFiles() ?: return
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val cacheMtime = cacheMtimeOf(dir)
            val gateMtime = gateMtimeOf(dir)
            mtimeSnapshot[dir.name] = combinedMtime(cacheMtime, gateMtime)
            cacheMtimeSnapshot[dir.name] = cacheMtime
        }
    }

    private fun checkForChanges() {
        if (!baseDir.exists() || !baseDir.isDirectory) return
        val dirs = baseDir.listFiles() ?: return
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val folderName = dir.name
            val cacheMtime = cacheMtimeOf(dir)
            val gateMtime = gateMtimeOf(dir)
            val currentMtime = combinedMtime(cacheMtime, gateMtime)
            val knownMtime = mtimeSnapshot[folderName]

            if (currentMtime != knownMtime) {
                mtimeSnapshot[folderName] = currentMtime
                if (currentMtime == 0L) {
                    // Both cache and gate vanished — job deleted, skip.
                    cacheMtimeSnapshot[folderName] = cacheMtime
                    continue
                }

                val cacheChanged = cacheMtime != cacheMtimeSnapshot[folderName]
                cacheMtimeSnapshot[folderName] = cacheMtime

                // Only reload the cache file when the cache-specific mtime changed; a gate-only
                // change should still notify coordinators without requiring the cache file.
                if (cacheChanged && cacheMtime != 0L) {
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
                } else {
                    // Gate-only change — notify without reloading the cache.
                    Log.d(TAG, "Deployment gate changed for $folderName — notifying coordinators")
                    onJobCacheUpdated(folderName)
                }
            }
        }

        // Board-level change: notify coordinators with an empty folder name.
        val currentBoardMtime = boardMtime()
        if (currentBoardMtime != knownBoardMtime) {
            knownBoardMtime = currentBoardMtime
            Log.d(TAG, "job_board.json changed — notifying coordinators")
            onJobCacheUpdated("")
        }
    }
}
