package com.kkc.sheettracker.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TrackerLamportClockTest {

    private val tempDirs = mutableListOf<File>()

    @Before
    fun setUp() {
        TrackerLamportClock.resetForTest()
    }

    @After
    fun tearDown() {
        TrackerLamportClock.resetForTest()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun nextReturnsStrictlyIncreasingValues() {
        val values = (1..5).map { TrackerLamportClock.next() }
        assertEquals(values.sorted(), values)
        assertEquals(values.toSet().size, values.size)
    }

    @Test
    fun persistsAcrossReinitWithSameBackingDir() {
        val stateDir = Files.createTempDirectory("lamport-test").toFile()
        tempDirs.add(stateDir)
        TrackerLamportClock.init(stateDir)
        val first = TrackerLamportClock.next()
        val second = TrackerLamportClock.next()
        assertTrue(second > first)

        // Simulate a process restart: re-init against the same dir, counter must not reset.
        TrackerLamportClock.init(stateDir)
        val third = TrackerLamportClock.next()
        assertTrue(third > second)
    }
}

class TrackerEventCodecTest {
    @Test
    fun appendThenReadRoundTripsMultipleLines() {
        val file = File(Files.createTempDirectory("event-log-test").toFile(), "tablet-a.ndjson")
        val payload1 = com.google.gson.JsonObject().apply {
            addProperty("file", "A.pdf")
            addProperty("page", 1)
        }
        val payload2 = com.google.gson.JsonObject().apply {
            addProperty("file", "A.pdf")
            addProperty("page", 2)
        }
        appendTrackerEvent(file, TrackerEvent(op = "set_complete_true", payload = payload1, wallTime = "2026-07-09T09:00:00Z", lamport = 1))
        appendTrackerEvent(file, TrackerEvent(op = "set_complete_true", payload = payload2, wallTime = "2026-07-09T09:00:01Z", lamport = 2))

        val events = readTrackerEvents(file)

        assertEquals(2, events.size)
        assertEquals(1, events[0].getAsJsonObject("payload").get("page").asInt)
        assertEquals(2, events[1].getAsJsonObject("payload").get("page").asInt)
        assertEquals(1L, events[0].get("lamport").asLong)
    }

    @Test
    fun readSkipsTornLastLineWithoutDroppingEarlierLines() {
        val file = File(Files.createTempDirectory("event-log-test").toFile(), "tablet-a.ndjson")
        val payload = com.google.gson.JsonObject().apply {
            addProperty("file", "A.pdf")
            addProperty("page", 1)
        }
        appendTrackerEvent(file, TrackerEvent(op = "set_complete_true", payload = payload, wallTime = "2026-07-09T09:00:00Z", lamport = 1))
        file.appendText("{\"op\": \"set_complete_tr")  // simulate a torn write

        val events = readTrackerEvents(file)

        assertEquals(1, events.size)
    }

    @Test
    fun readReturnsEmptyListForMissingFile() {
        val file = File(Files.createTempDirectory("event-log-test").toFile(), "does-not-exist.ndjson")
        assertTrue(readTrackerEvents(file).isEmpty())
    }
}

/** AUD-08: Android total-order parity with the watcher's _sort_combined_actions key. */
class TrackerTotalOrderTest {

    private fun cnc(ts: String, lamport: Long, eventId: String, action: String = "complete",
                   file: String = "A.pdf", page: Int = 1) =
        com.kkc.sheettracker.data.models.TrackerAction(
            file = file, page = page, action = action, timestamp = ts, lamport = lamport, eventId = eventId
        )

    @Test
    fun equalTimestampBreaksOnLamportThenEventId() {
        val a = cnc("2026-07-09T09:00:00Z", lamport = 5, eventId = "zzz")
        val b = cnc("2026-07-09T09:00:00Z", lamport = 5, eventId = "aaa")
        val c = cnc("2026-07-09T09:00:00Z", lamport = 3, eventId = "mmm")
        val sorted = listOf(a, b, c).shuffled().sortedWith(TRACKER_TOTAL_ORDER)
        // lamport 3 first, then lamport 5 ordered by eventId aaa < zzz
        assertEquals(listOf(c, b, a), sorted)
    }

    @Test
    fun decodePreservesLamportAndEventId() {
        val event = cncTrackerActionToEvent(cnc("2026-07-09T09:00:00Z", lamport = 0, eventId = ""))
        val line = encodeTrackerEventLine(event)
        val parsed = com.google.gson.JsonParser.parseString(line).asJsonObject
        val decoded = decodeCncTrackerEvent(parsed)!!
        assertEquals(event.lamport, decoded.lamport)
        assertEquals(event.eventId, decoded.eventId)
        assertTrue(decoded.eventId.isNotBlank())
    }

    @Test
    fun hardwoodEqualTimestampBreaksOnLamportThenEventId() {
        fun hw(ts: String, lamport: Long, eventId: String, action: String = "set_done_count") =
            com.kkc.sheettracker.data.models.HardwoodTrackerAction(
                docType = "cutlist", rowId = "r1", action = action, timestamp = ts,
                lamport = lamport, eventId = eventId
            )
        val a = hw("2026-07-09T09:00:00Z", 5, "zzz")
        val b = hw("2026-07-09T09:00:00Z", 5, "aaa")
        val c = hw("2026-07-09T09:00:00Z", 3, "mmm")
        val sorted = listOf(a, b, c).shuffled().sortedWith(HARDWOOD_TRACKER_TOTAL_ORDER)
        assertEquals(listOf(c, b, a), sorted)
    }
}

/** AUD-08: Lamport counter persists atomically and survives a torn backing file. */
class TrackerLamportAtomicPersistTest {
    @org.junit.Before
    fun setUp() { TrackerLamportClock.resetForTest() }

    @org.junit.After
    fun tearDown() { TrackerLamportClock.resetForTest() }

    @Test
    fun tornBackingFileDoesNotCrashAndCounterKeepsAdvancing() {
        val stateDir = Files.createTempDirectory("lamport-atomic-test").toFile()
        TrackerLamportClock.init(stateDir)
        val first = TrackerLamportClock.next()
        // Corrupt the backing file (simulate a torn/garbage write from before the atomic fix).
        File(stateDir, "tracker_lamport.txt").writeText("not-a-number")

        // Re-init and advance: the unparseable value is ignored, the counter still advances.
        TrackerLamportClock.init(stateDir)
        val next = TrackerLamportClock.next()
        assertTrue(next > 0)

        // The persisted file is now valid again and parseable.
        val persisted = File(stateDir, "tracker_lamport.txt").readText().trim().toLong()
        assertEquals(next, persisted)
        stateDir.deleteRecursively()
    }
}
