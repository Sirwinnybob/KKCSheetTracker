package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLabelScrollbarTest {

    @Test
    fun dockScaleForDistance_isFullScaleAtZeroDistance() {
        assertEquals(1f, dockScaleForDistance(distance = 0, radius = 1.6f), 0.001f)
    }

    @Test
    fun dockScaleForDistance_decreasesAsDistanceGrows() {
        val near = dockScaleForDistance(distance = 1, radius = 1.6f)
        val far = dockScaleForDistance(distance = 4, radius = 1.6f)
        assertTrue("near ($near) should be larger than far ($far)", near > far)
        assertTrue(near < 1f)
        assertTrue(far > 0f)
    }

    @Test
    fun dockScaleForDistance_approachesZeroFarFromFocus() {
        val scale = dockScaleForDistance(distance = 20, radius = 1.6f)
        assertTrue(scale < 0.01f)
    }

    @Test
    fun dockScaleForDistance_zeroRadiusOnlyMagnifiesExactMatch() {
        assertEquals(1f, dockScaleForDistance(distance = 0, radius = 0f), 0.001f)
        assertEquals(0f, dockScaleForDistance(distance = 1, radius = 0f), 0.001f)
    }

    @Test
    fun centerOutLoadOrder_startsAtFocusAndAlternatesOutward() {
        val order = centerOutLoadOrder(count = 5, focus = 2)
        assertEquals(listOf(2, 1, 3, 0, 4), order)
    }

    @Test
    fun centerOutLoadOrder_clampsFocusToValidRange() {
        val order = centerOutLoadOrder(count = 3, focus = 99)
        assertEquals(listOf(2, 1, 0), order)
    }

    @Test
    fun centerOutLoadOrder_handlesFocusAtEdge() {
        val order = centerOutLoadOrder(count = 4, focus = 0)
        assertEquals(listOf(0, 1, 2, 3), order)
    }

    @Test
    fun centerOutLoadOrder_returnsEmptyForEmptyDocument() {
        assertEquals(emptyList<Int>(), centerOutLoadOrder(count = 0, focus = 0))
    }
}
