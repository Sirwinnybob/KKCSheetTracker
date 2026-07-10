package com.kkc.sheettracker.data

import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TrackerChangeMonitorEventsTest {

    @Test
    fun discoverTrackerDirs_includesCncAndHardwoodEventsSubdirectories() {
        val baseDir = Files.createTempDirectory("monitor-events-discover-test").toFile()
        val jobFolder = "1234 - Test Job"
        File(baseDir, "$jobFolder/CNC/.tracker/events").mkdirs()
        File(baseDir, "$jobFolder/.metadata/hardwoods/.tracker/events").mkdirs()

        val progressStore = ProgressStore(baseDir, "tablet-a", File(baseDir, ".local"), readOnly = true)
        val hardwoodsStore = HardwoodsProgressStore(baseDir, "tablet-a", readOnly = true)
        val monitor = TrackerChangeMonitor(
            baseDir = baseDir,
            progressStore = progressStore,
            hardwoodsProgressStore = hardwoodsStore,
            viewerInteraction = MutableStateFlow(false)
        )

        val method = TrackerChangeMonitor::class.java.getDeclaredMethod("discoverTrackerDirs")
        method.isAccessible = true
        val discovered = method.invoke(monitor) as List<*>

        val hasCncEvents = discovered.any {
            it.toString().contains("kind=CNC") && it.toString().replace('\\', '/').contains("CNC/.tracker/events")
        }
        val hasHardwoodEvents = discovered.any {
            it.toString().contains("kind=HARDWOODS") &&
                it.toString().replace('\\', '/').contains(".metadata/hardwoods/.tracker/events")
        }
        assertTrue("expected discoverTrackerDirs to include CNC/.tracker/events, got: $discovered", hasCncEvents)
        assertTrue("expected discoverTrackerDirs to include .metadata/hardwoods/.tracker/events, got: $discovered", hasHardwoodEvents)
    }

    @Test
    fun ndjsonFileWrittenInCncEventsDir_isDetectedAsCncChange() {
        val baseDir = Files.createTempDirectory("monitor-events-live-test").toFile()
        val jobFolder = "1234 - Test Job"
        val eventsDir = File(baseDir, "$jobFolder/CNC/.tracker/events").apply { mkdirs() }

        val progressStore = ProgressStore(baseDir, "tablet-self", File(baseDir, ".local"), readOnly = true)
        val hardwoodsStore = HardwoodsProgressStore(baseDir, "tablet-self", readOnly = true)
        val reported = java.util.concurrent.CopyOnWriteArrayList<Set<String>>()
        val monitor = TrackerChangeMonitor(
            baseDir = baseDir,
            progressStore = progressStore,
            hardwoodsProgressStore = hardwoodsStore,
            viewerInteraction = MutableStateFlow(false),
            onCncJobsChanged = { jobs -> reported += jobs },
            pollingIntervalMs = 100L
        )

        monitor.start()
        try {
            Thread.sleep(1_700L) // clear the startup warmup window
            reported.clear() // discard the initial discovery invalidation from tracking CNC/.tracker itself

            val payload = JsonObject().apply {
                addProperty("file", "A.pdf")
                addProperty("page", 1)
            }
            appendTrackerEvent(
                File(eventsDir, "tablet-peer.ndjson"),
                TrackerEvent(op = "set_complete_true", payload = payload, wallTime = "2026-07-09T09:00:00Z", lamport = 1)
            )

            waitUntil(timeoutMs = 4_000L) {
                reported.any { jobFolder in it }
            }
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
