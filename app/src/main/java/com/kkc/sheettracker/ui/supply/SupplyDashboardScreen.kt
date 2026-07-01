package com.kkc.sheettracker.ui.supply

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.ExperimentalMaterial3Api
import com.kkc.sheettracker.ui.dashboard.DashboardShell
import com.kkc.sheettracker.ui.dashboard.DashboardAccent
import com.kkc.sheettracker.ui.dashboard.DashboardSectionHeader
import com.kkc.sheettracker.ui.dashboard.DashboardSurfaceCard
import com.kkc.sheettracker.ui.dashboard.DashboardWidgetRenderer
import com.kkc.sheettracker.ui.dashboard.DashboardInventoryItemModel
import com.kkc.sheettracker.ui.dashboard.DashboardWidgetModel
import com.kkc.sheettracker.ui.dashboard.buildSupplyCategoryWidgets

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

    val subscriptionData by subscriptionManager.subscriptionData.collectAsState()
    val notificationCount by subscriptionManager.notificationCount.collectAsState()
    var notifications by remember { mutableStateOf<List<SupplyNotificationItem>>(emptyList()) }
    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    val tabCount = (categories.size + 2).coerceAtLeast(2)
    val pagerState = rememberPagerState(pageCount = { tabCount })

    // Categories can reload smaller/empty while the pager still points at a former category page.
    // Snap back into range so ScrollableTabRow never reads a tab index past the tab list.
    LaunchedEffect(tabCount) {
        if (pagerState.currentPage > tabCount - 1) {
            pagerState.scrollToPage(tabCount - 1)
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
        editChromeState = null
        activeModal = SupplyDashboardModal.Detail(itemId)
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

    val currentCategoryId = if (!isLoading && searchQuery.isBlank() && categories.isNotEmpty() && pagerState.currentPage > 1) {
        categories.getOrNull(pagerState.currentPage - 2)?.id
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
                    if (pagerState.currentPage > 1) {
                        val currentCat = categories.getOrNull(pagerState.currentPage - 2)
                        if (currentCat != null) {
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
                    ScrollableTabRow(
                        selectedTabIndex = pagerState.currentPage.coerceIn(0, tabCount - 1),
                        edgePadding = 12.dp,
                        indicator = { tabPositions ->
                            // Material3's default indicator indexes tabPositions[selectedTabIndex]
                            // with no bounds check. The subcomposed tab count can briefly lag one
                            // frame behind tabCount when categories reload, so guard here too.
                            val safeIndex = pagerState.currentPage.coerceIn(0, tabCount - 1)
                            tabPositions.getOrNull(safeIndex)?.let { position ->
                                TabRowDefaults.SecondaryIndicator(
                                    Modifier.tabIndicatorOffset(position)
                                )
                            }
                        }
                    ) {
                        Tab(
                            selected = pagerState.currentPage == 0,
                            onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("Updates")
                                    if (notificationCount > 0) {
                                        Badge { Text(notificationCount.toString()) }
                                    }
                                }
                            }
                        )
                        val attentionCount = items.count { (SUPPLY_STATUS_PRIORITY[it.status] ?: 99) < 5 }
                        Tab(
                            selected = pagerState.currentPage == 1,
                            onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("Needs Attention")
                                    if (attentionCount > 0) {
                                        Badge { Text(attentionCount.toString()) }
                                    }
                                }
                            }
                        )
                        categories.forEachIndexed { index, category ->
                            val isSubscribed = subscriptionData.subscribedCategoryIds.contains(category.id)
                            Tab(
                                selected = pagerState.currentPage == index + 2,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index + 2) } },
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(category.name, maxLines = 1)
                                        if (isSubscribed) {
                                            Icon(
                                                Icons.Filled.Notifications,
                                                contentDescription = "Subscribed",
                                                modifier = Modifier.size(12.dp)
                                            )
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
                    when (page) {
                    0 -> UpdatesPage(
                        notifications = notifications,
                        categories = categories,
                        subscriptionManager = subscriptionManager,
                        onOpenItem = ::openDetailModal,
                        reloadUpdates = { scope.launch { reloadUpdates() } },
                        modifier = Modifier.fillMaxSize()
                    )
                    1 -> NeedsAttentionPage(
                        items = items,
                        categories = categories,
                        onOpenItem = ::openDetailModal,
                        onLongPress = { item -> statusSheetItem = items.firstOrNull { it.id == item.id } },
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> {
                        val category = categories.getOrNull(page - 2)
                        if (category == null) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No category")
                            }
                        } else {
                            val categoryItems = items.filter { it.categoryId == category.id }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = 160.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CategoryAddHeader(
                                    category = category,
                                    onAddItem = { openNewItemModal(category.id) }
                                )
                                DashboardWidgetRenderer(
                                    widgets = buildSupplyCategoryWidgets(
                                        category = category,
                                        items = categoryItems,
                                        isSubscribed = subscriptionData.subscribedCategoryIds.contains(category.id),
                                        notificationCount = notifications.count { it.item.categoryId == category.id }
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
                                if (categoryItems.isEmpty()) {
                                    EmptyCategoryAddCard(
                                        category = category,
                                        onAddItem = { openNewItemModal(category.id) }
                                    )
                                }
                            }
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
            val itemTitle = items.firstOrNull { it.id == modal.itemId }?.name ?: "Supply Item"
            SupplyModalFrame(
                title = itemTitle,
                onDismiss = { dismissSupplyModal() },
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
                    subscriptionManager = subscriptionManager
                )
            }
        }
        is SupplyDashboardModal.EditItem -> {
            SupplyModalFrame(
                title = "Edit Item",
                onDismiss = { dismissSupplyModal() },
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
        null -> Unit
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
                            val result = withContext(Dispatchers.IO) {
                                runCatching { repository.setStatus(item.id, status, employeeName.ifBlank { "Floor" }, tabletId) }
                            }
                            result.onFailure { Toast.makeText(context, "Failed to change status: ${it.message}", Toast.LENGTH_LONG).show() }
                            items = withContext(Dispatchers.IO) { runCatching { repository.getItems() }.getOrDefault(items) }
                        }
                    },
                    icon = {
                        Box(modifier = Modifier.size(12.dp).background(supplyStatusColor(SUPPLY_STATUS_PRIORITY[status] ?: 99), CircleShape))
                    }
                )
            },
            onDismiss = { statusSheetItem = null }
        )
    }
}

private sealed interface SupplyDashboardModal {
    data class Detail(val itemId: String) : SupplyDashboardModal
    data class EditItem(val itemId: String) : SupplyDashboardModal
    data class NewItem(val categoryId: String) : SupplyDashboardModal
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
        accent = supplyAccent(item.status)
    )
}

fun supplyStatusColor(tier: Int): Color = when (tier) {
    1, 2 -> Color(0xFFD32F2F)
    3 -> Color(0xFFEF6C00)
    4 -> Color(0xFF1565C0)
    else -> Color(0xFF2E7D32)
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
