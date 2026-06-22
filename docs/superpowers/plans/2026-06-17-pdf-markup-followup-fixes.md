# PDF Markup Follow-Up Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the remaining PDF and classic-cutlist markup bugs so drawing, erasing, undo, finger/stylus behavior, and persistence work consistently across assembly split view, hardwoods classic mode, and all PDF viewers.

**Architecture:** Keep one shared PDF rendering/input stack for all PDF screens, but separate static markup rendering from interactive pen input. Introduce explicit ownership/ordering metadata for PDF strokes so undo is tablet-scoped and deterministic, then add a lightweight reload signal so open viewers can refresh shared markup without navigation.

**Tech Stack:** Kotlin, Jetpack Compose, Android `MotionEvent` / pointer input, local JSON persistence via `Gson`, Gradle Android release build, `adb` manual verification.

---

## File Structure

- Modify: `app/src/main/java/com/kkc/sheettracker/data/models/Models.kt`
  - Add per-stroke ownership/ordering fields needed for deterministic undo and multi-tablet safety.
- Modify: `app/src/main/java/com/kkc/sheettracker/data/PdfMarkupStore.kt`
  - Normalize persistence behavior, expose a reload/invalidation signal, and keep merged reads stable.
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupSupport.kt`
  - Add pure helpers for erase hit-testing in transformed coordinates and any stroke ordering helpers.
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupUi.kt`
  - Fix erase hit-testing under zoom/pan and improve finger/stylus routing behavior.
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt`
  - Fix stale gesture routing by keying pointer handlers to pen/finger mode state.
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt`
  - Scope undo to current tablet strokes, consume store invalidation, and keep visibility/rendering behavior correct.
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`
  - Mirror the same shared-PDF fixes for the full-sheet PDF path.
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/ClassicCutListTable.kt`
  - Separate tool-state sharing from cutlist-specific stroke undo/clear so bottom-navbar controls can target the correct surface.
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`
  - Provide a hardwoods-specific bottom-navbar control model for classic cutlist + reference PDF together.
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt`
  - Verify the shared split-view pen state uses the fixed PDF toolbar semantics and doesn’t regress on non-PDF panes.
- Create: `app/src/test/java/com/kkc/sheettracker/ui/markup/PdfMarkupSupportTest.kt`
  - Unit tests for transformed erase hit-testing and stroke ordering helpers.
- Create: `app/src/test/java/com/kkc/sheettracker/data/PdfMarkupStoreTest.kt`
  - Unit tests for persistence merge behavior, tablet-scoped undo semantics, and legacy file compatibility.

### Task 1: Fix Shared PDF Input Routing And Erase Hit-Testing

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupSupport.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupUi.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/markup/PdfMarkupSupportTest.kt`

- [ ] **Step 1: Write the failing unit tests for transformed erase behavior**

```kotlin
package com.kkc.sheettracker.ui.markup

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfMarkupSupportTest {

    @Test
    fun eraseHitTest_respectsZoomAndPanTransform() {
        val transform = computePdfPageTransform(
            viewSize = IntSize(1000, 1400),
            pageAspectRatio = 0.75f,
            zoom = 2f,
            panX = 120f,
            panY = -80f
        )
        val stroke = listOf(0.2f, 0.3f, 0.5f, 0.3f)
        val midpoint = transform.normalizedPageToView(0.35f, 0.3f)

        val distance = distanceFromViewPointToStroke(
            viewX = midpoint.first,
            viewY = midpoint.second,
            strokePoints = stroke,
            transform = transform
        )

        assertTrue(distance < 5f)
    }

    @Test
    fun eraseHitTest_missesFarAwayPointAfterTransform() {
        val transform = computePdfPageTransform(
            viewSize = IntSize(1000, 1400),
            pageAspectRatio = 0.75f,
            zoom = 2f,
            panX = 120f,
            panY = -80f
        )
        val stroke = listOf(0.2f, 0.3f, 0.5f, 0.3f)

        val distance = distanceFromViewPointToStroke(
            viewX = 40f,
            viewY = 60f,
            strokePoints = stroke,
            transform = transform
        )

        assertTrue(distance > 30f)
    }
}
```

- [ ] **Step 2: Run the unit test to verify it fails with the current erase math**

Run: `./gradlew.bat :app:testReleaseUnitTest --tests "com.kkc.sheettracker.ui.markup.PdfMarkupSupportTest" --info`

Expected: FAIL because `distanceFromViewPointToStroke(...)` does not exist yet and the current erase path still uses untransformed coordinates.

- [ ] **Step 3: Add pure transform-aware erase helpers in `PdfMarkupSupport.kt`**

```kotlin
fun distanceFromViewPointToStroke(
    viewX: Float,
    viewY: Float,
    strokePoints: List<Float>,
    transform: PdfPageTransform
): Float {
    var minDistance = Float.MAX_VALUE
    for (i in 0 until strokePoints.size - 3 step 2) {
        val a = transform.normalizedPageToView(strokePoints[i], strokePoints[i + 1])
        val b = transform.normalizedPageToView(strokePoints[i + 2], strokePoints[i + 3])
        val distance = distanceToSegment(
            px = viewX,
            py = viewY,
            ax = a.first,
            ay = a.second,
            bx = b.first,
            by = b.second
        )
        if (distance < minDistance) minDistance = distance
    }
    return minDistance
}
```

- [ ] **Step 4: Update `PdfMarkupUi.kt` to use the transformed helper and allow two-finger navigation when finger draw is enabled**

```kotlin
fun eraseAt(viewX: Float, viewY: Float) {
    val toDelete = activeStrokes.firstOrNull { stroke ->
        distanceFromViewPointToStroke(
            viewX = viewX,
            viewY = viewY,
            strokePoints = stroke.points,
            transform = transform
        ) < 30f
    }
    if (toDelete != null) onStrokeErased(toDelete.id)
}
```

```kotlin
val canHandleInput = isStylusTool || allowFingerDrawing
val pointerCount = motionEvent.pointerCount
val shouldTreatFingerAsDraw = allowFingerDrawing && !isStylusTool && pointerCount == 1
```

Use `shouldTreatFingerAsDraw` so one-finger touch can draw while two-finger touch still falls through to the base PDF pan/zoom layer.

- [ ] **Step 5: Re-key PDF pan/zoom handlers to mode state so pen/finger toggles take effect immediately**

```kotlin
Modifier.pointerInput(pageKey, allowStylusGestures, allowFingerGestures) {
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        val isStylusGesture =
            firstDown.type == PointerType.Stylus || firstDown.type == PointerType.Eraser
        val shouldHandleGesture =
            if (isStylusGesture) allowStylusGestures else allowFingerGestures
        if (!shouldHandleGesture) {
            do {
                val blockedEvent = awaitPointerEvent()
            } while (blockedEvent.changes.any { it.pressed })
            return@awaitEachGesture
        }
        // existing pan/zoom body
    }
}
```

Mirror the same keying fix in `MarkupPdfPageView(...)` inside `SheetViewerScreen.kt`.

- [ ] **Step 6: Re-run the unit tests**

Run: `./gradlew.bat :app:testReleaseUnitTest --tests "com.kkc.sheettracker.ui.markup.PdfMarkupSupportTest" --info`

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupSupport.kt app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupUi.kt app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt app/src/test/java/com/kkc/sheettracker/ui/markup/PdfMarkupSupportTest.kt
git commit -m "fix: correct pdf erase hit testing and input routing"
```

### Task 2: Make PDF Undo Tablet-Scoped, Ordered, And Live-Reloadable

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/models/Models.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/PdfMarkupStore.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/PdfMarkupStoreTest.kt`

- [ ] **Step 1: Write failing unit tests for tablet-scoped undo ordering and legacy-file compatibility**

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.PdfInkStroke
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfMarkupStoreTest {

    @Test
    fun mergedActiveStrokes_preservesOtherTabletStrokes() {
        // create two stores with different tablet ids in same temp base dir
        // save one stroke each to same page
        // assert merged read returns both strokes
    }

    @Test
    fun tabletPageMarkup_returnsOnlyCurrentTabletStrokesForUndo() {
        // save two tablets, then assert current-tablet view contains only its own strokes
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew.bat :app:testReleaseUnitTest --tests "com.kkc.sheettracker.data.PdfMarkupStoreTest" --info`

Expected: FAIL because the current store has no tablet-scoped page read and no deterministic stroke ordering metadata.

- [ ] **Step 3: Extend `PdfInkStroke` with ownership and ordering metadata**

```kotlin
data class PdfInkStroke(
    val id: String = "",
    val color: Int = 0,
    val lineWidth: Float = 4.0f,
    val isHighlighter: Boolean = false,
    val points: List<Float> = emptyList(),
    val tabletId: String = "",
    val createdAtEpochMs: Long = 0L
)
```

Update all creation sites to populate:

```kotlin
PdfInkStroke(
    id = UUID.randomUUID().toString(),
    color = activeColor.toArgb(),
    lineWidth = activeThickness,
    isHighlighter = gestureTool == DrawingTool.HIGHLIGHTER,
    points = finalizedPoints,
    tabletId = currentTabletId,
    createdAtEpochMs = System.currentTimeMillis()
)
```

- [ ] **Step 4: Add explicit current-tablet page reads and a reload signal in `PdfMarkupStore.kt`**

```kotlin
private val _markupVersion = MutableStateFlow(0L)
val markupVersion: StateFlow<Long> = _markupVersion

fun loadCurrentTabletPageMarkup(
    jobFolderName: String,
    pdfFilename: String,
    page: Int
): PdfPageMarkup? {
    val normalizedFilename = normalizedPdfFilename(pdfFilename)
    return loadTabletMarkup(jobFolderName)
        .pages
        .firstOrNull { it.pdfFilename == normalizedFilename && it.page == page }
}
```

Increment `_markupVersion` after successful writes:

```kotlin
_markupVersion.value = System.currentTimeMillis()
```

- [ ] **Step 5: Change PDF undo to use only current-tablet strokes sorted by `createdAtEpochMs`**

```kotlin
val currentTabletPageMarkup = pdfMarkupStore?.loadCurrentTabletPageMarkup(
    jobFolderName = pdfMarkupJobFolderName,
    pdfFilename = resolvedPdfFilename,
    page = sourcePage
)

val currentTabletVisibleStrokes = currentTabletPageMarkup
    ?.strokes
    .orEmpty()
    .filter { it.id !in localDeletedIds }
    .sortedBy { it.createdAtEpochMs }
```

Use `currentTabletVisibleStrokes.lastOrNull()` for undo in `UnifiedReferenceViewer.kt`, and mirror the same logic in `SheetViewerScreen.kt`.

- [ ] **Step 6: Subscribe open viewers to `markupVersion` so other-tablet edits appear without page navigation**

```kotlin
val markupVersion by pdfMarkupStore?.markupVersion?.collectAsState(initial = 0L)
    ?: remember { mutableLongStateOf(0L) }

LaunchedEffect(
    pdfMarkupStore,
    pdfMarkupJobFolderName,
    resolvedPdfFilename,
    sourcePage,
    markupVersion
) {
    // existing reload body
}
```

Apply the equivalent change to the full-sheet PDF reload effect.

- [ ] **Step 7: Re-run the store tests**

Run: `./gradlew.bat :app:testReleaseUnitTest --tests "com.kkc.sheettracker.data.PdfMarkupStoreTest" --info`

Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/models/Models.kt app/src/main/java/com/kkc/sheettracker/data/PdfMarkupStore.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt app/src/test/java/com/kkc/sheettracker/data/PdfMarkupStoreTest.kt
git commit -m "fix: scope pdf undo to current tablet and live refresh markup"
```

### Task 3: Finish Hardwoods Classic Bottom-Navbar Integration

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/ClassicCutListTable.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupUi.kt`

- [ ] **Step 1: Add a hardwoods-specific bottom-navbar control model instead of reusing the PDF toolbar directly**

```kotlin
data class HardwoodsMarkupControls(
    val hasUndo: Boolean,
    val strokesVisible: Boolean,
    val onUndo: () -> Unit,
    val onToggleVisibility: () -> Unit,
    val selectedTool: DrawingTool,
    val activeColor: Color,
    val allowFingerDrawing: Boolean
)
```

Host it from `HardwoodsWorkspaceScreen.kt` and render it into `LocalNavBarDecoration.current.extendedControls`.

- [ ] **Step 2: Make cutlist-specific undo/visibility target cutlist strokes instead of PDF strokes**

```kotlin
val hasClassicMarkupHistory = activeStrokes.any { it.docType == selectedDoc.docType.name }
val visibleClassicPageStrokes = activeStrokes.filter {
    it.docType == selectedDoc.docType.name && it.page == currentClassicPage && it.id !in hiddenClassicStrokeIds
}
```

Use those values in navbar handlers instead of `PdfMarkupToolbar(...)`.

- [ ] **Step 3: Keep one shared tool-state but two independent stroke targets**

```kotlin
ClassicCutListTable(
    toolState = sharedMarkupToolState,
    showMarkupToolbar = false,
    // existing stroke save callbacks
)

ReferencePane(
    markupEnabled = isClassicView || referenceMarkupEnabled,
    markupToolState = sharedMarkupToolState,
    // existing PDF callbacks
)
```

The shared `toolState` is correct; only the action handlers need to be split by surface.

- [ ] **Step 4: Add manual verification notes inline for the implementing worker**

```text
Manual verify:
1. Open classic cutlist + reference PDF split.
2. Use bottom-navbar pen controls.
3. Draw on cutlist table, then on reference PDF.
4. Tap undo and confirm the intended surface stroke is removed.
5. Toggle visibility and confirm both surfaces behave according to the designed scope.
```

- [ ] **Step 5: Rebuild and smoke-test hardwoods manually**

Run: `./gradlew.bat :app:assembleRelease`

Expected: `BUILD SUCCESSFUL`

Run: `adb install -r "app/build/outputs/apk/release/app-release.apk"`

Expected: `Success`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/ClassicCutListTable.kt app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupUi.kt
git commit -m "fix: finish hardwoods classic navbar markup controls"
```

### Task 4: Verify Assembly Shared Pen Mode And Input Edge Cases

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupUi.kt`

- [ ] **Step 1: Guard shared assembly pen mode so non-PDF panes do not present misleading pen state**

```kotlin
val anyPdfPaneVisible = firstPaneSource.isPdfSource() || secondPaneSource.isPdfSource()
if (!anyPdfPaneVisible && sharedPdfMarkupEnabled) {
    sharedPdfMarkupEnabled = false
}
```

- [ ] **Step 2: Verify pane-level toggle semantics are symmetrical**

```kotlin
markupEnabled = paneSource.isPdfSource() && sharedPdfMarkupEnabled
onToggleMarkupEnabled = {
    if (paneSource.isPdfSource()) {
        sharedPdfMarkupEnabled = !sharedPdfMarkupEnabled
    }
}
```

Use the same body for both panes so toggling either button produces identical behavior.

- [ ] **Step 3: Add manual verification checklist covering palm rejection, stylus button eraser, and finger mode**

```text
Assembly manual QA:
1. Split view with two PDFs: toggle pen from left pane, draw on both panes.
2. Split view with one PDF + checklist/3D pane: toggle pen from PDF pane and confirm non-PDF pane is unaffected.
3. Finger Draw off: stylus draws, fingers pan/zoom, palm does not create marks.
4. Finger Draw on: one finger draws, two fingers still pan/zoom.
5. Stylus side button temporarily erases on both panes and reverts to previous tool on release.
```

- [ ] **Step 4: Rebuild and run the assembly manual smoke test**

Run: `./gradlew.bat :app:assembleRelease`

Expected: `BUILD SUCCESSFUL`

Run: `adb install -r "app/build/outputs/apk/release/app-release.apk"`

Expected: `Success`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupUi.kt
git commit -m "fix: harden assembly shared pen mode interactions"
```

### Task 5: Residual-Risk Sweep And End-To-End Verification

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/PdfMarkupStore.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt`

- [ ] **Step 1: Remove temporary debug logging after the persistence path is stable**

Delete lines using:

```kotlin
Log.d("PdfMarkupDebug", ...)
```

from:
- `PdfMarkupStore.kt`
- `UnifiedReferenceViewer.kt`
- `SheetViewerScreen.kt`

- [ ] **Step 2: Re-run focused unit tests**

Run: `./gradlew.bat :app:testReleaseUnitTest --tests "com.kkc.sheettracker.ui.markup.PdfMarkupSupportTest" --tests "com.kkc.sheettracker.data.PdfMarkupStoreTest" --info`

Expected: PASS

- [ ] **Step 3: Run release build and install**

Run: `./gradlew.bat clean :app:assembleRelease --no-build-cache`

Expected: `BUILD SUCCESSFUL`

Run: `adb install -r "app/build/outputs/apk/release/app-release.apk"`

Expected: `Success`

- [ ] **Step 4: Execute full manual regression pass**

```text
Manual regression matrix:
1. ReferencePdfViewerScreen: draw, hide pen mode, reopen job, verify markup still visible.
2. AssemblyViewerScreen fullscreen PDF: stylus draw, finger pan, side-button erase, reopen job.
3. Assembly split view with two PDFs: shared pen toggle, draw both panes, reopen job.
4. SheetViewer full PDF mode: draw, hide pen mode, confirm markup still visible and persists.
5. Hardwoods classic split: draw on cutlist and reference PDF, verify bottom navbar controls behave correctly.
6. Multi-tablet simulation: create stroke on tablet A, verify it appears on tablet B without page navigation after reload signal.
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/PdfMarkupStore.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt
git commit -m "test: complete markup regression sweep"
```

## Self-Review

- Spec coverage:
  - Shared PDF erase/pan/finger regressions: covered by Task 1.
  - Tablet-scoped undo and live reload across tablets: covered by Task 2.
  - Hardwoods classic bottom-navbar integration: covered by Task 3.
  - Assembly split-view shared pen behavior: covered by Task 4.
  - Residual risk from the audit and final end-to-end verification: covered by Task 5.
- Placeholder scan:
  - No `TODO`, `TBD`, or “appropriate handling” placeholders remain.
- Type consistency:
  - `tabletId` and `createdAtEpochMs` are used consistently as new `PdfInkStroke` fields.
  - `markupVersion` is the single reload signal name across store and viewers.
  - `distanceFromViewPointToStroke(...)` is the single transform-aware erase helper name across tests and implementation.
