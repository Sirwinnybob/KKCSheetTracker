package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLabelScrollbarTest {

    @Test
    fun indexForTouchY_returnsFallbackWhenNoItems() {
        assertEquals(7, indexForTouchY(items = emptyList(), touchY = 100f, fallback = 7))
    }

    @Test
    fun indexForTouchY_picksItemContainingTouch() {
        val items = listOf(
            ItemBounds(index = 0, offset = 0, size = 50),
            ItemBounds(index = 1, offset = 50, size = 50),
            ItemBounds(index = 2, offset = 100, size = 50)
        )
        assertEquals(1, indexForTouchY(items, touchY = 60f, fallback = 0))
    }

    @Test
    fun indexForTouchY_picksNearestCenterWhenBetweenItems() {
        val items = listOf(
            ItemBounds(index = 0, offset = 0, size = 10),
            ItemBounds(index = 1, offset = 200, size = 10)
        )
        assertEquals(0, indexForTouchY(items, touchY = 20f, fallback = -1))
        assertEquals(1, indexForTouchY(items, touchY = 190f, fallback = -1))
    }

    @Test
    fun indexForTouchY_handlesSingleItem() {
        val items = listOf(ItemBounds(index = 3, offset = 40, size = 20))
        assertEquals(3, indexForTouchY(items, touchY = 0f, fallback = -1))
        assertEquals(3, indexForTouchY(items, touchY = 1000f, fallback = -1))
    }
}
