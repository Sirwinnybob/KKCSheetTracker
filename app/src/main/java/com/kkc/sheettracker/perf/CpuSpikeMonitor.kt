package com.kkc.sheettracker.perf

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.ViewerInteractionSignal
import com.kkc.sheettracker.ui.components.WebViewBlurGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Temporary field diagnostic: logs only genuinely abnormal, long-sustained CPU load (the "stuck
 * forever at 70-100%" pattern a runaway loop produces), not normal brief bursts from PDF loading,
 * navigation, or scrolling — those settle back down within a few seconds and never accumulate
 * enough consecutive high samples to cross SUSTAINED_MIN_SAMPLES.
 *
 * The trigger and `cpuPercent`/`peakCpuPercent`/`avgCpuPercent` are WHOLE-PROCESS CPU (every
 * thread, so it can exceed 100% on a multi-core tablet). That is what saturates the device, and
 * it catches work off the main thread such as WebView GPU/compositor threads. The main (UI)
 * thread alone is logged separately as `mainThreadCpuPercent`/`avgMainThreadCpuPercent`, so a
 * high process figure with a low main-thread figure means the load is not composition. Each entry
 * also records whether a WebView (3D pane) was on screen.
 *
 * 3D viewer mode is a legitimate, deliberately sustained heavy load while the user is touching it
 * and is suppressed outright via [ViewerInteractionSignal]. Self-disables after [EXPIRY_DAYS]
 * from first install — intended to run for a few days on production tablets, not indefinitely.
 */
object CpuSpikeMonitor {
    private const val TAG = "KKC_CPU_SPIKE_MONITOR"
    private const val PREFS_NAME = "kkc_tracker"
    private const val STARTED_AT_KEY = "perf_monitor_started_at"

    private const val SAMPLE_INTERVAL_MS = 5_000L
    private const val SUSTAINED_THRESHOLD_PERCENT = 35.0
    private const val SUSTAINED_MIN_SAMPLES = 12 // 12 * 5s = 60s of continuous elevated load
    private const val INTERACTION_GRACE_MS = 10_000L
    private const val EXPIRY_DAYS = 5L

    @Volatile
    private var contextSnapshot = CpuSpikeContext()

    @Volatile
    private var installed = false

    private lateinit var store: CpuSpikeLogStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val startedAt = prefs.getLong(STARTED_AT_KEY, 0L)
            val effectiveStartedAt = if (startedAt > 0L) {
                startedAt
            } else {
                val now = System.currentTimeMillis()
                prefs.edit().putLong(STARTED_AT_KEY, now).apply()
                now
            }
            val expiresAt = effectiveStartedAt + EXPIRY_DAYS * 24 * 60 * 60 * 1000L
            if (System.currentTimeMillis() >= expiresAt) {
                installed = true
                return
            }

            store = CpuSpikeLogStore(
                pendingDir = File(appContext.filesDir, "cpu_spike_logs/pending")
            )
            installed = true
            startSampling(expiresAt)
        }
    }

    fun updateContext(
        tabletId: String? = null,
        workMode: String? = null,
        currentTab: String? = null,
        currentRoute: String? = null,
        activeJobFolderName: String? = null,
        basePath: String? = null
    ) {
        synchronized(this) {
            contextSnapshot = contextSnapshot.copy(
                tabletId = tabletId ?: contextSnapshot.tabletId,
                workMode = workMode ?: contextSnapshot.workMode,
                currentTab = currentTab ?: contextSnapshot.currentTab,
                currentRoute = currentRoute ?: contextSnapshot.currentRoute,
                activeJobFolderName = activeJobFolderName ?: contextSnapshot.activeJobFolderName,
                basePath = basePath ?: contextSnapshot.basePath
            )
        }
    }

    fun updateNavigationContext(
        currentTab: String,
        currentRoute: String?,
        activeJobFolderName: String?
    ) {
        synchronized(this) {
            contextSnapshot = contextSnapshot.copy(
                currentTab = currentTab,
                currentRoute = currentRoute,
                activeJobFolderName = activeJobFolderName
            )
        }
    }

    fun flushPending(basePath: String) {
        if (!::store.isInitialized) return
        runCatching {
            store.flushPending(File(basePath))
        }.onFailure { error ->
            Log.w(TAG, "Unable to flush pending CPU spike logs", error)
        }
    }

    private fun startSampling(expiresAt: Long) {
        val clkTck = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L)
        scope.launch {
            var prevTicks = -1L
            var prevMainTicks = -1L
            var prevWallMs = 0L
            var wasInteracting = false
            var interactionEndedAt = 0L
            var streakCount = 0
            var streakPeak = 0.0
            var streakSum = 0.0
            var streakMainSum = 0.0
            var episodeStartAt = 0L
            var episodeLogged = false

            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                if (System.currentTimeMillis() >= expiresAt) return@launch

                val now = System.currentTimeMillis()
                val ticks = readProcessTicks()
                val mainTicks = readMainThreadTicks()
                if (ticks == null || prevTicks < 0) {
                    prevTicks = ticks ?: prevTicks
                    prevMainTicks = mainTicks ?: prevMainTicks
                    prevWallMs = now
                    continue
                }
                val deltaTicks = ticks - prevTicks
                val deltaWallMs = now - prevWallMs
                val deltaMainTicks = if (mainTicks != null && prevMainTicks >= 0) mainTicks - prevMainTicks else 0L
                prevTicks = ticks
                prevMainTicks = mainTicks ?: prevMainTicks
                prevWallMs = now
                if (deltaWallMs <= 0) continue
                val cpuPercent = (deltaTicks * 1000.0 / clkTck) / deltaWallMs * 100.0
                val mainThreadPercent = (deltaMainTicks * 1000.0 / clkTck) / deltaWallMs * 100.0

                val interacting = ViewerInteractionSignal.isViewerInteracting.value
                if (interacting) {
                    wasInteracting = true
                    // 3D mode is a legitimate, deliberately sustained heavy load — drop any
                    // in-progress streak/episode silently rather than logging it.
                    streakCount = 0
                    streakPeak = 0.0
                    streakSum = 0.0
                    streakMainSum = 0.0
                    episodeLogged = false
                    continue
                }
                if (wasInteracting) {
                    wasInteracting = false
                    interactionEndedAt = now
                }
                if (now - interactionEndedAt < INTERACTION_GRACE_MS) {
                    continue
                }

                if (cpuPercent >= SUSTAINED_THRESHOLD_PERCENT) {
                    if (streakCount == 0) episodeStartAt = now
                    streakCount++
                    streakPeak = maxOf(streakPeak, cpuPercent)
                    streakSum += cpuPercent
                    streakMainSum += mainThreadPercent

                    if (streakCount == SUSTAINED_MIN_SAMPLES && !episodeLogged) {
                        episodeLogged = true
                        logEntry(
                            CpuSpikeEntry(
                                entryType = "sustained_start",
                                triggerReason = "sustained",
                                cpuPercent = cpuPercent,
                                mainThreadCpuPercent = mainThreadPercent,
                                webViewShowing = WebViewBlurGate.shared.isActive.value
                            )
                        )
                    }
                } else {
                    if (episodeLogged) {
                        logEntry(
                            CpuSpikeEntry(
                                entryType = "episode_end",
                                triggerReason = "sustained",
                                cpuPercent = cpuPercent,
                                peakCpuPercent = streakPeak,
                                avgCpuPercent = if (streakCount > 0) streakSum / streakCount else 0.0,
                                durationMs = now - episodeStartAt,
                                mainThreadCpuPercent = mainThreadPercent,
                                avgMainThreadCpuPercent = if (streakCount > 0) streakMainSum / streakCount else 0.0,
                                webViewShowing = WebViewBlurGate.shared.isActive.value
                            )
                        )
                    }
                    streakCount = 0
                    streakPeak = 0.0
                    streakSum = 0.0
                    streakMainSum = 0.0
                    episodeLogged = false
                }
            }
        }
    }

    private fun logEntry(entry: CpuSpikeEntry) {
        if (!::store.isInitialized) return
        val snapshot = contextSnapshot
        runCatching {
            store.recordEntry(
                baseDir = snapshot.basePath?.let(::File),
                context = snapshot,
                environment = CpuSpikeEnvironment(
                    appVersionName = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE,
                    androidRelease = Build.VERSION.RELEASE,
                    androidSdk = Build.VERSION.SDK_INT,
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL
                ),
                entry = entry
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to write CPU spike log", error)
        }
    }

    /** Whole-process utime+stime in clock ticks, summed over every thread. */
    private fun readProcessTicks(): Long? =
        runCatching { ProcStat.parseCpuTicks(File("/proc/self/stat").readText()) }.getOrNull()

    /** Main (UI) thread only: on Linux the thread group leader's tid equals the pid, and its own
     * stat lives under /proc/self/task/<pid>/stat (the row `top -H` shows for the main thread). */
    private fun readMainThreadTicks(): Long? =
        runCatching { ProcStat.parseCpuTicks(File("/proc/self/task/${Os.getpid()}/stat").readText()) }.getOrNull()
}
