package com.kkc.sheettracker.data.mixservice

import com.kkc.sheettracker.data.models.SheetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageCodeSelectionTest {
    @Test
    fun `isRowLocked is true only for complete or re-nested`() {
        assertTrue(isRowLocked(SheetStatus.COMPLETE))
        assertTrue(isRowLocked(SheetStatus.RE_NESTED))
        assertFalse(isRowLocked(SheetStatus.NOT_STARTED))
        assertFalse(isRowLocked(SheetStatus.SKIPPED))
        assertFalse(isRowLocked(SheetStatus.HAS_BAD_PARTS))
    }

    @Test
    fun `deriveRowSelection defaults MIX checked when material has no existing mix`() {
        val selection = deriveRowSelection("R1.pgm", mixPrograms = emptyList(), hasExistingMix = false, editHistory = null)
        assertTrue(selection.mix)
        assertFalse(selection.secondPass)
        assertFalse(selection.superPass)
        assertFalse(selection.removePUnload)
    }

    @Test
    fun `deriveRowSelection reflects existing mix membership when a mix exists`() {
        val inMix = deriveRowSelection("R1.pgm", mixPrograms = listOf("R1.pgm"), hasExistingMix = true, editHistory = null)
        val notInMix = deriveRowSelection("R2.pgm", mixPrograms = listOf("R1.pgm"), hasExistingMix = true, editHistory = null)
        assertTrue(inMix.mix)
        assertFalse(notInMix.mix)
    }

    @Test
    fun `deriveRowSelection reflects live second-pass state from the ledger`() {
        val history = PgmEditHistoryView(
            files = mapOf("R1.pgm" to PgmEditFileHistory(PgmEditCurrentState(mode = "super", punloadRemoved = true)))
        )
        val selection = deriveRowSelection("R1.pgm", emptyList(), hasExistingMix = false, editHistory = history)
        assertTrue(selection.secondPass)
        assertTrue(selection.superPass)
        assertTrue(selection.removePUnload)
    }

    @Test
    fun `toggleSecondPass off clears super, toggleSuperPass on forces second pass`() {
        val checked = ManageCodeRowSelection(secondPass = true, superPass = true)
        assertFalse(toggleSecondPass(checked, false).superPass)

        val unchecked = ManageCodeRowSelection()
        val withSuper = toggleSuperPass(unchecked, true)
        assertTrue(withSuper.secondPass)
        assertTrue(withSuper.superPass)
    }
}
