package com.kkc.sheettracker.perf

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    val ended: Boolean
)

/**
 * Detects a blocked main thread, which CPU% can never see: a hang on I/O or a lock costs no CPU.
 * Once a second it posts a no-op to the main looper; if that has not run within [stallMs] the main
 * thread is stuck, and its stack is captured right then so the log names the blocking call.
 * Uses uptime rather than wall time, so a suspended device does not read as a stall.
 */
class MainThreadWatchdog(
    private val scope: CoroutineScope,
    private val onStall: (MainThreadStall) -> Unit,
    private val stallMs: Long = 2_000L,
    private val probeIntervalMs: Long = 1_000L
) {
    private class Probe {
        @Volatile
        var ranAtMs = 0L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        scope.launch {
            var probe = post()
            var pendingSince = SystemClock.uptimeMillis()
            var stallReported = false
            while (isActive) {
                delay(probeIntervalMs)
                val now = SystemClock.uptimeMillis()
                val ranAt = probe.ranAtMs
                if (ranAt != 0L) {
                    if (stallReported) {
                        onStall(MainThreadStall(blockedMs = ranAt - pendingSince, stack = emptyList(), ended = true))
                        stallReported = false
                    }
                    probe = post()
                    pendingSince = now
                } else if (!stallReported && now - pendingSince >= stallMs) {
                    stallReported = true
                    onStall(MainThreadStall(blockedMs = now - pendingSince, stack = captureMainThreadStack(), ended = false))
                }
            }
        }
    }

    private fun post(): Probe {
        val probe = Probe()
        mainHandler.post { probe.ranAtMs = SystemClock.uptimeMillis() }
        return probe
    }

}

private const val MAX_STACK_FRAMES = 30

/** The main thread's current stack, safe to call from any thread. */
internal fun captureMainThreadStack(): List<String> =
    Looper.getMainLooper().thread.stackTrace
        .take(MAX_STACK_FRAMES)
        .map { "${it.className}.${it.methodName}(${it.fileName ?: "?"}:${it.lineNumber})" }
