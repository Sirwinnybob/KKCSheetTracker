package com.kkc.sheettracker.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class TrackerChangeMonitorStartThreadTest {

    /** Records the thread of every directory listing so the test can see where the scan ran. */
    private class ListingRecorderDir(path: String, val listingThreads: MutableList<Thread>) : File(path) {
        override fun listFiles(): Array<File>? {
            listingThreads += Thread.currentThread()
            return super.listFiles()
        }
    }

    @Test
    fun `start does not scan tracker directories on the calling thread`() {
        // start() is called from a lifecycle ON_START observer on the main thread. Field logs
        // (cpu_spikes main_thread_stall, 8.5.6 / 8.5.8) caught it blocked 2.9-4.5 s at cold start
        // listing every job's tracker dirs over the shared-storage FUSE mount.
        val realBase = Files.createTempDirectory("tracker-change-monitor-start-thread").toFile()
        File(realBase, "1234 - Test Job/CNC/.tracker").mkdirs()
        val listingThreads = CopyOnWriteArrayList<Thread>()
        val baseDir = ListingRecorderDir(realBase.path, listingThreads)
        val reported = CopyOnWriteArrayList<Set<String>>()
        val monitor = TrackerChangeMonitor(
            baseDir = baseDir,
            progressStore = ProgressStore(realBase, "tablet-a", File(realBase, ".local"), readOnly = true),
            hardwoodsProgressStore = HardwoodsProgressStore(realBase, "tablet-a", readOnly = true),
            viewerInteraction = MutableStateFlow(false),
            onCncJobsChanged = { reported += it }
        )

        val caller = Thread.currentThread()
        monitor.start()
        try {
            waitUntil(2_000L) { listingThreads.isNotEmpty() }
            assertFalse(
                "tracker dir scan ran on the thread that called start()",
                listingThreads.any { it === caller }
            )
            // The initial scan still happens and still reports the job it found.
            waitUntil(3_000L) { reported.any { "1234 - Test Job" in it } }
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
