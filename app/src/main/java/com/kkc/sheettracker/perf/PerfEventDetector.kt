package com.kkc.sheettracker.perf

/** Everything the detectors look at for one sampling tick. Pure data, so it is unit-testable. */
data class PerfSample(
    val nowMs: Long,
    /** Whole-process CPU across all threads; can exceed 100% on a multi-core tablet. */
    val cpuPercent: Double,
    val mainThreadCpuPercent: Double,
    val topThreads: List<ThreadLoad>,
    val frames: FrameSummary,
    val rssBytes: Long?,
    val foreground: Boolean,
    /** A 3D pane is being touched: legitimately heavy, so episodes are closed and not re-opened. */
    val viewerInteracting: Boolean,
    val msSinceUserInput: Long
)

data class PerfEvent(
    val entryType: String,
    /** "cpu", "idle_redraw" or "memory". */
    val detector: String,
    val sample: PerfSample,
    val peakValue: Double? = null,
    val avgValue: Double? = null,
    val durationMs: Long? = null,
    val endReason: String? = null,
    val memoryGrowthBytes: Long? = null
)

/**
 * Opens an episode after [startSamples] consecutive active samples and only closes it after
 * [endSamples] consecutive calm ones, so one quiet sample in the middle of a long spike no longer
 * splits it into two episodes.
 */
class SustainedDetector(private val startSamples: Int, private val endSamples: Int) {
    class Transition(val started: Boolean, val peak: Double, val avg: Double, val durationMs: Long)

    private var activeStreak = 0
    private var calmStreak = 0
    private var inEpisode = false
    private var streakStartMs = 0L
    private var lastActiveMs = 0L
    private var peak = 0.0
    private var sum = 0.0
    private var count = 0

    val isActive: Boolean get() = inEpisode

    fun update(nowMs: Long, active: Boolean, value: Double): Transition? {
        if (!inEpisode) {
            if (!active) {
                clear()
                return null
            }
            if (activeStreak == 0) streakStartMs = nowMs
            activeStreak++
            accumulate(value)
            lastActiveMs = nowMs
            if (activeStreak < startSamples) return null
            inEpisode = true
            calmStreak = 0
            return Transition(started = true, peak = peak, avg = avg(), durationMs = 0)
        }
        accumulate(value)
        if (active) {
            calmStreak = 0
            lastActiveMs = nowMs
            return null
        }
        calmStreak++
        if (calmStreak < endSamples) return null
        return finish()
    }

    /** Closes an open episode early (e.g. the app went to the background). Null if none is open. */
    fun abort(): Transition? = if (inEpisode) finish() else null.also { clear() }

    private fun finish(): Transition {
        val t = Transition(started = false, peak = peak, avg = avg(), durationMs = lastActiveMs - streakStartMs)
        clear()
        return t
    }

    private fun accumulate(value: Double) {
        if (value > peak) peak = value
        sum += value
        count++
    }

    private fun avg() = if (count > 0) sum / count else 0.0

    private fun clear() {
        activeStreak = 0; calmStreak = 0; inEpisode = false
        peak = 0.0; sum = 0.0; count = 0
    }
}

/**
 * Turns a stream of [PerfSample]s into log-worthy events:
 *  - cpu: sustained whole-process CPU (the original "stuck at 100%" pattern).
 *  - idle_redraw: the app keeps drawing frames while the user is doing nothing. Catches
 *    compositor/animation loops even on a fast tablet where CPU stays low.
 *  - memory: resident memory that climbs steadily.
 */
class PerfEventDetector(private val config: Config = Config()) {
    data class Config(
        val cpuThresholdPercent: Double = 35.0,
        val cpuStartSamples: Int = 12, // 12 x 5s = 60s
        val cpuEndSamples: Int = 3,
        val idleAfterInputMs: Long = 10_000L,
        val idleFpsThreshold: Double = 15.0,
        val idleStartSamples: Int = 12,
        val idleEndSamples: Int = 2,
        val interactionGraceMs: Long = 10_000L,
        val memoryWindowMs: Long = 10 * 60_000L,
        val memoryGrowthBytes: Long = 300L * 1024 * 1024,
        val memoryFloorBytes: Long = 1024L * 1024 * 1024,
        val memoryCooldownMs: Long = 30 * 60_000L,
        val memoryMinSpacingMs: Long = 30_000L
    )

    private val cpu = SustainedDetector(config.cpuStartSamples, config.cpuEndSamples)
    private val idle = SustainedDetector(config.idleStartSamples, config.idleEndSamples)
    private var wasInteracting = false
    private var interactionEndedAt = Long.MIN_VALUE / 2
    private val rssHistory = ArrayDeque<Pair<Long, Long>>()
    private var memoryCooldownUntil = Long.MIN_VALUE / 2

    fun process(s: PerfSample): List<PerfEvent> {
        val events = ArrayList<PerfEvent>(2)

        if (s.viewerInteracting) {
            wasInteracting = true
            // Close (and log) rather than silently drop: an episode that ends because the user
            // grabbed the 3D pane is still a fact worth having in the log.
            cpu.abort()?.let { events += ended("cpu", it, s, "viewer_interaction") }
            idle.abort()?.let { events += ended("idle_redraw", it, s, "viewer_interaction") }
            events += memory(s)
            return events
        }
        if (wasInteracting) {
            wasInteracting = false
            interactionEndedAt = s.nowMs
        }

        if (s.nowMs - interactionEndedAt >= config.interactionGraceMs) {
            cpu.update(s.nowMs, s.cpuPercent >= config.cpuThresholdPercent, s.cpuPercent)?.let {
                events += if (it.started) started("cpu", "sustained_start", s) else ended("cpu", it, s, "calm")
            }
        }

        if (!s.foreground) {
            idle.abort()?.let { events += ended("idle_redraw", it, s, "backgrounded") }
        } else {
            val idleAndDrawing = s.msSinceUserInput >= config.idleAfterInputMs &&
                s.frames.fps >= config.idleFpsThreshold
            idle.update(s.nowMs, idleAndDrawing, s.frames.fps)?.let {
                events += if (it.started) started("idle_redraw", "idle_redraw_start", s) else ended("idle_redraw", it, s, "calm")
            }
        }

        events += memory(s)
        return events
    }

    private fun memory(s: PerfSample): List<PerfEvent> {
        val rss = s.rssBytes ?: return emptyList()
        val last = rssHistory.lastOrNull()
        if (last == null || s.nowMs - last.first >= config.memoryMinSpacingMs) {
            rssHistory.addLast(s.nowMs to rss)
        }
        while (rssHistory.isNotEmpty() && s.nowMs - rssHistory.first().first > config.memoryWindowMs) {
            rssHistory.removeFirst()
        }
        if (s.nowMs < memoryCooldownUntil || rss < config.memoryFloorBytes) return emptyList()
        val floor = rssHistory.minOfOrNull { it.second } ?: return emptyList()
        val growth = rss - floor
        if (growth < config.memoryGrowthBytes) return emptyList()
        memoryCooldownUntil = s.nowMs + config.memoryCooldownMs
        return listOf(
            PerfEvent(entryType = "memory_growth", detector = "memory", sample = s, memoryGrowthBytes = growth)
        )
    }

    private fun started(detector: String, entryType: String, s: PerfSample) =
        PerfEvent(entryType = entryType, detector = detector, sample = s)

    private fun ended(detector: String, t: SustainedDetector.Transition, s: PerfSample, reason: String): PerfEvent {
        val type = if (detector == "cpu") "episode_end" else "${detector}_end"
        return PerfEvent(
            entryType = type,
            detector = detector,
            sample = s,
            peakValue = t.peak,
            avgValue = t.avg,
            durationMs = t.durationMs,
            endReason = reason
        )
    }
}
