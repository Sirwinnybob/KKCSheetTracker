# Supply Tab Reordering Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a "Reorder Tabs" option to the Supply Dashboard overflow menu that opens a drag-and-drop modal, letting users rearrange updates, needs attention, to-order, and category tabs, with top matching far left and bottom matching far right.

**Architecture:** Persist the order in SharedPreferences as a comma-separated string list of tab IDs. Dynamically resolve the supply tabs list on load, filtering out inactive or deleted category/admin pages, and appending any new categories to the end. Swaps update the preferences instantly.

**Tech Stack:** Kotlin, Jetpack Compose, `sh.calvin.reorderable` drag-and-drop library.

---

### Task 1: Update UiPreferencesStore to support supply tab order persistence

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/UiPreferencesStore.kt`

**Step 1: Write minimal implementation**
We will append the getter and setter for the tab order in `UiPreferencesStore`.

```kotlin
    fun getSupplyTabOrder(): List<String> {
        val raw = prefs.getString("supply_tab_order", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",")
    }

    fun setSupplyTabOrder(order: List<String>) {
        prefs.edit().putString("supply_tab_order", order.joinToString(",")).apply()
    }
```

**Step 2: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/data/UiPreferencesStore.kt
git commit -m "feat: add supply tab order persistence to UiPreferencesStore"
```

---

### Task 2: Create unit tests for buildSupplyTabsList

**Files:**
- Create: `app/src/test/java/com/kkc/sheettracker/ui/supply/SupplyTabSortingTest.kt`

**Step 1: Write the failing tests**
Write tests for categories list sorting, admin mode adjustments, filtering deleted categories, and appending new categories.

```kotlin
package com.kkc.sheettracker.ui.supply

import com.kkc.sheettracker.data.models.SupplyCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class SupplyTabSortingTest {
    @Test
    fun testBuildSupplyTabsListDefaultOrder() {
        val categories = listOf(
            SupplyCategory("cat1", "Category 1", 0),
            SupplyCategory("cat2", "Category 2", 1)
        )
        // Default order without saved preferences
        val result = buildSupplyTabsList(categories, isAdminMode = true, savedTabOrder = emptyList())
        val ids = result.map { it.id }
        assertEquals(listOf("updates", "needs_attention", "to_order", "cat1", "cat2"), ids)
    }

    @Test
    fun testBuildSupplyTabsListReordered() {
        val categories = listOf(
            SupplyCategory("cat1", "Category 1", 0),
            SupplyCategory("cat2", "Category 2", 1)
        )
        val savedOrder = listOf("cat2", "updates", "to_order", "cat1", "needs_attention")
        val result = buildSupplyTabsList(categories, isAdminMode = true, savedTabOrder = savedOrder)
        val ids = result.map { it.id }
        assertEquals(savedOrder, ids)
    }

    @Test
    fun testBuildSupplyTabsListWithNewAndDeletedCategories() {
        val categories = listOf(
            SupplyCategory("cat1", "Category 1", 0),
            SupplyCategory("cat3", "Category 3", 2) // cat3 is new
        )
        // cat2 was deleted, cat3 is not in savedOrder
        val savedOrder = listOf("cat2", "updates", "needs_attention", "cat1")
        val result = buildSupplyTabsList(categories, isAdminMode = false, savedTabOrder = savedOrder)
        val ids = result.map { it.id }
        // cat2 should be filtered out because it is not available (not in categories list), and cat3 should be appended at the end
        assertEquals(listOf("updates", "needs_attention", "cat1", "cat3"), ids)
    }
}
```

**Step 2: Run test to verify it fails**
Run: `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.supply.SupplyTabSortingTest`
Expected: FAIL due to unresolved references to `buildSupplyTabsList` and `SupplyTabItem`.

---

### Task 3: Implement buildSupplyTabsList and related structures to make tests pass

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyTabModels.kt`

**Step 1: Write minimal implementation**
We will implement the domain logic for tab sorting in a separate model file so it can be cleanly unit-tested without needing Compose UI context.

```kotlin
package com.kkc.sheettracker.ui.supply

import com.kkc.sheettracker.data.models.SupplyCategory

sealed interface SupplyTabType {
    object Updates : SupplyTabType
    object NeedsAttention : SupplyTabType
    object ToOrder : SupplyTabType
    data class CategoryTab(val category: SupplyCategory) : SupplyTabType
}

data class SupplyTabItem(
    val id: String,
    val type: SupplyTabType,
    val name: String
)

fun buildSupplyTabsList(
    categories: List<SupplyCategory>,
    isAdminMode: Boolean,
    savedTabOrder: List<String>
): List<SupplyTabItem> {
    val availableTabs = mutableListOf<SupplyTabItem>()
    availableTabs.add(SupplyTabItem("updates", SupplyTabType.Updates, "Updates"))
    availableTabs.add(SupplyTabItem("needs_attention", SupplyTabType.NeedsAttention, "Needs Attention"))
    if (isAdminMode) {
        availableTabs.add(SupplyTabItem("to_order", SupplyTabType.ToOrder, "To Order"))
    }
    categories.forEach { category ->
        availableTabs.add(SupplyTabItem(category.id, SupplyTabType.CategoryTab(category), category.name))
    }

    if (savedTabOrder.isEmpty()) {
        return availableTabs
    }

    val sortedList = mutableListOf<SupplyTabItem>()
    // First, place tabs that are found in the saved order
    savedTabOrder.forEach { tabId ->
        val found = availableTabs.find { it.id == tabId }
        if (found != null) {
            sortedList.add(found)
            availableTabs.remove(found)
        }
    }
    // Then, append any remaining available tabs (e.g. newly added categories or admin tabs)
    sortedList.addAll(availableTabs)
    return sortedList
}
```

**Step 2: Run test to verify it passes**
Run: `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.supply.SupplyTabSortingTest`
Expected: PASS.

**Step 3: Commit**
```bash
git add app/src/test/java/com/kkc/sheettracker/ui/supply/SupplyTabSortingTest.kt app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyTabModels.kt
git commit -m "test & feat: implement buildSupplyTabsList and verify correctness via unit tests"
```

---

### Task 4: Implement SupplyTabReorderScreen UI

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyTabReorderScreen.kt`

**Step 1: Write UI implementation**
Create a full-screen or dialog-body Composable for reordering using the `sh.calvin.reorderable` library. Include Up/Down arrow buttons for extra accessibility.

```kotlin
package com.kkc.sheettracker.ui.supply

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.UiPreferencesStore
import com.kkc.sheettracker.ui.dashboard.DashboardSurfaceCard
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun SupplyTabReorderScreen(
    availableTabs: List<SupplyTabItem>,
    preferencesStore: UiPreferencesStore,
    onOrderChanged: (List<String>) -> Unit
) {
    val tabs = remember { mutableStateListOf<SupplyTabItem>().apply { addAll(availableTabs) } }
    val listState = rememberLazyListState()
    
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index in tabs.indices && to.index in tabs.indices) {
            tabs.add(to.index, tabs.removeAt(from.index))
        }
    }

    val saveOrder = {
        val newOrder = tabs.map { it.id }
        preferencesStore.setSupplyTabOrder(newOrder)
        onOrderChanged(newOrder)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Drag or use arrows to reorder tabs. Top of the list is far left, bottom is far right.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(tabs, key = { _, item -> item.id }) { index, tabItem ->
                ReorderableItem(reorderState, key = tabItem.id) {
                    DashboardSurfaceCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DragHandle,
                                    contentDescription = "Drag to reorder",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .draggableHandle(
                                            onDragStopped = { saveOrder() }
                                        )
                                )
                                
                                Text(
                                    text = tabItem.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            tabs.add(index - 1, tabs.removeAt(index))
                                            saveOrder()
                                        }
                                    },
                                    enabled = index > 0
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowUpward,
                                        contentDescription = "Move Up"
                                    )
                                }
                                
                                IconButton(
                                    onClick = {
                                        if (index < tabs.size - 1) {
                                            tabs.add(index + 1, tabs.removeAt(index))
                                            saveOrder()
                                        }
                                    },
                                    enabled = index < tabs.size - 1
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowDownward,
                                        contentDescription = "Move Down"
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
```

**Step 2: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyTabReorderScreen.kt
git commit -m "feat: implement SupplyTabReorderScreen UI with drag-and-drop and accessibility arrows"
```

---

### Task 5: Integrate Reorder feature into SupplyDashboardScreen

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt`

**Step 1: Wire local state and dynamic tab resolution**
Load saved tab order from `UiPreferencesStore` on launch, and resolve `supplyTabs` dynamically. Replace the old pager count and page resolution logic.

**Step 2: Add "Reorder Tabs" button to overflow menu**
Add option to the `DropdownMenu` inside the topbar actions.

**Step 3: Define modal routing for SupplyDashboardModal.ReorderTabs**
Add `ReorderTabs` object to `SupplyDashboardModal` and handle it in the `when (activeModal)` block to render `SupplyTabReorderScreen` inside a `SupplyModalFrame`. Update pager scroll page matching the active selected tab after reordering.

**Step 4: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt
git commit -m "feat: integrate supply tab reordering into SupplyDashboardScreen"
```

---

### Task 6: Build & Verification

**Step 1: Run unit tests**
Run: `.\gradlew.bat testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

**Step 2: Compile and deploy release package**
Run: `powershell -ExecutionPolicy Bypass -File .\adb-install-release.ps1`
Expected: Successfully installs to the tablet.

---
