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
import com.kkc.sheettracker.data.mixservice.DuplicateMixWarning
import com.kkc.sheettracker.data.mixservice.MixOperationRestoreState
import com.kkc.sheettracker.data.mixservice.MixCatalogFetchResult
import com.kkc.sheettracker.data.mixservice.MixCatalogMutationResult
import com.kkc.sheettracker.data.mixservice.MixCatalogSnapshot
import com.kkc.sheettracker.data.mixservice.MixGenerationTarget
import com.kkc.sheettracker.data.mixservice.MaterialSubmission
import com.kkc.sheettracker.data.mixservice.MixLifecycle
import com.kkc.sheettracker.data.mixservice.buildManageCodeActions
import com.kkc.sheettracker.data.mixservice.planMaterialSubmission
import com.kkc.sheettracker.data.mixservice.buildExternalDeleteAction
import com.kkc.sheettracker.data.mixservice.buildManageCodeRows
import com.kkc.sheettracker.data.mixservice.defaultMixName
import com.kkc.sheettracker.data.mixservice.deriveRowSelection
import com.kkc.sheettracker.data.mixservice.findCrossMixDuplicates
import com.kkc.sheettracker.data.mixservice.isRowLocked
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
    val mixConflict: List<String> = emptyList(),
    /** Set only by an operator row reorder or selection edit, never by target hydration. */
    val mixLayoutDirty: Boolean = false,
    /** Active catalog mixes for display (header line + per-row "in <name>" tag). */
    val activeMixes: List<com.kkc.sheettracker.data.mixservice.MixCatalogEntry> = emptyList(),
)

/** Prefers the job-wide service check; a material snapshot is only a failure fallback. */
internal fun preSubmitPgmConflicts(
    jobWideConflicts: List<DuplicateMixWarning>?,
    programs: List<String>,
    thisMixName: String,
    catalog: MixCatalogSnapshot,
): List<DuplicateMixWarning> = jobWideConflicts
    ?: findCrossMixDuplicates(programs, thisMixName, catalog)

/** Completion refreshes are scoped to both the action and the generated session. */
internal fun catalogCompletionRefreshKey(
    sessionToken: Long,
    actionIndex: Int,
    action: ManageCodeOperationAction,
): String = "$sessionToken:$actionIndex:${action.kind}:${action.material}"

internal fun isCatalogChangedFailure(session: ManageCodeSession?, job: String): Boolean =
    session?.job == job &&
        session.current.state == "failed" &&
        session.current.error == "catalog_changed"

internal enum class ExternalDeleteSubmissionPath {
    START,
    REPLACE_CATALOG_CHANGED,
}

/** A refreshed external-delete choice replaces only the retained catalog-conflict session. */
internal fun externalDeleteSubmissionPath(
    session: ManageCodeSession?,
    job: String,
): ExternalDeleteSubmissionPath = if (isCatalogChangedFailure(session, job)) {
    ExternalDeleteSubmissionPath.REPLACE_CATALOG_CHANGED
} else {
    ExternalDeleteSubmissionPath.START
}

@Suppress("UNUSED_PARAMETER")
internal fun catalogChangedRecoveryMessage(materialName: String): String =
    "Mix catalog changed — refresh and choose an action again"

internal fun catalogRecoveryTriggerAfterRefresh(
    materialName: String,
    result: MixCatalogFetchResult,
): String? = when (result) {
    is MixCatalogFetchResult.Success -> null
    MixCatalogFetchResult.NetworkError -> materialName
}

internal data class CatalogTargetRefreshDecision(
    val target: MixGenerationTarget?,
    val reopenAction: Boolean,
)

/** Drops only a target invalidated by a newer catalog revision and requests a fresh action path. */
internal fun reconcileCatalogTargetAfterRefresh(
    selectedTarget: MixGenerationTarget?,
    catalog: MixCatalogSnapshot,
    materialName: String,
): CatalogTargetRefreshDecision {
    if (selectedTarget == null) return CatalogTargetRefreshDecision(null, reopenAction = false)
    return if (resolveMixGenerationTarget(selectedTarget, catalog, materialName) == null) {
        CatalogTargetRefreshDecision(null, reopenAction = true)
    } else {
        CatalogTargetRefreshDecision(selectedTarget, reopenAction = false)
    }
}

internal fun updateMixLayoutSelection(
    state: ManageCodeMaterialState,
    editablePgm: String,
    selection: ManageCodeRowSelection
): ManageCodeMaterialState {
    val selectionChanged = state.selections[editablePgm] != selection
    return state.copy(
        selections = state.selections + (editablePgm to selection),
        mixLayoutDirty = state.mixLayoutDirty || selectionChanged
    )
}

internal fun updateMixLayoutSelections(
    state: ManageCodeMaterialState,
    selections: Map<String, ManageCodeRowSelection>
): ManageCodeMaterialState {
    val selectionsChanged = state.selections != selections
    return state.copy(
        selections = selections,
        mixLayoutDirty = state.mixLayoutDirty || selectionsChanged
    )
}

internal fun updateMixLayoutRows(
    state: ManageCodeMaterialState,
    rows: List<ManageCodeRow>
): ManageCodeMaterialState {
    val orderChanged = state.rows.map { it.editablePgm } != rows.map { it.editablePgm }
    return state.copy(rows = rows, mixLayoutDirty = state.mixLayoutDirty || orderChanged)
}

/** Applies refreshed service metadata without replacing edits made while the request was away. */
internal fun mergeCatalogRefreshMaterialState(
    current: ManageCodeMaterialState?,
    hydrated: ManageCodeMaterialState,
): ManageCodeMaterialState {
    if (current == null || !current.mixLayoutDirty) return hydrated
    return hydrated.copy(
        rows = current.rows,
        selections = current.selections,
        mixLayoutDirty = true,
    )
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
                    text = "Multiple mixes already exist for this material (${state.mixConflict.joinToString()}) — this material will be skipped until resolved on the CNC",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            state.activeMixes.forEach { entry ->
                val compile = when (entry.lastCompileOk) {
                    true -> "compiled ${entry.lastCompiledAt.orEmpty()}".trim()
                    false -> "compile failed"
                    null -> entry.status ?: "not compiled"
                }
                Text(
                    text = "Existing mix: ${entry.name} — ${entry.programs.size} PGMs, $compile",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
            val membership = remember(state.activeMixes) { mixMembershipByPgm(state.activeMixes) }

            AnimatedVisibility(
                visible = expanded && state.hasPgmsOnThisCnc,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                // Expands to show every row; the screen's outer list does the scrolling. The max is
                // only a finite bound (required inside the outer LazyColumn) -- LazyColumn wraps its
                // content, so the generous per-row allowance never adds blank space.
                LazyColumn(
                    state = listState,
                    userScrollEnabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = (rowsState.value.size * 240 + 16).dp),
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
                                mixName = row.pgmFiles.firstNotNullOfOrNull { membership[it] },
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
    mixName: String?,
    onSelectionChanged: (ManageCodeRowSelection) -> Unit,
    loadThumbnail: suspend (ManageCodeRow) -> ImageBitmap?,
    dragModifier: Modifier
) {
    // Only fetched when this row is actually composed -- collapsed cards never touch the loader.
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
                mixName?.let {
                    Text("in $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
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
                val detail = when (operation.error) {
                    "catalog_changed" -> "Mix catalog changed — refresh and choose an action again"
                    else -> operation.error?.takeIf { it.isNotBlank() }
                }
                return Failed(detail?.let { "$stage: $it" } ?: stage)
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

internal fun mixCatalogUnavailableMessage(result: MixCatalogFetchResult): String? = when (result) {
    is MixCatalogFetchResult.Success -> null
    MixCatalogFetchResult.NetworkError -> "Mix catalog unavailable — showing last known state"
}

private sealed interface ManageCodeSessionPreparation {
    data class Ready(val session: ManageCodeSession) : ManageCodeSessionPreparation
    data class SelectAction(val pending: PendingMixAction) : ManageCodeSessionPreparation
    data class Duplicate(
        val material: String,
        val target: MixGenerationTarget,
        val warnings: List<com.kkc.sheettracker.data.mixservice.DuplicateMixWarning>,
    ) : ManageCodeSessionPreparation
    data class Blocked(val message: String) : ManageCodeSessionPreparation
}

private data class PendingMixAction(
    val materialName: String,
    val catalog: MixCatalogSnapshot,
)

private data class PendingDuplicateMixAction(
    val materialName: String,
    val target: MixGenerationTarget,
    val duplicates: List<com.kkc.sheettracker.data.mixservice.DuplicateMixWarning>,
)

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
) {
    val app = LocalContext.current.applicationContext as KKCApplication
    val coordinator = app.mixOperationCoordinator
    val coordinatorScope = rememberCoroutineScope()
    // The application owns the one client shared by reads and durable mutation submission.
    val serviceClient = app.mixServiceClient
    val catalogRepository = app.mixCatalogRepository
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
    LaunchedEffect(Unit) { reachable = serviceClient.isReachable() }
    val screenPresentation = manageCodeScreenPresentation(
        reachable = reachable,
        restoreState = restoreState,
        session = operationSession,
        job = jobFolderName,
    )
    val operationUiState = screenPresentation.operationState

    val materials = job?.materials.orEmpty().filter { onlyMaterialName == null || it.materialName == onlyMaterialName }
    var materialStates by remember { mutableStateOf<Map<String, ManageCodeMaterialState>>(emptyMap()) }
    var materialCatalogs by remember { mutableStateOf<Map<String, MixCatalogSnapshot>>(emptyMap()) }
    var catalogLoadErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var expandedMaterial by remember { mutableStateOf(onlyMaterialName) }
    var pendingMixAction by remember { mutableStateOf<PendingMixAction?>(null) }
    var pendingDuplicateWarning by remember { mutableStateOf<PendingDuplicateMixAction?>(null) }
    var selectedTargets by remember { mutableStateOf<Map<String, MixGenerationTarget>>(emptyMap()) }
    var pendingMixChoice by remember { mutableStateOf<Pair<String, Set<String>>?>(null) } // material to tapped pgms
    var allowedDuplicateMaterials by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refreshAttempt by remember { mutableIntStateOf(0) }
    var startRequest by remember { mutableIntStateOf(0) }
    var preflightMessage by remember { mutableStateOf<String?>(null) }
    var refreshedOperationIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var operationSessionToken by remember { mutableLongStateOf(0L) }
    var catalogRecoveryMaterial by remember { mutableStateOf<String?>(null) }
    var catalogRecoveryAttempt by remember { mutableIntStateOf(0) }

    fun applyRefreshedCatalog(materialName: String, snapshot: MixCatalogSnapshot) {
        val decision = reconcileCatalogTargetAfterRefresh(
            selectedTarget = selectedTargets[materialName],
            catalog = snapshot,
            materialName = materialName,
        )
        if (!decision.reopenAction) return
        selectedTargets = selectedTargets - materialName
        allowedDuplicateMaterials = allowedDuplicateMaterials - materialName
        pendingMixAction = PendingMixAction(materialName, snapshot)
        preflightMessage = "Catalog refreshed — choose a new action for $materialName"
    }

    suspend fun loadMaterialState(
        material: com.kkc.sheettracker.data.models.Material,
        catalog: MixCatalogSnapshot?,
        loadLiveData: Boolean = true,
    ): ManageCodeMaterialState {
        val pgms = if (loadLiveData) serviceClient.listPgms(jobFolderName, material.materialName)
            else emptyList<com.kkc.sheettracker.data.mixservice.PgmInventoryItem>()
        val hasPgms = pgms.isNotEmpty() || (!loadLiveData && material.metadata?.pages.orEmpty().isNotEmpty())
        val pages = material.metadata?.pages.orEmpty()
        var rows = buildManageCodeRows(pages)
        val existingMix = catalog?.entries
            ?.filter { it.lifecycle == com.kkc.sheettracker.data.mixservice.MixLifecycle.ACTIVE }
            ?.singleOrNull()
        if (existingMix != null) {
            rows = com.kkc.sheettracker.data.mixservice.applyExistingOrder(rows, existingMix.programs)
        }
        val editHistory = if (loadLiveData) serviceClient.listPgmEdits(jobFolderName, material.materialName) else null
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
            mixConflict = emptyList(),
            activeMixes = catalog?.entries?.filter { it.lifecycle == MixLifecycle.ACTIVE }.orEmpty(),
        )
        return state
    }

    LaunchedEffect(refreshAttempt) { reachable = serviceClient.isReachable() }

    LaunchedEffect(job, reachable, refreshAttempt) {
        if (job == null) return@LaunchedEffect
        for (material in materials) {
            val materialName = material.materialName
            val cached = catalogRepository.cached(jobFolderName, materialName)
            if (cached != null) {
                materialCatalogs = materialCatalogs + (materialName to cached)
                val hydrated = loadMaterialState(material, cached, loadLiveData = reachable == true)
                materialStates = materialStates + (
                    materialName to mergeCatalogRefreshMaterialState(materialStates[materialName], hydrated)
                )
            } else if (materialStates[materialName] == null) {
                val hydrated = loadMaterialState(material, null, loadLiveData = reachable == true)
                materialStates = materialStates + (
                    materialName to mergeCatalogRefreshMaterialState(materialStates[materialName], hydrated)
                )
            }
            if (reachable != true) continue
            when (val refreshed = catalogRepository.refresh(jobFolderName, materialName)) {
                is MixCatalogFetchResult.Success -> {
                    materialCatalogs = materialCatalogs + (materialName to refreshed.snapshot)
                    val hydrated = loadMaterialState(material, refreshed.snapshot)
                    materialStates = materialStates + (
                        materialName to mergeCatalogRefreshMaterialState(materialStates[materialName], hydrated)
                    )
                    applyRefreshedCatalog(materialName, refreshed.snapshot)
                    catalogLoadErrors = catalogLoadErrors - materialName
                }
                MixCatalogFetchResult.NetworkError -> {
                    mixCatalogUnavailableMessage(refreshed)?.let { message ->
                        catalogLoadErrors = catalogLoadErrors + (materialName to message)
                    }
                }
            }
        }
    }

    fun updateSelection(materialName: String, editablePgm: String, selection: ManageCodeRowSelection) {
        val state = materialStates[materialName] ?: return
        materialStates = materialStates + (materialName to updateMixLayoutSelection(state, editablePgm, selection))
    }

    fun requestMixChange(materialName: String, pgms: Set<String>, checked: Boolean) {
        val state = materialStates[materialName] ?: return
        when (val outcome = mixCheckOutcome(state, pgms, checked, hasChoice = materialName in selectedTargets)) {
            is MixCheckOutcome.Prompt -> pendingMixChoice = materialName to outcome.pgms
            is MixCheckOutcome.Apply -> {
                val updated = applyMixChecks(state, outcome.pgms, outcome.checked)
                materialStates = materialStates + (materialName to updated)
                if (state.activeMixes.isNotEmpty() && shouldClearMixChoice(updated)) {
                    selectedTargets = selectedTargets - materialName
                }
            }
        }
    }

    fun updateRows(materialName: String, rows: List<ManageCodeRow>) {
        val state = materialStates[materialName] ?: return
        materialStates = materialStates + (materialName to updateMixLayoutRows(state, rows))
    }

    suspend fun buildSession(): ManageCodeSessionPreparation {
        val candidates = mutableListOf<ManageCodeMaterialActionCandidate>()
        for (material in materials) {
            val materialName = material.materialName
            val state = materialStates[materialName] ?: return ManageCodeSessionPreparation.Blocked("Material data is still loading")
            if (!state.hasPgmsOnThisCnc) continue
            val catalog = materialCatalogs[materialName]
                ?: return ManageCodeSessionPreparation.Blocked("Mix catalog unavailable — refresh and try again")
            val selectedTarget = selectedTargets[materialName]
            val selectedDecision = reconcileCatalogTargetAfterRefresh(selectedTarget, catalog, materialName)
            if (selectedDecision.reopenAction) {
                selectedTargets = selectedTargets - materialName
                allowedDuplicateMaterials = allowedDuplicateMaterials - materialName
                val pending = PendingMixAction(materialName, catalog)
                pendingMixAction = pending
                preflightMessage = "Catalog refreshed — choose a new action for $materialName"
                return ManageCodeSessionPreparation.SelectAction(pending)
            }
            if (catalog.entries.any { it.lifecycle == MixLifecycle.EXTERNAL }) {
                return ManageCodeSessionPreparation.SelectAction(PendingMixAction(materialName, catalog))
            }
            val submission = planMaterialSubmission(
                rows = state.rows,
                selections = state.selections,
                locked = state.locked,
                catalog = catalog,
                materialName = materialName,
                selectedTarget = selectedDecision.target,
                automaticTarget = mixActionDialogContent(catalog).automaticTarget,
            )
            val (plan, change) = when (submission) {
                MaterialSubmission.NeedsTarget ->
                    return ManageCodeSessionPreparation.SelectAction(PendingMixAction(materialName, catalog))
                MaterialSubmission.StaleTarget ->
                    return ManageCodeSessionPreparation.Blocked("Mix catalog changed — choose an action again")
                is MaterialSubmission.Ready -> submission.plan to submission.change
            }
            if (!change.orderOrMembershipChanged && change.editRows.isEmpty()) continue
            if (plan != null && change.orderOrMembershipChanged) {
                val target = selectedDecision.target ?: MixGenerationTarget.FirstDefault
                val duplicates = preSubmitPgmConflicts(
                    jobWideConflicts = serviceClient.getPgmConflicts(
                        job = jobFolderName,
                        material = materialName,
                        programs = change.programs,
                        exclude = plan.name,
                    ),
                    programs = change.programs,
                    thisMixName = plan.name,
                    catalog = catalog,
                )
                if (duplicates.isNotEmpty() && materialName !in allowedDuplicateMaterials) {
                    return ManageCodeSessionPreparation.Duplicate(materialName, target, duplicates)
                }
            }
            val materialActions = buildManageCodeActions(
                job = jobFolderName,
                material = materialName,
                plan = plan,
                change = change,
                requestId = UUID.randomUUID().toString(),
            )
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
        val catalogChangedFailure = isCatalogChangedFailure(operationSession, jobFolderName)
        if (
            startRequest == 0 ||
            (operationSession != null && !operationSession.isCompletedSuccessfully && !catalogChangedFailure) ||
            restoreState != MixOperationRestoreState.Ready ||
            reachable != true
        ) return@LaunchedEffect
        when (val preparation = buildSession()) {
            is ManageCodeSessionPreparation.Ready -> {
                preflightMessage = null
                selectedTargets = emptyMap()
                allowedDuplicateMaterials = emptySet()
                operationSessionToken += 1L
                if (catalogChangedFailure) {
                    catalogRecoveryMaterial = null
                    coordinator.replaceCatalogChangedSession(preparation.session)
                } else {
                    coordinator.start(preparation.session)
                }
            }
            is ManageCodeSessionPreparation.SelectAction -> {
                pendingMixAction = preparation.pending
            }
            is ManageCodeSessionPreparation.Duplicate -> {
                pendingDuplicateWarning = PendingDuplicateMixAction(
                    materialName = preparation.material,
                    target = preparation.target,
                    duplicates = preparation.warnings,
                )
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
        completedActions.forEachIndexed { index, action ->
            val refreshKey = catalogCompletionRefreshKey(operationSessionToken, index, action)
            if (refreshKey in refreshedOperationIds) return@forEachIndexed
            val material = materials.firstOrNull { it.materialName == action.material } ?: return@forEachIndexed
            val snapshot = catalogRepository.cached(jobFolderName, action.material)
            if (snapshot != null) {
                materialCatalogs = materialCatalogs + (material.materialName to snapshot)
                val hydrated = loadMaterialState(material, snapshot)
                materialStates = materialStates + (
                    material.materialName to mergeCatalogRefreshMaterialState(materialStates[material.materialName], hydrated)
                )
            } else {
                val hydrated = loadMaterialState(material, null)
                materialStates = materialStates + (
                    material.materialName to mergeCatalogRefreshMaterialState(materialStates[material.materialName], hydrated)
                )
            }
            refreshedOperationIds = refreshedOperationIds + refreshKey
        }
    }

    LaunchedEffect(catalogRecoveryAttempt, job, reachable) {
        val materialName = catalogRecoveryMaterial ?: return@LaunchedEffect
        if (job == null || reachable != true) return@LaunchedEffect
        val material = materials.firstOrNull { it.materialName == materialName }
            ?: return@LaunchedEffect
        when (val refreshed = catalogRepository.refresh(jobFolderName, materialName)) {
            is MixCatalogFetchResult.Success -> {
                materialCatalogs = materialCatalogs + (materialName to refreshed.snapshot)
                val hydrated = loadMaterialState(material, refreshed.snapshot)
                materialStates = materialStates + (
                    materialName to mergeCatalogRefreshMaterialState(materialStates[materialName], hydrated)
                )
                applyRefreshedCatalog(materialName, refreshed.snapshot)
                catalogLoadErrors = catalogLoadErrors - materialName
                catalogRecoveryMaterial = catalogRecoveryTriggerAfterRefresh(materialName, refreshed)
                pendingMixAction = PendingMixAction(materialName, refreshed.snapshot)
                preflightMessage = "Catalog refreshed — choose a new action for $materialName"
            }
            MixCatalogFetchResult.NetworkError -> {
                catalogLoadErrors = catalogLoadErrors + (
                    materialName to "Could not refresh the mix catalog — cached information was retained"
                )
                preflightMessage = "Mix catalog changed — refresh failed; try again before choosing an action"
            }
        }
    }

    fun retryCurrentOperation() {
        val material = operationSession?.currentAction?.material ?: operationSession?.current?.material ?: return
        coordinator.retry(jobFolderName, material)
    }

    fun recoverCatalogChangedOperation() {
        val material = operationSession?.currentAction?.material
            ?: operationSession?.current?.material
            ?: return
        // The failed action contains the stale revision and must not be retried. A refreshed
        // snapshot will open a new choice dialog and produce a new durable action instead.
        pendingMixAction = null
        selectedTargets = selectedTargets - material
        allowedDuplicateMaterials = allowedDuplicateMaterials - material
        catalogRecoveryMaterial = material
        catalogRecoveryAttempt += 1
        preflightMessage = catalogChangedRecoveryMessage(material)
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
                    TextButton(onClick = { reachable = null; refreshAttempt += 1 }) { Text("Retry") }
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
                        onSelectionChanged = { pgm, sel ->
                            val previous = state.selections[pgm] ?: ManageCodeRowSelection()
                            if (sel.mix != previous.mix) {
                                requestMixChange(material.materialName, setOf(pgm), sel.mix)
                            } else {
                                updateSelection(material.materialName, pgm, sel)
                            }
                        },
                        onSelectAll = { field, checked ->
                            if (field == "MIX") {
                                val unlocked = state.rows.map { it.editablePgm }.filter { it !in state.locked }.toSet()
                                requestMixChange(material.materialName, unlocked, checked)
                            } else {
                                val updated = state.selections.mapValues { (pgm, selection) ->
                                    if (pgm in state.locked) selection else when (field) {
                                        "PUNLOAD" -> selection.copy(removePUnload = checked)
                                        "2ND" -> toggleSecondPass(selection, checked)
                                        "SUPER" -> toggleSuperPass(selection, checked)
                                        else -> selection
                                    }
                                }
                                materialStates = materialStates + (
                                    material.materialName to updateMixLayoutSelections(state, updated)
                                )
                            }
                        },
                        loadThumbnail = loadThumbnail
                    )
                    catalogLoadErrors[material.materialName]?.let { message ->
                        Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                item {
                    val isRetryable = operationUiState is ManageCodeOperationUiState.Failed
                    val catalogChangedFailure = isCatalogChangedFailure(operationSession, jobFolderName)
                    Button(
                        onClick = {
                            if (screenPresentation.canRetryRestore) coordinator.restore()
                            else if (catalogChangedFailure) recoverCatalogChangedOperation()
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
                                    progress = { operationUiState.fraction }, modifier = Modifier.fillMaxWidth()
                                )
                                ManageCodeOperationUiState.Compiling -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                else -> Unit
                            }
                            Text(
                                when {
                                    restoreState == MixOperationRestoreState.Restoring -> "Restoring prior session…"
                                    screenPresentation.canRetryRestore -> "Retry session restore"
                                    catalogChangedFailure -> "Refresh catalog and choose again"
                                    isRetryable -> "Retry — ${manageCodeOperationLabel(operationUiState, operationSession)}"
                                    else -> manageCodeOperationLabel(operationUiState, operationSession)
                                },
                            )
                        }
                    }
                    preflightMessage?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    (operationUiState as? ManageCodeOperationUiState.Failed)?.let { failed ->
                        Text(failed.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    screenPresentation.restoreError?.let { message ->
                        Text("Session restore failed: $message", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        pendingMixAction?.let { pending ->
            val originalName = pending.catalog.entries
                .firstOrNull { it.lifecycle == com.kkc.sheettracker.data.mixservice.MixLifecycle.ACTIVE }
                ?.name ?: defaultMixName(pending.catalog.material)
            MixActionDialog(
                catalog = pending.catalog,
                originalName = originalName,
                onDismiss = { pendingMixAction = null },
                onTargetSelected = { target ->
                    pendingMixAction = null
                    selectedTargets = selectedTargets + (pending.materialName to target)
                    if (target is MixGenerationTarget.ReplaceActive) {
                        materialStates[pending.materialName]?.let { current ->
                            materialStates = materialStates + (
                                pending.materialName to selectReplacementTarget(current, target)
                            )
                        }
                    }
                    startRequest += 1
                },
                onDeleteExternal = { filename ->
                    val action = buildExternalDeleteAction(
                        job = jobFolderName,
                        material = pending.materialName,
                        catalog = pending.catalog,
                        filename = filename,
                    )
                    pendingMixAction = null
                    if (action != null) {
                        preflightMessage = null
                        operationSessionToken += 1L
                        val newSession = ManageCodeSession(jobFolderName, listOf(action))
                        when (externalDeleteSubmissionPath(operationSession, jobFolderName)) {
                            ExternalDeleteSubmissionPath.REPLACE_CATALOG_CHANGED -> {
                                coordinatorScope.launch {
                                    coordinator.replaceCatalogChangedSession(newSession)
                                }
                            }
                            ExternalDeleteSubmissionPath.START -> coordinator.start(newSession)
                        }
                    }
                },
            )
        }

        pendingMixChoice?.let { (materialName, tapped) ->
            val catalog = materialCatalogs[materialName]
            if (catalog == null) {
                pendingMixChoice = null
            } else {
                ExistingMixChoiceDialog(
                    catalog = catalog,
                    materialName = materialName,
                    onReplace = { target ->
                        pendingMixChoice = null
                        selectedTargets = selectedTargets + (materialName to target)
                        materialStates[materialName]?.let { current ->
                            materialStates = materialStates + (materialName to applyReplaceChoice(current, target, tapped))
                        }
                    },
                    onAdditional = { target ->
                        pendingMixChoice = null
                        selectedTargets = selectedTargets + (materialName to target)
                        materialStates[materialName]?.let { current ->
                            materialStates = materialStates + (materialName to applyAdditionalChoice(current, tapped))
                        }
                    },
                    onCancel = { pendingMixChoice = null },
                )
            }
        }

        pendingDuplicateWarning?.let { pending ->
            AlertDialog(
                onDismissRequest = { pendingDuplicateWarning = null },
                title = { Text("Already in another mix") },
                text = { Text(pending.duplicates.joinToString("\n") { "${it.pgm} is already in ${it.otherMixName}" }) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDuplicateWarning = null
                        allowedDuplicateMaterials = allowedDuplicateMaterials + pending.materialName
                        selectedTargets = selectedTargets + (pending.materialName to pending.target)
                        startRequest += 1
                    }) { Text("Continue anyway") }
                },
                dismissButton = { TextButton(onClick = { pendingDuplicateWarning = null }) { Text("Go back and edit") } }
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
