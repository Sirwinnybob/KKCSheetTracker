package com.kkc.sheettracker.ui.mixes

import com.kkc.sheettracker.data.mixfiles.*
import org.junit.Assert.assertEquals
import org.junit.Test

class RevisionSummaryTest {
    private fun row(vararg pgms: String) = MixFileRow("test.mix", MixFileKind.MIX, null, null, "r", null, false, null, null,
        pgms.map { MixFileEntry("C:\\$it", it, linkedMapOf("R" to "1"), null, 1, null, true, false) })
    @Test fun summaryReordered() { assertEquals("reordered", revisionSummary(row("a", "b"), row("b", "a"))) }
    @Test fun summaryAddedRemoved() {
        assertEquals("2 sheets added", revisionSummary(row("a"), row("a", "b", "c")))
        assertEquals("1 sheets removed", revisionSummary(row("a", "a", "b"), row("a", "b")))
        assertEquals("1 sheets added", revisionSummary(row("a", "b"), row("a", "c")))
    }
    @Test fun summaryQuantitiesChanged() {
        val old = row("a")
        assertEquals("quantities changed", revisionSummary(old, old.copy(entries = old.entries.map { it.copy(quantity = 3) })))
    }
    @Test fun summaryNoChange() { assertEquals("no change", revisionSummary(row("a", "a"), row("a", "a"))) }
}
