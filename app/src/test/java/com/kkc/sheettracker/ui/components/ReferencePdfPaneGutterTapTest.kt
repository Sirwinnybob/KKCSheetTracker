package com.kkc.sheettracker.ui.components

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePdfPaneGutterTapTest {

    @Test
    fun classifySideGutterTap_detectsLeftAndRightGutters() {
        val size = IntSize(width = 1200, height = 800)
        val aspect = 0.6f // fit width = 480, gutters on both sides

        assertEquals(SideGutterTapRegion.LEFT, classifySideGutterTap(100f, size, aspect))
        assertEquals(SideGutterTapRegion.RIGHT, classifySideGutterTap(1100f, size, aspect))
    }

    @Test
    fun classifySideGutterTap_returnsNoneInsideSheetArea() {
        val size = IntSize(width = 1200, height = 800)
        val aspect = 0.6f

        assertEquals(SideGutterTapRegion.NONE, classifySideGutterTap(600f, size, aspect))
    }

    @Test
    fun classifySideGutterTap_returnsNoneWhenNoSideGuttersExist() {
        val size = IntSize(width = 1200, height = 800)
        val aspect = 2.0f // fit width fills viewport width

        assertEquals(SideGutterTapRegion.NONE, classifySideGutterTap(40f, size, aspect))
        assertEquals(SideGutterTapRegion.NONE, classifySideGutterTap(1160f, size, aspect))
    }

    @Test
    fun isFitStateForSideGutterNavigation_requiresZoomOutAndCenteredPan() {
        assertTrue(
            isFitStateForSideGutterNavigation(
                zoom = 1.01f,
                panX = 10f,
                panY = -8f,
                panTolerancePx = 24f
            )
        )
        assertFalse(
            isFitStateForSideGutterNavigation(
                zoom = 1.3f,
                panX = 0f,
                panY = 0f,
                panTolerancePx = 24f
            )
        )
        assertFalse(
            isFitStateForSideGutterNavigation(
                zoom = 1.0f,
                panX = 30f,
                panY = 0f,
                panTolerancePx = 24f
            )
        )
    }
}
