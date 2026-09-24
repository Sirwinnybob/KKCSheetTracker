# Continuous PDF Viewer Ink Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the continuous-scroll PDF viewer, let the stylus draw on any visible page, show strokes on every page, and keep finger scroll/pinch/tap working while ink is on.

**Architecture:** A new `PdfMarkupPageStates` holder keyed by `PdfMarkupPageKey` replaces the single-page stroke lists in `UnifiedReferenceViewer`. The whole job's markup loads once (`getMergedActiveStrokesByPage`), and each add/erase persists only its own page. `ContinuousReferencePdfPane` keeps its scroll/zoom handler on during ink, ignores stylus pointers in it, and locks fingers out only when a finger owns ink (finger drawing on, or eraser tool).

**Tech Stack:** Kotlin, Jetpack Compose (snapshot state, `pointerInput`), JUnit4 unit tests, Gradle (`.\gradlew.bat`).

**Spec:** `docs/superpowers/specs/2026-09-23-continuous-ink-mode-design.md`

**Deviation from spec:** the finger-lock predicate keys off `markupToolState.selectedTool`, not `activeTool`. `activeTool` flips to `ERASER` whenever the stylus side-button is held (`isStylusButtonEraserActive`); using it would toggle the whole gesture modifier mid-stroke. The overlay only lets a *finger* erase when the tool is manually `ERASER`, so `selectedTool` is the correct, stable signal. Task 2 updates the spec.

**Working-tree warning (re-checked at review, 2026-09-23):** the earlier `UnifiedReferenceViewer.kt` WIP (zebra tints, `navigatorInHeader`, `pageStepper`, `showMarkupToggleButton`) was committed in `40d4f3f5`; that file is now clean, so Task 4 commits it directly. The tree now has *unrelated* uncommitted work in `app/src/main/java/com/kkc/sheettracker/perf/*` and `app/src/test/java/com/kkc/sheettracker/perf/*`. Never revert, stage or commit those; always `git add` explicit paths, never `git add -A` / `.`. Run `git status --short` before each commit.

**Review amendments (2026-09-23):** verified against the code before execution.
1. **Persist race moved in scope (Task 1 + Task 4).** Own saves always trigger a reload: `atomicWriteFile` renames `*.tmp-N` onto `<tablet>.markup.json`, and the observer fires on `MOVED_TO` for `.json`. A reload that read the disk before an in-flight save landed would `replaceAll` the page with stale data, and the next stroke on that page would persist on top of the stale copy, **permanently** losing a stroke. Separate `scope.launch(Dispatchers.IO)` saves could also land out of order. `PdfMarkupPageStates` now tracks unsaved edits and a reload guard, and the viewer serializes saves on one writer. Waiting for check 8 to catch this on-device would pause the plan at Task 5 for something cheap to fix in new code.
2. **Stylus with ink off (Task 3).** As written, the handler skipped stylus pointers unconditionally, so with ink **off** the pen could no longer scroll, fling or tap the continuous viewer (the overlay returns `false` when `!inputEnabled`). The stylus skip is now gated on `markupEnabled`.
3. **Palm rest (Task 3 comment, Task 5 check 4).** `PdfMarkupOverlay` uses `pointerInteropFilter`. In Compose UI 1.11.4, when `ACTION_DOWN` returns `false` (a finger/palm with finger drawing off), the filter goes `NotDispatching` until **every** pointer lifts. So a pen that lands while the palm is already down never reaches the overlay. This already happens in paged mode and is not caused by this plan. The handler's `stylusTookOver` abort still helps (the list stops scrolling, and lifting the palm doesn't toggle the chrome), but check 4 no longer expects ink while the palm is down, and a failure there is not a blocker.

## Observation Protocol (applies to every task, every agent)

Everyone who touches this plan — the controller, each implementer subagent, each spec reviewer and each
code-quality reviewer — must **actively look for and flag** potential issues while working, **including
things unrelated to the task or this plan**. Look for: bugs, races and ordering problems, compute-heavy or
allocation-heavy code (especially on the UI thread or per-frame/per-pointer-event), unnecessary IO,
recomposition storms, memory leaks, fragile tests, duplication, dead code and anything else a careful
maintainer would want to know about.

**Where:** append entries to `docs/superpowers/plans/2026-09-23-continuous-ink-mode-observations.md`
under the current task's heading, using the entry format at the top of that file. It is seeded with
issues already spotted while planning; do not re-log those, but do add to them (e.g. confirm, correct or
measure one).

**Rules:**
1. **Flag, don't fix.** Fix only what the current task requires. Anything else — even a one-line obvious
   improvement — goes in the log, not the diff. Unrelated fixes hide in review and risk the user's
   uncommitted work in this tree.
2. **A bug that blocks the current task** is in scope: fix it, and log it as `in-scope: yes`.
3. **Bound the effort.** Note what you noticed while reading code you were already reading; don't go
   audit unrelated modules. A one-line observation is fine. Say `(unverified)` if you did not confirm it.
4. **Be specific:** file, line, what is wrong, why it matters, and a suggested fix. No vague "could be better".
5. **Reviewers too:** spec and quality reviewers must scan the code they read for these issues *in addition*
   to their normal checks, and report them as a separate "Observations" section of their review.
6. **Do not commit the observations file per task.** The controller commits it once in Task 6.

**Subagent prompt addendum — paste verbatim at the end of every implementer, spec-reviewer and
code-quality-reviewer prompt:**

> Observation duty: while doing this task, also flag potential bugs, race conditions, compute-heavy or
> allocation-heavy code, needless IO, fragile tests, duplication or anything else worth improving —
> **including things unrelated to this task or plan**. Do NOT fix unrelated things; append each one to
> `docs/superpowers/plans/2026-09-23-continuous-ink-mode-observations.md` under this task's heading in the
> format at the top of that file (`[Task N | role] KIND — file:line — problem — suggested fix — in-scope: yes/no`),
> and end your report with an "Observations" section listing what you added (or "none"). Mark anything you
> did not verify as `(unverified)`. Fixes required by this task are still made normally.
>
> Ink blocker duty: if you find an ink/markup-related issue that would make this plan's code wrong, unsafe,
> lossy for strokes, or unusable (see "Ink Blocker Protocol" in the plan), STOP. Do not work around it and do
> not start further steps. Log it with `KIND` = `BLOCKER` plus exact evidence (file:line, quoted log or test
> output), and make the first line of your report `STATUS: BLOCKED (ink)`. Ink improvements that do not meet
> that bar are ordinary observations: log them and continue.
>
> Device rule: never touch the Android tablet. No `adb shell input`, no android-tablet MCP tap/swipe/type
> tools, no computer-use, no installs. The user does all on-device UI (navigating, tapping, stroking); the
> controller alone handles install and logcat, per Task 5.

**Controller duty:** after each task, read the new observations, dedupe them, and mention any `BUG`/`RACE`
in your status update to the user right away rather than waiting for the end.

## Ink Blocker Protocol (pause the plan)

Some ink problems are not just "log it": they change what this plan can safely build. When any agent
finds an **ink-related issue that would affect the implementation of this plan**, the plan **pauses** and
the user gets a ready-to-send prompt for a separate session to fix it first.

**A finding is a blocker (not just an observation) if it is ink/markup related AND any of these is true:**
- It makes a plan step's code wrong, impossible or unsafe as written (a signature, file, line or assumption
  in the plan doesn't match reality — e.g. `PdfMarkupOverlay` consumes finger events, `savePageMarkup`
  semantics differ, snapshot state doesn't behave as the tests assume).
- It would make the new behavior lose or corrupt strokes (persist race that drops a stroke, reload that wipes
  in-flight strokes, wrong page attribution, coordinate error under zoom).
- It would make the new behavior unusable in practice (erase or draw lag on the UI thread with realistic
  stroke counts, a reload after every stroke that flickers or discards the stroke being drawn).
- Fixing it inside this plan's task would sprawl into files or behavior the plan doesn't own.

Ink issues that only *could* be improved and don't meet any bullet above stay ordinary observations
(log and continue). If unsure, treat it as a blocker and ask the user rather than guessing.

**What an agent does on finding a blocker:**
1. **Stop.** Do not work around it, do not start the next step, and do not dispatch further tasks. Leave any
   half-finished edit in a compiling state or revert only your own edits for this task; never touch the
   user's other uncommitted changes.
2. Log it in the observations file with `KIND` = `BLOCKER` and the evidence (file:line, log line, failing
   test output).
3. Report `STATUS: BLOCKED (ink)` as the first line of the report, followed by the evidence.

**What the controller does on a blocker (do not proceed until the user replies):**
1. Verify the evidence yourself in the code (read the lines, re-run the failing test). If it doesn't hold up,
   say so, downgrade to a normal observation, and continue.
2. Tell the user: which task/step paused, the blocker in two or three sentences, and why it affects this plan.
3. Give the user **one self-contained prompt** for a fresh Claude Code session (template below), in its own
   fenced block, so it can be copied whole. Do not assume that session has this conversation's context.
4. **Wait.** Resume only when the user says the fix landed or tells you to proceed anyway.

**On resume:** run `git log --oneline -10` and `git status --short`, re-read every file the remaining tasks
edit, and re-check each plan assumption (signatures, line numbers, `PdfMarkupOverlay` behavior). Update the
plan file where reality changed, commit that plan edit, then continue from the paused step.

**Prompt template for the fix session** (fill every `<...>`; keep it self-contained):

````
Repo: C:\Scripts\KKCSheetTracker (Android, Kotlin, Jetpack Compose). Branch: <current branch>.
Read CLAUDE.md first. The working tree has unrelated uncommitted edits (<list from `git status --short`,
e.g. app/src/main/java/com/kkc/sheettracker/perf/*>) — do not revert or commit them; stage only the files you change.

Problem: <one paragraph: what is wrong, how it shows up, who is affected>.

Evidence:
- <file:line — what the code does>
- <log output / failing test / repro steps, quoted exactly>

Why it matters: <what breaks or gets lost, e.g. "a stroke drawn right after another can be overwritten on disk">.

Scope: fix only this. Files likely involved: <paths>. Out of scope: <what not to touch, e.g. the continuous
viewer wiring in docs/superpowers/plans/2026-09-23-continuous-ink-mode.md — that work is paused waiting on this fix>.

Constraints: <e.g. keep PdfMarkupStore file format unchanged; keep the public PdfMarkupOverlay signature; tablets run release builds>.

Acceptance: <observable outcome, e.g. "two strokes drawn within 50 ms on the same page both persist, in order">.
Add or update unit tests where behavior is testable off-device (JVM); note the one known environment-only
PdfMarkup MotionEvent test failure is not a regression. Run:
.\gradlew.bat :app:testDebugUnitTest --tests "<test class>"
Commit with a conventional message, and report the commit hash and what changed so the paused plan can resume.
````

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
| `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt` | Modify | Use `PdfMarkupPageStates` for load (guarded), serialized persist, paged + continuous wiring, undo |
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
    private val pendingSaves = mutableListOf<() -> Unit>()
    private val states = PdfMarkupPageStates { key, snapshot, onSaved ->
        persisted += key to snapshot
        pendingSaves += onSaved
    }

    private fun landAllSaves() {
        pendingSaves.forEach { it() }
        pendingSaves.clear()
    }

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
    fun `guarded reload keeps a page whose save has not landed`() {
        states.add(pageA, stroke("a1"))

        val guard = states.beginReload()
        states.replaceAll(
            mapOf(
                pageA to PdfMarkupPageSnapshot(),
                pageB to PdfMarkupPageSnapshot(strokes = listOf(stroke("b-remote")))
            ),
            guard
        )

        assertEquals(listOf("a1"), ids(pageA))
        assertEquals(listOf("b-remote"), ids(pageB))
    }

    @Test
    fun `guarded reload keeps a page edited while the reload was reading`() {
        states.add(pageA, stroke("a1"))
        landAllSaves()

        val guard = states.beginReload()
        states.add(pageA, stroke("a2"))
        states.replaceAll(mapOf(pageA to PdfMarkupPageSnapshot(strokes = listOf(stroke("a1")))), guard)

        assertEquals(listOf("a1", "a2"), ids(pageA))
    }

    @Test
    fun `guarded reload replaces a page whose saves landed before it started`() {
        states.add(pageA, stroke("a1"))
        landAllSaves()

        val guard = states.beginReload()
        states.replaceAll(
            mapOf(pageA to PdfMarkupPageSnapshot(strokes = listOf(stroke("a1"), stroke("remote")))),
            guard
        )

        assertEquals(listOf("a1", "remote"), ids(pageA))
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

/** Captured by [PdfMarkupPageStates.beginReload] before a reload starts reading the disk. */
class PdfMarkupReloadGuard internal constructor(
    internal val seqAtStart: Long,
    internal val unsavedAtStart: Set<PdfMarkupPageKey>
)

/**
 * Snapshot-backed markup state for every page of one job, so the continuous viewer can draw and
 * show strokes on any page. Each mutation calls [persist] with just the page it changed; the
 * caller must invoke `onSaved` on the main thread once that write has landed on disk.
 *
 * Not thread-safe: call everything on the main thread.
 */
@Stable
class PdfMarkupPageStates(
    private val persist: (PdfMarkupPageKey, PdfMarkupPageSnapshot, onSaved: () -> Unit) -> Unit
) {
    private data class UndoEntry(val key: PdfMarkupPageKey, val strokeId: String)

    private val pages = mutableStateMapOf<PdfMarkupPageKey, PdfMarkupPageSnapshot>()
    private val undoStack = mutableStateListOf<UndoEntry>()

    // Plain (non-snapshot) bookkeeping of which local edits have reached disk, so a reload that
    // read the file before a save landed can't replace newer in-memory strokes with stale ones.
    private var mutationSeq = 0L
    private val lastMutation = HashMap<PdfMarkupPageKey, Long>()
    private val lastSaved = HashMap<PdfMarkupPageKey, Long>()

    fun strokesFor(key: PdfMarkupPageKey): List<PdfInkStroke> =
        pages[key]?.visibleStrokes.orEmpty()

    private fun commit(key: PdfMarkupPageKey, next: PdfMarkupPageSnapshot) {
        pages[key] = next
        val seq = ++mutationSeq
        lastMutation[key] = seq
        persist(key, next) {
            if (seq > (lastSaved[key] ?: 0L)) lastSaved[key] = seq
        }
    }

    fun add(key: PdfMarkupPageKey, stroke: PdfInkStroke) {
        if (!key.isValid()) return
        val current = pages[key] ?: PdfMarkupPageSnapshot()
        val next = current.copy(
            strokes = current.strokes + stroke,
            ownStrokeIds = current.ownStrokeIds + stroke.id
        )
        undoStack.add(UndoEntry(key, stroke.id))
        commit(key, next)
    }

    /** Returns true if a visible stroke was removed. */
    fun erase(key: PdfMarkupPageKey, strokeId: String): Boolean {
        if (!key.isValid()) return false
        val current = pages[key] ?: return false
        if (strokeId in current.deletedIds || current.strokes.none { it.id == strokeId }) return false
        commit(key, current.copy(deletedIds = current.deletedIds + strokeId))
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

    /** Call on the main thread immediately before a reload starts reading the store. */
    fun beginReload(): PdfMarkupReloadGuard = PdfMarkupReloadGuard(
        seqAtStart = mutationSeq,
        unsavedAtStart = lastMutation.filter { (key, seq) -> seq > (lastSaved[key] ?: 0L) }.keys.toSet()
    )

    /**
     * Swaps in freshly loaded pages. With a [guard], pages that had an unsaved edit when the
     * reload started, or were edited since, keep their in-memory state: the disk copy may predate
     * them. The save that lands for such a page triggers another reload that picks up remote
     * strokes for it.
     */
    fun replaceAll(
        snapshots: Map<PdfMarkupPageKey, PdfMarkupPageSnapshot>,
        guard: PdfMarkupReloadGuard? = null
    ) {
        val keep = if (guard == null) {
            emptyMap()
        } else {
            pages.filterKeys { key ->
                key in guard.unsavedAtStart || (lastMutation[key] ?: 0L) > guard.seqAtStart
            }
        }
        pages.clear()
        pages.putAll(snapshots)
        pages.putAll(keep)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.markup.PdfMarkupPageStatesTest"`
Expected: PASS, 14 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/markup/PdfMarkupPageStates.kt app/src/test/java/com/kkc/sheettracker/ui/markup/PdfMarkupPageStatesTest.kt
git commit -m "feat(markup): add per-page markup state holder with cross-page undo

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Observations checkpoint** — append findings (or "none") under `## Task 1` in the observations file, per the Observation Protocol. Do not commit that file yet.

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

- [ ] **Step 7: Observations checkpoint** — append findings (or "none") under `## Task 2` in the observations file, per the Observation Protocol. Do not commit that file yet.

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
            "A pen-down must not start a scroll/zoom/tap gesture while ink is on.",
            source.contains("currentMarkupEnabled && isStylusPointerType(firstDown.type)")
        )
        assertFalse(
            "With ink off the pen must still scroll, fling and tap; the stylus skip must be gated on markupEnabled.",
            source.contains("if (isStylusPointerType(firstDown.type))")
        )
        assertTrue(
            "A pen landing mid-gesture (resting palm) must hand the gesture to the overlay.",
            source.contains("stylusTookOver")
        )
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"`
Expected: FAIL — `gesturesEnabled = !markupEnabled` still present; no `shouldContinuousPaneOwnFingerGestures(` call, no `currentMarkupEnabled && isStylusPointerType(firstDown.type)`, no `stylusTookOver`.

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

`pointerInput(orientation, documentIdentity)` does not restart when `markupEnabled` changes, so read it
through updated state. Next to the existing `val currentOnSingleTap by rememberUpdatedState(onSingleTap)`
(~line 622) add:
```kotlin
    val currentMarkupEnabled by rememberUpdatedState(markupEnabled)
```

In the `awaitEachGesture { ... }` block (starts ~line 1121):

(a) Immediately after the line
```kotlin
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
```
insert:
```kotlin
                            // With ink on, pen strokes belong to PdfMarkupOverlay: never scroll,
                            // zoom, fling or tap-toggle chrome for a stylus. With ink off the
                            // overlay ignores input, so the pen scrolls like a finger. awaitEachGesture
                            // waits for every pointer to lift before it starts the next gesture.
                            if (currentMarkupEnabled && isStylusPointerType(firstDown.type)) return@awaitEachGesture
```
This must sit **after** `flingJob?.cancel()` (a pen touch still stops a running fling, so the page can't scroll under the stroke — changed during Task 3 review) and **before** `isInteracting = true`.

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
                                // scrolling, and fire no tap or fling when the palm lifts. The
                                // overlay's pointerInteropFilter already refused the palm's
                                // ACTION_DOWN, so it won't see this pen until every pointer lifts.
                                if (currentMarkupEnabled && event.changes.any { it.pressed && isStylusPointerType(it.type) }) {
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

Also update the stale comment above `val strokes = markupStrokesForPage(...)` (~line 1008): it says visibility is scoped by "markupStrokesVisible + centered page match". After Task 4 there is no centered-page match, so change that to "markupStrokesVisible".

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt
git commit -m "feat(viewer): let fingers scroll and pinch while stylus inks in continuous mode

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

- [ ] **Step 7: Observations checkpoint** — append findings (or "none") under `## Task 3` in the observations file, per the Observation Protocol. Pay particular attention to the pointer-event loop and per-frame work in this file (~1,400 lines, hot path: runs on every pointer event and every frame during scroll/zoom). Do not commit that file yet.

---

### Task 4: Wire `UnifiedReferenceViewer` to `PdfMarkupPageStates`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt`

Before editing, confirm `git status --short -- app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt` prints nothing. If it shows changes, someone else has edited the file since this review: do not commit it in Step 9, and ask the user instead.

- [ ] **Step 1: Add imports and the save dispatcher**

Next to the existing `com.kkc.sheettracker.ui.markup.PdfMarkupToolState` / `PdfMarkupToolbar` imports (~lines 70-71) add:

```kotlin
import com.kkc.sheettracker.ui.markup.PdfMarkupPageStates
import com.kkc.sheettracker.ui.markup.buildPdfMarkupSnapshots
import com.kkc.sheettracker.ui.markup.pdfMarkupPageKey
```
and with the `kotlinx.coroutines` imports (~lines 75-81) add:
```kotlin
import kotlinx.coroutines.CoroutineStart
```
(`Dispatchers`, `NonCancellable`, `launch`, `withContext` are already imported.)

Add this file-level declaration immediately above the first `@Composable` (~line 495):
```kotlin
// A single writer for markup saves, so saves run in the order the strokes were made. Separate
// Dispatchers.IO launches could land out of order and let an older page snapshot overwrite a newer one.
private val markupSaveDispatcher = Dispatchers.IO.limitedParallelism(1)
```
(If the compiler flags `limitedParallelism` as experimental, add `@OptIn(ExperimentalCoroutinesApi::class)` to that declaration.)

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
        PdfMarkupPageStates { key, snapshot, onSaved ->
            if (pdfMarkupStore != null && pdfMarkupJobFolderName.isNotBlank()) {
                // UNDISPATCHED enqueues onto the single writer right now, in call order.
                // NonCancellable: a stroke made just before leaving the viewer still saves.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    withContext(markupSaveDispatcher + NonCancellable) {
                        // A failed write must neither crash the viewer nor leave the page
                        // flagged "unsaved" forever (every later reload would skip it).
                        runCatching {
                            pdfMarkupStore.savePageMarkup(
                                jobFolderName = pdfMarkupJobFolderName,
                                pdfFilename = key.pdfFilename,
                                page = key.page,
                                strokes = snapshot.visibleStrokes,
                                deletedStrokeIds = snapshot.deletedIds
                            )
                        }.onFailure { error ->
                            AppLog.e("PdfMarkupDebug", "savePageMarkup failed pdf=${key.pdfFilename} page=${key.page}", error)
                        }
                    }
                    onSaved() // back on the main thread
                }
            }
        }
    }
    var markupStrokesVisible by remember(pdfMarkupStore, pdfMarkupJobFolderName) { mutableStateOf(true) }
    val markupChangeGeneration = rememberPdfMarkupChangeGeneration(pdfMarkupStore, pdfMarkupJobFolderName)
    val currentMarkupKey = pdfMarkupPageKey(resolvedPdfFilename, sourcePage)

    LaunchedEffect(pdfMarkupStore, pdfMarkupJobFolderName, markupChangeGeneration) {
        if (pdfMarkupStore == null || pdfMarkupJobFolderName.isBlank()) {
            markupPageStates.clear()
            return@LaunchedEffect
        }
        // Guard first: our own save fires this reload (the observer sees the MOVED_TO), and a
        // stroke drawn while we read must not be replaced by the older disk copy.
        val guard = markupPageStates.beginReload()
        val snapshots = withContext(Dispatchers.IO) {
            buildPdfMarkupSnapshots(
                merged = pdfMarkupStore.getMergedActiveStrokesByPage(pdfMarkupJobFolderName),
                ownPages = pdfMarkupStore.loadTabletMarkup(pdfMarkupJobFolderName).pages
            )
        }
        markupPageStates.replaceAll(snapshots, guard)
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
    // derivedStateOf: recompose only when the answer flips, not on every stroke on any page.
    val hasMarkupHistory by remember(markupPageStates, currentMarkupKey) {
        derivedStateOf { markupPageStates.hasUndo(currentMarkupKey) }
    }
```
(`derivedStateOf` is already imported.)

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
Expected: PASS.

- [ ] **Step 9: Commit**

Run `git status --short` first. Stage **only** this file (the `perf/*` changes belong to other work):

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt
git commit -m "feat(viewer): per-page ink state so continuous mode draws on any visible page

Saves are serialized on one writer and reloads keep pages with unsaved edits,
so quick strokes can no longer be dropped by an out-of-order save or a stale reload.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

- [ ] **Step 10: Observations checkpoint** — append findings (or "none") under `## Task 4` in the observations file, per the Observation Protocol. Pay particular attention to recomposition cost (`markupPageStates.hasUndo(...)` and `strokesFor(...)` are read during composition), and to anything in this ~1,200-line composable that recomposes or allocates per frame. Do not commit that file yet.

---

### Task 5: Full verification and on-device check

- [ ] **Step 1: Run the whole unit suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: PASS except the one known off-device `PdfMarkup` `MotionEvent` stub failure (environment-only, not a regression; see project memory). Report any other failure; do not paper over it.

- [ ] **Step 2: Build and install a debug APK (controller only, after the user says go)**

Logging matters here: `AppLog.d` emits only in **debug** builds (`logging/AppLog.kt`), so a release APK shows
none of the `PdfMarkupDebug` lines this task relies on. Use the debug build path from CLAUDE.md.

1. Confirm the target is safe to overwrite. The tablet currently attached (`adb devices`) must be running a
   **debuggable** build of `com.kkc.sheettracker`:
   ```
   adb -s <serial> shell dumpsys package com.kkc.sheettracker | findstr /i "DEBUGGABLE versionName"
   ```
   If it is **not** `DEBUGGABLE` (a production release tablet), STOP and ask the user: a debug APK is signed
   differently, so `adb install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and the only workaround
   (uninstall) wipes the tablet's data. Never uninstall to make it fit.
2. Ask the user to confirm the serial and that it is OK to install. Then:
   ```
   .\gradlew.bat assembleDebug
   adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
   ```
3. Production tablets run **release** builds. Building or deploying a release APK
   (`.\gradlew.bat assembleRelease` + `adb install -r ...`, per project notes `adb-install-release.ps1` breaks
   under `powershell -File`) happens only when the user asks, after Step 5.

- [ ] **Step 3: Testing division of labor — the user drives the tablet, agents watch the log**

**The user does all UI: navigating, tapping, stroking, pinching, toggling.** Agents and the controller never
touch the tablet UI. That means no `adb shell input ...` (tap/swipe/text/keyevent), no android-tablet MCP
`Click`/`Swipe`/`Drag`/`Type`/`Press`, and no computer-use. Subagents don't touch the device at all and never
install; only the controller may, per Step 2.

What the controller **does**:
- Starts logcat capture before each check, and stops and reads it after:
  ```
  adb -s <serial> logcat -c
  adb -s <serial> logcat -v time PdfMarkupDebug:D AndroidRuntime:E *:S > <scratchpad>\ink-check-<n>.log
  ```
  (run in the background; use the scratchpad directory, not `/tmp`). Add `UnifiedReferenceViewer:W` if you need
  the navigator warnings. Watch for `AndroidRuntime` crashes throughout.
- Gives the user **one check at a time**: exact instructions (which job/PDF, which mode, what to draw or
  gesture), then says "tell me when done".
- Reads the log for that check and reports what it shows against the expectations below, then asks the user
  what they saw on screen. A passing log does not replace what the user sees, and vice versa.
- May take a passive screenshot (`adb exec-out screencap -p`) once the user says they're on the screen; never
  navigates to get there.

Log lines available (all tag `PdfMarkupDebug`): `savePageMarkup ... pdf=<f> page=<n> strokes=<k> deleted=<d>`
(once per stroke or erase), `saveTabletMarkup ...`, `loadTabletMarkup ...`,
`UnifiedReferenceViewer reload job=<j> pages=<n>` (once per reload).

**Checks (the user performs each; the controller confirms via the log):**

| # | User does | Log should show |
|---|---|---|
| 1 | Continuous mode, ink on. Draw one stroke on the centered page, scroll to the next page, draw one stroke there. Leave the viewer, re-enter. | Two `savePageMarkup` lines with **different** `page=`; after re-entering, a `reload ... pages=` count including both. User confirms both strokes show. |
| 2 | Scroll through several pages with ink on, then ink off. | No `savePageMarkup`. User confirms strokes on every page stay visible. |
| 3 | Ink on: finger-scroll, fling, pinch-zoom; then draw with the pen. | No `savePageMarkup` from finger gestures; exactly one per pen stroke. User confirms pen never scrolls the list and finger never draws. |
| 4 | Rest the palm on the screen, then write with the pen; lift both. Repeat with the pen down first, then the palm. | Palm first: the list stops scrolling and lifting the palm doesn't toggle the chrome. Whether the pen inks depends on the OS palm rejection (known overlay limit, same as paged mode, see amendment 3); log it, don't block. Pen first: one `savePageMarkup` per stroke, and the list doesn't move. |
| 4b | Ink **off**: scroll, fling and tap with the pen. | No `savePageMarkup`. User confirms the pen scrolls and the tap toggles the chrome, same as before this change. |
| 5 | Turn on "allow finger drawing", finger-draw one stroke; then select the eraser and erase it with a finger. | One save per finger stroke, one per erase (`deleted` grows). User confirms the list locked during both, as before. |
| 6 | Draw on page N, scroll to N+1, tap undo. | One `savePageMarkup` for page N with `strokes` reduced and `deleted` +1. User confirms the stroke on N vanished. |
| 7 | Pinch-zoom in, draw a stroke, zoom out. | One save. User confirms the stroke sits exactly where the pen touched. |
| 8 | Two strokes on the same page **within about a second**, quickly. | Two saves; the last `savePageMarkup` for that page shows `strokes=` 2 more than before, and any `reload` afterwards still shows both. **User confirms both strokes stay visible, with no flicker.** Now guarded by Task 1/4, so a stroke that disappears or a final save with too few strokes is a **BUG in this plan's code**: fix it before Task 6. |
| 9 | Draw 20+ strokes on one page, then scroll and erase. | Count `reload` lines vs strokes (a reload after every stroke confirms the seeded PERF entry). User reports any lag or jank while drawing or erasing. |
| 10 | Switch to paged (non-continuous) mode: draw, erase, undo, toggle visibility. | Saves for the current page only; nothing for other pages. User confirms paged behavior is unchanged. |

- [ ] **Step 4: Record results**

For each check, note pass/fail, the user's on-screen report, and the log excerpt that backs it (quote the
shortest decisive lines). Fix failures in a follow-up commit before Task 6; a failure that meets the
Ink Blocker Protocol pauses the plan instead.

- [ ] **Step 5: Observations checkpoint** — append findings (or "none") under `## Task 5`. Then tell the user the debug build is what they tested; the release build for production tablets is a separate step they trigger. Include anything seen on-device (dropped frames or jank while scrolling with many strokes, log spam, slow page loads, unexpected reloads after each stroke) and any failing or flaky tests beyond the one known `MotionEvent` failure, with the measurement or log line that shows it. Do not commit that file yet.

---

### Task 6: Wrap-up

`UnifiedReferenceViewer.kt` was committed in Task 4 Step 9, because the review found it clean. If Task 4 left it uncommitted (someone else edited it in the meantime), ask the user now whether to commit it whole or leave it for them. Do not choose for them.

- [ ] **Step 1: Bump nothing**

Do not bump the app version here; the user does that as a separate `chore: bump version` commit when releasing.

- [ ] **Step 2: Triage the observations**

Read the whole observations file. Dedupe, drop anything you disproved, and fill the `## Triage` table
(entry, proposed action: `fix now` / `follow-up` / `ignore`, owner). Then:
1. Present the user a short list: every `BUG`/`RACE`, every `PERF` you consider real, and a count of the rest, each with file:line.
2. For items the user wants tracked, offer to spin each out as a separate background task (`spawn_task`) with a self-contained prompt. Do not start on any of them without the user's go-ahead.
3. Commit the log with the plan artifacts:

```bash
git add docs/superpowers/plans/2026-09-23-continuous-ink-mode-observations.md
git commit -m "docs: record observations from continuous ink mode work

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

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
- Working-tree note → header warning (re-checked at review), Task 4 Step 9 commit, Task 6 fallback.
- Review amendments → persist ordering + reload guard (Task 1 tests 12-14, Task 4 Steps 1-2); stylus gated on ink (Task 3 Step 4, check 4b); palm-rest limit documented (check 4).

**Placeholder scan:** none. Task 4 step 2/3 quote the old code by region and end marker because the exact old block is 30+ lines already in the file; the replacement text is complete.

**Type consistency:** `pdfMarkupPageKey(filename, page)`, `PdfMarkupPageSnapshot(strokes, deletedIds, ownStrokeIds).visibleStrokes`, `PdfMarkupPageStates(persist: (key, snapshot, onSaved) -> Unit).{strokesFor, add, erase, hasUndo, undoLast, beginReload, replaceAll(snapshots, guard), clear}` (guard required since the Task 1 review fix-up), `buildPdfMarkupSnapshots(merged, ownPages)`, `shouldContinuousPaneOwnFingerGestures(markupEnabled, allowFingerDrawing, selectedTool)`, `isStylusPointerType(type)` are used with identical names and signatures in every task.
