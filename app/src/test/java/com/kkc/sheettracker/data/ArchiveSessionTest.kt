package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ArchiveSessionTest {
    @Test
    fun `session is constructed with readOnly stores pointed at the cache job dir`() {
        val cacheRoot = Files.createTempDirectory("archive-session-test").toFile()
        cacheRoot.resolve("100 - Alpha").also { it.mkdirs() }

        val session = ArchiveSession.create(
            archiveJobId = "100 - Alpha",
            contentVersion = "v1",
            cacheJobParentDir = cacheRoot,
            folderName = "100 - Alpha",
            tabletId = "tablet-7",
            isDebugBuild = true,
        )

        assertEquals("100 - Alpha", session.archiveJobId)
        assertEquals("v1", session.contentVersion)
        assertTrue(session.readOnly)
        assertEquals(cacheRoot, session.baseDir)
        assertEquals("100 - Alpha", session.folderName)
    }

    @Test
    fun `folderName and baseDir resolve correctly across repeated create calls for the same parent dir`() {
        // Regression test for the bug where folderName was re-derived by listing
        // cacheJobParentDir's children: ProgressStore's own init{} creates a second directory
        // (".state") under that same parent as a side effect of construction, so a second
        // ArchiveSession.create call for the same cacheJobParentDir (e.g. user navigates away
        // from the archive job screen and back) previously risked non-deterministically
        // resolving folderName to ".state" instead of the real job folder, since File.listFiles()
        // order is not guaranteed. Passing folderName explicitly eliminates the directory scan
        // entirely, so this must hold on every call, not just the first.
        val cacheRoot = Files.createTempDirectory("archive-session-test-repeat").toFile()
        cacheRoot.resolve("200 - Beta").also { it.mkdirs() }

        val first = ArchiveSession.create(
            archiveJobId = "200 - Beta",
            contentVersion = "v1",
            cacheJobParentDir = cacheRoot,
            folderName = "200 - Beta",
            tabletId = "tablet-7",
            isDebugBuild = true,
        )
        assertEquals("200 - Beta", first.folderName)
        assertEquals(cacheRoot, first.baseDir)

        // At this point ProgressStore's init{} has created cacheRoot/.state as a second
        // subdirectory alongside the real job folder, exactly the navigate-away-and-back
        // scenario the bug depended on.
        assertTrue(cacheRoot.resolve(".state").isDirectory)

        val second = ArchiveSession.create(
            archiveJobId = "200 - Beta",
            contentVersion = "v1",
            cacheJobParentDir = cacheRoot,
            folderName = "200 - Beta",
            tabletId = "tablet-7",
            isDebugBuild = true,
        )

        assertEquals("200 - Beta", second.folderName)
        assertEquals(cacheRoot, second.baseDir)
    }
}
