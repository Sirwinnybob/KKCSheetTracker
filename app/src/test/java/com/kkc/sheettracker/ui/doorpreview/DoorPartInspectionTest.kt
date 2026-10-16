package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.*
import org.junit.Assert.*
import org.junit.Test

class DoorPartInspectionTest {
    @Test fun frameMemberShowsCutThenFinalSize() {
        val member=CabinetFrameMember("LS",DoorRect(0.0,0.0,1.0,84.0),DoorPart("ls","frame","Left Stile","Oak","1.0625","84"))
        assertEquals(listOf("Cut size 1.0625 × 84 in","Final size 1 × 84 in"),doorPartSizeLines(frameMemberRegion(member)))
    }
    @Test fun railMemberLengthAxisIsHorizontal() {
        val member=CabinetFrameMember("TR",DoorRect(1.5,30.5,60.75,1.25),DoorPart("tr","frame","Top Rail","Oak","1.25","60.75"))
        assertEquals("Final size 1.25 × 60.75 in",doorPartSizeLines(frameMemberRegion(member))[1])
    }
    @Test fun slopedFrameMemberShowsOnlyItsCutSize() {
        val member=CabinetFrameMember("TR",DoorRect(0.0,0.0,10.0,5.0),DoorPart("tr","frame","Top Rail","Oak","1.5","12"),
            listOf(DoorPoint(0.0,3.0),DoorPoint(10.0,0.0),DoorPoint(10.0,2.0),DoorPoint(0.0,5.0)))
        assertEquals(listOf("Cut size 1.5 × 12 in"),doorPartSizeLines(frameMemberRegion(member)))
    }
    private fun simpleDoor(): DoorPreviewData {
        val geometry = DoorGeometry(
            "simple", DoorConstruction.FIVE_PIECE, 20.0, 30.0, 0.75,
            DoorFrame(2.0, 2.0, 2.0, 2.0, 0.5, "Oak"),
            DoorPanel("flat", 0.47, 0.47, DoorRect(2.0, 2.0, 16.0, 26.0), "Plywood"),
            DoorHinges(DoorHingeSide.NONE, "N", 0, emptyList(), null, emptyList()), emptyList()
        )
        val parts = listOf("leftStile", "rightStile", "topRail", "bottomRail", "panel").map { role ->
            DoorPart(role, role, role, "Oak", "2.03125", "30.0625")
        }
        return DoorPreviewData(geometry, "1A", "Flat", parts = parts)
    }

    @Test fun selectsVisibleFrameAndPanelFacesAndIgnoresDashedTenonsAndEmptySpace() {
        val door = simpleDoor()
        val regions = buildDoorPartRegions(door, buildDoorDrawing(door.geometry))
        val fit = DoorPreviewFit.of(20.0, 30.0, 240f, 400f, 20f)
        // Fit is 10 pixels/inch with the door at (20,50), y down.
        assertEquals("leftStile", hitDoorPart(regions, fit, 240f, 400f, 30f, 200f)?.part?.partId)
        assertEquals("rightStile", hitDoorPart(regions, fit, 240f, 400f, 210f, 200f)?.part?.partId)
        assertEquals("topRail", hitDoorPart(regions, fit, 240f, 400f, 120f, 60f)?.part?.partId)
        assertEquals("bottomRail", hitDoorPart(regions, fit, 240f, 400f, 120f, 340f)?.part?.partId)
        assertEquals("panel", hitDoorPart(regions, fit, 240f, 400f, 120f, 200f)?.part?.partId)
        assertEquals("leftStile", hitDoorPart(regions, fit, 240f, 400f, 35f, 60f)?.part?.partId)
        assertNull(hitDoorPart(regions, fit, 240f, 400f, 2f, 2f))
        assertNull(hitDoorPart(regions, fit, 240f, 400f, Float.NaN, 2f))
        assertNull(hitDoorPart(regions, fit, 240f, 400f, 30f, 200f, zoom = 0f))
    }

    @Test fun hitTestingReversesBothZoomAndPanBeforeFindingThePart() {
        val door = simpleDoor()
        val regions = buildDoorPartRegions(door, buildDoorDrawing(door.geometry))
        val fit = DoorPreviewFit.of(20.0, 30.0, 240f, 400f, 20f)
        // Original (35,200) becomes (50,185) at 2x zoom with pan (100,-15).
        val hit = hitDoorPart(regions, fit, 240f, 400f, 50f, 185f, zoom = 2f, panX = 100f, panY = -15f)
        assertEquals("leftStile", hit?.part?.partId)
        assertEquals("2.03125", hit?.part?.width)
        assertEquals("30.0625", hit?.part?.length)
    }

    @Test fun romanoPiecesKeepTheirIndividualIdsAndOriginalCutSizes() {
        val json = requireNotNull(javaClass.classLoader?.getResource("cvjob/job_data_302_cab11_layout.json")).readText()
        val door = (parseCvJobDoorPreviews(json) as CvJobDoorPreviews.Loaded).doors.single()
        val regions = buildDoorPartRegions(door, buildDoorDrawing(door.geometry))
        assertEquals(11, regions.size)
        assertEquals(11, regions.map { it.part.partId }.toSet().size)
        val fit = DoorPreviewFit.of(116.018, 35.268, 1160.18f, 600f, 0f)
        val mid = hitDoorPart(regions, fit, 1160.18f, 600f, 580.09f, 300f)
        assertEquals("489261", mid?.part?.partId)
        assertEquals("Door Mid Stile", mid?.part?.description)
        assertEquals("24.9555", mid?.part?.length)
        assertEquals(23.9555, mid!!.rect.height, 0.00001)
        val panel = hitDoorPart(regions, fit, 1160.18f, 600f, 992.6575f, 300f)
        assertEquals("489266", panel?.part?.partId)
        assertEquals("25.4445", panel?.part?.width)
        assertEquals(24.5045, panel!!.rect.width, 0.00001)
    }

    private fun lines(door: DoorPreviewData, partId: String): List<String> {
        val region = buildDoorPartRegions(door, buildDoorDrawing(door.geometry)).single { it.part.partId == partId }
        return doorPartSizeLines(region)
    }

    @Test fun stileShowsCutThenFinalFromTheFinishedGeometry() {
        // Cut 2.03125 x 30.0625 is oversize: finished stile is 2 wide and as tall as the door.
        assertEquals(listOf("Cut size 2.03125 × 30.0625 in", "Final size 2 × 30 in"), lines(simpleDoor(), "leftStile"))
    }

    @Test fun railShowsFinalVisibleLengthAndItsTenons() {
        // Rail length axis is horizontal: 20 - 2 - 2 = 16 visible between the stiles.
        assertEquals(
            listOf("Cut size 2.03125 × 30.0625 in", "Final size 2 × 16 in", "Tenons 0.5 at each end"),
            lines(simpleDoor(), "topRail")
        )
    }

    @Test fun panelShowsItsVisibleOpeningNotAFinalCutSize() {
        assertEquals(
            listOf("Cut size 2.03125 × 30.0625 in", "Visible opening 16 × 26 in"),
            lines(simpleDoor(), "panel")
        )
    }

    @Test fun romanoMidStileUsesItsPublishedFinishedRectangleAndTenon() {
        val json = requireNotNull(javaClass.classLoader?.getResource("cvjob/job_data_302_cab11_layout.json")).readText()
        val door = (parseCvJobDoorPreviews(json) as CvJobDoorPreviews.Loaded).doors.single()
        assertEquals(
            listOf("Cut size 3 × 24.9555 in", "Final size 3 × 23.9555 in", "Tenons 0.5 at each end"),
            lines(door, "489261")
        )
        assertEquals(
            listOf("Cut size 25.4445 × 24.8955 in", "Visible opening 24.5045 × 23.9555 in"),
            lines(door, "489266")
        )
    }

    @Test fun finalIsOmittedWhenItWouldContradictTheCutSizeOrTheCutSizeIsUnreadable() {
        val door = simpleDoor()
        val tooSmall = door.copy(parts = door.parts.map {
            if (it.partId == "leftStile") it.copy(width = "1.0", length = "1.0") else it
        })
        assertEquals(listOf("Cut size 1.0 × 1.0 in"), lines(tooSmall, "leftStile"))
        val unreadable = door.copy(parts = door.parts.map {
            if (it.partId == "leftStile") it.copy(width = "", length = "") else it
        })
        assertEquals(listOf("Cut size unavailable"), lines(unreadable, "leftStile"))
    }

    @Test fun slabsShowCutSizeOnly() {
        val door = simpleDoor()
        val slab = door.copy(
            geometry = door.geometry.copy(construction = DoorConstruction.SLAB, frame = null, panel = null),
            parts = listOf(DoorPart("s", "slab", "Door Slab", "Oak", "20", "30"))
        )
        assertEquals(listOf("Cut size 20 × 30 in"), lines(slab, "s"))
    }

    @Test fun slabsAreSelectableButUnsupportedOrMissingComponentsAreNeverGuessed() {
        val door = simpleDoor()
        val slab = door.copy(
            geometry = door.geometry.copy(construction = DoorConstruction.SLAB, frame = null, panel = null),
            parts = listOf(DoorPart("s", "slab", "Door Slab", "Oak", "20", "30"))
        )
        assertEquals(listOf(DoorPartRegion(slab.parts.single(), DoorRect(0.0, 0.0, 20.0, 30.0))),
            buildDoorPartRegions(slab, buildDoorDrawing(slab.geometry)))
        val unsupported = door.copy(geometry = door.geometry.copy(construction = DoorConstruction.OTHER))
        assertTrue(buildDoorPartRegions(unsupported, buildDoorDrawing(unsupported.geometry)).isEmpty())
        val missing = door.copy(parts = door.parts.filterNot { it.role == "panel" })
        val fit = DoorPreviewFit.of(20.0, 30.0, 240f, 400f, 20f)
        assertNull(hitDoorPart(buildDoorPartRegions(missing, buildDoorDrawing(missing.geometry)), fit, 240f, 400f, 120f, 200f))
    }
}
