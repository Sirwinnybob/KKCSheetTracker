package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ScanStalenessTest {

    private fun writeFileWithMtime(file: File, mtime: Long) {
        file.parentFile?.mkdirs()
        file.writeText("{}")
        // Explicit mtimes keep the test independent of filesystem clock granularity.
        file.setLastModified(mtime)
    }

    @Test
    fun signatureIsStableWhenNothingChanges() {
        val baseDir = Files.createTempDirectory("scan-staleness-stable").toFile()
        writeFileWithMtime(File(baseDir, "job_board.json"), 1_000_000L)
        writeFileWithMtime(File(baseDir, "Job-A/.metadata/cache_static.json"), 2_000_000L)
        writeFileWithMtime(File(baseDir, "Job-A/.metadata/deployment_gate.json"), 3_000_000L)

        val first = computeLightStalenessSignature(baseDir)
        val second = computeLightStalenessSignature(baseDir)

        assertEquals("signature must be stable when nothing changes", first, second)
    }

    @Test
    fun changingBoardMtimeFlipsSignature() {
        val baseDir = Files.createTempDirectory("scan-staleness-board").toFile()
        val boardFile = File(baseDir, "job_board.json")
        writeFileWithMtime(boardFile, 1_000_000L)
        writeFileWithMtime(File(baseDir, "Job-A/.metadata/cache_static.json"), 2_000_000L)

        val before = computeLightStalenessSignature(baseDir)
        boardFile.setLastModified(9_000_000L)
        val after = computeLightStalenessSignature(baseDir)

        assertNotEquals("a job_board.json mtime change must flip the signature", before, after)
    }

    @Test
    fun changingDeploymentGateMtimeFlipsSignature() {
        val baseDir = Files.createTempDirectory("scan-staleness-gate").toFile()
        writeFileWithMtime(File(baseDir, "job_board.json"), 1_000_000L)
        writeFileWithMtime(File(baseDir, "Job-A/.metadata/cache_static.json"), 2_000_000L)
        val gateFile = File(baseDir, "Job-A/.metadata/deployment_gate.json")
        writeFileWithMtime(gateFile, 3_000_000L)

        val before = computeLightStalenessSignature(baseDir)
        gateFile.setLastModified(9_000_000L)
        val after = computeLightStalenessSignature(baseDir)

        assertNotEquals("a deployment_gate.json mtime change must flip the signature", before, after)
    }

    @Test
    fun changingCacheStaticMtimeFlipsSignature() {
        val baseDir = Files.createTempDirectory("scan-staleness-cache").toFile()
        writeFileWithMtime(File(baseDir, "job_board.json"), 1_000_000L)
        val cacheFile = File(baseDir, "Job-A/.metadata/cache_static.json")
        writeFileWithMtime(cacheFile, 2_000_000L)

        val before = computeLightStalenessSignature(baseDir)
        cacheFile.setLastModified(9_000_000L)
        val after = computeLightStalenessSignature(baseDir)

        assertNotEquals("a cache_static.json mtime change must flip the signature", before, after)
    }
}
