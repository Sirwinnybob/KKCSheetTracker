package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.DoorConstruction
import com.kkc.sheettracker.data.cvjob.DoorFrame
import com.kkc.sheettracker.data.cvjob.DoorGeometry
import com.kkc.sheettracker.data.cvjob.DoorHingeSide
import com.kkc.sheettracker.data.cvjob.DoorHinges
import com.kkc.sheettracker.data.cvjob.DoorPanel
import com.kkc.sheettracker.data.cvjob.DoorPoint
import com.kkc.sheettracker.data.cvjob.DoorPreviewData
import com.kkc.sheettracker.data.cvjob.DoorRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoorPreviewTextTest {
    @Test
    fun statusMessageExplainsMissingDrawingsOnly() {
        assertNull(doorPreviewStatusMessage(null, 0))
        assertEquals(
            "No door data: job data has not been published from CV Cutlist Query.",
            doorPreviewStatusMessage(CvJobDoorPreviews.NoJobData, 0)
        )
        assertEquals(
            "No door data: job data is from an older CV Cutlist Query. Publish job data again.",
            doorPreviewStatusMessage(CvJobDoorPreviews.NoDoorGeometry, 0)
        )
        assertEquals(
            "Door data unavailable: job data could not be read.",
            doorPreviewStatusMessage(CvJobDoorPreviews.Unreadable("bad"), 0)
        )
        val loaded = CvJobDoorPreviews.Loaded(emptyList())
        assertEquals("No doors in the job data.", doorPreviewStatusMessage(loaded, 0))
        assertNull(doorPreviewStatusMessage(loaded, 3))
    }

    @Test
    fun detailLinesUseDecimalMeasurementsAndPreserveMaterialNames() {
        val geometry = DoorGeometry(
            doorId = "53753",
            construction = DoorConstruction.FIVE_PIECE,
            width = 31.375,
            height = 18.0,
            thickness = 0.75,
            frame = DoorFrame(2.25, 2.25, 2.25, 2.25, 0.5, "3/4 Paint Grade Wood"),
            panel = DoorPanel("glass", 0.4375, 0.4375, DoorRect(2.25, 2.25, 26.875, 13.5), "1/8 Glass"),
            hinges = DoorHinges(
                DoorHingeSide.TOP, "T", 2, listOf(3.1496, 28.2254), 0.8071,
                listOf(DoorPoint(3.1496, 17.1929), DoorPoint(28.2254, 17.1929))
            ),
            checks = emptyList()
        )
        val preview = DoorPreviewData(geometry, "1B", "Square Glass 2 1/4\"")
        assertEquals(
            listOf(
                "Size 31.375 × 18 × 0.75",
                "Stiles 2.25 / 2.25, rails 2.25 top / 2.25 bottom",
                "Tenons 0.5",
                "Frame: 3/4 Paint Grade Wood",
                "Opening 26.875 × 13.5",
                "Panel in groove 0.4375 sides, 0.4375 top/bottom",
                "Panel: glass, 1/8 Glass",
                "Hinges on top at 3.1496 from left, 3.1496 from right"
            ),
            doorDetailLines(preview, buildDoorDrawing(geometry))
        )

        // Hinges listed in the notes but not drawable (no points) read as no hinges.
        val noPoints = geometry.copy(hinges = geometry.hinges.copy(points = emptyList()))
        assertEquals(true, "No hinges" in doorDetailLines(preview.copy(geometry = noPoints), buildDoorDrawing(noPoints)))
    }

    @Test
    fun sideHingesReferenceTheNearestEndUsingFinishedDoorHeight() {
        for (side in listOf(DoorHingeSide.LEFT, DoorHingeSide.RIGHT)) {
            val positions = listOf(3.0, 20.0, 35.5)
            val x = if (side == DoorHingeSide.LEFT) 0.8071 else 15.1929
            val points = positions.map { DoorPoint(x, it) }
            val geometry = DoorGeometry(
                "door", DoorConstruction.SLAB, 16.0, 40.0, 0.75, null, null,
                DoorHinges(side, side.name, 3, positions, 0.8071, points), emptyList()
            )
            val preview = DoorPreviewData(geometry, "1A", "Slab")
            val drawing = buildDoorDrawing(geometry)
            assertEquals(
                "Hinges on ${side.name.lowercase()} at 3 from bottom, 20 from bottom, 4.5 from top",
                doorDetailLines(preview, drawing).last()
            )
            // Display conversion must never change CV coordinates or the drawn cup centres.
            assertEquals(positions, geometry.hinges.positions)
            assertEquals(points, drawing.shapes.filterIsInstance<DoorShape.HingeCup>().map { it.center })
        }
    }

    @Test
    fun fourHingeDoorLabelsEveryDistanceWithItsOwnReferenceEnd() {
        val positions = listOf(3.1496, 14.0, 26.0, 36.8504)
        val geometry = DoorGeometry(
            "door", DoorConstruction.SLAB, 16.0, 40.0, 0.75, null, null,
            DoorHinges(DoorHingeSide.LEFT, "L", 4, positions, 0.8071, positions.map { DoorPoint(0.8071, it) }),
            emptyList()
        )
        assertEquals(
            "Hinges on left at 3.1496 from bottom, 14 from bottom, 14 from top, 3.1496 from top",
            doorDetailLines(DoorPreviewData(geometry, "1A", "Slab"), buildDoorDrawing(geometry)).last()
        )
    }
}
