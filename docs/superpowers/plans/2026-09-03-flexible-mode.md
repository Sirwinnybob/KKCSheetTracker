# Flexible Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Flexible Mode" Settings toggle that replaces the tablet's fixed `WorkMode` on the Dashboard and Jobs screens with a live header mode-switcher, so a tablet can preview/enter any tracker (CNC/Hardwoods/Assembly/Specialty on Jobs; CNC/Hardwoods on Dashboard) without a trip to Settings.

**Architecture:** `flexibleModeEnabled: Boolean` is threaded through the exact same call chain `workMode: WorkMode` already follows (`MainActivity` → `AppNavigation` → `MultiBackStackNavigation`/`LegacySingleStackNavigation` → `DashboardTabHost`/`JobsTabHost`/`SettingsTabHost` → `SettingsScreen`). When true, the Dashboard and Jobs `composable("dashboard")`/`composable("jobs")` blocks (each duplicated once for `MultiBackStackNavigation` and once inline in `LegacySingleStackNavigation`) build all of that screen's mode specs unconditionally instead of one, hold local mode-selection state, and render a new `ModeSwitcherRow` composable in place of/alongside the existing header controls. Tapping a job still uses that mode's existing, untouched `onJobClick` — no new navigation code.

**Tech Stack:** Kotlin, Jetpack Compose, Compose Navigation, SharedPreferences (`kkc_tracker`), JUnit4 (JVM unit tests only — this repo has no instrumented/Compose UI test harness; navigation-wiring correctness is verified with source-text assertions matching the existing `LegacyStandardsTransitionWiringTest` pattern).

**Reference spec:** [docs/superpowers/specs/2026-09-03-flexible-mode-design.md](../specs/2026-09-03-flexible-mode-design.md)

---

## Task A: `ModeSwitcherRow` composable

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/ModeSwitcherRow.kt`

No test — this repo has no Compose UI/instrumented test harness (`app/src/androidTest` doesn't exist), and this composable has no non-visual logic to unit test. Verified manually in Task H.

- [ ] **Step 1: Create the composable**

A plain `@Composable` function, **not** a `RowScope` extension — every call site in this plan (Tasks D, F, G) invokes it unqualified as `ModeSwitcherRow(modes = ..., selected = ..., onSelect = ...)` from inside a `RowScope` lambda (a `TopAppBar` `actions` block, or a `topBarActions` lambda), which only compiles if `ModeSwitcherRow` takes no receiver — a `RowScope.ModeSwitcherRow(...)` extension would require an explicit receiver (`someRowScope.ModeSwitcherRow(...)`) at every call site instead.

```kotlin
package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.navigation.WorkMode

private fun WorkMode.shortLabel(): String = when (this) {
    WorkMode.CNC -> "CNC"
    WorkMode.HARDWOODS -> "HW"
    WorkMode.ASSEMBLY -> "ASM"
    WorkMode.SPECIALTY -> "SPC"
}

/**
 * Compact toggle-chip row for a TopAppBar `actions` slot, letting the operator switch which
 * WorkMode a Dashboard/Jobs screen is currently showing. Distinct from the larger
 * WorkModeIconTile grid used in Settings.
 */
@Composable
fun ModeSwitcherRow(
    modes: List<WorkMode>,
    selected: WorkMode,
    onSelect: (WorkMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        modes.forEach { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = { Text(mode.shortLabel()) }
            )
        }
    }
}
```

- [ ] **Step 2: Compile-check**

```bash
.\gradlew.bat app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ModeSwitcherRow.kt
git commit -m "feat: add ModeSwitcherRow header toggle composable"
```

---

## Task B: Remove the Jobs screen sort button (independent cleanup)

Unconditional cleanup requested alongside Flexible Mode — the sort-order toggle ("nobody uses it, everyone uses production order") is removed regardless of whether Flexible Mode is on, freeing header space the mode switcher needs.

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreenSortRemovalTest.kt`

- [ ] **Step 1: Write the failing wiring test**

```kotlin
package com.kkc.sheettracker.ui.jobs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class UnifiedJobsScreenSortRemovalTest {
    @Test
    fun sortByNameStateAndSortButtonAreGone() {
        val source = unifiedJobsScreenSource()
        assertFalse("sortByName state must be removed", source.contains("sortByName"))
        assertFalse("Sort icon import must be removed", source.contains("automirrored.filled.Sort"))
        assertFalse("SortByAlpha icon import must be removed", source.contains("filled.SortByAlpha"))
    }

    private fun unifiedJobsScreenSource(): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt")
            if (candidate.exists()) return candidate.readText()
            val direct = File(dir, "src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Unable to locate UnifiedJobsScreen.kt from ${System.getProperty("user.dir")}")
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
.\gradlew.bat app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.jobs.UnifiedJobsScreenSortRemovalTest"
```
Expected: FAIL — `sortByName state must be removed` (the string is still present).

- [ ] **Step 3: Remove the two now-unused icon imports**

In `UnifiedJobsScreen.kt`, delete these two lines:
```kotlin
import androidx.compose.material.icons.automirrored.filled.Sort
```
```kotlin
import androidx.compose.material.icons.filled.SortByAlpha
```

- [ ] **Step 4: Remove the `sortByName` state declaration**

Old:
```kotlin
    var sortByName by rememberSaveable { mutableStateOf(false) }
    var boardView by rememberSaveable { mutableStateOf(uiPrefs.getBoardView(spec.modeName.lowercase())) }
```
New:
```kotlin
    var boardView by rememberSaveable { mutableStateOf(uiPrefs.getBoardView(spec.modeName.lowercase())) }
```

- [ ] **Step 5: Drop `sortByName` from the admin-mode reset and remove the sort/board LaunchedEffect**

Old:
```kotlin
    LaunchedEffect(adminMode) {
        if (adminMode) {
            query = TextFieldValue("")
            sortByName = false
            boardView = false
        }
    }
    LaunchedEffect(sortByName) { if (sortByName) boardView = false }
```
New:
```kotlin
    LaunchedEffect(adminMode) {
        if (adminMode) {
            query = TextFieldValue("")
            boardView = false
        }
    }
```

- [ ] **Step 6: Drop `sortByName` from card derivation (always production order)**

Old:
```kotlin
    val cards = remember(scanGeneration, progressVersion, sortByName, localJobEdits) {
        val all = spec.deriveJobCards().map { card ->
            val edit = localJobEdits[card.folderName]
            if (edit != null) {
                val newBoardSection = edit.boardSection ?: card.boardSection
                val newIsPending = newBoardSection == 1
                val newBadges = card.badges.toMutableSet()
                if (newIsPending) newBadges.add(JobBadge.PENDING_DELIVERY) else newBadges.remove(JobBadge.PENDING_DELIVERY)
                card.copy(
                    labels = edit.labels ?: card.labels,
                    boardSection = newBoardSection,
                    isPending = newIsPending,
                    badges = newBadges
                )
            } else {
                card
            }
        }
        if (sortByName) {
            all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
        } else {
            all
        }
    }
```
New:
```kotlin
    val cards = remember(scanGeneration, progressVersion, localJobEdits) {
        spec.deriveJobCards().map { card ->
            val edit = localJobEdits[card.folderName]
            if (edit != null) {
                val newBoardSection = edit.boardSection ?: card.boardSection
                val newIsPending = newBoardSection == 1
                val newBadges = card.badges.toMutableSet()
                if (newIsPending) newBadges.add(JobBadge.PENDING_DELIVERY) else newBadges.remove(JobBadge.PENDING_DELIVERY)
                card.copy(
                    labels = edit.labels ?: card.labels,
                    boardSection = newBoardSection,
                    isPending = newIsPending,
                    badges = newBadges
                )
            } else {
                card
            }
        }
    }
```

- [ ] **Step 7: Remove the sort `IconButton` and simplify the board-view button's `enabled` guard**

Old:
```kotlin
                    IconButton(
                        onClick = { if (!adminMode) sortByName = !sortByName },
                        enabled = !adminMode
                    ) {
                        Icon(
                            imageVector = if (sortByName) Icons.Default.SortByAlpha else Icons.AutoMirrored.Filled.Sort,
                            contentDescription = if (sortByName) "Sort: A–Z Name" else "Sort: Production Order"
                        )
                    }
                    IconButton(
                        onClick = {
                            if (!sortByName) {
                                boardView = !boardView
                                uiPrefs.setBoardView(spec.modeName.lowercase(), boardView)
                            }
                        },
                        enabled = !sortByName && !adminMode
                    ) {
```
New:
```kotlin
                    IconButton(
                        onClick = {
                            boardView = !boardView
                            uiPrefs.setBoardView(spec.modeName.lowercase(), boardView)
                        },
                        enabled = !adminMode
                    ) {
```

- [ ] **Step 8: Drop `sortByName` from the `AnimatedContent` target state**

Old:
```kotlin
            AnimatedContent(
                targetState = sortByName to boardView,
                transitionSpec = {
                    if (lowEndMode.animationsDisabled) {
                        fadeIn(snap()) togetherWith fadeOut(snap())
                    } else {
                        val dir = if (targetState.first) 1 else -1
                        slideInHorizontally { it * dir } togetherWith slideOutHorizontally { -it * dir }
                    }
                },
                label = "sort_anim"
            ) { (_, isBoardView) ->
```
New:
```kotlin
            AnimatedContent(
                targetState = boardView,
                transitionSpec = {
                    if (lowEndMode.animationsDisabled) {
                        fadeIn(snap()) togetherWith fadeOut(snap())
                    } else {
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    }
                },
                label = "sort_anim"
            ) { isBoardView ->
```

(The direction the board-view/list-view transition previously slid was keyed off `sortByName`, which is gone; it now always slides the same direction — a cosmetic detail only, not user-visible as a bug since board/list toggling was always a simple flip.)

- [ ] **Step 9: Remove the 5 `sortByName = sortByName,` arguments passed to `UnifiedJobCard`**

Delete the line `sortByName = sortByName,` from each of the 5 `UnifiedJobCard(...)` call sites in this file (in the board-view grid's active/pending items, and the list-view's pinned/active/pending items). `UnifiedJobCard`'s `sortByName` parameter already defaults to `false` ([UnifiedJobCard.kt:59](../../../app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobCard.kt)), so omitting the argument is equivalent to the new always-production-order behavior — no change needed in `UnifiedJobCard.kt` itself.

- [ ] **Step 10: Run the test again to verify it passes**

```bash
.\gradlew.bat app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.jobs.UnifiedJobsScreenSortRemovalTest"
```
Expected: PASS.

- [ ] **Step 11: Compile-check the whole module**

```bash
.\gradlew.bat app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL` (confirms no other file still references `sortByName` on `UnifiedJobCard` or this screen).

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt app/src/test/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreenSortRemovalTest.kt
git commit -m "refactor: remove unused Jobs sort-order toggle, always sort production order"
```

---

## Task C: Thread `flexibleModeEnabled` end-to-end (Settings persistence + toggle UI)

Adds the pref, threads a new `flexibleModeEnabled: Boolean` parameter through the **same chain `workMode` already follows**, and exposes it as a Settings toggle. After this task, turning the toggle on/off persists correctly, even though Dashboard/Jobs don't yet react to it (that's Tasks E and G).

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/navigation/FlexibleModeWiringTest.kt`

- [ ] **Step 1: Write the failing wiring test**

```kotlin
package com.kkc.sheettracker.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FlexibleModeWiringTest {
    @Test
    fun flexibleModeEnabledReachesEveryFunctionWorkModeReaches() {
        val source = navGraphSource()

        val functionsRequiringParam = listOf(
            "fun AppNavigation(",
            "private fun MultiBackStackNavigation(",
            "private fun LegacySingleStackNavigation(",
            "private fun DashboardTabHost(",
            "private fun JobsTabHost(",
            "private fun SettingsTabHost("
        )
        functionsRequiringParam.forEach { signature ->
            val start = source.indexOf(signature)
            assertTrue("$signature not found", start >= 0)
            val paramBlockEnd = source.indexOf(") {", start).let { if (it < 0) source.indexOf("){", start) else it }
            assertTrue("closing paren for $signature not found", paramBlockEnd > start)
            val paramBlock = source.substring(start, paramBlockEnd)
            assertTrue(
                "$signature must declare flexibleModeEnabled: Boolean",
                paramBlock.contains("flexibleModeEnabled: Boolean")
            )
        }
    }

    @Test
    fun settingsScreenCallSitesForwardFlexibleModeEnabled() {
        val source = navGraphSource()
        val settingsScreenCallCount = Regex("SettingsScreen\\(").findAll(source).count()
        val forwardedCount = Regex("flexibleModeEnabled = flexibleModeEnabled").findAll(source).count()
        assertTrue(
            "expected at least as many flexibleModeEnabled forwards ($forwardedCount) as SettingsScreen( calls ($settingsScreenCallCount) plus the AppNavigation/MultiBackStack/Legacy chain",
            forwardedCount >= settingsScreenCallCount
        )
    }

    private fun navGraphSource(): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt")
            if (candidate.exists()) return candidate.readText()
            val direct = File(dir, "src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Unable to locate NavGraph.kt from ${System.getProperty("user.dir")}")
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
.\gradlew.bat app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.FlexibleModeWiringTest"
```
Expected: FAIL — none of the signatures declare `flexibleModeEnabled` yet.

- [ ] **Step 3: `SettingsScreen.kt` — add the parameters**

Old ([SettingsScreen.kt:48-59](../../../app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt)):
```kotlin
fun SettingsScreen(
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    followSystemTheme: Boolean = true,
    darkThemeOverride: Boolean = false,
    workMode: WorkMode,
    onThemeChanged: (Boolean) -> Unit,
    onFollowSystemThemeChanged: (Boolean) -> Unit = {},
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onTabletIdChanged: (String) -> Unit,
```
New:
```kotlin
fun SettingsScreen(
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    followSystemTheme: Boolean = true,
    darkThemeOverride: Boolean = false,
    workMode: WorkMode,
    flexibleModeEnabled: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    onFollowSystemThemeChanged: (Boolean) -> Unit = {},
    onWorkModeChanged: (WorkMode) -> Unit,
    onFlexibleModeChanged: (Boolean) -> Unit,
    onReinstallLatest: () -> Unit,
    onTabletIdChanged: (String) -> Unit,
```

- [ ] **Step 4: `SettingsScreen.kt` — add the toggle row UI**

Old ([SettingsScreen.kt:229-237](../../../app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt)):
```kotlin
                WorkModeIconTile(
                    label = "Specialty",
                    isSelected = workMode == WorkMode.SPECIALTY,
                    onClick = { onWorkModeChanged(WorkMode.SPECIALTY) },
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Appearance ───────────────────────────────────────────────
```
New:
```kotlin
                WorkModeIconTile(
                    label = "Specialty",
                    isSelected = workMode == WorkMode.SPECIALTY,
                    onClick = { onWorkModeChanged(WorkMode.SPECIALTY) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Flexible Mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Show a mode switcher on Dashboard and Jobs instead of locking to Work Mode above",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = flexibleModeEnabled,
                    onCheckedChange = onFlexibleModeChanged
                )
            }

            // ── Appearance ───────────────────────────────────────────────
```

`Row`, `Column`, `Text`, `MaterialTheme`, `Switch`, `Alignment`, `Modifier` are all already imported/used elsewhere in this file (`androidx.compose.foundation.layout.*` and `androidx.compose.material3.*` wildcard imports at the top) — no new imports needed.

- [ ] **Step 5: `NavGraph.kt` — add `flexibleModeEnabled: Boolean` to the 6 function signatures**

Insert `flexibleModeEnabled: Boolean,` immediately after the `workMode: WorkMode,` line in each of these 6 signatures (identify each by the unique lines shown — several signatures share the surrounding `workMode: WorkMode,\n    employeeName: String,` pattern, so use the full block shown, including the function name above it, as the match):

**`AppNavigation`** (top-level, `fun AppNavigation(`):
```kotlin
    continuousScrollDefault: Boolean,
    workMode: WorkMode,
    employeeName: String,
    onEmployeeNameChanged: (String) -> Unit,
    clockInState: ClockInState,
    onThemeChanged: (Boolean) -> Unit,
    onFollowSystemThemeChanged: (Boolean) -> Unit,
    onUseStandardSheetsChanged: (Boolean) -> Unit,
    onContinuousScrollDefaultChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onBasePathChanged: (String) -> Unit,
    onTabletIdChanged: (String) -> Unit,
    syncthingApiKey: String,
    syncthingStatus: SyncthingStatusUiState,
    onSyncthingApiKeySave: (String) -> Unit,
    onSyncthingCheckNow: () -> Unit,
    onSyncthingStartNow: () -> Unit,
    supplySubscriptionManager: SupplySubscriptionManager,
    themeCatalog: KKCThemeCatalog,
    onThemeFollowSyncedDefaultChanged: (Boolean) -> Unit,
    onThemeOverrideChanged: (String?) -> Unit,
    onThemeCatalogReload: () -> Unit,
    hardwoodsProgressStore: HardwoodsProgressStore? = null,
    specialtyProgressStore: SpecialtyProgressStore? = null
) {
```
This exact 27-line block is unique to `AppNavigation` (it's the only signature ending in `specialtyProgressStore: SpecialtyProgressStore? = null\n) {`). Insert `flexibleModeEnabled: Boolean,` as the new second line (right after `workMode: WorkMode,`).

**`MultiBackStackNavigation`** — the block from `workMode: WorkMode,` through `onReinstallLatest: () -> Unit,` is identical to `AppNavigation`'s but this function's own signature ends differently. Anchor on the function name plus its own `workMode` line:
```kotlin
private fun MultiBackStackNavigation(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    hardwoodsProgressStore: HardwoodsProgressStore,
    specialtyProgressStore: SpecialtyProgressStore,
    appStateFlags: AppStateFeatureFlags,
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isViewOnlyMode: Boolean,
    isDarkTheme: Boolean,
    followSystemTheme: Boolean,
    darkThemeOverride: Boolean,
    useStandardSheets: Boolean,
    continuousScrollDefault: Boolean,
    workMode: WorkMode,
    employeeName: String,
```
Insert `flexibleModeEnabled: Boolean,` after `workMode: WorkMode,` — this whole block (starting at `private fun MultiBackStackNavigation(`) is unique.

**`LegacySingleStackNavigation`** — same technique, anchor on the function name:
```kotlin
private fun LegacySingleStackNavigation(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    hardwoodsProgressStore: HardwoodsProgressStore,
    specialtyProgressStore: SpecialtyProgressStore,
    appStateFlags: AppStateFeatureFlags,
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isViewOnlyMode: Boolean,
    isDarkTheme: Boolean,
    followSystemTheme: Boolean,
    darkThemeOverride: Boolean,
    useStandardSheets: Boolean,
    continuousScrollDefault: Boolean,
    workMode: WorkMode,
    employeeName: String,
```
Insert `flexibleModeEnabled: Boolean,` after `workMode: WorkMode,`.

**`DashboardTabHost`**:
```kotlin
private fun DashboardTabHost(
    navController: NavHostController,
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    appStateFlags: AppStateFeatureFlags,
    workMode: WorkMode,
    hardwoodsScanCoordinator: HardwoodsScanCoordinator,
```
Insert `flexibleModeEnabled: Boolean,` after `workMode: WorkMode,`.

**`JobsTabHost`**:
```kotlin
private fun JobsTabHost(
    navController: NavHostController,
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    appStateFlags: AppStateFeatureFlags,
    adminSyncConfig: AdminSyncConfig,
    isDarkTheme: Boolean,
    cncSheetIsDarkTheme: Boolean,
    useStandardSheets: Boolean,
    continuousScrollDefault: Boolean = false,
    workMode: WorkMode,
    hardwoodsRepository: HardwoodsRepository,
```
Insert `flexibleModeEnabled: Boolean,` after `workMode: WorkMode,`.

**`SettingsTabHost`**:
```kotlin
private fun SettingsTabHost(
    navController: NavHostController,
    tabletId: String,
    basePath: String,
    isDebugBuild: Boolean,
    isDarkTheme: Boolean,
    followSystemTheme: Boolean,
    darkThemeOverride: Boolean,
    useStandardSheets: Boolean,
    continuousScrollDefault: Boolean,
    workMode: WorkMode,
    employeeName: String,
    onEmployeeNameChanged: (String) -> Unit,
    onThemeChanged: (Boolean) -> Unit,
    onFollowSystemThemeChanged: (Boolean) -> Unit,
    onUseStandardSheetsChanged: (Boolean) -> Unit,
    onContinuousScrollDefaultChanged: (Boolean) -> Unit,
    onWorkModeChanged: (WorkMode) -> Unit,
    onReinstallLatest: () -> Unit,
    onTabletIdChanged: (String) -> Unit,
```
Insert `flexibleModeEnabled: Boolean,` after `workMode: WorkMode,`, and also insert `onFlexibleModeChanged: (Boolean) -> Unit,` right after `onWorkModeChanged: (WorkMode) -> Unit,` in this same block (this is the only one of the 6 signatures that needs the callback too, since it's the one that ultimately calls `SettingsScreen`).

- [ ] **Step 6: `NavGraph.kt` — thread the argument at each call site**

Add `flexibleModeEnabled = flexibleModeEnabled,` immediately after each existing `workMode = workMode,` line at these 7 call sites inside `NavGraph.kt` (each identified by unique surrounding lines):

1. `AppNavigation` → `MultiBackStackNavigation` — inside the `if (flags.navMultiStackEnabled) { MultiBackStackNavigation(` block, after `workMode = workMode,` / before `employeeName = employeeName,`.
2. `AppNavigation` → `LegacySingleStackNavigation` — inside the `} else { LegacySingleStackNavigation(` block, same pattern.
3. `MultiBackStackNavigation` → `DashboardTabHost` — the call starting `DashboardTabHost(\n    navController = dashboardNavController,`.
4. `MultiBackStackNavigation` → `JobsTabHost` — the call starting `JobsTabHost(\n    navController = jobsNavController,`.
5. `MultiBackStackNavigation` → `SettingsTabHost` — the call starting `SettingsTabHost(\n    navController = settingsNavController,`.
6. `SettingsTabHost`'s own body → `SettingsScreen` — add both `flexibleModeEnabled = flexibleModeEnabled,` after `workMode = workMode,` **and** `onFlexibleModeChanged = onFlexibleModeChanged,` after `onWorkModeChanged = onWorkModeChanged,`.
7. `LegacySingleStackNavigation`'s own inline `composable("settings")` → `SettingsScreen` — same two additions as #6 (this is the `SettingsScreen(...)` call that also passes `uiPreferencesStore = UiPreferencesStore(LocalContext.current)` — use that as the unique anchor for this specific call site vs. #6's).

Do **not** touch: `SearchTabHost`, `StandardsTabHost`, `ArchiveLibraryHost`, or `ArchiveJobDetailHost` — these also take `workMode: WorkMode` but are unrelated to this feature (Search/Standards/Archive routing).

- [ ] **Step 7: `MainActivity.kt` — add the pref, state, and callback**

Old ([MainActivity.kt:307-311](../../../app/src/main/java/com/kkc/sheettracker/MainActivity.kt)):
```kotlin
            var workMode by remember {
                mutableStateOf(
                    WorkMode.fromStored(prefs.getString("work_mode", null))
                )
            }
```
New:
```kotlin
            var workMode by remember {
                mutableStateOf(
                    WorkMode.fromStored(prefs.getString("work_mode", null))
                )
            }
            var flexibleModeEnabled by remember {
                mutableStateOf(prefs.getBoolean("flexible_mode_enabled", false))
            }
```

Old ([MainActivity.kt:370](../../../app/src/main/java/com/kkc/sheettracker/MainActivity.kt), inside the `AppNavigation(...)` call):
```kotlin
                        workMode = workMode,
                        employeeName = employeeName,
```
New:
```kotlin
                        workMode = workMode,
                        flexibleModeEnabled = flexibleModeEnabled,
                        employeeName = employeeName,
```

Old ([MainActivity.kt:394-398](../../../app/src/main/java/com/kkc/sheettracker/MainActivity.kt)):
```kotlin
                        onWorkModeChanged = { mode ->
                            workMode = mode
                            prefs.edit().putString("work_mode", mode.name).apply()
                            CrashReporter.updateContext(workMode = mode.name)
                        },
```
New:
```kotlin
                        onWorkModeChanged = { mode ->
                            workMode = mode
                            prefs.edit().putString("work_mode", mode.name).apply()
                            CrashReporter.updateContext(workMode = mode.name)
                        },
                        onFlexibleModeChanged = { enabled ->
                            flexibleModeEnabled = enabled
                            prefs.edit().putBoolean("flexible_mode_enabled", enabled).apply()
                        },
```

- [ ] **Step 8: Run the wiring test again to verify it passes**

```bash
.\gradlew.bat app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.FlexibleModeWiringTest"
```
Expected: PASS.

- [ ] **Step 9: Compile-check the whole module**

```bash
.\gradlew.bat app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. This is the step that actually proves every one of the 7 call sites in Step 6 was found and updated — a missed call site fails Kotlin's required-parameter check with a clear "no value passed for parameter flexibleModeEnabled" error naming the exact call.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/MainActivity.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt app/src/test/java/com/kkc/sheettracker/navigation/FlexibleModeWiringTest.kt
git commit -m "feat: add Flexible Mode setting, threaded end-to-end alongside Work Mode"
```

---

## Task D: `UnifiedJobsScreen` — accept and render the mode switcher

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt`

- [ ] **Step 1: Add the new parameters**

Old ([UnifiedJobsScreen.kt:155-169](../../../app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt), post Task B):
```kotlin
fun UnifiedJobsScreen(
    spec: UnifiedJobsSpec,
    jobRepository: JobRepository,
    deliverySchedule: DeliverySchedule,
    onDeliveryScheduleApplied: (DeliverySchedule) -> Unit,
    basePath: String,
    tabletId: String,
    isDebugBuild: Boolean,
    pinnedFolderNames: List<String> = emptyList(),
    onTogglePin: (folderName: String, isCurrentlyPinned: Boolean) -> Unit = { _, _ -> },
    onJobClick: (UnifiedJobUiModel) -> Unit,
    onOpenHardwoodsChange: ((folderName: String, docType: HardwoodDocType, rowId: String) -> Unit)? = null,
    onSearchClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    active: Boolean = true
) {
```
New:
```kotlin
fun UnifiedJobsScreen(
    spec: UnifiedJobsSpec,
    jobRepository: JobRepository,
    deliverySchedule: DeliverySchedule,
    onDeliveryScheduleApplied: (DeliverySchedule) -> Unit,
    basePath: String,
    tabletId: String,
    isDebugBuild: Boolean,
    pinnedFolderNames: List<String> = emptyList(),
    onTogglePin: (folderName: String, isCurrentlyPinned: Boolean) -> Unit = { _, _ -> },
    onJobClick: (UnifiedJobUiModel) -> Unit,
    onOpenHardwoodsChange: ((folderName: String, docType: HardwoodDocType, rowId: String) -> Unit)? = null,
    onSearchClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    active: Boolean = true,
    flexibleModeEnabled: Boolean = false,
    selectedFlexMode: com.kkc.sheettracker.navigation.WorkMode? = null,
    onFlexModeSelected: ((com.kkc.sheettracker.navigation.WorkMode) -> Unit)? = null
) {
```

(Using the fully-qualified `com.kkc.sheettracker.navigation.WorkMode` here matches this file's existing style of fully-qualifying cross-package types inline, e.g. `com.kkc.sheettracker.ui.jobs.UnifiedJobsScreen` is called fully-qualified from `NavGraph.kt`; add a plain `import com.kkc.sheettracker.navigation.WorkMode` instead if you prefer — either compiles.)

- [ ] **Step 2: Render the switcher in the TopAppBar actions, in the space the sort button (Task B) freed**

Old (post Task B, [UnifiedJobsScreen.kt actions block](../../../app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt)):
```kotlin
                actions = {
                    if (restoreActionVisible(adminEnabled = adminMode)) {
                        TextButton(onClick = { showRestoreArchivedJobSheet = true }) {
                            Text("Restore")
                        }
                    }
                    RefreshIconButton(
                        loading = scanStatus == ScanStatus.LOADING,
                        onClick = { spec.refresh(RefreshReason.USER_REFRESH, force = true) }
                    )
                    IconButton(
```
New:
```kotlin
                actions = {
                    if (restoreActionVisible(adminEnabled = adminMode)) {
                        TextButton(onClick = { showRestoreArchivedJobSheet = true }) {
                            Text("Restore")
                        }
                    }
                    if (flexibleModeEnabled && selectedFlexMode != null && onFlexModeSelected != null) {
                        com.kkc.sheettracker.ui.components.ModeSwitcherRow(
                            modes = com.kkc.sheettracker.navigation.WorkMode.entries,
                            selected = selectedFlexMode,
                            onSelect = onFlexModeSelected
                        )
                    }
                    RefreshIconButton(
                        loading = scanStatus == ScanStatus.LOADING,
                        onClick = { spec.refresh(RefreshReason.USER_REFRESH, force = true) }
                    )
                    IconButton(
```

- [ ] **Step 3: Compile-check**

```bash
.\gradlew.bat app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. All existing callers use the new params' defaults (`false`/`null`/`null`), so no other call site needs changes yet.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt
git commit -m "feat: UnifiedJobsScreen renders ModeSwitcherRow when Flexible Mode is active"
```

---

## Task E: Wire the Jobs switcher into NavGraph (both `composable("jobs")` copies)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/navigation/FlexibleModeWiringTest.kt` (extend)

- [ ] **Step 1: Extend the wiring test**

Add to `FlexibleModeWiringTest.kt`:
```kotlin
    @Test
    fun jobsComposableBuildsAllFourSpecsWhenFlexible() {
        val source = navGraphSource()
        val occurrences = Regex("if \\(flexibleModeEnabled\\)").findAll(source).count()
        assertTrue("expected at least 2 flexibleModeEnabled branches in the jobs composables (one per duplicate)", occurrences >= 2)
        val rememberCalls = listOf(
            "rememberCncJobsSpec(",
            "rememberHardwoodsJobsSpec(",
            "rememberAssemblyJobsSpec(",
            "rememberSpecialtyJobsSpec("
        )
        rememberCalls.forEach { call ->
            val count = Regex(Regex.escape(call)).findAll(source).count()
            assertTrue("$call should now appear at least 4 times (2 unconditional builds x 2 duplicated jobs composables, plus original)", count >= 4)
        }
    }
```

- [ ] **Step 2: Run it to verify it fails**

```bash
.\gradlew.bat app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.FlexibleModeWiringTest"
```
Expected: FAIL — `jobsComposableBuildsAllFourSpecsWhenFlexible` fails (no `if (flexibleModeEnabled)` branch exists yet).

- [ ] **Step 3: Replace `JobsTabHost`'s `composable("jobs")` block**

This is the copy inside `JobsTabHost` (used by `MultiBackStackNavigation`), using `coroutineScope` and `assemblyViewerDefaultsStore`. Replace the entire block from `composable("jobs") {` through the closing of the `UnifiedJobsScreen(...)` call (i.e. everything shown in the "Read" excerpt spanning what is currently lines ~1392-1490) with:

```kotlin
        composable("jobs") {
            val cncSpec = com.kkc.sheettracker.ui.jobs.rememberCncJobsSpec(
                scanCoordinator = scanCoordinator,
                appStateStore = appStateStore,
                progressStore = progressStore,
                jobRepository = jobRepository,
                hardwoodsRepository = hardwoodsRepository,
                engine = unifiedEngine,
                coroutineScope = coroutineScope,
                onJobClick = { jobFolder ->
                    navController.navigate("job/${java.net.URLEncoder.encode(jobFolder, "UTF-8")}") { launchSingleTop = true }
                },
                onView3D = { jobFolder ->
                    val target = resolveDefaultThreeDTarget(File(basePath), jobRepository, jobFolder)
                    navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = target.assemblyPage, plansPage = target.plansPage, source = "3d", room = target.room)) { launchSingleTop = true }
                },
                onViewCoverSheet = { jobFolder ->
                    navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                }
            )
            val hardwoodsSpec = com.kkc.sheettracker.ui.jobs.rememberHardwoodsJobsSpec(
                scanCoordinator = hardwoodsScanCoordinator,
                hardwoodsRepository = hardwoodsRepository,
                progressStore = hardwoodsProgressStore,
                jobRepository = jobRepository,
                engine = unifiedEngine,
                coroutineScope = coroutineScope,
                onJobClick = { jobFolder ->
                    navController.navigate("hardwoods/job/${java.net.URLEncoder.encode(jobFolder, "UTF-8")}") { launchSingleTop = true }
                },
                onView3D = { jobFolder ->
                    val target = resolveDefaultThreeDTarget(File(basePath), jobRepository, jobFolder)
                    navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = target.assemblyPage, plansPage = target.plansPage, source = "3d", room = target.room)) { launchSingleTop = true }
                },
                onViewCoverSheet = { jobFolder ->
                    navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                }
            )
            val assemblySpec = com.kkc.sheettracker.ui.jobs.rememberAssemblyJobsSpec(
                assemblyScanCoordinator = assemblyScanCoordinator,
                assemblyStateStore = assemblyStateStore,
                jobRepository = jobRepository,
                engine = unifiedEngine,
                progressStore = progressStore,
                hardwoodsProgressStore = hardwoodsProgressStore,
                coroutineScope = coroutineScope,
                onJobClick = { jobFolder ->
                    coroutineScope.launch {
                        val d = assemblyViewerDefaultsStore.current()
                        navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = 1, plansPage = 1, layout = d.layout, firstPane = d.firstPane, secondPane = d.secondPane, hideUiOnOpen = d.hideUiOnOpen)) { launchSingleTop = true }
                    }
                },
                onView3D = { jobFolder -> }, // not used
                onViewCoverSheet = { jobFolder ->
                    navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                }
            )
            val specialtySpec = com.kkc.sheettracker.ui.jobs.rememberSpecialtyJobsSpec(
                specialtyScanCoordinator = specialtyScanCoordinator,
                specialtyStateStore = specialtyStateStore,
                jobRepository = jobRepository,
                engine = unifiedEngine,
                coroutineScope = coroutineScope,
                onJobClick = { jobFolder ->
                    navController.navigate(specialtyJobRoute(jobFolder)) { launchSingleTop = true }
                },
                onView3D = { jobFolder ->
                    val room = resolveSpecialtyThreeDRoom(File(basePath), jobFolder)
                    if (room != null) {
                        navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = 1, plansPage = 1, source = "3d", room = room)) { launchSingleTop = true }
                    }
                },
                onViewCoverSheet = { jobFolder ->
                    navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                }
            )

            var flexMode by remember { mutableStateOf(workMode) }
            val spec = if (flexibleModeEnabled) {
                when (flexMode) {
                    WorkMode.CNC -> cncSpec
                    WorkMode.HARDWOODS -> hardwoodsSpec
                    WorkMode.ASSEMBLY -> assemblySpec
                    WorkMode.SPECIALTY -> specialtySpec
                }
            } else {
                when (workMode) {
                    WorkMode.CNC -> cncSpec
                    WorkMode.HARDWOODS -> hardwoodsSpec
                    WorkMode.ASSEMBLY -> assemblySpec
                    WorkMode.SPECIALTY -> specialtySpec
                }
            }

            com.kkc.sheettracker.ui.jobs.UnifiedJobsScreen(
                spec = spec,
                jobRepository = jobRepository,
                deliverySchedule = deliverySchedule,
                onDeliveryScheduleApplied = onDeliveryScheduleApplied,
                basePath = basePath,
                tabletId = tabletId,
                isDebugBuild = isDebugBuild,
                pinnedFolderNames = pinnedFolderNames,
                onTogglePin = onTogglePin,
                onJobClick = { model -> model.onCardClick() },
                onOpenHardwoodsChange = { jobFolderName, docType, rowId ->
                    navController.navigate(hardwoodsWorkspaceRoute(jobFolderName, docType, rowId)) {
                        launchSingleTop = true
                    }
                },
                onSearchClick = onSearchClick,
                onSettingsClick = onSettingsClick,
                active = jobsListActive,
                flexibleModeEnabled = flexibleModeEnabled,
                selectedFlexMode = flexMode,
                onFlexModeSelected = { flexMode = it }
            )
        }
```

Note: this always builds all 4 specs now (not just when `flexibleModeEnabled`) — per the spec doc, this is intentionally cheap (each `remember*JobsSpec` derives from the same already-cached `engine.getCachedJobInfos()`, no new scan work), and keeping it unconditional avoids a second near-duplicate branch. The `active = jobsListActive` line (defined earlier in `JobsTabHost` as `val jobsListActive = active && isJobsListRoute(jobsBackStack?.destination?.route)`) is unchanged by this edit.

- [ ] **Step 4: Replace `LegacySingleStackNavigation`'s inline `composable("jobs")` block**

This copy does not receive `onSearchClick`/`onSettingsClick` as parameters the way `JobsTabHost` does — it inlines `navController.navigate("search"/"settings")` directly, and it uses `legacyCoroutineScope`/`legacyAssemblyViewerDefaultsStore` instead of `coroutineScope`/`assemblyViewerDefaultsStore`. Do **not** reuse Step 3's parameter-reference style for `onSearchClick`/`onSettingsClick`/`active` here. Replace the entire second `composable("jobs") { val spec = when (workMode) { ... } ... UnifiedJobsScreen(...) }` block (in `LegacySingleStackNavigation`) with:

```kotlin
                    composable("jobs") {
                        val cncSpec = com.kkc.sheettracker.ui.jobs.rememberCncJobsSpec(
                            scanCoordinator = scanCoordinator,
                            appStateStore = appStateStore,
                            progressStore = progressStore,
                            jobRepository = jobRepository,
                            hardwoodsRepository = hardwoodsRepository,
                            engine = unifiedEngine,
                            coroutineScope = legacyCoroutineScope,
                            onJobClick = { jobFolder ->
                                navController.navigate("job/${java.net.URLEncoder.encode(jobFolder, "UTF-8")}") { launchSingleTop = true }
                            },
                            onView3D = { jobFolder ->
                                val target = resolveDefaultThreeDTarget(File(basePath), jobRepository, jobFolder)
                                navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = target.assemblyPage, plansPage = target.plansPage, source = "3d", room = target.room)) { launchSingleTop = true }
                            },
                            onViewCoverSheet = { jobFolder ->
                                navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                            }
                        )
                        val hardwoodsSpec = com.kkc.sheettracker.ui.jobs.rememberHardwoodsJobsSpec(
                            scanCoordinator = hardwoodsScanCoordinator,
                            hardwoodsRepository = hardwoodsRepository,
                            progressStore = hardwoodsProgressStore,
                            jobRepository = jobRepository,
                            engine = unifiedEngine,
                            coroutineScope = legacyCoroutineScope,
                            onJobClick = { jobFolder ->
                                navController.navigate("hardwoods/job/${java.net.URLEncoder.encode(jobFolder, "UTF-8")}") { launchSingleTop = true }
                            },
                            onView3D = { jobFolder ->
                                val target = resolveDefaultThreeDTarget(File(basePath), jobRepository, jobFolder)
                                navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = target.assemblyPage, plansPage = target.plansPage, source = "3d", room = target.room)) { launchSingleTop = true }
                            },
                            onViewCoverSheet = { jobFolder ->
                                navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                            }
                        )
                        val assemblySpec = com.kkc.sheettracker.ui.jobs.rememberAssemblyJobsSpec(
                            assemblyScanCoordinator = assemblyScanCoordinator,
                            assemblyStateStore = assemblyStateStore,
                            jobRepository = jobRepository,
                            engine = unifiedEngine,
                            progressStore = progressStore,
                            hardwoodsProgressStore = hardwoodsProgressStore,
                            coroutineScope = legacyCoroutineScope,
                            onJobClick = { jobFolder ->
                                legacyCoroutineScope.launch {
                                    val d = legacyAssemblyViewerDefaultsStore.current()
                                    navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = 1, plansPage = 1, layout = d.layout, firstPane = d.firstPane, secondPane = d.secondPane, hideUiOnOpen = d.hideUiOnOpen)) { launchSingleTop = true }
                                }
                            },
                            onView3D = { jobFolder -> }, // not used
                            onViewCoverSheet = { jobFolder ->
                                navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                            }
                        )
                        val specialtySpec = com.kkc.sheettracker.ui.jobs.rememberSpecialtyJobsSpec(
                            specialtyScanCoordinator = specialtyScanCoordinator,
                            specialtyStateStore = specialtyStateStore,
                            jobRepository = jobRepository,
                            engine = unifiedEngine,
                            coroutineScope = legacyCoroutineScope,
                            onJobClick = { jobFolder ->
                                navController.navigate(specialtyJobRoute(jobFolder)) { launchSingleTop = true }
                            },
                            onView3D = { jobFolder ->
                                val room = resolveSpecialtyThreeDRoom(File(basePath), jobFolder)
                                if (room != null) {
                                    navController.navigate(assemblyViewerRoute(jobFolderName = jobFolder, assemblyPage = 1, plansPage = 1, source = "3d", room = room)) { launchSingleTop = true }
                                }
                            },
                            onViewCoverSheet = { jobFolder ->
                                navController.navigate(referenceViewerRoute(jobFolder, ReferenceDocType.DELIVERY_SHEETS, 1)) { launchSingleTop = true }
                            }
                        )

                        var flexMode by remember { mutableStateOf(workMode) }
                        val spec = if (flexibleModeEnabled) {
                            when (flexMode) {
                                WorkMode.CNC -> cncSpec
                                WorkMode.HARDWOODS -> hardwoodsSpec
                                WorkMode.ASSEMBLY -> assemblySpec
                                WorkMode.SPECIALTY -> specialtySpec
                            }
                        } else {
                            when (workMode) {
                                WorkMode.CNC -> cncSpec
                                WorkMode.HARDWOODS -> hardwoodsSpec
                                WorkMode.ASSEMBLY -> assemblySpec
                                WorkMode.SPECIALTY -> specialtySpec
                            }
                        }

                        com.kkc.sheettracker.ui.jobs.UnifiedJobsScreen(
                            spec = spec,
                            jobRepository = jobRepository,
                            deliverySchedule = deliverySchedule,
                            onDeliveryScheduleApplied = onDeliveryScheduleApplied,
                            basePath = basePath,
                            tabletId = tabletId,
                            isDebugBuild = isDebugBuild,
                            pinnedFolderNames = pinnedFolderNames,
                            onTogglePin = onTogglePin,
                            onJobClick = { model -> model.onCardClick() },
                            onOpenHardwoodsChange = { jobFolderName, docType, rowId ->
                                navController.navigate(hardwoodsWorkspaceRoute(jobFolderName, docType, rowId)) {
                                    launchSingleTop = true
                                }
                            },
                            onSearchClick = { navController.navigate("search") { launchSingleTop = true } },
                            onSettingsClick = { navController.navigate("settings") { launchSingleTop = true } },
                            active = currentNavDest == NavDestination.JOBS && isJobsListRoute(currentRoute),
                            flexibleModeEnabled = flexibleModeEnabled,
                            selectedFlexMode = flexMode,
                            onFlexModeSelected = { flexMode = it }
                        )
                    }
```

- [ ] **Step 5: Run the wiring test again to verify it passes**

```bash
.\gradlew.bat app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.FlexibleModeWiringTest"
```
Expected: PASS.

- [ ] **Step 6: Compile-check**

```bash
.\gradlew.bat app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/test/java/com/kkc/sheettracker/navigation/FlexibleModeWiringTest.kt
git commit -m "feat: Jobs screen switches mode live when Flexible Mode is on"
```

---

## Task F: `UnifiedModeDashboardScreen` — accept a mode switcher for CNC/Hardwoods

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt`

- [ ] **Step 1: Add the `RowScope` import**

Add near the other `androidx.compose.foundation.layout.*` imports:
```kotlin
import androidx.compose.foundation.layout.RowScope
```

- [ ] **Step 2: Add `modeSwitcher` to the `Cnc` and `Hardwoods` spec variants**

Old ([UnifiedModeDashboardScreen.kt:101-117](../../../app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt)):
```kotlin
sealed interface UnifiedModeDashboardSpec {
    data class Cnc(
        val scanCoordinator: ScanCoordinator,
        val appStateStore: AppStateStore,
        val jobRepository: JobRepository,
        val progressStore: ProgressStore,
        val appStateFlags: AppStateFeatureFlags,
        val onNavigateToJobs: () -> Unit,
        val onOpenSheet: (jobFolderName: String, pdfFilename: String, page: Int) -> Unit
    ) : UnifiedModeDashboardSpec

    data class Hardwoods(
        val scanCoordinator: HardwoodsScanCoordinator,
        val progressStore: HardwoodsProgressStore,
        val liveEngine: UnifiedMetadataEngine,
        val onOpenJob: (HardwoodJob) -> Unit
    ) : UnifiedModeDashboardSpec
```
New:
```kotlin
sealed interface UnifiedModeDashboardSpec {
    data class Cnc(
        val scanCoordinator: ScanCoordinator,
        val appStateStore: AppStateStore,
        val jobRepository: JobRepository,
        val progressStore: ProgressStore,
        val appStateFlags: AppStateFeatureFlags,
        val onNavigateToJobs: () -> Unit,
        val onOpenSheet: (jobFolderName: String, pdfFilename: String, page: Int) -> Unit,
        val modeSwitcher: (@Composable RowScope.() -> Unit)? = null
    ) : UnifiedModeDashboardSpec

    data class Hardwoods(
        val scanCoordinator: HardwoodsScanCoordinator,
        val progressStore: HardwoodsProgressStore,
        val liveEngine: UnifiedMetadataEngine,
        val onOpenJob: (HardwoodJob) -> Unit,
        val modeSwitcher: (@Composable RowScope.() -> Unit)? = null
    ) : UnifiedModeDashboardSpec
```

- [ ] **Step 3: Pass it through the dispatcher**

Old ([UnifiedModeDashboardScreen.kt:136-152](../../../app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt)):
```kotlin
@Composable
fun UnifiedModeDashboardScreen(spec: UnifiedModeDashboardSpec) {
    when (spec) {
        is UnifiedModeDashboardSpec.Cnc -> CncDashboardContent(
            scanCoordinator = spec.scanCoordinator,
            appStateStore = spec.appStateStore,
            jobRepository = spec.jobRepository,
            progressStore = spec.progressStore,
            appStateFlags = spec.appStateFlags,
            onNavigateToJobs = spec.onNavigateToJobs,
            onOpenSheet = spec.onOpenSheet
        )
        is UnifiedModeDashboardSpec.Hardwoods -> HardwoodsDashboardContent(
            scanCoordinator = spec.scanCoordinator,
            progressStore = spec.progressStore,
            liveEngine = spec.liveEngine,
            onOpenJob = spec.onOpenJob
        )
```
New:
```kotlin
@Composable
fun UnifiedModeDashboardScreen(spec: UnifiedModeDashboardSpec) {
    when (spec) {
        is UnifiedModeDashboardSpec.Cnc -> CncDashboardContent(
            scanCoordinator = spec.scanCoordinator,
            appStateStore = spec.appStateStore,
            jobRepository = spec.jobRepository,
            progressStore = spec.progressStore,
            appStateFlags = spec.appStateFlags,
            onNavigateToJobs = spec.onNavigateToJobs,
            onOpenSheet = spec.onOpenSheet,
            modeSwitcher = spec.modeSwitcher
        )
        is UnifiedModeDashboardSpec.Hardwoods -> HardwoodsDashboardContent(
            scanCoordinator = spec.scanCoordinator,
            progressStore = spec.progressStore,
            liveEngine = spec.liveEngine,
            onOpenJob = spec.onOpenJob,
            modeSwitcher = spec.modeSwitcher
        )
```

- [ ] **Step 4: `CncDashboardContent` — accept and forward `modeSwitcher`**

Old ([UnifiedModeDashboardScreen.kt:170-178](../../../app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt)):
```kotlin
private fun CncDashboardContent(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    appStateFlags: AppStateFeatureFlags,
    onNavigateToJobs: () -> Unit,
    onOpenSheet: (jobFolderName: String, pdfFilename: String, page: Int) -> Unit
) {
```
New:
```kotlin
private fun CncDashboardContent(
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    appStateFlags: AppStateFeatureFlags,
    onNavigateToJobs: () -> Unit,
    onOpenSheet: (jobFolderName: String, pdfFilename: String, page: Int) -> Unit,
    modeSwitcher: (@Composable RowScope.() -> Unit)? = null
) {
```

Old ([UnifiedModeDashboardScreen.kt:213-220](../../../app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt)):
```kotlin
    DashboardShell(
        title = "Dashboard",
        subtitle = "CNC",
        loading = false,
        errorMessage = scanState.errorMessage ?: appUiState.errorMessage,
        emptyMessage = "No CNC dashboard widgets are available yet.",
        hasContent = widgets.isNotEmpty(),
        onRefresh = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }
    ) {
```
New:
```kotlin
    DashboardShell(
        title = "Dashboard",
        subtitle = "CNC",
        loading = false,
        errorMessage = scanState.errorMessage ?: appUiState.errorMessage,
        emptyMessage = "No CNC dashboard widgets are available yet.",
        hasContent = widgets.isNotEmpty(),
        onRefresh = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) },
        topBarActions = { modeSwitcher?.invoke(this) }
    ) {
```

- [ ] **Step 5: `HardwoodsDashboardContent` — accept and forward `modeSwitcher`**

Old ([UnifiedModeDashboardScreen.kt:799-804](../../../app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt)):
```kotlin
private fun HardwoodsDashboardContent(
    scanCoordinator: HardwoodsScanCoordinator,
    progressStore: HardwoodsProgressStore,
    liveEngine: UnifiedMetadataEngine,
    onOpenJob: (HardwoodJob) -> Unit
) {
```
New:
```kotlin
private fun HardwoodsDashboardContent(
    scanCoordinator: HardwoodsScanCoordinator,
    progressStore: HardwoodsProgressStore,
    liveEngine: UnifiedMetadataEngine,
    onOpenJob: (HardwoodJob) -> Unit,
    modeSwitcher: (@Composable RowScope.() -> Unit)? = null
) {
```

Old ([UnifiedModeDashboardScreen.kt:840-847](../../../app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt)):
```kotlin
    DashboardShell(
        title = "Hardwoods Dashboard",
        subtitle = "Hardwoods",
        loading = scanState.status == ScanStatus.LOADING,
        errorMessage = scanState.errorMessage,
        emptyMessage = "No hardwood jobs are available yet.",
        hasContent = jobInfos.isNotEmpty(),
        onRefresh = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }
    ) {
```
New:
```kotlin
    DashboardShell(
        title = "Hardwoods Dashboard",
        subtitle = "Hardwoods",
        loading = scanState.status == ScanStatus.LOADING,
        errorMessage = scanState.errorMessage,
        emptyMessage = "No hardwood jobs are available yet.",
        hasContent = jobInfos.isNotEmpty(),
        onRefresh = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) },
        topBarActions = { modeSwitcher?.invoke(this) }
    ) {
```

- [ ] **Step 6: Compile-check**

```bash
.\gradlew.bat app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. All 4 existing `UnifiedModeDashboardSpec.Cnc(...)`/`.Hardwoods(...)` construction sites (both `composable("dashboard")` copies, unchanged until Task G) compile unchanged since `modeSwitcher` defaults to `null`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt
git commit -m "feat: UnifiedModeDashboardScreen supports an optional header mode switcher"
```

---

## Task G: Wire the Dashboard switcher into NavGraph (both `composable("dashboard")` copies)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/navigation/FlexibleModeWiringTest.kt` (extend)

- [ ] **Step 1: Extend the wiring test**

Add to `FlexibleModeWiringTest.kt`:
```kotlin
    @Test
    fun dashboardComposableOffersOnlyCncAndHardwoodsWhenFlexible() {
        val source = navGraphSource()
        val occurrences = Regex("flexible_dashboard_last_mode").findAll(source).count()
        assertTrue("expected the persisted dashboard-mode pref key to appear at least twice (once per duplicated dashboard composable)", occurrences >= 2)
        val assemblySpecInFlexibleBranch = Regex(
            "flexibleModeEnabled[\\s\\S]{0,400}UnifiedModeDashboardSpec\\.Assembly"
        ).containsMatchIn(source)
        assertFalse("Assembly must not be built inside a flexibleModeEnabled dashboard branch", assemblySpecInFlexibleBranch)
    }
```
Add the `assertFalse` import alongside the existing `assertTrue` import at the top of the file:
```kotlin
import org.junit.Assert.assertFalse
```

- [ ] **Step 2: Run it to verify it fails**

```bash
.\gradlew.bat app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.FlexibleModeWiringTest"
```
Expected: FAIL — `dashboardComposableOffersOnlyCncAndHardwoodsWhenFlexible` fails (no `flexible_dashboard_last_mode` key exists yet).

- [ ] **Step 3: Replace `DashboardTabHost`'s `composable("dashboard")` block**

Replace the entire `composable("dashboard") { when (workMode) { ... } }` block inside `DashboardTabHost` (currently ~lines 1254-1309) with:

```kotlin
        composable("dashboard") {
            if (flexibleModeEnabled) {
                val context = LocalContext.current
                val dashPrefs = remember { context.getSharedPreferences("kkc_tracker", android.content.Context.MODE_PRIVATE) }
                var dashMode by remember {
                    mutableStateOf(
                        WorkMode.fromStored(dashPrefs.getString("flexible_dashboard_last_mode", null))
                            .let { if (it == WorkMode.HARDWOODS) it else WorkMode.CNC }
                    )
                }
                val switcher: @Composable RowScope.() -> Unit = {
                    com.kkc.sheettracker.ui.components.ModeSwitcherRow(
                        modes = listOf(WorkMode.CNC, WorkMode.HARDWOODS),
                        selected = dashMode,
                        onSelect = { mode ->
                            dashMode = mode
                            dashPrefs.edit().putString("flexible_dashboard_last_mode", mode.name).apply()
                        }
                    )
                }
                when (dashMode) {
                    WorkMode.HARDWOODS -> UnifiedModeDashboardScreen(
                        UnifiedModeDashboardSpec.Hardwoods(
                            scanCoordinator = hardwoodsScanCoordinator,
                            progressStore = hardwoodsProgressStore,
                            liveEngine = liveEngine,
                            onOpenJob = { job ->
                                onOpenHardwoodsJobInJobs(job.folderName)
                            },
                            modeSwitcher = switcher
                        )
                    )
                    else -> UnifiedModeDashboardScreen(
                        UnifiedModeDashboardSpec.Cnc(
                            scanCoordinator = scanCoordinator,
                            appStateStore = appStateStore,
                            jobRepository = jobRepository,
                            progressStore = progressStore,
                            appStateFlags = appStateFlags,
                            onNavigateToJobs = onNavigateToJobs,
                            onOpenSheet = onOpenSheet,
                            modeSwitcher = switcher
                        )
                    )
                }
            } else {
                when (workMode) {
                    WorkMode.CNC -> {
                        UnifiedModeDashboardScreen(
                            UnifiedModeDashboardSpec.Cnc(
                                scanCoordinator = scanCoordinator,
                                appStateStore = appStateStore,
                                jobRepository = jobRepository,
                                progressStore = progressStore,
                                appStateFlags = appStateFlags,
                                onNavigateToJobs = onNavigateToJobs,
                                onOpenSheet = onOpenSheet
                            )
                        )
                    }
                    WorkMode.HARDWOODS -> {
                        UnifiedModeDashboardScreen(
                            UnifiedModeDashboardSpec.Hardwoods(
                                scanCoordinator = hardwoodsScanCoordinator,
                                progressStore = hardwoodsProgressStore,
                                liveEngine = liveEngine,
                                onOpenJob = { job ->
                                    onOpenHardwoodsJobInJobs(job.folderName)
                                }
                            )
                        )
                    }
                    WorkMode.ASSEMBLY -> {
                        UnifiedModeDashboardScreen(
                            UnifiedModeDashboardSpec.Assembly(
                                scanCoordinator = assemblyScanCoordinator,
                                assemblyStateStore = assemblyStateStore,
                                cncProgressStore = progressStore,
                                hardwoodsProgressStore = hardwoodsProgressStore,
                                specialtyStateStore = specialtyStateStore,
                                onOpenJob = { folderName ->
                                    navController.navigate("assembly/job/${URLEncoder.encode(folderName, "UTF-8")}") {
                                        launchSingleTop = true
                                    }
                                }
                            )
                        )
                    }
                    WorkMode.SPECIALTY -> {
                        UnifiedModeDashboardScreen(
                            UnifiedModeDashboardSpec.Specialty(
                                specialtyStateStore = specialtyStateStore,
                                onNavigateToJobs = onNavigateToJobs,
                                onOpenJob = { folderName ->
                                    onOpenSpecialtyJobInJobs(folderName)
                                }
                            )
                        )
                    }
                }
            }
        }
```

`NavGraph.kt` already imports `androidx.compose.ui.platform.LocalContext`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.getValue`, and `androidx.compose.runtime.setValue` — no changes needed for those. It does **not** yet import `RowScope`; add this line alongside the other `androidx.compose.foundation.layout.*` imports near the top of the file:
```kotlin
import androidx.compose.foundation.layout.RowScope
```

- [ ] **Step 4: Replace `LegacySingleStackNavigation`'s inline `composable("dashboard")` block**

This copy (currently ~lines 2663-2731) does not receive `onNavigateToJobs`/`onOpenSheet`/`onOpenHardwoodsJobInJobs` as parameters the way `DashboardTabHost` does — it inlines `navController.navigate(...)` and `openSheetLegacy(...)` directly. Do **not** reuse Step 3's parameter-reference style here. Replace the entire `composable("dashboard") { when (workMode) { ... } }` block in this copy with:

```kotlin
                    composable("dashboard") {
                        if (flexibleModeEnabled) {
                            val context = LocalContext.current
                            val dashPrefs = remember { context.getSharedPreferences("kkc_tracker", android.content.Context.MODE_PRIVATE) }
                            var dashMode by remember {
                                mutableStateOf(
                                    WorkMode.fromStored(dashPrefs.getString("flexible_dashboard_last_mode", null))
                                        .let { if (it == WorkMode.HARDWOODS) it else WorkMode.CNC }
                                )
                            }
                            val switcher: @Composable RowScope.() -> Unit = {
                                com.kkc.sheettracker.ui.components.ModeSwitcherRow(
                                    modes = listOf(WorkMode.CNC, WorkMode.HARDWOODS),
                                    selected = dashMode,
                                    onSelect = { mode ->
                                        dashMode = mode
                                        dashPrefs.edit().putString("flexible_dashboard_last_mode", mode.name).apply()
                                    }
                                )
                            }
                            when (dashMode) {
                                WorkMode.HARDWOODS -> UnifiedModeDashboardScreen(
                                    UnifiedModeDashboardSpec.Hardwoods(
                                        scanCoordinator = hardwoodsScanCoordinator,
                                        progressStore = hardwoodsProgressStore,
                                        liveEngine = unifiedEngine,
                                        onOpenJob = { job ->
                                            navController.navigate("hardwoods/job/${URLEncoder.encode(job.folderName, "UTF-8")}") {
                                                launchSingleTop = true
                                            }
                                        },
                                        modeSwitcher = switcher
                                    )
                                )
                                else -> UnifiedModeDashboardScreen(
                                    UnifiedModeDashboardSpec.Cnc(
                                        scanCoordinator = scanCoordinator,
                                        appStateStore = appStateStore,
                                        jobRepository = jobRepository,
                                        progressStore = progressStore,
                                        appStateFlags = appStateFlags,
                                        onNavigateToJobs = {
                                            navController.navigate("jobs") {
                                                launchSingleTop = true
                                            }
                                        },
                                        onOpenSheet = { folderName, pdfFilename, page ->
                                            openSheetLegacy(folderName, pdfFilename, page)
                                        },
                                        modeSwitcher = switcher
                                    )
                                )
                            }
                        } else {
                            when (workMode) {
                                WorkMode.CNC -> {
                                    UnifiedModeDashboardScreen(
                                        UnifiedModeDashboardSpec.Cnc(
                                            scanCoordinator = scanCoordinator,
                                            appStateStore = appStateStore,
                                            jobRepository = jobRepository,
                                            progressStore = progressStore,
                                            appStateFlags = appStateFlags,
                                            onNavigateToJobs = {
                                                navController.navigate("jobs") {
                                                    launchSingleTop = true
                                                }
                                            },
                                            onOpenSheet = { folderName, pdfFilename, page ->
                                                openSheetLegacy(folderName, pdfFilename, page)
                                            }
                                        )
                                    )
                                }
                                WorkMode.HARDWOODS -> {
                                    UnifiedModeDashboardScreen(
                                        UnifiedModeDashboardSpec.Hardwoods(
                                            scanCoordinator = hardwoodsScanCoordinator,
                                            progressStore = hardwoodsProgressStore,
                                            liveEngine = unifiedEngine,
                                            onOpenJob = { job ->
                                                navController.navigate("hardwoods/job/${URLEncoder.encode(job.folderName, "UTF-8")}") {
                                                    launchSingleTop = true
                                                }
                                            }
                                        )
                                    )
                                }
                                WorkMode.ASSEMBLY -> {
                                    UnifiedModeDashboardScreen(
                                        UnifiedModeDashboardSpec.Assembly(
                                            scanCoordinator = assemblyScanCoordinator,
                                            assemblyStateStore = assemblyStateStore,
                                            cncProgressStore = progressStore,
                                            hardwoodsProgressStore = hardwoodsProgressStore,
                                            specialtyStateStore = specialtyStateStore,
                                            onOpenJob = { folderName ->
                                                navController.navigate("assembly/job/${URLEncoder.encode(folderName, "UTF-8")}") {
                                                    launchSingleTop = true
                                                }
                                            }
                                        )
                                    )
                                }
                                WorkMode.SPECIALTY -> {
                                    UnifiedModeDashboardScreen(
                                        UnifiedModeDashboardSpec.Specialty(
                                            specialtyStateStore = specialtyStateStore,
                                            onNavigateToJobs = {
                                                navController.navigate("jobs") {
                                                    launchSingleTop = true
                                                }
                                            },
                                            onOpenJob = { folderName ->
                                                navController.navigate(specialtyJobRoute(folderName)) {
                                                    launchSingleTop = true
                                                }
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }
```

Note the data class field stays `liveEngine` in both copies — only the local variable fed into it differs (`liveEngine` in `DashboardTabHost`, `unifiedEngine` here).

- [ ] **Step 5: Run the wiring test again to verify it passes**

```bash
.\gradlew.bat app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.FlexibleModeWiringTest"
```
Expected: PASS (all tests in the class).

- [ ] **Step 6: Compile-check**

```bash
.\gradlew.bat app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/test/java/com/kkc/sheettracker/navigation/FlexibleModeWiringTest.kt
git commit -m "feat: Dashboard switches between CNC/Hardwoods live when Flexible Mode is on"
```

---

## Task H: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full unit test suite**

```bash
.\gradlew.bat app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, all tests pass (including the pre-existing `WorkModeTest`, `LegacyStandardsTransitionWiringTest`, `UnifiedJobsScreenTest`, and the new tests from Tasks B/C/E/G).

- [ ] **Step 2: Full release build**

```bash
.\gradlew.bat assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Manual verification on a connected tablet**

Per [CLAUDE.md](../../../CLAUDE.md), install via:
```bash
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Check, with Flexible Mode **off** (default):
- Jobs screen: sort button is gone; list is production-order; everything else (board view, restore, delivery banner) behaves as before.
- Dashboard/Jobs both still lock to the tablet's Work Mode exactly as before.

Then turn Flexible Mode **on** in Settings and check:
- Jobs screen shows 4 chips (CNC/HW/ASM/SPC); tapping each swaps the list content live; tapping a job opens it in whichever mode's chip is currently selected.
- Dashboard shows 2 chips (CNC/HW) **even when the tablet's underlying Work Mode is set to Assembly or Specialty** — confirms the switcher isn't gated on `workMode`.
- Pick Hardwoods on the Dashboard switcher, navigate away (e.g. to Jobs) and back — Dashboard should reopen on Hardwoods (persisted pref), not reset to CNC.
- Turn Flexible Mode back off — both screens return to exactly the fixed-mode behavior for whatever Work Mode is currently selected in Settings.

- [ ] **Step 4: Report results**

No commit for this task — it's verification only. If manual testing surfaces a bug, fix it as a new commit before considering the feature done.
