package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TrackerLamportClockTest {

    @Test
    fun nextReturnsStrictlyIncreasingValues() {
        val values = (1..5).map { TrackerLamportClock.next() }
        assertEquals(values.sorted(), values)
        assertEquals(values.toSet().size, values.size)
    }

    @Test
    fun persistsAcrossReinitWithSameBackingDir() {
        val stateDir = Files.createTempDirectory("lamport-test").toFile()
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
