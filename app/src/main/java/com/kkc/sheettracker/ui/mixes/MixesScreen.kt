package com.kkc.sheettracker.ui.mixes

import android.content.Context
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kkc.sheettracker.KKCApplication
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.cnc.*
import com.kkc.sheettracker.data.mixfiles.*
import com.kkc.sheettracker.data.mixservice.MixServiceClient
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.ui.components.kkcCardDepth
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.theme.LocalKKCStatusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class MixCardAction { RENAME, SPLIT, DELETE, REVISIONS, COMPARE }

@Composable
fun MixesScreen(scanCoordinator: ScanCoordinator, progressStore: ProgressStore, jobFolderName: String,
    initialMaterial: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as KKCApplication
    val scan by scanCoordinator.state.collectAsState()
    val progressVersion by progressStore.progressVersion.collectAsState()
    val machine = CncMachines.scm
    val service = remember(machine) { MixServiceClient(machine.baseUrl) }
    val api = remember(machine) { MixFilesClient(machine) }
    val scope = rememberCoroutineScope()
    val engine = remember(scan.snapshot.basePath) { UnifiedMetadataEngineRegistry.getOrCreate(File(scan.snapshot.basePath), com.kkc.sheettracker.BuildConfig.DEBUG) }
    val job by produceState<com.kkc.sheettracker.data.models.Job?>(null, engine, jobFolderName, scan.snapshot.generation) {
        value = withContext(Dispatchers.IO) { engine.getCncSnapshot(jobFolderName)?.job }
    }
    val materials = job?.materials.orEmpty()
    val stores = remember(materials.map { it.materialName }, jobFolderName, machine) {
        materials.associate { it.materialName to app.mixFilesStores.store(machine, jobFolderName, it.materialName) }
    }
    ForegroundMixLeases(stores.values.toList(), app.machineStatusStore)
    val machineStatus by app.machineStatusStore.status.collectAsState()
    val catalogStates = rememberMixCatalogStates(jobFolderName, stores.mapValues { it.value.state })
    var selectedMaterial by remember(jobFolderName) { mutableStateOf(initialMaterial) }
    LaunchedEffect(materials) { selectedMaterial = selectAvailableMaterial(selectedMaterial, materials.map { it.materialName }) }
    val material = materials.firstOrNull { it.materialName == selectedMaterial }
    val session = remember(jobFolderName, selectedMaterial) { MixesSession() }
    var inventory by remember(selectedMaterial) { mutableStateOf<List<String>>(emptyList()) }
    var revertSupported by remember(machine) { mutableStateOf(false) }
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingLeaveText by remember { mutableStateOf("Mix and second-pass changes haven't been applied.") }
    var editRow by remember(session) { mutableStateOf<RowModel?>(null) }
    var editEntryId by remember(session) { mutableStateOf<Long?>(null) }
    var splitMode by remember(session) { mutableStateOf(false) }
    var splitIds by remember(session) { mutableStateOf<Set<Long>>(emptySet()) }
    var splitName by remember(session) { mutableStateOf(false) }
    var actionTarget by remember(session) { mutableStateOf<MixFileRow?>(null) }
    var cardDialog by remember(session) { mutableStateOf<MixCardAction?>(null) }
    var namingNew by remember(session) { mutableStateOf(false) }
    var revisionsKey by remember(session) { mutableStateOf<String?>(null) }
    var compareFiles by remember(session) { mutableStateOf(false) }
    var deleteRevision by remember(session) { mutableStateOf<MixFileRow?>(null) }
    var rawRestore by remember(session) { mutableStateOf<Pair<MixFileRow, MixFileRow?>?>(null) }
    var historyRow by remember(session) { mutableStateOf<List<String>>(emptyList()) }
    var historyPgm by remember(session) { mutableStateOf<String?>(null) }
    var mismatches by remember(session) { mutableStateOf<List<FrzMismatch>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(session.rebaseMessage) { session.rebaseMessage?.let { snackbar.showSnackbar(it); session.acknowledgeRebase() } }
    fun latestCatalog() = stores[selectedMaterial]?.state?.value.catalogSnapshot() ?: session.gate.deferredCatalog ?: session.catalog
    fun leave(action: () -> Unit) {
        if (session.saving) return
        val abandonAndLeave = { if (session.abandonAll(latestCatalog())) action() }
        pendingLeaveText = "Mix and second-pass changes haven't been applied."
        if (session.dirty) pendingLeave = abandonAndLeave else abandonAndLeave()
    }
    fun leaveMix(action: () -> Unit) {
        if (session.applying) return
        val abandon = { if (session.discardMix(latestCatalog())) { splitMode = false; splitIds = emptySet(); action() } }
        pendingLeaveText = "Mix changes haven't been applied. Second-pass changes stay with this material."
        if (session.draft != null) pendingLeave = abandon else abandon()
    }
    BackHandler { leave(onBack) }
    val latest = catalogStates[selectedMaterial].catalogSnapshot()
    LaunchedEffect(session, latest) { latest?.let(session::onCatalog) }
    LaunchedEffect(machine) { revertSupported = "pgm-edits-revert" in api.features() }
    fun historyPgms() = (inventory + material?.let { sheetPages(it.metadata?.pages.orEmpty()).flatMap { page -> page.pgmFiles } }.orEmpty()).distinct()
    suspend fun retryHistory(name: String) { session.reloadHistory(historyPgms()) { service.listPgmEdits(jobFolderName, name) } }
    LaunchedEffect(session, material?.materialName) {
        val name = material?.materialName ?: return@LaunchedEffect
        try { inventory = service.listPgms(jobFolderName, name).map { it.name } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Metadata PGMs still allow a separate history recovery. */ }
        retryHistory(name)
    }
    val materialInputs = materials.map { m ->
        val state = catalogStates[m.materialName] ?: CatalogState.Loading
        val visible = if (m == material && session.catalog != null) state.withCatalog(session.catalog!!) else state
        @Suppress("UNUSED_VARIABLE") val version = progressVersion
        MaterialMixesInput(machine, m, visible, statusByPage = m.metadata?.pages.orEmpty().associate {
            it.pageNumber to progressStore.getSheetStatus(jobFolderName, m.pdfFilename, it.pageNumber, m.fileFingerprint)
        }, hasPgms = sheetPages(m.metadata?.pages.orEmpty()).isNotEmpty() || (m == material && inventory.isNotEmpty()))
    }
    val selected = materialInputs.firstOrNull { it.material == material }
    val presentation = buildMixesUi(MixesUiInput(materialInputs, selectedMaterial, selectedTab = session.selectedTab, draft = session.draft,
        secondPassDraft = session.secondPass, draftFolderRevision = session.draftFolderRevision, conflict = session.conflict,
        applyResult = session.applyResult, savedInterrupted = session.savedInterrupted, applying = session.applying,
        secondPassApplying = session.secondApplying, machineStatus = machineStatus))
    val ui = presentation.copy(secondPassApplyEnabled = presentation.secondPassApplyEnabled && session.secondHistoryConfirmed && !session.historyLoading)
    val group = session.catalog?.let(::groupMixFiles)?.firstOrNull { it.key == ui.selectedTab }
    val catalogWritable = catalogStates[selectedMaterial] is CatalogState.Live && session.applyResult !is MixFilesResult.ServiceOutdated
    // Read ProgressStore at the action boundary, rather than a status map from the last composition.
    fun locks(): List<String> = material?.let { m -> sheetPages(m.metadata?.pages.orEmpty()).filter {
        isLockedStatus(progressStore.getSheetStatus(jobFolderName, m.pdfFilename, it.pageNumber, m.fileFingerprint))
    }.flatMap { it.pgmFiles }.distinct() }.orEmpty()
    fun edit(change: (MixDraft) -> MixDraft) = session.edit(group, locks(), change)
    fun publish(result: MixFilesResult<MixFilesCatalog>?, name: String) {
        if (result is MixFilesResult.Ok) stores[name]?.publish(result.value)
        else if (result is MixFilesResult.Offline || (result is MixFilesResult.Rejected && result.error.code == "revision_conflict"))
            scope.launch {
                stores[name]?.refreshNow()
                if (session.draft == null) session.acknowledgeRefresh(stores[name]?.state?.value is CatalogState.Live)
            }
        if (result is MixFilesResult.Rejected && result.error.code == "frz_completion_mismatch") mismatches = result.error.mismatches
    }
    fun applyMix(resolutions: Map<String, String> = emptyMap()) {
        val name = selectedMaterial ?: return
        if (!ui.card.let { it?.applyEnabled == true } || session.applying) return
        scope.launch {
            val result = session.saveMix(::locks) { draft ->
                when (val write = buildWrite(draft, group, draft.completedPgms.toList(), resolutions)) {
                    is MixWrite.Write -> api.write(jobFolderName, name, write.file, write.expectedRevision, write.entries, write.completion)
                    is MixWrite.Frz -> api.frz(jobFolderName, name, write.file, write.expectedRevision, write.completion, write.pending)
                    is MixWrite.Split -> api.split(jobFolderName, name, write.file, write.expectedRevision, write.entries, write.newMixes, write.completion)
                }
            }
            publish(result, name)
            if (result == null) session.mixSaveBlockedReason?.let { snackbar.showSnackbar(it) }
            if (result is MixFilesResult.Ok) { splitMode = false; splitIds = emptySet() }
        }
    }
    fun mutate(submit: suspend (String) -> MixFilesResult<MixFilesCatalog>) {
        val name = selectedMaterial ?: return
        if (session.applying || !catalogWritable) return
        scope.launch { publish(session.mutateCatalog { submit(name) }, name) }
    }
    fun openRevisions(key: String, compare: Boolean = false) { revisionsKey = key; compareFiles = compare }
    val takenNames = session.catalog?.files.orEmpty().filter { it.kind != MixFileKind.REVISION }.map { it.file } + session.draft?.splitOff.orEmpty().map { it.file }
    val revisionGroups = session.catalog?.let(::groupMixFiles).orEmpty()
    val eligibleSplitIds = (ui.groups?.remaining.orEmpty() + ui.groups?.unmatched.orEmpty()).filter { it.canSplit }.flatMap { it.draftEntryIds }.toSet()
    val validSplitIds = splitIds.intersect(eligibleSplitIds)
    val conflictReason = "This mix changed on the CNC. Close the dialog to resolve the conflict."
    MixesWorkspace(job?.let { "${it.jobNumber} · ${it.jobName}" } ?: jobFolderName, selectedMaterial, selected?.pages?.size ?: 0,
        ui, session.secondPass, revertSupported, inventory, material?.let { File(File(scan.snapshot.basePath, "$jobFolderName/CNC"), it.pdfFilename) },
        onBack = { leave(onBack) }, onMaterial = { name -> leave { selectedMaterial = name } },
        onTab = { key -> leaveMix { session.selectedTab = key } },
        onNew = { leaveMix { session.startNew(selectedMaterial ?: "Material", selected?.pages.orEmpty(), locks()) } },
        onDiscard = { session.discardMix(latestCatalog()) },
        onApply = { applyMix() }, onRefresh = { selectedMaterial?.let { name -> scope.launch {
            stores[name]?.refreshNow()
            session.acknowledgeRefresh(stores[name]?.state?.value is CatalogState.Live)
            retryHistory(name)
        } } },
        onLoadTheirs = { session.discardMix(latestCatalog()) }, onReapply = { session.reapply(locks()) },
        onDragStart = session::beginDrag, onDrop = { session.drop(group, locks(), it) },
        onAdd = { page -> edit { d -> page.pgmFiles.fold(d) { next, pgm -> MixDrafts.add(next, pgm) } } },
        onSecondPass = { pgms, field, on -> session.updateSecond(pgms, field, on, locks()) },
        onSecondDiscard = { session.discardSecond() },
        onSecondApply = {
            val name = selectedMaterial
            if (name != null && ui.secondPassApplyEnabled && !session.secondApplying) scope.launch {
                session.saveSecond(::locks, { submitted ->
                    val deviceId = context.getSharedPreferences("kkc_tracker", Context.MODE_PRIVATE).getString("tablet_id", null)
                        ?.takeIf { it.isNotBlank() } ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    val runner = SecondPassRunner(service, api::submitPgmEditRevert, secondPassRequestIds(deviceId))
                    runner.apply(jobFolderName, name, planSecondPass(submitted)) { session.secondMessage = it }
                }, { service.listPgmEdits(jobFolderName, name) })
            }
        }, secondMessage = session.secondMessage,
        recoveryEnabled = session.gate.deferredCatalog != null,
        reapplyEnabled = session.draft?.newFile == null && session.gate.deferredCatalog?.let { c -> groupMixFiles(c).any { it.key == session.draft?.groupKey } } == true,
        mixEditingEnabled = !session.applying, secondEditingEnabled = catalogWritable && !session.secondApplying && session.secondHistoryConfirmed && !session.historyLoading,
        secondDiscardEnabled = !session.secondApplying, navigationEnabled = !session.saving,
        historyRetryVisible = !session.secondHistoryConfirmed, historyLoading = session.historyLoading,
        onHistoryRetry = { selectedMaterial?.let { name -> scope.launch { retryHistory(name) } } },
        snackbar = snackbar, splitMode = splitMode, splitIds = validSplitIds,
        catalogActionsEnabled = catalogWritable && !session.applying && group != null,
        newMix = session.draft?.newFile != null,
        onSplitSelection = { row -> splitIds = if (row.draftEntryIds.all { it in splitIds }) splitIds - row.draftEntryIds.toSet() else splitIds + row.draftEntryIds },
        onSplitConfirm = { splitIds = validSplitIds; splitName = validSplitIds.isNotEmpty() }, onSplitCancel = { splitMode = false; splitIds = emptySet() },
        companionMismatch = group?.primary?.companionMismatch == true || group?.mix?.companionMismatch == true,
        revisionGroups = revisionGroups, onMaterialRevisions = { openRevisions(it) },
        onCardAction = { action -> when (action) {
            MixCardAction.REVISIONS -> group?.let { openRevisions(it.key) }
            MixCardAction.COMPARE -> group?.let { openRevisions(it.key, true) }
            MixCardAction.SPLIT -> { edit { it }; splitMode = true; splitIds = emptySet() }
            MixCardAction.RENAME -> if (session.draft?.newFile != null) namingNew = true else leaveMix {
                actionTarget = session.catalog?.let(::groupMixFiles)?.firstOrNull { it.key == ui.selectedTab }?.primary
                cardDialog = MixCardAction.RENAME.takeIf { actionTarget != null }
            }
            MixCardAction.DELETE -> leaveMix {
                actionTarget = session.catalog?.let(::groupMixFiles)?.firstOrNull { it.key == ui.selectedTab }?.primary
                cardDialog = MixCardAction.DELETE.takeIf { actionTarget != null }
            }
        } },
        onRowAction = { row, action -> when (action) {
            MixRowAction.REMOVE -> if (row.canRemove) edit { d -> row.draftEntryIds.fold(d) { next, id -> MixDrafts.remove(next, id) } }
            MixRowAction.EDIT -> if (row.canEditCounts) { edit { it }; editRow = row; editEntryId = row.entries.firstOrNull()?.id }
            MixRowAction.SPLIT -> if (row.canSplit) { edit { it }; splitIds = row.draftEntryIds.toSet(); splitName = true }
            MixRowAction.HISTORY -> { historyRow = row.entries.map { it.source?.pgm ?: it.newPgm.orEmpty() }; historyPgm = historyRow.firstOrNull() }
        } })
    editRow?.let { row ->
        val entries = row.entries.map { old -> session.draft?.entries?.firstOrNull { it.id == old.id } ?: old }
        val entry = entries.firstOrNull { it.id == editEntryId } ?: entries.first()
        val entryLocked = locks().any { it.equals(entry.source?.pgm ?: entry.newPgm, true) }
        EntryEditSheet(entry, group?.isInterrupted == true, onSave = { updated ->
            edit { d ->
                val counted = MixDrafts.setQuantity(d, updated.id, updated.quantity, updated.remaining)
                if (d.isInterrupted) counted else MixDrafts.setParameters(counted, updated.id, LinkedHashMap(updated.parameters.orEmpty()))
            }; editRow = null
        }, onDismiss = { editRow = null }, enabled = !session.applying && !session.conflict && !entryLocked,
            siblings = entries, onSelect = { editEntryId = it.id }, dismissEnabled = !session.applying,
            blockedReason = if (session.conflict) conflictReason else if (entryLocked) "This sheet is now completed and locked." else null)
    }
    if (splitName) SplitNameDialog(nextMixName(selectedMaterial ?: "Material", takenNames), onConfirm = { file ->
        edit { d -> MixDrafts.splitOff(d, splitIds, file) }; splitName = false; splitMode = false; splitIds = emptySet()
    }, onDismiss = { splitName = false }, taken = takenNames,
        enabled = !session.applying && !session.conflict && validSplitIds == splitIds && splitIds.isNotEmpty(), dismissEnabled = !session.applying,
        blockedReason = if (session.conflict) conflictReason else if (validSplitIds != splitIds) "A selected sheet is now locked. Close and select pending sheets." else null)
    if (namingNew) session.draft?.newFile?.let { file -> RenameDialog(file,
        onConfirm = { session.renameNew(it); namingNew = false }, onDismiss = { namingNew = false },
        taken = takenNames, enabled = !session.applying && !session.conflict, dismissEnabled = !session.applying,
        blockedReason = conflictReason.takeIf { session.conflict }) }
    actionTarget?.let { target -> when (cardDialog) {
        MixCardAction.RENAME -> RenameDialog(target.file, onConfirm = { file ->
            cardDialog = null
            mutate { name -> api.rename(jobFolderName, name, target.file, file, target.revision) }
        }, onDismiss = { cardDialog = null }, extension = target.file.substringAfterLast('.'), taken = takenNames, enabled = !session.applying)
        MixCardAction.DELETE -> DeleteConfirm(target.file, onConfirm = {
            cardDialog = null; mutate { name -> api.delete(jobFolderName, name, target.file, target.revision) }
        }, onDismiss = { cardDialog = null }, enabled = !session.applying)
        else -> Unit
    } }
    revisionsKey?.let { key -> revisionGroups.firstOrNull { it.key == key }?.let { revisions ->
        BoxWithConstraints { RevisionsPanel(revisions, onView = {}, onRestore = { revision -> leaveMix {
            val fresh = session.catalog?.let(::groupMixFiles)?.firstOrNull { it.key == key }
            val target = fresh?.mix
            if (target == null || target.error != null) rawRestore = revision to target
            else { session.stageRevision(fresh, revision, locks()); revisionsKey = null }
        } }, onDeleteRevision = { revision -> leaveMix { deleteRevision = revision } },
            onDismiss = { revisionsKey = null }, rail = useRail(maxWidth.value.toInt()), compare = compareFiles,
            mutationsEnabled = catalogWritable && !session.applying) }
    } }
    deleteRevision?.let { revision -> DeleteConfirm(revision.file, onConfirm = {
        deleteRevision = null; mutate { name -> api.deleteRevision(jobFolderName, name, revision.file, revision.revision) }
    }, onDismiss = { deleteRevision = null }, permanent = true, enabled = !session.applying) }
    rawRestore?.let { (revision, target) -> AlertDialog(onDismissRequest = { rawRestore = null }, title = { Text("Restore mix file?") },
        text = { Text(if (target == null) "Recover ${revision.revisionOf} exactly as saved in ${revision.file}." else "Replace unreadable ${target.file} with ${revision.file}.") },
        confirmButton = { TextButton(onClick = {
            rawRestore = null
            val companion = session.catalog?.files?.firstOrNull { it.file.equals(target?.companion, true) }
            val completion = if (companion != null) Completion(locks(), emptyMap(), companion.revision) else null
            mutate { name -> api.restore(jobFolderName, name, revision.file, target?.file ?: requireNotNull(revision.revisionOf), target?.revision, completion) }
        }, enabled = !session.applying && (target != null || revision.revisionOf?.endsWith(".mix", true) == true)) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { rawRestore = null }, enabled = !session.applying) { Text("Cancel") } }) }
    historyPgm?.let { pgm ->
        val record = session.history?.files?.entries?.firstOrNull { it.key.equals(pgm, true) }?.value
        val locked = locks().any { it.equals(pgm, true) }
        SecondPassHistorySheet(pgm, record?.history.orEmpty(), revertSupported && !locked && !session.secondApplying && session.secondHistoryConfirmed &&
            session.secondPass?.current?.get(pgm)?.let { it.secondPass || it.removePUnload } == true,
            onRevert = { SecondPassField.entries.forEach { session.updateSecond(listOf(pgm), it, false, locks()) }; historyPgm = null },
            onDismiss = { historyPgm = null }, current = record?.current, revertSupported = revertSupported,
            pgms = historyRow, onSelect = { historyPgm = it })
    }
    mismatches?.let { list -> FrzMismatchDialog(list, statusOf = { pgm ->
        val page = selected?.pages?.firstOrNull { p -> p.pgmFiles.any { it.equals(pgm, true) } }
        page?.let { selected.statusOf(it.pageNumber).toString().replace('_', ' ') } ?: if (locks().any { it.equals(pgm, true) }) "Complete" else "Not complete"
    }, onResolve = { choices -> mismatches = null; applyMix(choices) }, onDismiss = { mismatches = null },
        enabled = !session.applying && !session.conflict, dismissEnabled = !session.applying, blockedReason = conflictReason.takeIf { session.conflict }) }
    pendingLeave?.let { action -> AlertDialog(onDismissRequest = { pendingLeave = null }, title = { Text("Discard unsaved changes?") },
        text = { Text(pendingLeaveText) }, confirmButton = {
            TextButton(onClick = { pendingLeave = null; action() }) { Text("Discard") }
        }, dismissButton = { TextButton(onClick = { pendingLeave = null }) { Text("Keep editing") } }) }
}
/** STARTED leases also release on backgrounding, even when the composition remains retained. */
@Composable
private fun ForegroundMixLeases(stores: List<MixFilesStore>, machineStore: MachineStatusStore) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, stores, machineStore) {
        var leases = emptyList<AutoCloseable>()
        fun sync() {
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (leases.isEmpty()) leases = stores.map { it.acquire() } + machineStore.acquire()
            } else { leases.forEach { it.close() }; leases = emptyList() }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        owner.lifecycle.addObserver(observer); sync()
        onDispose { owner.lifecycle.removeObserver(observer); leases.forEach { it.close() } }
    }
}

/** Move every A/Z occurrence together, retaining lock positions through MixDrafts.move. */
internal fun moveRowBlock(draft: MixDraft, ids: List<Long>, targetIds: List<Long>): MixDraft {
    if (ids.isEmpty() || targetIds.isEmpty() || ids.any { it in targetIds }) return draft
    val all = draft.entries.map { it.id }
    val block = all.filter { it in ids }
    val target = all.indexOfFirst { it in targetIds }
    val from = all.indexOfFirst { it in ids }
    if (target < 0 || from < 0) return draft
    val order = all.filterNot { it in ids }.toMutableList()
    val at = if (from < target) order.indexOfLast { it in targetIds } + 1 else order.indexOfFirst { it in targetIds }
    order.addAll(at, block)
    return order.foldIndexed(draft) { index, d, id -> MixDrafts.move(d, id, index) }
}

internal fun CatalogState?.catalogSnapshot(): MixFilesCatalog? = when (this) {
    is CatalogState.Live -> catalog; is CatalogState.Offline -> cached; is CatalogState.Outdated -> cached; else -> null
}
private fun CatalogState.withCatalog(catalog: MixFilesCatalog): CatalogState = when (this) {
    is CatalogState.Offline -> CatalogState.Offline(catalog); is CatalogState.Outdated -> CatalogState.Outdated(catalog); else -> CatalogState.Live(catalog)
}

/** Pure workspace renderer shared by the route and device fixtures; Task 8 supplies dialog actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MixesWorkspace(title: String, material: String?, sheetCount: Int, ui: MixesUiModel,
    secondPass: SecondPassDraft?, revertSupported: Boolean, inventory: List<String>, pdf: File?,
    onBack: () -> Unit, onMaterial: (String) -> Unit, onTab: (String) -> Unit, onNew: () -> Unit,
    onDiscard: () -> Unit, onApply: () -> Unit, onRefresh: () -> Unit, onLoadTheirs: () -> Unit, onReapply: () -> Unit,
    onDragStart: () -> Unit, onDrop: (List<Pair<RowModel, RowModel>>) -> Unit, onAdd: (SheetPage) -> Unit,
    onSecondPass: (List<String>, SecondPassField, Boolean) -> Unit, onSecondDiscard: () -> Unit, onSecondApply: () -> Unit,
    secondMessage: String?, onRowAction: ((RowModel, MixRowAction) -> Unit)? = null,
    onCardAction: ((MixCardAction) -> Unit)? = null, recoveryEnabled: Boolean = true, reapplyEnabled: Boolean = true,
    mixEditingEnabled: Boolean = true, secondEditingEnabled: Boolean = true, secondDiscardEnabled: Boolean = true,
    navigationEnabled: Boolean = true, historyRetryVisible: Boolean = false, historyLoading: Boolean = false,
    onHistoryRetry: () -> Unit = {}, snackbar: SnackbarHostState? = null,
    splitMode: Boolean = false, splitIds: Set<Long> = emptySet(), onSplitSelection: (RowModel) -> Unit = {},
    onSplitConfirm: () -> Unit = {}, onSplitCancel: () -> Unit = {}, companionMismatch: Boolean = false,
    revisionGroups: List<MixGroup> = emptyList(), onMaterialRevisions: (String) -> Unit = {},
    catalogActionsEnabled: Boolean = !ui.readOnly && mixEditingEnabled, newMix: Boolean = false) {
    val status = LocalKKCStatusColors.current
    Scaffold(snackbarHost = { snackbar?.let { SnackbarHost(it) } }, topBar = {
        TopAppBar(title = { Column { Text(title, style = MaterialTheme.typography.titleMedium); Text("Mixes", style = MaterialTheme.typography.labelMedium) } },
            navigationIcon = { IconButton(onClick = onBack, enabled = navigationEnabled) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                Text(when (val machine = ui.machineStatus) {
                    MachineStatus.Unavailable -> "CNC unavailable"
                    is MachineStatus.Live -> if (machine.producing == true) "CNC online · cutting" else "CNC online · idle"
                }, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge,
                    color = if (ui.machineStatus is MachineStatus.Live) status.complete else MaterialTheme.colorScheme.onSurfaceVariant)
            })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding)) {
            val widthDp = maxWidth.value.toInt()
            val rail = useRail(widthDp)
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (rail) MixesMaterialNav(ui.nav, true, widthDp, onMaterial, Modifier.width(220.dp).padding(top = 8.dp, bottom = 160.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!rail) MixesMaterialNav(ui.nav, false, widthDp, onMaterial, Modifier.padding(top = 8.dp))
                    if (material == null) {
                        Text("No job materials available. Refresh the job and try again.", Modifier.padding(16.dp))
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(material, style = MaterialTheme.typography.titleLarge)
                                Text("$sheetCount sheets · ${ui.tabs.size} ${if (ui.tabs.size == 1) "mix" else "mixes"}", style = MaterialTheme.typography.bodySmall)
                            }
                            val pgms = secondPass?.wanted?.keys?.toList() ?: inventory
                            SecondPassMenu(secondPass, pgms, secondEditingEnabled, revertSupported, { field, on -> onSecondPass(pgms, field, on) })
                            var revisionsMenu by remember { mutableStateOf(false) }
                            val withRevisions = revisionGroups.filter { it.mixRevisions.isNotEmpty() || it.frzRevisions.isNotEmpty() }
                            if (withRevisions.isNotEmpty()) Box {
                                TextButton(onClick = { revisionsMenu = true }) { Text("Revisions / Deleted mixes") }
                                DropdownMenu(revisionsMenu, { revisionsMenu = false }) { withRevisions.forEach { revisions ->
                                    DropdownMenuItem(text = { Text(revisions.title + if (revisions.mix == null && revisions.frz == null) " · Deleted" else "") },
                                        onClick = { revisionsMenu = false; onMaterialRevisions(revisions.key) })
                                } }
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ui.tabs.forEach { tab -> FilterChip(selected = tab.key == ui.selectedTab, enabled = navigationEnabled, onClick = { onTab(tab.key) }, label = {
                                Text(tab.title + when { tab.error -> " · Error"; tab.interrupted -> " · Interrupted"; else -> "" } + if (tab.companionMismatch) " · Files differ" else "")
                            }) }
                            TextButton(onClick = onNew, enabled = !ui.readOnly && navigationEnabled) { Text("+ New mix") }
                        }
                        ui.banner?.let { banner ->
                            GroupCard {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text(when (banner) {
                                        Banner.ChangedOnCnc -> "This mix changed on the CNC"
                                        Banner.SaveUnconfirmed -> "Couldn't confirm the save. Refresh to check the CNC before trying again."
                                        Banner.ServiceOutdated -> "The CNC mix service needs updating"
                                        Banner.Offline -> "CNC offline · showing the last saved catalog"
                                        is Banner.Message -> banner.text
                                    }, color = if (banner == Banner.ChangedOnCnc || banner == Banner.SaveUnconfirmed) status.skip else MaterialTheme.colorScheme.onSurface)
                                    Row {
                                        TextButton(onClick = onRefresh) { Text("Refresh") }
                                        if (banner == Banner.ChangedOnCnc) {
                                            ConflictBanner(onLoadTheirs, onReapply, recoveryEnabled && mixEditingEnabled, recoveryEnabled && reapplyEnabled && mixEditingEnabled)
                                        }
                                    }
                                }
                            }
                        }
                        ui.card?.let { card ->
                            GroupCard {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    LinearProgressIndicator(progress = { card.progress }, modifier = Modifier.fillMaxWidth(), color = status.complete)
                                    Text(card.summary, style = MaterialTheme.typography.bodySmall)
                                    if (companionMismatch) Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("MIX and FRZ entries differ", color = status.skip)
                                        TextButton(onClick = { onCardAction?.invoke(MixCardAction.COMPARE) }, enabled = onCardAction != null) { Text("Compare") }
                                    }
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                        if (rail) {
                                            TextButton(onClick = { onCardAction?.invoke(MixCardAction.RENAME) }, enabled = onCardAction != null && (catalogActionsEnabled || newMix && mixEditingEnabled && !ui.readOnly)) { Text("Rename") }
                                            TextButton(onClick = { onCardAction?.invoke(MixCardAction.SPLIT) }, enabled = onCardAction != null && mixEditingEnabled && card.splitEnabled) { Text("Split") }
                                            TextButton(onClick = { onCardAction?.invoke(MixCardAction.DELETE) }, enabled = onCardAction != null && catalogActionsEnabled) { Text("Delete") }
                                            TextButton(onClick = { onCardAction?.invoke(MixCardAction.REVISIONS) }, enabled = onCardAction != null && !newMix) { Text("Revisions") }
                                        } else Box {
                                            var menu by remember { mutableStateOf(false) }
                                            TextButton(onClick = { menu = true }, enabled = onCardAction != null) { Text("More") }
                                            DropdownMenu(menu, { menu = false }) {
                                                listOf(MixCardAction.RENAME to "Rename", MixCardAction.SPLIT to "Split", MixCardAction.DELETE to "Delete", MixCardAction.REVISIONS to "Revisions").forEach { (action, label) ->
                                                    DropdownMenuItem(text = { Text(label) }, enabled = when (action) {
                                                        MixCardAction.REVISIONS -> !newMix
                                                        MixCardAction.SPLIT -> mixEditingEnabled && card.splitEnabled
                                                        MixCardAction.RENAME -> catalogActionsEnabled || newMix && mixEditingEnabled && !ui.readOnly
                                                        else -> catalogActionsEnabled
                                                    }, onClick = { menu = false; onCardAction?.invoke(action) })
                                                }
                                            }
                                        }
                                        if (card.applyVisible) {
                                            TextButton(onClick = onDiscard, enabled = mixEditingEnabled) { Text("Discard") }
                                            Button(onClick = onApply, enabled = card.applyEnabled, modifier = Modifier.testTag("mixes-apply")) { Text(card.applyLabel) }
                                        }
                                    }
                                    card.applyBlockedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = status.skip) }
                                }
                            }
                        }
                        if (ui.secondPassApply != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = onSecondApply, enabled = ui.secondPassApplyEnabled) { Text(ui.secondPassApply) }
                            TextButton(onClick = onSecondDiscard, enabled = secondDiscardEnabled) { Text("Discard 2nd pass") }
                        }
                        secondMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (historyRetryVisible) TextButton(onClick = onHistoryRetry, enabled = !historyLoading && !ui.readOnly) {
                            Text(if (historyLoading) "Loading second-pass history" else "Retry second-pass history")
                        }
                        val groups = ui.groups
                        if (groups != null) {
                            if (splitMode) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${splitIds.size} entries selected")
                                TextButton(onClick = onSplitConfirm, enabled = splitIds.isNotEmpty() && mixEditingEnabled && ui.banner != Banner.ChangedOnCnc) { Text("Move to new mix…") }
                                TextButton(onClick = onSplitCancel, enabled = mixEditingEnabled) { Text("Cancel split") }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth().kkcCardDepth(RoundedCornerShape(12.dp), elevation = 2.dp).background(MaterialTheme.colorScheme.surface)) {
                                MixesSheetList(groups, secondPass, revertSupported, !ui.readOnly && mixEditingEnabled && ui.banner != Banner.ChangedOnCnc,
                                    pdf, onDragStart, onDrop, onAdd, onSecondPass, onRowAction, Modifier.fillMaxSize(),
                                    secondPassEnabled = secondEditingEnabled,
                                    splitMode = splitMode, splitIds = splitIds, onSplitSelection = onSplitSelection)
                            }
                        } else {
                            Text(if (ui.readOnly) "Waiting for the CNC catalog. Refresh to reconnect." else "No mix yet. Create a new mix from this material's sheets.", Modifier.padding(16.dp))
                            TextButton(onClick = onRefresh) { Text("Refresh") }
                        }
                    }
                }
            }
        }
    }
}
