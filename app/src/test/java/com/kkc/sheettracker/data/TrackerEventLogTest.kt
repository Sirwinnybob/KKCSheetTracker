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
