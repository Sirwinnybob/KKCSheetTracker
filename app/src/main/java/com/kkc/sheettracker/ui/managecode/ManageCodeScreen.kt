package com.kkc.sheettracker.ui.managecode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.mixservice.ManageCodeRow
import com.kkc.sheettracker.data.mixservice.ManageCodeRowSelection
import com.kkc.sheettracker.data.mixservice.MixServiceClient
import com.kkc.sheettracker.data.mixservice.MixCatalogFetchResult
import com.kkc.sheettracker.data.mixservice.MixCatalogMutationResult
import com.kkc.sheettracker.data.mixservice.MixCatalogRepository
import com.kkc.sheettracker.data.mixservice.MixCatalogCache
import com.kkc.sheettracker.data.mixservice.MixCatalogSnapshot
import com.kkc.sheettracker.data.mixservice.MixCatalogMutation
import com.kkc.sheettracker.data.mixservice.MixGenerationTarget
import com.kkc.sheettracker.data.mixservice.PgmEditSubmitResult
import com.kkc.sheettracker.data.mixservice.buildManageCodeChange
import com.kkc.sheettracker.data.mixservice.buildManageCodeRows
import com.kkc.sheettracker.data.mixservice.deriveRowSelection
import com.kkc.sheettracker.data.mixservice.findCrossMixDuplicates
import com.kkc.sheettracker.data.mixservice.isRowLocked
import com.kkc.sheettracker.data.mixservice.defaultMixName
import com.kkc.sheettracker.data.mixservice.resolveMixGenerationTarget
import com.kkc.sheettracker.data.mixservice.toggleSecondPass
import com.kkc.sheettracker.data.mixservice.toggleSuperPass
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

data class ManageCodeMaterialState(
    val materialName: String,
    val hasPgmsOnThisCnc: Boolean,
    val rows: List<ManageCodeRow>,
    val locked: Set<String>,
    val selections: Map<String, ManageCodeRowSelection>,
    /** Set only by an operator row reorder or MIX toggle, never by target hydration. */
    val mixLayoutDirty: Boolean = false,
    val mixConflict: List<String> = emptyList()
)

internal fun updateMixLayoutSelection(
    state: ManageCodeMaterialState,
    editablePgm: String,
    selection: ManageCodeRowSelection
): ManageCodeMaterialState {
    val mixChanged = state.selections[editablePgm]?.mix != selection.mix
    return state.copy(
        selections = state.selections + (editablePgm to selection),
        mixLayoutDirty = state.mixLayoutDirty || mixChanged
    )
}

internal fun updateMixLayoutSelections(
    state: ManageCodeMaterialState,
    selections: Map<String, ManageCodeRowSelection>
): ManageCodeMaterialState {
    val mixChanged = selections.any { (pgm, selection) -> state.selections[pgm]?.mix != selection.mix }
    return state.copy(
        selections = selections,
        mixLayoutDirty = state.mixLayoutDirty || mixChanged
    )
}

internal fun updateMixLayoutRows(
    state: ManageCodeMaterialState,
    rows: List<ManageCodeRow>
): ManageCodeMaterialState {
    val orderChanged = state.rows.map { it.editablePgm } != rows.map { it.editablePgm }
    return state.copy(rows = rows, mixLayoutDirty = state.mixLayoutDirty || orderChanged)
}

/** Seeds an untouched multi-active screen from the chosen target without replacing operator edits. */
internal fun selectReplacementTarget(
    state: ManageCodeMaterialState,
    target: MixGenerationTarget.ReplaceActive
): ManageCodeMaterialState {
    if (state.mixLayoutDirty) return state
    val rows = com.kkc.sheettracker.data.mixservice.applyExistingOrder(state.rows, target.programsBaseline)
    val selections = rows.associate { row ->
        val existing = state.selections[row.editablePgm] ?: ManageCodeRowSelection()
        row.editablePgm to existing.copy(mix = row.editablePgm in target.programsBaseline)
    }
    return state.copy(rows = rows, selections = selections)
}

@Composable
fun ManageCodeMaterialCard(
    state: ManageCodeMaterialState,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onRowsReordered: (List<ManageCodeRow>) -> Unit,
    onSelectionChanged: (editablePgm: String, ManageCodeRowSelection) -> Unit,
    onSelectAll: (field: String, checked: Boolean) -> Unit,
    loadThumbnail: suspend (ManageCodeRow) -> ImageBitmap?
) {
    val rowsState = remember(state.rows) { mutableStateOf(state.rows) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val current = rowsState.value.toMutableList()
        if (from.index in current.indices && to.index in current.indices) {
            current.add(to.index, current.removeAt(from.index))
            rowsState.value = current
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = if (state.hasPgmsOnThisCnc) 1.dp else 0.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevronRotation")
                    IconButton(onClick = onExpandToggle, enabled = state.hasPgmsOnThisCnc) {
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            modifier = Modifier.rotate(chevronRotation)
                        )
                    }
                    Text(
                        text = state.materialName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (!state.hasPgmsOnThisCnc) {
                    Text(
                        text = "No PGMs on this CNC",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("MIX", "PUNLOAD", "2ND", "SUPER").forEach { field ->
                            val unlocked = state.rows.filter { it.editablePgm !in state.locked }
                            val allChecked = unlocked.isNotEmpty() && unlocked.all { row ->
                                val selection = state.selections[row.editablePgm] ?: ManageCodeRowSelection()
                                when (field) {
                                    "MIX" -> selection.mix
                                    "PUNLOAD" -> selection.removePUnload
                                    "2ND" -> selection.secondPass
                                    "SUPER" -> selection.superPass
                                    else -> false
                                }
                            }
                            LabeledCheckbox(
                                label = if (field == "PUNLOAD") "PUN" else if (field == "SUPER") "SUP" else field,
                                checked = allChecked,
                                onCheckedChange = { onSelectAll(field, !allChecked) }
                            )
                        }
                    }
                }
            }

            if (state.mixConflict.isNotEmpty()) {
                Text(
                    text = "Multiple mixes already exist for this material (${state.mixConflict.joinToString()}) — resolve on the CNC before generating",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded && state.hasPgmsOnThisCnc,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = (rowsState.value.size.coerceAtMost(4) * 132).dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    itemsIndexed(rowsState.value, key = { _, row -> "${row.pageNumber}-${row.editablePgm}" }) { index, row ->
                        val locked = row.editablePgm in state.locked
                        val selection = state.selections[row.editablePgm] ?: ManageCodeRowSelection()
                        ReorderableItem(reorderState, key = "${row.pageNumber}-${row.editablePgm}") {
                            ManageCodeRowView(
                                row = row,
                                locked = locked,
                                zebra = index % 2 == 1,
                                selection = selection,
                                onSelectionChanged = { onSelectionChanged(row.editablePgm, it) },
                                loadThumbnail = loadThumbnail,
                                dragModifier = if (locked) Modifier else Modifier.draggableHandle(
                                    onDragStopped = { onRowsReordered(rowsState.value) }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManageCodeRowView(
    row: ManageCodeRow,
    locked: Boolean,
    zebra: Boolean,
    selection: ManageCodeRowSelection,
    onSelectionChanged: (ManageCodeRowSelection) -> Unit,
    loadThumbnail: suspend (ManageCodeRow) -> ImageBitmap?,
    dragModifier: Modifier
) {
    // Only fetched when this row is actually composed -- collapsed cards and rows scrolled
    // out of the inner LazyColumn's viewport never touch the loader.
    var thumbnail by remember(row.pageNumber) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(row.pageNumber, row.thumbnailPath) {
        thumbnail = loadThumbnail(row)
    }
    val thumbnailAlpha by animateFloatAsState(if (thumbnail != null) 1f else 0f, label = "thumbnailFadeIn")

    // Mirrors SheetViewerScreen's own Sheet Navigator row (thumbnail size, card shape, padding)
    // so Manage Code's list reads as the same kind of sheet-list UI, not a smaller/different one.
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        color = if (zebra) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (locked) {
                Icon(Icons.Filled.Lock, contentDescription = "Locked", modifier = Modifier.size(20.dp))
            } else {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "Drag to reorder",
                    modifier = Modifier.size(24.dp).then(dragModifier)
                )
            }
            Box(
                modifier = Modifier
                    .size(width = 148.dp, height = 100.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                val currentThumbnail = thumbnail
                if (currentThumbnail != null) {
                    androidx.compose.foundation.Image(
                        bitmap = currentThumbnail,
                        contentDescription = "Sheet ${row.pageNumber} thumbnail",
                        modifier = Modifier.fillMaxSize().padding(2.dp).alpha(thumbnailAlpha),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                    )
                } else {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = "Image icon",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.pgmFiles.joinToString(" + "),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (!locked) {
                LabeledCheckbox("MIX", selection.mix) { onSelectionChanged(selection.copy(mix = it)) }
                LabeledCheckbox("PUN", selection.removePUnload) { onSelectionChanged(selection.copy(removePUnload = it)) }
                LabeledCheckbox("2ND", selection.secondPass) { onSelectionChanged(toggleSecondPass(selection, it)) }
                LabeledCheckbox("SUP", selection.superPass, visible = selection.secondPass) {
                    onSelectionChanged(toggleSuperPass(selection, it))
                }
            }
        }
    }
}

@Composable
private fun LabeledCheckbox(
    label: String,
    checked: Boolean,
    visible: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    // `visible` never removes this from composition -- SUP always reserves its slot so
    // MIX/PUN/2ND don't shift left when it fades in on 2ND being checked.
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "checkboxVisibility")
    val scale by animateFloatAsState(
        if (checked) 1.1f else 1f,
        animationSpec = spring(dampingRatio = 0.5f),
        label = "checkboxPop"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp).alpha(alpha)) {
        Checkbox(
            checked = checked,
            onCheckedChange = if (visible) onCheckedChange else ({}),
            modifier = Modifier.size(32.dp).graphicsLayer(scaleX = scale, scaleY = scale)
        )
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
    }
}

sealed class ManageCodeMaterialResult {
    object Success : ManageCodeMaterialResult()
    data class Blocked(val reason: String) : ManageCodeMaterialResult()
}

internal fun mixCatalogUnavailableMessage(result: MixCatalogFetchResult): String? = when (result) {
    is MixCatalogFetchResult.Success -> null
    MixCatalogFetchResult.NetworkError ->
        "Mix catalog unavailable — update the CNC mix service, then refresh"
}

private data class PendingMixAction(
    val materialName: String,
    val catalog: MixCatalogSnapshot
)

private data class PendingDuplicateMixAction(
    val materialName: String,
    val target: MixGenerationTarget,
    val duplicates: List<com.kkc.sheettracker.data.mixservice.DuplicateMixWarning>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCodeScreen(
    scanCoordinator: ScanCoordinator,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    jobFolderName: String,
    onlyMaterialName: String?,
    onBack: () -> Unit,
    client: MixServiceClient = remember { MixServiceClient() }
) {
    val appContext = LocalContext.current.applicationContext
    val mixCatalogRepository = remember(client, appContext) {
        MixCatalogRepository(client, MixCatalogCache.inAppFiles(appContext))
    }
    val scanState by scanCoordinator.state.collectAsState()
    val unifiedEngine = remember(scanState.snapshot.basePath) {
        UnifiedMetadataEngineRegistry.getOrCreate(File(scanState.snapshot.basePath), com.kkc.sheettracker.BuildConfig.DEBUG)
    }
    val job by produceState<com.kkc.sheettracker.data.models.Job?>(initialValue = null, unifiedEngine, jobFolderName) {
        value = withContext(Dispatchers.IO) { unifiedEngine.getCncSnapshot(jobFolderName)?.job }
    }
    var reachable by remember { mutableStateOf<Boolean?>(null) }
    var refreshAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(refreshAttempt) { reachable = client.isReachable() }

    val materials = job?.materials.orEmpty().filter { onlyMaterialName == null || it.materialName == onlyMaterialName }
    var materialStates by remember { mutableStateOf<Map<String, ManageCodeMaterialState>>(emptyMap()) }
    var materialCatalogs by remember { mutableStateOf<Map<String, MixCatalogSnapshot>>(emptyMap()) }
    var catalogLoadErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var expandedMaterial by remember { mutableStateOf(onlyMaterialName) }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<String, ManageCodeMaterialResult>>(emptyMap()) }
    var pendingMixAction by remember { mutableStateOf<PendingMixAction?>(null) }
    var pendingDuplicateWarning by remember { mutableStateOf<PendingDuplicateMixAction?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadMaterialState(
        material: com.kkc.sheettracker.data.models.Material,
        catalog: MixCatalogSnapshot?,
        loadLiveData: Boolean = true
    ): ManageCodeMaterialState {
        val pgms = if (loadLiveData) client.listPgms(jobFolderName, material.materialName)
            else emptyList<com.kkc.sheettracker.data.mixservice.PgmInventoryItem>()
        val pages = material.metadata?.pages.orEmpty()
        var rows = buildManageCodeRows(pages)
        val hasPgms = pgms.isNotEmpty() || (!loadLiveData && rows.isNotEmpty())
        val existingMix = catalog?.entries
            ?.filter { it.lifecycle == com.kkc.sheettracker.data.mixservice.MixLifecycle.ACTIVE }
            ?.singleOrNull()
        if (existingMix != null) {
            rows = com.kkc.sheettracker.data.mixservice.applyExistingOrder(rows, existingMix.programs)
        }
        val editHistory = if (loadLiveData) client.listPgmEdits(jobFolderName, material.materialName) else null
        val locked = rows.filter { row ->
            isRowLocked(progressStore.getSheetStatus(jobFolderName, material.pdfFilename, row.pageNumber, material.fileFingerprint))
        }.map { it.editablePgm }.toSet()
        val selections = rows.associate { row ->
            row.editablePgm to deriveRowSelection(
                row.editablePgm,
                existingMix?.programs.orEmpty(),
                hasExistingMix = existingMix != null,
                editHistory = editHistory
            )
        }
        val state = ManageCodeMaterialState(
            materialName = material.materialName,
            hasPgmsOnThisCnc = hasPgms,
            rows = rows,
            locked = locked,
            selections = selections,
            mixConflict = emptyList()
        )
        return state
    }

    LaunchedEffect(job, reachable, refreshAttempt) {
        if (job == null) return@LaunchedEffect
        for (material in materials) {
            mixCatalogRepository.cached(jobFolderName, material.materialName)?.let { cached ->
                materialCatalogs = materialCatalogs + (material.materialName to cached)
                materialStates = materialStates + (material.materialName to loadMaterialState(
                    material, cached, loadLiveData = reachable == true))
            }
            if (reachable != true) {
                if (materialStates[material.materialName] == null) {
                    materialStates = materialStates + (material.materialName to loadMaterialState(
                        material, null, loadLiveData = false))
                }
                continue
            }
            val refreshed = mixCatalogRepository.refresh(jobFolderName, material.materialName)
            if (refreshed is MixCatalogFetchResult.Success) {
                materialCatalogs = materialCatalogs + (material.materialName to refreshed.snapshot)
                materialStates = materialStates + (material.materialName to loadMaterialState(material, refreshed.snapshot))
                catalogLoadErrors = catalogLoadErrors - material.materialName
            } else {
                if (materialStates[material.materialName] == null) {
                    materialStates = materialStates + (material.materialName to loadMaterialState(material, null))
                }
                mixCatalogUnavailableMessage(refreshed)?.let { message ->
                    catalogLoadErrors = catalogLoadErrors + (material.materialName to message)
                }
            }
        }
    }

    fun updateSelection(materialName: String, editablePgm: String, selection: ManageCodeRowSelection) {
        val state = materialStates[materialName] ?: return
        materialStates = materialStates + (materialName to updateMixLayoutSelection(state, editablePgm, selection))
    }

    fun updateRows(materialName: String, rows: List<ManageCodeRow>) {
        val state = materialStates[materialName] ?: return
        materialStates = materialStates + (materialName to updateMixLayoutRows(state, rows))
    }

    suspend fun refreshMaterialCatalog(materialName: String): MixCatalogSnapshot? {
        val material = materials.firstOrNull { it.materialName == materialName } ?: return null
        val refreshed = mixCatalogRepository.refresh(jobFolderName, materialName)
        if (refreshed !is MixCatalogFetchResult.Success) return null
        materialCatalogs = materialCatalogs + (materialName to refreshed.snapshot)
        materialStates = materialStates + (materialName to loadMaterialState(material, refreshed.snapshot))
        return refreshed.snapshot
    }

    suspend fun applyMutationCatalog(materialName: String, snapshot: MixCatalogSnapshot) {
        val material = materials.firstOrNull { it.materialName == materialName } ?: return
        materialCatalogs = materialCatalogs + (materialName to snapshot)
        materialStates = materialStates + (materialName to loadMaterialState(material, snapshot))
    }

    suspend fun generateOne(
        materialName: String,
        target: MixGenerationTarget,
        ignoreDuplicates: Boolean
    ): ManageCodeMaterialResult {
        val catalog = materialCatalogs[materialName]
            ?: return ManageCodeMaterialResult.Blocked("Mix catalog unavailable — refresh and try again")
        val state = materialStates[materialName] ?: return ManageCodeMaterialResult.Blocked("No data")
        val plan = resolveMixGenerationTarget(target, catalog, materialName)
            ?: return ManageCodeMaterialResult.Blocked("Mix catalog changed or has external files — choose an action again")
        val change = buildManageCodeChange(
            rows = state.rows,
            selections = state.selections,
            locked = state.locked,
            originalPrograms = plan.programsBaseline
        )
        if (change.orderOrMembershipChanged && !ignoreDuplicates) {
            val duplicates = findCrossMixDuplicates(change.programs, plan.name, catalog)
            if (duplicates.isNotEmpty()) {
                pendingDuplicateWarning = PendingDuplicateMixAction(materialName, target, duplicates)
                return ManageCodeMaterialResult.Blocked("Duplicate PGM membership — confirm to continue")
            }
        }
        if (change.orderOrMembershipChanged) {
            val mutationResult = when (plan.mutation) {
                MixCatalogMutation.CREATE -> mixCatalogRepository.createMix(
                    job = jobFolderName,
                    material = materialName,
                    name = plan.name,
                    programs = change.programs,
                    expectedRevision = plan.expectedRevision
                )
                MixCatalogMutation.REPLACE -> mixCatalogRepository.replaceMix(
                    job = jobFolderName,
                    material = materialName,
                    name = plan.name,
                    programs = change.programs,
                    expectedRevision = plan.expectedRevision
                )
            }
            when (val mutation = mutationResult) {
                is MixCatalogMutationResult.Success -> applyMutationCatalog(materialName, mutation.snapshot)
                is MixCatalogMutationResult.SyncFailed -> applyMutationCatalog(materialName, mutation.snapshot)
                MixCatalogMutationResult.CatalogChanged -> {
                    refreshMaterialCatalog(materialName)?.let { pendingMixAction = PendingMixAction(materialName, it) }
                    return ManageCodeMaterialResult.Blocked("Mix catalog changed — choose an action again")
                }
                else -> return ManageCodeMaterialResult.Blocked("Mix write failed: ${mixMutationErrorMessage(mutation)}")
            }
        }
        if (change.editRows.isNotEmpty()) {
            val submitResult = client.submitPgmEdits(jobFolderName, materialName, UUID.randomUUID().toString(), change.editRows)
            if (submitResult !is PgmEditSubmitResult.Success) {
                return ManageCodeMaterialResult.Blocked("Second-pass edit failed: ${pgmEditErrorMessage(submitResult)}")
            }
        }
        return ManageCodeMaterialResult.Success
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (onlyMaterialName != null) "Manage code — $onlyMaterialName" else "Manage code") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (reachable == false || catalogLoadErrors.isNotEmpty()) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        if (reachable == false) "Mix service unreachable — showing saved mix information"
                        else catalogLoadErrors.values.first(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { refreshAttempt += 1 }) { Text("Retry") }
                }
            }
            LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                    itemsIndexed(materials, key = { _, m -> m.materialName }) { _, material ->
                        val state = materialStates[material.materialName] ?: return@itemsIndexed
                        // Cache persists across expand/collapse so re-expanding a card doesn't
                        // re-fetch; the fetch itself only fires per-row, on first composition of
                        // that row (see ManageCodeRowView), not eagerly for the whole material.
                        val thumbnailCache = remember(material.materialName) { mutableStateMapOf<Int, ImageBitmap?>() }
                        val loadThumbnail: suspend (ManageCodeRow) -> ImageBitmap? = loader@{ row ->
                            if (thumbnailCache.containsKey(row.pageNumber)) return@loader thumbnailCache[row.pageNumber]
                            val pdfFile = jobRepository.getPdfFile(jobFolderName, material.pdfFilename)
                            val bitmap = withContext(Dispatchers.IO) {
                                com.kkc.sheettracker.ui.viewer.loadSheetThumbnailForToc(
                                    pdfFile,
                                    row.pageNumber - 1,
                                    row.thumbnailPath
                                )
                            }?.asImageBitmap()
                            thumbnailCache[row.pageNumber] = bitmap
                            bitmap
                        }
                        ManageCodeMaterialCard(
                            state = state,
                            expanded = expandedMaterial == material.materialName,
                            onExpandToggle = {
                                expandedMaterial = if (expandedMaterial == material.materialName) null else material.materialName
                            },
                            onRowsReordered = { updateRows(material.materialName, it) },
                            onSelectionChanged = { pgm, sel -> updateSelection(material.materialName, pgm, sel) },
                            onSelectAll = { field, checked ->
                                val updated = state.selections.mapValues { (pgm, sel) ->
                                    if (pgm in state.locked) sel else when (field) {
                                        "MIX" -> sel.copy(mix = checked)
                                        "PUNLOAD" -> sel.copy(removePUnload = checked)
                                        "2ND" -> toggleSecondPass(sel, checked)
                                        "SUPER" -> toggleSuperPass(sel, checked)
                                        else -> sel
                                    }
                                }
                                materialStates = materialStates + (
                                    material.materialName to updateMixLayoutSelections(state, updated)
                                )
                            },
                            loadThumbnail = loadThumbnail
                        )
                        results[material.materialName]?.let { result ->
                            val label = when (result) {
                                ManageCodeMaterialResult.Success -> "Done"
                                is ManageCodeMaterialResult.Blocked -> result.reason
                            }
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                        catalogLoadErrors[material.materialName]?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val next = mutableMapOf<String, ManageCodeMaterialResult>()
                                    for (material in materials) {
                                        if (!(materialStates[material.materialName]?.hasPgmsOnThisCnc ?: false)) continue
                                        val catalog = materialCatalogs[material.materialName]
                                        if (catalog == null) {
                                            next[material.materialName] = ManageCodeMaterialResult.Blocked(
                                                "Mix catalog unavailable — refresh and try again"
                                            )
                                            continue
                                        }
                                        val automaticTarget = mixActionDialogContent(catalog).automaticTarget
                                        if (automaticTarget == null) {
                                            pendingMixAction = PendingMixAction(material.materialName, catalog)
                                            break
                                        }
                                        next[material.materialName] = generateOne(
                                            material.materialName,
                                            automaticTarget,
                                            ignoreDuplicates = false
                                        )
                                    }
                                    results = results + next
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text(if (busy) "Generating…" else "Generate mixes and edit code")
                        }
                    }
            }
        }

        pendingMixAction?.let { pending ->
            val originalName = pending.catalog.entries
                .firstOrNull { it.lifecycle == com.kkc.sheettracker.data.mixservice.MixLifecycle.ACTIVE }
                ?.name ?: defaultMixName(pending.materialName)
            MixActionDialog(
                catalog = pending.catalog,
                originalName = originalName,
                onDismiss = { pendingMixAction = null },
                onTargetSelected = { target ->
                    pendingMixAction = null
                    if (target is MixGenerationTarget.ReplaceActive) {
                        materialStates[pending.materialName]?.let { current ->
                            materialStates = materialStates + (
                                pending.materialName to selectReplacementTarget(current, target)
                            )
                        }
                    }
                    scope.launch {
                        busy = true
                        val result = generateOne(pending.materialName, target, ignoreDuplicates = false)
                        results = results + (pending.materialName to result)
                        busy = false
                    }
                },
                onDeleteExternal = { filename ->
                    pendingMixAction = null
                    scope.launch {
                        busy = true
                        val result = when (val mutation = mixCatalogRepository.deleteExternalMix(
                            job = jobFolderName,
                            material = pending.materialName,
                            filename = filename,
                            expectedRevision = pending.catalog.revision
                        )) {
                            is MixCatalogMutationResult.Success -> {
                                applyMutationCatalog(pending.materialName, mutation.snapshot)
                                ManageCodeMaterialResult.Success
                            }
                            is MixCatalogMutationResult.SyncFailed -> {
                                applyMutationCatalog(pending.materialName, mutation.snapshot)
                                ManageCodeMaterialResult.Success
                            }
                            MixCatalogMutationResult.CatalogChanged -> {
                                refreshMaterialCatalog(pending.materialName)?.let {
                                    pendingMixAction = PendingMixAction(pending.materialName, it)
                                }
                                ManageCodeMaterialResult.Blocked("Mix catalog changed — choose an action again")
                            }
                            else -> ManageCodeMaterialResult.Blocked(
                                "External mix deletion failed: ${mixMutationErrorMessage(mutation)}"
                            )
                        }
                        results = results + (pending.materialName to result)
                        busy = false
                    }
                }
            )
        }

        pendingDuplicateWarning?.let { pending ->
            AlertDialog(
                onDismissRequest = { pendingDuplicateWarning = null },
                title = { Text("Already in another mix") },
                text = {
                    Text(pending.duplicates.joinToString("\n") { "${it.pgm} is already in ${it.otherMixName}" })
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDuplicateWarning = null
                        scope.launch {
                            val result = generateOne(pending.materialName, pending.target, ignoreDuplicates = true)
                            results = results + (pending.materialName to result)
                        }
                    }) { Text("Continue anyway") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDuplicateWarning = null }) { Text("Go back and edit") }
                }
            )
        }
    }
}

internal fun mixMutationErrorMessage(result: MixCatalogMutationResult): String = when (result) {
    is MixCatalogMutationResult.Success, is MixCatalogMutationResult.SyncFailed -> ""
    MixCatalogMutationResult.CatalogChanged -> "Mix catalog changed"
    MixCatalogMutationResult.ExternalMixesPresent -> "External mix files must be removed first"
    MixCatalogMutationResult.EditBusy -> "Another edit is in progress — try again"
    MixCatalogMutationResult.CompileBusy -> "CNC is busy compiling another mix — try again"
    MixCatalogMutationResult.WinxisoTimeout -> "Compile timed out — try again"
    is MixCatalogMutationResult.DuplicateName ->
        "A mix named ${result.name} already exists. Mix names are case-insensitive and must be unique across all definitions."
    is MixCatalogMutationResult.MissingProgram -> "PGM file missing: ${result.pgm}"
    is MixCatalogMutationResult.HistorySyncError -> result.message.ifBlank { "History sync failed" }
    is MixCatalogMutationResult.BadRequest -> result.message.ifBlank { "Invalid mix request" }
    MixCatalogMutationResult.NetworkError -> "Could not reach the mix service"
}

private fun pgmEditErrorMessage(result: PgmEditSubmitResult): String = when (result) {
    is PgmEditSubmitResult.Success -> ""
    PgmEditSubmitResult.Disabled -> "Second-pass editing is disabled on this CNC"
    PgmEditSubmitResult.EditBusy -> "Another edit is in progress — try again"
    PgmEditSubmitResult.CompileBusy -> "CNC is busy compiling — try again"
    PgmEditSubmitResult.WinxisoTimeout -> "Edit timed out — try again"
    PgmEditSubmitResult.NetworkError -> "Could not reach the mix service"
}
