package com.kkc.sheettracker.ui.cnc

import com.kkc.sheettracker.data.cnc.*
import com.kkc.sheettracker.data.models.*
import org.junit.Assert.*
import org.junit.Test

class CncStatusPresentationTest {
    private val ref = CncSheetRef("709 - BRAD TAYLOR", "709 - 19mm Pre_Finished.pdf", 11)
    private val item = DashboardRecentMaterialItem(ref.jobFolderName, "709", "19mm", ref.pdfFilename,
        "fp1", 6, 6, 123L, StatusCounts(total = 11, complete = 5), 5f / 11f, "6.png")
    private val projection = CncAppStateProjection(
        CncProgramIndex(thumbnailsBySheet = mapOf(ref to "11.png")),
        mapOf(JobMaterialKey(ref.jobFolderName, ref.pdfFilename) to item))
    private fun marks(running: Boolean = true) = CncSheetMarks(CncLoadedMark.Matched(ref, "R82010BN", running))
    @Test fun loadedMaterialMovesFirstWithoutDuplicationAndUsesLoadedThumbnailAndPage() {
        val other = item.copy(pdfFilename = "other.pdf")
        val result = projectCncDashboard(listOf(other, item), projection, marks())
        assertEquals(2, result.recentCount)
        assertEquals(2, result.rows.size)
        val first = result.rows.first() as CncDashboardRow.Material
        assertEquals(11, first.openPage)
        assertEquals(11, first.item.nextIncompletePage)
        assertEquals("11.png", first.item.thumbnailPath)
        assertEquals(item.counts, first.item.counts)
        assertEquals(6, item.nextIncompletePage)
        assertEquals("ON CNC", first.pill?.dashboardText)
    }
    @Test fun insertedMaterialKeepsRecentCountAndAllRecentRows() {
        val recent = (1..4).map { item.copy(pdfFilename = "$it.pdf") }
        val result = projectCncDashboard(recent, projection, marks())
        assertEquals(4, result.recentCount)
        assertEquals(5, result.rows.size)
        assertEquals(11, (result.rows.first() as CncDashboardRow.Material).openPage)
        assertEquals(1, projectCncDashboard(emptyList(), projection, marks()).rows.size)
        assertEquals(recent, projectCncDashboard(recent, projection, CncSheetMarks()).rows.map { (it as CncDashboardRow.Material).item })
    }
    @Test fun unknownAndMissingProjectionNeverFabricateProgress() {
        val unknown = CncSheetMarks(CncLoadedMark.Unmatched("UNKNOWN", false))
        assertEquals(listOf(CncDashboardRow.Unknown("UNKNOWN", false)), projectCncDashboard(emptyList(), projection, unknown).rows)
        assertEquals(listOf(CncDashboardRow.Unknown("R82010BN", true)), projectCncDashboard(emptyList(), CncAppStateProjection(), marks()).rows)
    }
    @Test fun loadedPillWinsAfterReloadAndCopyMatchesEveryPlacement() {
        val reloaded = marks().copy(lastFinished = CncFinishedMark(ref, "7:04"))
        val loaded = cncPillForSheet(reloaded, ref)!!
        assertEquals("ON CNC · CUTTING", loaded.headerText)
        assertEquals("ON CNC", loaded.navigatorText)
        assertEquals(CncPillKind.CUTTING, loaded.kind)
        assertEquals("LOADED · IDLE", cncPillForSheet(marks(false), ref)?.headerText)
        val cut = cncPillForSheet(CncSheetMarks(lastFinished = CncFinishedMark(ref, "7:04")), ref)!!
        assertEquals("CUT ON CNC 7:04", cut.headerText)
        assertEquals("CUT 7:04", cut.navigatorText)
        assertNull(cncPillForSheet(reloaded, ref.copy(page = 6)))
        assertNull(cncPillForSheet(reloaded, ref.copy(jobFolderName = "OTHER")))
        assertNull(cncPillForSheet(reloaded, ref.copy(pdfFilename = "OTHER.pdf")))
    }
}

