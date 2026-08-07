package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
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

    // Reproduces the "drag 4 pages before the PDF starts scrolling" bug: in BUCKETED mode one
    // tick spans several real pages, and dragging must actually move the requested page or
    // ContinuousReferencePdfPane's scrollToPage effect (keyed on the value) never re-fires.
    // pageForTouchY must vary continuously across a bucketed tick's own span, not snap to the
    // bucket's first page for the whole tick.
    @Test
    fun pageForTouchY_interpolatesAcrossBucketedRange() {
        val items = listOf(ItemBounds(index = 0, offset = 0, size = 100))
        val entries = listOf(
            ScrollbarEntry(label = "43", rangeLabel = "43-44", page = 43, pageRange = 43..44, rowIndex = 42)
        )
        assertEquals(43, pageForTouchY(items, entries, touchY = 10f, fallbackPage = -1))
        assertEquals(44, pageForTouchY(items, entries, touchY = 90f, fallbackPage = -1))
    }

    @Test
    fun pageForTouchY_singlePageRangeReturnsThatPage() {
        val items = listOf(ItemBounds(index = 0, offset = 0, size = 100))
        val entries = listOf(
            ScrollbarEntry(label = "5", rangeLabel = null, page = 5, pageRange = 5..5, rowIndex = 4)
        )
        assertEquals(5, pageForTouchY(items, entries, touchY = 0f, fallbackPage = -1))
        assertEquals(5, pageForTouchY(items, entries, touchY = 100f, fallbackPage = -1))
    }

    @Test
    fun pageForTouchY_returnsFallbackWhenNoItems() {
        assertEquals(9, pageForTouchY(emptyList(), emptyList(), touchY = 10f, fallbackPage = 9))
    }

    @Test
    fun pageForTouchY_widerBucketStillSpansFullRange() {
        val items = listOf(ItemBounds(index = 0, offset = 0, size = 40))
        val entries = listOf(
            ScrollbarEntry(label = "97", rangeLabel = "97-100", page = 97, pageRange = 97..100, rowIndex = 96)
        )
        assertEquals(97, pageForTouchY(items, entries, touchY = 0f, fallbackPage = -1))
        assertEquals(98, pageForTouchY(items, entries, touchY = 13f, fallbackPage = -1))
        assertEquals(99, pageForTouchY(items, entries, touchY = 27f, fallbackPage = -1))
        assertEquals(100, pageForTouchY(items, entries, touchY = 40f, fallbackPage = -1))
    }
}
