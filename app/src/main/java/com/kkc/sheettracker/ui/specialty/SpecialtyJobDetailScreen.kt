package com.kkc.sheettracker.ui.specialty

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.kkc.sheettracker.ui.components.headerBackground
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.NavBarSpecialtyDecoration
import com.kkc.sheettracker.ui.components.StatusBorderedCard
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_OTHER
import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS
import com.kkc.sheettracker.data.SpecialtyStateStore
import com.kkc.sheettracker.data.SpecialtyViewerDefaults
import com.kkc.sheettracker.data.SpecialtyViewerDefaultsStore
import com.kkc.sheettracker.data.completionKeysForItem
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.loadAdminBoardStock
import com.kkc.sheettracker.data.models.ReferenceDocType
import androidx.compose.material.icons.filled.Print
import com.kkc.sheettracker.ui.components.PrintDocumentsBottomSheet
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.StatusChip
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpecialtyJobDetailScreen(
    jobFolderName: String,
    specialtyStateStore: SpecialtyStateStore,
    specialtyViewerDefaultsStore: SpecialtyViewerDefaultsStore,
    jobRepository: JobRepository,
    hasAssemblySheet: Boolean,
    hasPlansElevations: Boolean,
    hasDeliverySheet: Boolean,
    hasThreeDAssets: Boolean,
    onOpenReferenceDocument: (ReferenceDocType, Int) -> Unit,
    onOpenThreeD: () -> Unit,
    onOpenDoorPanels: () -> Unit,
    onOpenSawRipList: () -> Unit,
    onOpenSplitView: () -> Unit,
    onJumpToCabinet: ((String) -> Unit)? = null,
    onBack: () -> Unit
) {
    val scanState by specialtyStateStore.scanState.collectAsState()
    val progressVersion by specialtyStateStore.progressVersion.collectAsState()
    val viewerDefaults by specialtyViewerDefaultsStore.defaults.collectAsState(initial = SpecialtyViewerDefaults())
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val completionOverrides = remember(jobFolderName) { mutableStateMapOf<String, Boolean>() }
    val inFlightUpdates = remember(jobFolderName) { mutableStateMapOf<String, Boolean>() }
    var toggleErrorMessage by remember(jobFolderName) { mutableStateOf<String?>(null) }
    var showAddSheet by remember(jobFolderName) { mutableStateOf(false) }
    var showPrintDialog by remember { mutableStateOf(false) }
    var editingItem by remember(jobFolderName) { mutableStateOf<com.kkc.sheettracker.data.models.TabletSpecialtyItem?>(null) }
    var deleteTargetItemId by remember(jobFolderName) { mutableStateOf<String?>(null) }
    var expandedSectionIds by remember(jobFolderName) { mutableStateOf<Set<String>?>(null) }
    val resolvedItems = remember(scanState.snapshot.generation, progressVersion, jobFolderName) {
        // Specialty mode excludes DELIVERY-tagged items
        specialtyStateStore.getResolvedItems(jobFolderName)
            .filter { !it.item.stations.contains(SpecialtyStation.DELIVERY) }
    }

    val sheetRipDoneVersion by specialtyStateStore.sheetRipDoneVersion.collectAsState()
    val sheetRipItems = remember(scanState.snapshot.basePath, jobFolderName) {
        loadAdminBoardStock(File(scanState.snapshot.basePath), jobFolderName)
            .filter { it.mode == "sheet" && it.feet != null && it.feet > 0 }
    }
    val sheetRipDone = remember(scanState.snapshot.basePath, jobFolderName, sheetRipDoneVersion) {
        specialtyStateStore.loadSheetRipDone(jobFolderName)
    }

    // Show current content immediately, then verify this job in the background.
    LaunchedEffect(jobFolderName) {
        specialtyStateStore.refreshJobOnOpen(jobFolderName)
    }
    LaunchedEffect(jobFolderName) {
        while (true) {
            delay(30_000L)
            specialtyStateStore.refresh(RefreshReason.APP_FOREGROUND, force = true)
        }
    }
    LaunchedEffect(jobFolderName, viewerDefaults.expandedSectionIds) {
        if (expandedSectionIds == null) {
            expandedSectionIds = viewerDefaults.expandedSectionIds
        }
    }

    val stationOrder = remember(viewerDefaults.stationOrder) {
        specialtyDetailStationOrder(viewerDefaults.stationOrder)
    }
    val sections = remember(resolvedItems, stationOrder) {
        buildSpecialtyDetailSections(resolvedItems = resolvedItems, stationOrder = stationOrder)
    }
    val activeExpandedSectionIds = expandedSectionIds ?: viewerDefaults.expandedSectionIds

    val completedItems = resolvedItems.count { isChecklistItemComplete(it, completionOverrides) }
    val totalItems = resolvedItems.size

    val navBarDeco = LocalNavBarDecoration.current
    DisposableEffect(navBarDeco) {
        onDispose { navBarDeco.specialtyDecoration = null }
    }
    SideEffect {
        navBarDeco.specialtyDecoration = NavBarSpecialtyDecoration(
            onAddItem = { editingItem = null; showAddSheet = true }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                modifier = Modifier.headerBackground(),
                title = {
                    Text(
                        jobFolderName,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "summary") {
                Text(
                    text = if (resolvedItems.isEmpty() &&
                        scanState.status != com.kkc.sheettracker.data.models.ScanStatus.READY
                    ) {
                        "Specialty checklist details are loading."
                    } else {
                        "$completedItems / $totalItems items complete"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item(key = "actions-reference") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasAssemblySheet) {
                        Button(onClick = { onOpenReferenceDocument(ReferenceDocType.ASSEMBLY, 1) }) {
                            Text("View Assembly")
                        }
                    }
                    if (hasPlansElevations) {
                        Button(onClick = { onOpenReferenceDocument(ReferenceDocType.PLANS_ELEVATIONS, 1) }) {
                            Text("View Plans & Elevations")
                        }
                    }
                    if (hasDeliverySheet) {
                        Button(onClick = { onOpenReferenceDocument(ReferenceDocType.DELIVERY_SHEETS, 1) }) {
                            Text("View Cover Sheet")
                        }
                    }
                    if (hasThreeDAssets) {
                        Button(onClick = onOpenThreeD) {
                            Text("View 3D")
                        }
                    }
                    Button(onClick = { showPrintDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Print,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Print")
                    }
                }
            }

            item(key = "actions-specialty") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpecialtyActionWidget(
                        modifier = Modifier.weight(1f),
                        title = "Door Panels",
                        subtitle = "View filtered panel cut lists",
                        onClick = onOpenDoorPanels
                    )
                    SpecialtyActionWidget(
                        modifier = Modifier.weight(1f),
                        title = "Rip List",
                        subtitle = "View sheet stock rip cuts",
                        onClick = onOpenSawRipList
                    )
                    SpecialtyActionWidget(
                        modifier = Modifier.weight(1f),
                        title = "Split View",
                        subtitle = "Open assembly + plans workspace",
                        onClick = onOpenSplitView
                    )
                }
            }

            if (sheetRipItems.isNotEmpty()) {
                val sheetDoneCount = sheetRipItems.count { sheetRipDone[it.id] == true }
                stickyHeader(key = "sheet-rips-header") {
                    SpecialtySectionHeader(
                        label = "Sheet Rips",
                        completed = sheetDoneCount,
                        total = sheetRipItems.size,
                        expanded = SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS in activeExpandedSectionIds,
                        onToggleExpanded = {
                            expandedSectionIds = toggleSpecialtySection(
                                current = activeExpandedSectionIds,
                                sectionId = SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS
                            )
                        }
                    )
                }

                if (SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS in activeExpandedSectionIds) {
                    items(
                        items = sheetRipItems,
                        key = { "sheet-rip:${it.id}" }
                    ) { item ->
                        val isDone = sheetRipDone[item.id] == true
                        val alpha = if (isDone) 0.5f else 1f

                        Surface(
                            tonalElevation = 3.dp,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(alpha)
                                .clickable {
                                    coroutineScope.launch {
                                        specialtyStateStore.setSheetRipDone(
                                            jobFolderName = jobFolderName,
                                            itemId = item.id,
                                            done = !isDone
                                        )
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Checkbox(
                                    checked = isDone,
                                    onCheckedChange = { next ->
                                        coroutineScope.launch {
                                            specialtyStateStore.setSheetRipDone(
                                                jobFolderName = jobFolderName,
                                                itemId = item.id,
                                                done = next
                                            )
                                        }
                                    }
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.material,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    val feet = item.feet ?: 0.0
                                    val rips = Math.ceil(feet / item.ripLength).toInt()
                                    Text(
                                        text = "${feet.toInt()} ft",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "$rips rips",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (resolvedItems.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No specialty checklist items found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                sections.forEach { section ->
                    val sectionKey = section.id
                    stickyHeader(key = "section-$sectionKey") {
                        val sectionDone = section.items.count { isChecklistItemComplete(it, completionOverrides) }
                        SpecialtySectionHeader(
                            label = section.label,
                            completed = sectionDone,
                            total = section.items.size,
                            expanded = section.id in activeExpandedSectionIds,
                            onToggleExpanded = {
                                expandedSectionIds = toggleSpecialtySection(
                                    current = activeExpandedSectionIds,
                                    sectionId = section.id
                                )
                            }
                        )
                    }
                    if (section.id in activeExpandedSectionIds) {
                        items(
                            items = section.items,
                            key = { resolved -> "$sectionKey::${resolved.item.id}" }
                        ) { resolved ->
                            val itemToggles = checklistTogglesForItem(resolved, completionOverrides)
                            SpecialtyChecklistRow(
                                resolved = resolved,
                                toggles = itemToggles,
                                inFlightUpdates = inFlightUpdates,
                                stationOrder = stationOrder,
                                onJumpToCabinet = onJumpToCabinet,
                                basePath = scanState.snapshot.basePath,
                                jobFolderName = jobFolderName,
                                onEditItem = { tabletItem ->
                                    editingItem = tabletItem
                                    showAddSheet = true
                                },
                                onDeleteItem = { itemId ->
                                    deleteTargetItemId = itemId
                                },
                                myTabletId = specialtyStateStore.tabletId,
                                onPatchDims = { dims, qty, mat ->
                                    coroutineScope.launch {
                                        try {
                                            specialtyStateStore.patchSpecialtyItemFields(jobFolderName, resolved.item.id, dims, qty, mat)
                                        } catch (_: Exception) {
                                            snackbarHostState.showSnackbar("Failed to save dimensions.")
                                        }
                                    }
                                },
                                onCheckedChange = { toggle, next ->
                                    val itemId = resolved.item.id
                                    val controlId = toggle.controlId
                                    val previous = completionOverrides[controlId] ?: toggle.checked
                                    completionOverrides[controlId] = next
                                    startInFlightUpdate(inFlightUpdates, controlId)
                                    coroutineScope.launch {
                                        try {
                                            specialtyStateStore.setItemCompletionKey(
                                                jobFolderName = jobFolderName,
                                                itemId = itemId,
                                                completionKey = toggle.completionKey,
                                                completed = next
                                            )
                                            completionOverrides.remove(controlId)
                                            toggleErrorMessage = null
                                        } catch (_: Exception) {
                                            completionOverrides[controlId] = previous
                                            val message = "Failed to update checklist item. Please retry."
                                            toggleErrorMessage = message
                                            snackbarHostState.showSnackbar(message)
                                        } finally {
                                            finishInFlightUpdate(inFlightUpdates, controlId)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (!toggleErrorMessage.isNullOrBlank()) {
                item(key = "error") {
                    Text(
                        text = toggleErrorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Add / Edit sheet
        if (showAddSheet) {
            AddSpecialtyItemSheet(
                existingItem = editingItem,
                tabletId = specialtyStateStore.tabletId,
                onDismiss = { showAddSheet = false; editingItem = null },
                onSave = { item ->
                    coroutineScope.launch {
                        try {
                            specialtyStateStore.saveTabletItem(jobFolderName, item)
                            showAddSheet = false
                            editingItem = null
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Failed to save item: ${e.message}")
                        }
                    }
                }
            )
        }

        // Delete confirmation dialog
        val deletingItemId = deleteTargetItemId
        if (deletingItemId != null) {
            AlertDialog(
                onDismissRequest = { deleteTargetItemId = null },
                title = { Text("Delete Item") },
                text = { Text("Delete this item? This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTargetItemId = null
                        coroutineScope.launch {
                            specialtyStateStore.deleteTabletItem(jobFolderName, deletingItemId)
                        }
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTargetItemId = null }) { Text("Cancel") }
                }
            )
        }
        if (showPrintDialog) {
            PrintDocumentsBottomSheet(
                jobFolderName = jobFolderName,
                jobRepository = jobRepository,
                onDismissRequest = { showPrintDialog = false }
            )
        }
    }
}

@Composable
private fun SpecialtySectionHeader(
    label: String,
    completed: Int,
    total: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$completed / $total",
                style = MaterialTheme.typography.labelMedium,
                color = if (completed >= total && total > 0)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                contentDescription = if (expanded) "Collapse $label" else "Expand $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SpecialtyActionWidget(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .heightIn(min = 92.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompactSpecialtyProgressCard(
    title: String,
    subtitle: String,
    fraction: Float,
    segmentedStatusCounts: StatusCounts,
    headerLeading: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    headerActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    inlineContent: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    StatusBorderedCard(
        status = specialtyCardStatus(segmentedStatusCounts, fraction),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 9.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    headerLeading()
                }
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    headerActions()
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 6.dp)
                    .background(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        shape = MaterialTheme.shapes.extraLarge
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                inlineContent()
            }
        }
    }
}

@Composable
internal fun SpecialtyChecklistRow(
    resolved: SpecialtyResolvedItem,
    toggles: List<SpecialtyChecklistToggle>,
    inFlightUpdates: Map<String, Boolean>,
    stationOrder: List<SpecialtyStation> = SpecialtyStation.entries.toList(),
    onCheckedChange: (SpecialtyChecklistToggle, Boolean) -> Unit,
    onJumpToCabinet: ((String) -> Unit)? = null,
    onPatchDims: ((String?, Int?, String?) -> Unit)? = null,
    basePath: String = "",
    jobFolderName: String = "",
    onEditItem: ((com.kkc.sheettracker.data.models.TabletSpecialtyItem) -> Unit)? = null,
    onDeleteItem: ((String) -> Unit)? = null,
    myTabletId: String = ""
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val item = resolved.item
    val title = specialtyItemTitle(item.cabinetLabel, item.cabinetNumbers, item.name)
    val completionKeys = completionKeysForItem(item)
    val totalSteps = completionKeys.size.coerceAtLeast(1)
    val completedSteps = toggles.count { it.checked }.coerceAtMost(totalSteps)
    val fraction = if (totalSteps <= 0) 0f else completedSteps.toFloat() / totalSteps.toFloat()
    val statusCounts = StatusCounts(
        total = totalSteps,
        complete = completedSteps,
        bad = 0,
        skipped = 0,
        notStarted = (totalSteps - completedSteps).coerceAtLeast(0)
    )
    val notes = item.notes?.trim().orEmpty()
    val attachmentCount = item.attachments.size
    val supplier = item.supplier?.trim().orEmpty()
    val model = item.model?.trim().orEmpty()
    val tracking = item.tracking?.trim().orEmpty()
    val orderDate = item.orderDate?.trim().orEmpty()
    val orderUrl = item.orderUrl?.trim().orEmpty()
    var attachmentsExpanded by remember(item.id) { mutableStateOf(false) }
    val orderedStations = remember(item.stations, stationOrder) {
        orderSpecialtyStations(item.stations, stationOrder)
    }

    CompactSpecialtyProgressCard(
        title = title,
        subtitle = "$completedSteps/$totalSteps steps complete",
        fraction = fraction,
        segmentedStatusCounts = statusCounts,
        headerLeading = {
            toggles.forEach { toggle ->
                val enabled = isToggleEnabled(toggle.controlId, inFlightUpdates)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Checkbox(
                        checked = toggle.checked,
                        onCheckedChange = { next -> onCheckedChange(toggle, next) },
                        enabled = enabled
                    )
                    if (toggles.size > 1) {
                        Text(
                            text = shortDivisionLabel(toggle.label ?: toggle.completionKey),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        headerActions = {
            // Edit/Delete controls for own tablet items
            val isMyTabletItem = item.id.startsWith("tablet:") &&
                item.createdBy == myTabletId && myTabletId.isNotBlank()
            if (isMyTabletItem) {
                val rawId = item.id.removePrefix("tablet:")
                val tabletItem = com.kkc.sheettracker.data.models.TabletSpecialtyItem(
                    id = rawId,
                    name = item.name,
                    category = item.category,
                    cabinetNumbers = item.cabinetNumbers,
                    stations = item.stations,
                    dimensions = item.dimensions,
                    quantity = item.quantity,
                    material = item.material,
                    supplier = item.supplier,
                    modelNumber = item.model,
                    orderDate = item.orderDate,
                    trackingNumber = item.tracking,
                    orderUrl = item.orderUrl,
                    notes = item.notes,
                    createdAt = item.createdAt.orEmpty(),
                    createdByDevice = item.createdBy.orEmpty()
                )
                IconButton(
                    onClick = { onEditItem?.invoke(tabletItem) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit item",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = { onDeleteItem?.invoke(item.id) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete item",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (item.cabinetNumbers.isNotEmpty() && onJumpToCabinet != null) {
                Button(
                    onClick = { onJumpToCabinet(item.cabinetNumbers.first()) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 32.dp)
                ) {
                    Text("View", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (item.category == com.kkc.sheettracker.data.models.SpecialtyItemCategory.TO_ORDER) {
                StatusChip(
                    text = "To Order",
                    backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            orderedStations.forEach { station ->
                val chip = stationChipSpec(station)
                StatusChip(
                    text = chip.label,
                    backgroundColor = chip.background,
                    contentColor = chip.content
                )
            }
        },
        inlineContent = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (notes.isNotBlank()) SpecialtyMetaRow(label = "Notes", value = notes)
                if (supplier.isNotBlank()) SpecialtyMetaRow(label = "Supplier", value = supplier)
                if (model.isNotBlank()) SpecialtyMetaRow(label = "Model", value = model)
                if (tracking.isNotBlank()) SpecialtyMetaRow(label = "Tracking", value = tracking)
                if (orderDate.isNotBlank()) SpecialtyMetaRow(label = "Order Date", value = orderDate)
                if (orderUrl.isNotBlank()) SpecialtyMetaRow(label = "Order URL", value = orderUrl)
                val isSawStation = SpecialtyStation.SAW in item.stations
                if (isSawStation || item.dimensions != null || item.quantity != null || !item.material.isNullOrBlank()) {
                    SpecialtyDimsSection(
                        item = item,
                        isSawStation = isSawStation,
                        onPatchDims = { d, q, m -> onPatchDims?.invoke(d, q, m) }
                    )
                }
                if (attachmentCount > 0) {
                    Box {
                        Surface(
                            tonalElevation = 0.dp,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.clickable { attachmentsExpanded = !attachmentsExpanded }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Attachments ($attachmentCount)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = if (attachmentsExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = "Arrow Drop Down icon",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = attachmentsExpanded,
                            onDismissRequest = { attachmentsExpanded = false }
                        ) {
                            item.attachments.forEach { att ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = att.originalName.ifBlank { att.filename },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    onClick = {
                                        attachmentsExpanded = false
                                        if (basePath.isNotBlank() && jobFolderName.isNotBlank()) {
                                            val rawItemId = item.id.removePrefix("checklist:")
                                            val attDir = java.io.File(
                                                basePath,
                                                "$jobFolderName/.metadata/admin/checklist_attachments/$rawItemId"
                                            )
                                            // Try exact filename first, then fall back to any file containing the attachment ID
                                            // (handles legacy uploads saved with an item-ID prefix)
                                            val file = java.io.File(attDir, att.filename).takeIf { it.exists() }
                                                ?: attDir.listFiles()?.firstOrNull { it.name.contains(att.id) }
                                            if (file != null && file.exists()) {
                                                try {
                                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                                        context,
                                                        "${context.packageName}.provider",
                                                        file
                                                    )
                                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                        setDataAndType(uri, att.mimeType ?: "application/octet-stream")
                                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(intent)
                                                } catch (e: Exception) {
                                                    android.util.Log.e("KKC", "Failed to open attachment: ${file.absolutePath}", e)
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                if (toggles.any { toggle -> !isToggleEnabled(toggle.controlId, inFlightUpdates) }) {
                    Text(
                        text = "Saving...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    )
}

private fun specialtyItemTitle(cabinetLabel: String?, cabinetNumbers: List<String>, name: String): String {
    val explicitLabel = cabinetLabel?.trim().orEmpty()
    if (explicitLabel.isNotBlank()) return "$explicitLabel - $name"
    val cleaned = cabinetNumbers
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (cleaned.isEmpty()) return name
    val cabinetLabel = cleaned.joinToString(", ") { cab ->
        if (cab.startsWith("#")) cab else "#$cab"
    }
    return "$cabinetLabel - $name"
}

internal data class SpecialtyDetailSection(
    val id: String,
    val label: String,
    val items: List<SpecialtyResolvedItem>
)

internal fun specialtyDetailStationOrder(savedOrder: List<SpecialtyStation>): List<SpecialtyStation> {
    val ordered = LinkedHashSet<SpecialtyStation>()
    savedOrder.forEach { ordered += it }
    SpecialtyStation.entries.forEach { ordered += it }
    return ordered.toList()
}

internal fun buildSpecialtyDetailSections(
    resolvedItems: List<SpecialtyResolvedItem>,
    stationOrder: List<SpecialtyStation>
): List<SpecialtyDetailSection> {
    if (resolvedItems.isEmpty()) return emptyList()
    val effectiveOrder = specialtyDetailStationOrder(stationOrder)
    val buckets = linkedMapOf<String, MutableList<SpecialtyResolvedItem>>()
    effectiveOrder.forEach { buckets[it.name] = mutableListOf() }
    buckets[SPECIALTY_VIEWER_SECTION_ID_OTHER] = mutableListOf()

    resolvedItems.forEach { resolved ->
        val stations = orderSpecialtyStations(resolved.item.stations, effectiveOrder)
        if (stations.isEmpty()) {
            buckets.getValue(SPECIALTY_VIEWER_SECTION_ID_OTHER) += resolved
        } else {
            stations.forEach { station ->
                buckets.getValue(station.name) += resolved
            }
        }
    }

    return buildList {
        effectiveOrder.forEach { station ->
            val items = buckets[station.name].orEmpty()
            if (items.isNotEmpty()) {
                add(
                    SpecialtyDetailSection(
                        id = station.name,
                        label = stationFilterLabel(station),
                        items = items.toList()
                    )
                )
            }
        }
        val otherItems = buckets[SPECIALTY_VIEWER_SECTION_ID_OTHER].orEmpty()
        if (otherItems.isNotEmpty()) {
            add(
                SpecialtyDetailSection(
                    id = SPECIALTY_VIEWER_SECTION_ID_OTHER,
                    label = "Other",
                    items = otherItems.toList()
                )
            )
        }
    }
}

internal fun toggleSpecialtySection(current: Set<String>, sectionId: String): Set<String> {
    val next = LinkedHashSet(current)
    if (!next.add(sectionId)) {
        next.remove(sectionId)
    }
    return next
}

internal fun orderSpecialtyStations(
    stations: List<SpecialtyStation>,
    stationOrder: List<SpecialtyStation>
): List<SpecialtyStation> {
    if (stations.isEmpty()) return emptyList()
    val effectiveOrder = specialtyDetailStationOrder(stationOrder)
    val orderIndex = effectiveOrder.withIndex().associate { (index, station) -> station to index }
    return stations
        .distinct()
        .sortedBy { orderIndex[it] ?: Int.MAX_VALUE }
}

private fun shortDivisionLabel(label: String): String {
    val key = label.trim().uppercase()
    return when {
        "HARDWOODS" in key -> "HW"
        "ASSEMBLY" in key -> "ASM"
        "SPECIALTY" in key -> "SPEC"
        "EDGE" in key -> "EDGE"
        else -> key.take(4)
    }
}

private data class StationChipSpec(
    val label: String,
    val background: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color
)

@Composable
private fun stationChipSpec(station: SpecialtyStation): StationChipSpec {
    val scheme = MaterialTheme.colorScheme
    return when (station) {
        SpecialtyStation.CNC -> StationChipSpec("CNC", scheme.primaryContainer, scheme.onPrimaryContainer)
        SpecialtyStation.HARDWOODS -> StationChipSpec("HW", scheme.secondaryContainer, scheme.onSecondaryContainer)
        SpecialtyStation.SAW -> StationChipSpec("SAW", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        SpecialtyStation.EDGE_BANDER -> StationChipSpec("EDGE", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        SpecialtyStation.ASSEMBLY -> StationChipSpec("ASM", scheme.secondaryContainer, scheme.onSecondaryContainer)
        SpecialtyStation.SPECIALTY -> StationChipSpec("SPEC", scheme.surfaceVariant, scheme.onSurfaceVariant)
        SpecialtyStation.DELIVERY -> StationChipSpec("DELIVERY", Color(0xFFDCFCE7), Color(0xFF15803D))
    }
}

private fun specialtyCardStatus(
    segmentedStatusCounts: StatusCounts,
    fraction: Float
): SheetStatus {
    val total = segmentedStatusCounts.total.coerceAtLeast(0)
    return when {
        total <= 0 && fraction <= 0f -> SheetStatus.NOT_STARTED
        segmentedStatusCounts.bad > 0 -> SheetStatus.HAS_BAD_PARTS
        segmentedStatusCounts.skipped >= total && total > 0 -> SheetStatus.SKIPPED
        segmentedStatusCounts.complete >= total && total > 0 -> SheetStatus.COMPLETE
        segmentedStatusCounts.complete <= 0 && segmentedStatusCounts.skipped <= 0 && fraction <= 0f -> SheetStatus.NOT_STARTED
        else -> SheetStatus.IN_PROGRESS
    }
}

@Composable
private fun SpecialtyMetaRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

internal data class SpecialtyChecklistToggle(
    val controlId: String,
    val completionKey: String,
    val label: String?,
    val checked: Boolean
)

internal fun checklistTogglesForItem(
    resolved: SpecialtyResolvedItem,
    completionOverrides: Map<String, Boolean>
): List<SpecialtyChecklistToggle> {
    val completionKeys = completionKeysForItem(resolved.item)
    return completionKeys.map { completionKey ->
        val controlId = checklistControlId(resolved.item.id, completionKey)
        val checked = completionOverrides[controlId] ?: (resolved.completionByKey[completionKey]?.completed == true)
        SpecialtyChecklistToggle(
            controlId = controlId,
            completionKey = completionKey,
            label = if (completionKeys.size >= 2) completionKey.replace('_', ' ') else null,
            checked = checked
        )
    }
}

internal fun isChecklistItemComplete(
    resolved: SpecialtyResolvedItem,
    completionOverrides: Map<String, Boolean>
): Boolean {
    return checklistTogglesForItem(resolved, completionOverrides).all { toggle -> toggle.checked }
}

private fun checklistControlId(itemId: String, completionKey: String): String {
    return "$itemId::$completionKey"
}

internal fun startInFlightUpdate(inFlightUpdates: MutableMap<String, Boolean>, controlId: String) {
    inFlightUpdates[controlId] = true
}

internal fun finishInFlightUpdate(inFlightUpdates: MutableMap<String, Boolean>, controlId: String) {
    inFlightUpdates.remove(controlId)
}

internal fun isToggleEnabled(controlId: String, inFlightUpdates: Map<String, Boolean>): Boolean {
    return inFlightUpdates[controlId] != true
}

/** Human-readable label for a station — used in filter chips and summary text. */
private fun stationFilterLabel(station: SpecialtyStation): String = when (station) {
    SpecialtyStation.SAW -> "Saw"
    SpecialtyStation.EDGE_BANDER -> "Edge Bander"
    SpecialtyStation.ASSEMBLY -> "Assembly"
    SpecialtyStation.CNC -> "CNC"
    SpecialtyStation.HARDWOODS -> "Hardwoods"
    SpecialtyStation.SPECIALTY -> "Specialty"
    SpecialtyStation.DELIVERY -> "Delivery"
}

@Composable
private fun SpecialtyDimsSection(
    item: com.kkc.sheettracker.data.models.SpecialtyItem,
    isSawStation: Boolean,
    onPatchDims: (String?, Int?, String?) -> Unit
) {
    var editing by remember(item.id) { mutableStateOf(false) }
    var editDims by remember(item.id) { mutableStateOf(item.dimensions ?: "") }
    var editQty by remember(item.id) { mutableStateOf(item.quantity?.toString() ?: "") }
    var editMat by remember(item.id) { mutableStateOf(item.material ?: "") }
    val hasData = !item.dimensions.isNullOrBlank() || item.quantity != null || !item.material.isNullOrBlank()

    if (isSawStation) {
        if (editing) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = editDims, onValueChange = { editDims = it }, label = { Text("Dims") }, modifier = Modifier.weight(2f), singleLine = true)
                    OutlinedTextField(value = editQty, onValueChange = { editQty = it }, label = { Text("Qty") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(value = editMat, onValueChange = { editMat = it }, label = { Text("Material") }, modifier = Modifier.weight(2f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        onPatchDims(editDims.trim().takeIf { it.isNotBlank() }, editQty.trim().toIntOrNull(), editMat.trim().takeIf { it.isNotBlank() })
                        editing = false
                    }) { Text("Save") }
                    TextButton(onClick = {
                        editDims = item.dimensions ?: ""
                        editQty = item.quantity?.toString() ?: ""
                        editMat = item.material ?: ""
                        editing = false
                    }) { Text("Cancel") }
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!hasData) {
                    SuggestionChip(onClick = { editing = true }, label = { Text("Add dims...", style = MaterialTheme.typography.labelSmall) })
                } else {
                    if (!item.dimensions.isNullOrBlank()) SuggestionChip(onClick = { editing = true }, label = { Text(item.dimensions, style = MaterialTheme.typography.labelSmall) })
                    if (item.quantity != null) SuggestionChip(onClick = { editing = true }, label = { Text("Qty: ${item.quantity}", style = MaterialTheme.typography.labelSmall) })
                    if (!item.material.isNullOrBlank()) SuggestionChip(onClick = { editing = true }, label = { Text(item.material, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
    } else if (hasData) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!item.dimensions.isNullOrBlank()) Row { Text("Dims: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold); Text(item.dimensions, style = MaterialTheme.typography.bodySmall) }
            if (item.quantity != null) Row { Text("Qty: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold); Text(item.quantity.toString(), style = MaterialTheme.typography.bodySmall) }
            if (!item.material.isNullOrBlank()) Row { Text("Material: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold); Text(item.material, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
