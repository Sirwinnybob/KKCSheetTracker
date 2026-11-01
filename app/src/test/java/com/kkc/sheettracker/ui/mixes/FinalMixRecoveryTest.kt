package com.kkc.sheettracker.ui.mixes

import com.google.gson.Gson
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.mixservice.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class FinalMixRecoveryTest {
    private fun entry(name: String) = MixFileEntry("Z:\\Outside Job\\$name", name, linkedMapOf("V" to "010.00"), "AD", 1, null, true, false)
    private fun mix() = MixFileRow("Run.mix", MixFileKind.MIX, null, null, "target-sha", null, false, null, null, listOf(entry("R1.PGM")))
    private fun revision() = mix().copy(file = "OLD2_Run.mix", kind = MixFileKind.REVISION, revisionOf = "Run.mix", revisionNumber = 2,
        revision = "source-sha", entries = listOf(entry("R1.PGM"), entry("Removed.pgm"), entry("R1.PGM")))
    private fun catalog(vararg files: MixFileRow, folder: String = "base") = MixFilesCatalog("J", "M", folder, files.toList())
    private fun history(json: String) = Gson().fromJson(json, PgmEditHistoryView::class.java)
    private fun ledger(files: String = "{}") = history("""{"ok":true,"stateSource":"ledger","error":null,"files":$files}""")
    private fun missing() = history("""{"ok":false,"stateSource":"unknown","error":"history_missing","files":{}}""")

    @Test fun retiredOldRevisionGroupKeepsDraftAndConflictWithoutCrashing() {
        val s = MixesSession(); val c = catalog(mix(), revision()); s.onCatalog(c)
        s.edit(groupMixFiles(c).single(), emptyList()) { MixDrafts.add(it, "Wanted.pgm") }
        val before = s.draft
        s.onCatalog(catalog(revision(), folder = "retired"))
        s.reapply(emptyList())
        assertEquals(before, s.draft); assertTrue(s.conflict)
        assertTrue(s.rebaseMessage.orEmpty().contains("Load theirs"))
    }

    @Test fun plainRevisionStagesRemovedAndRepeatedMembersAsImmutableRawRestore() {
        val g = groupMixFiles(catalog(mix(), revision())).single()
        val d = MixDrafts.fromRevision(g, revision())
        assertEquals(revision().entries, d.entries.map { it.source })
        assertEquals("Restore", buildWrite(d, g, emptyList(), emptyMap()).javaClass.simpleName)
        assertEquals(d, MixDrafts.add(d, "Ignored.pgm"))
        assertEquals(d, MixDrafts.remove(d, d.entries.first().id))
        assertEquals(d, MixDrafts.move(d, d.entries.last().id, 0))
    }

    @Test fun sessionDoesNotAllowEditingStagedPlainRestore() {
        val s = MixesSession(); val c = catalog(mix(), revision()); s.onCatalog(c)
        val g = groupMixFiles(c).single(); s.stageRevision(g, revision(), emptyList())
        val before = s.draft
        s.edit(g, emptyList()) { MixDrafts.add(it, "Ignored.pgm") }
        assertEquals(before, s.draft)
    }

    @Test fun malformedHttp200CannotBecomeCleanHistory() = runTest {
        MockWebServer().use { server ->
            server.start()
            for (body in listOf("{}", """{"ok":false,"stateSource":"unknown","error":"history_malformed","files":{}}""",
                """{"ok":true,"stateSource":"future","files":{}}""")) {
                server.enqueue(MockResponse().setBody(body))
                val s = MixesSession()
                s.reloadHistory(listOf("R1.pgm")) { MixServiceClient(server.url("/").toString()).listPgmEdits("J", "M") }
                assertFalse(body, s.secondHistoryConfirmed)
                assertNotNull(s.secondMessage)
            }
        }
    }

    @Test fun failedRestoreHistoryBlocksEditingAndPreservesWanted() = runTest {
        val s = MixesSession(); s.reloadHistory(listOf("R1.pgm")) { ledger() }
        s.updateSecond(listOf("R1.pgm"), SecondPassField.SECOND, true, emptyList())
        val before = s.secondPass
        s.reloadHistory(listOf("R1.pgm")) { ledger("""{"r1.PGM":{"stateSource":"ledger","current":{"mode":"unknown","punloadRemoved":null,"stateSource":"failed_restore_error"},"history":[]}}""") }
        assertFalse(s.secondHistoryConfirmed); assertEquals(before, s.secondPass)
        s.updateSecond(listOf("R1.pgm"), SecondPassField.SUPER, true, emptyList())
        assertEquals(before, s.secondPass)
    }

    @Test fun firstAdditiveEditAllowedButMissingLedgerCannotConfirmSubmittedSave() = runTest {
        val s = MixesSession(); s.reloadHistory(listOf("R1.pgm")) { missing() }
        assertTrue(s.secondHistoryConfirmed)
        assertTrue(s.secondMessage.orEmpty().contains("No recorded"))
        s.updateSecond(listOf("R1.PGM"), SecondPassField.SECOND, true, emptyList())
        var calls = 0
        s.saveSecond({ emptyList() }, { calls++; SecondPassOutcome.Done }, { missing() })
        assertEquals(1, calls); assertFalse(s.secondHistoryConfirmed)
        assertEquals(1, s.secondPass!!.changeCount)
        assertFalse(s.secondMessage.orEmpty().contains("changes applied"))
    }

    @Test fun unknownPostSaveHistoryKeepsWantedAndDoesNotReportSuccess() = runTest {
        val s = MixesSession(); s.reloadHistory(listOf("R1.pgm")) { ledger() }
        s.updateSecond(listOf("R1.pgm"), SecondPassField.SECOND, true, emptyList())
        s.saveSecond({ emptyList() }, { SecondPassOutcome.Done }, { history("""{"ok":false,"stateSource":"unknown","error":"history_unavailable","files":{}}""") })
        assertFalse(s.secondHistoryConfirmed); assertEquals(1, s.secondPass!!.changeCount)
        assertFalse(s.secondMessage.orEmpty().contains("changes applied"))
    }

    @Test fun mixedCaseInventoryMetadataCatalogAndHistoryShareOneMaterialEdit() = runTest {
        val s = MixesSession()
        val inventory = listOf("R1.pgm", "Other.pgm"); val metadata = listOf("r1.PGM", "OTHER.PGM")
        s.onCatalog(catalog(mix()))
        s.reloadHistory(inventory + metadata) { ledger("""{"r1.pgm":{"stateSource":"ledger","current":{"mode":"standard","punloadRemoved":false},"history":[]}}""") }
        assertEquals(2, s.secondPass!!.wanted.size)
        assertEquals(TriState.ON, SecondPassDrafts.tri(s.secondPass!!, listOf(mix().entries.single().pgm), SecondPassField.SECOND))
        s.updateSecond(metadata + inventory, SecondPassField.PUNLOAD, true, emptyList())
        assertEquals(2, s.secondPass!!.changeCount)
        val rows = planSecondPass(s.secondPass!!).filterIsInstance<SecondPassStep.Edit>().flatMap { it.rows }
        assertEquals(listOf("R1.pgm", "Other.pgm"), rows.map { it.name })
        assertTrue(rows.first().secondPass == "standard")
        s.reloadHistory(listOf("R1.PGM", "OTHER.PGM")) { ledger("""{"r1.pgm":{"stateSource":"ledger","current":{"mode":"standard","punloadRemoved":false},"history":[]}}""") }
        assertTrue(s.secondHistoryConfirmed)
        assertEquals(2, s.secondPass!!.changeCount)
        assertEquals(TriState.ON, SecondPassDrafts.tri(s.secondPass!!, inventory, SecondPassField.PUNLOAD))
    }
    @Test fun restoreDispatchUsesExactTokensOnlyOnApplyAndKeepsIntentOnConflict() = runTest {
        MockWebServer().use { server ->
            server.start()
            val s = MixesSession(); val c = catalog(mix(), revision()); s.onCatalog(c)
            val g = groupMixFiles(c).single(); s.stageRevision(g, revision(), emptyList())
            val intent = s.draft!!.rawRestore!!
            assertEquals("source-sha", intent.sourceRevision)
            assertEquals("target-sha", intent.expectedTargetRevision)
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"ok":false,"code":"revision_conflict","error":"changed"}"""))
            val api = MixFilesClient(com.kkc.sheettracker.data.cnc.CncMachines.scm.copy(baseUrl = server.url("/").toString()))
            s.saveMix { api.submitMixWrite("J", "M", buildWrite(it, g, emptyList(), emptyMap())) }
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/jobs/J/materials/M/mix-files/restore", request.path)
            val body = com.google.gson.JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals(setOf("revisionFile", "targetFile", "expectedTargetRevision"), body.keySet())
            assertEquals("OLD2_Run.mix", body["revisionFile"].asString)
            assertEquals("Run.mix", body["targetFile"].asString)
            assertEquals("target-sha", body["expectedTargetRevision"].asString)
            assertEquals(intent, s.draft!!.rawRestore); assertTrue(s.conflict)
            s.onCatalog(catalog(mix().copy(revision = "new-target"), revision(), folder = "new"))
            assertTrue(s.reapplyEnabled); s.reapply(emptyList())
            assertEquals(intent.copy(expectedTargetRevision = "new-target"), s.draft!!.rawRestore)
            assertFalse(s.conflict)
            val preview = s.draft
            val frz = mix().copy(file = "Run.FRZ", kind = MixFileKind.FRZ, companion = "Run.mix")
            s.onCatalog(catalog(mix().copy(companion = "Run.FRZ"), frz, revision(), folder = "pair"))
            assertFalse(s.reapplyEnabled); s.reapply(emptyList())
            assertEquals(preview, s.draft); assertTrue(s.conflict)
            var writes = 0
            assertNull(s.saveMix { writes++; MixFilesResult.Ok(c) })
            assertEquals(0, writes)
            s.onCatalog(catalog(revision(), folder = "retired"))
            assertFalse(s.reapplyEnabled); s.reapply(emptyList())
            assertEquals(preview, s.draft); assertTrue(s.conflict)
            assertNull(s.saveMix { writes++; MixFilesResult.Ok(c) })
            assertEquals(0, writes)
        }
    }

    @Test fun missingAndUnreadablePlainTargetsHaveReviewableApplyRestore() {
        for (target in listOf(null, mix().copy(error = "unreadable", entries = emptyList()))) {
            val c = catalog(*listOfNotNull(target, revision()).toTypedArray())
            val g = groupMixFiles(c).single(); val s = MixesSession(); s.onCatalog(c)
            s.stageRevision(g, revision(), emptyList())
            val write = buildWrite(s.draft!!, g, emptyList(), emptyMap()) as MixWrite.Restore
            assertEquals(target?.revision, write.intent.expectedTargetRevision)
            val material = MaterialMixesInput(com.kkc.sheettracker.data.cnc.CncMachines.scm,
                com.kkc.sheettracker.data.models.Material("M.pdf", "M", 1), CatalogState.Live(c))
            val ui = buildMixesUi(MixesUiInput(listOf(material), "M", draft = s.draft, draftFolderRevision = "base"))
            val card = requireNotNull(ui.card)
            assertTrue(card.applyEnabled)
            assertEquals("Apply restore", card.applyLabel)
            assertTrue(ui.restorePreview)
            assertFalse(card.splitEnabled)
            assertTrue(ui.groups!!.unmatched.all { !it.canRemove && !it.canEditCounts && !it.draggable })
        }
    }

    @Test fun missingLedgerAfterSubmittedSaveStaysUncertainOnRetry() = runTest {
        val s = MixesSession(); s.reloadHistory(listOf("R1.pgm")) { missing() }
        s.updateSecond(listOf("R1.pgm"), SecondPassField.SECOND, true, emptyList())
        s.saveSecond({ emptyList() }, { SecondPassOutcome.Done }, { missing() })
        s.reloadHistory(listOf("R1.pgm")) { missing() }
        assertFalse(s.secondHistoryConfirmed); assertEquals(1, s.secondPass!!.changeCount)
        s.reloadHistory(listOf("R1.pgm")) { ledger("""{"R1.pgm":{"stateSource":"ledger","current":{"mode":"standard","punloadRemoved":false},"history":[]}}""") }
        assertTrue(s.secondHistoryConfirmed); assertEquals(0, s.secondPass!!.changeCount)
    }

    @Test fun incompleteOrMistypedLedgerEnvelopeIsRejectedByWireParser() = runTest {
        MockWebServer().use { server ->
            server.start()
            for (body in listOf(
                """{"ok":true,"stateSource":"ledger","error":null}""",
                """{"ok":"true","stateSource":"ledger","error":null,"files":{}}""",
                """{"ok":true,"stateSource":"ledger","files":{}}""",
                """{"ok":true,"stateSource":"ledger","error":null,"files":{"a.pgm":{"stateSource":"ledger","current":{"mode":"none","punloadRemoved":"false"},"history":[]}}}"""
            )) {
                server.enqueue(MockResponse().setBody(body))
                assertNull(body, MixServiceClient(server.url("/").toString()).listPgmEdits("J", "M"))
            }
        }
    }

    @Test fun plannerAndChangeCountDeduplicateCaseAliasesWithCanonicalTransportSpelling() {
        val none = SecondPassState(false, false, false)
        val standard = SecondPassState(true, false, false)
        val d = SecondPassDraft(linkedMapOf("R1.PGM" to standard, "r1.pgm" to standard), mapOf("r1.PGM" to none))
        assertEquals(1, d.changeCount)
        assertEquals(listOf(SecondPassStep.Edit(listOf(PgmEditRow("R1.PGM", "standard", false)))), planSecondPass(d))
        assertEquals(TriState.ON, SecondPassDrafts.tri(d, listOf("R1.PGM", "r1.pgm"), SecondPassField.SECOND))
    }

    @Test fun cancelledPostSubmissionReadBackLeavesHistoryUnconfirmed() = runTest {
        val s = MixesSession(); s.reloadHistory(listOf("R1.pgm")) { ledger() }
        s.updateSecond(listOf("R1.pgm"), SecondPassField.SECOND, true, emptyList())
        try {
            s.saveSecond({ emptyList() }, { SecondPassOutcome.Done }, { throw kotlinx.coroutines.CancellationException("screen stopped") })
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertFalse(s.secondHistoryConfirmed)
        assertEquals(1, s.secondPass!!.changeCount)
    }

}
