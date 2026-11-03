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
    @Test fun bothMachinesAppearOnRecentCardsWithTheirOwnPills() {
        val blueRef = ref.copy(pdfFilename = "other.pdf", page = 3)
        val blueItem = item.copy(pdfFilename = blueRef.pdfFilename)
        val bothProjection = projection.copy(materialRows = projection.materialRows + (JobMaterialKey(blueRef.jobFolderName, blueRef.pdfFilename) to blueItem))
        val machines = listOf("Red SCM" to marks(), "Blue SCM" to CncSheetMarks(CncLoadedMark.Matched(blueRef, "R2", false)))
        val result = projectCncDashboard(listOf(item, blueItem), bothProjection, machines)
        val cards = result.rows.filterIsInstance<CncDashboardRow.Material>()
        assertEquals(2, cards.size)
        assertEquals("RED SCM · ON CNC", cards[0].pill?.dashboardText)
        assertEquals("BLUE SCM · LOADED · IDLE", cards[1].pill?.dashboardText)
        assertEquals(3, cards[1].openPage)
    }
    @Test fun sameMaterialOnBothMachinesKeepsBothProgramCards() {
        val blue = CncSheetMarks(CncLoadedMark.Matched(ref.copy(page = 7), "R2", true))
        val result = projectCncDashboard(listOf(item), projection, listOf("Red SCM" to marks(), "Blue SCM" to blue))
        val cards = result.rows.filterIsInstance<CncDashboardRow.Material>()
        assertEquals(2, cards.size)
        assertEquals(listOf(11, 7), cards.map { it.openPage })
    }
    @Test fun unmatchedProgramNamesItsMachineWithoutGuessingMaterial() {
        val result = projectCncDashboard(listOf(item), projection, listOf("Blue SCM" to CncSheetMarks(CncLoadedMark.Unmatched("R2", true))))
        assertEquals(CncDashboardRow.Unknown("R2", true, "Blue SCM"), result.rows.first())
        assertEquals(item, (result.rows.last() as CncDashboardRow.Material).item)
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

    @Test fun mixReorderingAndContinuationUsePhysicalHeadPage() {
        val material = Material(ref.pdfFilename, "m", 12, metadata = MaterialMetadata(pages = listOf(
            PageMetadata(pageNumber = 11), PageMetadata(pageNumber = 12, isPartListContinuation = true, continuationHeadPage = 11))))
        val pills = cncPillsForMaterial(marks(), ref.jobFolderName, material, listOf(11, 3, 6, 12))
        assertEquals(setOf(11, 12), pills.keys)
        assertEquals("ON CNC", pills.getValue(11).navigatorText)
        assertEquals(pills[11], pills[12])
        assertTrue(cncPillsForMaterial(CncSheetMarks(), ref.jobFolderName, material, listOf(11, 3, 6)).isEmpty())
    }
}

