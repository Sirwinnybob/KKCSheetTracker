package com.kkc.sheettracker.data.mixfiles

import com.google.gson.Gson
import com.kkc.sheettracker.data.mixservice.MixOperationService
import com.kkc.sheettracker.data.mixservice.MixServiceOperation
import com.kkc.sheettracker.data.mixservice.PgmEditHistoryView
import com.kkc.sheettracker.data.mixservice.PgmEditRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

data class SecondPassState(val secondPass: Boolean, val superPass: Boolean, val removePUnload: Boolean)

data class SecondPassDraft(val wanted: Map<String, SecondPassState>, val current: Map<String, SecondPassState>) {
    val changeCount: Int get() = wanted.count { (pgm, state) -> state.normalized() != current[pgm]?.normalized() }
}

enum class SecondPassField { PUNLOAD, SECOND, SUPER }

sealed interface TriState {
    data object ON : TriState
    data object OFF : TriState
    data class PARTIAL(val count: Int) : TriState
}

private val NO_EDITS = SecondPassState(false, false, false)
private fun SecondPassState.normalized() = if (superPass) copy(secondPass = true) else this

object SecondPassDrafts {
    fun from(history: PgmEditHistoryView?, pgms: List<String>): SecondPassDraft {
        val current = pgms.associateWith { pgm ->
            val state = history?.files?.get(pgm)?.current
            SecondPassState(state?.mode in setOf("standard", "super"), state?.mode == "super", state?.punloadRemoved == true)
        }
        return SecondPassDraft(current.toMap(), current)
    }

    fun set(d: SecondPassDraft, pgm: String, state: SecondPassState): SecondPassDraft {
        if (pgm !in d.wanted) return d
        return d.copy(wanted = d.wanted + (pgm to state.normalized()))
    }

    /** The caller supplies only unlocked PGMs; no other row is changed. */
    fun setAll(d: SecondPassDraft, pgms: List<String>, field: SecondPassField, on: Boolean): SecondPassDraft =
        pgms.distinct().fold(d) { draft, pgm ->
            val state = draft.wanted[pgm] ?: return@fold draft
            set(draft, pgm, when (field) {
                SecondPassField.PUNLOAD -> state.copy(removePUnload = on)
                SecondPassField.SECOND -> state.copy(secondPass = on, superPass = on && state.superPass)
                SecondPassField.SUPER -> state.copy(superPass = on, secondPass = on || state.secondPass)
            })
        }

    fun tri(d: SecondPassDraft, pgms: List<String>, field: SecondPassField): TriState {
        val states = pgms.distinct().mapNotNull(d.wanted::get)
        val count = states.count { state -> when (field) {
            SecondPassField.PUNLOAD -> state.removePUnload
            SecondPassField.SECOND -> state.normalized().secondPass
            SecondPassField.SUPER -> state.superPass
        } }
        return when {
            count == 0 -> TriState.OFF
            count == states.size -> TriState.ON
            else -> TriState.PARTIAL(count)
        }
    }
}

sealed interface SecondPassStep {
    data class Edit(val rows: List<PgmEditRow>) : SecondPassStep
    data class Revert(val pgms: List<String>) : SecondPassStep
}

fun planSecondPass(d: SecondPassDraft): List<SecondPassStep> {
    val reverts = mutableListOf<String>()
    val edits = mutableListOf<PgmEditRow>()
    d.wanted.forEach { (pgm, raw) ->
        val wanted = raw.normalized()
        val current = (d.current[pgm] ?: NO_EDITS).normalized()
        if (wanted == current) return@forEach
        if ((current.secondPass && !wanted.secondPass) || (current.superPass && !wanted.superPass) ||
            (current.removePUnload && !wanted.removePUnload)) reverts.add(pgm)
        if (wanted != NO_EDITS) edits.add(PgmEditRow(pgm, when {
            wanted.superPass -> "super"
            wanted.secondPass -> "standard"
            else -> "none"
        }, wanted.removePUnload))
    }
    return buildList {
        if (reverts.isNotEmpty()) add(SecondPassStep.Revert(reverts))
        if (edits.isNotEmpty()) add(SecondPassStep.Edit(edits))
    }
}

/** Read the established tablet identity at Apply time, then share this source for the whole plan. */
fun secondPassRequestIds(deviceId: String, epochMs: () -> Long = System::currentTimeMillis): () -> String {
    val safeId = deviceId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    require(safeId.isNotEmpty()) { "A tablet identity is required" }
    var last = Long.MIN_VALUE
    val lock = Any()
    return { synchronized(lock) {
        val next = maxOf(epochMs(), if (last == Long.MIN_VALUE) Long.MIN_VALUE else last + 1)
        last = next
        "tablet-$safeId-$next"
    } }
}

sealed interface SecondPassOutcome {
    data object Done : SecondPassOutcome
    data class Failed(val code: String?, val message: String, val partial: Boolean) : SecondPassOutcome
}

class SecondPassRunner(
    private val service: MixOperationService,
    private val revert: suspend (job: String, material: String, requestId: String, pgms: List<String>) -> MixServiceOperation,
    private val requestId: () -> String,
) {
    suspend fun apply(job: String, material: String, plan: List<SecondPassStep>, onProgress: (String) -> Unit): SecondPassOutcome {
        var completedStep = false
        var submitted = false
        try {
            for (step in plan) {
                currentCoroutineContext().ensureActive()
                val id = requestId()
                submitted = true // A transport failure after this point leaves the CNC outcome uncertain.
                var operation = when (step) {
                    is SecondPassStep.Edit -> service.submitPgmEdits(job, material, id, step.rows)
                    is SecondPassStep.Revert -> revert(job, material, id, step.pgms)
                }
                onProgress(operation.stage)
                while (!operation.isTerminal) {
                    if (operation.id.isBlank()) return SecondPassOutcome.Failed(null, "The CNC returned no operation ID", true)
                    delay(1_000)
                    operation = service.getOperation(operation.id)
                    onProgress(operation.stage)
                }
                if (operation.state != "completed") {
                    val disabled = operation.code == "edit_failed" && operation.error.orEmpty().contains("disabled", ignoreCase = true)
                    return SecondPassOutcome.Failed(operation.code,
                        if (disabled) "Second-pass editing is switched off on the CNC" else operation.error ?: "Second-pass operation ${operation.state}",
                        completedStep || operation.state == "interrupted")
                }
                if (!successfulFiles(operation.result)) {
                    return SecondPassOutcome.Failed(operation.code, "Second-pass operation did not succeed for every file. Reload CNC history before applying again.", true)
                }
                completedStep = true
                submitted = false
            }
            return SecondPassOutcome.Done
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Never retry a mutation whose acceptance/result is uncertain.
            return SecondPassOutcome.Failed(null, failure.message ?: "Unable to confirm the CNC operation. Reload CNC history before applying again.", completedStep || submitted)
        }
    }

    private fun successfulFiles(result: Any?): Boolean = try {
        val json = Gson().toJsonTree(result).asJsonObject
        val ok = json["ok"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
        val files = json["files"]?.takeIf { it.isJsonArray }?.asJsonArray
        ok && files != null && files.size() > 0 && files.all { file ->
            val status = file.asJsonObject["status"]
            status != null && status.isJsonPrimitive && status.asJsonPrimitive.isString && status.asString in setOf("succeeded", "skipped")
        }
    } catch (_: Exception) { false }
}
