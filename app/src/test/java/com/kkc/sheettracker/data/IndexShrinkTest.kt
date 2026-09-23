package com.kkc.sheettracker.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexShrinkTest {
    @Test
    fun theFirstDeriveIsNeverAShrink() {
        assertFalse(isIndexShrink(previousCount = -1, currentCount = 0))
        assertFalse(isIndexShrink(previousCount = -1, currentCount = 12))
    }

    @Test
    fun fewerJobsThanTheLastDeriveIsAShrink() {
        assertTrue(isIndexShrink(previousCount = 12, currentCount = 11))
        assertTrue(isIndexShrink(previousCount = 1, currentCount = 0))
    }

    @Test
    fun sameOrMoreJobsIsNotAShrink() {
        assertFalse(isIndexShrink(previousCount = 12, currentCount = 12))
        assertFalse(isIndexShrink(previousCount = 11, currentCount = 12))
    }
}
