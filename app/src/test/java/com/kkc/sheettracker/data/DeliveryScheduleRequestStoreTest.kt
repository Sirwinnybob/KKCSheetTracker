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

        val root = JsonParser.parseString(File(baseDir, "delivery_schedule_request.json").readText()).asJsonObject
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

        val edits = JsonParser.parseString(File(baseDir, "delivery_schedule_request.json").readText())
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

        val root = JsonParser.parseString(File(baseDir, "delivery_schedule_request.json").readText()).asJsonObject
        assertTrue(root.get("resetAll").asBoolean)
        assertEquals(0, root.getAsJsonArray("slotEdits").size())
    }
}
