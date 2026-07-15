# Supply Kanban Board and Adaptive Detail Dialog Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Redesign the Supply tracker UI in KKCSheetTracker to display categories as side-by-side columns (Kanban Board style) and restyle cards and status badges to match the provided screenshots. The card detail dialog will feature an adaptive two-column layout on wide screens, a tabbed Comments/Actions view, and support local file deletion.

**Architecture:** 
1. Keep the top category tab navigation but consolidate the category page rendering into a single "Board" pager page. 
2. Sync the horizontal scroll position of the board column layout (`LazyRow` with `rememberLazyListState()`) with the selected category tab via a reactive `derivedStateOf` index mapping.
3. Use `BoxWithConstraints` inside `SupplyItemDetailScreen` to display a split two-column layout on tablets (width >= 600.dp) and a single scrollable column on phones.
4. Implement `deleteItem(itemId)` and `deleteComment(itemId, commentId)` in `SupplyRepository` using standard file operations to delete `$itemId.json` or `$commentId.json` respectively, allowing Syncthing to sync deletions naturally without altering data schemas.

**Tech Stack:** Jetpack Compose, Kotlin, Android SDK, GSON.

---

### Task 1: Update SupplyRepository with Delete Operations
Add simple local file deletion methods for items and comments.

**Files:**
- Modify: [SupplyRepository.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt)

**Step 1: Write the implementation**
Open [SupplyRepository.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt) and add:
```kotlin
    fun deleteItem(itemId: String): Boolean {
        val file = File(itemsDir, "$itemId.json")
        val commentDir = File(commentsDir, itemId)
        val deletedItem = if (file.exists()) file.delete() else false
        if (commentDir.exists()) {
            commentDir.deleteRecursively()
        }
        return deletedItem
    }

    fun deleteComment(itemId: String, commentId: String): Boolean {
        val file = File(File(commentsDir, itemId), "$commentId.json")
        return if (file.exists()) file.delete() else false
    }
```

**Step 2: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt
git commit -m "feat(supply): add item and comment deletion to SupplyRepository"
```

---

### Task 2: Update Tab Models and sorting tests
Define the new `Board` tab type and adapt tab list construction.

**Files:**
- Modify: [SupplyTabModels.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyTabModels.kt)
- Modify: [SupplyTabSortingTest.kt](file:///c:/Scripts/KKCSheetTracker/app/src/test/java/com/kkc/sheettracker/ui/supply/SupplyTabSortingTest.kt)

**Step 1: Update SupplyTabModels.kt**
Replace lines 5-10 with:
```kotlin
sealed interface SupplyTabType {
    object Board : SupplyTabType
    object Updates : SupplyTabType
    object NeedsAttention : SupplyTabType
    object ToOrder : SupplyTabType
    data class CategoryTab(val category: SupplyCategory) : SupplyTabType
}
```

**Step 2: Update SupplyTabSortingTest.kt**
Verify that tests compile and assertions match expected behavior. In [SupplyTabSortingTest.kt](file:///c:/Scripts/KKCSheetTracker/app/src/test/java/com/kkc/sheettracker/ui/supply/SupplyTabSortingTest.kt#L17-L29), verify that `buildSupplyTabsList` still correctly produces the lists of tabs. Since `buildSupplyTabsList` output list of category tabs didn't change (only utility tabs are defined next to them), verify with `./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.supply.SupplyTabSortingTest`.

**Step 3: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyTabModels.kt
git commit -m "refactor(supply): add Board to SupplyTabType"
```

---

### Task 3: Implement Board and Card layout UI components
Build the side-by-side Kanban Board, Column (bucket), and Board Card elements.

**Files:**
- Modify: [SupplyDashboardScreen.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt)

**Step 1: Write Kanban Column & Card Composable Functions**
Define them inside [SupplyDashboardScreen.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt) as private or helper functions:
```kotlin
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

    Card(
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

    Card(
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
                        imageVector = Icons.Default.Add,
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
```

**Step 2: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt
git commit -m "feat(supply): implement Kanban column and card components in SupplyDashboardScreen"
```

---

### Task 4: Integrate Board Scroll-Sync in SupplyDashboardScreen
Integrate the board horizontal page rendering, pager, scroll states, and tab selections.

**Files:**
- Modify: [SupplyDashboardScreen.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt)

**Step 1: Set up horizontal list state and pager mapping**
Change pager state logic:
```kotlin
    val boardPageIndex = if (isAdminMode) 3 else 2
    val pagerPageCount = boardPageIndex + 1
    val pagerState = rememberPagerState(pageCount = { pagerPageCount })
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
```

**Step 2: Map Tab click events to scroll Board**
Update Tab selection logic to navigate to board page and scroll:
```kotlin
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
                                    text = { ... }
                                )
                            }
                        }
```

**Step 3: Draw the Board view inside HorizontalPager**
Replace the page content block:
```kotlin
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { page ->
                    val tabItem = supplyTabs.getOrNull(page) // Maps to Updates/NeedsAttention/ToOrder or Board (page == boardPageIndex)
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
                    } else if (tabItem != null) {
                        when (val type = tabItem.type) {
                            SupplyTabType.Updates -> UpdatesPage(...)
                            SupplyTabType.NeedsAttention -> NeedsAttentionPage(...)
                            SupplyTabType.ToOrder -> ToOrderPage(...)
                            else -> {}
                        }
                    }
                }
```

**Step 4: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt
git commit -m "feat(supply): connect Board horizontal scroll state to category navigation tabs"
```

---

### Task 5: Redesign Card Detail dialog with adaptive layout
Restructure the Supply card detail view to be 2-column on landscape tablets and show comments/actions tabs.

**Files:**
- Modify: [SupplyItemDetailScreen.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemDetailScreen.kt)

**Step 1: Add helper composables and state variables**
Define a custom `SideActionButton` helper:
```kotlin
@Composable
private fun SideActionButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checked: Boolean = false
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (checked) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        if (isDark) Color(0xFF243547) else Color(0xFFEAEEF2)
    }
    val textColor = if (checked) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        if (isDark) Color(0xFFE2EDF7) else Color(0xFF1E2A38)
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(4.dp),
        color = bgColor,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
```

**Step 2: Restructure Detail UI**
Use `BoxWithConstraints` to choose between layouts:
```kotlin
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp
        var showDeleteConfirmDialog by remember { mutableStateOf(false) }

        when {
            isLoading -> { ... }
            errorMessage != null -> { ... }
            currentItem != null -> {
                if (isWide) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // LEFT COLUMN (68% width)
                        Column(
                            modifier = Modifier
                                .weight(0.68f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = currentItem.name,
                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val tier = SUPPLY_STATUS_PRIORITY[currentItem.status] ?: 99
                                val baseColor = supplyStatusColor(tier)
                                val (chipBgColor, chipTextColor) = getSoftStatusColors(currentItem.status, baseColor)
                                StatusChip(
                                    text = currentItem.status,
                                    backgroundColor = chipBgColor,
                                    contentColor = chipTextColor,
                                    modifier = Modifier
                                        .border(
                                            BorderStroke(0.5.dp, chipTextColor.copy(alpha = 0.25f)),
                                            shape = CircleShape
                                        )
                                        .clickable { showStatusSheet = true }
                                )
                                IconButton(onClick = { showStatusSheet = true }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Add, "Change Status", modifier = Modifier.size(16.dp))
                                }
                                Text("in category ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    categoryMap[currentItem.categoryId]?.name ?: "unknown",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (!currentItem.notes.isNullOrBlank()) {
                                DetailSection(title = "Notes", accent = supplyAccent(currentItem.status)) {
                                    MarkdownText(currentItem.notes, style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            // Schema Fields list
                            // ... DF rendering ...

                            ItemBarcodeSection(item = currentItem, barcodeStore = barcodeStore, scope = coroutineScope, onRefresh = { loadData() })

                            // Attachments Photo list
                            // ... Photos rendering ...

                            HorizontalDivider()

                            // Comments and Actions Tabbed View
                            var commentTab by remember { mutableStateOf(0) }
                            TabRow(selectedTabIndex = commentTab, containerColor = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                                Tab(selected = commentTab == 0, onClick = { commentTab = 0 }) {
                                    Text("Comments", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
                                }
                                Tab(selected = commentTab == 1, onClick = { commentTab = 1 }) {
                                    Text("Actions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
                                }
                            }
                            if (commentTab == 0) {
                                // Comments form + CommentCard list
                                // ... Comments list ...
                            } else {
                                Text("No recent history.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                            }
                        }

                        // RIGHT COLUMN (32% width)
                        Column(
                            modifier = Modifier
                                .weight(0.32f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("LIST", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isSystemInDarkTheme()) Color(0xFF243547) else Color(0xFFEAEEF2)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.List, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                    Text(categoryMap[currentItem.categoryId]?.name ?: "None", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            }

                            Text("ADD TO CARD", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SideActionButton(onClick = { showStatusSheet = true }, icon = Icons.Default.Bookmark, text = "Labels")
                                SideActionButton(onClick = { galleryLauncher.launch("image/*") }, icon = Icons.Default.AttachFile, text = "Attachment")
                                SideActionButton(onClick = { onEdit() }, icon = Icons.Default.Edit, text = "Custom Field")
                                SideActionButton(onClick = {}, icon = Icons.Default.Person, text = "Members", enabled = false)
                            }

                            Text("ACTIONS", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SideActionButton(onClick = {}, icon = Icons.Default.Flag, text = "Join", enabled = false)
                                val isSubscribed = subscriptionData.subscribedItemIds.contains(currentItem.id)
                                SideActionButton(
                                    onClick = { coroutineScope.launch { subscriptionManager.toggleItemSubscription(currentItem.id) } },
                                    icon = if (isSubscribed) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                                    text = if (isSubscribed) "Unsubscribe" else "Board subscribed",
                                    checked = isSubscribed
                                )
                                SideActionButton(onClick = {}, icon = Icons.Default.Archive, text = "Archive", enabled = false)
                                SideActionButton(onClick = { showDeleteConfirmDialog = true }, icon = Icons.Default.Delete, text = "Delete")
                            }
                        }
                    }
                } else {
                    // Fall back to original single column LazyColumn layout
                    // ... original LazyColumn logic ...
                }
            }
        }

        // Delete Confirm Dialog
        if (showDeleteConfirmDialog && currentItem != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("Delete Item?") },
                text = { Text("Are you sure you want to delete ${currentItem.name}? This will remove the item permanently across all devices.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirmDialog = false
                            coroutineScope.launch {
                                withContext(Dispatchers.IO) {
                                    repository.deleteItem(currentItem.id)
                                }
                                onBack() // close detail view
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("Cancel") } }
            )
        }
    }
```

**Step 3: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemDetailScreen.kt
git commit -m "feat(supply): implement adaptive split layout and delete action in card detail view"
```

---

### Task 6: Add delete button to comments
Support deleting comments from the CommentCard directly.

**Files:**
- Modify: [SupplyItemDetailScreen.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemDetailScreen.kt)

**Step 1: Expose a Delete button in CommentCard**
Modify `CommentCard` to take a `onDelete` lambda:
```kotlin
@Composable
private fun CommentCard(
    comment: SupplyComment,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    ...
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(comment.author, ...)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(formattedTime, ...)
                        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Delete, "Delete Comment", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                        }
                    }
                }
    ...
}
```
And wire it up inside the comments list row:
```kotlin
CommentCard(
    comment = comment,
    onDelete = {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                repository.deleteComment(currentItem.id, comment.id)
            }
            comments = withContext(Dispatchers.IO) {
                repository.getComments(currentItem.id)
            }
        }
    },
    modifier = Modifier.fillMaxWidth()
)
```

**Step 2: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemDetailScreen.kt
git commit -m "feat(supply): implement comment deletion inside card detail dialog"
```

---

## Verification Plan

### Automated Tests
Run unit tests to verify compile-safety and test assertions:
- Run: `.\gradlew.bat testDebugUnitTest -v`
Expected: Compile success and 100% test passes.

### Manual Verification
1. Open the app on tablet and tap the "Supply" navigation tab.
2. Verify all categories are rendered side-by-side as a board, scrollable horizontally.
3. Tap on category tabs at the top (e.g. "EDGE BANDER") and verify the board smoothly scrolls to center the selected category.
4. Tap any card (e.g. "1/2\" prefinished ply") to open the detail dialog.
5. In landscape/wide screens, verify it is displayed in the two-column layout.
6. Verify clicking "Delete" button in the right action panel prompts a confirmation alert and deletes the item.
7. Verify adding and deleting comments.
