package com.kkc.sheettracker.data.mixservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageCodeOrchestratorTest {
    private val rows = listOf(
        ManageCodeRow(pageNumber = 1, pgmFiles = listOf("R1.pgm"), editablePgm = "R1.pgm", thumbnailPath = null),
        ManageCodeRow(pageNumber = 2, pgmFiles = listOf("R2A.pgm", "R2Z.pgm"), editablePgm = "R2Z.pgm", thumbnailPath = null),
        ManageCodeRow(pageNumber = 3, pgmFiles = listOf("R3.pgm"), editablePgm = "R3.pgm", thumbnailPath = null)
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
            "R2Z.pgm" to ManageCodeRowSelection(mix = true),
            "R3.pgm" to ManageCodeRowSelection(mix = true, secondPass = true, superPass = true)
        )
        val change = buildManageCodeChange(listOf(rows[0], rows[1], rows[2]), selections, locked = emptySet(), originalPrograms = emptyList())
        val r1 = change.editRows.single { it.name == "R1.pgm" }
        val r3 = change.editRows.single { it.name == "R3.pgm" }
        assertEquals("none", r1.secondPass)
        assertTrue(r1.removePUnload)
        assertEquals("super", r3.secondPass)
        assertTrue(change.editRows.none { it.name == "R2Z.pgm" })
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

    @Test
    fun `first default generation resolves an empty baseline only for a catalog without active or external entries`() {
        val emptyCatalog = catalog(entries = emptyList())

        assertEquals(
            MixGenerationPlan(name = "19mmMix", programsBaseline = emptyList(), expectedRevision = 7L),
            resolveMixGenerationTarget(MixGenerationTarget.FirstDefault, emptyCatalog, "19mm")
        )
        assertEquals(
            null,
            resolveMixGenerationTarget(
                MixGenerationTarget.FirstDefault,
                catalog(entries = listOf(active("Current", "R1.pgm"))),
                "19mm"
            )
        )
    }

    @Test
    fun `additional mix target accepts only a valid unique active name`() {
        val snapshot = catalog(entries = listOf(active("Current", "R1.pgm")))

        assertEquals(
            MixGenerationPlan(name = "Second", programsBaseline = emptyList(), expectedRevision = 7L),
            resolveMixGenerationTarget(MixGenerationTarget.CreateAdditional("Second"), snapshot, "19mm")
        )
        assertEquals(null, resolveMixGenerationTarget(MixGenerationTarget.CreateAdditional("Current"), snapshot, "19mm"))
        assertEquals(null, resolveMixGenerationTarget(MixGenerationTarget.CreateAdditional("bad/name"), snapshot, "19mm"))
    }

    @Test
    fun `replace target requires the exact active name and current revision while preserving its programs baseline`() {
        val snapshot = catalog(entries = listOf(active("Current", "R2.pgm", "R1.pgm")))

        assertEquals(
            MixGenerationPlan(name = "Current", programsBaseline = listOf("R2.pgm", "R1.pgm"), expectedRevision = 7L),
            resolveMixGenerationTarget(MixGenerationTarget.ReplaceActive("Current", expectedRevision = 7L), snapshot, "19mm")
        )
        assertEquals(null, resolveMixGenerationTarget(MixGenerationTarget.ReplaceActive("Old", 7L), snapshot, "19mm"))
        assertEquals(null, resolveMixGenerationTarget(MixGenerationTarget.ReplaceActive("Current", 6L), snapshot, "19mm"))
    }

    @Test
    fun `external catalog entry blocks every generation target`() {
        val snapshot = catalog(entries = listOf(external("Manual.mix")))

        listOf(
            MixGenerationTarget.FirstDefault,
            MixGenerationTarget.CreateAdditional("Second"),
            MixGenerationTarget.ReplaceActive("Current", 7L)
        ).forEach { target ->
            assertEquals(null, resolveMixGenerationTarget(target, snapshot, "19mm"))
        }
    }

    private fun catalog(entries: List<MixCatalogEntry>) = MixCatalogSnapshot(
        job = "100 - Alpha",
        material = "19mm",
        revision = 7L,
        entries = entries
    )

    private fun active(name: String, vararg programs: String) = MixCatalogEntry(
        name = name,
        mixFilename = "$name.mix",
        lifecycle = MixLifecycle.ACTIVE,
        programs = programs.toList()
    )

    private fun external(filename: String) = MixCatalogEntry(
        name = filename,
        mixFilename = filename,
        lifecycle = MixLifecycle.EXTERNAL
    )
}
