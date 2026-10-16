package com.kkc.sheettracker.data.cvjob

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CvJobCabinetLayoutTest {
    private fun root(cab: Int = 9) = JsonParser.parseString(javaClass.getResource("/cvjob/job_data_690_cabinet${cab}_layout.json")!!.readText()).asJsonObject
    private fun load(root: JsonObject) = parseCvJobDoorPreviews(root.toString()) as CvJobDoorPreviews.Loaded
    private fun layout(root: JsonObject) = root.getAsJsonArray("cabinetLayouts")[0].asJsonObject
    private fun fronts(root: JsonObject) = layout(root).getAsJsonArray("fronts")
    @Test fun cabinet9LayoutAttachesToEachOfItsDoors() {
        val doors = load(root()).doors
        assertEquals(3, doors.size)
        doors.forEach { d ->
            val l = d.cabinetLayout!!
            assertEquals(3, l.fronts.size); assertEquals(5, l.frameMembers.size)
            assertEquals(57.0, l.faceWidth, 0.0)
        }
    }
    @Test fun selectedFrontIsFoundByDoorId() {
        val d = load(root()).forDoor("53737")!!
        assertEquals(36.125, d.cabinetLayout!!.fronts.single { it.doorId == d.doorId }.rect.height, 0.0)
    }
    @Test fun oldFileWithoutCabinetLayoutsHasNone() {
        val r = root(); r.remove("cabinetLayouts")
        assertTrue(load(r).doors.all { it.cabinetLayout == null })
    }
    @Test fun layoutIsDroppedWhenARectIsNotFinite() {
        for ((key, value) in listOf("width" to "NaN", "height" to "0", "x" to "Infinity")) {
            val r = root(); fronts(r)[0].asJsonObject.getAsJsonObject("rect").addProperty(key, value)
            assertTrue(load(r).doors.all { it.cabinetLayout == null })
        }
    }
    @Test fun layoutIsDroppedWhenAMemberHasNoPartRow() {
        val r = root(); layout(r).getAsJsonArray("frameMembers")[0].asJsonObject.addProperty("part", "missing")
        assertTrue(load(r).doors.all { it.cabinetLayout == null })
    }
    @Test fun unknownDoorIdFrontIsKeptAsAnInertFront() {
        val r = root(); fronts(r)[0].asJsonObject.addProperty("doorId", "unknown")
        val doors = load(r).doors
        assertNull(doors.single { it.doorId == "53737" }.cabinetLayout)
        assertTrue(doors.last().cabinetLayout!!.fronts.any { it.doorId == "unknown" })
    }
    @Test fun doorsOfOtherCabinetsGetNone() {
        val r = root(); r.getAsJsonArray("doors")[0].asJsonObject.addProperty("cabinet", "999")
        assertNull(load(r).forDoor("53737")!!.cabinetLayout)
    }
    @Test fun cabinet15LayoutAttachesWithItsSlope() {
        val l = load(root(15)).doors.first().cabinetLayout!!
        assertEquals(36.6897, l.slope!!.degrees, 0.0001); assertTrue(l.slope.highOnLeft)
        assertEquals(3, l.frameMembers.count { it.polygon != null })
        assertEquals(1, l.openings.count { it.polygon != null })
    }
    @Test fun frontShapeFollowsItsOwnDoor() {
        val doors = load(root(15)).doors; val l = doors.first().cabinetLayout!!
        val shapes = l.fronts.associate { it.doorId to frontShape(l, it, doors.associateBy { d -> d.doorId }) }
        assertTrue(shapes["53751"] is FrontShape.Outline)
        assertTrue(shapes["53752"] is FrontShape.Rect); assertTrue(shapes["53753"] is FrontShape.Rect)
        val points = (shapes["53751"] as FrontShape.Outline).points
        assertEquals(DoorPoint(0.8125, 31.8125), points.first())
    }
    @Test fun doorAngleNeedNotMatchTheCabinet() {
        val doors = load(root(15)).doors; val d = doors.first { it.doorId == "53751" }; val l = d.cabinetLayout!!
        val slope = d.geometry.slope as DoorSlope.Verified
        val changed = d.copy(geometry = d.geometry.copy(slope = slope.copy(slopeDegrees = 20.0,
            outline = listOf(DoorPoint(0.0,0.0),DoorPoint(23.875,0.0),DoorPoint(23.875,15.0),DoorPoint(0.0,24.5689)))))
        val shape = frontShape(l, l.fronts.single { it.doorId == d.doorId }, mapOf(d.doorId to changed)) as FrontShape.Outline
        assertEquals(46.8125, shape.points[2].y, 0.0)
    }
    @Test fun straightDoorInAnAngledCabinetStaysARectangle() {
        val doors=load(root(15)).doors; val l=doors.first().cabinetLayout!!
        assertTrue(frontShape(l,l.fronts.single { it.doorId=="53753" },doors.associateBy { it.doorId }) is FrontShape.Rect)
    }
    @Test fun angledDoorInAStraightCabinetUsesItsOutline() {
        val r=root(15);layout(r).remove("slope");val doors=load(r).doors;val l=doors.first().cabinetLayout!!
        assertTrue(frontShape(l,l.fronts.single { it.doorId=="53751" },doors.associateBy { it.doorId }) is FrontShape.Outline)
    }
    @Test fun refusedDoorIsBlankButTheCabinetStays() {
        val r=root(15);r.getAsJsonArray("parts").first { it.asJsonObject.get("id").asString=="s-tr" }.asJsonObject.addProperty("length","27")
        val doors=load(r).doors;val d=doors.first { it.doorId=="53751" };val l=d.cabinetLayout!!
        assertTrue(d.geometry.slope is DoorSlope.Refused)
        assertEquals(FrontShape.Blank,frontShape(l,l.fronts.single { it.doorId==d.doorId },doors.associateBy { it.doorId }))
    }
    @Test fun frontWithNoDoorRowIsBlankInASlopedCabinetButARectangleInAStraightOne() {
        for ((cab, blank) in listOf(9 to false,15 to true)) {
            val r=root(cab);fronts(r)[0].asJsonObject.addProperty("doorId","unknown")
            val doors=load(r).doors;val l=doors.last().cabinetLayout!!
            val shape=frontShape(l,l.fronts.first(),doors.associateBy { it.doorId })
            assertEquals(blank,shape==FrontShape.Blank)
        }
    }
    @Test fun emptySlopedOpeningStaysInTheLayoutWithNoFront() {
        val r=root(15);val fs=fronts(r);val i=fs.indexOfFirst { it.asJsonObject.get("doorId").asString=="53751" };fs.remove(i)
        val l=load(r).forDoor("53753")!!.cabinetLayout!!
        assertTrue(l.openings.single { it.id=="DOR_OPEN@1" }.polygon!=null)
        assertFalse(l.fronts.any { it.opening=="DOR_OPEN@1" })
    }
    @Test fun polygonWithNonFinitePointDropsTheLayout() {
        val r=root(15);layout(r).getAsJsonArray("frameMembers")[0].asJsonObject.getAsJsonArray("polygon")[0].asJsonObject.addProperty("x","NaN")
        assertTrue(load(r).doors.all { it.cabinetLayout==null })
    }
    @Test fun hiddenDoorKeepsItsVerifiedShapeButIsNotSelectable() {
        val r=root(15);r.getAsJsonArray("doors").first { it.asJsonObject.get("id").asString=="53751" }.asJsonObject.addProperty("hidden",true)
        val loaded=load(r)
        assertNull(loaded.forDoor("53751"))
        val l=loaded.doors.first().cabinetLayout!!
        assertTrue(frontShape(l,l.fronts.single { it.doorId=="53751" },loaded.allDoors.associateBy { it.doorId }) is FrontShape.Outline)
    }
}
