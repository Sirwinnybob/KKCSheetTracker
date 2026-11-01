package com.kkc.sheettracker.ui.mixes

import androidx.compose.runtime.*
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.mixservice.PgmEditHistoryView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Material-scoped editing/transaction boundary. Each save freezes only the draft it submits. */
internal class MixesSession(val gate: CatalogGate = CatalogGate()) {
    var catalog by mutableStateOf(gate.appliedCatalog); private set
    var selectedTab by mutableStateOf<String?>(null)
    var draft by mutableStateOf<MixDraft?>(null); private set
    var draftFolderRevision by mutableStateOf<String?>(null); private set
    var conflict by mutableStateOf(false); private set
    var dragging by mutableStateOf(false); private set
    var applyResult by mutableStateOf<MixFilesResult<MixFilesCatalog>?>(null); private set
    var applying by mutableStateOf(false); private set
    var savedInterrupted by mutableStateOf(false); private set
    var secondPass by mutableStateOf<SecondPassDraft?>(null); private set
    var secondApplying by mutableStateOf(false); private set
    var secondHistoryConfirmed by mutableStateOf(false); private set
    var historyLoading by mutableStateOf(false); private set
    var secondMessage by mutableStateOf<String?>(null)
    // Once submitted, bootstrap permission cannot stand in for read-back verification, even on Retry.
    private var pendingHistoryVerification: Set<String> = emptySet()
    var history by mutableStateOf<PgmEditHistoryView?>(null); private set
    var rebaseMessage by mutableStateOf<String?>(null); private set
    var mixSaveBlockedReason by mutableStateOf<String?>(null); private set
    val reapplyEnabled get() = !applying && draft?.let { MixDrafts.canReapply(it, reapplyGroup()) } == true
    private fun reapplyGroup() = gate.deferredCatalog?.let(::groupMixFiles)?.firstOrNull { it.key == draft?.groupKey }
    val recoveryMessage get() = if (conflict && !reapplyEnabled) "This target is unavailable for Re-apply. Load theirs, then use revision recovery; your draft is kept until you discard it." else null
    val dirty get() = draft != null || (secondPass?.changeCount ?: 0) > 0
    val saving get() = applying || secondApplying

    fun onCatalog(next: MixFilesCatalog) {
        when (gate.onCatalog(next, dragging, draft != null)) {
            GateDecision.APPLY -> catalog = gate.appliedCatalog
            GateDecision.CONFLICT -> conflict = true
            GateDecision.DEFER -> Unit
        }
    }
    fun edit(group: MixGroup?, locked: List<String>, change: (MixDraft) -> MixDraft) {
        if (applying || draft?.rawRestore != null) return
        val initial = draft ?: group?.let { MixDrafts.start(it, locked) } ?: return
        if (draft == null) draftFolderRevision = catalog?.folderRevision
        draft = change(initial.copy(completedPgms = locked.map(::key).toSet()))
        mixSaveBlockedReason = null
        savedInterrupted = false
        if (applyResult !is MixFilesResult.Offline) applyResult = null
    }
    fun beginDrag() { if (!applying && draft?.rawRestore == null) dragging = true }
    fun drop(group: MixGroup?, locked: List<String>, moves: List<Pair<RowModel, RowModel>>) {
        if (moves.isNotEmpty()) edit(group, locked) { initial -> moves.fold(initial) { d, (from, to) -> moveRowBlock(d, from.draftEntryIds, to.draftEntryIds) } }
        dragging = false
        if (gate.onDrop(draft != null) == GateDecision.CONFLICT) conflict = true else catalog = gate.appliedCatalog
    }
    /** Every abandonment accepts the current store snapshot before its caller selects or seeds. */
    fun discardMix(latest: MixFilesCatalog?): Boolean {
        if (applying) return false
        (latest ?: gate.deferredCatalog ?: catalog)?.let { gate.accept(it); catalog = it }
        draft = null; draftFolderRevision = null; conflict = false; applyResult = null; savedInterrupted = false; mixSaveBlockedReason = null
        return true
    }
    fun discardSecond(): Boolean {
        if (secondApplying) return false
        secondPass = secondPass?.let { it.copy(wanted = it.current) }
        if (secondHistoryConfirmed) secondMessage = null
        return true
    }
    fun abandonAll(latest: MixFilesCatalog?): Boolean {
        if (saving) return false
        discardMix(latest); discardSecond()
        return true
    }
    fun startNew(material: String, pages: List<SheetPage>, locked: List<String>) {
        if (applying) return
        val file = nextMixName(material, catalog?.files.orEmpty().filter { it.kind != MixFileKind.REVISION }.map { it.file }) + ".mix"
        val other = catalog?.let(::groupMixFiles).orEmpty().filter { it.mix != null || it.frz != null }.flatMap { it.primary.entries }.map { it.pgm }.toSet()
        draft = MixDrafts.startNew(file, pages, other).copy(completedPgms = locked.map(::key).toSet())
        draftFolderRevision = catalog?.folderRevision; selectedTab = draft?.groupKey
        conflict = false; applyResult = null
    }
    suspend fun saveMix(locked: () -> List<String> = { draft?.completedPgms.orEmpty().toList() },
        submit: suspend (MixDraft) -> MixFilesResult<MixFilesCatalog>): MixFilesResult<MixFilesCatalog>? {
        if (applying || conflict) return null
        val current = draft ?: return null
        if (current.rawRestore != null) {
            val group = catalog?.let(::groupMixFiles)?.firstOrNull { it.key == current.groupKey }
            val valid = runCatching { buildWrite(current, group, emptyList(), emptyMap()) }.isSuccess
            if (!valid) { mixSaveBlockedReason = "Restore target or revision changed. Load theirs and choose a revision again."; return null }
        }
        // Read once at the actual submission boundary, before any suspension or request construction.
        // The same snapshot validates split membership and supplies buildWrite's completion.
        val completion = locked()
        mixSaveBlockedReason = splitCompletionBlockReason(current, completion)
        if (mixSaveBlockedReason != null) return null
        val submitted = current.copy(completedPgms = completion.map(::key).toSet())
        applying = true
        return try {
            val result = submit(submitted)
            applyResult = result
            if (result is MixFilesResult.Rejected && result.error.code == "revision_conflict") conflict = true
            if (result is MixFilesResult.Ok) {
                gate.accept(result.value); catalog = result.value
                savedInterrupted = submitted.isInterrupted; draft = null; draftFolderRevision = null; conflict = false
            }
            result
        } finally { applying = false }
    }
    fun stageRevision(group: MixGroup, revision: MixFileRow, locked: List<String>): Boolean {
        if (applying) return false
        val preview = try { MixDrafts.fromRevision(group, revision, locked) }
        catch (failure: IllegalArgumentException) {
            rebaseMessage = failure.message ?: "Refresh and choose a readable revision."
            return false
        }
        draft = preview
        draftFolderRevision = catalog?.folderRevision
        selectedTab = group.key; conflict = false; applyResult = null; savedInterrupted = false
        return true
    }
    fun renameNew(file: String) {
        if (applying) return
        val current = draft?.takeIf { it.newFile != null } ?: return
        draft = current.copy(newFile = file, groupKey = key(file), changeCount = current.changeCount + 1)
        selectedTab = key(file)
    }
    /** Rename, retirement, raw recovery and permanent revision deletion share the mix save guard. */
    suspend fun mutateCatalog(submit: suspend () -> MixFilesResult<MixFilesCatalog>): MixFilesResult<MixFilesCatalog>? {
        if (applying) return null
        applying = true
        return try {
            submit().also { result ->
                applyResult = result
                if (result is MixFilesResult.Ok) {
                    gate.accept(result.value); catalog = result.value
                    draft = null; draftFolderRevision = null; conflict = false; savedInterrupted = false
                }
            }
        } finally { applying = false }
    }
    fun acknowledgeRefresh(confirmed: Boolean) { if (!applying && confirmed) applyResult = null }
    fun reapply(locked: List<String>) {
        if (applying) return
        val fresh = gate.deferredCatalog ?: return
        val d = draft?.takeIf { it.newFile == null } ?: return
        val group = groupMixFiles(fresh).firstOrNull { it.key == d.groupKey }
        if (!MixDrafts.canReapply(d, group)) {
            rebaseMessage = "This target is unavailable for Re-apply. Load theirs, then use revision recovery."
            return
        }
        val rebased = MixDrafts.rebase(d, requireNotNull(group), locked)
        draft = rebased.draft; draftFolderRevision = fresh.folderRevision
        gate.accept(fresh); catalog = fresh; conflict = false; applyResult = null
        rebaseMessage = rebased.dropped.takeIf { it.isNotEmpty() }?.let { "No longer on CNC: ${it.joinToString()}" }
    }
    fun acknowledgeRebase() { rebaseMessage = null }
    fun updateSecond(pgms: List<String>, field: SecondPassField, on: Boolean, locked: List<String>) {
        if (secondApplying || !secondHistoryConfirmed || historyLoading) return
        val locks = locked.map(::key).toSet()
        secondPass = secondPass?.let { SecondPassDrafts.setAll(it, pgms.filterNot { pgm -> key(pgm) in locks }, field, on) }
    }
    suspend fun reloadHistory(pgms: List<String>, load: suspend () -> PgmEditHistoryView?) {
        if (secondApplying || historyLoading) return
        historyLoading = true
        secondHistoryConfirmed = false
        try {
            val history = load()
            val previouslyRecorded = this.history?.files?.keys.orEmpty()
            val required = previouslyRecorded + pendingHistoryVerification
            secondHistoryConfirmed = history?.canEdit == true && (required.isEmpty() || history.records(required))
            if (secondHistoryConfirmed && history != null) {
                this.history = history
                pendingHistoryVerification = emptySet()
                secondPass = mergeWanted(SecondPassDrafts.from(history, pgms), secondPass)
                secondMessage = if (!history.records(pgms)) NO_RECORDED_STATE else null
            } else secondMessage = HISTORY_FAILURE
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { secondMessage = HISTORY_FAILURE }
        finally { historyLoading = false }
    }
    suspend fun saveSecond(locked: () -> List<String>, submit: suspend (SecondPassDraft) -> SecondPassOutcome,
        load: suspend () -> PgmEditHistoryView?) {
        if (secondApplying || !secondHistoryConfirmed || historyLoading) return
        val current = secondPass ?: return
        val locks = locked().map(::key).toSet()
        val submitted = current.copy(wanted = current.wanted.mapValues { (pgm, wanted) ->
            if (key(pgm) in locks) current.current.pgmValue(pgm) ?: wanted else wanted
        })
        secondPass = submitted
        if (submitted.changeCount == 0) return // Nothing unlocked remains: never call the runner.
        secondApplying = true
        secondHistoryConfirmed = false
        pendingHistoryVerification = submitted.wanted.filter { (pgm, state) -> state != submitted.current.pgmValue(pgm) }.keys
        try {
            val outcome = submit(submitted)
            val history = load()
            secondHistoryConfirmed = history?.records(pendingHistoryVerification) == true
            if (secondHistoryConfirmed && history != null) {
                this.history = history
                pendingHistoryVerification = emptySet()
                val fresh = SecondPassDrafts.from(history, submitted.wanted.keys.toList())
                secondPass = if (outcome == SecondPassOutcome.Done) fresh else mergeWanted(fresh, submitted)
            }
            secondMessage = when (outcome) {
                SecondPassOutcome.Done -> if (secondHistoryConfirmed) "Second-pass changes applied" else "Changes submitted. $HISTORY_FAILURE"
                is SecondPassOutcome.Failed -> outcome.message + if (!secondHistoryConfirmed) " $HISTORY_FAILURE" else ""
            }
        } catch (cancelled: CancellationException) { secondMessage = HISTORY_FAILURE; throw cancelled }
        catch (_: Exception) { secondHistoryConfirmed = false; secondMessage = "Couldn't confirm second-pass changes. Reload history before applying again." }
        finally { secondApplying = false }
    }
    private fun mergeWanted(fresh: SecondPassDraft, old: SecondPassDraft?): SecondPassDraft {
        val deltas = old?.wanted.orEmpty().filter { (pgm, state) -> state != old?.current?.pgmValue(pgm) }
        return deltas.entries.fold(fresh) { draft, (pgm, wanted) -> SecondPassDrafts.set(draft, pgm, wanted) }
    }
    private fun key(value: String) = value.lowercase(Locale.ROOT)
    companion object {
        const val HISTORY_FAILURE = "Second-pass state is unknown. Retry history or recover the CNC file before editing."
        const val NO_RECORDED_STATE = "No recorded state for some files. First additive edits are allowed; unchecked boxes are not verified clean."
    }
}

/** Stable per-job destination: surviving collectors keep writing the map rendered after membership changes. */
@Composable
internal fun rememberMixCatalogStates(job: String, sources: Map<String, StateFlow<CatalogState>>): Map<String, CatalogState> {
    val states = remember(job) { mutableStateMapOf<String, CatalogState>() }
    LaunchedEffect(job, sources.keys.toSet()) { states.keys.toList().filterNot { it in sources }.forEach(states::remove) }
    sources.forEach { (name, source) -> key(job, name) {
        LaunchedEffect(source, states) { source.collect { states[name] = it } }
    } }
    return states
}
