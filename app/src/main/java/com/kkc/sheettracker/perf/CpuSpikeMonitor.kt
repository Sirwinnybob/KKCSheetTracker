package com.kkc.sheettracker.perf

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.Window
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
 * Field performance watchdog. Every 5 s it samples the process and hands the sample to
 * [PerfEventDetector], which decides what is worth a log file:
 *
 *  - `sustained_start` / `episode_end`: whole-process CPU held high for a minute. Whole process
 *    (every thread, so it can exceed 100%) is what saturates the tablet and it catches work off
 *    the main thread such as the WebView GPU/compositor threads. The log names the hottest
 *    threads (`topThreads`), the main thread's own share (`mainThreadCpuPercent`) and its stack.
 *  - `idle_redraw_start` / `idle_redraw_end`: the app keeps drawing frames (`frames.fps`, plus
 *    `avgGpuMs` on API 31+) while the user does nothing — a compositor or animation loop, caught
 *    even where CPU stays low.
 *  - `main_thread_stall` / `main_thread_stall_end`: the UI thread blocked for 2 s+, with its stack.
 *  - `memory_growth`: resident memory climbing steadily.
 *
 * Android exposes no GPU utilisation to apps, so GPU cost is inferred from GPU/compositor thread
 * CPU, frame rate and per-frame GPU time rather than a percentage.
 *
 * Runs permanently: sampling costs a few small /proc reads every 5 s, entries are rate limited,
 * and the log store keeps at most [CpuSpikeLogStore.DEFAULT_RETENTION_LIMIT] files per tablet.
 * A 3D pane being touched is legitimately heavy: an open episode is closed with
 * `endReason = viewer_interaction` and detection pauses briefly afterwards.
 */
object CpuSpikeMonitor {
    private const val TAG = "KKC_CPU_SPIKE_MONITOR"
    private const val SAMPLE_INTERVAL_MS = 5_000L
    private const val MAIN_STACK_MIN_PERCENT = 25.0
    private const val MAX_STARTS_PER_HOUR = 12
    private const val BYTES_PER_MB = 1024L * 1024L

    @Volatile
    private var contextSnapshot = CpuSpikeContext()

    @Volatile
    private var installed = false

    @Volatile
    private var foreground = true

    @Volatile
    private var lastInputMs = System.currentTimeMillis()

    @Volatile
    private var lastCpuPercent = 0.0

    private lateinit var appContext: Context
    private lateinit var store: CpuSpikeLogStore
    private val frameStats = FrameWindowStats()
    private val frameCollector = FrameMetricsCollector(frameStats)
    private val limiter = RateLimiter(MAX_STARTS_PER_HOUR)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            appContext = context.applicationContext
            store = CpuSpikeLogStore(pendingDir = File(appContext.filesDir, "cpu_spike_logs/pending"))
            installed = true
            lastInputMs = System.currentTimeMillis()
            startSampling()
            MainThreadWatchdog(scope, ::recordStall).start()
        }
    }

    /** Call from the Activity's start/stop. Idle-redraw detection only applies while foreground. */
    fun setForeground(isForeground: Boolean) {
        foreground = isForeground
    }

    /** Call for every touch/key event so "the user is idle" is known precisely. */
    fun noteUserInput() {
        lastInputMs = System.currentTimeMillis()
    }

    fun attachWindow(window: Window) {
        if (installed) frameCollector.attach(window)
    }

    fun detachWindow() {
        frameCollector.detach()
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

    private fun startSampling() {
        val clkTck = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L)
        val threadSampler = ThreadCpuSampler(clkTck, Os.getpid(), { ThreadCpuSampler.readSelfThreads() })
        val detector = PerfEventDetector()
        scope.launch {
            var prevTicks = -1L
            var prevWallMs = 0L
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val ticks = readProcessTicks()
                val threads = threadSampler.sample(now)
                if (ticks == null || threads == null || prevTicks < 0) {
                    prevTicks = ticks ?: prevTicks
                    prevWallMs = now
                    continue
                }
                val wallMs = now - prevWallMs
                val cpuPercent = (ticks - prevTicks) * 1000.0 / clkTck / wallMs * 100.0
                prevTicks = ticks
                prevWallMs = now
                lastCpuPercent = cpuPercent

                val sample = PerfSample(
                    nowMs = now,
                    cpuPercent = cpuPercent,
                    mainThreadCpuPercent = threads.mainThreadCpuPercent,
                    topThreads = threads.topThreads,
                    frames = frameStats.snapshotAndReset(wallMs),
                    rssBytes = readRssBytes(),
                    foreground = foreground,
                    viewerInteracting = ViewerInteractionSignal.isViewerInteracting.value,
                    msSinceUserInput = now - lastInputMs
                )
                for (event in detector.process(sample)) record(event)
            }
        }
    }

    private fun record(event: PerfEvent) {
        val s = event.sample
        val isStart = event.entryType.endsWith("_start") || event.entryType == "memory_growth"
        if (isStart && !limiter.tryAcquire(event.entryType, s.nowMs)) return
        val isCpu = event.detector == "cpu"
        val isIdle = event.detector == "idle_redraw"
        val stack = if (event.entryType == "sustained_start" && s.mainThreadCpuPercent >= MAIN_STACK_MIN_PERCENT) {
            captureMainThreadStack()
        } else {
            null
        }
        logEntry(
            CpuSpikeEntry(
                entryType = event.entryType,
                triggerReason = if (isCpu) "sustained" else event.detector,
                cpuPercent = s.cpuPercent,
                peakCpuPercent = event.peakValue.takeIf { isCpu },
                avgCpuPercent = event.avgValue.takeIf { isCpu },
                durationMs = event.durationMs,
                mainThreadCpuPercent = s.mainThreadCpuPercent,
                webViewShowing = WebViewBlurGate.shared.isActive.value,
                endReason = event.endReason,
                foreground = s.foreground,
                viewerInteracting = s.viewerInteracting,
                topThreads = s.topThreads.takeIf { it.isNotEmpty() },
                frames = s.frames,
                peakFps = event.peakValue.takeIf { isIdle },
                avgFps = event.avgValue.takeIf { isIdle },
                rssMb = s.rssBytes?.let { it / BYTES_PER_MB },
                memoryGrowthMb = event.memoryGrowthBytes?.let { it / BYTES_PER_MB },
                thermalStatus = thermalStatus(),
                mainThreadStack = stack
            )
        )
    }

    private fun recordStall(stall: MainThreadStall) {
        val type = if (stall.ended) "main_thread_stall_end" else "main_thread_stall"
        if (!stall.ended && !limiter.tryAcquire(type, System.currentTimeMillis())) return
        logEntry(
            CpuSpikeEntry(
                entryType = type,
                triggerReason = "main_thread_stall",
                cpuPercent = lastCpuPercent,
                webViewShowing = WebViewBlurGate.shared.isActive.value,
                foreground = foreground,
                thermalStatus = thermalStatus(),
                blockedMs = stall.blockedMs,
                mainThreadStack = stall.stack.takeIf { it.isNotEmpty() }
            )
        )
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

    private fun readRssBytes(): Long? =
        runCatching { ProcStat.parseRssBytes(File("/proc/self/status").readText()) }.getOrNull()

    private fun thermalStatus(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        }.getOrNull()
    }
}
