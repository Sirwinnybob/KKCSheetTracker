package com.kkc.sheettracker.perf

data class StartupStage(val stage: String, val elapsedMs: Long)

data class StartupSnapshot(
    val startedAtUptimeMs: Long,
    val elapsedMs: Long,
    val latestStage: String,
    val completed: Boolean,
    val stages: List<StartupStage>
)

/** In-memory checkpoints only: safe even while an Android system service is unresponsive. */
internal class StartupTrace {
    private var startedAtMs: Long? = null
    private var completedAtMs: Long? = null
    private val stages = ArrayDeque<StartupStage>()

    @Synchronized
    fun begin(nowMs: Long) {
        startedAtMs = nowMs
        completedAtMs = null
        stages.clear()
        stages.add(StartupStage("activity_onCreate", 0))
    }

    @Synchronized
    fun mark(stage: String, nowMs: Long) {
        val started = startedAtMs ?: return
        if (completedAtMs != null || stages.lastOrNull()?.stage == stage) return
        stages.add(StartupStage(stage, (nowMs - started).coerceAtLeast(0)))
        while (stages.size > 16) stages.removeFirst()
    }

    @Synchronized
    fun finish(nowMs: Long) {
        if (startedAtMs == null || completedAtMs != null) return
        mark("first_pre_draw", nowMs)
        completedAtMs = nowMs
    }

    @Synchronized
    fun snapshot(nowMs: Long): StartupSnapshot? {
        val started = startedAtMs ?: return null
        return StartupSnapshot(
            started, ((completedAtMs ?: nowMs) - started).coerceAtLeast(0),
            stages.last().stage, completedAtMs != null, stages.toList()
        )
    }
}
