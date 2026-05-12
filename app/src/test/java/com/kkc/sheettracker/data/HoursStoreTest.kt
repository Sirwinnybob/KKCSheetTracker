package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HoursEntry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class HoursStoreTest {

    private fun tempBaseDir(): File = Files.createTempDirectory("hours_test").toFile()

    @Test
    fun clockIn_createsEntryWithNullClockOut() {
        val baseDir = tempBaseDir()
        val store = HoursStore(baseDir, "tablet-1")
        val date = LocalDate.of(2026, 5, 12)

        val entry = store.clockIn("Alice", date)

        assertTrue(entry.clockOutMs == null)
        assertEquals("Alice", entry.employeeName)
        assertEquals("tablet-1", entry.tabletId)
    }

    @Test
    fun clockOut_setsClockOutMs() {
        val baseDir = tempBaseDir()
        val store = HoursStore(baseDir, "tablet-1")
        val date = LocalDate.of(2026, 5, 12)

        val entry = store.clockIn("Alice", date)
        val updated = store.clockOut(entry.id, date)

        assertNotNull(updated)
        assertNotNull(updated!!.clockOutMs)
        assertTrue(updated.clockOutMs!! >= entry.clockInMs)
    }

    @Test
    fun getEntriesForDate_returnsAllEntries() {
        val baseDir = tempBaseDir()
        val store = HoursStore(baseDir, "tablet-1")
        val date = LocalDate.of(2026, 5, 12)

        store.clockIn("Alice", date)
        store.clockIn("Bob", date)

        val entries = store.getEntriesForDate(date)
        assertEquals(2, entries.size)
    }

    @Test
    fun getActiveEntry_returnsOnlyOpenEntry() {
        val baseDir = tempBaseDir()
        val store = HoursStore(baseDir, "tablet-1")
        val date = LocalDate.of(2026, 5, 12)

        val entry = store.clockIn("Alice", date)
        store.clockOut(entry.id, date)
        store.clockIn("Alice", date)

        val active = store.getActiveEntry("Alice", date)
        assertNotNull(active)
        assertNull(active!!.clockOutMs)
    }

    @Test
    fun persistsAcrossInstances() {
        val baseDir = tempBaseDir()
        val date = LocalDate.of(2026, 5, 12)

        val store1 = HoursStore(baseDir, "tablet-1")
        val entry = store1.clockIn("Alice", date)

        val store2 = HoursStore(baseDir, "tablet-1")
        val entries = store2.getEntriesForDate(date)
        assertEquals(1, entries.size)
        assertEquals(entry.id, entries[0].id)
    }

    @Test
    fun clockOut_returnsNullIfAlreadyClockedOut() {
        val baseDir = tempBaseDir()
        val store = HoursStore(baseDir, "tablet-1")
        val date = LocalDate.of(2026, 5, 12)

        val entry = store.clockIn("Alice", date)
        store.clockOut(entry.id, date)
        val second = store.clockOut(entry.id, date)

        assertNull(second)
    }
}
