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
    @Test fun suspendedMixSaveFreezesOnlyMixDraftAndDiscard() = runTest {
        val session = session()
        session.reloadHistory(listOf("a.pgm", "b.pgm")) { PgmEditHistoryView() }
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
        session.reloadHistory(listOf("a.pgm", "b.pgm")) { PgmEditHistoryView() }
        session.updateSecond(listOf("b.pgm"), SecondPassField.SECOND, true, emptyList())
        val response = CompletableDeferred<SecondPassOutcome>()
        val save = launch { session.saveSecond({ emptyList() }, { response.await() }, { PgmEditHistoryView() }) }
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
        session.reloadHistory(listOf("pA.pgm", "pZ.pgm", "other.pgm")) { PgmEditHistoryView() }
        session.updateSecond(listOf("pA.pgm", "pZ.pgm"), SecondPassField.SECOND, true, emptyList())
        var attempts = 0
        session.saveSecond({ listOf("PA.PGM", "pz.pgm") }, { attempts++; SecondPassOutcome.Done }, { PgmEditHistoryView() })
        assertEquals(0, attempts)
        assertEquals(0, session.secondPass!!.changeCount)
        session.updateSecond(listOf("pA.pgm", "pZ.pgm", "other.pgm"), SecondPassField.SECOND, true, emptyList())
        session.saveSecond({ listOf("pA.pgm", "pZ.pgm") }, { submitted ->
            assertEquals(listOf("other.pgm"), planSecondPass(submitted).filterIsInstance<SecondPassStep.Edit>().flatMap { it.rows }.map { it.name })
            attempts++; SecondPassOutcome.Done
        }, { PgmEditHistoryView() })
        assertEquals(1, attempts)
    }
    @Test fun failedHistoryRetryKeepsWantedEditsAndOnlyConfirmedRecoveryAllowsApply() = runTest {
        val session = session()
        session.reloadHistory(listOf("a.pgm")) { PgmEditHistoryView() }
        session.updateSecond(listOf("a.pgm"), SecondPassField.SECOND, true, emptyList())
        session.reloadHistory(listOf("a.pgm")) { null }
        assertFalse(session.secondHistoryConfirmed)
        assertEquals(1, session.secondPass!!.changeCount)
        var attempts = 0
        session.saveSecond({ emptyList() }, { attempts++; SecondPassOutcome.Done }, { PgmEditHistoryView() })
        assertEquals(0, attempts)
        session.reloadHistory(listOf("a.pgm")) { PgmEditHistoryView() }
        assertTrue(session.secondHistoryConfirmed)
        assertEquals(1, session.secondPass!!.changeCount)
        assertNull(session.secondMessage)
    }
    private fun session() = MixesSession().also { it.onCatalog(catalog("base")) }
    private fun group(c: MixFilesCatalog) = groupMixFiles(c).single()
    private fun catalog(revision: String, pgm: String = "a.pgm") = MixFilesCatalog("J", "M", revision, listOf(
        MixFileRow("MapleMix.mix", MixFileKind.MIX, null, null, revision, null, false, null, null,
            listOf(MixFileEntry("Z:\\$pgm", pgm, linkedMapOf(), null, 1, null, true, false)))))
    private fun pages(vararg pgms: String) = pgms.mapIndexed { i, pgm -> SheetPage(i+1, listOf(pgm), false, false, null) }
}
