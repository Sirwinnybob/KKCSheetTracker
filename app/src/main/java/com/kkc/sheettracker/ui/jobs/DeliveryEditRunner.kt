package com.kkc.sheettracker.ui.jobs

import android.util.Log
import com.kkc.sheettracker.data.DeliveryEditOutcome
import com.kkc.sheettracker.data.DeliveryScheduleEditRequest
import com.kkc.sheettracker.data.DeliveryScheduleEditor
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.overlayPendingDeliveryRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

internal const val DELIVERY_EDIT_FAILED_NOTICE = "Couldn't save this delivery change on this tablet. Try again."

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
 */
internal class DeliveryEditRunner(
    private val scope: CoroutineScope,
    private val mutex: Mutex,
    private val io: CoroutineContext = Dispatchers.IO,
    private val log: (String, Throwable) -> Unit = { message, t -> Log.w(TAG, message, t) }
) {
    fun run(
        editor: () -> DeliveryScheduleEditor,
        currentSchedule: () -> DeliverySchedule,
        readPending: () -> DeliveryScheduleEditRequest?,
        onApplied: (DeliverySchedule) -> Unit,
        onResult: (DeliveryEditResult) -> Unit,
        edit: suspend (DeliveryScheduleEditor, DeliverySchedule) -> DeliveryEditOutcome
    ): Job = scope.launch {
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
            onResult(
                DeliveryEditResult(
                    notices = notices,
                    revert = outcome == null || outcome is DeliveryEditOutcome.Refused || notices.isNotEmpty(),
                    pending = pending
                )
            )
        }
    }

    private companion object {
        const val TAG = "DeliveryEditRunner"
    }
}
