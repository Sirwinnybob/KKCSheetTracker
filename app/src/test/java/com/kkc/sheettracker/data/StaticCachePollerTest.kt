package com.kkc.sheettracker.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class StaticCachePollerTest {

    @Test
    fun `clearing idle override wakes poller before stale long delay`() {
        val baseDir = Files.createTempDirectory("static-cache-poller-wake-test").toFile()
        val jobDir = File(baseDir, "1234 - Test Job/.metadata").apply { mkdirs() }
        val indexFile = File(jobDir, "cache_index.json").apply { writeText("{\"version\":1}") }
        val override = MutableStateFlow<Long?>(5_000L)
        val updates = CopyOnWriteArrayList<String>()
        val poller = StaticCachePoller(
            baseDir = baseDir,
            onJobCacheUpdated = { updates += it },
            pollIntervalMs = 5_000L,
            intervalOverrideMs = override
        )

        poller.start()
        try {
            Thread.sleep(100L)
            indexFile.writeText("{\"version\":2}")
            override.value = null

            waitUntil(1_500L) { updates.contains("1234 - Test Job") }
            assertTrue(updates.contains("1234 - Test Job"))
        } finally {
            poller.stop()
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
