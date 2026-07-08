# Hardwoods Cut List — Hold-to-Complete / Hold-to-Zero Tally Buttons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the Hardwoods workspace's four tally UIs (Classic Cut List, Admin Board Stock, Rip Cut/Board Stock, List View), tap-and-hold on a `+`/`-` tally button jumps the line to complete/zero instead of stepping by one; regular taps keep today's single-step behavior. (List View — Task 5 — was added after manual verification of the original 3-area plan surfaced that it's a fourth, distinct tally UI missed during design.)

**Architecture:** Pure UI-layer change. No new `ProgressStore`/`HardwoodsProgressStore` functions — each area already has a `doneCount`-setting function (`setDoneCount`, `setBoardStockRipDone`, `setAdminBoardStockDone`) that the new long-press handlers call directly with the target value (max or 0). Buttons that don't support `onLongClick` natively (`Button`, `IconButton`) are replaced with `combinedClickable`-based equivalents that preserve today's visuals.

**Tech Stack:** Kotlin, Jetpack Compose, Material3.

**Spec:** [docs/superpowers/specs/2026-07-08-hardwoods-tally-hold-to-complete-design.md](../specs/2026-07-08-hardwoods-tally-hold-to-complete-design.md)

---

## Context for the implementer

Two files are touched, both under `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/`:

- `ClassicCutListTable.kt` — the Classic Cut List table. Its `TableRow` private composable (defined ~line 767) already has `decrementEnabled`/`incrementEnabled` booleans computed from `skipped`/`done`/`qty` — these already encode "not skipped AND not already at the target extreme", so the long-press guard reuses them via Compose's `enabled` parameter (when `enabled = false`, `combinedClickable` fires neither `onClick` nor `onLongClick`).
- `HardwoodsWorkspaceScreen.kt` — hosts `HardwoodsBoardStockList` (private composable, ~line 1815), which renders both the **Admin Board Stock** rows and the **Rip Cut/Board Stock** rows. The Admin rows' `+`/`-` buttons already pass an `enabled = !itemSkipped && done > 0` (or `< boards`) expression; the Rip Cut rows' buttons currently have **no** `enabled` expression at all (relying on the store to clamp), so for Rip Cut this plan computes the guard as a local boolean and checks it inside the long-press lambda instead of changing the buttons' existing always-enabled short-tap look.

There is no new pure/business logic to unit-test — this is Compose gesture wiring calling existing, already-tested store setters. Verification is: (1) the project compiles and existing unit tests still pass, (2) manual on-device check per Task 5 using the `debug-android-tablet` skill.

`ExperimentalFoundationApi` (needed for `combinedClickable`) is already imported in both files; `HardwoodsBoardStockList` is already `@OptIn(ExperimentalFoundationApi::class)`. The new `TallyStepButton` helper in `HardwoodsWorkspaceScreen.kt` needs its own `@OptIn` since opt-in doesn't propagate to sibling declarations.

---

### Task 1: Classic Cut List — thread complete/zero callbacks through `ClassicCutListTable`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/ClassicCutListTable.kt:117-133` (function signature), `:626-648` (call site), `:769-800` (`TableRow` signature + row modifier), `:888-959` (tally IconButtons)

- [ ] **Step 1: Add two new callback parameters to `ClassicCutListTable`'s signature**

In `ClassicCutListTable.kt`, at the parameter list starting line 117, add two new parameters after `onToggleSkip`:

```kotlin
fun ClassicCutListTable(
    docType: HardwoodDocType,
    rows: List<HardwoodCutlistRow>,
    rowProgressMap: Map<Pair<String, String>, HardwoodRowProgress>,
    onIncrementProgress: (rowId: String, currentDone: Int, maxQty: Int) -> Unit,
    onDecrementProgress: (rowId: String, currentDone: Int, maxQty: Int) -> Unit,
    onToggleSkip: (rowId: String, currentSkipped: Boolean) -> Unit,
    onCompleteProgress: (rowId: String, qty: Int) -> Unit,
    onZeroProgress: (rowId: String, qty: Int) -> Unit,
    activeStrokes: List<HardwoodInkStroke>,
    onSaveStrokes: (strokes: List<HardwoodInkStroke>, deletedIds: List<String>) -> Unit,
    onRowLongPress: (HardwoodCutlistRow) -> Unit,
    isDarkTheme: Boolean,
    widthColorBands: Map<String, Color>,
    toolState: PdfMarkupToolState,
    modifier: Modifier = Modifier,
    showMarkupToolbar: Boolean = true,
    hostMarkupToolbarInNavBar: Boolean = false
) {
```

- [ ] **Step 2: Wire the new callbacks at the `TableRow` call site**

At `ClassicCutListTable.kt:626-648`, add `onCompleteAll` and `onZeroOut` to the `TableRow(...)` call, right after `onToggleSkip`:

```kotlin
                                TableRow(
                                    row = row,
                                    done = done,
                                    qty = qty,
                                    skipped = skipped,
                                    washColor = washColor,
                                    borderWashColor = borderWashColor,
                                    widthColor = widthColor,
                                    tallyActionsEnabled = tallyActionsEnabled,
                                    onIncrement = { onIncrementProgress(row.rowId, done, qty) },
                                    onDecrement = { onDecrementProgress(row.rowId, done, qty) },
                                    onToggleSkip = { onToggleSkip(row.rowId, skipped) },
                                    onCompleteAll = { onCompleteProgress(row.rowId, qty) },
                                    onZeroOut = { onZeroProgress(row.rowId, qty) },
                                    longPressEnabled = classicRowLongPressEnabled(allowFingerDrawing),
                                    onLongPress = { onRowLongPress(row) },
                                    tallyTargetKeyPrefix = "${docType.name}-${classicPage}-${row.rowId}",
                                    onTallyTargetChanged = { key, target ->
                                        if (target == null) {
                                            tallyHitTargets.remove(key)
                                        } else {
                                            tallyHitTargets[key] = target
                                        }
                                    }
                                )
```

- [ ] **Step 3: Add the new parameters to `TableRow`'s signature**

At `ClassicCutListTable.kt:769-785`, add `onCompleteAll` and `onZeroOut` after `onDecrement`:

```kotlin
private fun TableRow(
    row: HardwoodCutlistRow,
    done: Int,
    qty: Int,
    skipped: Boolean,
    washColor: Color,
    borderWashColor: Color,
    widthColor: Color,
    tallyActionsEnabled: Boolean,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onCompleteAll: () -> Unit,
    onZeroOut: () -> Unit,
    onToggleSkip: () -> Unit,
    longPressEnabled: Boolean,
    onLongPress: () -> Unit,
    tallyTargetKeyPrefix: String,
    onTallyTargetChanged: (String, TallyHitTarget?) -> Unit
) {
```

- [ ] **Step 4: Replace the decrement `IconButton` with a long-press-capable `Box`**

At `ClassicCutListTable.kt:888-907`, replace:

```kotlin
            IconButton(
                onClick = {
                    onDecrement()
                },
                enabled = decrementEnabled,
                modifier = Modifier
                    .size(36.dp)
                    .trackTallyTarget(
                        key = "$tallyTargetKeyPrefix-decrement",
                        enabled = decrementEnabled,
                        onTap = onDecrement,
                        onTargetChanged = onTallyTargetChanged
                    )
            ) {
                Icon(
                    Icons.Default.RemoveCircleOutline,
                    contentDescription = "Minus",
                    tint = if (decrementEnabled) statusColors.bad else Color.Gray.copy(alpha = 0.3f)
                )
            }
```

with:

```kotlin
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .trackTallyTarget(
                        key = "$tallyTargetKeyPrefix-decrement",
                        enabled = decrementEnabled,
                        onTap = onDecrement,
                        onTargetChanged = onTallyTargetChanged
                    )
                    .combinedClickable(
                        enabled = decrementEnabled,
                        onClick = onDecrement,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onZeroOut()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.RemoveCircleOutline,
                    contentDescription = "Minus",
                    tint = if (decrementEnabled) statusColors.bad else Color.Gray.copy(alpha = 0.3f)
                )
            }
```

- [ ] **Step 5: Replace the increment `IconButton` the same way**

At `ClassicCutListTable.kt:940-959`, replace:

```kotlin
            IconButton(
                onClick = {
                    onIncrement()
                },
                enabled = incrementEnabled,
                modifier = Modifier
                    .size(36.dp)
                    .trackTallyTarget(
                        key = "$tallyTargetKeyPrefix-increment",
                        enabled = incrementEnabled,
                        onTap = onIncrement,
                        onTargetChanged = onTallyTargetChanged
                    )
            ) {
                Icon(
                    Icons.Default.AddCircleOutline,
                    contentDescription = "Plus",
                    tint = if (incrementEnabled) statusColors.completeBorder else Color.Gray.copy(alpha = 0.3f)
                )
            }
```

with:

```kotlin
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .trackTallyTarget(
                        key = "$tallyTargetKeyPrefix-increment",
                        enabled = incrementEnabled,
                        onTap = onIncrement,
                        onTargetChanged = onTallyTargetChanged
                    )
                    .combinedClickable(
                        enabled = incrementEnabled,
                        onClick = onIncrement,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCompleteAll()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.AddCircleOutline,
                    contentDescription = "Plus",
                    tint = if (incrementEnabled) statusColors.completeBorder else Color.Gray.copy(alpha = 0.3f)
                )
            }
```

`IconButton` is no longer used in this composable's tally column — leave the `import androidx.compose.material3.*` wildcard import as-is (still used elsewhere in the file).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/ClassicCutListTable.kt
git commit -m "feat: add hold-to-complete/zero to classic cut list tally buttons"
```

---

### Task 2: Wire the new callbacks at `ClassicCutListTable`'s call site in `HardwoodsWorkspaceScreen.kt`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt:985-1020`

- [ ] **Step 1: Add `onCompleteProgress`/`onZeroProgress` to the `ClassicCutListTable(...)` call**

Find the `ClassicCutListTable(` call (starts ~line 985). Right after the existing `onToggleSkip = { ... }` block (ends ~line 1019 with `},`), insert:

```kotlin
                        onCompleteProgress = { rowId, qty ->
                            hardwoodsProgressStore.setDoneCount(
                                jobFolderName = jobFolderName,
                                docType = selectedDoc.docType.name,
                                rowId = rowId,
                                qty = qty,
                                doneCount = qty
                            )
                        },
                        onZeroProgress = { rowId, qty ->
                            hardwoodsProgressStore.setDoneCount(
                                jobFolderName = jobFolderName,
                                docType = selectedDoc.docType.name,
                                rowId = rowId,
                                qty = qty,
                                doneCount = 0
                            )
                        },
```

so the full block reads (context — `onToggleSkip` unchanged, new block inserted right after it, before `onSaveStrokes`):

```kotlin
                        onToggleSkip = { rowId, currentSkipped ->
                            val rowUi = rowDisplayMap[rowId]
                            if (rowUi?.isMultiCab == true) {
                                val targetRow = rows.firstOrNull { it.rowId == rowId }
                                if (targetRow != null) {
                                    cabSkipRow = targetRow
                                }
                            } else {
                                hardwoodsProgressStore.setSkipped(
                                    jobFolderName = jobFolderName,
                                    docType = selectedDoc.docType.name,
                                    rowId = rowId,
                                    skipped = !currentSkipped
                                )
                            }
                        },
                        onCompleteProgress = { rowId, qty ->
                            hardwoodsProgressStore.setDoneCount(
                                jobFolderName = jobFolderName,
                                docType = selectedDoc.docType.name,
                                rowId = rowId,
                                qty = qty,
                                doneCount = qty
                            )
                        },
                        onZeroProgress = { rowId, qty ->
                            hardwoodsProgressStore.setDoneCount(
                                jobFolderName = jobFolderName,
                                docType = selectedDoc.docType.name,
                                rowId = rowId,
                                qty = qty,
                                doneCount = 0
                            )
                        },
```

- [ ] **Step 2: Compile check**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (this will fail until Task 1 is also in place — if run standalone here, confirm the only errors are unresolved reference errors that Task 1 already fixed; since Task 1 runs first in this plan, this should be a clean pass).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt
git commit -m "feat: wire classic cut list hold-to-complete/zero to progress store"
```

---

### Task 3: Add the `TallyStepButton` helper and wire it into Admin Board Stock rows

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt:1-95` (imports), `:1811-1812` (new composable), `:2067-2100` (Admin rows)

- [ ] **Step 1: Add missing imports**

At the top of `HardwoodsWorkspaceScreen.kt`, add these two imports (alongside the existing `androidx.compose.foundation.shape.RoundedCornerShape` at line 29, and existing `androidx.compose.ui.hapticfeedback.HapticFeedbackType` at line 94 — both files' import blocks are not sorted strictly, so append near the related existing import):

```kotlin
import androidx.compose.foundation.shape.CircleShape
```
(add directly below `import androidx.compose.foundation.shape.RoundedCornerShape` at line 29)

```kotlin
import androidx.compose.ui.graphics.vector.ImageVector
```
(add directly below `import androidx.compose.ui.graphics.Color` at line 68)

- [ ] **Step 2: Add the `TallyStepButton` private composable**

Insert this new composable in `HardwoodsWorkspaceScreen.kt` right after `MaterialSkipPill` ends (after line 1811, before `@Composable` / `private fun HardwoodsBoardStockList` at line 1813):

```kotlin
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TallyStepButton(
    icon: ImageVector,
    contentDescription: String,
    containerColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val backgroundColor = if (enabled) containerColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val iconTint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Surface(
        shape = CircleShape,
        color = backgroundColor,
        modifier = modifier
            .heightIn(min = 32.dp)
            .widthIn(min = 32.dp)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = iconTint, modifier = Modifier.size(14.dp))
        }
    }
}
```

- [ ] **Step 3: Replace the Admin Board Stock `+`/`-` `Button`s with `TallyStepButton`**

At `HardwoodsWorkspaceScreen.kt:2067-2100`, replace:

```kotlin
                                            Button(
                                                onClick = {
                                                    progressStore.decrementAdminBoardStockDone(
                                                        jobFolderName, material, item.id, maxCount = boards
                                                    )
                                                },
                                                enabled = !itemSkipped && done > 0,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = statusColors.bad,
                                                    contentColor = Color.White
                                                ),
                                                contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.heightIn(min = 32.dp).widthIn(min = 32.dp)
                                            ) { Icon(Icons.Default.Remove, contentDescription = "Done -", modifier = Modifier.size(14.dp)) }
                                            ProgressPill(
                                                done = done,
                                                total = boards,
                                                state = rowState,
                                                skippedFillColor = statusColors.completeBorder.copy(alpha = 0.52f)
                                            )
                                            Button(
                                                onClick = {
                                                    progressStore.incrementAdminBoardStockDone(
                                                        jobFolderName, material, item.id, maxCount = boards
                                                    )
                                                },
                                                enabled = !itemSkipped && done < boards,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = statusColors.completeBorder,
                                                    contentColor = Color.White
                                                ),
                                                contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.heightIn(min = 32.dp).widthIn(min = 32.dp)
                                            ) { Icon(Icons.Default.Add, contentDescription = "Done +", modifier = Modifier.size(14.dp)) }
```

with:

```kotlin
                                            TallyStepButton(
                                                icon = Icons.Default.Remove,
                                                contentDescription = "Done -",
                                                containerColor = statusColors.bad,
                                                enabled = !itemSkipped && done > 0,
                                                onClick = {
                                                    progressStore.decrementAdminBoardStockDone(
                                                        jobFolderName, material, item.id, maxCount = boards
                                                    )
                                                },
                                                onLongClick = {
                                                    progressStore.setAdminBoardStockDone(
                                                        jobFolderName, material, item.id, doneCount = 0
                                                    )
                                                }
                                            )
                                            ProgressPill(
                                                done = done,
                                                total = boards,
                                                state = rowState,
                                                skippedFillColor = statusColors.completeBorder.copy(alpha = 0.52f)
                                            )
                                            TallyStepButton(
                                                icon = Icons.Default.Add,
                                                contentDescription = "Done +",
                                                containerColor = statusColors.completeBorder,
                                                enabled = !itemSkipped && done < boards,
                                                onClick = {
                                                    progressStore.incrementAdminBoardStockDone(
                                                        jobFolderName, material, item.id, maxCount = boards
                                                    )
                                                },
                                                onLongClick = {
                                                    progressStore.setAdminBoardStockDone(
                                                        jobFolderName, material, item.id, doneCount = boards
                                                    )
                                                }
                                            )
```

(The `enabled` flag passed to `TallyStepButton` already excludes skipped and already-at-target cases, so `combinedClickable` never invokes `onLongClick` when the guard would otherwise reject it — no extra `if` needed inside these lambdas.)

- [ ] **Step 4: Compile check**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt
git commit -m "feat: add hold-to-complete/zero to admin board stock tally buttons"
```

---

### Task 4: Wire `TallyStepButton` into Rip Cut/Board Stock rows

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt:2336-2379`

Unlike the Admin rows, these buttons currently have **no** `enabled` expression (today they're always tappable; the store clamps internally). To avoid changing their existing always-on look, this task keeps `enabled = true` and puts the skip/bounds guard inside the long-press lambda instead.

- [ ] **Step 1: Replace the Rip Cut `+`/`-` `Button`s with `TallyStepButton`**

At `HardwoodsWorkspaceScreen.kt:2336-2379`, replace:

```kotlin
                                        Button(
                                            onClick = {
                                                progressStore.decrementBoardStockRipDone(
                                                    jobFolderName = jobFolderName,
                                                    material = line.material,
                                                    normalizedWidth = line.normalizedWidth,
                                                    source = line.source.name,
                                                    maxCount = line.neededRips
                                                )
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = statusColors.bad,
                                                contentColor = Color.White
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier
                                                .heightIn(min = 32.dp)
                                                .widthIn(min = 32.dp)
                                        ) { Icon(Icons.Default.Remove, contentDescription = "Rip done -", modifier = Modifier.size(14.dp)) }
                                        ProgressPill(
                                            done = done,
                                            total = line.neededRips,
                                            state = rowState,
                                            skippedFillColor = statusColors.completeBorder.copy(alpha = 0.52f)
                                        )
                                        Button(
                                            onClick = {
                                                progressStore.incrementBoardStockRipDone(
                                                    jobFolderName = jobFolderName,
                                                    material = line.material,
                                                    normalizedWidth = line.normalizedWidth,
                                                    source = line.source.name,
                                                    maxCount = line.neededRips
                                                )
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = statusColors.completeBorder,
                                                contentColor = Color.White
                                            ),
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier
                                                .heightIn(min = 32.dp)
                                                .widthIn(min = 32.dp)
                                        ) { Icon(Icons.Default.Add, contentDescription = "Rip done +", modifier = Modifier.size(14.dp)) }
```

with:

```kotlin
                                        val canDecrementRip = !lineSkipped && done > 0
                                        val canIncrementRip = !lineSkipped && done < line.neededRips
                                        TallyStepButton(
                                            icon = Icons.Default.Remove,
                                            contentDescription = "Rip done -",
                                            containerColor = statusColors.bad,
                                            enabled = true,
                                            onClick = {
                                                progressStore.decrementBoardStockRipDone(
                                                    jobFolderName = jobFolderName,
                                                    material = line.material,
                                                    normalizedWidth = line.normalizedWidth,
                                                    source = line.source.name,
                                                    maxCount = line.neededRips
                                                )
                                            },
                                            onLongClick = {
                                                if (canDecrementRip) {
                                                    progressStore.setBoardStockRipDone(
                                                        jobFolderName = jobFolderName,
                                                        material = line.material,
                                                        normalizedWidth = line.normalizedWidth,
                                                        source = line.source.name,
                                                        doneCount = 0
                                                    )
                                                }
                                            }
                                        )
                                        ProgressPill(
                                            done = done,
                                            total = line.neededRips,
                                            state = rowState,
                                            skippedFillColor = statusColors.completeBorder.copy(alpha = 0.52f)
                                        )
                                        TallyStepButton(
                                            icon = Icons.Default.Add,
                                            contentDescription = "Rip done +",
                                            containerColor = statusColors.completeBorder,
                                            enabled = true,
                                            onClick = {
                                                progressStore.incrementBoardStockRipDone(
                                                    jobFolderName = jobFolderName,
                                                    material = line.material,
                                                    normalizedWidth = line.normalizedWidth,
                                                    source = line.source.name,
                                                    maxCount = line.neededRips
                                                )
                                            },
                                            onLongClick = {
                                                if (canIncrementRip) {
                                                    progressStore.setBoardStockRipDone(
                                                        jobFolderName = jobFolderName,
                                                        material = line.material,
                                                        normalizedWidth = line.normalizedWidth,
                                                        source = line.source.name,
                                                        doneCount = line.neededRips
                                                    )
                                                }
                                            }
                                        )
```

(`lineSkipped` and `done` are already in scope at this point in the row's `items(...)` block — see `HardwoodsWorkspaceScreen.kt:2262-2264`.)

- [ ] **Step 2: Compile check**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run the existing unit test suite**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all existing tests pass (no store code changed, so `ProgressStoreTest` / `HardwoodsProgressStoreTest` results are unaffected — this just confirms nothing else broke).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt
git commit -m "feat: add hold-to-complete/zero to rip cut board stock tally buttons"
```

---

### Task 5: Wire `TallyStepButton` into List View rows (`HardwoodsPartRow`)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt` (`HardwoodsPartRow` private composable, ~line 1305-1574)

**Discovered during manual verification of Task 5 (now Task 6):** the Hardwoods workspace has a fourth tally UI that was missed during design — "List View" (`isClassicView = false`, the default view for Face Frame/Nailer/Door Cut Lists and Door List), rendered by `HardwoodsPartRow` inside the same `HardwoodsWorkspaceScreen.kt` file, entirely separate from `ClassicCutListTable.kt`. It has the same `+`/`-` `Button` pair pattern as the Rip Cut rows (Task 4): no `enabled` expression today (buttons are always tappable; the store clamps internally), and a pre-existing row-level long-press (`onJump`, jumps to reference PDF — same pattern as `ClassicCutListTable`'s `onRowLongPress`) that must not be disturbed.

- [ ] **Step 1: Replace the `+`/`-` `Button`s with `TallyStepButton`**

Find the non-Door-List branch's decrement/increment `Button`s in `HardwoodsPartRow` (search for `onDecrement()` / `onIncrement()` inside `haptic.performHapticFeedback` blocks, contentDescriptions `"Done -"` / `"Done +"`). Replace:

```kotlin
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDecrement()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = statusColors.bad, contentColor = Color.White),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .heightIn(min = 32.dp)
                            .widthIn(min = 32.dp)
                    ) { Icon(Icons.Default.Remove, contentDescription = "Done -", modifier = Modifier.size(14.dp)) }
                    ProgressPill(
                        done = done,
                        total = qty,
                        state = rowState.asProgressState(),
                        skippedFillColor = statusColors.completeBorder.copy(alpha = 0.52f),
                        modifier = Modifier
                    )
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onIncrement()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = statusColors.completeBorder, contentColor = Color.White),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .heightIn(min = 32.dp)
                            .widthIn(min = 32.dp)
                    ) { Icon(Icons.Default.Add, contentDescription = "Done +", modifier = Modifier.size(14.dp)) }
```

with:

```kotlin
                    val canDecrementPart = !visuals.skipOn && done > 0
                    val canIncrementPart = !visuals.skipOn && done < qty
                    TallyStepButton(
                        icon = Icons.Default.Remove,
                        contentDescription = "Done -",
                        containerColor = statusColors.bad,
                        enabled = true,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDecrement()
                        },
                        onLongClick = {
                            if (canDecrementPart) {
                                onZero()
                            }
                        }
                    )
                    ProgressPill(
                        done = done,
                        total = qty,
                        state = rowState.asProgressState(),
                        skippedFillColor = statusColors.completeBorder.copy(alpha = 0.52f),
                        modifier = Modifier
                    )
                    TallyStepButton(
                        icon = Icons.Default.Add,
                        contentDescription = "Done +",
                        containerColor = statusColors.completeBorder,
                        enabled = true,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onIncrement()
                        },
                        onLongClick = {
                            if (canIncrementPart) {
                                onComplete()
                            }
                        }
                    )
```

`enabled = true` is kept on both (mirrors Task 4's Rip Cut approach) since these buttons never had an `enabled` expression before this change — the guard lives inside `onLongClick` instead, using `visuals.skipOn` (the row's existing skip-state flag, already computed above in this composable) and `done`/`qty` (already in scope). Note this reuses the existing short-tap haptic call already present on these buttons (a pre-existing quirk of this composable — every button here already fires `HapticFeedbackType.LongPress` on regular tap, unrelated to this task; leave it as-is, don't "fix" it).

- [ ] **Step 2: Add `onComplete`/`onZero` parameters to `HardwoodsPartRow`'s signature**

At the top of `HardwoodsPartRow`'s parameter list (~line 1305-1318), add two new parameters after `onDecrement`:

```kotlin
private fun HardwoodsPartRow(
    rowUi: HardwoodsRowUiModel,
    qty: Int,
    progress: HardwoodRowProgress,
    revisionState: HardwoodRowRevisionState?,
    skippedCabs: Set<String>,
    isHighlighted: Boolean,
    widthBand: Color,
    isDoorListDoc: Boolean,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onComplete: () -> Unit,
    onZero: () -> Unit,
    onSkipToggle: () -> Unit,
    onJump: () -> Unit
) {
```

- [ ] **Step 3: Wire `onComplete`/`onZero` at the `HardwoodsPartRow` call site**

At the `HardwoodsPartRow(...)` call (~line 1189-1202), add two new lambdas after `onDecrement = onDecrement`, calling `hardwoodsProgressStore.setDoneCount` directly (same function Task 2 already uses for Classic View — this is the same doc-type-scoped row progress store used by List View's `onIncrement`/`onDecrement` just above it in the same scope):

```kotlin
                                    val onComplete = remember(row.rowId, qty, selectedDoc.docType.name, jobFolderName) {
                                        {
                                            hardwoodsProgressStore.setDoneCount(
                                                jobFolderName = jobFolderName,
                                                docType = selectedDoc.docType.name,
                                                rowId = row.rowId,
                                                qty = qty,
                                                doneCount = qty
                                            )
                                        }
                                    }
                                    val onZero = remember(row.rowId, qty, selectedDoc.docType.name, jobFolderName) {
                                        {
                                            hardwoodsProgressStore.setDoneCount(
                                                jobFolderName = jobFolderName,
                                                docType = selectedDoc.docType.name,
                                                rowId = row.rowId,
                                                qty = qty,
                                                doneCount = 0
                                            )
                                        }
                                    }
                                    HardwoodsPartRow(
                                        rowUi = rowUi,
                                        qty = qty,
                                        progress = progress,
                                        revisionState = rowRevisionStateMap[selectedDoc.docType.name to row.rowId],
                                        skippedCabs = skippedCabs,
                                        isHighlighted = isHighlighted,
                                        widthBand = widthBand,
                                        isDoorListDoc = isDoorListDoc,
                                        onIncrement = onIncrement,
                                        onDecrement = onDecrement,
                                        onComplete = onComplete,
                                        onZero = onZero,
                                        onSkipToggle = onSkipToggle,
                                        onJump = { startRowJump(row) }
                                    )
```

(Place the two new `remember { ... }` blocks right after the existing `onDecrement` `remember` block and before `onSkipToggle`'s, matching the existing style of `onIncrement`/`onDecrement` in this same scope — reuse `row.rowId`/`qty`/`selectedDoc.docType.name`/`jobFolderName`, don't redeclare them.)

**Note:** The Door List branch (`isDoorListDoc == true`) does not have `+`/`-` tally buttons at all (it shows a `ProgressPill` and an "Open Ref" button instead) — nothing to change there; `onComplete`/`onZero` are simply unused in that branch, which is fine (Kotlin doesn't warn on unused lambda parameters).

- [ ] **Step 4: Compile check**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Run the existing unit test suite**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all existing tests pass (no store code changed, so `ProgressStoreTest` / `HardwoodsProgressStoreTest` results are unaffected).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt
git commit -m "feat: add hold-to-complete/zero to list view tally buttons"
```

---

### Task 6: Manual on-device verification

**Files:** none (verification only)

- [ ] **Step 1: Build and install a debug build on the shop tablet**

Follow the `debug-android-tablet` skill to build and deploy a debug build to a connected tablet (per `CLAUDE.md`, local development uses `assembleDebug` + `adb install -r`, not the release script).

- [ ] **Step 2: Verify Classic Cut List view**

Open a job's hardwoods Classic Cut List. For a row with `0 < done < qty`:
- Hold the `+` icon → row jumps to `done == qty`, complete color/state, haptic buzz felt.
- Hold the `-` icon on a different row with `0 < done < qty` → row jumps to `done == 0`, haptic buzz felt.
- Hold `+` on a row already at `done == qty` → no visible change, no haptic (button is disabled at that state).
- Hold `-` or `+` on a skipped row → no visible change, no haptic.

- [ ] **Step 3: Verify Rip Cut / Board Stock list**

Switch to the Rip Cut List view. For a "Need N rips" row with `0 < done < N`:
- Hold `+` → jumps to `done == N`.
- Hold `-` → jumps to `done == 0`.
- Hold either on a skipped line, or on a line already at its target extreme → no change (regular tap still works normally in both cases, since `enabled = true` was kept for these buttons per Task 4).

- [ ] **Step 4: Verify Admin Board Stock list**

In the same Rip Cut List view, scroll to the Admin Board Stock section. For an item row with `0 < done < boards`:
- Hold `+` → jumps to `done == boards`, button now visually disabled (matches existing `enabled` dimming already present for this section).
- Hold `-` → jumps to `done == 0`, `-` button now visually disabled.
- Confirm a skipped item's buttons are already visually disabled and holding does nothing.

- [ ] **Step 5: Verify List View**

Switch a doc type (e.g. Face Frame Cut List) to List View (the default, non-Classic toggle). For a row with `0 < done < qty`:
- Hold `+` → jumps to `done == qty`.
- Hold `-` → jumps to `done == 0`.
- Hold either on a skipped line, or on a line already at its target extreme → no visible change (regular tap still works normally, since `enabled = true` was kept for these buttons per Task 5).
- Confirm Door List (a doc type with no `+`/`-` buttons, just "Open Ref") is unaffected — nothing to hold there, no crash.

- [ ] **Step 6: Regression check — short taps still work everywhere**

In all four areas (Classic Cut List, Rip Cut/Board Stock, Admin Board Stock, List View), tap (not hold) `+` and `-` a few times each and confirm single-step increment/decrement is unchanged from current production behavior.

- [ ] **Step 7: Regression check — unrelated long-press features still work**

In both the Classic Cut List view and List View, long-press on a row's *background* (not on a tally button) and confirm it still jumps to that row's location on the reference PDF (existing `onRowLongPress`/`onJump`/`startRowJump` behavior, untouched by this change).

---

## Notes for the implementer

- No `ProgressStore.kt` or `HardwoodsProgressStore.kt` changes in this plan — all three setters (`setDoneCount`, `setBoardStockRipDone`, `setAdminBoardStockDone`) already exist and are already covered by `ProgressStoreTest`/`HardwoodsProgressStoreTest`.
- `TallyStepButton` is intentionally private to `HardwoodsWorkspaceScreen.kt` and only used by the Admin and Rip Cut rows in that file — `ClassicCutListTable.kt`'s tally buttons are edited in place (Task 1) rather than extracted, since they already have distinct `trackTallyTarget` wiring that doesn't apply to the other two areas.
