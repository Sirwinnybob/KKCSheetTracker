package com.kkc.sheettracker.data.mixfiles

import com.google.gson.JsonParser
import com.kkc.sheettracker.data.cnc.CncMachines
import com.kkc.sheettracker.data.mixservice.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SecondPassTest {
    private val none = SecondPassState(false, false, false)
    private val standard = SecondPassState(true, false, false)
    private val superState = SecondPassState(true, true, false)
    private fun draft(current: SecondPassState, wanted: SecondPassState) =
        SecondPassDraft(mapOf("p.pgm" to wanted), mapOf("p.pgm" to current))

    @Test fun draftReadsCurrentFromHistory() {
        val d = SecondPassDrafts.from(PgmEditHistoryView(mapOf("opaque +.pgm" to
            PgmEditFileHistory(PgmEditCurrentState("super", true)))), listOf("opaque +.pgm", "new.pgm"))
        assertEquals(SecondPassState(true, true, true), d.current["opaque +.pgm"])
        assertEquals(none, d.current["new.pgm"])
        assertEquals(d.current, d.wanted)
        assertEquals(0, d.changeCount)
        val changed = SecondPassDrafts.set(d, "new.pgm", SecondPassState(false, true, false))
        assertEquals(superState, changed.wanted["new.pgm"])
        assertEquals(1, changed.changeCount)
        assertEquals(none, d.wanted["new.pgm"])
    }

    @Test fun addOnlyPlansSingleEdit() {
        val d = SecondPassDraft(
            linkedMapOf("new.pgm" to standard, "upgrade.pgm" to superState, "pun.pgm" to standard.copy(removePUnload = true)),
            linkedMapOf("new.pgm" to none, "upgrade.pgm" to standard, "pun.pgm" to standard))
        assertEquals(listOf(SecondPassStep.Edit(listOf(PgmEditRow("new.pgm", "standard", false),
            PgmEditRow("upgrade.pgm", "super", false), PgmEditRow("pun.pgm", "standard", true)))), planSecondPass(d))
    }

    @Test fun takingOff2ndPlansRevertThenEditForRemainingPun() {
        assertEquals(listOf(SecondPassStep.Revert(listOf("p.pgm")),
            SecondPassStep.Edit(listOf(PgmEditRow("p.pgm", "none", true)))),
            planSecondPass(draft(standard.copy(removePUnload = true), none.copy(removePUnload = true))))
    }

    @Test fun takingOffEverythingPlansRevertOnly() {
        assertEquals(listOf(SecondPassStep.Revert(listOf("p.pgm"))), planSecondPass(draft(superState.copy(removePUnload = true), none)))
        assertTrue(planSecondPass(draft(none, none)).isEmpty())
    }

    @Test fun superToStandardIsRevertThenStandard() {
        assertEquals(listOf(SecondPassStep.Revert(listOf("p.pgm")),
            SecondPassStep.Edit(listOf(PgmEditRow("p.pgm", "standard", false)))), planSecondPass(draft(superState, standard)))
    }

    @Test fun setAllSkipsPgmsNotPassed() {
        val d = SecondPassDrafts.from(null, listOf("unlocked.pgm", "locked.pgm"))
        val on = SecondPassDrafts.setAll(d, listOf("unlocked.pgm"), SecondPassField.SUPER, true)
        assertEquals(superState, on.wanted["unlocked.pgm"])
        assertEquals(none, on.wanted["locked.pgm"])
        assertEquals(none, SecondPassDrafts.setAll(on, listOf("unlocked.pgm"), SecondPassField.SECOND, false).wanted["unlocked.pgm"])
        assertEquals(none.copy(removePUnload = true), SecondPassDrafts.setAll(d, listOf("unlocked.pgm"), SecondPassField.PUNLOAD, true).wanted["unlocked.pgm"])
    }

    @Test fun triStateCountsPartial() {
        val d = SecondPassDrafts.set(SecondPassDrafts.from(null, listOf("a", "b", "c")), "a", standard)
        assertEquals(TriState.PARTIAL(1), SecondPassDrafts.tri(d, listOf("a", "b", "c"), SecondPassField.SECOND))
        assertEquals(TriState.ON, SecondPassDrafts.tri(d, listOf("a"), SecondPassField.SECOND))
        assertEquals(TriState.OFF, SecondPassDrafts.tri(d, listOf("b", "c"), SecondPassField.SECOND))
        assertEquals(TriState.OFF, SecondPassDrafts.tri(d, emptyList(), SecondPassField.SUPER))
    }

    @Test fun runnerPollsUntilTerminalAndChecksFileStatuses() = runTest {
        val service = FakeService().apply {
            polls.add(MixServiceOperation(id = "op", state = "running", stage = "compiling"))
            polls.add(completed(true, "succeeded", "failed_restored"))
        }
        val progress = mutableListOf<String>()
        val runner = SecondPassRunner(service, { _, _, _, _ -> error("unexpected revert") }, { "tablet-test-1" })
        val result = runner.apply("J", "M", listOf(SecondPassStep.Edit(listOf(PgmEditRow("a", "standard", false)))), progress::add)
        assertTrue((result as SecondPassOutcome.Failed).partial)
        assertEquals(2_000L, currentTime)
        assertEquals(listOf("op", "op"), service.pollIds)
        assertEquals(listOf("J", "M", "tablet-test-1"), service.submissions.single().take(3))
        assertTrue(progress.contains("compiling"))
    }

    @Test fun completedRequiresTopLevelOkAndEveryFileStatus() = runTest {
        for (op in listOf(completed(false, "succeeded"), completed(true, "unknown"),
            MixServiceOperation(state = "completed", result = null))) {
            val runner = SecondPassRunner(FakeService(op), { _, _, _, _ -> error("unexpected") }, { "r" })
            assertTrue(runner.apply("J", "M", listOf(SecondPassStep.Edit(listOf(PgmEditRow("a", "standard", false))))) {} is SecondPassOutcome.Failed)
        }
        val runner = SecondPassRunner(FakeService(completed(true, "succeeded", "skipped")), { _, _, _, _ -> error("unexpected") }, { "r" })
        assertEquals(SecondPassOutcome.Done, runner.apply("J", "M", listOf(SecondPassStep.Edit(listOf(PgmEditRow("a", "standard", false))))) {})
    }

    @Test fun disabledSecondPassMapsToCopy() = runTest {
        val runner = SecondPassRunner(FakeService(MixServiceOperation(state = "failed", code = "edit_failed", error = "second-pass PGM editing is disabled")), { _, _, _, _ -> error("unexpected") }, { "r" })
        val failed = runner.apply("J", "M", listOf(SecondPassStep.Edit(listOf(PgmEditRow("a", "standard", false))))) {} as SecondPassOutcome.Failed
        assertEquals("Second-pass editing is switched off on the CNC", failed.message)
        assertFalse(failed.partial)
    }

    @Test fun revertCompletesBeforeEditWithDistinctRequestIdsAndFailureStopsPlan() = runTest {
        val ids = secondPassRequestIds("shop tablet/#", { 42L })
        val service = FakeService(completed(true, "succeeded"))
        val events = mutableListOf<String>()
        val runner = SecondPassRunner(service, { job, material, id, pgms ->
            assertEquals("J", job); assertEquals("M", material); assertEquals(listOf("p.pgm"), pgms)
            assertTrue(service.submissions.isEmpty()); events.add(id); completed(true, "skipped")
        }, ids)
        val plan = planSecondPass(draft(superState, standard))
        assertEquals(SecondPassOutcome.Done, runner.apply("J", "M", plan) {})
        assertEquals(listOf("tablet-shop_tablet__-42"), events)
        assertEquals("tablet-shop_tablet__-43", service.submissions.single()[2])
        val blocked = SecondPassRunner(service, { _, _, _, _ -> MixServiceOperation(state = "interrupted", error = "stopped") }, ids)
        assertTrue(blocked.apply("J", "M", plan) {} is SecondPassOutcome.Failed)
        assertEquals(1, service.submissions.size)
    }

    @Test fun cancellationPropagatesAndUncertainSubmissionIsNotRetried() = runTest {
        val service = FakeService()
        var attempts = 0
        val runner = SecondPassRunner(service, { _, _, _, _ -> attempts++; throw java.io.IOException("connection lost") }, { "r" })
        val failed = runner.apply("J", "M", listOf(SecondPassStep.Revert(listOf("a")))) {} as SecondPassOutcome.Failed
        assertTrue(failed.partial)
        assertEquals(1, attempts)
        val cancelled = SecondPassRunner(service, { _, _, _, _ -> throw CancellationException("cancelled") }, { "r" })
        try { cancelled.apply("J", "M", listOf(SecondPassStep.Revert(listOf("a")))) {}; fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
    }

    @Test fun revertUsesCapabilityGateAsyncEnvelopeAndOpaqueSegments() = runTest {
        val server = MockWebServer(); server.start()
        try {
            val api = MixFilesClient(CncMachines.scm.copy(baseUrl = server.url("/").toString()))
            server.enqueue(MockResponse().setBody("""{"ok":true,"features":[]}"""))
            try { api.submitPgmEditRevert("J", "M", "r", listOf("a.pgm")); fail("Unsupported revert submitted") }
            catch (_: MixOperationClientException) { }
            assertEquals("/status", server.takeRequest().path)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody("""{"ok":true,"features":["pgm-edits-revert"]}"""))
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"operation":{"id":"revert-op","kind":"pgm_edit","state":"queued"}}"""))
            assertEquals("revert-op", api.submitPgmEditRevert("J/#", "M+ /", "r-2", listOf("opaque +.pgm")).id)
            assertEquals("/status", server.takeRequest().path)
            val sent = server.takeRequest()
            assertEquals("POST", sent.method)
            assertEquals("/jobs/J%2F%23/materials/M+%20%2F/pgm-edits/revert", sent.path)
            assertEquals(JsonParser.parseString("""{"requestId":"r-2","files":[{"name":"opaque +.pgm","to":"original"}]}"""), JsonParser.parseString(sent.body.readUtf8()))
        } finally { server.shutdown() }
    }

    @Test fun realHistoryDecoderPopulatesTypedEventsAndBeforeFinalStates() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"ok":true,"files":{"opaque +.pgm":{"current":{"mode":"standard","punloadRemoved":true},"history":[{"eventId":"r:1","requestId":"r","at":"2026-10-30T08:00:00","actor":"cnc","action":"edit_reverted","requestedMode":"standard","punloadRequested":true,"before":{"mode":"super","punloadRemoved":true},"final":{"mode":"standard","punloadRemoved":true},"result":{"mode":"standard","punloadRemoved":true},"backup":"BACKUP_new/opaque +.pgm","restoredFrom":"BACKUP_first/opaque +.pgm","mixFiles":["opaque +.mix"]}]}}}"""))
            val view = MixServiceClient(server.url("/").toString()).listPgmEdits("J", "M")!!
            val event: PgmEditHistoryEvent = view.files.getValue("opaque +.pgm").history.single()
            assertEquals("r:1", event.eventId); assertEquals("r", event.requestId)
            assertEquals("edit_reverted", event.action); assertEquals("cnc", event.actor)
            assertEquals("2026-10-30T08:00:00", event.at)
            assertEquals("standard", event.requestedMode); assertEquals(true, event.punloadRequested)
            assertEquals("super", event.before?.mode); assertEquals("standard", event.final?.mode)
            assertEquals(true, event.result?.punloadRemoved)
            assertEquals("BACKUP_first/opaque +.pgm", event.restoredFrom)
            assertEquals("BACKUP_new/opaque +.pgm", event.backup)
            assertEquals(listOf("opaque +.mix"), event.mixFiles)
        } finally { server.shutdown() }
    }

    @Test fun editClientDoesNotReplaySubmissionAfterLostResponse() = runTest {
        val server = MockWebServer(); server.start()
        try {
            val client = MixServiceClient(CncMachines.scm.copy(baseUrl = server.url("/").toString()).baseUrl)
            server.enqueue(MockResponse().setBody("""{"ok":true,"operation":{"id":"old","state":"completed"}}"""))
            client.getOperation("old") // Establish a pooled connection before its next response disappears.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"operation":{"id":"duplicate"}}"""))
            try { client.submitPgmEdits("J", "M", "tablet-test-42", listOf(PgmEditRow("p.pgm", "standard", false))); fail("Uncertain submission was replayed") }
            catch (_: MixOperationClientException) { }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    private fun completed(ok: Boolean, vararg statuses: String) = MixServiceOperation(id = "op", state = "completed", stage = "completed",
        result = mapOf("ok" to ok, "files" to statuses.mapIndexed { i, s -> mapOf("name" to "$i.pgm", "status" to s) }))

    private class FakeService(var submitted: MixServiceOperation = MixServiceOperation(id = "op")) : MixOperationService {
        val polls = ArrayDeque<MixServiceOperation>()
        val pollIds = mutableListOf<String>()
        val submissions = mutableListOf<List<Any>>()
        override suspend fun submitPgmEdits(job: String, material: String, requestId: String, files: List<PgmEditRow>): MixServiceOperation {
            submissions.add(listOf(job, material, requestId, files)); return submitted
        }
        override suspend fun getOperation(id: String): MixServiceOperation { pollIds.add(id); return polls.removeFirst() }
        override suspend fun submitMix(job: String, material: String, name: String, programs: List<String>, replaceExisting: Boolean): MixServiceOperation = error("unexpected mix")
        override suspend fun submitCatalogMutation(action: ManageCodeOperationAction): MixServiceOperation = error("unexpected catalog")
        override suspend fun listJobOperations(job: String): List<MixServiceOperation> = error("unexpected list")
    }
}
