package com.kkc.sheettracker.perf

import org.junit.Assert.*
import org.junit.Test

class StallDiagnosticsTest {
    @Test
    fun samplesAreSpacedAndBoundedAndSkippedWhenWatchdogWasPaused() {
        val window = StallDiagnosticWindow("stall-1", startedAtMs = 0)
        var captures = 0
        fun capture(now: Long, gap: Long = 1_000) = window.capture(now, gap) {
            captures++
            MainThreadStallSample(now, now, "RUNNABLE", "other", emptyList())
        }
        val openingSnapshot = capture(2_000)!!
        assertEquals(1, openingSnapshot.size)
        assertNull(capture(2_500))
        assertNull(capture(12_000, gap = 10_000))
        assertEquals(2, capture(13_000)!!.size)
        assertEquals(3, capture(14_000)!!.size)
        assertEquals(4, capture(15_000)!!.size)
        assertNull(capture(16_000))
        assertEquals(4, captures)
        assertEquals(1, openingSnapshot.size)
        assertEquals(10_000L, window.longestWatchdogTickGapMs)
        assertEquals(listOf(2_000L, 13_000L, 14_000L, 15_000L), window.samples.map { it.uptimeMs })
    }

    @Test
    fun accessibilityWaitRequiresBothBinderAndAccessibilityFrames() {
        val binder = "android.os.BinderProxy.transactNative(BinderProxy.java:-2)"
        val accessibility = "android.view.accessibility.IAccessibilityManager\$Stub\$Proxy.addClient(IAccessibilityManager.java:1454)"
        assertEquals("accessibility_binder", classifyStallWait(listOf(binder, accessibility), "RUNNABLE"))
        assertEquals("binder", classifyStallWait(listOf(binder), "RUNNABLE"))
        assertEquals("other", classifyStallWait(listOf(accessibility), "RUNNABLE"))
        assertEquals("java_monitor", classifyStallWait(emptyList(), "BLOCKED"))
    }

    @Test
    fun startupTraceShowsEdgeToEdgeWaitAndStopsTimingAtFirstPreDraw() {
        val trace = StartupTrace()
        trace.begin(100)
        trace.mark("edge_to_edge_setup", 140)
        val waiting = trace.snapshot(2_140)!!
        assertEquals("edge_to_edge_setup", waiting.latestStage)
        assertEquals(2_040L, waiting.elapsedMs)
        assertFalse(waiting.completed)
        trace.finish(2_300)
        trace.mark("ignored_recomposition", 3_000)
        val finished = trace.snapshot(10_000)!!
        assertEquals("first_pre_draw", finished.latestStage)
        assertEquals(2_200L, finished.elapsedMs)
        assertTrue(finished.completed)
        trace.begin(20_000)
        assertEquals(listOf("activity_onCreate"), trace.snapshot(20_001)!!.stages.map { it.stage })
    }

    @Test
    fun pressureParserHandlesMissingOrMalformedProcFiles() {
        val pressure = parseStallSystemPressure(
            "1.20 0.50 0.25 3/800 123", "MemTotal: 8192000 kB\nMemAvailable: 2048000 kB",
            "VmRSS: 102400 kB", null, null, 100
        )
        assertEquals(listOf(1.2, 0.5, 0.25), pressure.loadAverage)
        assertEquals(3, pressure.runnableTasks)
        assertEquals(800, pressure.totalTasks)
        assertEquals(2_000L, pressure.availableMemoryMb)
        assertEquals(100L, pressure.processRssMb)
        val absent = parseStallSystemPressure("bad", "", "", null, null, 100)
        assertNull(absent.loadAverage)
        assertNull(absent.availableMemoryMb)
        assertNull(absent.processRssMb)
    }
}
