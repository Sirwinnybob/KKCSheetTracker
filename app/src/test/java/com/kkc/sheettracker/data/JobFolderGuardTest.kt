package com.kkc.sheettracker.data

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
}
