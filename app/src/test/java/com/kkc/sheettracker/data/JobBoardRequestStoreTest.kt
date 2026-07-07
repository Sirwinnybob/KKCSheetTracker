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

        val edits = Gson().fromJson(
            File(baseDir, "job_board_request.json").readText(),
            JobBoardEditRequest::class.java
        ).edits

        // Without the read-merge-write lock, concurrent edits lose updates; every folder must survive.
        assertEquals((0 until editCount).map { "Job-$it" }.toSet(), edits.map { it.folderName }.toSet())
        assertEquals(editCount, edits.size)
    }
}
