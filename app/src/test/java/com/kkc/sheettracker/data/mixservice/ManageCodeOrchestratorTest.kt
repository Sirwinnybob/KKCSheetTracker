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
    fun `catalog duplicate warning considers active entries and ignores archived membership`() {
        val warnings = findCrossMixDuplicates(
            programs = listOf("R1.pgm"),
            thisMixName = "New",
            catalog = catalog(entries = listOf(history("Old", "R1.pgm"), active("Live", "R2.pgm")))
        )

        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `replace target uses the selected mix baseline without overwriting unsaved operator edits`() {
        val rows = listOf(
            ManageCodeRow(1, listOf("R1.pgm"), "R1.pgm", null),
            ManageCodeRow(2, listOf("R2.pgm"), "R2.pgm", null),
            ManageCodeRow(3, listOf("R4.pgm"), "R4.pgm", null),
            ManageCodeRow(4, listOf("R3.pgm"), "R3.pgm", null)
        )
        val target = MixGenerationTarget.ReplaceActive(
            name = "Current",
            expectedRevision = 7L,
            programsBaseline = listOf("R3.pgm", "R4.pgm")
        )
        val plan = resolveMixGenerationTarget(target, catalog(entries = listOf(active("Current", "R3.pgm", "R4.pgm"))), "19mm")!!

        val change = buildManageCodeChange(
            rows = rows,
            selections = mapOf(
                "R1.pgm" to ManageCodeRowSelection(mix = true),
                "R2.pgm" to ManageCodeRowSelection(mix = false),
                "R4.pgm" to ManageCodeRowSelection(mix = false),
                "R3.pgm" to ManageCodeRowSelection(mix = true)
            ),
            locked = emptySet(),
            originalPrograms = plan.programsBaseline
        )

        assertEquals(listOf("R1.pgm", "R3.pgm"), change.programs)
        assertTrue(change.orderOrMembershipChanged)
    }

    @Test
    fun `first default generation resolves an empty baseline only for a catalog without active or external entries`() {
        val emptyCatalog = catalog(entries = emptyList())

        assertEquals(
            MixGenerationPlan(name = "19mmMix", programsBaseline = emptyList(), expectedRevision = 7L, mutation = MixCatalogMutation.CREATE),
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
            MixGenerationPlan(name = "Second", programsBaseline = emptyList(), expectedRevision = 7L, mutation = MixCatalogMutation.CREATE),
            resolveMixGenerationTarget(MixGenerationTarget.CreateAdditional("Second"), snapshot, "19mm")
        )
        assertEquals(null, resolveMixGenerationTarget(MixGenerationTarget.CreateAdditional("Current"), snapshot, "19mm"))
        assertEquals(null, resolveMixGenerationTarget(MixGenerationTarget.CreateAdditional(" Second "), snapshot, "19mm"))
        assertEquals(null, resolveMixGenerationTarget(MixGenerationTarget.CreateAdditional("   "), snapshot, "19mm"))
        assertEquals(null, resolveMixGenerationTarget(MixGenerationTarget.CreateAdditional("bad/name"), snapshot, "19mm"))
        assertEquals(null, resolveMixGenerationTarget(MixGenerationTarget.CreateAdditional("CON"), snapshot, "19mm"))
        assertEquals(null, resolveMixGenerationTarget(MixGenerationTarget.CreateAdditional("Old"), catalog(entries = listOf(history("Old"))), "19mm"))
        assertEquals(
            "Kitchen — (2)",
            resolveMixGenerationTarget(MixGenerationTarget.CreateAdditional("Kitchen — (2)"), snapshot, "19mm")?.name
        )
    }

    @Test
    fun `replace target requires exact active name and current revision while preserving its programs baseline`() {
        val snapshot = catalog(entries = listOf(active("Current", "R2.pgm", "R1.pgm")))

        assertEquals(
            MixGenerationPlan(name = "Current", programsBaseline = listOf("R2.pgm", "R1.pgm"), expectedRevision = 7L, mutation = MixCatalogMutation.REPLACE),
            resolveMixGenerationTarget(MixGenerationTarget.ReplaceActive("Current", expectedRevision = 7L, programsBaseline = listOf("R2.pgm", "R1.pgm")), snapshot, "19mm")
        )
        assertEquals(null, resolveMixGenerationTarget(MixGenerationTarget.ReplaceActive("Old", 7L, emptyList()), snapshot, "19mm"))
        assertEquals(null, resolveMixGenerationTarget(MixGenerationTarget.ReplaceActive("Current", 6L, listOf("R2.pgm", "R1.pgm")), snapshot, "19mm"))
        assertEquals(null, resolveMixGenerationTarget(MixGenerationTarget.ReplaceActive("Current", 7L, listOf("R1.pgm", "R2.pgm")), snapshot, "19mm"))
    }

    @Test
    fun `external catalog entry blocks every generation target`() {
        val snapshot = catalog(entries = listOf(external("Manual.mix")))

        listOf(
            MixGenerationTarget.FirstDefault,
            MixGenerationTarget.CreateAdditional("Second"),
            MixGenerationTarget.ReplaceActive("Current", 7L, emptyList())
        ).forEach { target ->
            assertEquals(null, resolveMixGenerationTarget(target, snapshot, "19mm"))
        }
    }

    @Test
    fun `active catalog replacement queues catalog before pgm edits and keeps revision`() {
        val target = MixGenerationTarget.ReplaceActive("Current", 7L, listOf("R1.pgm"))
        val plan = resolveMixGenerationTarget(
            target = target,
            catalog = catalog(entries = listOf(active("Current", "R1.pgm"))),
            materialName = "Maple"
        )!!
        val change = ManageCodeChange(
            orderOrMembershipChanged = true,
            programs = listOf("R2.pgm"),
            editRows = listOf(PgmEditRow("R2.pgm", "standard", removePUnload = false))
        )

        val actions = buildManageCodeActions(
            job = "100 - Alpha",
            material = "Maple",
            plan = plan,
            change = change,
            requestId = "edit-1"
        )

        assertEquals(listOf("catalog_replace", "pgm_edits"), actions.map { it.kind })
        assertEquals(7L, actions.first().expectedRevision)
        assertEquals("100 - Alpha", actions.first().job)
        assertEquals("edit-1", actions[1].requestId)
    }

    @Test
    fun `external delete action retains revision and only accepts exact external filename`() {
        val snapshot = catalog(
            entries = listOf(
                external("Manual.mix"),
                active("Current", "R1.pgm")
            )
        )

        val action = buildExternalDeleteAction("100 - Alpha", "19mm", snapshot, "Manual.mix")

        assertEquals(ManageCodeOperationAction.EXTERNAL_DELETE, action?.kind)
        assertEquals("Manual.mix", action?.externalMixFilename)
        assertEquals(7L, action?.expectedRevision)
        assertEquals(null, buildExternalDeleteAction("100 - Alpha", "19mm", snapshot, "manual.mix"))
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

    private fun history(name: String, vararg programs: String) = MixCatalogEntry(
        name = name,
        mixFilename = "$name.mix",
        lifecycle = MixLifecycle.HISTORY,
        programs = programs.toList()
    )
}
