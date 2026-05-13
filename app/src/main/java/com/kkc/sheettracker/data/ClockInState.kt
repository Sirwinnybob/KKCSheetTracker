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
    val pendingPrompt: Boolean = false
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
            pendingPrompt = false
        )
        persist()
    }

    /** Returns elapsed milliseconds, then clears state. */
    fun clockOut(): Long {
        if (!snapshot.isActive) return 0L
        val elapsed = if (snapshot.startTimeMs > 0L)
            System.currentTimeMillis() - snapshot.startTimeMs else 0L
        snapshot = ClockInSnapshot()
        prefs.edit().clear().apply()
        return elapsed
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

        fun create(context: Context): ClockInState = ClockInState(
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        )

        private fun load(prefs: SharedPreferences): ClockInSnapshot {
            if (!prefs.getBoolean(KEY_ACTIVE, false)) return ClockInSnapshot()
            return ClockInSnapshot(
                isActive = true,
                jobNumber = prefs.getString(KEY_JOB_NUMBER, "") ?: "",
                jobName = prefs.getString(KEY_JOB_NAME, "") ?: "",
                folderName = prefs.getString(KEY_FOLDER_NAME, "") ?: "",
                tabType = prefs.getString(KEY_TAB_TYPE, "cnc") ?: "cnc",
                startTimeMs = prefs.getLong(KEY_START_TIME, 0L),
                pendingPrompt = prefs.getBoolean(KEY_PENDING_PROMPT, false)
            )
        }
    }
}
