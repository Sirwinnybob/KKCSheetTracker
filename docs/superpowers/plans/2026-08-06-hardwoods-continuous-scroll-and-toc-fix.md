# Hardwoods Continuous-Scroll Parity + Reference Viewer TOC Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the hardwoods cut list screen the same continuous-scroll toggle (and its `PdfLabelScrollbar`) that Assembly and the single-doc reference viewer already have, and fix a regression where `ReferencePdfViewerScreen` loses its Sheet Navigator/TOC access once continuous scroll is toggled on.

**Architecture:** Both parts are prop-threading fixes to existing, already-shared components — no new components, no changes to `UnifiedReferenceViewer`'s internals. Part A copies `AssemblyViewerScreen`'s/`ReferencePdfViewerScreen`'s established `continuousScrollDefault` → local `rememberSaveable` state → toggle `IconButton` → `UnifiedReferenceViewer(continuousScrollEnabled = ...)` pattern onto `HardwoodsWorkspaceScreen`, and threads the default through both of its `NavGraph.kt` call sites. Part B copies `AssemblyViewerScreen`'s external `tocRequestToken` counter pattern onto `ReferencePdfViewerScreen`, whose only TOC entry point today is an internal callback that's silently absent in continuous mode.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3.

**Spec:** [docs/superpowers/specs/2026-08-06-hardwoods-continuous-scroll-and-toc-fix-design.md](../specs/2026-08-06-hardwoods-continuous-scroll-and-toc-fix-design.md)

**Testing note:** This codebase has no unit tests covering Compose screen-level prop wiring (the one existing test for this area, `PdfLabelScrollbarTest.kt`, covers only `PdfLabelScrollbar`'s internal pure logic — not caller wiring). Every task below is verified by a Gradle compile check (`assembleDebug`) plus a final on-device manual checklist, matching the spec's own Testing section and existing project convention. No test files are created in this plan.

---

### Task 1: Add `continuousScrollDefault` param and local toggle state to `HardwoodsWorkspaceScreen`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`

- [ ] **Step 1: Add the two missing icon imports**

Add these two lines to the import block (near the other `androidx.compose.material.icons.*` imports, e.g. right after line 44 `import androidx.compose.material.icons.Icons`):

```kotlin
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ViewDay
```

- [ ] **Step 2: Add the `continuousScrollDefault` param to the function signature**

Current signature (lines 346-361):

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
    isDarkTheme: Boolean,
    isClockedInHere: Boolean = false,
    onClockIn: (jobNumber: String, jobName: String) -> Unit = { _, _ -> },
    onOpenThreeDTarget: (cabinet: String?, assemblyPage: Int?, plansPage: Int?, room: String?) -> Unit,
    onBack: () -> Unit,
    clockInState: ClockInState? = null
) {
```

Change to (new `continuousScrollDefault` param inserted after `initialRowId`):

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
    clockInState: ClockInState? = null
) {
```

- [ ] **Step 3: Add the local `continuousScrollEnabled` state next to `showReferencePane`**

Current (line 604):

```kotlin
    var showReferencePane by remember(jobFolderName) { mutableStateOf(true) }
```

Change to:

```kotlin
    var showReferencePane by remember(jobFolderName) { mutableStateOf(true) }
    var continuousScrollEnabled by rememberSaveable(jobFolderName) { mutableStateOf(continuousScrollDefault) }
```

- [ ] **Step 4: Compile check**

Run: `.\gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`. (The new param has a default value and the new state is unused so far — this step only proves no syntax/import errors before wiring it up.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt
git commit -m "feat(hardwoods): add continuousScrollDefault param and local toggle state"
```

---

### Task 2: Add the continuous-scroll toggle button to the hardwoods top bar

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`

- [ ] **Step 1: Add the toggle `IconButton` to the `actions` block**

Current (lines 1018-1020):

```kotlin
                    TextButton(onClick = { showReferencePane = !showReferencePane }) {
                        Text(if (showReferencePane) "Hide PDF" else "Show PDF")
                    }
                },
```

Change to (new `IconButton` inserted before the closing `},` of `actions`, only shown while the reference pane is visible):

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

- [ ] **Step 2: Compile check**

Run: `.\gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt
git commit -m "feat(hardwoods): add continuous-scroll toggle button to top bar"
```

---

### Task 3: Wire `continuousScrollEnabled` into the `UnifiedReferenceViewer` call

The `UnifiedReferenceViewer` call from Task 1/2 lives inside `private fun ReferencePane(...)` (`HardwoodsWorkspaceScreen.kt:2127`) — a separate composable from `HardwoodsWorkspaceScreen` itself, so the new state has to be threaded through as a parameter, not closed over directly.

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`

- [ ] **Step 1: Add `continuousScrollEnabled` to `ReferencePane`'s parameter list**

Current (lines 2127-2148):

```kotlin
private fun ReferencePane(
    modifier: Modifier,
    jobRepository: JobRepository,
    jobFolderName: String,
    cabinetIndex: CabinetSheetIndex?,
    isDarkTheme: Boolean,
    referenceDocType: ReferenceDocType,
    jumpTarget: HardwoodsJumpTarget,
    onReferenceDocTypeChange: (ReferenceDocType) -> Unit,
    onJumpTargetChange: (HardwoodsJumpTarget) -> Unit,
    currentPage: Int,
    onCurrentPageChange: (Int) -> Unit,
    roomName: String?,
    serverPort: Int,
    serverError: String?,
    onThreeDFullScreen: () -> Unit,
    onOpenIn3DApp: () -> Unit,
    markupEnabled: Boolean,
    onToggleMarkupEnabled: () -> Unit,
    markupToolState: PdfMarkupToolState,
    ownsNavBarMarkupControls: Boolean
) {
```

Change to (new param added after `onCurrentPageChange`):

```kotlin
private fun ReferencePane(
    modifier: Modifier,
    jobRepository: JobRepository,
    jobFolderName: String,
    cabinetIndex: CabinetSheetIndex?,
    isDarkTheme: Boolean,
    referenceDocType: ReferenceDocType,
    jumpTarget: HardwoodsJumpTarget,
    onReferenceDocTypeChange: (ReferenceDocType) -> Unit,
    onJumpTargetChange: (HardwoodsJumpTarget) -> Unit,
    currentPage: Int,
    onCurrentPageChange: (Int) -> Unit,
    continuousScrollEnabled: Boolean,
    roomName: String?,
    serverPort: Int,
    serverError: String?,
    onThreeDFullScreen: () -> Unit,
    onOpenIn3DApp: () -> Unit,
    markupEnabled: Boolean,
    onToggleMarkupEnabled: () -> Unit,
    markupToolState: PdfMarkupToolState,
    ownsNavBarMarkupControls: Boolean
) {
```

- [ ] **Step 2: Pass the state at `ReferencePane`'s call site**

Current (`HardwoodsWorkspaceScreen.kt:1680-1687`):

```kotlin
            ReferencePane(
                modifier = secondMod.fillMaxSize(),
                jobRepository = jobRepository,
                jobFolderName = jobFolderName,
                cabinetIndex = cabinetIndex,
                isDarkTheme = isDarkTheme,
                referenceDocType = referenceDocType,
                jumpTarget = jumpTarget,
```

Change to (new line added after `cabinetIndex`):

```kotlin
            ReferencePane(
                modifier = secondMod.fillMaxSize(),
                jobRepository = jobRepository,
                jobFolderName = jobFolderName,
                cabinetIndex = cabinetIndex,
                continuousScrollEnabled = continuousScrollEnabled,
                isDarkTheme = isDarkTheme,
                referenceDocType = referenceDocType,
                jumpTarget = jumpTarget,
```

- [ ] **Step 3: Pass it into the viewer call inside `ReferencePane`**

Current (lines 2397-2425):

```kotlin
        UnifiedReferenceViewer(
            modifier = modifier,
            displayPage = currentPage,
            onDisplayPageChange = onCurrentPageChange,
            defaultPdfFilename = defaultPdfFilename,
            pdfFileForFilename = { filename ->
                jobRepository.getJobRootPdfFile(
                    jobFolderName = jobFolderName,
                    pdfFilename = filename,
                    preferDarkMode = isDarkTheme
                )
            },
            preferDarkMode = isDarkTheme,
            virtualMapping = virtualMapping,
            navigatorCabinetToPages = navigatorCabinetToPages,
            navigatorPlanViewLabels = navigatorPlanViewLabels,
            navigatorWarningMessage = if (referenceDocType == ReferenceDocType.ASSEMBLY) {
                assemblyVirtualSanitized.warningMessage
            } else {
                null
            },
            showDocControls = docControls,
            pdfMarkupStore = pdfMarkupStore,
            pdfMarkupJobFolderName = jobFolderName,
            markupEnabled = markupEnabled,
            onToggleMarkupEnabled = onToggleMarkupEnabled,
            markupToolState = markupToolState,
            ownsNavBarMarkupControls = ownsNavBarMarkupControls
        )
```

Change to (new `continuousScrollEnabled` line added before the closing `)`):

```kotlin
        UnifiedReferenceViewer(
            modifier = modifier,
            displayPage = currentPage,
            onDisplayPageChange = onCurrentPageChange,
            defaultPdfFilename = defaultPdfFilename,
            pdfFileForFilename = { filename ->
                jobRepository.getJobRootPdfFile(
                    jobFolderName = jobFolderName,
                    pdfFilename = filename,
                    preferDarkMode = isDarkTheme
                )
            },
            preferDarkMode = isDarkTheme,
            virtualMapping = virtualMapping,
            navigatorCabinetToPages = navigatorCabinetToPages,
            navigatorPlanViewLabels = navigatorPlanViewLabels,
            navigatorWarningMessage = if (referenceDocType == ReferenceDocType.ASSEMBLY) {
                assemblyVirtualSanitized.warningMessage
            } else {
                null
            },
            showDocControls = docControls,
            pdfMarkupStore = pdfMarkupStore,
            pdfMarkupJobFolderName = jobFolderName,
            markupEnabled = markupEnabled,
            onToggleMarkupEnabled = onToggleMarkupEnabled,
            markupToolState = markupToolState,
            ownsNavBarMarkupControls = ownsNavBarMarkupControls,
            continuousScrollEnabled = continuousScrollEnabled
        )
```

- [ ] **Step 4: Compile check**

Run: `.\gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt
git commit -m "feat(hardwoods): wire continuous-scroll toggle into UnifiedReferenceViewer"
```

---

### Task 4: Thread `continuousScrollDefault` through both `NavGraph.kt` call sites

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

- [ ] **Step 1: Pass it at the first call site (inside `JobsTabHost`, ~line 1630)**

Current (lines 1630-1639):

```kotlin
            HardwoodsWorkspaceScreen(
                scanCoordinator = hardwoodsScanCoordinator,
                hardwoodsRepository = hardwoodsRepository,
                hardwoodsProgressStore = hardwoodsProgressStore,
                sheetRipProgressStore = sheetRipProgressStore,
                jobRepository = jobRepository,
                jobFolderName = folderName,
                initialDocType = docType,
                initialRowId = rowId,
                isDarkTheme = isDarkTheme,
```

Change to:

```kotlin
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
                isDarkTheme = isDarkTheme,
```

(`continuousScrollDefault` is already an in-scope param of the enclosing `JobsTabHost` function, declared at `NavGraph.kt:1097` — this just passes the existing value through, same as its other callees already do.)

- [ ] **Step 2: Pass it at the second call site (~line 2790)**

Current (lines 2790-2799):

```kotlin
                    HardwoodsWorkspaceScreen(
                        scanCoordinator = hardwoodsScanCoordinator,
                        hardwoodsRepository = hardwoodsRepository,
                        hardwoodsProgressStore = hardwoodsProgressStore,
                        sheetRipProgressStore = sheetRipProgressStore,
                        jobRepository = jobRepository,
                        jobFolderName = folderName,
                        initialDocType = docType,
                        initialRowId = rowId,
                        isDarkTheme = preferDarkMode,
```

Change to:

```kotlin
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
                        isDarkTheme = preferDarkMode,
```

(`continuousScrollDefault` is already an in-scope param of this enclosing function, declared at `NavGraph.kt:1977`.)

- [ ] **Step 3: Compile check**

Run: `.\gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat(hardwoods): thread continuousScrollDefault from NavGraph to HardwoodsWorkspaceScreen"
```

---

### Task 5: Add `tocRequestToken` state and TOC button to `ReferencePdfViewerScreen`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt`

- [ ] **Step 1: Add the icon import**

Add to the import block (near the other `androidx.compose.material.icons.*` imports, e.g. right after line 13 `import androidx.compose.material.icons.filled.ViewDay`):

```kotlin
import androidx.compose.material.icons.filled.UnfoldMore
```

- [ ] **Step 2: Add the `tocRequestToken` state**

Current (line 84):

```kotlin
    var continuousScrollEnabled by rememberSaveable(jobFolderName, docType) { mutableStateOf(continuousScrollDefault) }
```

Change to:

```kotlin
    var continuousScrollEnabled by rememberSaveable(jobFolderName, docType) { mutableStateOf(continuousScrollDefault) }
    var tocRequestToken by rememberSaveable(jobFolderName, docType) { mutableIntStateOf(0) }
```

- [ ] **Step 3: Add the TOC `IconButton` next to the existing toggle**

Current (lines 128-136):

```kotlin
                actions = {
                    IconButton(onClick = { continuousScrollEnabled = !continuousScrollEnabled }) {
                        Icon(
                            if (continuousScrollEnabled) Icons.Default.ViewDay else Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = if (continuousScrollEnabled) "Switch to single page" else "Switch to continuous scroll",
                            tint = if (continuousScrollEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
```

Change to:

```kotlin
                actions = {
                    IconButton(onClick = { tocRequestToken += 1 }) {
                        Icon(Icons.Default.UnfoldMore, contentDescription = "Sheet list")
                    }
                    IconButton(onClick = { continuousScrollEnabled = !continuousScrollEnabled }) {
                        Icon(
                            if (continuousScrollEnabled) Icons.Default.ViewDay else Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = if (continuousScrollEnabled) "Switch to single page" else "Switch to continuous scroll",
                            tint = if (continuousScrollEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
```

- [ ] **Step 4: Pass `tocRequestToken` into the `UnifiedReferenceViewer` call**

Current (lines 176-178):

```kotlin
            continuousScrollEnabled = continuousScrollEnabled,
            isSplitPaneActive = false,
            hazeState = hazeState,
```

Change to:

```kotlin
            continuousScrollEnabled = continuousScrollEnabled,
            isSplitPaneActive = false,
            hazeState = hazeState,
            tocRequestToken = tocRequestToken,
```

- [ ] **Step 5: Compile check**

Run: `.\gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt
git commit -m "fix(viewer): restore Sheet Navigator access in continuous-scroll mode"
```

---

### Task 6: Full regression build and manual on-device verification

**Files:** none (verification only)

- [ ] **Step 1: Full debug build**

Run: `.\gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`, no warnings about unused `continuousScrollDefault`/`tocRequestToken` params (both are consumed).

- [ ] **Step 2: Install to a connected tablet**

Run: `adb install -r app\build\outputs\apk\debug\app-debug.apk`
Expected: `Success`.

- [ ] **Step 3: Manual checklist — hardwoods (Part A)**

On-device, with the debug-android-tablet skill's ADB workflow if useful for logs:

1. Open a hardwoods job's cut list with the global "Continuous scroll" Appearance setting OFF. Confirm the new toggle button appears in the top bar (while the PDF pane is shown) and starts in single-page mode.
2. Tap the toggle. Confirm the view switches to continuous scroll and `PdfLabelScrollbar` appears on the right edge.
3. Turn the global Appearance setting ON, reopen a hardwoods job. Confirm it now opens directly in continuous mode.
4. Tap "Hide PDF". Confirm the continuous-scroll toggle button disappears. Tap "Show PDF" again. Confirm the toggle reappears in its last state.

- [ ] **Step 4: Manual checklist — TOC fix (Part B)**

1. Open `ReferencePdfViewerScreen` (e.g. from a CNC job's Assembly or Plans & Elevations reference). Toggle continuous scroll on.
2. Tap the new "Sheet list" TOC button. Confirm the Sheet Navigator sheet opens and tapping a result jumps to the correct page with a smooth animated scroll.
3. Toggle continuous scroll back off. Confirm the original internal TOC button and prev/next arrows still work as before (no regression to single-page mode).
4. Repeat step 2 on a job with plan-view pages or bucketed display. Confirm navigator entries and jump targets match single-page mode's behavior.

- [ ] **Step 5: Report results**

If any checklist item fails, note which one and the observed vs. expected behavior before proceeding further — do not mark this plan complete with a known-failing checklist item.
