package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuousReferencePdfPaneTest {

    @Test
    fun computeRenderWindow_addsOnePageBufferOnEachSide() {
        val window = computeRenderWindow(firstVisiblePage = 5, lastVisiblePage = 6, totalPages = 20, buffer = 1)

        assertEquals(4..7, window)
    }

    @Test
    fun computeRenderWindow_clampsToDocumentBounds() {
        val start = computeRenderWindow(firstVisiblePage = 1, lastVisiblePage = 1, totalPages = 20, buffer = 1)
        val end = computeRenderWindow(firstVisiblePage = 20, lastVisiblePage = 20, totalPages = 20, buffer = 1)

        assertEquals(1..2, start)
        assertEquals(19..20, end)
    }

    @Test
    fun computeRenderWindow_returnsEmptyForEmptyDocument() {
        val window = computeRenderWindow(firstVisiblePage = 1, lastVisiblePage = 1, totalPages = 0, buffer = 1)

        assertEquals(IntRange.EMPTY, window)
    }
}
