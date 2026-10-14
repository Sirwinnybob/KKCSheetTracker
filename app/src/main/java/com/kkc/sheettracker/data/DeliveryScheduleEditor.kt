package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliveryScheduleFailure
import com.kkc.sheettracker.data.models.parseDatedSlotKey
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** The HT routes the editor needs; [AdminSyncClient] in production. */
interface DeliveryScheduleRemote {
    suspend fun applyEdits(request: DeliveryScheduleEditRequest): DeliveryScheduleApplyResult?
    suspend fun setJobDate(folderName: String, date: LocalDate?, window: String?, tabletId: String): DeliveryJobDateResult
}

fun AdminSyncClient.asDeliveryRemote(): DeliveryScheduleRemote = object : DeliveryScheduleRemote {
    override suspend fun applyEdits(request: DeliveryScheduleEditRequest) = applyDeliveryScheduleEdits(request)
    override suspend fun setJobDate(folderName: String, date: LocalDate?, window: String?, tabletId: String) =
        setDeliveryJobDate(folderName, date, window, tabletId)
}

sealed interface DeliveryEditOutcome {
    val notices: List<String>
    /** HT confirmed; [schedule] is canonical and should replace the UI's copy. */
    data class Applied(val schedule: DeliverySchedule, override val notices: List<String>) : DeliveryEditOutcome
    /** Written to this tablet's request file; show as pending until a fresh read confirms it. */
    data class Queued(override val notices: List<String>) : DeliveryEditOutcome
    /** Nothing changed; tell the user why. */
    data class Refused(override val notices: List<String>) : DeliveryEditOutcome
}

fun formatDeliveryFailure(f: DeliveryScheduleFailure, removal: Boolean = false): String {
    val why = f.reason.ifBlank {
        when (f.status) {
            409 -> "that day already has $MAX_DELIVERY_JOBS_PER_DAY deliveries"
            404 -> "no office job for this folder"
            400 -> "the office job is closed or invalid"
            else -> "refused by Hours Tracker (${f.status})"
        }
    }
    return "Job ${f.jobNumber.ifBlank { f.folderName }} ${if (removal) "was not removed" else "not placed"}: $why"
}

/**
 * Turns a delivery-dialog gesture into the safest HT write available:
 * 1. v2 server + dated slot + job folder: per-job `PUT` (changes one job; can't clobber others).
 * 2. Otherwise (or PUT unavailable): whole-slot edit via `POST /api/admin-sync/delivery-schedule`.
 * 3. POST failed: this tablet's request file (HT polls every 15 s).
 *
 * [current] must be the freshest schedule with this tablet's pending request overlaid.
 *
 * While this tablet's request file is still waiting for HT ([hasPendingRequest]), every edit is
 * queued into that file instead of going online: HT applies the file later as whole-slot edits, and
 * an online placement made in the meantime would be undone by the older queued slot contents.
 */
class DeliveryScheduleEditor(
    private val remote: DeliveryScheduleRemote?,
    private val queue: DeliveryScheduleQueue,
    private val tabletId: String,
    private val clock: Clock = Clock.systemUTC(),
    private val hasPendingRequest: suspend () -> Boolean = { false }
) {
    suspend fun placeJob(current: DeliverySchedule, job: DeliveryJob, toSlot: String, fromSlot: String?): DeliveryEditOutcome {
        val dest = current.slotByKey(toSlot).jobs
        if (dest.any { it.sameJob(job) }) return DeliveryEditOutcome.Applied(current, emptyList())
        // The cap is per DAY, not per half-day. The moving job doesn't count against its own
        // destination day, so an AM<->PM move inside a full day stays allowed.
        val others = deliveryDayJobs(current, toSlot).count { !it.sameJob(job) }
        if (others >= MAX_DELIVERY_JOBS_PER_DAY) {
            return DeliveryEditOutcome.Refused(
                listOf("Job ${job.jobNumber} not placed: that day already has $MAX_DELIVERY_JOBS_PER_DAY deliveries")
            )
        }
        // A v1 (undated) HT also rejects a 4th job in one half-day, quarantining the whole request.
        if (!current.isDated && dest.size >= MAX_DELIVERY_JOBS_PER_LEGACY_SLOT) {
            return DeliveryEditOutcome.Refused(
                listOf("Job ${job.jobNumber} not placed: that half-day already has $MAX_DELIVERY_JOBS_PER_LEGACY_SLOT deliveries")
            )
        }
        val dated = parseDatedSlotKey(toSlot)
        if (dated != null && job.folderName.isBlank()) {
            return DeliveryEditOutcome.Refused(listOf("Job ${job.jobNumber} not placed: it has no Ready Jobs folder"))
        }
        val queueOnly = hasPendingRequest()
        if (!queueOnly && dated != null && current.isDated && remote != null) {
            when (val r = remote.setJobDate(job.folderName, dated.date, dated.period, tabletId)) {
                is DeliveryJobDateResult.Applied -> {
                    // HT can answer 200 without placing the job (e.g. its folder is no longer a
                    // real office job); the reply's failures say why. The job did not move, but HT's
                    // regenerated schedule is canonical, so hand it back with the notices.
                    if (r.result.schedule.slotByKey(toSlot).jobs.none { it.sameJob(job) }) {
                        return DeliveryEditOutcome.Applied(
                            r.result.schedule,
                            r.result.failures.map(::formatDeliveryFailure)
                                .ifEmpty { listOf("Job ${job.jobNumber} not placed: Hours Tracker did not schedule it") }
                        )
                    }
                    return applied(r.result)
                }
                is DeliveryJobDateResult.Refused -> return refused(job, r)
                // HT applied it but sent no usable document: don't retry, the live/file refresh shows it.
                DeliveryJobDateResult.AcceptedWithoutDocument -> return DeliveryEditOutcome.Queued(emptyList())
                DeliveryJobDateResult.Unavailable -> Unit
            }
        }
        val edits = buildList {
            add(DeliveryScheduleSlotEdit(toSlot, dest + job))
            // v1 servers keep copies per slot, so a move must also empty the source slot. v2
            // jobs have one date: placing moves them, and resending the source could clobber.
            if (!current.isDated && fromSlot != null && fromSlot != toSlot) {
                add(DeliveryScheduleSlotEdit(fromSlot, current.slotByKey(fromSlot).jobs.filterNot { it.sameJob(job) }))
            }
        }
        return sendSlotEdits(edits, queueOnly)
    }

    suspend fun removeJob(current: DeliverySchedule, job: DeliveryJob, fromSlot: String): DeliveryEditOutcome {
        val queueOnly = hasPendingRequest()
        if (!queueOnly && parseDatedSlotKey(fromSlot) != null && current.isDated && remote != null && job.folderName.isNotBlank()) {
            when (val r = remote.setJobDate(job.folderName, null, null, tabletId)) {
                is DeliveryJobDateResult.Applied -> {
                    if (r.result.schedule.slotByKey(fromSlot).jobs.any { it.sameJob(job) }) {
                        return DeliveryEditOutcome.Applied(
                            r.result.schedule,
                            r.result.failures.map { formatDeliveryFailure(it, removal = true) }
                                .ifEmpty { listOf("Job ${job.jobNumber} was not removed: Hours Tracker kept its date") }
                        )
                    }
                    return applied(r.result)
                }
                is DeliveryJobDateResult.Refused -> return refused(job, r)
                DeliveryJobDateResult.AcceptedWithoutDocument -> return DeliveryEditOutcome.Queued(emptyList())
                DeliveryJobDateResult.Unavailable -> Unit
            }
        }
        return sendSlotEdits(
            listOf(DeliveryScheduleSlotEdit(fromSlot, current.slotByKey(fromSlot).jobs.filterNot { it.sameJob(job) })),
            queueOnly
        )
    }

    /** Field edits inside one slot (job card edit). Always a whole-slot edit. */
    suspend fun replaceSlot(current: DeliverySchedule, slotKey: String, jobs: List<DeliveryJob>): DeliveryEditOutcome =
        sendSlotEdits(listOf(DeliveryScheduleSlotEdit(slotKey, jobs)), hasPendingRequest())

    /** Clears every job dated in the mirrored Mon-Fri week (`weekOf`..`weekOf+4`). */
    suspend fun resetWeek(current: DeliverySchedule): DeliveryEditOutcome {
        if (!hasPendingRequest()) {
            val request = DeliveryScheduleEditRequest(tabletId, Instant.now(clock).toString(), resetAll = true)
            remote?.applyEdits(request)?.let { return applied(it) }
        }
        queue.queueReset(tabletId, current.weekOf)
        return DeliveryEditOutcome.Queued(listOf("Reset queued; it applies when Hours Tracker picks it up."))
    }

    private suspend fun sendSlotEdits(edits: List<DeliveryScheduleSlotEdit>, queueOnly: Boolean): DeliveryEditOutcome {
        if (!queueOnly) {
            val request = DeliveryScheduleEditRequest(tabletId, Instant.now(clock).toString(), slotEdits = edits)
            remote?.applyEdits(request)?.let { return applied(it) }
        }
        val notices = mutableListOf<String>()
        edits.forEach { edit ->
            val dated = parseDatedSlotKey(edit.slot.trim().lowercase()) != null
            queue.queueSlotEdit(edit.slot, edit.jobs, tabletId).droppedJobs.forEach { dropped ->
                // Mirrors DeliveryScheduleRequestStore's validity rule; anything else was over the cap.
                val invalid = dropped.jobNumber.isBlank() || dropped.description.isBlank() ||
                    (dated && dropped.folderName.isBlank())
                val why = when {
                    invalid -> "missing job number, name or folder"
                    dated -> "that day already has $MAX_DELIVERY_JOBS_PER_DAY deliveries"
                    else -> "that half-day already has $MAX_DELIVERY_JOBS_PER_LEGACY_SLOT deliveries"
                }
                notices += "Job ${dropped.jobNumber.ifBlank { "(no number)" }} not queued: $why"
            }
        }
        return DeliveryEditOutcome.Queued(notices)
    }

    private fun applied(result: DeliveryScheduleApplyResult) =
        DeliveryEditOutcome.Applied(result.schedule, result.failures.map(::formatDeliveryFailure))

    // FastAPI returns 422 `detail` as a JSON list/object, so the reason can be raw JSON text.
    // Blank it so formatDeliveryFailure falls back to its status-based text.
    private fun refused(job: DeliveryJob, r: DeliveryJobDateResult.Refused): DeliveryEditOutcome.Refused {
        val reason = r.reason.takeUnless { it.trimStart().startsWith("[") || it.trimStart().startsWith("{") }.orEmpty()
        return DeliveryEditOutcome.Refused(
            listOf(formatDeliveryFailure(DeliveryScheduleFailure("", job.jobNumber, job.folderName, r.status, reason)))
        )
    }
}

private fun DeliveryJob.sameJob(other: DeliveryJob): Boolean =
    if (folderName.isNotBlank() && other.folderName.isNotBlank()) folderName.equals(other.folderName, ignoreCase = true)
    else jobNumber == other.jobNumber
