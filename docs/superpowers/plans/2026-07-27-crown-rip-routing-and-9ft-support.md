# Crown Rip Routing and Flexible Stock-Length Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route Crown stock correctly between Specialty and Hardwoods, make Sheet rows non-tallyable in Hardwoods, and preserve any positive stock length from shared board-stock metadata.

**Architecture:** Hours Tracker remains the owner of each job's `board_stock.json`; this Android slice only reads its existing `mode`, `type`, `moldingId`, and `ripLength` fields. Small pure helpers in the two screen files make routing, unit labels, and tally eligibility deterministic and testable; UI code only consumes those helpers.

**Tech Stack:** Kotlin, Jetpack Compose Material3, Gson, JUnit4 plain unit tests.

## Global Constraints

- Shared source file: `Y:\Ready Jobs\<job>\.metadata\admin\board_stock.json`; Hours Tracker owns authoring and KKCSheetTracker reads it.
- Any positive whole-number `ripLength` is valid; absent, blank, zero, negative, and malformed values retain the legacy fallback of `10`.
- `mode == "sheet"` is Specialty's interactive sheet-rip source. `mode == "bd_ft"` remains the normal Hardwoods board-foot source; Crown is additionally visible in Hardwoods for either mode.
- A Crown item is recognized by case-insensitive `type == "crown"`, with `moldingId` beginning `Crown:` as the legacy fallback.
- Sheet rows have no Hardwoods tally or skip controls and must not write Hardwoods progress. Specialty remains their interactive completion surface.
- Do not migrate existing JSON, change `sheet_rip_done.json`, or modify Hours Tracker in this repository.

---

### Task 1: Accept flexible board-stock metadata

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/AdminBoardStockStore.kt:31-41`
- Modify: `app/src/test/java/com/kkc/sheettracker/data/AdminBoardStockStoreTest.kt`

**Interfaces:**
- Produces: `AdminBoardStockItem.ripLength` equal to any positive JSON integer.
- Preserves: zero, negative, unsupported, and absent lengths deserialize as `10`.

- [ ] **Step 1: Write the failing parser regression test**

```kotlin
@Test
fun loadAdminBoardStock_preservesPositiveRipLengths() {
    val baseDir = writeBoardStock(
        """{"schemaVersion":1,"items":[
          {"id":"nine","material":"Maple","name":"Crown","feet":18.0,"ripLength":9},
          {"id":"twelve","material":"Maple","name":"Long","feet":24.0,"ripLength":12},
          {"id":"bad","material":"Maple","name":"Bad","feet":18.0,"ripLength":0}
        ]}"""
    )

    val items = loadAdminBoardStock(baseDir, "123 - Test Job")

    assertEquals(9, items.single { it.id == "nine" }.ripLength)
    assertEquals(12, items.single { it.id == "twelve" }.ripLength)
    assertEquals(10, items.single { it.id == "bad" }.ripLength)
    assertEquals(2, Math.ceil(items.single { it.id == "nine" }.feet!! / items.single { it.id == "nine" }.ripLength).toInt())
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.data.AdminBoardStockStoreTest`

Expected: FAIL because the parser accepts only `8` and `10`, so both positive non-preset lengths fall back to `10`.

- [ ] **Step 3: Make the minimal parser change**

Replace the hard-coded `8 || 10` acceptance check with a positive-integer check:

```kotlin
val ripLength = obj.get("ripLength")?.takeIf { !it.isJsonNull }?.asInt
    ?.takeIf { it > 0 }
    ?: 10
```

- [ ] **Step 4: Run the focused parser tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.data.AdminBoardStockStoreTest`

Expected: PASS, including the `9`, `12`, and invalid-fallback assertions.

- [ ] **Step 5: Commit the parser slice**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/AdminBoardStockStore.kt app/src/test/java/com/kkc/sheettracker/data/AdminBoardStockStoreTest.kt
git commit -m "feat: accept flexible board stock lengths"
```

### Task 2: Make Crown routing and Sheet tally eligibility explicit

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt:277-285,475-487,2534-2707`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt:167-171,1089-1099`
- Modify: `app/src/test/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsRowHelpersTest.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreenLogicTest.kt`

**Interfaces:**
- Produces in Hardwoods:
  - `isCrownAdminBoardStockItem(item): Boolean`
  - `hardwoodsBoardStockUnitLabel(item): String?` (`"Sheet"`/`"BD FT"` only for Crown)
  - `showsHardwoodsBoardStockTallyControls(item): Boolean` (`false` for every Sheet row)
  - `isVisibleInHardwoodsRipList(item, isSawRipEntry): Boolean`
- Produces in Specialty: `specialtySheetRipItems(items): List<AdminBoardStockItem>`.
- Consumes: `AdminBoardStockItem.mode`, `type`, and `moldingId`; it does not modify item/progress files.

- [ ] **Step 1: Write failing routing and display tests**

Add to `HardwoodsRowHelpersTest`:

```kotlin
@Test
fun hardwoodsRipRouting_keepsCrownInBothUnitsButMakesSheetReadOnly() {
    val sheetCrown = AdminBoardStockItem("c1", "Maple", "Crown", 18.0, mode = "sheet", type = "crown")
    val boardCrown = AdminBoardStockItem("c2", "Maple", "Crown", 18.0, mode = "bd_ft", type = "crown")

    assertTrue(isVisibleInHardwoodsRipList(sheetCrown, isSawRipEntry = false))
    assertTrue(isVisibleInHardwoodsRipList(boardCrown, isSawRipEntry = false))
    assertEquals("Sheet", hardwoodsBoardStockUnitLabel(sheetCrown))
    assertEquals("BD FT", hardwoodsBoardStockUnitLabel(boardCrown))
    assertFalse(showsHardwoodsBoardStockTallyControls(sheetCrown))
    assertTrue(showsHardwoodsBoardStockTallyControls(boardCrown))
}
```

Add to `SpecialtyJobDetailScreenLogicTest`:

```kotlin
@Test
fun specialtySheetRipItems_includesOnlySheetModeCrown() {
    val sheetCrown = AdminBoardStockItem("sheet", "Maple", "Crown", 18.0, mode = "sheet", type = "crown")
    val boardCrown = AdminBoardStockItem("board", "Maple", "Crown", 18.0, mode = "bd_ft", type = "crown")

    assertEquals(listOf(sheetCrown), specialtySheetRipItems(listOf(sheetCrown, boardCrown)))
}
```

- [ ] **Step 2: Run the focused tests to verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.hardwoods.HardwoodsRowHelpersTest --tests com.kkc.sheettracker.ui.specialty.SpecialtyJobDetailScreenLogicTest`

Expected: FAIL because the new helpers do not exist.

- [ ] **Step 3: Implement the pure helpers and use them for loading**

Implement case-insensitive `sheet`/`bd_ft` checks and the Crown legacy fallback. Replace the current inline filters so:

```kotlin
fun isVisibleInHardwoodsRipList(item: AdminBoardStockItem, isSawRipEntry: Boolean): Boolean =
    if (isSawRipEntry) item.mode.equals("sheet", ignoreCase = true)
    else item.mode.equals("bd_ft", ignoreCase = true) || isCrownAdminBoardStockItem(item)

fun specialtySheetRipItems(items: List<AdminBoardStockItem>): List<AdminBoardStockItem> =
    items.filter { it.mode.equals("sheet", ignoreCase = true) && it.feet != null && it.feet > 0 }
```

Use `specialtySheetRipItems(loadAdminBoardStock(...))` at Specialty loading time and `isVisibleInHardwoodsRipList` at Hardwoods loading time.

- [ ] **Step 4: Render unit labels and remove Sheet controls**

In the existing `AdminBoardStockContent` row, add the `Sheet` or `BD FT` label beside the Crown item details. Gate the complete row of decrement button, progress pill, increment button, and item skip control behind `showsHardwoodsBoardStockTallyControls(item)`. Do not call any `HardwoodsProgressStore` tally/skip mutation for a Sheet item.

Keep the molding preview button and feet/rip informational text visible for both units. Leave `NONE` handling unchanged.

- [ ] **Step 5: Run the focused routing tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.hardwoods.HardwoodsRowHelpersTest --tests com.kkc.sheettracker.ui.specialty.SpecialtyJobDetailScreenLogicTest`

Expected: PASS.

- [ ] **Step 6: Commit the routing slice**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt app/src/test/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsRowHelpersTest.kt app/src/test/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreenLogicTest.kt
git commit -m "feat: route crown rips by unit"
```

### Task 3: Verify the Android slice

**Files:**
- Verify only the files changed in Tasks 1-2.

- [ ] **Step 1: Run all Android unit tests**

Run: `./gradlew.bat :app:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 2: Build the signed release artifact**

Run: `./gradlew.bat :app:assembleRelease --rerun-tasks --no-build-cache`

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/release/app-release.apk` exists.

- [ ] **Step 3: Inspect the final diff**

Run: `git diff HEAD~2..HEAD --check`

Expected: no whitespace errors; no changes to progress-file ownership or updater feed files.

- [ ] **Step 4: Perform tablet smoke check**

Use a job with a `Sheet` Crown and a `BD FT` Crown. Verify: Specialty lists only the Sheet Crown; Hardwoods displays both labels; Sheet rows have no tally/skip controls; BD FT Crown retains the existing tally controls; 9-foot and non-preset positive lengths report `ceil(feet / ripLength)` rips.
