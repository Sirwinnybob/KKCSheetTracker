package com.kkc.sheettracker.data.mixfiles

import org.junit.Assert.*
import org.junit.Test

class MixDraftTest {
    private fun entry(pgm: String, quantity: Int = 1, remaining: Int? = null, path: String = "C:\\Jobs\\$pgm") =
        MixFileEntry(path, pgm, linkedMapOf("R" to "$quantity", "V" to "010.0"), "AD", quantity, remaining, true, false)

    private fun row(file: String, entries: List<MixFileEntry>, revision: String = "base-$file") =
        MixFileRow(file, if (file.endsWith(".frz", true)) MixFileKind.FRZ else MixFileKind.MIX,
            null, null, revision, null, false, null, null, entries)

    private fun group(entries: List<MixFileEntry>, interrupted: Boolean = false, frzOnly: Boolean = false) =
        MixGroup("run.mix", "run", if (frzOnly) null else row("Run.mix", entries),
            if (interrupted || frzOnly) row("Run.FRZ", entries) else null, emptyList(), emptyList())

    private fun names(draft: MixDraft) = draft.entries.map { it.source?.pgm ?: it.newPgm }
    private fun write(draft: MixDraft, group: MixGroup? = null, locks: List<String> = emptyList()) =
        buildWrite(draft, group, locks, emptyMap())

    @Test fun plainMixReorderBuildsWriteWithFullEntries() {
        val g = group(listOf(entry("A.pgm"), entry("B.pgm")))
        val d = MixDrafts.start(g)
        val changed = MixDrafts.move(d, d.entries.last().id, 0)
        val request = write(changed, g) as MixWrite.Write
        assertEquals("Run.mix", request.file)
        assertEquals("base-Run.mix", request.expectedRevision)
        assertEquals(listOf("B.pgm", "A.pgm"), names(changed))
        assertEquals(EntrySpec.Existing(g.mix!!.entries[1].path, g.mix.entries[1].parameters, "AD"), request.entries[0])
        assertNull(request.completion)
        assertEquals(1, changed.changeCount)
    }

    @Test fun existingOutsideEntryIsSentAsFullEntry() {
        val outside = entry("R1.pgm", path = "C:\\CNC\\Old Jobs 2024\\Job\\R1.pgm").copy(pgmExists = false)
        val g = group(listOf(outside))
        assertEquals(listOf(EntrySpec.Existing(outside.path, outside.parameters, outside.fields)),
            (write(MixDrafts.start(g), g) as MixWrite.Write).entries)
    }

    @Test fun addedPgmIsNewPgmOnly() {
        val g = group(listOf(entry("A.pgm")))
        val request = write(MixDrafts.add(MixDrafts.start(g), "B.pgm"), g) as MixWrite.Write
        assertEquals(EntrySpec.NewPgm("B.pgm"), request.entries.last())
        assertEquals(setOf("pgm"), MixFilesJson.entry(request.entries.last()).keySet())
    }

    @Test fun reorderWithRepeatedPgmMovesOnlyThatEntry() {
        val g = group(listOf(entry("A.pgm", 1), entry("A.pgm", 2), entry("B.pgm")))
        val d = MixDrafts.start(g)
        assertEquals(3, d.entries.map { it.id }.toSet().size)
        val changed = MixDrafts.move(d, d.entries[1].id, 0)
        assertEquals(listOf(d.entries[1].id, d.entries[0].id, d.entries[2].id), changed.entries.map { it.id })
        assertEquals(listOf(2, 1, 1), changed.entries.map { it.quantity })
    }

    @Test fun pairEditUsesFrzWithCompanionRevision() {
        val g = group(listOf(entry("A.pgm", 3, 2)), interrupted = true)
        val d = MixDrafts.start(g)
        val fresh = g.copy(mix = g.mix!!.copy(revision = "later-mix"), frz = g.frz!!.copy(revision = "later-frz"))
        val request = buildWrite(d, fresh, listOf("Done.pgm"), mapOf("Done.pgm" to "tablet")) as MixWrite.Frz
        assertEquals("Run.FRZ", request.file)
        assertEquals("base-Run.FRZ", request.expectedRevision)
        assertEquals(Completion(listOf("Done.pgm"), mapOf("Done.pgm" to "tablet"), "base-Run.mix"), request.completion)
    }

    @Test fun frzPendingOmitsLockedAndUnchangedCounts() {
        val g = group(listOf(entry("Done.pgm", 2, 0), entry("A.pgm", 3, 2), entry("B.pgm", 1, 0)), frzOnly = true)
        val request = write(MixDrafts.start(g, listOf("done.PGM")), g, listOf("done.PGM")) as MixWrite.Frz
        assertEquals(listOf(EntrySpec.Pending("A.pgm", null, null), EntrySpec.Pending("B.pgm", null, null)), request.pending)
        assertNull(request.completion.companionExpectedRevision)
    }

    @Test fun setParametersOnFrzIsRejected() {
        val d = MixDrafts.start(group(listOf(entry("A.pgm", remaining = 1)), frzOnly = true))
        assertThrows(IllegalArgumentException::class.java) { MixDrafts.setParameters(d, d.entries[0].id, linkedMapOf("V" to "20")) }
    }

    @Test fun quantityEditOnFrzDefaultsRemainingToQuantity() {
        val g = group(listOf(entry("A.pgm", 3, 1)), interrupted = true)
        val d = MixDrafts.start(g)
        val changed = MixDrafts.setQuantity(d, d.entries[0].id, 2, null)
        assertEquals(2, changed.entries[0].remaining)
        assertEquals(listOf(EntrySpec.Pending("A.pgm", 2, 2)), (write(changed, g) as MixWrite.Frz).pending)
        assertEquals(2, MixDrafts.setQuantity(d, d.entries[0].id, 2, 9).entries[0].remaining)
        assertThrows(IllegalArgumentException::class.java) { MixDrafts.setQuantity(d, d.entries[0].id, 0, null) }
    }

    @Test fun splitOfPairUsesSplitOnMixWithCompanionRevision() {
        val g = group(listOf(entry("A.pgm", 3, 2), entry("B.pgm", 2, 1)), interrupted = true)
        val d = MixDrafts.start(g)
        val changed = MixDrafts.splitOff(d, setOf(d.entries[1].id), "Other.mix")
        val fresh = g.copy(mix = g.mix!!.copy(revision = "new-mix"), frz = g.frz!!.copy(revision = "new-frz"))
        val request = write(changed, fresh) as MixWrite.Split
        assertEquals("Run.mix", request.file)
        assertEquals("base-Run.mix", request.expectedRevision)
        assertEquals("base-Run.FRZ", request.completion!!.companionExpectedRevision)
        assertEquals(listOf(EntrySpec.Existing(d.entries[0].source!!.path, d.entries[0].source!!.parameters, "AD")), request.entries)
        assertEquals(listOf(NewMix("Other.mix", listOf(EntrySpec.Existing(d.entries[1].source!!.path, d.entries[1].source!!.parameters, "AD")))), request.newMixes)
    }

    @Test fun lockedEntryCannotBeSplitOff() {
        val g = group(listOf(entry("Done.pgm", remaining = 0), entry("A.pgm", remaining = 1)), interrupted = true)
        val d = MixDrafts.start(g, listOf("DONE.pgm"))
        val id = d.entries.first().id
        assertEquals(d, MixDrafts.splitOff(d, setOf(id), "Other.mix"))
        assertEquals(d, MixDrafts.move(d, id, 1))
        assertEquals(d, MixDrafts.remove(d, id))
        assertEquals(d, MixDrafts.setQuantity(d, id, 2, 1))
        val plain = MixDrafts.start(g.copy(frz = null), listOf("Done.pgm"))
        assertEquals(1, MixDrafts.remove(plain, plain.entries[0].id).entries.size)
        assertEquals(plain, MixDrafts.setParameters(plain, plain.entries[0].id, linkedMapOf("R" to "2")))
    }

    @Test fun newMixDraftHasNullExpectedRevisionAndNaturalOrder() {
        val pages = listOf(SheetPage(10, listOf("R10.pgm"), false, false, null),
            SheetPage(2, listOf("R2A.pgm", "R2Z.pgm"), false, false, null),
            SheetPage(1, listOf("R1.pgm"), false, false, null))
        val d = MixDrafts.startNew("New.mix", pages, setOf("r1.PGM"))
        assertEquals(listOf("R2A.pgm", "R2Z.pgm", "R10.pgm"), names(d))
        val request = write(d) as MixWrite.Write
        assertNull(request.expectedRevision)
        assertEquals("New.mix", request.file)
    }

    @Test fun fromRevisionOfPairKeepsOnlyOrderOfPendingSheets() {
        val g = group(listOf(entry("Done.pgm", remaining = 0), entry("A.pgm", 3, 2), entry("B.pgm", 2, 1)), interrupted = true)
        val revision = row("OLD1_Run.mix", listOf(entry("B.pgm", 8), entry("Done.pgm"), entry("A.pgm", 8), entry("Gone.pgm")))
        val d = MixDrafts.fromRevision(g, revision, listOf("Done.pgm"))
        assertEquals(listOf("Done.pgm", "B.pgm", "A.pgm"), names(d))
        assertEquals(listOf(0, 1, 2), d.entries.map { it.remaining })
        assertEquals(listOf(EntrySpec.Pending("B.pgm", null, null), EntrySpec.Pending("A.pgm", null, null)),
            (write(d, g, listOf("Done.pgm")) as MixWrite.Frz).pending)
        assertThrows(IllegalArgumentException::class.java) { MixDrafts.fromRevision(g, row("OLD1_Run.FRZ", emptyList())) }
    }

    @Test fun rebaseKeepsUserOrderAndReportsDropped() {
        val g = group(listOf(entry("A.pgm"), entry("B.pgm"), entry("Gone.pgm"), entry("Remove.pgm")))
        val initial = MixDrafts.start(g)
        var d = MixDrafts.move(initial, initial.entries[1].id, 0)
        d = MixDrafts.add(MixDrafts.remove(d, initial.entries[3].id), "Added.pgm")
        val fresh = group(listOf(entry("A.pgm"), entry("B.pgm"), entry("Remove.pgm"), entry("Fresh.pgm")))
            .copy(mix = row("Run.mix", listOf(entry("A.pgm"), entry("B.pgm"), entry("Remove.pgm"), entry("Fresh.pgm")), "fresh"))
        val result = MixDrafts.rebase(d, fresh)
        assertEquals(listOf("B.pgm", "A.pgm", "Added.pgm", "Fresh.pgm"), names(result.draft))
        assertEquals(listOf("Gone.pgm"), result.dropped)
        assertEquals("fresh", result.draft.baseMixRevision)
    }

    @Test fun rebasePreservesRepeatedOccurrenceEditsAndFreshUntouchedValues() {
        val a = entry("A.pgm", 1)
        val b = entry("A.pgm", 2)
        val g = group(listOf(a, b, entry("B.pgm")))
        val d = MixDrafts.start(g)
        val changed = MixDrafts.setQuantity(MixDrafts.move(d, d.entries[1].id, 0), d.entries[1].id, 4, null)
        val fresh = group(listOf(a.copy(parameters = linkedMapOf("R" to "1", "V" to "99")), b, entry("B.pgm", 3)))
        val rebased = MixDrafts.rebase(changed, fresh).draft
        assertEquals(listOf(d.entries[1].id, d.entries[0].id, d.entries[2].id), rebased.entries.map { it.id })
        assertEquals(listOf(4, 1, 3), rebased.entries.map { it.quantity })
        assertEquals("99", rebased.entries[1].parameters!!["V"])
    }

    @Test fun noOpAndUndoOperationsClearChangeCount() {
        val g = group(listOf(entry("A.pgm"), entry("B.pgm")))
        val d = MixDrafts.start(g)
        assertEquals(d, MixDrafts.move(d, d.entries[0].id, 0))
        assertEquals(d, MixDrafts.remove(d, -1))
        assertEquals(d, MixDrafts.setQuantity(d, d.entries[0].id, 1, null))
        val moved = MixDrafts.move(d, d.entries[1].id, 0)
        assertEquals(0, MixDrafts.move(moved, d.entries[1].id, 1).changeCount)
        val added = MixDrafts.add(d, "X.pgm")
        assertEquals(0, MixDrafts.remove(added, added.entries.last().id).changeCount)
        val changed = MixDrafts.setParameters(d, d.entries[0].id, linkedMapOf("R" to "1", "V" to "22"))
        assertEquals(0, MixDrafts.setParameters(changed, changed.entries[0].id, g.mix!!.entries[0].parameters).changeCount)
    }

    @Test fun plainQuantityAndParameterEditsRetainOpaqueFields() {
        val g = group(listOf(entry("A.pgm", 3)))
        val d = MixDrafts.start(g)
        val changed = MixDrafts.setQuantity(d, d.entries[0].id, 2, null)
        val request = write(changed, g) as MixWrite.Write
        assertEquals(EntrySpec.Existing(g.mix!!.entries[0].path, linkedMapOf("R" to "2", "V" to "010.0"), "AD"), request.entries.single())
    }

    @Test fun frzOnlySplitIsExplicitlyUnsupported() {
        val d = MixDrafts.start(group(listOf(entry("A.pgm", remaining = 1)), frzOnly = true))
        val error = assertThrows(IllegalArgumentException::class.java) { MixDrafts.splitOff(d, setOf(d.entries[0].id), "Other.mix") }
        assertTrue(error.message!!.contains("FRZ-only"))
    }

    @Test fun newPgmCountAndParameterEditsRequireInitialApply() {
        val d = MixDrafts.add(MixDrafts.start(group(emptyList())), "New.pgm")
        val id = d.entries.single().id
        val error = assertThrows(IllegalArgumentException::class.java) { MixDrafts.setQuantity(d, id, 2, null) }
        assertEquals("Apply the new sheet before editing its quantity or parameters", error.message)
        assertThrows(IllegalArgumentException::class.java) { MixDrafts.setParameters(d, id, linkedMapOf("R" to "2")) }
    }

    @Test fun pairSplitFullEntriesCarryOnlyChangedCounts() {
        val g = group(listOf(entry("A.pgm", 3, 2), entry("B.pgm", remaining = 1)), interrupted = true)
        val d = MixDrafts.start(g)
        val counted = MixDrafts.setQuantity(d, d.entries[0].id, 2, 1)
        val split = MixDrafts.splitOff(counted, setOf(d.entries[1].id), "Other.mix")
        val spec = ((write(split, g) as MixWrite.Split).entries.single() as EntrySpec.Existing)
        val json = MixFilesJson.entry(spec)
        assertEquals(d.entries[0].source!!.path, json["path"].asString)
        assertEquals("010.0", json.getAsJsonObject("parameters")["V"].asString)
        assertEquals(2, json["quantity"].asInt)
        assertEquals(1, json["remaining"].asInt)
    }

    @Test fun repeatedSharedSourceObjectsRemainDistinctOnRebase() {
        val a = entry("A.pgm")
        val g = group(listOf(a, a, entry("B.pgm")))
        val d = MixDrafts.start(g)
        val changed = MixDrafts.move(d, d.entries[1].id, 0)
        val rebased = MixDrafts.rebase(changed, g).draft
        assertEquals(listOf(d.entries[1].id, d.entries[0].id, d.entries[2].id), rebased.entries.map { it.id })
        assertEquals(3, rebased.entries.map { it.id }.toSet().size)
    }

    @Test fun rebasePreservesFreshOrderAndParametersWhenOnlyQuantityChanged() {
        val g = group(listOf(entry("A.pgm", 3), entry("B.pgm")))
        val d = MixDrafts.start(g)
        val changed = MixDrafts.setQuantity(d, d.entries[0].id, 2, null)
        val fresh = group(listOf(entry("B.pgm"), entry("A.pgm", 3).copy(parameters = linkedMapOf("R" to "3", "V" to "99"))))
        val rebased = MixDrafts.rebase(changed, fresh).draft
        assertEquals(listOf("B.pgm", "A.pgm"), names(rebased))
        assertEquals(linkedMapOf("R" to "2", "V" to "99"), rebased.entries.last().parameters)
    }

    @Test fun undoPlainQuantityRestoresOriginalParameterSpellingAndOmission() {
        val source = entry("A.pgm").copy(parameters = linkedMapOf("V" to "010.0"))
        val d = MixDrafts.start(group(listOf(source)))
        val changed = MixDrafts.setQuantity(d, d.entries[0].id, 2, null)
        val undone = MixDrafts.setQuantity(changed, d.entries[0].id, 1, null)
        assertEquals(0, undone.changeCount)
        assertEquals(source.parameters, undone.entries[0].parameters)
    }

    @Test fun frzUnchangedQuantityLeavesCountdownUnchangedEvenWhenZero() {
        val d = MixDrafts.start(group(listOf(entry("A.pgm", 2, 0)), frzOnly = true))
        assertEquals(d, MixDrafts.setQuantity(d, d.entries[0].id, 2, null))
    }

    @Test fun unlockedMoveDoesNotShiftPlainCompletedRows() {
        val d = MixDrafts.start(group(listOf(entry("A.pgm"), entry("Done.pgm"), entry("B.pgm"))), listOf("Done.pgm"))
        assertEquals(listOf("B.pgm", "Done.pgm", "A.pgm"), names(MixDrafts.move(d, d.entries[2].id, 0)))
    }

    @Test fun fromPlainRevisionKeepsExactDocumentsAndCurrentWriteRevision() {
        val g = group(listOf(entry("A.pgm")))
        val old = entry("Outside.pgm", 4, path = "Z:\\Old Jobs\\Outside.pgm").copy(pgmExists = false)
        val d = MixDrafts.fromRevision(g, row("OLD2_Run.mix", listOf(old)))
        assertEquals(listOf(old), d.entries.map { it.source })
        assertEquals("base-Run.mix", (write(d, g) as MixWrite.Write).expectedRevision)
        assertEquals(1, d.changeCount)
    }

    @Test fun rebaseKeepsRemovalOfOnlyOneRepeatedOccurrence() {
        val first = entry("A.pgm", 1)
        val second = entry("A.pgm", 2)
        val g = group(listOf(first, second, entry("B.pgm")))
        val d = MixDrafts.start(g)
        val changed = MixDrafts.remove(d, d.entries[0].id)
        val result = MixDrafts.rebase(changed, g)
        assertEquals(listOf(d.entries[1].id, d.entries[2].id), result.draft.entries.map { it.id })
        assertEquals(listOf(2, 1), result.draft.entries.map { it.quantity })
        assertTrue(result.dropped.isEmpty())
    }

    @Test fun frzRemainingOnlyEditAndPartiallyLockedRepeatUsePendingOrder() {
        val g = group(listOf(entry("A.pgm", 3, 0), entry("A.pgm", 3, 2)), frzOnly = true)
        val d = MixDrafts.start(g)
        val changed = MixDrafts.setQuantity(d, d.entries[1].id, 3, 1)
        val request = buildWrite(changed, g, emptyList(), mapOf("a.PGM" to "frz")) as MixWrite.Frz
        assertEquals(listOf(EntrySpec.Pending("A.pgm", null, 1)), request.pending)
    }

    @Test fun interruptedStartOrdersTabletLocksFirstAndKeepsPendingZero() {
        val g = group(listOf(entry("A.pgm", remaining = 0), entry("Done.pgm", remaining = 2), entry("B.pgm", remaining = 1)), frzOnly = true)
        val d = MixDrafts.start(g, listOf("done.PGM"))
        assertEquals(listOf("Done.pgm", "A.pgm", "B.pgm"), names(d))
        assertEquals(0, d.entries[1].remaining)
        assertEquals(0, d.changeCount)
    }

    @Test fun newIdsAreNotReusedAfterRemoval() {
        val d = MixDrafts.start(group(listOf(entry("A.pgm"))))
        val added = MixDrafts.add(d, "B.pgm")
        val next = MixDrafts.add(MixDrafts.remove(added, added.entries.last().id), "B.pgm")
        assertNotEquals(added.entries.last().id, next.entries.last().id)
    }
}
