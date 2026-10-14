package com.kkc.sheettracker.data

import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JobFolderGuardTest {
    private fun base(): File = Files.createTempDirectory("job-guard").toFile()

    @Test
    fun blankAndTraversalNamesAreNeverLive() {
        val base = base()
        File(base, ".metadata").mkdirs() // would match "." if not rejected
        assertFalse(isLiveJobFolder(base, ""))
        assertFalse(isLiveJobFolder(base, "   "))
        assertFalse(isLiveJobFolder(base, "a/b"))
        assertFalse(isLiveJobFolder(base, "a\\b"))
        assertFalse(isLiveJobFolder(base, "."))
        assertFalse(isLiveJobFolder(base, ".."))
    }

    @Test
    fun jobWithMetadataDirectoryIsLive() {
        val base = base()
        File(base, "670 - ESCHRICH/.metadata").mkdirs()
        assertTrue(isLiveJobFolder(base, "670 - ESCHRICH"))
    }

    @Test
    fun jobDirWithoutMetadataIsNotLive() {
        val base = base()
        File(base, "670 - ESCHRICH").mkdirs()
        assertFalse(isLiveJobFolder(base, "670 - ESCHRICH"))
    }

    @Test
    fun missingJobIsNotLiveAndNothingIsCreated() {
        val base = base()
        assertFalse(isLiveJobFolder(base, "999 - GONE"))
        assertFalse(File(base, "999 - GONE").exists())
    }

    @Test
    fun metadataThatIsAFileIsNotLive() {
        val base = base()
        File(base, "670 - X").mkdirs()
        File(base, "670 - X/.metadata").writeText("x")
        assertFalse(isLiveJobFolder(base, "670 - X"))
    }

    @Test
    fun failedAtomicWriteLeavesNoTempFile() {
        val dir = base()
        // A non-empty directory at the target path makes the final move fail after the temp is written.
        val target = File(dir, "out.json").apply { mkdirs() }
        File(target, "keep.txt").writeText("x")
        try {
            atomicWriteFile(target, "{}")
            org.junit.Assert.fail("expected the move to fail")
        } catch (_: java.io.IOException) {
            // expected
        }
        assertEquals(listOf("out.json"), dir.list()!!.toList())
        assertTrue(File(target, "keep.txt").isFile)
    }

    @Test
    fun successfulAtomicWriteLeavesOnlyTheTarget() {
        val dir = base()
        atomicWriteFile(File(dir, "out.json"), "{\"a\":1}")
        assertEquals(listOf("out.json"), dir.list()!!.toList())
        assertEquals("{\"a\":1}", File(dir, "out.json").readText())
    }

    @Test
    fun debouncerShowsAtMostOncePerJobPerWindow() {
        val d = JobFolderGuardDebouncer(windowMs = 10_000L)
        assertTrue(d.shouldShow("670", 1_000L))
        assertFalse(d.shouldShow("670", 5_000L))
        assertTrue("other job independent", d.shouldShow("671", 5_000L))
        assertFalse(d.shouldShow("670", 10_999L))
        assertTrue(d.shouldShow("670", 11_000L))
        assertFalse("window restarts from the last shown", d.shouldShow("670", 15_000L))
    }

    @Test
    fun reportEmitsTheJobName() = kotlinx.coroutines.runBlocking {
        val received = kotlinx.coroutines.CompletableDeferred<String>()
        val collector = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            JobFolderGuardEvents.skipped.collect { received.complete(it) }
        }
        JobFolderGuardEvents.report("999 - GONE")
        assertEquals("999 - GONE", kotlinx.coroutines.withTimeout(2_000) { received.await() })
        assertEquals("999 - GONE is no longer in Ready Jobs — change not saved", JobFolderGuardEvents.toastText("999 - GONE"))
        collector.cancel()
    }
}
