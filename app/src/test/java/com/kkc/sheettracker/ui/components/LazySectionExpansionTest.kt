package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class LazySectionExpansionTest {
    @Test fun largeSectionsStayBoundedUntilTheyFillTheScreen() {
        assertEquals(0, lazySectionRowCount(500, false, 0f, 18))
        for (fraction in listOf(0f, 0.01f, 0.5f, 0.99f)) {
            assertEquals(18, lazySectionRowCount(500, true, fraction, 18))
        }
        assertEquals(500, lazySectionRowCount(500, true, 1f, 18))
        assertEquals(18, lazySectionRowCount(500, false, 1f, 18))
        assertEquals(18, lazySectionRowCount(500, false, 0.5f, 18))
        assertEquals(0, lazySectionRowCount(500, false, 0f, 18))
    }

    @Test fun smallAndEmptySectionsDoNotInventRows() {
        assertEquals(3, lazySectionRowCount(3, true, 0.5f, 18))
        assertEquals(0, lazySectionRowCount(0, true, 0.5f, 18))
        assertEquals(1, lazySectionRowCount(500, true, 0.5f, 0))
    }
}
