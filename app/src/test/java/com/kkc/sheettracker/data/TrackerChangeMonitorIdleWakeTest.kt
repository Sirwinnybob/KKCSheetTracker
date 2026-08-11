package com.kkc.sheettracker.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class TrackerChangeMonitorIdleWakeTest {

    @Test
    fun `clearing idle override wakes tracker poller before stale long delay`() {
        val baseDir = Files.createTempDirectory("tracker-change-monitor-wake-test").toFile()
        val trackerDir = File(baseDir, "1234 - Test Job/CNC/.tracker").apply { mkdirs() }
        val trackerFile = File(trackerDir, "tablet-a.json").apply {
            writeText("{\"actions\":[]}")
        }
        val override = MutableStateFlow<Long?>(5_000L)
        val reported = CopyOnWriteArrayList<Set<String>>()
        val monitor = TrackerChangeMonitor(
            baseDir = baseDir,
            progressStore = ProgressStore(baseDir, "tablet-a", File(baseDir, ".local"), readOnly = true),
            hardwoodsProgressStore = HardwoodsProgressStore(baseDir, "tablet-a", readOnly = true),
            viewerInteraction = MutableStateFlow(false),
            onCncJobsChanged = { reported += it },
            pollingIntervalMs = 5_000L,
            intervalOverrideMs = override
        )

        monitor.start()
        try {
            Thread.sleep(1_700L)
            reported.clear()
            trackerFile.writeText("{\"actions\":[{\"file\":\"a.pdf\"}]}")
            override.value = null

            waitUntil(1_500L) { reported.any { "1234 - Test Job" in it } }
            assertTrue(reported.any { "1234 - Test Job" in it })
        } finally {
            monitor.stop()
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20L)
        }
        throw AssertionError("Timed out waiting for condition")
    }
}
