package com.kkc.sheettracker.data.unified

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression for the dashboard idle-data blink (docs/2026-09-21-dashboard-idle-data-blink-diagnosis.md).
 * When another tablet's tracker write lands, TrackerChangeMonitor invalidates that job and the app
 * derives the Dashboard immediately, before the 2 s-coalesced rescan has re-listed. invalidateJob
 * used to delete the job from the cached list, so the derive saw N-1 jobs (totals, cards and alert
 * rows dropped) for a couple of seconds.
 */
class UnifiedMetadataEngineInvalidationTest {

    private fun tempBaseDir(): File = Files.createTempDirectory("engine-invalidate-test").toFile()

    private fun writeJob(baseDir: File, folder: String, number: String, done: Int): File {
        val metadata = File(File(baseDir, folder).apply { mkdirs() }, ".metadata").apply { mkdirs() }
        File(metadata, "deployment_gate.json").writeText("""{"deployed": true}""")
        return File(metadata, "cache_index.json").apply {
            writeText(indexJson(folder, number, done))
        }
    }

    private fun indexJson(folder: String, number: String, done: Int) =
        """{"jobInfo":{"folderName":"$folder","jobNumber":"$number","jobName":"Job $number"},""" +
            """"progressSummary":{"cnc":{"totalSheets":10,"done":$done}}}"""

    private fun engine(baseDir: File) = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)

    @Test
    fun invalidatingAJobKeepsItVisibleUntilTheNextRescan() {
        val base = tempBaseDir()
        writeJob(base, "1001 - Alpha", "1001", done = 1)
        writeJob(base, "1002 - Beta", "1002", done = 1)
        val engine = engine(base)
        engine.listJobsFromCacheIndex()
        assertEquals(2, engine.getCachedJobInfos().size)

        engine.invalidateJob("1002 - Beta")

        assertEquals(
            "a derive that runs before the rescan must still see every job",
            listOf("1001 - Alpha", "1002 - Beta"),
            engine.getCachedJobInfos().map { it.folderName }.sorted()
        )
    }

    @Test
    fun theNextRescanReplacesTheStaleEntryWithFreshIndexData() {
        val base = tempBaseDir()
        val index = writeJob(base, "1001 - Alpha", "1001", done = 1)
        writeJob(base, "1002 - Beta", "1002", done = 1)
        val engine = engine(base)
        engine.listJobsFromCacheIndex()
        val firstStamp = index.lastModified()

        engine.invalidateJob("1001 - Alpha")
        index.writeText(indexJson("1001 - Alpha", "1001", done = 7))
        assertTrue(index.setLastModified(firstStamp + 2_000))
        engine.listJobsFromCacheIndex()

        val alpha = engine.getCachedJobInfos().single { it.folderName == "1001 - Alpha" }
        assertEquals(7, alpha.indexProgress?.cnc?.done)
    }

    @Test
    fun aJobThatIsGoneFromDiskStillDropsOutAfterARescan() {
        val base = tempBaseDir()
        writeJob(base, "1001 - Alpha", "1001", done = 1)
        writeJob(base, "1002 - Beta", "1002", done = 1)
        val engine = engine(base)
        engine.listJobsFromCacheIndex()

        engine.invalidateJob("1002 - Beta")
        assertTrue(File(base, "1002 - Beta").deleteRecursively())
        engine.listJobsFromCacheIndex()

        assertEquals(
            "keeping the entry across invalidate must not create ghost jobs",
            listOf("1001 - Alpha"),
            engine.getCachedJobInfos().map { it.folderName }
        )
    }

    @Test
    fun invalidatingStillForcesTheIndexToBeReReadFromDisk() {
        val base = tempBaseDir()
        val index = writeJob(base, "1001 - Alpha", "1001", done = 1)
        val engine = engine(base)
        engine.listJobsFromCacheIndex()
        val stamp = index.lastModified()

        // Same mtime, different content: only an invalidated (cleared) cache entry would re-read it.
        index.writeText(indexJson("1001 - Alpha", "1001", done = 5))
        assertTrue(index.setLastModified(stamp))
        engine.invalidateJob("1001 - Alpha")

        assertEquals(5, engine.getProgressFromIndex("1001 - Alpha")?.cnc?.done)
    }
}
