# CNC Popup Reference Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a draggable/resizable floating popup in the CNC sheet viewer that shows Plans & Elevations / Assembly reference sheets inline, jumping to a part's cabinet page when the part is tapped.

**Architecture:** Reuse the Calculator overlay pattern (SharedPreferences-backed floating Box) to host the existing `UnifiedReferenceViewer`. Extract the per-doc-type data derivation currently inline in `ReferencePdfViewerScreen` into a shared `rememberReferenceViewerData` composable so the full-screen viewer and the new modal behave identically.

**Tech Stack:** Kotlin, Jetpack Compose, Material3 (`SingleChoiceSegmentedButtonRow`), Haze (frosted glass), JUnit4 (pure-logic unit tests only — no Robolectric in this module).

**Spec:** `docs/superpowers/specs/2026-07-16-cnc-popup-reference-viewer-design.md`

---

## File Structure

- **Create** `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferenceViewerData.kt` — `ReferenceViewerData` data class + `rememberReferenceViewerData(...)`. Single responsibility: derive per-doc-type PDF/navigator data off the main thread.
- **Create** `app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt` — `ReferenceModalSnapshot`, pure helpers (`pageForActiveDoc`, `withDocType`, `resolveJumpPage`), `ReferenceModalOverlayState`, `rememberReferenceModalOverlayState()`, `ReferenceModalHost(...)`.
- **Create** `app/src/test/java/com/kkc/sheettracker/ui/components/ReferenceModalStateTest.kt` — unit tests for the pure helpers.
- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt` — replace inline derivation (lines ~73–201) with `rememberReferenceViewerData(...)`.
- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt` — "Popup Viewer" button in the chip row, host the modal, wire the part-tap jump.

---

## Task 1: Extract `rememberReferenceViewerData` (behavior-preserving refactor)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferenceViewerData.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt:73-201`

This is a pure move. No unit test (Compose/`produceState` + engine I/O). Verified by compile + manual full-screen check at the end.

- [ ] **Step 1: Create the shared data file**

Create `ReferenceViewerData.kt`. Move the derivation logic out of `ReferencePdfViewerScreen` verbatim (the block currently spanning `documentIndex` through `defaultPdfFilename`, lines ~73–201), wrapped in a composable that returns a bundle. Keep the exact same `produceState` / `Dispatchers.IO` calls — do NOT convert any of them to synchronous `remember{}` (project rule: engine I/O must stay off the main thread).

```kotlin
package com.kkc.sheettracker.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import com.kkc.sheettracker.data.models.ReferenceDocType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ReferenceViewerData(
    val defaultPdfFilename: String,
    val virtualMapping: UnifiedVirtualPageMapping?,
    val navigatorCabinetToPages: Map<String, List<Int>>,
    val navigatorPlanViewLabels: Map<Int, String>,
    val warningMessage: String?
)

@Composable
fun rememberReferenceViewerData(
    jobRepository: JobRepository,
    jobFolderName: String,
    docType: ReferenceDocType,
    refreshGeneration: Long,
    isDarkTheme: Boolean
): ReferenceViewerData {
    val sheetIndex by produceState<CabinetSheetIndex?>(
        initialValue = null,
        key1 = jobFolderName,
        key2 = refreshGeneration
    ) {
        value = withContext(Dispatchers.IO) { jobRepository.getCabinetSheetIndex(jobFolderName) }
    }
    val documentIndex = remember(sheetIndex, docType) {
        when (docType) {
            ReferenceDocType.ASSEMBLY -> sheetIndex?.documents?.assembly
            ReferenceDocType.PLANS_ELEVATIONS -> sheetIndex?.documents?.plansElevations
            ReferenceDocType.DELIVERY_SHEETS -> null
        }
    }

    val assemblyVirtualRawMap = remember(sheetIndex) {
        sheetIndex?.documents?.assembly?.virtualCombined?.virtualPageToSource
            ?.mapNotNull { (virtualPageKey, source) ->
                val page = virtualPageKey.toIntOrNull() ?: return@mapNotNull null
                if (page <= 0) return@mapNotNull null
                page to UnifiedVirtualPageSource(
                    pdfFilename = source.pdfFilename,
                    page = source.page,
                    cabinet = source.cabinet,
                    sourceVariant = source.variant
                )
            }
            ?.toMap()
            .orEmpty()
    }
    val assemblyVirtualTotalPages = remember(sheetIndex) {
        (sheetIndex?.documents?.assembly?.virtualCombined?.totalVirtualPages ?: 0).coerceAtLeast(0)
    }
    val assemblyFallbackPdfFilename by produceState(
        initialValue = "",
        key1 = documentIndex,
        key2 = jobFolderName
    ) {
        value = documentIndex?.pdfFilename?.takeIf { it.isNotBlank() }
            ?: withContext(Dispatchers.IO) {
                jobRepository.findReferencePdfFilename(jobFolderName, ReferenceDocType.ASSEMBLY)
            }.orEmpty()
    }
    val assemblyVirtualSanitized = remember(
        assemblyVirtualTotalPages,
        assemblyVirtualRawMap,
        documentIndex,
        sheetIndex,
        assemblyFallbackPdfFilename
    ) {
        sanitizeVirtualAssemblyData(
            totalVirtualPages = assemblyVirtualTotalPages,
            defaultPdfFilename = assemblyFallbackPdfFilename,
            sourceByDisplayPage = assemblyVirtualRawMap,
            cabinetToPages = sheetIndex?.documents?.assembly?.virtualCombined?.cabinetToPages.orEmpty()
        )
    }
    val virtualMapping = remember(docType, assemblyVirtualSanitized) {
        if (docType != ReferenceDocType.ASSEMBLY || assemblyVirtualTotalPages <= 0) {
            null
        } else {
            assemblyVirtualSanitized.mapping
        }
    }
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
        }
    }
    val navigatorPlanViewLabels = remember(docType, documentIndex) {
        if (docType != ReferenceDocType.PLANS_ELEVATIONS) {
            emptyMap()
        } else {
            val pageToRoom = documentIndex?.pageDetails
                .orEmpty()
                .mapNotNull { (pageKey, detail) ->
                    val page = pageKey.toIntOrNull() ?: return@mapNotNull null
                    val room = extractRoomDisplayName(detail.room) ?: return@mapNotNull null
                    page to room
                }
                .toMap()
            buildPlanViewLabelsFromPageToRoom(pageToRoom)
        }
    }
    val defaultPdfFilename by produceState(
        documentIndex?.pdfFilename?.takeIf { it.isNotBlank() }.orEmpty(),
        documentIndex,
        docType,
        jobFolderName,
        refreshGeneration
    ) {
        value = documentIndex?.pdfFilename?.takeIf { it.isNotBlank() }
            ?: withContext(Dispatchers.IO) {
                jobRepository.findReferencePdfFilename(jobFolderName, docType)
            }.orEmpty()
    }

    return ReferenceViewerData(
        defaultPdfFilename = defaultPdfFilename,
        virtualMapping = virtualMapping,
        navigatorCabinetToPages = navigatorCabinetToPages,
        navigatorPlanViewLabels = navigatorPlanViewLabels,
        warningMessage = if (docType == ReferenceDocType.ASSEMBLY) {
            assemblyVirtualSanitized.warningMessage
        } else {
            null
        }
    )
}
```

- [ ] **Step 2: Rewrite `ReferencePdfViewerScreen` to consume the bundle**

In `ReferencePdfViewerScreen.kt`, delete the moved derivation (the `sheetIndex`, `documentIndex`, `assemblyVirtual*`, `virtualMapping`, `navigatorCabinetToPages`, `navigatorPlanViewLabels`, and `defaultPdfFilename` declarations, lines ~66–201) and replace with:

```kotlin
val referenceData = rememberReferenceViewerData(
    jobRepository = jobRepository,
    jobFolderName = jobFolderName,
    docType = docType,
    refreshGeneration = refreshGeneration,
    isDarkTheme = isDarkTheme
)
```

Then update the `UnifiedReferenceViewer(...)` call args (lines ~234–269) to read from `referenceData`:
- `defaultPdfFilename = referenceData.defaultPdfFilename`
- `virtualMapping = referenceData.virtualMapping`
- `navigatorCabinetToPages = referenceData.navigatorCabinetToPages`
- `navigatorPlanViewLabels = referenceData.navigatorPlanViewLabels`
- `navigatorWarningMessage = referenceData.warningMessage`

Remove now-unused imports (`CabinetSheetIndex`, `produceState`, `Dispatchers`, `withContext`) if no other reference remains in the file. Keep `resumeKey`/`currentPage`/markup logic as-is.

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Fix any missing-import / unused-import errors surfaced.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferenceViewerData.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt
git commit -m "refactor(viewer): extract rememberReferenceViewerData for reuse"
```

---

## Task 2: `ReferenceModalOverlay` state + pure helpers (TDD)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/ReferenceModalStateTest.kt`

Unit-test the pure logic (page resolution, doc-toggle page restore). SharedPreferences glue is thin and verified manually.

- [ ] **Step 1: Write the failing test**

Create `ReferenceModalStateTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.models.ReferenceDocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReferenceModalStateTest {

    @Test
    fun pageForActiveDoc_returnsPerDocPage() {
        val snap = ReferenceModalSnapshot(
            docType = ReferenceDocType.PLANS_ELEVATIONS,
            plansPage = 4,
            assemblyPage = 9
        )
        assertEquals(4, snap.pageForActiveDoc())
        assertEquals(9, snap.copy(docType = ReferenceDocType.ASSEMBLY).pageForActiveDoc())
    }

    @Test
    fun withDocType_switchesAndKeepsOtherDocPage() {
        val snap = ReferenceModalSnapshot(
            docType = ReferenceDocType.PLANS_ELEVATIONS,
            plansPage = 4,
            assemblyPage = 9
        )
        val switched = snap.withDocType(ReferenceDocType.ASSEMBLY)
        assertEquals(ReferenceDocType.ASSEMBLY, switched.docType)
        assertEquals(4, switched.plansPage)
        assertEquals(9, switched.assemblyPage)
        assertEquals(9, switched.pageForActiveDoc())
    }

    @Test
    fun withPage_updatesOnlyActiveDoc() {
        val snap = ReferenceModalSnapshot(
            docType = ReferenceDocType.PLANS_ELEVATIONS,
            plansPage = 4,
            assemblyPage = 9
        )
        val updated = snap.withPage(7)
        assertEquals(7, updated.plansPage)
        assertEquals(9, updated.assemblyPage)
    }

    @Test
    fun resolveJumpPage_returnsFirstPageForCabinet() {
        val map = mapOf("3" to listOf(5, 6), "8" to listOf(11))
        assertEquals(5, resolveJumpPage(map, 3))
        assertEquals(11, resolveJumpPage(map, 8))
    }

    @Test
    fun resolveJumpPage_returnsNullWhenCabinetAbsent() {
        val map = mapOf("3" to listOf(5))
        assertNull(resolveJumpPage(map, 99))
        assertNull(resolveJumpPage(emptyMap(), 3))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ReferenceModalStateTest"`
Expected: FAIL — `ReferenceModalSnapshot` / `resolveJumpPage` unresolved.

- [ ] **Step 3: Write the snapshot + pure helpers**

Create `ReferenceModalOverlay.kt` with the data + pure logic first (state class and Host added in Steps 5 & Task 3):

```kotlin
package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.models.ReferenceDocType

data class ReferenceModalSnapshot(
    val isOpen: Boolean = false,
    val docType: ReferenceDocType = ReferenceDocType.PLANS_ELEVATIONS,
    val plansPage: Int = 1,
    val assemblyPage: Int = 1,
    val modalX: Float = 24f,
    val modalY: Float = 24f,
    val modalWidth: Float = 360f,
    val modalHeight: Float = 480f
) {
    fun pageForActiveDoc(): Int =
        if (docType == ReferenceDocType.ASSEMBLY) assemblyPage else plansPage

    fun withDocType(next: ReferenceDocType): ReferenceModalSnapshot = copy(docType = next)

    fun withPage(page: Int): ReferenceModalSnapshot {
        val safe = page.coerceAtLeast(1)
        return if (docType == ReferenceDocType.ASSEMBLY) copy(assemblyPage = safe) else copy(plansPage = safe)
    }
}

/** First page mapped to [cabinet] in the active doc's page space, or null if none. */
fun resolveJumpPage(cabinetToPages: Map<String, List<Int>>, cabinet: Int): Int? =
    cabinetToPages[cabinet.toString()]?.firstOrNull()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ReferenceModalStateTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Add the SharedPreferences-backed state class**

Append to `ReferenceModalOverlay.kt` (mirrors `CalculatorOverlayState`; `showNoRefNote` is transient, not persisted):

```kotlin
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

class ReferenceModalOverlayState internal constructor(
    private val prefs: SharedPreferences
) {
    var snapshot by mutableStateOf(load(prefs))
        private set

    var noRefNoteToken by mutableStateOf(0)
        private set

    fun toggleOpen(defaultDocType: ReferenceDocType?) = setOpen(!snapshot.isOpen, defaultDocType)

    fun setOpen(open: Boolean, defaultDocType: ReferenceDocType?) {
        var next = snapshot
        if (open && defaultDocType != null) next = next.withDocType(defaultDocType)
        next = next.copy(isOpen = open)
        if (next == snapshot) return
        snapshot = next
        persist()
    }

    fun setDocType(docType: ReferenceDocType) {
        if (snapshot.docType == docType) return
        snapshot = snapshot.withDocType(docType)
        persist()
    }

    fun setPage(page: Int) {
        val next = snapshot.withPage(page)
        if (next == snapshot) return
        snapshot = next
        persist()
    }

    fun showNoRefNote() { noRefNoteToken += 1 }

    fun updateModalBounds(x: Float, y: Float, width: Float, height: Float, persistNow: Boolean) {
        val next = snapshot.copy(modalX = x, modalY = y, modalWidth = width, modalHeight = height)
        if (next == snapshot) return
        snapshot = next
        if (persistNow) persist()
    }

    fun clampToViewport(vw: Float, vh: Float, margin: Float, minW: Float, minH: Float) {
        val maxW = (vw - margin * 2f).coerceAtLeast(minW)
        val maxH = (vh - margin * 2f).coerceAtLeast(minH)
        val w = snapshot.modalWidth.coerceIn(minW, maxW)
        val h = snapshot.modalHeight.coerceIn(minH, maxH)
        val x = snapshot.modalX.coerceIn(margin, (vw - w - margin).coerceAtLeast(margin))
        val y = snapshot.modalY.coerceIn(margin, (vh - h - margin).coerceAtLeast(margin))
        val next = snapshot.copy(modalX = x, modalY = y, modalWidth = w, modalHeight = h)
        if (next != snapshot) { snapshot = next; persist() }
    }

    private fun persist() {
        prefs.edit()
            .putBoolean(KEY_OPEN, snapshot.isOpen)
            .putString(KEY_DOC, snapshot.docType.name)
            .putInt(KEY_PLANS_PAGE, snapshot.plansPage)
            .putInt(KEY_ASM_PAGE, snapshot.assemblyPage)
            .putFloat(KEY_X, snapshot.modalX)
            .putFloat(KEY_Y, snapshot.modalY)
            .putFloat(KEY_W, snapshot.modalWidth)
            .putFloat(KEY_H, snapshot.modalHeight)
            .apply()
    }

    companion object {
        private const val PREFS_FILE = "kkc_tracker"
        private const val KEY_OPEN = "refmodal_open"
        private const val KEY_DOC = "refmodal_doc"
        private const val KEY_PLANS_PAGE = "refmodal_plans_page"
        private const val KEY_ASM_PAGE = "refmodal_asm_page"
        private const val KEY_X = "refmodal_x_dp"
        private const val KEY_Y = "refmodal_y_dp"
        private const val KEY_W = "refmodal_w_dp"
        private const val KEY_H = "refmodal_h_dp"

        fun create(context: Context): ReferenceModalOverlayState =
            ReferenceModalOverlayState(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))

        private fun load(prefs: SharedPreferences): ReferenceModalSnapshot {
            val doc = prefs.getString(KEY_DOC, null)
                ?.let { runCatching { ReferenceDocType.valueOf(it) }.getOrNull() }
                ?: ReferenceDocType.PLANS_ELEVATIONS
            return ReferenceModalSnapshot(
                isOpen = prefs.getBoolean(KEY_OPEN, false),
                docType = doc,
                plansPage = prefs.getInt(KEY_PLANS_PAGE, 1),
                assemblyPage = prefs.getInt(KEY_ASM_PAGE, 1),
                modalX = prefs.getFloat(KEY_X, 24f),
                modalY = prefs.getFloat(KEY_Y, 24f),
                modalWidth = prefs.getFloat(KEY_W, 360f),
                modalHeight = prefs.getFloat(KEY_H, 480f)
            )
        }
    }
}

@Composable
fun rememberReferenceModalOverlayState(): ReferenceModalOverlayState {
    val context = LocalContext.current
    return remember { ReferenceModalOverlayState.create(context) }
}
```

- [ ] **Step 6: Compile**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt app/src/test/java/com/kkc/sheettracker/ui/components/ReferenceModalStateTest.kt
git commit -m "feat(viewer): add ReferenceModalOverlay state + pure helpers"
```

---

## Task 3: `ReferenceModalHost` composable (floating frame + embedded viewer)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt`

UI — no unit test; manual verification in Task 4.

- [ ] **Step 1: Add the Host composable**

Append `ReferenceModalHost` to `ReferenceModalOverlay.kt`. It reuses the calculator's drag-to-move header, `◢` resize corner, viewport clamp, and frosted `hazeEffect`, and embeds `UnifiedReferenceViewer`. Add the imports it needs (Compose layout/foundation/material3, `Icons.Filled.OpenInNew` not needed here, `Icons.Filled.Close`, haze, `LocalDensity`, `zIndex`, `LocalKKCThemeTokens`, `SingleChoiceSegmentedButtonRow`/`SegmentedButton`/`SegmentedButtonDefaults`, `java.io.File`, `com.kkc.sheettracker.data.JobRepository`, `com.kkc.sheettracker.data.models.ReferenceDocType`, `com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewer`, `com.kkc.sheettracker.ui.viewer.rememberReferenceViewerData`).

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
    hazeState: dev.chrisbanes.haze.HazeState? = null,
    modifier: Modifier = Modifier
) {
    val snapshot = state.snapshot
    if (!snapshot.isOpen) return

    androidx.activity.compose.BackHandler(enabled = snapshot.isOpen) {
        state.setOpen(false, null)
    }

    val referenceData = com.kkc.sheettracker.ui.viewer.rememberReferenceViewerData(
        jobRepository = jobRepository,
        jobFolderName = jobFolderName,
        docType = snapshot.docType,
        refreshGeneration = refreshGeneration,
        isDarkTheme = isDarkTheme
    )

    // "No reference for this cabinet" transient note — cleared on doc change or next successful jump.
    var noteVisibleForToken by remember { mutableStateOf(-1) }
    LaunchedEffect(state.noRefNoteToken) {
        if (state.noRefNoteToken > 0) {
            noteVisibleForToken = state.noRefNoteToken
            kotlinx.coroutines.delay(2500)
            if (noteVisibleForToken == state.noRefNoteToken) noteVisibleForToken = -1
        }
    }
    val showNote = noteVisibleForToken == state.noRefNoteToken && state.noRefNoteToken > 0

    val margin = 12f
    val minWidth = 300f
    val minHeight = 360f
    val density = LocalDensity.current.density

    Box(modifier = modifier.fillMaxSize().zIndex(9f)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(4.dp)
        ) {
            val vw = maxWidth.value
            val vh = maxHeight.value
            LaunchedEffect(vw, vh, snapshot.modalX, snapshot.modalY, snapshot.modalWidth, snapshot.modalHeight) {
                state.clampToViewport(vw, vh, margin, minWidth, minHeight)
            }
            val maxWDp = (vw - margin * 2f).coerceAtLeast(minWidth)
            val maxHDp = (vh - margin * 2f).coerceAtLeast(minHeight)
            val widthDp = snapshot.modalWidth.coerceIn(minWidth, maxWDp)
            val heightDp = snapshot.modalHeight.coerceIn(minHeight, maxHDp)

            val shape = RoundedCornerShape(15.dp)
            val frosted = LocalKKCThemeTokens.current.frosted
            val panelModifier = if (hazeState != null) {
                Modifier.hazeEffect(
                    state = hazeState,
                    style = HazeDefaults.style(
                        backgroundColor = MaterialTheme.colorScheme.surface.copy(
                            alpha = frosted.backgroundAlpha.coerceIn(0.72f, 0.95f)
                        ),
                        blurRadius = frosted.blurDp.coerceAtLeast(1f).dp
                    )
                )
            } else {
                Modifier.background(MaterialTheme.colorScheme.surface)
            }

            Box(
                modifier = Modifier
                    .offset(snapshot.modalX.dp, snapshot.modalY.dp)
                    .width(widthDp.dp)
                    .height(heightDp.dp)
                    .shadow(10.dp, shape, clip = false)
                    .clip(shape)
                    .then(panelModifier)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header: drag handle + doc toggle + close
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f))
                            .pointerInput(vw, vh, widthDp, heightDp) {
                                var liveX = 0f
                                var liveY = 0f
                                detectDragGestures(
                                    onDragStart = { liveX = state.snapshot.modalX; liveY = state.snapshot.modalY },
                                    onDragEnd = {
                                        val s = state.snapshot
                                        state.updateModalBounds(s.modalX, s.modalY, s.modalWidth, s.modalHeight, true)
                                    }
                                ) { change, drag ->
                                    change.consume()
                                    liveX = (liveX + drag.x / density)
                                        .coerceIn(margin, (vw - widthDp - margin).coerceAtLeast(margin))
                                    liveY = (liveY + drag.y / density)
                                        .coerceIn(margin, (vh - heightDp - margin).coerceAtLeast(margin))
                                    state.updateModalBounds(liveX, liveY, state.snapshot.modalWidth, state.snapshot.modalHeight, false)
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                        IconButton(onClick = { state.setOpen(false, null) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close reference popup")
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
                        if (showNote) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    "No reference sheet for this cabinet in ${if (snapshot.docType == ReferenceDocType.ASSEMBLY) "Assembly" else "Plans"}.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
```

Add the remaining imports at the top of the file (copy the import list from `CalculatorOverlay.kt` for the layout/foundation/material3/haze/graphics ones: `Box`, `BoxWithConstraints`, `Column`, `Row`, `fillMaxSize`, `fillMaxWidth`, `height`, `width`, `offset`, `padding`, `windowInsetsPadding`, `WindowInsets`, `safeDrawing`, `RoundedCornerShape`, `background`, `border` not needed, `detectDragGestures`, `pointerInput`, `clip`, `shadow`, `zIndex`, `Alignment`, `dp`, `MaterialTheme`, `Surface`, `Text`, `Icon`, `IconButton`, `Icons`, `Icons.Filled.Close`, `LaunchedEffect`, `mutableStateOf`, `remember`, `getValue`, `setValue`, `HazeDefaults`, `hazeEffect`, `LocalDensity`).

- [ ] **Step 2: Compile**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Resolve any missing imports.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ReferenceModalOverlay.kt
git commit -m "feat(viewer): add ReferenceModalHost floating frame"
```

---

## Task 4: Wire button, host, and part-tap jump into the CNC viewer

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`

- [ ] **Step 1: Create the modal state near the other state**

In `SheetViewerScreen` (after the existing `showReferenceDocDialog` state, ~line 337), add:

```kotlin
val referenceModal = com.kkc.sheettracker.ui.components.rememberReferenceModalOverlayState()
```

Determine the default doc for opening (Plans preferred, else Assembly). Place near `hasAssemblyReference`/`hasPlansReference` (~line 370):

```kotlin
val defaultModalDoc: ReferenceDocType? = when {
    hasPlansReference -> ReferenceDocType.PLANS_ELEVATIONS
    hasAssemblyReference -> ReferenceDocType.ASSEMBLY
    else -> null
}
```

- [ ] **Step 2: Add the "Popup Viewer" button in the chip row**

In the filename `Row` (`SheetViewerScreen.kt:1359-1371`), after the `Text(pdfFilename, ...)` (which has `weight(1f)`) and before/after the `remakeLabel` chip, insert the button so it sits inline on the right with the chips. Add import `androidx.compose.material.icons.filled.OpenInNew`.

```kotlin
if (hasAssemblyReference || hasPlansReference) {
    AssistChip(
        onClick = { referenceModal.toggleOpen(defaultModalDoc) },
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
```

- [ ] **Step 3: Wire the part-tap jump**

Add this `LaunchedEffect` at the same scope as `selectedCabinetNumber` (top-level in the composable body, e.g. near line 450 after the derived state). It reads the active doc's cabinet→pages from a bundle derived for the modal's current docType:

```kotlin
val modalReferenceData = com.kkc.sheettracker.ui.viewer.rememberReferenceViewerData(
    jobRepository = jobRepository,
    jobFolderName = jobFolderName,
    docType = referenceModal.snapshot.docType,
    refreshGeneration = fileFingerprint,
    isDarkTheme = isDarkTheme
)
LaunchedEffect(selectedCabinetNumber, referenceModal.snapshot.isOpen, referenceModal.snapshot.docType) {
    val cabinet = selectedCabinetNumber
    if (!referenceModal.snapshot.isOpen || cabinet == null) return@LaunchedEffect
    val target = com.kkc.sheettracker.ui.components.resolveJumpPage(
        modalReferenceData.navigatorCabinetToPages, cabinet
    )
    if (target != null) referenceModal.setPage(target) else referenceModal.showNoRefNote()
}
```

Note: confirm the exact names in scope — `jobRepository`, `jobFolderName`, `isDarkTheme`, and the refresh key (`fileFingerprint` is referenced in the existing debug log at line 1445; if the in-scope name differs, use the same value passed to the full-screen viewer's `refreshGeneration`). If `isDarkTheme` is not a local, use the same expression the screen already uses for dark mode.

- [ ] **Step 4: Host the modal as an overlay**

Find the screen's root container (the outermost `Box`/`Scaffold` content). Add `ReferenceModalHost` as the LAST child of the root `Box` so it layers above the diagram (matching how the calculator overlay is hosted). Pass the same `hazeState` the screen already uses if present; otherwise pass `null`.

```kotlin
com.kkc.sheettracker.ui.components.ReferenceModalHost(
    state = referenceModal,
    jobRepository = jobRepository,
    jobFolderName = jobFolderName,
    refreshGeneration = fileFingerprint,
    isDarkTheme = isDarkTheme,
    hasPlans = hasPlansReference,
    hasAssembly = hasAssemblyReference,
    hazeState = null
)
```

If the root is a `Scaffold` (not a `Box`), wrap its content in a `Box { ... ReferenceModalHost(...) }` so the host can `fillMaxSize()` over the content.

- [ ] **Step 5: Compile**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Resolve scope/name mismatches flagged in Step 3.

- [ ] **Step 6: Run the full unit-test suite**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: PASS (the pre-existing PdfMarkup MotionEvent env-only failure may remain — that is a known off-device stub failure, not a regression).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt
git commit -m "feat(viewer): add Popup Viewer button, host, and part-tap jump in CNC viewer"
```

---

## Task 5: Manual verification on tablet

**Files:** none (verification only).

- [ ] **Step 1: Build + install the release APK**

Run: `.\adb-install-release.ps1`
(Tablets run release builds — do not verify on debug. If the script's unicode breaks under `powershell -File`, run `.\gradlew.bat assembleRelease` then `adb install -r app\build\outputs\apk\release\app-release.apk`.)

- [ ] **Step 2: Verify the feature**

On a connected tablet, open a CNC job in the sheet viewer and confirm:
- "Popup Viewer" chip (popout icon) appears under the header, inline with filename/sheet-size chips — and is hidden for jobs with no reference docs.
- Tapping it opens the floating modal; drag the header to move, drag `◢` to resize; close and reopen → position/size restored.
- Toggle Plans & Elevations / Assembly; a doc with no sheet is disabled; toggling restores that doc's last page.
- With the modal open, tap parts of different cabinets → modal jumps to the correct page in the active doc; the modal's own arrows + sheet-navigator work.
- Tap a part whose cabinet has no page in the active doc → transient "no reference sheet" note; modal stays put.
- Parts under the modal remain tappable (empty overlay regions fall through).
- Regression: long-press part → "Open Reference Sheet" full-screen path still works; full-screen `ReferencePdfViewerScreen` (Plans and Assembly) renders and navigates as before the Task 1 refactor.

- [ ] **Step 3: Update deferred memory note if needed**

No memory change required unless verification surfaces a new non-obvious constraint.

---

## Self-Review Notes

- **Spec coverage:** button placement (Task 4.2), modal frame + toggle + embedded nav/navigator (Task 3), part-tap jump following active doc (Task 4.3), missing-page note (Tasks 2/3), persistence of position+size+docType+per-doc page (Task 2.5), shared-data refactor (Task 1), page-space correctness via `rememberReferenceViewerData` (Tasks 1 & 4.3). All covered.
- **Type consistency:** `ReferenceModalSnapshot`, `ReferenceModalOverlayState`, `pageForActiveDoc()`, `withDocType()`, `withPage()`, `resolveJumpPage()`, `setOpen(open, defaultDocType)`, `setPage(page)`, `showNoRefNote()`, `ReferenceViewerData` fields — used identically across tasks.
- **Known env caveat:** one pre-existing PdfMarkup MotionEvent unit test fails off-device; not introduced here.
