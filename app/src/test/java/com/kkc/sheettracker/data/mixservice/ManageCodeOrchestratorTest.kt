package com.kkc.sheettracker.data.mixservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageCodeOrchestratorTest {
    private val rows = listOf(
        ManageCodeRow(pageNumber = 1, pgmFiles = listOf("R1.pgm"), editablePgm = "R1.pgm"),
        ManageCodeRow(pageNumber = 2, pgmFiles = listOf("R2A.pgm", "R2Z.pgm"), editablePgm = "R2Z.pgm"),
        ManageCodeRow(pageNumber = 3, pgmFiles = listOf("R3.pgm"), editablePgm = "R3.pgm")
    )

    @Test
    fun `buildManageCodeChange excludes locked rows from programs and edit rows`() {
        val selections = mapOf(
            "R1.pgm" to ManageCodeRowSelection(mix = true, secondPass = true),
            "R2Z.pgm" to ManageCodeRowSelection(mix = true),
            "R3.pgm" to ManageCodeRowSelection(mix = true)
        )
        val change = buildManageCodeChange(rows, selections, locked = setOf("R1.pgm"), originalPrograms = emptyList())
        assertFalse(change.programs.contains("R1.pgm"))
        assertTrue(change.editRows.none { it.name == "R1.pgm" })
    }

    @Test
    fun `buildManageCodeChange includes both A and Z files for a checked combined row`() {
        val selections = mapOf("R2Z.pgm" to ManageCodeRowSelection(mix = true))
        val change = buildManageCodeChange(rows.take(2).drop(1), selections, locked = emptySet(), originalPrograms = emptyList())
        assertEquals(listOf("R2A.pgm", "R2Z.pgm"), change.programs)
    }

    @Test
    fun `buildManageCodeChange builds edit rows only for punload or second pass selections`() {
        val selections = mapOf(
            "R1.pgm" to ManageCodeRowSelection(mix = true, removePUnload = true),
            "R3.pgm" to ManageCodeRowSelection(mix = true, secondPass = true, superPass = true)
        )
        val change = buildManageCodeChange(listOf(rows[0], rows[2]), selections, locked = emptySet(), originalPrograms = emptyList())
        val r1 = change.editRows.single { it.name == "R1.pgm" }
        val r3 = change.editRows.single { it.name == "R3.pgm" }
        assertEquals("none", r1.secondPass)
        assertTrue(r1.removePUnload)
        assertEquals("super", r3.secondPass)
    }

    @Test
    fun `buildManageCodeChange flags order or membership changed correctly`() {
        val selections = mapOf(
            "R1.pgm" to ManageCodeRowSelection(mix = true),
            "R3.pgm" to ManageCodeRowSelection(mix = true)
        )
        val subset = listOf(rows[0], rows[2])
        val unchanged = buildManageCodeChange(subset, selections, emptySet(), originalPrograms = listOf("R1.pgm", "R3.pgm"))
        val changed = buildManageCodeChange(subset, selections, emptySet(), originalPrograms = listOf("R3.pgm", "R1.pgm"))
        assertFalse(unchanged.orderOrMembershipChanged)
        assertTrue(changed.orderOrMembershipChanged)
    }

    @Test
    fun `findCrossMixDuplicates flags a pgm already owned by a different mix, ignoring the mix being edited`() {
        val others = listOf(
            MixDefinition(name = "OtherMix", programs = listOf("R1.pgm")),
            MixDefinition(name = "ThisMix", programs = listOf("R1.pgm"))
        )
        val warnings = findCrossMixDuplicates(listOf("R1.pgm", "R3.pgm"), thisMixName = "ThisMix", otherMixes = others)
        assertEquals(listOf(DuplicateMixWarning("R1.pgm", "OtherMix")), warnings)
    }
}
