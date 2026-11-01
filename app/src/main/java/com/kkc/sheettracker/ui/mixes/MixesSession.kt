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
    var history by mutableStateOf<PgmEditHistoryView?>(null); private set
    var rebaseMessage by mutableStateOf<String?>(null); private set
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
        if (applying) return
        val initial = draft ?: group?.let { MixDrafts.start(it, locked) } ?: return
        if (draft == null) draftFolderRevision = catalog?.folderRevision
        draft = change(initial.copy(completedPgms = locked.map(::key).toSet()))
        savedInterrupted = false
        if (applyResult !is MixFilesResult.Offline) applyResult = null
    }
    fun beginDrag() { if (!applying) dragging = true }
    fun drop(group: MixGroup?, locked: List<String>, moves: List<Pair<RowModel, RowModel>>) {
        if (moves.isNotEmpty()) edit(group, locked) { initial -> moves.fold(initial) { d, (from, to) -> moveRowBlock(d, from.draftEntryIds, to.draftEntryIds) } }
        dragging = false
        if (gate.onDrop(draft != null) == GateDecision.CONFLICT) conflict = true else catalog = gate.appliedCatalog
    }
    /** Every abandonment accepts the current store snapshot before its caller selects or seeds. */
    fun discardMix(latest: MixFilesCatalog?): Boolean {
        if (applying) return false
        (latest ?: gate.deferredCatalog ?: catalog)?.let { gate.accept(it); catalog = it }
        draft = null; draftFolderRevision = null; conflict = false; applyResult = null; savedInterrupted = false
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
    suspend fun saveMix(submit: suspend (MixDraft) -> MixFilesResult<MixFilesCatalog>): MixFilesResult<MixFilesCatalog>? {
        if (applying) return null
        val submitted = draft ?: return null
        applying = true
        return try {
            val result = submit(submitted)
            applyResult = result
            if (result is MixFilesResult.Ok) {
                gate.accept(result.value); catalog = result.value
                savedInterrupted = submitted.isInterrupted; draft = null; draftFolderRevision = null; conflict = false
            }
            result
        } finally { applying = false }
    }
    fun stageRevision(group: MixGroup, revision: MixFileRow, locked: List<String>) {
        if (applying) return
        draft = MixDrafts.fromRevision(group, revision, locked)
        draftFolderRevision = catalog?.folderRevision
        selectedTab = group.key; conflict = false; applyResult = null; savedInterrupted = false
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
        val group = groupMixFiles(fresh).firstOrNull { it.key == d.groupKey } ?: return
        val rebased = MixDrafts.rebase(d, group, locked)
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
            secondHistoryConfirmed = history != null
            if (history != null) {
                this.history = history
                secondPass = mergeWanted(SecondPassDrafts.from(history, pgms), secondPass)
                secondMessage = null
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
            if (key(pgm) in locks) current.current[pgm] ?: wanted else wanted
        })
        secondPass = submitted
        if (submitted.changeCount == 0) return // Nothing unlocked remains: never call the runner.
        secondApplying = true
        try {
            val outcome = submit(submitted)
            val history = load()
            secondHistoryConfirmed = history != null
            if (history != null) {
                this.history = history
                val fresh = SecondPassDrafts.from(history, submitted.wanted.keys.toList())
                secondPass = if (outcome == SecondPassOutcome.Done) fresh else mergeWanted(fresh, submitted)
            }
            secondMessage = when (outcome) {
                SecondPassOutcome.Done -> if (history != null) "Second-pass changes applied" else "Changes submitted. $HISTORY_FAILURE"
                is SecondPassOutcome.Failed -> outcome.message
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { secondHistoryConfirmed = false; secondMessage = "Couldn't confirm second-pass changes. Reload history before applying again." }
        finally { secondApplying = false }
    }
    private fun mergeWanted(fresh: SecondPassDraft, old: SecondPassDraft?): SecondPassDraft {
        val deltas = old?.wanted.orEmpty().filter { (pgm, state) -> state != old?.current?.get(pgm) && pgm in fresh.wanted }
        return fresh.copy(wanted = fresh.wanted + deltas)
    }
    private fun key(value: String) = value.lowercase(Locale.ROOT)
    companion object { const val HISTORY_FAILURE = "Couldn't load second-pass history. Retry before editing." }
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
