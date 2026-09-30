# Specialty Job Kanban Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an experimental, globally remembered kanban layout (one column per station) to the specialty job screen, and fold the four action cards into the reference pill row in both layouts.

**Architecture:** Pure helpers (column building, per-column toggle, station dots, card ordering, action-row spec) live in a new `SpecialtyKanbanBoard.kt` and are unit tested. The board reuses `SupplyBoardState` (horizontal scroll + active column) and `KKCSlidingTabRow` (station pill row), and the list view's existing toggle/save/edit/delete/dims handlers. `SpecialtyJobDetailScreen` gains a top-bar toggle backed by `UiPreferencesStore` and switches between the existing `LazyColumn` and the board.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), JUnit4 + mockito-kotlin for JVM unit tests, Gradle (`gradlew.bat`), adb.

**Spec:** `docs/superpowers/specs/2026-09-24-specialty-kanban-design.md`

## As built (updated 2026-09-28)

This plan is the original step-by-step record. The shipped code on branch
`claude/code-review-ultra-9w4mpb` differs from it in these ways; where a task below disagrees,
the code (and this list) wins. Do not re-apply the superseded snippets.

- **Action row:** no `|` divider and no Print pill. `KKCPillActionRow` takes `actions` (specialty
  actions, left) and `trailingActions` (reference documents, right) plus `fillWidth = true`, so the
  row is full width from the first frame; its edges fade while pills are scrolled off-screen.
  `dividerAfterIndex`, `pillActionRowDividerAfter` and `SpecialtyActionRowSpec` /
  `specialtyActionRow` were removed. Print is a top-bar icon.
- **Checkbox saves:** do **not** remove the override when the save returns (the Task 5 snippet
  does, which makes checked cards flip back for a moment). All checklist screens use
  `ChecklistOverrides` + `rememberLoadedChecklist` (`ui/specialty/ChecklistOverrides.kt`): each
  load is numbered, a reload is forced after every save, and a tick stays until the stored value
  matches it or a load that started after the save lands. Read-only saves therefore snap back.
- **Columns:** no per-column `LazyColumn`, `KANBAN_COLUMN_WIDTH` or `animateItem`. Columns are the
  Supply board's layout, shared through `ui/supply/BoardColumns.kt` (`BoardColumnsRow`,
  `BoardColumnCard`, `BoardCardFlow`, `BOARD_CARD_WIDTH` = 300dp): cards fill the board height and
  spill into sub-columns, and move with `animateBounds` (off when low-end animations or lazy
  loading are on). Buckets keep 172dp (the list's bottom padding) above the nav bar, or clear the
  keyboard when it is taller. A card taller than its bucket scrolls inside itself.
- **Column data:** columns are `SpecialtyDetailSection`s (`buildSpecialtyKanbanColumns`); headers
  use `kanbanHeaderColor` (station color darkened for white text).
- **Cards:** `StatusBorderedCard` like the list rows; a card done in its column dims only its title,
  steps and bar. Default 48dp checkbox with a screen-reader label; To Order chip in the header;
  station dots with descriptions; the list row's details block (`SpecialtyItemDetails`: notes,
  supplier, model, tracking, order date/URL, dims/Qty editors, attachments, "Saving...") instead
  of a one-line detail; Delete at the end of the View / Edit row. Card toggle state is one
  value-compared `KanbanCardToggleState` so unchanged cards skip recomposition.
- **Sheet rips:** done states load on IO in one pass (`SpecialtyStateStore.loadSheetRipDoneStates`);
  finished rips sort to the bottom like cards.
- **Read-only:** `SpecialtyJobDetailScreen(readOnly)` (archive, and live view-only mode) disables
  checkboxes and hides Edit / Delete / Add Item and the editors in both layouts.
- **Layout setting:** screens follow a toggle made on another open specialty screen
  (`UiPreferencesStore.observeSpecialtyKanbanLayout`).
- **Out-of-plan changes on the same branch:** `CardDepth.kt` (`kkcCardDepth`, now used by job,
  Supply, dashboard, settings and kanban cards), `JobBoardGrid.kt`, `UnifiedJobCard.kt`,
  `UnifiedJobsScreen.kt`, `SupplyDashboardScreen.kt`, the assembly checklist screens and the
  `8.6.1` version bump. See `docs/2026-09-28-specialty-kanban-code-review.md` for why.

---

## Conventions for every task

- Repo root: `C:\Scripts\KKCSheetTracker`. Branch: `claude/code-review-ultra-9w4mpb` (the plan
  originally said `main`; the work and its review fixes landed on this feature branch).
- Run unit tests for the app module only (the `updater-agent` module has unrelated failures):
  `.\gradlew.bat :app:testDebugUnitTest --tests "<fully.qualified.TestClass>"`
- Compile check: `.\gradlew.bat :app:compileDebugKotlin -q` (no output = success).
- Edit files with the Edit/Write tools, not sed/python.
- Stage only the files each task names. Every commit message ends with:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- `SpecialtyJobDetailScreen.kt` path, used throughout:
  `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt`

## File map

| File | Change |
|---|---|
| `app/src/main/java/com/kkc/sheettracker/data/UiPreferencesStore.kt` | add `specialty_kanban_layout` getter/setter |
| `app/src/main/java/com/kkc/sheettracker/ui/components/KKCSlidingPill.kt` | `KKCPillActionRow(dividerAfterIndex)` + `pillActionRowDividerAfter()` helper |
| `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyKanbanBoard.kt` | **new**: pure helpers, card, column, board composables |
| `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt` | toggle, action row, shared handlers, sheet-rip row extraction, layout switch; make `specialtyItemTitle` + `SpecialtyDimsSection` internal |
| `app/src/test/java/com/kkc/sheettracker/data/UiPreferencesStoreTest.kt` | pref tests |
| `app/src/test/java/com/kkc/sheettracker/ui/components/KKCSlidingPillTest.kt` | divider helper test |
| `app/src/test/java/com/kkc/sheettracker/ui/specialty/SpecialtyKanbanBoardLogicTest.kt` | **new**: helper tests |

Also changed as built (see "As built" above): `ui/specialty/ChecklistOverrides.kt` (new),
`ui/supply/BoardColumns.kt` (new), `ui/supply/SupplyDashboardScreen.kt`, `ui/components/CardDepth.kt`
(new), `ui/components/JobBoardGrid.kt`, `ui/jobs/UnifiedJobCard.kt`, `ui/jobs/UnifiedJobsScreen.kt`,
`data/SpecialtyProgressStore.kt`, `data/SpecialtyStateStore.kt`, the assembly checklist screens,
`navigation/NavGraph.kt`, `navigation/ArchiveJobDetailHost.kt` and `app/build.gradle.kts`.

---

### Task 0: Clear pending uncommitted work (prerequisite)

The working tree already holds unrelated, uncommitted changes (station-tinted section headers +
bottom padding, and the dark-mode primary contrast fix). `SpecialtyJobDetailScreen.kt` is one of
those files, so they must be committed first or kanban commits will mix them in.

- [ ] **Step 1: Check status**

Run: `git status --short`
Expected (possibly more): `StatusComponents.kt`, `UnifiedJobCard.kt`, `SpecialtyJobDetailScreen.kt`, `Theme.kt` modified; `KKCContrast.kt`, `KKCDarkPrimaryContrastTest.kt` untracked.

- [ ] **Step 2: Ask the user** whether to commit these now. If yes, commit them as two commits:

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/StatusComponents.kt app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobCard.kt app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt
git commit -m "feat(specialty): tint station section headers with job-list bar colors" -m "Also raises the job screen's bottom padding so the last section clears the nav bar." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git add app/src/main/java/com/kkc/sheettracker/ui/theme/KKCContrast.kt app/src/main/java/com/kkc/sheettracker/ui/theme/Theme.kt app/src/test/java/com/kkc/sheettracker/ui/theme/KKCDarkPrimaryContrastTest.kt
git commit -m "fix(theme): lift unreadable dark-mode primaries" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

If the user says no, stop and ask how to proceed — do not start Task 1 on a dirty `SpecialtyJobDetailScreen.kt`.

---

### Task 1: Kanban layout preference

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/UiPreferencesStore.kt` (after `setBoardThumbnails`)
- Test: `app/src/test/java/com/kkc/sheettracker/data/UiPreferencesStoreTest.kt`

- [ ] **Step 1: Write the failing tests** — add to `UiPreferencesStoreTest` (the class's `setUp` already mocks booleans into `storage`):

```kotlin
    @Test
    fun specialtyKanbanLayout_defaultsToFalse() {
        val store = UiPreferencesStore(context)
        assertFalse(store.getSpecialtyKanbanLayout())
    }

    @Test
    fun specialtyKanbanLayout_persists() {
        val store = UiPreferencesStore(context)
        store.setSpecialtyKanbanLayout(true)
        assertTrue(store.getSpecialtyKanbanLayout())
        store.setSpecialtyKanbanLayout(false)
        assertFalse(store.getSpecialtyKanbanLayout())
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.UiPreferencesStoreTest"`
Expected: compile error `Unresolved reference 'getSpecialtyKanbanLayout'`.

- [ ] **Step 3: Implement** — in `UiPreferencesStore`, after `setBoardThumbnails(...)`:

```kotlin
    /**
     * Experimental kanban layout for specialty job screens. One choice for every job on this
     * tablet (not per job); may move to Settings if the layout is kept.
     */
    fun getSpecialtyKanbanLayout(): Boolean =
        prefs.getBoolean("specialty_kanban_layout", false)

    fun setSpecialtyKanbanLayout(enabled: Boolean) =
        prefs.edit().putBoolean("specialty_kanban_layout", enabled).apply()
```

- [ ] **Step 4: Run to verify pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.UiPreferencesStoreTest"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/UiPreferencesStore.kt app/src/test/java/com/kkc/sheettracker/data/UiPreferencesStoreTest.kt
git commit -m "feat(specialty): remember kanban layout preference" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Pill action row divider

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/KKCSlidingPill.kt` (`KKCPillActionRow`, ~line 749)
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/KKCSlidingPillTest.kt`

- [ ] **Step 1: Write the failing test** — add to `KKCSlidingPillTest`:

```kotlin
    @Test
    fun pillActionRowDividerOnlyBetweenTwoRealGroups() {
        // divider after index 3 of 10 actions -> after the 4th pill only
        assertEquals(listOf(3), (0 until 10).filter { pillActionRowDividerAfter(it, 3, 10) })
        // no divider requested
        assertEquals(emptyList<Int>(), (0 until 10).filter { pillActionRowDividerAfter(it, null, 10) })
        // never a trailing divider after the last pill
        assertEquals(emptyList<Int>(), (0 until 4).filter { pillActionRowDividerAfter(it, 3, 4) })
    }
```

(Add `import org.junit.Assert.assertEquals` if the file doesn't already import it.)

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.KKCSlidingPillTest"`
Expected: `Unresolved reference 'pillActionRowDividerAfter'`.

- [ ] **Step 3: Implement** — replace the `KKCPillActionRow` signature and loop. New signature and helper:

```kotlin
/** True when a group divider belongs right after the pill at [index]. Never after the last pill. */
internal fun pillActionRowDividerAfter(index: Int, dividerAfterIndex: Int?, count: Int): Boolean =
    dividerAfterIndex != null && index == dividerAfterIndex && index < count - 1

@Composable
fun KKCPillActionRow(
    actions: List<KKCPillAction>,
    modifier: Modifier = Modifier,
    /** Draws a padded vertical divider after the pill at this index (splits two groups). */
    dividerAfterIndex: Int? = null
) {
```

Change `actions.forEach { action ->` to `actions.forEachIndexed { index, action ->`, and immediately after the closing brace of that pill's outer `Box(...) { ... }` (still inside the loop) add:

```kotlin
                if (pillActionRowDividerAfter(index, dividerAfterIndex, actions.size)) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .width(1.dp)
                            .height(20.dp)
                            .background(style.selectedText.copy(alpha = 0.35f))
                    )
                }
```

Add any missing imports: `androidx.compose.foundation.background`, `androidx.compose.foundation.layout.width`.

- [ ] **Step 4: Run to verify pass + compile**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.KKCSlidingPillTest"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/KKCSlidingPill.kt app/src/test/java/com/kkc/sheettracker/ui/components/KKCSlidingPillTest.kt
git commit -m "feat(ui): optional group divider in KKCPillActionRow" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Pure kanban helpers

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyKanbanBoard.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/ui/specialty/SpecialtyKanbanBoardLogicTest.kt`

- [ ] **Step 1: Write the failing tests** — create `SpecialtyKanbanBoardLogicTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.specialty

import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_OTHER
import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS
import com.kkc.sheettracker.data.SpecialtyProgressStore
import com.kkc.sheettracker.data.models.SpecialtyCompletionState
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.ui.components.KKCPillAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpecialtyKanbanBoardLogicTest {

    private fun resolved(
        id: String,
        stations: List<SpecialtyStation>,
        done: Map<String, Boolean> = emptyMap(),
        category: SpecialtyItemCategory = SpecialtyItemCategory.CUSTOM,
        material: String? = null,
        orderDate: String? = null
    ) = SpecialtyResolvedItem(
        item = SpecialtyItem(
            id = id, name = id, category = category, stations = stations,
            material = material, orderDate = orderDate
        ),
        completionByKey = done.mapValues { SpecialtyCompletionState(completed = it.value) }
    )

    @Test
    fun columns_sheetRipsFirst_stationsInOrder_otherLast() {
        val saw = resolved("a", listOf(SpecialtyStation.SAW))
        val asm = resolved("b", listOf(SpecialtyStation.ASSEMBLY))
        val none = resolved("c", emptyList())
        val order = listOf(SpecialtyStation.ASSEMBLY, SpecialtyStation.SAW)
        val sections = buildSpecialtyDetailSections(listOf(saw, asm, none), order)

        val columns = buildSpecialtyKanbanColumns(sections, hasSheetRips = true)

        assertEquals(
            listOf(SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS, "ASSEMBLY", "SAW", SPECIALTY_VIEWER_SECTION_ID_OTHER),
            columns.map { it.id }
        )
        assertEquals(listOf("Sheet Rips", "Assembly", "Saw", "Other"), columns.map { it.label })
    }

    @Test
    fun columns_skipSheetRipsWhenNone_andEmptyStations() {
        val saw = resolved("a", listOf(SpecialtyStation.SAW))
        val sections = buildSpecialtyDetailSections(listOf(saw), SpecialtyStation.entries.toList())
        val columns = buildSpecialtyKanbanColumns(sections, hasSheetRips = false)
        assertEquals(listOf("SAW"), columns.map { it.id })
    }

    @Test
    fun columnToggle_picksStationKeyForSplitItems() {
        val item = resolved(
            "split", listOf(SpecialtyStation.SAW, SpecialtyStation.ASSEMBLY),
            done = mapOf("SAW" to true, "ASSEMBLY" to false)
        )
        val toggles = checklistTogglesForItem(item, emptyMap())
        assertEquals("SAW", kanbanColumnToggle(toggles, "SAW")!!.completionKey)
        assertEquals("ASSEMBLY", kanbanColumnToggle(toggles, "ASSEMBLY")!!.completionKey)
    }

    @Test
    fun columnToggle_usesSingleItemKeyForUnsplitItems() {
        val item = resolved(
            "order", listOf(SpecialtyStation.CNC, SpecialtyStation.SAW),
            category = SpecialtyItemCategory.TO_ORDER
        )
        val toggles = checklistTogglesForItem(item, emptyMap())
        assertEquals(SpecialtyProgressStore.ITEM_COMPLETION_KEY, kanbanColumnToggle(toggles, "SAW")!!.completionKey)
        assertNull(kanbanColumnToggle(emptyList(), "SAW"))
    }

    @Test
    fun stationDots_excludeCurrentColumn_inStationOrder_withDoneState() {
        val item = resolved(
            "split", listOf(SpecialtyStation.SAW, SpecialtyStation.CNC, SpecialtyStation.ASSEMBLY),
            done = mapOf("SAW" to true, "CNC" to true, "ASSEMBLY" to false)
        )
        val toggles = checklistTogglesForItem(item, emptyMap())
        val dots = kanbanStationDots(item, "ASSEMBLY", toggles, SpecialtyStation.entries.toList())
        assertEquals(
            listOf(SpecialtyStation.CNC to true, SpecialtyStation.SAW to true),
            dots.map { it.station to it.done }
        )
    }

    @Test
    fun cardOrder_doneDropsToBottom_bothGroupsKeepOrder() {
        val a = resolved("a", listOf(SpecialtyStation.SAW))
        val b = resolved("b", listOf(SpecialtyStation.SAW))
        val c = resolved("c", listOf(SpecialtyStation.SAW))
        val d = resolved("d", listOf(SpecialtyStation.SAW))
        val doneIds = setOf("a", "c")
        val ordered = orderKanbanCards(listOf(a, b, c, d)) { it.item.id in doneIds }
        assertEquals(listOf("b", "d", "a", "c"), ordered.map { it.item.id })
    }

    @Test
    fun detailLine_prefersMaterialThenOrderDate() {
        assertEquals("Material: Walnut", kanbanDetailLine(resolved("a", emptyList(), material = "Walnut", orderDate = "08-24").item))
        assertEquals("Order Date: 08-24", kanbanDetailLine(resolved("a", emptyList(), orderDate = "08-24").item))
        assertNull(kanbanDetailLine(resolved("a", emptyList(), material = "  ").item))
    }

    @Test
    fun actionRow_specialtyActionsFirst_dividerAfterLastSpecialty() {
        val specialty = listOf(KKCPillAction("Door Panels", {}), KKCPillAction("Split View", {}))
        val reference = listOf(KKCPillAction("Assembly", {}), KKCPillAction("Print", {}))
        val spec = specialtyActionRow(specialty, reference)
        assertEquals(listOf("Door Panels", "Split View", "Assembly", "Print"), spec.actions.map { it.label })
        assertEquals(1, spec.dividerAfterIndex)
        assertNull(specialtyActionRow(specialty, emptyList()).dividerAfterIndex)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.specialty.SpecialtyKanbanBoardLogicTest"`
Expected: unresolved references (`buildSpecialtyKanbanColumns`, `kanbanColumnToggle`, ...).

- [ ] **Step 3: Implement helpers** — create `SpecialtyKanbanBoard.kt` with (composables are added in Tasks 5–7):

```kotlin
package com.kkc.sheettracker.ui.specialty

import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS
import com.kkc.sheettracker.data.models.SpecialtyItem
import com.kkc.sheettracker.data.models.SpecialtyResolvedItem
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.ui.components.KKCPillAction

/** One board column: Sheet Rips (items empty; rips render separately), a station, or Other. */
internal data class SpecialtyKanbanColumn(
    val id: String,
    val label: String,
    val items: List<SpecialtyResolvedItem>
)

/** Sheet Rips first (when present), then the list's station sections in order, Other last. */
internal fun buildSpecialtyKanbanColumns(
    sections: List<SpecialtyDetailSection>,
    hasSheetRips: Boolean
): List<SpecialtyKanbanColumn> = buildList {
    if (hasSheetRips) add(SpecialtyKanbanColumn(SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS, "Sheet Rips", emptyList()))
    sections.forEach { add(SpecialtyKanbanColumn(it.id, it.label, it.items)) }
}

/**
 * The checkbox a card shows in [columnId]: that station's toggle for station-split items, else the
 * item's single completion toggle (shared across its columns, as in the list).
 */
internal fun kanbanColumnToggle(
    toggles: List<SpecialtyChecklistToggle>,
    columnId: String
): SpecialtyChecklistToggle? =
    toggles.firstOrNull { it.completionKey == columnId } ?: toggles.singleOrNull()

internal data class KanbanStationDot(val station: SpecialtyStation, val done: Boolean)

/** The item's other stations (not [columnId]) in station order, each with its done state. */
internal fun kanbanStationDots(
    resolved: SpecialtyResolvedItem,
    columnId: String,
    toggles: List<SpecialtyChecklistToggle>,
    stationOrder: List<SpecialtyStation>
): List<KanbanStationDot> =
    orderSpecialtyStations(resolved.item.stations, stationOrder)
        .filter { it.name != columnId }
        .map { station -> KanbanStationDot(station, kanbanColumnToggle(toggles, station.name)?.checked == true) }

/** Unchecked cards keep list order on top; checked cards keep list order at the bottom. */
internal fun orderKanbanCards(
    items: List<SpecialtyResolvedItem>,
    isDone: (SpecialtyResolvedItem) -> Boolean
): List<SpecialtyResolvedItem> {
    val (done, open) = items.partition(isDone)
    return open + done
}

/** The one-line detail under the progress bar: material, else order date. */
internal fun kanbanDetailLine(item: SpecialtyItem): String? {
    item.material?.trim()?.takeIf { it.isNotEmpty() }?.let { return "Material: $it" }
    item.orderDate?.trim()?.takeIf { it.isNotEmpty() }?.let { return "Order Date: $it" }
    return null
}

internal data class SpecialtyActionRowSpec(
    val actions: List<KKCPillAction>,
    val dividerAfterIndex: Int?
)

/** Specialty actions on the left, a divider, then the reference-document pills. */
internal fun specialtyActionRow(
    specialtyActions: List<KKCPillAction>,
    referenceActions: List<KKCPillAction>
): SpecialtyActionRowSpec = SpecialtyActionRowSpec(
    actions = specialtyActions + referenceActions,
    dividerAfterIndex = if (specialtyActions.isNotEmpty() && referenceActions.isNotEmpty()) {
        specialtyActions.lastIndex
    } else {
        null
    }
)
```

- [ ] **Step 4: Run to verify pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.specialty.SpecialtyKanbanBoardLogicTest"`
Expected: BUILD SUCCESSFUL, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyKanbanBoard.kt app/src/test/java/com/kkc/sheettracker/ui/specialty/SpecialtyKanbanBoardLogicTest.kt
git commit -m "feat(specialty): kanban column, toggle, dot, and ordering helpers" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Combined action row in the list view

Replaces the `actions-reference` pill row and the `actions-specialty` widget row with one row.

**Files:**
- Modify: `SpecialtyJobDetailScreen.kt` (items `actions-reference` and `actions-specialty`, ~lines 303–360)

- [ ] **Step 1: Add a shared action-row builder inside `SpecialtyJobDetailScreen`** — just above `SharedTransitionLayout {` (~line 253):

```kotlin
    val actionRow = specialtyActionRow(
        specialtyActions = buildList {
            add(KKCPillAction("Door Panels", onOpenDoorPanels))
            add(KKCPillAction("Rip List", onOpenSawRipList))
            if (availability.hasClosetRods) add(KKCPillAction("Closet Rods", onOpenClosetRods))
            add(KKCPillAction("Split View", onOpenSplitView))
        },
        referenceActions = buildList {
            if (availability.hasAssemblySheet) add(KKCPillAction("Assembly", { onOpenReferenceDocument(ReferenceDocType.ASSEMBLY, 1) }))
            if (availability.hasPlansElevations) add(KKCPillAction("Plans & Elevations", { onOpenReferenceDocument(ReferenceDocType.PLANS_ELEVATIONS, 1) }))
            if (availability.hasDeliverySheet) add(KKCPillAction("Delivery", { onOpenReferenceDocument(ReferenceDocType.DELIVERY_SHEETS, 1) }))
            if (availability.hasPullsSheet) add(KKCPillAction("Pulls", { onOpenReferenceDocument(ReferenceDocType.PULLS, 1) }))
            if (availability.hasThreeDAssets) add(KKCPillAction("3D", onOpenThreeD))
            add(KKCPillAction("Print", { showPrintDialog = true }, Icons.Default.Print))
        }
    )
```

- [ ] **Step 2: Replace both list items** — delete the whole `item(key = "actions-reference") { ... }` block and the whole `item(key = "actions-specialty") { ... }` block, and put this single item in their place:

```kotlin
            item(key = "actions") {
                KKCPillActionRow(
                    actions = actionRow.actions,
                    dividerAfterIndex = actionRow.dividerAfterIndex,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
```

- [ ] **Step 3: Remove the now-unused `SpecialtyActionWidget`** composable (~line 787) if nothing else references it:

Run: `git grep -n "SpecialtyActionWidget" -- app/src/main`
If the only hit is its own definition, delete that function.

- [ ] **Step 4: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin -q`
Expected: no output. Fix unused-import warnings only if they are errors.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt
git commit -m "feat(specialty): fold action cards into the reference pill row" -m "Door Panels, Rip List, Closet Rods and Split View become pills left of a divider." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Shared handlers and sheet-rip row extraction

Pulls logic the board needs out of the list's inline lambdas so both layouts call the same code.

**Files:**
- Modify: `SpecialtyJobDetailScreen.kt`

- [ ] **Step 1: Make two private helpers internal** (same file):
  - `private fun specialtyItemTitle(` → `internal fun specialtyItemTitle(`
  - `private fun SpecialtyDimsSection(` → `internal fun SpecialtyDimsSection(`

- [ ] **Step 2: Add shared handlers** inside `SpecialtyJobDetailScreen`, right after the `actionRow` val from Task 4:

```kotlin
    // SUPERSEDED -- do not copy. Removing the override on save made checked cards flip back
    // briefly; the shipped code uses ChecklistOverrides.toggle (see "As built" at the top).
    val onToggleChecked: (SpecialtyResolvedItem, SpecialtyChecklistToggle, Boolean) -> Unit = { resolved, toggle, next ->
        val itemId = resolved.item.id
        val controlId = toggle.controlId
        val previous = completionOverrides[controlId] ?: toggle.checked
        completionOverrides[controlId] = next
        startInFlightUpdate(inFlightUpdates, controlId)
        coroutineScope.launch {
            try {
                specialtyStateStore.setItemCompletionKey(
                    jobFolderName = jobFolderName,
                    itemId = itemId,
                    completionKey = toggle.completionKey,
                    completed = next
                )
                completionOverrides.remove(controlId)
                toggleErrorMessage = null
            } catch (_: Exception) {
                completionOverrides[controlId] = previous
                val message = "Failed to update checklist item. Please retry."
                toggleErrorMessage = message
                snackbarHostState.showSnackbar(message)
            } finally {
                finishInFlightUpdate(inFlightUpdates, controlId)
            }
        }
    }
    val onPatchItemDims: (SpecialtyResolvedItem, String?, Double?, String?) -> Unit = { resolved, dims, qty, mat ->
        coroutineScope.launch {
            try {
                specialtyStateStore.patchSpecialtyItemFields(jobFolderName, resolved.item.id, dims, qty, mat)
            } catch (_: Exception) {
                snackbarHostState.showSnackbar("Failed to save dimensions.")
            }
        }
    }
    val onEditItem: (com.kkc.sheettracker.data.models.SpecialtyItem) -> Unit = { item ->
        editingItem = item
        showAddSheet = true
    }
    val sheetRipTarget: (AdminBoardStockItem) -> Int = { item ->
        Math.ceil((item.feet ?: 0.0) / item.ripLength).toInt().coerceAtLeast(0)
    }
    val sheetRipIsDone: (AdminBoardStockItem) -> Boolean = { item ->
        resolveSheetRipTallyState(
            specialtyStateStore.getSheetRipStoredDoneCount(jobFolderName, item),
            sheetRipDone[item.id] == true,
            sheetRipTarget(item)
        ).isComplete
    }
    val onSetSheetRipDone: (AdminBoardStockItem, Boolean) -> Unit = { item, completed ->
        coroutineScope.launch {
            specialtyStateStore.setSheetRipCompletion(
                jobFolderName = jobFolderName,
                item = item,
                target = sheetRipTarget(item),
                completed = completed
            )
        }
    }
```

- [ ] **Step 3: Point the list at the shared handlers** — in the list's `SpecialtyChecklistRow(...)` call replace the three lambdas:

```kotlin
                                        onEditItem = onEditItem,
                                        onPatchDims = { dims, qty, mat -> onPatchItemDims(resolved, dims, qty, mat) },
                                        onCheckedChange = { toggle, next -> onToggleChecked(resolved, toggle, next) }
```

(Keep `onDeleteItem = { itemId -> deleteTargetItemId = itemId }` and the other arguments as they are.)

- [ ] **Step 4: Extract the sheet-rip row** — add this composable at file level (below `SpecialtyChecklistRow`):

```kotlin
/** One sheet-rip tally row (material, label, feet, rip count). Used by the list and the board. */
@Composable
internal fun SpecialtySheetRipRow(
    item: AdminBoardStockItem,
    isDone: Boolean,
    target: Int,
    onSetDone: (Boolean) -> Unit,
    onPreviewMolding: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 3.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isDone) 0.5f else 1f)
            .clickable { onSetDone(!isDone) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Checkbox(checked = isDone, onCheckedChange = onSetDone)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.material, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = specialtySheetRipItemLabel(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onPreviewMolding != null) {
                IconButton(onClick = onPreviewMolding) {
                    Icon(Icons.Filled.Visibility, contentDescription = "Preview ${item.name} molding profile")
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "${(item.feet ?: 0.0).toInt()} ft", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = specialtySheetRipLengthLabel(target, item.ripLength),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

Then in the list's sheet-rip `itemsIndexed` block: delete the local `target`/`tally`/`isDone`/`alpha` vals and replace the inner `Surface(tonalElevation = 3.dp, ...) { Row { ... } }` (the one with `.clickable { ... setSheetRipCompletion ... }`) with:

```kotlin
                            SpecialtySheetRipRow(
                                item = item,
                                isDone = sheetRipIsDone(item),
                                target = sheetRipTarget(item),
                                onSetDone = { completed -> onSetSheetRipDone(item, completed) },
                                onPreviewMolding = if (item.moldingId != null) ({ previewMoldingItem = item }) else null,
                                modifier = Modifier.padding(
                                    start = 8.dp,
                                    top = if (index == 0) 8.dp else 0.dp,
                                    end = 8.dp,
                                    bottom = 8.dp
                                )
                            )
```

Also replace the `sheetDoneCount` computation with `val sheetDoneCount = sheetRipItems.count(sheetRipIsDone)`.

- [ ] **Step 5: Compile and run existing specialty tests**

Run: `.\gradlew.bat :app:compileDebugKotlin -q`
Then: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.specialty.*"`
Expected: no compile output; tests BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt
git commit -m "refactor(specialty): share checklist handlers and sheet-rip row" -m "Prepares the job screen for a second (kanban) layout; no behavior change." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Kanban card and column composables

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyKanbanBoard.kt`

- [ ] **Step 1: Add the card** — append to `SpecialtyKanbanBoard.kt`:

```kotlin
/** Header colors for the two non-station columns. */
internal val KANBAN_SHEET_RIPS_COLOR = Color(0xFF475569)
internal val KANBAN_OTHER_COLOR = Color(0xFF6B7280)

internal fun kanbanColumnColor(columnId: String): Color = when (columnId) {
    SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS -> KANBAN_SHEET_RIPS_COLOR
    SPECIALTY_VIEWER_SECTION_ID_OTHER -> KANBAN_OTHER_COLOR
    else -> stationBarColor(columnId)
}

@Composable
internal fun SpecialtyKanbanCard(
    resolved: SpecialtyResolvedItem,
    columnId: String,
    columnColor: Color,
    toggles: List<SpecialtyChecklistToggle>,
    inFlightUpdates: Map<String, Boolean>,
    stationOrder: List<SpecialtyStation>,
    onToggle: (SpecialtyChecklistToggle, Boolean) -> Unit,
    onView: ((String) -> Unit)?,
    onEdit: (SpecialtyItem) -> Unit,
    onDelete: (String) -> Unit,
    onPatchDims: (String?, Double?, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val item = resolved.item
    val toggle = kanbanColumnToggle(toggles, columnId)
    val done = toggle?.checked == true
    val totalSteps = toggles.size.coerceAtLeast(1)
    val completedSteps = toggles.count { it.checked }.coerceAtMost(totalSteps)
    val dots = kanbanStationDots(resolved, columnId, toggles, stationOrder)
    val detail = kanbanDetailLine(item)
    val isSawStation = SpecialtyStation.SAW in item.stations
    val showDims = item.category != SpecialtyItemCategory.TO_ORDER &&
        (isSawStation || item.dimensions != null || item.quantity != null)

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (done) 0.5f else 1f)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (toggle != null) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        Checkbox(
                            checked = toggle.checked,
                            onCheckedChange = { next -> onToggle(toggle, next) },
                            enabled = isToggleEnabled(toggle.controlId, inFlightUpdates),
                            colors = CheckboxDefaults.colors(checkedColor = columnColor)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onDelete(item.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete item",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = specialtyItemTitle(item.cabinetLabel, item.cabinetNumbers, item.name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "$completedSteps/$totalSteps steps complete",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { completedSteps.toFloat() / totalSteps.toFloat() },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = columnColor,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
            if (dots.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dots.forEach { dot ->
                        val dotColor = stationBarColor(dot.station.name)
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (dot.done) dotColor else Color.Transparent)
                                .border(1.5.dp, dotColor, CircleShape)
                        )
                    }
                }
            }
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (item.cabinetNumbers.isNotEmpty() && onView != null) {
                    Button(
                        onClick = { onView(item.cabinetNumbers.first()) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 32.dp)
                    ) { Text("View", style = MaterialTheme.typography.labelMedium) }
                }
                OutlinedButton(
                    onClick = { onEdit(item) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 32.dp)
                ) {
                    Text("Edit", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
            if (showDims) {
                SpecialtyDimsSection(item = item, isSawStation = isSawStation, onPatchDims = onPatchDims)
            }
        }
    }
}
```

Note: `SpecialtyDimsSection` also renders a `Material:` row for non-saw items with data; `showDims` above deliberately omits the `material`-only case so material shows once (via `detail`).

- [ ] **Step 2: Add the column** — append:

```kotlin
@Composable
internal fun SpecialtyKanbanColumnFrame(
    label: String,
    color: Color,
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    Card(
        modifier = modifier.width(KANBAN_COLUMN_WIDTH).fillMaxHeight(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(text = "$done/$total", style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Last card clears the floating nav bar and its Add Item decoration.
            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

internal val KANBAN_COLUMN_WIDTH = 260.dp
```

- [ ] **Step 3: Add imports** at the top of `SpecialtyKanbanBoard.kt` (keep the Task 3 imports):

```kotlin
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.SPECIALTY_VIEWER_SECTION_ID_OTHER
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.ui.jobs.stationBarColor
```

- [ ] **Step 4: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin -q`
Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyKanbanBoard.kt
git commit -m "feat(specialty): kanban card and column composables" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Board with station pill row

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyKanbanBoard.kt`

- [ ] **Step 1: Add the board** — append:

```kotlin
/**
 * Station columns side by side, panned horizontally like the Supply board, with a sliding station
 * pill row that tracks the column at the left edge. Every column stays composed (built a few up
 * front, then one per frame) so panning never builds a column mid-swipe.
 */
@Composable
internal fun SpecialtyKanbanBoard(
    columns: List<SpecialtyKanbanColumn>,
    stationOrder: List<SpecialtyStation>,
    completionOverrides: Map<String, Boolean>,
    inFlightUpdates: Map<String, Boolean>,
    sheetRipItems: List<AdminBoardStockItem>,
    sheetRipIsDone: (AdminBoardStockItem) -> Boolean,
    sheetRipRow: @Composable (AdminBoardStockItem) -> Unit,
    onToggle: (SpecialtyResolvedItem, SpecialtyChecklistToggle, Boolean) -> Unit,
    onView: ((String) -> Unit)?,
    onEdit: (SpecialtyItem) -> Unit,
    onDelete: (String) -> Unit,
    onPatchDims: (SpecialtyResolvedItem, String?, Double?, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val board = rememberSupplyBoardState()
    val scope = rememberCoroutineScope()
    val animationsOn = !LocalLowEndMode.current.animationsDisabled
    val density = LocalDensity.current
    LaunchedEffect(columns.map { it.id }) { board.updateKeys(columns.map { it.id }) }

    var builtColumns by remember { mutableIntStateOf(3) }
    LaunchedEffect(columns.size) {
        while (builtColumns < columns.size) {
            withFrameNanos { }
            builtColumns++
        }
    }

    val activeKey by remember(board) { derivedStateOf { board.activeKey() } }

    Column(modifier = modifier) {
        KKCPillContainer(
            style = rememberKKCPillStyle(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            KKCSlidingTabRow(
                modifier = Modifier.fillMaxWidth(),
                trackingPosition = { if (board.isScrollInProgress) board.position() else null },
                items = columns.map { column ->
                    KKCTabItem(
                        label = column.label.uppercase(),
                        isSelected = (activeKey ?: columns.firstOrNull()?.id) == column.id,
                        alwaysBold = true,
                        onClick = { scope.launch { board.scrollToColumn(column.id, animate = animationsOn) } }
                    )
                }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp)
                .onSizeChanged { board.viewportPx = it.width }
                .horizontalScroll(board.scroll),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 4dp + 12dp spacing = 16dp inset, matching SupplyBoardState's edge.
            Spacer(Modifier.width(4.dp))
            columns.take(builtColumns).forEach { column ->
                key(column.id) {
                    val placed = Modifier.onPlaced { coords ->
                        board.columns[column.id] = coords.positionInParent().x.roundToInt() to coords.size.width
                    }
                    val color = kanbanColumnColor(column.id)
                    if (column.id == SPECIALTY_VIEWER_SECTION_ID_SHEET_RIPS) {
                        SpecialtyKanbanColumnFrame(
                            label = column.label,
                            color = color,
                            done = sheetRipItems.count(sheetRipIsDone),
                            total = sheetRipItems.size,
                            modifier = placed
                        ) {
                            items(sheetRipItems, key = { it.id }) { rip -> sheetRipRow(rip) }
                        }
                    } else {
                        val togglesById = column.items.associate { it.item.id to checklistTogglesForItem(it, completionOverrides) }
                        val isDone: (SpecialtyResolvedItem) -> Boolean = { r ->
                            kanbanColumnToggle(togglesById.getValue(r.item.id), column.id)?.checked == true
                        }
                        val ordered = orderKanbanCards(column.items, isDone)
                        SpecialtyKanbanColumnFrame(
                            label = column.label,
                            color = color,
                            done = column.items.count(isDone),
                            total = column.items.size,
                            modifier = placed
                        ) {
                            items(ordered, key = { it.item.id }) { resolved ->
                                SpecialtyKanbanCard(
                                    resolved = resolved,
                                    columnId = column.id,
                                    columnColor = color,
                                    toggles = togglesById.getValue(resolved.item.id),
                                    inFlightUpdates = inFlightUpdates,
                                    stationOrder = stationOrder,
                                    onToggle = { toggle, next -> onToggle(resolved, toggle, next) },
                                    onView = onView,
                                    onEdit = onEdit,
                                    onDelete = onDelete,
                                    onPatchDims = { d, q, m -> onPatchDims(resolved, d, q, m) },
                                    modifier = if (animationsOn) Modifier.animateItem() else Modifier
                                )
                            }
                        }
                    }
                }
            }
            // Room to scroll the last columns up to the left edge so they can become active.
            Spacer(Modifier.width(with(density) { (board.viewportPx * 0.6f).toDp() }))
        }
    }
}
```

- [ ] **Step 2: Add imports** (in addition to Task 3/6 imports):

```kotlin
import androidx.compose.animation.core.withFrameNanos
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import com.kkc.sheettracker.data.models.AdminBoardStockItem
import com.kkc.sheettracker.ui.components.KKCPillContainer
import com.kkc.sheettracker.ui.components.KKCSlidingTabRow
import com.kkc.sheettracker.ui.components.KKCTabItem
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.components.rememberKKCPillStyle
import com.kkc.sheettracker.ui.supply.rememberSupplyBoardState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
```

If `withFrameNanos` doesn't resolve from `androidx.compose.animation.core`, use `androidx.compose.runtime.withFrameNanos` (check what `SupplyDashboardScreen.kt` imports and copy it).

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin -q`
Expected: no output. `SupplyBoardState.columns`, `updateKeys`, and `viewportPx`'s setter are `internal`, so they're reachable from this package (same module). If the compiler rejects any of them, widen that one member to `internal` in `SupplyBoard.kt` (don't change behavior).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyKanbanBoard.kt
git commit -m "feat(specialty): kanban board with station pill row" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Toggle and layout switch in the job screen

**Files:**
- Modify: `SpecialtyJobDetailScreen.kt`

- [ ] **Step 1: Preference state** — near the other `remember` vals at the top of `SpecialtyJobDetailScreen` (after `val isDarkTheme = ...`):

```kotlin
    val context = LocalContext.current
    val uiPrefs = remember { UiPreferencesStore(context) }
    var kanbanLayout by remember { mutableStateOf(uiPrefs.getSpecialtyKanbanLayout()) }
```

Imports: `androidx.compose.ui.platform.LocalContext`, `com.kkc.sheettracker.data.UiPreferencesStore`, `androidx.compose.material.icons.filled.ViewKanban`, `androidx.compose.material.icons.automirrored.filled.ViewList`.

- [ ] **Step 2: Top-bar toggle** — in `KKCTopAppBar(actions = { ... })`, before the Archive `if`:

```kotlin
                    IconButton(onClick = {
                        kanbanLayout = !kanbanLayout
                        uiPrefs.setSpecialtyKanbanLayout(kanbanLayout)
                    }) {
                        Icon(
                            imageVector = if (kanbanLayout) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.ViewKanban,
                            contentDescription = if (kanbanLayout) "List layout" else "Kanban layout"
                        )
                    }
```

- [ ] **Step 3: Switch content** — the Scaffold content lambda is `{ padding -> LazyColumn(...) { ... } ...sheets/dialogs... }`. Wrap only the `LazyColumn(...) { ... }` call:

```kotlin
    ) { padding ->
        if (kanbanLayout) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(top = 12.dp)
            ) {
                Text(
                    text = if (resolvedItems.isEmpty() &&
                        scanState.status != com.kkc.sheettracker.data.models.ScanStatus.READY
                    ) "Specialty checklist details are loading." else "$completedItems / $totalItems items complete",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
                KKCPillActionRow(
                    actions = actionRow.actions,
                    dividerAfterIndex = actionRow.dividerAfterIndex,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
                if (resolvedItems.isEmpty() && sheetRipItems.isEmpty()) {
                    Text(
                        "No specialty checklist items found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                } else {
                    val kanbanColumns = remember(sections, sheetRipItems) {
                        buildSpecialtyKanbanColumns(sections, hasSheetRips = sheetRipItems.isNotEmpty())
                    }
                    SpecialtyKanbanBoard(
                        columns = kanbanColumns,
                        stationOrder = stationOrder,
                        completionOverrides = completionOverrides,
                        inFlightUpdates = inFlightUpdates,
                        sheetRipItems = sheetRipItems,
                        sheetRipIsDone = sheetRipIsDone,
                        sheetRipRow = { rip ->
                            SpecialtySheetRipRow(
                                item = rip,
                                isDone = sheetRipIsDone(rip),
                                target = sheetRipTarget(rip),
                                onSetDone = { completed -> onSetSheetRipDone(rip, completed) },
                                onPreviewMolding = if (rip.moldingId != null) ({ previewMoldingItem = rip }) else null
                            )
                        },
                        onToggle = onToggleChecked,
                        onView = onJumpToCabinet,
                        onEdit = onEditItem,
                        onDelete = { itemId -> deleteTargetItemId = itemId },
                        onPatchDims = onPatchItemDims,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            LazyColumn(
                // ... existing list, unchanged ...
            )
        }
```

(Leave the Add/Edit sheet, delete dialog, print sheet, and archive sheet after this `if/else` exactly as they are, so both layouts share them.)

- [ ] **Step 4: Compile and run specialty + prefs tests**

Run: `.\gradlew.bat :app:compileDebugKotlin -q`
Then: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.specialty.*" --tests "com.kkc.sheettracker.data.UiPreferencesStoreTest"`
Expected: no compile output; BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt
git commit -m "feat(specialty): experimental kanban layout toggle on the job screen" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: On-device verification

**Files:** none (verification only)

- [ ] **Step 1: Build and install debug**

```bash
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Expected: `Success`.

- [ ] **Step 2: Ask the user** to open job 106 (or any job with Saw/Assembly/Specialty items and sheet rips) on the tablet and tap the new kanban icon. Do not adb-tap through the app. Wait for them to say they're there.

- [ ] **Step 3: Screenshot and check**

```bash
adb exec-out screencap -p > "%TEMP%\kanban1.png"
```

Check against the spec and "As built" above:
- Top-bar icon toggles; the choice survives leaving and reopening another job.
- Action row: Door Panels, Rip List, (Closet Rods), Split View on the left; Assembly, Plans &
  Elevations, Delivery, Pulls, 3D (as available) pinned right; full width from the first frame, no
  `|` divider. Print is the top-bar printer icon.
- Columns: Sheet Rips first (slate), stations in Settings order with darkened station colors, Other
  last (gray); header shows `done/total`. Long columns spill into sub-columns.
- Station pill row follows panning and jumps on tap.
- Card: status border, one checkbox (station-colored when checked), To Order chip where relevant,
  title, steps, bar, labelled dots, the list row's details (notes, supplier, dims/Qty, attachments,
  Saving...), View + `Edit ✎` + Delete.
- Checking a card drops it to the bottom (title/steps/bar dimmed) and fills its dot on other columns;
  the tick doesn't flip back after the save.
- Buckets stop above the nav bar / Add Item; with the keyboard up, a card's dims fields stay visible.
- An archived specialty job: nothing tickable or editable, summary says "(read-only)".
- Repeat once in dark mode and once with low-end animations disabled (no slide, instant jump).

- [ ] **Step 4: Report** findings to the user with the screenshot; fix issues as new tasks before calling the feature done.
