package com.kkc.sheettracker.perf

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A main-thread hang: [blockedMs] at detection (with the stack), then the final length. */
data class MainThreadStall(
    val blockedMs: Long,
    /** Main thread stack captured while it was stuck; empty on the closing event. */
    val stack: List<String>,
    /** True for the closing event, whose [blockedMs] is the stall's total length. */
    val ended: Boolean,
    val stallId: String? = null,
    val samples: List<MainThreadStallSample> = emptyList(),
    val followUp: Boolean = false,
    val longestWatchdogTickGapMs: Long? = null
)

/**
 * Detects a blocked main thread, which CPU% can never see: a hang on I/O or a lock costs no CPU.
 * Once a second it posts a no-op to the main looper; if that has not run within [stallMs] the main
 * thread is stuck. Up to four spaced stack/pressure snapshots show whether the call persists;
 * bounded detail reports preserve that evidence even if the process dies before the stall ends.
 * Uses uptime rather than wall time, so a suspended device does not read as a stall; see
 * [StallTimeline] for the process-freeze case uptime alone does not cover.
 */
class MainThreadWatchdog(
    private val scope: CoroutineScope,
    private val onStall: (MainThreadStall) -> Unit,
    private val stallMs: Long = 2_000L,
    private val probeIntervalMs: Long = 1_000L,
    private val startupSnapshot: (Long) -> StartupSnapshot? = { null }
) {
    private class Probe {
        @Volatile
        var ranAtMs = 0L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clockTicksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
        .getOrDefault(100L).takeIf { it > 0 } ?: 100L

    fun start() {
        scope.launch {
            var probe = post()
            var lastTickAt = SystemClock.uptimeMillis()
            val timeline = StallTimeline(stallMs, probeIntervalMs, startMs = lastTickAt)
            var diagnosticWindow: StallDiagnosticWindow? = null
            while (isActive) {
                delay(probeIntervalMs)
                val now = SystemClock.uptimeMillis()
                val tickGapMs = now - lastTickAt
                lastTickAt = now
                when (val step = timeline.onTick(now, probe.ranAtMs)) {
                    is StallTimeline.Step.ProbeAnswered -> {
                        step.endedStallMs?.let {
                            val window = diagnosticWindow
                            onStall(MainThreadStall(
                                blockedMs = it, stack = emptyList(), ended = true,
                                stallId = window?.stallId, samples = window?.samples.orEmpty(),
                                longestWatchdogTickGapMs = maxOf(window?.longestWatchdogTickGapMs ?: 0, tickGapMs)
                            ))
                        }
                        diagnosticWindow = null
                        probe = post()
                    }
                    is StallTimeline.Step.StallDetected -> {
                        val window = StallDiagnosticWindow(
                            "${Os.getpid()}-$now", now - step.blockedMs,
                            sampleIntervalMs = probeIntervalMs,
                            maxWatchdogTickGapMs = probeIntervalMs + stallMs
                        )
                        diagnosticWindow = window
                        val samples = window.capture(now, tickGapMs) { captureSample(now, step.blockedMs) }.orEmpty()
                        onStall(MainThreadStall(
                            blockedMs = step.blockedMs, stack = samples.lastOrNull()?.stack.orEmpty(), ended = false,
                            stallId = window.stallId, samples = samples,
                            longestWatchdogTickGapMs = window.longestWatchdogTickGapMs
                        ))
                    }
                    StallTimeline.Step.Waiting -> {
                        val window = diagnosticWindow ?: continue
                        val blockedMs = now - window.startedAtMs
                        val samples = window.capture(now, tickGapMs) { captureSample(now, blockedMs) } ?: continue
                        onStall(MainThreadStall(
                            blockedMs = blockedMs, stack = samples.last().stack, ended = false,
                            stallId = window.stallId, samples = samples, followUp = true,
                            longestWatchdogTickGapMs = window.longestWatchdogTickGapMs
                        ))
                    }
                }
            }
        }
    }

    private fun captureSample(nowMs: Long, blockedMs: Long): MainThreadStallSample {
        val mainThread = Looper.getMainLooper().thread
        val state = mainThread.state.name
        val stack = captureMainThreadStack()
        val pid = Os.getpid()
        fun readProc(path: String) = runCatching { File(path).readText() }.getOrNull()
        val pressure = parseStallSystemPressure(
            readProc("/proc/loadavg"), readProc("/proc/meminfo"), readProc("/proc/self/status"),
            readProc("/proc/self/stat")?.let(ProcStat::parseCpuTicks),
            readProc("/proc/self/task/$pid/stat")?.let(ProcStat::parseCpuTicks), clockTicksPerSecond
        )
        return MainThreadStallSample(
            nowMs, blockedMs, state, classifyStallWait(stack, state), stack, startupSnapshot(nowMs), pressure
        )
    }

    private fun post(): Probe {
        val probe = Probe()
        mainHandler.post { probe.ranAtMs = SystemClock.uptimeMillis() }
        return probe
    }

}

/**
 * The watchdog's bookkeeping, one call per tick, kept free of Android so it can be unit tested.
 *
 * Android's cached-app freezer stops every thread in the process, the watchdog's included, while
 * uptime keeps counting. On thaw the old probe is still unanswered and the gap looks like a
 * main-thread stall (field log: a "15 s stall" at 0.5% CPU while backgrounded). A tick that
 * arrives far later than [probeIntervalMs] means the watchdog itself was not running, so the time
 * since the last tick is not blamed on the main thread.
 */
internal class StallTimeline(
    private val stallMs: Long,
    private val probeIntervalMs: Long,
    startMs: Long
) {
    sealed interface Step {
        /** The main thread ran the probe; post a new one. [endedStallMs] closes a reported stall. */
        data class ProbeAnswered(val endedStallMs: Long?) : Step
        /** The main thread has been stuck for [blockedMs]; capture its stack now. */
        data class StallDetected(val blockedMs: Long) : Step
        data object Waiting : Step
    }

    private var pendingSince = startMs
    private var lastTickAt = startMs
    private var stallReported = false

    /** [probeRanAtMs] is when the main thread ran the current probe, or 0 if it has not yet. */
    fun onTick(nowMs: Long, probeRanAtMs: Long): Step {
        val overslept = nowMs - lastTickAt > probeIntervalMs + stallMs
        val previousTickAt = lastTickAt
        lastTickAt = nowMs
        if (probeRanAtMs != 0L) {
            val ended = if (stallReported) {
                if (overslept) previousTickAt - pendingSince else probeRanAtMs - pendingSince
            } else {
                null
            }
            stallReported = false
            pendingSince = nowMs
            return Step.ProbeAnswered(ended)
        }
        if (overslept && !stallReported) {
            pendingSince = nowMs
            return Step.Waiting
        }
        if (!stallReported && nowMs - pendingSince >= stallMs) {
            stallReported = true
            return Step.StallDetected(nowMs - pendingSince)
        }
        return Step.Waiting
    }
}

private const val MAX_STACK_FRAMES = 30

/** The main thread's current stack, safe to call from any thread. */
internal fun captureMainThreadStack(): List<String> =
    Looper.getMainLooper().thread.stackTrace
        .take(MAX_STACK_FRAMES)
        .map { "${it.className}.${it.methodName}(${it.fileName ?: "?"}:${it.lineNumber})" }
