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
import com.kkc.sheettracker.KKCApplication
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.ProgressStore
import com.kkc.sheettracker.data.ScanCoordinator
import com.kkc.sheettracker.data.mixservice.ManageCodeOperationAction
import com.kkc.sheettracker.data.mixservice.ManageCodeRow
import com.kkc.sheettracker.data.mixservice.ManageCodeRowSelection
import com.kkc.sheettracker.data.mixservice.ManageCodeSession
import com.kkc.sheettracker.data.mixservice.MixServiceClient
import com.kkc.sheettracker.data.mixservice.MixOperationRestoreState
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
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

data class ManageCodeMaterialState(
    val materialName: String,
    val hasPgmsOnThisCnc: Boolean,
    val rows: List<ManageCodeRow>,
    val locked: Set<String>,
    val selections: Map<String, ManageCodeRowSelection>,
    val mixConflict: List<String> = emptyList()
)

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
                    text = "Multiple mixes already exist for this material (${state.mixConflict.joinToString()}) — this material will be skipped until resolved on the CNC",
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

sealed interface ManageCodeOperationUiState {
    data object Idle : ManageCodeOperationUiState
    data object Queued : ManageCodeOperationUiState
    data object Submitting : ManageCodeOperationUiState
    data class Preparing(val fraction: Float) : ManageCodeOperationUiState
    data object Compiling : ManageCodeOperationUiState
    data class Syncing(val completedMaterials: Int, val totalMaterials: Int) : ManageCodeOperationUiState
    data object Completed : ManageCodeOperationUiState
    data class Failed(val message: String) : ManageCodeOperationUiState

    companion object {
        fun from(session: ManageCodeSession?, job: String): ManageCodeOperationUiState {
            if (session == null || session.job != job) return Idle
            val operation = session.current
            if (operation.state in setOf("failed", "interrupted")) {
                val stage = if (operation.state == "interrupted") "Operation interrupted" else "Operation failed"
                return Failed(operation.error?.takeIf { it.isNotBlank() }?.let { "$stage: $it" } ?: stage)
            }
            if (session.isTerminal && operation.state == "completed") return Completed
            return when (operation.stage) {
                "queued" -> Queued
                "submitting" -> Submitting
                "preparing" -> Preparing(
                    if (operation.totalPrograms > 0) {
                        operation.completedPrograms.toFloat() / operation.totalPrograms
                    } else {
                        0f
                    }
                )
                "compiling" -> Compiling
                "syncing" -> Syncing(session.completedMaterials, session.totalMaterials)
                else -> Syncing(session.completedMaterials, session.totalMaterials)
            }
        }
    }
}

internal data class ManageCodeScreenPresentation(
    val operationState: ManageCodeOperationUiState,
    val showContent: Boolean,
    val showUnreachableBanner: Boolean,
    val actionEnabled: Boolean,
    val canRetryRestore: Boolean,
    val restoreError: String?,
)

internal fun manageCodeScreenPresentation(
    reachable: Boolean?,
    restoreState: MixOperationRestoreState,
    session: ManageCodeSession?,
    job: String,
): ManageCodeScreenPresentation {
    val operationState = ManageCodeOperationUiState.from(session, job)
    val canRetryRestore = restoreState is MixOperationRestoreState.Failed
    val operationCanStart = operationState == ManageCodeOperationUiState.Idle ||
        operationState == ManageCodeOperationUiState.Completed ||
        operationState is ManageCodeOperationUiState.Failed
    return ManageCodeScreenPresentation(
        operationState = operationState,
        showContent = true,
        showUnreachableBanner = reachable == false,
        actionEnabled = canRetryRestore ||
            (restoreState == MixOperationRestoreState.Ready && reachable == true && operationCanStart),
        canRetryRestore = canRetryRestore,
        restoreError = (restoreState as? MixOperationRestoreState.Failed)?.message,
    )
}

internal fun manageCodeOperationLabel(
    state: ManageCodeOperationUiState,
    session: ManageCodeSession?,
): String {
    val completed = session?.completedMaterials ?: 0
    val total = session?.totalMaterials ?: 0
    val count = if (total > 0) "$completed / $total" else null
    return when (state) {
        ManageCodeOperationUiState.Idle -> "Generate mixes and edit code"
        ManageCodeOperationUiState.Queued -> listOfNotNull(count, "Queued").joinToString(" — ")
        ManageCodeOperationUiState.Submitting -> listOfNotNull(count, "Submitting").joinToString(" — ")
        is ManageCodeOperationUiState.Preparing -> {
            val operation = session?.current
            val programs = if (operation?.totalPrograms ?: 0 > 0) {
                "Preparing ${operation?.completedPrograms} / ${operation?.totalPrograms} programs"
            } else {
                "Preparing"
            }
            listOfNotNull(count, programs).joinToString(" — ")
        }
        ManageCodeOperationUiState.Compiling -> listOfNotNull(count, "Compiling").joinToString(" — ")
        is ManageCodeOperationUiState.Syncing -> listOfNotNull(count, "Syncing").joinToString(" — ")
        ManageCodeOperationUiState.Completed -> {
            val stage = if (session?.warnings?.isNotEmpty() == true || session?.current?.warning != null) {
                "Finished with warning"
            } else {
                "Finished"
            }
            listOfNotNull(count, stage).joinToString(" — ")
        }
        is ManageCodeOperationUiState.Failed -> listOfNotNull(count, "Retry required").joinToString(" — ")
    }
}

private sealed interface ManageCodeSessionPreparation {
    data class Ready(val session: ManageCodeSession) : ManageCodeSessionPreparation
    data class Duplicate(
        val material: String,
        val warnings: List<com.kkc.sheettracker.data.mixservice.DuplicateMixWarning>,
    ) : ManageCodeSessionPreparation
    data class Blocked(val message: String) : ManageCodeSessionPreparation
}

internal data class ManageCodeMaterialActionCandidate(
    val material: String,
    val hasMixConflict: Boolean,
    val actions: List<ManageCodeOperationAction>,
)

internal data class ManageCodeConflictGateDecision(
    val actions: List<ManageCodeOperationAction>,
    val conflictBlockMessage: String?,
)

internal fun manageCodeConflictGateDecision(
    candidates: List<ManageCodeMaterialActionCandidate>,
): ManageCodeConflictGateDecision {
    val conflictedMaterials = candidates.filter { it.hasMixConflict }.map { it.material }
    val actions = candidates.filterNot { it.hasMixConflict }.flatMap { it.actions }
    val conflictBlockMessage = if (actions.isEmpty() && conflictedMaterials.isNotEmpty()) {
        "Multiple mixes already exist for ${conflictedMaterials.joinToString()} — resolve on the CNC first"
    } else {
        null
    }
    return ManageCodeConflictGateDecision(actions, conflictBlockMessage)
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
    val app = LocalContext.current.applicationContext as KKCApplication
    val coordinator = app.mixOperationCoordinator
    val operationSessions by coordinator.sessions.collectAsState()
    val restoreState by coordinator.restoreState.collectAsState()
    val operationSession = operationSessions[jobFolderName]
    val scanState by scanCoordinator.state.collectAsState()
    val unifiedEngine = remember(scanState.snapshot.basePath) {
        UnifiedMetadataEngineRegistry.getOrCreate(File(scanState.snapshot.basePath), com.kkc.sheettracker.BuildConfig.DEBUG)
    }
    val job by produceState<com.kkc.sheettracker.data.models.Job?>(initialValue = null, unifiedEngine, jobFolderName) {
        value = withContext(Dispatchers.IO) { unifiedEngine.getCncSnapshot(jobFolderName)?.job }
    }
    var reachable by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { reachable = client.isReachable() }
    val screenPresentation = manageCodeScreenPresentation(
        reachable = reachable,
        restoreState = restoreState,
        session = operationSession,
        job = jobFolderName,
    )
    val operationUiState = screenPresentation.operationState

    val materials = job?.materials.orEmpty().filter { onlyMaterialName == null || it.materialName == onlyMaterialName }
    var materialStates by remember { mutableStateOf<Map<String, ManageCodeMaterialState>>(emptyMap()) }
    var mixNames by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var expandedMaterial by remember { mutableStateOf(onlyMaterialName) }
    var pendingDuplicateWarning by remember { mutableStateOf<Pair<String, List<com.kkc.sheettracker.data.mixservice.DuplicateMixWarning>>?>(null) }
    var allowedDuplicateMaterials by remember { mutableStateOf<Set<String>>(emptySet()) }
    var startRequest by remember { mutableIntStateOf(0) }
    var preflightMessage by remember { mutableStateOf<String?>(null) }
    var refreshedOperationIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun loadMaterialState(material: com.kkc.sheettracker.data.models.Material): Pair<ManageCodeMaterialState, String?> {
        val pgms = client.listPgms(jobFolderName, material.materialName)
        val hasPgms = pgms.isNotEmpty()
        val pages = material.metadata?.pages.orEmpty()
        var rows = buildManageCodeRows(pages)
        val mixLookup = client.getMix(jobFolderName, material.materialName)
        val existingMix = (mixLookup as? com.kkc.sheettracker.data.mixservice.MixLookupResult.Found)?.definition
        val mixConflict = (mixLookup as? com.kkc.sheettracker.data.mixservice.MixLookupResult.Conflict)?.names.orEmpty()
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
            selections = selections,
            mixConflict = mixConflict
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

    suspend fun buildSession(): ManageCodeSessionPreparation {
        val candidates = mutableListOf<ManageCodeMaterialActionCandidate>()
        for (material in materials) {
            val materialName = material.materialName
            val state = materialStates[materialName] ?: return ManageCodeSessionPreparation.Blocked("Material data is still loading")
            if (!state.hasPgmsOnThisCnc) continue
            if (state.mixConflict.isNotEmpty()) {
                candidates += ManageCodeMaterialActionCandidate(
                    material = materialName,
                    hasMixConflict = true,
                    actions = emptyList(),
                )
                continue
            }
            val materialActions = mutableListOf<ManageCodeOperationAction>()
            val existingName = mixNames[materialName]
            val change = buildManageCodeChange(
                rows = state.rows,
                selections = state.selections,
                locked = state.locked,
                originalPrograms = client.listMixes(jobFolderName, materialName)?.firstOrNull { it.name == existingName }?.programs.orEmpty()
            )
            if (change.orderOrMembershipChanged && materialName !in allowedDuplicateMaterials) {
                // The server check is job-wide, as is the client fallback. A PGM can collide
                // with a mix that belongs to another material in this same job.
                val duplicates = client.getPgmConflicts(jobFolderName, materialName, change.programs, existingName)
                    ?: findCrossMixDuplicates(change.programs, existingName ?: "", client.listMixes(jobFolderName).orEmpty())
                if (duplicates.isNotEmpty()) return ManageCodeSessionPreparation.Duplicate(materialName, duplicates)
            }
            if (change.orderOrMembershipChanged) {
                val name = existingName ?: "${materialName.replace(Regex("[^A-Za-z0-9 _-]"), "")}Mix"
                materialActions += ManageCodeOperationAction.mix(
                    material = materialName,
                    name = name,
                    programs = change.programs,
                    replaceExisting = existingName != null,
                )
            }
            if (change.editRows.isNotEmpty()) {
                materialActions += ManageCodeOperationAction.pgmEdits(
                    material = materialName,
                    requestId = UUID.randomUUID().toString(),
                    editRows = change.editRows,
                )
            }
            candidates += ManageCodeMaterialActionCandidate(
                material = materialName,
                hasMixConflict = false,
                actions = materialActions,
            )
        }
        val decision = manageCodeConflictGateDecision(candidates)
        return when {
            decision.conflictBlockMessage != null -> ManageCodeSessionPreparation.Blocked(decision.conflictBlockMessage)
            decision.actions.isEmpty() -> ManageCodeSessionPreparation.Blocked("No mix or code changes to submit")
            else -> ManageCodeSessionPreparation.Ready(ManageCodeSession(jobFolderName, decision.actions))
        }
    }

    LaunchedEffect(startRequest) {
        if (
            startRequest == 0 ||
            (operationSession != null && !operationSession.isCompletedSuccessfully) ||
            restoreState != MixOperationRestoreState.Ready ||
            reachable != true
        ) return@LaunchedEffect
        when (val preparation = buildSession()) {
            is ManageCodeSessionPreparation.Ready -> {
                preflightMessage = null
                allowedDuplicateMaterials = emptySet()
                coordinator.start(preparation.session)
            }
            is ManageCodeSessionPreparation.Duplicate -> {
                pendingDuplicateWarning = preparation.material to preparation.warnings
            }
            is ManageCodeSessionPreparation.Blocked -> {
                preflightMessage = preparation.message
            }
        }
    }

    val completedActions = operationSession
        ?.actions
        ?.take(operationSession.currentActionIndex)
        .orEmpty()
    LaunchedEffect(completedActions, materials) {
        completedActions.forEach { action ->
            val operationId = action.operationId ?: return@forEach
            if (operationId in refreshedOperationIds) return@forEach
            val material = materials.firstOrNull { it.materialName == action.material } ?: return@forEach
            val (state, mixName) = loadMaterialState(material)
            materialStates = materialStates + (material.materialName to state)
            mixNames = mixNames + (material.materialName to mixName)
            refreshedOperationIds = refreshedOperationIds + operationId
        }
    }

    fun retryCurrentOperation() {
        val material = operationSession?.currentAction?.material ?: operationSession?.current?.material ?: return
        coordinator.retry(jobFolderName, material)
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
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Mix service unreachable — showing last known state",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(materials, key = { _, m -> m.materialName }) { _, material ->
                        val state = materialStates[material.materialName] ?: return@itemsIndexed
                        val thumbnailCache = remember(material.materialName) { mutableStateMapOf<Int, ImageBitmap?>() }
                        val loadThumbnail: suspend (ManageCodeRow) -> ImageBitmap? = loader@{ row ->
                            if (thumbnailCache.containsKey(row.pageNumber)) return@loader thumbnailCache[row.pageNumber]
                            val pdfFile = jobRepository.getPdfFile(jobFolderName, material.pdfFilename)
                            val bitmap = withContext(Dispatchers.IO) {
                                com.kkc.sheettracker.ui.viewer.loadSheetThumbnailForToc(pdfFile, row.pageNumber - 1, row.thumbnailPath)
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
                                val updated = state.selections.mapValues { (pgm, selection) ->
                                    if (pgm in state.locked) selection else when (field) {
                                        "MIX" -> selection.copy(mix = checked)
                                        "PUNLOAD" -> selection.copy(removePUnload = checked)
                                        "2ND" -> toggleSecondPass(selection, checked)
                                        "SUPER" -> toggleSuperPass(selection, checked)
                                        else -> selection
                                    }
                                }
                                materialStates = materialStates + (material.materialName to state.copy(selections = updated))
                            },
                            loadThumbnail = loadThumbnail
                        )
                    }
                    item {
                        val isRetryable = operationUiState is ManageCodeOperationUiState.Failed
                        Button(
                            onClick = {
                                if (screenPresentation.canRetryRestore) coordinator.restore()
                                else if (isRetryable) retryCurrentOperation()
                                else startRequest += 1
                            },
                            enabled = screenPresentation.actionEnabled,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                when (operationUiState) {
                                    is ManageCodeOperationUiState.Preparing -> LinearProgressIndicator(
                                        progress = { operationUiState.fraction },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    ManageCodeOperationUiState.Compiling -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    else -> Unit
                                }
                                Text(
                                    when {
                                        restoreState == MixOperationRestoreState.Restoring -> "Restoring prior session…"
                                        screenPresentation.canRetryRestore -> "Retry session restore"
                                        isRetryable -> "Retry — ${manageCodeOperationLabel(operationUiState, operationSession)}"
                                        else -> manageCodeOperationLabel(operationUiState, operationSession)
                                    },
                                )
                            }
                        }
                        preflightMessage?.let { message ->
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        (operationUiState as? ManageCodeOperationUiState.Failed)?.let { failed ->
                            Text(
                                text = failed.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        screenPresentation.restoreError?.let { message ->
                            Text(
                                text = "Session restore failed: $message",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
        }

        pendingDuplicateWarning?.let { (materialName, duplicates) ->
            AlertDialog(
                onDismissRequest = { pendingDuplicateWarning = null },
                title = { Text("Already in another mix") },
                text = { Text(duplicates.joinToString("\n") { "${it.pgm} is already in ${it.otherMixName}" }) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDuplicateWarning = null
                        allowedDuplicateMaterials = allowedDuplicateMaterials + materialName
                        startRequest += 1
                    }) { Text("Continue anyway") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDuplicateWarning = null }) { Text("Go back and edit") }
                }
            )
        }
    }
}
