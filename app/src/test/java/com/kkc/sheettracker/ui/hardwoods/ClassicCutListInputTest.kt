package com.kkc.sheettracker.ui.hardwoods

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
}
