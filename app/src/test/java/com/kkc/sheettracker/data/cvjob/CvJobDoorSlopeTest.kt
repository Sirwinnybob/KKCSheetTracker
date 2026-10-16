package com.kkc.sheettracker.data.cvjob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.sqrt

/**
 * Sizes are job 690, door 1A (a sloped base cabinet): 23.875 x 24.5689, stiles 24.6314 / 8.5186 cut,
 * top rail 26.8384, bottom rail 20.375, flat panel 20.311 x 19.2368, allowances EDX 0.03125,
 * stile EDY 0.0625, rail EDY 1.0 (tenon 0.5). Part ids are synthetic.
 */
class CvJobDoorSlopeTest {
    private val width = 23.875
    private val high = 24.5689
    private val low = 8.5186 - 0.0625
    private val stile = 2.25
    private val slope = (high - low) / (width - stile)

    private fun frame(allowance: Double? = 0.0625) =
        DoorFrame(stile, stile, stile, stile, 0.5, "3/4 Birch", allowance)

    private fun panel(insetX: Double? = 0.468, insetY: Double? = 0.468) =
        DoorPanel("flat", insetX, insetY, DoorRect(2.25, 2.25, 19.375, 20.0689), "Birch ply")

    private fun geometry(
        frame: DoorFrame? = frame(),
        panel: DoorPanel? = panel(),
        height: Double = high,
        construction: DoorConstruction = DoorConstruction.FIVE_PIECE
    ) = DoorGeometry(
        doorId = "53751", construction = construction, width = width, height = height, thickness = 0.75,
        frame = frame, panel = panel,
        hinges = DoorHinges(DoorHingeSide.NONE, "", 0, emptyList(), null, emptyList()),
        checks = emptyList()
    )

    private fun part(role: String, w: String, l: String) = DoorPart("p-$role", role, role, "Birch", w, l)

    private fun parts(
        ls: String = "24.6314", rs: String = "8.5186", tr: String = "26.8384",
        br: String = "20.375", panelW: String = "20.311", panelL: String = "19.2368"
    ) = listOf(
        part("leftStile", "2.2813", ls), part("rightStile", "2.2813", rs),
        part("topRail", "2.2813", tr), part("bottomRail", "2.2813", br), part("panel", panelW, panelL)
    )

    private fun verified(result: DoorSlope) = result as DoorSlope.Verified

    @Test
    fun door1AVerifiesAndTheOutlineIsTheTrapezoid() {
        val v = verified(deriveDoorSlope(geometry(), parts()))
        assertTrue(v.highOnLeft)
        assertEquals(Math.toDegrees(atan(slope)), v.slopeDegrees, 1e-9)
        assertEquals(36.6897, v.slopeDegrees, 0.0005)

        val lowOuter = high - slope * width
        assertEquals(
            listOf(DoorPoint(0.0, 0.0), DoorPoint(width, 0.0), DoorPoint(width, lowOuter), DoorPoint(0.0, high)),
            v.outline
        )
        // The published right stile length is the long point at its inner edge.
        val rightStile = v.members.single { it.role == "rightStile" }.polygon
        assertEquals(low, rightStile.maxOf { it.y }, 1e-6)
        assertEquals(DoorPoint(width - stile, low), rightStile.last())
    }

    @Test
    fun theFiveMembersAreTheStilesRailsAndTheVisiblePanel() {
        val v = verified(deriveDoorSlope(geometry(), parts()))
        assertEquals(listOf("leftStile", "rightStile", "topRail", "bottomRail", "panel"), v.members.map { it.role })
        assertEquals(parts().map { it.partId }, v.members.map { it.partId })

        val topRail = v.members.single { it.role == "topRail" }.polygon
        val thickness = stile * sqrt(1 + slope * slope)
        // Vertical ends: the rail's upper edge is the door's top line between the stiles.
        assertEquals(listOf(stile, width - stile), listOf(topRail[3].x, topRail[2].x))
        assertEquals(high - slope * stile, topRail[3].y, 1e-9)
        assertEquals(high - slope * stile - thickness, topRail[0].y, 1e-9)

        val bottomRail = v.members.single { it.role == "bottomRail" }.polygon
        assertEquals(listOf(stile, width - stile), listOf(bottomRail[0].x, bottomRail[1].x))
        assertEquals(stile, bottomRail[2].y, 1e-9)

        val opening = v.members.single { it.role == "panel" }.polygon
        assertEquals(stile, opening[0].y, 1e-9)
        assertEquals(topRail[0].y, opening[3].y, 1e-9)
    }

    @Test
    fun theMirrorImageDoorHasItsHighSideOnTheRight() {
        val v = verified(deriveDoorSlope(geometry(), parts(ls = "8.5186", rs = "24.6314")))
        assertEquals(false, v.highOnLeft)
        val lowOuter = high - slope * width
        assertEquals(
            listOf(DoorPoint(0.0, 0.0), DoorPoint(width, 0.0), DoorPoint(width, high), DoorPoint(0.0, lowOuter)),
            v.outline
        )
        val leftStile = v.members.single { it.role == "leftStile" }.polygon
        assertEquals(low, leftStile.maxOf { it.y }, 1e-6)
    }

    @Test
    fun equalStilesMeanAnOrdinaryDoor() {
        assertEquals(DoorSlope.None, deriveDoorSlope(geometry(), parts(ls = "24.6314", rs = "24.6314")))
        assertEquals(DoorSlope.None, deriveDoorSlope(geometry(), emptyList()))
    }

    @Test
    fun doorsThatAreNotFivePieceFramesAreLeftAlone() {
        assertEquals(DoorSlope.None, deriveDoorSlope(geometry(construction = DoorConstruction.SLAB), parts()))
        assertEquals(DoorSlope.None, deriveDoorSlope(geometry(construction = DoorConstruction.OTHER), parts()))
        assertEquals(DoorSlope.None, deriveDoorSlope(geometry(frame = null), parts()))
    }

    private fun assertRefused(result: DoorSlope, mention: String) {
        assertTrue("expected Refused, got $result", result is DoorSlope.Refused)
        val reason = (result as DoorSlope.Refused).reason
        assertTrue("'$reason' should mention '$mention'", reason.contains(mention, ignoreCase = true))
    }

    @Test
    fun aTopRailThatDoesNotReproduceIsRefused() {
        assertRefused(deriveDoorSlope(geometry(), parts(tr = "26.8484")), "top rail")
        assertRefused(deriveDoorSlope(geometry(), parts(tr = "26.8284")), "top rail")
    }

    @Test
    fun aBottomRailThatDoesNotMatchTheSpanIsRefused() {
        assertRefused(deriveDoorSlope(geometry(), parts(br = "20.385")), "bottom rail")
    }

    @Test
    fun aPanelThatDoesNotReproduceIsRefused() {
        assertRefused(deriveDoorSlope(geometry(), parts(panelL = "19.2468")), "panel")
        assertRefused(deriveDoorSlope(geometry(), parts(panelW = "20.321")), "panel")
        assertRefused(deriveDoorSlope(geometry(panel = panel(insetX = null)), parts()), "panel")
        assertRefused(deriveDoorSlope(geometry(panel = null), parts()), "panel")
    }

    @Test
    fun aTallStileThatIsNotTheDoorHeightIsRefused() {
        assertRefused(deriveDoorSlope(geometry(height = 24.5), parts()), "height")
    }

    @Test
    fun missingDataOnAClearlyShapedDoorIsRefusedNotDrawnAsARectangle() {
        assertRefused(deriveDoorSlope(geometry(frame = frame(allowance = null)), parts()), "allowance")
        assertRefused(deriveDoorSlope(geometry(), parts().filterNot { it.role == "topRail" }), "top rail")
        assertRefused(deriveDoorSlope(geometry(), parts(tr = "n/a")), "top rail")
        assertRefused(deriveDoorSlope(geometry(), parts().filterNot { it.role == "panel" }), "panel")
    }

    @Test
    fun aLowStileThatWouldReachZeroOrLessIsRefused() {
        // Very steep: the top line crosses the floor before the door's far edge.
        assertRefused(deriveDoorSlope(geometry(), parts(rs = "0.5")), "close")
    }

    @Test
    fun noDoorInTheJob688FixtureBecomesSlopedOrRefused() {
        val text = requireNotNull(javaClass.classLoader?.getResource("cvjob/job_data_688_doors.json")).readText()
        val loaded = parseCvJobDoorPreviews(text) as CvJobDoorPreviews.Loaded
        assertTrue(loaded.doors.isNotEmpty())
        for (door in loaded.doors) {
            assertEquals("door ${door.doorId}", DoorSlope.None, door.geometry.slope)
        }
    }

    @Test
    fun theSlopedDoorFixtureParsesAndVerifies() {
        val text = requireNotNull(javaClass.classLoader?.getResource("cvjob/job_data_690_sloped_door.json")).readText()
        val loaded = parseCvJobDoorPreviews(text) as CvJobDoorPreviews.Loaded
        val door = loaded.forDoor("53751")!!
        assertEquals(0.0625, door.geometry.frame!!.stileLengthAllowance!!, 0.0)
        val v = verified(door.geometry.slope)
        assertTrue(v.highOnLeft)
        assertEquals(36.6897, v.slopeDegrees, 0.0005)
    }
}
