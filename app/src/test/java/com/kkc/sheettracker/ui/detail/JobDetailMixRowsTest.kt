package com.kkc.sheettracker.ui.detail

import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.mixservice.ViewerMixSelection
import com.kkc.sheettracker.data.models.*
import com.kkc.sheettracker.ui.viewer.*
import org.junit.Assert.*
import org.junit.Test

class JobDetailMixRowsTest {
    private val material = Material("19mm.pdf", "19MM PRE_FINISHED", 6, metadata = MaterialMetadata(pages = listOf(
        PageMetadata(pageNumber = 1, sheetFiles = listOf("R1")),
        PageMetadata(pageNumber = 2, sheetFiles = listOf("R2A", "R2Z")),
        PageMetadata(pageNumber = 3, sheetFiles = listOf("R3"), hiddenInApp = true),
        PageMetadata(pageNumber = 4, sheetFiles = listOf("R4"), trackingExcluded = true),
        PageMetadata(pageNumber = 5, sheetFiles = listOf("R5"), isPartListContinuation = true),
        PageMetadata(pageNumber = 6, sheetFiles = listOf("R6")))))
    private fun entry(pgm: String) = MixFileEntry(pgm, pgm, linkedMapOf(), null, 1, null, true, false)
    private fun row(file: String = "A.mix", pgms: List<String> = listOf("R1.pgm"), kind: MixFileKind = MixFileKind.MIX,
                    companion: String? = null, error: String? = null) = MixFileRow(file, kind, null, null, "r", companion, false, error, null, pgms.map(::entry))
    private fun catalog(vararg rows: MixFileRow) = MixFilesCatalog("job", material.materialName, "rev", rows.toList())

    @Test fun rowPerActiveGroupWithUnmatchedCount() {
        val c = catalog(row(companion = "A.FRZ"), row("A.FRZ", listOf("R2Z.pgm", "R2A.pgm", "missing.pgm", "missing.pgm"), MixFileKind.FRZ, "A.mix"),
            row("B.mix"), row("orphan.mix.001", kind = MixFileKind.REVISION).copy(revisionOf = "orphan.mix"))
        val rows = jobDetailMixRows(material, c) { SheetStatus.NOT_STARTED }
        assertEquals(2, rows.size)
        assertEquals(ViewerMixSelection("A", "A.FRZ"), rows[0].selection)
        assertTrue(rows[0].interrupted)
        assertEquals(2, rows[0].unmatched)
        assertFalse(rows[0].error)
    }
    @Test fun errorFileRowFlagged() {
        val row = jobDetailMixRows(material, catalog(row(error = "unreadable"))) { SheetStatus.COMPLETE }.single()
        assertTrue(row.error)
        assertNotNull(row.selection)
    }
    @Test fun liveOrderFollowsCatalog() {
        val first = catalog(row(pgms = listOf("R2Z.pgm", "R1.pgm", "R2A.pgm", "R1.pgm", "R3.pgm", "R4.pgm", "R5.pgm", "lost.pgm")))
        assertEquals(ViewerOrder.Mix(listOf(2, 1, 3, 4, 5), listOf("lost.pgm")), liveViewerOrder(material, first, "A.mix"))
        assertEquals(ViewerOrder.Mix(listOf(1, 2), emptyList<String>()), liveViewerOrder(material, catalog(row(pgms = listOf("R1.pgm", "R2Z.pgm"))), "A.mix"))
    }
    @Test fun renamedMixFallsBackToNatural() {
        assertEquals(ViewerOrder.Natural("mix was renamed or deleted"), liveViewerOrder(material, catalog(row("Renamed.mix")), "A.mix"))
    }
    @Test fun emptyMatchStaysScopedAndOpenable() {
        val c = catalog(row(pgms = listOf("missing.pgm")))
        assertEquals(ViewerOrder.Mix(emptyList<Int>(), listOf("missing.pgm")), liveViewerOrder(material, c, "A.mix"))
        assertEquals(ViewerMixSelection("A", "A.mix"), jobDetailMixRows(material, c) { SheetStatus.NOT_STARTED }.single().selection)
    }
    @Test fun needsRefreshReflectsCompletedInterruptedEntries() {
        val c = catalog(row("A.FRZ", kind = MixFileKind.FRZ).copy(entries = listOf(entry("R1.pgm").copy(remaining = 1))))
        assertTrue(jobDetailMixRows(material, c) { SheetStatus.COMPLETE }.single().needsRefresh)
        assertFalse(jobDetailMixRows(material, c) { SheetStatus.SKIPPED }.single().needsRefresh)
    }
    @Test fun currentPageStaysAndRemovalUsesFollowingDisplayedOrder() {
        assertEquals(11, retainedViewerPage(11, listOf(11, 3, 6), listOf(6, 11, 3), 1))
        assertEquals(6, retainedViewerPage(11, listOf(11, 3, 6), listOf(6, 2), 1))
        assertEquals(2, retainedViewerPage(6, listOf(11, 3, 6), listOf(2, 3), 1))
        assertEquals(1, retainedViewerPage(6, listOf(11, 3, 6), emptyList(), 1))
    }
    @Test fun offlineKeepsCachedOrderAndOutdatedUsesNatural() {
        val cached = catalog(row(pgms = listOf("R2A.pgm", "R1.pgm")))
        assertEquals(ViewerOrder.Mix(listOf(2, 1), emptyList<String>()), viewerOrderForState(material, CatalogState.Offline(cached), "A.mix"))
        assertTrue(viewerOrderForState(material, CatalogState.Outdated(cached), "A.mix") is ViewerOrder.Natural)
    }

    @Test fun blueNeverOffersAnFrz() {
        val c = catalog(row(companion = "A.FRZ"), row("A.FRZ", listOf("R2Z.pgm"), MixFileKind.FRZ, "A.mix"),
            row("Only.FRZ", listOf("R6.pgm"), MixFileKind.FRZ))
        val blue = jobDetailMixRows(material, c, frzSupported = false) { SheetStatus.NOT_STARTED }
        assertEquals(listOf(ViewerMixSelection("A", "A.mix")), blue.map { it.selection })
        assertFalse(blue.single().interrupted)
        assertEquals(2, jobDetailMixRows(material, c) { SheetStatus.NOT_STARTED }.size) // Red keeps its FRZ rows.
        // A restored or deep-linked FRZ selection on Blue falls back to natural order and names the machine.
        val order = viewerOrderForState(material, CatalogState.Live(c), "A.FRZ", frzSupported = false, machineName = "Blue SCM")
        assertEquals(ViewerOrder.Natural("Blue SCM doesn't run FRZ files; showing natural sheet order."), order)
        assertTrue(viewerOrderForState(material, CatalogState.Live(c), "A.mix", frzSupported = false) is ViewerOrder.Mix)
    }

    @Test fun eachMaterialShowsItsOwningMachineAndNeverMergesBoth() {
        val red = com.kkc.sheettracker.data.cnc.CncMachines.scm
        val blue = com.kkc.sheettracker.data.cnc.CncMachines.scmNew
        val live = CatalogState.Live(catalog(row()))
        assertEquals(listOf(blue), jobDetailMaterialMachines(true, mapOf("scm" to CatalogState.NoFolder, "scm-new" to live)))
        assertEquals(listOf(red), jobDetailMaterialMachines(true, mapOf("scm" to live, "scm-new" to CatalogState.NoFolder)))
        assertEquals(listOf(red), jobDetailMaterialMachines(true, mapOf("scm" to CatalogState.Loading, "scm-new" to CatalogState.Loading)))
        assertEquals(listOf(red, blue), jobDetailMaterialMachines(true, mapOf("scm" to live, "scm-new" to live)))
        assertEquals(listOf(null), jobDetailMaterialMachines(false, mapOf("scm-new" to live))) // Archived snapshots.
        assertEquals("Blue SCM · Offline · showing saved mix order.", jobDetailMachineNotice(blue, "Offline · showing saved mix order."))
        assertEquals("Mix order unavailable", jobDetailMachineNotice(null, "Mix order unavailable"))
    }

    @Test fun viewerMixNoticeNamesTheMachine() {
        val blue = com.kkc.sheettracker.data.cnc.CncMachines.scmNew
        assertEquals("Blue SCM", viewerMixNotice(null, blue))
        assertEquals("Blue SCM · Offline · showing saved mix order.", viewerMixNotice("Offline · showing saved mix order.", blue))
        assertNull(viewerMixNotice(null, null))
        assertEquals("On Red SCM and Blue SCM", machineChoiceLabel(com.kkc.sheettracker.data.cnc.CncMachines.mixMachines))
    }

    @Test fun caseOnlyFrzFilenameChangeKeepsLiveMixScope() {
        val c = catalog(row("19mm Pre_FinishedMix.frz", listOf("R2Z.pgm", "R1.pgm"), MixFileKind.FRZ))
        assertEquals(ViewerOrder.Mix(listOf(2, 1), emptyList<String>()),
            liveViewerOrder(material, c, "19MM PRE_FINISHEDMIX.FRZ"))
    }

}
