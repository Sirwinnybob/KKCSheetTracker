# Archive Job Detail Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace both archive-detail placeholders with the matching live job experience, rooted in the restored cache and silently persisting nothing.

**Architecture:** Expand `ArchiveSession` into a complete read-only dependency graph. A new nested `ArchiveJobDetailHost` owns one session and all archive child destinations, so none can fall through to the global live navigation graph. Existing detail composables remain unchanged.

**Tech Stack:** Kotlin, Jetpack Compose Navigation, coroutines/StateFlow, Android local unit tests, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-20-archive-job-detail-screens-design.md`

## Global Constraints

- Reuse the four existing detail screens; do not create a restricted archive UI.
- Every archive write no-ops: CNC, Hardwoods, Specialty, PDF markup, sheet-rip, and tablet Specialty items.
- Read only from `context.cacheDir/archive-cache/<archiveJobId>` and the explicitly supplied restored `folderName`.
- Archive is removed from the bottom navigation and added immediately after Safety / SDS in the Library grid in both navigation variants.
- Both Library entry points must invoke the same archive host; archive child destinations must never use live dependencies.
- Do not add services, network dependencies, or tablet archive-trigger UI.
- Do not install or uninstall an Android app during verification.

---

## File Structure

- Modify `data/SheetRipProgressStore.kt` and `data/TabletSpecialtyItemsStore.kt` to establish missing no-op boundaries.
- Modify `data/ScanCoordinator.kt`, `data/HardwoodsScanCoordinator.kt`, `data/AssemblyScanCoordinator.kt`, `data/SpecialtyScanCoordinator.kt`, and `data/ArchiveSession.kt` to compose and dispose archive dependencies.
- Modify the four viewer/workspace composables for injected markup stores and view-only live fallback.
- Create `navigation/ThreeDRouteResolver.kt` and `navigation/ArchiveJobDetailHost.kt`.
- Modify `ui/standards/StandardsHubScreen.kt`, `navigation/NavigationCoordinator.kt`, and `navigation/NavGraph.kt` to move Archive from bottom navigation into the Library flow.
- Extend data tests and add navigation source-wiring tests.

### Task 1: Make the Specialty auxiliary stores read-only

**Files:**

- Modify: `app/src/main/java/com/kkc/sheettracker/data/SheetRipProgressStore.kt:17-76`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/TabletSpecialtyItemsStore.kt:17-82`
- Modify: `app/src/test/java/com/kkc/sheettracker/data/SheetRipProgressStoreTest.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/data/TabletSpecialtyItemsStoreTest.kt`

**Interfaces:**

- Produces `SheetRipProgressStore(baseDir: File, readOnly: Boolean = false)`.
- Produces `TabletSpecialtyItemsStore(baseDir: File, tabletId: String, readOnly: Boolean = false)`.
- With `readOnly = true`, `setDone`, `saveItem`, `deleteItem`, and `deleteItemTombstone` return before bookkeeping, directory creation, or I/O.

- [ ] **Step 1: Write the failing no-op tests.**

~~~kotlin
@Test
fun readOnly_setDone_doesNotCreateSheetRipFile() = runBlocking {
    val baseDir = Files.createTempDirectory("sheet-rip-read-only").toFile()
    val store = SheetRipProgressStore(baseDir, readOnly = true)
    store.setDone("1234 - Test Job", "item-1", done = true)
    assertTrue(store.loadDone("1234 - Test Job").isEmpty())
    assertFalse(baseDir.resolve("1234 - Test Job/.metadata/admin/sheet_rip_done.json").exists())
}

@Test
fun readOnly_mutations_doNotCreateTabletItemsSidecar() = runBlocking {
    val baseDir = Files.createTempDirectory("tablet-items-read-only").toFile()
    val store = TabletSpecialtyItemsStore(baseDir, "tablet-7", readOnly = true)
    store.saveItem("1234 - Test Job", testTabletItem("item-1"))
    store.deleteItem("1234 - Test Job", "item-1")
    store.deleteItemTombstone("1234 - Test Job", "item-1")
    assertTrue(store.loadAllItems("1234 - Test Job").isEmpty())
    assertFalse(baseDir.resolve("1234 - Test Job/.metadata/admin/tablet_items_tablet-7.json").exists())
}
~~~

- [ ] **Step 2: Run it and confirm the constructor argument is missing.**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SheetRipProgressStoreTest" --tests "com.kkc.sheettracker.data.TabletSpecialtyItemsStoreTest"`

Expected: compilation fails because neither constructor accepts `readOnly`.

- [ ] **Step 3: Implement the smallest persistence guards.**

~~~kotlin
class SheetRipProgressStore(private val baseDir: File, private val readOnly: Boolean = false) {
    suspend fun setDone(
        jobFolderName: String,
        itemId: String,
        done: Boolean,
        projectionRevision: Long? = null,
    ) {
        if (readOnly) return
        // preserve existing revision registration and atomic write
    }
}

class TabletSpecialtyItemsStore(
    private val baseDir: File,
    val tabletId: String,
    private val readOnly: Boolean = false,
) {
    suspend fun saveItem(jobFolderName: String, item: TabletSpecialtyItem) {
        if (readOnly) return
        // preserve existing mutex and writeItems flow
    }
}
~~~

Add the same first-line guard to both delete methods. Keep default values false so all live callers stay writable.

- [ ] **Step 4: Run focused regressions.**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SheetRipProgressStoreTest" --tests "com.kkc.sheettracker.data.TabletSpecialtyItemsStoreTest" --tests "com.kkc.sheettracker.data.SpecialtyStateStoreTest"`

Expected: all tests pass.

- [ ] **Step 5: Commit.**

~~~powershell
git add app/src/main/java/com/kkc/sheettracker/data/SheetRipProgressStore.kt app/src/main/java/com/kkc/sheettracker/data/TabletSpecialtyItemsStore.kt app/src/test/java/com/kkc/sheettracker/data/SheetRipProgressStoreTest.kt app/src/test/java/com/kkc/sheettracker/data/TabletSpecialtyItemsStoreTest.kt
git commit -m "feat: add read-only specialty stores"
~~~

### Task 2: Complete and dispose the archive dependency session

**Files:**

- Modify: `app/src/main/java/com/kkc/sheettracker/data/ArchiveSession.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/ScanCoordinator.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/HardwoodsScanCoordinator.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/AssemblyScanCoordinator.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/SpecialtyScanCoordinator.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/data/ArchiveSessionTest.kt`

**Interfaces:**

- Each coordinator adds `fun close()`.
- `ArchiveSession` exposes archive-scoped repositories, sheet-rip/tablet-item stores, all four coordinators, `AppStateStore`, `AssemblyStateStore`, `SpecialtyStateStore`, and `fun close()`.
- `ArchiveSession.create` uses `JobRepository(baseDir, isDebugBuild, unifiedEngine)` and `SpecialtyRepository(baseDir, specialtyProgressStore, unifiedEngine)`.

- [ ] **Step 1: Write the failing session graph test.**

~~~kotlin
@Test
fun session_exposesArchiveScopedDependenciesAndNoOpsExtraSpecialtyWrites() = runBlocking {
    val session = createSessionWithRestoredJob()
    session.sheetRipProgressStore.setDone(session.folderName, "rip-1", true)
    session.tabletSpecialtyItemsStore.saveItem(session.folderName, testTabletItem("item-1"))
    assertEquals(session.baseDir, session.jobRepository.getJobDirectory(session.folderName).parentFile)
    assertTrue(session.sheetRipProgressStore.loadDone(session.folderName).isEmpty())
    assertTrue(session.tabletSpecialtyItemsStore.loadAllItems(session.folderName).isEmpty())
    session.close()
    session.close()
}
~~~

- [ ] **Step 2: Run it before implementation.**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.ArchiveSessionTest"`

Expected: compilation fails on the missing session members and `close()`.

- [ ] **Step 3: Add session composition and deterministic cancellation.**

~~~kotlin
private val scopeJob = SupervisorJob()
private val scope = CoroutineScope(scopeJob + Dispatchers.IO)

fun close() {
    scopeJob.cancel()
}
~~~

Apply that pattern to each coordinator. In `ArchiveSession.create`, build read-only stores, engine, repositories, coordinators, then derived state stores in the dependency order used by `NavGraph.kt`. `ArchiveSession.close()` delegates to all four coordinators and remains idempotent.

- [ ] **Step 4: Run data regressions.**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.ArchiveSessionTest" --tests "com.kkc.sheettracker.data.SpecialtyStateStoreTest"`

Expected: all tests pass.

- [ ] **Step 5: Commit.**

~~~powershell
git add app/src/main/java/com/kkc/sheettracker/data/ArchiveSession.kt app/src/main/java/com/kkc/sheettracker/data/ScanCoordinator.kt app/src/main/java/com/kkc/sheettracker/data/HardwoodsScanCoordinator.kt app/src/main/java/com/kkc/sheettracker/data/AssemblyScanCoordinator.kt app/src/main/java/com/kkc/sheettracker/data/SpecialtyScanCoordinator.kt app/src/test/java/com/kkc/sheettracker/data/ArchiveSessionTest.kt
git commit -m "feat: compose archive job session"
~~~

### Task 3: Inject archive-safe PDF markup stores

**Files:**

- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/navigation/ArchiveViewerWiringTest.kt`

**Interfaces:** Each viewer adds `overridePdfMarkupStore: PdfMarkupStore? = null` and `pdfMarkupReadOnly: Boolean = false`. The override wins; otherwise the current preference-derived fallback uses `readOnly = pdfMarkupReadOnly`.

- [ ] **Step 1: Write the failing source-wiring test.**

~~~kotlin
@Test
fun eachViewerHasAnInjectedStoreAndReadOnlyFallback() {
    viewerSources().forEach { source ->
        assertTrue(source.contains("overridePdfMarkupStore: PdfMarkupStore? = null"))
        assertTrue(source.contains("readOnly = pdfMarkupReadOnly"))
    }
}

@Test
fun bothLiveNavGraphsPassViewOnlyStateToViewerFallbacks() {
    assertEquals(8, Regex("pdfMarkupReadOnly = isViewOnlyMode").findAll(navGraphSource()).count())
}
~~~

Use the source-reading pattern already established by `NavGraphCncSyncWiringTest`.

- [ ] **Step 2: Run it before implementation.**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.navigation.ArchiveViewerWiringTest"`

Expected: assertion failure for missing parameters.

- [ ] **Step 3: Implement all four seams and live wiring.**

~~~kotlin
val pdfMarkupStore = overridePdfMarkupStore ?: remember(markupStoreConfig, pdfMarkupReadOnly) {
    markupStoreConfig?.let { PdfMarkupStore(File(it.basePath), it.tabletId, readOnly = pdfMarkupReadOnly) }
}
~~~

Retain each screen's existing preferences lookup only in the fallback. Thread `isViewOnlyMode` into `LegacySingleStackNavigation` if needed, and pass it to all eight live viewer call sites.

- [ ] **Step 4: Run markup and wiring tests.**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.navigation.ArchiveViewerWiringTest" --tests "com.kkc.sheettracker.data.PdfMarkupStoreTest"`

Expected: all tests pass.

- [ ] **Step 5: Commit.**

~~~powershell
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/test/java/com/kkc/sheettracker/navigation/ArchiveViewerWiringTest.kt
git commit -m "feat: inject archive PDF markup store"
~~~

### Task 4: Build and wire the shared archive detail host

**Files:**

- Create: `app/src/main/java/com/kkc/sheettracker/navigation/ThreeDRouteResolver.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/navigation/ArchiveJobDetailHost.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/standards/StandardsHubScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavigationCoordinator.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:555-651,968-974,1983-2096,2197-2332,3201-3266,3438-3528`
- Create: `app/src/test/java/com/kkc/sheettracker/navigation/ArchiveJobDetailHostWiringTest.kt`

**Interfaces:**

- `resolveDefaultThreeDTarget(baseDir: File, jobRepository: JobRepository, jobFolderName: String)` and `resolveSpecialtyThreeDRoom(baseDir: File, jobFolderName: String)` replace the current string-root helpers.
- `StandardsHubScreen` accepts `onOpenArchive: () -> Unit`; its `ARCHIVE` tile follows `SAFETY` in `StandardsTile.entries` and uses the hub's existing card behavior.
- `ArchiveLibraryHost` owns a local nav controller for the archive library and archive-job host; it is entered through `standards/archive` in each Standards flow.
- `ArchiveJobDetailHost` accepts route args, tablet/theme/viewer settings, `workMode`, `appStateFlags`, and `onExitArchive`.
- Its nested routes are `detail`, `viewer/{pdfFilename}/{startPage}`, `referenceViewer/{docType}/{startPage}`, `hardwoods/workspace/{docType}/{rowId}`, and the existing full `assembly/viewer/{folderName}/{startPageAssembly}/{startPagePlans}?source={source}&cab={cab}&room={room}&layout={layout}&first={first}&second={second}&hideUi={hideUi}` argument pattern adapted to the host's fixed archive folder.

- [ ] **Step 1: Write failing host and root-route tests.**

~~~kotlin
@Test
fun archiveHostOwnsAndClosesOneSession() {
    val source = archiveHostSource()
    assertTrue(source.contains("remember(archiveJobId, folderName, contentVersion"))
    assertTrue(source.contains("DisposableEffect(session)"))
    assertTrue(source.contains("onDispose { session.close() }"))
}

@Test
fun bothArchiveRootRoutesDelegateToTheSharedHost() {
    val source = navGraphSource()
    assertEquals(2, Regex("ArchiveLibraryHost\\(").findAll(source).count())
    assertFalse(source.contains("Archive job detail view not yet available"))
}
~~~

Also assert the host contains all four detail composables, `session.pdfMarkupStore`, `session.sheetRipProgressStore`, `session.baseDir.absolutePath`, and no `ClockInState`. Add assertions that `StandardsTile.SAFETY` precedes `StandardsTile.ARCHIVE`, both Standards callers pass `onOpenArchive`, and neither `visibleDestinations` list nor `NavigationCoordinator` contains `ARCHIVE`.

- [ ] **Step 2: Run it before the host exists.**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.navigation.ArchiveJobDetailHostWiringTest"`

Expected: source-file lookup failure.

- [ ] **Step 3: Implement session ownership, archive children, and both root integrations.**

~~~kotlin
val session = remember(archiveJobId, folderName, contentVersion, cacheJobParentDir, tabletId, isDebugBuild) {
    ArchiveSession.create(archiveJobId, contentVersion, cacheJobParentDir, folderName, tabletId, isDebugBuild)
}
DisposableEffect(session) { onDispose { session.close() } }
LaunchedEffect(session, workMode) {
    when (workMode) {
        WorkMode.CNC -> session.scanCoordinator.refresh(RefreshReason.APP_START, force = true)
        WorkMode.HARDWOODS -> session.hardwoodsScanCoordinator.refresh(RefreshReason.APP_START, force = true)
        WorkMode.ASSEMBLY -> session.assemblyScanCoordinator.refresh(RefreshReason.APP_START, force = true)
        WorkMode.SPECIALTY -> session.specialtyScanCoordinator.refresh(RefreshReason.APP_START, force = true)
    }
}
~~~

Use a nested `rememberNavController()`. Supply only session dependencies to all five destinations; archive callbacks stay in the nested graph; unavailable material returns to `detail`; 3D uses the extracted resolver with `session.baseDir`; viewers receive `overridePdfMarkupStore = session.pdfMarkupStore`; Hardwoods workspace receives `session.sheetRipProgressStore`. Pass no live `ClockInState`.

Rename the top-level `ArchiveTabHost` to `ArchiveLibraryHost`, let it own its local navigation controller, and call it from `standards/archive` in both Standards flows. Its library-open callback navigates to its local archive-job route, which renders `ArchiveJobDetailHost`. Add `ARCHIVE` after `SAFETY` in `StandardsTile`, thread `onOpenArchive` through both callers, and remove the separate archive tab/controller/layer plus `ARCHIVE` from `NavDestination`, `TopLevelTab`, `NavigationCoordinator`, and both bottom-nav destination lists.

- [ ] **Step 4: Run focused navigation tests.**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.navigation.ArchiveJobDetailHostWiringTest" --tests "com.kkc.sheettracker.navigation.ArchiveViewerWiringTest" --tests "com.kkc.sheettracker.data.ArchiveSessionTest"`

Expected: all tests pass.

- [ ] **Step 5: Run full verification and commit.**

~~~powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
git add app/src/main/java/com/kkc/sheettracker/navigation/ThreeDRouteResolver.kt app/src/main/java/com/kkc/sheettracker/navigation/ArchiveJobDetailHost.kt app/src/main/java/com/kkc/sheettracker/navigation/NavigationCoordinator.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/main/java/com/kkc/sheettracker/ui/standards/StandardsHubScreen.kt app/src/test/java/com/kkc/sheettracker/navigation/ArchiveJobDetailHostWiringTest.kt
git commit -m "feat: wire archive job detail navigation"
~~~

Expected: both Gradle commands exit 0 and produce `app/build/outputs/apk/debug/app-debug.apk`; do not install it.
