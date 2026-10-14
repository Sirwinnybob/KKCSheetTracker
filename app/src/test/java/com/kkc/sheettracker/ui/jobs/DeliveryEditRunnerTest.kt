package com.kkc.sheettracker.ui.jobs

import com.kkc.sheettracker.data.DeliveryEditOutcome
import com.kkc.sheettracker.data.DeliveryJobDateResult
import com.kkc.sheettracker.data.DeliveryQueueResult
import com.kkc.sheettracker.data.DeliveryScheduleEditRequest
import com.kkc.sheettracker.data.DeliveryScheduleEditor
import com.kkc.sheettracker.data.DeliveryScheduleQueue
import com.kkc.sheettracker.data.DeliveryScheduleSlotEdit
import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DeliveryEditRunnerTest {
    private val schedule = DeliverySchedule(isDated = true, weekOf = LocalDate.of(2026, 10, 12))
    private val editor = DeliveryScheduleEditor(null, object : DeliveryScheduleQueue {
        override suspend fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String) =
            DeliveryQueueResult(true, emptyList())
        override suspend fun queueReset(tabletId: String, mirroredWeekOf: LocalDate?) = true
    }, "T1")

    // One thread, FIFO: edits are launched, and reach the runner's lock, in the order they were submitted.
    private val executor = Executors.newSingleThreadExecutor()
    private val ordered: CoroutineDispatcher = executor.asCoroutineDispatcher()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun runner(
        logged: MutableList<Throwable> = mutableListOf(),
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ) = DeliveryEditRunner(
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        mutex = Mutex(),
        log = { _, t -> logged += t }
    )

    private fun DeliveryEditRunner.submit(
        results: MutableList<DeliveryEditResult>,
        edit: suspend (DeliveryScheduleEditor, DeliverySchedule) -> DeliveryEditOutcome
    ): Job = run(
        editor = { editor },
        currentSchedule = { schedule },
        readPending = { null },
        onApplied = {},
        onResult = { results += it },
        edit = edit
    )

    @Test
    fun editExceptionBecomesNoticeAndRevertInsteadOfCrash() = runBlocking {
        val logged = mutableListOf<Throwable>()
        val results = Collections.synchronizedList(mutableListOf<DeliveryEditResult>())
        val job = runner(logged).submit(results) { _, _ -> throw IOException("disk full") }
        withTimeout(5_000) { job.join() }

        assertFalse("edit job must not fail", job.isCancelled)
        assertEquals(listOf(DELIVERY_EDIT_FAILED_NOTICE), results.single().notices)
        assertTrue(results.single().revert)
        assertTrue(logged.single() is IOException)
    }

    @Test
    fun cancellingTheCallerDoesNotCancelTheEdit() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val submitted = CompletableDeferred<Job>()
        val results = Collections.synchronizedList(mutableListOf<DeliveryEditResult>())
        val r = runner()
        // Stands in for the screen's composition scope, which is cancelled when the route leaves.
        val caller = launch(Dispatchers.Default) {
            submitted.complete(
                r.submit(results) { _, _ ->
                    gate.await()
                    DeliveryEditOutcome.Queued(emptyList())
                }
            )
            awaitCancellation()
        }
        val editJob = withTimeout(5_000) { submitted.await() }
        caller.cancel()
        caller.join()
        gate.complete(Unit)
        withTimeout(5_000) { editJob.join() }

        assertFalse(editJob.isCancelled)
        assertEquals(1, results.size)
        assertTrue(results.single().notices.isEmpty())
        assertFalse(results.single().revert)
    }

    @Test
    fun queuedEditsReadTheLiveScheduleLeftByThePreviousEditInsideTheLock() = runBlocking {
        var live = schedule
        val applied = schedule.withSlot("2026-10-21_pm", listOf(DeliveryJob("670", "E", folderName = "670 - E")))
        val seen = Collections.synchronizedList(mutableListOf<DeliverySchedule>())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val r = runner(dispatcher = ordered)
        val first = r.run({ editor }, { live }, { null }, onApplied = { live = it }, onResult = {}) { _, current ->
            seen += current
            firstStarted.complete(Unit)
            releaseFirst.await()
            DeliveryEditOutcome.Applied(applied, emptyList())
        }
        // The second edit is submitted only once the first holds the lock, so the order is by construction.
        withTimeout(5_000) { firstStarted.await() }
        val second = r.run({ editor }, { live }, { null }, onApplied = { live = it }, onResult = {}) { _, current ->
            seen += current
            DeliveryEditOutcome.Queued(emptyList())
        }
        releaseFirst.complete(Unit)
        withTimeout(5_000) { first.join(); second.join() }
        assertEquals(listOf(schedule, applied), seen.toList())
    }

    @Test
    fun editsNeverOverlapWhateverTheDispatcher() = runBlocking {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val results = Collections.synchronizedList(mutableListOf<DeliveryEditResult>())
        val r = runner()
        val jobs = (1..8).map {
            r.submit(results) { _, _ ->
                maxActive.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
                kotlinx.coroutines.delay(5)
                active.decrementAndGet()
                DeliveryEditOutcome.Queued(emptyList())
            }
        }
        withTimeout(10_000) { jobs.forEach { it.join() } }
        assertEquals(1, maxActive.get())
        assertEquals(8, results.size)
    }

    @Test
    fun pendingRequestIsOverlaidOnTheLiveScheduleAndReReadAfterwards() = runBlocking {
        val pending = DeliveryScheduleEditRequest("T1", "x", slotEdits = listOf(
            DeliveryScheduleSlotEdit("2026-10-21_am", listOf(DeliveryJob("702", "S", folderName = "702 - S")))
        ))
        var seen: DeliverySchedule? = null
        var result: DeliveryEditResult? = null
        val job = runner().run({ editor }, { schedule }, { pending }, onApplied = {}, onResult = { result = it }) { _, current ->
            seen = current
            DeliveryEditOutcome.Queued(emptyList())
        }
        withTimeout(5_000) { job.join() }
        assertEquals("702", seen!!.slotByKey("2026-10-21_am").jobs.single().jobNumber)
        assertEquals(pending, result!!.pending)
    }

    // ---- C5: a refused edit must not wipe the optimistic state of a later edit still queued ----

    @Test
    fun revertIsHeldUntilTheLastQueuedEditFinishes() = runBlocking {
        val results = Collections.synchronizedList(mutableListOf<DeliveryEditResult>())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val r = runner(dispatcher = ordered)
        val first = r.submit(results) { _, _ ->
            firstStarted.complete(Unit)
            releaseFirst.await()
            DeliveryEditOutcome.Refused(listOf("Job 1 not placed: that day already has 6 deliveries"))
        }
        withTimeout(5_000) { firstStarted.await() }
        val second = r.submit(results) { _, _ -> DeliveryEditOutcome.Queued(emptyList()) }
        assertEquals(2, r.inFlight.value)
        releaseFirst.complete(Unit)
        withTimeout(5_000) { first.join(); second.join() }

        assertEquals(2, results.size)
        // The refusal's notice is delivered at once, but it must not trigger the revert...
        assertEquals(listOf("Job 1 not placed: that day already has 6 deliveries"), results[0].notices)
        assertFalse("revert while a later edit is still queued", results[0].revert)
        // ...which waits for the last edit, so it lands exactly once.
        assertTrue(results[1].revert)
        assertEquals(0, r.inFlight.value)
    }

    @Test
    fun aSingleRefusedEditRevertsImmediately() = runBlocking {
        val results = Collections.synchronizedList(mutableListOf<DeliveryEditResult>())
        val r = runner()
        withTimeout(5_000) {
            r.submit(results) { _, _ -> DeliveryEditOutcome.Refused(listOf("nope")) }.join()
        }
        assertTrue(results.single().revert)
        assertEquals(0, r.inFlight.value)
    }

    @Test
    fun aLaterRefusalRevertsAndACleanRunLeavesNoStaleRevert() = runBlocking {
        val results = Collections.synchronizedList(mutableListOf<DeliveryEditResult>())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val r = runner(dispatcher = ordered)
        val first = r.submit(results) { _, _ ->
            firstStarted.complete(Unit)
            releaseFirst.await()
            DeliveryEditOutcome.Queued(emptyList())
        }
        withTimeout(5_000) { firstStarted.await() }
        val second = r.submit(results) { _, _ -> DeliveryEditOutcome.Refused(listOf("nope")) }
        releaseFirst.complete(Unit)
        withTimeout(5_000) { first.join(); second.join() }
        assertEquals(listOf(false, true), results.map { it.revert })

        // Nothing is left over for the next, clean edit.
        val third = r.submit(results) { _, _ -> DeliveryEditOutcome.Queued(emptyList()) }
        withTimeout(5_000) { third.join() }
        assertFalse(results.last().revert)
    }

    @Test
    fun inFlightCountsRunningAndWaitingEditsAndDropsToZero() = runBlocking {
        val results = Collections.synchronizedList(mutableListOf<DeliveryEditResult>())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val r = runner(dispatcher = ordered)
        assertEquals(0, r.inFlight.value)
        val first = r.submit(results) { _, _ -> started.complete(Unit); release.await(); DeliveryEditOutcome.Queued(emptyList()) }
        withTimeout(5_000) { started.await() }
        val second = r.submit(results) { _, _ -> DeliveryEditOutcome.Queued(emptyList()) }
        assertEquals(2, r.inFlight.value)
        release.complete(Unit)
        withTimeout(5_000) { first.join(); second.join() }
        assertEquals(0, r.inFlight.value)
    }

    // ---- C3: keep the consumed request's overlay until the schedule changes ----

    private val request = DeliveryScheduleEditRequest(
        "T1", "x", slotEdits = listOf(DeliveryScheduleSlotEdit("2026-10-21_am", listOf(DeliveryJob("702", "S", folderName = "702 - S"))))
    )
    private val changed = schedule.withSlot("2026-10-21_am", listOf(DeliveryJob("702", "S", folderName = "702 - S")))

    @Test
    fun aVanishedRequestIsHeldAgainstTheScheduleItVanishedUnder() {
        val held = nextHeldDeliveryRequest(previous = request, new = null, held = null, schedule = schedule)
        assertEquals(HeldDeliveryRequest(request, schedule), held)
        // Same schedule: keep overlaying; the request itself wins whenever it is back.
        assertSame(request, effectiveDeliveryOverlayRequest(null, held, schedule))
        assertSame(request, effectiveDeliveryOverlayRequest(request, null, schedule))
        // The live schedule moved on: the hold is dropped, no flicker-prevention needed any more.
        assertNull(effectiveDeliveryOverlayRequest(null, held, changed))
    }

    @Test
    fun aNewRequestClearsTheHoldAndNothingNewKeepsIt() {
        val held = HeldDeliveryRequest(request, schedule)
        assertNull(nextHeldDeliveryRequest(previous = null, new = request, held = held, schedule = schedule))
        assertSame(held, nextHeldDeliveryRequest(previous = null, new = null, held = held, schedule = schedule))
        assertNull(nextHeldDeliveryRequest(previous = null, new = null, held = null, schedule = schedule))
    }

    // ---- C12: the server URL is resolved per edit ----

    @Test
    fun lazyRemoteAsksForTheClientOnEveryCallAndDegradesWhenThereIsNone() = runBlocking {
        val asked = AtomicInteger(0)
        val remote = lazyDeliveryRemote { asked.incrementAndGet(); null }
        assertNull(remote.applyEdits(DeliveryScheduleEditRequest("T1", "x")))
        assertSame(
            DeliveryJobDateResult.Unavailable,
            remote.setJobDate("670 - E", LocalDate.of(2026, 10, 21), "am", "T1")
        )
        assertEquals(2, asked.get())
    }

    @Test
    fun anEditMadeBeforeAnyServerIsKnownIsQueuedNotLostOrCrashed() = runBlocking {
        val queued = Collections.synchronizedList(mutableListOf<String>())
        val queueingEditor = DeliveryScheduleEditor(
            lazyDeliveryRemote { null },
            object : DeliveryScheduleQueue {
                override suspend fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String): DeliveryQueueResult {
                    queued += slot
                    return DeliveryQueueResult(true, emptyList())
                }
                override suspend fun queueReset(tabletId: String, mirroredWeekOf: LocalDate?) = true
            },
            "T1"
        )
        val outcome = queueingEditor.placeJob(schedule, DeliveryJob("670", "E", folderName = "670 - E"), "2026-10-21_am", null)
        assertTrue(outcome is DeliveryEditOutcome.Queued)
        assertEquals(listOf("2026-10-21_am"), queued.toList())
    }
}
