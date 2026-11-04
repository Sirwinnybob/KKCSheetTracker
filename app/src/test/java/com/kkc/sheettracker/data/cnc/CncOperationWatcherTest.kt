package com.kkc.sheettracker.data.cnc

import com.kkc.sheettracker.data.mixfiles.MixFilesCatalog
import com.kkc.sheettracker.data.mixfiles.MixFilesResult
import com.kkc.sheettracker.data.mixfiles.SecondPassOutcome
import com.kkc.sheettracker.data.mixfiles.SecondPassRunner
import com.kkc.sheettracker.data.mixfiles.SecondPassStep
import com.kkc.sheettracker.data.mixservice.MixOperationService
import com.kkc.sheettracker.data.mixservice.MixServiceOperation
import com.kkc.sheettracker.data.mixservice.PgmEditRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CncOperationWatcherTest {
    @get:Rule val temp = TemporaryFolder()
    private val red = CncMachines.scm
    private val blue = CncMachines.scmNew
    private val preview = TransferPreview("pv-1", emptyList(), listOf("R2.pgm"), emptyList(), emptyList(), true)
    private val succeeded = mapOf("ok" to true, "files" to listOf(mapOf("name" to "p.pgm", "status" to "succeeded")))

    private class FakeMoves(private val clock: () -> Long) : MaterialTransferApi {
        val starts = mutableListOf<String>()
        val pollTimes = mutableListOf<Long>()
        var startGate: CompletableDeferred<Unit>? = null
        var operation: TransferResult<TransferOperation> = TransferResult.Ok(TransferOperation("op-1", "staging", null, null))
        override suspend fun preview(request: TransferPreviewRequest) = error("unused")
        override suspend fun start(previewId: String, requestId: String): TransferResult<TransferOperation> {
            starts += requestId; startGate?.await()
            return TransferResult.Ok(TransferOperation("op-1", "preparing", null, null))
        }
        override suspend fun operation(operationId: String): TransferResult<TransferOperation> { pollTimes += clock(); return operation }
    }

    private class FakeReader(private val clock: () -> Long) : MixOperationReader {
        val reads = mutableListOf<Pair<String, Long>>()
        var next: () -> MixServiceOperation = { throw IllegalStateException("offline") }
        override suspend fun getOperation(operationId: String): MixServiceOperation { reads += operationId to clock(); return next() }
    }

    private class Harness(val watcher: CncOperationWatcher, val refreshes: MutableList<Triple<String, String, String>>,
        val published: MutableList<MixFilesCatalog>)

    private fun TestScope.harness(transfers: MaterialTransferStore, mixes: MixOperationStore, endpoints: MutableList<String>, reader: FakeReader): Harness {
        val refreshes = mutableListOf<Triple<String, String, String>>()
        val published = mutableListOf<MixFilesCatalog>()
        var ids = 0
        val watcher = CncOperationWatcher(transfers, mixes, backgroundScope, { endpoint -> endpoints += endpoint; reader },
            refreshCatalog = { m, j, mat -> refreshes += Triple(m, j, mat) }, publishCatalog = { _, _, _, c -> published += c },
            newRequestId = { "mix-${++ids}" })
        return Harness(watcher, refreshes, published)
    }

    private fun transferStore(api: FakeMoves) = MaterialTransferStore(temp.root.resolve("transfers.json"),
        clientFor = { endpoint -> assertEquals(blue.baseUrl, endpoint); api }, now = { 1_000L }, newRequestId = { "req-1" })
    private fun mixStore() = MixOperationStore(temp.root.resolve("mix_operations.json"), now = { 1_000L })
    /** A screen's own scope: cancelled when the operator leaves Mixes. */
    private fun TestScope.screenScope() = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

    @Test fun moveKeepsBeingWatchedAfterTheScreenLeavesMidPost() = runTest {
        val api = FakeMoves { testScheduler.currentTime }.apply { startGate = CompletableDeferred() }
        val transfers = transferStore(api)
        val h = harness(transfers, mixStore(), mutableListOf(), FakeReader { 0 })
        h.watcher.start()
        val screen = screenScope()
        screen.launch { h.watcher.startMove(red, blue, "711", "19mm", preview, "rev", PunloadPolicy.REMOVE) }
        runCurrent()
        screen.cancel() // The operator leaves Mixes while the POST is in flight.
        api.startGate!!.complete(Unit)
        runCurrent()
        assertEquals(TransferState.SUBMITTED, transfers.records.value.single().state)
        advanceTimeBy(30_000)
        assertTrue("polling continues without the screen", api.pollTimes.size >= 4)
        api.operation = TransferResult.Ok(TransferOperation("op-1", "completed", "completed", null))
        advanceTimeBy(10_001)
        assertEquals(TransferState.COMPLETED, transfers.records.value.single().state)
        val polls = api.pollTimes.size
        advanceTimeBy(60_000)
        assertEquals("polling stops at a terminal state", polls, api.pollTimes.size)
        assertEquals(listOf("req-1"), api.starts) // Never re-POSTed.
        assertTrue(h.refreshes.containsAll(listOf(Triple(red.id, "711", "19mm"), Triple(blue.id, "711", "19mm"))))
        val notice = h.watcher.notices.value.single()
        assertEquals(OperationNoticeTone.SUCCESS, notice.tone)
        assertEquals("Red SCM → Blue SCM", notice.machine)
        assertEquals("19mm", notice.material); assertEquals("711", notice.job)
    }

    @Test fun offlineMoveKeepsCheckingWithBackoffToTheCap() = runTest {
        val api = FakeMoves { testScheduler.currentTime }
        val transfers = transferStore(api)
        transfers.start(red, blue, "711", "19mm", preview, "rev", PunloadPolicy.REMOVE)
        api.operation = TransferResult.Offline
        val h = harness(transfers, mixStore(), mutableListOf(), FakeReader { 0 })
        h.watcher.start()
        runCurrent()
        advanceTimeBy(60_000)
        val gaps = api.pollTimes.zipWithNext { a, b -> b - a }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 10_000L, 10_000L), gaps.take(5))
        assertTrue(gaps.all { it <= 10_000L })
        assertEquals(TransferState.SUBMITTED, transfers.records.value.single().state)
        assertTrue(h.watcher.notices.value.isEmpty())
        // Back online: the same operation finishes; nothing was resent.
        api.operation = TransferResult.Ok(TransferOperation("op-1", "failed", "failed", "destination busy"))
        advanceTimeBy(10_001)
        assertEquals(TransferState.FAILED, transfers.records.value.single().state)
        assertEquals(1, api.starts.size)
        val notice = h.watcher.notices.value.single()
        assertEquals(OperationNoticeTone.FAILURE, notice.tone)
        assertTrue(notice.sticky)
    }

    @Test fun checkNowReadsImmediatelyAndRestartsBackoff() = runTest {
        val api = FakeMoves { testScheduler.currentTime }
        val transfers = transferStore(api)
        transfers.start(red, blue, "711", "19mm", preview, "rev", PunloadPolicy.REMOVE)
        api.operation = TransferResult.Offline
        val h = harness(transfers, mixStore(), mutableListOf(), FakeReader { 0 })
        h.watcher.start()
        runCurrent()
        advanceTimeBy(20_000) // Reads at 0, 2, 6, 14 s; next due at 24 s.
        val before = api.pollTimes.size
        h.watcher.checkNow("req-1")
        runCurrent()
        assertEquals(before + 1, api.pollTimes.size)
        advanceTimeBy(2_001)
        assertEquals(before + 2, api.pollTimes.size)
    }

    @Test fun persistedMoveResumesAfterRestart() = runTest {
        val api = FakeMoves { testScheduler.currentTime }
        transferStore(api).start(red, blue, "711", "19mm", preview, "rev", PunloadPolicy.REMOVE)
        api.operation = TransferResult.Ok(TransferOperation("op-1", "activating", "needs_attention", "activation unverified"))
        // A new process: fresh store from the same journal, fresh watcher.
        val transfers = transferStore(api)
        val h = harness(transfers, mixStore(), mutableListOf(), FakeReader { 0 })
        h.watcher.start()
        runCurrent()
        val record = transfers.records.value.single()
        assertEquals(TransferState.NEEDS_ATTENTION, record.state)
        assertEquals(1, api.starts.size)
        val notice = h.watcher.notices.value.single()
        assertEquals(OperationNoticeTone.ATTENTION, notice.tone)
        assertTrue(notice.sticky)
        advanceTimeBy(60_000)
        assertEquals("needs attention stays until dismissed", 1, h.watcher.notices.value.size)
        h.watcher.dismiss(notice.id)
        runCurrent()
        assertTrue(h.watcher.notices.value.isEmpty())
        // The notice is gone, but the material stays blocked until the office resolves it in Mixes.
        assertTrue(transfers.records.value.single().blocksMaterial)
        assertTrue(transferStore(api).records.value.single().noticeAcknowledged)
    }

    @Test fun secondPassPlanIsWatchedAfterTheScreenLeavesWithOnePoller() = runTest {
        val reader = FakeReader { testScheduler.currentTime }
        val service = object : MixOperationService {
            val posts = mutableListOf<String>()
            var screenPolls = 0
            override suspend fun submitPgmEdits(job: String, material: String, requestId: String, files: List<PgmEditRow>): MixServiceOperation {
                posts += requestId; return MixServiceOperation(id = "op-7", state = "queued")
            }
            override suspend fun getOperation(id: String): MixServiceOperation { screenPolls++; error("the runner must not poll") }
        }
        val endpoints = mutableListOf<String>()
        val mixes = mixStore()
        val h = harness(MaterialTransferStore(temp.root.resolve("t.json")), mixes, endpoints, reader)
        h.watcher.start()
        var outcome: SecondPassOutcome? = null
        val screen = screenScope()
        screen.launch {
            outcome = h.watcher.runSecondPass(blue, "711", "19mm") { tracker ->
                SecondPassRunner(service, { _, _, _, _ -> error("unused") }, { "tablet-t-1" }, tracker = tracker)
                    .apply("711", "19mm", listOf(SecondPassStep.Edit(listOf(PgmEditRow("p.pgm", "standard", true))))) {}
            }
        }
        runCurrent()
        assertEquals(MixOperationState.SUBMITTED, mixes.get("tablet-t-1")!!.state)
        assertEquals(blue.baseUrl, mixes.get("tablet-t-1")!!.endpoint)
        screen.cancel()
        advanceTimeBy(30_000) // Offline reads keep going.
        assertTrue(reader.reads.size >= 4)
        reader.next = { MixServiceOperation(id = "op-7", state = "completed", stage = "completed", result = succeeded) }
        advanceTimeBy(10_001)
        val reads = reader.reads.size
        advanceTimeBy(60_000)
        assertEquals(reads, reader.reads.size)
        assertNull("the screen was gone before the outcome", outcome)
        assertEquals(listOf("tablet-t-1"), service.posts)
        assertEquals(0, service.screenPolls)
        assertTrue(endpoints.all { it == blue.baseUrl })
        assertNull("step records are folded into one plan notice", mixes.get("tablet-t-1"))
        val notice = h.watcher.notices.value.single()
        assertEquals(OperationNoticeTone.SUCCESS, notice.tone)
        assertEquals("Blue SCM", notice.machine)
        assertEquals("Second-pass changes applied", notice.outcome)
        assertTrue(h.refreshes.contains(Triple(blue.id, "711", "19mm")))
    }

    @Test fun secondPassRunnerGetsTheWatchersTerminalSnapshot() = runTest {
        val reader = FakeReader { testScheduler.currentTime }.apply {
            next = { MixServiceOperation(id = "op-7", state = "failed", stage = "failed", error = "edit busy", code = "edit_busy") }
        }
        val service = object : MixOperationService {
            override suspend fun submitPgmEdits(job: String, material: String, requestId: String, files: List<PgmEditRow>) =
                MixServiceOperation(id = "op-7", state = "queued")
            override suspend fun getOperation(id: String): MixServiceOperation = error("the runner must not poll")
        }
        val mixes = mixStore()
        val h = harness(MaterialTransferStore(temp.root.resolve("t.json")), mixes, mutableListOf(), reader)
        h.watcher.start()
        h.watcher.viewing(blue.id, "711", "19mm") // Mixes is open: it shows this refusal inline.
        var outcome: SecondPassOutcome? = null
        backgroundScope.launch {
            outcome = h.watcher.runSecondPass(blue, "711", "19mm") { tracker ->
                SecondPassRunner(service, { _, _, _, _ -> error("unused") }, { "tablet-t-2" }, tracker = tracker)
                    .apply("711", "19mm", listOf(SecondPassStep.Edit(listOf(PgmEditRow("p.pgm", "standard", true))))) {}
            }
        }
        advanceTimeBy(2_001)
        val failed = outcome as SecondPassOutcome.Failed
        assertEquals("edit_busy", failed.code)
        assertEquals(com.kkc.sheettracker.data.mixfiles.SecondPassMutation.REFUSED_BEFORE_MUTATION, failed.mutation)
        assertTrue("viewed refusals aren't repeated app-wide", h.watcher.notices.value.isEmpty())
        assertTrue(mixes.records.value.isEmpty())
    }

    @Test fun persistedSecondPassOperationResumesAfterRestart() = runTest {
        val first = mixStore()
        first.begin("tablet-t-3", MixOperationKind.SECOND_PASS_REVERT, blue.id, blue.baseUrl, "711", "19mm", "Second-pass changes", 1, 2)
        first.update("tablet-t-3") { it.copy(state = MixOperationState.SUBMITTED, operationId = "op-9") }
        val reader = FakeReader { testScheduler.currentTime }.apply {
            next = { MixServiceOperation(id = "op-9", state = "completed", stage = "completed", result = succeeded) }
        }
        val endpoints = mutableListOf<String>()
        val mixes = mixStore() // New process.
        val h = harness(MaterialTransferStore(temp.root.resolve("t.json")), mixes, endpoints, reader)
        h.watcher.start()
        runCurrent()
        assertEquals(listOf(blue.baseUrl), endpoints)
        val record = mixes.get("tablet-t-3")!!
        // Step 1 of 2 finished, but the plan's second step died with the old process.
        assertEquals(MixOperationState.NEEDS_ATTENTION, record.state)
        val notice = h.watcher.notices.value.single()
        assertEquals(OperationNoticeTone.ATTENTION, notice.tone)
        advanceTimeBy(60_000)
        assertEquals(1, reader.reads.size)
        h.watcher.dismiss(notice.id)
        runCurrent()
        assertTrue(mixes.records.value.isEmpty())
    }

    @Test fun interruptedOperationNeedsAttention() = runTest {
        val first = mixStore()
        first.begin("tablet-t-4", MixOperationKind.SECOND_PASS_EDIT, red.id, red.baseUrl, "711", "19mm", "Second-pass changes")
        first.update("tablet-t-4") { it.copy(state = MixOperationState.SUBMITTED, operationId = "op-4") }
        val reader = FakeReader { 0 }.apply { next = { MixServiceOperation(id = "op-4", state = "interrupted", stage = "interrupted") } }
        val mixes = mixStore()
        val h = harness(MaterialTransferStore(temp.root.resolve("t.json")), mixes, mutableListOf(), reader)
        h.watcher.start()
        runCurrent()
        assertEquals(MixOperationState.NEEDS_ATTENTION, mixes.get("tablet-t-4")!!.state)
        assertTrue(h.watcher.notices.value.single().sticky)
    }

    @Test fun mixWriteDiedMidPostIsUnconfirmedAfterRestart() = runTest {
        mixStore().begin("mix-1", MixOperationKind.MIX_WRITE, blue.id, blue.baseUrl, "711", "19mm", "Save 19mm Remaining.mix")
        val mixes = mixStore()
        val h = harness(MaterialTransferStore(temp.root.resolve("t.json")), mixes, mutableListOf(), FakeReader { 0 })
        h.watcher.start()
        runCurrent()
        assertEquals(MixOperationState.UNCERTAIN, mixes.get("mix-1")!!.state)
        val notice = h.watcher.notices.value.single()
        assertEquals(OperationNoticeTone.ATTENTION, notice.tone)
        assertTrue(notice.outcome.startsWith("Save 19mm Remaining.mix: not confirmed"))
    }

    @Test fun mixWriteFinishesAfterTheScreenLeavesAndIsNeverResent() = runTest {
        val gate = CompletableDeferred<Unit>()
        var posts = 0
        val catalog = MixFilesCatalog("711", "19mm", "folder-2", emptyList())
        val mixes = mixStore()
        val h = harness(MaterialTransferStore(temp.root.resolve("t.json")), mixes, mutableListOf(), FakeReader { 0 })
        h.watcher.start()
        val screen = screenScope()
        screen.launch {
            h.watcher.submitMixWrite(blue, "711", "19mm", "Save 19mm Remaining.mix") { posts++; gate.await(); MixFilesResult.Ok(catalog) }
        }
        runCurrent()
        assertEquals(MixOperationState.SUBMITTING, mixes.records.value.single().state)
        screen.cancel()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, posts)
        assertEquals(listOf(catalog), h.published)
        assertEquals("Save 19mm Remaining.mix: done", h.watcher.notices.value.single().outcome)
    }

    @Test fun lostMixWriteReplyIsUnconfirmedAndRefreshed() = runTest {
        var posts = 0
        val mixes = mixStore()
        val h = harness(MaterialTransferStore(temp.root.resolve("t.json")), mixes, mutableListOf(), FakeReader { 0 })
        h.watcher.start()
        h.watcher.viewing(red.id, "711", "19mm")
        val result = h.watcher.submitMixWrite(red, "711", "19mm", "Rename A.mix to B.mix") { posts++; MixFilesResult.Offline }
        runCurrent()
        assertEquals(MixFilesResult.Offline, result)
        assertEquals(1, posts)
        assertEquals(MixOperationState.UNCERTAIN, mixes.records.value.single().state)
        assertEquals(listOf(Triple(red.id, "711", "19mm")), h.refreshes)
        // Even on the open screen, an unconfirmed save stays visible until dismissed.
        assertEquals(OperationNoticeTone.ATTENTION, h.watcher.notices.value.single().tone)
        advanceTimeBy(60_000)
        assertEquals(1, posts)
    }

    @Test fun viewedCompletionIsNotRepeatedAppWide() = runTest {
        val mixes = mixStore()
        val h = harness(MaterialTransferStore(temp.root.resolve("t.json")), mixes, mutableListOf(), FakeReader { 0 })
        h.watcher.start()
        val lease = h.watcher.viewing(red.id, "711", "19mm")
        h.watcher.submitMixWrite(red, "711", "19mm", "Save A.mix") { MixFilesResult.Ok(MixFilesCatalog("711", "19mm", "r", emptyList())) }
        runCurrent()
        assertTrue(h.watcher.notices.value.isEmpty())
        assertTrue(mixes.records.value.isEmpty())
        lease.close()
        h.watcher.submitMixWrite(red, "711", "19mm", "Save A.mix") { MixFilesResult.Ok(MixFilesCatalog("711", "19mm", "r2", emptyList())) }
        runCurrent()
        assertEquals(1, h.watcher.notices.value.size)
    }
}
