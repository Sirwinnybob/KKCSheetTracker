# Hidden Materials Cutlist UI Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the hide/unhide UI (long-press menu, "show hidden materials" toggle) into `HardwoodsWorkspaceScreen` — the single shared cutlist screen that both the Hardwoods menu and the Specialty menu (Door Panels / Saw Rip List / Closet Rods) navigate into — using the already-complete Android data layer from the prior plan.

**Architecture:** `HardwoodsWorkspaceScreen` is reached via one shared route (`hardwoods/workspace/{folderName}/{docType}/{startPage}`), duplicated once per navigation stack (`MultiBackStackNavigation` and `LegacySingleStackNavigation` in `NavGraph.kt`), from both Hardwoods-entry and Specialty-entry call sites — there is no separate "Specialty cutlist screen" in code. This plan threads a `HiddenMaterialsMode` argument through that shared route (set to `HARDWOODS` or `SPECIALTY` depending on which menu navigated in), then builds the cross-tablet live-synced hide/unhide state *locally inside* `HardwoodsWorkspaceScreen` itself — scoped to the screen's own lifecycle and `(jobFolderName, mode)`, mirroring `DeliveryScheduleLifecycleGate`'s wiring pattern but without threading six new params through two layers of `NavGraph.kt`, since (unlike the delivery schedule) hidden-materials state is only ever needed on this one screen.

**Tech Stack:** Kotlin, Jetpack Compose, OkHttp (WebSocket + REST), AndroidX DataStore Preferences, JUnit4, MockWebServer, Mockito-Kotlin.

---

## Context for the implementer

The full hidden-materials data layer already exists and is fully tested (prior plan, `docs/superpowers/plans/2026-09-08-hidden-materials-android-data-layer.md`, merged to `main`):

- `com.kkc.sheettracker.data.models.HiddenMaterialsMode` (`HARDWOODS`, `SPECIALTY`) and `HiddenMaterialsDocument`/`HiddenMaterialsGlobalDoc`/`HiddenMaterialsJobDoc`/`HiddenMaterialEntry` — `app/src/main/java/com/kkc/sheettracker/data/models/HiddenMaterialsModels.kt`
- `com.kkc.sheettracker.data.HiddenMaterialsRepository` — `fetchDocument(mode, jobFolderName)` reads the mode's global file + one job's override file off the shared drive; `isHiddenIn(document, jobFolderName, docType, material)` (top-level function in the same file) implements the three-step effective-visibility rule — `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsRepository.kt`
- `com.kkc.sheettracker.data.HiddenMaterialsRequestStore` — `writeRequest(mode, action, scope, jobId, docType, material, tabletId, requestedAt)` writes the durable per-tablet sidecar backup — `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsRequestStore.kt`
- `com.kkc.sheettracker.data.AdminSyncClient.applyHiddenMaterialsAction(mode, action, scope, docType, material, tabletId, jobId, requestedAt): Boolean` — the REST fast path, POSTs to `/api/admin-sync/hardwoods-hidden-materials`, already returns `false` (never throws) on any network/server failure — `app/src/main/java/com/kkc/sheettracker/data/AdminSyncClient.kt`
- `com.kkc.sheettracker.data.HiddenMaterialsStateStore` — `document: StateFlow<HiddenMaterialsDocument>`, `liveConnected: Boolean`, `applyLive(document)`, `setLiveConnected(value)`, `markLiveDisconnected()`, `refreshFallback()` — `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsStateStore.kt`
- `com.kkc.sheettracker.data.HiddenMaterialsLiveClient(config: AdminSyncConfig, mode, tabletId, onDocument, onConnectionState)` — read-only WebSocket, `start()`/`stop()` — `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsLiveClient.kt`
- `com.kkc.sheettracker.data.HiddenMaterialsLifecycleGate` (`internal`) + `HiddenMaterialsClientBinding` (`internal`) — stale-callback guard, mirrors `DeliveryScheduleLifecycleGate` exactly — `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsLifecycleGate.kt`
- `com.kkc.sheettracker.data.HiddenMaterialsVisibilityPreferencesStore` — per-tablet, per-mode "show hidden materials" boolean, `showHiddenFlow(mode): Flow<Boolean>`, `suspend fun showHidden(mode)`, `suspend fun setShowHidden(mode, value)`, `companion object { fun create(context) }` — `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsVisibilityPreferencesStore.kt`

`internal` visibility in Kotlin is module-scoped, not package-scoped — `HiddenMaterialsLifecycleGate`/`HiddenMaterialsClientBinding` are freely usable from `com.kkc.sheettracker.ui.hardwoods` since both packages are in the same `app` Gradle module (the existing `applyDoorPanelsSheetFilter`/`hardwoodsWorkspaceRoute` are already `internal` and already tested from `app/src/test`, confirming this).

Read the design spec before starting: `docs/superpowers/specs/2026-09-08-hardwoods-hidden-materials-design.md`. Its "UI" section describes the two required interactions (long-press/overflow menu per material section, toolbar toggle) — written before this plan discovered the screen is shared, so it describes them as if there were two separate screens; there is only one, and this plan implements the interactions on it once.

**Discovered during Task 3 (post-hoc note, not present when this plan was first written):** there is a *third* place `HardwoodsWorkspaceScreen` is reached from — `app/src/main/java/com/kkc/sheettracker/navigation/ArchiveJobDetailHost.kt`, a read-only nested nav host for viewing archived/completed jobs (`pdfMarkupReadOnly = true` and `archiveClientFactory = { null }` everywhere in that file — deliberately no live admin-sync connection). It reaches the shared screen from both its Hardwoods and Specialty branches, exactly like the two main nav stacks. This was missed during the original research (which only searched `NavGraph.kt`) and was caught and fixed as part of Task 3 (commit `928481fa`) — the route there now carries the same `{hiddenMaterialsMode}` segment, and the composable now passes a real `AdminSyncConfig` into `HardwoodsWorkspaceScreen`. **Because archive viewing is read-only and deliberately has no live sync, Task 4 (live-synced state) and Task 6 (hide/unhide menu) below must gate on the screen's existing `pdfMarkupReadOnly` parameter** — already `true` at every archive call site, already `false` at every live-app call site — rather than assuming live sync and mutation are always wanted. This requirement is folded into both tasks' text below.

---

### Task 1: Dual-write action submitter

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsActionSubmitter.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsActionSubmitterTest.kt`

This is the one new piece of pure/testable logic this plan adds: a small function that fires both write paths for one hide/unhide action (sidecar always, REST best-effort), so the UI layer (Task 6) only needs one call.

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsActionSubmitterTest.kt
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HiddenMaterialsActionSubmitterTest {

    private lateinit var server: MockWebServer
    private lateinit var requestStore: HiddenMaterialsRequestStore
    private lateinit var baseDir: java.io.File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseDir = Files.createTempDirectory("hidden-materials-submitter").toFile()
        requestStore = HiddenMaterialsRequestStore(baseDir)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `writes the sidecar and posts the REST fast path when a server URL is available`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"applied":true}"""))

        submitHiddenMaterialsAction(
            serverUrl = server.url("/").toString().trimEnd('/'),
            requestStore = requestStore,
            mode = HiddenMaterialsMode.HARDWOODS,
            action = "hide",
            scope = "job",
            jobId = "123 - Job",
            docType = "NAILER_CUT_LIST",
            material = "Maple",
            tabletId = "tablet-1",
            requestedAt = "2026-09-08T18:00:00Z"
        )

        val sidecar = java.io.File(
            hiddenMaterialsJobPath(baseDir, HiddenMaterialsMode.HARDWOODS, "123 - Job").parentFile,
            "hidden_materials_request.tablet-1.json"
        )
        assertTrue("expected sidecar request file to exist", sidecar.exists())
        val recorded = server.takeRequest()
        assertTrue(recorded.path == "/api/admin-sync/hardwoods-hidden-materials")
    }

    @Test
    fun `writes the sidecar without attempting REST when no server URL is available`() = runBlocking {
        submitHiddenMaterialsAction(
            serverUrl = null,
            requestStore = requestStore,
            mode = HiddenMaterialsMode.SPECIALTY,
            action = "hide",
            scope = "global",
            jobId = null,
            docType = "DOOR_LIST",
            material = "Oak",
            tabletId = "tablet-1",
            requestedAt = "2026-09-08T18:00:00Z"
        )

        val sidecar = java.io.File(
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.SPECIALTY).parentFile,
            "hidden_materials_request.tablet-1.json"
        )
        assertTrue("expected sidecar request file to exist even with no server URL", sidecar.exists())
        assertTrue(server.requestCount == 0)
    }

    @Test
    fun `writes the sidecar even when the REST call fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        submitHiddenMaterialsAction(
            serverUrl = server.url("/").toString().trimEnd('/'),
            requestStore = requestStore,
            mode = HiddenMaterialsMode.HARDWOODS,
            action = "unhide",
            scope = "job",
            jobId = "123 - Job",
            docType = "NAILER_CUT_LIST",
            material = "Maple",
            tabletId = "tablet-1",
            requestedAt = "2026-09-08T18:00:00Z"
        )

        val sidecar = java.io.File(
            hiddenMaterialsJobPath(baseDir, HiddenMaterialsMode.HARDWOODS, "123 - Job").parentFile,
            "hidden_materials_request.tablet-1.json"
        )
        assertTrue("expected sidecar request file to exist despite REST failure", sidecar.exists())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterialsActionSubmitterTest"`
Expected: FAIL to compile (`submitHiddenMaterialsAction` does not exist yet)

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsActionSubmitter.kt
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fires both write paths for one hide/unhide action, per the dual-write requirement in the
 * 2026-09-08 hidden-materials design doc: the durable sidecar is always written first (it alone
 * guarantees eventual delivery), then the REST fast path is attempted on a best-effort basis --
 * AdminSyncClient.applyHiddenMaterialsAction already swallows network/server failures internally,
 * so no error handling is needed here for that half.
 */
suspend fun submitHiddenMaterialsAction(
    serverUrl: String?,
    requestStore: HiddenMaterialsRequestStore,
    mode: HiddenMaterialsMode,
    action: String,
    scope: String,
    jobId: String?,
    docType: String,
    material: String,
    tabletId: String,
    requestedAt: String
) {
    withContext(Dispatchers.IO) {
        requestStore.writeRequest(
            mode = mode,
            action = action,
            scope = scope,
            jobId = jobId,
            docType = docType,
            material = material,
            tabletId = tabletId,
            requestedAt = requestedAt
        )
        if (serverUrl != null) {
            AdminSyncClient(serverUrl).applyHiddenMaterialsAction(
                mode = mode,
                action = action,
                scope = scope,
                docType = docType,
                material = material,
                tabletId = tabletId,
                jobId = jobId,
                requestedAt = requestedAt
            )
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterialsActionSubmitterTest"`
Expected: 3 tests passed

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsActionSubmitter.kt app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsActionSubmitterTest.kt
git commit -m "feat: add submitHiddenMaterialsAction dual-write helper"
```

---

### Task 2: Thread `HiddenMaterialsMode` through the shared workspace route

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/navigation/HardwoodsWorkspaceRouteTest.kt`

`hardwoodsWorkspaceRoute(jobFolderName, docType, rowId)` at `NavGraph.kt:3955` builds the one route both the Hardwoods menu and the Specialty menu navigate into. It gains a required `mode: HiddenMaterialsMode` parameter, encoded as a 4th path segment. Every one of its 16 call sites (8 in `MultiBackStackNavigation`, 8 in `LegacySingleStackNavigation` — an exact mirror of the same 8 logical call sites) must pass the correct literal: `HARDWOODS` from Hardwoods-entry call sites, `SPECIALTY` from Specialty-entry call sites. Because this changes a shared function's required parameters, this task updates the builder, both route pattern declarations, and all 16 call sites together as one compiling change.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/kkc/sheettracker/navigation/HardwoodsWorkspaceRouteTest.kt
package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwoodsWorkspaceRouteTest {
    @Test
    fun hardwoodsWorkspaceRoute_encodesModeAsTrailingSegment() {
        assertEquals(
            "hardwoods/workspace/1234+-+Kitchen/NAILER_CUT_LIST/row-1/HARDWOODS",
            hardwoodsWorkspaceRoute("1234 - Kitchen", HardwoodDocType.NAILER_CUT_LIST, "row-1", HiddenMaterialsMode.HARDWOODS)
        )
    }

    @Test
    fun hardwoodsWorkspaceRoute_specialtyModeEncodesDistinctly() {
        assertEquals(
            "hardwoods/workspace/1234+-+Kitchen/DOOR_CUT_LIST/_/SPECIALTY",
            hardwoodsWorkspaceRoute("1234 - Kitchen", HardwoodDocType.DOOR_CUT_LIST, null, HiddenMaterialsMode.SPECIALTY)
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.HardwoodsWorkspaceRouteTest"`
Expected: FAIL to compile (`hardwoodsWorkspaceRoute` does not accept a 4th argument yet)

- [ ] **Step 3: Update the route builder**

In `NavGraph.kt`, find (around line 3955):

```kotlin
internal fun hardwoodsWorkspaceRoute(jobFolderName: String, docType: HardwoodDocType, rowId: String?): String {
    val encodedRowId = URLEncoder.encode(rowId ?: "_", "UTF-8")
    return "hardwoods/workspace/${URLEncoder.encode(jobFolderName, "UTF-8")}/${URLEncoder.encode(docType.name, "UTF-8")}/$encodedRowId"
}
```

Replace with:

```kotlin
internal fun hardwoodsWorkspaceRoute(
    jobFolderName: String,
    docType: HardwoodDocType,
    rowId: String?,
    mode: com.kkc.sheettracker.data.models.HiddenMaterialsMode
): String {
    val encodedRowId = URLEncoder.encode(rowId ?: "_", "UTF-8")
    return "hardwoods/workspace/${URLEncoder.encode(jobFolderName, "UTF-8")}/${URLEncoder.encode(docType.name, "UTF-8")}/$encodedRowId/${mode.name}"
}
```

- [ ] **Step 4: Update both route pattern declarations and their NavArguments**

Both nav stacks declare an identical composable for this route. In `MultiBackStackNavigation` (around line 1984-1990):

```kotlin
        composable(
            "hardwoods/workspace/{folderName}/{docType}/{startPage}",
            arguments = listOf(
                navArgument("folderName") { type = NavType.StringType },
                navArgument("docType") { type = NavType.StringType },
                navArgument("startPage") { type = NavType.StringType }
            )
        ) { backStack ->
```

Replace with:

```kotlin
        composable(
            "hardwoods/workspace/{folderName}/{docType}/{startPage}/{hiddenMaterialsMode}",
            arguments = listOf(
                navArgument("folderName") { type = NavType.StringType },
                navArgument("docType") { type = NavType.StringType },
                navArgument("startPage") { type = NavType.StringType },
                navArgument("hiddenMaterialsMode") { type = NavType.StringType }
            )
        ) { backStack ->
```

And in `LegacySingleStackNavigation` (around line 3399-3406), apply the identical change:

```kotlin
                composable(
                    "hardwoods/workspace/{folderName}/{docType}/{startPage}",
                    arguments = listOf(
                        navArgument("folderName") { type = NavType.StringType },
                        navArgument("docType") { type = NavType.StringType },
                        navArgument("startPage") { type = NavType.StringType }
                    )
                ) { backStack ->
```

Replace with:

```kotlin
                composable(
                    "hardwoods/workspace/{folderName}/{docType}/{startPage}/{hiddenMaterialsMode}",
                    arguments = listOf(
                        navArgument("folderName") { type = NavType.StringType },
                        navArgument("docType") { type = NavType.StringType },
                        navArgument("startPage") { type = NavType.StringType },
                        navArgument("hiddenMaterialsMode") { type = NavType.StringType }
                    )
                ) { backStack ->
```

- [ ] **Step 5: Update all 16 call sites**

For every call below, add the trailing `mode = HiddenMaterialsMode.HARDWOODS` or `mode = HiddenMaterialsMode.SPECIALTY` argument matching the entry point. Use fully-qualified `com.kkc.sheettracker.data.models.HiddenMaterialsMode.HARDWOODS`/`.SPECIALTY` (matching this file's existing fully-qualified-enum convention, e.g. `HardwoodDocType.valueOf`'s callers) unless you add an import — either is fine as long as it's consistent per call site; this plan uses the fully-qualified form throughout `NavGraph.kt` since the file does not otherwise import `HiddenMaterialsMode`.

**`MultiBackStackNavigation` (HARDWOODS — 4 call sites):**

Line ~1012 (`onHardwoodsResultClick`, search jump):
```kotlin
                                coordinator.openHardwoodsRouteInJobs(hardwoodsWorkspaceRoute(folderName, docType, rowId))
```
→
```kotlin
                                coordinator.openHardwoodsRouteInJobs(
                                    hardwoodsWorkspaceRoute(folderName, docType, rowId, com.kkc.sheettracker.data.models.HiddenMaterialsMode.HARDWOODS)
                                )
```

Line ~1568 (`onOpenHardwoodsChange`, jobs list "changed" jump):
```kotlin
                onOpenHardwoodsChange = { jobFolderName, docType, rowId ->
                    navController.navigate(hardwoodsWorkspaceRoute(jobFolderName, docType, rowId)) {
                        launchSingleTop = true
                    }
                },
```
→
```kotlin
                onOpenHardwoodsChange = { jobFolderName, docType, rowId ->
                    navController.navigate(
                        hardwoodsWorkspaceRoute(jobFolderName, docType, rowId, com.kkc.sheettracker.data.models.HiddenMaterialsMode.HARDWOODS)
                    ) {
                        launchSingleTop = true
                    }
                },
```

Line ~1938 (`HardwoodsJobDetailScreen.onOpenWorkspace`):
```kotlin
                onOpenWorkspace = { docType ->
                    navController.navigate(hardwoodsWorkspaceRoute(folderName, docType, null)) {
                        launchSingleTop = true
                    }
                },
```
→
```kotlin
                onOpenWorkspace = { docType ->
                    navController.navigate(
                        hardwoodsWorkspaceRoute(folderName, docType, null, com.kkc.sheettracker.data.models.HiddenMaterialsMode.HARDWOODS)
                    ) {
                        launchSingleTop = true
                    }
                },
```

Line ~1944 (`HardwoodsJobDetailScreen.onOpenRipCutList`):
```kotlin
                onOpenRipCutList = {
                    navController.navigate(
                        hardwoodsWorkspaceRoute(
                            folderName,
                            HardwoodDocType.FACE_FRAME_CUT_LIST,
                            HARDWOODS_RIP_CUT_LIST_ROW_ID
                        )
                    ) {
                        launchSingleTop = true
                    }
                },
```
→
```kotlin
                onOpenRipCutList = {
                    navController.navigate(
                        hardwoodsWorkspaceRoute(
                            folderName,
                            HardwoodDocType.FACE_FRAME_CUT_LIST,
                            HARDWOODS_RIP_CUT_LIST_ROW_ID,
                            com.kkc.sheettracker.data.models.HiddenMaterialsMode.HARDWOODS
                        )
                    ) {
                        launchSingleTop = true
                    }
                },
```

**`MultiBackStackNavigation` (SPECIALTY — 4 call sites, all inside `SpecialtyJobDetailScreen`'s callbacks around line 1719-1785):**

`onOpenDoorPanels` (~1719-1729):
```kotlin
                onOpenDoorPanels = {
                    navController.navigate(
                        hardwoodsWorkspaceRoute(
                            folderName,
                            HardwoodDocType.DOOR_CUT_LIST,
                            HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID
                        )
                    ) {
                        launchSingleTop = true
                    }
                },
```
→
```kotlin
                onOpenDoorPanels = {
                    navController.navigate(
                        hardwoodsWorkspaceRoute(
                            folderName,
                            HardwoodDocType.DOOR_CUT_LIST,
                            HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID,
                            com.kkc.sheettracker.data.models.HiddenMaterialsMode.SPECIALTY
                        )
                    ) {
                        launchSingleTop = true
                    }
                },
```

`onOpenSawRipList` (~1730-1737):
```kotlin
                onOpenSawRipList = {
                    navController.navigate(
                        hardwoodsWorkspaceRoute(
                            folderName,
                            HardwoodDocType.DOOR_CUT_LIST,
                            HARDWOODS_SAW_RIP_LIST_ROW_ID
                        )
                    ) { launchSingleTop = true }
                },
```
→
```kotlin
                onOpenSawRipList = {
                    navController.navigate(
                        hardwoodsWorkspaceRoute(
                            folderName,
                            HardwoodDocType.DOOR_CUT_LIST,
                            HARDWOODS_SAW_RIP_LIST_ROW_ID,
                            com.kkc.sheettracker.data.models.HiddenMaterialsMode.SPECIALTY
                        )
                    ) { launchSingleTop = true }
                },
```

`onOpenClosetRods` (~1739-1747):
```kotlin
                onOpenClosetRods = {
                    navController.navigate(
                        hardwoodsWorkspaceRoute(
                            folderName,
                            HardwoodDocType.CLOSET_ROD_CUT_LIST,
                            null
                        )
                    ) { launchSingleTop = true }
                },
```
→
```kotlin
                onOpenClosetRods = {
                    navController.navigate(
                        hardwoodsWorkspaceRoute(
                            folderName,
                            HardwoodDocType.CLOSET_ROD_CUT_LIST,
                            null,
                            com.kkc.sheettracker.data.models.HiddenMaterialsMode.SPECIALTY
                        )
                    ) { launchSingleTop = true }
                },
```

`SPECIALTY_DOOR_PANELS_ROUTE_PATTERN` redirect composable (~1770-1785):
```kotlin
            androidx.compose.runtime.LaunchedEffect(folderName) {
                navController.navigate(
                    hardwoodsWorkspaceRoute(
                        folderName,
                        HardwoodDocType.DOOR_CUT_LIST,
                        HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID
                    )
                ) {
                    launchSingleTop = true
                }
            }
```
→
```kotlin
            androidx.compose.runtime.LaunchedEffect(folderName) {
                navController.navigate(
                    hardwoodsWorkspaceRoute(
                        folderName,
                        HardwoodDocType.DOOR_CUT_LIST,
                        HARDWOODS_DOOR_PANELS_SHEET_FILTER_ROW_ID,
                        com.kkc.sheettracker.data.models.HiddenMaterialsMode.SPECIALTY
                    )
                ) {
                    launchSingleTop = true
                }
            }
```

**`LegacySingleStackNavigation` — apply the identical 8 edits at the mirrored call sites** (same surrounding code, only local variable names like `legacyAdminSyncConfig` differ elsewhere in the same composables — these navigate-call edits are byte-identical to the `MultiBackStackNavigation` ones above):

- ~2994 `onHardwoodsResultClick`/search jump → HARDWOODS (mirrors line ~1012)
- ~3115 `SpecialtyJobDetailScreen.onOpenDoorPanels` → SPECIALTY (mirrors line ~1721)
- ~3126 `SpecialtyJobDetailScreen.onOpenSawRipList` → SPECIALTY (mirrors line ~1732)
- ~3135 `SpecialtyJobDetailScreen.onOpenClosetRods` → SPECIALTY (mirrors line ~1741)
- ~3197 `SPECIALTY_DOOR_PANELS_ROUTE_PATTERN` redirect → SPECIALTY (mirrors line ~1777)
- ~3353 `HardwoodsJobDetailScreen.onOpenWorkspace` → HARDWOODS (mirrors line ~1938)
- ~3359 `HardwoodsJobDetailScreen.onOpenRipCutList` → HARDWOODS (mirrors line ~1944)
- ~3534 `HardwoodsSearchScreen.onResultClick` → HARDWOODS (mirrors line ~1568's role, but note this one is at the equivalent of a different MultiBackStack call site — `HardwoodsSearchScreen` lives inside `SearchTabHost`; the point is it is a Hardwoods-context navigation, same as its 4 HARDWOODS siblings above)

Apply the same edit shape shown in Step 5's `MultiBackStackNavigation` examples to each.

- [ ] **Step 6: Run the route test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.HardwoodsWorkspaceRouteTest"`
Expected: 2 tests passed

- [ ] **Step 7: Compile the whole module to catch any missed call site**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL — a missed call site fails this step with "no value passed for parameter 'mode'", not a silently-wrong route

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/test/java/com/kkc/sheettracker/navigation/HardwoodsWorkspaceRouteTest.kt
git commit -m "feat: thread HiddenMaterialsMode through the shared workspace route"
```

---

### Task 3: Decode the mode and thread `adminSyncConfig` into `HardwoodsWorkspaceScreen`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

`HardwoodsWorkspaceScreen`'s signature gains two params: `hiddenMaterialsMode: HiddenMaterialsMode` (which mode's hide state to show/filter) and `adminSyncConfig: AdminSyncConfig` (needed by Task 4's live client and Task 6's REST write — both nav stacks already build an `AdminSyncConfig` locally and pass it to sibling screens via `archiveClientFactory`, so this reuses the existing instance rather than constructing a new one).

- [ ] **Step 1: Add the new params to `HardwoodsWorkspaceScreen`'s signature**

In `HardwoodsWorkspaceScreen.kt`, find (around line 349-367):

```kotlin
fun HardwoodsWorkspaceScreen(
    scanCoordinator: HardwoodsScanCoordinator,
    hardwoodsRepository: HardwoodsRepository,
    hardwoodsProgressStore: HardwoodsProgressStore,
    sheetRipProgressStore: SheetRipProgressStore,
    jobRepository: JobRepository,
    jobFolderName: String,
    initialDocType: HardwoodDocType,
    initialRowId: String?,
    continuousScrollDefault: Boolean = false,
    isDarkTheme: Boolean,
    isClockedInHere: Boolean = false,
    onClockIn: (jobNumber: String, jobName: String) -> Unit = { _, _ -> },
    onOpenThreeDTarget: (cabinet: String?, assemblyPage: Int?, plansPage: Int?, room: String?) -> Unit,
    onBack: () -> Unit,
    clockInState: ClockInState? = null,
    overridePdfMarkupStore: PdfMarkupStore? = null,
    pdfMarkupReadOnly: Boolean = false
) {
```

Replace with:

```kotlin
fun HardwoodsWorkspaceScreen(
    scanCoordinator: HardwoodsScanCoordinator,
    hardwoodsRepository: HardwoodsRepository,
    hardwoodsProgressStore: HardwoodsProgressStore,
    sheetRipProgressStore: SheetRipProgressStore,
    jobRepository: JobRepository,
    jobFolderName: String,
    initialDocType: HardwoodDocType,
    initialRowId: String?,
    hiddenMaterialsMode: HiddenMaterialsMode,
    adminSyncConfig: AdminSyncConfig,
    continuousScrollDefault: Boolean = false,
    isDarkTheme: Boolean,
    isClockedInHere: Boolean = false,
    onClockIn: (jobNumber: String, jobName: String) -> Unit = { _, _ -> },
    onOpenThreeDTarget: (cabinet: String?, assemblyPage: Int?, plansPage: Int?, room: String?) -> Unit,
    onBack: () -> Unit,
    clockInState: ClockInState? = null,
    overridePdfMarkupStore: PdfMarkupStore? = null,
    pdfMarkupReadOnly: Boolean = false
) {
```

Add the two new imports next to the existing `com.kkc.sheettracker.data.*` imports (around line 136-146):

```kotlin
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
```

- [ ] **Step 2: Wire the `MultiBackStackNavigation` call site**

In `NavGraph.kt`, find the route composable updated in Task 2 (around line 1991-2000) and decode the mode argument, then pass both new params. Find:

```kotlin
        ) { backStack ->
            val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            val rawDocType = URLDecoder.decode(backStack.arguments?.getString("docType") ?: "", "UTF-8")
            val docType = runCatching { HardwoodDocType.valueOf(rawDocType) }.getOrDefault(HardwoodDocType.FACE_FRAME_CUT_LIST)
            val rowIdArg = URLDecoder.decode(backStack.arguments?.getString("startPage") ?: "", "UTF-8")
            val rowId = rowIdArg.takeIf { it.isNotBlank() && it != "_" }
            val isClockedInHere = clockInState.snapshot.isActive &&
                clockInState.snapshot.folderName == folderName &&
                clockInState.snapshot.tabType == "hardwoods"
            HardwoodsWorkspaceScreen(
                scanCoordinator = hardwoodsScanCoordinator,
                hardwoodsRepository = hardwoodsRepository,
                hardwoodsProgressStore = hardwoodsProgressStore,
                sheetRipProgressStore = sheetRipProgressStore,
                jobRepository = jobRepository,
                jobFolderName = folderName,
                initialDocType = docType,
                initialRowId = rowId,
                continuousScrollDefault = continuousScrollDefault,
```

Replace with:

```kotlin
        ) { backStack ->
            val folderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
            val rawDocType = URLDecoder.decode(backStack.arguments?.getString("docType") ?: "", "UTF-8")
            val docType = runCatching { HardwoodDocType.valueOf(rawDocType) }.getOrDefault(HardwoodDocType.FACE_FRAME_CUT_LIST)
            val rowIdArg = URLDecoder.decode(backStack.arguments?.getString("startPage") ?: "", "UTF-8")
            val rowId = rowIdArg.takeIf { it.isNotBlank() && it != "_" }
            val rawHiddenMaterialsMode = URLDecoder.decode(backStack.arguments?.getString("hiddenMaterialsMode") ?: "", "UTF-8")
            val hiddenMaterialsMode = runCatching {
                com.kkc.sheettracker.data.models.HiddenMaterialsMode.valueOf(rawHiddenMaterialsMode)
            }.getOrDefault(com.kkc.sheettracker.data.models.HiddenMaterialsMode.HARDWOODS)
            val isClockedInHere = clockInState.snapshot.isActive &&
                clockInState.snapshot.folderName == folderName &&
                clockInState.snapshot.tabType == "hardwoods"
            HardwoodsWorkspaceScreen(
                scanCoordinator = hardwoodsScanCoordinator,
                hardwoodsRepository = hardwoodsRepository,
                hardwoodsProgressStore = hardwoodsProgressStore,
                sheetRipProgressStore = sheetRipProgressStore,
                jobRepository = jobRepository,
                jobFolderName = folderName,
                initialDocType = docType,
                initialRowId = rowId,
                hiddenMaterialsMode = hiddenMaterialsMode,
                adminSyncConfig = adminSyncConfig,
                continuousScrollDefault = continuousScrollDefault,
```

(`adminSyncConfig` here is the `MultiBackStackNavigation`-local `val adminSyncConfig = remember { AdminSyncConfig.create(context) }` at line ~651, already in scope at this call site — same instance `archiveClientFactory` uses four lines away in other composables in this file.)

- [ ] **Step 3: Wire the `LegacySingleStackNavigation` call site**

Apply the identical decode-and-pass edit to the mirrored composable body in `LegacySingleStackNavigation` (around line 3406-3423), passing `legacyAdminSyncConfig` (the stack-local instance already in scope there, at line ~2554) instead of `adminSyncConfig`.

- [ ] **Step 4: Compile to verify both call sites are consistent**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: thread hiddenMaterialsMode and adminSyncConfig into HardwoodsWorkspaceScreen"
```

---

### Task 4: Build the live-synced hidden-materials state locally in the screen

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`

This wires the cross-tablet live/fallback document for `(jobFolderName, hiddenMaterialsMode)`, mirroring `AppNavigation`'s `deliveryScheduleStore`/`DeliveryScheduleLiveClient`/`DeliveryScheduleLifecycleGate` wiring in `NavGraph.kt:353-465` exactly, but scoped to this screen's own composition and lifecycle instead of the whole app's — connects when the screen enters composition or resumes, disconnects when it stops or leaves, and rebuilds when `jobFolderName` or `hiddenMaterialsMode` changes (a different job/mode is a different document). There is no unit test for this step: it is Compose lifecycle wiring around already-fully-tested building blocks (`HiddenMaterialsStateStore`, `HiddenMaterialsLiveClient`, `HiddenMaterialsLifecycleGate` each carry their own full test suites from the prior plan), exactly like this screen's existing `onClockIn`/`onOpenThreeDTarget` callback wiring has no dedicated test — verify it by device/emulator per Task 7's manual checklist.

**Read-only gating:** `HardwoodsWorkspaceScreen` is also reached from `ArchiveJobDetailHost.kt` (a read-only archived-job viewer, always passing `pdfMarkupReadOnly = true` and never using live admin-sync for anything else in that file) as well as the two live nav stacks (always `pdfMarkupReadOnly = false`). The live WebSocket connection this task adds must never open during archive viewing — the file fallback (`hiddenMaterialsRepository.fetchDocument(...)`, already read-only) is fine and should still run so archived-job filtering reflects real state, but `hiddenMaterialsClient.start()` must not be called when `pdfMarkupReadOnly` is `true`. The existing `pdfMarkupReadOnly: Boolean` parameter (already on this screen's signature, already correctly threaded from all 3 call sites) is the gate to use — no new parameter is needed.

- [ ] **Step 1: Add the new imports**

Add to the import block (near the other `com.kkc.sheettracker.data.*` imports, line ~136-146):

```kotlin
import com.kkc.sheettracker.data.HiddenMaterialsClientBinding
import com.kkc.sheettracker.data.HiddenMaterialsLifecycleGate
import com.kkc.sheettracker.data.HiddenMaterialsLiveClient
import com.kkc.sheettracker.data.HiddenMaterialsRepository
import com.kkc.sheettracker.data.HiddenMaterialsRequestStore
import com.kkc.sheettracker.data.HiddenMaterialsStateStore
import com.kkc.sheettracker.data.HiddenMaterialsVisibilityPreferencesStore
import com.kkc.sheettracker.data.isHiddenIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
```

(`kotlinx.coroutines.isActive` is required for the `kotlinx.coroutines.currentCoroutineContext().isActive` call in Step 2's wiring block below — it's an extension property that needs a real import even when the receiver expression is fully qualified. Missing this from the original plan text was caught and fixed during Task 4's implementation, commit `252e3c33`.)

- [ ] **Step 2: Add the wiring block**

In the composable body, right after the existing `val engine = remember(scanState.snapshot.basePath) { ... }` / `job by produceState<HardwoodJob?>(...)` block (after line ~400, before the `rows = remember(...)` block at line 458), insert:

```kotlin
    val hiddenMaterialsRepository = remember(scanState.snapshot.basePath) {
        HiddenMaterialsRepository(File(scanState.snapshot.basePath))
    }
    val hiddenMaterialsRequestStore = remember(scanState.snapshot.basePath) {
        HiddenMaterialsRequestStore(File(scanState.snapshot.basePath))
    }
    val hiddenMaterialsVisibilityStore = remember { HiddenMaterialsVisibilityPreferencesStore.create(context) }
    val showHiddenMaterials by hiddenMaterialsVisibilityStore.showHiddenFlow(hiddenMaterialsMode)
        .collectAsState(initial = false)
    val hiddenMaterialsTabletId = remember { prefs.getString("tablet_id", "") ?: "" }

    val hiddenMaterialsStore = remember(jobFolderName, hiddenMaterialsMode, hiddenMaterialsRepository) {
        HiddenMaterialsStateStore(
            initialDocument = HiddenMaterialsDocument(),
            fallbackLoader = { hiddenMaterialsRepository.fetchDocument(hiddenMaterialsMode, jobFolderName) }
        )
    }
    val hiddenMaterialsDocument by hiddenMaterialsStore.document.collectAsState()
    val hiddenMaterialsLifecycleGate = remember(jobFolderName, hiddenMaterialsMode) { HiddenMaterialsLifecycleGate() }
    val hiddenMaterialsClientBinding = remember(jobFolderName, hiddenMaterialsMode, hiddenMaterialsLifecycleGate) {
        HiddenMaterialsClientBinding(hiddenMaterialsLifecycleGate)
    }
    val hiddenMaterialsClient = remember(
        jobFolderName,
        hiddenMaterialsMode,
        hiddenMaterialsClientBinding,
        hiddenMaterialsStore,
        hiddenMaterialsTabletId,
        adminSyncConfig
    ) {
        HiddenMaterialsLiveClient(
            config = adminSyncConfig,
            mode = hiddenMaterialsMode,
            tabletId = hiddenMaterialsTabletId,
            onDocument = hiddenMaterialsClientBinding.documentCallback(hiddenMaterialsStore),
            onConnectionState = hiddenMaterialsClientBinding.connectionCallback(hiddenMaterialsStore)
        )
    }
    val hiddenMaterialsLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(
        hiddenMaterialsLifecycleOwner,
        jobFolderName,
        hiddenMaterialsMode,
        hiddenMaterialsClient,
        hiddenMaterialsClientBinding,
        hiddenMaterialsStore
    ) {
        val sourceToken = hiddenMaterialsLifecycleGate.bindSource()
        hiddenMaterialsClientBinding.bind(sourceToken)
        var lifecycleJob: Job? = null

        fun cancelLifecycleJob() {
            lifecycleJob?.cancel()
            lifecycleJob = null
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    val startToken = hiddenMaterialsLifecycleGate.begin(sourceToken)
                    if (startToken != 0L) {
                        cancelLifecycleJob()
                        lifecycleJob = scope.launch(Dispatchers.IO) {
                            if (!hiddenMaterialsLifecycleGate.isCurrent(sourceToken, startToken)) return@launch
                            hiddenMaterialsStore.refreshFallback()
                            if (!kotlinx.coroutines.currentCoroutineContext().isActive ||
                                !hiddenMaterialsLifecycleGate.isCurrent(sourceToken, startToken)
                            ) return@launch
                            if (!pdfMarkupReadOnly) {
                                hiddenMaterialsLifecycleGate.runIfCurrent(sourceToken, startToken) {
                                    hiddenMaterialsClient.start()
                                }
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    val stopToken = hiddenMaterialsLifecycleGate.stop(sourceToken)
                    cancelLifecycleJob()
                    hiddenMaterialsClient.stop()
                    if (stopToken != 0L) {
                        hiddenMaterialsStore.markLiveDisconnected()
                        lifecycleJob = scope.launch(Dispatchers.IO) {
                            if (hiddenMaterialsLifecycleGate.isCurrent(sourceToken, stopToken)) {
                                hiddenMaterialsStore.refreshFallback()
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
        hiddenMaterialsLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            hiddenMaterialsLifecycleOwner.lifecycle.removeObserver(observer)
            cancelLifecycleJob()
            val cleanupToken = hiddenMaterialsLifecycleGate.dispose(sourceToken)
            hiddenMaterialsClient.stop()
            if (cleanupToken != 0L) {
                hiddenMaterialsStore.markLiveDisconnected()
                scope.launch(Dispatchers.IO) {
                    if (hiddenMaterialsLifecycleGate.isCleanupCurrent(cleanupToken)) {
                        hiddenMaterialsStore.refreshFallback()
                    }
                }
            }
        }
    }
```

This reuses `scope` (the `rememberCoroutineScope()` already declared at line 377) and `prefs`/`context` (already declared at lines 374/378) — no new coroutine scope or context lookup is needed.

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (the new locals are unused until Tasks 5-7 consume them — Kotlin warns, does not fail, on unused `val`s introduced by `remember`/`collectAsState` since they're read by `by` delegation sites added later in this same task's surrounding code; if the compiler flags any of `hiddenMaterialsDocument`, `showHiddenMaterials`, `hiddenMaterialsRequestStore`, or `hiddenMaterialsTabletId` as truly unused at this point, that's expected until Tasks 5-6 add their call sites — do not delete them)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt
git commit -m "feat: wire live-synced hidden-materials state into HardwoodsWorkspaceScreen"
```

---

### Task 5: Filter hidden sections and badge them when shown

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`

Hide scope is `(docType, material)` — a whole material section, not individual rows (matches the design doc). This task skips a hidden section's `stickyHeader`/rows entirely when `showHiddenMaterials` is off, and badges it "HIDDEN" (keeping it in the dimmed-section visual language already used for skipped sections) when on.

- [ ] **Step 1: Compute `sectionHidden` and skip hidden sections when the toggle is off**

Find, in the `partSections.forEach` render loop (around line 1494-1504):

```kotlin
                        partSections.forEach { section ->
                            val sectionRows = if (showChangedOnly) {
                                section.rows.filter { row ->
                                    row.rowId in selectedDocPendingChanged
                                }
                            } else {
                                section.rows
                            }
                            if (sectionRows.isEmpty()) {
                                return@forEach
                            }
```

Replace with:

```kotlin
                        partSections.forEach { section ->
                            val sectionRows = if (showChangedOnly) {
                                section.rows.filter { row ->
                                    row.rowId in selectedDocPendingChanged
                                }
                            } else {
                                section.rows
                            }
                            if (sectionRows.isEmpty()) {
                                return@forEach
                            }
                            val sectionHidden = isHiddenIn(
                                hiddenMaterialsDocument,
                                jobFolderName,
                                selectedDoc.docType.name,
                                section.material
                            )
                            if (sectionHidden && !showHiddenMaterials) {
                                return@forEach
                            }
```

- [ ] **Step 2: Reflect `sectionHidden` in the section header's dimmed styling**

Find, a few lines later (around line 1526-1532):

```kotlin
                                SectionProgressHeader(
                                    title = section.material,
                                    itemCount = section.rows.size,
                                    done = sectionProgress.donePieces,
                                    total = sectionProgress.totalPieces,
                                    dimmed = sectionAllSkipped,
                                    skipped = sectionAllSkipped,
```

Replace with:

```kotlin
                                SectionProgressHeader(
                                    title = section.material,
                                    itemCount = section.rows.size,
                                    done = sectionProgress.donePieces,
                                    total = sectionProgress.totalPieces,
                                    dimmed = sectionAllSkipped || sectionHidden,
                                    skipped = sectionAllSkipped,
```

(`skipped` intentionally stays tied only to `sectionAllSkipped` — it drives the "SKIPPED" progress-bar color language, a distinct concept from hidden materials. The "HIDDEN" badge itself is added in Task 6's `headerActions` edit, which touches the same `SectionProgressHeader(...)` call.)

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt
git commit -m "feat: filter hidden material sections and dim them when shown"
```

---

### Task 6: Hide/unhide menu on each section header

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`

Adds the overflow menu the design doc specifies: not-hidden sections offer "Hide for this job" / "Hide for all future jobs"; hidden sections offer "Unhide for this job" / "Remove from all-jobs auto-hide". This extends the same `headerActions` slot that already hosts `MaterialSkipPill` for nailer sections — both must coexist, since `RowScope` allows multiple children.

**Read-only gating:** per Task 4's note, `HardwoodsWorkspaceScreen`'s existing `pdfMarkupReadOnly` parameter is `true` when reached from `ArchiveJobDetailHost.kt` (a read-only archived-job viewer). The menu button added below must not render at all in that case — archive viewing must not offer a way to mutate hide state. Step 3 renders `HiddenMaterialMenuButton` only `if (!pdfMarkupReadOnly)`.

- [ ] **Step 1: Add the `MoreVert` icon import**

Add to the icons import block (near line 48-53):

```kotlin
import androidx.compose.material.icons.filled.MoreVert
```

- [ ] **Step 2: Add the `HiddenMaterialMenuButton` composable**

Add this new private composable directly above `MaterialSkipPill` (around line 2431, so it sits with its sibling small header-action composables):

```kotlin
@Composable
private fun HiddenMaterialMenuButton(
    hidden: Boolean,
    onAction: (action: String, scope: String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Hide material options",
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            if (hidden) {
                DropdownMenuItem(
                    text = { Text("Unhide for this job") },
                    onClick = {
                        expanded = false
                        onAction("unhide", "job")
                    }
                )
                DropdownMenuItem(
                    text = { Text("Remove from all-jobs auto-hide") },
                    onClick = {
                        expanded = false
                        onAction("unhide", "global")
                    }
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Hide for this job") },
                    onClick = {
                        expanded = false
                        onAction("hide", "job")
                    }
                )
                DropdownMenuItem(
                    text = { Text("Hide for all future jobs") },
                    onClick = {
                        expanded = false
                        onAction("hide", "global")
                    }
                )
            }
        }
    }
}
```

- [ ] **Step 3: Wire it into `headerActions`, alongside the existing skip pill and the "HIDDEN" badge**

Find (around line 1543-1562, right after Task 5's `dimmed = sectionAllSkipped || sectionHidden` edit in the same `SectionProgressHeader(...)` call):

```kotlin
                                    headerActions = if (isNailerDoc) {
                                        {
                                            MaterialSkipPill(
                                                skipped = sectionAllSkipped,
                                                onClick = {
                                                    val nextSkipped = !sectionAllSkipped
                                                    sectionRows.forEach { row ->
                                                        hardwoodsProgressStore.setSkipped(
                                                            jobFolderName = jobFolderName,
                                                            docType = selectedDoc.docType.name,
                                                            rowId = row.rowId,
                                                            skipped = nextSkipped
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    } else {
                                        null
                                    },
```

Replace with:

```kotlin
                                    headerActions = {
                                        if (isNailerDoc) {
                                            MaterialSkipPill(
                                                skipped = sectionAllSkipped,
                                                onClick = {
                                                    val nextSkipped = !sectionAllSkipped
                                                    sectionRows.forEach { row ->
                                                        hardwoodsProgressStore.setSkipped(
                                                            jobFolderName = jobFolderName,
                                                            docType = selectedDoc.docType.name,
                                                            rowId = row.rowId,
                                                            skipped = nextSkipped
                                                        )
                                                    }
                                                }
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        if (sectionHidden) {
                                            Text(
                                                text = "HIDDEN",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.padding(end = 6.dp)
                                            )
                                        }
                                        if (!pdfMarkupReadOnly) {
                                            HiddenMaterialMenuButton(
                                                hidden = sectionHidden,
                                                onAction = { action, requestScope ->
                                                    scope.launch {
                                                        submitHiddenMaterialsAction(
                                                            serverUrl = adminSyncConfig.getServerUrl(),
                                                            requestStore = hiddenMaterialsRequestStore,
                                                            mode = hiddenMaterialsMode,
                                                            action = action,
                                                            scope = requestScope,
                                                            jobId = jobFolderName,
                                                            docType = selectedDoc.docType.name,
                                                            material = section.material,
                                                            tabletId = hiddenMaterialsTabletId,
                                                            requestedAt = java.time.Instant.now().toString()
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    },
```

Add the one remaining new import, next to the other `com.kkc.sheettracker.data.*` imports:

```kotlin
import com.kkc.sheettracker.data.submitHiddenMaterialsAction
```

- [ ] **Step 4: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt
git commit -m "feat: add hide/unhide menu to material section headers"
```

---

### Task 7: "Show hidden materials" toolbar toggle

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`

- [ ] **Step 1: Add the `VisibilityOff` icon import**

Add to the icons import block (near line 51, next to the already-imported `Icons.Default.Visibility`):

```kotlin
import androidx.compose.material.icons.filled.VisibilityOff
```

- [ ] **Step 2: Add the toggle button to the top app bar's actions**

Find, in `KKCTopAppBar`'s `actions = { ... }` block (around line 1027-1038):

```kotlin
                    TextButton(onClick = { showReferencePane = !showReferencePane }) {
                        Text(if (showReferencePane) "Hide PDF" else "Show PDF")
                    }
                    if (showReferencePane) {
                        IconButton(onClick = { continuousScrollEnabled = !continuousScrollEnabled }) {
                            Icon(
                                if (continuousScrollEnabled) Icons.Default.ViewDay else Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = if (continuousScrollEnabled) "Switch to single page" else "Switch to continuous scroll",
                                tint = if (continuousScrollEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
```

Replace with:

```kotlin
                    TextButton(onClick = { showReferencePane = !showReferencePane }) {
                        Text(if (showReferencePane) "Hide PDF" else "Show PDF")
                    }
                    if (showReferencePane) {
                        IconButton(onClick = { continuousScrollEnabled = !continuousScrollEnabled }) {
                            Icon(
                                if (continuousScrollEnabled) Icons.Default.ViewDay else Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = if (continuousScrollEnabled) "Switch to single page" else "Switch to continuous scroll",
                                tint = if (continuousScrollEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    IconButton(onClick = {
                        scope.launch {
                            hiddenMaterialsVisibilityStore.setShowHidden(hiddenMaterialsMode, !showHiddenMaterials)
                        }
                    }) {
                        Icon(
                            if (showHiddenMaterials) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (showHiddenMaterials) "Hide hidden materials" else "Show hidden materials",
                            tint = if (showHiddenMaterials) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
```

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run the full existing test suite for regressions**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: all tests pass (aside from the pre-existing, unrelated `MixOperationCoordinatorTest` flake documented when the prior plan closed — rerun that one test alone with `--rerun-tasks` if it fails here too, to confirm it's the same flake and not a real regression)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt
git commit -m "feat: add show-hidden-materials toolbar toggle"
```

- [ ] **Step 6: Manual/device verification**

Deploy to a tablet (or two, for the cross-tablet checks) per this repo's `CLAUDE.md` build instructions, then confirm the full checklist from the design doc's Testing scope section:

1. Open a job's Hardwoods cutlist screen. Long-press (overflow menu) a material section, choose "Hide for this job" — the section disappears immediately.
2. Toggle "Show hidden materials" on — the hidden section reappears with a "HIDDEN" badge and dimmed styling. Its menu now offers "Unhide for this job" / "Remove from all-jobs auto-hide".
3. Choose "Unhide for this job" — the badge clears; turning the toggle off still shows the section normally (it's no longer hidden).
4. From the same job, open Specialty → Door Panels (or Saw Rip List / Closet Rods) for a material also hidden in Hardwoods mode global scope: confirm hiding a material on the Hardwoods cutlist screen does **not** hide it on the Specialty door panels screen, and vice versa (independent mode state, same underlying screen).
5. Hide a material "for all future jobs" (global scope) from tablet A; on tablet B (same job or a different job), confirm it disappears within a few seconds via the live WebSocket path.
6. Put tablet A in airplane mode, hide/unhide a material — confirm the action is still queued (no crash, no visible error) and takes effect on other tablets once tablet A's sidecar file syncs and the backend's poller picks it up.
7. Force-kill and reopen the app on a tablet mid-hidden-state — confirm the fallback file read on screen entry shows the correct current hidden state (not stale).

---

### Task 8: Update the `kkc-metadata-map` skill

**Files:**
- Modify: `C:\Users\chadc\.claude\skills\kkc-metadata-map` (the canonical copy — **not** any per-repo mirror, which a sync hook overwrites)

Per the design doc's Follow-up section and this feature's original scoping instruction, this is the final task. The map currently has no entries for `hidden_materials_global.json` / `hidden_materials.json` or the `.metadata\specialty\` subtree.

- [ ] **Step 1: Read the current canonical skill file**

Open `C:\Users\chadc\.claude\skills\kkc-metadata-map` and locate its Ownership Map, Symptom Routing, and Common Mistakes sections.

- [ ] **Step 2: Add Ownership Map rows**

Add four rows (two files × two modes), following the existing table's column conventions:

- `Y:\Ready Jobs\.metadata\hardwoods\hidden_materials_global.json` — owner: Hours Tracker backend (`routes/hidden_materials_store.py`); readers: KKCSheetTracker tablets (Hardwoods mode, read-only)
- `Y:\Ready Jobs\.metadata\specialty\hidden_materials_global.json` — owner: Hours Tracker backend (`routes/hidden_materials_store.py`); readers: KKCSheetTracker tablets (Specialty mode, read-only)
- `Y:\Ready Jobs\<job>\.metadata\hardwoods\hidden_materials.json` — owner: Hours Tracker backend; readers: KKCSheetTracker tablets (Hardwoods mode, read-only, one job's file per job folder)
- `Y:\Ready Jobs\<job>\.metadata\specialty\hidden_materials.json` — owner: Hours Tracker backend; readers: KKCSheetTracker tablets (Specialty mode, read-only, one job's file per job folder)

Note explicitly that `.metadata\specialty\` is a new subtree distinct from the existing `.metadata\admin\specialty_items.json`/`.tracker` domain (Hours-Tracker-owned custom specialty items) — this one is Android-tablet-owned hidden-materials state only.

- [ ] **Step 3: Add Symptom Routing entries**

Add entries for: "a material a tablet hid is still visible on another tablet" (check the mode's global/job file content, then the live WebSocket connection state, then the sidecar-request poller logs) and "hiding a material in Hardwoods also hid it in Specialty" (mode segregation bug — check `hiddenMaterialsModeSubdir`/route mode threading, since the two modes should never share a file).

- [ ] **Step 4: Add a Common Mistakes entry**

Note that `HardwoodsWorkspaceScreen` is a single shared composable/route reached from both the Hardwoods menu and the Specialty menu (Door Panels/Saw Rip List/Closet Rods) — any future change to this screen must consider both entry points and both `HiddenMaterialsMode` values, since there is no separate "Specialty cutlist screen" in code despite the design doc's UI section describing them as if there were two.

- [ ] **Step 5: Save and verify the sync hook has not silently reverted the change**

After saving, re-open the file to confirm the edits persisted (the per-repo mirrors are overwritten by a sync hook — editing the canonical copy is what makes the change durable).

- [ ] **Step 6: Commit** (in whichever repo/location the canonical skill file's own version control lives, if any — if it is not under git, this step is a no-op; just confirm the file is saved)

---

## End of Plan

All eight tasks together complete the hidden-materials feature end-to-end: Plan 1 (backend, shipped), Plan 2 (Android data layer, shipped), and this plan (UI wiring for both Hardwoods and Specialty entry points into the one shared cutlist screen). After Task 7 lands and passes manual/device verification, invoke `finishing-a-development-branch` for this plan.
