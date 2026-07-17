# CNC Viewer Mode Switch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the CNC main viewer swap its entire image area to Plans & Elevations or Assembly (full-size, own paging), and give the popup reference viewer a third "Sheet" tab.

**Architecture:** Both features reuse the existing `UnifiedReferenceViewer` + `ReferencePdfPane` pipeline (already built for the popup). Two small immutable snapshot classes (`ReferenceModalSnapshot`, new `MainViewReferenceSnapshot`) hold per-feature page/mode state and are unit-testable in isolation; two thin `SharedPreferences`-backed wrapper classes persist them. The app scaffold nav bar (`NavBarCncDecoration`) is untouched — it always controls the Sheet page/status, regardless of what the main image area is showing.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), JUnit4 (plain unit tests, no Robolectric/Compose UI test harness in this codebase).

---

## Spec

See `docs/superpowers/specs/2026-07-16-cnc-viewer-mode-switch-design.md` for the full design and rationale. This plan implements it task-by-task.

## Task 1: Add `ReferenceDocType.SHEET` and fix exhaustive `when`s

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/models/Models.kt:93-97`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferenceViewerData.kt:37-43,94-106,107-121`

- [ ] **Step 1: Add the enum value**

In `Models.kt`, replace:

```kotlin
enum class ReferenceDocType {
    ASSEMBLY,
    PLANS_ELEVATIONS,
    DELIVERY_SHEETS
}
```

with:

```kotlin
enum class ReferenceDocType {
    ASSEMBLY,
    PLANS_ELEVATIONS,
    DELIVERY_SHEETS,
    SHEET
}
```

- [ ] **Step 2: Try to build — confirm it fails on exhaustive `when`**

Run: `.\gradlew.bat :app:compileDebugKotlin`

Expected: FAIL — `'when' expression must be exhaustive` at the three `when (docType)` blocks in `ReferenceViewerData.kt` (lines ~38, ~95, ~108).

- [ ] **Step 3: Add `SHEET` branches**

In `ReferenceViewerData.kt`, in the first `when (docType)` block (~line 38):

```kotlin
    val documentIndex = remember(sheetIndex, docType) {
        when (docType) {
            ReferenceDocType.ASSEMBLY -> sheetIndex?.documents?.assembly
            ReferenceDocType.PLANS_ELEVATIONS -> sheetIndex?.documents?.plansElevations
            ReferenceDocType.DELIVERY_SHEETS -> null
            ReferenceDocType.SHEET -> null
        }
    }
```

In the second `when (docType)` block (~line 95):

```kotlin
    val navigatorCabinetToPages = remember(docType, documentIndex, assemblyVirtualSanitized, virtualMapping) {
        when (docType) {
            ReferenceDocType.ASSEMBLY -> {
                if (virtualMapping != null) {
                    assemblyVirtualSanitized.cabinetToPages
                } else {
                    documentIndex?.cabinetToPages.orEmpty()
                }
            }
            ReferenceDocType.PLANS_ELEVATIONS -> documentIndex?.cabinetToPages.orEmpty()
            ReferenceDocType.DELIVERY_SHEETS -> emptyMap()
            ReferenceDocType.SHEET -> emptyMap()
        }
    }
```

The third block (~line 108, `navigatorPlanViewLabels`) already uses `if (docType != ReferenceDocType.PLANS_ELEVATIONS)` rather than an exhaustive `when` — no change needed there.

- [ ] **Step 4: Build again — confirm it compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/models/Models.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferenceViewerData.kt
git commit -m "feat(viewer): add ReferenceDocType.SHEET"
```

---

## Task 2: Extend `ReferenceModalSnapshot` for the Sheet tab (TDD)

**Files:**
- Modify: `app/src/test/java/com/kkc/sheettracker/ui/components/ReferenceModalStateTest.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt:57-76`

- [ ] **Step 1: Write the failing tests**

Append to `ReferenceModalStateTest.kt` (inside the existing `ReferenceModalStateTest` class, after the last `@Test` function):

```kotlin
    @Test
    fun pageForActiveDoc_returnsSheetPage() {
        val snap = ReferenceModalSnapshot(
            docType = ReferenceDocType.SHEET,
            plansPage = 4,
            assemblyPage = 9,
            sheetPage = 12
        )
        assertEquals(12, snap.pageForActiveDoc())
    }

    @Test
    fun withPage_updatesOnlySheetPageWhenDocTypeIsSheet() {
        val snap = ReferenceModalSnapshot(
            docType = ReferenceDocType.SHEET,
            plansPage = 4,
            assemblyPage = 9,
            sheetPage = 12
        )
        val updated = snap.withPage(20)
        assertEquals(4, updated.plansPage)
        assertEquals(9, updated.assemblyPage)
        assertEquals(20, updated.sheetPage)
    }

    @Test
    fun withDocType_syncsSheetPageWhenProvided() {
        val snap = ReferenceModalSnapshot(
            docType = ReferenceDocType.PLANS_ELEVATIONS,
            sheetPage = 3
        )
        val switched = snap.withDocType(ReferenceDocType.SHEET, syncPage = 17)
        assertEquals(ReferenceDocType.SHEET, switched.docType)
        assertEquals(17, switched.sheetPage)
    }

    @Test
    fun withDocType_noSyncKeepsExistingSheetPage() {
        val snap = ReferenceModalSnapshot(
            docType = ReferenceDocType.PLANS_ELEVATIONS,
            sheetPage = 3
        )
        val switched = snap.withDocType(ReferenceDocType.SHEET)
        assertEquals(ReferenceDocType.SHEET, switched.docType)
        assertEquals(3, switched.sheetPage)
    }

    @Test
    fun withDocType_syncPageIgnoredForNonSheetTarget() {
        val snap = ReferenceModalSnapshot(docType = ReferenceDocType.SHEET, plansPage = 4)
        val switched = snap.withDocType(ReferenceDocType.PLANS_ELEVATIONS, syncPage = 99)
        assertEquals(ReferenceDocType.PLANS_ELEVATIONS, switched.docType)
        assertEquals(4, switched.plansPage)
    }
```

- [ ] **Step 2: Run the tests — confirm they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ReferenceModalStateTest"`

Expected: FAIL — compile error (`sheetPage` is not a member of `ReferenceModalSnapshot`, `withDocType` does not accept a second argument).

- [ ] **Step 3: Implement — extend the snapshot**

In `ReferenceModalOverlay.kt`, replace the `ReferenceModalSnapshot` data class (lines 57-76):

```kotlin
data class ReferenceModalSnapshot(
    val isOpen: Boolean = false,
    val docType: ReferenceDocType = ReferenceDocType.PLANS_ELEVATIONS,
    val plansPage: Int = 1,
    val assemblyPage: Int = 1,
    val sheetPage: Int = 1,
    val modalX: Float = 24f,
    val modalY: Float = 24f,
    val modalWidth: Float = 360f,
    val modalHeight: Float = 480f
) {
    fun pageForActiveDoc(): Int = when (docType) {
        ReferenceDocType.ASSEMBLY -> assemblyPage
        ReferenceDocType.SHEET -> sheetPage
        else -> plansPage
    }

    /**
     * Switches doc type. When switching to [ReferenceDocType.SHEET] with [syncPage] provided,
     * snaps the Sheet tab's page to it (the main viewer's current Sheet page) — a one-time sync
     * on tab switch, not continuous following. Ignored for any other target doc type.
     */
    fun withDocType(next: ReferenceDocType, syncPage: Int? = null): ReferenceModalSnapshot {
        val base = copy(docType = next)
        return if (next == ReferenceDocType.SHEET && syncPage != null) {
            base.copy(sheetPage = syncPage.coerceAtLeast(1))
        } else {
            base
        }
    }

    fun withPage(page: Int): ReferenceModalSnapshot {
        val safe = page.coerceAtLeast(1)
        return when (docType) {
            ReferenceDocType.ASSEMBLY -> copy(assemblyPage = safe)
            ReferenceDocType.SHEET -> copy(sheetPage = safe)
            else -> copy(plansPage = safe)
        }
    }
}
```

- [ ] **Step 4: Run the tests — confirm they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ReferenceModalStateTest"`

Expected: PASS (all tests in the class, old and new)

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/kkc/sheettracker/ui/components/ReferenceModalStateTest.kt app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt
git commit -m "feat(viewer): add Sheet page tracking to ReferenceModalSnapshot"
```

---

## Task 3: Persist `sheetPage`; thread `syncPage` through `setDocType`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt:126-131,167-209`

No new unit tests here — `ReferenceModalOverlayState` wraps Android `SharedPreferences` and isn't covered by the plain-JUnit test setup in this codebase (matches the existing convention: only the pure `ReferenceModalSnapshot`/top-level functions are unit tested, per `ReferenceModalStateTest.kt`). Task 2's tests already cover the underlying logic this step wires up.

- [ ] **Step 1: Update `setDocType`**

Replace:

```kotlin
    fun setDocType(docType: ReferenceDocType) {
        clearNoRefNote()
        if (snapshot.docType == docType) return
        snapshot = snapshot.withDocType(docType)
        persist()
    }
```

with:

```kotlin
    fun setDocType(docType: ReferenceDocType, syncPage: Int? = null) {
        clearNoRefNote()
        val next = snapshot.withDocType(docType, syncPage)
        if (next == snapshot) return
        snapshot = next
        persist()
    }
```

(The old early-return on `snapshot.docType == docType` is replaced by comparing the *resulting* snapshot — this preserves old no-op behavior when nothing changes, but still applies a `syncPage` resync even when re-selecting the tab that's already active.)

- [ ] **Step 2: Add the `sheetPage` pref key and persist/load it**

In the `companion object`, add the key constant next to the existing ones:

```kotlin
        private const val KEY_SHEET_PAGE = "refmodal_sheet_page"
```

In `persist()`, add the line (alongside the existing `putInt` calls):

```kotlin
            .putInt(KEY_SHEET_PAGE, snapshot.sheetPage)
```

In `load()`, add `sheetPage` to the returned `ReferenceModalSnapshot`:

```kotlin
                sheetPage = prefs.getInt(KEY_SHEET_PAGE, 1),
```

- [ ] **Step 3: Build**

Run: `.\gradlew.bat :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt
git commit -m "feat(viewer): persist Sheet page and support syncPage on doc-type switch"
```

---

## Task 4: `MainViewReferenceState` for the main-view mode switch (TDD)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/viewer/MainViewReferenceState.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/ui/viewer/MainViewReferenceStateTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/kkc/sheettracker/ui/viewer/MainViewReferenceStateTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.viewer

import com.kkc.sheettracker.data.models.ReferenceDocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainViewReferenceStateTest {

    @Test
    fun pageForMode_returnsPerModePage() {
        val snap = MainViewReferenceSnapshot(mode = ReferenceDocType.PLANS_ELEVATIONS, plansPage = 3, assemblyPage = 8)
        assertEquals(3, snap.pageForMode())
        assertEquals(8, snap.copy(mode = ReferenceDocType.ASSEMBLY).pageForMode())
    }

    @Test
    fun pageForMode_defaultsToPlansPageWhenModeIsNull() {
        val snap = MainViewReferenceSnapshot(mode = null, plansPage = 3, assemblyPage = 8)
        assertEquals(3, snap.pageForMode())
    }

    @Test
    fun withMode_switchesAndKeepsOtherModePage() {
        val snap = MainViewReferenceSnapshot(mode = ReferenceDocType.PLANS_ELEVATIONS, plansPage = 3, assemblyPage = 8)
        val switched = snap.withMode(ReferenceDocType.ASSEMBLY)
        assertEquals(ReferenceDocType.ASSEMBLY, switched.mode)
        assertEquals(3, switched.plansPage)
        assertEquals(8, switched.assemblyPage)
    }

    @Test
    fun withMode_toNullReturnsToSheet() {
        val snap = MainViewReferenceSnapshot(mode = ReferenceDocType.ASSEMBLY, plansPage = 3, assemblyPage = 8)
        val switched = snap.withMode(null)
        assertNull(switched.mode)
    }

    @Test
    fun withPage_updatesOnlyActiveModePage() {
        val snap = MainViewReferenceSnapshot(mode = ReferenceDocType.ASSEMBLY, plansPage = 3, assemblyPage = 8)
        val updated = snap.withPage(15)
        assertEquals(3, updated.plansPage)
        assertEquals(15, updated.assemblyPage)
    }
}
```

- [ ] **Step 2: Run the tests — confirm they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.viewer.MainViewReferenceStateTest"`

Expected: FAIL — compile error (`MainViewReferenceSnapshot` is unresolved).

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/kkc/sheettracker/ui/viewer/MainViewReferenceState.kt`:

```kotlin
package com.kkc.sheettracker.ui.viewer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kkc.sheettracker.data.models.ReferenceDocType

/**
 * Main CNC viewer's image-area mode: which document is shown full-size in place of the Sheet
 * bitmap. `mode == null` means Sheet (the default). Independent of, and separately persisted
 * from, the popup reference viewer's own state in `ReferenceModalOverlay.kt`.
 */
data class MainViewReferenceSnapshot(
    val mode: ReferenceDocType? = null,
    val plansPage: Int = 1,
    val assemblyPage: Int = 1
) {
    fun pageForMode(): Int = if (mode == ReferenceDocType.ASSEMBLY) assemblyPage else plansPage

    fun withMode(next: ReferenceDocType?): MainViewReferenceSnapshot = copy(mode = next)

    fun withPage(page: Int): MainViewReferenceSnapshot {
        val safe = page.coerceAtLeast(1)
        return if (mode == ReferenceDocType.ASSEMBLY) copy(assemblyPage = safe) else copy(plansPage = safe)
    }
}

class MainViewReferenceState internal constructor(
    private val prefs: SharedPreferences
) {
    var snapshot by mutableStateOf(load(prefs))
        private set

    fun setMode(next: ReferenceDocType?) {
        val nextSnapshot = snapshot.withMode(next)
        if (nextSnapshot == snapshot) return
        snapshot = nextSnapshot
        persist()
    }

    fun setPage(page: Int) {
        val next = snapshot.withPage(page)
        if (next == snapshot) return
        snapshot = next
        persist()
    }

    private fun persist() {
        prefs.edit()
            .putString(KEY_MODE, snapshot.mode?.name.orEmpty())
            .putInt(KEY_PLANS_PAGE, snapshot.plansPage)
            .putInt(KEY_ASM_PAGE, snapshot.assemblyPage)
            .apply()
    }

    companion object {
        private const val PREFS_FILE = "kkc_tracker"
        private const val KEY_MODE = "mainview_ref_mode"
        private const val KEY_PLANS_PAGE = "mainview_ref_plans_page"
        private const val KEY_ASM_PAGE = "mainview_ref_asm_page"

        fun create(context: Context): MainViewReferenceState =
            MainViewReferenceState(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

        private fun load(prefs: SharedPreferences): MainViewReferenceSnapshot {
            val modeName = prefs.getString(KEY_MODE, null)
            val mode = modeName?.takeIf { it.isNotBlank() }
                ?.let { runCatching { ReferenceDocType.valueOf(it) }.getOrNull() }
            return MainViewReferenceSnapshot(
                mode = mode,
                plansPage = prefs.getInt(KEY_PLANS_PAGE, 1),
                assemblyPage = prefs.getInt(KEY_ASM_PAGE, 1)
            )
        }
    }
}

@Composable
fun rememberMainViewReferenceState(): MainViewReferenceState {
    val context = LocalContext.current
    return remember { MainViewReferenceState.create(context) }
}
```

- [ ] **Step 4: Run the tests — confirm they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.viewer.MainViewReferenceStateTest"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/MainViewReferenceState.kt app/src/test/java/com/kkc/sheettracker/ui/viewer/MainViewReferenceStateTest.kt
git commit -m "feat(viewer): add MainViewReferenceState for main-view mode switch"
```

---

## Task 5: Popup — add the "Sheet" tab

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt` (imports, `ReferenceModalHost` signature ~219-230, segmented row ~349-364, jump effect ~252-259, `referenceData` construction ~238-244)

No new unit tests — this task is Compose UI wiring, matching the existing test-coverage boundary in this codebase (Compose composition isn't unit tested here; see Task 3 note). Manual verification happens in Task 7.

- [ ] **Step 1: Add the `File` import**

At the top of `ReferenceModalOverlay.kt`, add to the import list (alphabetically near the other `android.*`/top-level imports):

```kotlin
import java.io.File
```

- [ ] **Step 2: Add new `ReferenceModalHost` parameters**

Replace the function signature:

```kotlin
@Composable
fun ReferenceModalHost(
    state: ReferenceModalOverlayState,
    jobRepository: JobRepository,
    jobFolderName: String,
    refreshGeneration: Long,
    isDarkTheme: Boolean,
    hasPlans: Boolean,
    hasAssembly: Boolean,
    selectedCabinet: Int?,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
```

with:

```kotlin
@Composable
fun ReferenceModalHost(
    state: ReferenceModalOverlayState,
    jobRepository: JobRepository,
    jobFolderName: String,
    refreshGeneration: Long,
    isDarkTheme: Boolean,
    hasPlans: Boolean,
    hasAssembly: Boolean,
    selectedCabinet: Int?,
    sheetPdfFilename: String,
    sheetPdfFile: File?,
    currentSheetPage: Int,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier
) {
```

- [ ] **Step 3: Override `referenceData` for the Sheet tab**

Replace:

```kotlin
    val referenceData = rememberReferenceViewerData(
        jobRepository = jobRepository,
        jobFolderName = jobFolderName,
        docType = snapshot.docType,
        refreshGeneration = refreshGeneration,
        isDarkTheme = isDarkTheme
    )
```

with:

```kotlin
    val refDocData = rememberReferenceViewerData(
        jobRepository = jobRepository,
        jobFolderName = jobFolderName,
        docType = snapshot.docType,
        refreshGeneration = refreshGeneration,
        isDarkTheme = isDarkTheme
    )
    // The Sheet tab isn't a reference document looked up by JobRepository — it's the CNC PDF
    // already open behind the popup. Override with the caller-supplied file/filename directly;
    // no virtual mapping or cabinet index applies to it.
    val referenceData = if (snapshot.docType == ReferenceDocType.SHEET) {
        refDocData.copy(
            defaultPdfFilename = sheetPdfFilename,
            virtualMapping = null,
            navigatorCabinetToPages = emptyMap(),
            navigatorPlanViewLabels = emptyMap(),
            warningMessage = null
        )
    } else {
        refDocData
    }
```

- [ ] **Step 4: Guard the jump-on-tap effect for the Sheet tab**

Replace:

```kotlin
    var handledCabinet by remember { mutableStateOf(selectedCabinet) }
    LaunchedEffect(selectedCabinet) {
        if (selectedCabinet == handledCabinet) return@LaunchedEffect
        handledCabinet = selectedCabinet
        val cabinet = selectedCabinet ?: return@LaunchedEffect
        val target = resolveJumpPage(referenceData.navigatorCabinetToPages, cabinet)
        if (target != null) state.setPage(target) else state.showNoRefNote()
    }
```

with:

```kotlin
    var handledCabinet by remember { mutableStateOf(selectedCabinet) }
    LaunchedEffect(selectedCabinet) {
        if (selectedCabinet == handledCabinet) return@LaunchedEffect
        handledCabinet = selectedCabinet
        // Nothing to jump to on the Sheet tab — it IS the current sheet.
        if (snapshot.docType == ReferenceDocType.SHEET) return@LaunchedEffect
        val cabinet = selectedCabinet ?: return@LaunchedEffect
        val target = resolveJumpPage(referenceData.navigatorCabinetToPages, cabinet)
        if (target != null) state.setPage(target) else state.showNoRefNote()
    }
```

- [ ] **Step 5: Add the "Sheet" segmented button and resolve the Sheet PDF file**

Replace the segmented row:

```kotlin
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                            SegmentedButton(
                                selected = snapshot.docType == ReferenceDocType.PLANS_ELEVATIONS,
                                onClick = { state.setDocType(ReferenceDocType.PLANS_ELEVATIONS) },
                                enabled = hasPlans,
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                label = { Text("Plans & Elev.", maxLines = 1) }
                            )
                            SegmentedButton(
                                selected = snapshot.docType == ReferenceDocType.ASSEMBLY,
                                onClick = { state.setDocType(ReferenceDocType.ASSEMBLY) },
                                enabled = hasAssembly,
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                label = { Text("Assembly", maxLines = 1) }
                            )
                        }
```

with:

```kotlin
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                            SegmentedButton(
                                selected = snapshot.docType == ReferenceDocType.SHEET,
                                onClick = { state.setDocType(ReferenceDocType.SHEET, syncPage = currentSheetPage) },
                                enabled = true,
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                                label = { Text("Sheet", maxLines = 1) }
                            )
                            SegmentedButton(
                                selected = snapshot.docType == ReferenceDocType.PLANS_ELEVATIONS,
                                onClick = { state.setDocType(ReferenceDocType.PLANS_ELEVATIONS) },
                                enabled = hasPlans,
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                                label = { Text("Plans & Elev.", maxLines = 1) }
                            )
                            SegmentedButton(
                                selected = snapshot.docType == ReferenceDocType.ASSEMBLY,
                                onClick = { state.setDocType(ReferenceDocType.ASSEMBLY) },
                                enabled = hasAssembly,
                                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                                label = { Text("Assembly", maxLines = 1) }
                            )
                        }
```

- [ ] **Step 6: Route the Sheet tab's file lookup through `sheetPdfFile`**

Replace:

```kotlin
                            pdfFileForFilename = { filename ->
                                jobRepository.getJobRootPdfFile(
                                    jobFolderName = jobFolderName,
                                    pdfFilename = filename,
                                    preferDarkMode = isDarkTheme
                                )
                            },
```

with:

```kotlin
                            pdfFileForFilename = { filename ->
                                if (snapshot.docType == ReferenceDocType.SHEET && filename == sheetPdfFilename) {
                                    sheetPdfFile
                                } else {
                                    jobRepository.getJobRootPdfFile(
                                        jobFolderName = jobFolderName,
                                        pdfFilename = filename,
                                        preferDarkMode = isDarkTheme
                                    )
                                }
                            },
```

- [ ] **Step 7: Build**

Run: `.\gradlew.bat :app:compileDebugKotlin`

Expected: FAIL — `ReferenceModalHost` call site in `SheetViewerScreen.kt` is now missing the three new required parameters. This is expected; fixed in Task 6.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt
git commit -m "feat(viewer): add Sheet tab to popup reference viewer"
```

---

## Task 6: Main viewer — mode toggle, image-area swap, tap-to-jump

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt` (state ~339, chip row ~1367-1407, `topContent` ~1490-1564, jump effect + `ReferenceModalHost` call site ~1675-1686)

No new unit tests — Compose UI wiring, same boundary as Task 5. Manual verification in Task 7.

- [ ] **Step 1: Add main-view reference state**

Replace:

```kotlin
    val referenceModal = com.kkc.sheettracker.ui.components.rememberReferenceModalOverlayState()
```

with:

```kotlin
    val referenceModal = com.kkc.sheettracker.ui.components.rememberReferenceModalOverlayState()
    val mainViewRef = rememberMainViewReferenceState()
    val mainViewReferenceData = rememberReferenceViewerData(
        jobRepository = jobRepository,
        jobFolderName = jobFolderName,
        docType = mainViewRef.snapshot.mode ?: ReferenceDocType.PLANS_ELEVATIONS,
        refreshGeneration = scanState.snapshot.generation,
        isDarkTheme = isDarkTheme
    )
```

(`MainViewReferenceState` and `rememberMainViewReferenceState` live in the same `ui.viewer` package as this file, so no import is needed. `ReferenceDocType` is already imported at the top of this file.)

- [ ] **Step 2: Add the 3-way mode toggle next to the Popup Viewer chip**

Replace the whole row (the one holding the `pdfFilename` text, the "Popup Viewer" chip, and the remake chip):

```kotlin
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            pdfFilename,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (hasAssemblyReference || hasPlansReference) {
                            AssistChip(
                                onClick = { referenceModal.toggleOpen(hasPlansReference, hasAssemblyReference, defaultModalDoc) },
                                label = { Text("Popup Viewer") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                                    )
                                }
                            )
                        }
                        val remakeLabel = currentPageRemake?.label?.takeIf { it.isNotBlank() }
                        if (remakeLabel != null) {
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                label = { Text(remakeLabel) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Remake"
                                    )
                                }
                            )
                        }
                    }
```

with:

```kotlin
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            pdfFilename,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        SingleChoiceSegmentedButtonRow {
                            SegmentedButton(
                                selected = mainViewRef.snapshot.mode == null,
                                onClick = { mainViewRef.setMode(null) },
                                enabled = true,
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                                label = { Text("Sheet", maxLines = 1) }
                            )
                            SegmentedButton(
                                selected = mainViewRef.snapshot.mode == ReferenceDocType.PLANS_ELEVATIONS,
                                onClick = { mainViewRef.setMode(ReferenceDocType.PLANS_ELEVATIONS) },
                                enabled = hasPlansReference,
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                                label = { Text("Plans & Elev.", maxLines = 1) }
                            )
                            SegmentedButton(
                                selected = mainViewRef.snapshot.mode == ReferenceDocType.ASSEMBLY,
                                onClick = { mainViewRef.setMode(ReferenceDocType.ASSEMBLY) },
                                enabled = hasAssemblyReference,
                                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                                label = { Text("Assembly", maxLines = 1) }
                            )
                        }
                        if (hasAssemblyReference || hasPlansReference) {
                            AssistChip(
                                onClick = { referenceModal.toggleOpen(hasPlansReference, hasAssemblyReference, defaultModalDoc) },
                                label = { Text("Popup Viewer") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                                    )
                                }
                            )
                        }
                        val remakeLabel = currentPageRemake?.label?.takeIf { it.isNotBlank() }
                        if (remakeLabel != null) {
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                label = { Text(remakeLabel) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Remake"
                                    )
                                }
                            )
                        }
                    }
```

- [ ] **Step 3: Swap the main image area for ref modes**

Replace the `topContent` lambda:

```kotlin
                topContent = { topModifier ->
                    Crossfade(targetState = bitmap, animationSpec = tween(150), label = "viewerBitmap") { activeBitmap ->
                        if (activeBitmap != null) {
                            if (showFullPdfPage || markupEnabled) {
                                MarkupPdfPageView(
                                    bitmap = activeBitmap,
                                    resetZoomTrigger = resetZoomTrigger,
                                    inputEnabled = markupEnabled,
                                    modifier = topModifier
                                        .fillMaxWidth()
                                        .padding(4.dp),
                                    markupStrokes = visiblePdfMarkupStrokes,
                                    markupToolState = markupToolState,
                                    onMarkupStrokeAdded = { stroke ->
                                        localMarkupStrokes.add(stroke)
                                        persistCurrentPageMarkup()
                                    },
                                    onMarkupStrokeErased = { strokeId ->
                                        if (strokeId !in localMarkupDeletedIds) {
                                            localMarkupDeletedIds.add(strokeId)
                                        }
                                        persistCurrentPageMarkup()
                                    },
                                    onTapEmpty = null
                                )
                            } else {
                                DiagramView(
                                    bitmap = activeBitmap,
                                    parts = parts,
                                    selectedPartNumber = selectedPartNumber,
                                    diagramBboxes = diagramBboxes,
                                    resetZoomTrigger = resetZoomTrigger,
                                    markupStrokes = visiblePdfMarkupStrokes,
                                    modifier = topModifier
                                        .fillMaxWidth()
                                        .padding(4.dp),
                                    onTapPart = { partNumber ->
                                        val isDeselecting = selectedPartNumber == partNumber
                                        selectedPartNumber = if (isDeselecting) null else partNumber
                                        selectedCabinetNumber = if (isDeselecting) {
                                            null
                                        } else {
                                            parts.firstOrNull { it.number == partNumber }?.cabNumber?.takeIf { it > 0 }
                                        }
                                        val tappedCabinet = parts.firstOrNull { it.number == partNumber }?.cabNumber
                                        Log.d(
                                            VIEWER_REF_TAG,
                                            "tap_part source=diagram part=$partNumber tappedCabinet=$tappedCabinet " +
                                                "selectedPartNow=$selectedPartNumber selectedCabinetNow=$selectedCabinetNumber"
                                        )
                                    },
                                    onLongPressPart = { partNumber ->
                                        val cabNumber = parts.firstOrNull { it.number == partNumber }?.cabNumber
                                        selectedPartNumber = partNumber
                                        selectedCabinetNumber = cabNumber?.takeIf { it > 0 }
                                        Log.d(
                                            VIEWER_REF_TAG,
                                            "tap_part source=diagram_long_press part=$partNumber tappedCabinet=$cabNumber " +
                                                "selectedPartNow=$selectedPartNumber selectedCabinetNow=$selectedCabinetNumber"
                                        )
                                        showReferenceDocDialog = true
                                    },
                                    onTapEmpty = null
                                )
                            }
                        } else {
                            SheetLoadingPlaceholder(modifier = topModifier.fillMaxWidth())
                        }
                    }
                },
```

with:

```kotlin
                topContent = { topModifier ->
                    if (mainViewRef.snapshot.mode != null) {
                        UnifiedReferenceViewer(
                            modifier = topModifier.fillMaxWidth(),
                            displayPage = mainViewRef.snapshot.pageForMode(),
                            onDisplayPageChange = { mainViewRef.setPage(it) },
                            defaultPdfFilename = mainViewReferenceData.defaultPdfFilename,
                            pdfFileForFilename = { filename ->
                                jobRepository.getJobRootPdfFile(
                                    jobFolderName = jobFolderName,
                                    pdfFilename = filename,
                                    preferDarkMode = isDarkTheme
                                )
                            },
                            fileIdentitySeed = scanState.snapshot.generation,
                            preferDarkMode = isDarkTheme,
                            virtualMapping = mainViewReferenceData.virtualMapping,
                            navigatorCabinetToPages = mainViewReferenceData.navigatorCabinetToPages,
                            navigatorPlanViewLabels = mainViewReferenceData.navigatorPlanViewLabels,
                            navigatorWarningMessage = mainViewReferenceData.warningMessage,
                            missingText = "Reference PDF not found.",
                            unreadableText = "Unable to read PDF pages.",
                            showHeaderRow = false,
                            showNavigationButtons = true,
                            compactArrows = true
                        )
                    } else {
                        Crossfade(targetState = bitmap, animationSpec = tween(150), label = "viewerBitmap") { activeBitmap ->
                            if (activeBitmap != null) {
                                if (showFullPdfPage || markupEnabled) {
                                    MarkupPdfPageView(
                                        bitmap = activeBitmap,
                                        resetZoomTrigger = resetZoomTrigger,
                                        inputEnabled = markupEnabled,
                                        modifier = topModifier
                                            .fillMaxWidth()
                                            .padding(4.dp),
                                        markupStrokes = visiblePdfMarkupStrokes,
                                        markupToolState = markupToolState,
                                        onMarkupStrokeAdded = { stroke ->
                                            localMarkupStrokes.add(stroke)
                                            persistCurrentPageMarkup()
                                        },
                                        onMarkupStrokeErased = { strokeId ->
                                            if (strokeId !in localMarkupDeletedIds) {
                                                localMarkupDeletedIds.add(strokeId)
                                            }
                                            persistCurrentPageMarkup()
                                        },
                                        onTapEmpty = null
                                    )
                                } else {
                                    DiagramView(
                                        bitmap = activeBitmap,
                                        parts = parts,
                                        selectedPartNumber = selectedPartNumber,
                                        diagramBboxes = diagramBboxes,
                                        resetZoomTrigger = resetZoomTrigger,
                                        markupStrokes = visiblePdfMarkupStrokes,
                                        modifier = topModifier
                                            .fillMaxWidth()
                                            .padding(4.dp),
                                        onTapPart = { partNumber ->
                                            val isDeselecting = selectedPartNumber == partNumber
                                            selectedPartNumber = if (isDeselecting) null else partNumber
                                            selectedCabinetNumber = if (isDeselecting) {
                                                null
                                            } else {
                                                parts.firstOrNull { it.number == partNumber }?.cabNumber?.takeIf { it > 0 }
                                            }
                                            val tappedCabinet = parts.firstOrNull { it.number == partNumber }?.cabNumber
                                            Log.d(
                                                VIEWER_REF_TAG,
                                                "tap_part source=diagram part=$partNumber tappedCabinet=$tappedCabinet " +
                                                    "selectedPartNow=$selectedPartNumber selectedCabinetNow=$selectedCabinetNumber"
                                            )
                                        },
                                        onLongPressPart = { partNumber ->
                                            val cabNumber = parts.firstOrNull { it.number == partNumber }?.cabNumber
                                            selectedPartNumber = partNumber
                                            selectedCabinetNumber = cabNumber?.takeIf { it > 0 }
                                            Log.d(
                                                VIEWER_REF_TAG,
                                                "tap_part source=diagram_long_press part=$partNumber tappedCabinet=$cabNumber " +
                                                    "selectedPartNow=$selectedPartNumber selectedCabinetNow=$selectedCabinetNumber"
                                            )
                                            showReferenceDocDialog = true
                                        },
                                        onTapEmpty = null
                                    )
                                }
                            } else {
                                SheetLoadingPlaceholder(modifier = topModifier.fillMaxWidth())
                            }
                        }
                    }
                },
```

- [ ] **Step 4: Add tap-to-jump for main-view ref mode, and update the `ReferenceModalHost` call site**

Replace:

```kotlin
        com.kkc.sheettracker.ui.components.ReferenceModalHost(
            state = referenceModal,
            jobRepository = jobRepository,
            jobFolderName = jobFolderName,
            refreshGeneration = scanState.snapshot.generation,
            isDarkTheme = isDarkTheme,
            hasPlans = hasPlansReference,
            hasAssembly = hasAssemblyReference,
            selectedCabinet = selectedCabinetNumber,
            hazeState = null,
            modifier = Modifier.fillMaxSize()
        )
```

with:

```kotlin
        LaunchedEffect(selectedCabinetNumber, mainViewRef.snapshot.mode) {
            val cabinet = selectedCabinetNumber ?: return@LaunchedEffect
            if (mainViewRef.snapshot.mode == null) return@LaunchedEffect
            val target = com.kkc.sheettracker.ui.components.resolveJumpPage(
                mainViewReferenceData.navigatorCabinetToPages,
                cabinet
            )
            if (target != null) mainViewRef.setPage(target)
        }
        com.kkc.sheettracker.ui.components.ReferenceModalHost(
            state = referenceModal,
            jobRepository = jobRepository,
            jobFolderName = jobFolderName,
            refreshGeneration = scanState.snapshot.generation,
            isDarkTheme = isDarkTheme,
            hasPlans = hasPlansReference,
            hasAssembly = hasAssemblyReference,
            selectedCabinet = selectedCabinetNumber,
            sheetPdfFilename = pdfFilename,
            sheetPdfFile = pdfFile,
            currentSheetPage = currentPage,
            hazeState = null,
            modifier = Modifier.fillMaxSize()
        )
```

- [ ] **Step 5: Build**

Run: `.\gradlew.bat :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Run the full unit test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL (all existing tests plus the new ones from Tasks 2 and 4 pass)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt
git commit -m "feat(viewer): main-view mode switch (Sheet/Plans/Assembly) with tap-to-jump"
```

---

## Task 7: Manual verification on a device

**Files:** none (verification only)

- [ ] **Step 1: Build and install the debug APK**

Run: `.\gradlew.bat assembleDebug`
Run: `adb install -r app\build\outputs\apk\debug\app-debug.apk`

Expected: both succeed; app launches on the connected tablet/emulator.

- [ ] **Step 2: Walk the spec's testing checklist**

Open a job with both Plans & Elevations and Assembly reference documents, open the CNC sheet
viewer, and confirm each item from the design doc's Testing section:

1. Toggle main view through Sheet → Plans → Assembly → Sheet. Part table stays constant across
   all three. Each mode remembers its own last-viewed page across toggles. The app scaffold nav
   bar's prev/next always moves the Sheet page (check the part table / Sheet page indicator)
   regardless of which mode is displayed on screen.
2. Tap a part row while in Plans or Assembly mode. The ref doc jumps to the matching page, or
   shows the "no reference for this cabinet" note if none exists.
3. Open the popup, switch to the Sheet tab — confirm it opens on the main view's current Sheet
   page. Page forward in the popup, switch to Plans and back to Sheet — confirm it re-syncs to
   whatever the main view's Sheet page is *now* (not the page you left the popup on).
4. Tap a part while the popup is open on the Sheet tab — confirm no "no reference" note fires and
   the popup doesn't attempt a jump.
5. Confirm popup and main-view mode switches are fully independent — e.g. main view on Assembly
   while the popup is on Plans, or vice versa, with no cross-interference.
6. Confirm "Plans & Elev." / "Assembly" options are disabled (main view toggle and popup tab) when
   the job has no such reference document; "Sheet" is always enabled in both places.

- [ ] **Step 3: Report results**

If any checklist item fails, note the exact behavior observed vs. expected before making further
changes — do not fix ad hoc without identifying which task's change is responsible.

---

## Addendum: fixes found during Task 7 device verification

Manual testing on a real tablet surfaced two real gaps not caught by static review:

1. Popup's Sheet tab renders the raw full PDF page (via `UnifiedReferenceViewer`/`ReferencePdfPane`,
   the same pipeline Plans/Assembly use) instead of the cropped diagram image the main viewer shows
   for the Sheet — the popup's Sheet tab doesn't visually match "just the sheet image" like the main
   viewer's `DiagramView` does. Plans/Assembly tabs are explicitly unaffected — full-page render for
   those was an earlier, deliberate design choice and stays as-is.
2. The main viewer's new 3-way mode toggle plus the separately-styled "Popup Viewer" `AssistChip` can
   push the row wider than expected on jobs with longer filenames, and visually the two don't read as
   one control group. Fix: make the toggle buttons more compact, and fold "Popup Viewer" into the same
   segmented row as a 4th segment instead of a separately-styled chip.

### Task 8: Popup Sheet tab — crop to diagram image like the main viewer

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt` (visibility only: `extractLargestEmbeddedImage`, `DiagramView` — promote from `private` to `internal`)
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt` (SHEET-tab render branch in `ReferenceModalHost`)

No new unit tests — Compose UI wiring, same test-coverage boundary as Tasks 5/6.

- [ ] **Step 1: Promote two functions from `private` to `internal`**

In `SheetViewerScreen.kt`, change:
```kotlin
private fun extractLargestEmbeddedImage(pdfFile: java.io.File, pageIndex: Int): Bitmap? {
```
to:
```kotlin
internal fun extractLargestEmbeddedImage(pdfFile: java.io.File, pageIndex: Int): Bitmap? {
```

And change:
```kotlin
private fun DiagramView(
```
to:
```kotlin
internal fun DiagramView(
```
Both stay in the `com.kkc.sheettracker.ui.viewer` package — `internal` makes them visible module-wide (including `ui.components`) without becoming public API. Do not change either function's body or signature otherwise.

- [ ] **Step 2: Add a standalone full-page-render fallback helper**

In `ReferenceModalOverlay.kt`, add a private helper near the bottom of the file (outside any class), used only when a page has no embedded diagram image to extract:

```kotlin
private fun renderSheetPageFallback(pdfFile: File, pageIndex: Int): Bitmap? {
    if (!pdfFile.exists()) return null
    return try {
        android.os.ParcelFileDescriptor.open(pdfFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    val scale = 2
                    val bmp = android.graphics.Bitmap.createBitmap(
                        page.width * scale,
                        page.height * scale,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}
```
(`PdfRenderer.Page` and `PdfRenderer` both implement `Closeable`, so `.use { }` closes them safely even on early return/exception — confirm this compiles against this project's minSdk; if `.use` isn't available on one of these types in this codebase's Android API level, fall back to explicit try/finally `close()` calls instead, matching the pattern already used elsewhere in `SheetViewerScreen.kt`'s `renderPageFromPdf`.)

- [ ] **Step 3: Add per-page bitmap resolution state and a page-count lookup**

In `ReferenceModalHost`, after the existing `referenceData`/`handledCabinet` block, add:

```kotlin
    var sheetBitmap by remember(sheetPdfFile) { mutableStateOf<Bitmap?>(null) }
    var sheetTotalPages by remember(sheetPdfFile) { mutableStateOf(0) }

    LaunchedEffect(sheetPdfFile) {
        val file = sheetPdfFile ?: return@LaunchedEffect
        sheetTotalPages = withContext(Dispatchers.IO) {
            runCatching {
                android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    android.graphics.pdf.PdfRenderer(fd).use { it.pageCount }
                }
            }.getOrDefault(0)
        }
    }

    LaunchedEffect(snapshot.sheetPage, sheetPdfFile) {
        val file = sheetPdfFile ?: run { sheetBitmap = null; return@LaunchedEffect }
        val pageIndex = snapshot.sheetPage - 1
        sheetBitmap = withContext(Dispatchers.IO) {
            extractLargestEmbeddedImage(file, pageIndex) ?: renderSheetPageFallback(file, pageIndex)
        }
    }
```
You'll need `import kotlinx.coroutines.Dispatchers` and `import kotlinx.coroutines.withContext` (check if already imported — this file already uses `kotlinx.coroutines.delay` fully-qualified inline, so these may not be imported yet) and `import androidx.compose.runtime.mutableStateOf`/`remember` (likely already imported — verify).

- [ ] **Step 4: Branch the render — bespoke pane for SHEET, existing `UnifiedReferenceViewer` for everything else**

Replace the `Box(modifier = Modifier.fillMaxWidth().weight(1f)) { UnifiedReferenceViewer(...) ... }` block's content so `UnifiedReferenceViewer` is only used for non-SHEET doc types, and SHEET renders `DiagramView` on the resolved bitmap plus a compact prev/next+counter pill matching the visual style `UnifiedReferenceViewer`'s `showNavigationButtons=true` already produces elsewhere in this codebase (a small `Surface` pill, bottom-end aligned — look at `ReferencePdfPane.kt` around its `showNavigationButtons` block, roughly lines 736-780, for the exact visual pattern to mirror: rounded `Surface` with `tonalElevation`, a `Row` containing prev `IconButton`, a page-count `Text`, next `IconButton`).

Structure:
```kotlin
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        if (snapshot.docType == ReferenceDocType.SHEET) {
                            val bmp = sheetBitmap
                            if (bmp != null) {
                                DiagramView(
                                    bitmap = bmp,
                                    parts = emptyList(),
                                    selectedPartNumber = null,
                                    diagramBboxes = emptyMap(),
                                    resetZoomTrigger = snapshot.sheetPage,
                                    onTapPart = {},
                                    onLongPressPart = {},
                                    modifier = Modifier.fillMaxSize(),
                                    onTapEmpty = null
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    androidx.compose.material3.CircularProgressIndicator()
                                }
                            }
                            if (sheetTotalPages > 1) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                                    tonalElevation = 3.dp,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)
                                    ) {
                                        IconButton(
                                            onClick = { state.setPage(snapshot.sheetPage - 1) },
                                            enabled = snapshot.sheetPage > 1,
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous", modifier = Modifier.size(20.dp))
                                        }
                                        Text("${snapshot.sheetPage}/$sheetTotalPages", style = MaterialTheme.typography.labelMedium)
                                        IconButton(
                                            onClick = { state.setPage(snapshot.sheetPage + 1) },
                                            enabled = snapshot.sheetPage < sheetTotalPages,
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next", modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            }
                        } else {
                            UnifiedReferenceViewer(
                                modifier = Modifier.fillMaxSize(),
                                displayPage = snapshot.pageForActiveDoc(),
                                onDisplayPageChange = { state.setPage(it) },
                                defaultPdfFilename = referenceData.defaultPdfFilename,
                                pdfFileForFilename = { filename ->
                                    jobRepository.getJobRootPdfFile(
                                        jobFolderName = jobFolderName,
                                        pdfFilename = filename,
                                        preferDarkMode = isDarkTheme
                                    )
                                },
                                fileIdentitySeed = refreshGeneration,
                                preferDarkMode = isDarkTheme,
                                virtualMapping = referenceData.virtualMapping,
                                navigatorCabinetToPages = referenceData.navigatorCabinetToPages,
                                navigatorPlanViewLabels = referenceData.navigatorPlanViewLabels,
                                navigatorWarningMessage = referenceData.warningMessage,
                                missingText = "Reference PDF not found.",
                                unreadableText = "Unable to read PDF pages.",
                                showHeaderRow = false,
                                showNavigationButtons = true,
                                compactArrows = true
                            )
                        }
                        if (showNote) {
                            // unchanged — existing "no reference" Surface note block stays exactly as-is
                        }
                    }
```
(The non-SHEET `pdfFileForFilename` lambda no longer needs the `snapshot.docType == SHEET` special-case — that branch is now unreachable in the `else` block since SHEET never reaches it, so simplify it back to the plain lookup as shown above. The `showNote` block underneath is unchanged — leave its existing code exactly where it is, just now a sibling of the new `if/else` instead of a sibling of a single unconditional `UnifiedReferenceViewer` call.)

- [ ] **Step 5: Build**

Run: `.\gradlew.bat :app:compileDebugKotlin` — expect BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt
git commit -m "fix(viewer): crop popup Sheet tab to diagram image, matching main viewer"
```

### Task 9: Compact the main-view mode toggle; fold Popup Viewer into it

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt` (the `Row` holding `pdfFilename` + mode toggle + Popup Viewer chip + remake chip, ~line 1403-1451)

No new unit tests — Compose UI styling only.

- [ ] **Step 1: Fold "Popup Viewer" into the segmented row as a 4th segment, and compact all four**

Replace the `SingleChoiceSegmentedButtonRow { ... }` block plus the separate `if (hasAssemblyReference || hasPlansReference) { AssistChip(...) }` block immediately after it with a single merged group:

```kotlin
                        val showPopupSegment = hasAssemblyReference || hasPlansReference
                        val segmentCount = if (showPopupSegment) 4 else 3
                        SingleChoiceSegmentedButtonRow {
                            SegmentedButton(
                                selected = mainViewRef.snapshot.mode == null,
                                onClick = { mainViewRef.setMode(null) },
                                enabled = true,
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = segmentCount),
                                label = { Text("Sheet", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                            )
                            SegmentedButton(
                                selected = mainViewRef.snapshot.mode == ReferenceDocType.PLANS_ELEVATIONS,
                                onClick = { mainViewRef.setMode(ReferenceDocType.PLANS_ELEVATIONS) },
                                enabled = hasPlansReference,
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = segmentCount),
                                label = { Text("Plans & Elev.", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                            )
                            SegmentedButton(
                                selected = mainViewRef.snapshot.mode == ReferenceDocType.ASSEMBLY,
                                onClick = { mainViewRef.setMode(ReferenceDocType.ASSEMBLY) },
                                enabled = hasAssemblyReference,
                                shape = SegmentedButtonDefaults.itemShape(index = 2, count = segmentCount),
                                label = { Text("Assembly", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                            )
                            if (showPopupSegment) {
                                SegmentedButton(
                                    selected = referenceModal.snapshot.isOpen,
                                    onClick = { referenceModal.toggleOpen(hasPlansReference, hasAssemblyReference, defaultModalDoc) },
                                    enabled = true,
                                    shape = SegmentedButtonDefaults.itemShape(index = 3, count = segmentCount),
                                    icon = {
                                        Icon(
                                            Icons.Filled.OpenInNew,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = { Text("Popup", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                                )
                            }
                        }
```
Note: the label text stays "Popup" (not "Popup Viewer") since it's now paired with an icon in a compact segment matching its siblings, not a standalone chip — this is a deliberate label shortening as part of "more compact," unlike the other three labels which stay full text.

If reducing to `labelMedium` alone isn't enough to reliably avoid wrapping/overflow on realistic long filenames, also try reducing icon size further and/or wrapping the whole `SingleChoiceSegmentedButtonRow` + `pdfFilename` Text in a layout that lets the segmented row take only the width it needs while the filename Text (already `Modifier.weight(1f)`, already `TextOverflow.Ellipsis`) absorbs any remaining shrink — the filename should always be the one to truncate first, never the controls. Use the device (adb screenshot as done during Task 7) to sanity-check the result on the actual tablet against a job with a long filename before considering this step done — don't rely on static reasoning alone for a visual layout fix.

- [ ] **Step 2: Build**

Run: `.\gradlew.bat :app:compileDebugKotlin` — expect BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt
git commit -m "style(viewer): compact main-view mode toggle, fold Popup Viewer into segmented row"
```

---

## Addendum 2: fixes found in final whole-feature review + further device feedback

### Task 10: Popup Sheet-tab cache/page-count invalidate on rescan

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt`

The popup's Sheet-tab `sheetBitmap`/`sheetTotalPages`/`sheetBitmapCache` (added in Task 8, hardened in its
fix-up commit) are keyed only on `sheetPdfFile` (path-based `File` equality) and `snapshot.sheetPage` —
never on `refreshGeneration`. Every other pipeline touched by this plan invalidates on rescan: the non-SHEET
branch passes `fileIdentitySeed = refreshGeneration` into `UnifiedReferenceViewer`; the main viewer's own
Sheet-mode cache is explicitly invalidated via `invalidatePreparedPagesForDocument` on fingerprint/identity
change. If the underlying CNC PDF is regenerated/synced while the popup is open on the Sheet tab, previously
cached pages can keep showing stale bitmaps.

- [ ] **Step 1**: Add `refreshGeneration` as a key to the cache's scoping `remember` and to both
  `LaunchedEffect`s that populate `sheetBitmap`/`sheetTotalPages`, e.g.:
  ```kotlin
  val sheetBitmapCache = remember(sheetPdfFile, refreshGeneration) { mutableMapOf<Int, Bitmap>() }
  ```
  and add `refreshGeneration` to both `LaunchedEffect(...)` key lists (`sheetTotalPages`'s effect and the
  bitmap-resolution effect). This makes a rescan (`refreshGeneration` bump) transparently drop the stale
  cache and re-resolve the current page, the same way `fileIdentitySeed` does for the non-SHEET path.
- [ ] **Step 2**: Build (`.\gradlew.bat :app:compileDebugKotlin` → BUILD SUCCESSFUL).
- [ ] **Step 3**: Commit: `fix(viewer): invalidate popup Sheet-tab cache on file rescan`.

### Task 11: Merge chips row + mode toggle onto one line; drop redundant filename text

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt` (~lines 1389-1490 —
  the `Column` holding the file-chips `Row` and the filename+toggle `Row`)

Reported live: on some jobs the chips row (R-number/size chips) and the toggle row (Sheet/Plans &
Elev./Assembly/Popup) read as two separate, visually misaligned lines, and the small `pdfFilename` text
("649 - 19mm Pre_Finished.pdf") is redundant with the screen's own title bar. Fix: merge everything onto
one row — chips scroll in the remaining space, drop the filename text entirely, toggle (and remake chip)
stay pinned on the right.

- [ ] **Step 1**: Replace the two-`Row`-plus-`Spacer` `Column` body with a single `Row`:
  ```kotlin
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
  ) {
      if (chips.isNotEmpty() || sheetSizeLabel != null) {
          Row(
              modifier = Modifier
                  .weight(1f)
                  .horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
              chips.forEach { label ->
                  AssistChip(
                      onClick = {},
                      label = { Text(label) },
                      colors = AssistChipDefaults.assistChipColors(
                          containerColor = MaterialTheme.colorScheme.secondaryContainer,
                          labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                      )
                  )
              }
              if (sheetSizeLabel != null) {
                  AssistChip(
                      onClick = {},
                      label = { Text(sheetSizeLabel) },
                      colors = AssistChipDefaults.assistChipColors(
                          containerColor = MaterialTheme.colorScheme.secondaryContainer,
                          labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                      )
                  )
              }
          }
      } else {
          Spacer(Modifier.weight(1f))
      }
      val segmentCount = if (resolvedShowPopupSegment) 4 else 3
      SingleChoiceSegmentedButtonRow {
          // ...four SegmentedButtons, unchanged from current code...
      }
      val remakeLabel = currentPageRemake?.label?.takeIf { it.isNotBlank() }
      if (remakeLabel != null) {
          AssistChip(
              onClick = {},
              enabled = false,
              label = { Text(remakeLabel) },
              leadingIcon = { Icon(imageVector = Icons.Default.Warning, contentDescription = "Remake") }
          )
      }
  }
  ```
  The `pdfFilename` `Text` composable is removed entirely — `pdfFilename` the *variable* stays untouched
  everywhere else in the file (it's still needed for `sheetPdfFilename`/file lookups), only its UI display
  here goes away. The four `SegmentedButton`s inside `SingleChoiceSegmentedButtonRow` are copied verbatim
  from the current code (Task 9's merged 4-segment row) — no change to their content, only to what
  surrounds them.
- [ ] **Step 2**: Build (`.\gradlew.bat :app:compileDebugKotlin` → BUILD SUCCESSFUL).
- [ ] **Step 3**: Device-verify (tablet at ADB serial `R52T602QXRE` available): install release build,
  navigate to a job, screenshot, confirm chips + toggle read as one aligned row and the filename text is
  gone.
- [ ] **Step 4**: Commit: `style(viewer): merge chips row with mode toggle, drop redundant filename text`.


