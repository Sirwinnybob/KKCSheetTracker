package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.TabletProgress
import com.kkc.sheettracker.data.models.TrackerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProgressStoreTest {
    private val gson = Gson()
    private val jobFolderName = "1234 - Test Job"
    private val tabletId = "tablet-test"

    @Test
    fun viewActionUpdatesLocalMaterialTouch() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))

        store.markSheetViewed(jobFolderName, "A.pdf", 7, "fp1")
        store.markSheetViewed(jobFolderName, "A.pdf", 9, "fp1")

        val touch = store.getLocalMaterialLastTouches(jobFolderName)["A.pdf"]
        assertEquals(9, touch?.page)
    }

    @Test
    fun viewActionDoesNotWriteStatusActions() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))

        store.markSheetViewed(jobFolderName, "A.pdf", 3, "fp1")
        val progress = readTabletProgress(baseDir, jobFolderName, tabletId)
        assertEquals(listOf("view"), progress.actions.map { it.action })
    }

    @Test
    fun localMaterialTouchesAreLatestPerMaterial() {
        val baseDir = createTempBaseDir()
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = tabletId,
            progress = TabletProgress(
                tabletId = tabletId,
                actions = listOf(
                    trackerAction("A.pdf", 2, "view", "2026-05-01T00:00:01Z"),
                    trackerAction("B.pdf", 4, "view", "2026-05-01T00:00:02Z"),
                    trackerAction("A.pdf", 6, "view", "2026-05-01T00:00:03Z")
                )
            )
        )
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))
        val touches = store.getLocalMaterialLastTouches(jobFolderName)

        assertEquals(6, touches["A.pdf"]?.page)
        assertEquals(4, touches["B.pdf"]?.page)
    }

    private fun createTempBaseDir(): File = Files.createTempDirectory("progress-store-test").toFile()

    private fun writeTabletProgress(baseDir: File, jobFolderName: String, tabletId: String, progress: TabletProgress) {
        val trackerDir = File(baseDir, "$jobFolderName/CNC/.tracker").apply { mkdirs() }
        File(trackerDir, "$tabletId.json").writeText(gson.toJson(progress))
    }

    private fun readTabletProgress(baseDir: File, jobFolderName: String, tabletId: String): TabletProgress {
        val trackerFile = File(baseDir, "$jobFolderName/CNC/.tracker/$tabletId.json")
        return gson.fromJson(trackerFile.readText(), TabletProgress::class.java)
    }

    @Test
    fun renestedSheetStatusAndSkippedStatus() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))
        val pdfFilename = "A.pdf"
        val page = 1
        val fileFingerprint = "fp1"

        // Initially NOT_STARTED
        assertEquals(SheetStatus.NOT_STARTED, store.getSheetStatus(jobFolderName, pdfFilename, page, fileFingerprint))
        assertFalse(store.isSheetSkipped(jobFolderName, pdfFilename, page, fileFingerprint))

        // Mark as re-nested
        store.markSheetRenested(jobFolderName, pdfFilename, page, fileFingerprint)

        // Status should be RE_NESTED and isSheetSkipped should be true
        assertEquals(SheetStatus.RE_NESTED, store.getSheetStatus(jobFolderName, pdfFilename, page, fileFingerprint))
        assertTrue(store.isSheetSkipped(jobFolderName, pdfFilename, page, fileFingerprint))

        // Unmark re-nested
        store.unmarkSheetRenested(jobFolderName, pdfFilename, page, fileFingerprint)

        // Status should return to NOT_STARTED and isSheetSkipped to false
        assertEquals(SheetStatus.NOT_STARTED, store.getSheetStatus(jobFolderName, pdfFilename, page, fileFingerprint))
        assertFalse(store.isSheetSkipped(jobFolderName, pdfFilename, page, fileFingerprint))
    }

    private fun trackerAction(file: String, page: Int, action: String, timestamp: String): TrackerAction {
        return TrackerAction(file = file, page = page, part = null, action = action, timestamp = timestamp, fileFingerprint = "fp1")
    }
}
