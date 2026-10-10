package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliveryScheduleFailure
import com.kkc.sheettracker.data.models.DeliverySlot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DeliveryScheduleEditorTest {
    private val a = DeliveryJob("670", "ESCHRICH", folderName = "670 - ESCHRICH")
    private val b = DeliveryJob("702", "SMITH", folderName = "702 - SMITH")
    private val mon = LocalDate.of(2026, 10, 12)
    private val v2 = DeliverySchedule(isDated = true, weekOf = mon)

    private class FakeRemote(
        var put: DeliveryJobDateResult = DeliveryJobDateResult.Unavailable,
        var post: DeliveryScheduleApplyResult? = null
    ) : DeliveryScheduleRemote {
        val puts = mutableListOf<Triple<String, LocalDate?, String?>>()
        val posts = mutableListOf<DeliveryScheduleEditRequest>()
        override suspend fun applyEdits(request: DeliveryScheduleEditRequest) = post.also { posts += request }
        override suspend fun setJobDate(folderName: String, date: LocalDate?, window: String?) =
            put.also { puts += Triple(folderName, date, window) }
    }

    private class FakeQueue : DeliveryScheduleQueue {
        val edits = mutableListOf<Pair<String, List<DeliveryJob>>>()
        var resets = 0
        override suspend fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String): DeliveryQueueResult {
            edits += slot to jobs
            return DeliveryQueueResult(true, emptyList())
        }
        override suspend fun queueReset(tabletId: String, mirroredWeekOf: LocalDate?) { resets++ }
    }

    @Test
    fun onlineDatedPlaceUsesPerJobPut() = runBlocking {
        val applied = DeliveryScheduleApplyResult(v2.withSlot("2026-10-21_pm", listOf(a)), emptyList())
        val remote = FakeRemote(put = DeliveryJobDateResult.Applied(applied))
        val queue = FakeQueue()
        val outcome = DeliveryScheduleEditor(remote, queue, "T1").placeJob(v2, a, "2026-10-21_pm", fromSlot = "2026-10-14_am")
        assertEquals(DeliveryEditOutcome.Applied(applied.schedule, emptyList()), outcome)
        assertEquals(listOf(Triple("670 - ESCHRICH", LocalDate.of(2026, 10, 21), "pm")), remote.puts)
        assertTrue(remote.posts.isEmpty())
        assertTrue(queue.edits.isEmpty())
    }

    @Test
    fun refusedPutIsReportedAndNotRetried() = runBlocking {
        val remote = FakeRemote(put = DeliveryJobDateResult.Refused(409, "Wed 10/21 PM already has 3 deliveries"))
        val queue = FakeQueue()
        val outcome = DeliveryScheduleEditor(remote, queue, "T1").placeJob(v2, a, "2026-10-21_pm", null)
        assertTrue(outcome is DeliveryEditOutcome.Refused)
        assertEquals(listOf("Job 670 not placed: Wed 10/21 PM already has 3 deliveries"), outcome.notices)
        assertTrue(remote.posts.isEmpty() && queue.edits.isEmpty())
    }

    @Test
    fun refusedPutWithRawJsonReasonFallsBackToStatusText() = runBlocking {
        val remote = FakeRemote(put = DeliveryJobDateResult.Refused(422, "[{\"loc\":[\"body\"],\"msg\":\"x\"}]"))
        val outcome = DeliveryScheduleEditor(remote, FakeQueue(), "T1").placeJob(v2, a, "2026-10-21_pm", null)
        assertTrue(outcome is DeliveryEditOutcome.Refused)
        assertEquals(listOf("Job 670 not placed: refused by Hours Tracker (422)"), outcome.notices)
    }

    @Test
    fun putUnavailableFallsBackToSlotEditThenRequestFile() = runBlocking {
        val current = v2.withSlot("2026-10-21_pm", listOf(b))
        val remote = FakeRemote(put = DeliveryJobDateResult.Unavailable, post = null)
        val queue = FakeQueue()
        val outcome = DeliveryScheduleEditor(remote, queue, "T1").placeJob(current, a, "2026-10-21_pm", "2026-10-14_am")
        assertTrue(outcome is DeliveryEditOutcome.Queued)
        // Dated: only the destination slot is sent (the job's one date moves it).
        assertEquals(listOf("2026-10-21_pm" to listOf(b, a)), remote.posts.single().slotEdits.map { it.slot to it.jobs })
        assertEquals(listOf("2026-10-21_pm" to listOf(b, a)), queue.edits)
    }

    @Test
    fun adminSyncFailuresBecomeNotices() = runBlocking {
        val failure = DeliveryScheduleFailure("2026-10-21_pm", "670", "670 - ESCHRICH", 409, "")
        val remote = FakeRemote(post = DeliveryScheduleApplyResult(v2, listOf(failure)))
        val outcome = DeliveryScheduleEditor(remote, FakeQueue(), "T1").replaceSlot(v2, "2026-10-21_pm", listOf(a))
        assertEquals(listOf("Job 670 not placed: that half-day already has 3 deliveries"), outcome.notices)
    }

    @Test
    fun v1ScheduleNeverUsesPutAndMovesBothSlots() = runBlocking {
        val v1 = DeliverySchedule(slots = mapOf("monday_am" to DeliverySlot(listOf(a)), "tuesday_pm" to DeliverySlot()))
        val remote = FakeRemote(put = DeliveryJobDateResult.Refused(404, "should not be called"), post = null)
        val queue = FakeQueue()
        DeliveryScheduleEditor(remote, queue, "T1").placeJob(v1, a, "tuesday_pm", "monday_am")
        assertTrue(remote.puts.isEmpty())
        assertEquals(setOf("tuesday_pm" to listOf(a), "monday_am" to emptyList<DeliveryJob>()), queue.edits.toSet())
    }

    @Test
    fun fullHalfDayRefusedLocally() = runBlocking {
        val full = v2.withSlot("2026-10-21_pm", listOf(b, b.copy(jobNumber = "703", folderName = "703 - X"), b.copy(jobNumber = "704", folderName = "704 - Y")))
        val remote = FakeRemote()
        val outcome = DeliveryScheduleEditor(remote, FakeQueue(), "T1").placeJob(full, a, "2026-10-21_pm", null)
        assertTrue(outcome is DeliveryEditOutcome.Refused)
        assertTrue(remote.puts.isEmpty())
    }

    @Test
    fun datedPlaceWithoutFolderRefused() = runBlocking {
        val outcome = DeliveryScheduleEditor(FakeRemote(), FakeQueue(), "T1").placeJob(v2, a.copy(folderName = ""), "2026-10-21_pm", null)
        assertTrue(outcome is DeliveryEditOutcome.Refused)
    }

    @Test
    fun secondOfflinePlaceIntoSameSlotKeepsFirstJob() = runBlocking {
        val queue = FakeQueue()
        val editor = DeliveryScheduleEditor(remote = null, queue = queue, tabletId = "T1")
        editor.placeJob(v2, a, "2026-10-21_pm", null)
        // UI passes live schedule + own pending request overlaid.
        val pending = DeliveryScheduleEditRequest("T1", "x", slotEdits = queue.edits.map { DeliveryScheduleSlotEdit(it.first, it.second) })
        editor.placeJob(overlayPendingDeliveryRequest(v2, pending), b, "2026-10-21_pm", null)
        assertEquals(listOf(a, b), queue.edits.last().second)
    }

    @Test
    fun removeOnlineClearsDateOfflineSendsSlotWithoutJob() = runBlocking {
        val current = v2.withSlot("2026-10-21_pm", listOf(a, b))
        val onlineRemote = FakeRemote(put = DeliveryJobDateResult.Applied(DeliveryScheduleApplyResult(v2, emptyList())))
        DeliveryScheduleEditor(onlineRemote, FakeQueue(), "T1").removeJob(current, a, "2026-10-21_pm")
        assertEquals(Triple("670 - ESCHRICH", null as LocalDate?, null as String?), onlineRemote.puts.single())

        val queue = FakeQueue()
        DeliveryScheduleEditor(null, queue, "T1").removeJob(current, a, "2026-10-21_pm")
        assertEquals(listOf("2026-10-21_pm" to listOf(b)), queue.edits)
    }

    @Test
    fun resetWeekPostsThenQueuesWithWeekOf() = runBlocking {
        val remote = FakeRemote(post = null)
        val queue = FakeQueue()
        val outcome = DeliveryScheduleEditor(remote, queue, "T1").resetWeek(v2)
        assertTrue(remote.posts.single().resetAll)
        assertEquals(1, queue.resets)
        assertTrue(outcome is DeliveryEditOutcome.Queued)
    }
}
