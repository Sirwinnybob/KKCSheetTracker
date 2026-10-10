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

class DeliveryScheduleRequestStoreTest {

    @Test
    fun queueSlotEdit_writesExpectedRequestJson() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-request-store").toFile()
        val store = DeliveryScheduleRequestStore(baseDir)

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
    fun queueSlotEdit_mergesMultipleEditsBySlot() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-request-store-merge").toFile()
        val store = DeliveryScheduleRequestStore(baseDir)

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
        val store = DeliveryScheduleRequestStore(baseDir)

        store.queueSlotEdit("monday_am", listOf(DeliveryJob(jobNumber = "1", description = "One")), "tablet-1")
        store.queueReset("tablet-1", mirroredWeekOf = null)

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
        val store = DeliveryScheduleRequestStore(baseDir)

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

    @Test
    fun datedKeysAreLowerCasedAndPassedThrough() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-dated").toFile()
        val result = DeliveryScheduleRequestStore(baseDir).queueSlotEdit(
            "2026-10-24_PM",
            listOf(DeliveryJob("670", "ESCHRICH", folderName = "670 - ESCHRICH")),
            "tablet-1"
        )
        assertTrue(result.queued)
        val edit = requestJson(baseDir).getAsJsonArray("slotEdits")[0].asJsonObject
        assertEquals("2026-10-24_pm", edit.get("slot").asString)
    }

    @Test
    fun invalidSlotKeyIsNotWritten() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-badkey").toFile()
        val store = DeliveryScheduleRequestStore(baseDir)
        listOf("2026-02-30_am", "saturday_am", "2026-10-24_noon", "").forEach { key ->
            assertFalse(store.queueSlotEdit(key, emptyList(), "tablet-1").queued)
        }
        assertFalse(File(baseDir, "delivery_schedule_request.tablet-1.json").exists())
    }

    @Test
    fun atMostThreeJobsAndInvalidJobsDropped() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-cap").toFile()
        val jobs = (1..4).map { DeliveryJob("$it", "Job $it", folderName = "$it - Job") } +
            DeliveryJob("", "No number", folderName = "x") +
            DeliveryJob("9", "", folderName = "y")
        val result = DeliveryScheduleRequestStore(baseDir).queueSlotEdit("2026-10-21_am", jobs, "tablet-1")
        assertEquals(listOf("4", "", "9"), result.droppedJobs.map { it.jobNumber })
        val written = requestJson(baseDir).getAsJsonArray("slotEdits")[0].asJsonObject.getAsJsonArray("jobs")
        assertEquals(3, written.size())
    }

    @Test
    fun jobWithoutFolderNameIsNotQueuedAsPlaceableOnDatedKey() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-folder").toFile()
        val noFolder = DeliveryJob("670", "ESCHRICH")
        val result = DeliveryScheduleRequestStore(baseDir).queueSlotEdit("2026-10-21_am", listOf(noFolder), "tablet-1")
        assertEquals(listOf(noFolder), result.droppedJobs)
        val written = requestJson(baseDir).getAsJsonArray("slotEdits")[0].asJsonObject.getAsJsonArray("jobs")
        assertEquals(0, written.size())
        // Weekday keys keep v1 behavior: folderName optional.
        val weekday = DeliveryScheduleRequestStore(baseDir).queueSlotEdit("monday_am", listOf(noFolder), "tablet-1")
        assertTrue(weekday.droppedJobs.isEmpty())
    }

    @Test
    fun requestedAtIsIsoString() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-iso").toFile()
        DeliveryScheduleRequestStore(baseDir).queueSlotEdit("monday_am", emptyList(), "tablet-1")
        java.time.Instant.parse(requestJson(baseDir).get("requestedAt").asString)
        Unit
    }

    @Test
    fun unreadableExistingFileIsPreservedNotSilentlyOverwritten() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-corrupt").toFile()
        val file = File(baseDir, "delivery_schedule_request.tablet-1.json")
        file.writeText("{not json")
        DeliveryScheduleRequestStore(baseDir).queueSlotEdit("monday_am", emptyList(), "tablet-1")
        val backups = baseDir.listFiles()!!.filter { it.name.startsWith("delivery_schedule_request.tablet-1.json.unreadable-") }
        assertEquals(1, backups.size)
        assertEquals("{not json", backups.single().readText())
        assertEquals("monday_am", requestJson(baseDir).getAsJsonArray("slotEdits")[0].asJsonObject.get("slot").asString)
    }

    @Test
    fun concurrentEditsBothLand() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-concurrent").toFile()
        val store = DeliveryScheduleRequestStore(baseDir)
        kotlinx.coroutines.coroutineScope {
            (0 until 10).map { i ->
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    store.queueSlotEdit("2026-10-${10 + i}_am", emptyList(), "tablet-1")
                }
            }
        }
        assertEquals(10, requestJson(baseDir).getAsJsonArray("slotEdits").size())
    }

    @Test
    fun resetKeepsQueuedEditsOutsideTheMirroredWeek() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-reset").toFile()
        val store = DeliveryScheduleRequestStore(baseDir)
        store.queueSlotEdit("2026-10-14_am", emptyList(), "tablet-1")
        store.queueSlotEdit("2026-10-21_am", emptyList(), "tablet-1")
        store.queueSlotEdit("tuesday_pm", emptyList(), "tablet-1")
        store.queueReset("tablet-1", mirroredWeekOf = java.time.LocalDate.of(2026, 10, 12))
        val root = requestJson(baseDir)
        assertTrue(root.get("resetAll").asBoolean)
        assertEquals(listOf("2026-10-21_am"), root.getAsJsonArray("slotEdits").map { it.asJsonObject.get("slot").asString })
    }

    @Test
    fun readPendingReturnsQueuedRequestOrNull() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-pending").toFile()
        val store = DeliveryScheduleRequestStore(baseDir)
        assertNull(store.readPending("tablet-1"))
        store.queueSlotEdit("2026-10-21_am", emptyList(), "tablet-1")
        assertEquals("2026-10-21_am", store.readPending("tablet-1")!!.slotEdits.single().slot)
        File(baseDir, "delivery_schedule_request.tablet-1.json").writeText("{not json")
        assertNull(store.readPending("tablet-1"))
    }

    @Test
    fun rejectedFilesAreNeverTouched() = runBlocking {
        val baseDir = Files.createTempDirectory("delivery-rejected").toFile()
        val rejected = File(baseDir, "delivery_schedule_request.tablet-1.json.rejected").apply { writeText("old") }
        DeliveryScheduleRequestStore(baseDir).queueSlotEdit("monday_am", emptyList(), "tablet-1")
        assertEquals("old", rejected.readText())
    }
}
