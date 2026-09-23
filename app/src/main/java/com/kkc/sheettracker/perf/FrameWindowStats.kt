package com.kkc.sheettracker.perf

/** Frames the app drew during one sampling window. */
data class FrameSummary(
    val frames: Int,
    val fps: Double,
    val avgFrameMs: Double,
    val maxFrameMs: Double,
    /** Frames slower than [FrameWindowStats.JANKY_MS]. */
    val jankyFrames: Int,
    /** Frames slower than [FrameWindowStats.FROZEN_MS] — the user sees the UI hang. */
    val frozenFrames: Int,
    /** GPU time per frame; null where the platform does not report it (API < 31). */
    val avgGpuMs: Double?,
    val maxGpuMs: Double?
) {
    companion object {
        val EMPTY = FrameSummary(0, 0.0, 0.0, 0.0, 0, 0, null, null)
    }
}

/**
 * Accumulates per-frame timings (fed from FrameMetrics on a background handler) and hands the
 * sampler thread a summary. An app with nothing to draw produces zero frames, so a high frame
 * rate while the user is idle is itself the signal for a redraw loop, independent of how fast
 * the tablet is.
 */
class FrameWindowStats {
    private var frames = 0
    private var totalMs = 0.0
    private var maxMs = 0.0
    private var janky = 0
    private var frozen = 0
    private var gpuFrames = 0
    private var gpuTotalMs = 0.0
    private var gpuMaxMs = 0.0

    @Synchronized
    fun record(totalFrameMs: Double, gpuMs: Double? = null) {
        frames++
        totalMs += totalFrameMs
        if (totalFrameMs > maxMs) maxMs = totalFrameMs
        if (totalFrameMs > JANKY_MS) janky++
        if (totalFrameMs > FROZEN_MS) frozen++
        if (gpuMs != null && gpuMs >= 0) {
            gpuFrames++
            gpuTotalMs += gpuMs
            if (gpuMs > gpuMaxMs) gpuMaxMs = gpuMs
        }
    }

    @Synchronized
    fun snapshotAndReset(windowMs: Long): FrameSummary {
        val summary = if (frames == 0 || windowMs <= 0) {
            FrameSummary.EMPTY
        } else {
            FrameSummary(
                frames = frames,
                fps = frames * 1000.0 / windowMs,
                avgFrameMs = totalMs / frames,
                maxFrameMs = maxMs,
                jankyFrames = janky,
                frozenFrames = frozen,
                avgGpuMs = if (gpuFrames > 0) gpuTotalMs / gpuFrames else null,
                maxGpuMs = if (gpuFrames > 0) gpuMaxMs else null
            )
        }
        frames = 0; totalMs = 0.0; maxMs = 0.0; janky = 0; frozen = 0
        gpuFrames = 0; gpuTotalMs = 0.0; gpuMaxMs = 0.0
        return summary
    }

    companion object {
        const val JANKY_MS = 32.0
        const val FROZEN_MS = 700.0
    }
}
