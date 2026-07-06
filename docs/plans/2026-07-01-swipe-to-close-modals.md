# Supply Page Modals Swipe-to-Close and Add Button Relocation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow swiping modals vertically to close them on the supply page, respecting scroll boundaries (only allow swipe-to-close if at the top or bottom of the modal scroll), and move the "Add Item" button from the category tab headers to the category card headers.

**Architecture:** Update `InventoryBlock` in `DashboardWidgetModel` to include an optional `onHeaderAction` callback, and render it in `DashboardWidgetRenderer` as an inline "Add Item" button. Remove `CategoryAddHeader` and `EmptyCategoryAddCard` from `SupplyDashboardScreen`. Add vertical gestures (`pointerInput` and `NestedScrollConnection`) to `SupplyModalFrame` to translate and animate the modal container off-screen based on scroll state and drag gestures.

**Tech Stack:** Jetpack Compose, Kotlin, Android SDK, Gradle.

---

### Task 1: Add onHeaderAction to DashboardWidgetModel.InventoryBlock

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardWidgetModel.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/dashboard/UnifiedDashboardFactoriesTest.kt`

**Step 1: Write the failing test**

In `app/src/test/java/com/kkc/sheettracker/ui/dashboard/UnifiedDashboardFactoriesTest.kt`, add the following test method:
```kotlin
    @Test
    fun `buildSupplyCategoryWidgets registers onAddItem callback`() {
        var clicked = false
        val widgets = buildSupplyCategoryWidgets(
            category = SupplyCategory(id = "cat-1", name = "Hardware", position = 1),
            items = emptyList(),
            isSubscribed = false,
            notificationCount = 0,
            onAddItem = { clicked = true }
        )
        val block = widgets.requireSingle<DashboardWidgetModel.InventoryBlock>()
        block.onHeaderAction?.invoke()
        assertTrue(clicked)
    }
```

**Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: Compile error because `onHeaderAction` does not exist on `InventoryBlock` and `buildSupplyCategoryWidgets` does not accept `onAddItem`.

**Step 3: Write minimal implementation**

1. Modify `app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardWidgetModel.kt` at line 98:
```kotlin
    data class InventoryBlock(
        override val key: String,
        val title: String,
        val subtitle: String? = null,
        val items: List<DashboardInventoryItemModel>,
        val summary: String? = null,
        val emptyMessage: String = "No inventory items are available.",
        val onHeaderAction: (() -> Unit)? = null
    ) : DashboardWidgetModel
```

2. Modify `buildSupplyCategoryWidgets` in `app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardWidgetFactories.kt`:
```kotlin
fun buildSupplyCategoryWidgets(
    category: SupplyCategory,
    items: List<SupplyItem>,
    isSubscribed: Boolean,
    notificationCount: Int,
    onAddItem: (() -> Unit)? = null
): List<DashboardWidgetModel> {
    val sortedItems = items.sortedWith(
        compareBy<SupplyItem>(
            { SUPPLY_STATUS_PRIORITY[it.status] ?: Int.MAX_VALUE },
            { it.name.lowercase() }
        )
    )
    val urgentCount = sortedItems.count { (SUPPLY_STATUS_PRIORITY[it.status] ?: Int.MAX_VALUE) <= 3 }
    val categoryAccent = when {
        urgentCount > 0 -> DashboardAccent.WARNING
        isSubscribed -> DashboardAccent.INFO
        else -> DashboardAccent.NEUTRAL
    }

    return listOf(
        DashboardWidgetModel.Hero(
            key = "supply-hero-${category.id}",
            title = "${category.name} Overview",
            primaryValue = "${sortedItems.size} ${pluralize("item", sortedItems.size)}",
            secondaryValue = if (isSubscribed) "Subscribed for updates" else "Not subscribed for updates",
            tertiaryValue = if (notificationCount > 0) {
                "$notificationCount ${pluralize("notification", notificationCount)} waiting"
            } else {
                "No active notifications"
            },
            accent = categoryAccent
        ),
        DashboardWidgetModel.InventoryBlock(
            key = "supply-inventory-${category.id}",
            title = category.name,
            subtitle = if (isSubscribed) "Watching this category" else "Category overview",
            items = sortedItems.map(::toInventoryItemModel),
            summary = buildSupplySummary(
                itemCount = sortedItems.size,
                urgentCount = urgentCount,
                notificationCount = notificationCount
            ),
            onHeaderAction = onAddItem
        )
    )
}
```

**Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardWidgetModel.kt app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardWidgetFactories.kt app/src/test/java/com/kkc/sheettracker/ui/dashboard/UnifiedDashboardFactoriesTest.kt
git commit -m "feat: add onHeaderAction callback to InventoryBlock and update factory"
```

---

### Task 2: Implement header action button rendering in DashboardWidgetRenderer

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardWidgetFactories.kt`

**Step 1: Write the failing test**

(Manual visual verification/rendering test or code modification check. Since layout/rendering is visual, we will implement it directly and run compilation checks first.)

**Step 2: Run build to verify compilation**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: PASS (No new code logic changes to tests yet).

**Step 3: Write minimal implementation**

Modify `DashboardWidgetRenderer` in `app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardWidgetFactories.kt` at line 601:
Add imports:
```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
```
Then replace the `InventoryBlock` card header container:
```kotlin
                is DashboardWidgetModel.InventoryBlock -> DashboardSurfaceCard(contentPadding = PaddingValues(0.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                DashboardSectionHeader(widget.title, widget.subtitle)
                                widget.summary?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            widget.onHeaderAction?.let { action ->
                                TextButton(
                                    onClick = action,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add Item", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
```

**Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardWidgetFactories.kt
git commit -m "feat: render inline Add Item button in InventoryBlock header"
```

---

### Task 3: Remove CategoryAddHeader and EmptyCategoryAddCard and update SupplyDashboardScreen

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt`

**Step 1: Write the failing test**

(Compilation/Verification check: Verify that CategoryAddHeader and EmptyCategoryAddCard usages are removed and we use buildSupplyCategoryWidgets with the callback instead.)

**Step 2: Run test to verify initial state**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: PASS

**Step 3: Write minimal implementation**

In `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt`:
1. Remove `CategoryAddHeader` definition (lines 605-630).
2. Remove `EmptyCategoryAddCard` definition (lines 633-658).
3. Inside the `HorizontalPager`'s `else` block (lines 350-394), replace the Column contents:
```kotlin
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = 160.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                DashboardWidgetRenderer(
                                    widgets = buildSupplyCategoryWidgets(
                                        category = category,
                                        items = categoryItems,
                                        isSubscribed = subscriptionData.subscribedCategoryIds.contains(category.id),
                                        notificationCount = notifications.count { it.item.categoryId == category.id },
                                        onAddItem = { openNewItemModal(category.id) }
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
```

**Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt
git commit -m "refactor: remove CategoryAddHeader and EmptyCategoryAddCard, wire onAddItem to category block header"
```

---

### Task 4: Add gesture swipe-to-close logic to SupplyModalFrame

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyModalFrame.kt`

**Step 1: Write the failing test**

(Visual gesture interaction. We can verify compiled code works and tests still build).

**Step 2: Run test to verify initial state**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: PASS

**Step 3: Write minimal implementation**

In `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyModalFrame.kt`:
1. Add imports:
```kotlin
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
```

2. Inside `SupplyModalFrame`, define variables and local callbacks:
```kotlin
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { 160.dp.toPx() } }
    var offsetY by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var animationJob by remember { androidx.compose.runtime.mutableStateOf<Job?>(null) }

    val transitionState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }

    fun requestDismiss() {
        transitionState.targetState = false
    }
```

3. Define `nestedScrollConnection`:
```kotlin
    val nestedScrollConnection = remember(thresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                animationJob?.cancel()
                val delta = available.y
                val currentOffset = offsetY

                if (currentOffset > 0f && delta < 0f) {
                    val newOffset = (currentOffset + delta).coerceAtLeast(0f)
                    offsetY = newOffset
                    return Offset(0f, newOffset - currentOffset)
                }
                if (currentOffset < 0f && delta > 0f) {
                    val newOffset = (currentOffset + delta).coerceAtMost(0f)
                    offsetY = newOffset
                    return Offset(0f, newOffset - currentOffset)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                animationJob?.cancel()
                val delta = available.y
                if (delta != 0f) {
                    offsetY += delta
                    return Offset(0f, delta)
                }
                return Offset.Zero
            }
        }
    }
```

4. Define `gestureModifier`:
```kotlin
    val gestureModifier = Modifier
        .pointerInput(thresholdPx) {
            coroutineScope {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val allUp = event.changes.all { !it.pressed }
                        if (allUp) {
                            if (offsetY != 0f && (animationJob == null || !animationJob!!.isActive)) {
                                animationJob = launch {
                                    val currentVal = offsetY
                                    val targetVal = if (currentVal > thresholdPx) {
                                        2000f
                                    } else if (currentVal < -thresholdPx) {
                                        -2000f
                                    } else {
                                        0f
                                    }

                                    if (targetVal != 0f) {
                                        animate(
                                            initialValue = currentVal,
                                            targetValue = targetVal,
                                            animationSpec = tween(durationMillis = 220)
                                        ) { value, _ ->
                                            offsetY = value
                                        }
                                        requestDismiss()
                                    } else {
                                        animate(
                                            initialValue = currentVal,
                                            targetValue = 0f,
                                            animationSpec = tween(durationMillis = 150)
                                        ) { value, _ ->
                                            offsetY = value
                                        }
                                    }
                                }
                            }
                        } else {
                            animationJob?.cancel()
                        }
                    }
                }
            }
        }
        .pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { animationJob?.cancel() },
                onDragEnd = {},
                onDragCancel = {
                    animationJob = scope.launch {
                        animate(
                            initialValue = offsetY,
                            targetValue = 0f,
                            animationSpec = tween(durationMillis = 150)
                        ) { value, _ ->
                            offsetY = value
                        }
                    }
                },
                onVerticalDrag = { change, dragAmount ->
                    animationJob?.cancel()
                    change.consume()
                    offsetY += dragAmount
                }
            )
        }
```

5. Apply translation offset, nested scroll, and gesture modifier to the modal container `Surface` in `SupplyModalFrame`:
```kotlin
                Surface(
                    modifier = modifier
                        .offset { IntOffset(0, offsetY.roundToInt()) }
                        .nestedScroll(nestedScrollConnection)
                        .then(gestureModifier)
                        .fillMaxWidth()
                        .widthIn(max = 1040.dp)
                        .fillMaxHeight(0.92f),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 6.dp,
                    shadowElevation = 16.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
```

**Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyModalFrame.kt
git commit -m "feat: add nested scroll and drag swipe-to-close behavior on SupplyModalFrame"
```

---

### Verification Plan

#### Automated Tests
- Run `.\gradlew.bat testDebugUnitTest --continue` to ensure all unit tests build and pass successfully.

#### Manual Verification
- Run `.\gradlew.bat assembleDebug` to build the debug APK.
- Install and launch the application on a target device or emulator using:
  `adb install -r app\build\outputs\apk\debug\app-debug.apk`
- Navigate to the Supply tab/page.
- Select a category tab (e.g. Hardware):
  - Verify that the card displaying "Add Item" at the top of the tab is gone.
  - Verify that the "Add Item" button is now located inline in the category card header.
  - Click "Add Item" and verify the New Item modal opens.
- Open a modal (e.g. details for an item with many comments or a long description):
  - Try swiping down on the modal title/header to verify it animates down off-screen and closes.
  - If the content is scrollable:
    - Verify that swiping up/down in the middle of the scroll range scrolls the content normally instead of closing the modal.
    - Scroll to the very top and swipe down again. Verify the modal animates down and closes.
    - Scroll to the very bottom and swipe up again. Verify the modal animates up and closes.
  - If the content is not scrollable (or fits fully):
    - Verify that swiping up OR down from any location immediately drags and closes the modal.
