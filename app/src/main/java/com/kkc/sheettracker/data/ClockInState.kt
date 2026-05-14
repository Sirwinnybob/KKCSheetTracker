package com.kkc.sheettracker.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class ClockInSnapshot(
    val isActive: Boolean = false,
    val jobNumber: String = "",
    val jobName: String = "",
    val folderName: String = "",
    val tabType: String = "cnc",   // "cnc" | "hardwoods" | "assembly"
    val startTimeMs: Long = 0L,
    val pendingPrompt: Boolean = false,
    val isPaused: Boolean = false,
    val pausedAtMs: Long = 0L,
    val accumulatedPausedMs: Long = 0L
)

class ClockInState(private val prefs: SharedPreferences) {
    var snapshot by mutableStateOf(load(prefs))
        private set

    fun clockIn(jobNumber: String, jobName: String, folderName: String, tabType: String) {
        snapshot = ClockInSnapshot(
            isActive = true,
            jobNumber = jobNumber,
            jobName = jobName,
            folderName = folderName,
            tabType = tabType,
            startTimeMs = System.currentTimeMillis(),
            pendingPrompt = false,
            isPaused = false,
            pausedAtMs = 0L,
            accumulatedPausedMs = 0L
        )
        persist()
    }

    /** Returns elapsed milliseconds, then clears state. */
    fun clockOut(): Long {
        if (!snapshot.isActive) return 0L
        val elapsed = elapsedActiveMs()
        snapshot = ClockInSnapshot()
        prefs.edit().clear().apply()
        return elapsed
    }

    fun pause() {
        if (!snapshot.isActive || snapshot.isPaused) return
        snapshot = snapshot.copy(
            isPaused = true,
            pausedAtMs = System.currentTimeMillis()
        )
        persist()
    }

    fun resume() {
        val pausedAtMs = snapshot.pausedAtMs
        if (!snapshot.isActive || !snapshot.isPaused || pausedAtMs <= 0L) return
        val now = System.currentTimeMillis()
        val pausedDelta = (now - pausedAtMs).coerceAtLeast(0L)
        snapshot = snapshot.copy(
            isPaused = false,
            pausedAtMs = 0L,
            accumulatedPausedMs = snapshot.accumulatedPausedMs + pausedDelta
        )
        persist()
    }

    fun elapsedActiveMs(nowMs: Long = System.currentTimeMillis()): Long {
        return computeActiveElapsedMs(snapshot, nowMs)
    }

    fun refreshFromDisk() {
        snapshot = load(prefs)
    }

    fun triggerPrompt() {
        if (!snapshot.isActive) return
        snapshot = snapshot.copy(pendingPrompt = true)
        persist()
    }

    fun dismissPromptKeepActive() {
        snapshot = snapshot.copy(pendingPrompt = false)
        persist()
    }

    private fun persist() {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, snapshot.isActive)
            .putString(KEY_JOB_NUMBER, snapshot.jobNumber)
            .putString(KEY_JOB_NAME, snapshot.jobName)
            .putString(KEY_FOLDER_NAME, snapshot.folderName)
            .putString(KEY_TAB_TYPE, snapshot.tabType)
            .putLong(KEY_START_TIME, snapshot.startTimeMs)
            .putBoolean(KEY_PENDING_PROMPT, snapshot.pendingPrompt)
            .putBoolean(KEY_IS_PAUSED, snapshot.isPaused)
            .putLong(KEY_PAUSED_AT_MS, snapshot.pausedAtMs)
            .putLong(KEY_ACCUMULATED_PAUSED_MS, snapshot.accumulatedPausedMs)
            .apply()
    }

    companion object {
        private const val PREFS_FILE = "kkc_clock_in"
        private const val KEY_ACTIVE = "is_active"
        private const val KEY_JOB_NUMBER = "job_number"
        private const val KEY_JOB_NAME = "job_name"
        private const val KEY_FOLDER_NAME = "folder_name"
        private const val KEY_TAB_TYPE = "tab_type"
        private const val KEY_START_TIME = "start_time_ms"
        private const val KEY_PENDING_PROMPT = "pending_prompt"
        private const val KEY_IS_PAUSED = "is_paused"
        private const val KEY_PAUSED_AT_MS = "paused_at_ms"
        private const val KEY_ACCUMULATED_PAUSED_MS = "accumulated_paused_ms"

        @Volatile
        private var sharedInstance: ClockInState? = null

        fun create(context: Context): ClockInState {
            val existing = sharedInstance
            if (existing != null) return existing
            return synchronized(this) {
                val secondCheck = sharedInstance
                if (secondCheck != null) {
                    secondCheck
                } else {
                    ClockInState(
                        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                    ).also { sharedInstance = it }
                }
            }
        }

        private fun load(prefs: SharedPreferences): ClockInSnapshot {
            if (!prefs.getBoolean(KEY_ACTIVE, false)) return ClockInSnapshot()
            return ClockInSnapshot(
                isActive = true,
                jobNumber = prefs.getString(KEY_JOB_NUMBER, "") ?: "",
                jobName = prefs.getString(KEY_JOB_NAME, "") ?: "",
                folderName = prefs.getString(KEY_FOLDER_NAME, "") ?: "",
                tabType = prefs.getString(KEY_TAB_TYPE, "cnc") ?: "cnc",
                startTimeMs = prefs.getLong(KEY_START_TIME, 0L),
                pendingPrompt = prefs.getBoolean(KEY_PENDING_PROMPT, false),
                isPaused = prefs.getBoolean(KEY_IS_PAUSED, false),
                pausedAtMs = prefs.getLong(KEY_PAUSED_AT_MS, 0L),
                accumulatedPausedMs = prefs.getLong(KEY_ACCUMULATED_PAUSED_MS, 0L)
            )
        }

        private fun computeActiveElapsedMs(snapshot: ClockInSnapshot, nowMs: Long): Long {
            if (!snapshot.isActive || snapshot.startTimeMs <= 0L) return 0L
            val pausedSince = if (snapshot.isPaused && snapshot.pausedAtMs > 0L) {
                (nowMs - snapshot.pausedAtMs).coerceAtLeast(0L)
            } else {
                0L
            }
            val totalPaused = snapshot.accumulatedPausedMs + pausedSince
            return (nowMs - snapshot.startTimeMs - totalPaused).coerceAtLeast(0L)
        }
    }
}
