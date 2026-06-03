package com.kkc.sheettracker.ui.supply

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kkc.sheettracker.data.SupplyRepository
import com.kkc.sheettracker.data.SupplySubscriptionManager
import com.kkc.sheettracker.data.SupplyNotificationItem
import com.kkc.sheettracker.data.SupplyChange
import com.kkc.sheettracker.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val coroutineScope = rememberCoroutineScope()

    var categories by remember { mutableStateOf<List<SupplyCategory>>(emptyList()) }
    var items by remember { mutableStateOf<List<SupplyItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val subscriptionData by subscriptionManager.subscriptionData.collectAsState()
    var notifications by remember { mutableStateOf<List<SupplyNotificationItem>>(emptyList()) }

    suspend fun reloadUpdates() {
        val updates = withContext(Dispatchers.IO) {
            subscriptionManager.scanForUpdates()
        }
        notifications = updates
    }

    LaunchedEffect(items, subscriptionData) {
        reloadUpdates()
    }

    // Add category dialog
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Status change bottom sheet state
    var statusSheetItem by remember { mutableStateOf<SupplyItem?>(null) }
    val statusSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    suspend fun loadData() {
        isLoading = true
        errorMessage = null
        try {
            val cats = withContext(Dispatchers.IO) { repository.getCategories() }
            val its = withContext(Dispatchers.IO) { repository.getItems() }
            categories = cats.sortedBy { it.position }
            items = its
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load supply data"
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(basePath) { loadData() }

    val pagerState = rememberPagerState(pageCount = { (categories.size + 1).coerceAtLeast(1) })

    val currentCategoryId = if (!isLoading && searchQuery.isBlank() && categories.isNotEmpty() && pagerState.currentPage > 0) {
        categories.getOrNull(pagerState.currentPage - 1)?.id
    } else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Supply Inventory") },
                actions = {
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
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
                                    val label = if (isSubscribed) "Unsubscribe from Category" else "Subscribe to Category"
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            showOverflowMenu = false
                                            coroutineScope.launch {
                                                subscriptionManager.toggleCategorySubscription(currentCat.id)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            currentCategoryId?.let { catId ->
                FloatingActionButton(onClick = { navController.navigate("supply/new/$catId") }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add item")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search items...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                errorMessage != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Error: $errorMessage",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Button(onClick = { coroutineScope.launch { loadData() } }) {
                                Text("Retry")
                            }
                        }
                    }
                }
                searchQuery.isNotBlank() -> {
                    // Flat filtered list
                    val filtered = items.filter {
                        it.name.contains(searchQuery, ignoreCase = true)
                    }.sortedBy { SUPPLY_STATUS_PRIORITY[it.status] ?: 99 }

                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No items match \"$searchQuery\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(filtered, key = { it.id }) { item ->
                                SupplyItemCard(
                                    item = item,
                                    onCardClick = { navController.navigate("supply/item/${item.id}") },
                                    onStatusLongPress = { statusSheetItem = item }
                                )
                            }
                        }
                    }
                }
                else -> {
                    // Tab row
                    val notificationCountState by subscriptionManager.notificationCount.collectAsState()
                    ScrollableTabRow(
                        selectedTabIndex = pagerState.currentPage,
                        edgePadding = 12.dp
                    ) {
                        Tab(
                            selected = pagerState.currentPage == 0,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(0)
                                }
                            },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("Updates", maxLines = 1)
                                    if (notificationCountState > 0) {
                                        Badge {
                                            Text(notificationCountState.toString())
                                        }
                                    }
                                }
                            }
                        )
                        categories.forEachIndexed { index, category ->
                            val isSubscribed = subscriptionData.subscribedCategoryIds.contains(category.id)
                            Tab(
                                selected = pagerState.currentPage == index + 1,
                                onClick = {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(index + 1)
                                    }
                                },
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(category.name, maxLines = 1)
                                        if (isSubscribed) {
                                            Icon(
                                                imageVector = Icons.Filled.Notifications,
                                                contentDescription = "Subscribed",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        if (page == 0) {
                            SupplyUpdatesPage(
                                notifications = notifications,
                                categories = categories,
                                navController = navController,
                                subscriptionManager = subscriptionManager,
                                reloadUpdates = { coroutineScope.launch { reloadUpdates() } }
                            )
                        } else {
                            val category = categories.getOrNull(page - 1)
                            if (category == null) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("No category")
                                }
                            } else {
                                val categoryItems = items
                                    .filter { it.categoryId == category.id }
                                    .sortedWith(
                                        compareBy(
                                            { SUPPLY_STATUS_PRIORITY[it.status] ?: 99 },
                                            { it.name }
                                        )
                                    )

                                if (categoryItems.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "No items in ${category.name}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(vertical = 8.dp)
                                    ) {
                                        items(categoryItems, key = { it.id }) { item ->
                                            SupplyItemCard(
                                                item = item,
                                                onCardClick = { navController.navigate("supply/item/${item.id}") },
                                                onStatusLongPress = { statusSheetItem = item }
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
    }

    // Add category dialog
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
                TextButton(
                    onClick = {
                        val name = newCategoryName.trim()
                        if (name.isNotBlank()) {
                            coroutineScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { repository.createCategory(name) }
                                }
                                result.onFailure { error ->
                                    Toast.makeText(context, "Failed to create category: ${error.message}", Toast.LENGTH_LONG).show()
                                }
                                showAddCategoryDialog = false
                                loadData()
                            }
                        }
                    },
                    enabled = newCategoryName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategoryDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Status change bottom sheet
    statusSheetItem?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { statusSheetItem = null },
            sheetState = statusSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "Change Status: ${item.name}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                HorizontalDivider()
                ALL_SUPPLY_STATUSES.forEach { status ->
                    val tier = SUPPLY_STATUS_PRIORITY[status] ?: 99
                    val tintColor = supplyStatusColor(tier)
                    NavigationDrawerItem(
                        label = { Text(status) },
                        selected = item.status == status,
                        onClick = {
                            val targetItem = item
                            coroutineScope.launch {
                                statusSheetState.hide()
                                statusSheetItem = null
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        repository.setStatus(
                                            targetItem.id, status,
                                            employeeName.ifBlank { "Floor" }, tabletId
                                        )
                                    }
                                }
                                result.onFailure { error ->
                                    Toast.makeText(context, "Failed to change status: ${error.message}", Toast.LENGTH_LONG).show()
                                }
                                // Re-read the item from disk so the resolved status is current
                                val updated = withContext(Dispatchers.IO) {
                                    runCatching { repository.getItem(targetItem.id) }.getOrNull()
                                }
                                if (updated != null) {
                                    items = items.map { if (it.id == updated.id) updated else it }
                                }
                            }
                        },
                        icon = {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(tintColor, CircleShape)
                            )
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SupplyItemCard(
    item: SupplyItem,
    onCardClick: () -> Unit,
    onStatusLongPress: () -> Unit
) {
    val tier = SUPPLY_STATUS_PRIORITY[item.status] ?: 99
    val statusColor = supplyStatusColor(tier)
    val sku = item.fields["sku"] ?: item.customFields["sku"] ?: ""
    val qty = item.fields["quantity"] ?: item.customFields["quantity"] ?: ""

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onCardClick,
                onLongClick = onStatusLongPress
            ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status circle
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(statusColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.status.take(2),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = buildList {
                    if (sku.isNotBlank()) add("SKU: $sku")
                    if (qty.isNotBlank()) add("Qty: $qty")
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta.joinToString("  |  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

internal fun supplyStatusColor(tier: Int): Color {
    return when (tier) {
        1, 2 -> Color(0xFFD32F2F) // red
        3 -> Color(0xFFEF6C00)    // orange
        4 -> Color(0xFF1565C0)    // blue
        else -> Color(0xFF2E7D32) // green
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupplyUpdatesPage(
    notifications: List<SupplyNotificationItem>,
    categories: List<SupplyCategory>,
    navController: NavHostController,
    subscriptionManager: SupplySubscriptionManager,
    reloadUpdates: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    if (notifications.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No updates or subscribed items. Subscribe to items or categories to receive updates here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(notifications, key = { it.item.id }) { notification ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                            coroutineScope.launch {
                                subscriptionManager.dismissNotification(notification.item.id)
                                reloadUpdates()
                            }
                            true
                        } else {
                            false
                        }
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    backgroundContent = {
                        val color = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                            Color(0xFFFFEBEE) // light red
                        } else {
                            Color(0xFFEEEEEE) // light gray
                        }
                        val alignment = when (dismissState.dismissDirection) {
                            SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                            SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                            else -> Alignment.CenterEnd
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(color, shape = MaterialTheme.shapes.medium)
                                .padding(horizontal = 16.dp),
                            contentAlignment = alignment
                        ) {
                            Text("Dismiss", style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    val tier = SUPPLY_STATUS_PRIORITY[notification.item.status] ?: 99
                    val statusColor = supplyStatusColor(tier)
                    val categoryName = categories.find { it.id == notification.item.categoryId }?.name ?: "Unknown Category"

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate("supply/item/${notification.item.id}") },
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Status dot
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(statusColor, CircleShape)
                                )
                                // Item name and category name
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = notification.item.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = categoryName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Bulleted list detailing changes
                            notification.changes.forEach { change ->
                                val text = when (change) {
                                    is SupplyChange.NewSubscriptionOrItem -> "New subscription or item added"
                                    is SupplyChange.DetailsUpdated -> "Details updated"
                                    is SupplyChange.StatusChanged -> "Status changed to ${change.status}"
                                    is SupplyChange.NewComments -> "${change.count} new comment(s)"
                                    is SupplyChange.NewAttachments -> "${change.count} new attachment(s)"
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
                                ) {
                                    Text("•", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = text,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Dismiss button at bottom right
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                TextButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            subscriptionManager.dismissNotification(notification.item.id)
                                            reloadUpdates()
                                        }
                                    }
                                ) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

