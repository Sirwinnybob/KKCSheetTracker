package com.kkc.sheettracker.ui.mixes

import com.kkc.sheettracker.data.cnc.CncMachines
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.SheetStatus
import org.junit.Assert.*
import org.junit.Test

class MixesPresentationTest {
    private fun page(n: Int, vararg pgms: String, hidden: Boolean = false) =
        SheetPage(n, pgms.toList().ifEmpty { listOf("P$n.pgm") }, hidden, false, null)
    private fun entry(pgm: String, q: Int = 1, left: Int? = null) =
        MixFileEntry("C:\\Job\\$pgm", pgm, linkedMapOf("R" to "$q", "V" to "010.0"), "opaque", q, left, true, false)
    private fun row(file: String = "Run.mix", entries: List<MixFileEntry> = listOf(entry("P1.pgm"))) =
        MixFileRow(file, if (file.endsWith(".frz", true)) MixFileKind.FRZ else MixFileKind.MIX,
            null, null, "rev-$file", null, false, null, null, entries)
    private fun catalog(vararg rows: MixFileRow, revision: String = "folder-1") = MixFilesCatalog("Job", "Oak", revision, rows.toList())
    private fun material(c: MixFilesCatalog = catalog(row()), pages: List<SheetPage> = listOf(page(1)), statuses: Map<Int, SheetStatus> = emptyMap()) =
        MaterialMixesInput(CncMachines.scm, Material("Oak.pdf", "Oak", pages.size), CatalogState.Live(c), pages, statuses)
    private fun input(m: MaterialMixesInput = material(), draft: MixDraft? = null) =
        MixesUiInput(listOf(m), "Oak", draft = draft)
    private fun group(c: MixFilesCatalog) = groupMixFiles(c).first()
    private fun pair(frzEntries: List<MixFileEntry>, mixEntries: List<MixFileEntry>) = catalog(
        row("Run.mix", mixEntries).copy(companion = "Run.FRZ", companionMismatch = true),
        row("Run.FRZ", frzEntries).copy(companion = "Run.mix", companionMismatch = true),
    )
    private fun edited(c: MixFilesCatalog) = MixDrafts.start(group(c)).copy(changeCount = 1)
    private fun error(code: String, edit: String? = null, restored: Boolean? = null) =
        MixFilesError(422, code, "wire diagnostic", null, emptyList(), edit, "Lost.pgm", null, restored)

    @Test fun navChipsLeftDoneNoMixNoPgms() {
        val interrupted = catalog(row("Run.FRZ", listOf(entry("P1.pgm", left = 1))))
        val materials = listOf(
            material(interrupted),
            material().copy(material = Material("Done.pdf", "Done", 1), statusByPage = mapOf(1 to SheetStatus.COMPLETE)),
            material(catalog()).copy(material = Material("Empty.pdf", "Empty", 1)),
            material(pages = emptyList()).copy(material = Material("None.pdf", "None", 0)),
            material().copy(material = Material("Offline.pdf", "Offline", 1), catalogState = CatalogState.Offline(null)),
        )
        val nav = buildMixesUi(MixesUiInput(materials, "Oak")).nav
        assertEquals(listOf(MaterialChip.Left(1, true), MaterialChip.Done, MaterialChip.NoMix, MaterialChip.NoPgms, MaterialChip.Offline), nav.map { it.chip })
        assertTrue(nav.first().selected)
        assertEquals(listOf("scm"), nav.map { it.machineId }.distinct())
    }

    @Test fun groupsSplitRemainingUnmatchedNotInMixCompleted() {
        val c = catalog(row(entries = listOf(entry("P1.pgm"), entry("Unknown.pgm"), entry("P2.pgm"))))
        val model = buildMixesUi(input(material(c, listOf(page(1), page(2), page(3)), mapOf(2 to SheetStatus.COMPLETE))))
        val groups = model.groups!!
        assertEquals(listOf("Sheet 1 · qty 1"), groups.remaining.map { it.label })
        assertEquals(listOf("Unknown.pgm"), groups.unmatched.map { it.label })
        assertEquals(listOf(3), groups.notInMix.map { it.pageNumber })
        assertEquals(listOf(2), groups.completed.map { (it.item as MixItem.Sheet).page.pageNumber })
        assertEquals(1f / 3f, model.card!!.progress, 0.001f)
        assertEquals("1 of 3 cut · 2 remaining", model.card.summary)
    }

    @Test fun hasBadPartsIsLockedSkippedIsNot() {
        val c = catalog(row(entries = (1..4).map { entry("P$it.pgm") }))
        val model = buildMixesUi(input(material(c, (1..4).map { page(it) }, mapOf(
            1 to SheetStatus.HAS_BAD_PARTS, 2 to SheetStatus.SKIPPED, 3 to SheetStatus.RE_NESTED, 4 to SheetStatus.IN_PROGRESS))))
        assertEquals(listOf(1, 3), model.groups!!.completed.map { (it.item as MixItem.Sheet).page.pageNumber })
        model.groups.completed.forEach {
            assertTrue(it.locked); assertFalse(it.draggable); assertTrue(it.canRemove)
            assertFalse(it.canEditCounts); assertFalse(it.canEditParameters); assertFalse(it.canSplit)
        }
        assertTrue(model.groups.remaining.all { it.draggable })
    }

    @Test fun disagreementFlaggedWhenTabletAndFrzDiffer() {
        val c = catalog(row("Run.FRZ", listOf(entry("P1.pgm", left = 1), entry("P2.pgm", left = 0), entry("P3.pgm", left = 0))))
        val groups = buildMixesUi(input(material(c, (1..3).map { page(it) }, mapOf(1 to SheetStatus.COMPLETE, 3 to SheetStatus.COMPLETE)))).groups!!
        assertTrue(groups.completed.first().disagreement)
        assertEquals(1, groups.completed.first().frzRemaining)
        assertFalse(groups.completed.last().disagreement)
        assertTrue(groups.remaining.single().disagreement)
        assertFalse(groups.remaining.single().locked)
        assertTrue(groups.completed.none { it.canRemove })
    }

    @Test fun unmatchedBlocksApplyOnInterruptedOnly() {
        for (file in listOf("Run.mix", "Run.FRZ")) {
            val c = catalog(row(file, listOf(entry("Unknown.pgm"))))
            val ui = buildMixesUi(input(material(c), edited(c)))
            assertEquals(file.endsWith("mix"), ui.card!!.applyEnabled)
            if (file.endsWith("FRZ")) assertEquals("1 sheets in this mix don't match the job — fix them before saving", ui.card.applyBlockedReason)
            assertEquals(listOf(RowChip.UNMATCHED), ui.groups!!.unmatched.single().chips)
        }
    }

    @Test fun applyLabelShowsChangeCount() {
        val c = catalog(row())
        val card = buildMixesUi(input(material(c), edited(c).copy(changeCount = 3))).card!!
        assertEquals("Apply 3 changes", card.applyLabel)
        assertTrue(card.applyVisible)
        assertTrue(card.applyEnabled)
        assertFalse(buildMixesUi(input()).card!!.applyVisible)
        assertFalse(buildMixesUi(input()).card!!.applyEnabled)
    }
    @Test fun completedStagedSplitBlocksApplyIncludingAzRepeatsWhileSecondPassStaysAvailable() {
        for (interrupted in listOf(false, true)) {
            val entries = listOf(entry("AzA.pgm", left = 1), entry("AzZ.pgm", left = 1), entry("AzA.pgm", left = 1), entry("P2.pgm", left = 1))
            val c = if (interrupted) pair(entries, entries) else catalog(row(entries = entries))
            val draft = MixDrafts.splitOff(MixDrafts.start(group(c)), setOf(1, 2, 3), "Other.mix")
            val pages = listOf(page(1, "AzA.pgm", "AzZ.pgm"), page(2))
            val second = SecondPassDrafts.setAll(SecondPassDrafts.from(com.kkc.sheettracker.data.mixservice.PgmEditHistoryView(ok = true, stateSource = "ledger"), listOf("P2.pgm")), listOf("P2.pgm"), SecondPassField.SECOND, true)
            for (status in listOf(SheetStatus.COMPLETE, SheetStatus.HAS_BAD_PARTS, SheetStatus.RE_NESTED)) {
                val ui = buildMixesUi(input(material(c, pages, mapOf(1 to status)), draft).copy(secondPassDraft = second))
                assertFalse("$status must block a $interrupted split", ui.card!!.applyEnabled)
                assertTrue(ui.card.applyVisible) // Discard remains reachable rather than silently dropping the split.
                assertEquals("Completed sheets are staged for a new mix: AzA.pgm, AzZ.pgm. Discard the mix changes and split only pending sheets.", ui.card.applyBlockedReason)
                assertTrue(ui.secondPassApplyEnabled)
                assertEquals(3, draft.splitOff.single().entries.size)
            }
            for (status in listOf(SheetStatus.SKIPPED, SheetStatus.IN_PROGRESS, SheetStatus.NOT_STARTED)) {
                assertTrue(buildMixesUi(input(material(c, pages, mapOf(1 to status)), draft)).card!!.applyEnabled)
            }
        }
    }

    @Test fun secondPassApplyIsSeparate() {
        val current = mapOf("P1.pgm" to SecondPassState(false, false, false), "P2.pgm" to SecondPassState(false, false, false))
        val second = SecondPassDraft(current.mapValues { SecondPassState(true, false, false) }, current)
        val ui = buildMixesUi(input().copy(secondPassDraft = second))
        assertEquals("Apply 2nd pass (2)", ui.secondPassApply)
        assertTrue(ui.secondPassApplyEnabled)
        assertFalse(ui.card!!.applyEnabled)
        assertTrue(buildMixesUi(input(draft = edited(catalog(row()))).copy(secondPassDraft = second, conflict = true)).secondPassApplyEnabled)
    }

    @Test fun catalogUpdateDuringDragIsDeferred() {
        val gate = CatalogGate()
        val first = catalog(row())
        val second = first.copy(folderRevision = "folder-2")
        val latest = first.copy(folderRevision = "folder-3")
        assertEquals(GateDecision.APPLY, gate.onCatalog(first, false, false))
        assertEquals(GateDecision.DEFER, gate.onCatalog(second, true, false))
        assertEquals(GateDecision.DEFER, gate.onCatalog(latest, true, false))
        assertEquals(first, gate.appliedCatalog)
        assertEquals(latest, gate.deferredCatalog)
        assertEquals(GateDecision.APPLY, gate.onDrop(false))
        assertEquals(latest, gate.appliedCatalog)
        assertNull(gate.deferredCatalog)
        assertEquals(GateDecision.DEFER, gate.onCatalog(second, true, true))
        assertEquals(GateDecision.CONFLICT, gate.onDrop(true))
        assertEquals(latest, gate.appliedCatalog)
        assertEquals(second, gate.deferredCatalog)
        gate.accept(second)
        assertNull(gate.deferredCatalog)
        assertEquals(second, gate.appliedCatalog)
    }

    @Test fun changedRevisionWithDraftShowsBannerAndBlocksApply() {
        val c = catalog(row())
        val ui = buildMixesUi(input(material(c), edited(c)).copy(draftFolderRevision = "older"))
        assertEquals(Banner.ChangedOnCnc, ui.banner)
        assertFalse(ui.card!!.applyEnabled)
        val rejected = buildMixesUi(input(material(c), edited(c)).copy(applyResult = MixFilesResult.Rejected(error("revision_conflict"))))
        assertEquals(Banner.ChangedOnCnc, rejected.banner)
        assertFalse(rejected.card!!.applyEnabled)
    }

    @Test fun offlineDuringApplyRefetchesInsteadOfRetrying() {
        val c = catalog(row())
        val ui = buildMixesUi(input(material(c), edited(c)).copy(applyResult = MixFilesResult.Offline))
        assertEquals(Banner.SaveUnconfirmed, ui.banner)
        assertTrue(ui.refreshNow)
        assertFalse(ui.card!!.applyEnabled)
        val cached = buildMixesUi(input(material(c).copy(catalogState = CatalogState.Offline(c)), edited(c)))
        assertEquals(Banner.Offline, cached.banner)
        assertFalse(cached.refreshNow)
        assertTrue(cached.readOnly)
        assertNotNull(cached.groups)
    }

    @Test fun outdatedServiceIsReadOnly() {
        val m = material().copy(catalogState = CatalogState.Outdated(catalog(row())))
        val ui = buildMixesUi(input(m, edited(catalog(row()))))
        assertEquals(Banner.ServiceOutdated, ui.banner)
        assertEquals("The CNC mix service needs updating", ui.card!!.applyBlockedReason)
        assertTrue(ui.readOnly)
        assertFalse(ui.card.applyEnabled)
        assertFalse(ui.groups!!.remaining.single().draggable)
        assertFalse(ui.groups.remaining.single().canRemove)
    }

    @Test fun errorCopyForEachCode() {
        val expected = linkedMapOf(
            "revision_conflict" to "This mix changed on the CNC",
            "name_exists" to "A mix with this name already exists. Choose another name.",
            "not_found" to "This mix or material could not be found. Refresh and try again.",
            "missing_program" to "PGM file missing: Lost.pgm",
            "mix_unreadable" to "Can't read this mix. Restore a revision or delete it.",
            "invalid_entries" to "The CNC couldn't accept these mix entries. Check the sheets and counts.",
            "companion_requires_completion" to "Refresh this interrupted mix before saving its completion state.",
            "frz_completion_mismatch" to "The tablet and FRZ disagree about completed sheets. Choose which to use for each sheet.",
            "frz_edit_disabled" to "Adding / removing / count changes on interrupted mixes are switched off on the CNC",
            "machine_busy_with_mix" to "The CNC is cutting this mix. Pause it first.",
            "state_unreadable" to "Can't read the CNC's state right now. Try again in a moment.",
            "companion_write_failed" to "The companion mix couldn't be saved. Refresh and check both files before trying again.",
            "write_failed" to "The CNC couldn't save the mix. The previous file is intact. Try again.",
            "bad_request" to "The CNC couldn't understand this request. Refresh and try again.",
            "request_too_large" to "This mix is too large to save in one request.",
            "internal" to "The CNC mix service encountered an error. Try again in a moment.",
            "server_busy" to "The CNC mix service is busy. Try again in a moment.",
        )
        expected.forEach { (code, copy) -> assertEquals(code, copy, errorCopy(error(code))) }
        assertEquals("Adding sheets to interrupted mixes is switched off on the CNC", errorCopy(error("frz_edit_disabled", "add")))
        assertEquals("Removing sheets from interrupted mixes is switched off on the CNC", errorCopy(error("frz_edit_disabled", "remove")))
        assertEquals("Count changes on interrupted mixes are switched off on the CNC", errorCopy(error("frz_edit_disabled", "count")))
        assertEquals("wire diagnostic", errorCopy(error("future_code")))
        assertTrue(errorCopy(error("companion_write_failed", restored = false)).contains("couldn't be restored"))
    }

    @Test fun draftRowsKeepIdentityOrderCountsParametersAndSourceIndices() {
        val c = catalog(row(entries = listOf(entry("P1.pgm"), entry("P1.pgm", 2), entry("P2.pgm"))))
        val start = MixDrafts.start(group(c))
        var d = MixDrafts.move(start, start.entries[1].id, 0)
        d = MixDrafts.setParameters(d, d.entries.first().id, linkedMapOf("R" to "4", "V" to "opaque spelling"))
        val rows = buildMixesUi(input(material(c, listOf(page(1), page(2))), d)).groups!!.remaining
        assertEquals(listOf(2L, 1L, 3L), rows.map { it.key })
        assertEquals(listOf(4, 1, 1), rows.map { (it.item as MixItem.Sheet).quantity })
        assertEquals(listOf(listOf(1), listOf(0), listOf(2)), rows.map { it.sourceEntryIndices })
        assertEquals("opaque spelling", rows.first().entries.single().parameters!!["V"])
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
    }

    @Test fun collapsedAzRowsExposeBothDraftIdsAndSplitAfterUnequalCount() {
        val c = catalog(row(entries = listOf(entry("P1A.pgm"), entry("P1Z.pgm"), entry("P2.pgm"))))
        val pages = listOf(page(1, "P1A.pgm", "P1Z.pgm"), page(2))
        val d = MixDrafts.start(group(c))
        val first = buildMixesUi(input(material(c, pages), d)).groups!!.remaining.first()
        assertEquals(listOf(1L, 2L), first.draftEntryIds)
        assertEquals(listOf(0, 1), first.sourceEntryIndices)
        assertEquals(listOf(0, 1), first.item.entryIndices)
        val moved = MixDrafts.move(MixDrafts.move(d, 2, 2), 1, 1)
        val rows = buildMixesUi(input(material(c, pages), moved)).groups!!.remaining
        assertEquals(listOf(3L, 1L), rows.map { it.key })
        assertEquals(listOf(1L, 2L), rows.last().draftEntryIds)
        val unequal = MixDrafts.setQuantity(d, 2, 2, null)
        assertEquals(listOf("Sheet 1 A · qty 1", "Sheet 1 Z · qty 2", "Sheet 2 · qty 1"),
            buildMixesUi(input(material(c, pages), unequal)).groups!!.remaining.map { it.label })
    }

    @Test fun newEntriesAndFrzOnlyExposeSupportedActions() {
        val c = catalog(row("Run.FRZ", listOf(entry("P1.pgm", left = 0))))
        val d = MixDrafts.add(MixDrafts.start(group(c)), "P2.pgm")
        val ui = buildMixesUi(input(material(c, listOf(page(1), page(2))), d))
        val new = ui.groups!!.remaining.last()
        assertTrue(new.draggable); assertTrue(new.canRemove)
        assertFalse(new.canEditCounts); assertFalse(new.canEditParameters); assertFalse(new.canSplit)
        assertEquals("Apply the new sheet before editing its quantity or parameters", new.editBlockedReason)
        assertEquals(listOf<Int?>(null), new.sourceEntryIndices)
        assertFalse(ui.card!!.splitEnabled)
    }

    @Test fun rowChipsAndNotInMixMembershipComeFromAllActiveMixes() {
        val bad = entry("P1.pgm").copy(pgmExists = false, headerDrift = true)
        val c = catalog(row(entries = listOf(bad)), row("Other.mix", listOf(entry("P1.pgm"), entry("P2.pgm"))))
        val ui = buildMixesUi(input(material(c, listOf(page(1, hidden = true), page(2))), null).copy(selectedTab = "run.mix"))
        assertEquals(setOf(RowChip.PGM_MISSING, RowChip.NEEDS_REFRESH, RowChip.HIDDEN_PAGE, RowChip.IN_OTHER_MIX), ui.groups!!.remaining.single().chips.toSet())
        assertEquals(mapOf(2 to listOf("Other")), ui.groups.notInMixOtherMixes)
        assertEquals(listOf(2), ui.groups.notInMix.map { it.pageNumber })
    }

    @Test fun revisionOnlyGroupsAreNotActiveTabsAndUnreadableMixIsReadOnly() {
        val archive = row("OLD1_Gone.mix").copy(kind = MixFileKind.REVISION, revisionOf = "Gone.mix", revisionNumber = 1)
        val c = catalog(archive)
        val ui = buildMixesUi(input(material(c)))
        assertTrue(ui.tabs.isEmpty()); assertNull(ui.selectedTab); assertNull(ui.card)
        val unreadable = buildMixesUi(input(material(catalog(row().copy(error = "invalid header")))))
        assertTrue(unreadable.readOnly)
        assertEquals(Banner.Message("invalid header"), unreadable.banner)
        assertTrue(unreadable.tabs.single().error)
    }

    @Test fun interruptedSuccessfulSavePromptsPanelmacReload() {
        val c = catalog(row("Run.FRZ"))
        val ui = buildMixesUi(input(material(c)).copy(applyResult = MixFilesResult.Ok(c), savedInterrupted = true))
        assertEquals(Banner.Message("Reload this mix in PanelMac"), ui.banner)
        assertFalse(ui.refreshNow)
    }

    @Test fun deletedActiveFileRetainsDraftTabAndRowsUntilConflictResolution() {
        val original = catalog(row(entries = listOf(entry("P1.pgm"), entry("P2.pgm"))))
        val d = MixDrafts.move(MixDrafts.start(group(original)), 2, 0)
        val ui = buildMixesUi(input(material(catalog(revision = "deleted"), listOf(page(1), page(2))), d))
        assertEquals("run.mix", ui.selectedTab)
        assertEquals(listOf("run.mix"), ui.tabs.map { it.key })
        assertEquals(listOf(2L, 1L), ui.groups!!.remaining.map { it.key })
        assertEquals(Banner.ChangedOnCnc, ui.banner)
        assertFalse(ui.card!!.applyEnabled)
    }

    @Test fun newMixWithoutActiveFilesHasDraftTabAndCannotSplitBeforeSaving() {
        val pages = listOf(page(1), page(2))
        val d = MixDrafts.startNew("First.mix", pages, emptySet())
        val ui = buildMixesUi(input(material(catalog(), pages), d))
        assertEquals("first.mix", ui.selectedTab)
        assertEquals("First", ui.tabs.single().title)
        assertTrue(ui.card!!.applyEnabled)
        assertFalse(ui.card.splitEnabled)
        assertEquals(listOf(1L, 2L), ui.groups!!.remaining.map { it.key })
        assertTrue(ui.groups.remaining.none { it.canEditCounts || it.canEditParameters })
    }

    @Test fun continuationPagesAreMarkedAndDoNotOfferDuplicateAddsForMatchedHeadPgms() {
        val continuation = page(2, "P1.pgm").copy(continuation = true)
        val withHead = buildMixesUi(input(material(catalog(row()), listOf(page(1), continuation))))
        assertTrue(withHead.groups!!.notInMix.isEmpty())
        val fallback = buildMixesUi(input(material(catalog(row()), listOf(continuation))))
        assertEquals(listOf(RowChip.HIDDEN_PAGE), fallback.groups!!.remaining.single().chips)
        assertFalse(fallback.groups.remaining.single().locked)
    }

    @Test fun companionOnlyUnmatchedIsVisibleReadOnlyAndBlocksInterruptedApply() {
        val lost = entry("Lost.pgm").copy(pgmExists = false, headerDrift = true)
        val c = pair(listOf(entry("P1.pgm", left = 1)), listOf(entry("P1.pgm"), lost))
        val ui = buildMixesUi(input(material(c), edited(c)))
        assertEquals(listOf("Lost.pgm"), ui.groups!!.unmatched.map { it.label })
        assertFalse(ui.card!!.applyEnabled)
        assertEquals("1 sheets in this mix don't match the job — fix them before saving", ui.card.applyBlockedReason)
        val orphan = ui.groups.unmatched.single()
        assertTrue(orphan.companionOnly)
        assertEquals("Run.mix", orphan.sourceFile)
        assertEquals(lost, orphan.entries.single().source)
        assertEquals(listOf(1), orphan.sourceEntryIndices)
        assertTrue(orphan.draftEntryIds.isEmpty())
        assertFalse(orphan.draggable || orphan.canRemove || orphan.canEditCounts || orphan.canEditParameters || orphan.canSplit)
        assertEquals(setOf(RowChip.UNMATCHED, RowChip.PGM_MISSING, RowChip.NEEDS_REFRESH), orphan.chips.toSet())
        assertTrue(orphan.editBlockedReason!!.contains("Run.mix"))
        assertEquals(listOf("P1.pgm"), ui.groups.remaining.flatMap { (it.item as MixItem.Sheet).pgms })
    }

    @Test fun pairUnmatchedDedupesSharedPathsButPreservesExtraOccurrencesAndDifferentPaths() {
        val shared = entry("Shared.pgm", left = 1)
        val sharedMix = entry("shared.PGM", 3).copy(path = shared.path.lowercase())
        val differentPath = entry("Shared.pgm").copy(path = "D:\\OtherJob\\Shared.pgm")
        val repeated = entry("Extra.pgm")
        val c = pair(listOf(entry("P1.pgm", left = 1), shared), listOf(
            entry("P1.pgm"), sharedMix, sharedMix, differentPath, repeated, repeated,
        ))
        val ui = buildMixesUi(input(material(c), edited(c)))
        val unmatched = ui.groups!!.unmatched
        assertEquals(listOf("Shared.pgm", "shared.PGM", "Shared.pgm", "Extra.pgm", "Extra.pgm"), unmatched.map { it.label })
        assertEquals(listOf(shared.path, sharedMix.path, differentPath.path, repeated.path, repeated.path), unmatched.map { it.entries.single().source!!.path })
        assertEquals(listOf(listOf(1), listOf(2), listOf(3), listOf(4), listOf(5)), unmatched.map { it.sourceEntryIndices })
        assertEquals(5, unmatched.map { it.key }.distinct().size)
        assertEquals(1, unmatched.count { it.canRemove })
        assertEquals(listOf(false, true, true, true, true), unmatched.map { it.companionOnly })
        assertEquals(listOf("Run.FRZ", "Run.mix", "Run.mix", "Run.mix", "Run.mix"), unmatched.map { it.sourceFile })
        assertFalse(ui.card!!.applyEnabled)
        assertEquals("5 sheets in this mix don't match the job — fix them before saving", ui.card.applyBlockedReason)
        val noDraft = buildMixesUi(input(material(c))).groups!!.unmatched
        assertEquals(unmatched.map { it.key }, noDraft.map { it.key })
    }

    @Test fun removingSharedUnmatchedFromPrimaryDraftDoesNotResurrectItFromCompanion() {
        val shared = entry("Shared.pgm", left = 1)
        val c = pair(listOf(entry("P1.pgm", left = 1), shared), listOf(entry("P1.pgm"), shared.copy(remaining = null)))
        val start = MixDrafts.start(group(c))
        val removed = MixDrafts.remove(start, start.entries.last().id)
        val ui = buildMixesUi(input(material(c), removed))
        assertTrue(ui.groups!!.unmatched.isEmpty())
        assertEquals(listOf(1L), ui.groups.remaining.map { it.key })
        assertTrue(ui.card!!.applyEnabled)
        assertNull(ui.card.applyBlockedReason)
    }
}
