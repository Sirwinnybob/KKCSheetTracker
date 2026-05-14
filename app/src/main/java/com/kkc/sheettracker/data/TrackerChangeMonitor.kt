package com.kkc.sheettracker.data

import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class TrackerChangeMonitor(
    private val baseDir: File,
    private val progressStore: ProgressStore,
    private val hardwoodsProgressStore: HardwoodsProgressStore,
    private val viewerInteraction: StateFlow<Boolean> = ViewerInteractionSignal.isViewerInteracting,
    private val onWatcherRefreshRequested: (() -> Unit)? = null,
    private val pollingIntervalMs: Long = POLLING_INTERVAL_MS
) {
    private enum class TrackerKind { CNC, HARDWOODS }

    private data class TrackedDir(
        val kind: TrackerKind,
        val jobFolderName: String,
        val dir: File
    )

    private data class Invalidation(
        val kind: TrackerKind,
        val jobFolderName: String?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var started = false
    private var pollJob: Job? = null
    private val observersByPath = mutableMapOf<String, FileObserver>()
    private val trackedByPath = mutableMapOf<String, TrackedDir>()
    private val signaturesByPath = mutableMapOf<String, Long>()
    private val lastInvalidationAtByKey = mutableMapOf<String, Long>()
    private val pendingByKey = LinkedHashMap<String, Invalidation>()
    private var flushJob: Job? = null
    private var refreshDispatchJob: Job? = null
    private var interactionJob: Job? = null
    private var startupWarmupUntilMs = 0L

    fun start() {
        val initialInvalidations = synchronized(lock) {
            if (started) return
            started = true
            startupWarmupUntilMs = System.currentTimeMillis() + STARTUP_WARMUP_MS
            val refreshInvalidations = refreshTrackedDirsLocked()
            refreshInvalidations + pollSignaturesLocked()
        }
        queueInvalidations(initialInvalidations)
        interactionJob = scope.launch {
            viewerInteraction.collectLatest { interacting ->
                if (!interacting) {
                    flushPendingNow()
                }
            }
        }
        pollJob = scope.launch {
            while (isActive) {
                delay(pollingIntervalMs)
                val invalidations = synchronized(lock) {
                    if (!started) emptyList() else {
                        val refreshInvalidations = refreshTrackedDirsLocked()
                        refreshInvalidations + pollSignaturesLocked()
                    }
                }
                queueInvalidations(invalidations)
            }
        }
    }

    fun stop() {
        val jobsToStop = synchronized(lock) {
            started = false
            val poll = pollJob
            pollJob = null
            val interaction = interactionJob
            interactionJob = null
            val flush = flushJob
            flushJob = null
            val refreshDispatch = refreshDispatchJob
            refreshDispatchJob = null
            trackedByPath.clear()
            signaturesByPath.clear()
            lastInvalidationAtByKey.clear()
            pendingByKey.clear()
            val observers = observersByPath.values.toList().also { observersByPath.clear() }
            listOf(poll, interaction, flush, refreshDispatch) to observers
        }
        jobsToStop.first.forEach { it?.cancel() }
        jobsToStop.second.forEach { it.stopWatching() }
    }

    private fun refreshTrackedDirsLocked(): List<Invalidation> {
        val discovered = discoverTrackerDirs().associateBy { it.dir.absolutePath }
        val invalidations = mutableListOf<Invalidation>()

        val removedPaths = trackedByPath.keys - discovered.keys
        removedPaths.forEach { path ->
            val removed = trackedByPath.remove(path)
            signaturesByPath.remove(path)
            observersByPath.remove(path)?.stopWatching()
            if (removed != null) {
                invalidations += Invalidation(kind = removed.kind, jobFolderName = removed.jobFolderName)
            }
        }

        discovered.forEach { (path, tracked) ->
            if (!trackedByPath.containsKey(path)) {
                trackedByPath[path] = tracked
                signaturesByPath.putIfAbsent(path, trackerSignature(tracked.dir))
                val observer = createObserver(tracked)
                observersByPath[path] = observer
                observer.startWatching()
            } else {
                trackedByPath[path] = tracked
                if (!observersByPath.containsKey(path)) {
                    val observer = createObserver(tracked)
                    observersByPath[path] = observer
                    observer.startWatching()
                }
            }
        }

        return invalidations
    }

    private fun pollSignaturesLocked(): List<Invalidation> {
        val invalidations = mutableListOf<Invalidation>()
        trackedByPath.forEach { (path, tracked) ->
            val next = trackerSignature(tracked.dir)
            val previous = signaturesByPath[path]
            if (previous == null) {
                signaturesByPath[path] = next
                return@forEach
            }
            if (previous != next) {
                signaturesByPath[path] = next
                invalidations += Invalidation(kind = tracked.kind, jobFolderName = tracked.jobFolderName)
            }
        }
        return invalidations
    }

    @Suppress("DEPRECATION")
    private fun createObserver(tracked: TrackedDir): FileObserver {
        val trackedPath = tracked.dir.absolutePath
        return object : FileObserver(tracked.dir.absolutePath, OBSERVER_EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && !path.endsWith(".json", ignoreCase = true)) return
                val invalidation = synchronized(lock) {
                    if (!started) return@synchronized null
                    val previousSig = signaturesByPath[trackedPath]
                    val nextSig = trackerSignature(tracked.dir)
                    if (previousSig == nextSig) return@synchronized null
                    signaturesByPath[trackedPath] = nextSig
                    Invalidation(kind = tracked.kind, jobFolderName = tracked.jobFolderName)
                }
                if (invalidation != null) {
                    queueInvalidations(listOf(invalidation))
                }
            }
        }
    }

    private fun discoverTrackerDirs(): List<TrackedDir> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        val jobDirs = baseDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        val tracked = mutableListOf<TrackedDir>()
        jobDirs.forEach { jobDir ->
            val jobFolderName = jobDir.name
            val cncTrackerDir = File(jobDir, "CNC/.tracker")
            if (cncTrackerDir.isDirectory) {
                tracked += TrackedDir(
                    kind = TrackerKind.CNC,
                    jobFolderName = jobFolderName,
                    dir = cncTrackerDir
                )
            }
            val hardwoodTrackerDir = File(jobDir, ".metadata/hardwoods/.tracker")
            if (hardwoodTrackerDir.isDirectory) {
                tracked += TrackedDir(
                    kind = TrackerKind.HARDWOODS,
                    jobFolderName = jobFolderName,
                    dir = hardwoodTrackerDir
                )
            }
        }
        return tracked
    }

    private fun trackerSignature(dir: File): Long {
        val files = dir.listFiles()
            ?.filter {
                it.isFile &&
                    it.extension.equals("json", ignoreCase = true) &&
                    !it.name.startsWith(".")
            }
            ?.sortedBy { it.name }
            .orEmpty()
        var signature = 1125899906842597L
        files.forEach { file ->
            signature = signature * 31 + file.name.hashCode().toLong()
            signature = signature * 31 + file.length()
            signature = signature * 31 + file.lastModified()
        }
        return signature * 31 + files.size.toLong()
    }

    private fun queueInvalidations(invalidations: List<Invalidation>) {
        if (invalidations.isEmpty()) return
        val now = System.currentTimeMillis()
        val deduped = invalidations
            .distinctBy { "${it.kind}|${it.jobFolderName.orEmpty()}" }
            .filter { invalidation ->
                val key = "${invalidation.kind}|${invalidation.jobFolderName.orEmpty()}"
                val previous = lastInvalidationAtByKey[key] ?: 0L
                if (now - previous < MIN_INVALIDATION_GAP_MS) {
                    false
                } else {
                    lastInvalidationAtByKey[key] = now
                    true
                }
            }
        if (deduped.isEmpty()) return
        synchronized(lock) {
            deduped.forEach { invalidation ->
                val key = "${invalidation.kind}|${invalidation.jobFolderName.orEmpty()}"
                pendingByKey[key] = invalidation
            }
            scheduleFlushLocked()
        }
    }

    private fun scheduleFlushLocked() {
        if (!started || flushJob?.isActive == true) return
        val now = System.currentTimeMillis()
        val inWarmup = now < startupWarmupUntilMs
        val delayMs = when {
            inWarmup -> (startupWarmupUntilMs - now).coerceAtLeast(50L)
            viewerInteraction.value -> INTERACTION_COALESCE_MS
            else -> NORMAL_COALESCE_MS
        }
        flushJob = scope.launch {
            try {
                delay(delayMs)
                flushPendingNow()
            } finally {
                synchronized(lock) {
                    if (flushJob === this) {
                        flushJob = null
                    }
                }
                val shouldScheduleAgain = synchronized(lock) { started && pendingByKey.isNotEmpty() }
                if (shouldScheduleAgain) {
                    synchronized(lock) { scheduleFlushLocked() }
                }
            }
        }
    }

    private fun flushPendingNow() {
        val pending = synchronized(lock) {
            if (!started || pendingByKey.isEmpty()) return
            val now = System.currentTimeMillis()
            if (now < startupWarmupUntilMs && viewerInteraction.value) {
                scheduleFlushLocked()
                return
            }
            val values = pendingByKey.values.toList()
            pendingByKey.clear()
            values
        }
        applyBatchedInvalidations(pending)
    }

    private fun applyBatchedInvalidations(invalidations: List<Invalidation>) {
        if (invalidations.isEmpty()) return
        val cncJobs = LinkedHashSet<String>()
        val hardwoodJobs = LinkedHashSet<String>()
        var invalidateAllCnc = false
        var invalidateAllHardwoods = false
        invalidations.forEach { invalidation ->
            when (invalidation.kind) {
                TrackerKind.CNC -> {
                    val jobFolderName = invalidation.jobFolderName
                    if (jobFolderName.isNullOrBlank()) invalidateAllCnc = true
                    else cncJobs += jobFolderName
                }
                TrackerKind.HARDWOODS -> {
                    val jobFolderName = invalidation.jobFolderName
                    if (jobFolderName.isNullOrBlank()) invalidateAllHardwoods = true
                    else hardwoodJobs += jobFolderName
                }
            }
        }

        if (invalidateAllCnc) {
            progressStore.invalidateAllIndexes()
        } else if (cncJobs.isNotEmpty()) {
            // Batch job invalidation to trigger one progress version bump for this cycle.
            progressStore.invalidateJobIndexes(cncJobs)
        }

        if (invalidateAllHardwoods) {
            hardwoodsProgressStore.invalidateAllCaches()
        } else if (hardwoodJobs.isNotEmpty()) {
            // Batch hardwood invalidation to trigger one progress version bump for this cycle.
            hardwoodsProgressStore.invalidateJobCaches(hardwoodJobs)
        }

        scheduleFullRefreshDispatch()
    }

    private fun scheduleFullRefreshDispatch() {
        if (onWatcherRefreshRequested == null) return
        synchronized(lock) {
            if (!started) return
            refreshDispatchJob?.cancel()
            refreshDispatchJob = scope.launch {
                delay(FULL_REFRESH_COALESCE_MS)
                onWatcherRefreshRequested.invoke()
            }
        }
    }

    private companion object {
        private const val POLLING_INTERVAL_MS = 10_000L
        private const val MIN_INVALIDATION_GAP_MS = 750L
        private const val STARTUP_WARMUP_MS = 1_500L
        private const val NORMAL_COALESCE_MS = 140L
        private const val INTERACTION_COALESCE_MS = 500L
        private const val FULL_REFRESH_COALESCE_MS = 2_000L
        private const val OBSERVER_EVENTS = FileObserver.CLOSE_WRITE or
            FileObserver.CREATE or
            FileObserver.DELETE or
            FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or
            FileObserver.DELETE_SELF or
            FileObserver.MOVE_SELF
    }
}
