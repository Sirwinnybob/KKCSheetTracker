package com.kkc.sheettracker.ui.markup

import com.kkc.sheettracker.data.models.PdfInkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfMarkupOverlayLogicTest {

    @Test
    fun strokePathCache_hitsDespiteFloat32WobbleInTheDerivedPageSize() {
        val strokes = ArrayList<PdfInkStroke>()
        // Width derived from right - left of a panning rect: 35040.0 one frame, 35039.996 the next.
        val left = -1234.567f
        val width = (left + 35040f) - left

        assertTrue(isStrokePathCacheHit(strokes, 35040f, 45340f, strokes, width, 45340.004f))
        assertTrue(isStrokePathCacheHit(strokes, 800f, 1000f, strokes, 800.009f, 999.991f))
    }

    @Test
    fun strokePathCache_missesOnARealSizeChangeOrANewStrokeList() {
        val strokes = ArrayList<PdfInkStroke>()

        assertFalse(isStrokePathCacheHit(strokes, 800f, 1000f, strokes, 800.5f, 1000f))
        assertFalse(isStrokePathCacheHit(strokes, 800f, 1000f, strokes, 800f, 1000.02f))
        assertFalse(isStrokePathCacheHit(strokes, 800f, 1000f, ArrayList(), 800f, 1000f))
        assertFalse(isStrokePathCacheHit(null, 800f, 1000f, strokes, 800f, 1000f))
        // Fresh cache (NaN size) never hits.
        assertFalse(isStrokePathCacheHit(strokes, Float.NaN, Float.NaN, strokes, 800f, 1000f))
    }

    @Test
    fun gestureTransform_prefersTheLiveTransform() {
        assertEquals("live", resolveMarkupGestureTransform("live", "last", gestureInProgress = true))
        assertEquals("live", resolveMarkupGestureTransform("live", null, gestureInProgress = false))
    }

    @Test
    fun gestureTransform_fallsBackToTheGesturesLastTransformOnlyMidGesture() {
        // Page scrolled out of view mid-stroke: keep mapping (and commit on UP) with the last one.
        assertEquals("last", resolveMarkupGestureTransform<String>(null, "last", gestureInProgress = true))
        // No gesture: a page with no rect takes no input.
        assertNull(resolveMarkupGestureTransform<String>(null, "last", gestureInProgress = false))
        assertNull(resolveMarkupGestureTransform<String>(null, null, gestureInProgress = true))
    }

    @Test
    fun overlayHandler_alwaysClosesTheGestureWithoutATransform_andDropsItOnCancel() {
        val source = markupUiSource()
        val nullBranch = source.substringAfter("if (transform == null) {").substringBefore("change.consume()")

        assertTrue(nullBranch.contains("endGesture()"))
        val endGesture = source.substringAfter("fun endGesture() {").substringBefore("}")
        assertTrue(endGesture.contains("pointerTracker.reset()"))
        assertTrue(endGesture.contains("isDrawing = false"))
        assertTrue(endGesture.contains("currentPoints.clear()"))
        assertTrue(endGesture.contains("gestureTransformMemory.transform = null"))

        val cancel = source.substringAfter("onCancel = {").substringBefore("}")
        assertTrue("A Compose cancel must drop the stroke, never commit it.", cancel.contains("endGesture()"))
        assertFalse(cancel.contains("onStrokeAdded"))
    }

    @Test
    fun overlayInput_isPerPointerComposeInput_sharedWithSiblings() {
        val source = markupUiSource()

        assertFalse(
            "pointerInteropFilter gates the whole stream on the first ACTION_DOWN: a refused palm hides the pen.",
            source.contains(".pointerInteropFilter")
        )
        assertTrue(source.contains("MarkupPointerInputElement("))
        // Paged viewers draw the overlay as a sibling above the zoomable page; that page must keep
        // getting fingers to scroll and zoom.
        assertTrue(source.contains("override fun sharePointerInputWithSiblings(): Boolean = true"))
        assertTrue(source.contains("if (pass == PointerEventPass.Main) onEvent(pointerEvent)"))
    }

    // ── MarkupPointerTracker ──────────────────────────────────────────────

    private fun down(id: Long, stylus: Boolean) = MarkupPointer(id, stylus, pressed = true, previousPressed = false)
    private fun held(id: Long, stylus: Boolean) = MarkupPointer(id, stylus, pressed = true, previousPressed = true)
    private fun up(id: Long, stylus: Boolean) = MarkupPointer(id, stylus, pressed = false, previousPressed = true)

    @Test
    fun tracker_palmFirstThenPen_firstPenStrokeStartsAndCommits() {
        val tracker = MarkupPointerTracker()
        val palm = 1L
        val pen = 2L

        assertEquals(MarkupPointerAction.None, tracker.next(listOf(down(palm, false)), allowFingerDrawing = false))
        assertEquals(MarkupPointerAction.None, tracker.next(listOf(held(palm, false)), allowFingerDrawing = false))
        assertEquals(
            MarkupPointerAction.Start(pen),
            tracker.next(listOf(held(palm, false), down(pen, true)), allowFingerDrawing = false)
        )
        assertEquals(
            MarkupPointerAction.Continue(pen),
            tracker.next(listOf(held(palm, false), held(pen, true)), allowFingerDrawing = false)
        )
        // Palm rejection lifts (cancels) the palm mid-stroke: the pen stroke carries on.
        assertEquals(
            MarkupPointerAction.Continue(pen),
            tracker.next(listOf(up(palm, false), held(pen, true)), allowFingerDrawing = false)
        )
        assertEquals(MarkupPointerAction.Finish(pen), tracker.next(listOf(up(pen, true)), allowFingerDrawing = false))
        assertFalse(tracker.isTracking)
    }

    @Test
    fun tracker_penLiftsWhilePalmStays_commitsAndNextPenStrokeStarts() {
        val tracker = MarkupPointerTracker()
        tracker.next(listOf(down(1, false)), allowFingerDrawing = false)
        tracker.next(listOf(held(1, false), down(2, true)), allowFingerDrawing = false)

        assertEquals(
            MarkupPointerAction.Finish(2),
            tracker.next(listOf(held(1, false), up(2, true)), allowFingerDrawing = false)
        )
        assertEquals(MarkupPointerAction.None, tracker.next(listOf(held(1, false)), allowFingerDrawing = false))
        assertEquals(
            MarkupPointerAction.Start(3),
            tracker.next(listOf(held(1, false), down(3, true)), allowFingerDrawing = false)
        )
    }

    @Test
    fun tracker_fingersNeverMarkWithFingerDrawingOff() {
        val tracker = MarkupPointerTracker()

        assertEquals(MarkupPointerAction.None, tracker.next(listOf(down(1, false)), allowFingerDrawing = false))
        assertEquals(
            MarkupPointerAction.None,
            tracker.next(listOf(held(1, false), down(2, false)), allowFingerDrawing = false)
        )
        assertEquals(
            MarkupPointerAction.None,
            tracker.next(listOf(up(1, false), up(2, false)), allowFingerDrawing = false)
        )
        assertFalse(tracker.isTracking)
    }

    @Test
    fun tracker_fingerDrawing_tracksTheFirstFingerOnly() {
        val tracker = MarkupPointerTracker()

        assertEquals(MarkupPointerAction.Start(1), tracker.next(listOf(down(1, false)), allowFingerDrawing = true))
        assertEquals(
            MarkupPointerAction.Continue(1),
            tracker.next(listOf(held(1, false), down(2, false)), allowFingerDrawing = true)
        )
        assertEquals(
            MarkupPointerAction.Finish(1),
            tracker.next(listOf(up(1, false), held(2, false)), allowFingerDrawing = true)
        )
        // The second finger went down during the first finger's stroke; it doesn't start a new one.
        assertEquals(MarkupPointerAction.None, tracker.next(listOf(held(2, false)), allowFingerDrawing = true))
    }

    @Test
    fun tracker_penTakesOverAFingerStroke_butNotAnotherPenStroke() {
        val tracker = MarkupPointerTracker()
        tracker.next(listOf(down(1, false)), allowFingerDrawing = true)

        assertEquals(
            MarkupPointerAction.Start(2),
            tracker.next(listOf(held(1, false), down(2, true)), allowFingerDrawing = true)
        )
        assertEquals(
            MarkupPointerAction.Continue(2),
            tracker.next(listOf(held(1, false), held(2, true), down(3, true)), allowFingerDrawing = true)
        )
    }

    @Test
    fun tracker_penDownPrefersThePenOverAFingerInTheSameEvent() {
        val tracker = MarkupPointerTracker()

        assertEquals(
            MarkupPointerAction.Start(2),
            tracker.next(listOf(down(1, false), down(2, true)), allowFingerDrawing = true)
        )
    }

    @Test
    fun tracker_trackedPointerMissingFromTheEvent_abandonsTheStroke() {
        val tracker = MarkupPointerTracker()
        tracker.next(listOf(down(2, true)), allowFingerDrawing = false)

        assertEquals(MarkupPointerAction.Abandon, tracker.next(listOf(held(1, false)), allowFingerDrawing = false))
        assertFalse(tracker.isTracking)
    }

    @Test
    fun tracker_resetForgetsTheStroke() {
        val tracker = MarkupPointerTracker()
        tracker.next(listOf(down(2, true)), allowFingerDrawing = false)
        tracker.reset()

        assertEquals(MarkupPointerAction.None, tracker.next(listOf(held(2, true)), allowFingerDrawing = false))
    }

    @Test
    fun stylusEraserButtonState_matchesPenSideButtons() {
        assertTrue(isStylusEraserButtonState(android.view.MotionEvent.BUTTON_STYLUS_PRIMARY))
        assertTrue(isStylusEraserButtonState(android.view.MotionEvent.BUTTON_STYLUS_SECONDARY))
        assertTrue(isStylusEraserButtonState(android.view.MotionEvent.BUTTON_SECONDARY))
        assertFalse(isStylusEraserButtonState(0))
        assertFalse(isStylusEraserButtonState(android.view.MotionEvent.BUTTON_PRIMARY))
    }

    private fun markupUiSource(): String {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = java.io.File(dir, "app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupUi.kt")
            if (candidate.exists()) return candidate.readText()
            val direct = java.io.File(dir, "src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupUi.kt")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Unable to locate PdfMarkupUi.kt from ${System.getProperty("user.dir")}")
    }
}
