package com.kkc.sheettracker.ui.supply

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kkc.sheettracker.data.SupplyRepository
import com.kkc.sheettracker.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SupplyDashboardScreen(
    serverUrl: String,
    employeeName: String,
    navController: NavHostController
) {
    val repository = remember(serverUrl) { SupplyRepository(serverUrl) }
    val coroutineScope = rememberCoroutineScope()

    var categories by remember { mutableStateOf<List<SupplyCategory>>(emptyList()) }
    var items by remember { mutableStateOf<List<SupplyItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Status change bottom sheet state
    var statusSheetItem by remember { mutableStateOf<SupplyItem?>(null) }
    val statusSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // New item creation dialog state
    var showNewItemDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    var newItemCategoryId by remember { mutableStateOf("") }
    var newItemCategoryExpanded by remember { mutableStateOf(false) }

    fun loadData() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val cats = withContext(Dispatchers.IO) { repository.getCategories() }
                val its = withContext(Dispatchers.IO) { repository.getItems() }
                categories = cats.sortedBy { it.position }
                items = its
                if (newItemCategoryId.isBlank() && cats.isNotEmpty()) {
                    newItemCategoryId = cats.first().id
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load supply data"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(serverUrl) { loadData() }

    val pagerState = rememberPagerState(pageCount = { categories.size.coerceAtLeast(1) })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Supply Inventory") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = { showNewItemDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "New Item")
                    }
                }
            )
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
                            Button(onClick = { loadData() }) {
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
                categories.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No categories found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    // Tab row
                    ScrollableTabRow(
                        selectedTabIndex = pagerState.currentPage,
                        edgePadding = 12.dp
                    ) {
                        categories.forEachIndexed { index, category ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                },
                                text = { Text(category.name, maxLines = 1) }
                            )
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        val category = categories.getOrNull(page)
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
                                try {
                                    val updated = withContext(Dispatchers.IO) {
                                        repository.patchStatus(targetItem.id, status)
                                    }
                                    items = items.map { if (it.id == updated.id) updated else it }
                                } catch (_: Exception) {
                                    // silently ignore — user can retry from detail
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

    // New item creation dialog
    if (showNewItemDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewItemDialog = false
                newItemName = ""
            },
            title = { Text("New Supply Item") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = newItemCategoryExpanded,
                        onExpandedChange = { newItemCategoryExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = categories.find { it.id == newItemCategoryId }?.name ?: "Select category",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Category") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = newItemCategoryExpanded) },
                            shape = MaterialTheme.shapes.medium
                        )
                        ExposedDropdownMenu(
                            expanded = newItemCategoryExpanded,
                            onDismissRequest = { newItemCategoryExpanded = false }
                        ) {
                            categories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.name) },
                                    onClick = {
                                        newItemCategoryId = cat.id
                                        newItemCategoryExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = newItemName,
                        onValueChange = { newItemName = it },
                        label = { Text("Item Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val catId = newItemCategoryId
                        val name = newItemName.trim()
                        if (catId.isNotBlank() && name.isNotBlank()) {
                            coroutineScope.launch {
                                try {
                                    val created = withContext(Dispatchers.IO) {
                                        repository.createItem(catId, name)
                                    }
                                    items = items + created
                                    showNewItemDialog = false
                                    newItemName = ""
                                    navController.navigate("supply/item/${created.id}")
                                } catch (e: Exception) {
                                    errorMessage = e.message
                                }
                            }
                        }
                    },
                    enabled = newItemName.isNotBlank() && newItemCategoryId.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNewItemDialog = false
                    newItemName = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

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
        onClick = onCardClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status circle — long press to change
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(statusColor, CircleShape)
                    .pointerInput(item.id) {
                        detectTapGestures(onLongPress = { onStatusLongPress() })
                    },
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
