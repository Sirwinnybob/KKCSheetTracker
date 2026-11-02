package com.kkc.sheettracker.ui.mixes

import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.mixservice.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SecondPassRefusalRecoveryTest {
    private val pgms = listOf("First.PGM")
    private fun missing() = PgmEditHistoryView(error = "history_missing", stateSource = "unknown")
    private fun ledger(vararg files: Pair<String, String>) = PgmEditHistoryView(
        files.associate { (name, mode) -> name to PgmEditFileHistory(PgmEditCurrentState(mode, false), stateSource = "ledger") },
        ok = true, stateSource = "ledger")
    private fun refused(code: String, error: String = "CNC refused before mutation") =
        MixServiceOperation(state = "failed", stage = "failed", code = code, error = error)
    private fun completed() = MixServiceOperation(state = "completed", result = mapOf("ok" to true,
        "files" to listOf(mapOf("name" to "First.PGM", "status" to "succeeded"))))
    private suspend fun staged(baseline: PgmEditHistoryView = missing()) = MixesSession().also {
        it.reloadHistory(pgms) { baseline }
        it.updateSecond(listOf("FIRST.pgm"), SecondPassField.SECOND, true, emptyList())
    }

    @Test fun provenFirstEditRefusalsPermitExplicitRetryWithoutLosingWantedOrRefusal() = runTest {
        val refusals = listOf(refused("edit_failed", "second-pass PGM editing is disabled"),
            refused("compile_busy"), refused("edit_busy"), refused("invalid_edit_request"), refused("missing_program"))
        for (baseline in listOf(missing(), ledger("Other.pgm" to "none"))) for (refusal in refusals) {
            val session = staged(baseline)
            session.startNew("M", emptyList(), emptyList())
            val mixDraft = session.draft
            val service = StubService { refusal }
            val runner = runner(service)
            session.saveSecond({ emptyList() }, { runner.apply("J", "M", planSecondPass(it)) {} }, { null })
            assertFalse(session.secondHistoryConfirmed)
            val refusalMessage = session.secondMessage!!.substringBefore(" ${MixesSession.HISTORY_FAILURE}")
            assertEquals(1, service.attempts)
            session.reloadHistory(listOf("first.pgm")) { baseline }
            assertTrue("${refusal.code}: ${baseline.stateSource}", session.secondHistoryConfirmed)
            assertEquals(1, session.secondPass!!.changeCount)
            assertEquals(SecondPassState(true, false, false), session.secondPass!!.wanted["first.pgm"])
            assertTrue(session.secondMessage.orEmpty().contains(refusalMessage))
            assertTrue(session.secondMessage.orEmpty().contains("not verified clean"))
            assertEquals(mixDraft, session.draft)
            assertEquals(1, service.attempts) // Retry history never resubmits a mutation.
            service.respond = { completed() }
            session.saveSecond({ emptyList() }, { runner.apply("J", "M", planSecondPass(it)) {} }, { ledger("FIRST.PGM" to "standard", "Other.pgm" to "none") })
            assertEquals(2, service.attempts)
            assertTrue(session.secondHistoryConfirmed)
            assertEquals(0, session.secondPass!!.changeCount)
            assertEquals("Second-pass changes applied", session.secondMessage)
            assertEquals(mixDraft, session.draft)
        }
    }

    @Test fun genericFailuresWithPartialFalseNeverRegainBootstrapPermission() = runTest {
        for (refusal in listOf(refused("edit_failed"), refused("operation_failed"), refused("future_code"),
            refused("edit_failed", "CLI disabled after writing one file"),
            refused("edit_failed", "SECOND-PASS PGM EDITING IS DISABLED"))) {
            val session = staged()
            val service = StubService { refusal }
            val runner = runner(service)
            session.saveSecond({ emptyList() }, {
                runner.apply("J", "M", planSecondPass(it)) {}.also { result -> assertFalse((result as SecondPassOutcome.Failed).partial) }
            }, { missing() })
            assertBlockedAfterRetry(session, service)
        }
    }

    @Test fun refusalRemainsVisibleThroughReadBackAndRetryFailures() = runTest {
        val session = staged()
        val service = StubService { refused("compile_busy", "CNC editor lock is busy") }
        val runner = runner(service)
        session.saveSecond({ emptyList() }, { runner.apply("J", "M", planSecondPass(it)) {} }, { throw IOException("history offline") })
        assertTrue(session.secondMessage.orEmpty().contains("CNC editor lock is busy"))
        session.reloadHistory(pgms) { null }
        assertFalse(session.secondHistoryConfirmed)
        assertTrue(session.secondMessage.orEmpty().contains("CNC editor lock is busy"))
        session.reloadHistory(pgms) { missing() }
        assertTrue(session.secondHistoryConfirmed)
        assertTrue(session.secondMessage.orEmpty().contains("CNC editor lock is busy"))
        assertEquals(1, session.secondPass!!.changeCount)
        assertEquals(1, service.attempts)
    }

    @Test fun timeoutTransportInterruptedAndCancellationKeepUncertainSubmissionGuard() = runTest {
        val responses: List<suspend () -> MixServiceOperation> = listOf(
            { refused("winxiso_timeout") }, { throw IOException("response lost") },
            { refused("compile_busy").copy(state = "interrupted") }, { throw CancellationException("screen left") })
        for (response in responses) {
            val session = staged()
            val service = StubService(response)
            val runner = runner(service)
            try { session.saveSecond({ emptyList() }, { runner.apply("J", "M", planSecondPass(it)) {} }, { missing() }) }
            catch (_: CancellationException) { }
            assertBlockedAfterRetry(session, service)
        }
    }

    @Test fun successfulEarlierStepMakesLaterRefusalPartialAndKeepsNewFileVerificationRequired() = runTest {
        val session = MixesSession()
        val names = listOf("Recorded.pgm", "First.PGM")
        session.reloadHistory(names) { ledger("Recorded.pgm" to "super") }
        session.updateSecond(names, SecondPassField.SUPER, false, emptyList())
        session.updateSecond(names, SecondPassField.SECOND, true, emptyList())
        val service = StubService { refused("compile_busy") }
        var reverts = 0
        val runner = SecondPassRunner(service, { _, _, _, _ -> reverts++; completed() }, { "id-$reverts" })
        session.saveSecond({ emptyList() }, {
            runner.apply("J", "M", planSecondPass(it)) {}.also { outcome -> assertTrue((outcome as SecondPassOutcome.Failed).partial) }
        }, { ledger("Recorded.pgm" to "none") })
        session.reloadHistory(names) { ledger("recorded.PGM" to "none") }
        assertFalse(session.secondHistoryConfirmed)
        assertEquals(2, session.secondPass!!.changeCount)
        assertEquals(1, reverts)
        assertEquals(1, service.attempts)
    }

    @Test fun recordedFileHistoryLossStillBlocksAfterProvenRefusal() = runTest {
        val session = staged(ledger("Recorded.pgm" to "none"))
        val service = StubService { refused("compile_busy") }
        val runner = runner(service)
        session.saveSecond({ emptyList() }, { runner.apply("J", "M", planSecondPass(it)) {} }, { ledger() })
        session.reloadHistory(pgms) { missing() }
        assertFalse(session.secondHistoryConfirmed)
        session.reloadHistory(pgms) { ledger() }
        assertFalse(session.secondHistoryConfirmed)
        session.reloadHistory(pgms) { ledger("RECORDED.PGM" to "none") }
        assertTrue(session.secondHistoryConfirmed)
        assertEquals(1, session.secondPass!!.changeCount)
    }

    @Test fun earlierUnknownSubmissionCannotBeClearedByAnotherRefusalOrDiscard() = runTest {
        val session = staged()
        val service = StubService { refused("edit_failed") }
        val runner = runner(service)
        session.saveSecond({ emptyList() }, { runner.apply("J", "M", planSecondPass(it)) {} }, { missing() })
        service.respond = { refused("compile_busy") }
        session.saveSecond({ emptyList() }, { runner.apply("J", "M", planSecondPass(it)) {} }, { missing() })
        assertEquals(1, service.attempts)
        session.discardSecond()
        session.reloadHistory(pgms) { missing() }
        assertFalse(session.secondHistoryConfirmed)
    }

    @Test fun nominalSuccessWithoutRecordedReadBackRemainsBlocked() = runTest {
        val session = staged()
        val service = StubService { completed() }
        val runner = runner(service)
        session.saveSecond({ emptyList() }, { runner.apply("J", "M", planSecondPass(it)) {} }, { missing() })
        assertBlockedAfterRetry(session, service)
    }

    private suspend fun assertBlockedAfterRetry(session: MixesSession, service: StubService) {
        session.reloadHistory(pgms) { missing() }
        assertFalse(session.secondHistoryConfirmed)
        assertEquals(1, session.secondPass!!.changeCount)
        session.saveSecond({ emptyList() }, { error("Uncertain outcome was resubmitted") }, { missing() })
        assertEquals(1, service.attempts)
    }
    private fun runner(service: StubService) = SecondPassRunner(service, { _, _, _, _ -> error("Unexpected revert") }, { "tablet-1" })
    private class StubService(var respond: suspend () -> MixServiceOperation) : MixOperationService {
        var attempts = 0
        override suspend fun submitPgmEdits(job: String, material: String, requestId: String, files: List<PgmEditRow>): MixServiceOperation {
            attempts++
            return respond()
        }
        override suspend fun getOperation(id: String): MixServiceOperation = error("Terminal fixture should not poll")
    }
}
