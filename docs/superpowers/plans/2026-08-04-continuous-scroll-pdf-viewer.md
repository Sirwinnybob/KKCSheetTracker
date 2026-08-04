# Continuous Scroll for Reference PDF Viewers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a continuous-scroll mode (vertical stack in single/fullscreen, horizontal strip per pane in split mode) to the Assembly Sheets / Plans & Elevations / Cover Sheet viewers, with a segmented cabinet/room label scrollbar, without regressing battery life or the existing single-page viewer.

**Architecture:** New sibling composable `ContinuousReferencePdfPane` (Lazy-list virtualized, settle-only debounced rendering, per-page file resolver backed by a small capped `PdfRenderEngine` cache) plus a new `PdfLabelScrollbar`. `UnifiedReferenceViewer` picks between the existing `ReferencePdfPane` and the new pane based on a toggle threaded from Settings (global default) and an in-viewer session-only override, following the exact `useStandardSheets` plumbing pattern already used throughout `MainActivity.kt` / `NavGraph.kt` / `SettingsScreen.kt`.

**Tech Stack:** Kotlin, Jetpack Compose, `android.graphics.pdf.PdfRenderer`, JUnit4 (plain, no Robolectric — this codebase only unit-tests pure functions extracted from Compose files).

**Spec:** [docs/superpowers/specs/2026-08-04-continuous-scroll-pdf-viewer-design.md](../specs/2026-08-04-continuous-scroll-pdf-viewer-design.md)

---

## File Structure

Create:
- `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt` — new composable + pure helpers (`computeRenderWindow`, `lruTouch`/`lruEvictionCandidates`, `PdfEngineCache`)
- `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt` — new composable + pure helper (`segmentIndexForOffsetFraction`)
- `app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt`
- `app/src/test/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbarTest.kt`

Modify:
- `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt` — extract `resolvePageSource`, extract+widen `buildNavigatorRowModels`, add continuous-scroll params, branch pane selection, mount scrollbar
- `app/src/test/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewerTest.kt` — tests for the two extracted functions
- `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt` — session toggle + global default plumbing
- `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt` — session toggle + global default plumbing + split-mode detection
- `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt` — new "Continuous scroll" switch
- `app/src/main/java/com/kkc/sheettracker/MainActivity.kt` — new `continuousScrollDefault` pref-backed state
- `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt` — thread the new params through `AppNavigation`, `MultiBackStackNavigation`, `LegacySingleStackNavigation`, `SettingsTabHost` and their call sites

---

### Task 1: Extract `resolvePageSource` (single source of truth for page → file/source-page)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt:474-477`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewerTest.kt`

Today `UnifiedReferenceViewer` inline-computes which file/page to render for the single visible page (lines 474-477). `ContinuousReferencePdfPane` needs to do this same resolution once per visible page, so it must be a standalone function, not composable-local code.

- [ ] **Step 1: Write the failing test**

Add to `UnifiedReferenceViewerTest.kt`:

```kotlin
    @Test
    fun resolvePageSource_usesVirtualMappingWhenPresent() {
        val mapping = UnifiedVirtualPageMapping(
            totalDisplayPages = 2,
            defaultPdfFilename = "fallback.pdf",
            sourceByDisplayPage = mapOf(
                1 to UnifiedVirtualPageSource(pdfFilename = "assembly.pdf", page = 5)
            )
        )

        val resolved = resolvePageSource(displayPage = 1, virtualMapping = mapping, defaultPdfFilename = "fallback.pdf")

        assertEquals("assembly.pdf", resolved.pdfFilename)
        assertEquals(5, resolved.sourcePage)
    }

    @Test
    fun resolvePageSource_fallsBackToDefaultFilenameAndDisplayPageWhenNoMapping() {
        val resolved = resolvePageSource(displayPage = 3, virtualMapping = null, defaultPdfFilename = "plans.pdf")

        assertEquals("plans.pdf", resolved.pdfFilename)
        assertEquals(3, resolved.sourcePage)
    }

    @Test
    fun resolvePageSource_fallsBackWhenDisplayPageMissingFromMapping() {
        val mapping = UnifiedVirtualPageMapping(
            totalDisplayPages = 1,
            defaultPdfFilename = "fallback.pdf",
            sourceByDisplayPage = emptyMap()
        )

        val resolved = resolvePageSource(displayPage = 1, virtualMapping = mapping, defaultPdfFilename = "fallback.pdf")

        assertEquals("fallback.pdf", resolved.pdfFilename)
        assertEquals(1, resolved.sourcePage)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewerTest"`
Expected: FAIL — `resolvePageSource` / `ResolvedPageSource` unresolved reference.

- [ ] **Step 3: Add the function**

In `UnifiedReferenceViewer.kt`, add near the other top-level internal functions (after `buildVirtualReverseIndex`, before `buildPageToCabinets`, i.e. after line 136):

```kotlin
internal data class ResolvedPageSource(
    val pdfFilename: String,
    val sourcePage: Int
)

internal fun resolvePageSource(
    displayPage: Int,
    virtualMapping: UnifiedVirtualPageMapping?,
    defaultPdfFilename: String
): ResolvedPageSource {
    val source = virtualMapping?.sourceByDisplayPage?.get(displayPage)
    val filename = source?.pdfFilename?.takeIf { it.isNotBlank() } ?: defaultPdfFilename
    val sourcePage = source?.page?.takeIf { it > 0 } ?: displayPage
    return ResolvedPageSource(pdfFilename = filename, sourcePage = sourcePage)
}
```

Then replace the inline computation at lines 474-477 (inside the `UnifiedReferenceViewer` composable) — currently:

```kotlin
    val activeVirtualSource = virtualMapping?.sourceByDisplayPage?.get(clampedDisplayPage)
    val resolvedPdfFilename = activeVirtualSource?.pdfFilename?.takeIf { it.isNotBlank() }
        ?: defaultPdfFilename
    val sourcePage = activeVirtualSource?.page?.takeIf { it > 0 } ?: clampedDisplayPage
```

with:

```kotlin
    val resolvedSource = resolvePageSource(clampedDisplayPage, virtualMapping, defaultPdfFilename)
    val resolvedPdfFilename = resolvedSource.pdfFilename
    val sourcePage = resolvedSource.sourcePage
```

(`activeVirtualSource` was also used later at line ~688 to read `source?.cabinet` for the TOC row models — leave that usage as `virtualMapping?.sourceByDisplayPage?.get(page)`, it iterates over every page so it isn't the same single-page value; only the two lines above are replaced.)

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewerTest"`
Expected: PASS, all tests green (including pre-existing ones — confirms the inline replacement didn't change behavior).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt app/src/test/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewerTest.kt
git commit -m "refactor: extract resolvePageSource for reuse by continuous scroll pane"
```

---

### Task 2: Extract `buildNavigatorRowModels` and widen `NavigatorRowModel` visibility

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt:91-100, 678-719`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewerTest.kt`

The Sheet Navigator modal already computes, per page, a primary label ("Cabinet 12", "ROOM - PLAN VIEW", or the existing "Page N"/"Sheet N" fallback from `defaultNavigatorPrimaryLabel`) inline inside a `remember` block (lines 678-719). `PdfLabelScrollbar` needs exactly this same page→label mapping. Extract it to a pure function and reuse it in both places — this also means the Cover Sheet's "plain page number" behavior falls out for free (no cabinets, no virtual source ⇒ `defaultNavigatorPrimaryLabel` already returns `"Page $page"`).

- [ ] **Step 1: Write the failing test**

Add to `UnifiedReferenceViewerTest.kt`:

```kotlin
    @Test
    fun buildNavigatorRowModels_fallsBackToPlainPageNumberWithNoCabinetsOrMapping() {
        val rows = buildNavigatorRowModels(
            totalPages = 3,
            virtualMapping = null,
            navigatorCabinetToPages = emptyMap(),
            navigatorPlanViewLabels = emptyMap()
        )

        assertEquals(listOf(1, 2, 3), rows.map { it.page })
        assertEquals(listOf("Page 1", "Page 2", "Page 3"), rows.map { it.primaryLabel })
    }

    @Test
    fun buildNavigatorRowModels_usesCabinetLabelsWhenPresent() {
        val rows = buildNavigatorRowModels(
            totalPages = 2,
            virtualMapping = null,
            navigatorCabinetToPages = mapOf("12" to listOf(1)),
            navigatorPlanViewLabels = emptyMap()
        )

        assertEquals("Cabinet 12", rows.first { it.page == 1 }.primaryLabel)
        assertEquals("Page 2", rows.first { it.page == 2 }.primaryLabel)
    }

    @Test
    fun buildNavigatorRowModels_usesPlanViewLabelForPlanPages() {
        val rows = buildNavigatorRowModels(
            totalPages = 2,
            virtualMapping = null,
            navigatorCabinetToPages = emptyMap(),
            navigatorPlanViewLabels = mapOf(1 to "KITCHEN - PLAN VIEW")
        )

        assertEquals("KITCHEN - PLAN VIEW", rows.first { it.page == 1 }.primaryLabel)
        assertTrue(rows.first { it.page == 1 }.isPlanView)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewerTest"`
Expected: FAIL — `buildNavigatorRowModels` unresolved reference, or `NavigatorRowModel`/`primaryLabel`/`isPlanView` not accessible (private).

- [ ] **Step 3: Widen visibility and extract the function**

Change line 91 from:

```kotlin
private data class NavigatorRowModel(
```

to:

```kotlin
internal data class NavigatorRowModel(
```

Add this function directly after the `NavigatorRowModel` data class (after line 100):

```kotlin
internal fun buildNavigatorRowModels(
    totalPages: Int,
    virtualMapping: UnifiedVirtualPageMapping?,
    navigatorCabinetToPages: Map<String, List<Int>>,
    navigatorPlanViewLabels: Map<Int, String>,
    navigatorPrimaryLabel: (page: Int, cabinets: List<String>, source: UnifiedVirtualPageSource?) -> String =
        ::defaultNavigatorPrimaryLabel,
    navigatorSecondaryLabel: (page: Int, cabinets: List<String>, source: UnifiedVirtualPageSource?) -> String =
        ::defaultNavigatorSecondaryLabel
): List<NavigatorRowModel> {
    if (totalPages <= 0) return emptyList()
    val pages = (1..totalPages).toList()
    val pageToCabinets = buildPageToCabinets(navigatorCabinetToPages)
    val pageToRoomKey = buildPageToRoomKey(totalPages = totalPages, navigatorPlanViewLabels = navigatorPlanViewLabels)
    return pages.map { page ->
        val source = virtualMapping?.sourceByDisplayPage?.get(page)
        val cabinets = buildList {
            val sourceCabinet = source?.cabinet?.trim().orEmpty()
            if (sourceCabinet.isNotBlank()) add(sourceCabinet)
            pageToCabinets[page].orEmpty().forEach { cabinet ->
                if (cabinet !in this) add(cabinet)
            }
        }
        val planViewLabel = navigatorPlanViewLabels[page]?.trim().orEmpty()
        val isPlanView = planViewLabel.isNotBlank()
        val primaryLabel = if (isPlanView) planViewLabel else navigatorPrimaryLabel(page, cabinets, source)
        val secondaryLabel = if (isPlanView) "Sheet $page • Plan View" else navigatorSecondaryLabel(page, cabinets, source)
        NavigatorRowModel(
            page = page,
            cabinets = cabinets,
            roomKey = pageToRoomKey[page],
            source = source,
            isPlanView = isPlanView,
            matchedCabinets = emptyList(),
            primaryLabel = primaryLabel,
            secondaryLabel = secondaryLabel
        )
    }
}
```

Now replace the inline `rowModels` computation inside the `UnifiedReferenceViewer` composable (lines 678-719) — currently a `remember(...) { pages.map { ... } }` block building the same shape — with:

```kotlin
    val rowModels = remember(
        effectiveTotalPages,
        virtualMapping,
        navigatorCabinetToPages,
        navigatorPlanViewLabels,
        navigatorPrimaryLabel,
        navigatorSecondaryLabel
    ) {
        buildNavigatorRowModels(
            totalPages = effectiveTotalPages,
            virtualMapping = virtualMapping,
            navigatorCabinetToPages = navigatorCabinetToPages,
            navigatorPlanViewLabels = navigatorPlanViewLabels,
            navigatorPrimaryLabel = navigatorPrimaryLabel,
            navigatorSecondaryLabel = navigatorSecondaryLabel
        )
    }
```

Delete the now-unused local `val pages = remember(effectiveTotalPages) { ... }` block above it (lines 675-677) if nothing else in the file references `pages` — check with a search for `pages` in the file before deleting; `pageToCabinets`/`pageToRoomKey` locals immediately below the deleted rowModels block (lines 518-524, defined earlier in the composable) become unused too since `buildNavigatorRowModels` now computes its own — delete those two `remember` blocks as well once confirmed unused elsewhere in the composable.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewerTest"`
Expected: PASS, all tests green.

- [ ] **Step 5: Compile-check the Compose file**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL (catches any leftover reference to the deleted `pages`/`pageToCabinets`/`pageToRoomKey` locals).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt app/src/test/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewerTest.kt
git commit -m "refactor: extract buildNavigatorRowModels for reuse by the label scrollbar"
```

---

### Task 3: `computeRenderWindow` pure function

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt`

Determines which pages should have bitmaps rendered given the currently visible range — the visible pages plus a 1-page buffer on each side, clamped to the document.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuousReferencePdfPaneTest {

    @Test
    fun computeRenderWindow_addsOnePageBufferOnEachSide() {
        val window = computeRenderWindow(firstVisiblePage = 5, lastVisiblePage = 6, totalPages = 20, buffer = 1)

        assertEquals(4..7, window)
    }

    @Test
    fun computeRenderWindow_clampsToDocumentBounds() {
        val start = computeRenderWindow(firstVisiblePage = 1, lastVisiblePage = 1, totalPages = 20, buffer = 1)
        val end = computeRenderWindow(firstVisiblePage = 20, lastVisiblePage = 20, totalPages = 20, buffer = 1)

        assertEquals(1..2, start)
        assertEquals(19..20, end)
    }

    @Test
    fun computeRenderWindow_returnsEmptyForEmptyDocument() {
        val window = computeRenderWindow(firstVisiblePage = 1, lastVisiblePage = 1, totalPages = 0, buffer = 1)

        assertEquals(IntRange.EMPTY, window)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"`
Expected: FAIL — file/function doesn't exist yet.

- [ ] **Step 3: Create the file with the function**

```kotlin
package com.kkc.sheettracker.ui.components

internal fun computeRenderWindow(
    firstVisiblePage: Int,
    lastVisiblePage: Int,
    totalPages: Int,
    buffer: Int = 1
): IntRange {
    if (totalPages <= 0) return IntRange.EMPTY
    val start = (firstVisiblePage - buffer).coerceIn(1, totalPages)
    val end = (lastVisiblePage + buffer).coerceIn(1, totalPages)
    return start..end
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt
git commit -m "feat: add computeRenderWindow for continuous-scroll render buffering"
```

---

### Task 4: LRU eviction helpers + `PdfEngineCache`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt`

Generic, framework-free LRU order tracking (testable), plus a thin `PdfEngineCache` wrapper that performs the actual `PdfRenderEngine` open/close side effects (not unit-tested directly — this codebase's convention, per `PdfRenderEngine` itself, is to unit-test only pure logic and rely on manual/on-device verification for Android-framework-dependent code).

- [ ] **Step 1: Write the failing test**

Add to `ContinuousReferencePdfPaneTest.kt`:

```kotlin
    @Test
    fun lruTouch_movesExistingKeyToTheEnd() {
        val order = lruTouch(listOf("a", "b", "c"), "a")

        assertEquals(listOf("b", "c", "a"), order)
    }

    @Test
    fun lruTouch_appendsNewKeyToTheEnd() {
        val order = lruTouch(listOf("a", "b"), "c")

        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test
    fun lruEvictionCandidates_returnsOldestEntriesBeyondCap() {
        val evicted = lruEvictionCandidates(listOf("a", "b", "c", "d"), maxOpen = 2)

        assertEquals(listOf("a", "b"), evicted)
    }

    @Test
    fun lruEvictionCandidates_returnsEmptyWhenUnderCap() {
        val evicted = lruEvictionCandidates(listOf("a", "b"), maxOpen = 3)

        assertEquals(emptyList<String>(), evicted)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"`
Expected: FAIL — `lruTouch`/`lruEvictionCandidates` unresolved.

- [ ] **Step 3: Add the pure functions and the cache wrapper**

Add to `ContinuousReferencePdfPane.kt`, below `computeRenderWindow`:

```kotlin
internal fun <T> lruTouch(order: List<T>, key: T): List<T> =
    order.filterNot { it == key } + key

internal fun <T> lruEvictionCandidates(order: List<T>, maxOpen: Int): List<T> =
    if (order.size <= maxOpen) emptyList() else order.take(order.size - maxOpen)

/**
 * Keeps at most [maxOpen] [PdfRenderEngine]s open at once, keyed by absolute file path.
 * Continuous-mode pages usually share one file; this only matters for Assembly's
 * FF/FL virtual-mapping case where a handful of pages point at a different file.
 */
internal class PdfEngineCache(
    private val maxOpen: Int = 3
) {
    private val engines = linkedMapOf<String, PdfRenderEngine>()
    private var order = listOf<String>()

    fun get(file: java.io.File): PdfRenderEngine {
        val key = file.absolutePath
        order = lruTouch(order, key)
        engines[key]?.let { return it }
        val engine = PdfRenderEngine(file)
        engines[key] = engine
        return engine
    }

    suspend fun trim() {
        val evicted = lruEvictionCandidates(order, maxOpen)
        evicted.forEach { key ->
            engines.remove(key)?.close()
        }
        order = order.filterNot { it in evicted }
    }

    suspend fun closeAll() {
        engines.values.forEach { it.close() }
        engines.clear()
        order = emptyList()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt
git commit -m "feat: add PdfEngineCache for multi-file continuous-scroll page resolution"
```

---

### Task 5: `segmentIndexForOffsetFraction` pure function

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbarTest.kt`

Maps a drag/tap fraction (0f at top/left, 1f at bottom/right of the scrollbar track) to a row index in the label list.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfLabelScrollbarTest {

    @Test
    fun segmentIndexForOffsetFraction_mapsZeroToFirstIndex() {
        assertEquals(0, segmentIndexForOffsetFraction(segmentCount = 10, fraction = 0f))
    }

    @Test
    fun segmentIndexForOffsetFraction_mapsOneToLastIndex() {
        assertEquals(9, segmentIndexForOffsetFraction(segmentCount = 10, fraction = 1f))
    }

    @Test
    fun segmentIndexForOffsetFraction_clampsOutOfRangeFractions() {
        assertEquals(0, segmentIndexForOffsetFraction(segmentCount = 10, fraction = -0.5f))
        assertEquals(9, segmentIndexForOffsetFraction(segmentCount = 10, fraction = 1.5f))
    }

    @Test
    fun segmentIndexForOffsetFraction_returnsZeroForEmptyList() {
        assertEquals(0, segmentIndexForOffsetFraction(segmentCount = 0, fraction = 0.5f))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.PdfLabelScrollbarTest"`
Expected: FAIL — file/function doesn't exist yet.

- [ ] **Step 3: Create the file with the function**

```kotlin
package com.kkc.sheettracker.ui.components

internal fun segmentIndexForOffsetFraction(segmentCount: Int, fraction: Float): Int {
    if (segmentCount <= 0) return 0
    val clamped = fraction.coerceIn(0f, 1f)
    return (clamped * segmentCount).toInt().coerceIn(0, segmentCount - 1)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.PdfLabelScrollbarTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt app/src/test/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbarTest.kt
git commit -m "feat: add segmentIndexForOffsetFraction for the label scrollbar"
```

---

### Task 6: Build the `ContinuousReferencePdfPane` composable

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt`

This is Compose UI code with no existing Compose-UI test harness in this codebase (all prior viewer tests target extracted pure functions only) — verification for this task is the manual on-device checklist in Task 14, not an automated test.

- [ ] **Step 1: Add the composable**

Append to `ContinuousReferencePdfPane.kt`:

```kotlin
@Composable
fun ContinuousReferencePdfPane(
    modifier: Modifier = Modifier,
    orientation: androidx.compose.foundation.gestures.Orientation,
    totalPages: Int,
    resolvePage: (Int) -> ResolvedPageSource,
    pdfFileForFilename: (String) -> java.io.File?,
    fileIdentitySeed: Long = 0L,
    preferDarkMode: Boolean = false,
    onCenteredPageChange: (Int) -> Unit,
    scrollToPage: Int,
    markupEnabled: Boolean = false,
    markupToolState: PdfMarkupToolState? = null,
    markupStrokesForPage: (sourceFilename: String, sourcePage: Int) -> List<PdfInkStroke> = { _, _ -> emptyList() },
    onMarkupStrokeAdded: ((sourceFilename: String, sourcePage: Int, PdfInkStroke) -> Unit)? = null,
    onMarkupStrokeErased: ((sourceFilename: String, sourcePage: Int, strokeId: String) -> Unit)? = null
) {
    val engineCache = remember(fileIdentitySeed) { PdfEngineCache(maxOpen = 3) }
    DisposableEffect(engineCache) {
        onDispose { pdfRenderEngineDisposalScope.launch { withContext(NonCancellable) { engineCache.closeAll() } } }
    }

    val listState = if (orientation == androidx.compose.foundation.gestures.Orientation.Vertical) {
        rememberLazyListState()
    } else {
        rememberLazyListState()
    }

    LaunchedEffect(scrollToPage, totalPages) {
        if (totalPages > 0 && scrollToPage in 1..totalPages) {
            listState.scrollToItem((scrollToPage - 1).coerceIn(0, totalPages - 1))
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) null else visible.first().index + 1
        }
            .distinctUntilChanged()
            .collectLatest { centeredPage -> if (centeredPage != null) onCenteredPageChange(centeredPage) }
    }

    val renderWindow by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) {
                IntRange.EMPTY
            } else {
                computeRenderWindow(
                    firstVisiblePage = visible.first().index + 1,
                    lastVisiblePage = visible.last().index + 1,
                    totalPages = totalPages,
                    buffer = 1
                )
            }
        }
    }

    val isSettled by remember {
        derivedStateOf { !listState.isScrollInProgress }
    }

    val pageContent: @Composable (Int) -> Unit = { displayPage ->
        val resolved = remember(displayPage, fileIdentitySeed) { resolvePage(displayPage) }
        val file = remember(resolved.pdfFilename, fileIdentitySeed) { pdfFileForFilename(resolved.pdfFilename) }
        val inWindow = displayPage in renderWindow
        var bitmap by remember(displayPage, resolved) { mutableStateOf<Bitmap?>(null) }
        var aspectRatio by remember(displayPage, resolved) { mutableStateOf<Float?>(null) }
        val matteColorArgb = if (preferDarkMode) MaterialTheme.colorScheme.surface.toArgb() else android.graphics.Color.WHITE

        LaunchedEffect(displayPage, resolved, file) {
            if (file == null) return@LaunchedEffect
            aspectRatio = withContext(Dispatchers.IO) {
                engineCache.get(file).pageAspectRatio((resolved.sourcePage - 1).coerceAtLeast(0))
            }
        }

        LaunchedEffect(displayPage, resolved, file, inWindow, isSettled, matteColorArgb) {
            if (file == null || !inWindow || !isSettled) return@LaunchedEffect
            if (bitmap != null) return@LaunchedEffect
            // Settle-only: waits for scroll to stop before spending render work, same
            // debounce intent as ReferencePdfPane's zoom-detail-tile flow.
            delay(120)
            if (!inWindow || listState.isScrollInProgress) return@LaunchedEffect
            val viewSize = androidx.compose.ui.unit.IntSize(1080, 1400)
            bitmap = withContext(Dispatchers.IO) {
                engineCache.get(file).renderBasePage(
                    pageIndex = (resolved.sourcePage - 1).coerceAtLeast(0),
                    viewSize = viewSize,
                    matteColorArgb = matteColorArgb
                )
            }
            engineCache.trim()
        }

        val strokes = if (markupEnabled) markupStrokesForPage(resolved.pdfFilename, resolved.sourcePage) else emptyList()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio ?: (8.5f / 11f))
        ) {
            val currentBitmap = bitmap
            if (currentBitmap != null) {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
                    contentDescription = "Page $displayPage",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            }
            if (markupToolState != null && onMarkupStrokeAdded != null && onMarkupStrokeErased != null &&
                (markupEnabled || strokes.isNotEmpty())
            ) {
                PdfMarkupOverlay(
                    modifier = Modifier.fillMaxSize(),
                    viewportState = PdfViewportState(zoom = 1f, panX = 0f, panY = 0f, viewSize = androidx.compose.ui.unit.IntSize.Zero),
                    pageAspectRatio = aspectRatio,
                    activeStrokes = strokes,
                    inputEnabled = markupEnabled,
                    activeTool = markupToolState.activeTool,
                    activeColor = markupToolState.activeColor,
                    activeThickness = markupToolState.activeThickness,
                    allowFingerDrawing = markupToolState.allowFingerDrawing,
                    onStylusButtonEraserChanged = { markupToolState.isStylusButtonEraserActive = it },
                    onStrokeAdded = { stroke -> onMarkupStrokeAdded(resolved.pdfFilename, resolved.sourcePage, stroke) },
                    onStrokeErased = { strokeId -> onMarkupStrokeErased(resolved.pdfFilename, resolved.sourcePage, strokeId) }
                )
            }
        }
    }

    // Markup drawing pins the list to the current page — the pen and the list scroll
    // gesture must not fight each other, same rule ReferencePdfPane applies today.
    val userScrollEnabled = !markupEnabled

    if (orientation == androidx.compose.foundation.gestures.Orientation.Vertical) {
        LazyColumn(
            modifier = modifier,
            state = listState,
            userScrollEnabled = userScrollEnabled,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(count = totalPages, key = { it + 1 }) { index -> pageContent(index + 1) }
        }
    } else {
        LazyRow(
            modifier = modifier,
            state = listState,
            userScrollEnabled = userScrollEnabled,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(count = totalPages, key = { it + 1 }) { index ->
                Box(Modifier.fillParentMaxWidth()) { pageContent(index + 1) }
            }
        }
    }
}
```

- [ ] **Step 2: Add the imports this composable needs**

At the top of `ContinuousReferencePdfPane.kt`, add:

```kotlin
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.PdfInkStroke
import com.kkc.sheettracker.ui.markup.PdfMarkupOverlay
import com.kkc.sheettracker.ui.markup.PdfMarkupToolState
import com.kkc.sheettracker.ui.viewer.ResolvedPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
```

(`PdfRenderEngine`, `PdfViewportState`, and `pdfRenderEngineDisposalScope` already live in this package — `ReferencePdfPane.kt` — so no import is needed for those; same-package visibility applies. `fillParentMaxWidth` requires the `LazyItemScope` receiver already provided inside `items { ... }`.)

- [ ] **Step 3: Compile-check**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Fix any unresolved-reference errors (most likely candidates: an import path that doesn't match this codebase's actual package for `PdfInkStroke`/`PdfMarkupToolState`/`PdfMarkupOverlay` — re-check against the `import` lines already present at the top of `ReferencePdfPane.kt:79-81` if so).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt
git commit -m "feat: add ContinuousReferencePdfPane composable"
```

---

### Task 7: Build the `PdfLabelScrollbar` composable

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt`

Right-edge, full-pane-height scrollbar. Tap or drag jumps the list; a floating label callout tracks the finger during drag.

- [ ] **Step 1: Add the composable**

Append to `PdfLabelScrollbar.kt`:

```kotlin
@Composable
fun PdfLabelScrollbar(
    modifier: Modifier = Modifier,
    rows: List<com.kkc.sheettracker.ui.viewer.NavigatorRowModel>,
    currentPage: Int,
    onPageSelected: (Int) -> Unit
) {
    if (rows.isEmpty()) return
    var trackHeightPx by remember { mutableStateOf(0f) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
            .pointerInput(rows.size) {
                detectTapGestures { offset ->
                    if (trackHeightPx <= 0f) return@detectTapGestures
                    val fraction = (offset.y / trackHeightPx).coerceIn(0f, 1f)
                    val index = segmentIndexForOffsetFraction(rows.size, fraction)
                    onPageSelected(rows[index].page)
                }
            }
            .pointerInput(rows.size) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        if (trackHeightPx > 0f) dragFraction = (offset.y / trackHeightPx).coerceIn(0f, 1f)
                    },
                    onVerticalDrag = { change, _ ->
                        if (trackHeightPx <= 0f) return@detectVerticalDragGestures
                        val fraction = (change.position.y / trackHeightPx).coerceIn(0f, 1f)
                        dragFraction = fraction
                        val index = segmentIndexForOffsetFraction(rows.size, fraction)
                        onPageSelected(rows[index].page)
                    },
                    onDragEnd = { dragFraction = null },
                    onDragCancel = { dragFraction = null }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(4.dp)
                .align(Alignment.CenterEnd)
                .background(MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(2.dp))
        )

        val activeFraction = dragFraction
        if (activeFraction != null) {
            val index = segmentIndexForOffsetFraction(rows.size, activeFraction)
            val label = rows[index].primaryLabel
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-32).dp, y = (activeFraction * trackHeightPx / 2).dp - 16.dp)
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
```

- [ ] **Step 2: Add the imports this composable needs**

At the top of `PdfLabelScrollbar.kt`, add:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
```

- [ ] **Step 3: Compile-check**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt
git commit -m "feat: add PdfLabelScrollbar composable"
```

---

### Task 8: Wire `UnifiedReferenceViewer` to branch between the two panes

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt:420-460 (signature), 605-669 (ReferencePdfPane call)`

- [ ] **Step 1: Add new parameters to the `UnifiedReferenceViewer` signature**

In the parameter list (after `markupControlsAsSlidingTab: Boolean = false` at line 459), add:

```kotlin
    continuousScrollEnabled: Boolean = false,
    isSplitPaneActive: Boolean = false
```

- [ ] **Step 2: Branch pane selection**

Replace the single `ReferencePdfPane(...)` call (lines 605-669) with a conditional: keep the existing call under `if (!continuousScrollEnabled) { ... }`, and add the new branch alongside it, wrapped together with the scrollbar in a `Box`:

```kotlin
    Box(modifier = modifier) {
        if (!continuousScrollEnabled) {
            ReferencePdfPane(
                modifier = Modifier.fillMaxSize(),
                pdfFile = pdfFile,
                currentPage = sourcePage,
                onCurrentPageChange = { nextSourcePage ->
                    if (virtualMapping != null) {
                        val mapped = resolveDisplayPageFromSource(
                            reverseIndex = reverseIndex,
                            sourceFilename = resolvedPdfFilename,
                            sourcePage = nextSourcePage
                        )
                        if (mapped != null && mapped != clampedDisplayPage) {
                            onDisplayPageChange(mapped)
                        }
                    } else {
                        onDisplayPageChange(nextSourcePage)
                    }
                },
                showDocControls = showDocControls,
                missingText = missingText,
                unreadableText = unreadableText,
                onTotalPagesChanged = { totalPages ->
                    sourceTotalPages = totalPages
                    if (virtualMapping != null) {
                        onTotalPagesChanged(virtualMapping.totalDisplayPages)
                    } else {
                        onTotalPagesChanged(totalPages)
                    }
                },
                onViewportStateChange = onViewportStateChange,
                showHeaderRow = showHeaderRow,
                showNavigationButtons = showNavigationButtons,
                innerPadding = innerPadding,
                tocRequestToken = tocRequestToken,
                displayPageOverride = clampedDisplayPage,
                displayTotalPagesOverride = effectiveTotalPages,
                onStepPage = {
                    onDisplayPageChange(
                        (clampedDisplayPage + it).coerceIn(1, effectiveTotalPages.coerceAtLeast(1))
                    )
                },
                onOpenSheetNavigator = { showSheetNavigator = true },
                onSingleTap = onSingleTap,
                compactArrows = compactArrows,
                preferDarkMode = preferDarkMode,
                contentPadding = contentPadding,
                markupEnabled = markupEnabled,
                onToggleMarkupEnabled = onToggleMarkupEnabled,
                markupToolState = markupToolState,
                markupStrokes = if (markupStrokesVisible) visibleMarkupStrokes else emptyList(),
                onMarkupStrokeAdded = { stroke ->
                    localMarkupStrokes.add(stroke)
                    persistMarkupState()
                },
                onMarkupStrokeErased = { strokeId ->
                    if (strokeId !in localDeletedIds) {
                        localDeletedIds.add(strokeId)
                    }
                    persistMarkupState()
                }
            )
        } else {
            ContinuousReferencePdfPane(
                modifier = Modifier.fillMaxSize().padding(end = 28.dp),
                orientation = if (isSplitPaneActive) {
                    androidx.compose.foundation.gestures.Orientation.Horizontal
                } else {
                    androidx.compose.foundation.gestures.Orientation.Vertical
                },
                totalPages = effectiveTotalPages,
                resolvePage = { displayPage -> resolvePageSource(displayPage, virtualMapping, defaultPdfFilename) },
                pdfFileForFilename = pdfFileForFilename,
                fileIdentitySeed = fileIdentitySeed,
                preferDarkMode = preferDarkMode,
                onCenteredPageChange = onDisplayPageChange,
                scrollToPage = clampedDisplayPage,
                markupEnabled = markupEnabled,
                markupToolState = markupToolState,
                markupStrokesForPage = { _, _ -> if (markupStrokesVisible) visibleMarkupStrokes else emptyList() },
                onMarkupStrokeAdded = { _, _, stroke ->
                    localMarkupStrokes.add(stroke)
                    persistMarkupState()
                },
                onMarkupStrokeErased = { _, _, strokeId ->
                    if (strokeId !in localDeletedIds) {
                        localDeletedIds.add(strokeId)
                    }
                    persistMarkupState()
                }
            )
            PdfLabelScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd),
                rows = rowModels,
                currentPage = clampedDisplayPage,
                onPageSelected = onDisplayPageChange
            )
        }
    }
```

Note: `rowModels` (from Task 2) is declared later in the file (around line 678) than the `ReferencePdfPane`/branch call site (around line 605) — move the `rowModels` `remember` block (and the `pages` deletion from Task 2 Step 3) so it's declared *before* this `Box`, since it's now referenced here too. Everything below that previously depended on `rowModels` (the Sheet Navigator's `searchFilteredRows`) still works unchanged since it's the same value, just computed earlier.

Also add `import androidx.compose.ui.Alignment` and `import androidx.compose.foundation.layout.padding` to `UnifiedReferenceViewer.kt` if not already present (check the existing import block first — `Alignment` is likely already imported since the file uses `Alignment.CenterVertically` elsewhere in the TOC sheet).

- [ ] **Step 3: Compile-check**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full unit test suite for this package**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.viewer.*" --tests "com.kkc.sheettracker.ui.components.*"`
Expected: PASS, no regressions.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt
git commit -m "feat: branch UnifiedReferenceViewer between single-page and continuous-scroll panes"
```

---

### Task 9: Settings screen switch

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt:68-69, 256-260`

- [ ] **Step 1: Add parameters**

In the `SettingsScreen` function signature, immediately after line 69 (`onUseStandardSheetsChanged: (Boolean) -> Unit = {},`), add:

```kotlin
    continuousScrollDefault: Boolean = false,
    onContinuousScrollDefaultChanged: (Boolean) -> Unit = {},
```

- [ ] **Step 2: Add the switch UI**

In the `SettingsCard(title = "Appearance")` block, immediately after the "Use Standard Sheets" `Switch` block (after line 260, before the `HorizontalDivider()` at line 263), add:

```kotlin

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Continuous Scroll", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Scroll reference PDFs page-to-page instead of tapping through them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = continuousScrollDefault,
                        onCheckedChange = onContinuousScrollDefaultChanged
                    )
                }
```

- [ ] **Step 3: Compile-check**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: FAILS at this stage — `NavGraph.kt`'s `SettingsScreen(...)` call site doesn't pass the two new params yet, but since both have default values (`= false`, `= {}`), it should actually still compile. Confirm BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt
git commit -m "feat: add Continuous Scroll switch to Settings"
```

---

### Task 10: Thread `continuousScrollDefault` through `MainActivity.kt` and `NavGraph.kt`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt:278, 334, 351-354`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt` (signatures at lines 173/180, 397/404, 1784/1790, 1958/1965; call sites for `AppNavigation`, `SettingsScreen`, `MultiBackStackNavigation`, `LegacySingleStackNavigation`, `SettingsTabHost`)

This task mirrors the existing `useStandardSheets`/`onUseStandardSheetsChanged` plumbing exactly — same pref-backed state pattern, same function signatures, same call sites. Every location listed below currently has a `useStandardSheets: Boolean` (or `onUseStandardSheetsChanged: (Boolean) -> Unit`) line; add the new pair immediately after it, using the same value/callback wiring shown for `MainActivity.kt`.

- [ ] **Step 1: `MainActivity.kt` — add the pref-backed state**

At line 278, immediately after `var useStandardSheets by remember { mutableStateOf(prefs.getBoolean("use_standard_sheets", false)) }`, add:

```kotlin
            var continuousScrollDefault by remember { mutableStateOf(prefs.getBoolean("continuous_scroll_default", false)) }
```

At line 334, immediately after `useStandardSheets = useStandardSheets,` (inside the `AppNavigation(...)` call), add:

```kotlin
                        continuousScrollDefault = continuousScrollDefault,
```

At lines 351-354, immediately after the `onUseStandardSheetsChanged = { useStd -> ... }` block, add:

```kotlin
                        onContinuousScrollDefaultChanged = { enabled ->
                            continuousScrollDefault = enabled
                            prefs.edit().putBoolean("continuous_scroll_default", enabled).apply()
                        },
```

- [ ] **Step 2: `NavGraph.kt` — add to every function signature that currently declares `useStandardSheets: Boolean`**

Run this to find every location that needs the new pair:

```bash
grep -n "useStandardSheets: Boolean\|onUseStandardSheetsChanged: (Boolean)" app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
```

At each matching `useStandardSheets: Boolean,` line, add directly below it:

```kotlin
    continuousScrollDefault: Boolean,
```

At each matching `onUseStandardSheetsChanged: (Boolean) -> Unit,` line, add directly below it:

```kotlin
    onContinuousScrollDefaultChanged: (Boolean) -> Unit,
```

(Four functions — `AppNavigation`, `MultiBackStackNavigation`, `LegacySingleStackNavigation`, `SettingsTabHost` — each declare both; add both new lines to all four.)

- [ ] **Step 3: `NavGraph.kt` — add to every call site that currently passes `useStandardSheets = ...`**

Run this to find every call site:

```bash
grep -n "useStandardSheets = \|onUseStandardSheetsChanged = " app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
```

At each `useStandardSheets = useStandardSheets,` call-site line, add directly below it:

```kotlin
                continuousScrollDefault = continuousScrollDefault,
```

At each `onUseStandardSheetsChanged = onUseStandardSheetsChanged,` call-site line, add directly below it:

```kotlin
                onContinuousScrollDefaultChanged = onContinuousScrollDefaultChanged,
```

Match the indentation of the surrounding lines at each specific location (it varies by call depth — copy the indentation of the `useStandardSheets = ...` line immediately above).

- [ ] **Step 4: `NavGraph.kt` — thread into the `SettingsScreen(...)` call site**

Inside `SettingsTabHost`'s `NavHost` (around line 1816, the `SettingsScreen(` call), add after the `useStandardSheets = useStandardSheets,` line (this call site uses different param names on the caller side — verify by reading the surrounding block first):

```kotlin
                continuousScrollDefault = continuousScrollDefault,
                onContinuousScrollDefaultChanged = onContinuousScrollDefaultChanged,
```

- [ ] **Step 5: `NavGraph.kt` — thread into the four `ReferencePdfViewerScreen`/`AssemblyViewerScreen` call sites**

At each of the four call sites found in Task 12/13 below (`ReferencePdfViewerScreen` at lines ~1526 and ~2683; `AssemblyViewerScreen` at lines ~1681 and ~2833), add the two new arguments — these are covered as part of Task 12 and Task 13 since those tasks also change the receiving screens' own signatures at the same time. Do not add them here yet; this step is a placeholder marker only for Task 10's own scope (MainActivity + the four NavGraph.kt function signatures/SettingsScreen call site) — skip it now.

- [ ] **Step 6: Verify wiring count matches**

Run:

```bash
grep -c "continuousScrollDefault" app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
grep -c "useStandardSheets" app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
```

Expected: the `continuousScrollDefault` count is close to (may be slightly less than, since Task 10 doesn't touch the two viewer screens' call sites yet) the `useStandardSheets` count. Large gaps indicate a missed location — go back and re-check the grep output from Steps 2-3.

- [ ] **Step 7: Compile-check**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`ReferencePdfViewerScreen`/`AssemblyViewerScreen` calls that don't yet pass the two new args still compile because Task 12/13 will give those params defaults.)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/MainActivity.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: thread continuousScrollDefault pref through MainActivity and NavGraph"
```

---

### Task 11: Reference viewer models get a `continuousScrollDefault` param — sanity groundwork

Skipped as a standalone task — folded into Tasks 12 and 13, since `ReferencePdfViewerScreen` and `AssemblyViewerScreen` are the only consumers and each needs bespoke session-toggle-button wiring alongside the plumbing.

---

### Task 12: `ReferencePdfViewerScreen` — session toggle + wiring

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt` (the two `ReferencePdfViewerScreen(...)` call sites, ~line 1526 and ~line 2683)

- [ ] **Step 1: Add parameters and session-toggle state to `ReferencePdfViewerScreen`**

In the function signature (currently lines 46-55), add after `refreshGeneration: Long = 0L,`:

```kotlin
    continuousScrollDefault: Boolean = false,
```

Inside the function body, after the existing `var markupEnabled by rememberSaveable { mutableStateOf(false) }` (line 79), add:

```kotlin
    var continuousScrollEnabled by rememberSaveable(jobFolderName, docType) { mutableStateOf(continuousScrollDefault) }
```

- [ ] **Step 2: Pass the state into `UnifiedReferenceViewer` and add a toggle button**

In the `UnifiedReferenceViewer(...)` call (lines 125-156), add:

```kotlin
            continuousScrollEnabled = continuousScrollEnabled,
            isSplitPaneActive = false,
```

Add a toggle button to the `TopAppBar`'s `actions` (the `TopAppBar` currently has no `actions` block — add one, right after the `navigationIcon` block and before `windowInsets`):

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

Add the two new icon imports at the top of the file:

```kotlin
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.automirrored.filled.MenuBook
```

- [ ] **Step 3: Wire the two `NavGraph.kt` call sites**

At both `ReferencePdfViewerScreen(...)` call sites (line ~1526 and ~2683), add after `refreshGeneration = refreshGeneration,`:

```kotlin
                continuousScrollDefault = continuousScrollDefault,
```

- [ ] **Step 4: Compile-check**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: wire continuous scroll toggle into ReferencePdfViewerScreen"
```

---

### Task 13: `AssemblyViewerScreen` — session toggle, split-mode detection, wiring

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt:172 (signature), 1002-1035 (PdfPaneWithFloatingControls signature), 704, 808 (call sites), ~1071 (UnifiedReferenceViewer call)`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt` (the two `AssemblyViewerScreen(...)` call sites, ~line 1681 and ~line 2833)

- [ ] **Step 1: Add a parameter to `AssemblyViewerScreen`'s own signature**

At line 172, in the `AssemblyViewerScreen` function signature, add:

```kotlin
    continuousScrollDefault: Boolean = false,
```

Add session state near the other `rememberSaveable`/`remember` UI-mode state in the function body (alongside `sharedPdfMarkupEnabled` — search for that name to find the right spot):

```kotlin
    var continuousScrollEnabled by rememberSaveable(jobFolderName) { mutableStateOf(continuousScrollDefault) }
```

- [ ] **Step 2: Add parameters to `PdfPaneWithFloatingControls`**

In its signature (lines 1002-1035), add after `hasNavBarBelow: Boolean = true`:

```kotlin
    continuousScrollEnabled: Boolean = false,
    isSplitPaneActive: Boolean = false
```

In its body, in the `UnifiedReferenceViewer(...)` call (starting line 1071), add:

```kotlin
                continuousScrollEnabled = continuousScrollEnabled,
                isSplitPaneActive = isSplitPaneActive,
```

- [ ] **Step 3: Pass the new params at both `PdfPaneWithFloatingControls` call sites**

At the first call site (line 704, `firstContent = { paneModifier -> PdfPaneWithFloatingControls(...) }`), add — matching the existing `compactArrows = (fullscreenPane == FullscreenPane.NONE),` line's pattern exactly, since split-mode is the same condition:

```kotlin
                        continuousScrollEnabled = continuousScrollEnabled,
                        isSplitPaneActive = (fullscreenPane == FullscreenPane.NONE),
```

At the second call site (line 808, `secondContent = { paneModifier -> PdfPaneWithFloatingControls(...) }`), add the identical two lines.

- [ ] **Step 4: Add a session-toggle button**

Find the existing markup-pen toggle button in this file's top bar `actions` block (search for `sharedPdfMarkupEnabled = !sharedPdfMarkupEnabled` — that callback is already wired to a button in the `TopAppBar`'s `actions`). Add a sibling `IconButton` immediately next to it:

```kotlin
                        IconButton(onClick = { continuousScrollEnabled = !continuousScrollEnabled }) {
                            Icon(
                                if (continuousScrollEnabled) Icons.Default.ViewDay else Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = if (continuousScrollEnabled) "Switch to single page" else "Switch to continuous scroll",
                                tint = if (continuousScrollEnabled) Color.White else Color.White.copy(alpha = 0.85f)
                            )
                        }
```

(Match the existing markup-toggle button's exact tint/styling expression instead of the placeholder `Color.White` shown above — copy whatever that neighboring button already uses, since this file's `TopAppBar` uses a custom color scheme, not `MaterialTheme.colorScheme` directly, per the `ButtonDefaults.buttonColors(containerColor = Color(0xFF38A169), ...)` seen elsewhere in this same top bar.)

Add the two new icon imports at the top of the file:

```kotlin
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.automirrored.filled.MenuBook
```

- [ ] **Step 5: Wire the two `NavGraph.kt` call sites**

At both `AssemblyViewerScreen(...)` call sites (line ~1681 and ~2833), add after `refreshGeneration = refreshGeneration,`:

```kotlin
                        continuousScrollDefault = continuousScrollDefault,
```

- [ ] **Step 6: Compile-check**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run the full unit test suite**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: PASS. (One pre-existing failure is expected and unrelated — the `PdfMarkup MotionEvent test` documented as env-only in project memory; confirm no *new* failures appear.)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: wire continuous scroll toggle into AssemblyViewerScreen split panes"
```

---

### Task 14: Manual on-device verification

**Files:** none (verification only)

- [ ] **Step 1: Build and install the release APK**

Run:

```bash
.\adb-install-release.ps1
```

If this fails with a unicode/encoding error (known issue — see project memory `adb_release_script_encoding`), instead run:

```bash
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

- [ ] **Step 2: Settings default**

Open Settings → Appearance → toggle "Continuous Scroll" on. Confirm it persists across an app restart.

- [ ] **Step 3: Single-mode vertical scroll**

Open a job's Assembly Sheets viewer in fullscreen (single-pane) mode. Confirm pages stack vertically, scroll is smooth (no dropped frames on fling), and the right-edge scrollbar shows cabinet labels that match the Sheet Navigator's existing labels for the same job.

- [ ] **Step 4: Split-mode horizontal scroll**

Switch the Assembly Viewer to split mode (both panes visible). Confirm each pane scrolls horizontally, independently of the other pane.

- [ ] **Step 5: Cover Sheet plain page numbers**

Open a job's Cover Sheet (Delivery Sheets) viewer with continuous scroll on. Confirm the scrollbar shows "Page 1", "Page 2", etc. instead of cabinet labels.

- [ ] **Step 6: Zoom-per-page reset**

While in continuous scroll, pinch-zoom into one page's detail, then scroll to the next page and back. Confirm the zoomed page reset to fit and the zoom didn't leak to the next page.

- [ ] **Step 7: Markup lock**

Enable the markup pen while in continuous scroll. Confirm the list no longer scrolls with a finger swipe (drawing works instead), and disabling the pen restores scrolling.

- [ ] **Step 8: In-viewer session toggle**

With the global Settings default OFF, use the in-viewer toggle button to switch a single open viewer to continuous scroll. Back out and reopen the same doc — confirm it reverted to single-page (session-only override did not persist).

- [ ] **Step 9: Memory/battery sanity check**

Continuously scroll through a large Assembly Sheets document (30+ pages) top to bottom and back, over roughly 2 minutes. Watch `adb shell dumpsys meminfo com.kkc.sheettracker` before and after — confirm memory is not growing unbounded (should plateau, not climb linearly with pages scrolled past).

- [ ] **Step 10: Multi-file (FF/FL) job, if available**

If a job with FF/FL virtual page mapping is available, open its Assembly Sheets in continuous scroll and confirm pages from both underlying files render correctly at the boundary where the mapping switches files.

---

## Self-Review Notes

- **Spec coverage:** Scope (Task 8, 12, 13) — vertical/horizontal branching wired via `isSplitPaneActive`. Toggle & persistence (Tasks 9-13) — global Settings default + in-viewer session-only override, both implemented. Rendering strategy (Tasks 3, 4, 6) — virtualization via Lazy lists, settle-only debounce, small render window, single-flight via existing `PdfRenderEngine` mutex, per-page zoom reset via list-item disposal, markup-locks-scroll. Multi-file resolution (Tasks 1, 4) — `resolvePageSource` + `PdfEngineCache`. Segmented scrollbar (Tasks 2, 5, 7) — `buildNavigatorRowModels` reuse, Cover Sheet plain-page-number fallback via existing `defaultNavigatorPrimaryLabel` behavior, drag/tap with live label callout. Position tracking (Task 6, 8) — `onCenteredPageChange`/`scrollToPage` wired to the existing `currentPage` resume mechanism unchanged.
- **Placeholder scan:** One intentional exception flagged inline at Task 13 Step 4 — the exact tint expression for the new toggle button is deliberately left as "match the neighboring button's existing styling" rather than fabricated, because the real expression wasn't read during planning and guessing it risks a wrong compile; this is a direct instruction to copy real adjacent code, not an unspecified requirement.
- **Type consistency:** `ResolvedPageSource` (Task 1) is the type both `UnifiedReferenceViewer.resolvePageSource` and `ContinuousReferencePdfPane.resolvePage` produce/consume. `NavigatorRowModel` (Task 2) is the type both the Sheet Navigator and `PdfLabelScrollbar` consume via `rows: List<NavigatorRowModel>`. `continuousScrollEnabled`/`isSplitPaneActive` names are consistent from `UnifiedReferenceViewer` (Task 8) through `PdfPaneWithFloatingControls` (Task 13) and `ReferencePdfViewerScreen`/`AssemblyViewerScreen`'s own `continuousScrollDefault` seed value (Tasks 12-13) through to `MainActivity`/`NavGraph` (Task 10).
