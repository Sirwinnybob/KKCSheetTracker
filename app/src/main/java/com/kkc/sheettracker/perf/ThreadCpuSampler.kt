package com.kkc.sheettracker.perf

import java.io.File

/** Combined CPU of all threads sharing a (digit-normalised) name, e.g. the "DefaultDispatch" pool. */
data class ThreadLoad(val name: String, val cpuPercent: Double, val threadCount: Int)

/**
 * Per-thread CPU between two samples. The thread names are the point: a WebView bug shows up as
 * `Chrome_InProcGp` / `RenderThread` being hot while `main` is not, which whole-process CPU alone
 * cannot tell you.
 */
class ThreadCpuSampler(
    private val clkTck: Long,
    private val mainTid: Int,
    private val readThreads: () -> Map<Int, ThreadTicks>,
    private val topCount: Int = 6,
    private val minReportedPercent: Double = 0.5
) {
    data class Result(val mainThreadCpuPercent: Double, val topThreads: List<ThreadLoad>)

    private var previous: Map<Int, ThreadTicks>? = null
    private var previousMs = 0L

    /** Null on the first call (nothing to diff against) or if the clock did not advance. */
    fun sample(nowMs: Long): Result? {
        val current = readThreads()
        val before = previous
        val beforeMs = previousMs
        previous = current
        previousMs = nowMs
        val wallMs = nowMs - beforeMs
        if (before == null || wallMs <= 0) return null

        fun percent(ticks: Long) = (ticks * 1000.0 / clkTck) / wallMs * 100.0

        val byName = HashMap<String, Pair<Double, Int>>()
        var mainPercent = 0.0
        for ((tid, now) in current) {
            val prev = before[tid]
            // A tid can be reused by a different thread; only diff when the name still matches.
            val delta = if (prev != null && prev.name == now.name) now.ticks - prev.ticks else now.ticks
            if (delta < 0) continue
            val pct = percent(delta)
            if (tid == mainTid) mainPercent = pct
            val key = normalise(now.name)
            val old = byName[key]
            byName[key] = Pair((old?.first ?: 0.0) + pct, (old?.second ?: 0) + 1)
        }
        val top = byName.entries
            .map { ThreadLoad(it.key, it.value.first, it.value.second) }
            .filter { it.cpuPercent >= minReportedPercent }
            .sortedByDescending { it.cpuPercent }
            .take(topCount)
        return Result(mainPercent, top)
    }

    companion object {
        /** Collapses digit runs so "binder:123_4" and "binder:123_5" aggregate. */
        fun normalise(name: String): String = name.replace(Regex("\\d+"), "#")

        /** Reads every thread of this process from /proc/self/task. */
        fun readSelfThreads(): Map<Int, ThreadTicks> {
            val out = HashMap<Int, ThreadTicks>()
            val dirs = File("/proc/self/task").listFiles() ?: return out
            for (dir in dirs) {
                val tid = dir.name.toIntOrNull() ?: continue
                val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: continue
                ProcStat.parseThread(stat)?.let { out[tid] = it }
            }
            return out
        }
    }
}
