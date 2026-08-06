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

    @Test
    fun visiblePageFraction_fullyOnScreenReturnsWholePage() {
        val frac = visiblePageFraction(
            pageLeft = 0f, pageTop = 0f, pageRight = 800f, pageBottom = 1000f,
            viewportWidth = 800f, viewportHeight = 1000f
        )

        assertEquals(0f, frac!!.left, 0.001f)
        assertEquals(0f, frac.top, 0.001f)
        assertEquals(1f, frac.right, 0.001f)
        assertEquals(1f, frac.bottom, 0.001f)
    }

    @Test
    fun visiblePageFraction_zoomedInShowsOnlyOnScreenSlice() {
        // Page is 3x the viewport (zoomed in), scrolled so only its middle third is on screen.
        val frac = visiblePageFraction(
            pageLeft = 0f, pageTop = -1000f, pageRight = 800f, pageBottom = 2000f,
            viewportWidth = 800f, viewportHeight = 1000f
        )

        assertEquals(0f, frac!!.left, 0.001f)
        assertEquals(1f / 3f, frac.top, 0.001f)
        assertEquals(1f, frac.right, 0.001f)
        assertEquals(2f / 3f, frac.bottom, 0.001f)
    }

    @Test
    fun visiblePageFraction_noOverlapReturnsNull() {
        val frac = visiblePageFraction(
            pageLeft = 0f, pageTop = 2000f, pageRight = 800f, pageBottom = 3000f,
            viewportWidth = 800f, viewportHeight = 1000f
        )

        assertEquals(null, frac)
    }

    @Test
    fun visiblePageFraction_partialOverlapAtEdge() {
        // Page's bottom half hangs off below the viewport.
        val frac = visiblePageFraction(
            pageLeft = 0f, pageTop = 500f, pageRight = 800f, pageBottom = 1500f,
            viewportWidth = 800f, viewportHeight = 1000f
        )

        assertEquals(0f, frac!!.left, 0.001f)
        assertEquals(0f, frac.top, 0.001f)
        assertEquals(1f, frac.right, 0.001f)
        assertEquals(0.5f, frac.bottom, 0.001f)
    }

    @Test
    fun maxCrossAxisPan_zoomOneAllowsNoPan() {
        assertEquals(0f, maxCrossAxisPan(viewportExtent = 800f, zoom = 1f), 0.001f)
    }

    @Test
    fun maxCrossAxisPan_scalesWithZoom() {
        assertEquals(400f, maxCrossAxisPan(viewportExtent = 800f, zoom = 2f), 0.001f)
    }

    @Test
    fun computeFlingStep_decaysVelocityAndCalculatesDelta() {
        val result = computeFlingStep(velocity = 1000f, dtSeconds = 0.016f, friction = 4.0f)

        // Exponential decay: v1 = 1000 * exp(-4.0 * 0.016) ≈ 938.0
        assertEquals(938.0f, result.nextVelocity, 1.0f)
        // Integrated delta: 1000 * (1 - exp(-0.064)) / 4.0 ≈ 15.5
        assertEquals(15.5f, result.delta, 0.5f)
    }

    @Test
    fun computeFlingStep_stopsWhenVelocityBelowThreshold() {
        val result = computeFlingStep(velocity = 5f, dtSeconds = 0.016f, minVelocityThreshold = 10f)

        assertEquals(0f, result.nextVelocity, 0.001f)
        assertEquals(0f, result.delta, 0.001f)
    }
}
