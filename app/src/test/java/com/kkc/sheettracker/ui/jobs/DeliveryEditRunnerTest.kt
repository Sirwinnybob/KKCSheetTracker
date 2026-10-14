package com.kkc.sheettracker.ui.jobs

import com.kkc.sheettracker.data.DeliveryEditOutcome
import com.kkc.sheettracker.data.DeliveryQueueResult
import com.kkc.sheettracker.data.DeliveryScheduleEditRequest
import com.kkc.sheettracker.data.DeliveryScheduleEditor
import com.kkc.sheettracker.data.DeliveryScheduleQueue
import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.util.Collections

class DeliveryEditRunnerTest {
    private val schedule = DeliverySchedule(isDated = true, weekOf = LocalDate.of(2026, 10, 12))
    private val editor = DeliveryScheduleEditor(null, object : DeliveryScheduleQueue {
        override suspend fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String) =
            DeliveryQueueResult(true, emptyList())
        override suspend fun queueReset(tabletId: String, mirroredWeekOf: LocalDate?) = Unit
    }, "T1")

    private fun runner(logged: MutableList<Throwable> = mutableListOf()) = DeliveryEditRunner(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        mutex = Mutex(),
        log = { _, t -> logged += t }
    )

    @Test
    fun editExceptionBecomesNoticeAndRevertInsteadOfCrash() = runBlocking {
        val logged = mutableListOf<Throwable>()
        val results = Collections.synchronizedList(mutableListOf<DeliveryEditResult>())
        val job = runner(logged).run(
            editor = { editor },
            currentSchedule = { schedule },
            readPending = { null },
            onApplied = {},
            onResult = { results += it }
        ) { _, _ -> throw IOException("disk full") }
        withTimeout(5_000) { job.join() }

        assertFalse("edit job must not fail", job.isCancelled)
        assertEquals(listOf(DELIVERY_EDIT_FAILED_NOTICE), results.single().notices)
        assertTrue(results.single().revert)
        assertTrue(logged.single() is IOException)
    }

    @Test
    fun cancellingTheCallerDoesNotCancelTheEdit() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val results = Collections.synchronizedList(mutableListOf<DeliveryEditResult>())
        val r = runner()
        var editJob: kotlinx.coroutines.Job? = null
        // Stands in for the screen's composition scope, which is cancelled when the route leaves.
        val caller = launch(Dispatchers.Default) {
            editJob = r.run(
                editor = { editor },
                currentSchedule = { schedule },
                readPending = { null },
                onApplied = {},
                onResult = { results += it }
            ) { _, _ ->
                gate.await()
                DeliveryEditOutcome.Queued(emptyList())
            }
            awaitCancellation()
        }
        while (editJob == null) kotlinx.coroutines.yield()
        caller.cancel()
        caller.join()
        gate.complete(Unit)
        withTimeout(5_000) { editJob!!.join() }

        assertFalse(editJob!!.isCancelled)
        assertEquals(1, results.size)
        assertTrue(results.single().notices.isEmpty())
        assertFalse(results.single().revert)
    }

    @Test
    fun queuedEditsReadTheLiveScheduleLeftByThePreviousEditInsideTheLock() = runBlocking {
        var live = schedule
        val applied = schedule.withSlot("2026-10-21_pm", listOf(DeliveryJob("670", "E", folderName = "670 - E")))
        val seen = Collections.synchronizedList(mutableListOf<DeliverySchedule>())
        val r = runner()
        val first = r.run({ editor }, { live }, { null }, onApplied = { live = it }, onResult = {}) { _, current ->
            seen += current
            DeliveryEditOutcome.Applied(applied, emptyList())
        }
        val second = r.run({ editor }, { live }, { null }, onApplied = { live = it }, onResult = {}) { _, current ->
            seen += current
            DeliveryEditOutcome.Queued(emptyList())
        }
        withTimeout(5_000) { first.join(); second.join() }
        assertEquals(listOf(schedule, applied), seen.toList())
    }

    @Test
    fun pendingRequestIsOverlaidOnTheLiveScheduleAndReReadAfterwards() = runBlocking {
        val pending = DeliveryScheduleEditRequest("T1", "x", slotEdits = listOf(
            com.kkc.sheettracker.data.DeliveryScheduleSlotEdit("2026-10-21_am", listOf(DeliveryJob("702", "S", folderName = "702 - S")))
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
}
