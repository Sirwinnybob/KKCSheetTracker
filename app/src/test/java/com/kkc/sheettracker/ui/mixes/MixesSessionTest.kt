package com.kkc.sheettracker.ui.mixes

import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.mixservice.PgmEditHistoryView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MixesSessionTest {
    @Test fun stagedSplitCompletionRacePreventsRequestWithoutChangingEitherDraft() = runTest {
        for (interrupted in listOf(false, true)) {
            val session = MixesSession()
            val template = catalog("split").files.single()
            val entries = listOf("AzA.pgm", "AzZ.pgm", "AzA.pgm", "other.pgm").map { pgm ->
                template.entries.single().copy(pgm = pgm, path = "Z:\\$pgm", remaining = if (interrupted) 1 else null)
            }
            val mix = template.copy(entries = entries, companion = if (interrupted) "MapleMix.FRZ" else null)
            val frz = mix.copy(file = "MapleMix.FRZ", kind = MixFileKind.FRZ, revision = "frz", companion = mix.file)
            val catalog = catalog("split").copy(files = if (interrupted) listOf(mix, frz) else listOf(mix))
            session.onCatalog(catalog)
            val group = group(catalog)
            session.reloadHistory(listOf("other.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
            session.updateSecond(listOf("other.pgm"), SecondPassField.SECOND, true, emptyList())
            session.edit(group, emptyList()) { MixDrafts.splitOff(it, setOf(1, 2, 3), "Other.mix") }
            val beforeMix = session.draft
            val beforeSecond = session.secondPass
            // The UI was eligible when staged, but ProgressStore locks changed before the actual submit.
            val currentLocks = listOf("AZA.PGM", "azz.pgm")
            var requests = 0
            val result = session.saveMix({ currentLocks }) { submitted ->
                buildWrite(submitted, group, submitted.completedPgms.toList(), emptyMap())
                requests++
                MixFilesResult.Ok(catalog)
            }
            assertNull(result)
            assertEquals(0, requests)
            assertFalse(session.applying)
            assertEquals("Completed sheets are staged for a new mix: AzA.pgm, AzZ.pgm. Discard the mix changes and split only pending sheets.", session.mixSaveBlockedReason)
            assertEquals(beforeMix, session.draft)
            assertEquals(beforeSecond, session.secondPass)
            assertTrue(session.discardMix(catalog))
            assertNull(session.mixSaveBlockedReason)
            assertEquals(beforeSecond, session.secondPass)
        }
    }
    @Test fun splitSaveUsesOneCurrentCompletionSnapshotForValidationAndWrite() = runTest {
        val session = session()
        val group = group(session.catalog!!)
        session.edit(group, emptyList()) { MixDrafts.splitOff(it, setOf(1), "Other.mix") }
        var reads = 0
        var requests = 0
        val saved = session.catalog!!
        val result = session.saveMix({ reads++; listOf("OtherA.pgm", "OtherZ.pgm") }) { submitted ->
            assertEquals(setOf("othera.pgm", "otherz.pgm"), submitted.completedPgms)
            val write = buildWrite(submitted, group, submitted.completedPgms.toList(), emptyMap()) as MixWrite.Split
            assertEquals("a.pgm", (write.newMixes.single().entries.single() as EntrySpec.Existing).path.substringAfterLast('\\'))
            requests++
            MixFilesResult.Ok(saved)
        }
        assertTrue(result is MixFilesResult.Ok)
        assertEquals(1, reads)
        assertEquals(1, requests)
        assertNull(session.mixSaveBlockedReason)
    }
    @Test fun tabAndNewDiscardOnlyMixAndKeepMaterialSecondPassDraft() = runTest {
        val session = session()
        session.reloadHistory(listOf("a.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        session.updateSecond(listOf("a.pgm"), SecondPassField.SECOND, true, emptyList())
        session.edit(group(session.catalog!!), emptyList()) { MixDrafts.add(it, "draft.pgm") }
        val wanted = session.secondPass
        val latest = catalog("new", "other.pgm")
        session.onCatalog(latest)
        assertTrue(session.discardMix(latest))
        assertEquals("new", session.catalog!!.folderRevision)
        assertEquals(wanted, session.secondPass)
        session.startNew("Maple", pages("a.pgm"), emptyList())
        assertEquals(wanted, session.secondPass)
    }
    @Test fun newNameAvoidsCaseInsensitiveMixAndFrzStems() {
        val session = session()
        val mix = session.catalog!!.files.single()
        session.onCatalog(catalog("names").copy(files = listOf(mix, mix.copy(file = "MAPLEMIX 2.FRZ", kind = MixFileKind.FRZ))))
        session.startNew("Maple", pages("new.pgm"), emptyList())
        assertEquals("MapleMix 3.mix", session.draft!!.newFile)
    }
    @Test fun namingNewDraftKeepsEntriesAndSecondPassAndDoesNotNeedRemoteRename() = runTest {
        val session = session()
        session.reloadHistory(listOf("a.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        session.updateSecond(listOf("a.pgm"), SecondPassField.SECOND, true, emptyList())
        session.startNew("Maple", pages("new.pgm"), emptyList())
        val before = session.draft!!
        session.renameNew("Custom Mix.mix")
        assertEquals("Custom Mix.mix", session.draft!!.newFile)
        assertEquals("custom mix.mix", session.selectedTab)
        assertEquals(before.entries, session.draft!!.entries)
        assertEquals(1, session.secondPass!!.changeCount)
    }
    @Test fun revisionRestoreAndCatalogMutationFreezeOnlyMixDraft() = runTest {
        val session = session()
        val group = group(session.catalog!!)
        session.stageRevision(group, group.primary, listOf("a.pgm"))
        assertEquals(setOf("a.pgm"), session.draft!!.completedPgms)
        val response = CompletableDeferred<MixFilesResult<MixFilesCatalog>>()
        val write = launch { session.mutateCatalog { response.await() } }
        runCurrent()
        val before = session.draft
        session.stageRevision(group, group.primary, emptyList())
        assertEquals(before, session.draft)
        assertFalse(session.discardMix(session.catalog))
        response.complete(MixFilesResult.Ok(catalog("renamed")))
        write.join()
        assertNull(session.draft)
        assertEquals("renamed", session.catalog!!.folderRevision)
    }
    @Test fun suspendedMixSaveFreezesOnlyMixDraftAndDiscard() = runTest {
        val session = session()
        session.reloadHistory(listOf("a.pgm", "b.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        session.edit(group(session.catalog!!), emptyList()) { MixDrafts.add(it, "b.pgm") }
        val submitted = session.draft
        val response = CompletableDeferred<MixFilesResult<MixFilesCatalog>>()
        val save = launch { session.saveMix { response.await() } }
        runCurrent()
        session.edit(group(session.catalog!!), emptyList()) { MixDrafts.add(it, "lost.pgm") }
        assertFalse(session.discardMix(session.catalog))
        assertFalse(session.abandonAll(session.catalog))
        assertEquals(submitted, session.draft)
        session.updateSecond(listOf("b.pgm"), SecondPassField.SECOND, true, emptyList())
        assertEquals(1, session.secondPass!!.changeCount)
        response.complete(MixFilesResult.Ok(catalog("saved")))
        save.join()
        assertNull(session.draft)
        assertEquals(1, session.secondPass!!.changeCount)
    }
    @Test fun suspendedSecondSaveFreezesSecondDraftButMixRemainsEditable() = runTest {
        val session = session()
        session.reloadHistory(listOf("a.pgm", "b.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        session.updateSecond(listOf("b.pgm"), SecondPassField.SECOND, true, emptyList())
        val response = CompletableDeferred<SecondPassOutcome>()
        val save = launch { session.saveSecond({ emptyList() }, { response.await() }, {
            com.kkc.sheettracker.data.mixservice.PgmEditHistoryView(mapOf("b.pgm" to
                com.kkc.sheettracker.data.mixservice.PgmEditFileHistory(com.kkc.sheettracker.data.mixservice.PgmEditCurrentState("standard", false), stateSource = "ledger")), ok = true, stateSource = "ledger")
        }) }
        runCurrent()
        session.updateSecond(listOf("a.pgm"), SecondPassField.SUPER, true, emptyList())
        assertFalse(session.discardSecond())
        assertEquals(1, session.secondPass!!.changeCount)
        session.edit(group(session.catalog!!), emptyList()) { MixDrafts.add(it, "independent.pgm") }
        response.complete(SecondPassOutcome.Done)
        save.join()
        assertEquals("independent.pgm", session.draft!!.entries.last().newPgm)
        assertEquals(0, session.secondPass!!.changeCount)
    }
    @Test fun confirmedTabAndNewAbandonmentAcceptDeferredCatalogBeforeChoosingOrSeeding() {
        val session = session()
        session.edit(group(session.catalog!!), emptyList()) { MixDrafts.add(it, "draft.pgm") }
        val fresh = catalog("fresh", "b.pgm")
        session.onCatalog(fresh)
        assertTrue(session.conflict)
        assertTrue(session.abandonAll(fresh))
        session.selectedTab = group(session.catalog!!).key
        assertEquals("b.pgm", group(session.catalog!!).primary.entries.single().pgm)
        session.edit(group(session.catalog!!), emptyList()) { MixDrafts.add(it, "again.pgm") }
        val newest = catalog("newest", "c.pgm")
        session.onCatalog(newest)
        assertTrue(session.abandonAll(newest))
        session.startNew("Maple", pages("a.pgm", "b.pgm", "c.pgm"), emptyList())
        assertEquals(listOf("a.pgm", "b.pgm"), session.draft!!.entries.map { it.newPgm })
        assertEquals("newest", session.draftFolderRevision)
    }
    @Test fun completionChangingAfterStagingDropsBothAzNamesBeforeAnySubmission() = runTest {
        val session = session()
        session.reloadHistory(listOf("pA.pgm", "pZ.pgm", "other.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        session.updateSecond(listOf("pA.pgm", "pZ.pgm"), SecondPassField.SECOND, true, emptyList())
        var attempts = 0
        session.saveSecond({ listOf("PA.PGM", "pz.pgm") }, { attempts++; SecondPassOutcome.Done }, { PgmEditHistoryView(ok = true, stateSource = "ledger") })
        assertEquals(0, attempts)
        assertEquals(0, session.secondPass!!.changeCount)
        session.updateSecond(listOf("pA.pgm", "pZ.pgm", "other.pgm"), SecondPassField.SECOND, true, emptyList())
        session.saveSecond({ listOf("pA.pgm", "pZ.pgm") }, { submitted ->
            assertEquals(listOf("other.pgm"), planSecondPass(submitted).filterIsInstance<SecondPassStep.Edit>().flatMap { it.rows }.map { it.name })
            attempts++; SecondPassOutcome.Done
        }, { PgmEditHistoryView(ok = true, stateSource = "ledger") })
        assertEquals(1, attempts)
    }
    @Test fun failedHistoryRetryKeepsWantedEditsAndOnlyConfirmedRecoveryAllowsApply() = runTest {
        val session = session()
        session.reloadHistory(listOf("a.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        session.updateSecond(listOf("a.pgm"), SecondPassField.SECOND, true, emptyList())
        session.reloadHistory(listOf("a.pgm")) { null }
        assertFalse(session.secondHistoryConfirmed)
        assertEquals(1, session.secondPass!!.changeCount)
        var attempts = 0
        session.saveSecond({ emptyList() }, { attempts++; SecondPassOutcome.Done }, { PgmEditHistoryView(ok = true, stateSource = "ledger") })
        assertEquals(0, attempts)
        session.reloadHistory(listOf("a.pgm")) { PgmEditHistoryView(ok = true, stateSource = "ledger") }
        assertTrue(session.secondHistoryConfirmed)
        assertEquals(1, session.secondPass!!.changeCount)
        assertEquals(MixesSession.NO_RECORDED_STATE, session.secondMessage)
    }
    private fun session() = MixesSession().also { it.onCatalog(catalog("base")) }
    private fun group(c: MixFilesCatalog) = groupMixFiles(c).single()
    private fun catalog(revision: String, pgm: String = "a.pgm") = MixFilesCatalog("J", "M", revision, listOf(
        MixFileRow("MapleMix.mix", MixFileKind.MIX, null, null, revision, null, false, null, null,
            listOf(MixFileEntry("Z:\\$pgm", pgm, linkedMapOf(), null, 1, null, true, false)))))
    private fun pages(vararg pgms: String) = pgms.mapIndexed { i, pgm -> SheetPage(i+1, listOf(pgm), false, false, null) }
}
