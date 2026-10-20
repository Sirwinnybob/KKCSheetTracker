package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.*
import org.junit.Assert.*
import org.junit.Test

class DoorHingeInspectionTest {
    private val bottomCup = DoorShape.HingeCup(DoorPoint(0.8, 3.125), HINGE_CUP_DIAMETER_IN / 2)
    private val topCup = bottomCup.copy(center = DoorPoint(0.8, 26.75))
    private val drawing = DoorDrawing(20.0, 30.0, listOf(bottomCup, topCup), emptyList(), emptyList())

    @Test fun sideHingesMeasureThePublishedCenterFromBothVerticalEnds() {
        for (side in listOf(DoorHingeSide.LEFT, DoorHingeSide.RIGHT)) {
            assertEquals(listOf("26.875 in from Top", "3.125 in from Bottom"),
                hingeDistanceLines(drawing, side, bottomCup))
            assertEquals(listOf("3.25 in from Top", "26.75 in from Bottom"),
                hingeDistanceLines(drawing, side, topCup))
        }
    }

    @Test fun topHingesMeasureAcrossTheWidthRatherThanTheHeight() {
        val cup = bottomCup.copy(center = DoorPoint(4.25, 29.2))
        assertEquals(listOf("4.25 in from Left", "15.75 in from Right"),
            hingeDistanceLines(drawing, DoorHingeSide.TOP, cup))
        assertTrue(hingeDistanceLines(drawing, DoorHingeSide.OTHER, cup).isEmpty())
        assertTrue(hingeDistanceLines(drawing, DoorHingeSide.NONE, cup).isEmpty())
    }

    @Test fun tapsUseTheDrawnCupsWithAnExpandedTouchTargetAndNearestCupWins() {
        // 10 px/in; cup centers are (28,318.75) and (28,82.5).
        val fit = DoorPreviewFit(10f, 20f, 50f, 30.0)
        assertEquals(bottomCup, hitDoorHinge(drawing, fit, 240f, 400f, 48f, 318.75f, touchRadiusPx = 24f))
        assertEquals(topCup, hitDoorHinge(drawing, fit, 240f, 400f, 28f, 82.5f))
        assertNull(hitDoorHinge(drawing, fit, 240f, 400f, 60f, 318.75f, touchRadiusPx = 24f))
        val close = drawing.copy(shapes = listOf(bottomCup, bottomCup.copy(center = DoorPoint(0.8, 5.0))))
        assertEquals(close.shapes[1], hitDoorHinge(close, fit, 240f, 400f, 28f, 302f))
        assertNull(hitDoorHinge(drawing.copy(shapes = emptyList()), fit, 240f, 400f, 28f, 318.75f))
        assertNull(hitDoorHinge(drawing.copy(refused = true), fit, 240f, 400f, 28f, 318.75f))
    }

    @Test fun zoomAndPanApplyToBothTheCupCenterAndItsVisibleRadius() {
        val fit = DoorPreviewFit(10f, 20f, 50f, 30.0)
        // At 2x, pan (100,-50), bottom cup appears at (36,387.5).
        assertEquals(bottomCup, hitDoorHinge(drawing, fit, 240f, 400f, 36f, 387.5f,
            zoom = 2f, panX = 100f, panY = -50f))
        assertNull(hitDoorHinge(drawing, fit, 240f, 400f, 28f, 318.75f,
            zoom = 2f, panX = 100f, panY = -50f))
        // A large visible cup must remain tappable out to its edge, beyond the minimum target.
        assertEquals(topCup, hitDoorHinge(drawing, fit, 240f, 400f, 90f, 80f,
            zoom = 5f, panX = 400f, panY = 467.5f, touchRadiusPx = 24f))
    }

    @Test fun invalidOrClippedTapsNeverSelectAHinge() {
        val fit = DoorPreviewFit(10f, 20f, 50f, 30.0)
        assertNull(hitDoorHinge(drawing, fit, 240f, 400f, -1f, 318f))
        assertNull(hitDoorHinge(drawing, fit, 240f, 400f, Float.NaN, 318f))
        assertNull(hitDoorHinge(drawing, fit, 240f, 400f, 28f, 318f, zoom = 0f))
        assertNull(hitDoorHinge(drawing, fit.copy(offsetX = Float.NaN), 240f, 400f, 28f, 318f))
    }

    @Test fun cabinetHingeFitMatchesTheDoorRendererIncludingCenteredPlacement() {
        val front = CabinetFront("front", "door", DoorRect(2.0, 5.0, 24.0, 32.0))
        val cabinet = CabinetFit(10f, 10f, 20f, 40.0, 0.0)
        val fit = cabinetDoorFit(cabinet, front, drawing)
        // Front occupies x=30..270,y=50..370; 320px/30in sets the scale to 10 2/3.
        assertEquals(51.86667f, fit.x(0.8), 0.001f)
        assertEquals(336.66667f, fit.y(3.125), 0.001f)
        assertEquals(bottomCup, hitDoorHinge(drawing, fit, 400f, 500f, 51.86667f, 336.66667f))
    }
}
