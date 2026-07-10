package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.TabletProgress
import com.kkc.sheettracker.data.models.TrackerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        val actions = readTrackerEventActions(baseDir, jobFolderName, tabletId)
        assertEquals(listOf("view"), actions)
    }

    @Test
    fun localMaterialTouchesAreLatestPerMaterial() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))

        store.markSheetViewed(jobFolderName, "A.pdf", 2, "fp1")
        store.markSheetViewed(jobFolderName, "B.pdf", 4, "fp1")
        store.markSheetViewed(jobFolderName, "A.pdf", 6, "fp1")

        val touches = store.getLocalMaterialLastTouches(jobFolderName)

        assertEquals(6, touches["A.pdf"]?.page)
        assertEquals(4, touches["B.pdf"]?.page)
    }

    @Test
    fun concurrentViewActionsPreserveEveryAppendedAction() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))
        val writerCount = 8
        val actionsPerWriter = 40
        val totalActions = writerCount * actionsPerWriter
        val startGate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(writerCount)

        try {
            val futures = (0 until writerCount).map { writer ->
                pool.submit(Callable {
                    startGate.await(5, TimeUnit.SECONDS)
                    repeat(actionsPerWriter) { offset ->
                        val page = writer * actionsPerWriter + offset + 1
                        store.markSheetViewed(jobFolderName, "A.pdf", page, "fp1")
                    }
                })
            }

            startGate.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val events = readTrackerEventObjects(baseDir, jobFolderName, tabletId)
        val pages = events.map { it.getAsJsonObject("payload").get("page").asInt }

        assertEquals(totalActions, events.size)
        assertEquals((1..totalActions).toSet(), pages.toSet())
        assertTrue(events.all { cncActionForOp(it.get("op").asString) == "view" })
    }

    @Test
    fun loadAllProgressExcludesSyncConflictFiles() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))

        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-1",
            progress = TabletProgress(tabletId = "tablet-1", actions = emptyList())
        )
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-2.sync-conflict-20260709",
            progress = TabletProgress(tabletId = "tablet-2.sync-conflict-20260709", actions = emptyList())
        )

        val allProgress = store.loadAllProgress(jobFolderName)
        assertEquals(1, allProgress.size)
        assertEquals("tablet-1", allProgress.first().tabletId)
    }

    @Test
    fun decodeToleratesWrongTypedFields() {
        val good = JsonParser.parseString(
            """{"op":"view","payload":{"file":"A.pdf","page":5,"timestamp":"2026-05-01T00:00:01Z"},"wallTime":"2026-05-01T00:00:01Z","lamport":1,"eventId":"e1"}"""
        ).asJsonObject
        val badTyped = JsonParser.parseString(
            """{"op":"view","payload":{"file":"A.pdf","page":"notanumber"},"wallTime":"2026-05-01T00:00:02Z","lamport":2,"eventId":"e2"}"""
        ).asJsonObject

        val decoded = listOf(good, badTyped).mapNotNull { decodeCncTrackerEvent(it) }
        assertEquals(1, decoded.size)
        assertEquals("A.pdf", decoded.first().file)
        assertEquals(5, decoded.first().page)
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

    private fun readTrackerEventObjects(baseDir: File, jobFolderName: String, tabletId: String): List<JsonObject> {
        val file = File(baseDir, "$jobFolderName/CNC/.tracker/events/$tabletId.ndjson")
        return readTrackerEvents(file)
    }

    private fun readTrackerEventActions(baseDir: File, jobFolderName: String, tabletId: String): List<String> {
        return readTrackerEventObjects(baseDir, jobFolderName, tabletId).map { cncActionForOp(it.get("op").asString) }
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
