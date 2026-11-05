package com.kkc.sheettracker.data.mixfiles

import com.google.gson.Gson
import com.kkc.sheettracker.data.cnc.MixOperationKind
import com.kkc.sheettracker.data.mixservice.MixOperationService
import com.kkc.sheettracker.data.mixservice.MixServiceOperation
import com.kkc.sheettracker.data.mixservice.PgmEditHistoryView
import com.kkc.sheettracker.data.mixservice.PgmEditRow
import com.kkc.sheettracker.data.mixservice.PgmNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.Locale

data class SecondPassState(val secondPass: Boolean, val superPass: Boolean, val removePUnload: Boolean)

/** PGM identity only: paths and parameter values remain opaque. First spelling wins. */
fun pgmIdentity(name: String): String = name.lowercase(Locale.ROOT)
fun canonicalPgms(names: Collection<String>): List<String> = names.distinctBy(::pgmIdentity)
/** A-side stays in sheet/MIX pairs, but never participates in machining edits. */
fun canEditPgm(name: String): Boolean = !name.endsWith("A.pgm", ignoreCase = true)
fun <T> Map<String, T>.pgmValue(name: String): T? = entries.firstOrNull { it.key.equals(name, true) }?.value

/** [punloadFixed]: the machine always removes PUNLOAD, so every state carries removal and it is never a change. */
data class SecondPassDraft(val wanted: Map<String, SecondPassState>, val current: Map<String, SecondPassState>,
    val punloadFixed: Boolean = false) {
    val changeCount: Int get() = wanted.entries.filter { canEditPgm(it.key) }.distinctBy { pgmIdentity(it.key) }.count { (pgm, state) -> state.normalized() != current.pgmValue(pgm)?.normalized() }
    /** Whether any of [pgms] has a staged change not yet applied. */
    fun pending(pgms: List<String>): Boolean = pgms.filter(::canEditPgm).any { pgm ->
        wanted.pgmValue(pgm)?.let { it.normalized() != current.pgmValue(pgm)?.normalized() } == true
    }
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
    fun from(history: PgmEditHistoryView?, pgms: List<String>, punloadFixed: Boolean = false): SecondPassDraft {
        require(history?.canEdit == true) { "Second-pass history is uncertain" }
        val current = canonicalPgms(pgms).associateWith { pgm ->
            val state = history.file(pgm)?.current
            SecondPassState(state?.mode in setOf("standard", "super"), state?.mode == "super", punloadFixed || state?.punloadRemoved == true)
        }
        return SecondPassDraft(current.toMap(), current, punloadFixed)
    }

    fun set(d: SecondPassDraft, pgm: String, state: SecondPassState): SecondPassDraft {
        if (!canEditPgm(pgm)) return d
        val canonical = d.wanted.keys.firstOrNull { it.equals(pgm, true) } ?: return d
        val next = if (d.punloadFixed) state.copy(removePUnload = true) else state
        return d.copy(wanted = d.wanted + (canonical to next.normalized()))
    }

    /** The caller supplies only unlocked PGMs; no other row is changed. */
    fun setAll(d: SecondPassDraft, pgms: List<String>, field: SecondPassField, on: Boolean): SecondPassDraft =
        canonicalPgms(pgms).fold(d) { draft, pgm ->
            val state = draft.wanted.pgmValue(pgm) ?: return@fold draft
            set(draft, pgm, when (field) {
                SecondPassField.PUNLOAD -> state.copy(removePUnload = on)
                SecondPassField.SECOND -> state.copy(secondPass = on, superPass = on && state.superPass)
                SecondPassField.SUPER -> state.copy(superPass = on, secondPass = on || state.secondPass)
            })
        }

    fun tri(d: SecondPassDraft, pgms: List<String>, field: SecondPassField): TriState {
        val states = canonicalPgms(pgms).filter(::canEditPgm).mapNotNull(d.wanted::pgmValue)
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
    d.wanted.entries.distinctBy { pgmIdentity(it.key) }.forEach { (pgm, raw) ->
        if (!canEditPgm(pgm)) return@forEach
        val wanted = raw.normalized()
        val current = (d.current.pgmValue(pgm) ?: NO_EDITS).normalized()
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

enum class SecondPassMutation { UNCERTAIN, REFUSED_BEFORE_MUTATION }

sealed interface SecondPassOutcome {
    data object Done : SecondPassOutcome
    data class Failed(val code: String?, val message: String, val partial: Boolean,
        val mutation: SecondPassMutation = SecondPassMutation.UNCERTAIN) : SecondPassOutcome
}

/**
 * Journals and watches each second-pass operation outside any screen. With a tracker, the runner
 * never polls: the single app-scoped watcher does, and the runner waits for its terminal snapshot.
 */
interface SecondPassTracker {
    /** Records [requestId] before [post] runs, then binds the accepted operation. [post] runs at most once. */
    suspend fun submit(kind: MixOperationKind, requestId: String, step: Int, steps: Int,
        post: suspend () -> MixServiceOperation): MixServiceOperation
    /** Suspends until the watcher sees a terminal state for [requestId]. */
    suspend fun awaitTerminal(requestId: String, onProgress: (String) -> Unit): MixServiceOperation
}

class SecondPassRunner(
    private val service: MixOperationService,
    private val revert: suspend (job: String, material: String, requestId: String, pgms: List<String>) -> MixServiceOperation,
    private val requestId: () -> String,
    private val revisions: SecondPassRevisionSupport? = null,
    private val onNotices: (List<PgmNotice>) -> Unit = {},
    private val tracker: SecondPassTracker? = null,
) {
    suspend fun apply(job: String, material: String, plan: List<SecondPassStep>, onProgress: (String) -> Unit): SecondPassOutcome {
        var completedStep = false
        var submitted = false
        var activeHashes = revisions?.snapshot?.hashes.orEmpty()
        try {
            revisions?.preflight(plan)
            for ((index, step) in plan.withIndex()) {
                currentCoroutineContext().ensureActive()
                val editRows = (step as? SecondPassStep.Edit)?.let { revisions?.editRows(it.rows, activeHashes) ?: it.rows }
                val revertRows = (step as? SecondPassStep.Revert)?.let { revisions?.revertRows(it.pgms, activeHashes) }
                val id = requestId()
                submitted = true // A transport failure after this point leaves the CNC outcome uncertain.
                val post: suspend () -> MixServiceOperation = { when (step) {
                    is SecondPassStep.Edit -> service.submitPgmEdits(job, material, id, editRows!!)
                    is SecondPassStep.Revert -> if (revisions != null) revisions.revert(job, material, id, revertRows!!) else revert(job, material, id, step.pgms)
                } }
                val kind = if (step is SecondPassStep.Revert) MixOperationKind.SECOND_PASS_REVERT else MixOperationKind.SECOND_PASS_EDIT
                var operation = tracker?.submit(kind, id, index + 1, plan.size, post) ?: post()
                onProgress(operation.stage)
                if (!operation.isTerminal && operation.id.isBlank()) return SecondPassOutcome.Failed(null, "The CNC returned no operation ID", true)
                if (tracker != null) {
                    if (!operation.isTerminal) operation = tracker.awaitTerminal(id, onProgress)
                } else while (!operation.isTerminal) {
                    delay(1_000)
                    operation = service.getOperation(operation.id)
                    onProgress(operation.stage)
                }
                if (operation.state != "completed") {
                    val disabled = operation.code == "edit_failed" && operation.error == "second-pass PGM editing is disabled"
                    // These service/CLI refusals precede all file writes. A previous successful
                    // plan step still needs recorded read-back, even when this step was refused.
                    val refused = !completedStep && operation.state == "failed" &&
                        (operation.mutation == "refused_before_mutation" || (operation.mutation == null &&
                            (disabled || operation.code in setOf("invalid_edit_request", "missing_program", "compile_busy", "edit_busy"))))
                    return SecondPassOutcome.Failed(operation.code,
                        if (disabled) "Second-pass editing is switched off on the CNC" else operation.error ?: "Second-pass operation ${operation.state}",
                        completedStep || operation.state == "interrupted",
                        if (refused) SecondPassMutation.REFUSED_BEFORE_MUTATION else SecondPassMutation.UNCERTAIN)
                }
                if (!successfulFiles(operation.result)) {
                    return SecondPassOutcome.Failed(operation.code, "Second-pass operation did not succeed for every file. Reload CNC history before applying again.", true)
                }
                completedStep = true
                submitted = false
                onNotices(secondPassNotices(operation.result))
                if (step is SecondPassStep.Revert && revisions != null) {
                    onProgress("Refreshing restored CNC revisions")
                    activeHashes = revisions.afterRestore(step.pgms, activeHashes)
                }
            }
            return SecondPassOutcome.Done
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: RevisionRefused) {
            return SecondPassOutcome.Failed("pgm_changed", refused.message ?: "Refresh CNC revisions", completedStep || submitted,
                if (!completedStep && !submitted) SecondPassMutation.REFUSED_BEFORE_MUTATION else SecondPassMutation.UNCERTAIN)
        } catch (failure: Exception) {
            // Never retry a mutation whose acceptance/result is uncertain.
            return SecondPassOutcome.Failed(null, failure.message ?: "Unable to confirm the CNC operation. Reload CNC history before applying again.", completedStep || submitted)
        }
    }

    private fun successfulFiles(result: Any?): Boolean = secondPassFilesSucceeded(result)
}

/** Every file of a completed second-pass operation succeeded (or was skipped). */
internal fun secondPassFilesSucceeded(result: Any?): Boolean = try {
        val json = Gson().toJsonTree(result).asJsonObject
        val ok = json["ok"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
        val files = json["files"]?.takeIf { it.isJsonArray }?.asJsonArray
        ok && files != null && files.size() > 0 && files.all { file ->
            val status = file.asJsonObject["status"]
            status != null && status.isJsonPrimitive && status.asJsonPrimitive.isString && status.asString in setOf("succeeded", "skipped")
        }
    } catch (_: Exception) { false }

private fun secondPassNotices(result: Any?): List<PgmNotice> = runCatching {
    val root = Gson().toJsonTree(result).asJsonObject
    root.getAsJsonArray("files").flatMap { item ->
        val file = item.asJsonObject
        val notices = file.getAsJsonArray("notices")?.map { Gson().fromJson(it, PgmNotice::class.java) }.orEmpty()
        val fallback = file.getAsJsonObject("conversion")?.getAsJsonArray("fallbackReasons")
        notices + if (fallback != null && fallback.size() > 0) listOf(PgmNotice("scm_fallback", "SCM fallback used for ${file["name"]?.asString ?: "a PGM"}")) else emptyList()
    }
}.getOrDefault(emptyList())
