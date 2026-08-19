# Dashboard Live Index Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Hardwoods and Assembly Dashboard-tab screens read job-list/progress data through the same shared `LiveAwareUnifiedMetadataEngine` instance the Jobs tab already uses, instead of each resolving its own raw `UnifiedMetadataEngineRegistry` instance — closing the known live/lagged data-inconsistency gap between the two tabs.

**Architecture:** Constructor/parameter-thread the single `liveIndexEngine` (`MultiBackStackNavigation`) / `unifiedEngine` (`LegacySingleStackNavigation`) instance NavGraph.kt already builds down into `AssemblyStateStore` and `HardwoodsDashboardContent`, replacing their independent `UnifiedMetadataEngineRegistry.getOrCreate(...)` calls. No new components. Also removes the now-confirmed-dead `AssemblyDashboardScreen.kt`.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4 + mockito-kotlin (unit tests, `app/src/test`).

**Spec:** `docs/superpowers/specs/2026-08-19-dashboard-live-index-wiring-design.md`

**Note on test coverage:** This repo has no Compose UI test infra (no `androidTest` directory, no `createComposeRule` usage anywhere). `AssemblyStateStore` is a plain Kotlin class, so its change gets a real JUnit test. `HardwoodsDashboardContent` is a `@Composable` with no non-trivial extractable pure-logic seam — its change is a pure wiring swap (parameter instead of local resolution) verified by compilation + a grep check that the removed call is actually gone, per Task 4. Do not invent Compose test infrastructure to cover this; that's out of scope for a wiring change and the design's own Testing section defers real-device verification to the deployment step.

---

### Task 1: `AssemblyStateStore` takes `liveEngine` instead of resolving its own

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/AssemblyStateStore.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/AssemblyStateStoreTest.kt` (new)

Current relevant code (`AssemblyStateStore.kt:1-47`):

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.BuildConfig
import com.kkc.sheettracker.data.models.AssemblyBomEntry
import com.kkc.sheettracker.data.models.AssemblyCabinetParts
import com.kkc.sheettracker.data.models.AssemblyCncPart
import com.kkc.sheettracker.data.models.AssemblyCncSummary
import com.kkc.sheettracker.data.models.AssemblyHardwoodRow
import com.kkc.sheettracker.data.models.AssemblyHardwoodsSummary
import com.kkc.sheettracker.data.models.AssemblyJob
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import com.kkc.sheettracker.data.models.AssemblyJobCard
import com.kkc.sheettracker.data.models.AssemblySearchEntry
import com.kkc.sheettracker.data.models.AssemblySheetPart
import com.kkc.sheettracker.data.models.AssemblyVirtualSourceRef
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import com.kkc.sheettracker.data.unified.UnifiedBoardStockOverlayLookup
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
import com.kkc.sheettracker.data.unified.UnifiedPartOverlayLookup

class AssemblyStateStore(
    private val assemblyScanCoordinator: AssemblyScanCoordinator,
    private val scanCoordinator: ScanCoordinator,
    private val hardwoodsScanCoordinator: HardwoodsScanCoordinator,
    private val progressStore: ProgressStore,
    private val hardwoodsProgressStore: HardwoodsProgressStore
) {
    private fun engine(): UnifiedMetadataEngine {
        val path = assemblyScanCoordinator.state.value.snapshot.basePath
        val baseDir = java.io.File(path)
        return UnifiedMetadataEngineRegistry.getOrCreate(
            baseDir = baseDir,
            isDebugBuild = BuildConfig.DEBUG
        )
    }
```

`engine()` is called 7 more times below this in `getJobs()`, `getCabinetSheetIndex()`, `deriveJobCards()` (x3), `getCabinetJumpPages()`, `getCabinetContext()`, `deriveCabinetParts()`.

`assemblyScanCoordinator`, `scanCoordinator`, and `hardwoodsScanCoordinator` are constructor fields — after this change `assemblyScanCoordinator` becomes unused within the class (it was only ever used for `engine()`'s base path), same as `scanCoordinator`/`hardwoodsScanCoordinator` already were before this change. Leave all three params in place — they're unrelated pre-existing/newly-inert fields, not part of this wiring fix, and removing them means touching every `AssemblyStateStore(...)` call site for a change this plan doesn't need to make.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/kkc/sheettracker/data/AssemblyStateStoreTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.AssemblyJob
import com.kkc.sheettracker.data.models.CacheIndexCncProgress
import com.kkc.sheettracker.data.models.CacheIndexHardwoodsProgress
import com.kkc.sheettracker.data.models.CacheIndexProgressSummary
import com.kkc.sheettracker.data.unified.UnifiedAssemblySnapshot
import com.kkc.sheettracker.data.unified.UnifiedJobInfo
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class AssemblyStateStoreTest {

    @Test
    fun `deriveJobCards reads through the injected live engine, not a registry lookup`() {
        val liveEngine = mock<UnifiedMetadataEngine> {
            on { getCachedJobInfos() } doReturn listOf(
                UnifiedJobInfo(folderName = "1234 - Job", jobNumber = "1234", jobName = "Job")
            )
            on { getAssemblySnapshot("1234 - Job") } doReturn UnifiedAssemblySnapshot(
                job = AssemblyJob(folderName = "1234 - Job", jobNumber = "1234", jobName = "Job")
            )
            on { getProgressFromIndex("1234 - Job") } doReturn CacheIndexProgressSummary(
                cnc = CacheIndexCncProgress(totalSheets = 4, done = 2),
                hardwoods = CacheIndexHardwoodsProgress(totalPieces = 10, donePieces = 5)
            )
        }
        val store = AssemblyStateStore(
            assemblyScanCoordinator = mock(),
            scanCoordinator = mock(),
            hardwoodsScanCoordinator = mock(),
            progressStore = mock(),
            hardwoodsProgressStore = mock(),
            liveEngine = liveEngine
        )

        val cards = store.deriveJobCards()

        assertEquals(1, cards.size)
        assertEquals("1234 - Job", cards[0].folderName)
        assertEquals(4, cards[0].cncSummary.totalSheets)
        assertEquals(2, cards[0].cncSummary.completedSheets)
        assertEquals(10, cards[0].hardwoodsSummary.totalPieces)
        assertEquals(5, cards[0].hardwoodsSummary.donePieces)
    }
}
```

This does not compile yet — `AssemblyStateStore`'s constructor has no `liveEngine` parameter.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.AssemblyStateStoreTest"`
Expected: FAIL — compilation error, "no value passed for parameter liveEngine" (or "unresolved reference").

- [ ] **Step 3: Add `liveEngine` and remove `engine()`**

Replace the import block and class header (`AssemblyStateStore.kt:1-47`) with:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.AssemblyBomEntry
import com.kkc.sheettracker.data.models.AssemblyCabinetParts
import com.kkc.sheettracker.data.models.AssemblyCncPart
import com.kkc.sheettracker.data.models.AssemblyCncSummary
import com.kkc.sheettracker.data.models.AssemblyHardwoodRow
import com.kkc.sheettracker.data.models.AssemblyHardwoodsSummary
import com.kkc.sheettracker.data.models.AssemblyJob
import com.kkc.sheettracker.data.models.HardwoodStatusCounts
import com.kkc.sheettracker.data.models.AssemblyJobCard
import com.kkc.sheettracker.data.models.AssemblySearchEntry
import com.kkc.sheettracker.data.models.AssemblySheetPart
import com.kkc.sheettracker.data.models.AssemblyVirtualSourceRef
import com.kkc.sheettracker.data.models.StatusCounts
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import com.kkc.sheettracker.data.unified.UnifiedBoardStockOverlayLookup
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import com.kkc.sheettracker.data.unified.UnifiedPartOverlayLookup

class AssemblyStateStore(
    private val assemblyScanCoordinator: AssemblyScanCoordinator,
    private val scanCoordinator: ScanCoordinator,
    private val hardwoodsScanCoordinator: HardwoodsScanCoordinator,
    private val progressStore: ProgressStore,
    private val hardwoodsProgressStore: HardwoodsProgressStore,
    private val liveEngine: UnifiedMetadataEngine
) {
    private fun engine(): UnifiedMetadataEngine = liveEngine
```

(`BuildConfig` and `UnifiedMetadataEngineRegistry` imports removed — no longer referenced anywhere in the file. Keeping the private `engine()` wrapper rather than renaming all 8 call sites — it's a one-line indirection now, and every existing call site (`getJobs()`, `deriveJobCards()`, `getCabinetSheetIndex()`, `getCabinetJumpPages()`, `getCabinetContext()`, `deriveCabinetParts()`) stays untouched.)

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.AssemblyStateStoreTest"`
Expected: PASS

- [ ] **Step 5: Run the full existing test suite for this file's package to check nothing else broke**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.*"`
Expected: PASS (no other test constructs `AssemblyStateStore`, per the codebase search performed during design — this just confirms that).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/AssemblyStateStore.kt app/src/test/java/com/kkc/sheettracker/data/AssemblyStateStoreTest.kt
git commit -m "refactor: inject live engine into AssemblyStateStore instead of resolving own"
```

---

### Task 2: Wire `liveEngine` into both `AssemblyStateStore` construction sites

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:521-529` (inside `MultiBackStackNavigation`)
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:2092-2100` (inside `LegacySingleStackNavigation`)

`MultiBackStackNavigation` already receives `liveIndexEngine: UnifiedMetadataEngine` as a function parameter (`NavGraph.kt:493`). `LegacySingleStackNavigation` already receives `unifiedEngine: UnifiedMetadataEngine` (`NavGraph.kt:2076`). Both are already in scope at their respective `AssemblyStateStore(...)` construction sites — this task only adds the new named argument and the corresponding `remember` key.

- [ ] **Step 1: `MultiBackStackNavigation` — `NavGraph.kt:521-529`**

Before:

```kotlin
    val assemblyStateStore = remember(assemblyScanCoordinator, scanCoordinator, hardwoodsScanCoordinator, progressStore, hardwoodsProgressStore) {
        AssemblyStateStore(
            assemblyScanCoordinator = assemblyScanCoordinator,
            scanCoordinator = scanCoordinator,
            hardwoodsScanCoordinator = hardwoodsScanCoordinator,
            progressStore = progressStore,
            hardwoodsProgressStore = hardwoodsProgressStore
        )
    }
```

After:

```kotlin
    val assemblyStateStore = remember(assemblyScanCoordinator, scanCoordinator, hardwoodsScanCoordinator, progressStore, hardwoodsProgressStore, liveIndexEngine) {
        AssemblyStateStore(
            assemblyScanCoordinator = assemblyScanCoordinator,
            scanCoordinator = scanCoordinator,
            hardwoodsScanCoordinator = hardwoodsScanCoordinator,
            progressStore = progressStore,
            hardwoodsProgressStore = hardwoodsProgressStore,
            liveEngine = liveIndexEngine
        )
    }
```

- [ ] **Step 2: `LegacySingleStackNavigation` — `NavGraph.kt:2092-2100`**

Before:

```kotlin
    val assemblyStateStore = remember(assemblyScanCoordinator, scanCoordinator, hardwoodsScanCoordinator, progressStore, hardwoodsProgressStore) {
        AssemblyStateStore(
            assemblyScanCoordinator = assemblyScanCoordinator,
            scanCoordinator = scanCoordinator,
            hardwoodsScanCoordinator = hardwoodsScanCoordinator,
            progressStore = progressStore,
            hardwoodsProgressStore = hardwoodsProgressStore
        )
    }
```

After:

```kotlin
    val assemblyStateStore = remember(assemblyScanCoordinator, scanCoordinator, hardwoodsScanCoordinator, progressStore, hardwoodsProgressStore, unifiedEngine) {
        AssemblyStateStore(
            assemblyScanCoordinator = assemblyScanCoordinator,
            scanCoordinator = scanCoordinator,
            hardwoodsScanCoordinator = hardwoodsScanCoordinator,
            progressStore = progressStore,
            hardwoodsProgressStore = hardwoodsProgressStore,
            liveEngine = unifiedEngine
        )
    }
```

- [ ] **Step 3: Compile to verify wiring is correct**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL — confirms both call sites resolve `liveIndexEngine`/`unifiedEngine` correctly and the new constructor arg matches.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: wire shared live engine into both AssemblyStateStore construction sites"
```

---

### Task 3: `HardwoodsDashboardContent` and `UnifiedModeDashboardSpec.Hardwoods` take `liveEngine`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt`

Current spec (`UnifiedModeDashboardScreen.kt:113-117`):

```kotlin
    data class Hardwoods(
        val scanCoordinator: HardwoodsScanCoordinator,
        val progressStore: HardwoodsProgressStore,
        val onOpenJob: (HardwoodJob) -> Unit
    ) : UnifiedModeDashboardSpec
```

Current dispatch (`UnifiedModeDashboardScreen.kt:147-151`):

```kotlin
        is UnifiedModeDashboardSpec.Hardwoods -> HardwoodsDashboardContent(
            scanCoordinator = spec.scanCoordinator,
            progressStore = spec.progressStore,
            onOpenJob = spec.onOpenJob
        )
```

Current content function (`UnifiedModeDashboardScreen.kt:742-751`):

```kotlin
@Composable
private fun HardwoodsDashboardContent(
    scanCoordinator: HardwoodsScanCoordinator,
    progressStore: HardwoodsProgressStore,
    onOpenJob: (HardwoodJob) -> Unit
) {
    val scanState by scanCoordinator.state.collectAsState()
    val engine = remember(scanState.snapshot.basePath) {
        UnifiedMetadataEngineRegistry.getOrCreate(File(scanState.snapshot.basePath), BuildConfig.DEBUG)
    }
```

- [ ] **Step 1: Add `liveEngine` to the spec**

In `UnifiedModeDashboardSpec.Hardwoods` (`UnifiedModeDashboardScreen.kt:113-117`):

```kotlin
    data class Hardwoods(
        val scanCoordinator: HardwoodsScanCoordinator,
        val progressStore: HardwoodsProgressStore,
        val liveEngine: UnifiedMetadataEngine,
        val onOpenJob: (HardwoodJob) -> Unit
    ) : UnifiedModeDashboardSpec
```

- [ ] **Step 2: Forward it through the dispatch**

In `UnifiedModeDashboardScreen` (`UnifiedModeDashboardScreen.kt:147-151`):

```kotlin
        is UnifiedModeDashboardSpec.Hardwoods -> HardwoodsDashboardContent(
            scanCoordinator = spec.scanCoordinator,
            progressStore = spec.progressStore,
            liveEngine = spec.liveEngine,
            onOpenJob = spec.onOpenJob
        )
```

- [ ] **Step 3: Take it as a parameter in `HardwoodsDashboardContent`, delete the local resolution**

```kotlin
@Composable
private fun HardwoodsDashboardContent(
    scanCoordinator: HardwoodsScanCoordinator,
    progressStore: HardwoodsProgressStore,
    liveEngine: UnifiedMetadataEngine,
    onOpenJob: (HardwoodJob) -> Unit
) {
    val scanState by scanCoordinator.state.collectAsState()
    val engine = liveEngine
```

(Every downstream reference in this function already reads `engine.getCachedJobInfos()` / `engine.getProgressFromIndex(...)` / `engine.getHardwoodsSnapshot(...)` — keeping the local `val engine = liveEngine` line means none of those call sites need touching.)

- [ ] **Step 4: Add the `UnifiedMetadataEngine` import, remove the now-unused ones**

At the top of `UnifiedModeDashboardScreen.kt`, change:

```kotlin
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngineRegistry
```

to:

```kotlin
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
```

Also remove `import com.kkc.sheettracker.BuildConfig` (line 57) — confirmed via grep that its only other use in this file was the deleted `BuildConfig.DEBUG` call. `import java.io.File` (line 91) stays — still used elsewhere in the file (thumbnail path resolution).

- [ ] **Step 5: Verify no stray reference remains**

Run: `grep -n "UnifiedMetadataEngineRegistry\|BuildConfig" "app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt"`
Expected: no output (both fully removed).

- [ ] **Step 6: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (This will fail until Task 4 updates the two call sites that construct `UnifiedModeDashboardSpec.Hardwoods(...)` — if compiling standalone here fails on the missing `liveEngine` argument at those call sites, that's expected; proceed to Task 4 before treating this as a blocker. If your workflow allows, do Task 3 and Task 4 as one compile-verified unit.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt
git commit -m "refactor: inject live engine into Hardwoods dashboard instead of resolving own"
```

---

### Task 4: Wire `liveEngine` into both `UnifiedModeDashboardSpec.Hardwoods` construction sites

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt` — `DashboardTabHost` signature + its call site (`MultiBackStackNavigation`)
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:2374-2382` (inside `LegacySingleStackNavigation`, no new param needed — `unifiedEngine` is already an existing function parameter there)

`DashboardTabHost` (`NavGraph.kt:1084-1102`) is a separate composable, one level removed from `MultiBackStackNavigation` where `liveIndexEngine` lives — it needs a new parameter threaded through, same as `assemblyStateStore` already is.

- [ ] **Step 1: Add `liveEngine` parameter to `DashboardTabHost`**

`NavGraph.kt:1084-1102`, before:

```kotlin
@Composable
private fun DashboardTabHost(
    navController: NavHostController,
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    appStateFlags: AppStateFeatureFlags,
    workMode: WorkMode,
    hardwoodsScanCoordinator: HardwoodsScanCoordinator,
    hardwoodsProgressStore: HardwoodsProgressStore,
    assemblyScanCoordinator: AssemblyScanCoordinator,
    assemblyStateStore: AssemblyStateStore,
    specialtyStateStore: SpecialtyStateStore,
    onNavigateToJobs: () -> Unit,
    onOpenJobInJobs: (String) -> Unit,
    onOpenSpecialtyJobInJobs: (String) -> Unit,
    onOpenHardwoodsJobInJobs: (String) -> Unit,
    onOpenSheet: (String, String, Int) -> Unit
) {
```

After (new param added, keeping the same style/ordering as the other data-source params):

```kotlin
@Composable
private fun DashboardTabHost(
    navController: NavHostController,
    scanCoordinator: ScanCoordinator,
    appStateStore: AppStateStore,
    jobRepository: JobRepository,
    progressStore: ProgressStore,
    appStateFlags: AppStateFeatureFlags,
    workMode: WorkMode,
    hardwoodsScanCoordinator: HardwoodsScanCoordinator,
    hardwoodsProgressStore: HardwoodsProgressStore,
    assemblyScanCoordinator: AssemblyScanCoordinator,
    assemblyStateStore: AssemblyStateStore,
    specialtyStateStore: SpecialtyStateStore,
    liveEngine: UnifiedMetadataEngine,
    onNavigateToJobs: () -> Unit,
    onOpenJobInJobs: (String) -> Unit,
    onOpenSpecialtyJobInJobs: (String) -> Unit,
    onOpenHardwoodsJobInJobs: (String) -> Unit,
    onOpenSheet: (String, String, Int) -> Unit
) {
```

- [ ] **Step 2: Pass it into the `Hardwoods` spec inside `DashboardTabHost`**

`NavGraph.kt:1124-1134`, before:

```kotlin
                WorkMode.HARDWOODS -> {
                    UnifiedModeDashboardScreen(
                        UnifiedModeDashboardSpec.Hardwoods(
                            scanCoordinator = hardwoodsScanCoordinator,
                            progressStore = hardwoodsProgressStore,
                            onOpenJob = { job ->
                                onOpenHardwoodsJobInJobs(job.folderName)
                            }
                        )
                    )
                }
```

After:

```kotlin
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
```

- [ ] **Step 3: Pass `liveIndexEngine` from the call site in `MultiBackStackNavigation`**

`NavGraph.kt:769-797`, add one line after `specialtyStateStore = specialtyStateStore,`:

```kotlin
                    DashboardTabHost(
                        navController = dashboardNavController,
                        scanCoordinator = scanCoordinator,
                        appStateStore = appStateStore,
                        jobRepository = jobRepository,
                        progressStore = progressStore,
                        appStateFlags = appStateFlags,
                        workMode = workMode,
                        hardwoodsScanCoordinator = hardwoodsScanCoordinator,
                        hardwoodsProgressStore = hardwoodsProgressStore,
                        assemblyScanCoordinator = assemblyScanCoordinator,
                        assemblyStateStore = assemblyStateStore,
                        specialtyStateStore = specialtyStateStore,
                        liveEngine = liveIndexEngine,
                        onNavigateToJobs = {
                            coordinator.navigateTopLevel(TopLevelTab.JOBS)
                        },
```

(remaining lines of this call unchanged)

- [ ] **Step 4: Wire the `LegacySingleStackNavigation` inline call site**

`NavGraph.kt:2374-2382`, before:

```kotlin
                            WorkMode.HARDWOODS -> {
                                UnifiedModeDashboardScreen(
                                    UnifiedModeDashboardSpec.Hardwoods(
                                        scanCoordinator = hardwoodsScanCoordinator,
                                        progressStore = hardwoodsProgressStore,
                                        onOpenJob = { job ->
                                            navController.navigate("hardwoods/job/${URLEncoder.encode(job.folderName, "UTF-8")}") {
                                                launchSingleTop = true
                                            }
                                        }
                                    )
                                )
                            }
```

After:

```kotlin
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
```

(`unifiedEngine` is the existing `LegacySingleStackNavigation` function parameter — already in scope here, same one used for `assemblyStateStore` in Task 2.)

- [ ] **Step 5: Compile the whole module**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. This validates Task 3 and Task 4 together — all four call/construction sites now agree on the `liveEngine` parameter.

- [ ] **Step 6: Run the full unit test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (including the new `AssemblyStateStoreTest` and the untouched `LiveAwareUnifiedMetadataEngineTest`).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: wire shared live engine into Dashboard-tab Hardwoods spec"
```

---

### Task 5: Delete confirmed-dead `AssemblyDashboardScreen.kt` and its test

**Files:**
- Delete: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyDashboardScreen.kt`
- Delete: `app/src/test/java/com/kkc/sheettracker/ui/assembly/AssemblyDashboardScreenTest.kt`

Confirmed during design research: `AssemblyDashboardScreen(...)` (the composable) has no caller anywhere in `app/src/main/java` — only its own test invokes `calculateAssemblyDashboardCabinetCount`, the one pure function in that file. The live Assembly dashboard is `AssemblyDashboardContent` inside `UnifiedModeDashboardScreen.kt`, unaffected by this deletion. User confirmed removal during design review (see spec's Context section).

- [ ] **Step 1: Re-confirm no caller exists right before deleting** (repo may have changed since design research)

Run: `grep -rn "AssemblyDashboardScreen(" "app/src/main/java"`
Expected: only the declaration line inside `AssemblyDashboardScreen.kt` itself (`fun AssemblyDashboardScreen(`), no call sites.

- [ ] **Step 2: Delete both files**

```bash
git rm app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyDashboardScreen.kt
git rm app/src/test/java/com/kkc/sheettracker/ui/assembly/AssemblyDashboardScreenTest.kt
```

- [ ] **Step 3: Compile and run tests to confirm nothing referenced them**

Run: `.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git commit -m "chore: remove dead AssemblyDashboardScreen composable and its test"
```

---

### Task 6: Update the original design doc's Known Limitations section

**Files:**
- Modify: `docs/superpowers/specs/2026-08-18-live-cache-index-tablet-client-design.md:139-157`

- [ ] **Step 1: Replace the "Dashboard-family screens stay on the file-backed path" bullet**

Before (`2026-08-18-live-cache-index-tablet-client-design.md:139-157`):

```markdown
## Known limitations

- **Dashboard-family screens stay on the file-backed path even while the
  socket is connected.** `UnifiedModeDashboardScreen` and
  `AssemblyDashboardScreen` (both reachable from `TopLevelTab.DASHBOARD`,
  a sibling tab to `TopLevelTab.JOBS`) call `getCachedJobInfos()` and
  `getProgressFromIndex()` against the raw `UnifiedMetadataEngineRegistry`
  singleton directly, not through `LiveAwareUnifiedMetadataEngine` — only
  the Jobs-tab composables (`JobsTabHost`, `LegacySingleStackNavigation`)
  were wired to the live wrapper. This means the Jobs tab and the Dashboard
  tab can show different data for the same job at the same moment while
  connected (Jobs tab ahead, by design; Dashboard tab lagging on the normal
  Syncthing/file-poll cadence) — a new, user-visible divergence that did not
  exist before this feature, since every screen previously read the same
  lagged source. It is bounded and self-healing (never a crash or data
  loss), and was surfaced by a whole-feature review after the tablet-client
  implementation (2026-08-19), not resolved as part of it. Extending the
  live wrapper to the Dashboard family is a candidate follow-up slice, not
  a silent gap.
```

After:

```markdown
## Known limitations

- ~~Dashboard-family screens stay on the file-backed path even while the
  socket is connected.~~ **Resolved 2026-08-19** by
  `docs/superpowers/specs/2026-08-19-dashboard-live-index-wiring-design.md`.
  The offending call sites were `HardwoodsDashboardContent` and
  `AssemblyStateStore.engine()` (both in `UnifiedModeDashboardScreen.kt` /
  `AssemblyStateStore.kt`) — not `AssemblyDashboardScreen.kt` as originally
  noted here, which the follow-up design's research found to be dead code
  with no caller. Both now read through the same shared live-aware engine
  instance the Jobs tab uses.
```

- [ ] **Step 2: Commit**

```bash
git add "docs/superpowers/specs/2026-08-18-live-cache-index-tablet-client-design.md"
git commit -m "docs: mark dashboard live-index gap as resolved"
```

---

### Task 7: Full verification pass

**Files:** none (verification only)

- [ ] **Step 1: Full clean compile**

Run: `.\gradlew.bat :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Full unit test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests green, including `AssemblyStateStoreTest` and every pre-existing test file (no regressions).

- [ ] **Step 3: Confirm no remaining raw registry reads on the Dashboard path**

Run: `grep -n "UnifiedMetadataEngineRegistry" "app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt" "app/src/main/java/com/kkc/sheettracker/data/AssemblyStateStore.kt"`
Expected: no output.

- [ ] **Step 4: Manual/deployment verification (per spec's Testing section — not automatable in this repo)**

On a shop tablet with the live socket connected, change a job's CNC or hardwood progress via the worker and confirm the Dashboard tab (Hardwoods and Assembly modes) updates within one socket push, matching what the Jobs tab already shows — not waiting on `StaticCachePoller`'s cadence. This step needs a real tablet + running `ready_jobs_worker_live_index` server; flag to the user as a manual follow-up rather than attempting to simulate it here.
