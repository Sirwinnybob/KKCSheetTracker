package com.kkc.sheettracker.ui.mixes

import androidx.compose.runtime.*
import com.kkc.sheettracker.data.cnc.CncMachine
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.mixorder.*
import com.kkc.sheettracker.data.mixservice.PgmEditHistoryView
import com.kkc.sheettracker.data.models.PageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Selection/session identity: identical job and material names on two machines never share a session. */
internal fun mixesSessionKey(machine: CncMachine, job: String, material: String?): MaterialKey =
    MaterialKey(machine.id, job, material.orEmpty())

/**
 * Material-scoped editing/transaction boundary. Each save freezes only the draft it submits.
 * [key] names the machine the session's drafts and in-flight submissions belong to; a selection
 * change creates a new session rather than retargeting this one.
 */
internal class MixesSession(val gate: CatalogGate = CatalogGate(), val key: MaterialKey? = null,
    /** Machines that always remove PUNLOAD never offer keeping it. */
    private val punloadFixed: Boolean = false) {
    var catalog by mutableStateOf(gate.appliedCatalog); private set
    var selectedTab by mutableStateOf<String?>(null)
    var draft by mutableStateOf<MixDraft?>(null); private set
    /** Suggestion behind the open new-mix draft; null for existing mixes and jobs that don't qualify. Cleared with [draft]. */
    var smart by mutableStateOf<SmartMixContext?>(null); private set
    /** Learned (or prior) weights for the next [startNew]; the screen sets this once training has finished. */
    var smartWeights: MixOrderWeights = PRIOR_WEIGHTS
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
    // Uncertain submissions need recorded read-back, even on Retry. Only a proven refusal
    // can release the requirement added by that submission; older requirements survive.
    private var pendingHistoryVerification: Set<String> = emptySet()
    private var refusedSecondMessage: String? = null
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
        draft = null; smart = null; draftFolderRevision = null; conflict = false; applyResult = null; savedInterrupted = false; mixSaveBlockedReason = null
        return true
    }
    fun discardSecond(): Boolean {
        if (secondApplying) return false
        secondPass = secondPass?.let { it.copy(wanted = it.current) }
        if (secondHistoryConfirmed) { secondMessage = null; refusedSecondMessage = null }
        return true
    }
    fun abandonAll(latest: MixFilesCatalog?): Boolean {
        if (saving) return false
        discardMix(latest); discardSecond()
        return true
    }
    /**
     * Opens a new-mix draft in Default (page) order. When [metadataPages] place every part of every sheet in the
     * draft, and the sheets account for exactly the draft's PGMs, the draft is re-sorted by [smartWeights]
     * and [smart] records the suggestion; otherwise [smart] stays null and nothing changes from Default.
     */
    fun startNew(material: String, pages: List<SheetPage>, locked: List<String>, metadataPages: List<PageMetadata> = emptyList()) {
        if (applying) return
        installNew(newMixSeed(material, pages, locked, metadataPages, catalog, smartWeights))
    }
    /** Geometry and scoring run on a worker; a late result never replaces a newer draft or catalog. */
    suspend fun startNewInBackground(material: String, pages: List<SheetPage>, locked: List<String>,
        metadataPages: List<PageMetadata>, dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default) {
        if (applying) return
        val sourceCatalog = catalog
        val previousDraft = draft
        val weights = smartWeights
        val seed = kotlinx.coroutines.withContext(dispatcher) {
            newMixSeed(material, pages, locked, metadataPages, sourceCatalog, weights)
        }
        if (!applying && catalog === sourceCatalog && draft === previousDraft) installNew(seed)
    }
    private fun newMixSeed(material: String, pages: List<SheetPage>, locked: List<String>, metadataPages: List<PageMetadata>,
        catalog: MixFilesCatalog?, weights: MixOrderWeights): Pair<MixDraft, SmartMixContext?> {
        val file = nextMixName(material, catalog?.files.orEmpty().filter { it.kind != MixFileKind.REVISION }.map { it.file }) + ".mix"
        val other = catalog?.let(::groupMixFiles).orEmpty().filter { it.mix != null || it.frz != null }.flatMap { it.primary.entries }.map { it.pgm }.toSet()
        val base = MixDrafts.startNew(file, pages, other).copy(completedPgms = locked.map(::key).toSet())
        val context = smartContext(base, metadataPages, weights)
        return (context?.let { MixDrafts.reorderSheets(base, orderedPgms(it.sheets, it.suggestedOrder)) } ?: base) to context
    }
    private fun installNew(seed: Pair<MixDraft, SmartMixContext?>) {
        val (seededDraft, context) = seed
        draft = seededDraft
        smart = context
        draftFolderRevision = catalog?.folderRevision; selectedTab = draft?.groupKey
        conflict = false; applyResult = null
    }
    private fun smartContext(base: MixDraft, metadataPages: List<PageMetadata>, weights: MixOrderWeights): SmartMixContext? {
        val draftPgms = base.entries.mapNotNull { it.newPgm }.map(::key).toSet()
        if (draftPgms.isEmpty()) return null
        val sheets = smartSheets(metadataPages, draftPgms)?.takeIf { it.isNotEmpty() } ?: return null
        // A continuation page with its own PGMs, or a PGM no page explains, would be ordered and logged wrongly.
        // Sheets may list PGMs startNew left out (a double-sided sheet whose A is in another mix), but every draft PGM must be explained.
        if (!sheets.flatMap { it.pgms }.map(::key).toSet().containsAll(draftPgms)) return null
        val validWeights = sanitizedSmartWeights(weights)
        val suggested = smartOrder(sheets, validWeights).map { it.key }
        return SmartMixContext(MixOrderMode.SMART, sheets, sheets.map { it.key }, suggested, validWeights)
    }
    private fun orderedPgms(sheets: List<SmartSheet>, order: List<String>): List<List<String>> {
        val byKey = sheets.associateBy { it.key }
        return order.mapNotNull { byKey[it]?.pgms }
    }
    /** Re-sorts the open new-mix draft to the suggested or the Default sheet order. Renames and added entries survive; drags do not. */
    fun setSmartMode(mode: MixOrderMode) {
        val context = smart ?: return
        val current = draft ?: return
        if (applying || current.newFile == null || mode == context.mode) return
        val order = if (mode == MixOrderMode.SMART) context.suggestedOrder else context.defaultOrder
        draft = MixDrafts.reorderSheets(current, orderedPgms(context.sheets, order))
        smart = context.copy(mode = mode)
    }
    /**
     * Blue SCM pause: stage a NEW mix of the unlocked entries in their current order, keeping each entry's
     * parameters and count for the operator to review before Apply. Completion comes from tablet status only;
     * the source mix is left unchanged as history and no sheet status is written.
     */
    fun startRemaining(group: MixGroup, locked: List<String>): Boolean {
        if (applying || draft != null || group.frz != null) return false
        val mix = group.mix ?: return false
        val locks = locked.map(::key).toSet()
        val keep = mix.entries.filterNot { key(it.pgm) in locks }
        if (keep.isEmpty() || keep.size == mix.entries.size) return false
        val taken = catalog?.files.orEmpty().filter { it.kind != MixFileKind.REVISION }.map { it.file.substringBeforeLast('.').lowercase(Locale.ROOT) }.toSet()
        val base = group.title.removeSuffix("Mix").trim().ifBlank { group.title } + "Remaining"
        val stem = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { key(it) !in taken }
        val file = "$stem.mix"
        val entries = keep.mapIndexed { index, entry -> DraftEntry(index + 1L, entry, null, entry.quantity, null, LinkedHashMap(entry.parameters)) }
        draft = MixDraft(key(file), null, null, entries, file, emptyList(), 1, completedPgms = locks, baselineEntries = emptyList())
        smart = null
        draftFolderRevision = catalog?.folderRevision; selectedTab = draft?.groupKey
        conflict = false; applyResult = null; savedInterrupted = false; mixSaveBlockedReason = null
        return true
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
                savedInterrupted = submitted.isInterrupted; draft = null; smart = null; draftFolderRevision = null; conflict = false
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
        draft = preview; smart = null
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
                    draft = null; smart = null; draftFolderRevision = null; conflict = false; savedInterrupted = false
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
                secondPass = mergeWanted(SecondPassDrafts.from(history, pgms, punloadFixed), secondPass)
                secondMessage = historyMessage(if (!history.records(pgms)) NO_RECORDED_STATE else null)
            } else secondMessage = historyMessage(HISTORY_FAILURE)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { secondMessage = historyMessage(HISTORY_FAILURE) }
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
        val earlierVerification = pendingHistoryVerification
        val previouslyRecorded = this.history?.files?.keys.orEmpty()
        pendingHistoryVerification = earlierVerification + submitted.wanted.filter { (pgm, state) -> state != submitted.current.pgmValue(pgm) }.keys
        refusedSecondMessage = null
        try {
            val outcome = submit(submitted)
            if (outcome is SecondPassOutcome.Failed && !outcome.partial && outcome.mutation == SecondPassMutation.REFUSED_BEFORE_MUTATION) {
                pendingHistoryVerification = earlierVerification
                refusedSecondMessage = outcome.message
            }
            val history = load()
            secondHistoryConfirmed = history?.records(previouslyRecorded + pendingHistoryVerification) == true
            if (secondHistoryConfirmed && history != null) {
                this.history = history
                pendingHistoryVerification = emptySet()
                val fresh = SecondPassDrafts.from(history, submitted.wanted.keys.toList(), punloadFixed)
                secondPass = if (outcome == SecondPassOutcome.Done) fresh else mergeWanted(fresh, submitted)
            }
            secondMessage = when (outcome) {
                SecondPassOutcome.Done -> if (secondHistoryConfirmed) "Second-pass changes applied" else "Changes submitted. $HISTORY_FAILURE"
                is SecondPassOutcome.Failed -> outcome.message + when {
                    !secondHistoryConfirmed -> " $HISTORY_FAILURE"
                    history?.records(submitted.wanted.keys) != true -> " $NO_RECORDED_STATE"
                    else -> ""
                }
            }
        } catch (cancelled: CancellationException) { secondMessage = historyMessage(HISTORY_FAILURE); throw cancelled }
        catch (_: Exception) { secondHistoryConfirmed = false; secondMessage = historyMessage("Couldn't confirm second-pass changes. Reload history before applying again.") }
        finally { secondApplying = false }
    }
    private fun historyMessage(note: String?): String? = listOfNotNull(refusedSecondMessage, note).takeIf { it.isNotEmpty() }?.joinToString(" ")
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
internal fun rememberMixCatalogStates(machineId: String, job: String, sources: Map<String, StateFlow<CatalogState>>): Map<String, CatalogState> {
    // Keyed by machine too: a machine switch must never render the other machine's state for a same-named material.
    val states = remember(machineId, job) { mutableStateMapOf<String, CatalogState>() }
    LaunchedEffect(states, sources.keys.toSet()) { states.keys.toList().filterNot { it in sources }.forEach(states::remove) }
    sources.forEach { (name, source) -> key(machineId, job, name) {
        LaunchedEffect(source, states) { source.collect { states[name] = it } }
    } }
    return states
}
