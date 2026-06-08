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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.navigation.NavHostController
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
    navController: NavHostController,
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

    val subscriptionData by subscriptionManager.subscriptionData.collectAsState()
    val notificationCount by subscriptionManager.notificationCount.collectAsState()
    var notifications by remember { mutableStateOf<List<SupplyNotificationItem>>(emptyList()) }
    val pagerState = rememberPagerState(pageCount = { (categories.size + 1).coerceAtLeast(1) })
    val statusSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    suspend fun loadData() {
        isLoading = true
        errorMessage = null
        try {
            val cats = withContext(Dispatchers.IO) { repository.getCategories() }.sortedBy { it.position }
            val its = withContext(Dispatchers.IO) { repository.getItems() }
            categories = cats
            items = its
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load supply data"
        } finally {
            isLoading = false
        }
    }

    suspend fun reloadUpdates() {
        notifications = withContext(Dispatchers.IO) { subscriptionManager.scanForUpdates() }
    }

    LaunchedEffect(basePath) { loadData() }
    LaunchedEffect(items, subscriptionData) { reloadUpdates() }

    val currentCategoryId = if (!isLoading && searchQuery.isBlank() && categories.isNotEmpty() && pagerState.currentPage > 0) {
        categories.getOrNull(pagerState.currentPage - 1)?.id
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
                    if (pagerState.currentPage > 0) {
                        val currentCat = categories.getOrNull(pagerState.currentPage - 1)
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
                FloatingActionButton(onClick = { navController.navigate("supply/new/$catId") }) {
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
                                items = filtered.map(::toInventoryItemModel),
                                summary = if (filtered.isEmpty()) null else "${filtered.size} matching items",
                                emptyMessage = "No items match \"$searchQuery\""
                            )
                        ),
                        onItemClick = { item ->
                            if (item is DashboardInventoryItemModel) {
                                navController.navigate("supply/item/${item.id}")
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
                        selectedTabIndex = pagerState.currentPage,
                        edgePadding = 12.dp
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
                        categories.forEachIndexed { index, category ->
                            val isSubscribed = subscriptionData.subscribedCategoryIds.contains(category.id)
                            Tab(
                                selected = pagerState.currentPage == index + 1,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index + 1) } },
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
                    if (page == 0) {
                        UpdatesPage(
                            notifications = notifications,
                            categories = categories,
                            navController = navController,
                            subscriptionManager = subscriptionManager,
                            reloadUpdates = { scope.launch { reloadUpdates() } },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val category = categories.getOrNull(page - 1)
                        if (category == null) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No category")
                            }
                        } else {
                            val categoryItems = items.filter { it.categoryId == category.id }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                DashboardWidgetRenderer(
                                    widgets = buildSupplyCategoryWidgets(
                                        category = category,
                                        items = categoryItems,
                                        isSubscribed = subscriptionData.subscribedCategoryIds.contains(category.id),
                                        notificationCount = notifications.count { it.item.categoryId == category.id }
                                    ),
                                    onItemClick = { item ->
                                        if (item is DashboardInventoryItemModel) {
                                            navController.navigate("supply/item/${item.id}")
                                        }
                                    },
                                    onItemLongPress = { item ->
                                        statusSheetItem = items.firstOrNull { it.id == item.id }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = { Text("New Category") },
            text = {
                OutlinedTextField(
                    value = newCategoryName,
                    onValueChange = { newCategoryName = it },
                    label = { Text("Category name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newCategoryName.trim()
                    if (name.isNotBlank()) {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { repository.createCategory(name) } }
                            result.onFailure { Toast.makeText(context, "Failed to create category: ${it.message}", Toast.LENGTH_LONG).show() }
                            showAddCategoryDialog = false
                            loadData()
                        }
                    }
                }, enabled = newCategoryName.isNotBlank()) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showAddCategoryDialog = false }) { Text("Cancel") } }
        )
    }

    statusSheetItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { statusSheetItem = null }, sheetState = statusSheetState) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                Text("Change Status: ${item.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                HorizontalDivider()
                ALL_SUPPLY_STATUSES.forEach { status ->
                    NavigationDrawerItem(
                        label = { Text(status) },
                        selected = item.status == status,
                        onClick = {
                            scope.launch {
                                statusSheetState.hide()
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
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun UpdatesPage(
    notifications: List<SupplyNotificationItem>,
    categories: List<SupplyCategory>,
    navController: NavHostController,
    subscriptionManager: SupplySubscriptionManager,
    reloadUpdates: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 16.dp),
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
                        .clickable { navController.navigate("supply/item/${notification.item.id}") }
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

private fun toInventoryItemModel(item: SupplyItem): DashboardInventoryItemModel {
    val quantity = item.fields["quantity"]?.takeIf { it.isNotBlank() }
    val supportingText = buildList {
        if (quantity != null) add("Qty $quantity")
        if (!item.notes.isNullOrBlank()) add(item.notes)
    }.joinToString(" • ").ifBlank { null }
    return DashboardInventoryItemModel(
        id = item.id,
        title = item.name,
        subtitle = item.status,
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

private fun supplyAccent(status: String): DashboardAccent = when (status.uppercase()) {
    "OUT", "ASAP", "MALFUNCTIONING", "NEED" -> DashboardAccent.DANGER
    "LOW" -> DashboardAccent.WARNING
    "ORDERED", "IN PROCESS", "ACKNOWLEDGED" -> DashboardAccent.INFO
    "IN STOCK", "COMPLETE", "RECEIVED" -> DashboardAccent.SUCCESS
    else -> DashboardAccent.NEUTRAL
}
