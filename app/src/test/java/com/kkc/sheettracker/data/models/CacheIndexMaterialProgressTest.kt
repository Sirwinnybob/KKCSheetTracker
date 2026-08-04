package com.kkc.sheettracker.data.models

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheIndexMaterialProgressTest {

    @Test
    fun statusCountsLeavesOnlyUnaccountedSheetsAsNotStarted() {
        val counts = CacheIndexMaterialProgress(
            totalSheets = 10,
            done = 2,
            bad = 1,
            skipped = 3,
            renested = 1
        ).toStatusCounts()

        assertEquals(4, counts.notStarted)
    }
}
