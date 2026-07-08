package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.ui.markup.DrawingTool
import com.kkc.sheettracker.ui.markup.resolveEffectiveDrawingTool
import com.kkc.sheettracker.ui.markup.shouldAppendStrokePoint
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassicCutListInputTest {

    @Test
    fun `temporary eraser override switches pen to eraser`() {
        assertEquals(
            DrawingTool.ERASER,
            resolveEffectiveDrawingTool(
                selectedTool = DrawingTool.PEN,
                isTemporaryEraserActive = true
            )
        )
    }

    @Test
    fun `temporary eraser override switches highlighter to eraser`() {
        assertEquals(
            DrawingTool.ERASER,
            resolveEffectiveDrawingTool(
                selectedTool = DrawingTool.HIGHLIGHTER,
                isTemporaryEraserActive = true
            )
        )
    }

    @Test
    fun `manual eraser remains eraser when temporary override is inactive`() {
        assertEquals(
            DrawingTool.ERASER,
            resolveEffectiveDrawingTool(
                selectedTool = DrawingTool.ERASER,
                isTemporaryEraserActive = false
            )
        )
    }

    @Test
    fun `tool reverts to previous selection when temporary eraser is released`() {
        assertEquals(
            DrawingTool.HIGHLIGHTER,
            resolveEffectiveDrawingTool(
                selectedTool = DrawingTool.HIGHLIGHTER,
                isTemporaryEraserActive = false
            )
        )
    }

    @Test
    fun `classic width scale fits full table inside viewport`() {
        assertEquals(
            0.8f,
            calculateClassicViewFitWidthScale(
                viewportWidthPx = 800,
                contentWidthPx = 1000
            )
        )
    }

    @Test
    fun `classic width scale stays within zoom bounds`() {
        assertEquals(
            2.5f,
            calculateClassicViewFitWidthScale(
                viewportWidthPx = 6000,
                contentWidthPx = 1000
            )
        )
    }

    @Test
    fun `small pen movement now counts as a point for dots`() {
        assertEquals(
            true,
            shouldAppendStrokePoint(
                lastX = 0.10f,
                lastY = 0.10f,
                nextX = 0.1014f,
                nextY = 0.10f
            )
        )
    }

    @Test
    fun `finger tally taps stay enabled when stylus drawing is active`() {
        assertEquals(
            true,
            classicTallyActionsEnabled(
                activeTool = DrawingTool.PEN,
                allowFingerDrawing = false
            )
        )
    }

    @Test
    fun `finger tally taps are disabled when finger drawing is enabled`() {
        assertEquals(
            false,
            classicTallyActionsEnabled(
                activeTool = DrawingTool.HIGHLIGHTER,
                allowFingerDrawing = true
            )
        )
    }

    @Test
    fun `finger does not draw unless finger drawing is enabled`() {
        assertEquals(
            false,
            classicPointerCanDraw(
                pointerType = PointerType.Touch,
                allowFingerDrawing = false
            )
        )
    }

    @Test
    fun `stylus can still draw when finger drawing is disabled`() {
        assertEquals(
            true,
            classicPointerCanDraw(
                pointerType = PointerType.Stylus,
                allowFingerDrawing = false
            )
        )
    }

    @Test
    fun `classic bottom scroll padding clears floating app scaffold`() {
        assertEquals(200.dp, classicCutListBottomScrollPadding())
    }

    @Test
    fun `zoom compensation is zero at the table origin`() {
        val delta = computeClassicZoomScrollDelta(
            oldScale = 1f,
            newScale = 1.25f,
            centroidX = 0f,
            centroidY = 0f
        )
        assertEquals(0f, delta.first, 0.0001f)
        assertEquals(0f, delta.second, 0.0001f)
    }

    @Test
    fun `zoom compensation is zero when scale does not change`() {
        val delta = computeClassicZoomScrollDelta(
            oldScale = 1.25f,
            newScale = 1.25f,
            centroidX = 400f,
            centroidY = 200f
        )
        assertEquals(0f, delta.first, 0.0001f)
        assertEquals(0f, delta.second, 0.0001f)
    }

    @Test
    fun `zooming in shifts scroll forward proportional to distance from origin`() {
        val delta = computeClassicZoomScrollDelta(
            oldScale = 1f,
            newScale = 1.25f,
            centroidX = 400f,
            centroidY = 200f
        )
        assertEquals(100f, delta.first, 0.0001f)
        assertEquals(50f, delta.second, 0.0001f)
    }

    @Test
    fun `zooming out shifts scroll backward proportional to distance from origin`() {
        val delta = computeClassicZoomScrollDelta(
            oldScale = 1.25f,
            newScale = 1f,
            centroidX = 400f,
            centroidY = 200f
        )
        assertEquals(-80f, delta.first, 0.0001f)
        assertEquals(-40f, delta.second, 0.0001f)
    }
}
