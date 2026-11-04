package com.kkc.sheettracker.ui.mixes

import android.content.Context
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.kkc.sheettracker.data.mixservice.*
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.ui.components.kkcCardDepth
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.theme.LocalKKCStatusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

internal enum class MixCardAction { RENAME, SPLIT, DELETE, REVISIONS, COMPARE }

/** One entry of the material header's overflow menu ("Move to…", move recovery). */
internal data class MaterialMenuAction(val label: String, val enabled: Boolean, val onClick: () -> Unit)

private data class PendingMove(val plan: MovePlan.Prepare, val material: String, val sourceRevision: String, val preview: TransferPreview)

@Composable
fun MixesScreen(scanCoordinator: ScanCoordinator, progressStore: ProgressStore, jobFolderName: String,
    initialMaterial: String?, onBack: () -> Unit, initialMachineId: String? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as KKCApplication
    val scan by scanCoordinator.state.collectAsState()
    val progressVersion by progressStore.progressVersion.collectAsState()
    // Saved like other selection state; unknown or non-mix machines (restored or deep-linked) fall back to Red.
    var selectedMachineId by rememberSaveable(jobFolderName) { mutableStateOf(CncMachines.mixMachine(initialMachineId).id) }
    val machine = CncMachines.mixMachine(selectedMachineId)
    val service = remember(machine) { MixServiceClient(machine) }
    val client = remember(machine) { MixFilesClient(machine) }
    // Every mix mutation re-reads this machine's /status and refuses a mismatched, read-only or FRZ-less target.
    val api: MixFilesApi = remember(client) { MachineGuardedMixFilesApi(machine, client, client::serviceStatus) }
    val scope = rememberCoroutineScope()
    val engine = remember(scan.snapshot.basePath) { UnifiedMetadataEngineRegistry.getOrCreate(File(scan.snapshot.basePath), com.kkc.sheettracker.BuildConfig.DEBUG) }
    val job by produceState<com.kkc.sheettracker.data.models.Job?>(null, engine, jobFolderName, scan.snapshot.generation) {
        value = withContext(Dispatchers.IO) { engine.getCncSnapshot(jobFolderName)?.job }
    }
    val materials = job?.materials.orEmpty()
    val machines = CncMachines.mixMachines
    // Both machines' catalogs are observed so each group shows the materials whose folder it holds.
    val storesByMachine = remember(materials.map { it.materialName }, jobFolderName) {
        machines.associate { m -> m.id to materials.associate { it.materialName to app.mixFilesStores.store(m, jobFolderName, it.materialName) } }
    }
    val stores = storesByMachine.getValue(machine.id)
    val statusStores = machines.map(app::statusStoreFor)
    ForegroundMixLeases(storesByMachine.values.flatMap { it.values }, statusStores)
    val machineStatuses = machines.zip(statusStores).associate { (m, store) -> m.id to store.status.collectAsState().value }
    val machineStatus = machineStatuses[machine.id] ?: MachineStatus.Unavailable
    val catalogStatesByMachine = machines.associate { m ->
        m.id to rememberMixCatalogStates(m.id, jobFolderName, storesByMachine.getValue(m.id).mapValues { it.value.state })
    }
    val catalogStates = catalogStatesByMachine.getValue(machine.id)
    val transferStore = app.materialTransfers
    // App-scoped: moves, mix saves and second-pass changes keep being watched after this screen closes.
    val operations = app.cncOperations
    val transfers by transferStore.records.collectAsState()
    val mixOperations by operations.mixOperations.records.collectAsState()
    var selectedMaterial by rememberSaveable(jobFolderName) { mutableStateOf(initialMaterial) }
    LaunchedEffect(materials) { selectedMaterial = selectAvailableMaterial(selectedMaterial, materials.map { it.materialName }) }
    val material = materials.firstOrNull { it.materialName == selectedMaterial }
    // Drafts, history, previews and in-flight saves belong to one machine/job/material session.
    val session = remember(machine.id, jobFolderName, selectedMaterial) {
        MixesSession(key = mixesSessionKey(machine, jobFolderName, selectedMaterial), punloadFixed = machine.forcesPUnloadRemoval)
    }
    // Without a machine from navigation, settle once on the machine that holds the requested material.
    // Held by both: stay put; both tiles are visible and the operator picks one. Never retargets a dirty session.
    var ownerResolved by rememberSaveable(jobFolderName) { mutableStateOf(initialMachineId != null || initialMaterial == null) }
    val initialStates = machines.associate { it.id to catalogStatesByMachine.getValue(it.id)[initialMaterial] }
    LaunchedEffect(ownerResolved, initialStates) {
        if (ownerResolved) return@LaunchedEffect
        if (selectedMaterial != initialMaterial || session.dirty) { ownerResolved = true; return@LaunchedEffect }
        if (!materialOwnershipSettled(initialStates, machines)) return@LaunchedEffect
        (resolveMaterialOwnership(initialStates, machines = machines) as? MaterialOwnership.Single)?.let { selectedMachineId = it.machine.id }
        ownerResolved = true
    }
    // Outcomes this screen shows inline aren't repeated as app-level notices while it is open.
    DisposableEffect(operations, machine.id, jobFolderName, selectedMaterial) {
        val lease = selectedMaterial?.let { operations.viewing(machine.id, jobFolderName, it) }
        onDispose { lease?.close() }
    }
    var inventory by remember(machine.id, selectedMaterial) { mutableStateOf<List<String>>(emptyList()) }
    var revertSupported by remember(machine) { mutableStateOf(false) }
    var editorFeatures by remember(machine) { mutableStateOf<Set<String>?>(null) }
    var serviceStatus by remember(machine) { mutableStateOf<MixServiceStatus?>(null) }
    var statusRefresh by remember(machine) { mutableIntStateOf(0) }
    val revisionSupported = editorFeatures?.contains("pgm-edit-revisions") == true
    val previewSupported = revisionSupported && editorFeatures?.contains("pgm-edit-preview") == true
    val revisionState = remember(session) { PgmRevisionState() }
    val pgmPreview = remember(session) { PgmPreviewState() }
    var showPgmPreview by remember(session) { mutableStateOf(false) }
    var revisionResume by remember(session) { mutableIntStateOf(0) }
    var manualRevisionRefresh by remember(session) { mutableIntStateOf(0) }
    var appliedRevisionRefresh by remember(session) { mutableIntStateOf(0) }
    fun refreshPgmRevisions() { pgmPreview.invalidate(); showPgmPreview = false; manualRevisionRefresh++ }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revisionResume++
            if (event == Lifecycle.Event.ON_STOP) {
                revisionState.unavailable("Refresh CNC revisions after reconnecting.")
                pgmPreview.markStale("Cached preview. Reconnect and refresh CNC revisions.")
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
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
    // Saves in flight belong to the app watcher, so leaving Mixes never abandons them; the outcome shows app-wide.
    fun leaveScreen() {
        if (!session.saving) return leave(onBack)
        if (session.applying || session.draft == null) return onBack()
        pendingLeaveText = "Mix changes haven't been applied. Second-pass changes keep applying after you leave."
        pendingLeave = onBack
    }
    BackHandler { leaveScreen() }
    val latest = catalogStates[selectedMaterial].catalogSnapshot()
    LaunchedEffect(session, latest) { latest?.let(session::onCatalog) }
    LaunchedEffect(machine, statusRefresh, revisionResume) {
        val status = client.serviceStatus()
        serviceStatus = status
        val features = status?.features.orEmpty()
        revertSupported = "pgm-edits-revert" in features
        editorFeatures = features
    }
    fun historyPgms() = canonicalPgms(inventory + material?.let { sheetPages(it.metadata?.pages.orEmpty()).flatMap { page -> page.pgmFiles } }.orEmpty())
    suspend fun retryHistory(name: String) { session.reloadHistory(historyPgms()) { service.listPgmEdits(jobFolderName, name) } }
    // A change submitted earlier (possibly from a closed screen) that the app watcher is still confirming.
    val pendingOperation = mixOperations.firstOrNull { !it.terminal && it.machineId == machine.id && it.job == jobFolderName && it.material == selectedMaterial }
    LaunchedEffect(session, material?.materialName, editorFeatures, revisionResume, manualRevisionRefresh, session.secondApplying, pendingOperation == null) {
        val name = material?.materialName ?: return@LaunchedEffect
        if (editorFeatures == null || session.secondApplying) return@LaunchedEffect
        try {
            val rows = service.listPgms(jobFolderName, name, withSha256 = revisionSupported)
            if (rows.isNotEmpty() || inventory.isEmpty()) inventory = rows.map { it.name }
            if (revisionSupported) {
                val explicit = manualRevisionRefresh != appliedRevisionRefresh
                val changed = revisionState.observe(rows, dirty = (session.secondPass?.changeCount ?: 0) > 0, explicit = explicit)
                if (changed) pgmPreview.markStale("PGM revisions changed. Review a fresh preview.")
                if (rows.isNotEmpty()) appliedRevisionRefresh = manualRevisionRefresh
            }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { revisionState.unavailable("Can't read CNC revisions. Reconnect and refresh.") }
        retryHistory(name)
    }
    fun statusesOf(m: com.kkc.sheettracker.data.models.Material) = m.metadata?.pages.orEmpty().associate {
        it.pageNumber to progressStore.getSheetStatus(jobFolderName, m.pdfFilename, it.pageNumber, m.fileFingerprint)
    }
    @Suppress("UNUSED_VARIABLE") val version = progressVersion
    val statusByMaterial = materials.associate { it.materialName to statusesOf(it) }
    val materialInputs = machines.flatMap { owner -> materials.map { m ->
        val state = catalogStatesByMachine.getValue(owner.id)[m.materialName] ?: CatalogState.Loading
        val current = owner.id == machine.id && m == material
        val visible = if (current && session.catalog != null) state.withCatalog(session.catalog!!) else state
        MaterialMixesInput(owner, m, visible, statusByPage = statusByMaterial[m.materialName].orEmpty(),
            hasPgms = sheetPages(m.metadata?.pages.orEmpty()).isNotEmpty() || (current && inventory.isNotEmpty()))
    } }
    val selected = materialInputs.firstOrNull { it.material == material && it.machine.id == machine.id }
    val presentation = buildMixesUi(MixesUiInput(materialInputs, selectedMaterial, selectedMachineId = machine.id, serviceStatus = serviceStatus,
        machines = machines, machineStatuses = machineStatuses, job = jobFolderName, transfers = transfers,
        selectedTab = session.selectedTab, draft = session.draft,
        secondPassDraft = session.secondPass, draftFolderRevision = session.draftFolderRevision, conflict = session.conflict,
        applyResult = session.applyResult, savedInterrupted = session.savedInterrupted, applying = session.applying,
        secondPassApplying = session.secondApplying, machineStatus = machineStatus))
    val ui = presentation.copy(secondPassApplyEnabled = presentation.secondPassApplyEnabled && session.secondHistoryConfirmed && !session.historyLoading &&
        (!revisionSupported || revisionState.canApply(session.secondPass)))
    val group = session.catalog?.let(::groupMixFiles)?.firstOrNull { it.key == ui.selectedTab }
    val catalogLive = catalogStates[selectedMaterial] is CatalogState.Live && session.applyResult !is MixFilesResult.ServiceOutdated
    val access = ui.access
    // FRZ groups stay view-only on machines without FRZ support; the guarded api refuses them as well.
    // While this material is being moved, both copies stay read-only on the tablet; the services also reserve it.
    val activeMove = transfers.firstOrNull { it.blocksMaterial && it.job == jobFolderName && it.material == selectedMaterial }
    val catalogWritable = catalogLive && activeMove == null && pendingOperation == null && access?.mixFiles != false && (access?.frz != false || group?.isInterrupted != true)
    val secondWritable = catalogLive && activeMove == null && pendingOperation == null && access?.secondPass != false
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
        if (!ui.card.let { it?.applyEnabled == true } || session.applying || (pendingOperation != null && !session.saving)) return
        scope.launch {
            val result = session.saveMix(::locks) { draft ->
                val write = buildWrite(draft, group, draft.completedPgms.toList(), resolutions)
                operations.submitMixWrite(machine, jobFolderName, name, mixWriteLabel(write)) { api.submitMixWrite(jobFolderName, name, write) }
            }
            publish(result, name)
            if (result == null) session.mixSaveBlockedReason?.let { snackbar.showSnackbar(it) }
            if (result is MixFilesResult.Ok) { splitMode = false; splitIds = emptySet() }
        }
    }
    fun mutate(label: String, submit: suspend (String) -> MixFilesResult<MixFilesCatalog>) {
        val name = selectedMaterial ?: return
        if (session.applying || !catalogWritable) return
        scope.launch { publish(session.mutateCatalog { operations.submitMixWrite(machine, jobFolderName, name, label) { submit(name) } }, name) }
    }
    fun openRevisions(key: String, compare: Boolean = false) { revisionsKey = key; compareFiles = compare }
    val takenNames = session.catalog?.files.orEmpty().filter { it.kind != MixFileKind.REVISION }.map { it.file } + session.draft?.splitOff.orEmpty().map { it.file }
    val revisionGroups = session.catalog?.let(::groupMixFiles).orEmpty()
    val eligibleSplitIds = (ui.groups?.remaining.orEmpty() + ui.groups?.unmatched.orEmpty()).filter { it.canSplit }.flatMap { it.draftEntryIds }.toSet()
    val validSplitIds = splitIds.intersect(eligibleSplitIds)
    val conflictReason = "This mix changed on the CNC. Close the dialog to resolve the conflict."
    fun openPgmPreview(pgm: String) {
        val name = selectedMaterial ?: return
        val draft = session.secondPass ?: return
        val hash = revisionState.hashes.pgmValue(pgm) ?: return
        val request = previewRequest(draft, pgm, hash, "preview-${System.nanoTime()}")
        val row = request.files.single()
        val key = PgmPreviewKey(machine.id, jobFolderName, name, pgm, hash, draft.wanted.pgmValue(pgm)!!, row.restoreOriginal)
        historyPgm = null; showPgmPreview = true; pgmPreview.start(key)
        scope.launch {
            try {
                var operation = service.submitPgmPreview(jobFolderName, name, request.requestId,
                    PgmEditRow(row.name, row.secondPass, row.removePUnload, row.expectedSha256), row.restoreOriginal)
                while (!operation.isTerminal) { delay(500); operation = service.getOperation(operation.id) }
                if (operation.state != "completed") error(operation.error ?: "Preview ${operation.state}")
                val file = decodePgmPreview(operation.result).file
                check(file.name.equals(pgm, true) && file.originalSha256 == hash && file.requested.mode == row.secondPass &&
                    file.requested.removePUnload == row.removePUnload && file.requested.restoreOriginal == row.restoreOriginal) { "Preview doesn't match this draft. Refresh CNC revisions." }
                pgmPreview.complete(key, file)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { pgmPreview.fail(key, failure.message ?: "Unable to preview. Refresh CNC revisions.") }
        }
    }
    var pendingPunload by remember { mutableStateOf<Pair<MovePlan.AskPunload, String>?>(null) }
    var pendingMove by remember { mutableStateOf<PendingMove?>(null) }
    var preparingMove by remember { mutableStateOf<String?>(null) }
    fun prepareMove(plan: MovePlan.Prepare, materialName: String) {
        val m = materials.firstOrNull { it.materialName == materialName } ?: return
        val catalog = (catalogStatesByMachine[plan.source.id]?.get(materialName) as? CatalogState.Live)?.catalog
        if (catalog == null) { scope.launch { snackbar.showSnackbar("Connect to ${plan.source.displayName} and refresh before moving.") }; return }
        if (preparingMove != null) return
        preparingMove = materialName
        scope.launch {
            try {
                snackbar.showSnackbar(plan.notice)
                // Read progress at the action boundary; the snapshot is a copy and nothing here writes a status.
                val input = MaterialMixesInput(plan.source, m, CatalogState.Live(catalog), statusByPage = statusesOf(m))
                val worklist = groupMixFiles(catalog).firstOrNull { it.mix != null && (materialName != selectedMaterial || it.key == ui.selectedTab) }?.mix?.file
                    ?: groupMixFiles(catalog).firstOrNull { it.mix != null }?.mix?.file
                val request = TransferPreviewRequest(plan.source, plan.destination, jobFolderName, materialName, catalog.folderRevision,
                    transferProgressSnapshot(jobFolderName, input, System.currentTimeMillis()), worklist, plan.policy)
                when (val result = MaterialTransferClient(plan.destination.baseUrl).preview(request)) {
                    is TransferResult.Ok -> pendingMove = PendingMove(plan, materialName, catalog.folderRevision, result.value)
                    is TransferResult.Rejected -> snackbar.showSnackbar(result.message.ifBlank { "${plan.destination.displayName} refused the move." })
                    TransferResult.Unsupported -> snackbar.showSnackbar("Moving materials needs a CNC service update. Nothing was changed.")
                    TransferResult.Offline -> snackbar.showSnackbar("Couldn't reach ${plan.destination.displayName} to prepare the move. Nothing was changed.")
                }
            } finally { preparingMove = null }
        }
    }
    fun requestMove(fromId: String, materialName: String, toId: String) {
        if (session.saving || preparingMove != null) return
        val source = CncMachines.mixMachine(fromId)
        val destination = CncMachines.mixMachine(toId)
        scope.launch {
            // Fresh identity/capability reads at the moment of the gesture, for both endpoints.
            val sourceStatus = MixFilesClient(source).serviceStatus()
            val destinationStatus = MixFilesClient(destination).serviceStatus()
            val destinationState = catalogStatesByMachine[destination.id]?.get(materialName)
            val plan = planMaterialMove(MoveContext(source, destination, materialName, sourceStatus, destinationStatus,
                dirty = fromId == machine.id && materialName == selectedMaterial && session.dirty,
                activeTransfer = transferStore.activeFor(jobFolderName, materialName) != null,
                needsAttention = transferStore.needsAttentionFor(jobFolderName, materialName) != null,
                sourceCatalogLive = catalogStatesByMachine[source.id]?.get(materialName) is CatalogState.Live,
                destinationHasMaterial = destinationState != null && ownsMaterial(destinationState)))
            when (plan) {
                is MovePlan.Blocked -> snackbar.showSnackbar(plan.reason)
                is MovePlan.AskPunload -> pendingPunload = plan to materialName
                is MovePlan.Prepare -> prepareMove(plan, materialName)
            }
        }
    }
    // The app watcher polls submitted moves, refreshes both machines and shows the outcome app-wide;
    // this screen only follows a verified move once, and never under an in-flight save.
    val followedMoves = remember(jobFolderName) { mutableSetOf<String>() }
    LaunchedEffect(transfers) {
        transfers.filter { it.job == jobFolderName && it.state == TransferState.COMPLETED && it.requestId !in followedMoves }.forEach { record ->
            followedMoves += record.requestId
            if (record.material == selectedMaterial && record.sourceMachineId == machine.id && !session.saving) selectedMachineId = record.destinationMachineId
        }
    }
    val selectedRecord = transfers.firstOrNull { it.job == jobFolderName && it.material == selectedMaterial && it.state != TransferState.COMPLETED }
    val materialMenu = buildList {
        machines.filter { it.id != machine.id }.forEach { target ->
            add(MaterialMenuAction("Move to ${target.displayName}\u2026", selectedRecord == null && !session.saving && preparingMove == null) {
                selectedMaterial?.let { requestMove(machine.id, it, target.id) }
            })
        }
        selectedRecord?.let { record ->
            if (record.state == TransferState.SUBMITTED) add(MaterialMenuAction("Check move status", true) { operations.checkNow(record.requestId) })
            if (record.state == TransferState.UNCERTAIN) add(MaterialMenuAction("Resend the same move request", true) {
                scope.launch { operations.resendMove(record.requestId) }
            })
            // Needs attention is terminal: no retry or resend. Its notice clears only once the office has resolved it.
            if (record.state == TransferState.NEEDS_ATTENTION) add(MaterialMenuAction("Office resolved \u2014 dismiss notice", true) {
                scope.launch {
                    transferStore.dismiss(record.requestId)
                    storesByMachine.values.forEach { it[record.material]?.refreshNow() }
                }
            })
            if (record.state == TransferState.UNCERTAIN || record.state == TransferState.FAILED) add(MaterialMenuAction("Dismiss move notice", true) {
                scope.launch {
                    transferStore.dismiss(record.requestId)
                    storesByMachine.values.forEach { it[record.material]?.refreshNow() }
                }
            })
        }
    }
    MixesWorkspace(job?.let { "${it.jobNumber} · ${it.jobName}" } ?: jobFolderName, selectedMaterial, selected?.pages?.size ?: 0,
        ui, session.secondPass, revertSupported, inventory, material?.let { File(File(scan.snapshot.basePath, "$jobFolderName/CNC"), it.pdfFilename) },
        machineName = machine.displayName,
        // Selecting another machine's tile abandons this session like a material switch; it never retargets drafts or saves.
        onSelectMaterial = { id, name -> if (id != machine.id || name != selectedMaterial) leave { selectedMachineId = id; selectedMaterial = name } },
        onMoveMaterial = { from, name, to -> requestMove(from, name, to) },
        materialMenu = materialMenu,
        onCreateRemaining = { leaveMix { group?.let { session.startRemaining(it, locks()) } } },
        onBack = { leaveScreen() }, onMaterial = { name -> leave { selectedMaterial = name } },
        onTab = { key -> leaveMix { session.selectedTab = key } },
        onNew = { leaveMix { session.startNew(selectedMaterial ?: "Material", selected?.pages.orEmpty(), locks()) } },
        onDiscard = { session.discardMix(latestCatalog()) },
        onApply = { applyMix() }, onRefresh = { selectedMaterial?.let { name -> scope.launch {
            statusRefresh++
            stores[name]?.refreshNow()
            session.acknowledgeRefresh(stores[name]?.state?.value is CatalogState.Live)
            retryHistory(name)
        } } },
        onLoadTheirs = { session.discardMix(latestCatalog()) }, onReapply = { session.reapply(locks()) },
        onDragStart = session::beginDrag, onDrop = { session.drop(group, locks(), it) },
        onAdd = { page -> edit { d -> page.pgmFiles.fold(d) { next, pgm -> MixDrafts.add(next, pgm) } } },
        onSecondPass = { pgms, field, on -> pgms.forEach(pgmPreview::invalidate); session.updateSecond(pgms, field, on, locks()) },
        onSecondDiscard = { pgmPreview.invalidate(); session.discardSecond() },
        onSecondApply = {
            val name = selectedMaterial
            if (name != null && ui.secondPassApplyEnabled && !session.secondApplying && pendingOperation == null) scope.launch {
                val notices = mutableListOf<PgmNotice>()
                session.saveSecond(::locks, { submitted ->
                    val deviceId = context.getSharedPreferences("kkc_tracker", Context.MODE_PRIVATE).getString("tablet_id", null)
                        ?.takeIf { it.isNotBlank() } ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    val revisions = if (revisionSupported) SecondPassRevisionSupport(
                        SecondPassRevisionSnapshot(revisionState.hashes, pgmPreview.previews),
                        refresh = { service.listPgms(jobFolderName, name, withSha256 = true).filter { validPgmHash(it.sha256) }.associate { it.name to it.sha256!! } },
                        revert = client::submitPgmEditRevertRows) else null
                    pgmPreview.invalidate()
                    val steps = planSecondPass(submitted)
                    // The plan runs and is watched in the app scope, so leaving Mixes never abandons it.
                    operations.runSecondPass(machine, jobFolderName, name) { tracker ->
                        SecondPassRunner(service, client::submitPgmEditRevert, secondPassRequestIds(deviceId),
                            revisions = revisions, onNotices = { synchronized(notices) { notices += it } }, tracker = tracker)
                            .apply(jobFolderName, name, steps) { session.secondMessage = it }
                    }.also { outcome ->
                        if (outcome is SecondPassOutcome.Failed && outcome.code in setOf("pgm_changed", "preview_changed"))
                            revisionState.requireReview("CNC files or the preview changed. Refresh revisions and review your draft.")
                    }
                }, { service.listPgmEdits(jobFolderName, name) })
                if (synchronized(notices) { notices.isNotEmpty() }) session.secondMessage = listOfNotNull(session.secondMessage,
                    notices.distinctBy { it.code to it.message }.joinToString(" ") { it.message }).joinToString(" ")
            }
        }, secondMessage = listOfNotNull(session.secondMessage, revisionState.message.takeIf { revisionSupported }).distinct().takeIf { it.isNotEmpty() }?.joinToString(" "),
        recoveryEnabled = session.gate.deferredCatalog != null,
        reapplyEnabled = session.reapplyEnabled,
        mixMessage = listOfNotNull(selectedRecord?.let(::transferNotice),
            pendingOperation?.takeIf { !session.saving }?.let { "${it.label} is still being confirmed on ${machine.displayName}. Editing resumes when it finishes." },
            session.recoveryMessage ?: RESTORE_PREVIEW_MESSAGE.takeIf { session.draft?.rawRestore != null }).joinToString("\n").ifEmpty { null },
        mixEditingEnabled = !session.applying && activeMove == null && (pendingOperation == null || session.saving), secondEditingEnabled = secondWritable && !session.secondApplying && session.secondHistoryConfirmed && !session.historyLoading,
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
            mutate("Rename ${target.file} to $file") { name -> api.rename(jobFolderName, name, target.file, file, target.revision) }
        }, onDismiss = { cardDialog = null }, extension = target.file.substringAfterLast('.'), taken = takenNames, enabled = !session.applying)
        MixCardAction.DELETE -> DeleteConfirm(target.file, onConfirm = {
            cardDialog = null; mutate("Delete ${target.file}") { name -> api.delete(jobFolderName, name, target.file, target.revision) }
        }, onDismiss = { cardDialog = null }, enabled = !session.applying)
        else -> Unit
    } }
    revisionsKey?.let { key -> revisionGroups.firstOrNull { it.key == key }?.let { revisions ->
        BoxWithConstraints { RevisionsPanel(revisions, onView = {}, onRestore = { revision -> leaveMix {
            val fresh = session.catalog?.let(::groupMixFiles)?.firstOrNull { it.key == key }
            if (fresh != null && session.stageRevision(fresh, revision, locks())) revisionsKey = null
        } }, onDeleteRevision = { revision -> leaveMix { deleteRevision = revision } },
            onDismiss = { revisionsKey = null }, rail = useRail(maxWidth.value.toInt()), compare = compareFiles,
            mutationsEnabled = catalogLive && !session.applying && access?.mixFiles != false &&
                (access?.frz != false || (revisions.frz == null && revisions.frzRevisions.isEmpty()))) }
    } }
    deleteRevision?.let { revision -> DeleteConfirm(revision.file, onConfirm = {
        deleteRevision = null; mutate("Delete revision ${revision.file}") { name -> api.deleteRevision(jobFolderName, name, revision.file, revision.revision) }
    }, onDismiss = { deleteRevision = null }, permanent = true, enabled = !session.applying) }
    historyPgm?.let { pgm ->
        val record = session.history?.files?.entries?.firstOrNull { it.key.equals(pgm, true) }?.value
        val locked = locks().any { it.equals(pgm, true) }
        SecondPassHistorySheet(pgm, record?.history.orEmpty(), revertSupported && !locked && !session.secondApplying && session.secondHistoryConfirmed &&
            session.secondPass?.current?.pgmValue(pgm)?.let { it.secondPass || it.removePUnload } == true,
            onRevert = { pgmPreview.invalidate(pgm); SecondPassField.entries.forEach { session.updateSecond(listOf(pgm), it, false, locks()) }; historyPgm = null },
            onDismiss = { historyPgm = null }, current = record?.current, revertSupported = revertSupported,
            pgms = historyRow, onSelect = { historyPgm = it },
            onPreview = if (previewSupported) ({ openPgmPreview(pgm) }) else null,
            previewEnabled = !session.secondApplying && revisionState.canApply(session.secondPass) &&
                session.secondPass?.let { it.wanted.pgmValue(pgm) != it.current.pgmValue(pgm) } == true,
            onRefreshRevisions = if (revisionSupported && !session.secondApplying) ::refreshPgmRevisions else null)
    }
    if (showPgmPreview) PgmPreviewSheet(pgmPreview, onRefresh = ::refreshPgmRevisions, onDismiss = { showPgmPreview = false })
    mismatches?.let { list -> FrzMismatchDialog(list, statusOf = { pgm ->
        val page = selected?.pages?.firstOrNull { p -> p.pgmFiles.any { it.equals(pgm, true) } }
        page?.let { selected.statusOf(it.pageNumber).toString().replace('_', ' ') } ?: if (locks().any { it.equals(pgm, true) }) "Complete" else "Not complete"
    }, onResolve = { choices -> mismatches = null; applyMix(choices) }, onDismiss = { mismatches = null },
        enabled = !session.applying && !session.conflict, dismissEnabled = !session.applying, blockedReason = conflictReason.takeIf { session.conflict }) }
    pendingLeave?.let { action -> AlertDialog(onDismissRequest = { pendingLeave = null }, title = { Text("Discard unsaved changes?") },
        text = { Text(pendingLeaveText) }, confirmButton = {
            TextButton(onClick = { pendingLeave = null; action() }) { Text("Discard") }
        }, dismissButton = { TextButton(onClick = { pendingLeave = null }) { Text("Keep editing") } }) }
    pendingPunload?.let { (ask, materialName) ->
        fun choose(choice: PunloadChoice) { pendingPunload = null; ask.choose(choice)?.let { prepareMove(it, materialName) } }
        AlertDialog(onDismissRequest = { choose(PunloadChoice.CANCEL) }, title = { Text(ask.question) },
            text = { Text("$materialName moves back to ${ask.destination.displayName}. Restore its PUNLOAD lines, or keep them removed. Cancel changes nothing.") },
            confirmButton = { TextButton(onClick = { choose(PunloadChoice.RESTORE) }) { Text("Restore PUNLOAD") } },
            dismissButton = { Row {
                TextButton(onClick = { choose(PunloadChoice.KEEP_REMOVED) }) { Text("Keep removed") }
                TextButton(onClick = { choose(PunloadChoice.CANCEL) }) { Text("Cancel") }
            } })
    }
    pendingMove?.let { move ->
        val preview = move.preview
        val review = movePreviewReview(move.material, move.plan, preview)
        AlertDialog(onDismissRequest = { pendingMove = null },
            title = { Text(review.title) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("move-preview"),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                review.summary.forEach { Text(it) }
                review.conflicts.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                review.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { if (review.startable) Button(modifier = Modifier.testTag("move-start"), onClick = {
                pendingMove = null // One tap starts at most one request; the journal refuses duplicates as well.
                scope.launch {
                    when (val started = operations.startMove(move.plan.source, move.plan.destination, jobFolderName, move.material,
                        preview, move.sourceRevision, move.plan.policy)) {
                        is TransferStart.AlreadyActive -> snackbar.showSnackbar(
                            if (started.record.state == TransferState.NEEDS_ATTENTION) MOVE_NEEDS_ATTENTION else "This material is already moving. Checking move status.")
                        is TransferStart.Started -> transferNotice(started.record)?.let { snackbar.showSnackbar(it) }
                    }
                }
            }) { Text("Start move") } },
            dismissButton = { TextButton(onClick = { pendingMove = null }) { Text(if (review.startable) "Cancel" else "Close") } })
    }
}
/** STARTED leases also release on backgrounding, even when the composition remains retained. */
@Composable
private fun ForegroundMixLeases(stores: List<MixFilesStore>, machineStores: List<MachineStatusStore>) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, stores, machineStores) {
        var leases = emptyList<AutoCloseable>()
        fun sync() {
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (leases.isEmpty()) leases = stores.map { it.acquire() } + machineStores.map { it.acquire() }
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
    secondMessage: String?, mixMessage: String? = null, onRowAction: ((RowModel, MixRowAction) -> Unit)? = null,
    onCardAction: ((MixCardAction) -> Unit)? = null, recoveryEnabled: Boolean = true, reapplyEnabled: Boolean = true,
    mixEditingEnabled: Boolean = true, secondEditingEnabled: Boolean = true, secondDiscardEnabled: Boolean = true,
    navigationEnabled: Boolean = true, historyRetryVisible: Boolean = false, historyLoading: Boolean = false,
    onHistoryRetry: () -> Unit = {}, snackbar: SnackbarHostState? = null,
    splitMode: Boolean = false, splitIds: Set<Long> = emptySet(), onSplitSelection: (RowModel) -> Unit = {},
    onSplitConfirm: () -> Unit = {}, onSplitCancel: () -> Unit = {}, companionMismatch: Boolean = false,
    revisionGroups: List<MixGroup> = emptyList(), onMaterialRevisions: (String) -> Unit = {},
    catalogActionsEnabled: Boolean = !ui.readOnly && mixEditingEnabled, newMix: Boolean = false,
    machineName: String? = null, onSelectMaterial: ((machineId: String, material: String) -> Unit)? = null,
    onMoveMaterial: ((fromMachineId: String, material: String, toMachineId: String) -> Unit)? = null,
    materialMenu: List<MaterialMenuAction> = emptyList(), onCreateRemaining: (() -> Unit)? = null) {
    val status = LocalKKCStatusColors.current
    Scaffold(snackbarHost = { snackbar?.let { SnackbarHost(it) } }, topBar = {
        TopAppBar(title = { Column { Text(title, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull("Mixes", machineName?.let { "$it \u00b7 ${machineStatusText(ui.machineStatus)}" }).joinToString(" \u00b7 "),
                    style = MaterialTheme.typography.labelMedium) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {})
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding)) {
            val widthDp = maxWidth.value.toInt()
            val rail = useRail(widthDp)
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                val groups = ui.machineGroups.ifEmpty { listOf(MachineGroupModel(ui.nav.firstOrNull()?.machineId ?: "scm", machineName ?: "SCM CNC", ui.machineStatus, ui.nav)) }
                val select: (String, String) -> Unit = { id, name -> if (navigationEnabled) onSelectMaterial?.invoke(id, name) ?: onMaterial(name) }
                val move = onMoveMaterial.takeIf { navigationEnabled }
                if (rail) MixesMaterialNav(groups, true, widthDp, select, Modifier.width(220.dp).padding(top = 8.dp, bottom = 160.dp), move)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!rail) MixesMaterialNav(groups, false, widthDp, select, Modifier.padding(top = 8.dp), move)
                    if (material == null) {
                        Text("No job materials available. Refresh the job and try again.", Modifier.padding(16.dp))
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(material, style = MaterialTheme.typography.titleLarge)
                                machineName?.let { Text("On $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text("$sheetCount sheets · ${ui.tabs.size} ${if (ui.tabs.size == 1) "mix" else "mixes"}", style = MaterialTheme.typography.bodySmall)
                            }
                            val pgms = secondPass?.wanted?.keys?.toList() ?: inventory
                            SecondPassMenu(secondPass, pgms, secondEditingEnabled, revertSupported, { field, on -> onSecondPass(pgms, field, on) })
                            var revisionsMenu by remember { mutableStateOf(false) }
                            val withRevisions = revisionGroups.filter { it.mixRevisions.isNotEmpty() || it.frzRevisions.isNotEmpty() }
                            if (materialMenu.isNotEmpty()) Box {
                                var open by remember { mutableStateOf(false) }
                                TextButton(onClick = { open = true }, modifier = Modifier.testTag("mixes-material-menu")) { Text("\u22ef") }
                                DropdownMenu(open, { open = false }) { materialMenu.forEach { action ->
                                    DropdownMenuItem(text = { Text(action.label) }, enabled = action.enabled, onClick = { open = false; action.onClick() })
                                } }
                            }
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
                        mixMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
                                        if (ui.remainingMixAvailable && onCreateRemaining != null) {
                                            // Blue SCM pause: a fresh MIX of the remaining sheets for review. Not an FRZ resume.
                                            TextButton(onClick = onCreateRemaining, enabled = mixEditingEnabled && navigationEnabled,
                                                modifier = Modifier.testTag("mixes-create-remaining")) { Text("Create remaining MIX") }
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
                                MixesSheetList(groups, secondPass, revertSupported, !ui.readOnly && !ui.restorePreview && mixEditingEnabled && ui.banner != Banner.ChangedOnCnc,
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

internal fun machineStatusLabel(name: String, status: MachineStatus): String =
    listOf(name, machineStatusText(status)).joinToString(" \u00b7 ")

internal fun machineStatusText(status: MachineStatus): String {
    val value = when (status) {
        MachineStatus.Unavailable -> "unavailable"
        is MachineStatus.Live -> when (status.producing) {
            true -> "cutting"
            false -> if (status.state == "ready") "ready" else "idle"
            null -> "unknown"
        }
    }
    val program = (status as? MachineStatus.Live)?.loaded?.name?.takeIf { it.isNotBlank() }
    return listOfNotNull(value, program).joinToString(" \u00b7 ")
}

/** Operator-facing subject of a mix-files save, for the app-level notice. */
internal fun mixWriteLabel(write: MixWrite): String = when (write) {
    is MixWrite.Write -> "Save ${write.file}"
    is MixWrite.Frz -> "Save ${write.file}"
    is MixWrite.Split -> "Split ${write.file}"
    is MixWrite.Restore -> "Restore ${write.intent.targetFile}"
}
