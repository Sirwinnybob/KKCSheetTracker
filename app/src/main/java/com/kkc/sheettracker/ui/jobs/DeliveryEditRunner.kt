package com.kkc.sheettracker.ui.jobs

import android.util.Log
import com.kkc.sheettracker.data.AdminSyncClient
import com.kkc.sheettracker.data.DeliveryEditOutcome
import com.kkc.sheettracker.data.DeliveryJobDateResult
import com.kkc.sheettracker.data.DeliveryScheduleApplyResult
import com.kkc.sheettracker.data.DeliveryScheduleEditRequest
import com.kkc.sheettracker.data.DeliveryScheduleEditor
import com.kkc.sheettracker.data.DeliveryScheduleRemote
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.overlayPendingDeliveryRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.coroutines.CoroutineContext

internal const val DELIVERY_EDIT_FAILED_NOTICE = "Couldn't save this delivery change on this tablet. Try again."

/** How long the dialog keeps showing a consumed request's result while the live schedule catches up. */
internal const val DELIVERY_OVERLAY_HOLD_MILLIS = 30_000L

/** What the dialog needs after one edit: new notices, whether to revert its optimistic change, and our pending request. */
internal data class DeliveryEditResult(
    val notices: List<String>,
    val revert: Boolean,
    val pending: DeliveryScheduleEditRequest?
)

/**
 * Runs delivery-dialog edits outside any composition. Offline an edit can wait ~10 s (PUT + POST
 * timeouts) before it is queued, so it must not die when the Jobs route leaves: [scope] is
 * process-lifetime and [mutex] is shared by every screen instance so edits stay serialized.
 *
 * Inside the lock each edit is built from [run]'s `currentSchedule()` (the store's live value,
 * which a previous edit's `onApplied` has already updated) plus this tablet's pending request.
 * Any failure other than cancellation becomes a notice instead of crashing the app.
 *
 * The dialog reverts its optimistic local state whenever [DeliveryEditResult.revert] arrives, which
 * would also wipe a LATER edit still waiting in the queue. So a revert is held back until the last
 * in-flight edit has finished ([inFlight] reaches 0) and delivered once, with that final result.
 */
internal class DeliveryEditRunner(
    private val scope: CoroutineScope,
    private val mutex: Mutex,
    private val io: CoroutineContext = Dispatchers.IO,
    private val log: (String, Throwable) -> Unit = { message, t -> Log.w(TAG, message, t) }
) {
    private val stateLock = Any()
    private val inFlightCount = MutableStateFlow(0)
    private var revertDeferred = false

    /** Edits submitted and not yet finished (running or waiting for the lock). */
    val inFlight: StateFlow<Int> = inFlightCount.asStateFlow()

    fun run(
        editor: () -> DeliveryScheduleEditor,
        currentSchedule: () -> DeliverySchedule,
        readPending: () -> DeliveryScheduleEditRequest?,
        onApplied: (DeliverySchedule) -> Unit,
        onResult: (DeliveryEditResult) -> Unit,
        edit: suspend (DeliveryScheduleEditor, DeliverySchedule) -> DeliveryEditOutcome
    ): Job {
        synchronized(stateLock) { inFlightCount.value += 1 }
        var counted = true
        fun release(wantsRevert: Boolean): Boolean = synchronized(stateLock) {
            if (!counted) return@synchronized false
            counted = false
            val remaining = inFlightCount.value - 1
            inFlightCount.value = remaining
            if (wantsRevert) revertDeferred = true
            val now = revertDeferred && remaining == 0
            if (now) revertDeferred = false
            now
        }
        val job = scope.launch {
            mutex.withLock {
                val outcome: DeliveryEditOutcome? = try {
                    withContext(io) {
                        val current = overlayPendingDeliveryRequest(currentSchedule(), readPending())
                        edit(editor(), current)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Delivery edit failed", e)
                    null
                }
                if (outcome is DeliveryEditOutcome.Applied) onApplied(outcome.schedule)
                val notices = outcome?.notices ?: listOf(DELIVERY_EDIT_FAILED_NOTICE)
                val pending = try {
                    withContext(io) { readPending() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Could not re-read the pending delivery request", e)
                    null
                }
                val wantsRevert = outcome == null || outcome is DeliveryEditOutcome.Refused || notices.isNotEmpty()
                onResult(DeliveryEditResult(notices = notices, revert = release(wantsRevert), pending = pending))
            }
        }
        // Cancelled before it ever ran (or while waiting for the lock): still stop counting it.
        // A revert already held back stays held for the next finishing edit.
        job.invokeOnCompletion { release(wantsRevert = false) }
        return job
    }

    private companion object {
        const val TAG = "DeliveryEditRunner"
    }
}

/**
 * The request file this tablet queued can vanish (HT applied and deleted it) a moment before the
 * live schedule shows the result. [baseSchedule] is the schedule on screen when the file vanished;
 * the overlay is kept for it until the schedule changes or [DELIVERY_OVERLAY_HOLD_MILLIS] passes.
 */
internal data class HeldDeliveryRequest(
    val request: DeliveryScheduleEditRequest,
    val baseSchedule: DeliverySchedule
)

/** The hold to keep after the pending request changed from [previous] to [new] while [schedule] is shown. */
internal fun nextHeldDeliveryRequest(
    previous: DeliveryScheduleEditRequest?,
    new: DeliveryScheduleEditRequest?,
    held: HeldDeliveryRequest?,
    schedule: DeliverySchedule
): HeldDeliveryRequest? = when {
    new != null -> null
    previous != null -> HeldDeliveryRequest(previous, schedule)
    else -> held
}

/** The request to overlay: the live one, else a still-valid hold (same schedule as when the file vanished). */
internal fun effectiveDeliveryOverlayRequest(
    pending: DeliveryScheduleEditRequest?,
    held: HeldDeliveryRequest?,
    schedule: DeliverySchedule
): DeliveryScheduleEditRequest? =
    pending ?: held?.takeIf { it.baseSchedule == schedule }?.request

/**
 * A [DeliveryScheduleRemote] that resolves the Hours Tracker client on EVERY call, so an edit made
 * before the (async) server-URL read finished still goes online instead of being queued. A null
 * client means no URL is configured: `applyEdits` returns null and `setJobDate` is Unavailable,
 * which the editor turns into a queued request.
 */
internal fun lazyDeliveryRemote(client: suspend () -> AdminSyncClient?): DeliveryScheduleRemote =
    object : DeliveryScheduleRemote {
        override suspend fun applyEdits(request: DeliveryScheduleEditRequest): DeliveryScheduleApplyResult? =
            client()?.applyDeliveryScheduleEdits(request)

        override suspend fun setJobDate(
            folderName: String,
            date: LocalDate?,
            window: String?,
            tabletId: String
        ): DeliveryJobDateResult =
            client()?.setDeliveryJobDate(folderName, date, window, tabletId) ?: DeliveryJobDateResult.Unavailable
    }
