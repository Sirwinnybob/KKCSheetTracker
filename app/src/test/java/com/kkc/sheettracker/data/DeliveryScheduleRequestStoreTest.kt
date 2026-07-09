package com.kkc.sheettracker.data

import com.google.gson.JsonParser
import com.kkc.sheettracker.data.models.DeliveryJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeliveryScheduleRequestStoreTest {

    @Test
    fun queueSlotEdit_writesExpectedRequestJson() {
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
    fun queueSlotEdit_mergesMultipleEditsBySlot() {
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
    fun queueReset_clearsQueuedSlotEdits() {
        val baseDir = Files.createTempDirectory("delivery-request-store-reset").toFile()
        val store = DeliveryScheduleRequestStore(baseDir)

        store.queueSlotEdit("monday_am", listOf(DeliveryJob(jobNumber = "1", description = "One")), "tablet-1")
        store.queueReset("tablet-1")

        val root = JsonParser.parseString(File(baseDir, "delivery_schedule_request.tablet-1.json").readText()).asJsonObject
        assertTrue(root.get("resetAll").asBoolean)
        assertEquals(0, root.getAsJsonArray("slotEdits").size())
    }

    // ==================== M-04 regression: per-tablet filenames don't collide ====================

    @Test
    fun twoTabletsQueuingBeforeAPollBothProduceDistinctRequestFiles() {
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
}
