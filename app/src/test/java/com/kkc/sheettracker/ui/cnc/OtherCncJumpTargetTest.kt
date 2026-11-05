package com.kkc.sheettracker.ui.cnc

import com.kkc.sheettracker.data.cnc.CncFinishedMark
import com.kkc.sheettracker.data.cnc.CncLoadedMark
import com.kkc.sheettracker.data.cnc.CncSheetMarks
import com.kkc.sheettracker.data.cnc.CncSheetRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtherCncJumpTargetTest {
    private val redSheet = CncSheetRef("715 - Smith", "715 - 1_2 Baltic.pdf", 4)
    private val blueSheet = CncSheetRef("720 - Jones", "720 - 3_4 Maple.pdf", 2)
    private val elsewhere = CncSheetRef("715 - Smith", "715 - 1_2 Baltic.pdf", 7)

    private fun loaded(sheet: CncSheetRef, running: Boolean = true) =
        CncSheetMarks(loaded = CncLoadedMark.Matched(sheet, sheet.pdfFilename, running))

    @Test fun onRedsSheetTargetsBlue() {
        val target = otherCncJumpTarget(listOf("Red SCM" to loaded(redSheet), "Blue SCM" to loaded(blueSheet)), redSheet)
        assertEquals("Blue SCM", target?.machineName)
        assertEquals(CncPillMachine.BLUE, target?.machine)
        assertEquals(blueSheet, target?.sheet)
    }

    @Test fun onBluesSheetTargetsRed() {
        val target = otherCncJumpTarget(listOf("Red SCM" to loaded(redSheet), "Blue SCM" to loaded(blueSheet)), blueSheet)
        assertEquals("Red SCM", target?.machineName)
    }

    @Test fun matchingIgnoresCase() {
        val current = CncSheetRef(redSheet.jobFolderName.uppercase(), redSheet.pdfFilename.lowercase(), redSheet.page)
        assertEquals("Blue SCM", otherCncJumpTarget(listOf("Red SCM" to loaded(redSheet), "Blue SCM" to loaded(blueSheet)), current)?.machineName)
    }

    @Test fun nothingWhenOtherMachineHasNoMatchedSheet() {
        val unmatched = CncSheetMarks(loaded = CncLoadedMark.Unmatched("random.pgm", true))
        assertNull(otherCncJumpTarget(listOf("Red SCM" to loaded(redSheet), "Blue SCM" to unmatched), redSheet))
        assertNull(otherCncJumpTarget(listOf("Red SCM" to loaded(redSheet), "Blue SCM" to CncSheetMarks()), redSheet))
        val finishedOnly = CncSheetMarks(lastFinished = CncFinishedMark(blueSheet, "3:15"))
        assertNull(otherCncJumpTarget(listOf("Red SCM" to loaded(redSheet), "Blue SCM" to finishedOnly), redSheet))
    }

    @Test fun bothMachinesOnTheSameSheetOffersNothing() {
        assertNull(otherCncJumpTarget(listOf("Red SCM" to loaded(redSheet), "Blue SCM" to loaded(redSheet)), redSheet))
    }

    @Test fun offSheetPrefersTheMachineTheViewerIsNotShowing() {
        val machines = listOf("Red SCM" to loaded(redSheet), "Blue SCM" to loaded(blueSheet))
        assertEquals("Blue SCM", otherCncJumpTarget(machines, elsewhere, viewerMachineName = "Red SCM")?.machineName)
        assertEquals("Red SCM", otherCncJumpTarget(machines, elsewhere, viewerMachineName = "Blue SCM")?.machineName)
    }

    @Test fun offSheetPrefersACuttingMachine() {
        val machines = listOf("Red SCM" to loaded(redSheet, running = false), "Blue SCM" to loaded(blueSheet, running = true))
        assertEquals("Blue SCM", otherCncJumpTarget(machines, elsewhere)?.machineName)
    }

    @Test fun idleLoadedSheetIsStillATarget() {
        val target = otherCncJumpTarget(listOf("Red SCM" to loaded(redSheet), "Blue SCM" to loaded(blueSheet, running = false)), redSheet)
        assertEquals("Blue SCM", target?.machineName)
        assertEquals(false, target?.running)
    }
}
