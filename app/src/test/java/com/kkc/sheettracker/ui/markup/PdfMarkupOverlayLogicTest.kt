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
    fun overlayHandler_alwaysClosesTheGestureOnUpOrCancel_evenWithoutATransform() {
        val source = markupUiSource()
        val nullBranch = source.substringAfter("if (transform == null) {").substringBefore("if (isGestureEnd) {")

        assertTrue(nullBranch.contains("if (!isGestureEnd) return@pointerInteropFilter false"))
        assertTrue(nullBranch.contains("isHandlingGesture = false"))
        assertTrue(nullBranch.contains("isDrawing = false"))
        assertTrue(nullBranch.contains("currentPoints.clear()"))
        assertTrue(nullBranch.contains("gestureTransformMemory.transform = null"))
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
