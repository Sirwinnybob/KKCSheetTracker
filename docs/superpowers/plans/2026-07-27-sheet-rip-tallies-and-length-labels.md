# Sheet Rip Tallies and Length Labels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Display each rip item's configured length, add Sheet tally controls only in the Hardwoods Saw Rip List, and keep the Specialty checkbox synchronized with that tally.

**Architecture:** The existing admin-board-stock tally in HardwoodsProgressStore is the canonical count. sheet_rip_done.json stays a Boolean compatibility projection and legacy fallback. A pure resolver clamps and combines the two values so both screens agree without a new metadata file.

**Tech Stack:** Kotlin, Jetpack Compose, StateFlow, atomic JSON storage, JUnit4, Gradle Android tests.

## Global Constraints

- Only HARDWOODS_SAW_RIP_LIST_ROW_ID exposes Sheet tally controls.
- No Sheet row exposes item or material Skip controls, including mixed unit groups.
- The regular Hardwoods Rip List keeps Sheet rows display-only.
- sheet_rip_done.json stays Boolean and Android-owned; do not add a second progress file.
- Positive whole-number ripLength values display as configured; invalid metadata already resolves to 10.
- Do not install, deploy, or uninstall an APK.

---

## File Structure

| File | Responsibility |
| --- | --- |
| app/src/main/java/com/kkc/sheettracker/data/SheetRipTally.kt | Pure count, legacy fallback, and completion resolver. |
| app/src/main/java/com/kkc/sheettracker/data/SpecialtyStateStore.kt | Specialty bridge to canonical tally and Boolean projection. |
| app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt | Saw-only Sheet controls, no-Skip rules, and Hardwood length text. |
| app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt | Tally-derived checkbox and Specialty length text. |
| app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt | Supplies SheetRipProgressStore to both Hardwood routes. |
| app/src/test/java/com/kkc/sheettracker/data/SheetRipTallyTest.kt | Resolver tests. |
| app/src/test/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsRowHelpersTest.kt | Saw-only control, Skip, and length-label tests. |
| app/src/test/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreenLogicTest.kt | Specialty completion and label tests. |

### Task 1: Canonical Sheet tally resolver and Specialty bridge

**Files:**
- Create: app/src/main/java/com/kkc/sheettracker/data/SheetRipTally.kt
- Modify: app/src/main/java/com/kkc/sheettracker/data/SpecialtyStateStore.kt:1-105
- Create: app/src/test/java/com/kkc/sheettracker/data/SheetRipTallyTest.kt
- Modify: app/src/test/java/com/kkc/sheettracker/data/SpecialtyStateStoreTest.kt

**Interfaces:**
- Produces SheetRipTallyState(done: Int, target: Int) with isComplete.
- Produces resolveSheetRipTallyState(storedDone: Int?, legacyDone: Boolean, target: Int): SheetRipTallyState.
- Produces SpecialtyStateStore.hardwoodsProgressVersion, getSheetRipStoredDoneCount(jobFolderName, item), and setSheetRipCompletion(jobFolderName, item, target, completed).

- [ ] **Step 1: Write the failing resolver tests**

Create SheetRipTallyTest.kt:

~~~kotlin
@Test fun resolve_usesLegacyOnlyWithoutTally() {
    assertEquals(SheetRipTallyState(3, 3), resolveSheetRipTallyState(null, true, 3))
    assertEquals(SheetRipTallyState(0, 3), resolveSheetRipTallyState(0, true, 3))
}

@Test fun resolve_clampsToZeroAndTarget() {
    assertEquals(SheetRipTallyState(0, 2), resolveSheetRipTallyState(-1, false, 2))
    assertEquals(SheetRipTallyState(2, 2), resolveSheetRipTallyState(9, false, 2))
}
~~~

- [ ] **Step 2: Run the new test to verify it fails**

Run: .\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.data.SheetRipTallyTest

Expected: compilation fails because the resolver types do not exist.

- [ ] **Step 3: Implement the pure resolver**

Create SheetRipTally.kt:

~~~kotlin
package com.kkc.sheettracker.data

data class SheetRipTallyState(val done: Int, val target: Int) {
    val isComplete: Boolean get() = target > 0 && done >= target
}

fun resolveSheetRipTallyState(storedDone: Int?, legacyDone: Boolean, target: Int): SheetRipTallyState {
    val normalizedTarget = target.coerceAtLeast(0)
    val done = when {
        storedDone != null -> storedDone.coerceIn(0, normalizedTarget)
        legacyDone -> normalizedTarget
        else -> 0
    }
    return SheetRipTallyState(done, normalizedTarget)
}
~~~

- [ ] **Step 4: Write failing StateStore bridge coverage**

In SpecialtyStateStoreTest.kt, use a Sheet AdminBoardStockItem("sheet-crown", "Maple", "Crown", 18.0, mode = "sheet") and add:

~~~kotlin
stateStore.setSheetRipCompletion(jobFolderName, item, target = 2, completed = true)
assertEquals(2, stateStore.getSheetRipStoredDoneCount(jobFolderName, item))
assertTrue(sheetRipStore.loadDone(jobFolderName)[item.id] == true)
stateStore.setSheetRipCompletion(jobFolderName, item, target = 2, completed = false)
assertEquals(0, stateStore.getSheetRipStoredDoneCount(jobFolderName, item))
assertFalse(sheetRipStore.loadDone(jobFolderName)[item.id] == true)
~~~

- [ ] **Step 5: Implement the StateStore bridge**

Expose the Hardwood StateFlow and add:

~~~kotlin
val hardwoodsProgressVersion: StateFlow<Long>
    get() = hardwoodsProgressStore.progressVersion

fun getSheetRipStoredDoneCount(jobFolderName: String, item: AdminBoardStockItem): Int? {
    val key = hardwoodsProgressStore.makeAdminBoardStockTallyKey(item.material, item.id)
    return hardwoodsProgressStore.getTotalsRip10DoneMap(jobFolderName)[key]
}

suspend fun setSheetRipCompletion(jobFolderName: String, item: AdminBoardStockItem, target: Int, completed: Boolean) = withContext(ioDispatcher) {
    val done = if (completed) target.coerceAtLeast(0) else 0
    hardwoodsProgressStore.setAdminBoardStockDone(jobFolderName, item.material, item.id, done)
    sheetRipProgressStore.setDone(jobFolderName, item.id, completed && target > 0)
    _sheetRipDoneVersion.value++
}
~~~

Keep loadSheetRipDone as the legacy Boolean reader.

- [ ] **Step 6: Run focused data tests**

Run: .\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.data.SheetRipTallyTest --tests com.kkc.sheettracker.data.SpecialtyStateStoreTest --tests com.kkc.sheettracker.data.SheetRipProgressStoreTest

Expected: all selected tests pass.

- [ ] **Step 7: Commit Task 1**

~~~powershell
git add app/src/main/java/com/kkc/sheettracker/data/SheetRipTally.kt app/src/main/java/com/kkc/sheettracker/data/SpecialtyStateStore.kt app/src/test/java/com/kkc/sheettracker/data/SheetRipTallyTest.kt app/src/test/java/com/kkc/sheettracker/data/SpecialtyStateStoreTest.kt
git commit -m "feat: synchronize sheet rip tally progress"
~~~

### Task 2: Add Saw-only Sheet tallies and Hardwood length text

**Files:**
- Modify: app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt:297-308,333-346,2558-2746
- Modify: app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:1595-1622,2727-2754
- Modify: app/src/test/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsRowHelpersTest.kt

**Interfaces:**
- Consumes resolveSheetRipTallyState from Task 1.
- Produces showsHardwoodsBoardStockTallyControls(item, isSawRipEntry), allowsHardwoodsBoardStockSkip(item), and hardwoodsBoardStockRequirementLabel(boards, ripLength, feet).

- [ ] **Step 1: Write failing Hardwood helper tests**

In HardwoodsRowHelpersTest.kt, assert:

~~~kotlin
assertTrue(showsHardwoodsBoardStockTallyControls(sheetCrown, true))
assertFalse(showsHardwoodsBoardStockTallyControls(sheetCrown, false))
assertTrue(showsHardwoodsBoardStockTallyControls(boardCrown, false))
assertFalse(allowsHardwoodsBoardStockSkip(sheetCrown))
assertTrue(allowsHardwoodsBoardStockSkip(boardCrown))
assertEquals("Need 2 x 9 ft boards · 12 ft", hardwoodsBoardStockRequirementLabel(2, 9, 12.0))
assertEquals("Need 2 x 12 ft boards · 18 ft", hardwoodsBoardStockRequirementLabel(2, 12, 18.0))
assertFalse(hardwoodsEffectiveMaterialSkipped(listOf(sheetCrown), true))
~~~

- [ ] **Step 2: Run the helper test to verify it fails**

Run: .\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.hardwoods.HardwoodsRowHelpersTest

Expected: compilation fails because the new helper signatures and label helper do not exist.

- [ ] **Step 3: Implement mode-aware control and Skip predicates**

Use:

~~~kotlin
internal fun showsHardwoodsBoardStockTallyControls(item: AdminBoardStockItem, isSawRipEntry: Boolean): Boolean =
    !item.mode.equals("sheet", ignoreCase = true) || isSawRipEntry

internal fun allowsHardwoodsBoardStockSkip(item: AdminBoardStockItem): Boolean =
    !item.mode.equals("sheet", ignoreCase = true)

internal fun hardwoodsBoardStockRequirementLabel(boards: Int, ripLength: Int, feet: Double): String =
    "Need $boards x $ripLength ft boards · ${feet.toInt()} ft"
~~~

Update all material and item Skip state, as well as header Skip visibility, to use allowsHardwoodsBoardStockSkip rather than tally eligibility.

- [ ] **Step 4: Inject legacy Boolean storage**

Add sheetRipProgressStore: SheetRipProgressStore to HardwoodsWorkspaceScreen and pass the existing store from both NavGraph routes. Load its Boolean map through produceState keyed by jobFolderName and progressVersion.

- [ ] **Step 5: Implement interactive Sheet Saw rows**

For a Sheet row only when isSawRipEntry is true, resolve the existing admin tally key with resolveSheetRipTallyState(storedDone, sheetRipDone[item.id] == true, boards). Render its progress pill and row state from that value.

For every decrement, increment, and long-press action calculate a value from zero through boards, then apply:

~~~kotlin
progressStore.setAdminBoardStockDone(jobFolderName, material, item.id, nextDone)
scope.launch { sheetRipProgressStore.setDone(jobFolderName, item.id, nextDone >= boards && boards > 0) }
~~~

Use zero for a decrement hold and boards for an increment hold. Keep MaterialSkipPill, row Skip, and SKIPPED controls guarded by allowsHardwoodsBoardStockSkip; they must not appear for Sheet rows.

- [ ] **Step 6: Render the Hardwood configured-length text**

Replace the current Need boards text with:

~~~kotlin
Text(hardwoodsBoardStockRequirementLabel(boards, item.ripLength, item.feet), ...)
~~~

Retain the NONE branch and Crown unit label.

- [ ] **Step 7: Run focused verification and commit**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.hardwoods.HardwoodsRowHelpersTest
git diff --check
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/test/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsRowHelpersTest.kt
git commit -m "feat: tally sheet rips in saw list"
~~~

Expected: focused test passes, diff check is clean, and the commit contains only this task.

### Task 3: Keep Specialty checklist simple but tally-derived

**Files:**
- Modify: app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt:148-172,338-456,1101-1105
- Modify: app/src/test/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreenLogicTest.kt

**Interfaces:**
- Consumes Task 1 StateStore APIs and resolveSheetRipTallyState.
- Produces specialtySheetRipLengthLabel(rips, ripLength): String.

- [ ] **Step 1: Write failing Specialty logic tests**

Add:

~~~kotlin
assertEquals("2 rips x 9 ft", specialtySheetRipLengthLabel(2, 9))
assertEquals("3 rips x 12 ft", specialtySheetRipLengthLabel(3, 12))
assertTrue(resolveSheetRipTallyState(2, false, 2).isComplete)
assertFalse(resolveSheetRipTallyState(1, true, 2).isComplete)
~~~

- [ ] **Step 2: Run the Specialty test to verify it fails**

Run: .\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.specialty.SpecialtyJobDetailScreenLogicTest

Expected: compilation fails because specialtySheetRipLengthLabel does not exist.

- [ ] **Step 3: Derive the checkbox from the tally**

Collect SpecialtyStateStore.hardwoodsProgressVersion in addition to sheetRipDoneVersion. For each item calculate:

~~~kotlin
val target = Math.ceil((item.feet ?: 0.0) / item.ripLength).toInt().coerceAtLeast(0)
val tally = resolveSheetRipTallyState(
    specialtyStateStore.getSheetRipStoredDoneCount(jobFolderName, item),
    sheetRipDone[item.id] == true,
    target
)
val isDone = tally.isComplete
~~~

Use isDone for header count, row alpha, row click, and Checkbox state.

- [ ] **Step 4: Make both checkbox paths set the canonical tally**

Replace direct setSheetRipDone calls with:

~~~kotlin
specialtyStateStore.setSheetRipCompletion(jobFolderName, item, target, completed = next)
~~~

Row click passes next = !isDone. Preserve only checkbox interaction on this screen; do not add plus/minus controls.

- [ ] **Step 5: Show the Specialty configured length**

Define:

~~~kotlin
internal fun specialtySheetRipLengthLabel(rips: Int, ripLength: Int): String =
    "$rips rips x $ripLength ft"
~~~

Replace the existing rip count text with this helper and retain the bold total-feet line.

- [ ] **Step 6: Run focused verification and commit**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.specialty.SpecialtyJobDetailScreenLogicTest --tests com.kkc.sheettracker.data.SpecialtyStateStoreTest
git add app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt app/src/test/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreenLogicTest.kt
git commit -m "feat: link sheet rip checklist to tallies"
~~~

Expected: focused tests pass and Specialty Job Detail remains checkbox-only.

### Task 4: Verify the completed Android slice

**Files:**
- Verify: every file changed by Tasks 1-3.

**Interfaces:**
- Consumes completed Task 1-3 APIs.
- Produces verified source/build status; no deployment artifact is copied or installed.

- [ ] **Step 1: Run all Android unit tests**

Run: .\gradlew.bat :app:testDebugUnitTest

Expected: BUILD SUCCESSFUL with zero failed tests.

- [ ] **Step 2: Build a clean release**

Run: .\gradlew.bat :app:assembleRelease --rerun-tasks --no-build-cache

Expected: BUILD SUCCESSFUL. Report whether output is signed; do not copy, install, or deploy it.

- [ ] **Step 3: Inspect scope and whitespace**

~~~powershell
git diff --check HEAD~3..HEAD
git status --short
~~~

Expected: no diff-check output and no unexpected uncommitted files.

- [ ] **Step 4: Manual smoke only with explicit authorization**

On a non-production test job with a 9 ft Sheet item and target greater than one: confirm Saw list label and tally; confirm no Sheet Skip control; confirm Specialty stays unchecked for a partial tally; check Specialty and confirm tally completes; then confirm the regular Hardwoods Rip List stays display-only. If live installation or progress mutation is not authorized, record this manual gate as unperformed.

## Plan Self-Review

- Coverage: Task 1 creates a shared count plus Boolean compatibility; Task 2 adds Saw-only tally/no-Skip and Hardwood labels; Task 3 derives the checkbox and adds Specialty labels; Task 4 verifies source and build output.
- Placeholder scan: no TODO/TBD text or unspecified test expectations remain.
- Type consistency: Task 1 defines each resolver/StateStore API later tasks consume, and Tasks 2 and 3 define their tested label helpers.
