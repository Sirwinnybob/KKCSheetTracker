package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DeliverySchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeliveryScheduleRepositoryTest {

    @Test
    fun fetchSchedule_returnsEmptySchedule_whenFileDoesNotExist() {
        val baseDir = Files.createTempDirectory("delivery-repo-test-empty").toFile()
        val repo = DeliveryScheduleRepository(baseDir)
        val schedule = repo.fetchSchedule()
        assertNotNull(schedule)
        assertTrue(schedule.isEmpty)
        assertTrue(schedule.slots.isEmpty())
    }

    @Test
    fun fetchSchedule_parsesValidJsonSuccessfully() {
        val baseDir = Files.createTempDirectory("delivery-repo-test-valid").toFile()
        val metadataDir = File(baseDir, ".metadata").apply { mkdirs() }
        val jsonFile = File(metadataDir, "delivery_schedule.json")

        val json = """
            {
              "slots": {
                "monday_am": {
                  "jobs": [
                    {
                      "jobNumber": "1001",
                      "description": "Cabinet delivery",
                      "address": "123 Main St"
                    }
                  ]
                },
                "tuesday_pm": {
                  "jobs": [
                    {
                      "jobNumber": "1002",
                      "description": "Hardwood materials",
                      "address": "456 Oak Ave"
                    }
                  ]
                }
              }
            }
        """.trimIndent()
        jsonFile.writeText(json)

        val repo = DeliveryScheduleRepository(baseDir)
        val schedule = repo.fetchSchedule()

        assertNotNull(schedule)
        assertEquals(10, schedule.slots.size) // 5 days * 2 periods = 10 slots mapped

        val monAm = schedule.slot("monday", "am")
        assertEquals(1, monAm.jobs.size)
        assertEquals("1001", monAm.jobs[0].jobNumber)
        assertEquals("Cabinet delivery", monAm.jobs[0].description)
        assertEquals("123 Main St", monAm.jobs[0].address)

        val tuePm = schedule.slot("tuesday", "pm")
        assertEquals(1, tuePm.jobs.size)
        assertEquals("1002", tuePm.jobs[0].jobNumber)

        val wedAm = schedule.slot("wednesday", "am")
        assertTrue(wedAm.jobs.isEmpty())
    }

    @Test
    fun fetchSchedule_handlesJsonNullOrMalformedGracefully() {
        val baseDir = Files.createTempDirectory("delivery-repo-test-nulls").toFile()
        val metadataDir = File(baseDir, ".metadata").apply { mkdirs() }
        val jsonFile = File(metadataDir, "delivery_schedule.json")

        // Contains explicit nulls for optional fields, which Gson represents as JsonNull
        val json = """
            {
              "slots": {
                "monday_am": {
                  "jobs": [
                    {
                      "jobNumber": null,
                      "description": "Cabinet delivery",
                      "address": null
                    }
                  ]
                }
              }
            }
        """.trimIndent()
        jsonFile.writeText(json)

        val repo = DeliveryScheduleRepository(baseDir)
        val schedule = repo.fetchSchedule()

        assertNotNull(schedule)
        // If the current implementation has the JsonNull.asString bug, the catch block
        // in fetchSchedule() will swallow the exception and return an empty DeliverySchedule.
        // If it handles it correctly (or we fix it), it should parse the slot but map nulls to default empty strings.
        val monAm = schedule.slot("monday", "am")
        assertEquals(1, monAm.jobs.size)
        assertEquals("", monAm.jobs[0].jobNumber)
        assertEquals("Cabinet delivery", monAm.jobs[0].description)
        assertEquals("", monAm.jobs[0].address)
    }
}
