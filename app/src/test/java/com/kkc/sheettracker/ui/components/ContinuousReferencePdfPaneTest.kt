package com.kkc.sheettracker.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import com.kkc.sheettracker.ui.viewer.ResolvedPageSource
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousReferencePdfPaneTest {

    @Test
    fun coalesceMainAxisDelta_preservesTotalMovementInOnePendingSlot() {
        var pending = 0f
        pending = coalesceMainAxisDelta(pending, 3f)
        pending = coalesceMainAxisDelta(pending, -1f)
        pending = coalesceMainAxisDelta(pending, 5f)

        assertEquals(7f, pending, 0.001f)
    }

    @Test
    fun coalescingChannel_rewakesDeltaAfterFlingSentinel() = runBlocking {
        val channel = CoalescingMainAxisDeltaChannel()
        channel.trySend(Float.NaN)
        channel.trySend(7f)

        assertTrue(channel.receive().isNaN())
        val delta = withTimeout(250) { channel.receive() }

        assertEquals(7f, delta, 0.001f)
    }

    @Test
    fun continuousPdfColors_preferDarkModeUsesPureBlack() {
        assertEquals(
            Color.Black,
            continuousPdfCanvasColor(preferDarkMode = true, lightCanvasColor = Color(0xFF123456))
        )
        assertEquals(
            android.graphics.Color.BLACK,
            continuousPdfMatteColorArgb(
                preferDarkMode = true,
                lightMatteColorArgb = android.graphics.Color.WHITE
            )
        )
    }

    @Test
    fun continuousPdfColors_lightModeRetainsExistingLightValues() {
        val lightCanvas = Color(0xFF123456)
        val lightMatte = 0xFFABCDEF.toInt()

        assertEquals(
            lightCanvas,
            continuousPdfCanvasColor(preferDarkMode = false, lightCanvasColor = lightCanvas)
        )
        assertEquals(
            lightMatte,
            continuousPdfMatteColorArgb(
                preferDarkMode = false,
                lightMatteColorArgb = lightMatte
            )
        )
    }

    @Test
    fun continuousMainAxisScrollDelta_multiTouchReturnsNull() {
        assertEquals(
            null,
            continuousMainAxisScrollDelta(
                isMultiTouch = true,
                panDelta = 100f,
                zoom = 2f,
                viewportExtent = 1752
            )
        )
    }

    @Test
    fun continuousMainAxisScrollDelta_oneFingerRetainsExistingCalculation() {
        assertEquals(
            -50f,
            continuousMainAxisScrollDelta(
                isMultiTouch = false,
                panDelta = 100f,
                zoom = 2f,
                viewportExtent = 1752
            )!!,
            0.001f
        )
    }

    @Test
    fun continuousPageRenderIdentity_changesWhenDarkModeChanges() {
        val resolved = ResolvedPageSource(pdfFilename = "plans.pdf", sourcePage = 3)
        val file = File("plans.pdf")

        val light = continuousPageRenderIdentity(
            displayPage = 5,
            resolved = resolved,
            file = file,
            preferDarkMode = false
        )
        val dark = continuousPageRenderIdentity(
            displayPage = 5,
            resolved = resolved,
            file = file,
            preferDarkMode = true
        )

        assertNotEquals(light, dark)
    }

    @Test
    fun continuousPageGeometryIdentity_staysStableAcrossDarkModeChanges() {
        val resolved = ResolvedPageSource(pdfFilename = "plans.pdf", sourcePage = 3)
        val file = File("plans.pdf")
        val light = continuousPageRenderIdentity(5, resolved, file, preferDarkMode = false)
        val dark = continuousPageRenderIdentity(5, resolved, file, preferDarkMode = true)

        assertEquals(
            continuousPageGeometryIdentity(light, fileIdentitySeed = 17L, docKey = "plans"),
            continuousPageGeometryIdentity(dark, fileIdentitySeed = 17L, docKey = "plans")
        )
    }

    @Test
    fun resolveCropRenderSize_preservesFullViewportDimensionsWithinPixelBudget() {
        val viewportCrop = IntSize(width = 1752, height = 2800)

        assertEquals(
            viewportCrop,
            resolveCropRenderSize(viewportCrop, maxPixels = 5_000_000L)
        )
    }

    @Test
    fun resolveCropRenderSize_skipsOversizedTileInsteadOfDownscaling() {
        val viewportCrop = IntSize(width = 1752, height = 2800)

        assertEquals(null, resolveCropRenderSize(viewportCrop, maxPixels = 1_000_000L))
    }

    @Test
    fun resolveVisibleCropRenderSize_usesVisibleFractionInsteadOfFullPageBounds() {
        val fraction = visiblePageFraction(
            pageLeft = 0f,
            pageTop = 0f,
            pageRight = 8760f,
            pageBottom = 14000f,
            viewportWidth = 1752f,
            viewportHeight = 2800f
        )!!

        assertEquals(
            IntSize(width = 1752, height = 2800),
            resolveVisibleCropRenderSize(
                pageWidthPx = 8760f,
                pageHeightPx = 14000f,
                cropFrac = fraction,
                maxPixels = 8_000_000L
            )
        )
    }

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
    fun mainAxisEdgePadding_zoomOneNeedsNoPadding() {
        assertEquals(0f, mainAxisEdgePadding(viewportExtent = 1000f, zoom = 1f), 0.001f)
    }

    @Test
    fun mainAxisEdgePadding_scalesWithZoom() {
        // At zoom 2x centered on viewport middle, first/last page edges overflow the
        // scrollable range by viewportExtent/2 * (1 - 1/zoom) in list-space units.
        assertEquals(250f, mainAxisEdgePadding(viewportExtent = 1000f, zoom = 2f), 0.001f)
    }

    @Test
    fun splitScrollDelta_noOverscroll_allGoesToRealScroll() {
        assertEquals(ScrollSplitResult(50f, 0f), splitScrollDelta(requestedDelta = 50f, currentOverscroll = 0f, maxOverscroll = 25f))
        assertEquals(ScrollSplitResult(-30f, 0f), splitScrollDelta(requestedDelta = -30f, currentOverscroll = 0f, maxOverscroll = 25f))
    }

    @Test
    fun splitScrollDelta_pushingDeeperIntoEndOverscroll_clampsToMax() {
        val result = splitScrollDelta(requestedDelta = 10f, currentOverscroll = 20f, maxOverscroll = 25f)

        assertEquals(0f, result.realScrollDelta, 0.001f)
        assertEquals(5f, result.overscrollDelta, 0.001f) // 20 + 10 clamped to 25
    }

    @Test
    fun splitScrollDelta_drainingEndOverscrollPartially_noRealScroll() {
        val result = splitScrollDelta(requestedDelta = -5f, currentOverscroll = 20f, maxOverscroll = 25f)

        assertEquals(0f, result.realScrollDelta, 0.001f)
        assertEquals(-5f, result.overscrollDelta, 0.001f)
    }

    @Test
    fun splitScrollDelta_drainingEndOverscrollFully_remainderGoesToRealScroll() {
        val result = splitScrollDelta(requestedDelta = -30f, currentOverscroll = 20f, maxOverscroll = 25f)

        assertEquals(-10f, result.realScrollDelta, 0.001f) // -30 + 20 drained
        assertEquals(-20f, result.overscrollDelta, 0.001f) // fully drains the 20
    }

    @Test
    fun splitScrollDelta_pushingDeeperIntoStartOverscroll_clampsToMax() {
        val result = splitScrollDelta(requestedDelta = -20f, currentOverscroll = -15f, maxOverscroll = 25f)

        assertEquals(0f, result.realScrollDelta, 0.001f)
        assertEquals(-10f, result.overscrollDelta, 0.001f) // -15 + -20 clamped to -25
    }

    @Test
    fun splitScrollDelta_drainingStartOverscrollFully_remainderGoesToRealScroll() {
        val result = splitScrollDelta(requestedDelta = 25f, currentOverscroll = -15f, maxOverscroll = 25f)

        assertEquals(10f, result.realScrollDelta, 0.001f) // 25 - 15 drained
        assertEquals(15f, result.overscrollDelta, 0.001f) // fully drains the -15
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
