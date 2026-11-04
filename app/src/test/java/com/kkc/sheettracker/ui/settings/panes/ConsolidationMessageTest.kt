package com.kkc.sheettracker.ui.settings.panes

import com.kkc.sheettracker.data.mixorder.ConsolidationResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsolidationMessageTest {
    @Test
    fun unavailableShare() {
        assertEquals("Ready Jobs share unavailable", consolidationMessage(null))
    }

    @Test
    fun mergedCounts() {
        assertEquals("Merged 12 records from 5 files", consolidationMessage(ConsolidationResult(12, 5, 0, 0, null)))
    }

    @Test
    fun badFilesAreAppended() {
        assertEquals(
            "Merged 12 records from 5 files, 2 unreadable moved to bad",
            consolidationMessage(ConsolidationResult(12, 5, 2, 1, null))
        )
    }
}
