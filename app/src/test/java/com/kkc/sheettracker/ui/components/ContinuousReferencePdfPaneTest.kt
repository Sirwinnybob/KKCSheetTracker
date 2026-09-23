package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.kkc.sheettracker.ui.markup.pdfPageTransformForRect
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kkc.sheettracker.ui.viewer.ResolvedPageSource
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousReferencePdfPaneTest {

    @Test
    fun cabinetNavigation_doesNotTreatTheLastReportedPageAsAlreadyAligned() {
        val source = continuousPaneSource()

        assertFalse(
            "At the document end, the displayed page can be the final visible page while the " +
                "first list item is still the preceding page. Cabinet navigation must still scroll.",
            source.contains("scrollToPage == lastReportedPage || isInteracting")
        )
    }

    @Test
    fun continuousCurrentPage_keepsFirstVisiblePageAwayFromTheDocumentEnd() {
        assertEquals(
            4,
            continuousCurrentPage(firstVisibleIndex = 3, lastVisibleIndex = 5, canScrollForward = true)
        )
    }

    @Test
    fun fingerGestures_areOwnedByThePaneWheneverInkIsOff() {
        for (allowFinger in listOf(false, true)) {
            assertTrue(shouldContinuousPaneOwnFingerGestures(markupEnabled = false, allowFingerDrawing = allowFinger))
        }
    }

    @Test
    fun fingerGestures_stayWithThePaneWhileInkIsOnAndOnlyThePenMarks() {
        // Covers every tool, eraser included: the eraser erases with the pen only.
        assertTrue(shouldContinuousPaneOwnFingerGestures(markupEnabled = true, allowFingerDrawing = false))
    }

    @Test
    fun fingerGestures_lockWhenFingerDrawingIsOn() {
        assertFalse(shouldContinuousPaneOwnFingerGestures(markupEnabled = true, allowFingerDrawing = true))
    }

    @Test
    fun stylusAndEraserPointersAreStylusTypes() {
        assertTrue(isStylusPointerType(PointerType.Stylus))
        assertTrue(isStylusPointerType(PointerType.Eraser))
        assertFalse(isStylusPointerType(PointerType.Touch))
        assertFalse(isStylusPointerType(PointerType.Mouse))
    }

    @Test
    fun inkMode_keepsFingerScrollAndZoomHandlerAvailable() {
        val source = continuousPaneSource()

        assertFalse(
            "Ink on must not blanket-disable the scroll/zoom handler; fingers scroll while the pen draws.",
            source.contains("val gesturesEnabled = !markupEnabled")
        )
        assertTrue(source.contains("shouldContinuousPaneOwnFingerGestures("))
    }

    @Test
    fun inkMode_fingerTapDoesNotToggleChrome() {
        assertTrue(
            "A finger tap while inking must not hide the chrome that holds the ink toolbar.",
            continuousPaneSource().contains("if (!currentMarkupEnabled && !wasMultiTouch && totalMovement <= touchSlop)")
        )
    }

    @Test
    fun scrollZoomHandler_ignoresStylusPointers() {
        val source = continuousPaneSource()

        assertTrue(
            "A pen-down must not start a scroll/zoom/tap gesture while ink is on.",
            source.contains("currentMarkupEnabled && isStylusPointerType(firstDown.type)")
        )
        assertFalse(
            "With ink off the pen must still scroll, fling and tap; the stylus skip must be gated on markupEnabled.",
            source.contains("if (isStylusPointerType(firstDown.type))")
        )
        assertTrue(
            "A pen landing mid-gesture (resting palm) must hand the gesture to the overlay.",
            source.contains("stylusTookOver")
        )

        val cancelIndex = source.indexOf("flingJob?.cancel()  // NOW cancel")
        val stylusCheckIndex = source.indexOf("currentMarkupEnabled && isStylusPointerType(firstDown.type)")
        val interactingIndex = source.indexOf("isInteracting = true", stylusCheckIndex)
        assertTrue("flingJob?.cancel() must be present.", cancelIndex >= 0)
        assertTrue(
            "A pen touch must still cancel a running fling before the stylus skip returns, so the page doesn't scroll under the stroke.",
            cancelIndex < stylusCheckIndex
        )
        assertTrue(
            "The stylus skip must return before isInteracting is set for a finger/scroll gesture.",
            stylusCheckIndex < interactingIndex
        )

        val loopTopStylusCheckIndex = source.indexOf(
            "event.changes.any { it.pressed && isStylusPointerType(it.type) }"
        )
        val consumeIndex = source.indexOf("event.changes.forEach { it.consume() }")
        assertTrue("Loop-top stylus check must be present.", loopTopStylusCheckIndex >= 0)
        assertTrue("consume() call must be present.", consumeIndex >= 0)
        assertTrue(
            "The mid-gesture stylus check must break before this event's changes are consumed, so a pen on a different page's overlay still gets its own down.",
            loopTopStylusCheckIndex < consumeIndex
        )

        val finallyIndex = source.indexOf("} finally {", loopTopStylusCheckIndex)
        val isInteractingFalseIndex = source.indexOf("isInteracting = false", loopTopStylusCheckIndex)
        val stylusTookOverReturnIndex = source.indexOf("if (stylusTookOver) return@awaitEachGesture")
        val singleTapIndex = source.indexOf("currentOnSingleTap?.invoke()")
        assertTrue("isInteracting = false must be reset inside a finally block.", finallyIndex in 0 until isInteractingFalseIndex)
        assertTrue(
            "The stylusTookOver return must come after the finally reset and before the single-tap dispatch.",
            isInteractingFalseIndex < stylusTookOverReturnIndex && stylusTookOverReturnIndex < singleTapIndex
        )
    }

    private fun continuousPaneSource(): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt")
            if (candidate.exists()) return candidate.readText()
            val direct = File(dir, "src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Unable to locate ContinuousReferencePdfPane.kt from ${System.getProperty("user.dir")}")
    }

    @Test
    fun continuousCurrentPage_selectsLastVisiblePageAtTheDocumentEnd() {
        assertEquals(
            10,
            continuousCurrentPage(firstVisibleIndex = 7, lastVisibleIndex = 9, canScrollForward = false)
        )
    }

    @Test
    fun continuousCurrentPage_keepsPageOneWhenTheEntireDocumentFits() {
        assertEquals(
            1,
            continuousCurrentPage(firstVisibleIndex = 0, lastVisibleIndex = 2, canScrollForward = false)
        )
    }

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
    fun continuousPdfDocumentFlingScope_cancelsOutgoingJobsWithScope() = runBlocking {
        val parent = CoroutineScope(coroutineContext + SupervisorJob())
        val documentScope = continuousPdfDocumentFlingScope(parent)
        val fling = documentScope.launch { awaitCancellation() }

        documentScope.cancel()
        withTimeout(1_000L) { fling.join() }

        assertTrue(fling.isCancelled)
        parent.cancel()
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
        val documentIdentity = resolveContinuousPdfDocumentIdentity(1, { resolved }) { file }

        assertEquals(
            continuousPageGeometryIdentity(light, documentIdentity, docKey = "plans"),
            continuousPageGeometryIdentity(dark, documentIdentity, docKey = "plans")
        )
    }

    @Test
    fun continuousPageGeometryIdentity_staysStableAcrossUnrelatedRefresh() {
        val file = File("plans.pdf")
        val resolved = ResolvedPageSource("plans.pdf", 3)
        val firstDocument = resolveContinuousPdfDocumentIdentity(1, { resolved }) { file }
        val secondDocument = resolveContinuousPdfDocumentIdentity(1, { resolved }) { file }
        val render = continuousPageRenderIdentity(5, resolved, file, preferDarkMode = false)

        assertEquals(
            continuousPageGeometryIdentity(render, firstDocument, docKey = "plans"),
            continuousPageGeometryIdentity(render, secondDocument, docKey = "plans")
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
    fun mainAxisEdgePadding_allowsHalfViewportPastTheDocumentAtNormalZoom() {
        assertEquals(500f, mainAxisEdgePadding(viewportExtent = 1000f, zoom = 1f), 0.001f)
    }

    @Test
    fun mainAxisEdgePadding_keepsHalfViewportSpaceWhenZoomed() {
        assertEquals(500f, mainAxisEdgePadding(viewportExtent = 1000f, zoom = 2f), 0.001f)
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

    @Test
    fun inkOverlays_areComposedAboveTheSharpCropCanvas_notInsidePageContent() {
        val source = continuousPaneSource().replace("\r\n", "\n")
        val cropCanvas = source.indexOf("Canvas(Modifier.fillMaxSize())")
        val inkLayerCall = source.indexOf("ContinuousInkOverlayLayer(\n                    inkPages =")
        val firstOverlayCall = source.indexOf("PdfMarkupOverlay(\n")
        val pageContentStart = source.indexOf("val pageContent: @Composable (Int) -> Unit")
        val pageContentEnd = source.indexOf("val gesturesEnabled = shouldContinuousPaneOwnFingerGestures(")

        assertTrue("crop canvas not found", cropCanvas >= 0)
        assertTrue("ink layer call not found", inkLayerCall >= 0)
        assertTrue("PdfMarkupOverlay call not found", firstOverlayCall >= 0)
        assertTrue("pageContent bounds not found", pageContentStart in 0 until pageContentEnd)
        assertTrue(
            "Zoomed sharp crops are drawn outside the zoom layer; ink must be composed after them " +
                "or the crops cover every stroke.",
            inkLayerCall > cropCanvas && firstOverlayCall > cropCanvas
        )
        assertFalse(
            "pageContent must not compose PdfMarkupOverlay (it sits under the crop canvas).",
            source.substring(pageContentStart, pageContentEnd).contains("PdfMarkupOverlay(")
        )
        assertTrue(source.contains("strokeWidthScale = zoomScale"))
        assertTrue(source.contains("val zoomScale = zoom.coerceAtLeast(1f)"))
        assertTrue(source.contains("zoom = sharedZoom,"))
    }

    @Test
    fun inkPages_areRemovedWhereverPageCoordinatesAreRemoved() {
        val source = continuousPaneSource().replace("\r\n", "\n")

        assertTrue(
            source.contains(
                "pageCoordinatesByDisplayPage.remove(displayPage)\n" +
                    "                inkPagesByDisplayPage.remove(displayPage)"
            )
        )
    }

    @Test
    fun inkOverlayPlacement_isRequeuedWhenListItemsAreRePlaced() {
        val source = continuousPaneSource()

        // Both the LazyColumn item root (pageContent's outer Box) and LazyRow's wrapper.
        assertEquals(
            2,
            Regex("""\.onPlaced \{ inkOverlayPlacer\.requestPlacement\(\) \}""").findAll(source).count()
        )
        // The list (inside the zoom layer) supplies the pre-transform coordinate space.
        assertTrue(source.contains("inkOverlayPlacer.zoomLayerContentCoordinates = it"))
        assertTrue(source.contains("placeable.placeWithLayer(frame?.offset ?: continuousInkParkedOffset("))
    }

    @Test
    fun inkPlacer_mapsPagesThroughTheZoomLayerStatesNotTheLayerMatrix() {
        val source = continuousPaneSource()
        val placer = source.substring(source.indexOf("internal class ContinuousInkOverlayPlacer("))
            .substringBefore("internal class ContinuousInkOverlayFrameHolder")

        assertTrue(placer.contains("layerContent.localBoundingBoxOf(page, clipBounds = false)"))
        assertTrue(placer.contains("continuousZoomLayerRectToScreen("))
        assertFalse("host coordinates include the layer matrix, which can be a frame stale", placer.contains("host.localBoundingBoxOf"))
        // The mirrored transform must stay in sync with the graphicsLayer block it copies.
        assertTrue(source.contains("val overscrollScreenPx = -sharedMainAxisOverscroll * sharedZoom"))
        assertTrue(source.contains("translationX = sharedCrossPan\n                            translationY = overscrollScreenPx".replace("\n", lineSeparatorOf(source))))
    }

    @Test
    fun continuousZoomLayerRectToScreen_vertical_matchesHandComputedLayerTransform() {
        // Layer 1000x2000 -> pivot (500, 1000). zoom 2, crossPan 50, overscroll 10 -> ty = -20.
        val rect = continuousZoomLayerRectToScreen(
            left = 100f, top = 300f, right = 900f, bottom = 1300f,
            zoom = 2f, crossPan = 50f, mainAxisOverscroll = 10f,
            layerWidth = 1000f, layerHeight = 2000f,
            orientation = Orientation.Vertical
        )

        assertEquals(-250f, rect.left, 0.001f)   // (100-500)*2 + 500 + 50
        assertEquals(1350f, rect.right, 0.001f)  // (900-500)*2 + 500 + 50
        assertEquals(-420f, rect.top, 0.001f)    // (300-1000)*2 + 1000 - 20
        assertEquals(1580f, rect.bottom, 0.001f) // (1300-1000)*2 + 1000 - 20
    }

    @Test
    fun continuousZoomLayerRectToScreen_horizontal_swapsCrossPanAndOverscrollAxes() {
        val rect = continuousZoomLayerRectToScreen(
            left = 100f, top = 300f, right = 900f, bottom = 1300f,
            zoom = 2f, crossPan = 50f, mainAxisOverscroll = 10f,
            layerWidth = 1000f, layerHeight = 2000f,
            orientation = Orientation.Horizontal
        )

        assertEquals(-320f, rect.left, 0.001f)  // (100-500)*2 + 500 - 20
        assertEquals(1280f, rect.right, 0.001f)
        assertEquals(-350f, rect.top, 0.001f)   // (300-1000)*2 + 1000 + 50
        assertEquals(1650f, rect.bottom, 0.001f)
    }

    @Test
    fun continuousZoomLayerRectToScreen_isIdentityAtRest() {
        val rect = continuousZoomLayerRectToScreen(
            left = 12.5f, top = 40f, right = 812.5f, bottom = 1075f,
            zoom = 1f, crossPan = 0f, mainAxisOverscroll = 0f,
            layerWidth = 825f, layerHeight = 1200f,
            orientation = Orientation.Vertical
        )

        assertEquals(Rect(12.5f, 40f, 812.5f, 1075f), rect)
    }

    @Test
    fun continuousInkOverlayFrame_clipsToHost_andKeepsTheFractionInThePageRect() {
        val frame = continuousInkOverlayFrame(
            pageOnScreen = Rect(-250.4f, 100.6f, 1350f, 1580.25f),
            hostWidth = 1000,
            hostHeight = 2000
        )!!

        // Integer box: floor of the clipped left/top, ceil of the clipped right/bottom.
        assertEquals(IntOffset(0, 100), frame.offset)
        assertEquals(IntSize(1000, 1481), frame.size)
        // Sub-pixel page position lives in the rect, relative to the box.
        assertEquals(-250.4f, frame.pageRectInBox.left, 0.001f)
        assertEquals(0.6f, frame.pageRectInBox.top, 0.001f)
        assertEquals(1350f, frame.pageRectInBox.right, 0.001f)
        assertEquals(1480.25f, frame.pageRectInBox.bottom, 0.001f)
    }

    @Test
    fun continuousInkOverlayFrame_staysWithinHostAtMaxZoom_onA1752pxTablet() {
        // A 1752x2267 page at the pane's max zoom (20x) is ~35040x45340 px on screen, beyond
        // Compose's 32767 px constraint limit. The overlay must never be sized to that.
        val hostWidth = 1752
        val hostHeight = 2267
        val onScreen = continuousZoomLayerRectToScreen(
            left = 0f, top = 0f, right = 1752f, bottom = 2267f,
            zoom = 20f, crossPan = 0f, mainAxisOverscroll = 0f,
            layerWidth = hostWidth.toFloat(), layerHeight = hostHeight.toFloat(),
            orientation = Orientation.Vertical
        )
        assertTrue(onScreen.width > 32767f && onScreen.height > 32767f)

        val frame = continuousInkOverlayFrame(onScreen, hostWidth, hostHeight)!!

        assertTrue(frame.size.width <= hostWidth && frame.size.height <= hostHeight)
        assertEquals(IntOffset.Zero, frame.offset)
        assertEquals(onScreen.width, frame.pageRectInBox.width, 1f)
    }

    @Test
    fun continuousInkOverlayFrame_isNullWhenThePageIsOffScreenOrInvalid() {
        assertNull(continuousInkOverlayFrame(Rect(-900f, 0f, -10f, 500f), 1000, 2000))
        assertNull(continuousInkOverlayFrame(Rect(0f, 2000f, 1000f, 3000f), 1000, 2000))
        assertNull(continuousInkOverlayFrame(Rect(0f, 0f, 100f, 100f), 0, 2000))
        assertNull(continuousInkOverlayFrame(Rect(Float.NaN, 0f, 100f, 100f), 1000, 2000))
    }

    @Test
    fun continuousInkParkedOffset_keepsTheWholeOverlayOutsideTheContentBox() {
        val parked = continuousInkParkedOffset(width = 3000, height = 4000)

        assertTrue(parked.x + 3000 < 0)
        assertTrue(parked.y + 4000 < 0)
    }

    @Test
    fun pdfPageTransformForRect_mapsNormalizedPointsOntoTheGivenRect() {
        val transform = pdfPageTransformForRect(IntSize(1000, 1481), Rect(-250.4f, 0.6f, 1349.6f, 2000.6f))!!

        val (x, y) = transform.normalizedPageToView(0.5f, 0.25f)
        assertEquals(549.6f, x, 0.01f)  // -250.4 + 0.5 * 1600
        assertEquals(500.6f, y, 0.01f)  // 0.6 + 0.25 * 2000
        val (nx, ny) = transform.viewToNormalizedPage(549.6f, 500.6f)
        assertEquals(0.5f, nx, 0.0001f)
        assertEquals(0.25f, ny, 0.0001f)
        assertNull(pdfPageTransformForRect(IntSize(10, 10), Rect(0f, 0f, 0f, 10f)))
    }

    @Test
    fun pdfMarkupOverlay_defaultsKeepPagedBehavior_andContinuousPassesZoomScales() {
        val markup = markupUiSource()
        val pane = continuousPaneSource()

        // Widths keep scaling with the paged viewport zoom; the eraser reach stays a fixed 30 px
        // for paged callers (ReferencePdfPane, SheetViewerScreen).
        assertTrue(markup.contains("strokeWidthScale: Float = viewportState.zoom.coerceAtLeast(1f)"))
        assertTrue(markup.contains("eraserRadiusScale: Float = 1f"))
        assertTrue(markup.contains("pageRectInView: (() -> Rect?)? = null"))
        assertTrue(markup.contains("width = stroke.lineWidth * strokeWidthScale"))
        assertTrue(markup.contains("width = activeThickness * strokeWidthScale"))
        assertTrue(markup.contains("d < ERASER_HIT_RADIUS_PX * eraserRadiusScale"))
        assertFalse(markup.contains("ERASER_HIT_RADIUS_PX * strokeWidthScale"))

        assertTrue(pane.contains("strokeWidthScale = zoomScale"))
        assertTrue(pane.contains("eraserRadiusScale = zoomScale"))
        assertTrue(pane.contains("pageRectInView = { frameHolder.pageRectInBox }"))
    }

    private fun lineSeparatorOf(source: String): String = if (source.contains("\r\n")) "\r\n" else "\n"

    private fun markupUiSource(): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupUi.kt")
            if (candidate.exists()) return candidate.readText()
            val direct = File(dir, "src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupUi.kt")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Unable to locate PdfMarkupUi.kt from ${System.getProperty("user.dir")}")
    }
}
