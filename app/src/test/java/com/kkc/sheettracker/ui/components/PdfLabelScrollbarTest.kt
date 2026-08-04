package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfLabelScrollbarTest {

    @Test
    fun segmentIndexForOffsetFraction_mapsZeroToFirstIndex() {
        assertEquals(0, segmentIndexForOffsetFraction(segmentCount = 10, fraction = 0f))
    }

    @Test
    fun segmentIndexForOffsetFraction_mapsOneToLastIndex() {
        assertEquals(9, segmentIndexForOffsetFraction(segmentCount = 10, fraction = 1f))
    }

    @Test
    fun segmentIndexForOffsetFraction_clampsOutOfRangeFractions() {
        assertEquals(0, segmentIndexForOffsetFraction(segmentCount = 10, fraction = -0.5f))
        assertEquals(9, segmentIndexForOffsetFraction(segmentCount = 10, fraction = 1.5f))
    }

    @Test
    fun segmentIndexForOffsetFraction_returnsZeroForEmptyList() {
        assertEquals(0, segmentIndexForOffsetFraction(segmentCount = 0, fraction = 0.5f))
    }
}
