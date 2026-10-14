package com.kkc.sheettracker.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide notice that a per-job store refused a write because `<job>/.metadata` is gone (the job
 * was renamed or archived). The stores keep skipping silently on disk; this lets the UI tell the
 * user the change was not saved. Reporting never blocks: the buffer drops the oldest event.
 */
object JobFolderGuardEvents {
    private val _skipped = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val skipped: SharedFlow<String> = _skipped.asSharedFlow()

    fun report(job: String) {
        _skipped.tryEmit(job)
    }

    fun toastText(job: String): String = "$job is no longer in Ready Jobs — change not saved"
}

/** At most one toast per job per [windowMs]; pure so it is testable without a clock or Android. */
class JobFolderGuardDebouncer(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private val lastShown = HashMap<String, Long>()

    /** True when a toast for [job] should be shown at [nowMs]; records it when so. */
    @Synchronized
    fun shouldShow(job: String, nowMs: Long): Boolean {
        val last = lastShown[job]
        if (last != null && nowMs - last in 0 until windowMs) return false
        lastShown[job] = nowMs
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 10_000L
    }
}
