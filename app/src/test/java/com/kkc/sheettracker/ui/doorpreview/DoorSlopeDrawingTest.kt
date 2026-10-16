package com.kkc.sheettracker.ui.doorpreview

import com.google.gson.JsonParser
import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.DoorPoint
import com.kkc.sheettracker.data.cvjob.DoorPreviewData
import com.kkc.sheettracker.data.cvjob.parseCvJobDoorPreviews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorSlopeDrawingTest {
    private val resource = "cvjob/job_data_690_sloped_door.json"

    private fun json() = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource(resource)).readText()
    ).asJsonObject

    private fun door(text: String = json().toString()): DoorPreviewData =
        (parseCvJobDoorPreviews(text) as CvJobDoorPreviews.Loaded).doors.single()

    private fun DoorDrawing.polys(role: DoorShapeRole) =
        shapes.filterIsInstance<DoorShape.Poly>().filter { it.role == role }

    private fun refusedJson() = json().also {
        // top rail 0.01 too long: the slope no longer reproduces
        it.getAsJsonArray("parts")[2].asJsonObject.addProperty("length", "26.8484")
    }.toString()

    @Test
    fun aVerifiedSlopedDoorDrawsPolygonsAndAPolygonOutline() {
        val d = buildDoorDrawing(door().geometry)
        assertFalse(d.refused)
        assertEquals(2, d.polys(DoorShapeRole.STILE).size)
        assertEquals(2, d.polys(DoorShapeRole.RAIL).size)
        assertEquals(1, d.polys(DoorShapeRole.PANEL).size)
        assertEquals(1, d.polys(DoorShapeRole.OUTLINE).size)
        // No rectangle may stand in for any piece of a door that is not rectangular.
        assertTrue(d.shapes.none { it is DoorShape.Rect })
        val outline = requireNotNull(d.outline)
        assertEquals(4, outline.size)
        assertEquals(24.5689, outline.maxOf { it.y }, 1e-9)
        assertEquals(6.7792, outline.filter { it.x == 23.875 }.maxOf { it.y }, 0.001)
    }

    @Test
    fun theTwoFalseExporterWarningsAreDroppedOnlyWhenTheDoorVerified() {
        val verified = buildDoorDrawing(door().geometry)
        assertTrue(verified.warnings.isEmpty())
        assertTrue(verified.notes.any { it.startsWith("Angled door") && it.contains("36.69") })

        val extra = json().also { it.getAsJsonArray("doorGeometry")[0].asJsonObject
            .getAsJsonArray("checks").add("Hinge UCS error: something else") }.toString()
        assertEquals(listOf("Hinge UCS error: something else"), buildDoorDrawing(door(extra).geometry).warnings)

        val refused = buildDoorDrawing(door(refusedJson()).geometry)
        assertEquals(2, refused.warnings.size)
    }

    @Test
    fun aRefusedDoorDrawsNothingAndSaysWhy() {
        val d = buildDoorDrawing(door(refusedJson()).geometry)
        assertTrue(d.refused)
        assertTrue(d.shapes.isEmpty())
        assertNull(d.outline)
        assertEquals(23.875, d.width, 0.0)
        assertEquals(24.5689, d.height, 0.0)
        assertTrue(d.notes.single().startsWith("Angled door: not drawn"))
        assertTrue(d.notes.single().contains("top rail"))
        assertTrue(buildDoorPartRegions(door(refusedJson()), d).isEmpty())
    }

    @Test
    fun anOrdinaryDoorStillDrawsRectangles() {
        val text = requireNotNull(javaClass.classLoader?.getResource("cvjob/job_data_688_doors.json")).readText()
        val first = (parseCvJobDoorPreviews(text) as CvJobDoorPreviews.Loaded).doors.first { it.geometry.frame != null }
        val d = buildDoorDrawing(first.geometry)
        assertFalse(d.refused)
        assertNull(d.outline)
        assertTrue(d.shapes.none { it is DoorShape.Poly })
        assertTrue(d.shapes.any { it is DoorShape.Rect })
    }

    @Test
    fun partRegionsCarryPolygonsAndHitTestingUsesThem() {
        val door = door()
        val drawing = buildDoorDrawing(door.geometry)
        val regions = buildDoorPartRegions(door, drawing)
        assertEquals(listOf("leftStile", "rightStile", "topRail", "bottomRail", "panel"), regions.map { it.part.role })
        assertTrue(regions.all { it.polygon != null })

        val fit = DoorPreviewFit.of(drawing.width, drawing.height, 400f, 300f, 0f)
        fun hit(x: Double, y: Double) =
            hitDoorPart(regions, fit, 400f, 300f, fit.x(x), fit.y(y))?.part?.role

        val topRail = regions.single { it.part.role == "topRail" }.polygon!!
        val cx = topRail.map { it.x }.average()
        val cy = topRail.map { it.y }.average()
        assertEquals("topRail", hit(cx, cy))
        assertEquals("leftStile", hit(1.0, 5.0))
        assertEquals("rightStile", hit(22.8, 3.0))
        assertEquals("bottomRail", hit(10.0, 1.0))
        assertEquals("panel", hit(10.0, 8.0))
        // The bounding box of a trapezoid includes air above the low side: nothing is there.
        assertNull(hit(22.5, 20.0))
        assertNull(hit(12.0, 22.0))
    }

    @Test
    fun slopedPiecesShowOnlyTheirCutSize() {
        val door = door()
        val regions = buildDoorPartRegions(door, buildDoorDrawing(door.geometry))
        for (region in regions) {
            val lines = doorPartSizeLines(region)
            assertEquals(region.part.role, 1, lines.size)
            assertTrue(lines.single().startsWith("Cut size"))
        }
        assertNotNull(regions.first().polygon)
    }

    @Test
    fun polygonContainmentMatchesEdgeCases() {
        val square = listOf(DoorPoint(0.0, 0.0), DoorPoint(2.0, 0.0), DoorPoint(2.0, 2.0), DoorPoint(0.0, 2.0))
        assertTrue(pointInDoorPolygon(1.0, 1.0, square))
        assertFalse(pointInDoorPolygon(3.0, 1.0, square))
        assertFalse(pointInDoorPolygon(1.0, -0.1, square))
    }
}
