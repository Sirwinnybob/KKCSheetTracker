package com.kkc.sheettracker.perf

data class StallSystemPressure(
    val loadAverage: List<Double>?,
    val runnableTasks: Int?,
    val totalTasks: Int?,
    val availableMemoryMb: Long?,
    val processRssMb: Long?,
    val processCpuTicks: Long?,
    val mainThreadCpuTicks: Long?,
    val clockTicksPerSecond: Long
)

data class MainThreadStallSample(
    val uptimeMs: Long,
    val blockedMs: Long,
    val threadState: String,
    val waitKind: String,
    val stack: List<String>,
    val startup: StartupSnapshot? = null,
    val systemPressure: StallSystemPressure? = null
)

/** At most four snapshots per episode, with no repeated collection during a process-freeze gap. */
internal class StallDiagnosticWindow(
    val stallId: String,
    val startedAtMs: Long,
    private val sampleIntervalMs: Long = 1_000,
    private val maxSamples: Int = 4,
    private val maxWatchdogTickGapMs: Long = 3_000
) {
    private val captured = mutableListOf<MainThreadStallSample>()
    val samples: List<MainThreadStallSample> get() = captured.toList()
    var longestWatchdogTickGapMs: Long = 0
        private set

    fun capture(nowMs: Long, tickGapMs: Long, collect: () -> MainThreadStallSample): List<MainThreadStallSample>? {
        longestWatchdogTickGapMs = maxOf(longestWatchdogTickGapMs, tickGapMs)
        if (tickGapMs > maxWatchdogTickGapMs || captured.size >= maxSamples) return null
        val last = captured.lastOrNull()
        if (last != null && nowMs - last.uptimeMs < sampleIntervalMs) return null
        captured += collect()
        return samples
    }
}

internal fun classifyStallWait(stack: List<String>, threadState: String): String {
    val binder = stack.any { it.contains("android.os.BinderProxy.transact") }
    return when {
        binder && stack.any { it.contains("android.view.accessibility.") } -> "accessibility_binder"
        binder -> "binder"
        threadState == "BLOCKED" -> "java_monitor"
        threadState == "WAITING" || threadState == "TIMED_WAITING" -> "thread_wait"
        else -> "other"
    }
}

internal fun parseStallSystemPressure(
    loadAvgText: String?, memInfoText: String?, statusText: String?,
    processCpuTicks: Long?, mainThreadCpuTicks: Long?, clockTicksPerSecond: Long
): StallSystemPressure {
    val loadFields = loadAvgText?.trim()?.split(Regex("\\s+")).orEmpty()
    val loadAverage = loadFields.take(3).mapNotNull { it.toDoubleOrNull() }
        .takeIf { it.size == 3 && it.all { value -> value.isFinite() && value >= 0 } }
    val tasks = loadFields.getOrNull(3)?.split('/')
    fun memoryMb(text: String?, key: String): Long? = text?.lineSequence()
        ?.firstOrNull { it.startsWith("$key:") }?.trim()?.split(Regex("\\s+"))
        ?.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }?.div(1024)
    return StallSystemPressure(
        loadAverage, tasks?.getOrNull(0)?.toIntOrNull(), tasks?.getOrNull(1)?.toIntOrNull(),
        memoryMb(memInfoText, "MemAvailable"), memoryMb(statusText, "VmRSS"),
        processCpuTicks, mainThreadCpuTicks, clockTicksPerSecond
    )
}
