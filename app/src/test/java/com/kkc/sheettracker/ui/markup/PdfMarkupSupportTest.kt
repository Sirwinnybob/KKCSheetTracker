package com.kkc.sheettracker.ui.markup

import android.view.MotionEvent
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfMarkupSupportTest {

    @Test
    fun `temporary eraser overrides pen but not manual eraser`() {
        assertEquals(DrawingTool.ERASER, resolveEffectiveDrawingTool(DrawingTool.PEN, true))
        assertEquals(DrawingTool.ERASER, resolveEffectiveDrawingTool(DrawingTool.ERASER, true))
        assertEquals(DrawingTool.HIGHLIGHTER, resolveEffectiveDrawingTool(DrawingTool.HIGHLIGHTER, false))
    }

    @Test
    fun `small pen movement counts with reduced threshold`() {
        assertTrue(
            shouldAppendStrokePoint(
                lastX = 0.10f,
                lastY = 0.10f,
                nextX = 0.1014f,
                nextY = 0.10f
            )
        )
        assertFalse(
            shouldAppendStrokePoint(
                lastX = 0.10f,
                lastY = 0.10f,
                nextX = 0.1004f,
                nextY = 0.10f
            )
        )
    }

    @Test
    fun `fitted page transform centers page and maps points round trip`() {
        val transform = computePdfPageTransform(
            viewSize = IntSize(1200, 800),
            pageAspectRatio = 0.6f,
            zoom = 1f,
            panX = 0f,
            panY = 0f
        )

        assertEquals(360f, transform.pageLeft)
        assertEquals(840f, transform.pageRight)

        val normalized = transform.viewToNormalizedPage(600f, 400f)
        assertEquals(0.5f, normalized.first, 0.0001f)
        assertEquals(0.5f, normalized.second, 0.0001f)

        val roundTrip = transform.normalizedPageToView(0.5f, 0.5f)
        assertEquals(600f, roundTrip.first, 0.0001f)
        assertEquals(400f, roundTrip.second, 0.0001f)
    }

    @Test
    fun `view outside page clamps to page edges`() {
        val transform = computePdfPageTransform(
            viewSize = IntSize(1200, 800),
            pageAspectRatio = 0.6f,
            zoom = 1f,
            panX = 0f,
            panY = 0f
        )

        val left = transform.viewToNormalizedPage(0f, 400f)
        val right = transform.viewToNormalizedPage(1200f, 400f)

        assertEquals(0f, left.first, 0.0001f)
        assertEquals(1f, right.first, 0.0001f)
    }

    @Test
    fun `distance to scaled stroke measures nearest segment`() {
        val distance = distanceToScaledStroke(
            px = 52f,
            py = 50f,
            points = listOf(0.1f, 0.5f, 0.9f, 0.5f),
            canvasWidth = 100f,
            canvasHeight = 100f
        )

        assertEquals(0f, distance, 0.0001f)
    }

    @Test
    fun `distance to view stroke respects zoom and pan transformed position`() {
        val transform = computePdfPageTransform(
            viewSize = IntSize(1200, 800),
            pageAspectRatio = 0.6f,
            zoom = 2f,
            panX = 100f,
            panY = -40f
        )

        val (x, y) = transform.normalizedPageToView(0.5f, 0.5f)
        val distance = distanceToViewStroke(
            px = x,
            py = y,
            points = listOf(0.1f, 0.5f, 0.9f, 0.5f),
            transform = transform
        )

        assertEquals(0f, distance, 0.0001f)
    }

    @Test
    fun `relevant motion event pointer index prefers stylus pointer over finger pointer`() {
        val index = findRelevantPointerIndex(
            pointerCount = 2,
            actionIndex = 0,
            toolTypeAt = { pointerIndex ->
                if (pointerIndex == 1) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
            }
        )

        assertEquals(1, index)
    }

    @Test
    fun `single point stroke expands into tiny committed mark`() {
        val finalized = finalizeStrokePoints(
            points = listOf(0.25f, 0.50f),
            activeThickness = 8f,
            canvasWidth = 1000f,
            canvasHeight = 2000f
        )

        assertEquals(4, finalized.size)
        assertEquals(0.25f, finalized[0], 0.0001f)
        assertEquals(0.50f, finalized[1], 0.0001f)
        assertTrue(finalized[2] > finalized[0])
        assertTrue(finalized[3] > finalized[1])
    }
}
