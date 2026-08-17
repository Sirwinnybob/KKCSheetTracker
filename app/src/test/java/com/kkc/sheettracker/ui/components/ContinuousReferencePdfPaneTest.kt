package com.kkc.sheettracker.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import com.kkc.sheettracker.ui.viewer.ResolvedPageSource
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousReferencePdfPaneTest {

    @Test
    fun continuousCropOverlayBounds_tracksTheStoredPdfFraction() {
        assertEquals(
            ContinuousCropOverlayBounds(leftPx = 200f, topPx = 260f, widthPx = 300f, heightPx = 200f),
            resolveContinuousCropOverlayBounds(
                pageLeftPx = -100f,
                pageTopPx = 60f,
                pageRightPx = 900f,
                pageBottomPx = 1_060f,
                cropFrac = UnitRect(left = 0.3f, top = 0.2f, right = 0.6f, bottom = 0.4f)
            )
        )
    }

    @Test
    fun continuousCropOverlayBounds_followsTheCurrentPageBounds() {
        val bounds = resolveContinuousCropOverlayBounds(
            pageLeftPx = -460f,
            pageTopPx = -540f,
            pageRightPx = 3_540f,
            pageBottomPx = 4_460f,
            cropFrac = UnitRect(left = 0.3f, top = 0.2f, right = 0.45f, bottom = 0.28f)
        )
        assertEquals(740f, bounds.leftPx, 0.001f)
        assertEquals(460f, bounds.topPx, 0.001f)
        assertEquals(600f, bounds.widthPx, 0.001f)
        assertEquals(400f, bounds.heightPx, 0.001f)
    }

    @Test
    fun continuousPdfCropRender_bypassesMotionDebounceForNewDarkVariant() {
        assertTrue(
            shouldRenderContinuousPdfCrop(
                inWindow = true,
                zoomedIn = true,
                settled = false,
                sourceVariantChanged = true
            )
        )
    }

    @Test
    fun continuousPdfCropRender_waitsForTheNewSourceGeometry() {
        assertFalse(
            shouldRenderContinuousPdfCrop(
                inWindow = true,
                zoomedIn = true,
                settled = true,
                sourceVariantChanged = true,
                sourceGeometryReady = false
            )
        )
    }

    @Test
    fun continuousPdfCropRender_keepsMotionDebounceForUnchangedVariant() {
        assertFalse(
            shouldRenderContinuousPdfCrop(
                inWindow = true,
                zoomedIn = true,
                settled = false,
                sourceVariantChanged = false
            )
        )
    }

    @Test
    fun continuousPdfPageRenderDwell_requiresFull300Milliseconds() {
        assertFalse(hasContinuousPdfPageRenderDwelled(visibleSinceMillis = 1_000L, nowMillis = 1_299L))
        assertTrue(hasContinuousPdfPageRenderDwelled(visibleSinceMillis = 1_000L, nowMillis = 1_300L))
    }

    @Test
    fun continuousPdfPageRenderDwell_isIneligibleWithoutVisibleStartTime() {
        assertFalse(hasContinuousPdfPageRenderDwelled(visibleSinceMillis = null, nowMillis = 2_000L))
    }

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
    fun coalescingChannel_deliversPendingDeltaBeforeFlingSentinel() = runBlocking {
        val channel = CoalescingMainAxisDeltaChannel()
        channel.trySend(5f)
        channel.trySend(Float.NaN)

        assertEquals(5f, channel.receive(), 0.001f)
        assertTrue(channel.receive().isNaN())
    }

    @Test
    fun programmaticScrollGuard_isActiveFromBeginUntilItsOwnRelease() {
        val guard = ProgrammaticScrollGuard()
        assertFalse(guard.isActive)
        val token = guard.begin()
        assertTrue(guard.isActive)
        guard.release(token)
        assertFalse(guard.isActive)
    }

    /**
     * Reproduces the continuous-scroll "pages render out of order" bug: a new scrollToPage value
     * cancels the in-flight animateScrollToItem for the previous one. That cancelled call's
     * `finally` still runs and must NOT clear the guard while a newer programmatic scroll (the one
     * that superseded it) is still in flight -- otherwise the centered-page listener treats the
     * list's transient, uncontrolled position as a fresh external nav request, and the two sides
     * ratchet the list further away from any position either one actually asked for.
     */
    @Test
    fun programmaticScrollGuard_staysActiveWhenASupersededGenerationReleasesLate() {
        val guard = ProgrammaticScrollGuard()
        val firstToken = guard.begin()
        val secondToken = guard.begin() // a new scrollToPage arrived, cancelling the first
        assertTrue(guard.isActive)

        guard.release(firstToken) // the cancelled first call's `finally` running late
        assertTrue("guard must stay active: a newer scroll is still in flight", guard.isActive)

        guard.release(secondToken) // the current scroll actually finishes
        assertFalse(guard.isActive)
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
    fun continuousMainAxisScrollDelta_oneFingerRetainsExistingCalculation() {
        assertEquals(
            -50f,
            continuousMainAxisScrollDelta(
                panDelta = 100f,
                zoom = 2f,
                viewportExtent = 1752
            )!!,
            0.001f
        )
    }

    @Test
    fun continuousMainAxisScrollDelta_verticalOffCenterZoomCompensatesLazyList() {
        val transform = computeZoomPan(
            zoom = 1f, panX = 0f, panY = 0f,
            zoomChange = 2f, panChange = Offset.Zero,
            centroid = Offset(500f, 250f),
            viewWidth = 1000, viewHeight = 1000,
            minZoom = 1f, maxZoom = 20f
        )

        assertEquals(250f, transform.panY, 0.001f)
        assertEquals(
            -125f,
            continuousMainAxisScrollDelta(
                panDelta = transform.panY,
                zoom = transform.zoom,
                viewportExtent = 1000
            )!!,
            0.001f
        )
    }

    @Test
    fun continuousMainAxisScrollDelta_twoFingerPanIsNotDropped() {
        assertEquals(
            -30f,
            continuousMainAxisScrollDelta(
                panDelta = 60f,
                zoom = 2f,
                viewportExtent = 1000
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
    fun continuousDocumentIdentity_isStableAcrossUnrelatedRecomputation() {
        val dir = Files.createTempDirectory("continuous-pdf-identity").toFile()
        val pdf = File(dir, "assembly.pdf").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(1_700_000_000_000L)
        }
        val resolve = { page: Int -> ResolvedPageSource("assembly.pdf", page) }
        val files = { _: String -> pdf }

        val before = resolveContinuousPdfDocumentIdentity(2, resolve, files)
        val afterUnrelatedRefresh = resolveContinuousPdfDocumentIdentity(2, resolve, files)

        assertEquals(before, afterUnrelatedRefresh)
    }

    @Test
    fun continuousDocumentIdentity_changesWhenMappingChanges() {
        val pdf = File(Files.createTempDirectory("continuous-pdf-map").toFile(), "assembly.pdf")
            .apply { writeBytes(byteArrayOf(1)) }
        val files = { _: String -> pdf }
        val first = resolveContinuousPdfDocumentIdentity(
            totalPages = 2,
            resolvePage = { page -> ResolvedPageSource("assembly.pdf", page) },
            pdfFileForFilename = files
        )
        val remapped = resolveContinuousPdfDocumentIdentity(
            totalPages = 2,
            resolvePage = { page -> ResolvedPageSource("assembly.pdf", 3 - page) },
            pdfFileForFilename = files
        )

        assertNotEquals(first, remapped)
    }

    @Test
    fun continuousDocumentIdentity_changesWhenSourceFileChanges() {
        val pdf = File(Files.createTempDirectory("continuous-pdf-file").toFile(), "assembly.pdf")
            .apply { writeBytes(byteArrayOf(1)) }
        val resolve = { page: Int -> ResolvedPageSource("assembly.pdf", page) }
        val before = resolveContinuousPdfDocumentIdentity(1, resolve) { pdf }

        pdf.appendBytes(byteArrayOf(2))
        pdf.setLastModified(pdf.lastModified() + 1_000L)
        val after = resolveContinuousPdfDocumentIdentity(1, resolve) { pdf }

        assertNotEquals(before, after)
    }

    @Test
    fun continuousDocumentIdentity_recordsMissingSource() {
        val identity = resolveContinuousPdfDocumentIdentity(
            totalPages = 1,
            resolvePage = { ResolvedPageSource("missing.pdf", 1) },
            pdfFileForFilename = { null }
        )

        assertEquals(false, identity.sources.single().exists)
        assertEquals("missing.pdf", identity.sources.single().pdfFilename)
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
