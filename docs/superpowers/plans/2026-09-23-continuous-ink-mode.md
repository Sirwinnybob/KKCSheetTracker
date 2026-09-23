# Continuous PDF Viewer Ink Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the continuous-scroll PDF viewer, let the stylus draw on any visible page, show strokes on every page, and keep finger scroll/pinch/tap working while ink is on.

**Architecture:** A new `PdfMarkupPageStates` holder keyed by `PdfMarkupPageKey` replaces the single-page stroke lists in `UnifiedReferenceViewer`. The whole job's markup loads once (`getMergedActiveStrokesByPage`), and each add/erase persists only its own page. `ContinuousReferencePdfPane` keeps its scroll/zoom handler on during ink, ignores stylus pointers in it, and locks fingers out only when a finger owns ink (finger drawing on, or eraser tool).

**Tech Stack:** Kotlin, Jetpack Compose (snapshot state, `pointerInput`), JUnit4 unit tests, Gradle (`.\gradlew.bat`).

**Spec:** `docs/superpowers/specs/2026-09-23-continuous-ink-mode-design.md`

**Deviation from spec:** the finger-lock predicate keys off `markupToolState.selectedTool`, not `activeTool`. `activeTool` flips to `ERASER` whenever the stylus side-button is held (`isStylusButtonEraserActive`); using it would toggle the whole gesture modifier mid-stroke. The overlay only lets a *finger* erase when the tool is manually `ERASER`, so `selectedTool` is the correct, stable signal. Task 2 updates the spec.

**Working-tree warning:** `UnifiedReferenceViewer.kt` already has uncommitted edits from other work (zebra tints, `navigatorInHeader`, `pageStepper`, `showMarkupToggleButton`). Never revert them. Do **not** `git add` that file in Task 4; Task 6 handles committing it.

**Test command (from `C:\Scripts\KKCSheetTracker`):**
```
.\gradlew.bat :app:testDebugUnitTest --tests "<fully.qualified.TestClass>"
```

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupPageStates.kt` | Create | Per-page stroke state, undo stack, snapshot builder, page-key normalizer |
| `app/src/test/java/com/kkc/sheettracker/ui/markup/PdfMarkupPageStatesTest.kt` | Create | Unit tests for the above |
| `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt` | Modify | Two pure predicates; stylus-aware gesture handler; new `gesturesEnabled` |
| `app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt` | Modify | Predicate tests + source-wiring guards |
| `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt` | Modify | Use `PdfMarkupPageStates` for load, persist, paged + continuous wiring, undo |
| `docs/superpowers/specs/2026-09-23-continuous-ink-mode-design.md` | Modify | `activeTool` → `selectedTool` |

---

### Task 1: `PdfMarkupPageStates` holder

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupPageStates.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/markup/PdfMarkupPageStatesTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/kkc/sheettracker/ui/markup/PdfMarkupPageStatesTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.markup

import com.kkc.sheettracker.data.models.PdfInkStroke
import com.kkc.sheettracker.data.models.PdfMarkupPageKey
import com.kkc.sheettracker.data.models.PdfPageMarkup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfMarkupPageStatesTest {
    private val pageA = pdfMarkupPageKey("Plans.pdf", 1)
    private val pageB = pdfMarkupPageKey("Plans.pdf", 2)

    private val persisted = mutableListOf<Pair<PdfMarkupPageKey, PdfMarkupPageSnapshot>>()
    private val states = PdfMarkupPageStates { key, snapshot -> persisted += key to snapshot }

    private fun stroke(id: String) =
        PdfInkStroke(id = id, points = listOf(0.1f, 0.1f, 0.2f, 0.2f))

    private fun ids(key: PdfMarkupPageKey) = states.strokesFor(key).map { it.id }

    @Test
    fun `page key trims and lowercases filename`() {
        assertEquals(PdfMarkupPageKey("plans.pdf", 3), pdfMarkupPageKey("  Plans.PDF ", 3))
    }

    @Test
    fun `add stores on its own page and persists only that page`() {
        states.add(pageB, stroke("s1"))

        assertEquals(listOf("s1"), ids(pageB))
        assertEquals(emptyList<String>(), ids(pageA))
        assertEquals(1, persisted.size)
        assertEquals(pageB, persisted.single().first)
        assertEquals(listOf("s1"), persisted.single().second.visibleStrokes.map { it.id })
    }

    @Test
    fun `strokes on different pages are independent`() {
        states.add(pageA, stroke("a1"))
        states.add(pageB, stroke("b1"))
        states.add(pageA, stroke("a2"))

        assertEquals(listOf("a1", "a2"), ids(pageA))
        assertEquals(listOf("b1"), ids(pageB))
    }

    @Test
    fun `erase hides stroke and persists deleted id`() {
        states.add(pageA, stroke("a1"))
        persisted.clear()

        assertTrue(states.erase(pageA, "a1"))

        assertEquals(emptyList<String>(), ids(pageA))
        assertEquals(listOf("a1"), persisted.single().second.deletedIds)
        assertEquals(emptyList<String>(), persisted.single().second.visibleStrokes.map { it.id })
    }

    @Test
    fun `erase of unknown stroke does nothing`() {
        states.add(pageA, stroke("a1"))
        persisted.clear()

        assertFalse(states.erase(pageA, "nope"))
        assertFalse(states.erase(pageB, "a1"))

        assertTrue(persisted.isEmpty())
        assertEquals(listOf("a1"), ids(pageA))
    }

    @Test
    fun `invalid page keys are ignored`() {
        states.add(pdfMarkupPageKey("", 1), stroke("x"))
        states.add(pdfMarkupPageKey("Plans.pdf", 0), stroke("y"))

        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `undo pops most recent stroke across pages first`() {
        states.add(pageA, stroke("a1"))
        states.add(pageB, stroke("b1"))

        assertTrue(states.undoLast(fallbackKey = pageA))
        assertEquals(emptyList<String>(), ids(pageB))
        assertEquals(listOf("a1"), ids(pageA))

        assertTrue(states.undoLast(fallbackKey = pageA))
        assertEquals(emptyList<String>(), ids(pageA))

        assertFalse(states.undoLast(fallbackKey = pageA))
    }

    @Test
    fun `undo skips stack entries that were already erased`() {
        states.add(pageA, stroke("a1"))
        states.add(pageB, stroke("b1"))
        states.erase(pageB, "b1")

        assertTrue(states.undoLast(fallbackKey = pageB))

        assertEquals(emptyList<String>(), ids(pageA))
    }

    @Test
    fun `undo falls back to own strokes on the centered page only`() {
        states.replaceAll(
            mapOf(
                pageA to PdfMarkupPageSnapshot(
                    strokes = listOf(stroke("other"), stroke("mine")),
                    ownStrokeIds = setOf("mine")
                )
            )
        )

        assertTrue(states.hasUndo(pageA))
        assertFalse(states.hasUndo(pageB))

        assertTrue(states.undoLast(pageA))
        assertEquals(listOf("other"), ids(pageA))

        assertFalse(states.hasUndo(pageA))
        assertFalse(states.undoLast(pageA))
    }

    @Test
    fun `replaceAll swaps the whole map`() {
        states.add(pageA, stroke("a1"))

        states.replaceAll(mapOf(pageB to PdfMarkupPageSnapshot(strokes = listOf(stroke("b1")))))

        assertEquals(emptyList<String>(), ids(pageA))
        assertEquals(listOf("b1"), ids(pageB))
    }

    @Test
    fun `buildPdfMarkupSnapshots merges other tablets strokes with own ids and deletions`() {
        val merged = mapOf(
            pageA to listOf(stroke("other"), stroke("mine")),
            pageB to listOf(stroke("only-other"))
        )
        val ownPages = listOf(
            PdfPageMarkup(
                pdfFilename = "Plans.pdf",
                page = 1,
                strokes = listOf(stroke("mine")),
                deletedStrokeIds = listOf("gone")
            ),
            PdfPageMarkup(
                pdfFilename = "Plans.pdf",
                page = 3,
                strokes = emptyList(),
                deletedStrokeIds = listOf("old")
            )
        )

        val result = buildPdfMarkupSnapshots(merged, ownPages)

        val a = result.getValue(pageA)
        assertEquals(listOf("other", "mine"), a.strokes.map { it.id })
        assertEquals(setOf("mine"), a.ownStrokeIds)
        assertEquals(listOf("gone"), a.deletedIds)

        val b = result.getValue(pageB)
        assertEquals(setOf<String>(), b.ownStrokeIds)
        assertEquals(emptyList<String>(), b.deletedIds)

        val c = result.getValue(pdfMarkupPageKey("Plans.pdf", 3))
        assertEquals(emptyList<String>(), c.strokes.map { it.id })
        assertEquals(listOf("old"), c.deletedIds)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.markup.PdfMarkupPageStatesTest"`
Expected: FAIL to compile — `Unresolved reference: pdfMarkupPageKey` / `PdfMarkupPageStates`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupPageStates.kt`:

```kotlin
package com.kkc.sheettracker.ui.markup

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.kkc.sheettracker.data.models.PdfInkStroke
import com.kkc.sheettracker.data.models.PdfMarkupPageKey
import com.kkc.sheettracker.data.models.PdfPageMarkup
import java.util.Locale

/** Same normalization `PdfMarkupStore` applies to filenames, so keys match store keys. */
fun pdfMarkupPageKey(pdfFilename: String, page: Int): PdfMarkupPageKey =
    PdfMarkupPageKey(pdfFilename.trim().lowercase(Locale.US), page)

private fun PdfMarkupPageKey.isValid(): Boolean = pdfFilename.isNotBlank() && page > 0

/**
 * Everything the viewer knows about one PDF page's markup.
 *
 * [strokes] includes strokes merged in from other tablets; [ownStrokeIds] is the subset this
 * tablet authored, which is all that undo is allowed to remove. [deletedIds] is this tablet's own
 * deletion list, persisted so the deletion wins when tablets merge.
 */
data class PdfMarkupPageSnapshot(
    val strokes: List<PdfInkStroke> = emptyList(),
    val deletedIds: List<String> = emptyList(),
    val ownStrokeIds: Set<String> = emptySet()
) {
    val visibleStrokes: List<PdfInkStroke> = strokes.filter { it.id !in deletedIds }
}

/**
 * Builds the initial per-page state from the store: [merged] is
 * `PdfMarkupStore.getMergedActiveStrokesByPage`, [ownPages] is this tablet's own file
 * (`PdfMarkupStore.loadTabletMarkup(job).pages`).
 */
fun buildPdfMarkupSnapshots(
    merged: Map<PdfMarkupPageKey, List<PdfInkStroke>>,
    ownPages: List<PdfPageMarkup>
): Map<PdfMarkupPageKey, PdfMarkupPageSnapshot> {
    val ownByKey = ownPages.associateBy { pdfMarkupPageKey(it.pdfFilename, it.page) }
    return (merged.keys + ownByKey.keys).associateWith { key ->
        val own = ownByKey[key]
        PdfMarkupPageSnapshot(
            strokes = merged[key].orEmpty(),
            deletedIds = own?.deletedStrokeIds.orEmpty(),
            ownStrokeIds = own?.strokes?.map { it.id }?.toSet().orEmpty()
        )
    }
}

/**
 * Snapshot-backed markup state for every page of one job, so the continuous viewer can draw and
 * show strokes on any page. Each mutation calls [persist] with just the page it changed.
 */
@Stable
class PdfMarkupPageStates(
    private val persist: (PdfMarkupPageKey, PdfMarkupPageSnapshot) -> Unit
) {
    private data class UndoEntry(val key: PdfMarkupPageKey, val strokeId: String)

    private val pages = mutableStateMapOf<PdfMarkupPageKey, PdfMarkupPageSnapshot>()
    private val undoStack = mutableStateListOf<UndoEntry>()

    fun strokesFor(key: PdfMarkupPageKey): List<PdfInkStroke> =
        pages[key]?.visibleStrokes.orEmpty()

    fun add(key: PdfMarkupPageKey, stroke: PdfInkStroke) {
        if (!key.isValid()) return
        val current = pages[key] ?: PdfMarkupPageSnapshot()
        val next = current.copy(
            strokes = current.strokes + stroke,
            ownStrokeIds = current.ownStrokeIds + stroke.id
        )
        pages[key] = next
        undoStack.add(UndoEntry(key, stroke.id))
        persist(key, next)
    }

    /** Returns true if a visible stroke was removed. */
    fun erase(key: PdfMarkupPageKey, strokeId: String): Boolean {
        if (!key.isValid()) return false
        val current = pages[key] ?: return false
        if (strokeId in current.deletedIds || current.strokes.none { it.id == strokeId }) return false
        val next = current.copy(deletedIds = current.deletedIds + strokeId)
        pages[key] = next
        persist(key, next)
        return true
    }

    private fun isVisible(entry: UndoEntry): Boolean =
        pages[entry.key]?.visibleStrokes?.any { it.id == entry.strokeId } == true

    private fun undoTarget(fallbackKey: PdfMarkupPageKey): UndoEntry? {
        undoStack.asReversed().firstOrNull { isVisible(it) }?.let { return it }
        val snapshot = pages[fallbackKey] ?: return null
        val id = snapshot.visibleStrokes.lastOrNull { it.id in snapshot.ownStrokeIds }?.id
            ?: return null
        return UndoEntry(fallbackKey, id)
    }

    fun hasUndo(fallbackKey: PdfMarkupPageKey): Boolean = undoTarget(fallbackKey) != null

    /**
     * Removes the most recent stroke drawn this session (any page). With nothing drawn this
     * session, falls back to this tablet's last persisted stroke on [fallbackKey].
     */
    fun undoLast(fallbackKey: PdfMarkupPageKey): Boolean {
        val target = undoTarget(fallbackKey) ?: return false
        return erase(target.key, target.strokeId)
    }

    fun replaceAll(snapshots: Map<PdfMarkupPageKey, PdfMarkupPageSnapshot>) {
        pages.clear()
        pages.putAll(snapshots)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.markup.PdfMarkupPageStatesTest"`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupPageStates.kt app/src/test/java/com/kkc/sheettracker/ui/markup/PdfMarkupPageStatesTest.kt
git commit -m "feat(markup): add per-page markup state holder with cross-page undo

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Gesture predicates (+ spec tweak)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt` (add two internal functions above `hasContinuousPdfPageRenderDwelled`, currently line 198; add imports)
- Modify: `app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt` (add tests before `private fun continuousPaneSource()`, currently line 43; add imports)
- Modify: `docs/superpowers/specs/2026-09-23-continuous-ink-mode-design.md`

- [ ] **Step 1: Write the failing tests**

In `ContinuousReferencePdfPaneTest.kt` add imports (keep alphabetical with the existing ones):

```kotlin
import androidx.compose.ui.input.pointer.PointerType
import com.kkc.sheettracker.ui.markup.DrawingTool
```

Add these tests inside the class, immediately above `private fun continuousPaneSource()`:

```kotlin
    @Test
    fun fingerGestures_areOwnedByThePaneWheneverInkIsOff() {
        for (allowFinger in listOf(false, true)) {
            for (tool in DrawingTool.values()) {
                assertTrue(
                    shouldContinuousPaneOwnFingerGestures(
                        markupEnabled = false,
                        allowFingerDrawing = allowFinger,
                        selectedTool = tool
                    )
                )
            }
        }
    }

    @Test
    fun fingerGestures_stayWithThePaneWhileInkIsOnAndOnlyTheStylusDraws() {
        assertTrue(
            shouldContinuousPaneOwnFingerGestures(
                markupEnabled = true,
                allowFingerDrawing = false,
                selectedTool = DrawingTool.PEN
            )
        )
        assertTrue(
            shouldContinuousPaneOwnFingerGestures(
                markupEnabled = true,
                allowFingerDrawing = false,
                selectedTool = DrawingTool.HIGHLIGHTER
            )
        )
    }

    @Test
    fun fingerGestures_lockWhenAFingerCanDrawOrErase() {
        assertFalse(
            shouldContinuousPaneOwnFingerGestures(
                markupEnabled = true,
                allowFingerDrawing = true,
                selectedTool = DrawingTool.PEN
            )
        )
        assertFalse(
            shouldContinuousPaneOwnFingerGestures(
                markupEnabled = true,
                allowFingerDrawing = false,
                selectedTool = DrawingTool.ERASER
            )
        )
    }

    @Test
    fun stylusAndEraserPointersAreStylusTypes() {
        assertTrue(isStylusPointerType(PointerType.Stylus))
        assertTrue(isStylusPointerType(PointerType.Eraser))
        assertFalse(isStylusPointerType(PointerType.Touch))
        assertFalse(isStylusPointerType(PointerType.Mouse))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"`
Expected: FAIL to compile — `Unresolved reference: shouldContinuousPaneOwnFingerGestures` / `isStylusPointerType`.

- [ ] **Step 3: Write the implementation**

In `ContinuousReferencePdfPane.kt` add imports next to the existing `androidx.compose.ui.input.pointer.*` imports and `com.kkc.sheettracker.ui.markup.*` imports (lines ~50-64):

```kotlin
import androidx.compose.ui.input.pointer.PointerType
import com.kkc.sheettracker.ui.markup.DrawingTool
```

Add above `internal fun hasContinuousPdfPageRenderDwelled(` (keep any doc comment that precedes it attached to that function):

```kotlin
/**
 * Whether the pane's scroll/pinch/tap handler should run for finger input. With ink on it stays
 * on so fingers keep scrolling while the stylus draws — except when a finger itself draws or
 * erases (finger drawing enabled, or the eraser tool selected, which erases with any pointer),
 * where the list must lock so the finger doesn't scroll and mark at once.
 *
 * Uses the manually [selectedTool], not the effective tool: the stylus side-button flips the
 * effective tool to ERASER mid-stroke, and that must not tear down the gesture modifier.
 */
internal fun shouldContinuousPaneOwnFingerGestures(
    markupEnabled: Boolean,
    allowFingerDrawing: Boolean,
    selectedTool: DrawingTool
): Boolean = !markupEnabled || !(allowFingerDrawing || selectedTool == DrawingTool.ERASER)

/** Pen and pen-eraser pointers belong to the markup overlay, never to scroll/zoom. */
internal fun isStylusPointerType(type: PointerType): Boolean =
    type == PointerType.Stylus || type == PointerType.Eraser
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"`
Expected: PASS (all tests in the class, including the pre-existing ones).

- [ ] **Step 5: Update the spec**

In `docs/superpowers/specs/2026-09-23-continuous-ink-mode-design.md` replace:

```
- `gesturesEnabled = !(markupEnabled && fingerOwnsInk)` where
  `fingerOwnsInk = allowFingerDrawing || activeTool == ERASER`.
  Extracted as a pure, tested function
  `shouldContinuousPaneOwnFingerGestures(markupEnabled, allowFingerDrawing, activeTool)`.
```
with:
```
- `gesturesEnabled = !(markupEnabled && fingerOwnsInk)` where
  `fingerOwnsInk = allowFingerDrawing || selectedTool == ERASER`.
  Uses the manually selected tool, not the effective tool: the stylus side-button flips the effective
  tool to ERASER mid-stroke and must not tear down the gesture modifier. Extracted as a pure, tested function
  `shouldContinuousPaneOwnFingerGestures(markupEnabled, allowFingerDrawing, selectedTool)`.
```
Also replace `The pane needs \`markupToolState\` (already passed) to compute it.` if it still reads correctly — leave it as is.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt docs/superpowers/specs/2026-09-23-continuous-ink-mode-design.md
git commit -m "feat(viewer): add stylus/finger gesture ownership predicates for continuous ink

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Wire gestures into `ContinuousReferencePdfPane`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt` (`gesturesEnabled` at ~line 1089; gesture handler at ~lines 1121-1193)
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt`

- [ ] **Step 1: Write the failing wiring guards**

Add inside `ContinuousReferencePdfPaneTest`, above `private fun continuousPaneSource()`:

```kotlin
    @Test
    fun inkMode_keepsFingerScrollAndZoomHandlerAvailable() {
        val source = continuousPaneSource()

        assertFalse(
            "Ink on must not blanket-disable the scroll/zoom handler; fingers scroll while the pen draws.",
            source.contains("val gesturesEnabled = !markupEnabled")
        )
        assertTrue(source.contains("shouldContinuousPaneOwnFingerGestures("))
    }

    @Test
    fun scrollZoomHandler_ignoresStylusPointers() {
        val source = continuousPaneSource()

        assertTrue(
            "A pen-down must not start a scroll/zoom/tap gesture.",
            source.contains("isStylusPointerType(firstDown.type)")
        )
        assertTrue(
            "A pen landing mid-gesture (resting palm) must hand the gesture to the overlay.",
            source.contains("stylusTookOver")
        )
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"`
Expected: FAIL — the three new assertions (`gesturesEnabled = !markupEnabled` still present; no `isStylusPointerType(firstDown.type)` / `stylusTookOver`).

- [ ] **Step 3: Replace `gesturesEnabled`**

In `ContinuousReferencePdfPane.kt`, replace the comment block and line that currently read (~1082-1089):

```kotlin
    // Markup drawing pins the list to the current page — the pen and the scroll/zoom gesture
    // must not fight each other, same rule ReferencePdfPane applies today. When markup is
    // enabled our own gesture handler below is simply absent, so touches pass straight through
    // to PdfMarkupOverlay. The list's own userScrollEnabled stays false at all times regardless,
    // because scrolling is otherwise always driven programmatically by our handler rather than
    // the list's built-in touch handling — that built-in handling is what used to race against
    // each page's own independent pinch detector.
    val gesturesEnabled = !markupEnabled
```
with:
```kotlin
    // With ink on, the stylus draws through each page's PdfMarkupOverlay while fingers keep
    // scrolling and pinching through our handler below — the handler skips stylus pointers, and
    // the overlay ignores finger input unless finger drawing is on or the eraser tool is
    // selected. Only in those two finger-owns-ink cases is our handler removed, so touches pass
    // straight through to the overlay and the list can't scroll under a drawing finger. The
    // list's own userScrollEnabled stays false at all times regardless, because scrolling is
    // otherwise always driven programmatically by our handler rather than the list's built-in
    // touch handling — that built-in handling is what used to race against each page's own
    // independent pinch detector.
    val gesturesEnabled = shouldContinuousPaneOwnFingerGestures(
        markupEnabled = markupEnabled,
        allowFingerDrawing = markupToolState?.allowFingerDrawing == true,
        selectedTool = markupToolState?.selectedTool ?: DrawingTool.PEN
    )
```

- [ ] **Step 4: Make the handler stylus-aware**

In the `awaitEachGesture { ... }` block (starts ~line 1121):

(a) Immediately after the line
```kotlin
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
```
insert:
```kotlin
                            // Pen strokes belong to PdfMarkupOverlay. Never scroll, zoom, fling or
                            // tap-toggle chrome for a stylus; awaitEachGesture waits for every
                            // pointer to lift before it starts the next gesture.
                            if (isStylusPointerType(firstDown.type)) return@awaitEachGesture
```
This must sit **before** `flingJob?.cancel()` and `isInteracting = true`.

(b) Change
```kotlin
                            var wasMultiTouch = false
```
to
```kotlin
                            var wasMultiTouch = false
                            var stylusTookOver = false
```

(c) As the first statements inside the `do {` loop, directly after `val event = awaitPointerEvent()`, insert:
```kotlin
                                // A pen landing mid-gesture (palm resting while writing): stop
                                // scrolling and hand everything to the overlay. The drag session
                                // times out on its own after 32 ms of no deltas.
                                if (event.changes.any { it.pressed && isStylusPointerType(it.type) }) {
                                    stylusTookOver = true
                                    break
                                }
```

(d) Directly after the line `isInteracting = false` that follows the loop (~line 1193), insert:
```kotlin
                            if (stylusTookOver) return@awaitEachGesture
```
so no tap or fling fires for an aborted gesture.

- [ ] **Step 5: Run tests and compile**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"`
Expected: PASS. (This also compiles the main source set; if it reports `break` or `return@awaitEachGesture` errors, re-check that the edits sit inside `awaitEachGesture`'s lambda and the `do { } while` loop respectively.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt
git commit -m "feat(viewer): let fingers scroll and pinch while stylus inks in continuous mode

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: Wire `UnifiedReferenceViewer` to `PdfMarkupPageStates`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt`

This file has uncommitted edits from other work. Edit only the regions below; do not `git add` it (Task 6).

- [ ] **Step 1: Add imports**

Next to the existing `com.kkc.sheettracker.ui.markup.PdfMarkupToolState` / `PdfMarkupToolbar` imports (~lines 70-71) add:

```kotlin
import com.kkc.sheettracker.ui.markup.PdfMarkupPageStates
import com.kkc.sheettracker.ui.markup.buildPdfMarkupSnapshots
import com.kkc.sheettracker.ui.markup.pdfMarkupPageKey
```

- [ ] **Step 2: Replace the single-page state, load effect, and persist helper**

Replace this whole region (currently from `val localMarkupStrokes = ...` at ~line 604 through the end of `LaunchedEffect(pdfMarkupStore, ..., markupChangeGeneration) { ... }` at ~line 634):

```kotlin
    val localMarkupStrokes = remember(pdfMarkupStore, pdfMarkupJobFolderName) { mutableStateListOf<PdfInkStroke>() }
    val localDeletedIds = remember(pdfMarkupStore, pdfMarkupJobFolderName) { mutableStateListOf<String>() }
    var markupStrokesVisible by remember(pdfMarkupStore, pdfMarkupJobFolderName) { mutableStateOf(true) }
    val markupChangeGeneration = rememberPdfMarkupChangeGeneration(pdfMarkupStore, pdfMarkupJobFolderName)

    LaunchedEffect(pdfMarkupStore, pdfMarkupJobFolderName, resolvedPdfFilename, sourcePage, markupChangeGeneration) {
        ... (whole body, ending with the AppLog.d "UnifiedReferenceViewer reload" call)
    }
```
with:

```kotlin
    // Markup for every page of the job, so the continuous viewer can draw on and show strokes for
    // any visible page. Each add/erase persists only the page it touched.
    val markupPageStates = remember(pdfMarkupStore, pdfMarkupJobFolderName) {
        PdfMarkupPageStates { key, snapshot ->
            if (pdfMarkupStore != null && pdfMarkupJobFolderName.isNotBlank()) {
                scope.launch(Dispatchers.IO) {
                    pdfMarkupStore.savePageMarkup(
                        jobFolderName = pdfMarkupJobFolderName,
                        pdfFilename = key.pdfFilename,
                        page = key.page,
                        strokes = snapshot.visibleStrokes,
                        deletedStrokeIds = snapshot.deletedIds
                    )
                }
            }
        }
    }
    var markupStrokesVisible by remember(pdfMarkupStore, pdfMarkupJobFolderName) { mutableStateOf(true) }
    val markupChangeGeneration = rememberPdfMarkupChangeGeneration(pdfMarkupStore, pdfMarkupJobFolderName)
    val currentMarkupKey = pdfMarkupPageKey(resolvedPdfFilename, sourcePage)

    LaunchedEffect(pdfMarkupStore, pdfMarkupJobFolderName, markupChangeGeneration) {
        if (pdfMarkupStore == null || pdfMarkupJobFolderName.isBlank()) {
            markupPageStates.replaceAll(emptyMap())
            return@LaunchedEffect
        }
        val snapshots = withContext(Dispatchers.IO) {
            buildPdfMarkupSnapshots(
                merged = pdfMarkupStore.getMergedActiveStrokesByPage(pdfMarkupJobFolderName),
                ownPages = pdfMarkupStore.loadTabletMarkup(pdfMarkupJobFolderName).pages
            )
        }
        markupPageStates.replaceAll(snapshots)
        AppLog.d(
            "PdfMarkupDebug",
            "UnifiedReferenceViewer reload job=$pdfMarkupJobFolderName pages=${snapshots.size}"
        )
    }
```

- [ ] **Step 3: Replace the derived stroke values and `persistMarkupState`**

Replace (currently ~lines 647-667):

```kotlin
    val visibleMarkupStrokes = remember(localMarkupStrokes.size, localDeletedIds.size) {
        localMarkupStrokes.filter { it.id !in localDeletedIds }
    }
    val hasMarkupHistory = remember(localMarkupStrokes.size, localDeletedIds.size) {
        visibleMarkupStrokes.isNotEmpty()
    }
    fun persistMarkupState() {
        ... (whole function)
    }
```
with:
```kotlin
    val hasMarkupHistory = markupPageStates.hasUndo(currentMarkupKey)
```

- [ ] **Step 4: Update the toolbar**

In the `val toolbar ... = remember(` key list, replace
```kotlin
        pdfMarkupStore,
        pdfMarkupJobFolderName,
        resolvedPdfFilename,
        sourcePage,
        onToggleMarkupEnabled
```
with
```kotlin
        markupPageStates,
        currentMarkupKey,
        onToggleMarkupEnabled
```
and replace the whole `onUndo = { ... }` lambda (the `val store = ...` through `scope.launch { ... }`) with:
```kotlin
                    onUndo = { markupPageStates.undoLast(currentMarkupKey) },
```

- [ ] **Step 5: Update the paged-mode call site**

In the `ReferencePdfPane(` call replace the three markup params (`markupStrokes`, `onMarkupStrokeAdded`, `onMarkupStrokeErased`) with:

```kotlin
                markupStrokes = if (markupStrokesVisible) markupPageStates.strokesFor(currentMarkupKey) else emptyList(),
                onMarkupStrokeAdded = { stroke -> markupPageStates.add(currentMarkupKey, stroke) },
                onMarkupStrokeErased = { strokeId -> markupPageStates.erase(currentMarkupKey, strokeId) }
```
(Keep `markupEnabled`, `onToggleMarkupEnabled`, `markupToolState` above them unchanged, and the trailing comma/paren structure of the call.)

- [ ] **Step 6: Update the continuous-mode call site**

In the `ContinuousReferencePdfPane(` call replace `markupStrokesForPage`, `onMarkupStrokeAdded`, `onMarkupStrokeErased` with:

```kotlin
                    markupStrokesForPage = { filename, page ->
                        if (markupStrokesVisible) {
                            markupPageStates.strokesFor(pdfMarkupPageKey(filename, page))
                        } else {
                            emptyList()
                        }
                    },
                    onMarkupStrokeAdded = { filename, page, stroke ->
                        markupPageStates.add(pdfMarkupPageKey(filename, page), stroke)
                    },
                    onMarkupStrokeErased = { filename, page, strokeId ->
                        markupPageStates.erase(pdfMarkupPageKey(filename, page), strokeId)
                    },
```

- [ ] **Step 7: Verify nothing references the removed names, then compile**

Run: `git grep -n "localMarkupStrokes\|localDeletedIds\|visibleMarkupStrokes\|persistMarkupState" -- app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt`
Expected: no output.

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Unused-import warnings for `mutableStateListOf` / `PdfInkStroke` are fine; remove those imports only if nothing else in the file uses them.)

- [ ] **Step 8: Run the affected test classes**

Run:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.markup.*" --tests "com.kkc.sheettracker.data.PdfMarkupStoreTest" --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"
```
Expected: PASS. (Do not commit yet — see Task 6.)

---

### Task 5: Full verification and on-device check

- [ ] **Step 1: Run the whole unit suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: PASS except the one known off-device `PdfMarkup` `MotionEvent` stub failure (environment-only, not a regression; see project memory). Report any other failure; do not paper over it.

- [ ] **Step 2: Build the release APK**

Per project notes `adb-install-release.ps1` breaks under `powershell -File` (unicode); build and install directly:

```
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```
Ask the user before installing to any device. Use `.\gradlew.bat assembleDebug` + the debug APK if only a local check is wanted.

- [ ] **Step 3: Manual checklist on a tablet with a stylus**

Open a job PDF in continuous mode and verify, per the spec:
1. Ink on, draw on the centered page, scroll, draw on the next page. Leave the viewer and re-enter: both strokes persist.
2. Strokes on every page are visible while scrolling, with ink on and off.
3. With ink on, finger scroll, fling and pinch-zoom work; pen strokes never scroll the list.
4. Resting a palm while writing does not scroll.
5. "Allow finger drawing" on, or eraser tool selected: fingers lock the list and draw or erase, as before.
6. Undo after drawing on page N then scrolling to N+1 undoes the most recent stroke.
7. While pinch-zoomed, the pen draws exactly where it touches.
8. Paged (non-continuous) mode: draw, erase, undo, and visibility toggle still work.

- [ ] **Step 4: Record results**

Note any failed item with page/steps. Fix in a follow-up commit before Task 6.

---

### Task 6: Commit `UnifiedReferenceViewer.kt`

`UnifiedReferenceViewer.kt` also carries uncommitted, unrelated edits (zebra tint, `navigatorInHeader`, `pageStepper`, `showMarkupToggleButton`, removal of the "Loading thumbnails" label) that belong to other in-progress work, and those hunks cannot be separated without interactive `git add -p`.

- [ ] **Step 1: Ask the user which to do**

Ask: commit `UnifiedReferenceViewer.kt` whole (ink change plus their other WIP hunks), or leave it uncommitted for them to commit with that other work? Do not choose for them.

- [ ] **Step 2 (if they say commit whole):**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt
git commit -m "feat(viewer): per-page ink state so continuous mode draws on any visible page

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

- [ ] **Step 3: Bump nothing**

Do not bump the app version here; the user does that as a separate `chore: bump version` commit when releasing.

---

## Self-Review

**Spec coverage**
- Per-page state, load-all, per-page persist, undo stack + own-stroke fallback → Task 1 (holder, `buildPdfMarkupSnapshots`), Task 4 (load effect, persist lambda, undo).
- `markupStrokesForPage` / callbacks routed by `(filename, page)`; paged mode via `currentMarkupKey`; centered-page gate removed → Task 4 steps 5-6.
- `gesturesEnabled` pure predicate (spec deviation `selectedTool` documented and spec updated) → Tasks 2-3.
- Stylus ignored in handler; mid-gesture abort → Task 3 step 4.
- Overlay presence unchanged, no overlay edits → no task needed (confirmed no file listed).
- Errors: null store / blank job / invalid key → Task 1 tests (`invalid page keys are ignored`), Task 4 load guard and persist guard.
- Tests and manual checklist → Tasks 1-3, 5.
- Working-tree note → header warning and Task 6.

**Placeholder scan:** none. Task 4 step 2/3 quote the old code by region and end marker because the exact old block is 30+ lines already in the file; the replacement text is complete.

**Type consistency:** `pdfMarkupPageKey(filename, page)`, `PdfMarkupPageSnapshot(strokes, deletedIds, ownStrokeIds).visibleStrokes`, `PdfMarkupPageStates.{strokesFor, add, erase, hasUndo, undoLast, replaceAll}`, `buildPdfMarkupSnapshots(merged, ownPages)`, `shouldContinuousPaneOwnFingerGestures(markupEnabled, allowFingerDrawing, selectedTool)`, `isStylusPointerType(type)` are used with identical names and signatures in every task.
