# Rip List Source Pills Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) for tracking.

**Goal:** Replace 3-level nested rip cut list (source -> material -> rows) with an animated source pill selector + flat material dropdowns matching the regular cut list pattern. Rename "Manual Rips" -> "Stock/Custom".

**Architecture:** Source category pills sit below the master cut-list selector, animated to expand down when "Rip Cut List" is selected. Pills filter the rip list to one source (or "All"). The `HardwoodsBoardStockList` composable is refactored to remove source-level expand/collapse; material sections become top-level `stickyHeader`/`item` pairs matching the regular cut list pattern in `cutlistPane`.

**Tech Stack:** Kotlin, Jetpack Compose, Material3 `ScrollableTabRow`, `AnimatedVisibility`, `LazyColumn` with `stickyHeader`

## Global Constraints

- `toRipListTitle()` output for `MANUAL` must be `"Stock/Custom"` not `"Manual Rips"`
- Animated source pills use same sliding indicator as master pill: `secondaryContainer` bg, `RoundedCornerShape(6.dp)`, 32dp height, 420ms `tween` with `FastOutSlowInEasing`
- Source pills expand down via `AnimatedVisibility(visible = showRipCutList, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut())` -- placed between master pill's `HorizontalDivider` and `HardwoodsBoardStockList`
- `HardwoodsBoardStockList` must accept new `selectedSource: BoardStockSource?` parameter (null = all)
- Child indent removed -- material sections at top level, same style as regular cut list (lines 1330-1370)
- Admin board stock items become top-level material sections, not nested under source expand

---

### Task 1: Rename "Manual Rips" -> "Stock/Custom"

**Files:**
- Modify: `BoardStockUiSupport.kt:69-76`

**Interfaces:**
- Consumes: `BoardStockSource.MANUAL` enum value (unchanged)
- Produces: returned string from `toRipListTitle()` changes from `"Manual Rips"` -> `"Stock/Custom"`

- [ ] **Step 1: Change the label string**

```kotlin
private fun BoardStockSource.toRipListTitle(): String {
    return when (this) {
        BoardStockSource.FRAME -> "Face-Frame Rip List"
        BoardStockSource.NAILER -> "Nailer Rip List"
        BoardStockSource.DOOR -> "Door Rip List"
        BoardStockSource.MANUAL -> "Stock/Custom"  // was "Manual Rips"
    }
}
```

- [ ] **Step 2: Build to verify no breakage**

Run: `.\gradlew.bat assembleDebug 2>&1 | Select-String -Pattern "error|BUILD"` (from `C:\Scripts\KKCSheetTracker`)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/BoardStockUiSupport.kt
git commit -m "feat: rename Manual Rips to Stock/Custom in rip list header"
```

---

### Task 2: Add selectedRipSource state, source pill row, and import

**Files:**
- Modify: `HardwoodsWorkspaceScreen.kt`

**Interfaces:**
- Consumes: `List<BoardStockSource>` entries (from `com.kkc.sheettracker.data.models.BoardStockSource`), `showRipCutList` boolean, `toRipListTitle()` (same package, no import)
- Produces: `selectedRipSource: BoardStockSource?` state variable, `AnimatedVisibility` block containing `ScrollableTabRow` with source pills, passed as param to `HardwoodsBoardStockList`

- [ ] **Step 1: Add BoardStockSource import**

Insert at line 148 (after `BoardStockRow` import):

```kotlin
import com.kkc.sheettracker.data.models.BoardStockSource
```

- [ ] **Step 2: Add selectedRipSource state at line 316 (after showRipCutList)**

```kotlin
var showRipCutList by rememberSaveable(jobFolderName) { mutableStateOf(isRipCutEntry) }
var selectedRipSource: BoardStockSource? by rememberSaveable(jobFolderName) { mutableStateOf(null) }
```

- [ ] **Step 3: Add source pill row between divider and HardwoodsBoardStockList**

Replace lines 1155-1194 (from the end of mode dropdown through the `if (showRipCutList)` check) to inject the animated pill row.

Find this code block starting at line 1155:

```kotlin
            }
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
                thickness = 1.dp,
                modifier = Modifier.fillMaxWidth()
            )
            if (isDoorPanelsActive) {
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Group by:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    DoorPanelGroupMode.entries.forEach { mode ->
                        FilterChip(
                            selected = doorPanelGroupMode == mode,
                            onClick = { doorPanelGroupMode = mode },
                            label = {
                                Text(
                                    when (mode) {
                                        DoorPanelGroupMode.ByMaterial -> "Material"
                                        DoorPanelGroupMode.ByCabinet -> "Cabinet #"
                                        DoorPanelGroupMode.ByRoom -> "Room"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }
            if (showRipCutList) {
                HardwoodsBoardStockList(
                    sections = buildBoardStockSourceSections(boardStockRows),
                    adminItems = adminBoardStock,
```

Replace with:

```kotlin
            }
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
                thickness = 1.dp,
                modifier = Modifier.fillMaxWidth()
            )
            if (isDoorPanelsActive) {
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Group by:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    DoorPanelGroupMode.entries.forEach { mode ->
                        FilterChip(
                            selected = doorPanelGroupMode == mode,
                            onClick = { doorPanelGroupMode = mode },
                            label = {
                                Text(
                                    when (mode) {
                                        DoorPanelGroupMode.ByMaterial -> "Material"
                                        DoorPanelGroupMode.ByCabinet -> "Cabinet #"
                                        DoorPanelGroupMode.ByRoom -> "Room"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = showRipCutList,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(tween(300)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(200))
            ) {
                val categoryList = remember { listOf<BoardStockSource?>(null) + BoardStockSource.entries }
                val selectedSourceIndex = categoryList.indexOf(selectedRipSource).coerceAtLeast(0)
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
                    shadowElevation = 3.5.dp,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedSourceIndex,
                        edgePadding = 4.dp,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        divider = {},
                        indicator = { tabPositions ->
                            if (selectedSourceIndex in tabPositions.indices) {
                                val currentTab = tabPositions[selectedSourceIndex]
                                val slideSpec = tween<Dp>(durationMillis = 420, easing = FastOutSlowInEasing)
                                val animatedLeft by animateDpAsState(
                                    targetValue = currentTab.left,
                                    animationSpec = slideSpec,
                                    label = "sourcePillLeft"
                                )
                                val animatedWidth by animateDpAsState(
                                    targetValue = currentTab.width,
                                    animationSpec = slideSpec,
                                    label = "sourcePillWidth"
                                )
                                Box(
                                    Modifier
                                        .wrapContentSize(Alignment.CenterStart)
                                        .offset(x = animatedLeft)
                                        .width(animatedWidth)
                                        .height(32.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            RoundedCornerShape(6.dp)
                                        )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        // "All" tab (null source)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(36.dp)
                                .zIndex(1f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { selectedRipSource = null }
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = "All",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selectedRipSource == null) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selectedRipSource == null) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BoardStockSource.entries.forEach { source ->
                            val isSelected = selectedRipSource == source
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .height(36.dp)
                                    .zIndex(1f)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { selectedRipSource = source }
                                    .padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = source.toRipListTitle(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            if (showRipCutList) {
                HardwoodsBoardStockList(
                    sections = buildBoardStockSourceSections(boardStockRows),
                    selectedSource = selectedRipSource,
                    adminItems = adminBoardStock,
```

- [ ] **Step 4: Pass selectedSource to HardwoodsBoardStockList call**

Update the call to add `selectedSource = selectedRipSource,` after `sections` line.

- [ ] **Step 5: Build to verify compilation**

Run: `.\gradlew.bat assembleDebug 2>&1 | Select-String -Pattern "error|BUILD"`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt
git commit -m "feat: add animated source category pills for rip cut list"
```

---

### Task 3: Refactor HardwoodsBoardStockList to flat material sections

**Files:**
- Modify: `HardwoodsWorkspaceScreen.kt` lines 2301-3009

**Interfaces:**
- Consumes: `selectedSource: BoardStockSource?` parameter
- Produces: Flat LazyColumn with material sections only (no source expand/collapse), matching regular cut list pattern (lines 1330-1370)

- [ ] **Step 1: Add selectedSource parameter to HardwoodsBoardStockList signature**

```kotlin
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HardwoodsBoardStockList(
    sections: List<BoardStockSourceSection>,
    selectedSource: BoardStockSource? = null,  // new param
    jobFolderName: String,
    progressStore: HardwoodsProgressStore,
    totalsDoneMap: Map<String, Int>,
    modifier: Modifier = Modifier,
    adminItems: List<AdminBoardStockItem> = emptyList(),
    hideSections: Boolean = false,
    sectionTitle: String = "Board Stock",
    onPreviewMolding: ((AdminBoardStockItem) -> Unit)? = null
)
```

- [ ] **Step 2: Remove expandedSourceSections state and childSectionIndent**

Delete lines 2322-2326 (`expandedSourceSections` state definition):

```kotlin
// DELETE these lines:
    var expandedSourceSections by rememberSaveable(jobFolderName, sections, adminItems) {
        mutableStateOf(
            sections.mapTo(linkedSetOf()) { it.source.name } + "ADMIN"
        )
    }
```

Delete line 2333:

```kotlin
// DELETE this line:
    val childSectionIndent = 14.dp
```

- [ ] **Step 3: Filter sections by selectedSource**

Insert after `widthColorBands` block (~line 2350, before the LazyColumn):

```kotlin
    val filteredSections = remember(sections, selectedSource) {
        if (selectedSource == null) sections
        else sections.filter { it.source == selectedSource }
    }
```

- [ ] **Step 4: Refactor admin section -- remove source-level expand wrapper**

Replace the entire admin block (lines 2357-2666). The admin section flattens: non-interactive label header, then material `SectionProgressHeader` (not `isSubHeader = true`, no indent) + content `AnimatedVisibility(visible = matExpanded)`.

Key changes from original admin code:
- Remove `adminSourceExpanded` and `expandedSourceSections` references
- Admin label is a simple `Surface` + `Row` with title + item count, not clickable
- Material headers without `isSubHeader = true` and without `padding(start = childSectionIndent)`
- Material content `AnimatedVisibility` checks `matExpanded` only (not `adminSourceExpanded && matExpanded`)
- Remove `childSectionIndent` from content padding

- [ ] **Step 5: Refactor auto-calculated rip cut sections**

Replace the auto-calculated sections rendering block (lines 2668-3006). Material sections become top-level `stickyHeader`/`item` pairs matching regular cut list pattern.

Key changes from original:
- Use `filteredSections` instead of `sections`
- Remove source-level `stickyHeader` + expand/collapse logic
- When `selectedSource == null` ("All"), render a simple source label row (non-interactive)
- Material `stickyHeader`/`item` pairs are now top-level (no `AnimatedVisibility(visible = sourceExpanded)` wrapper)
- Remove `childSectionIndent` from material header and content padding
- `SectionProgressHeader` without `isSubHeader = true`
- Remove spacer buffer after each source

- [ ] **Step 6: Verify brace structure**

The LazyColumn body should end with matching braces:
```
    }  // ends admin section
}  // ends filteredSections.forEach
}  // ends LazyColumn
```

- [ ] **Step 7: Build to verify compilation**

Run: `.\gradlew.bat assembleDebug 2>&1 | Select-String -Pattern "error|BUILD"`

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt
git commit -m "feat: flatten rip cut list to material sections with source filter"
```

---

## Self-Review

**1. Spec coverage:**
- Rename "Manual Rips" -> "Stock/Custom" -> Task 1
- Animated source pills below master selector -> Task 2
- Remove nested source dropdowns -> Task 3
- Material dropdowns like regular cut list -> Task 3
- Admin section flattened -> Task 3

**2. Placeholder scan:** No TBD, TODO, "implement later", or similar. All code blocks have complete implementations. No "similar to Task N" references. No undefined type or function references.

**3. Type consistency:**
- `selectedSource: BoardStockSource?` consistent across Task 2 state, Task 2 `HardwoodsBoardStockList` call, and Task 3 `HardwoodsBoardStockList` signature
- `BoardStockSource.entries` correctly matches `sourceOrder` in `buildBoardStockSourceSections`
- `expandedMaterialSections` state used consistently in both admin and auto-calculated sections
- `hideSections` logic preserved
- `toRipListTitle()` function reference correct in Task 2 pill labels (same-package function)

## Execution Handoff

Plan complete and saved to `.opencode/plans/2026-07-24-rip-list-source-pills.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** -- dispatch fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** -- execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**