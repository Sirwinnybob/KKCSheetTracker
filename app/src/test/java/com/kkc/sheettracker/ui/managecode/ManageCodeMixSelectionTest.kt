package com.kkc.sheettracker.ui.managecode

import com.kkc.sheettracker.data.mixservice.ManageCodeRow
import com.kkc.sheettracker.data.mixservice.ManageCodeRowSelection
import com.kkc.sheettracker.data.mixservice.MixGenerationTarget
import com.kkc.sheettracker.data.mixservice.MixCatalogFetchResult
import com.kkc.sheettracker.data.mixservice.buildManageCodeChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageCodeMixSelectionTest {
    @Test
    fun `unavailable mix catalog has an operator visible explanation`() {
        assertEquals(
            "Mix catalog unavailable — update the CNC mix service, then refresh",
            mixCatalogUnavailableMessage(MixCatalogFetchResult.NetworkError)
        )
    }

    @Test
    fun `untouched multi active material hydrates selected B membership and order`() {
        val state = state(rows = listOf("R1.pgm", "R2.pgm", "R4.pgm", "R3.pgm"))
        val target = MixGenerationTarget.ReplaceActive("B", 7L, listOf("R3.pgm", "R4.pgm"))

        val selected = selectReplacementTarget(state, target)
        val change = buildManageCodeChange(
            selected.rows,
            selected.selections,
            selected.locked,
            target.programsBaseline
        )

        assertEquals(listOf("R3.pgm", "R4.pgm"), selected.rows.map { it.editablePgm }.take(2))
        assertEquals(listOf("R3.pgm", "R4.pgm"), change.programs)
        assertFalse(change.orderOrMembershipChanged)
        assertFalse(selected.mixLayoutDirty)
    }

    @Test
    fun `dirty single active material retains operator membership and order`() {
        val initial = state(rows = listOf("R1.pgm", "R2.pgm", "R3.pgm"))
        val reordered = updateMixLayoutRows(initial, initial.rows.let { listOf(it[2], it[0], it[1]) })
        val edited = updateMixLayoutSelection(
            reordered,
            "R2.pgm",
            reordered.selections.getValue("R2.pgm").copy(mix = false)
        )
        val target = MixGenerationTarget.ReplaceActive("Current", 7L, listOf("R1.pgm", "R2.pgm"))

        val selected = selectReplacementTarget(edited, target)
        val change = buildManageCodeChange(
            selected.rows,
            selected.selections,
            selected.locked,
            target.programsBaseline
        )

        assertEquals(listOf("R3.pgm", "R1.pgm", "R2.pgm"), selected.rows.map { it.editablePgm })
        assertEquals(listOf("R3.pgm", "R1.pgm"), change.programs)
        assertTrue(selected.mixLayoutDirty)
        assertTrue(change.orderOrMembershipChanged)
    }

    @Test
    fun `dirty multi active material retains operator membership and order`() {
        val initial = state(rows = listOf("R1.pgm", "R2.pgm", "R3.pgm", "R4.pgm"))
        val reordered = updateMixLayoutRows(initial, initial.rows.let { listOf(it[3], it[0], it[1], it[2]) })
        val edited = updateMixLayoutSelection(
            reordered,
            "R3.pgm",
            reordered.selections.getValue("R3.pgm").copy(mix = false)
        )
        val target = MixGenerationTarget.ReplaceActive("B", 7L, listOf("R2.pgm", "R3.pgm"))

        val selected = selectReplacementTarget(edited, target)
        val change = buildManageCodeChange(
            selected.rows,
            selected.selections,
            selected.locked,
            target.programsBaseline
        )

        assertEquals(listOf("R4.pgm", "R1.pgm", "R2.pgm", "R3.pgm"), selected.rows.map { it.editablePgm })
        assertEquals(listOf("R4.pgm", "R1.pgm", "R2.pgm"), change.programs)
        assertTrue(selected.mixLayoutDirty)
        assertTrue(change.orderOrMembershipChanged)
    }

    private fun state(rows: List<String>): ManageCodeMaterialState {
        val manageRows = rows.mapIndexed { index, pgm ->
            ManageCodeRow(index + 1, listOf(pgm), pgm, null)
        }
        return ManageCodeMaterialState(
            materialName = "Mat",
            hasPgmsOnThisCnc = true,
            rows = manageRows,
            locked = emptySet(),
            selections = manageRows.associate { it.editablePgm to ManageCodeRowSelection(mix = true) }
        )
    }
}
