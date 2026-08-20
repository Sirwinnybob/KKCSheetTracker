package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkc.sheettracker.data.models.Material
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
    fun loadAllProgressMergesNdjsonPeerEvents() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))
        val trackerDir = File(baseDir, "$jobFolderName/CNC/.tracker")
        val eventsFile = File(trackerDir, "events/tablet-b.ndjson")
        val payload = JsonObject().apply {
            addProperty("file", "A.pdf")
            addProperty("page", 3)
            addProperty("fileFingerprint", "fp1")
            addProperty("timestamp", "2026-07-09T09:00:00Z")
        }
        appendTrackerEvent(eventsFile, TrackerEvent(op = "set_complete_true", payload = payload, wallTime = "2026-07-09T09:00:00Z", lamport = 1))

        val allProgress = store.loadAllProgress(jobFolderName)

        assertEquals(1, allProgress.size)
        assertEquals("tablet-b", allProgress.first().tabletId)
        assertEquals("complete", allProgress.first().actions.first().action)
        assertEquals(3, allProgress.first().actions.first().page)
    }

    @Test
    fun loadAllProgressMergesLegacyAndNdjsonForSameTabletId() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-b",
            progress = TabletProgress(tabletId = "tablet-b", actions = listOf(trackerAction("A.pdf", 1, "view", "2026-07-09T08:00:00Z")))
        )
        val eventsFile = File(baseDir, "$jobFolderName/CNC/.tracker/events/tablet-b.ndjson")
        val payload = JsonObject().apply {
            addProperty("file", "A.pdf")
            addProperty("page", 2)
            addProperty("timestamp", "2026-07-09T09:00:00Z")
        }
        appendTrackerEvent(eventsFile, TrackerEvent(op = "set_complete_true", payload = payload, wallTime = "2026-07-09T09:00:00Z", lamport = 1))

        val allProgress = store.loadAllProgress(jobFolderName)

        assertEquals(1, allProgress.size)
        assertEquals(2, allProgress.first().actions.size)
        assertEquals(setOf("view", "complete"), allProgress.first().actions.map { it.action }.toSet())
    }

    @Test
    fun loadAllProgressToleratesPreAud08LegacyJsonMissingLamportAndEventId() {
        // Real production tracker/consolidated.json files predate AUD-08 and were written before
        // TrackerAction gained lamport/eventId fields, so they never contain those keys at all.
        // Gson leaves eventId (a non-null String field) as a raw null for such files, and
        // sanitizeAction() must not let that null reach TrackerAction's non-null constructor
        // parameter -- doing so throws and the whole file gets silently dropped as "malformed".
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))
        val trackerDir = File(baseDir, "$jobFolderName/CNC/.tracker").apply { mkdirs() }
        File(trackerDir, "consolidated.json").writeText(
            """{"tabletId":"consolidated","actions":[""" +
                """{"file":"A.pdf","page":1,"action":"complete","timestamp":"2026-06-22T21:05:59Z","fileFingerprint":"fp1"}""" +
                """]}"""
        )

        val allProgress = store.loadAllProgress(jobFolderName)

        assertEquals(1, allProgress.size)
        assertEquals(1, allProgress.first().actions.size)
        assertEquals("complete", allProgress.first().actions.first().action)
    }

    @Test
    fun defaultStoreDoesNotMatchHistoricalFingerprintByByteLength() {
        val baseDir = createTempBaseDir()
        val historicalFingerprint = "1623305_1777664058047"
        val extractedFingerprint = "1623305_1777664059000"
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-archive",
            progress = TabletProgress(
                tabletId = "tablet-archive",
                actions = listOf(
                    TrackerAction(
                        file = "A.pdf",
                        page = 1,
                        action = "complete",
                        timestamp = "2026-08-20T10:00:00Z",
                        fileFingerprint = historicalFingerprint,
                    )
                )
            )
        )

        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))

        assertEquals(
            SheetStatus.NOT_STARTED,
            store.getSheetStatus(jobFolderName, "A.pdf", 1, extractedFingerprint)
        )
    }

    @Test
    fun writableStoreCannotEnableArchiveFingerprintCompatibility() {
        val baseDir = createTempBaseDir()
        val historicalFingerprint = "1623305_1777664058047"
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-archive",
            progress = TabletProgress(
                tabletId = "tablet-archive",
                actions = listOf(
                    TrackerAction(
                        file = "A.pdf",
                        page = 1,
                        action = "complete",
                        timestamp = "2026-08-20T10:00:00Z",
                        fileFingerprint = historicalFingerprint,
                    )
                )
            )
        )

        val store = ProgressStore(
            baseDir = baseDir,
            tabletId = tabletId,
            localStateDir = File(baseDir, ".local"),
            archiveFingerprintCompatibility = true,
        )

        assertEquals(
            SheetStatus.NOT_STARTED,
            store.getSheetStatus(jobFolderName, "A.pdf", 1, "1623305_1777664059000")
        )
    }

    @Test
    fun archiveFingerprintCompatibilityUsesMatchingSizeForCompletionAndBadParts() {
        val baseDir = createTempBaseDir()
        val historicalFingerprint = "1623305_1777664058047"
        val extractedFingerprint = "1623305_1777664059000"
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-archive",
            progress = TabletProgress(
                tabletId = "tablet-archive",
                actions = listOf(
                    TrackerAction(
                        file = "A.pdf",
                        page = 1,
                        action = "complete",
                        timestamp = "2026-08-20T10:00:00Z",
                        fileFingerprint = historicalFingerprint,
                    ),
                    TrackerAction(
                        file = "A.pdf",
                        page = 1,
                        part = 7,
                        action = "bad_part",
                        timestamp = "2026-08-20T10:01:00Z",
                        fileFingerprint = historicalFingerprint,
                    ),
                    TrackerAction(
                        file = "A.pdf",
                        page = 2,
                        action = "skip",
                        timestamp = "2026-08-20T10:02:00Z",
                        fileFingerprint = historicalFingerprint,
                        reNested = true,
                    )
                )
            )
        )

        val store = ProgressStore(
            baseDir = baseDir,
            tabletId = tabletId,
            localStateDir = File(baseDir, ".local"),
            readOnly = true,
            archiveFingerprintCompatibility = true,
        )

        assertEquals(
            SheetStatus.HAS_BAD_PARTS,
            store.getSheetStatus(jobFolderName, "A.pdf", 1, extractedFingerprint)
        )
        assertEquals(
            SheetStatus.RE_NESTED,
            store.getSheetStatus(jobFolderName, "A.pdf", 2, extractedFingerprint)
        )
        assertEquals(
            setOf(7),
            store.getBadParts(jobFolderName, "A.pdf", 1, extractedFingerprint, includeDraft = false)
        )
        assertEquals(
            SheetStatus.NOT_STARTED,
            store.getSheetStatus(jobFolderName, "A.pdf", 1, "1623306_1777664059000")
        )
    }

    @Test
    fun archiveFingerprintCompatibilityUsesFallbackForPendingBadParts() {
        val baseDir = createTempBaseDir()
        val historicalFingerprint = "1623305_1777664058047"
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-archive",
            progress = TabletProgress(
                tabletId = "tablet-archive",
                actions = listOf(
                    TrackerAction(
                        file = "A.pdf",
                        page = 1,
                        action = "complete",
                        timestamp = "2026-08-20T10:00:00Z",
                        fileFingerprint = historicalFingerprint,
                    ),
                    TrackerAction(
                        file = "A.pdf",
                        page = 1,
                        part = 7,
                        action = "bad_part",
                        timestamp = "2026-08-20T10:01:00Z",
                        fileFingerprint = historicalFingerprint,
                    )
                )
            )
        )

        val store = ProgressStore(
            baseDir = baseDir,
            tabletId = tabletId,
            localStateDir = File(baseDir, ".local"),
            readOnly = true,
            archiveFingerprintCompatibility = true,
        )

        assertEquals(
            1,
            store.getPendingBadPartsForMaterial(
                jobFolderName,
                "A.pdf",
                "1623305_1777664059000",
            )
        )
    }

    @Test
    fun archiveFingerprintCompatibilityUsesMostRecentlyObservedMatchingSizeFingerprint() {
        val baseDir = createTempBaseDir()
        val firstFingerprint = "1623305_1777664058047"
        val latestFingerprint = "1623305_1777664059000"
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-archive",
            progress = TabletProgress(
                tabletId = "tablet-archive",
                actions = listOf(
                    TrackerAction(
                        file = "A.pdf",
                        page = 1,
                        action = "complete",
                        timestamp = "2026-08-20T10:00:00Z",
                        fileFingerprint = firstFingerprint,
                    ),
                    TrackerAction(
                        file = "A.pdf",
                        page = 1,
                        action = "skip",
                        timestamp = "2026-08-20T10:01:00Z",
                        fileFingerprint = latestFingerprint,
                    )
                )
            )
        )

        val store = ProgressStore(
            baseDir = baseDir,
            tabletId = tabletId,
            localStateDir = File(baseDir, ".local"),
            readOnly = true,
            archiveFingerprintCompatibility = true,
        )

        assertEquals(
            SheetStatus.SKIPPED,
            store.getSheetStatus(jobFolderName, "A.pdf", 1, "1623305_1777664060000")
        )
    }

    @Test
    fun archiveFingerprintCompatibilityDoesNotMatchMalformedFingerprints() {
        val baseDir = createTempBaseDir()
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-archive",
            progress = TabletProgress(
                tabletId = "tablet-archive",
                actions = listOf(
                    TrackerAction(
                        file = "A.pdf",
                        page = 1,
                        action = "complete",
                        timestamp = "2026-08-20T10:00:00Z",
                        fileFingerprint = "not-a-size-fingerprint",
                    )
                )
            )
        )

        val store = ProgressStore(
            baseDir = baseDir,
            tabletId = tabletId,
            localStateDir = File(baseDir, ".local"),
            readOnly = true,
            archiveFingerprintCompatibility = true,
        )

        assertEquals(
            SheetStatus.NOT_STARTED,
            store.getSheetStatus(jobFolderName, "A.pdf", 1, "1623305_1777664060000")
        )
        assertEquals(
            SheetStatus.NOT_STARTED,
            store.getSheetStatus(jobFolderName, "A.pdf", 1, "1623305")
        )
    }

    @Test
    fun readOnlyStoreDoesNotCreateLocalStateDirectory() {
        val baseDir = createTempBaseDir()
        val localStateDir = File(baseDir, ".archive-state")

        ProgressStore(
            baseDir = baseDir,
            tabletId = tabletId,
            localStateDir = localStateDir,
            readOnly = true,
        )

        assertFalse(localStateDir.exists())
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

        // Removing a re-nested skip means its remake has been queued, so it becomes done.
        assertEquals(SheetStatus.COMPLETE, store.getSheetStatus(jobFolderName, pdfFilename, page, fileFingerprint))
        assertFalse(store.isSheetSkipped(jobFolderName, pdfFilename, page, fileFingerprint))
    }

    @Test
    fun pruneWithUnknownMaterialsDoesNotDiscardLocalDrafts() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))

        store.toggleBadPart(jobFolderName, "A.pdf", 1, "fp1", 7)
        store.pruneLocalStateForJob(jobFolderName, emptyList())

        assertEquals(setOf(7), store.getDraftBadParts(jobFolderName, "A.pdf", 1, "fp1"))
    }

    @Test
    fun pruneDoesNotDeleteTabletLocalOcrDirectories() {
        val baseDir = createTempBaseDir()
        val localStateDir = File(baseDir, ".local")
        val store = ProgressStore(baseDir, tabletId, localStateDir)
        val ocrFile = File(localStateDir, "ocr/$jobFolderName/stale.pdf/stale-fp/p1.json").apply {
            parentFile?.mkdirs()
            writeText("legacy OCR cache")
        }

        store.pruneLocalStateForJob(
            jobFolderName,
            listOf(Material("A.pdf", "A", pageCount = 1, fileFingerprint = "fp1"))
        )

        assertTrue(ocrFile.exists())
    }

    @Test
    fun indexStatusCountsUseCanonicalTotalAndKeepRenestedSeparate() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))

        // Build the in-memory index, then append two actions to it.
        store.getSheetStatus(jobFolderName, "A.pdf", 1, "fp1")
        store.markSheetComplete(jobFolderName, "A.pdf", 1, "fp1")
        store.markSheetRenested(jobFolderName, "A.pdf", 2, "fp1")

        val counts = store.getIndexJobStatusCountsOrNull(jobFolderName, canonicalTotal = 20)
            ?: error("expected loaded index counts")

        assertEquals(19, counts.total)
        assertEquals(1, counts.complete)
        assertEquals(0, counts.bad)
        assertEquals(0, counts.skipped)
        assertEquals(1, counts.reNested)
        assertEquals(18, counts.notStarted)
    }

    @Test
    fun indexStatusCountsWithoutCanonicalTotalDoNotPretendTouchedPagesAreTheJobTotal() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))

        store.getSheetStatus(jobFolderName, "A.pdf", 1, "fp1")
        store.markSheetComplete(jobFolderName, "A.pdf", 1, "fp1")

        assertEquals(null, store.getIndexJobStatusCountsOrNull(jobFolderName))
    }

    private fun trackerAction(
        file: String,
        page: Int,
        action: String,
        timestamp: String,
        fileFingerprint: String = "fp1",
    ): TrackerAction {
        return TrackerAction(file = file, page = page, part = null, action = action, timestamp = timestamp, fileFingerprint = fileFingerprint)
    }
}
