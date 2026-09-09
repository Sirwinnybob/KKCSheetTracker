package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.ArchiveJobEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveLibraryStoreTest {

    private fun entry(id: String) = ArchiveJobEntry(id, id, "100", "Alpha", "2026-08-19T00:00:00Z", "v1")

    private fun entryWithJobNumber(jobNumber: String) =
        ArchiveJobEntry(jobNumber, jobNumber, jobNumber, "Alpha", "2026-08-19T00:00:00Z", "v1")

    @Test
    fun `applySnapshot replaces the full list and marks connected`() {
        val store = ArchiveLibraryStore()
        store.applySnapshot(mapOf("100 - Alpha" to entry("100 - Alpha")))
        assertEquals(listOf(entry("100 - Alpha")), store.entries.value)
        assertTrue(store.connected.value)
    }

    @Test
    fun `applyDelta upserts a new entry`() {
        val store = ArchiveLibraryStore()
        store.applySnapshot(emptyMap())
        store.applyDelta("100 - Alpha", entry("100 - Alpha"))
        assertEquals(listOf(entry("100 - Alpha")), store.entries.value)
    }

    @Test
    fun `applyDelta with null entry removes it`() {
        val store = ArchiveLibraryStore()
        store.applySnapshot(mapOf("100 - Alpha" to entry("100 - Alpha")))
        store.applyDelta("100 - Alpha", null)
        assertEquals(emptyList<ArchiveJobEntry>(), store.entries.value)
    }

    @Test
    fun `setConnected false clears the connected flag but keeps the last known entries`() {
        val store = ArchiveLibraryStore()
        store.applySnapshot(mapOf("100 - Alpha" to entry("100 - Alpha")))
        store.setConnected(false)
        assertFalse(store.connected.value)
        assertEquals(listOf(entry("100 - Alpha")), store.entries.value)
    }

    @Test
    fun `lettered job numbers sort next to their numeric neighbors, not last`() {
        val store = ArchiveLibraryStore()
        val jobs = listOf("541", "530b", "548", "530a", "520")
        store.applySnapshot(jobs.associateBy { it }.mapValues { (_, jobNumber) -> entryWithJobNumber(jobNumber) })

        val order = store.entries.value.map { it.jobNumber }
        assertEquals(listOf("520", "530a", "530b", "541", "548"), order)
    }
}
