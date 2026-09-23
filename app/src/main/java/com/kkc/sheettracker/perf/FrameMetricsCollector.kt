package com.kkc.sheettracker.perf

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/**
 * Feeds [FrameWindowStats] from the window's FrameMetrics: one callback per frame the app
 * actually draws (an idle app draws none). GPU time per frame is only reported on API 31+.
 */
class FrameMetricsCollector(private val stats: FrameWindowStats) {
    private var thread: HandlerThread? = null
    private var attachedWindow: Window? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    @Synchronized
    fun attach(window: Window) {
        if (attachedWindow === window) return
        detach()
        val worker = thread ?: HandlerThread("kkc-frame-metrics").also { it.start(); thread = it }
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            // The FrameMetrics object is reused between callbacks, so read values immediately.
            val totalMs = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / NANOS_PER_MS
            val gpuMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                metrics.getMetric(FrameMetrics.GPU_DURATION).takeIf { it > 0 }?.let { it / NANOS_PER_MS }
            } else {
                null
            }
            stats.record(totalMs, gpuMs)
        }
        runCatching { window.addOnFrameMetricsAvailableListener(l, Handler(worker.looper)) }
            .onSuccess {
                attachedWindow = window
                listener = l
            }
    }

    @Synchronized
    fun detach() {
        val w = attachedWindow
        val l = listener
        if (w != null && l != null) runCatching { w.removeOnFrameMetricsAvailableListener(l) }
        attachedWindow = null
        listener = null
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000.0
    }
}
