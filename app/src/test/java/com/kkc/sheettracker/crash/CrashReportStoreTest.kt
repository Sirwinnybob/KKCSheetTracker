package com.kkc.sheettracker.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CrashReportStoreTest {

    @Test
    fun recordCrash_writesReadableJsonToSharedCrashDirectory() {
        val baseDir = Files.createTempDirectory("crash-shared").toFile()
        val pendingDir = Files.createTempDirectory("crash-pending").toFile()
        val store = CrashReportStore(
            pendingDir = pendingDir,
            clock = { 1_783_730_400_123L },
            retentionLimit = 100
        )

        val result = store.recordCrash(
            baseDir = baseDir,
            context = CrashReportContext(
                tabletId = "Tablet / CNC #1",
                workMode = "CNC",
                currentTab = "jobs",
                currentRoute = "viewer/{folderName}/{pdfFilename}/{startPage}",
                activeJobFolderName = "1234 Smith Kitchen"
            ),
            environment = CrashEnvironment(
                appVersionName = "6.2.0",
                appVersionCode = 6016,
                androidRelease = "15",
                androidSdk = 35,
                manufacturer = "Samsung",
                model = "Tab Active"
            ),
            throwable = IllegalStateException("boom")
        )

        assertTrue(result.writtenFile.absolutePath.contains(".metadata${File.separator}crashes"))
        assertFalse(result.wrotePending)
        val json = result.writtenFile.readText()
        assertTrue(json.contains("\"tabletId\": \"Tablet / CNC #1\""))
        assertTrue(json.contains("\"workMode\": \"CNC\""))
        assertTrue(json.contains("\"currentTab\": \"jobs\""))
        assertTrue(json.contains("\"activeJobFolderName\": \"1234 Smith Kitchen\""))
        assertTrue(json.contains("\"appVersionName\": \"6.2.0\""))
        assertTrue(json.contains("\"exceptionType\": \"java.lang.IllegalStateException\""))
        assertTrue(json.contains("\"message\": \"boom\""))
        assertTrue(json.contains("IllegalStateException: boom"))
    }

    @Test
    fun recordCrash_usesPendingDirectoryWhenBaseDirIsMissing() {
        val pendingDir = Files.createTempDirectory("crash-pending").toFile()
        val store = CrashReportStore(
            pendingDir = pendingDir,
            clock = { 1_783_730_400_123L },
            retentionLimit = 100
        )

        val result = store.recordCrash(
            baseDir = null,
            context = CrashReportContext(tabletId = "Tablet A"),
            environment = CrashEnvironment(appVersionName = "6.2.0", appVersionCode = 6016),
            throwable = RuntimeException("early startup")
        )

        assertTrue(result.wrotePending)
        assertTrue(result.writtenFile.parentFile!!.absolutePath.startsWith(pendingDir.absolutePath))
        assertTrue(result.writtenFile.readText().contains("\"message\": \"early startup\""))
    }

    @Test
    fun flushPendingCopiesReportsToSharedDirectoryAndDeletesPendingFiles() {
        val baseDir = Files.createTempDirectory("crash-flush-shared").toFile()
        val pendingDir = Files.createTempDirectory("crash-flush-pending").toFile()
        val pendingFile = File(pendingDir, "2026-06-23T10-00-00_Tablet-A_crash.json")
        pendingFile.writeText("{\"status\":\"pending\"}")
        val store = CrashReportStore(pendingDir = pendingDir, retentionLimit = 100)

        val copied = store.flushPending(baseDir)

        assertEquals(1, copied.size)
        assertTrue(copied.single().absolutePath.contains(".metadata${File.separator}crashes"))
        assertTrue(copied.single().readText().contains("\"status\":\"pending\""))
        assertFalse(pendingFile.exists())
    }

    @Test
    fun enforceRetentionKeepsNewestCrashFiles() {
        val baseDir = Files.createTempDirectory("crash-retention").toFile()
        val crashDir = File(baseDir, ".metadata/crashes").apply { mkdirs() }
        repeat(5) { index ->
            File(crashDir, "old-$index.json").apply {
                writeText("{}")
                setLastModified(1_000L + index)
            }
        }
        val keep = File(crashDir, "keep.txt").apply {
            writeText("not a crash report")
            setLastModified(1L)
        }
        val store = CrashReportStore(pendingDir = Files.createTempDirectory("pending").toFile(), retentionLimit = 2)

        store.enforceRetention(crashDir)

        val remainingJson = crashDir.listFiles()
            .orEmpty()
            .filter { it.extension == "json" }
            .map { it.name }
            .sorted()
        assertEquals(listOf("old-3.json", "old-4.json"), remainingJson)
        assertTrue(keep.exists())
    }

    @Test
    fun enforceRetention_enforcesPerTablet() {
        val baseDir = Files.createTempDirectory("crash-retention-tablet").toFile()
        val crashDir = File(baseDir, ".metadata/crashes").apply { mkdirs() }

        // tablet-1 has 3 reports
        val t1File1 = File(crashDir, "2026-07-10T08-00-00-000_tablet-1_crash.json").apply {
            writeText("{}")
            setLastModified(1000L)
        }
        val t1File2 = File(crashDir, "2026-07-10T08-01-00-000_tablet-1_crash.json").apply {
            writeText("{}")
            setLastModified(2000L)
        }
        val t1File3 = File(crashDir, "2026-07-10T08-02-00-000_tablet-1_crash.json").apply {
            writeText("{}")
            setLastModified(3000L)
        }

        // tablet-2 has 1 report
        val t2File1 = File(crashDir, "2026-07-10T08-00-00-000_tablet-2_crash.json").apply {
            writeText("{}")
            setLastModified(1000L)
        }

        val store = CrashReportStore(
            pendingDir = Files.createTempDirectory("pending").toFile(),
            retentionLimit = 2
        )

        store.enforceRetention(crashDir)

        // For tablet-1, the oldest file (t1File1) should be deleted, and the two newer ones kept
        assertFalse(t1File1.exists())
        assertTrue(t1File2.exists())
        assertTrue(t1File3.exists())

        // For tablet-2, the file should still be kept, despite the crash loop on tablet-1
        assertTrue(t2File1.exists())
    }
}
