package com.kkc.sheettracker.ui.managecode

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.mixservice.ManageCodeRow
import com.kkc.sheettracker.data.mixservice.ManageCodeRowSelection
import com.kkc.sheettracker.data.mixservice.MixServiceClient
import com.kkc.sheettracker.data.mixservice.MixWriteResult
import com.kkc.sheettracker.data.mixservice.PgmEditSubmitResult
import com.kkc.sheettracker.data.mixservice.buildManageCodeChange
import com.kkc.sheettracker.data.mixservice.buildManageCodeRows
import com.kkc.sheettracker.data.mixservice.deriveRowSelection
import com.kkc.sheettracker.data.mixservice.findCrossMixDuplicates
import com.kkc.sheettracker.data.mixservice.isRowLocked
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
    val selections: Map<String, ManageCodeRowSelection>
)

@Composable
fun ManageCodeMaterialCard(
    state: ManageCodeMaterialState,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onRowsReordered: (List<ManageCodeRow>) -> Unit,
    onSelectionChanged: (editablePgm: String, ManageCodeRowSelection) -> Unit,
    onSelectAll: (field: String, checked: Boolean) -> Unit,
    thumbnailFor: (pageNumber: Int) -> androidx.compose.ui.graphics.ImageBitmap?
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
                    IconButton(onClick = onExpandToggle, enabled = state.hasPgmsOnThisCnc) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand"
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

            if (expanded && state.hasPgmsOnThisCnc) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = (rowsState.value.size.coerceAtMost(4) * 132).dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    itemsIndexed(rowsState.value, key = { _, row -> "${row.pageNumber}-${row.editablePgm}" }) { _, row ->
                        val locked = row.editablePgm in state.locked
                        val selection = state.selections[row.editablePgm] ?: ManageCodeRowSelection()
                        ReorderableItem(reorderState, key = "${row.pageNumber}-${row.editablePgm}") {
                            ManageCodeRowView(
                                row = row,
                                locked = locked,
                                selection = selection,
                                onSelectionChanged = { onSelectionChanged(row.editablePgm, it) },
                                thumbnail = thumbnailFor(row.pageNumber),
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
    selection: ManageCodeRowSelection,
    onSelectionChanged: (ManageCodeRowSelection) -> Unit,
    thumbnail: androidx.compose.ui.graphics.ImageBitmap?,
    dragModifier: Modifier
) {
    // Mirrors SheetViewerScreen's own Sheet Navigator row (thumbnail size, card shape, padding)
    // so Manage Code's list reads as the same kind of sheet-list UI, not a smaller/different one.
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
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
                if (thumbnail != null) {
                    androidx.compose.foundation.Image(
                        bitmap = thumbnail,
                        contentDescription = "Sheet ${row.pageNumber} thumbnail",
                        modifier = Modifier.fillMaxSize().padding(2.dp),
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
    val scanState by scanCoordinator.state.collectAsState()
    val unifiedEngine = remember(scanState.snapshot.basePath) {
        UnifiedMetadataEngineRegistry.getOrCreate(File(scanState.snapshot.basePath), com.kkc.sheettracker.BuildConfig.DEBUG)
    }
    val job by produceState<com.kkc.sheettracker.data.models.Job?>(initialValue = null, unifiedEngine, jobFolderName) {
        value = withContext(Dispatchers.IO) { unifiedEngine.getCncSnapshot(jobFolderName)?.job }
    }
    var reachable by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { reachable = client.isReachable() }

    val materials = job?.materials.orEmpty().filter { onlyMaterialName == null || it.materialName == onlyMaterialName }
    var materialStates by remember { mutableStateOf<Map<String, ManageCodeMaterialState>>(emptyMap()) }
    var mixNames by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var expandedMaterial by remember { mutableStateOf(onlyMaterialName) }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<String, ManageCodeMaterialResult>>(emptyMap()) }
    var pendingDuplicateWarning by remember { mutableStateOf<Pair<String, List<com.kkc.sheettracker.data.mixservice.DuplicateMixWarning>>?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadMaterialState(material: com.kkc.sheettracker.data.models.Material): Pair<ManageCodeMaterialState, String?> {
        val pgms = client.listPgms(jobFolderName, material.materialName)
        val hasPgms = pgms.isNotEmpty()
        val pages = material.metadata?.pages.orEmpty()
        var rows = buildManageCodeRows(pages)
        val existingMixes = client.listMixes(jobFolderName, material.materialName)
        // TODO: if existingMixes has more than one entry, this silently picks the first rather than surfacing a conflict (design spec Section 7) — not handled yet.
        val existingMix = existingMixes?.firstOrNull()
        if (existingMix != null) {
            rows = com.kkc.sheettracker.data.mixservice.applyExistingOrder(rows, existingMix.programs)
        }
        val editHistory = client.listPgmEdits(jobFolderName, material.materialName)
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
            selections = selections
        )
        return state to existingMix?.name
    }

    LaunchedEffect(job, reachable) {
        if (job == null || reachable != true) return@LaunchedEffect
        val nextStates = mutableMapOf<String, ManageCodeMaterialState>()
        val nextNames = mutableMapOf<String, String?>()
        for (material in materials) {
            val (state, mixName) = loadMaterialState(material)
            nextStates[material.materialName] = state
            nextNames[material.materialName] = mixName
        }
        materialStates = nextStates
        mixNames = nextNames
    }

    fun updateSelection(materialName: String, editablePgm: String, selection: ManageCodeRowSelection) {
        val state = materialStates[materialName] ?: return
        materialStates = materialStates + (materialName to state.copy(
            selections = state.selections + (editablePgm to selection)
        ))
    }

    fun updateRows(materialName: String, rows: List<ManageCodeRow>) {
        val state = materialStates[materialName] ?: return
        materialStates = materialStates + (materialName to state.copy(rows = rows))
    }

    suspend fun generateOne(materialName: String, ignoreDuplicates: Boolean): ManageCodeMaterialResult {
        val state = materialStates[materialName] ?: return ManageCodeMaterialResult.Blocked("No data")
        val existingName = mixNames[materialName]
        val change = buildManageCodeChange(
            rows = state.rows,
            selections = state.selections,
            locked = state.locked,
            originalPrograms = client.listMixes(jobFolderName, materialName)?.firstOrNull { it.name == existingName }?.programs.orEmpty()
        )
        if (change.orderOrMembershipChanged && !ignoreDuplicates) {
            val allOtherMixes = client.listMixes(jobFolderName).orEmpty()
            val duplicates = findCrossMixDuplicates(change.programs, existingName ?: "", allOtherMixes)
            if (duplicates.isNotEmpty()) {
                pendingDuplicateWarning = materialName to duplicates
                return ManageCodeMaterialResult.Blocked("Duplicate PGM membership — confirm to continue")
            }
        }
        if (change.orderOrMembershipChanged) {
            val name = existingName ?: "${materialName.replace(Regex("[^A-Za-z0-9 _-]"), "")}Mix"
            val writeResult = if (existingName != null) {
                client.updateMix(jobFolderName, materialName, name, change.programs)
            } else {
                client.createMix(jobFolderName, materialName, name, change.programs)
            }
            // SyncFailed means the mix itself was created/updated successfully -- only the
            // sidecar history write failed afterward. Treating it as a failure would make the
            // caller retry, and a retry against an already-created mix name is a real
            // DuplicateName. Not blocking here; loadMaterialState()'s post-Generate refresh
            // re-reads the mix from the service's own store either way.
            if (writeResult !is MixWriteResult.Success && writeResult !is MixWriteResult.SyncFailed) {
                return ManageCodeMaterialResult.Blocked("Mix write failed: ${mixWriteErrorMessage(writeResult)}")
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
            if (reachable == false) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Mix service unreachable", color = MaterialTheme.colorScheme.error)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(materials, key = { _, m -> m.materialName }) { _, material ->
                        val state = materialStates[material.materialName] ?: return@itemsIndexed
                        val thumbnailCache = remember(material.materialName) { mutableStateMapOf<Int, androidx.compose.ui.graphics.ImageBitmap?>() }
                        LaunchedEffect(state.rows) {
                            val pdfFile = jobRepository.getPdfFile(jobFolderName, material.pdfFilename)
                            for (row in state.rows) {
                                if (thumbnailCache.containsKey(row.pageNumber)) continue
                                val bitmap = withContext(Dispatchers.IO) {
                                    com.kkc.sheettracker.ui.viewer.loadSheetThumbnailForToc(
                                        pdfFile,
                                        row.pageNumber - 1,
                                        row.thumbnailPath
                                    )
                                }
                                thumbnailCache[row.pageNumber] = bitmap?.asImageBitmap()
                            }
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
                                materialStates = materialStates + (material.materialName to state.copy(selections = updated))
                            },
                            thumbnailFor = { pageNumber -> thumbnailCache[pageNumber] }
                        )
                        results[material.materialName]?.let { result ->
                            val label = when (result) {
                                ManageCodeMaterialResult.Success -> "Done"
                                is ManageCodeMaterialResult.Blocked -> result.reason
                            }
                            Text(label, style = MaterialTheme.typography.labelSmall)
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
                                        val result = generateOne(material.materialName, ignoreDuplicates = false)
                                        next[material.materialName] = result
                                        if (result is ManageCodeMaterialResult.Success) {
                                            val (state, mixName) = loadMaterialState(material)
                                            materialStates = materialStates + (material.materialName to state)
                                            mixNames = mixNames + (material.materialName to mixName)
                                        }
                                    }
                                    results = next
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
        }

        pendingDuplicateWarning?.let { (materialName, duplicates) ->
            AlertDialog(
                onDismissRequest = { pendingDuplicateWarning = null },
                title = { Text("Already in another mix") },
                text = {
                    Text(duplicates.joinToString("\n") { "${it.pgm} is already in ${it.otherMixName}" })
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDuplicateWarning = null
                        scope.launch {
                            val result = generateOne(materialName, ignoreDuplicates = true)
                            results = results + (materialName to result)
                            if (result is ManageCodeMaterialResult.Success) {
                                materials.firstOrNull { it.materialName == materialName }?.let { material ->
                                    val (state, mixName) = loadMaterialState(material)
                                    materialStates = materialStates + (materialName to state)
                                    mixNames = mixNames + (materialName to mixName)
                                }
                            }
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

private fun mixWriteErrorMessage(result: MixWriteResult): String = when (result) {
    is MixWriteResult.Success -> ""
    is MixWriteResult.SyncFailed -> "Mix saved, but history sync failed (${result.code})"
    is MixWriteResult.DuplicateName -> "A mix named \"${result.name}\" already exists"
    MixWriteResult.UnknownJobOrMaterial -> "Job or material not found on the CNC"
    is MixWriteResult.MissingProgram -> "PGM file missing: ${result.pgm}"
    is MixWriteResult.BadRequest -> result.message.ifBlank { "Invalid mix request" }
    MixWriteResult.CompileBusy -> "CNC is busy compiling another mix — try again"
    MixWriteResult.WinxisoTimeout -> "Compile timed out — try again"
    MixWriteResult.NetworkError -> "Could not reach the mix service"
}

private fun pgmEditErrorMessage(result: PgmEditSubmitResult): String = when (result) {
    is PgmEditSubmitResult.Success -> ""
    PgmEditSubmitResult.Disabled -> "Second-pass editing is disabled on this CNC"
    PgmEditSubmitResult.EditBusy -> "Another edit is in progress — try again"
    PgmEditSubmitResult.CompileBusy -> "CNC is busy compiling — try again"
    PgmEditSubmitResult.WinxisoTimeout -> "Edit timed out — try again"
    PgmEditSubmitResult.NetworkError -> "Could not reach the mix service"
}
