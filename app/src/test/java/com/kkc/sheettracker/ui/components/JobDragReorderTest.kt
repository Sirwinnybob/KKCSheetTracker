package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

private data class FakeJob(val folderName: String, val boardSection: Int)

class JobDragReorderTest {

    @Test
    fun `active items take the reordered names in position while pending items keep their own`() {
        val original = listOf(
            FakeJob("A", boardSection = 0),
            FakeJob("B", boardSection = 1),
            FakeJob("C", boardSection = 0),
            FakeJob("D", boardSection = 0)
        )
        // Drag reversed the active subset (A, C, D) to (D, C, A).
        val reordered = listOf("D", "C", "A")

        val result = mergeActiveReorder(
            original = original,
            reorderedActiveFolderNames = reordered,
            boardSectionOf = { it.boardSection },
            folderNameOf = { it.folderName }
        )

        assertEquals(listOf("D", "B", "C", "A"), result)
    }

    @Test
    fun `falls back to original order when reordered list has fewer entries than active items`() {
        val original = listOf(
            FakeJob("A", boardSection = 0),
            FakeJob("B", boardSection = 1),
            FakeJob("C", boardSection = 0),
            FakeJob("D", boardSection = 0)
        )
        // Desynced: only 2 active names captured for 3 active items (e.g. a job moved to
        // Pending Delivery mid-drag). Must not throw and must not misassign names.
        val stale = listOf("D", "C")

        val result = mergeActiveReorder(
            original = original,
            reorderedActiveFolderNames = stale,
            boardSectionOf = { it.boardSection },
            folderNameOf = { it.folderName }
        )

        assertEquals(listOf("A", "B", "C", "D"), result)
    }

    @Test
    fun `falls back to original order when reordered list has more entries than active items`() {
        val original = listOf(
            FakeJob("A", boardSection = 0),
            FakeJob("B", boardSection = 1)
        )
        val stale = listOf("A", "C", "D")

        val result = mergeActiveReorder(
            original = original,
            reorderedActiveFolderNames = stale,
            boardSectionOf = { it.boardSection },
            folderNameOf = { it.folderName }
        )

        assertEquals(listOf("A", "B"), result)
    }

    @Test
    fun `empty active section returns pending items untouched`() {
        val original = listOf(
            FakeJob("X", boardSection = 1),
            FakeJob("Y", boardSection = 1)
        )

        val result = mergeActiveReorder(
            original = original,
            reorderedActiveFolderNames = emptyList(),
            boardSectionOf = { it.boardSection },
            folderNameOf = { it.folderName }
        )

        assertEquals(listOf("X", "Y"), result)
    }
}
