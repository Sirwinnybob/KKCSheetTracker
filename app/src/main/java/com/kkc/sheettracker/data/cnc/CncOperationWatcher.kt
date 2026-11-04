package com.kkc.sheettracker.data.cnc

import com.kkc.sheettracker.data.mixfiles.MaterialKey
import com.kkc.sheettracker.data.mixfiles.MixFilesCatalog
import com.kkc.sheettracker.data.mixfiles.MixFilesResult
import com.kkc.sheettracker.data.mixfiles.SecondPassMutation
import com.kkc.sheettracker.data.mixfiles.SecondPassOutcome
import com.kkc.sheettracker.data.mixfiles.SecondPassTracker
import com.kkc.sheettracker.data.mixfiles.secondPassFilesSucceeded
import com.kkc.sheettracker.data.mixservice.MixOperationClientException
import com.kkc.sheettracker.data.mixservice.MixServiceOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Status read of one async mix-service operation on a bound endpoint. Throws when no readable reply. */
fun interface MixOperationReader {
    suspend fun getOperation(operationId: String): MixServiceOperation
}

enum class OperationNoticeTone { SUCCESS, FAILURE, ATTENTION }

/** An app-level outcome of a CNC move or mix change, shown on whatever screen is open. */
data class OperationNotice(
    val id: String,
    val tone: OperationNoticeTone,
    /** "Blue SCM", or "Red SCM → Blue SCM" for a move. */
    val machine: String,
    val job: String,
    val material: String,
    val outcome: String,
    /** Stays until the operator dismisses it; non-sticky notices may time out. */
    val sticky: Boolean,
)

/**
 * App-scoped watcher for every CNC move and mix/second-pass mutation the tablet submits. It is owned by
 * the application, never a screen: a submission runs in [scope], and each pending operation has exactly
 * one poller on its bound endpoint snapshot, with backoff, until a terminal outcome. Pending work resumes
 * from the durable journals after a restart. Nothing here ever re-POSTs a write.
 */
class CncOperationWatcher(
    val transfers: MaterialTransferStore,
    val mixOperations: MixOperationStore,
    private val scope: CoroutineScope,
    private val operationReader: (endpoint: String) -> MixOperationReader,
    private val refreshCatalog: suspend (machineId: String, job: String, material: String) -> Unit,
    private val publishCatalog: (machineId: String, job: String, material: String, catalog: MixFilesCatalog) -> Unit,
    private val machineName: (String) -> String = { id -> CncMachines.all.firstOrNull { it.id == id }?.displayName ?: id },
    private val newRequestId: () -> String = { "tablet-mix-${UUID.randomUUID()}" },
    private val minDelayMs: Long = 2_000L,
    private val maxDelayMs: Long = 10_000L,
) {
    private val lock = Any()
    private val pollers = mutableMapOf<String, Job>()
    private val finishedPollers = mutableSetOf<String>()
    private val wakeups = mutableMapOf<String, Channel<Unit>>()
    /** Second-pass step records whose outcome a running plan reports itself. */
    private val liveRequests = mutableSetOf<String>()
    private val viewed = MutableStateFlow<Map<MaterialKey, Int>>(emptyMap())
    private var started = false

    val notices: StateFlow<List<OperationNotice>> = combine(transfers.records, mixOperations.records) { moves, mixes ->
        moves.sortedBy { it.updatedAtMs }.mapNotNull(::moveNotice) + mixes.sortedBy { it.updatedAtMs }.mapNotNull(::mixNotice)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Starts (once) the pollers for every pending record, including those restored from disk. */
    fun start() {
        synchronized(lock) { if (started) return; started = true }
        scope.launch {
            combine(transfers.records, mixOperations.records) { moves, mixes ->
                moves.filter { it.state == TransferState.SUBMITTED && it.operationId != null }.map { PollTarget(true, it.requestId, it.operationId!!) } +
                    mixes.filter { it.pollable }.map { PollTarget(false, it.requestId, it.operationId!!) }
            }.collect { targets -> targets.forEach(::ensurePolling) }
        }
    }

    // --- Moves ------------------------------------------------------------------------------------

    /** Submits in the app scope: leaving the screen mid-POST never drops the reply or strands the record. */
    suspend fun startMove(source: CncMachine, destination: CncMachine, job: String, material: String,
        preview: TransferPreview, expectedSourceRevision: String, policy: PunloadPolicy): TransferStart =
        scope.async { transfers.start(source, destination, job, material, preview, expectedSourceRevision, policy) }.await()

    /** Explicit operator resend of an uncertain move (same request and preview IDs), in the app scope. */
    suspend fun resendMove(requestId: String): MaterialTransferRecord? = scope.async { transfers.resendUncertain(requestId) }.await()

    /** Operator "check now": the operation's poller reads immediately and restarts its backoff. */
    fun checkNow(requestId: String) {
        synchronized(lock) { wakeups[requestId] }?.trySend(Unit)
    }

    // --- Mix files (synchronous replies) ------------------------------------------------------------

    /**
     * Runs one mix-files mutation in the app scope with a write-ahead journal record. The reply is the
     * outcome; a lost reply is recorded as unconfirmed and the machine's catalog is re-read. Never re-POSTed.
     */
    suspend fun submitMixWrite(machine: CncMachine, job: String, material: String, label: String,
        submit: suspend () -> MixFilesResult<MixFilesCatalog>): MixFilesResult<MixFilesCatalog> = scope.async {
        val requestId = newRequestId()
        mixOperations.begin(requestId, MixOperationKind.MIX_WRITE, machine.id, machine.baseUrl, job, material, label)
        val result = try { submit() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { MixFilesResult.Offline }
        val (state, message) = when (result) {
            is MixFilesResult.Ok -> MixOperationState.COMPLETED to null
            is MixFilesResult.Rejected -> MixOperationState.FAILED to result.error.message.ifBlank { result.error.code }
            MixFilesResult.ServiceOutdated -> MixOperationState.FAILED to "The CNC service needs an update."
            MixFilesResult.Offline -> MixOperationState.UNCERTAIN to MixOperationStore.UNCONFIRMED
        }
        val key = MaterialKey(machine.id, job, material)
        val record = mixOperations.update(requestId) {
            it.copy(state = state, message = message, acknowledged = autoAcknowledged(key, state))
        }
        if (result is MixFilesResult.Ok) publishCatalog(machine.id, job, material, result.value) else refresh(machine.id, job, material)
        if (record?.acknowledged == true) mixOperations.acknowledge(requestId)
        result
    }.await()

    // --- Second pass (async operations) -------------------------------------------------------------

    /**
     * Runs a whole second-pass plan in the app scope. Steps are journaled and polled by this watcher;
     * the plan's outcome becomes one notice. A step left behind by process death is reported on its own.
     */
    suspend fun runSecondPass(machine: CncMachine, job: String, material: String,
        plan: suspend (SecondPassTracker) -> SecondPassOutcome): SecondPassOutcome = scope.async {
        val tracker = PlanTracker(machine, job, material)
        val outcome = try { plan(tracker) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { SecondPassOutcome.Failed(null, failure.message ?: UNCONFIRMED_SECOND_PASS, tracker.requestIds.isNotEmpty()) }
        withContext(NonCancellable) { finishPlan(machine, job, material, tracker.requestIds, outcome) }
        outcome
    }.await()

    private suspend fun finishPlan(machine: CncMachine, job: String, material: String, steps: List<String>, outcome: SecondPassOutcome) {
        synchronized(lock) { liveRequests.removeAll(steps.toSet()) }
        steps.forEach { mixOperations.acknowledge(it) }
        if (steps.isEmpty() && outcome == SecondPassOutcome.Done) return // Nothing was sent.
        val (state, message) = when (outcome) {
            SecondPassOutcome.Done -> MixOperationState.COMPLETED to null
            is SecondPassOutcome.Failed ->
                if (!outcome.partial && outcome.mutation == SecondPassMutation.REFUSED_BEFORE_MUTATION) MixOperationState.FAILED to outcome.message
                else MixOperationState.NEEDS_ATTENTION to outcome.message
        }
        val requestId = "plan-${steps.firstOrNull() ?: newRequestId()}"
        mixOperations.begin(requestId, MixOperationKind.SECOND_PASS_EDIT, machine.id, machine.baseUrl, job, material, SECOND_PASS_LABEL)
        val record = mixOperations.update(requestId) {
            it.copy(state = state, message = message, acknowledged = autoAcknowledged(MaterialKey(machine.id, job, material), state))
        }
        refresh(machine.id, job, material)
        if (record?.acknowledged == true) mixOperations.acknowledge(requestId)
    }

    private inner class PlanTracker(val machine: CncMachine, val job: String, val material: String) : SecondPassTracker {
        val requestIds = mutableListOf<String>()

        override suspend fun submit(kind: MixOperationKind, requestId: String, step: Int, steps: Int,
            post: suspend () -> MixServiceOperation): MixServiceOperation {
            synchronized(lock) { liveRequests += requestId }
            requestIds += requestId
            mixOperations.begin(requestId, kind, machine.id, machine.baseUrl, job, material, SECOND_PASS_LABEL, step, steps)
            val operation = try { post() } catch (failure: Throwable) {
                withContext(NonCancellable) { mixOperations.update(requestId) {
                    it.copy(state = MixOperationState.UNCERTAIN, message = failure.message ?: MixOperationStore.UNCONFIRMED, acknowledged = true)
                } }
                throw failure
            }
            mixOperations.update(requestId) { current ->
                when {
                    operation.isTerminal -> applyOperation(current, operation)
                    operation.id.isBlank() -> current.copy(state = MixOperationState.UNCERTAIN, message = "The CNC returned no operation ID", acknowledged = true)
                    else -> current.copy(state = MixOperationState.SUBMITTED, operationId = operation.id, stage = operation.stage, operation = operation)
                }
            }
            return operation
        }

        override suspend fun awaitTerminal(requestId: String, onProgress: (String) -> Unit): MixServiceOperation {
            var stage: String? = null
            val record = mixOperations.records.map { list -> list.firstOrNull { it.requestId == requestId } }
                .first { record ->
                    record?.stage?.takeIf { it != stage }?.let { stage = it; onProgress(it) }
                    record == null || record.terminal
                }
            return record?.operation?.takeIf { it.isTerminal } ?: throw MixOperationClientException(UNCONFIRMED_SECOND_PASS)
        }
    }

    // --- Viewing -----------------------------------------------------------------------------------

    /**
     * The Mixes screen holds this while it shows [machineId]/[job]/[material]: plain completions and refusals
     * it already shows inline are not repeated app-wide. Attention and unconfirmed outcomes always are.
     */
    fun viewing(machineId: String, job: String, material: String): AutoCloseable {
        val key = MaterialKey(machineId, job, material)
        viewed.update { it + (key to (it[key] ?: 0) + 1) }
        var closed = false
        return AutoCloseable {
            synchronized(lock) { if (closed) return@AutoCloseable; closed = true }
            viewed.update { current ->
                val count = (current[key] ?: 1) - 1
                if (count <= 0) current - key else current + (key to count)
            }
        }
    }

    private fun autoAcknowledged(key: MaterialKey, state: MixOperationState) =
        (viewed.value[key] ?: 0) > 0 && (state == MixOperationState.COMPLETED || state == MixOperationState.FAILED)

    // --- Notices -----------------------------------------------------------------------------------

    /** Hides a notice. A move that needs attention keeps blocking its material until the office resolves it. */
    fun dismiss(noticeId: String) {
        scope.launch {
            val requestId = noticeId.substringAfter(':')
            when {
                noticeId.startsWith(MOVE_PREFIX) -> {
                    val record = transfers.records.value.firstOrNull { it.requestId == requestId } ?: return@launch
                    if (record.state == TransferState.COMPLETED || record.state == TransferState.FAILED) transfers.dismiss(requestId)
                    else transfers.acknowledgeNotice(requestId)
                }
                noticeId.startsWith(MIX_PREFIX) -> mixOperations.acknowledge(requestId)
            }
        }
    }

    private fun moveNotice(record: MaterialTransferRecord): OperationNotice? {
        if (record.noticeAcknowledged) return null
        val destination = machineName(record.destinationMachineId)
        val (tone, outcome) = when (record.state) {
            TransferState.COMPLETED -> OperationNoticeTone.SUCCESS to "Moved to $destination"
            TransferState.FAILED -> OperationNoticeTone.FAILURE to
                "Move didn't finish${record.message?.let { ": $it" } ?: ""}. Nothing was changed on either machine."
            TransferState.NEEDS_ATTENTION -> OperationNoticeTone.ATTENTION to (MOVE_NEEDS_ATTENTION + (record.message?.let { " ($it)" } ?: ""))
            TransferState.UNCERTAIN -> OperationNoticeTone.ATTENTION to
                "The move request to $destination wasn't confirmed. Open Mixes to resend the same request; don't start another move."
            TransferState.SUBMITTING, TransferState.SUBMITTED -> return null
        }
        return OperationNotice(MOVE_PREFIX + record.requestId, tone, "${machineName(record.sourceMachineId)} → $destination",
            record.job, record.material, outcome, sticky = tone != OperationNoticeTone.SUCCESS)
    }

    private fun mixNotice(record: MixOperationRecord): OperationNotice? {
        if (record.acknowledged || !record.terminal) return null
        val secondPass = record.kind != MixOperationKind.MIX_WRITE
        val (tone, outcome) = when (record.state) {
            MixOperationState.COMPLETED -> OperationNoticeTone.SUCCESS to
                (if (secondPass) "Second-pass changes applied" else "${record.label}: done")
            MixOperationState.FAILED -> OperationNoticeTone.FAILURE to
                ((if (secondPass) "Second-pass changes weren't applied" else "${record.label}: not saved") + (record.message?.let { ": $it" } ?: ""))
            MixOperationState.NEEDS_ATTENTION -> OperationNoticeTone.ATTENTION to
                ((if (secondPass) "Second-pass changes need review" else "${record.label} needs review") + (record.message?.let { ": $it" } ?: ""))
            MixOperationState.UNCERTAIN -> OperationNoticeTone.ATTENTION to
                (if (secondPass) "Couldn't confirm second-pass changes. Reload second-pass history in Mixes before applying again."
                else "${record.label}: not confirmed. Review the mix on ${machineName(record.machineId)} before changing it again.")
            MixOperationState.SUBMITTING, MixOperationState.SUBMITTED -> return null
        }
        return OperationNotice(MIX_PREFIX + record.requestId, tone, machineName(record.machineId), record.job, record.material, outcome,
            sticky = tone != OperationNoticeTone.SUCCESS)
    }

    // --- Polling -----------------------------------------------------------------------------------

    private data class PollTarget(val move: Boolean, val requestId: String, val operationId: String) {
        val key: String get() = "${if (move) MOVE_PREFIX else MIX_PREFIX}$requestId:$operationId"
    }

    private fun ensurePolling(target: PollTarget) {
        synchronized(lock) {
            if (pollers[target.key]?.isActive == true || target.key in finishedPollers) return
            val wake = wakeups.getOrPut(target.requestId) { Channel(Channel.CONFLATED) }
            pollers[target.key] = scope.launch {
                try { if (target.move) pollMove(target.requestId, wake) else pollMix(target.requestId, wake) }
                finally { synchronized(lock) { finishedPollers += target.key; pollers.remove(target.key) } }
            }
        }
    }

    /** Waits [waitMs] or until an operator "check now"; true when woken early. */
    private suspend fun pause(waitMs: Long, wake: Channel<Unit>): Boolean = withTimeoutOrNull(waitMs) { wake.receive(); true } ?: false

    private suspend fun pollMove(requestId: String, wake: Channel<Unit>) {
        var wait = minDelayMs
        var phase: String? = null
        while (true) {
            val record = transfers.pollOnce(requestId) ?: return
            if (record.state != TransferState.SUBMITTED) {
                // Terminal: both machines' copies of the material may have changed.
                refresh(record.sourceMachineId, record.job, record.material)
                refresh(record.destinationMachineId, record.job, record.material)
                return
            }
            if (record.phase != phase) { phase = record.phase; wait = minDelayMs }
            wait = if (pause(wait, wake)) minDelayMs else (wait * 2).coerceAtMost(maxDelayMs)
        }
    }

    private suspend fun pollMix(requestId: String, wake: Channel<Unit>) {
        var wait = minDelayMs
        var stage: String? = null
        while (true) {
            val record = mixOperations.get(requestId)?.takeIf { it.pollable } ?: return
            val operation = try { operationReader(record.endpoint).getOperation(record.operationId!!) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } // Offline or unreadable is not an outcome: keep checking.
            if (operation != null && (operation.id.isBlank() || operation.id == record.operationId)) {
                val next = mixOperations.update(requestId) { current -> if (current.pollable) applyOperation(current, operation) else current }
                if (next == null || !next.pollable) {
                    next?.let { refresh(it.machineId, it.job, it.material) }
                    if (next != null && next.acknowledged && !isLive(requestId)) mixOperations.acknowledge(requestId)
                    return
                }
                if (operation.stage != stage) { stage = operation.stage; wait = minDelayMs }
            }
            wait = if (pause(wait, wake)) minDelayMs else (wait * 2).coerceAtMost(maxDelayMs)
        }
    }

    private fun isLive(requestId: String) = synchronized(lock) { requestId in liveRequests }

    private fun applyOperation(record: MixOperationRecord, operation: MixServiceOperation): MixOperationRecord {
        if (!operation.isTerminal) return record.copy(state = MixOperationState.SUBMITTED, stage = operation.stage, operation = operation)
        val live = isLive(record.requestId)
        val succeeded = operation.state == "completed" && (record.kind == MixOperationKind.MIX_WRITE || secondPassFilesSucceeded(operation.result))
        val orphanedSteps = !live && record.step < record.steps
        val (state, message) = when {
            succeeded && orphanedSteps -> MixOperationState.NEEDS_ATTENTION to
                "Step ${record.step} of ${record.steps} finished, but the app restarted before the rest were sent. Reload history in Mixes and apply again."
            succeeded -> MixOperationState.COMPLETED to null
            operation.state == "completed" -> MixOperationState.NEEDS_ATTENTION to "Not every file succeeded. Reload second-pass history before editing."
            operation.state == "failed" -> MixOperationState.FAILED to (operation.error ?: "The CNC refused the change")
            else -> MixOperationState.NEEDS_ATTENTION to "The CNC stopped mid-change (${operation.state}). Reload second-pass history before editing."
        }
        val acknowledged = live || autoAcknowledged(MaterialKey(record.machineId, record.job, record.material), state)
        return record.copy(state = state, stage = operation.stage, operation = operation, message = message, acknowledged = acknowledged)
    }

    private suspend fun refresh(machineId: String, job: String, material: String) {
        try { refreshCatalog(machineId, job, material) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { } // The catalog store reports its own offline state.
    }

    companion object {
        const val MOVE_PREFIX = "move:"
        const val MIX_PREFIX = "mix:"
        const val SECOND_PASS_LABEL = "Second-pass changes"
        const val UNCONFIRMED_SECOND_PASS = "Unable to confirm the CNC operation. Reload CNC history before applying again."
    }
}
