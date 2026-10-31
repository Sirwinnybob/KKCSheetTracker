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
import com.kkc.sheettracker.data.mixservice.PgmEditHistoryView
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.ui.components.kkcCardDepth
import com.kkc.sheettracker.ui.settings.GroupCard
import com.kkc.sheettracker.ui.theme.LocalKKCStatusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

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
    val catalogStates = remember(stores) { mutableStateMapOf<String, CatalogState>() }
    stores.forEach { (name, store) -> LaunchedEffect(store) { store.state.collect { catalogStates[name] = it } } }
    var selectedMaterial by remember(jobFolderName) { mutableStateOf(initialMaterial) }
    LaunchedEffect(materials) { selectedMaterial = selectAvailableMaterial(selectedMaterial, materials.map { it.materialName }) }
    val material = materials.firstOrNull { it.materialName == selectedMaterial }
    val gates = remember(jobFolderName) { mutableMapOf<String, CatalogGate>() }
    val gate = selectedMaterial?.let { gates.getOrPut(it) { CatalogGate() } }
    var catalog by remember(selectedMaterial) { mutableStateOf<MixFilesCatalog?>(gate?.appliedCatalog) }
    var tab by remember(selectedMaterial) { mutableStateOf<String?>(null) }
    var draft by remember(selectedMaterial) { mutableStateOf<MixDraft?>(null) }
    var draftFolderRevision by remember(selectedMaterial) { mutableStateOf<String?>(null) }
    var conflict by remember(selectedMaterial) { mutableStateOf(false) }
    var dragging by remember(selectedMaterial) { mutableStateOf(false) }
    var applyResult by remember(selectedMaterial) { mutableStateOf<MixFilesResult<MixFilesCatalog>?>(null) }
    var applying by remember(selectedMaterial) { mutableStateOf(false) }
    var savedInterrupted by remember(selectedMaterial) { mutableStateOf(false) }
    var history by remember(selectedMaterial) { mutableStateOf<PgmEditHistoryView?>(null) }
    var secondPass by remember(selectedMaterial) { mutableStateOf<SecondPassDraft?>(null) }
    var inventory by remember(selectedMaterial) { mutableStateOf<List<String>>(emptyList()) }
    var secondApplying by remember(selectedMaterial) { mutableStateOf(false) }
    var secondHistoryConfirmed by remember(selectedMaterial) { mutableStateOf(false) }
    var secondMessage by remember(selectedMaterial) { mutableStateOf<String?>(null) }
    var revertSupported by remember(machine) { mutableStateOf(false) }
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val dirty = draft != null || (secondPass?.changeCount ?: 0) > 0
    fun leave(action: () -> Unit) { if (!applying && !secondApplying) { if (dirty) pendingLeave = action else action() } }
    BackHandler { leave(onBack) }
    val latest = catalogStates[selectedMaterial].catalogSnapshot()
    LaunchedEffect(selectedMaterial, latest) {
        if (latest != null && gate != null) {
            when (gate.onCatalog(latest, dragging, draft != null)) {
                GateDecision.APPLY -> catalog = gate.appliedCatalog
                GateDecision.CONFLICT -> conflict = true
                GateDecision.DEFER -> Unit
            }
        }
    }
    LaunchedEffect(machine) { revertSupported = "pgm-edits-revert" in api.features() }
    LaunchedEffect(selectedMaterial) {
        val name = selectedMaterial ?: return@LaunchedEffect
        try {
            inventory = service.listPgms(jobFolderName, name).map { it.name }
            history = service.listPgmEdits(jobFolderName, name)
            val pgms = (inventory + material?.let { sheetPages(it.metadata?.pages.orEmpty()).flatMap { page -> page.pgmFiles } }.orEmpty()).distinct()
            secondHistoryConfirmed = history != null
            if (history != null) secondPass = SecondPassDrafts.from(history, pgms)
            else secondMessage = "Couldn't load second-pass history. Refresh before editing."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { secondMessage = "Couldn't load second-pass history. Refresh before editing." }
    }
    val materialInputs = materials.map { m ->
        val state = catalogStates[m.materialName] ?: CatalogState.Loading
        val visible = if (m == material && catalog != null) state.withCatalog(catalog!!) else state
        // progressVersion is the authoritative invalidation signal; reads are synchronous tablet status.
        @Suppress("UNUSED_VARIABLE") val version = progressVersion
        MaterialMixesInput(machine, m, visible, statusByPage = m.metadata?.pages.orEmpty().associate {
            it.pageNumber to progressStore.getSheetStatus(jobFolderName, m.pdfFilename, it.pageNumber, m.fileFingerprint)
        }, hasPgms = sheetPages(m.metadata?.pages.orEmpty()).isNotEmpty() || (m == material && inventory.isNotEmpty()))
    }
    val selected = materialInputs.firstOrNull { it.material == material }
    val presentation = buildMixesUi(MixesUiInput(materialInputs, selectedMaterial, selectedTab = tab, draft = draft,
        secondPassDraft = secondPass, draftFolderRevision = draftFolderRevision, conflict = conflict,
        applyResult = applyResult, savedInterrupted = savedInterrupted, applying = applying, secondPassApplying = secondApplying, machineStatus = machineStatus))
    val ui = presentation.copy(secondPassApplyEnabled = presentation.secondPassApplyEnabled && secondHistoryConfirmed)
    val group = catalog?.let(::groupMixFiles)?.firstOrNull { it.key == ui.selectedTab }
    fun locks(): List<String> = selected?.pages.orEmpty().filter { isLockedStatus(selected!!.statusOf(it.pageNumber)) }.flatMap { it.pgmFiles }.distinct()
    fun edit(block: (MixDraft) -> MixDraft) {
        val initial = draft ?: group?.let { MixDrafts.start(it, locks()) } ?: return
        if (draft == null) draftFolderRevision = catalog?.folderRevision
        draft = block(initial.copy(completedPgms = locks().map { it.lowercase(Locale.ROOT) }.toSet()))
        savedInterrupted = false
        if (applyResult !is MixFilesResult.Offline) applyResult = null
    }
    fun updateSecond(pgms: List<String>, field: SecondPassField, on: Boolean) {
        secondPass?.let { secondPass = SecondPassDrafts.setAll(it, pgms.filterNot { pgm -> locks().any { name -> name.equals(pgm, true) } }, field, on) }
    }
    MixesWorkspace(job?.let { "${it.jobNumber} · ${it.jobName}" } ?: jobFolderName, selectedMaterial, selected?.pages?.size ?: 0,
        ui, secondPass, revertSupported, inventory, material?.let { File(File(scan.snapshot.basePath, "$jobFolderName/CNC"), it.pdfFilename) },
        onBack = { leave(onBack) }, onMaterial = { name -> leave { selectedMaterial = name } },
        onTab = { key -> leave { draft = null; secondPass = secondPass?.let { it.copy(wanted = it.current) }; tab = key; conflict = false; applyResult = null } },
        onNew = {
            leave {
                val files = catalog?.files.orEmpty().map { it.file.lowercase() }.toSet()
                val base = "${selectedMaterial ?: "Material"}Mix"
                val name = generateSequence(1) { it + 1 }.map { n -> "$base${if (n == 1) "" else " $n"}.mix" }.first { it.lowercase() !in files }
                val other = catalog?.let(::groupMixFiles).orEmpty().filter { it.mix != null || it.frz != null }.flatMap { it.primary.entries }.map { it.pgm }.toSet()
                draft = MixDrafts.startNew(name, selected?.pages.orEmpty(), other).copy(completedPgms = locks().map { it.lowercase(Locale.ROOT) }.toSet())
                draftFolderRevision = catalog?.folderRevision; tab = draft?.groupKey; conflict = false; applyResult = null
            }
        }, onDiscard = { draft = null; conflict = false; applyResult = null; latest?.let { gate?.accept(it); catalog = it } },
        onApply = {
            val d = draft
            val name = selectedMaterial
            if (d != null && name != null && ui.card?.applyEnabled == true) scope.launch {
                applying = true
                try {
                    val result = when (val write = buildWrite(d, group, locks(), emptyMap())) {
                        is MixWrite.Write -> api.write(jobFolderName, name, write.file, write.expectedRevision, write.entries, write.completion)
                        is MixWrite.Frz -> api.frz(jobFolderName, name, write.file, write.expectedRevision, write.completion, write.pending)
                        is MixWrite.Split -> api.split(jobFolderName, name, write.file, write.expectedRevision, write.entries, write.newMixes, write.completion)
                    }
                    applyResult = result
                    if (result is MixFilesResult.Ok) {
                        stores[name]?.publish(result.value); gate?.accept(result.value); catalog = result.value
                        savedInterrupted = d.isInterrupted; draft = null; draftFolderRevision = null; conflict = false
                    } else if (result is MixFilesResult.Offline || (result is MixFilesResult.Rejected && result.error.code == "revision_conflict")) stores[name]?.refreshNow()
                } finally { applying = false }
            }
        }, onRefresh = { selectedMaterial?.let { name -> scope.launch {
            stores[name]?.refreshNow()
            if (stores[name]?.state?.value is CatalogState.Live) applyResult = null
            history = service.listPgmEdits(jobFolderName, name)
            secondHistoryConfirmed = history != null
            history?.let {
                val fresh = SecondPassDrafts.from(it, (inventory + selected?.pages.orEmpty().flatMap { page -> page.pgmFiles }).distinct())
                secondPass = secondPass?.takeIf { d -> d.changeCount > 0 }?.let { d -> fresh.copy(wanted = d.wanted) } ?: fresh
            }
        } } },
        onLoadTheirs = { draft = null; conflict = false; applyResult = null; gate?.deferredCatalog?.let { gate.accept(it); catalog = it } },
        onReapply = {
            val fresh = gate?.deferredCatalog
            val d = draft
            if (fresh != null && d != null && d.newFile == null) {
                val freshGroup = groupMixFiles(fresh).firstOrNull { it.key == d.groupKey }
                if (freshGroup != null) {
                    val rebased = MixDrafts.rebase(d, freshGroup, locks())
                    draft = rebased.draft; draftFolderRevision = fresh.folderRevision; gate.accept(fresh); catalog = fresh; conflict = false; applyResult = null
                    secondMessage = rebased.dropped.takeIf { it.isNotEmpty() }?.let { "No longer on CNC: ${it.joinToString()}" }
                }
            }
        }, onDragStart = { dragging = true }, onDrop = { moves ->
            // Create the draft against the visible snapshot BEFORE allowing a deferred catalog through.
            if (moves.isNotEmpty()) edit { initial -> moves.fold(initial) { d, (from, to) -> moveRowBlock(d, from.draftEntryIds, to.draftEntryIds) } }
            dragging = false
            if (gate?.onDrop(draft != null) == GateDecision.CONFLICT) conflict = true else catalog = gate?.appliedCatalog
        }, onAdd = { page -> edit { d -> page.pgmFiles.fold(d) { next, pgm -> MixDrafts.add(next, pgm) } } },
        onSecondPass = ::updateSecond, onSecondDiscard = { secondPass = secondPass?.let { it.copy(wanted = it.current) }; secondMessage = null },
        onSecondApply = {
            val name = selectedMaterial
            val d = secondPass
            if (name != null && d != null && ui.secondPassApplyEnabled) scope.launch {
                secondApplying = true
                try {
                    val deviceId = context.getSharedPreferences("kkc_tracker", Context.MODE_PRIVATE).getString("tablet_id", null)
                        ?.takeIf { it.isNotBlank() } ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    val runner = SecondPassRunner(service, api::submitPgmEditRevert, secondPassRequestIds(deviceId))
                    val outcome = runner.apply(jobFolderName, name, planSecondPass(d)) { secondMessage = it }
                    history = service.listPgmEdits(jobFolderName, name)
                    secondMessage = when (outcome) { SecondPassOutcome.Done -> "Second-pass changes applied"; is SecondPassOutcome.Failed -> outcome.message }
                    secondHistoryConfirmed = history != null
                    if (history != null) {
                        val fresh = SecondPassDrafts.from(history, d.wanted.keys.toList())
                        secondPass = if (outcome == SecondPassOutcome.Done) fresh else fresh.copy(wanted = d.wanted)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { secondHistoryConfirmed = false; secondMessage = "Couldn't confirm second-pass changes. Reload history before applying again." }
                finally { secondApplying = false }
            }
        }, secondMessage = secondMessage,
        recoveryEnabled = gate?.deferredCatalog != null,
        reapplyEnabled = draft?.newFile == null && gate?.deferredCatalog?.let { c -> groupMixFiles(c).any { it.key == draft?.groupKey } } == true,
        onRowAction = { row, action -> if (action == MixRowAction.REMOVE) edit { d -> row.draftEntryIds.fold(d) { next, id -> MixDrafts.remove(next, id) } } })
    pendingLeave?.let { action -> AlertDialog(onDismissRequest = { pendingLeave = null }, title = { Text("Discard unsaved changes?") },
        text = { Text("Mix and second-pass changes haven't been applied.") }, confirmButton = {
            TextButton(onClick = { draft = null; secondPass = secondPass?.let { it.copy(wanted = it.current) }; pendingLeave = null; action() }) { Text("Discard") }
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
    onCardAction: ((MixCardAction) -> Unit)? = null, recoveryEnabled: Boolean = true, reapplyEnabled: Boolean = true) {
    val status = LocalKKCStatusColors.current
    Scaffold(topBar = {
        TopAppBar(title = { Column { Text(title, style = MaterialTheme.typography.titleMedium); Text("Mixes", style = MaterialTheme.typography.labelMedium) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
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
                            SecondPassMenu(secondPass, pgms, !ui.readOnly, revertSupported, { field, on -> onSecondPass(pgms, field, on) })
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ui.tabs.forEach { tab -> FilterChip(selected = tab.key == ui.selectedTab, onClick = { onTab(tab.key) }, label = {
                                Text(tab.title + when { tab.error -> " · Error"; tab.interrupted -> " · Interrupted"; else -> "" } + if (tab.companionMismatch) " · Files differ" else "")
                            }) }
                            TextButton(onClick = onNew, enabled = !ui.readOnly) { Text("+ New mix") }
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
                                            TextButton(onClick = onLoadTheirs, enabled = recoveryEnabled) { Text("Load theirs") }
                                            TextButton(onClick = onReapply, enabled = recoveryEnabled && reapplyEnabled) { Text("Re-apply my changes") }
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
                                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { onCardAction?.invoke(MixCardAction.RENAME) }, enabled = onCardAction != null && !ui.readOnly) { Text("Rename") }
                                        TextButton(onClick = { onCardAction?.invoke(MixCardAction.SPLIT) }, enabled = onCardAction != null && card.splitEnabled) { Text("Split") }
                                        TextButton(onClick = { onCardAction?.invoke(MixCardAction.DELETE) }, enabled = onCardAction != null && !ui.readOnly) { Text("Delete") }
                                        TextButton(onClick = { onCardAction?.invoke(MixCardAction.REVISIONS) }, enabled = onCardAction != null) { Text("Revisions") }
                                        if (card.applyVisible) {
                                            TextButton(onClick = onDiscard) { Text("Discard") }
                                            Button(onClick = onApply, enabled = card.applyEnabled, modifier = Modifier.testTag("mixes-apply")) { Text(card.applyLabel) }
                                        }
                                    }
                                    card.applyBlockedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = status.skip) }
                                }
                            }
                        }
                        if (ui.secondPassApply != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = onSecondApply, enabled = ui.secondPassApplyEnabled) { Text(ui.secondPassApply) }
                            TextButton(onClick = onSecondDiscard) { Text("Discard 2nd pass") }
                        }
                        secondMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        val groups = ui.groups
                        if (groups != null) {
                            Box(Modifier.weight(1f).fillMaxWidth().kkcCardDepth(RoundedCornerShape(12.dp), elevation = 2.dp).background(MaterialTheme.colorScheme.surface)) {
                                MixesSheetList(groups, secondPass, revertSupported, !ui.readOnly && ui.banner != Banner.ChangedOnCnc,
                                    pdf, onDragStart, onDrop, onAdd, onSecondPass, onRowAction, Modifier.fillMaxSize())
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
