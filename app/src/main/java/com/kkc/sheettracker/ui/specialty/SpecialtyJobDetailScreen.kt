package com.kkc.sheettracker.ui.specialty

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.kkc.sheettracker.ui.components.headerBackground
import com.kkc.sheettracker.ui.components.KKCTopAppBar
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kkc.sheettracker.ui.components.SectionProgressHeader
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
import com.kkc.sheettracker.data.resolveSheetRipTallyState
import com.kkc.sheettracker.data.loadAdminBoardStock
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.ReferenceDocType
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Visibility
import com.kkc.sheettracker.data.MoldingLibraryRepository
import com.kkc.sheettracker.data.models.AdminBoardStockItem
import com.kkc.sheettracker.data.models.MoldingLibraryItem
import com.kkc.sheettracker.ui.components.PrintDocumentsBottomSheet
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.ArchiveLifecycleClient
import com.kkc.sheettracker.ui.detail.ArchiveLifecycleActionSheet
import com.kkc.sheettracker.ui.detail.archiveActionVisible
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.ui.standards.MoldingDetailOverlay
import com.kkc.sheettracker.ui.standards.rememberSvgImageLoader
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
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
    hasClosetRods: Boolean,
    onOpenReferenceDocument: (ReferenceDocType, Int) -> Unit,
    onOpenThreeD: () -> Unit,
    onOpenDoorPanels: () -> Unit,
    onOpenSawRipList: () -> Unit,
    onOpenClosetRods: () -> Unit,
    onOpenSplitView: () -> Unit,
    onJumpToCabinet: ((String) -> Unit)? = null,
    tabletId: String,
    archiveClientFactory: suspend () -> ArchiveLifecycleClient?,
    onArchiveCompleted: () -> Unit,
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
    var showArchiveActionSheet by remember(jobFolderName) { mutableStateOf(false) }
    val adminEnabled by AdminModeController.enabled.collectAsState()
    var editingItem by remember(jobFolderName) { mutableStateOf<com.kkc.sheettracker.data.models.SpecialtyItem?>(null) }
    var deleteTargetItemId by remember(jobFolderName) { mutableStateOf<String?>(null) }
    var expandedSectionIds by remember(jobFolderName) { mutableStateOf<Set<String>?>(null) }
    val resolvedItems = remember(scanState.snapshot.generation, progressVersion, jobFolderName) {
        specialtyStateStore.getResolvedItems(jobFolderName)
            .filter { isItemRelevantToMode(it, SpecialtySurfaceMode.SPECIALTY) }
    }

    val sheetRipDoneVersion by specialtyStateStore.sheetRipDoneVersion.collectAsState()
    val hardwoodsProgressVersion by specialtyStateStore.hardwoodsProgressVersion.collectAsState()
    val sheetRipItems = remember(scanState.snapshot.basePath, jobFolderName) {
        specialtySheetRipItems(loadAdminBoardStock(File(scanState.snapshot.basePath), jobFolderName))
    }
    val sheetRipDone = remember(
        scanState.snapshot.basePath,
        jobFolderName,
        sheetRipDoneVersion,
        hardwoodsProgressVersion
    ) {
        specialtyStateStore.loadSheetRipDone(jobFolderName)
    }
    // Molding preview — same pattern as HardwoodsWorkspaceScreen
    val moldingLibraryRepository = remember(scanState.snapshot.basePath) {
        MoldingLibraryRepository(File(scanState.snapshot.basePath))
    }
    var previewMoldingItem by remember(jobFolderName) { mutableStateOf<AdminBoardStockItem?>(null) }
    val moldingSvgImageLoader = rememberSvgImageLoader()
    val isDarkTheme = LocalKKCIsDarkTheme.current

    // Show current content immediately, then verify this job in the background.
    LaunchedEffect(jobFolderName) {
        specialtyStateStore.refreshJobOnOpen(jobFolderName)
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
    LaunchedEffect(Unit) {
        navBarDeco.searchDecoration = null
        navBarDeco.keepSearchDeco = false
    }
    DisposableEffect(navBarDeco) {
        onDispose { navBarDeco.specialtyDecoration = null }
    }
    SideEffect {
        navBarDeco.specialtyDecoration = NavBarSpecialtyDecoration(
            onAddItem = { editingItem = null; showAddSheet = true }
        )
    }

    SharedTransitionLayout {
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            KKCTopAppBar(
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
                actions = {
                    if (archiveActionVisible(adminEnabled = adminEnabled, sourceIsLive = true)) {
                        TextButton(onClick = { showArchiveActionSheet = true }) {
                            Text("Archive")
                        }
                    }
                },
                )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 112.dp)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            item(key = "actions-reference") {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 12.dp),
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
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpecialtyActionWidget(
                        modifier = Modifier.width(168.dp),
                        title = "Door Panels",
                        subtitle = "View filtered panel cut lists",
                        onClick = onOpenDoorPanels
                    )
                    SpecialtyActionWidget(
                        modifier = Modifier.width(168.dp),
                        title = "Rip List",
                        subtitle = "View sheet stock rip cuts",
                        onClick = onOpenSawRipList
                    )
                    if (hasClosetRods) {
                        SpecialtyActionWidget(
                            modifier = Modifier.width(168.dp),
                            title = "Closet Rods",
                            subtitle = "View rod cut list",
                            onClick = onOpenClosetRods
                        )
                    }
                    SpecialtyActionWidget(
                        modifier = Modifier.width(168.dp),
                        title = "Split View",
                        subtitle = "Open assembly + plans workspace",
                        onClick = onOpenSplitView
                    )
                }
            }

            if (sheetRipItems.isNotEmpty()) {
                val sheetDoneCount = sheetRipItems.count { item ->
                    val target = Math.ceil((item.feet ?: 0.0) / item.ripLength).toInt().coerceAtLeast(0)
                    resolveSheetRipTallyState(
                        specialtyStateStore.getSheetRipStoredDoneCount(jobFolderName, item),
                        sheetRipDone[item.id] == true,
                        target
                    ).isComplete
                }
                val sheetExpanded = SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS in activeExpandedSectionIds
                item(key = "sheet-rips-spacing") {
                    Spacer(Modifier.height(12.dp))
                }
                stickyHeader(key = "sheet-rips-header") {
                    SectionProgressHeader(
                        title = "Sheet Rips",
                        itemCount = sheetRipItems.size,
                        done = sheetDoneCount,
                        total = sheetRipItems.size,
                        expanded = sheetExpanded,
                        onToggleExpanded = {
                            expandedSectionIds = toggleSpecialtySection(
                                current = activeExpandedSectionIds,
                                sectionId = SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val sheetRipEntries = specialtySheetRipLazyRowEntries(sheetRipItems)
                itemsIndexed(items = sheetRipEntries, key = { _, entry -> entry.key }) { index, entry ->
                    val item = entry.item
                    val target = Math.ceil((item.feet ?: 0.0) / item.ripLength).toInt().coerceAtLeast(0)
                    val tally = resolveSheetRipTallyState(
                        specialtyStateStore.getSheetRipStoredDoneCount(jobFolderName, item),
                        sheetRipDone[item.id] == true,
                        target
                    )
                    val isDone = tally.isComplete
                    val alpha = if (isDone) 0.5f else 1f
                    AnimatedVisibility(
                        visible = sheetExpanded,
                        enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                        exit = shrinkVertically(tween(300)) + fadeOut(tween(300))
                    ) {
                        val isDark = LocalKKCIsDarkTheme.current
                        val backdropColor = if (isDark) Color(0xFF22252A) else Color.White
                        Surface(
                            shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 10.dp, bottomEnd = 10.dp),
                            color = backdropColor,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Surface(
                                tonalElevation = 3.dp,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = 8.dp,
                                        top = if (index == 0) 8.dp else 0.dp,
                                        end = 8.dp,
                                        bottom = 8.dp
                                    )
                                    .alpha(alpha)
                                    .clickable {
                                        coroutineScope.launch {
                                            specialtyStateStore.setSheetRipCompletion(
                                                jobFolderName = jobFolderName,
                                                item = item,
                                                target = target,
                                                completed = !isDone
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
                                                specialtyStateStore.setSheetRipCompletion(
                                                    jobFolderName = jobFolderName,
                                                    item = item,
                                                    target = target,
                                                    completed = next
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
                                            text = specialtySheetRipItemLabel(item),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (item.moldingId != null) {
                                        IconButton(onClick = { previewMoldingItem = item }) {
                                            Icon(
                                                Icons.Filled.Visibility,
                                                contentDescription = "Preview ${item.name} molding profile"
                                            )
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        val feet = item.feet ?: 0.0
                                        val rips = target
                                        Text(
                                            text = "${feet.toInt()} ft",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = specialtySheetRipLengthLabel(rips, item.ripLength),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            } else {
                sections.forEach { section ->
                    val sectionKey = section.id
                    val sectionDone = section.items.count { isChecklistItemComplete(it, completionOverrides) }
                    val sectionExpanded = section.id in activeExpandedSectionIds

                    item(key = "section-$sectionKey-spacing") {
                        Spacer(Modifier.height(12.dp))
                    }
                    stickyHeader(key = "section-$sectionKey") {
                        SectionProgressHeader(
                            title = section.label,
                            itemCount = section.items.size,
                            done = sectionDone,
                            total = section.items.size,
                            expanded = sectionExpanded,
                            onToggleExpanded = {
                                expandedSectionIds = toggleSpecialtySection(
                                    current = activeExpandedSectionIds,
                                    sectionId = section.id
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    val sectionEntries = specialtyChecklistLazyRowEntries(sectionKey, section.items)
                    itemsIndexed(items = sectionEntries, key = { _, entry -> entry.key }) { index, entry ->
                        val resolved = entry.item
                        val itemToggles = checklistTogglesForItem(resolved, completionOverrides)
                        AnimatedVisibility(
                            visible = sectionExpanded,
                            enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                            exit = shrinkVertically(tween(300)) + fadeOut(tween(300))
                        ) {
                            val isDark = LocalKKCIsDarkTheme.current
                            val backdropColor = if (isDark) Color(0xFF22252A) else Color.White
                            Surface(
                                shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 10.dp, bottomEnd = 10.dp),
                                color = backdropColor,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(
                                        start = 8.dp,
                                        top = if (index == 0) 8.dp else 0.dp,
                                        end = 8.dp,
                                        bottom = 8.dp
                                    )
                                ) {
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
                },
                onDelete = { itemId ->
                    deleteTargetItemId = itemId
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
        if (showArchiveActionSheet) {
            ArchiveLifecycleActionSheet(
                folderName = jobFolderName,
                adminEnabled = adminEnabled,
                tabletId = tabletId,
                clientFactory = archiveClientFactory,
                onCompleted = onArchiveCompleted,
                onDismiss = { showArchiveActionSheet = false },
            )
        }
    }

        // Molding detail overlay — renders above the full screen, same pattern as HardwoodsWorkspaceScreen
        AnimatedVisibility(
            visible = previewMoldingItem != null,
            enter = fadeIn() + scaleIn(initialScale = 0.95f),
            exit  = fadeOut() + scaleOut(targetScale = 0.95f)
        ) {
            previewMoldingItem?.let { item ->
                val moldingId = item.moldingId
                if (moldingId != null) {
                    val parts    = moldingId.split(":", limit = 2)
                    val category = parts.getOrElse(0) { "" }
                    val fileId   = parts.getOrElse(1) { "" }
                    MoldingDetailOverlay(
                        item = MoldingLibraryItem(
                            id       = moldingId,
                            category = category,
                            fileId   = fileId,
                            name     = item.name
                        ),
                        repository              = moldingLibraryRepository,
                        svgImageLoader          = moldingSvgImageLoader,
                        sharedTransitionScope   = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedVisibility,
                        isDarkPreview           = isDarkTheme,
                        onDismiss               = { previewMoldingItem = null }
                    )
                }
            }
        }
    } // Box
    } // SharedTransitionLayout
}
internal fun hasClosetRodCutList(index: HardwoodCutlistIndex?): Boolean {
    return index
        ?.documents
        .orEmpty()
        .any { doc ->
            doc.docType == HardwoodDocType.CLOSET_ROD_CUT_LIST && doc.rows.isNotEmpty()
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
    headerLeading: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    headerLeading()
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    headerActions()
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
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
    onPatchDims: ((String?, Double?, String?) -> Unit)? = null,
    basePath: String = "",
    jobFolderName: String = "",
    onEditItem: ((com.kkc.sheettracker.data.models.SpecialtyItem) -> Unit)? = null,
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
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides androidx.compose.ui.unit.Dp.Unspecified
                    ) {
                        Checkbox(
                            checked = toggle.checked,
                            onCheckedChange = { next -> onCheckedChange(toggle, next) },
                            enabled = enabled,
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                    if (toggles.size > 1) {
                        Text(
                            text = shortDivisionLabel(toggle.label ?: toggle.completionKey),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        headerActions = {
            IconButton(
                onClick = { onEditItem?.invoke(item) },
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
                if (item.category == com.kkc.sheettracker.data.models.SpecialtyItemCategory.TO_ORDER) {
                    SpecialtyQuantitySection(
                        item = item,
                        onPatchQuantity = { q -> onPatchDims?.invoke(item.dimensions, q, item.material) }
                    )
                } else {
                    val isSawStation = SpecialtyStation.SAW in item.stations
                    if (isSawStation || item.dimensions != null || item.quantity != null || !item.material.isNullOrBlank()) {
                        SpecialtyDimsSection(
                            item = item,
                            isSawStation = isSawStation,
                            onPatchDims = { d, q, m -> onPatchDims?.invoke(d, q, m) }
                        )
                    }
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

internal data class SpecialtyLazyRowEntry<T>(
    val key: String,
    val item: T
)

internal fun specialtySheetRipLazyRowEntries(
    items: List<AdminBoardStockItem>
): List<SpecialtyLazyRowEntry<AdminBoardStockItem>> = items.map { item ->
    SpecialtyLazyRowEntry(
        key = "sheet-rip|${item.id}",
        item = item
    )
}

internal fun specialtySheetRipItems(
    items: List<AdminBoardStockItem>
): List<AdminBoardStockItem> = items.filter {
    it.mode.equals("sheet", ignoreCase = true) && it.feet != null && it.feet > 0
}

internal fun specialtySheetRipLengthLabel(rips: Int, ripLength: Int): String = "${rips}x ${ripLength}ft Sheet Rips"

internal fun specialtySheetRipItemLabel(item: AdminBoardStockItem): String =
    if (item.mode.equals("sheet", ignoreCase = true) && item.type.equals("crown", ignoreCase = true)) {
        "Plywood Crown — ${item.name}"
    } else {
        item.name
    }

internal fun specialtyChecklistLazyRowEntries(
    sectionId: String,
    items: List<SpecialtyResolvedItem>
): List<SpecialtyLazyRowEntry<SpecialtyResolvedItem>> = items.map { item ->
    SpecialtyLazyRowEntry(
        key = "section|$sectionId|${item.item.id}",
        item = item
    )
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
    onPatchDims: (String?, Double?, String?) -> Unit
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
                        onPatchDims(editDims.trim().takeIf { it.isNotBlank() }, editQty.trim().toDoubleOrNull(), editMat.trim().takeIf { it.isNotBlank() })
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

@Composable
private fun SpecialtyQuantitySection(
    item: com.kkc.sheettracker.data.models.SpecialtyItem,
    onPatchQuantity: (Double?) -> Unit
) {
    var editing by remember(item.id) { mutableStateOf(false) }
    var editQty by remember(item.id) { mutableStateOf(item.quantity?.toString() ?: "") }

    if (editing) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = editQty, onValueChange = { editQty = it }, label = { Text("Qty") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(onClick = {
                onPatchQuantity(editQty.trim().toDoubleOrNull())
                editing = false
            }) { Text("Save") }
            TextButton(onClick = {
                editQty = item.quantity?.toString() ?: ""
                editing = false
            }) { Text("Cancel") }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item.quantity != null) {
                SuggestionChip(onClick = { editing = true }, label = { Text("Qty: ${item.quantity}", style = MaterialTheme.typography.labelSmall) })
            } else {
                SuggestionChip(onClick = { editing = true }, label = { Text("Add quantity...", style = MaterialTheme.typography.labelSmall) })
            }
        }
    }
}
