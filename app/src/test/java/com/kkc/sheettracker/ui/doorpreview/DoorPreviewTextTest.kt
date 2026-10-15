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
    fun detailLinesUseShopFractions() {
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
                "Size 31 3/8 × 18 × 3/4",
                "Stiles 2 1/4 / 2 1/4, rails 2 1/4 top / 2 1/4 bottom",
                "Tenons 1/2",
                "Frame: 3/4 Paint Grade Wood",
                "Opening 26 7/8 × 13 1/2",
                "Panel in groove 7/16 sides, 7/16 top/bottom",
                "Panel: glass, 1/8 Glass",
                "Hinges on top at 3.1496, 28.2254 from left"
            ),
            doorDetailLines(preview, buildDoorDrawing(geometry))
        )

        // Hinges listed in the notes but not drawable (no points) read as no hinges.
        val noPoints = geometry.copy(hinges = geometry.hinges.copy(points = emptyList()))
        assertEquals(true, "No hinges" in doorDetailLines(preview.copy(geometry = noPoints), buildDoorDrawing(noPoints)))
    }
}
