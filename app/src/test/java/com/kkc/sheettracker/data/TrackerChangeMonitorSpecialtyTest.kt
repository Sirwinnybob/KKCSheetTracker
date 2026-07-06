package com.kkc.sheettracker.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TrackerChangeMonitorSpecialtyTest {
    @Test
    fun cncTrackerChangeReportsAffectedJobForTargetedMetadataRefresh() {
        val baseDir = Files.createTempDirectory("tracker-change-monitor-cnc-target-test").toFile()
        val jobFolder = "1234 - Test Job"
        val trackerDir = File(baseDir, "$jobFolder/CNC/.tracker").apply { mkdirs() }
        val trackerFile = File(trackerDir, "tablet-a.json")
        trackerFile.writeText("""{"tabletId":"tablet-a","actions":[]}""")

        val progressStore = ProgressStore(baseDir, "tablet-a", File(baseDir, ".local"), readOnly = true)
        val hardwoodsStore = HardwoodsProgressStore(baseDir, "tablet-a", readOnly = true)
        val reported = mutableListOf<Set<String>>()
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
            Thread.sleep(1_700L)
            reported.clear()

            trackerFile.writeText(
                """
                    {
                      "tabletId":"tablet-a",
                      "actions":[
                        {"file":"1234 - White Melamine.pdf","page":1,"part":7,"action":"unbad_part","timestamp":"2026-07-06T12:00:00Z","fileFingerprint":"fp-a"}
                      ]
                    }
                """.trimIndent()
            )

            waitUntil(timeoutMs = 4_000L) {
                reported.any { jobFolder in it }
            }
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun discoverTrackerDirs_includesSpecialtyTrackerDirectory() {
        val baseDir = Files.createTempDirectory("tracker-change-monitor-specialty-test").toFile()
        val jobFolder = "1234 - Test Job"
        File(baseDir, "$jobFolder/.metadata/admin/.tracker").mkdirs()

        val progressStore = ProgressStore(baseDir, "tablet-a", File(baseDir, ".local"), readOnly = true)
        val hardwoodsStore = HardwoodsProgressStore(baseDir, "tablet-a", readOnly = true)
        val specialtyStore = SpecialtyProgressStore(baseDir, "tablet-a", readOnly = true)
        val monitor = TrackerChangeMonitor(
            baseDir = baseDir,
            progressStore = progressStore,
            hardwoodsProgressStore = hardwoodsStore,
            specialtyProgressStore = specialtyStore,
            viewerInteraction = MutableStateFlow(false)
        )

        val method = TrackerChangeMonitor::class.java.getDeclaredMethod("discoverTrackerDirs")
        method.isAccessible = true
        val discovered = method.invoke(monitor) as List<*>

        val hasSpecialty = discovered.any { it.toString().contains("kind=SPECIALTY") }
        assertTrue(hasSpecialty)
    }

    @Test
    fun addingSpecialtyTrackerDirAfterStart_invalidatesSpecialtyCache() {
        val baseDir = Files.createTempDirectory("tracker-change-monitor-specialty-live-test").toFile()
        val progressStore = ProgressStore(baseDir, "tablet-a", File(baseDir, ".local"), readOnly = true)
        val hardwoodsStore = HardwoodsProgressStore(baseDir, "tablet-a", readOnly = true)
        val specialtyStore = SpecialtyProgressStore(baseDir, "tablet-a", readOnly = true)
        val monitor = TrackerChangeMonitor(
            baseDir = baseDir,
            progressStore = progressStore,
            hardwoodsProgressStore = hardwoodsStore,
            specialtyProgressStore = specialtyStore,
            viewerInteraction = MutableStateFlow(false),
            pollingIntervalMs = 100L
        )

        monitor.start()
        try {
            Thread.sleep(1_700L)
            val versionBefore = specialtyStore.progressVersion.value

            val jobFolder = "1234 - New Job"
            val trackerDir = File(baseDir, "$jobFolder/.metadata/admin/.tracker")
            trackerDir.mkdirs()
            File(trackerDir, "tablet-a.json").writeText(
                """
                    {
                      "tabletId":"tablet-a",
                      "schemaVersion":2,
                      "completions":{}
                    }
                """.trimIndent()
            )

            waitUntil(timeoutMs = 4_000L) {
                specialtyStore.progressVersion.value > versionBefore
            }
            assertEquals(versionBefore + 1L, specialtyStore.progressVersion.value)
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
