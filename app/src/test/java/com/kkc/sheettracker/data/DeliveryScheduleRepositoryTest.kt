package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DatedSlotKey
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliveryScheduleFailure
import com.kkc.sheettracker.data.models.datedSlotKey
import com.kkc.sheettracker.data.models.isWeekdaySlotKey
import com.kkc.sheettracker.data.models.parseDatedSlotKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
                      "address": "123 Main St",
                      "folderName": "1001 - Cabinet delivery"
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
        assertEquals("1001 - Cabinet delivery", monAm.jobs[0].folderName)

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

    @Test
    fun parsesV2DocumentDatedSlotsWeekOfAndMirror() {
        val doc = parseDeliveryScheduleDocument(
            """
            {"schemaVersion":2,
             "datedSlots":{
               "2026-10-14_am":{"jobs":[{"jobNumber":"670","description":"ESCHRICH","address":"1364 Victorian","folderName":"670 - ESCHRICH 1364 VICTORIAN"}]},
               "2026-10-18_am":{"jobs":[{"jobNumber":"702","description":"SMITH","folderName":"702 - SMITH"}]}
             },
             "weekOf":"2026-10-12",
             "slots":{"tuesday_am":{"jobs":[{"jobNumber":"670","description":"ESCHRICH","address":"1364 Victorian","folderName":"670 - ESCHRICH 1364 VICTORIAN"}]}}}
            """.trimIndent()
        )
        val s = doc.schedule
        assertTrue(s.isDated)
        assertEquals(java.time.LocalDate.of(2026, 10, 12), s.weekOf)
        assertEquals("670", s.slotByKey("2026-10-14_am").jobs.single().jobNumber)
        // Sunday delivery only exists in datedSlots.
        assertEquals("702", s.slotByKey("2026-10-18_am").jobs.single().jobNumber)
        assertEquals("", s.slotByKey("2026-10-18_am").jobs.single().address)
        assertEquals("670", s.slot("tuesday", "am").jobs.single().jobNumber)
        assertTrue(doc.failures.isEmpty())
    }

    @Test
    fun v2JobWithOnlyAnAmKeyShowsInThatDaysAmAndPmStaysEmpty() {
        // HT lists a job with a date but no half-day under AM; the tablet must not require a PM.
        val s = parseDeliverySchedule(
            """{"schemaVersion":2,"weekOf":"2026-10-12",
                "datedSlots":{"2026-10-14_am":{"jobs":[{"jobNumber":"670","description":"ESCHRICH","folderName":"670 - ESCHRICH"}]}},
                "slots":{}}"""
        )
        val day = buildDeliveryWeek(
            s, java.time.LocalDate.of(2026, 10, 12), today = java.time.LocalDate.of(2026, 10, 12), includeEmptyWeekend = false
        ).days[2]
        assertEquals(listOf("670"), day.am.jobs.map { it.jobNumber })
        assertTrue(day.pm.jobs.isEmpty())
        assertEquals(1, deliveryDayCount(s, "2026-10-14_pm"))
    }

    @Test
    fun v1DocumentStillParsesIntoWeekdayGrid() {
        val s = parseDeliverySchedule("""{"schemaVersion":1,"slots":{"monday_pm":{"jobs":[{"jobNumber":"1","description":"One"}]}}}""")
        assertFalse(s.isDated)
        assertNull(s.weekOf)
        assertTrue(s.datedSlots.isEmpty())
        assertEquals(10, s.slots.size)
        assertEquals("1", s.slot("monday", "pm").jobs.single().jobNumber)
    }

    @Test
    fun badDatedKeyOrBadJobIsSkippedWithoutLosingOthers() {
        val s = parseDeliverySchedule(
            """
            {"schemaVersion":2,"weekOf":"2026-10-12","slots":{},
             "datedSlots":{
               "2026-02-30_am":{"jobs":[{"jobNumber":"9","description":"Bad date"}]},
               "Tuesday_AM":{"jobs":[{"jobNumber":"8","description":"Bad key"}]},
               "2026-10-15_noon":{"jobs":[]},
               "2026-10-15_pm":{"jobs":["not-an-object",{"jobNumber":"7","description":"Good"}]},
               "2026-10-16_am":"not-an-object"
             }}
            """.trimIndent()
        )
        assertEquals(setOf("2026-10-15_pm"), s.datedSlots.keys)
        assertEquals("7", s.slotByKey("2026-10-15_pm").jobs.single().jobNumber)
    }

    @Test
    fun adminSyncResponseFailuresAreParsed() {
        val doc = parseDeliveryScheduleDocument(
            """
            {"schemaVersion":2,"weekOf":"2026-10-12","slots":{},"datedSlots":{},
             "failures":[{"slot":"2026-10-21_pm","jobNumber":"670","folderName":"670 - ESCHRICH","status":409,"reason":"half-day is full"}]}
            """.trimIndent()
        )
        assertEquals(
            listOf(DeliveryScheduleFailure("2026-10-21_pm", "670", "670 - ESCHRICH", 409, "half-day is full")),
            doc.failures
        )
    }

    @Test
    fun getAndPutShapeUsesScheduleKeyAsMirror() {
        // GET/PUT /api/delivery-schedule returns {schedule: <slots mirror>, datedSlots, weekOf, failures}.
        val s = parseDeliverySchedule(
            """{"schedule":{"thursday_pm":{"jobs":[{"jobNumber":"670","description":"E","folderName":"670 - E"}]}},
                "datedSlots":{"2026-10-15_pm":{"jobs":[{"jobNumber":"670","description":"E","folderName":"670 - E"}]}},
                "weekOf":"2026-10-12","failures":[]}"""
        )
        assertTrue(s.isDated)
        assertEquals("670", s.slot("thursday", "pm").jobs.single().jobNumber)
        assertEquals("670", s.slotByKey("2026-10-15_pm").jobs.single().jobNumber)
    }

    @Test
    fun weekOfThatIsNotAMondayIsIgnored() {
        val s = parseDeliverySchedule("""{"schemaVersion":2,"weekOf":"2026-10-14","datedSlots":{},"slots":{}}""")
        assertTrue(s.isDated)
        assertNull(s.weekOf)
    }

    @Test
    fun slotKeyHelpers() {
        assertEquals(DatedSlotKey(java.time.LocalDate.of(2026, 10, 21), "pm"), parseDatedSlotKey("2026-10-21_pm"))
        assertNull(parseDatedSlotKey("2026-10-21_PM"))
        assertNull(parseDatedSlotKey("2026-13-01_am"))
        assertEquals("2026-10-21_am", datedSlotKey(java.time.LocalDate.of(2026, 10, 21), "am"))
        assertTrue(isWeekdaySlotKey("friday_pm"))
        assertFalse(isWeekdaySlotKey("saturday_am"))
    }

    @Test
    fun withSlotRoutesDatedKeysToDatedSlots() {
        val job = com.kkc.sheettracker.data.models.DeliveryJob("1", "One", folderName = "1 - One")
        val s = DeliverySchedule(isDated = true)
            .withSlot("2026-10-21_pm", listOf(job))
            .withSlot("monday_am", listOf(job))
        assertEquals(listOf(job), s.datedSlots["2026-10-21_pm"]?.jobs)
        assertEquals(listOf(job), s.slots["monday_am"]?.jobs)
    }
}
