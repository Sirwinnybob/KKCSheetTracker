package com.kkc.sheettracker.ui.viewer

import com.kkc.sheettracker.data.cnc.CncLoadedMark
import com.kkc.sheettracker.data.cnc.CncSheetMarks
import com.kkc.sheettracker.data.cnc.CncSheetRef
import com.kkc.sheettracker.data.mixfiles.MixFileEntry
import com.kkc.sheettracker.data.mixfiles.MixFileKind
import com.kkc.sheettracker.data.mixfiles.MixFileRow
import com.kkc.sheettracker.data.mixfiles.MixFilesCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunningMixSelectionTest {
    private fun entry(pgm: String) =
        MixFileEntry("C:\\Jobs\\$pgm", pgm, linkedMapOf(), null, 1, null, true, false)

    private fun row(file: String, kind: MixFileKind, vararg pgms: String, companion: String? = null) =
        MixFileRow(file, kind, null, null, "r1", companion, false, null, null, pgms.map(::entry))

    private fun marks(name: String) =
        CncSheetMarks(CncLoadedMark.Matched(CncSheetRef("709 - JOB", "m.pdf", 3), name, true))

    private val catalog = MixFilesCatalog("709 - JOB", "m", "rev", listOf(
        row("First.mix", MixFileKind.MIX, "A1.pgm", "A2.pgm"),
        row("Second.mix", MixFileKind.MIX, "B1.pgm", "B2.pgm"),
    ))

    @Test fun picksTheMixHoldingTheLoadedPgmIgnoringCaseAndExtension() {
        val selection = runningMixSelection(catalog, marks("b2.PgM"), frzSupported = true)
        assertEquals("Second", selection?.name)
        assertEquals("Second.mix", selection?.mixFile)
        assertEquals("First.mix", runningMixSelection(catalog, marks("A1"), frzSupported = true)?.mixFile)
    }

    @Test fun unknownOrMissingPgmFallsBackToNaturalOrder() {
        assertNull(runningMixSelection(catalog, marks("C9"), frzSupported = true))
        assertNull(runningMixSelection(catalog, CncSheetMarks(), frzSupported = true))
        assertNull(runningMixSelection(null, marks("A1"), frzSupported = true))
    }

    @Test fun pgmInSeveralMixesIsNotGuessed() {
        val shared = MixFilesCatalog("j", "m", "rev", listOf(
            row("One.mix", MixFileKind.MIX, "S1.pgm"), row("Two.mix", MixFileKind.MIX, "S1.pgm")))
        assertNull(runningMixSelection(shared, marks("S1"), frzSupported = true))
    }

    @Test fun interruptedMixOrdersByItsFrzOnlyWhereTheMachineSupportsIt() {
        val interrupted = MixFilesCatalog("j", "m", "rev", listOf(
            row("Run.mix", MixFileKind.MIX, "R1.pgm", "R2.pgm", companion = "Run.frz"),
            row("Run.frz", MixFileKind.FRZ, "R2.pgm", companion = "Run.mix"),
        ))
        assertEquals("Run.frz", runningMixSelection(interrupted, marks("R2"), frzSupported = true)?.mixFile)
        assertEquals("Run.mix", runningMixSelection(interrupted, marks("R2"), frzSupported = false)?.mixFile)
    }
}
