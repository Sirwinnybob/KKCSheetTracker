package com.kkc.sheettracker.ui.managecode

import com.kkc.sheettracker.data.mixservice.ManageCodeRow
import com.kkc.sheettracker.data.mixservice.ManageCodeRowSelection
import com.kkc.sheettracker.data.mixservice.MixCatalogEntry
import com.kkc.sheettracker.data.mixservice.MixGenerationTarget
import com.kkc.sheettracker.data.mixservice.MixLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixChoiceStateTest {
    private val rows = listOf("R1.pgm", "R2.pgm", "R3.pgm", "R4.pgm").mapIndexed { i, pgm ->
        ManageCodeRow(pageNumber = i + 1, pgmFiles = listOf(pgm), editablePgm = pgm, thumbnailPath = null)
    }
    private val mix = MixCatalogEntry(name = "MMix", mixFilename = "MMix.mix", lifecycle = MixLifecycle.ACTIVE, programs = listOf("R1.pgm", "R2.pgm"))

    private fun state(activeMixes: List<MixCatalogEntry> = listOf(mix), locked: Set<String> = emptySet(), rows: List<ManageCodeRow> = this.rows) =
        ManageCodeMaterialState(
            materialName = "M",
            hasPgmsOnThisCnc = true,
            rows = rows,
            locked = locked,
            selections = rows.associate { it.editablePgm to ManageCodeRowSelection() },
            activeMixes = activeMixes,
        )

    @Test
    fun `first MIX check on existing-mix material prompts instead of applying`() {
        val outcome = mixCheckOutcome(state(), setOf("R3.pgm"), checked = true, hasChoice = false)
        assertEquals(MixCheckOutcome.Prompt(setOf("R3.pgm")), outcome)
    }

    @Test
    fun `MIX check applies directly once a choice exists or no mix exists`() {
        assertTrue(mixCheckOutcome(state(), setOf("R3.pgm"), checked = true, hasChoice = true) is MixCheckOutcome.Apply)
        assertTrue(mixCheckOutcome(state(emptyList()), setOf("R3.pgm"), checked = true, hasChoice = false) is MixCheckOutcome.Apply)
        assertTrue(mixCheckOutcome(state(), setOf("R3.pgm"), checked = false, hasChoice = false) is MixCheckOutcome.Apply)
    }

    @Test
    fun `replace choice checks mix members except locked rows plus tapped`() {
        val target = MixGenerationTarget.ReplaceActive("MMix", 7L, listOf("R1.pgm", "R2.pgm"))
        val updated = applyReplaceChoice(state(locked = setOf("R2.pgm")), target, tapped = setOf("R4.pgm"))
        val checked = updated.selections.filterValues { it.mix }.keys
        assertEquals(setOf("R1.pgm", "R4.pgm"), checked)
        assertTrue(updated.mixLayoutDirty)
    }

    @Test
    fun `replace choice matches a row when any of its pgmFiles is in the baseline`() {
        val multiFileRows = listOf(
            ManageCodeRow(pageNumber = 1, pgmFiles = listOf("R1A.pgm", "R1Z.pgm"), editablePgm = "R1Z.pgm", thumbnailPath = null),
            ManageCodeRow(pageNumber = 2, pgmFiles = listOf("R2.pgm"), editablePgm = "R2.pgm", thumbnailPath = null),
        )
        // The catalog's baseline stores every pgmFile of a checked row (see buildManageCodeChange),
        // so the non-editable "A" stem can be the only one present in the baseline.
        val target = MixGenerationTarget.ReplaceActive("MMix", 7L, listOf("R1A.pgm"))
        val updated = applyReplaceChoice(state(rows = multiFileRows, locked = emptySet()), target, tapped = emptySet())
        val checked = updated.selections.filterValues { it.mix }.keys
        assertEquals(setOf("R1Z.pgm"), checked)
    }

    @Test
    fun `additional choice checks only tapped rows`() {
        val updated = applyAdditionalChoice(state(), tapped = setOf("R3.pgm"))
        assertEquals(setOf("R3.pgm"), updated.selections.filterValues { it.mix }.keys)
    }

    @Test
    fun `choice clears when no MIX box remains checked`() {
        assertTrue(shouldClearMixChoice(state()))
        assertFalse(shouldClearMixChoice(applyAdditionalChoice(state(), setOf("R3.pgm"))))
    }

    @Test
    fun `mix membership tag maps each pgm to its active mix`() {
        assertEquals(mapOf("R1.pgm" to "MMix", "R2.pgm" to "MMix"), mixMembershipByPgm(listOf(mix)))
    }
}
