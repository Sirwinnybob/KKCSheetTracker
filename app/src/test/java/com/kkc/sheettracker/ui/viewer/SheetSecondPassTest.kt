package com.kkc.sheettracker.ui.viewer

import com.kkc.sheettracker.data.cnc.MachineAccess
import com.kkc.sheettracker.data.mixfiles.SecondPassStep
import com.kkc.sheettracker.data.mixservice.PgmEditCurrentState
import com.kkc.sheettracker.data.mixservice.PgmEditFileHistory
import com.kkc.sheettracker.data.mixservice.PgmEditHistoryView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetSecondPassTest {
    private val pair = listOf("530_12A.pgm", "530_12Z.pgm")
    private val allowed = MachineAccess(mixFiles = true, frz = true, secondPass = true)

    private fun history(vararg files: Pair<String, String>) = PgmEditHistoryView(
        files.associate { (name, mode) -> name to PgmEditFileHistory(PgmEditCurrentState(mode, false), stateSource = "ledger") },
        ok = true, stateSource = "ledger")

    private fun snapshot(history: PgmEditHistoryView?, revert: Boolean = true, access: MachineAccess? = allowed,
        inventory: List<String>? = pair) = SheetSecondPassSnapshot(access, revert, inventory, history)

    private fun ui(snapshot: SheetSecondPassSnapshot?, pgms: List<String> = pair, loading: Boolean = false, applying: Boolean = false,
        locked: Boolean = false, readOnly: Boolean = false, otherBlock: String? = null) =
        sheetSecondPassUi(pgms, snapshot, "Red SCM", loading, applying, locked, readOnly, otherBlock, punloadFixed = false)

    @Test fun aSideIsNeverATarget() {
        assertEquals(listOf("530_12Z.pgm"), sheetSecondPassPgms(pair))
        assertEquals(listOf("530_12Z.pgm"), ui(snapshot(history())).pgms)
    }

    @Test fun noRecordedHistoryReadsOffAndOffersTurningOn() {
        val result = ui(snapshot(history()))
        assertEquals(SheetSecondPassDisplay.OFF, result.display)
        assertEquals("OFF", result.statusText)
        assertTrue(result.turnOn)
        assertTrue(result.canToggle)
    }

    @Test fun standardAndSuperReadOnAndOfferTurningOff() {
        val on = ui(snapshot(history("530_12Z.pgm" to "standard")))
        assertEquals(SheetSecondPassDisplay.ON, on.display)
        assertFalse(on.turnOn)
        assertTrue(on.canToggle)
        assertEquals(SheetSecondPassDisplay.SUPER, ui(snapshot(history("530_12Z.pgm" to "super"))).display)
    }

    @Test fun aSideEditsDoNotMakeTheSheetReadOn() {
        assertEquals(SheetSecondPassDisplay.OFF, ui(snapshot(history("530_12A.pgm" to "standard"))).display)
    }

    @Test fun unknownLedgerIsNeverShownClean() {
        val uncertain = PgmEditHistoryView(mapOf("530_12Z.pgm" to PgmEditFileHistory(null, stateSource = "unknown")),
            ok = true, stateSource = "ledger")
        val result = ui(snapshot(uncertain))
        assertEquals(SheetSecondPassDisplay.UNKNOWN, result.display)
        assertFalse(result.canToggle)
        assertEquals(SheetSecondPassDisplay.UNKNOWN, ui(snapshot(null)).display)
    }

    @Test fun loadingAndApplyingBlockTaps() {
        assertEquals(SheetSecondPassDisplay.LOADING, ui(null, loading = true).display)
        assertEquals(SheetSecondPassDisplay.UNKNOWN, ui(null, loading = false).display)
        val applying = ui(snapshot(history()), applying = true)
        assertEquals(SheetSecondPassDisplay.APPLYING, applying.display)
        assertFalse(applying.canToggle)
    }

    @Test fun programMissingFromCncIsNotOnCnc() {
        val result = ui(snapshot(history(), inventory = listOf("other.pgm")))
        assertEquals("NOT ON CNC", result.statusText)
        assertFalse(result.canToggle)
    }

    @Test fun inventoryMatchIgnoresCase() {
        assertTrue(ui(snapshot(history(), inventory = listOf("530_12a.PGM", "530_12z.PGM"))).canToggle)
    }

    @Test fun blockersKeepStatusVisibleButExplain() {
        val locked = ui(snapshot(history("530_12Z.pgm" to "standard")), locked = true)
        assertEquals(SheetSecondPassDisplay.ON, locked.display)
        assertFalse(locked.canToggle)
        assertFalse(ui(snapshot(history()), readOnly = true).canToggle)
        assertEquals("busy", ui(snapshot(history()), otherBlock = "busy").blockedReason)
        val noAccess = ui(snapshot(history(), access = MachineAccess(false, false, false, reason = "Editing is off.")))
        assertEquals("Editing is off.", noAccess.blockedReason)
    }

    @Test fun turningOffNeedsRevertSupportButTurningOnDoesNot() {
        assertFalse(ui(snapshot(history("530_12Z.pgm" to "standard"), revert = false)).canToggle)
        assertNull(ui(snapshot(history(), revert = false)).blockedReason)
    }

    @Test fun planTurnsOnOnlyTheZSide() {
        val steps = sheetSecondPassPlan(history(), pair, on = true, punloadFixed = false)
        val rows = steps.filterIsInstance<SecondPassStep.Edit>().flatMap { it.rows }
        assertEquals(listOf("530_12Z.pgm"), rows.map { it.name })
        assertEquals("standard", rows.single().secondPass)
        assertTrue(steps.none { it is SecondPassStep.Revert })
    }

    @Test fun planTurningOffRevertsAndTurningOnAgainIsNoOp() {
        val on = history("530_12Z.pgm" to "standard")
        assertEquals(listOf(SecondPassStep.Revert(listOf("530_12Z.pgm"))), sheetSecondPassPlan(on, pair, on = false, punloadFixed = false))
        assertTrue(sheetSecondPassPlan(on, pair, on = true, punloadFixed = false).isEmpty())
    }

    @Test fun planOnFixedPunloadMachineKeepsRemovalWithTheEdit() {
        val rows = sheetSecondPassPlan(history(), listOf("p.pgm"), on = true, punloadFixed = true)
            .filterIsInstance<SecondPassStep.Edit>().flatMap { it.rows }
        assertTrue(rows.single().removePUnload)
    }
}
