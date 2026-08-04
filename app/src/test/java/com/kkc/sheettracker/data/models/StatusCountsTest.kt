package com.kkc.sheettracker.data.models

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusCountsTest {
    @Test
    fun renestedSheetsRemainPartOfTheCanonicalMaterialTotal() {
        val counts = StatusCounts(total = 15, complete = 2, reNested = 13)

        assertEquals(15, counts.total)
        assertEquals(13, counts.reNested)
    }
}
