package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.*
import org.junit.Assert.*
import org.junit.Test

class CabinetDoorInspectionTest {
    private fun doors(cabinet: Int = 9) = parseCvJobDoorPreviews(javaClass.getResource("/cvjob/job_data_690_cabinet${cabinet}_layout.json")!!.readText()) as CvJobDoorPreviews.Loaded

    @Test fun selectedDoorPartWinsOverItsFrontAndTheUnderlyingFrame() {
        val door = doors().forDoor("53737")!!
        val layout = door.cabinetLayout!!
        val front = layout.fronts.single { it.doorId == door.doorId }
        val regions = cabinetDoorPartRegions(front, door, buildDoorDrawing(door.geometry))
        val fit = cabinetFit(layout, 570f, 600f, 0f)
        val hit = hitCabinetTarget(layout, fit, 570f, 600f, fit.x(1.0), fit.y(10.0), doorParts = regions) as CabinetTarget.Part
        assertEquals("slab-53737", hit.region.part.partId)
        assertEquals(DoorRect(0.8125, 0.3125, 18.375, 36.125), hit.region.rect)
        assertEquals("LS", (hitCabinetTarget(layout, fit, 570f, 600f, fit.x(0.25), fit.y(10.0), doorParts = regions) as CabinetTarget.Member).member.code)
        assertEquals("53738", (hitCabinetTarget(layout, fit, 570f, 600f, fit.x(25.0), fit.y(20.0), doorParts = regions) as CabinetTarget.Front).front.doorId)
    }

    @Test fun slopedDoorPartsKeepTheirTranslatedPolygonsAndCutOnlySizes() {
        val door = doors(15).forDoor("53751")!!
        val layout = door.cabinetLayout!!
        val front = layout.fronts.single { it.doorId == door.doorId }
        val regions = cabinetDoorPartRegions(front, door, buildDoorDrawing(door.geometry))
        val left = regions.single { it.part.role == "leftStile" }
        assertEquals(DoorPoint(0.8125, 31.8125), left.polygon!!.first())
        assertEquals(listOf("Cut size 2.2813 × 24.6314 in"), doorPartSizeLines(left))
        val fit = cabinetFit(layout, 570f, 600f, 0f)
        val shape: (CabinetFront) -> FrontShape = { frontShape(layout, it, doors(15).allDoors.associateBy { d -> d.doorId }) }
        val hit = hitCabinetTarget(layout, fit, 570f, 600f, fit.x(1.3), fit.y(42.0), frontShape = shape, doorParts = regions) as CabinetTarget.Part
        assertEquals("s-ls", hit.region.part.partId)
        assertNull(hitCabinetTarget(layout.copy(frameMembers = emptyList()), fit, 570f, 600f, fit.x(24.5), fit.y(55.0), frontShape = shape, doorParts = regions))
    }

    @Test fun cabinetDoorPartHitsReverseTheSameZoomAndPanAsTheDrawing() {
        val door = doors().forDoor("53737")!!
        val layout = door.cabinetLayout!!
        val regions = cabinetDoorPartRegions(layout.fronts.single { it.doorId == door.doorId }, door, buildDoorDrawing(door.geometry))
        val fit = cabinetFit(layout, 570f, 600f, 0f)
        val hit = hitCabinetTarget(layout, fit, 570f, 600f, 115f, 287.5f, zoom = 2f, panX = 200f, doorParts = regions) as CabinetTarget.Part
        assertEquals("slab-53737", hit.region.part.partId)
    }

    @Test fun refusedDoorHasNoInspectableCabinetParts() {
        val door = doors(15).forDoor("53751")!!
        val refused = door.copy(geometry = door.geometry.copy(slope = DoorSlope.Refused("unproved")))
        val front = door.cabinetLayout!!.fronts.single { it.doorId == door.doorId }
        assertTrue(cabinetDoorPartRegions(front, refused, buildDoorDrawing(refused.geometry)).isEmpty())
    }
}
