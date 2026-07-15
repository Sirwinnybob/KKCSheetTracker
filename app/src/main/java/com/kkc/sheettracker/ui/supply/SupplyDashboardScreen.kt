package com.kkc.sheettracker.ui.supply

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.SupplyChange
import com.kkc.sheettracker.data.SupplyNotificationItem
import com.kkc.sheettracker.data.SupplyRepository
import com.kkc.sheettracker.data.SupplySubscriptionManager
import com.kkc.sheettracker.data.models.ALL_SUPPLY_STATUSES
import com.kkc.sheettracker.data.models.SUPPLY_STATUS_PRIORITY
import com.kkc.sheettracker.data.models.SupplyCategory
import com.kkc.sheettracker.data.models.SupplyItem
import com.kkc.sheettracker.data.AdminModeController
import com.kkc.sheettracker.data.ToOrderRepository
import com.kkc.sheettracker.data.ToOrderGroup
import com.kkc.sheettracker.data.UiPreferencesStore
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.data.SpecialtyProgressStore
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import androidx.compose.material3.Surface
import java.io.File
import com.kkc.sheettracker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.ExperimentalMaterial3Api
import com.kkc.sheettracker.ui.dashboard.DashboardShell
import com.kkc.sheettracker.ui.dashboard.DashboardAccent
import com.kkc.sheettracker.ui.dashboard.DashboardSectionHeader
import com.kkc.sheettracker.ui.dashboard.DashboardSurfaceCard
import com.kkc.sheettracker.ui.dashboard.DashboardSurfaceDefaults
import com.kkc.sheettracker.ui.dashboard.DashboardWidgetRenderer
import com.kkc.sheettracker.ui.dashboard.DashboardInventoryItemModel
import com.kkc.sheettracker.ui.dashboard.DashboardWidgetModel
import com.kkc.sheettracker.ui.dashboard.buildSupplyCategoryWidgets
import com.kkc.sheettracker.ui.dashboard.getSoftStatusColors

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import com.kkc.sheettracker.ui.components.StatusChip
import com.kkc.sheettracker.data.SupplyBarcodeStore
import com.kkc.sheettracker.data.ScanMode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SupplyDashboardScreen(
    basePath: String,
    tabletId: String,
    employeeName: String,
    subscriptionManager: SupplySubscriptionManager
) {
    val context = LocalContext.current
    val repository = remember(basePath) { SupplyRepository(basePath) }
    val scope = rememberCoroutineScope()

    var categories by remember { mutableStateOf<List<SupplyCategory>>(emptyList()) }
    var items by remember { mutableStateOf<List<SupplyItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var statusSheetItem by remember { mutableStateOf<SupplyItem?>(null) }
    var activeModal by remember { mutableStateOf<SupplyDashboardModal?>(null) }
    var editChromeState by remember { mutableStateOf<SupplyItemEditorChromeState?>(null) }
    val barcodeStore = remember(basePath) { SupplyBarcodeStore(basePath, repository) }
    val scanMode by barcodeStore.scanMode.collectAsState()
    val pickPendingBarcode by barcodeStore.pickPendingBarcode.collectAsState()
    var knownBarcodeResult by remember { mutableStateOf<Pair<SupplyItem, String>?>(null) }
    var unknownBarcodeResult by remember { mutableStateOf<String?>(null) }
    var itemToConfirmLink by remember { mutableStateOf<Pair<SupplyItem, String>?>(null) }
    var pendingNewItemBarcode by remember { mutableStateOf<String?>(null) }
    // Monotonic guard for the status-change write-then-reload sequence below: each status pick
    // launches its own write+reload coroutine, and two picks in quick succession can have the
    // older reload's getItems() result land after the newer one, stomping fresher state. Only
    // the reload that's still the most-recently-issued one when it completes is applied.
    val itemsReloadRequestId = remember { java.util.concurrent.atomic.AtomicLong(0L) }

    val subscriptionData by subscriptionManager.subscriptionData.collectAsState()
    val notificationCount by subscriptionManager.notificationCount.collectAsState()
    var notifications by remember { mutableStateOf<List<SupplyNotificationItem>>(emptyList()) }
    val categoryMap = remember(categories) { categories.associateBy { it.id } }

    // Admin-only "To Order" tab: cross-job aggregation of specialty/checklist TO_ORDER items,
    // mirroring the Hours Tracker web "To Order" tab. Placed next to the Needs Attention tab.
    val isAdminMode by AdminModeController.enabled.collectAsState()
    val preferencesStore = remember(context) { UiPreferencesStore(context) }
    var savedTabOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(context) {
        savedTabOrder = preferencesStore.getSupplyTabOrder()
    }
    val supplyTabs = remember(categories, isAdminMode, savedTabOrder) {
        // Ensure utility tabs are always at the beginning of the savedTabOrder
        val utilityIds = listOfNotNull(
            "updates",
            "needs_attention",
            if (isAdminMode) "to_order" else null
        )
        val cleanOrder = utilityIds + savedTabOrder.filter { it !in utilityIds }
        buildSupplyTabsList(categories, isAdminMode, cleanOrder)
    }
    val boardPageIndex = if (isAdminMode) 3 else 2
    val pagerState = rememberPagerState(pageCount = { boardPageIndex + 1 })
    val boardScrollState = rememberLazyListState()

    val sortedCategories = remember(categories, savedTabOrder) {
        val catMap = categories.associateBy { it.id }
        val sorted = mutableListOf<SupplyCategory>()
        savedTabOrder.forEach { id ->
            catMap[id]?.let { sorted.add(it) }
        }
        categories.forEach { cat ->
            if (!sorted.contains(cat)) sorted.add(cat)
        }
        sorted
    }

    val selectedTabIndex by remember(supplyTabs, pagerState.currentPage, boardScrollState, sortedCategories) {
        derivedStateOf {
            if (pagerState.currentPage < boardPageIndex) {
                pagerState.currentPage
            } else {
                val visibleIndex = boardScrollState.firstVisibleItemIndex
                val activeCat = sortedCategories.getOrNull(visibleIndex)
                if (activeCat != null) {
                    supplyTabs.indexOfFirst { it.id == activeCat.id }.coerceAtLeast(boardPageIndex)
                } else {
                    boardPageIndex
                }
            }
        }
    }

    val specialtyStore = remember(basePath, tabletId) {
        SpecialtyProgressStore(File(basePath), tabletId, readOnly = false)
    }
    val toOrderRepo = remember(basePath, tabletId) {
        ToOrderRepository(
            engine = UnifiedMetadataEngineRegistry.getOrCreate(File(basePath), BuildConfig.DEBUG),
            specialtyStore = specialtyStore
        )
    }
    var toOrderGroups by remember { mutableStateOf<List<ToOrderGroup>>(emptyList()) }
    var toOrderLoading by remember { mutableStateOf(false) }
    var editingToOrderItem by remember { mutableStateOf<Pair<String, SpecialtyResolvedItem>?>(null) }
    LaunchedEffect(isAdminMode) {
        if (isAdminMode) {
            toOrderLoading = true
            toOrderGroups = withContext(Dispatchers.IO) { toOrderRepo.loadGroups() }
            toOrderLoading = false
        }
    }

    // Categories can reload smaller/empty while the pager still points at a former category page.
    // Snap back into range so the tab row never reads an index past the tab list.
    LaunchedEffect(boardPageIndex) {
        if (pagerState.currentPage > boardPageIndex) {
            pagerState.scrollToPage(boardPageIndex)
        }
    }

    suspend fun loadData(showLoading: Boolean = true) {
        if (showLoading) {
            isLoading = true
        }
        errorMessage = null
        try {
            val cats = withContext(Dispatchers.IO) { repository.getCategories() }.sortedBy { it.position }
            val its = withContext(Dispatchers.IO) { repository.getItems() }
            categories = cats
            items = its
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load supply data"
        } finally {
            if (showLoading) {
                isLoading = false
            }
        }
    }

    suspend fun reloadUpdates() {
        notifications = withContext(Dispatchers.IO) { subscriptionManager.scanForUpdates() }
    }

    fun openDetailModal(itemId: String) {
        val pending = pickPendingBarcode
        if (pending != null) {
            val item = items.firstOrNull { it.id == itemId }
            if (item != null) {
                itemToConfirmLink = Pair(item, pending)
            }
        } else {
            editChromeState = null
            activeModal = SupplyDashboardModal.Detail(itemId)
        }
    }

    fun openNewItemModal(categoryId: String) {
        editChromeState = null
        activeModal = SupplyDashboardModal.NewItem(categoryId)
    }

    fun dismissSupplyModal() {
        activeModal = null
        editChromeState = null
        scope.launch {
            loadData(showLoading = false)
            reloadUpdates()
        }
    }

    LaunchedEffect(basePath) { loadData() }
    LaunchedEffect(items, subscriptionData) { reloadUpdates() }

    val currentCategoryId = if (!isLoading && searchQuery.isBlank() && supplyTabs.isNotEmpty()) {
        val activeTab = supplyTabs.getOrNull(pagerState.currentPage)
        if (activeTab?.type is SupplyTabType.CategoryTab) {
            activeTab.type.category.id
        } else null
    } else null

    DashboardShell(
        title = "Supply Inventory",
        subtitle = "Supply",
        loading = isLoading,
        errorMessage = errorMessage,
        emptyMessage = "No supply data is available yet.",
        hasContent = !isLoading && errorMessage == null && (items.isNotEmpty() || categories.isNotEmpty() || notifications.isNotEmpty()),
        scrollable = false,
        onRefresh = { scope.launch { loadData(); reloadUpdates() } },
        topBarActions = {
            IconButton(onClick = { barcodeStore.setScanMode(ScanMode.Global) }) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan barcode")
            }
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Add Category") },
                        onClick = {
                            showOverflowMenu = false
                            newCategoryName = ""
                            showAddCategoryDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Reorder Tabs") },
                        onClick = {
                            showOverflowMenu = false
                            activeModal = SupplyDashboardModal.ReorderTabs
                        }
                    )
                    val activeTab = supplyTabs.getOrNull(pagerState.currentPage)
                    if (activeTab?.type is SupplyTabType.CategoryTab) {
                        val currentCat = activeTab.type.category
                        val isSubscribed = subscriptionData.subscribedCategoryIds.contains(currentCat.id)
                        DropdownMenuItem(
                            text = { Text(if (isSubscribed) "Unsubscribe from Category" else "Subscribe to Category") },
                            onClick = {
                                showOverflowMenu = false
                                scope.launch { subscriptionManager.toggleCategorySubscription(currentCat.id) }
                            }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            currentCategoryId?.let { catId ->
                FloatingActionButton(onClick = { openNewItemModal(catId) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add item")
                }
            }
        }
    ) {
        // Pick mode banner
        AnimatedVisibility(visible = pickPendingBarcode != null, enter = expandVertically(), exit = shrinkVertically()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Tap an item to link barcode", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    IconButton(onClick = { barcodeStore.clearPickMode() }) {
                        Icon(Icons.Filled.Close, "Cancel link", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }

        DashboardSurfaceCard(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search items...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )
        }

        when {
            searchQuery.isNotBlank() -> {
                val filtered = items.filter { it.name.contains(searchQuery, ignoreCase = true) }
                    .sortedBy { SUPPLY_STATUS_PRIORITY[it.status] ?: 99 }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DashboardWidgetRenderer(
                        widgets = listOf(
                            DashboardWidgetModel.InventoryBlock(
                                key = "supply-search",
                                title = "Search Results",
                                items = filtered.map { toInventoryItemModel(it, categoryMap) },
                                summary = if (filtered.isEmpty()) null else "${filtered.size} matching items",
                                emptyMessage = "No items match \"$searchQuery\""
                            )
                        ),
                        onItemClick = { item ->
                            if (item is DashboardInventoryItemModel) {
                                openDetailModal(item.id)
                            }
                        },
                        onItemLongPress = { item ->
                            statusSheetItem = items.firstOrNull { it.id == item.id }
                        }
                    )
                }
            }

            else -> {
                DashboardSurfaceCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    SecondaryScrollableTabRow(
                        selectedTabIndex = selectedTabIndex,
                        containerColor = TabRowDefaults.primaryContainerColor,
                        contentColor = TabRowDefaults.primaryContentColor,
                        edgePadding = 12.dp,
                        indicator = {
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(selectedTabIndex, matchContentSize = false)
                            )
                        }
                    ) {
                        supplyTabs.forEachIndexed { index, tabItem ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = {
                                    scope.launch {
                                        if (tabItem.type is SupplyTabType.CategoryTab) {
                                            pagerState.animateScrollToPage(boardPageIndex)
                                            val catIndex = sortedCategories.indexOf(tabItem.type.category)
                                            if (catIndex >= 0) {
                                                boardScrollState.animateScrollToItem(catIndex)
                                            }
                                        } else {
                                            pagerState.animateScrollToPage(index)
                                        }
                                    }
                                },
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            tabItem.name.uppercase(java.util.Locale.getDefault()),
                                            maxLines = 1,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                        )
                                        when (val type = tabItem.type) {
                                            SupplyTabType.Updates -> {
                                                if (notificationCount > 0) {
                                                    Badge { Text(notificationCount.toString()) }
                                                }
                                            }
                                            SupplyTabType.NeedsAttention -> {
                                                val attentionCount = items.count { (SUPPLY_STATUS_PRIORITY[it.status] ?: 99) < 5 }
                                                if (attentionCount > 0) {
                                                    Badge { Text(attentionCount.toString()) }
                                                }
                                            }
                                            SupplyTabType.ToOrder -> {
                                                val count = toOrderGroups.sumOf { it.items.size }
                                                if (count > 0) {
                                                    Badge { Text(count.toString()) }
                                                }
                                            }
                                            is SupplyTabType.CategoryTab -> {
                                                val isSubscribed = subscriptionData.subscribedCategoryIds.contains(type.category.id)
                                                if (isSubscribed) {
                                                    Icon(
                                                        Icons.Filled.Notifications,
                                                        contentDescription = "Subscribed",
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { page ->
                    if (page == boardPageIndex) {
                        LazyRow(
                            state = boardScrollState,
                            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            items(sortedCategories, key = { it.id }) { category ->
                                val categoryItems = items.filter { it.categoryId == category.id }
                                CategoryBoardColumn(
                                    category = category,
                                    items = categoryItems,
                                    onAddItem = { openNewItemModal(category.id) },
                                    onOpenItem = ::openDetailModal,
                                    onLongPress = { item -> statusSheetItem = item }
                                )
                            }
                        }
                    } else {
                        val tabItem = supplyTabs.getOrNull(page)
                        if (tabItem != null) {
                            when (val type = tabItem.type) {
                                SupplyTabType.Updates -> UpdatesPage(
                                    notifications = notifications,
                                    categories = categories,
                                    subscriptionManager = subscriptionManager,
                                    onOpenItem = ::openDetailModal,
                                    reloadUpdates = { scope.launch { reloadUpdates() } },
                                    modifier = Modifier.fillMaxSize()
                                )
                                SupplyTabType.NeedsAttention -> NeedsAttentionPage(
                                    items = items,
                                    categories = categories,
                                    onOpenItem = ::openDetailModal,
                                    onLongPress = { item -> statusSheetItem = items.firstOrNull { it.id == item.id } },
                                    modifier = Modifier.fillMaxSize()
                                )
                                SupplyTabType.ToOrder -> ToOrderPage(
                                    groups = toOrderGroups,
                                    loading = toOrderLoading,
                                    onToggleComplete = { jobFolder, resolvedItem, completed ->
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                specialtyStore.setCompletion(
                                                    jobFolderName = jobFolder,
                                                    itemId = resolvedItem.item.id,
                                                    completionKey = SpecialtyProgressStore.ITEM_COMPLETION_KEY,
                                                    completed = completed,
                                                    completedBy = employeeName.ifBlank { "Floor" },
                                                    completedAt = java.time.Instant.now().toString()
                                                )
                                                // Auto-fill orderDate on first check-off
                                                if (completed && resolvedItem.item.orderDate.isNullOrBlank()) {
                                                    val today = defaultToOrderDate(java.time.LocalDate.now())
                                                    val item = resolvedItem.item
                                                    specialtyStore.updateToOrderItem(
                                                        jobFolderName = jobFolder,
                                                        itemId = item.id,
                                                        name = item.name,
                                                        cabinetNumbers = item.cabinetNumbers,
                                                        supplier = item.supplier,
                                                        model = item.model,
                                                        orderDate = today,
                                                        tracking = item.tracking,
                                                        orderUrl = item.orderUrl,
                                                        notes = item.notes,
                                                        quantity = item.quantity,
                                                        material = item.material,
                                                        dimensions = item.dimensions
                                                    )
                                                }
                                            }
                                            toOrderGroups = withContext(Dispatchers.IO) { toOrderRepo.loadGroups() }
                                        }
                                    },
                                    onEditItem = { jobFolder, resolvedItem ->
                                        editingToOrderItem = Pair(jobFolder, resolvedItem)
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddCategoryDialog) {
        SupplyModalFrame(
            title = "New Category",
            onDismiss = { showAddCategoryDialog = false },
            modifier = Modifier.heightIn(max = 420.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text("Category name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showAddCategoryDialog = false }) { Text("Cancel") }
                    Button(
                        onClick = {
                            val name = newCategoryName.trim()
                            if (name.isNotBlank()) {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { repository.createCategory(name) } }
                                    result.onFailure { Toast.makeText(context, "Failed to create category: ${it.message}", Toast.LENGTH_LONG).show() }
                                    showAddCategoryDialog = false
                                    loadData(showLoading = false)
                                }
                            }
                        },
                        enabled = newCategoryName.isNotBlank()
                    ) { Text("Create") }
                }
            }
        }
    }

    when (val modal = activeModal) {
        is SupplyDashboardModal.Detail -> {
            val isSubscribed = subscriptionData.subscribedItemIds.contains(modal.itemId)
            val item = items.firstOrNull { it.id == modal.itemId }
            val itemTitle = item?.name ?: "Supply Item"
            SupplyModalFrame(
                title = "",
                onDismiss = { dismissSupplyModal() },
                headerTint = supplyStatusHeaderTint(item?.status),
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            subscriptionManager.toggleItemSubscription(modal.itemId)
                        }
                    }) {
                        Icon(
                            imageVector = if (isSubscribed) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                            contentDescription = if (isSubscribed) "Unsubscribe from notifications" else "Subscribe to notifications",
                            tint = if (isSubscribed) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = {
                        editChromeState = null
                        activeModal = SupplyDashboardModal.EditItem(modal.itemId)
                    }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit item")
                    }
                }
            ) {
                SupplyItemDetailScreen(
                    itemId = modal.itemId,
                    basePath = basePath,
                    tabletId = tabletId,
                    employeeName = employeeName,
                    onBack = { dismissSupplyModal() },
                    onEdit = {
                        editChromeState = null
                        activeModal = SupplyDashboardModal.EditItem(modal.itemId)
                    },
                    subscriptionManager = subscriptionManager,
                    barcodeStore = barcodeStore
                )
            }
        }
        is SupplyDashboardModal.EditItem -> {
            val item = items.firstOrNull { it.id == modal.itemId }
            SupplyModalFrame(
                title = "Edit Item",
                onDismiss = { dismissSupplyModal() },
                headerTint = supplyStatusHeaderTint(editChromeState?.status ?: item?.status),
                actions = {
                    editChromeState?.let { chrome ->
                        TextButton(
                            onClick = chrome.onSave,
                            enabled = chrome.canSave
                        ) {
                            if (chrome.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Save")
                            }
                        }
                    }
                }
            ) {
                SupplyItemEditScreen(
                    itemId = modal.itemId,
                    initialCategoryId = null,
                    basePath = basePath,
                    tabletId = tabletId,
                    employeeName = employeeName,
                    onBack = { dismissSupplyModal() },
                    onSaved = { savedItemId ->
                        scope.launch {
                            loadData(showLoading = false)
                            reloadUpdates()
                            editChromeState = null
                            activeModal = SupplyDashboardModal.Detail(savedItemId)
                        }
                    },
                    onChromeStateChanged = { editChromeState = it }
                )
            }
        }
        is SupplyDashboardModal.NewItem -> {
            SupplyModalFrame(
                title = "New Item",
                onDismiss = { dismissSupplyModal() },
                headerTint = supplyStatusHeaderTint(editChromeState?.status ?: "IN STOCK"),
                actions = {
                    editChromeState?.let { chrome ->
                        TextButton(
                            onClick = chrome.onSave,
                            enabled = chrome.canSave
                        ) {
                            if (chrome.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Save")
                            }
                        }
                    }
                }
            ) {
                SupplyItemEditScreen(
                    itemId = null,
                    initialCategoryId = modal.categoryId,
                    basePath = basePath,
                    tabletId = tabletId,
                    employeeName = employeeName,
                    onBack = { dismissSupplyModal() },
                    onSaved = { savedItemId ->
                        scope.launch {
                            val barcode = pendingNewItemBarcode
                            if (barcode != null) {
                                barcodeStore.link(barcode, savedItemId)
                                pendingNewItemBarcode = null
                            }
                            loadData(showLoading = false)
                            reloadUpdates()
                            editChromeState = null
                            activeModal = SupplyDashboardModal.Detail(savedItemId)
                        }
                    },
                    onChromeStateChanged = { editChromeState = it }
                )
            }
        }
        is SupplyDashboardModal.ReorderTabs -> {
            SupplyModalFrame(
                title = "Reorder Tabs",
                onDismiss = { dismissSupplyModal() },
                headerTint = null
            ) {
                val categoryTabs = remember(supplyTabs) {
                    supplyTabs.filter { it.type is SupplyTabType.CategoryTab }
                }
                SupplyTabReorderScreen(
                    availableTabs = categoryTabs,
                    preferencesStore = preferencesStore,
                    onOrderChanged = { newOrder ->
                        val selectedTabId = supplyTabs.getOrNull(selectedTabIndex)?.id
                        savedTabOrder = newOrder
                        if (selectedTabId != null) {
                            val utilityIds = listOfNotNull(
                                "updates",
                                "needs_attention",
                                if (isAdminMode) "to_order" else null
                            )
                            val cleanOrder = utilityIds + newOrder.filter { it !in utilityIds }
                            val nextTabs = buildSupplyTabsList(categories, isAdminMode, cleanOrder)
                            val newIndex = nextTabs.indexOfFirst { it.id == selectedTabId }
                            if (newIndex >= 0) {
                                scope.launch {
                                    val nextTabItem = nextTabs.getOrNull(newIndex)
                                    if (nextTabItem?.type is SupplyTabType.CategoryTab) {
                                        pagerState.scrollToPage(boardPageIndex)
                                        val catIndex = newOrder.indexOf(nextTabItem.type.category.id)
                                        if (catIndex >= 0) {
                                            boardScrollState.scrollToItem(catIndex)
                                        }
                                    } else {
                                        pagerState.scrollToPage(newIndex)
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
        null -> Unit
    }

    editingToOrderItem?.let { (jobFolder, resolvedItem) ->
        ToOrderEditDialog(
            item = resolvedItem.item,
            onDismiss = { editingToOrderItem = null },
            onSave = { name, cabNums, supplier, model, orderDate, tracking, orderUrl, notes, qty, material, dimensions ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            specialtyStore.updateToOrderItem(
                                jobFolderName = jobFolder,
                                itemId = resolvedItem.item.id,
                                name = name,
                                cabinetNumbers = cabNums,
                                supplier = supplier,
                                model = model,
                                orderDate = orderDate,
                                tracking = tracking,
                                orderUrl = orderUrl,
                                notes = notes,
                                quantity = qty,
                                material = material,
                                dimensions = dimensions
                            )
                        }
                    }
                    result.onFailure {
                        Toast.makeText(context, "Failed to save: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                    editingToOrderItem = null
                    toOrderGroups = withContext(Dispatchers.IO) { toOrderRepo.loadGroups() }
                }
            }
        )
    }

    statusSheetItem?.let { item ->
        SupplyPickerDialog(
            title = "Change Status: ${item.name}",
            options = ALL_SUPPLY_STATUSES.map { status ->
                SupplyPickerOption(
                    id = status,
                    label = status,
                    selected = item.status == status,
                    onClick = {
                        scope.launch {
                            statusSheetItem = null
                            performSupplyStatusChange(
                                setStatus = { withContext(Dispatchers.IO) { repository.setStatus(item.id, status, employeeName.ifBlank { "Floor" }, tabletId) } },
                                reloadItems = { withContext(Dispatchers.IO) { repository.getItems() } },
                                currentItems = { items },
                                requestIdCounter = itemsReloadRequestId,
                                onItemsReloaded = { items = it },
                                onFailure = { Toast.makeText(context, "Failed to change status: ${it.message}", Toast.LENGTH_LONG).show() }
                            )
                        }
                    },
                    icon = {
                        Box(modifier = Modifier.size(12.dp).background(supplyStatusColor(SUPPLY_STATUS_PRIORITY[status] ?: 99), CircleShape))
                    }
                )
            },
            onDismiss = { statusSheetItem = null },
            headerTint = supplyStatusHeaderTint(item.status)
        )
    }

    // Scanner overlay (Global mode)
    if (scanMode != ScanMode.Idle && scanMode == ScanMode.Global) {
        val knownSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val unknownSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        SupplyScannerOverlay(
            barcodeStore = barcodeStore,
            isModalActive = knownBarcodeResult != null || unknownBarcodeResult != null,
            onDismiss = { barcodeStore.setScanMode(ScanMode.Idle) },
            onKnownBarcode = { item, barcode -> knownBarcodeResult = Pair(item, barcode) },
            onUnknownBarcode = { barcode -> unknownBarcodeResult = barcode }
        )

        knownBarcodeResult?.let { (item, barcode) ->
            KnownBarcodeSheet(
                item = item,
                categoryName = categoryMap[item.categoryId]?.name ?: "",
                sheetState = knownSheetState,
                onStatusPick = { newStatus ->
                    scope.launch {
                        withContext(Dispatchers.IO) { repository.setStatus(item.id, newStatus, employeeName, tabletId) }
                        barcodeStore.setScanMode(ScanMode.Idle)
                        knownBarcodeResult = null
                        loadData()
                    }
                },
                onViewItem = {
                    barcodeStore.setScanMode(ScanMode.Idle)
                    knownBarcodeResult = null
                    activeModal = SupplyDashboardModal.Detail(item.id)
                },
                onDismiss = { knownBarcodeResult = null }
            )
        }

        unknownBarcodeResult?.let { barcode ->
            UnknownBarcodeSheet(
                barcode = barcode,
                sheetState = unknownSheetState,
                onLinkToExisting = {
                    unknownBarcodeResult = null
                    barcodeStore.setScanMode(ScanMode.Idle)
                    barcodeStore.setPickPendingBarcode(barcode)
                },
                onAddNewItem = {
                    unknownBarcodeResult = null
                    barcodeStore.setScanMode(ScanMode.Idle)
                    pendingNewItemBarcode = barcode
                    openNewItemModal(currentCategoryId ?: categories.firstOrNull()?.id ?: "")
                },
                onDismiss = { unknownBarcodeResult = null }
            )
        }
    }

    // Pick mode link confirmation dialog
    itemToConfirmLink?.let { (item, barcode) ->
        AlertDialog(
            onDismissRequest = { itemToConfirmLink = null },
            title = { Text("Link barcode?") },
            text = { Text("Link \"${barcode.take(20)}\" to ${item.name}?") },
            confirmButton = {
                TextButton(onClick = {
                    itemToConfirmLink = null
                    scope.launch { barcodeStore.link(barcode, item.id); barcodeStore.clearPickMode(); loadData() }
                }) { Text("Link") }
            },
            dismissButton = { TextButton(onClick = { itemToConfirmLink = null }) { Text("Cancel") } }
        )
    }
}

private sealed interface SupplyDashboardModal {
    data class Detail(val itemId: String) : SupplyDashboardModal
    data class EditItem(val itemId: String) : SupplyDashboardModal
    data class NewItem(val categoryId: String) : SupplyDashboardModal
    object ReorderTabs : SupplyDashboardModal
}

/**
 * Writes a status change then reloads the full item list, applying the reload to state only if
 * no newer status change was issued while this reload was in flight. Without this guard, two
 * status changes issued in quick succession (e.g. two rapid taps in the status picker) can have
 * the older write's reload land after the newer one's, silently reverting the UI to stale data
 * even though the newer write already succeeded on disk.
 *
 * [requestIdCounter] must be shared across all callers whose results should be mutually
 * ordered (i.e. one counter per `items` state). Extracted from the Composable body so the
 * ordering guard is unit-testable without Compose/Android.
 */
internal suspend fun performSupplyStatusChange(
    setStatus: suspend () -> Unit,
    reloadItems: suspend () -> List<SupplyItem>,
    currentItems: () -> List<SupplyItem>,
    requestIdCounter: java.util.concurrent.atomic.AtomicLong,
    onItemsReloaded: (List<SupplyItem>) -> Unit,
    onFailure: (Throwable) -> Unit = {}
) {
    val statusResult = runCatching { setStatus() }
    statusResult.onFailure(onFailure)
    val requestId = requestIdCounter.incrementAndGet()
    val reloaded = runCatching { reloadItems() }.getOrDefault(currentItems())
    if (requestId == requestIdCounter.get()) {
        onItemsReloaded(reloaded)
    }
}

@Composable
private fun CategoryAddHeader(
    category: SupplyCategory,
    onAddItem: () -> Unit
) {
    DashboardSurfaceCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(category.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Category inventory",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onAddItem) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Item")
            }
        }
    }
}

@Composable
private fun EmptyCategoryAddCard(
    category: SupplyCategory,
    onAddItem: () -> Unit
) {
    DashboardSurfaceCard(accent = DashboardAccent.INFO) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("No items in ${category.name}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add the first item so this category is useful on the floor.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onAddItem) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Item")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun UpdatesPage(
    notifications: List<SupplyNotificationItem>,
    categories: List<SupplyCategory>,
    subscriptionManager: SupplySubscriptionManager,
    onOpenItem: (String) -> Unit,
    reloadUpdates: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            DashboardSurfaceCard(accent = if (notifications.isEmpty()) DashboardAccent.NEUTRAL else DashboardAccent.INFO) {
                DashboardSectionHeader(
                    title = "Updates",
                    subtitle = if (notifications.isEmpty()) {
                        "No active notifications right now."
                    } else {
                        "${notifications.size} update${if (notifications.size == 1) "" else "s"} across subscribed items"
                    }
                )
                if (notifications.isEmpty()) {
                    Text(
                        "Subscribe to items or categories to receive updates here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        items(notifications, key = { it.item.id }) { notification ->
            @Suppress("DEPRECATION")
            val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                    scope.launch {
                        subscriptionManager.dismissNotification(notification.item.id)
                        reloadUpdates()
                    }
                    true
                } else {
                    false
                }
            })
            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            ) {
                DashboardSurfaceCard(
                    accent = supplyAccent(notification.item.status),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenItem(notification.item.id) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(2.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            notification.item.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            categories.find { it.id == notification.item.categoryId }?.name ?: "Unknown Category",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        notification.changes.forEach { change ->
                            val text = when (change) {
                                is SupplyChange.NewSubscriptionOrItem -> "New subscription or item added"
                                is SupplyChange.DetailsUpdated -> "Details updated"
                                is SupplyChange.StatusChanged -> "Status changed to ${change.status}"
                                is SupplyChange.NewComments -> "${change.count} new comment(s)"
                                is SupplyChange.NewAttachments -> "${change.count} new attachment(s)"
                            }
                            Text("• $text", style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    subscriptionManager.dismissNotification(notification.item.id)
                                    reloadUpdates()
                                }
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

private fun toInventoryItemModel(
    item: SupplyItem,
    categoryMap: Map<String, SupplyCategory> = emptyMap()
): DashboardInventoryItemModel {
    val quantity = item.fields["quantity"]?.takeIf { it.isNotBlank() }
    val sku = item.fields["sku"]?.takeIf { it.isNotBlank() }
    val supportingText = listOfNotNull(
        quantity?.let { "Qty $it" },
        item.notes?.takeIf { it.isNotBlank() }
    ).joinToString("\n").ifBlank { null }
    return DashboardInventoryItemModel(
        id = item.id,
        title = item.name,
        subtitle = categoryMap[item.categoryId]?.name ?: "",
        supportingText = supportingText,
        badge = item.status,
        accent = supplyAccent(item.status),
        sku = sku,
        quantity = quantity,
        notes = item.notes
    )
}

fun supplyStatusColor(tier: Int): Color = when (tier) {
    1, 2 -> Color(0xFFD32F2F)        // red   — OUT / ASAP / NEED
    3 -> Color(0xFFEF6C00)            // orange — LOW
    4 -> Color(0xFF1565C0)            // blue  — ORDERED / IN PROCESS
    5 -> Color(0xFF2E7D32)            // green — IN STOCK / COMPLETE
    6 -> Color(0xFFF59E0B)            // amber — NOT ORDERED
    7 -> Color(0xFF388E3C)            // green — ORDERED (To Order)
    else -> Color(0xFF2E7D32)         // default green
}

fun supplyStatusHeaderTint(status: String?): Color? {
    val normalized = status?.takeIf { it.isNotBlank() } ?: return null
    return supplyStatusColor(SUPPLY_STATUS_PRIORITY[normalized] ?: 99)
}

fun supplyAccent(status: String): DashboardAccent = when (status.uppercase()) {
    "OUT", "ASAP", "MALFUNCTIONING", "NEED" -> DashboardAccent.DANGER
    "LOW" -> DashboardAccent.WARNING
    "ORDERED", "IN PROCESS", "ACKNOWLEDGED" -> DashboardAccent.INFO
    "IN STOCK", "COMPLETE", "RECEIVED" -> DashboardAccent.SUCCESS
    else -> DashboardAccent.NEUTRAL
}

private data class AttentionTier(
    val title: String,
    val statuses: Set<String>,
    val accent: DashboardAccent
)

private val ATTENTION_TIERS = listOf(
    AttentionTier("Critical", setOf("OUT", "ASAP"), DashboardAccent.DANGER),
    AttentionTier("Urgent", setOf("MALFUNCTIONING", "NEED"), DashboardAccent.DANGER),
    AttentionTier("Low Stock", setOf("LOW"), DashboardAccent.WARNING),
    AttentionTier("In Progress", setOf("ORDERED", "IN PROCESS", "ACKNOWLEDGED"), DashboardAccent.INFO),
)

@Composable
private fun NeedsAttentionPage(
    items: List<SupplyItem>,
    categories: List<SupplyCategory>,
    onOpenItem: (String) -> Unit,
    onLongPress: (DashboardInventoryItemModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val categoryMap = remember(categories) { categories.associateBy { it.id } }

    val tierGroups = remember(items) {
        ATTENTION_TIERS.mapNotNull { tier ->
            val tierItems = items
                .filter { it.status.uppercase() in tier.statuses }
                .sortedWith(compareBy({ categoryMap[it.categoryId]?.name ?: "" }, { it.name }))
            if (tierItems.isEmpty()) null else tier to tierItems
        }
    }

    if (tierGroups.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("All Clear", style = MaterialTheme.typography.titleMedium)
                Text(
                    "No items need attention right now",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        tierGroups.forEach { (tier, tierItems) ->
            DashboardWidgetRenderer(
                widgets = listOf(
                    DashboardWidgetModel.InventoryBlock(
                        key = "attention-${tier.title}",
                        title = tier.title,
                        subtitle = "${tierItems.size} item${if (tierItems.size == 1) "" else "s"}",
                        items = tierItems.map { item ->
                            val categoryName = categoryMap[item.categoryId]?.name
                            val quantity = item.fields["quantity"]?.takeIf { it.isNotBlank() }
                            val supporting = listOfNotNull(
                                quantity?.let { "Qty $it" },
                                item.notes?.takeIf { it.isNotBlank() }
                            ).joinToString("\n").ifBlank { null }
                            DashboardInventoryItemModel(
                                id = item.id,
                                title = item.name,
                                subtitle = categoryName ?: "",
                                supportingText = supporting,
                                badge = item.status,
                                accent = supplyAccent(item.status)
                            )
                        }
                    )
                ),
                onItemClick = { item ->
                    if (item is DashboardInventoryItemModel) {
                        onOpenItem(item.id)
                    }
                },
                onItemLongPress = { item ->
                    if (item is DashboardInventoryItemModel) onLongPress(item)
                }
            )
        }
    }
}

/**
 * Admin-only cross-job "To Order" view: one section per job, listing that job's specialty +
 * checklist items flagged TO_ORDER. Fully editable.
 */
@Composable
private fun ToOrderPage(
    groups: List<ToOrderGroup>,
    loading: Boolean,
    onToggleComplete: (jobFolderName: String, item: SpecialtyResolvedItem, completed: Boolean) -> Unit,
    onEditItem: (jobFolderName: String, item: SpecialtyResolvedItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var collapsedJobIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(groups) {
        val visibleJobIds = groups.map { it.folderName }.toSet()
        collapsedJobIds = collapsedJobIds
            .filter { it in visibleJobIds }
            .toSet() + autoCollapsedToOrderJobIds(groups)
    }

    if (loading) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (groups.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Nothing to order", style = MaterialTheme.typography.titleMedium)
                Text(
                    "No items are flagged To Order across active jobs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        buildToOrderJobSections(groups, collapsedJobIds).forEach { section ->
            ToOrderJobSectionCard(
                section = section,
                onToggleCollapsed = {
                    collapsedJobIds = toggleToOrderJobCollapse(collapsedJobIds, section.group.folderName)
                },
                onToggleComplete = onToggleComplete,
                onEditItem = onEditItem
            )
        }
    }
}

internal data class ToOrderJobSection(
    val group: ToOrderGroup,
    val completedCount: Int,
    val totalCount: Int,
    val isCollapsed: Boolean
) {
    val isFullyOrdered: Boolean = totalCount > 0 && completedCount == totalCount
}

internal fun autoCollapsedToOrderJobIds(groups: List<ToOrderGroup>): Set<String> {
    return groups
        .filter { group -> group.items.isNotEmpty() && group.items.all { it.isComplete } }
        .map { it.folderName }
        .toSet()
}

internal fun toggleToOrderJobCollapse(collapsedJobIds: Set<String>, folderName: String): Set<String> {
    return if (folderName in collapsedJobIds) {
        collapsedJobIds - folderName
    } else {
        collapsedJobIds + folderName
    }
}

internal fun buildToOrderJobSections(
    groups: List<ToOrderGroup>,
    collapsedJobIds: Set<String>
): List<ToOrderJobSection> {
    return groups.map { group ->
        ToOrderJobSection(
            group = group,
            completedCount = group.items.count { it.isComplete },
            totalCount = group.items.size,
            isCollapsed = group.folderName in collapsedJobIds
        )
    }
}

@Composable
private fun ToOrderJobSectionCard(
    section: ToOrderJobSection,
    onToggleCollapsed: () -> Unit,
    onToggleComplete: (jobFolderName: String, item: SpecialtyResolvedItem, completed: Boolean) -> Unit,
    onEditItem: (jobFolderName: String, item: SpecialtyResolvedItem) -> Unit
) {
    val accent = if (section.isFullyOrdered) DashboardAccent.SUCCESS else DashboardAccent.WARNING

    DashboardSurfaceCard(
        accent = accent,
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleCollapsed)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DashboardSectionHeader(
                    title = section.group.jobName.ifBlank { section.group.folderName },
                    subtitle = buildString {
                        append(section.group.jobNumber.ifBlank { section.group.folderName })
                        append(" • ")
                        append(section.completedCount)
                        append(" / ")
                        append(section.totalCount)
                        append(" ordered")
                    }
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val statusText = if (section.isFullyOrdered) "ORDERED" else "OPEN"
                val baseColor = supplyStatusColor(if (section.isFullyOrdered) 7 else 6)
                val (chipBgColor, chipTextColor) = getSoftStatusColors(statusText, baseColor)
                StatusChip(
                    text = statusText,
                    backgroundColor = chipBgColor,
                    contentColor = chipTextColor
                )
                Icon(
                    imageVector = if (section.isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = if (section.isCollapsed) "Expand job" else "Collapse job"
                )
            }
        }

        AnimatedVisibility(
            visible = !section.isCollapsed,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                section.group.items.forEach { resolvedItem ->
                    ToOrderItemRow(
                        jobFolderName = section.group.folderName,
                        resolvedItem = resolvedItem,
                        onEditItem = onEditItem,
                        onToggleComplete = onToggleComplete
                    )
                }
            }
        }
    }
}

@Composable
private fun ToOrderItemRow(
    jobFolderName: String,
    resolvedItem: SpecialtyResolvedItem,
    onEditItem: (jobFolderName: String, item: SpecialtyResolvedItem) -> Unit,
    onToggleComplete: (jobFolderName: String, item: SpecialtyResolvedItem, completed: Boolean) -> Unit
) {
    val item = resolvedItem.item
    val status = toOrderStatusLabel(resolvedItem.isComplete)
    val tier = if (resolvedItem.isComplete) 7 else 6
    val accent = if (resolvedItem.isComplete) DashboardAccent.SUCCESS else DashboardAccent.WARNING
    val cardText = toOrderItemCardText(item)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onEditItem(jobFolderName, resolvedItem) },
                onLongClick = { onToggleComplete(jobFolderName, resolvedItem, !resolvedItem.isComplete) }
            )
            .background(DashboardSurfaceDefaults.accentWash(accent))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { onToggleComplete(jobFolderName, resolvedItem, !resolvedItem.isComplete) }
        ) {
            Icon(
                imageVector = if (resolvedItem.isComplete) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = if (resolvedItem.isComplete) "Mark not ordered" else "Mark ordered",
                tint = supplyStatusColor(tier)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val baseColor = supplyStatusColor(tier)
                val (chipBgColor, chipTextColor) = getSoftStatusColors(status, baseColor)
                StatusChip(
                    text = status,
                    backgroundColor = chipBgColor,
                    contentColor = chipTextColor
                )
                if (resolvedItem.isComplete && !cardText.orderDateLabel.isNullOrBlank()) {
                    StatusChip(
                        text = cardText.orderDateLabel,
                        backgroundColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                cardText.cabinetLabel?.let { cabinetLabel ->
                    Text(
                        cabinetLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    cardText.itemName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                cardText.quantityLabel?.let { quantityLabel ->
                    Text(
                        quantityLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            if (cardText.supportingText.isNotBlank()) {
                Text(
                    cardText.supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal data class ToOrderItemCardText(
    val cabinetLabel: String?,
    val itemName: String,
    val quantityLabel: String?,
    val orderDateLabel: String?,
    val supportingText: String
)

internal fun toOrderStatusLabel(isComplete: Boolean): String {
    return if (isComplete) "ORDERED" else "NOT ORDERED"
}

internal fun defaultToOrderDate(date: java.time.LocalDate): String {
    return date.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))
}

internal fun toOrderItemCardText(item: SpecialtyItem): ToOrderItemCardText {
    val cabinetLabel = when {
        !item.cabinetLabel.isNullOrBlank() -> "Cab #${item.cabinetLabel}"
        item.cabinetNumbers.isNotEmpty() -> "Cab #${item.cabinetNumbers.joinToString(", ")}"
        else -> null
    }
    val quantityLabel = item.quantity?.let { quantity ->
        val label = if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
        "Qty $label"
    }
    val supportingText = listOfNotNull(
        item.dimensions?.takeIf { it.isNotBlank() },
        item.material?.takeIf { it.isNotBlank() },
        item.supplier?.takeIf { it.isNotBlank() }?.let { "Supplier: $it" },
        item.model?.takeIf { it.isNotBlank() }?.let { "Model: $it" }
    ).joinToString(" • ")

    return ToOrderItemCardText(
        cabinetLabel = cabinetLabel,
        itemName = item.name,
        quantityLabel = quantityLabel,
        orderDateLabel = item.orderDate?.takeIf { it.isNotBlank() },
        supportingText = supportingText
    )
}

@Composable
private fun ToOrderEditDialog(
    item: SpecialtyItem,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        cabinetNumbers: List<String>,
        supplier: String?,
        model: String?,
        orderDate: String?,
        tracking: String?,
        orderUrl: String?,
        notes: String?,
        quantity: Double?,
        material: String?,
        dimensions: String?
    ) -> Unit
) {
    var name by remember { mutableStateOf(item.name) }
    var cabinetNumbersStr by remember { mutableStateOf(item.cabinetNumbers.joinToString(", ")) }
    var supplier by remember { mutableStateOf(item.supplier.orEmpty()) }
    var model by remember { mutableStateOf(item.model.orEmpty()) }
    var orderDate by remember { mutableStateOf(item.orderDate.orEmpty()) }
    var tracking by remember { mutableStateOf(item.tracking.orEmpty()) }
    var orderUrl by remember { mutableStateOf(item.orderUrl.orEmpty()) }
    var notes by remember { mutableStateOf(item.notes.orEmpty()) }
    var quantityStr by remember { mutableStateOf(item.quantity?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty()) }
    var material by remember { mutableStateOf(item.material.orEmpty()) }
    var dimensions by remember { mutableStateOf(item.dimensions.orEmpty()) }

    SupplyModalFrame(
        title = "Edit To Order Item",
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxHeight(0.85f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Item Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            OutlinedTextField(
                value = cabinetNumbersStr,
                onValueChange = { cabinetNumbersStr = it },
                label = { Text("Cabinet Numbers (comma separated)") },
                placeholder = { Text("e.g. 1, 2, 3") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = quantityStr,
                    onValueChange = { quantityStr = it },
                    label = { Text("Quantity") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = dimensions,
                    onValueChange = { dimensions = it },
                    label = { Text("Dimensions") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = material,
                    onValueChange = { material = it },
                    label = { Text("Material") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = supplier,
                    onValueChange = { supplier = it },
                    label = { Text("Supplier") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model Number") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = orderDate,
                    onValueChange = { orderDate = it },
                    label = { Text("Order Date") },
                    placeholder = { Text("MM-DD or any date") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
            }

            OutlinedTextField(
                value = tracking,
                onValueChange = { tracking = it },
                label = { Text("Tracking Number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            OutlinedTextField(
                value = orderUrl,
                onValueChange = { orderUrl = it },
                label = { Text("Order URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                shape = MaterialTheme.shapes.medium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val cabNums = cabinetNumbersStr.split(",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                        val qty = quantityStr.toDoubleOrNull()
                        onSave(
                            name.trim(),
                            cabNums,
                            supplier.trim().ifBlank { null },
                            model.trim().ifBlank { null },
                            orderDate.trim().ifBlank { null },
                            tracking.trim().ifBlank { null },
                            orderUrl.trim().ifBlank { null },
                            notes.trim().ifBlank { null },
                            qty,
                            material.trim().ifBlank { null },
                            dimensions.trim().ifBlank { null }
                        )
                    },
                    enabled = name.isNotBlank()
                ) { Text("Save") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardCard(
    item: SupplyItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val tier = item.status.let { SUPPLY_STATUS_PRIORITY[it] } ?: 99
    val baseColor = supplyStatusColor(tier)
    val (chipBgColor, chipTextColor) = getSoftStatusColors(item.status, baseColor)

    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFD5DFE5))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip(
                text = item.status,
                backgroundColor = chipBgColor,
                contentColor = chipTextColor
            )
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E2A38)
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            val description = remember(item) {
                val quantity = item.fields["quantity"]?.takeIf { it.isNotBlank() }
                val notes = item.notes?.takeIf { it.isNotBlank() }
                val sku = item.fields["sku"]?.takeIf { it.isNotBlank() }
                listOfNotNull(
                    quantity?.let { "Qty: $it" },
                    sku?.let { "SKU: $sku" },
                    notes?.replace("\n", " ")
                ).joinToString(" • ")
            }
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CategoryBoardColumn(
    category: SupplyCategory,
    items: List<SupplyItem>,
    onAddItem: () -> Unit,
    onOpenItem: (String) -> Unit,
    onLongPress: (SupplyItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val columnBgColor = if (isDark) Color(0xFF1C2B3E) else Color(0xFFEDF2F5)
    val headerColor = if (isDark) Color(0xFF2C5E66) else Color(0xFF356A73)

    androidx.compose.material3.Card(
        modifier = modifier
            .width(300.dp)
            .fillMaxHeight(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = columnBgColor)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerColor)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = category.name.uppercase(),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (items.isEmpty()) {
                    Text(
                        text = "No items in this category.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(8.dp)
                    )
                } else {
                    items.forEach { item ->
                        BoardCard(
                            item = item,
                            onClick = { onOpenItem(item.id) },
                            onLongClick = { onLongPress(item) }
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAddItem() }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = if (isDark) Color(0xFF4FA7C0) else Color(0xFF356A73),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Add another card",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Medium,
                            color = if (isDark) Color(0xFF4FA7C0) else Color(0xFF356A73)
                        )
                    )
                }
            }
        }
    }
}
