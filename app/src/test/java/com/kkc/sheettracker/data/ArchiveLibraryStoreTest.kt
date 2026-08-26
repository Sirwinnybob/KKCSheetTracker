package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.ArchiveJobEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveLibraryStoreTest {

    private fun entry(id: String) = ArchiveJobEntry(id, id, "100", "Alpha", "2026-08-19T00:00:00Z", "v1")

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
}
