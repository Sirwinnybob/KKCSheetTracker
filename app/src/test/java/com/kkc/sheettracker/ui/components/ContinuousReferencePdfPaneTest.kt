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

    @Test
    fun lruTouch_movesExistingKeyToTheEnd() {
        val order = lruTouch(listOf("a", "b", "c"), "a")

        assertEquals(listOf("b", "c", "a"), order)
    }

    @Test
    fun lruTouch_appendsNewKeyToTheEnd() {
        val order = lruTouch(listOf("a", "b"), "c")

        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test
    fun lruEvictionCandidates_returnsOldestEntriesBeyondCap() {
        val evicted = lruEvictionCandidates(listOf("a", "b", "c", "d"), maxOpen = 2)

        assertEquals(listOf("a", "b"), evicted)
    }

    @Test
    fun lruEvictionCandidates_returnsEmptyWhenUnderCap() {
        val evicted = lruEvictionCandidates(listOf("a", "b"), maxOpen = 3)

        assertEquals(emptyList<String>(), evicted)
    }
}
