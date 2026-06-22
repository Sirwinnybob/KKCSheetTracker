package com.kkc.sheettracker.ui.components

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePdfPaneZoomPanTest {

    private val minZoom = 1f
    private val maxZoom = 14f

    /**
     * Regression test for the "Cannot round NaN value" crash on the terminal
     * frame of a pinch/pan gesture. When the last pointer lifts,
     * [androidx.compose.foundation.gestures.calculateCentroid] returns
     * [Offset.Unspecified] (NaN, NaN). The anchor-compensation term must not
     * propagate that NaN into the pan offsets (NaN * 0f == NaN).
     */
    @Test
    fun computeZoomPan_unspecifiedCentroid_doesNotProduceNaN() {
        val result = computeZoomPan(
            zoom = 2f,
            panX = 40f,
            panY = -25f,
            zoomChange = 1f,
            panChange = Offset.Zero,
            centroid = Offset.Unspecified,
            viewWidth = 1200,
            viewHeight = 800,
            minZoom = minZoom,
            maxZoom = maxZoom
        )

        assertFalse("panX must be finite", result.panX.isNaN())
        assertFalse("panY must be finite", result.panY.isNaN())
        // No real transform occurred, so pan should be unchanged.
        assertEquals(40f, result.panX, 0.001f)
        assertEquals(-25f, result.panY, 0.001f)
        assertEquals(2f, result.zoom, 0.001f)
    }

    @Test
    fun computeZoomPan_anchorsZoomUnderCentroid() {
        // Pinch-zoom in around a centroid offset from the view center.
        val result = computeZoomPan(
            zoom = 1f,
            panX = 0f,
            panY = 0f,
            zoomChange = 2f,
            panChange = Offset.Zero,
            centroid = Offset(900f, 400f), // right of center (center = 600,400)
            viewWidth = 1200,
            viewHeight = 800,
            minZoom = minZoom,
            maxZoom = maxZoom
        )

        assertEquals(2f, result.zoom, 0.001f)
        // anchorX = 900 - 600 = 300; pan = 300 * (1 - 2) = -300
        assertEquals(-300f, result.panX, 0.001f)
        assertEquals(0f, result.panY, 0.001f)
        assertTrue(result.panX.isFinite())
    }

    @Test
    fun computeZoomPan_clampsZoomToBounds() {
        val result = computeZoomPan(
            zoom = 10f,
            panX = 0f,
            panY = 0f,
            zoomChange = 5f, // 10 * 5 = 50, clamps to maxZoom
            panChange = Offset.Zero,
            centroid = Offset(600f, 400f),
            viewWidth = 1200,
            viewHeight = 800,
            minZoom = minZoom,
            maxZoom = maxZoom
        )

        assertEquals(maxZoom, result.zoom, 0.001f)
    }
}
