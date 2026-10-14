package com.kkc.sheettracker.data

import com.google.gson.JsonParser
import com.kkc.sheettracker.data.models.DeliveryJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class DeliveryScheduleRequestStoreTest {

    /** Fixed shop "today" (a Wednesday) so dated keys below never age out of the 14-day window. */
    private val today = LocalDate.of(2026, 10, 14)

    private fun store(baseDir: File, clockMillis: () -> Long = System::currentTimeMillis, now: LocalDate = today) =
        DeliveryScheduleRequestStore(baseDir, clockMillis = clockMillis, today = { now })

    @Test
    fun queueSlotEdit_writesExpectedRequestJson() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-request-store").toFile()
        val store = store(baseDir)

        store.queueSlotEdit(
            slot = "monday_am",
            jobs = listOf(
                DeliveryJob(
                    jobNumber = "123",
                    description = "Smith Kitchen",
                    address = "123 Main St",
                    folderName = "123 - Smith Kitchen"
                )
            ),
            tabletId = "tablet-1"
        )

        // METADATA_AUDIT.md M-04: the request file is now keyed on tabletId, not a fixed name.
        val root = JsonParser.parseString(File(baseDir, "delivery_schedule_request.tablet-1.json").readText()).asJsonObject
        assertEquals("tablet-1", root.get("tabletId").asString)
        assertFalse(root.get("resetAll").asBoolean)
        val edit = root.getAsJsonArray("slotEdits")[0].asJsonObject
        assertEquals("monday_am", edit.get("slot").asString)
        val job = edit.getAsJsonArray("jobs")[0].asJsonObject
        assertEquals("123", job.get("jobNumber").asString)
        assertEquals("Smith Kitchen", job.get("description").asString)
        assertEquals("123 Main St", job.get("address").asString)
        assertEquals("123 - Smith Kitchen", job.get("folderName").asString)
    }

    @Test
    fun sixJobDaySplitFourAmTwoPm_queuesAllSixAsOneEditPerSlot() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-request-store-six").toFile()
        val store = store(baseDir)
        fun job(n: Int) = DeliveryJob(jobNumber = "$n", description = "Job $n", folderName = "$n - Job")

        val am = store.queueSlotEdit("2026-10-21_am", (1..4).map(::job), "tablet-1")
        val pm = store.queueSlotEdit("2026-10-21_pm", (5..6).map(::job), "tablet-1")

        assertTrue(am.queued && pm.queued)
        assertTrue(am.droppedJobs.isEmpty() && pm.droppedJobs.isEmpty())
        val request = store.readPending("tablet-1")!!
        assertEquals(setOf("2026-10-21_am", "2026-10-21_pm"), request.slotEdits.map { it.slot }.toSet())
        assertEquals(6, request.slotEdits.sumOf { it.jobs.size })
        val schedule = overlayPendingDeliveryRequest(
            com.kkc.sheettracker.data.models.DeliverySchedule(isDated = true, weekOf = LocalDate.of(2026, 10, 19)),
            request,
            today
        )
        assertEquals(6, deliveryDayJobs(schedule, "2026-10-21_am").size)
    }

    @Test
    fun oneSlotEditKeepsSixJobsAndDropsTheSeventh() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-request-store-cap").toFile()
        val store = store(baseDir)
        val seven = (1..7).map { DeliveryJob(jobNumber = "$it", description = "Job $it", folderName = "$it - Job") }

        val result = store.queueSlotEdit("2026-10-21_am", seven, "tablet-1")

        assertTrue(result.queued)
        assertEquals(listOf(seven.last()), result.droppedJobs)
        assertEquals(seven.take(6), store.readPending("tablet-1")!!.slotEdits.single().jobs)
    }

    @Test
    fun weekdaySlotEditKeepsThreeJobsForUnupgradedHoursTracker() = runBlocking {
        // A v1 Hours Tracker rejects more than 3 jobs in one half-day and quarantines the whole
        // request file, so weekday keys are capped at the legacy per-slot limit.
        val baseDir = Files.createTempDirectory("delivery-request-store-legacy-cap").toFile()
        val store = store(baseDir)
        val four = (1..4).map { DeliveryJob(jobNumber = "$it", description = "Job $it") }

        val result = store.queueSlotEdit("monday_am", four, "tablet-1")

        assertTrue(result.queued)
        assertEquals(MAX_DELIVERY_JOBS_PER_LEGACY_SLOT, 3)
        assertEquals(listOf(four.last()), result.droppedJobs)
        assertEquals(four.take(3), store.readPending("tablet-1")!!.slotEdits.single().jobs)
    }

    @Test
    fun datedSlotEditIsNotLimitedByTheLegacyHalfDayCap() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-request-store-dated-no-legacy-cap").toFile()
        val store = store(baseDir)
        val five = (1..5).map { DeliveryJob(jobNumber = "$it", description = "Job $it", folderName = "$it - Job") }

        val result = store.queueSlotEdit("2026-10-21_pm", five, "tablet-1")

        assertTrue(result.droppedJobs.isEmpty())
        assertEquals(five, store.readPending("tablet-1")!!.slotEdits.single().jobs)
    }

    @Test
    fun queueSlotEdit_mergesMultipleEditsBySlot() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-request-store-merge").toFile()
        val store = store(baseDir)

        store.queueSlotEdit("monday_am", listOf(DeliveryJob(jobNumber = "1", description = "One")), "tablet-1")
        store.queueSlotEdit("tuesday_pm", listOf(DeliveryJob(jobNumber = "2", description = "Two")), "tablet-1")
        store.queueSlotEdit("monday_am", listOf(DeliveryJob(jobNumber = "3", description = "Three")), "tablet-1")

        val edits = JsonParser.parseString(File(baseDir, "delivery_schedule_request.tablet-1.json").readText())
            .asJsonObject
            .getAsJsonArray("slotEdits")
        assertEquals(2, edits.size())
        val monday = edits.first { it.asJsonObject.get("slot").asString == "monday_am" }.asJsonObject
        assertEquals("3", monday.getAsJsonArray("jobs")[0].asJsonObject.get("jobNumber").asString)
    }

    @Test
    fun queueReset_clearsQueuedSlotEdits() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-request-store-reset").toFile()
        val store = store(baseDir)

        store.queueSlotEdit("monday_am", listOf(DeliveryJob(jobNumber = "1", description = "One")), "tablet-1")
        assertTrue(store.queueReset("tablet-1", mirroredWeekOf = null))

        val root = JsonParser.parseString(File(baseDir, "delivery_schedule_request.tablet-1.json").readText()).asJsonObject
        assertTrue(root.get("resetAll").asBoolean)
        assertEquals(0, root.getAsJsonArray("slotEdits").size())
    }

    // ==================== M-04 regression: per-tablet filenames don't collide ====================

    @Test
    fun twoTabletsQueuingBeforeAPollBothProduceDistinctRequestFiles() = runBlocking {
        // Regression for METADATA_AUDIT.md M-04: two tablets queuing delivery-schedule edits
        // before the backend's next poll cycle used to collide on one shared
        // `delivery_schedule_request.json` (lost update or an unread `.sync-conflict-*` copy).
        // Each tablet must now write its own file so both edits survive until the backend
        // consumes them.
        val baseDir = Files.createTempDirectory("delivery-request-store-two-tablets").toFile()
        val store = store(baseDir)

        store.queueSlotEdit("monday_am", listOf(DeliveryJob(jobNumber = "1", description = "One")), "tablet-a")
        store.queueSlotEdit("tuesday_pm", listOf(DeliveryJob(jobNumber = "2", description = "Two")), "tablet-b")

        val tabletAFile = File(baseDir, "delivery_schedule_request.tablet-a.json")
        val tabletBFile = File(baseDir, "delivery_schedule_request.tablet-b.json")
        assertTrue("tablet-a's request file is missing", tabletAFile.exists())
        assertTrue("tablet-b's request file is missing", tabletBFile.exists())

        val tabletARoot = JsonParser.parseString(tabletAFile.readText()).asJsonObject
        val tabletBRoot = JsonParser.parseString(tabletBFile.readText()).asJsonObject
        assertEquals("tablet-a", tabletARoot.get("tabletId").asString)
        assertEquals("monday_am", tabletARoot.getAsJsonArray("slotEdits")[0].asJsonObject.get("slot").asString)
        assertEquals("tablet-b", tabletBRoot.get("tabletId").asString)
        assertEquals("tuesday_pm", tabletBRoot.getAsJsonArray("slotEdits")[0].asJsonObject.get("slot").asString)
    }

    private fun requestJson(baseDir: File, tabletId: String = "tablet-1") =
        JsonParser.parseString(File(baseDir, "delivery_schedule_request.$tabletId.json").readText()).asJsonObject

    private fun requestFile(baseDir: File, tabletId: String = "tablet-1") =
        File(baseDir, "delivery_schedule_request.$tabletId.json")

    @Test
    fun datedKeysAreLowerCasedAndPassedThrough() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-dated").toFile()
        val result = store(baseDir).queueSlotEdit(
            "2026-10-24_PM",
            listOf(DeliveryJob("670", "ESCHRICH", folderName = "670 - ESCHRICH")),
            "tablet-1"
        )
        assertTrue(result.queued)
        assertNull(result.reason)
        val edit = requestJson(baseDir).getAsJsonArray("slotEdits")[0].asJsonObject
        assertEquals("2026-10-24_pm", edit.get("slot").asString)
    }

    @Test
    fun invalidSlotKeyIsNotWritten() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-badkey").toFile()
        val store = store(baseDir)
        listOf("2026-02-30_am", "saturday_am", "2026-10-24_noon", "").forEach { key ->
            val result = store.queueSlotEdit(key, emptyList(), "tablet-1")
            assertFalse(result.queued)
            assertEquals(DeliveryQueueFailure.InvalidSlot, result.reason)
        }
        assertFalse(requestFile(baseDir).exists())
    }

    @Test
    fun atMostSixJobsAndInvalidJobsDropped() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-cap").toFile()
        val jobs = (1..7).map { DeliveryJob("$it", "Job $it", folderName = "$it - Job") } +
            DeliveryJob("", "No number", folderName = "x") +
            DeliveryJob("9", "", folderName = "y")
        val result = store(baseDir).queueSlotEdit("2026-10-21_am", jobs, "tablet-1")
        assertEquals(listOf("7", "", "9"), result.droppedJobs.map { it.jobNumber })
        val written = requestJson(baseDir).getAsJsonArray("slotEdits")[0].asJsonObject.getAsJsonArray("jobs")
        assertEquals(6, written.size())
    }

    @Test
    fun duplicateJobsCollapseByFolderIgnoringCaseAndDoNotHideRealDrops() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-dupes").toFile()
        fun job(n: Int) = DeliveryJob("$n", "Job $n", folderName = "$n - Job")
        // 1..5, a repeat of 5 (different case folder), then 6 and 7: the repeat is one job, so the
        // sixth distinct job still fits and only the seventh is over the cap.
        val submitted = (1..5).map(::job) + job(5).copy(folderName = "5 - JOB") + job(6) + job(7)
        val result = store(baseDir).queueSlotEdit("2026-10-21_am", submitted, "tablet-1")
        assertTrue(result.queued)
        assertEquals(listOf(job(7)), result.droppedJobs)
        assertEquals((1..6).map(::job), requestJson(baseDir).getAsJsonArray("slotEdits")[0].asJsonObject
            .getAsJsonArray("jobs").map { DeliveryJob(it.asJsonObject["jobNumber"].asString, it.asJsonObject["description"].asString, folderName = it.asJsonObject["folderName"].asString) })
    }

    @Test
    fun weekdayJobsWithoutFolderCollapseByJobNumber() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-dupes-weekday").toFile()
        val one = DeliveryJob("1", "One")
        val result = store(baseDir).queueSlotEdit("monday_am", listOf(one, one.copy(description = "One again"), DeliveryJob("2", "Two")), "tablet-1")
        assertTrue(result.droppedJobs.isEmpty())
        assertEquals(listOf("1", "2"), store(baseDir).readPending("tablet-1")!!.slotEdits.single().jobs.map { it.jobNumber })
    }

    @Test
    fun datedSlotEditDropsJobsWithoutAFolderNameWhileWeekdayKeysKeepThem() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-folder").toFile()
        val store = store(baseDir)
        val good = DeliveryJob("671", "SMITH", folderName = "671 - SMITH")
        val noFolder = DeliveryJob("670", "ESCHRICH")
        val dated = store.queueSlotEdit("2026-10-21_am", listOf(good, noFolder), "tablet-1")
        assertTrue(dated.queued)
        assertEquals(listOf(noFolder), dated.droppedJobs)
        assertEquals(listOf(good), store.readPending("tablet-1")!!.slotEdits.single().jobs)
        // Weekday keys keep v1 behavior: folderName optional.
        val weekday = store.queueSlotEdit("monday_am", listOf(noFolder), "tablet-1")
        assertTrue(weekday.queued)
        assertTrue(weekday.droppedJobs.isEmpty())
    }

    @Test
    fun datedSlotEditWhereEveryJobIsDroppedWritesNothingAndKeepsTheQueuedSlot() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-all-invalid").toFile()
        val store = store(baseDir)
        val good = DeliveryJob("671", "SMITH", folderName = "671 - SMITH")
        val noFolder = DeliveryJob("670", "ESCHRICH")

        // Nothing queued yet: no file appears.
        val first = store.queueSlotEdit("2026-10-21_am", listOf(noFolder), "tablet-1")
        assertFalse(first.queued)
        assertEquals(DeliveryQueueFailure.AllJobsInvalid, first.reason)
        assertEquals(listOf(noFolder), first.droppedJobs)
        assertFalse(requestFile(baseDir).exists())

        // A queued edit for that slot is not overwritten by an all-invalid resubmission (an empty
        // list would clear the slot on HT).
        store.queueSlotEdit("2026-10-21_am", listOf(good), "tablet-1")
        val before = requestFile(baseDir).readText()
        val second = store.queueSlotEdit("2026-10-21_am", listOf(noFolder, DeliveryJob("", "x", folderName = "y")), "tablet-1")
        assertFalse(second.queued)
        assertEquals(before, requestFile(baseDir).readText())

        // An explicitly empty list is still a legitimate "clear this slot".
        assertTrue(store.queueSlotEdit("2026-10-21_am", emptyList(), "tablet-1").queued)
        assertTrue(store.readPending("tablet-1")!!.slotEdits.single().jobs.isEmpty())
    }

    @Test
    fun datedKeyOlderThanTheHistoryWindowIsRejected() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-too-old").toFile()
        val store = store(baseDir)
        val jobs = listOf(DeliveryJob("670", "ESCHRICH", folderName = "670 - ESCHRICH"))
        // today = Oct 14, window = 14 days: Sep 30 is the oldest accepted date.
        val old = store.queueSlotEdit("2026-09-29_am", jobs, "tablet-1")
        assertFalse(old.queued)
        assertEquals(DeliveryQueueFailure.TooOld, old.reason)
        assertEquals(jobs, old.droppedJobs)
        assertFalse(requestFile(baseDir).exists())

        assertTrue(store.queueSlotEdit("2026-09-30_pm", jobs, "tablet-1").queued)
        // Weekday keys have no date to age out.
        assertTrue(store.queueSlotEdit("monday_am", jobs, "tablet-1").queued)
    }

    @Test
    fun requestedAtIsIsoString() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-iso").toFile()
        store(baseDir).queueSlotEdit("monday_am", emptyList(), "tablet-1")
        java.time.Instant.parse(requestJson(baseDir).get("requestedAt").asString)
        Unit
    }

    @Test
    fun unreadableExistingFileIsPreservedNotSilentlyOverwritten() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-corrupt").toFile()
        val file = requestFile(baseDir)
        file.writeText("{not json")
        store(baseDir).queueSlotEdit("monday_am", emptyList(), "tablet-1")
        val backups = baseDir.listFiles()!!.filter { it.name.startsWith("delivery_schedule_request.tablet-1.json.unreadable-") }
        assertEquals(1, backups.size)
        assertEquals("{not json", backups.single().readText())
        assertEquals("monday_am", requestJson(baseDir).getAsJsonArray("slotEdits")[0].asJsonObject.get("slot").asString)
    }

    @Test
    fun onlyTheFiveNewestUnreadableBackupsAreKept() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-backup-prune").toFile()
        val prefix = "delivery_schedule_request.tablet-1.json.unreadable-"
        listOf(100L, 101L, 102L, 103L, 104L, 105L).forEach { File(baseDir, "$prefix$it").writeText("old $it") }
        // A different tablet's backup and the rejected file must never be pruned.
        val otherTablet = File(baseDir, "delivery_schedule_request.tablet-2.json.unreadable-1").apply { writeText("x") }
        val rejected = File(baseDir, "delivery_schedule_request.tablet-1.json.rejected").apply { writeText("y") }
        requestFile(baseDir).writeText("{not json")

        store(baseDir, clockMillis = { 999L }).queueSlotEdit("monday_am", emptyList(), "tablet-1")

        val kept = baseDir.list()!!.filter { it.startsWith(prefix) }.toSet()
        assertEquals(setOf("${prefix}999", "${prefix}105", "${prefix}104", "${prefix}103", "${prefix}102"), kept)
        assertTrue(otherTablet.exists())
        assertTrue(rejected.exists())
    }

    /** Corrupt request file whose predictable backup path is blocked by a directory. */
    private fun corruptWithBlockedBackup(baseDir: File): File {
        val file = requestFile(baseDir)
        file.writeText("{not json")
        assertTrue(File(baseDir, "delivery_schedule_request.tablet-1.json.unreadable-1234").mkdir())
        return file
    }

    @Test
    fun failedBackupAbortsQueueSlotEditAndLeavesOriginalUntouched() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-backup-fail").toFile()
        val file = corruptWithBlockedBackup(baseDir)
        val jobs = listOf(DeliveryJob("1", "One"))
        val result = store(baseDir, clockMillis = { 1234L }).queueSlotEdit("monday_am", jobs, "tablet-1")
        assertFalse(result.queued)
        assertEquals(DeliveryQueueFailure.Blocked, result.reason)
        assertEquals(jobs, result.droppedJobs)
        assertEquals("{not json", file.readText())
    }

    @Test
    fun failedBackupAbortsQueueResetAndLeavesOriginalUntouched() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-backup-fail-reset").toFile()
        val file = corruptWithBlockedBackup(baseDir)
        assertFalse(store(baseDir, clockMillis = { 1234L }).queueReset("tablet-1", mirroredWeekOf = null))
        assertEquals("{not json", file.readText())
    }

    @Test
    fun readFailureAbortsWithoutBackupOrWrite() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-read-fail").toFile()
        // A directory at the request path exists but cannot be read as text.
        val blocked = requestFile(baseDir)
        assertTrue(blocked.mkdir())
        val store = store(baseDir)
        assertFalse(store.queueSlotEdit("monday_am", emptyList(), "tablet-1").queued)
        assertFalse(store.queueReset("tablet-1", mirroredWeekOf = null))
        assertTrue(blocked.isDirectory)
        assertEquals(listOf("delivery_schedule_request.tablet-1.json"), baseDir.list()!!.toList())
    }

    @Test
    fun concurrentEditsBothLand() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-concurrent").toFile()
        val store = store(baseDir)
        kotlinx.coroutines.coroutineScope {
            (0 until 10).map { i ->
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    store.queueSlotEdit("2026-10-${10 + i}_am", emptyList(), "tablet-1")
                }
            }
        }
        assertEquals(10, requestJson(baseDir).getAsJsonArray("slotEdits").size())
    }

    private fun slotsOf(baseDir: File) =
        requestJson(baseDir).getAsJsonArray("slotEdits").map { it.asJsonObject.get("slot").asString }

    private suspend fun queueEmpty(store: DeliveryScheduleRequestStore, vararg keys: String) =
        keys.forEach { store.queueSlotEdit(it, emptyList(), "tablet-1") }

    @Test
    fun resetKeepsQueuedEditsOutsideTheMirroredWeek() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-reset").toFile()
        val store = store(baseDir)
        queueEmpty(store, "2026-10-14_am", "2026-10-21_am", "tuesday_pm")
        assertTrue(store.queueReset("tablet-1", mirroredWeekOf = LocalDate.of(2026, 10, 12)))
        val root = requestJson(baseDir)
        assertTrue(root.get("resetAll").asBoolean)
        assertEquals(listOf("2026-10-21_am"), slotsOf(baseDir))
    }

    @Test
    fun resetWindowBoundariesAreMondayThroughFridayOfTheMirroredWeek() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-reset-bounds").toFile()
        val store = store(baseDir)
        // weekOf = Mon Oct 12: Fri Oct 16 is cleared, Sat Oct 17 and the previous Sun Oct 11 survive.
        queueEmpty(store, "2026-10-12_am", "2026-10-16_pm", "2026-10-17_am", "2026-10-11_pm")
        assertTrue(store.queueReset("tablet-1", mirroredWeekOf = LocalDate.of(2026, 10, 12)))
        assertEquals(setOf("2026-10-17_am", "2026-10-11_pm"), slotsOf(baseDir).toSet())
    }

    @Test
    fun resetWithoutWeekOfUsesHoursTrackersRuleFromToday() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-reset-null").toFile()
        // Wednesday Oct 14: window is Oct 12-16.
        val wedStore = store(baseDir)
        queueEmpty(wedStore, "2026-10-14_am", "2026-10-16_am", "2026-10-17_am", "2026-10-21_am", "monday_am")
        assertTrue(wedStore.queueReset("tablet-1", mirroredWeekOf = null))
        assertEquals(setOf("2026-10-17_am", "2026-10-21_am"), slotsOf(baseDir).toSet())

        // Saturday Oct 17: HT's weekOf rolls to Mon Oct 19, so the window is Oct 19-23.
        val satDir = Files.createTempDirectory("delivery-reset-null-sat").toFile()
        val satStore = store(satDir, now = LocalDate.of(2026, 10, 17))
        queueEmpty(satStore, "2026-10-16_am", "2026-10-17_am", "2026-10-21_am", "2026-10-24_am")
        assertTrue(satStore.queueReset("tablet-1", mirroredWeekOf = null))
        assertEquals(setOf("2026-10-16_am", "2026-10-17_am", "2026-10-24_am"), slotsOf(satDir).toSet())
    }

    @Test
    fun readPendingReturnsQueuedRequestOrNull() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-pending").toFile()
        val store = store(baseDir)
        assertNull(store.readPending("tablet-1"))
        store.queueSlotEdit("2026-10-21_am", emptyList(), "tablet-1")
        assertEquals("2026-10-21_am", store.readPending("tablet-1")!!.slotEdits.single().slot)
        requestFile(baseDir).writeText("{not json")
        assertNull(store.readPending("tablet-1"))
        // Valid JSON without slotEdits is not a request either.
        requestFile(baseDir).writeText("""{"tabletId":"tablet-1","requestedAt":"x"}""")
        assertNull(store.readPending("tablet-1"))
    }

    @Test
    fun rejectedFilesAreNeverTouched() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-rejected").toFile()
        val rejected = File(baseDir, "delivery_schedule_request.tablet-1.json.rejected").apply { writeText("old") }
        store(baseDir).queueSlotEdit("monday_am", emptyList(), "tablet-1")
        assertEquals("old", rejected.readText())
    }
}
