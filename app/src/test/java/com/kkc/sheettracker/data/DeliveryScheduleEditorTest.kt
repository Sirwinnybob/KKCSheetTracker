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
        val putTablets = mutableListOf<String>()
        override suspend fun setJobDate(folderName: String, date: LocalDate?, window: String?, tabletId: String) =
            put.also { puts += Triple(folderName, date, window); putTablets += tabletId }
    }

    private class FakeQueue(
        val drop: (slot: String, jobs: List<DeliveryJob>) -> List<DeliveryJob> = { _, _ -> emptyList() },
        /** When set, replaces the default "queued, dropping [drop]" answer. */
        val answer: ((slot: String, jobs: List<DeliveryJob>) -> DeliveryQueueResult)? = null,
        val resetQueued: Boolean = true
    ) : DeliveryScheduleQueue {
        val edits = mutableListOf<Pair<String, List<DeliveryJob>>>()
        var resets = 0
        override suspend fun queueSlotEdit(slot: String, jobs: List<DeliveryJob>, tabletId: String): DeliveryQueueResult {
            edits += slot to jobs
            return answer?.invoke(slot, jobs) ?: DeliveryQueueResult(true, drop(slot, jobs))
        }
        override suspend fun queueReset(tabletId: String, mirroredWeekOf: LocalDate?): Boolean {
            resets++
            return resetQueued
        }
    }

    @Test
    fun onlineDatedPlaceUsesPerJobPut() = runBlocking {
        val applied = DeliveryScheduleApplyResult(v2.withSlot("2026-10-21_pm", listOf(a)), emptyList())
        val remote = FakeRemote(put = DeliveryJobDateResult.Applied(applied))
        val queue = FakeQueue()
        val outcome = DeliveryScheduleEditor(remote, queue, "T1").placeJob(v2, a, "2026-10-21_pm", fromSlot = "2026-10-14_am")
        assertEquals(DeliveryEditOutcome.Applied(applied.schedule, emptyList()), outcome)
        assertEquals(listOf(Triple("670 - ESCHRICH", LocalDate.of(2026, 10, 21), "pm")), remote.puts)
        assertEquals(listOf("T1"), remote.putTablets)
        assertTrue(remote.posts.isEmpty())
        assertTrue(queue.edits.isEmpty())
    }

    @Test
    fun putAppliedWithoutTheJobAndWithFailuresKeepsHtScheduleWithFormattedFailure() = runBlocking {
        val failure = DeliveryScheduleFailure("2026-10-21_pm", "670", "670 - ESCHRICH", 404, "")
        val ht = DeliveryScheduleApplyResult(v2, listOf(failure))
        val remote = FakeRemote(put = DeliveryJobDateResult.Applied(ht))
        val outcome = DeliveryScheduleEditor(remote, FakeQueue(), "T1").placeJob(v2, a, "2026-10-21_pm", null)
        assertEquals(DeliveryEditOutcome.Applied(ht.schedule, listOf("Job 670 not placed: no office job for this folder")), outcome)
    }

    @Test
    fun putAppliedWithoutTheJobAndWithoutFailuresKeepsHtScheduleWithGenericText() = runBlocking {
        val ht = DeliveryScheduleApplyResult(v2, emptyList())
        val remote = FakeRemote(put = DeliveryJobDateResult.Applied(ht))
        val outcome = DeliveryScheduleEditor(remote, FakeQueue(), "T1").placeJob(v2, a, "2026-10-21_pm", null)
        assertEquals(
            DeliveryEditOutcome.Applied(ht.schedule, listOf("Job 670 not placed: Hours Tracker did not schedule it")),
            outcome
        )
    }

    @Test
    fun putAppliedMatchesPlacedJobByFolderIgnoringCase() = runBlocking {
        val lower = a.copy(folderName = "670 - eschrich")
        val remote = FakeRemote(put = DeliveryJobDateResult.Applied(DeliveryScheduleApplyResult(v2.withSlot("2026-10-21_pm", listOf(lower)), emptyList())))
        val outcome = DeliveryScheduleEditor(remote, FakeQueue(), "T1").placeJob(v2, a, "2026-10-21_pm", null)
        assertTrue(outcome is DeliveryEditOutcome.Applied)
    }

    @Test
    fun removeAppliedWhileJobStillInSlotKeepsHtSchedule() = runBlocking {
        val current = v2.withSlot("2026-10-21_pm", listOf(a, b))
        val ht = DeliveryScheduleApplyResult(current, emptyList())
        val remote = FakeRemote(put = DeliveryJobDateResult.Applied(ht))
        val outcome = DeliveryScheduleEditor(remote, FakeQueue(), "T1").removeJob(current, a, "2026-10-21_pm")
        assertEquals(DeliveryEditOutcome.Applied(ht.schedule, listOf("Job 670 was not removed: Hours Tracker kept its date")), outcome)
    }

    @Test
    fun removeAppliedWhileJobStillInSlotUsesRemovalWordingForFailures() = runBlocking {
        val current = v2.withSlot("2026-10-21_pm", listOf(a))
        val failure = DeliveryScheduleFailure("", "670", "670 - ESCHRICH", 400, "")
        val ht = DeliveryScheduleApplyResult(current, listOf(failure))
        val remote = FakeRemote(put = DeliveryJobDateResult.Applied(ht))
        val outcome = DeliveryScheduleEditor(remote, FakeQueue(), "T1").removeJob(current, a, "2026-10-21_pm")
        assertEquals(
            DeliveryEditOutcome.Applied(ht.schedule, listOf("Job 670 was not removed: the office job is closed or invalid")),
            outcome
        )
    }

    @Test
    fun formatDeliveryFailure404SaysNoOfficeJob() {
        assertEquals(
            "Job 670 not placed: no office job for this folder",
            formatDeliveryFailure(DeliveryScheduleFailure("", "670", "670 - E", 404, ""))
        )
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
    fun refusedPutWithEmptyReasonUsesTheStatusFallbackTable() = runBlocking {
        // AdminSyncClient passes through the server's own text or "": the wording lives only here.
        fun refusal(status: Int): List<String> = runBlocking {
            val remote = FakeRemote(put = DeliveryJobDateResult.Refused(status, ""))
            DeliveryScheduleEditor(remote, FakeQueue(), "T1").placeJob(v2, a, "2026-10-21_pm", null).notices
        }
        assertEquals(listOf("Job 670 not placed: that day already has 6 deliveries"), refusal(409))
        assertEquals(listOf("Job 670 not placed: no office job for this folder"), refusal(404))
        assertEquals(listOf("Job 670 not placed: the office job is closed or invalid"), refusal(400))
        assertEquals(listOf("Job 670 not placed: refused by Hours Tracker (422)"), refusal(422))
    }

    @Test
    fun refusedRemoveWithEmptyReasonUsesTheStatusFallbackToo() = runBlocking {
        val current = v2.withSlot("2026-10-21_pm", listOf(a))
        val remote = FakeRemote(put = DeliveryJobDateResult.Refused(404, ""))
        val outcome = DeliveryScheduleEditor(remote, FakeQueue(), "T1").removeJob(current, a, "2026-10-21_pm")
        assertEquals(DeliveryEditOutcome.Refused(listOf("Job 670 not placed: no office job for this folder")), outcome)
    }

    @Test
    fun placingAJobAlreadyInTheSlotIsQueuedNotAppliedAndTouchesNothing() = runBlocking {
        val current = v2.withSlot("2026-10-21_pm", listOf(a))
        val remote = FakeRemote(put = DeliveryJobDateResult.Applied(DeliveryScheduleApplyResult(v2, emptyList())))
        val queue = FakeQueue()
        val outcome = DeliveryScheduleEditor(remote, queue, "T1").placeJob(current, a.copy(folderName = "670 - eschrich"), "2026-10-21_pm", null)
        assertEquals(DeliveryEditOutcome.Queued(emptyList()), outcome)
        assertTrue(remote.puts.isEmpty() && remote.posts.isEmpty() && queue.edits.isEmpty())
    }

    // ---- A10: storage failures are not cap notices ----

    @Test
    fun blockedQueueRefusesWithASaveFailedNoticeNotACapNotice() = runBlocking {
        val blocked = FakeQueue(answer = { _, jobs -> DeliveryQueueResult(false, jobs, DeliveryQueueFailure.Blocked) })
        val outcome = DeliveryScheduleEditor(null, blocked, "T1").placeJob(v2, a, "2026-10-21_pm", null)
        assertEquals(
            DeliveryEditOutcome.Refused(listOf("Couldn't save this delivery change on this tablet. Try again.")),
            outcome
        )
        // A null reason on an unqueued result is treated as a storage failure too.
        val unknown = FakeQueue(answer = { _, jobs -> DeliveryQueueResult(false, jobs) })
        assertEquals(outcome, DeliveryScheduleEditor(null, unknown, "T1").replaceSlot("2026-10-21_pm", listOf(a)))
    }

    @Test
    fun invalidSlotAndTooOldAndAllInvalidEachGetTheirOwnNotice() = runBlocking {
        fun notices(reason: DeliveryQueueFailure, jobs: List<DeliveryJob> = listOf(a)): DeliveryEditOutcome =
            runBlocking {
                DeliveryScheduleEditor(null, FakeQueue(answer = { _, j -> DeliveryQueueResult(false, j, reason) }), "T1")
                    .replaceSlot("2026-10-21_pm", jobs)
            }
        assertEquals(
            DeliveryEditOutcome.Refused(listOf("Couldn't save this delivery change: that isn't a valid delivery slot.")),
            notices(DeliveryQueueFailure.InvalidSlot)
        )
        assertEquals(
            DeliveryEditOutcome.Refused(listOf("Couldn't save this delivery change: that day is more than 14 days in the past.")),
            notices(DeliveryQueueFailure.TooOld)
        )
        val noName = DeliveryJob("671", "", folderName = "671 - X")
        assertEquals(
            DeliveryEditOutcome.Refused(listOf("Job 671 not queued: missing job number, name or folder")),
            notices(DeliveryQueueFailure.AllJobsInvalid, listOf(noName))
        )
    }

    @Test
    fun aFailedSecondEditStillRefusesAfterTheFirstWasQueued() = runBlocking {
        val v1 = DeliverySchedule(slots = mapOf("monday_am" to DeliverySlot(listOf(a)), "tuesday_pm" to DeliverySlot()))
        val queue = FakeQueue(answer = { slot, jobs ->
            if (slot == "monday_am") DeliveryQueueResult(false, jobs, DeliveryQueueFailure.Blocked) else DeliveryQueueResult(true, emptyList())
        })
        val outcome = DeliveryScheduleEditor(null, queue, "T1").placeJob(v1, a, "tuesday_pm", "monday_am")
        assertTrue(outcome is DeliveryEditOutcome.Refused)
        assertEquals(listOf("Couldn't save this delivery change on this tablet. Try again."), outcome.notices)
        assertEquals(2, queue.edits.size)
    }

    @Test
    fun resetRefusedWhenTheQueueCannotSaveIt() = runBlocking {
        val queue = FakeQueue(resetQueued = false)
        val outcome = DeliveryScheduleEditor(null, queue, "T1").resetWeek(v2)
        assertEquals(DeliveryEditOutcome.Refused(listOf("Couldn't save the reset on this tablet. Try again.")), outcome)
        assertEquals(1, queue.resets)
    }

    @Test
    fun acceptedWithoutDocumentIsQueuedWithoutRetryOrQueueWrite() = runBlocking {
        val current = v2.withSlot("2026-10-21_pm", listOf(a))
        val remote = FakeRemote(put = DeliveryJobDateResult.AcceptedWithoutDocument)
        val queue = FakeQueue()
        val editor = DeliveryScheduleEditor(remote, queue, "T1")

        val placed = editor.placeJob(v2, a, "2026-10-21_pm", null)
        assertEquals(DeliveryEditOutcome.Queued(emptyList()), placed)

        val removed = editor.removeJob(current, a, "2026-10-21_pm")
        assertEquals(DeliveryEditOutcome.Queued(emptyList()), removed)

        assertEquals(2, remote.puts.size)
        assertTrue(remote.posts.isEmpty())
        assertTrue(queue.edits.isEmpty())
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
        val outcome = DeliveryScheduleEditor(remote, FakeQueue(), "T1").replaceSlot("2026-10-21_pm", listOf(a))
        assertEquals(listOf("Job 670 not placed: that day already has 6 deliveries"), outcome.notices)
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
    fun seventhJobInADayRefusedLocallyWithNoRemoteOrQueueCall() = runBlocking {
        val full = fullDay(am = 4, pm = 2)
        val remote = FakeRemote()
        val queue = FakeQueue()
        val outcome = DeliveryScheduleEditor(remote, queue, "T1").placeJob(full, a, "2026-10-21_pm", null)
        assertEquals(DeliveryEditOutcome.Refused(listOf("Job 670 not placed: that day already has 6 deliveries")), outcome)
        assertTrue(remote.puts.isEmpty() && remote.posts.isEmpty() && queue.edits.isEmpty())
        // The other half of the same day is just as full.
        val amOutcome = DeliveryScheduleEditor(remote, queue, "T1").placeJob(full, a, "2026-10-21_am", null)
        assertTrue(amOutcome is DeliveryEditOutcome.Refused)
    }

    @Test
    fun sixthJobInADayIsAllowedEvenWhenOneHalfHoldsFive() = runBlocking {
        val five = fullDay(am = 5, pm = 0)
        val remote = FakeRemote(put = DeliveryJobDateResult.Unavailable)
        val outcome = DeliveryScheduleEditor(remote, FakeQueue(), "T1").placeJob(five, a, "2026-10-21_am", null)
        assertTrue(outcome is DeliveryEditOutcome.Queued)
    }

    @Test
    fun movingAmToPmInsideAFullDayIsAllowed() = runBlocking {
        val full = fullDay(am = 4, pm = 2)
        val mover = full.slotByKey("2026-10-21_am").jobs.first()
        val moved = full
            .withSlot("2026-10-21_am", full.slotByKey("2026-10-21_am").jobs.drop(1))
            .withSlot("2026-10-21_pm", full.slotByKey("2026-10-21_pm").jobs + mover)
        val applied = DeliveryScheduleApplyResult(moved, emptyList())
        val remote = FakeRemote(put = DeliveryJobDateResult.Applied(applied))
        val outcome = DeliveryScheduleEditor(remote, FakeQueue(), "T1").placeJob(full, mover, "2026-10-21_pm", "2026-10-21_am")
        assertEquals(DeliveryEditOutcome.Applied(moved, emptyList()), outcome)
        assertEquals(listOf(Triple(mover.folderName, LocalDate.of(2026, 10, 21), "pm")), remote.puts)
    }

    @Test
    fun weekdayDayCapCountsBothHalves() = runBlocking {
        val jobs = (1..6).map { DeliveryJob("8$it", "J$it", folderName = "8$it - J$it") }
        val v1 = DeliverySchedule(slots = mapOf("monday_am" to DeliverySlot(jobs.take(3)), "monday_pm" to DeliverySlot(jobs.drop(3))))
        val queue = FakeQueue()
        val outcome = DeliveryScheduleEditor(null, queue, "T1").placeJob(v1, a, "monday_am", null)
        assertEquals(listOf("Job 670 not placed: that day already has 6 deliveries"), outcome.notices)
        assertTrue(queue.edits.isEmpty())
    }

    @Test
    fun replaceSlotWithFiveJobsIsNotClipped() = runBlocking {
        val five = (1..5).map { DeliveryJob("9$it", "J$it", folderName = "9$it - J$it") }
        val remote = FakeRemote(post = null)
        val queue = FakeQueue()
        DeliveryScheduleEditor(remote, queue, "T1").replaceSlot("2026-10-21_am", five)
        assertEquals(five, remote.posts.single().slotEdits.single().jobs)
        assertEquals(listOf("2026-10-21_am" to five), queue.edits)
    }

    private fun fullDay(am: Int, pm: Int): DeliverySchedule {
        fun job(n: Int) = DeliveryJob("5$n", "J$n", folderName = "5$n - J$n")
        return v2
            .withSlot("2026-10-21_am", (0 until am).map(::job))
            .withSlot("2026-10-21_pm", (am until am + pm).map(::job))
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
        assertEquals(listOf("T1"), onlineRemote.putTablets)

        val queue = FakeQueue()
        DeliveryScheduleEditor(null, queue, "T1").removeJob(current, a, "2026-10-21_pm")
        assertEquals(listOf("2026-10-21_pm" to listOf(b)), queue.edits)
    }

    // ---- I2: while this tablet's request file is pending, never write around it ----

    @Test
    fun pendingRequestQueuesPlaceWithoutPutOrPostAndKeepsOverlaidJobs() = runBlocking {
        // Our queued file already dated `a` into 10/21 PM; HT will apply it as a whole-slot edit.
        val pending = DeliveryScheduleEditRequest("T1", "x", slotEdits = listOf(DeliveryScheduleSlotEdit("2026-10-21_pm", listOf(a))))
        val current = overlayPendingDeliveryRequest(v2, pending)
        val remote = FakeRemote(
            put = DeliveryJobDateResult.Applied(DeliveryScheduleApplyResult(v2, emptyList())),
            post = DeliveryScheduleApplyResult(v2, emptyList())
        )
        val queue = FakeQueue()
        val editor = DeliveryScheduleEditor(remote, queue, "T1", hasPendingRequest = { true })

        val outcome = editor.placeJob(current, b, "2026-10-21_pm", null)

        assertTrue(outcome is DeliveryEditOutcome.Queued)
        assertTrue(remote.puts.isEmpty())
        assertTrue(remote.posts.isEmpty())
        assertEquals(listOf("2026-10-21_pm" to listOf(a, b)), queue.edits)
    }

    @Test
    fun pendingRequestQueuesRemoveReplaceAndReset() = runBlocking {
        val current = v2.withSlot("2026-10-21_pm", listOf(a, b))
        val remote = FakeRemote(
            put = DeliveryJobDateResult.Applied(DeliveryScheduleApplyResult(v2, emptyList())),
            post = DeliveryScheduleApplyResult(v2, emptyList())
        )
        val queue = FakeQueue()
        val editor = DeliveryScheduleEditor(remote, queue, "T1", hasPendingRequest = { true })

        assertTrue(editor.removeJob(current, a, "2026-10-21_pm") is DeliveryEditOutcome.Queued)
        assertTrue(editor.replaceSlot("2026-10-21_am", listOf(a)) is DeliveryEditOutcome.Queued)
        assertTrue(editor.resetWeek(current) is DeliveryEditOutcome.Queued)

        assertTrue(remote.puts.isEmpty())
        assertTrue(remote.posts.isEmpty())
        assertEquals(listOf("2026-10-21_pm" to listOf(b), "2026-10-21_am" to listOf(a)), queue.edits)
        assertEquals(1, queue.resets)
    }

    @Test
    fun noPendingRequestKeepsOnlinePutPath() = runBlocking {
        val applied = DeliveryScheduleApplyResult(v2.withSlot("2026-10-21_pm", listOf(a)), emptyList())
        val remote = FakeRemote(put = DeliveryJobDateResult.Applied(applied))
        val queue = FakeQueue()
        var asked = 0
        val editor = DeliveryScheduleEditor(remote, queue, "T1", hasPendingRequest = { asked++; false })

        val outcome = editor.placeJob(v2, a, "2026-10-21_pm", null)

        assertEquals(DeliveryEditOutcome.Applied(applied.schedule, emptyList()), outcome)
        assertEquals(1, remote.puts.size)
        assertTrue(queue.edits.isEmpty())
        assertTrue(asked > 0)
    }

    // ---- I3: v1 (undated) Hours Tracker caps each half-day at 3 ----

    @Test
    fun v1FourthJobInAHalfDayRefusedLocally() = runBlocking {
        val jobs = (1..3).map { DeliveryJob("8$it", "J$it", folderName = "8$it - J$it") }
        val v1 = DeliverySchedule(slots = mapOf("monday_am" to DeliverySlot(jobs)))
        val remote = FakeRemote(post = DeliveryScheduleApplyResult(v1, emptyList()))
        val queue = FakeQueue()
        val outcome = DeliveryScheduleEditor(remote, queue, "T1").placeJob(v1, a, "monday_am", null)
        assertEquals(
            DeliveryEditOutcome.Refused(listOf("Job 670 not placed: that half-day already has 3 deliveries")),
            outcome
        )
        assertTrue(remote.puts.isEmpty() && remote.posts.isEmpty() && queue.edits.isEmpty())
        // The other half of the same v1 day still has room.
        val pm = DeliveryScheduleEditor(FakeRemote(post = null), FakeQueue(), "T1").placeJob(v1, a, "monday_pm", null)
        assertTrue(pm is DeliveryEditOutcome.Queued)
    }

    @Test
    fun datedScheduleAllowsAFourthJobInOneHalfDay() = runBlocking {
        val three = fullDay(am = 3, pm = 0)
        val outcome = DeliveryScheduleEditor(null, FakeQueue(), "T1").placeJob(three, a, "2026-10-21_am", null)
        assertTrue(outcome is DeliveryEditOutcome.Queued)
    }

    // ---- M2: queue-drop notices name the real limit ----

    @Test
    fun queueDropNoticesNameTheDayOrHalfDayLimit() = runBlocking {
        val dropAll = FakeQueue(drop = { _, jobs -> jobs.takeLast(1) })
        DeliveryScheduleEditor(null, dropAll, "T1").replaceSlot("2026-10-21_am", listOf(b, a)).let {
            assertEquals(listOf("Job 670 not queued: that day already has 6 deliveries"), it.notices)
        }
        DeliveryScheduleEditor(null, FakeQueue(drop = { _, jobs -> jobs.takeLast(1) }), "T1")
            .replaceSlot("monday_am", listOf(b, a)).let {
                assertEquals(listOf("Job 670 not queued: that half-day already has 3 deliveries"), it.notices)
            }
        // An invalid job is reported as invalid, not as over the limit.
        val noName = DeliveryJob("671", "", folderName = "671 - X")
        DeliveryScheduleEditor(null, FakeQueue(drop = { _, jobs -> jobs.filter { it.description.isBlank() } }), "T1")
            .replaceSlot("2026-10-21_am", listOf(noName)).let {
                assertEquals(listOf("Job 671 not queued: missing job number, name or folder"), it.notices)
            }
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
