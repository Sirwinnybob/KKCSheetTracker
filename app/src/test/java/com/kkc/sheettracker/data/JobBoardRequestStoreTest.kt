package com.kkc.sheettracker.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class JobBoardRequestStoreTest {

    @Test
    fun concurrentEditsToDistinctFoldersAreAllRetained() {
        val baseDir = Files.createTempDirectory("job-board-request-test").toFile()
        val editCount = 50
        // Two separate store instances over the same directory, mirroring how different screens
        // each build their own JobBoardRequestStore(File(basePath)).
        val stores = listOf(JobBoardRequestStore(baseDir), JobBoardRequestStore(baseDir))
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(editCount)

        for (i in 0 until editCount) {
            val store = stores[i % stores.size]
            pool.execute {
                start.await()
                store.queueLabelEdit("Job-$i", listOf(i), "tablet-a")
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("edits did not complete in time", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        // METADATA_AUDIT.md M-04: the request file is now keyed on tabletId, not a fixed name.
        val edits = Gson().fromJson(
            File(baseDir, "job_board_request.tablet-a.json").readText(),
            JobBoardEditRequest::class.java
        ).edits

        // Without the read-merge-write lock, concurrent edits lose updates; every folder must survive.
        assertEquals((0 until editCount).map { "Job-$it" }.toSet(), edits.map { it.folderName }.toSet())
        assertEquals(editCount, edits.size)
    }

    // ==================== M-04 regression: per-tablet filenames don't collide ====================

    @Test
    fun twoTabletsQueuingBeforeAPollBothProduceDistinctRequestFiles() {
        // Regression for METADATA_AUDIT.md M-04: two tablets queuing job-board edits before the
        // backend's next poll cycle used to collide on one shared `job_board_request.json`
        // (lost update or an unread `.sync-conflict-*` copy). Each tablet must now write its own
        // file so both edits survive until the backend consumes them.
        val baseDir = Files.createTempDirectory("job-board-request-two-tablets").toFile()
        val store = JobBoardRequestStore(baseDir)

        store.queueLabelEdit("Job-A", listOf(1), "tablet-a")
        store.queueLabelEdit("Job-B", listOf(2), "tablet-b")

        val tabletAFile = File(baseDir, "job_board_request.tablet-a.json")
        val tabletBFile = File(baseDir, "job_board_request.tablet-b.json")
        assertTrue("tablet-a's request file is missing", tabletAFile.exists())
        assertTrue("tablet-b's request file is missing", tabletBFile.exists())

        val tabletAEdits = Gson().fromJson(tabletAFile.readText(), JobBoardEditRequest::class.java)
        val tabletBEdits = Gson().fromJson(tabletBFile.readText(), JobBoardEditRequest::class.java)

        assertEquals(listOf("Job-A"), tabletAEdits.edits.map { it.folderName })
        assertEquals("tablet-a", tabletAEdits.tabletId)
        assertEquals(listOf("Job-B"), tabletBEdits.edits.map { it.folderName })
        assertEquals("tablet-b", tabletBEdits.tabletId)
    }
}
