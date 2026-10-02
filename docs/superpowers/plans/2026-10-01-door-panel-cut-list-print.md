# Door Panel Cut List Print Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Door Panel Cut List" section to the shared print sheet that generates a sheet-panel cut list PDF (Standard or Room Tags) filtered by material and room, and hands it to the Android print screen.

**Architecture:** Pure-Kotlin data layer (load `cutlist_index.json` + `cabinet_sheet_index.json`, resolve cabinets to rooms, apply selections, build a model) → pure-Kotlin layout pass (columns, wrapping, pagination, colors → positioned blocks per page) → thin Android `PdfDocument`/Canvas painter → existing `printPdfFile`. A Compose section inside `PrintDocumentsBottomSheet` drives selections and calls the pipeline.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, `android.graphics.pdf.PdfDocument`, Gson, JUnit 4 (no Robolectric).

**Spec:** `docs/superpowers/specs/2026-10-01-door-panel-cut-list-print-design.md`

## Global Constraints

- Rows: Door Cut List rows with unit type Sheet only, via existing `parseDoorCutUnitTypeMetadata` + `filterDoorCutRowsToSheets` (`data/DoorCutSheetFilter.kt`).
- Row order: `(page, rowOrdinal)` ascending — CV's own order. Never re-sort rows.
- Dimensions and cabinet text: printed exactly as CV wrote them unless a room split/filter forces re-formatting.
- Title text: `Door Cut List` (no "2.0"). No totals block.
- MDF rule: a material whose name contains `MDF` (case-insensitive) defaults unchecked; all others default checked.
- Rooms: all default checked; Rooms group hidden when the job has exactly one room key.
- No persistence of selections.
- Page: US Letter portrait 612 × 792 pt; margins 72 left/right, 40 top/bottom.
- Data and layout files must not import `android.*` or Compose (they run under plain JUnit; `unitTests.isReturnDefaultValues = true` is set in `app/build.gradle.kts`).
- Gson may leave non-null-typed model fields `null` when JSON omits them. Keep `.orEmpty()` / safe calls on parsed model fields exactly as written below.
- Edit code with the Edit/Write tools, not sed/python scripts.
- Unit test command (PowerShell, repo root): `.\gradlew.bat :app:testDebugUnitTest --tests "<FQN>"`. One pre-existing PdfMarkup MotionEvent test fails off-device in full runs; it is unrelated — always run with `--tests` filters below.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Cabinet text with odd formatting** (`9(3)`, `15A (2)`, trailing commas) — must parse to the right cabinet and count, not silently become Unassigned. Pinned in Task 1 (`parseCabinetText handles compact and lettered cabinets`).
2. **Room labels that don't follow `Room #N (NAME)`** (e.g. plain `KITCHEN`) — must display as-is and sort after numbered rooms, before Unassigned. Pinned in Task 1 (`roomDisplayName falls back to raw label`, `roomKeyComparator orders numbered then named then unassigned`).
3. **Rows with no material** — must still print under a visible heading instead of crashing or vanishing. Pinned in Task 2 (`null material groups under UNKNOWN_MATERIAL`).
4. **Many rooms/materials on one job** — expanded section must stay usable inside the 550 dp sheet. Section body is `heightIn(max = 360.dp)` + `verticalScroll` (Task 6); pinned by manual check in Task 7 step 3.
5. **Double-tapping Print** — must not launch two print jobs. Button is disabled while `building` (Task 6); pinned by manual check in Task 7 step 3.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListData.kt` | Create. Cabinet text parsing, cabinet→room map, room naming/sorting, row resolution, source loading, selections, option lists, room colors, model. Pure Kotlin. |
| `app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListLayout.kt` | Create. Geometry/colors, columns, text wrapping, pagination → `List<CutListPage>`. Pure Kotlin. |
| `app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListPdf.kt` | Create. `PaintTextMeasurer`, Canvas painter, PDF writer, temp-file helper. Android. |
| `app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListSection.kt` | Create. Compose section (collapsed row, checkboxes, Print). |
| `app/src/main/java/com/kkc/sheettracker/ui/components/PrintDocumentsBottomSheet.kt` | Modify. Insert section above file tree; `printPdfFile` → `internal` with optional `jobName`. |
| `app/src/test/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListDataTest.kt` | Create. Tasks 1–3 tests. |
| `app/src/test/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListLayoutTest.kt` | Create. Task 4 tests. |

Deviation from spec: test fixtures are built inline (Kotlin builders and short JSON strings) instead of `app/src/test/resources/doorcutlist/*.json`. Same shapes (669 FF+FL split, cross-room rows; 684 three rooms + MDF; 690 single room), less indirection.

---

### Task 1: Cabinet parsing, room map, room naming

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListData.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListDataTest.kt`

**Interfaces:**
- Consumes: `CabinetSheetIndex`, `CabinetSheetIndexDocuments`, `ReferenceDocumentIndex`, `AssemblySourceDocumentIndex`, `AssemblyVirtualCombinedIndex`, `CabinetPageDetail` from `com.kkc.sheettracker.data.models`.
- Produces:
  - `const val UNASSIGNED_ROOM_KEY = "Unassigned"`
  - `data class CabinetCount(val cabinet: String, val count: Int)`
  - `fun parseCabinetText(raw: String): List<CabinetCount>`
  - `fun formatCabinets(counts: List<CabinetCount>): String`
  - `val roomKeyComparator: Comparator<String>`
  - `fun roomDisplayName(key: String): String`
  - `fun buildCabinetRoomMap(index: CabinetSheetIndex?): Map<String, String>`

- [ ] **Step 1: Write the failing tests**

Create `DoorPanelCutListDataTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.components.doorcutlist

import com.kkc.sheettracker.data.models.AssemblySourceDocumentIndex
import com.kkc.sheettracker.data.models.AssemblyVirtualCombinedIndex
import com.kkc.sheettracker.data.models.CabinetPageDetail
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import com.kkc.sheettracker.data.models.CabinetSheetIndexDocuments
import com.kkc.sheettracker.data.models.ReferenceDocumentIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorPanelCutListDataTest {

    private fun page(room: String?, vararg cabs: String) =
        CabinetPageDetail(cabinets = cabs.toList(), room = room)

    private fun pages(vararg details: CabinetPageDetail): Map<String, CabinetPageDetail> =
        details.mapIndexed { i, d -> (i + 1).toString() to d }.toMap()

    // ---- parseCabinetText / formatCabinets ----

    @Test
    fun `parseCabinetText reads counts and bare cabinets`() {
        assertEquals(
            listOf(CabinetCount("9", 3), CabinetCount("11", 2), CabinetCount("13", 1)),
            parseCabinetText("9 (3), 11 (2), 13")
        )
    }

    @Test
    fun `parseCabinetText handles compact and lettered cabinets`() {
        assertEquals(
            listOf(CabinetCount("9", 3), CabinetCount("15A", 2), CabinetCount("20", 1)),
            parseCabinetText("9(3), 15A (2), 20,")
        )
    }

    @Test
    fun `parseCabinetText returns empty for blank input`() {
        assertEquals(emptyList<CabinetCount>(), parseCabinetText(""))
        assertEquals(emptyList<CabinetCount>(), parseCabinetText(" , "))
    }

    @Test
    fun `formatCabinets omits count of one`() {
        assertEquals(
            "9 (3), 20",
            formatCabinets(listOf(CabinetCount("9", 3), CabinetCount("20", 1)))
        )
    }

    // ---- room naming / ordering ----

    @Test
    fun `roomDisplayName extracts parenthesized name`() {
        assertEquals("VANITIES", roomDisplayName("Room #3 (VANITIES)"))
        assertEquals("UTILITY - BENCH", roomDisplayName("Room #4 (UTILITY - BENCH)"))
    }

    @Test
    fun `roomDisplayName falls back to raw label`() {
        assertEquals("KITCHEN", roomDisplayName("KITCHEN"))
        assertEquals(UNASSIGNED_ROOM_KEY, roomDisplayName(UNASSIGNED_ROOM_KEY))
    }

    @Test
    fun `roomKeyComparator orders numbered then named then unassigned`() {
        val sorted = listOf(
            UNASSIGNED_ROOM_KEY, "Room #10 (X)", "KITCHEN", "Room #2 (B)", "Room #1 (A)"
        ).sortedWith(roomKeyComparator)
        assertEquals(
            listOf("Room #1 (A)", "Room #2 (B)", "Room #10 (X)", "KITCHEN", UNASSIGNED_ROOM_KEY),
            sorted
        )
    }

    // ---- buildCabinetRoomMap ----

    @Test
    fun `buildCabinetRoomMap returns empty for null index`() {
        assertTrue(buildCabinetRoomMap(null).isEmpty())
    }

    @Test
    fun `buildCabinetRoomMap prefers virtualCombined then sources then assembly then plans`() {
        val index = CabinetSheetIndex(
            documents = CabinetSheetIndexDocuments(
                assembly = ReferenceDocumentIndex(
                    // Top-level = FF sheets only, and (deliberately) a conflicting room for cab 1.
                    pageDetails = pages(page("Room #9 (WRONG)", "1"), page("Room #1 (KITCHEN)", "2")),
                    sources = listOf(
                        AssemblySourceDocumentIndex(pageDetails = pages(page("Room #5 (CLOSET)", "56"))),
                    ),
                    virtualCombined = AssemblyVirtualCombinedIndex(
                        pageDetails = pages(page("Room #1 (KITCHEN)", "1"), page("Room #5 (CLOSET)", "57"))
                    )
                ),
                plansElevations = ReferenceDocumentIndex(
                    pageDetails = pages(
                        page("Room #5 (CLOSET)", "63", "64"),
                        page("Room #8 (WRONG)", "2")
                    )
                )
            )
        )
        val map = buildCabinetRoomMap(index)
        assertEquals("Room #1 (KITCHEN)", map["1"])   // virtualCombined beats top-level
        assertEquals("Room #5 (CLOSET)", map["57"])   // virtualCombined
        assertEquals("Room #5 (CLOSET)", map["56"])   // sources
        assertEquals("Room #1 (KITCHEN)", map["2"])   // top-level beats plans
        assertEquals("Room #5 (CLOSET)", map["63"])   // plans fallback
        assertEquals("Room #5 (CLOSET)", map["64"])
    }

    @Test
    fun `buildCabinetRoomMap skips pages with blank room`() {
        val index = CabinetSheetIndex(
            documents = CabinetSheetIndexDocuments(
                assembly = ReferenceDocumentIndex(pageDetails = pages(page("  ", "70"), page(null, "71"))),
                plansElevations = ReferenceDocumentIndex(pageDetails = pages(page("Room #5 (CLOSET)", "70", "71")))
            )
        )
        val map = buildCabinetRoomMap(index)
        assertEquals("Room #5 (CLOSET)", map["70"])
        assertEquals("Room #5 (CLOSET)", map["71"])
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.doorcutlist.DoorPanelCutListDataTest"`
Expected: compilation FAIL — `Unresolved reference: parseCabinetText` (and the other new names).

- [ ] **Step 3: Write the implementation**

Create `DoorPanelCutListData.kt`:

```kotlin
package com.kkc.sheettracker.ui.components.doorcutlist

import com.kkc.sheettracker.data.models.CabinetPageDetail
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import java.util.Locale

/**
 * Data layer for the generated Door Panel Cut List print. Pure Kotlin (no android.*), so every
 * rule here is covered by plain JUnit. Rows come from .metadata/hardwoods/cutlist_index.json in
 * CV order; rooms come from .metadata/cabinet_sheet_index.json.
 */

const val UNASSIGNED_ROOM_KEY = "Unassigned"

data class CabinetCount(val cabinet: String, val count: Int)

private val CABINET_TOKEN = Regex("""^\s*(.+?)\s*(?:\(\s*(\d+)\s*\))?\s*$""")
private val ROOM_NUMBER = Regex("""#\s*(\d+)""")
private val ROOM_LABEL = Regex("""^Room\s*#\s*\d+\s*\((.*)\)\s*$""", RegexOption.IGNORE_CASE)

/** CV cabinet text → (cabinet, count) pairs: "9 (3), 11 (2), 13" → [9×3, 11×2, 13×1]. */
fun parseCabinetText(raw: String): List<CabinetCount> =
    raw.split(',').mapNotNull { token ->
        val match = CABINET_TOKEN.find(token) ?: return@mapNotNull null
        val cabinet = match.groupValues[1].trim()
        if (cabinet.isEmpty()) return@mapNotNull null
        CabinetCount(cabinet, match.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1)
    }

fun formatCabinets(counts: List<CabinetCount>): String =
    counts.joinToString(", ") { if (it.count > 1) "${it.cabinet} (${it.count})" else it.cabinet }

/** Numbered rooms ("Room #N (...)") by N, then other labels alphabetically, Unassigned last. */
val roomKeyComparator: Comparator<String> = compareBy<String>(
    { key ->
        when {
            key == UNASSIGNED_ROOM_KEY -> 2
            ROOM_NUMBER.containsMatchIn(key) -> 0
            else -> 1
        }
    },
    { key -> ROOM_NUMBER.find(key)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
    { key -> key.uppercase(Locale.US) }
)

/** "Room #3 (VANITIES)" → "VANITIES"; anything else is shown as-is. */
fun roomDisplayName(key: String): String =
    ROOM_LABEL.find(key.trim())?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: key.trim()

/**
 * Cabinet → CV room label. First room found wins, scanning in priority order:
 * combined assembly (FF + FL sets), each assembly source, top-level assembly (first PDF only),
 * then Plans & Elevations. Pages with a blank room are skipped.
 */
fun buildCabinetRoomMap(index: CabinetSheetIndex?): Map<String, String> {
    if (index == null) return emptyMap()
    // Gson can leave these null despite non-null Kotlin types; keep the safe calls.
    val assembly = index.documents?.assembly
    val pageSets: List<Map<String, CabinetPageDetail>> = buildList {
        add(assembly?.virtualCombined?.pageDetails.orEmpty())
        assembly?.sources.orEmpty().forEach { add(it.pageDetails.orEmpty()) }
        add(assembly?.pageDetails.orEmpty())
        add(index.documents?.plansElevations?.pageDetails.orEmpty())
    }
    val result = linkedMapOf<String, String>()
    pageSets.forEach { pages ->
        pages.values.forEach { page ->
            val room = page.room?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            page.cabinets.orEmpty().forEach { cabinet ->
                val key = cabinet.trim()
                if (key.isNotEmpty()) result.putIfAbsent(key, room)
            }
        }
    }
    return result
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.doorcutlist.DoorPanelCutListDataTest"`
Expected: PASS (10 tests). "Unnecessary safe call" compiler warnings on the Gson-guarded lines are expected.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListData.kt app/src/test/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListDataTest.kt
git commit -m "feat(print): door cut list cabinet parsing and room map

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Row resolution, selections, options, room colors, model

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListData.kt` (append)
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListDataTest.kt` (append)

**Interfaces:**
- Consumes (Task 1): `UNASSIGNED_ROOM_KEY`, `CabinetCount`, `parseCabinetText`, `formatCabinets`, `roomKeyComparator`, `roomDisplayName`; `HardwoodCutlistRow` from `data.models`.
- Produces:
  - `const val UNKNOWN_MATERIAL = "(No material)"`
  - `data class RoomGroup(val roomKey: String, val cabinets: List<CabinetCount>, val qty: Int)`
  - `data class ResolvedCutListRow(val material: String, val qty: Int, val description: String, val width: String, val length: String, val cabinetText: String, val groups: List<RoomGroup>)`
  - `fun resolveRow(row: HardwoodCutlistRow, cabinetRooms: Map<String, String>): ResolvedCutListRow`
  - `data class DoorPanelCutListSource(val jobFolderName: String, val rows: List<ResolvedCutListRow>)` with `val materials: List<String>`, `val roomKeys: List<String>`
  - `data class CutListSelection(val roomTags: Boolean, val materials: Set<String>, val rooms: Set<String>)`
  - `fun isDefaultUncheckedMaterial(material: String): Boolean`
  - `fun defaultSelection(source: DoorPanelCutListSource): CutListSelection`
  - `data class MaterialOption(val material: String, val pieces: Int, val checked: Boolean)`
  - `data class RoomOption(val key: String, val displayName: String, val pieces: Int, val checked: Boolean)`
  - `fun materialOptions(source: DoorPanelCutListSource, selection: CutListSelection): List<MaterialOption>`
  - `fun roomOptions(source: DoorPanelCutListSource, selection: CutListSelection): List<RoomOption>`
  - `fun showRoomFilter(source: DoorPanelCutListSource): Boolean`
  - `val ROOM_PALETTE: List<Int>`, `val UNASSIGNED_ROOM_COLOR: Int`
  - `fun assignRoomColors(roomKeys: List<String>, seed: String): Map<String, Int>`
  - `data class CutListRow(val qty: Int, val description: String, val width: String, val length: String, val cabinetText: String, val roomKey: String?)`
  - `data class CutListMaterialSection(val material: String, val unitsLabel: String, val rows: List<CutListRow>)`
  - `data class CutListRoom(val key: String, val displayName: String, val color: Int)`
  - `data class DoorPanelCutListModel(val jobTitle: String, val dateText: String, val roomTags: Boolean, val filteredRoomNames: List<String>, val rooms: List<CutListRoom>, val materials: List<CutListMaterialSection>)` with `val totalPieces: Int`, `val canPrint: Boolean`
  - `fun buildCutListModel(source: DoorPanelCutListSource, selection: CutListSelection, dateText: String): DoorPanelCutListModel`
  - `fun cutListJobNumber(jobFolderName: String): String`

- [ ] **Step 1: Write the failing tests**

Append inside `class DoorPanelCutListDataTest` (add imports `com.kkc.sheettracker.data.models.HardwoodCutlistRow`, `org.junit.Assert.assertFalse`, `org.junit.Assert.assertNull`, `org.junit.Assert.assertNotEquals`):

```kotlin
    // ---- fixtures modelled on 669 / 684 ----

    private val rooms669 = mapOf(
        "20" to "Room #1 (KITCHEN)",
        "23" to "Room #2 (PANTRY)",
        "30" to "Room #3 (VANITIES)",
        "42" to "Room #4 (UTILITY - BENCH)",
        "63" to "Room #5 (CLOSET)",
        "64" to "Room #5 (CLOSET)",
    )

    private fun row(
        ordinal: Int,
        qty: Int,
        cabinetText: String,
        material: String? = "3/4 2s White Oak Rift",
        width: String = "17.125",
        description: String = "Door Slab 1B-L",
        cabinets: List<String> = emptyList(),
    ) = HardwoodCutlistRow(
        rowId = "DOOR_CUT_LIST:1:$ordinal",
        page = 1,
        rowOrdinal = ordinal,
        qty = qty,
        material = material,
        description = description,
        width = width,
        length = "24.125",
        unitType = "SHEETS",
        cabinets = cabinets,
        rawCabinetText = cabinetText,
    )

    private fun source669() = DoorPanelCutListSource(
        jobFolderName = "669 - WIECHERT 3146 NW CROSSINGS",
        rows = listOf(
            resolveRow(row(0, 1, "20", width = "34.375"), rooms669),
            resolveRow(row(1, 2, "20, 42"), rooms669),                       // spans KITCHEN + UTILITY
            resolveRow(row(2, 2, "23, 30", width = "19.125"), rooms669),     // spans PANTRY + VANITIES
            resolveRow(row(3, 2, "63, 64", material = "3/4 DOUBLE FUMED", width = "31.0675"), rooms669),
            resolveRow(row(4, 1, "30", material = "1/4 MDF", width = "9.565"), rooms669),
        )
    )

    // ---- resolveRow ----

    @Test
    fun `single room row keeps CV qty and cabinet text`() {
        val r = resolveRow(row(0, 7, "9 (3), 11 (2), 13 (2)"), mapOf("9" to "R1", "11" to "R1", "13" to "R1"))
        assertEquals(7, r.qty)
        assertEquals("9 (3), 11 (2), 13 (2)", r.cabinetText)
        assertEquals(listOf(RoomGroup("R1", parseCabinetText("9 (3), 11 (2), 13 (2)"), 7)), r.groups)
    }

    @Test
    fun `multi room row splits qty by cabinet counts in room order`() {
        val r = resolveRow(
            row(0, 7, "16 (2), 9 (3), 11 (2)"),
            mapOf("9" to "Room #1 (KITCHEN)", "11" to "Room #1 (KITCHEN)", "16" to "Room #2 (LAUNDRY)")
        )
        assertEquals(
            listOf(
                RoomGroup("Room #1 (KITCHEN)", listOf(CabinetCount("9", 3), CabinetCount("11", 2)), 5),
                RoomGroup("Room #2 (LAUNDRY)", listOf(CabinetCount("16", 2)), 2),
            ),
            r.groups
        )
    }

    @Test
    fun `blank cabinet text falls back to cabinets list`() {
        val r = resolveRow(row(0, 2, "", cabinets = listOf("20", "42")), rooms669)
        assertEquals(listOf("Room #1 (KITCHEN)", "Room #4 (UTILITY - BENCH)"), r.groups.map { it.roomKey })
        assertEquals("20, 42", r.cabinetText)
    }

    @Test
    fun `row with no cabinets is unassigned with original qty`() {
        val r = resolveRow(row(0, 3, ""), rooms669)
        assertEquals(listOf(RoomGroup(UNASSIGNED_ROOM_KEY, emptyList(), 3)), r.groups)
    }

    @Test
    fun `unknown cabinet maps to unassigned`() {
        val r = resolveRow(row(0, 1, "99"), rooms669)
        assertEquals(UNASSIGNED_ROOM_KEY, r.groups.single().roomKey)
    }

    @Test
    fun `null material groups under UNKNOWN_MATERIAL`() {
        val r = resolveRow(row(0, 1, "20", material = null), rooms669)
        assertEquals(UNKNOWN_MATERIAL, r.material)
    }

    // ---- source / selection / options ----

    @Test
    fun `source exposes materials in first appearance order and sorted rooms`() {
        val s = source669()
        assertEquals(listOf("3/4 2s White Oak Rift", "3/4 DOUBLE FUMED", "1/4 MDF"), s.materials)
        assertEquals(
            listOf("Room #1 (KITCHEN)", "Room #2 (PANTRY)", "Room #3 (VANITIES)", "Room #4 (UTILITY - BENCH)", "Room #5 (CLOSET)"),
            s.roomKeys
        )
    }

    @Test
    fun `default selection unchecks MDF materials and checks all rooms`() {
        assertTrue(isDefaultUncheckedMaterial("1/4 MDF"))
        assertTrue(isDefaultUncheckedMaterial("1/4 PG Maple mdf"))
        assertFalse(isDefaultUncheckedMaterial("3/4 2s White Oak Rift"))
        val sel = defaultSelection(source669())
        assertFalse(sel.roomTags)
        assertEquals(setOf("3/4 2s White Oak Rift", "3/4 DOUBLE FUMED"), sel.materials)
        assertEquals(source669().roomKeys.toSet(), sel.rooms)
    }

    @Test
    fun `material counts respect room filter and room counts respect material filter`() {
        val s = source669()
        val sel = defaultSelection(s).copy(rooms = s.roomKeys.toSet() - "Room #4 (UTILITY - BENCH)")
        val mats = materialOptions(s, sel).associateBy { it.material }
        assertEquals(1 + 1 + 2, mats.getValue("3/4 2s White Oak Rift").pieces) // UTILITY share of row 1 removed
        assertTrue(mats.getValue("3/4 2s White Oak Rift").checked)
        assertFalse(mats.getValue("1/4 MDF").checked)

        val roomsOpt = roomOptions(s, sel).associateBy { it.key }
        assertEquals(1, roomsOpt.getValue("Room #3 (VANITIES)").pieces) // 1 from row 2; unchecked MDF row excluded
        assertEquals("VANITIES", roomsOpt.getValue("Room #3 (VANITIES)").displayName)
        assertFalse(roomsOpt.getValue("Room #4 (UTILITY - BENCH)").checked)
    }

    @Test
    fun `room filter hidden for single room job`() {
        val single = DoorPanelCutListSource("690 - X", listOf(resolveRow(row(0, 1, "20"), rooms669)))
        assertFalse(showRoomFilter(single))
        assertTrue(showRoomFilter(source669()))
    }

    // ---- room colors ----

    @Test
    fun `room colors are distinct stable and unassigned is gray`() {
        val keys = source669().roomKeys + UNASSIGNED_ROOM_KEY
        val a = assignRoomColors(keys, "669 - WIECHERT 3146 NW CROSSINGS")
        val b = assignRoomColors(keys.reversed(), "669 - WIECHERT 3146 NW CROSSINGS")
        assertEquals(a, b)
        val named = keys.filter { it != UNASSIGNED_ROOM_KEY }.map { a.getValue(it) }
        assertEquals(named.size, named.toSet().size)
        assertTrue(named.all { it in ROOM_PALETTE })
        assertEquals(UNASSIGNED_ROOM_COLOR, a.getValue(UNASSIGNED_ROOM_KEY))
    }

    // ---- model ----

    @Test
    fun `room tags splits rows per room and keeps CV order`() {
        val s = source669()
        val m = buildCutListModel(s, defaultSelection(s).copy(roomTags = true), "1 October, 2026")
        val oak = m.materials.first { it.material == "3/4 2s White Oak Rift" }.rows
        assertEquals(
            listOf(
                Triple(1, "20", "Room #1 (KITCHEN)"),
                Triple(1, "20", "Room #1 (KITCHEN)"),
                Triple(1, "42", "Room #4 (UTILITY - BENCH)"),
                Triple(1, "23", "Room #2 (PANTRY)"),
                Triple(1, "30", "Room #3 (VANITIES)"),
            ),
            oak.map { Triple(it.qty, it.cabinetText, it.roomKey) }
        )
        assertEquals(listOf("3/4 2s White Oak Rift", "3/4 DOUBLE FUMED"), m.materials.map { it.material })
        assertEquals("Sheet", m.materials.first().unitsLabel)
    }

    @Test
    fun `standard mode keeps rows whole when all rooms checked`() {
        val s = source669()
        val m = buildCutListModel(s, defaultSelection(s), "d")
        val oak = m.materials.first().rows
        assertEquals(listOf(1, 2, 2), oak.map { it.qty })
        assertEquals(listOf("20", "20, 42", "23, 30"), oak.map { it.cabinetText })
        assertTrue(oak.all { it.roomKey == null })
        assertTrue(m.rooms.isEmpty())
        assertTrue(m.filteredRoomNames.isEmpty())
    }

    @Test
    fun `standard mode trims excluded rooms share and omits fully excluded rows`() {
        val s = source669()
        val sel = defaultSelection(s).copy(rooms = setOf("Room #1 (KITCHEN)", "Room #2 (PANTRY)"))
        val m = buildCutListModel(s, sel, "d")
        assertEquals(listOf("3/4 2s White Oak Rift"), m.materials.map { it.material }) // CLOSET-only material gone
        val oak = m.materials.single().rows
        assertEquals(listOf(Pair(1, "20"), Pair(1, "20"), Pair(1, "23")), oak.map { it.qty to it.cabinetText })
        assertEquals(listOf("KITCHEN", "PANTRY"), m.filteredRoomNames)
    }

    @Test
    fun `room tags legend uses colors from all source rooms`() {
        val s = source669()
        val all = buildCutListModel(s, defaultSelection(s).copy(roomTags = true), "d")
        val filtered = buildCutListModel(
            s, defaultSelection(s).copy(roomTags = true, rooms = setOf("Room #3 (VANITIES)")), "d"
        )
        val vanAll = all.rooms.first { it.key == "Room #3 (VANITIES)" }.color
        val vanFiltered = filtered.rooms.single().color
        assertEquals(vanAll, vanFiltered)
        assertEquals("VANITIES", filtered.rooms.single().displayName)
    }

    @Test
    fun `canPrint false when no material or no room selected`() {
        val s = source669()
        assertTrue(buildCutListModel(s, defaultSelection(s), "d").canPrint)
        assertFalse(buildCutListModel(s, defaultSelection(s).copy(materials = emptySet()), "d").canPrint)
        assertFalse(buildCutListModel(s, defaultSelection(s).copy(rooms = emptySet()), "d").canPrint)
        assertFalse(
            buildCutListModel(
                s, defaultSelection(s).copy(materials = setOf("3/4 DOUBLE FUMED"), rooms = setOf("Room #1 (KITCHEN)")), "d"
            ).canPrint
        )
    }

    @Test
    fun `total pieces and job number`() {
        val s = source669()
        assertEquals(1 + 2 + 2 + 2, buildCutListModel(s, defaultSelection(s), "d").totalPieces)
        assertEquals("669", cutListJobNumber("669 - WIECHERT 3146 NW CROSSINGS"))
        assertEquals("644d", cutListJobNumber("644d - SHOWROOM"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.doorcutlist.DoorPanelCutListDataTest"`
Expected: compilation FAIL — `Unresolved reference: resolveRow` etc.

- [ ] **Step 3: Write the implementation**

Append to `DoorPanelCutListData.kt` (add import `com.kkc.sheettracker.data.models.HardwoodCutlistRow`):

```kotlin
const val UNKNOWN_MATERIAL = "(No material)"

data class RoomGroup(val roomKey: String, val cabinets: List<CabinetCount>, val qty: Int)

/** One CV row with its cabinets resolved to rooms. [groups] is never empty, in room order. */
data class ResolvedCutListRow(
    val material: String,
    val qty: Int,
    val description: String,
    val width: String,
    val length: String,
    val cabinetText: String,
    val groups: List<RoomGroup>,
)

fun resolveRow(row: HardwoodCutlistRow, cabinetRooms: Map<String, String>): ResolvedCutListRow {
    // Gson can leave String/List fields null; keep the orEmpty() calls.
    val rawText = row.rawCabinetText.orEmpty()
    val counts = parseCabinetText(rawText).ifEmpty {
        row.cabinets.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.map { CabinetCount(it, 1) }
    }
    val byRoom = counts.groupBy { cabinetRooms[it.cabinet] ?: UNASSIGNED_ROOM_KEY }
    val groups = when (byRoom.size) {
        0 -> listOf(RoomGroup(UNASSIGNED_ROOM_KEY, emptyList(), row.qty))
        1 -> byRoom.entries.single().let { (room, cabs) -> listOf(RoomGroup(room, cabs, row.qty)) }
        else -> byRoom.keys.sortedWith(roomKeyComparator).map { room ->
            val cabs = byRoom.getValue(room)
            RoomGroup(room, cabs, cabs.sumOf { it.count })
        }
    }
    return ResolvedCutListRow(
        material = row.material?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_MATERIAL,
        qty = row.qty,
        description = row.description.orEmpty(),
        width = row.width.orEmpty(),
        length = row.length.orEmpty(),
        cabinetText = rawText.ifBlank { formatCabinets(counts) },
        groups = groups,
    )
}

/** Everything loaded once per print-sheet open. [rows] are sheet rows in CV order. */
data class DoorPanelCutListSource(
    val jobFolderName: String,
    val rows: List<ResolvedCutListRow>,
) {
    val materials: List<String> = rows.map { it.material }.distinct()
    val roomKeys: List<String> =
        rows.flatMap { r -> r.groups.map { it.roomKey } }.distinct().sortedWith(roomKeyComparator)
}

data class CutListSelection(
    val roomTags: Boolean,
    val materials: Set<String>,
    val rooms: Set<String>,
)

fun isDefaultUncheckedMaterial(material: String): Boolean = material.contains("MDF", ignoreCase = true)

fun defaultSelection(source: DoorPanelCutListSource): CutListSelection = CutListSelection(
    roomTags = false,
    materials = source.materials.filterNot(::isDefaultUncheckedMaterial).toSet(),
    rooms = source.roomKeys.toSet(),
)

data class MaterialOption(val material: String, val pieces: Int, val checked: Boolean)
data class RoomOption(val key: String, val displayName: String, val pieces: Int, val checked: Boolean)

private fun ResolvedCutListRow.qtyIn(rooms: Set<String>): Int =
    groups.filter { it.roomKey in rooms }.sumOf { it.qty }

/** Material checkbox rows; piece counts exclude unchecked rooms. */
fun materialOptions(source: DoorPanelCutListSource, selection: CutListSelection): List<MaterialOption> =
    source.materials.map { material ->
        MaterialOption(
            material = material,
            pieces = source.rows.filter { it.material == material }.sumOf { it.qtyIn(selection.rooms) },
            checked = material in selection.materials,
        )
    }

/** Room checkbox rows; piece counts exclude unchecked materials. */
fun roomOptions(source: DoorPanelCutListSource, selection: CutListSelection): List<RoomOption> =
    source.roomKeys.map { key ->
        RoomOption(
            key = key,
            displayName = roomDisplayName(key),
            pieces = source.rows.filter { it.material in selection.materials }.sumOf { it.qtyIn(setOf(key)) },
            checked = key in selection.rooms,
        )
    }

fun showRoomFilter(source: DoorPanelCutListSource): Boolean = source.roomKeys.size > 1

val ROOM_PALETTE: List<Int> = listOf(
    0xFFF6C85F, 0xFF9FD8A0, 0xFF8EC5F0, 0xFFF4A3A3, 0xFFC7A6EA,
    0xFF7FD3CF, 0xFFF7B077, 0xFFE9A6D2, 0xFFC9D66B, 0xFFB5B5E8,
).map { it.toInt() }

val UNASSIGNED_ROOM_COLOR: Int = 0xFFD9D9D9.toInt()

/**
 * Distinct color per room, shuffled per job so neighbouring jobs look different, but stable for
 * the same job on every tablet (String.hashCode and java.util.Random are both specified).
 */
fun assignRoomColors(roomKeys: List<String>, seed: String): Map<String, Int> {
    val palette = ROOM_PALETTE.toMutableList()
    java.util.Collections.shuffle(palette, java.util.Random(seed.hashCode().toLong()))
    val result = linkedMapOf<String, Int>()
    roomKeys.distinct()
        .filter { it != UNASSIGNED_ROOM_KEY }
        .sortedWith(roomKeyComparator)
        .forEachIndexed { i, key -> result[key] = palette[i % palette.size] }
    result[UNASSIGNED_ROOM_KEY] = UNASSIGNED_ROOM_COLOR
    return result
}

data class CutListRow(
    val qty: Int,
    val description: String,
    val width: String,
    val length: String,
    val cabinetText: String,
    /** Set only in Room Tags mode. */
    val roomKey: String?,
)

data class CutListMaterialSection(val material: String, val unitsLabel: String, val rows: List<CutListRow>)

data class CutListRoom(val key: String, val displayName: String, val color: Int)

data class DoorPanelCutListModel(
    val jobTitle: String,
    val dateText: String,
    val roomTags: Boolean,
    /** Checked room display names for the header note; empty when every room is checked. */
    val filteredRoomNames: List<String>,
    /** Room Tags only: rooms present in the output, room order, with their colors. */
    val rooms: List<CutListRoom>,
    val materials: List<CutListMaterialSection>,
) {
    val totalPieces: Int get() = materials.sumOf { section -> section.rows.sumOf { it.qty } }
    val canPrint: Boolean get() = materials.any { it.rows.isNotEmpty() }
}

fun cutListJobNumber(jobFolderName: String): String = jobFolderName.substringBefore(" - ").trim()

fun buildCutListModel(
    source: DoorPanelCutListSource,
    selection: CutListSelection,
    dateText: String,
): DoorPanelCutListModel {
    val sections = source.materials
        .filter { it in selection.materials }
        .mapNotNull { material ->
            val rows = source.rows
                .filter { it.material == material }
                .flatMap { outputRows(it, selection) }
            rows.takeIf { it.isNotEmpty() }?.let { CutListMaterialSection(material, "Sheet", it) }
        }
    val colors = assignRoomColors(source.roomKeys, source.jobFolderName)
    val usedRooms = if (selection.roomTags) {
        sections.flatMap { s -> s.rows.mapNotNull { it.roomKey } }
            .distinct()
            .sortedWith(roomKeyComparator)
            .map { CutListRoom(it, roomDisplayName(it), colors[it] ?: UNASSIGNED_ROOM_COLOR) }
    } else {
        emptyList()
    }
    val filteredRoomNames = if (source.roomKeys.all { it in selection.rooms }) {
        emptyList()
    } else {
        source.roomKeys.filter { it in selection.rooms }.map(::roomDisplayName)
    }
    return DoorPanelCutListModel(
        jobTitle = source.jobFolderName,
        dateText = dateText,
        roomTags = selection.roomTags,
        filteredRoomNames = filteredRoomNames,
        rooms = usedRooms,
        materials = sections,
    )
}

private fun outputRows(row: ResolvedCutListRow, selection: CutListSelection): List<CutListRow> {
    val kept = row.groups.filter { it.roomKey in selection.rooms }
    if (kept.isEmpty()) return emptyList()
    fun make(qty: Int, cabinetText: String, roomKey: String?) =
        CutListRow(qty, row.description, row.width, row.length, cabinetText, roomKey)
    return when {
        selection.roomTags -> kept.map { group ->
            val text = if (row.groups.size == 1) row.cabinetText else formatCabinets(group.cabinets)
            make(group.qty, text, group.roomKey)
        }
        kept.size == row.groups.size -> listOf(make(row.qty, row.cabinetText, null))
        else -> listOf(make(kept.sumOf { it.qty }, formatCabinets(kept.flatMap { it.cabinets }), null))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.doorcutlist.DoorPanelCutListDataTest"`
Expected: PASS (all Task 1 + Task 2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListData.kt app/src/test/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListDataTest.kt
git commit -m "feat(print): door cut list room split, filters, and model

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Source loader from cutlist_index.json

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListData.kt` (append)
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListDataTest.kt` (append)

**Interfaces:**
- Consumes: `parseDoorCutUnitTypeMetadata(raw: String?)`, `filterDoorCutRowsToSheets(rows, metadata)`, `loadHardwoodsCutlistIndexRawJson(basePath: String, jobFolderName: String): String?` (all `com.kkc.sheettracker.data`, in `DoorCutSheetFilter.kt`); `HardwoodCutlistIndex`, `HardwoodDocType`; Task 1–2 `buildCabinetRoomMap`, `resolveRow`, `DoorPanelCutListSource`.
- Produces:
  - `fun buildDoorPanelCutListSource(jobFolderName: String, rawCutlistIndexJson: String?, cabinetIndex: CabinetSheetIndex?): DoorPanelCutListSource?`
  - `fun loadDoorPanelCutListSource(basePath: String, jobFolderName: String, cabinetIndex: CabinetSheetIndex?): DoorPanelCutListSource?`

- [ ] **Step 1: Write the failing tests**

Append inside `class DoorPanelCutListDataTest`:

```kotlin
    // ---- buildDoorPanelCutListSource (JSON) ----

    private fun rowJson(
        id: String, page: Int, ordinal: Int, qty: Int, cab: String, material: String, unit: String,
        width: String = "10.0",
    ) = """{"rowId":"$id","page":$page,"rowOrdinal":$ordinal,"qty":$qty,"description":"Door Flat Panel 1A",""" +
        """"width":"$width","length":"20.0","cabinets":[],"rawCabinetText":"$cab","material":"$material","unitType":"$unit"}"""

    private fun cutlistJson(vararg rows: String) =
        """{"documents":[{"docType":"DOOR_CUT_LIST","pdfFilename":"x.pdf","pageCount":2,"rows":[${rows.joinToString(",")}]}]}"""

    @Test
    fun `source keeps sheet rows only in page then ordinal order`() {
        val json = cutlistJson(
            rowJson("DOOR_CUT_LIST:2:0", 2, 0, 1, "20", "1/4 MDF", "SHEETS", width = "8.0"),
            rowJson("DOOR_CUT_LIST:1:1", 1, 1, 1, "20", "1/4 MDF", "SHEETS", width = "9.0"),
            rowJson("DOOR_CUT_LIST:1:0", 1, 0, 1, "20", "1/4 MDF", "SHEETS", width = "9.5"),
            rowJson("DOOR_CUT_LIST:1:2", 1, 2, 2, "20 (2)", "3/4 Paint Grade Wood", "BD_FT"),
        )
        val source = buildDoorPanelCutListSource("684 - X", json, null)!!
        assertEquals(listOf("9.5", "9.0", "8.0"), source.rows.map { it.width })
        assertEquals(listOf("1/4 MDF"), source.materials)
        assertEquals(listOf(UNASSIGNED_ROOM_KEY), source.roomKeys) // no cabinet index
    }

    @Test
    fun `source is null when json missing corrupt or without sheet rows`() {
        assertNull(buildDoorPanelCutListSource("x", null, null))
        assertNull(buildDoorPanelCutListSource("x", "   ", null))
        assertNull(buildDoorPanelCutListSource("x", "{not json", null))
        assertNull(buildDoorPanelCutListSource("x", """{"documents":[]}""", null))
        assertNull(
            buildDoorPanelCutListSource(
                "x", cutlistJson(rowJson("DOOR_CUT_LIST:1:0", 1, 0, 1, "1", "3/4 Oak", "BD_FT")), null
            )
        )
        // No unitType anywhere → same rule as SpecialtyDoorPanelsScreen: hidden.
        val noUnit = cutlistJson(rowJson("DOOR_CUT_LIST:1:0", 1, 0, 1, "1", "1/4 MDF", "SHEETS"))
            .replace(""","unitType":"SHEETS"""", "")
        assertNull(buildDoorPanelCutListSource("x", noUnit, null))
    }

    @Test
    fun `source resolves rooms from cabinet index`() {
        val json = cutlistJson(rowJson("DOOR_CUT_LIST:1:0", 1, 0, 2, "63, 64", "3/4 DOUBLE FUMED", "SHEETS"))
        val index = CabinetSheetIndex(
            documents = CabinetSheetIndexDocuments(
                assembly = ReferenceDocumentIndex(
                    virtualCombined = AssemblyVirtualCombinedIndex(pageDetails = pages(page("Room #5 (CLOSET)", "63", "64")))
                )
            )
        )
        val source = buildDoorPanelCutListSource("669 - X", json, index)!!
        assertEquals(listOf("Room #5 (CLOSET)"), source.roomKeys)
        assertEquals(2, source.rows.single().groups.single().qty)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.doorcutlist.DoorPanelCutListDataTest"`
Expected: compilation FAIL — `Unresolved reference: buildDoorPanelCutListSource`.

- [ ] **Step 3: Write the implementation**

Append to `DoorPanelCutListData.kt` (add imports `com.google.gson.Gson`, `com.kkc.sheettracker.data.filterDoorCutRowsToSheets`, `com.kkc.sheettracker.data.loadHardwoodsCutlistIndexRawJson`, `com.kkc.sheettracker.data.parseDoorCutUnitTypeMetadata`, `com.kkc.sheettracker.data.models.HardwoodCutlistIndex`, `com.kkc.sheettracker.data.models.HardwoodDocType`):

```kotlin
private val cutListGson = Gson()

/**
 * Sheet-unit Door Cut List rows (same rule as the Specialty door panels screen) in CV order,
 * resolved to rooms. Null when there is nothing printable; callers hide the section.
 */
fun buildDoorPanelCutListSource(
    jobFolderName: String,
    rawCutlistIndexJson: String?,
    cabinetIndex: CabinetSheetIndex?,
): DoorPanelCutListSource? {
    if (rawCutlistIndexJson.isNullOrBlank()) return null
    val index = runCatching { cutListGson.fromJson(rawCutlistIndexJson, HardwoodCutlistIndex::class.java) }
        .getOrNull() ?: return null
    val doorDoc = index.documents.orEmpty().firstOrNull { it.docType == HardwoodDocType.DOOR_CUT_LIST }
        ?: return null
    val unitTypes = parseDoorCutUnitTypeMetadata(rawCutlistIndexJson)
    val sheetRows = filterDoorCutRowsToSheets(doorDoc.rows.orEmpty(), unitTypes)
        .sortedWith(compareBy({ it.page }, { it.rowOrdinal }))
    if (sheetRows.isEmpty()) return null
    val cabinetRooms = buildCabinetRoomMap(cabinetIndex)
    return DoorPanelCutListSource(jobFolderName, sheetRows.map { resolveRow(it, cabinetRooms) })
}

/** File I/O wrapper; call on Dispatchers.IO. */
fun loadDoorPanelCutListSource(
    basePath: String,
    jobFolderName: String,
    cabinetIndex: CabinetSheetIndex?,
): DoorPanelCutListSource? = buildDoorPanelCutListSource(
    jobFolderName = jobFolderName,
    rawCutlistIndexJson = loadHardwoodsCutlistIndexRawJson(basePath, jobFolderName),
    cabinetIndex = cabinetIndex,
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.doorcutlist.DoorPanelCutListDataTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListData.kt app/src/test/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListDataTest.kt
git commit -m "feat(print): load door cut list source from cutlist index

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Layout and pagination

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListLayout.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListLayoutTest.kt`

**Interfaces:**
- Consumes (Task 2): `DoorPanelCutListModel`, `CutListMaterialSection`, `CutListRow`, `CutListRoom`, `UNASSIGNED_ROOM_COLOR`, `roomDisplayName`, `cutListJobNumber`.
- Produces:
  - `interface TextMeasurer { fun width(text: String, sizePt: Float, bold: Boolean): Float }`
  - `enum class CellAlign { LEFT, CENTER, RIGHT }`, `enum class ColumnKey { QTY, DESCRIPTION, WIDTH, STAR, LENGTH, CABINET, ROOM }`
  - `data class CutListColumn(val key: ColumnKey, val title: String, val widthPt: Float, val align: CellAlign)`
  - `object CutListGeometry` (constants below), `object CutListColors` (colors below)
  - `fun cutListColumns(roomTags: Boolean): List<CutListColumn>`
  - `fun wrapText(text: String, maxWidth: Float, sizePt: Float, bold: Boolean, measurer: TextMeasurer): List<String>`
  - `fun headerBandText(model: DoorPanelCutListModel): String`
  - `sealed interface CutListBlock { val top: Float; val height: Float }` with `TitleBlock`, `LegendBlock`, `LegendChip`, `MaterialHeaderBlock`, `TableHeaderBlock`, `RowBlock`, `FooterBlock`
  - `data class CutListPage(val pageNumber: Int, val blocks: List<CutListBlock>)`
  - `fun layoutDoorPanelCutList(model: DoorPanelCutListModel, measurer: TextMeasurer): List<CutListPage>`

- [ ] **Step 1: Write the failing tests**

Create `DoorPanelCutListLayoutTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.components.doorcutlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorPanelCutListLayoutTest {

    /** Fixed advance: half the point size per character. */
    private val measurer = object : TextMeasurer {
        override fun width(text: String, sizePt: Float, bold: Boolean): Float = text.length * sizePt * 0.5f
    }

    private fun rows(n: Int, width: (Int) -> String = { "10.0" }, cabinet: String = "12", room: String? = null) =
        List(n) { i -> CutListRow(1, "Door Flat Panel 1A", width(i), "20.0", cabinet, room) }

    private fun model(
        vararg sections: CutListMaterialSection,
        roomTags: Boolean = false,
        rooms: List<CutListRoom> = emptyList(),
        filtered: List<String> = emptyList(),
    ) = DoorPanelCutListModel(
        jobTitle = "669 - WIECHERT 3146 NW CROSSINGS",
        dateText = "1 October, 2026",
        roomTags = roomTags,
        filteredRoomNames = filtered,
        rooms = rooms,
        materials = sections.toList(),
    )

    private fun section(name: String, rows: List<CutListRow>) = CutListMaterialSection(name, "Sheet", rows)

    @Test
    fun `column sets share the same table width`() {
        val std = cutListColumns(false).sumOf { it.widthPt.toDouble() }
        val tags = cutListColumns(true).sumOf { it.widthPt.toDouble() }
        assertEquals(CutListGeometry.TABLE_WIDTH.toDouble(), std, 0.01)
        assertEquals(std, tags, 0.01)
        assertEquals(ColumnKey.ROOM, cutListColumns(true).last().key)
    }

    @Test
    fun `wrapText splits on spaces within width`() {
        // 8.5pt → 4.25pt per char; 40pt fits 9 chars.
        assertEquals(listOf("17 (2),", "18 (2),", "19 (4),", "20 (2)"), wrapText("17 (2), 18 (2), 19 (4), 20 (2)", 40f, 8.5f, false, measurer))
        assertEquals(listOf(""), wrapText("", 40f, 8.5f, false, measurer))
    }

    @Test
    fun `header band text includes mode and filtered rooms`() {
        assertEquals(
            "669 - WIECHERT 3146 NW CROSSINGS · 1 October, 2026 · Standard",
            headerBandText(model())
        )
        assertEquals(
            "669 - WIECHERT 3146 NW CROSSINGS · 1 October, 2026 · Room Tags · Rooms: KITCHEN, PANTRY",
            headerBandText(model(roomTags = true, filtered = listOf("KITCHEN", "PANTRY")))
        )
    }

    @Test
    fun `table header repeats on continuation pages`() {
        val pages = layoutDoorPanelCutList(model(section("1/4 MDF", rows(90))), measurer)
        assertTrue(pages.size >= 2)
        pages.drop(1).forEach { page ->
            val first = page.blocks.first()
            assertTrue(first is TableHeaderBlock && first.continued)
        }
        assertEquals(90, pages.sumOf { p -> p.blocks.count { it is RowBlock } })
    }

    @Test
    fun `material header is never orphaned and rows never cross the content bottom`() {
        for (firstCount in 20..45) {
            val pages = layoutDoorPanelCutList(
                model(section("A", rows(firstCount)), section("B", rows(10))), measurer
            )
            pages.forEach { page ->
                page.blocks.forEachIndexed { i, block ->
                    if (block is MaterialHeaderBlock) {
                        val after = page.blocks.drop(i + 1)
                        assertTrue("firstCount=$firstCount", after.firstOrNull() is TableHeaderBlock)
                        assertTrue("firstCount=$firstCount", after.drop(1).take(2).all { it is RowBlock })
                    }
                    if (block is RowBlock) {
                        assertTrue(block.top + block.height <= CutListGeometry.CONTENT_BOTTOM + 0.01f)
                    }
                }
            }
        }
    }

    @Test
    fun `long cabinet text wraps and grows the row`() {
        val long = "17 (2), 18 (2), 19 (4), 20 (2), 21 (2), 22 (2), 23 (2), 24 (2), 25 (2), 26 (2)"
        val pages = layoutDoorPanelCutList(model(section("A", rows(1, cabinet = long))), measurer)
        val row = pages.single().blocks.filterIsInstance<RowBlock>().single()
        assertTrue(row.cells.getValue(ColumnKey.CABINET).size > 1)
        assertTrue(row.height > CutListGeometry.ROW_MIN_HEIGHT)
    }

    @Test
    fun `width bands cycle per run and advance at each new table`() {
        val widths = listOf("26.0", "26.0", "15.8", "13.4", "13.4")
        val pages = layoutDoorPanelCutList(
            model(section("A", rows(5, width = { widths[it] })), section("B", rows(1, width = { "13.4" }))),
            measurer
        )
        val bands = pages.flatMap { it.blocks }.filterIsInstance<RowBlock>().map { it.widthBandColor }
        val w = CutListColors.WIDTH_BANDS
        assertEquals(listOf(w[0], w[0], w[1], w[2], w[2], w[3]), bands)
    }

    @Test
    fun `room tags rows have room color and no width band`() {
        val kitchen = CutListRoom("Room #1 (KITCHEN)", "KITCHEN", 0xFFF6C85F.toInt())
        val pages = layoutDoorPanelCutList(
            model(
                section("A", rows(2, room = "Room #1 (KITCHEN)") + rows(1, room = "Unassigned")),
                roomTags = true,
                rooms = listOf(kitchen),
            ),
            measurer
        )
        val rowBlocks = pages.flatMap { it.blocks }.filterIsInstance<RowBlock>()
        rowBlocks.forEach { assertNull(it.widthBandColor) }
        assertEquals(listOf(kitchen.color, kitchen.color, UNASSIGNED_ROOM_COLOR), rowBlocks.map { it.roomColor })
        assertEquals(listOf("KITCHEN"), rowBlocks.first().cells.getValue(ColumnKey.ROOM))
        val legend = pages.first().blocks.filterIsInstance<LegendBlock>().single()
        assertEquals(listOf("KITCHEN"), legend.chips.map { it.label })
    }

    @Test
    fun `rows alternate shading starting shaded and continue across pages`() {
        val pages = layoutDoorPanelCutList(model(section("A", rows(90))), measurer)
        val shading = pages.flatMap { it.blocks }.filterIsInstance<RowBlock>().map { it.shaded }
        assertEquals(List(90) { it % 2 == 0 }, shading)
    }

    @Test
    fun `every page has a numbered footer`() {
        val pages = layoutDoorPanelCutList(model(section("A", rows(90))), measurer)
        pages.forEachIndexed { i, page ->
            val footer = page.blocks.last() as FooterBlock
            assertEquals("669 · Door Cut List · Page ${i + 1} of ${pages.size}", footer.text)
            assertEquals(i + 1, page.pageNumber)
        }
        assertTrue(pages.first().blocks.first() is TitleBlock)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.doorcutlist.DoorPanelCutListLayoutTest"`
Expected: compilation FAIL — `Unresolved reference: TextMeasurer` etc.

- [ ] **Step 3: Write the implementation**

Create `DoorPanelCutListLayout.kt`:

```kotlin
package com.kkc.sheettracker.ui.components.doorcutlist

import kotlin.math.max

/**
 * Pure layout for the Door Panel Cut List PDF: decides every block's page and position so the
 * Canvas painter only draws. Units are PDF points (72 per inch). No android.* imports.
 */

interface TextMeasurer {
    fun width(text: String, sizePt: Float, bold: Boolean): Float
}

enum class CellAlign { LEFT, CENTER, RIGHT }

enum class ColumnKey { QTY, DESCRIPTION, WIDTH, STAR, LENGTH, CABINET, ROOM }

data class CutListColumn(val key: ColumnKey, val title: String, val widthPt: Float, val align: CellAlign)

object CutListGeometry {
    const val PAGE_WIDTH = 612f
    const val PAGE_HEIGHT = 792f
    const val MARGIN_X = 72f
    const val MARGIN_TOP = 40f
    const val MARGIN_BOTTOM = 40f
    const val TABLE_WIDTH = 467f
    const val FOOTER_HEIGHT = 16f
    const val CONTENT_BOTTOM = PAGE_HEIGHT - MARGIN_BOTTOM - FOOTER_HEIGHT

    const val TITLE_SIZE = 26f
    const val TITLE_HEIGHT = 34f
    const val BAND_SIZE = 8.5f
    const val BAND_LINE_HEIGHT = 12f
    const val BAND_PADDING = 3f
    const val SECTION_GAP = 6f

    const val LEGEND_CHIP_WIDTH = 92f
    const val LEGEND_CHIP_HEIGHT = 18f
    const val LEGEND_GAP = 2f

    const val MATERIAL_SIZE = 12f
    const val MATERIAL_HEIGHT = 27f
    const val HEADER_ROW_HEIGHT = 17f

    const val CELL_SIZE = 8.5f
    const val ROOM_CELL_SIZE = 7.5f
    const val ROW_MIN_HEIGHT = 19f
    const val ROW_LINE_HEIGHT = 10.5f
    const val ROW_VPAD = 4f
    const val CELL_HPAD = 5f

    const val FOOTER_SIZE = 7.5f
}

object CutListColors {
    val NAVY: Int = 0xFF16375E.toInt()
    val TEXT: Int = 0xFF1F2D3D.toInt()
    val FOOTER: Int = 0xFF6B7785.toInt()
    val BAND: Int = 0xFFF3F3F3.toInt()
    val ROW_SHADE: Int = 0xFFEBEFF6.toInt()
    val WHITE: Int = 0xFFFFFFFF.toInt()
    /** CV's Door Cut List width shading, cycled per run of equal widths. */
    val WIDTH_BANDS: List<Int> =
        listOf(0xFFEADBC8, 0xFFDDEAFB, 0xFFFCE4E4, 0xFFF1ECFB, 0xFFE4F7E4).map { it.toInt() }
}

fun cutListColumns(roomTags: Boolean): List<CutListColumn> = if (!roomTags) {
    listOf(
        CutListColumn(ColumnKey.QTY, "Qty", 34f, CellAlign.CENTER),
        CutListColumn(ColumnKey.DESCRIPTION, "Description", 140f, CellAlign.LEFT),
        CutListColumn(ColumnKey.WIDTH, "Width", 78f, CellAlign.RIGHT),
        CutListColumn(ColumnKey.STAR, "*", 16f, CellAlign.CENTER),
        CutListColumn(ColumnKey.LENGTH, "Length", 92f, CellAlign.LEFT),
        CutListColumn(ColumnKey.CABINET, "Cabinet (Qty)", 107f, CellAlign.RIGHT),
    )
} else {
    listOf(
        CutListColumn(ColumnKey.QTY, "Qty", 28f, CellAlign.CENTER),
        CutListColumn(ColumnKey.DESCRIPTION, "Description", 116f, CellAlign.LEFT),
        CutListColumn(ColumnKey.WIDTH, "Width", 60f, CellAlign.RIGHT),
        CutListColumn(ColumnKey.STAR, "*", 12f, CellAlign.CENTER),
        CutListColumn(ColumnKey.LENGTH, "Length", 62f, CellAlign.LEFT),
        CutListColumn(ColumnKey.CABINET, "Cabinet (Qty)", 97f, CellAlign.RIGHT),
        CutListColumn(ColumnKey.ROOM, "Room", 92f, CellAlign.CENTER),
    )
}

/** Greedy word wrap on spaces. A single word wider than [maxWidth] gets its own line. */
fun wrapText(text: String, maxWidth: Float, sizePt: Float, bold: Boolean, measurer: TextMeasurer): List<String> {
    val words = text.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf("")
    val lines = mutableListOf<String>()
    var current = ""
    for (word in words) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (current.isEmpty() || measurer.width(candidate, sizePt, bold) <= maxWidth) {
            current = candidate
        } else {
            lines += current
            current = word
        }
    }
    lines += current
    return lines
}

fun headerBandText(model: DoorPanelCutListModel): String = buildString {
    append(model.jobTitle).append(" · ").append(model.dateText).append(" · ")
    append(if (model.roomTags) "Room Tags" else "Standard")
    if (model.filteredRoomNames.isNotEmpty()) {
        append(" · Rooms: ").append(model.filteredRoomNames.joinToString(", "))
    }
}

sealed interface CutListBlock {
    val top: Float
    val height: Float
}

data class TitleBlock(
    override val top: Float,
    override val height: Float,
    val title: String,
    val bandLines: List<String>,
) : CutListBlock

data class LegendChip(val label: String, val color: Int, val left: Float, val top: Float, val width: Float, val height: Float)

data class LegendBlock(override val top: Float, override val height: Float, val chips: List<LegendChip>) : CutListBlock

data class MaterialHeaderBlock(override val top: Float, override val height: Float, val text: String) : CutListBlock

data class TableHeaderBlock(
    override val top: Float,
    override val height: Float,
    val columns: List<CutListColumn>,
    val continued: Boolean,
) : CutListBlock

data class RowBlock(
    override val top: Float,
    override val height: Float,
    val columns: List<CutListColumn>,
    /** Wrapped lines per column. */
    val cells: Map<ColumnKey, List<String>>,
    val shaded: Boolean,
    /** Standard mode only. */
    val widthBandColor: Int?,
    /** Room Tags mode only. */
    val roomColor: Int?,
) : CutListBlock

data class FooterBlock(override val top: Float, override val height: Float, val text: String) : CutListBlock

data class CutListPage(val pageNumber: Int, val blocks: List<CutListBlock>)

private data class PreparedRow(
    val cells: Map<ColumnKey, List<String>>,
    val height: Float,
    val widthBandColor: Int?,
    val roomColor: Int?,
)

fun layoutDoorPanelCutList(model: DoorPanelCutListModel, measurer: TextMeasurer): List<CutListPage> {
    val g = CutListGeometry
    val columns = cutListColumns(model.roomTags)
    val roomsByKey = model.rooms.associateBy { it.key }
    val pages = mutableListOf(mutableListOf<CutListBlock>())
    var y = g.MARGIN_TOP

    fun newPage() {
        pages += mutableListOf<CutListBlock>()
        y = g.MARGIN_TOP
    }
    fun add(block: CutListBlock) {
        pages.last() += block
        y = block.top + block.height
    }

    val bandLines = wrapText(headerBandText(model), g.TABLE_WIDTH - 2 * g.BAND_PADDING, g.BAND_SIZE, false, measurer)
    add(
        TitleBlock(
            top = y,
            height = g.TITLE_HEIGHT + bandLines.size * g.BAND_LINE_HEIGHT + 2 * g.BAND_PADDING + g.SECTION_GAP,
            title = "Door Cut List",
            bandLines = bandLines,
        )
    )

    if (model.roomTags && model.rooms.isNotEmpty()) {
        val perRow = max(1, (g.TABLE_WIDTH / g.LEGEND_CHIP_WIDTH).toInt())
        val legendTop = y
        val chips = model.rooms.mapIndexed { i, room ->
            LegendChip(
                label = room.displayName,
                color = room.color,
                left = g.MARGIN_X + (i % perRow) * g.LEGEND_CHIP_WIDTH,
                top = legendTop + (i / perRow) * (g.LEGEND_CHIP_HEIGHT + g.LEGEND_GAP),
                width = g.LEGEND_CHIP_WIDTH - g.LEGEND_GAP,
                height = g.LEGEND_CHIP_HEIGHT,
            )
        }
        val chipRows = (model.rooms.size + perRow - 1) / perRow
        add(LegendBlock(legendTop, chipRows * (g.LEGEND_CHIP_HEIGHT + g.LEGEND_GAP) + g.SECTION_GAP, chips))
    }

    var bandIndex = -1
    model.materials.forEach { section ->
        val prepared = section.rows.mapIndexed { i, row ->
            if (i == 0 || row.width != section.rows[i - 1].width) bandIndex++
            prepareRow(
                row = row,
                columns = columns,
                measurer = measurer,
                widthBandColor = if (model.roomTags) null else CutListColors.WIDTH_BANDS[bandIndex % CutListColors.WIDTH_BANDS.size],
                room = row.roomKey?.let { key -> roomsByKey[key] ?: CutListRoom(key, roomDisplayName(key), UNASSIGNED_ROOM_COLOR) },
            )
        }
        // Never orphan a material header: it needs its column header and up to 2 rows below it.
        val needed = g.MATERIAL_HEIGHT + g.HEADER_ROW_HEIGHT + prepared.take(2).sumOf { it.height.toDouble() }.toFloat()
        if (y > g.MARGIN_TOP && y + needed > g.CONTENT_BOTTOM) newPage()
        add(MaterialHeaderBlock(y, g.MATERIAL_HEIGHT, "Material: '${section.material}'  |  Units: ${section.unitsLabel}"))
        add(TableHeaderBlock(y, g.HEADER_ROW_HEIGHT, columns, continued = false))
        prepared.forEachIndexed { i, row ->
            if (y + row.height > g.CONTENT_BOTTOM) {
                newPage()
                add(TableHeaderBlock(y, g.HEADER_ROW_HEIGHT, columns, continued = true))
            }
            add(RowBlock(y, row.height, columns, row.cells, shaded = i % 2 == 0, row.widthBandColor, row.roomColor))
        }
    }

    val jobNumber = cutListJobNumber(model.jobTitle)
    val footerTop = g.PAGE_HEIGHT - g.MARGIN_BOTTOM - g.FOOTER_HEIGHT + 4f
    return pages.mapIndexed { i, blocks ->
        CutListPage(
            pageNumber = i + 1,
            blocks = blocks + FooterBlock(footerTop, g.FOOTER_HEIGHT - 4f, "$jobNumber · Door Cut List · Page ${i + 1} of ${pages.size}"),
        )
    }
}

private fun prepareRow(
    row: CutListRow,
    columns: List<CutListColumn>,
    measurer: TextMeasurer,
    widthBandColor: Int?,
    room: CutListRoom?,
): PreparedRow {
    val g = CutListGeometry
    val cells = columns.associate { column ->
        val text = when (column.key) {
            ColumnKey.QTY -> row.qty.toString()
            ColumnKey.DESCRIPTION -> row.description
            ColumnKey.WIDTH -> row.width
            ColumnKey.STAR -> "*"
            ColumnKey.LENGTH -> row.length
            ColumnKey.CABINET -> row.cabinetText
            ColumnKey.ROOM -> room?.displayName.orEmpty()
        }
        val bold = column.key == ColumnKey.ROOM
        val size = if (bold) g.ROOM_CELL_SIZE else g.CELL_SIZE
        val lines = when (column.key) {
            ColumnKey.DESCRIPTION, ColumnKey.CABINET, ColumnKey.ROOM ->
                wrapText(text, column.widthPt - 2 * g.CELL_HPAD, size, bold, measurer)
            else -> listOf(text)
        }
        column.key to lines
    }
    val maxLines = cells.values.maxOf { it.size }
    return PreparedRow(
        cells = cells,
        height = max(g.ROW_MIN_HEIGHT, maxLines * g.ROW_LINE_HEIGHT + 2 * g.ROW_VPAD),
        widthBandColor = widthBandColor,
        roomColor = room?.color,
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.doorcutlist.*"`
Expected: PASS (data + layout tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListLayout.kt app/src/test/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListLayoutTest.kt
git commit -m "feat(print): door cut list page layout and pagination

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: PDF painter and print hand-off

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListPdf.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PrintDocumentsBottomSheet.kt:320-322` (`printPdfFile` signature)

**Interfaces:**
- Consumes (Task 4): `TextMeasurer`, `layoutDoorPanelCutList`, all block types, `CutListGeometry`, `CutListColors`, `CellAlign`, `ColumnKey`; (Task 2) `DoorPanelCutListModel`, `cutListJobNumber`.
- Produces:
  - `class PaintTextMeasurer : TextMeasurer`
  - `fun writeDoorPanelCutListPdf(model: DoorPanelCutListModel, out: File): Int` (page count; deletes `out` on failure and rethrows)
  - `fun prepareCutListPrintFile(context: Context, jobFolderName: String): File`
  - `internal fun printPdfFile(context: Context, file: File, jobName: String = "KKC Sheet Tracker - ${file.name}")` in `com.kkc.sheettracker.ui.components`

No JVM unit test is possible (Canvas/PdfDocument are stubs off-device); the layout it draws is fully tested in Task 4. Verification here is compilation; visual check is Task 7.

- [ ] **Step 1: Make `printPdfFile` internal with an optional job name**

In `PrintDocumentsBottomSheet.kt`, replace:

```kotlin
private fun printPdfFile(context: Context, file: File) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val jobName = "KKC Sheet Tracker - ${file.name}"
```

with:

```kotlin
internal fun printPdfFile(
    context: Context,
    file: File,
    jobName: String = "KKC Sheet Tracker - ${file.name}"
) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
```

- [ ] **Step 2: Write the painter and writer**

Create `DoorPanelCutListPdf.kt`:

```kotlin
package com.kkc.sheettracker.ui.components.doorcutlist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/** Android side of the Door Panel Cut List print: measures text and paints laid-out pages. */

class PaintTextMeasurer : TextMeasurer {
    private val regularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    override fun width(text: String, sizePt: Float, bold: Boolean): Float {
        val paint = if (bold) boldPaint else regularPaint
        paint.textSize = sizePt
        return paint.measureText(text)
    }
}

/** Writes the PDF to [out]; returns the page count. Deletes [out] and rethrows on failure. */
fun writeDoorPanelCutListPdf(model: DoorPanelCutListModel, out: File): Int {
    val pages = layoutDoorPanelCutList(model, PaintTextMeasurer())
    val document = PdfDocument()
    try {
        pages.forEach { page ->
            val info = PdfDocument.PageInfo.Builder(
                CutListGeometry.PAGE_WIDTH.toInt(),
                CutListGeometry.PAGE_HEIGHT.toInt(),
                page.pageNumber
            ).create()
            val pdfPage = document.startPage(info)
            CutListPainter(pdfPage.canvas).draw(page)
            document.finishPage(pdfPage)
        }
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { document.writeTo(it) }
        return pages.size
    } catch (e: Exception) {
        out.delete()
        throw e
    } finally {
        document.close()
    }
}

/** cacheDir/print/door_cut_list_{job}.pdf, clearing earlier generated files first. */
fun prepareCutListPrintFile(context: Context, jobFolderName: String): File {
    val dir = File(context.cacheDir, "print").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val safeJob = cutListJobNumber(jobFolderName)
        .ifEmpty { "job" }
        .replace(Regex("""[^A-Za-z0-9_-]"""), "_")
    return File(dir, "door_cut_list_$safeJob.pdf")
}

private class CutListPainter(private val canvas: Canvas) {
    private val g = CutListGeometry
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }

    fun draw(page: CutListPage) {
        page.blocks.forEach { block ->
            when (block) {
                is TitleBlock -> drawTitle(block)
                is LegendBlock -> drawLegend(block)
                is MaterialHeaderBlock -> drawText(
                    block.text, g.MARGIN_X, block.top + 8f + g.MATERIAL_SIZE, g.MATERIAL_SIZE, true,
                    CutListColors.NAVY, CellAlign.LEFT
                )
                is TableHeaderBlock -> drawTableHeader(block)
                is RowBlock -> drawRow(block)
                is FooterBlock -> drawText(
                    block.text, g.MARGIN_X, block.top + g.FOOTER_SIZE + 2f, g.FOOTER_SIZE, false,
                    CutListColors.FOOTER, CellAlign.LEFT
                )
            }
        }
    }

    private fun drawTitle(block: TitleBlock) {
        drawText(block.title, g.MARGIN_X, block.top + g.TITLE_SIZE, g.TITLE_SIZE, true, CutListColors.NAVY, CellAlign.LEFT)
        val bandTop = block.top + g.TITLE_HEIGHT
        val bandBottom = bandTop + block.bandLines.size * g.BAND_LINE_HEIGHT + 2 * g.BAND_PADDING
        drawRect(g.MARGIN_X, bandTop, g.MARGIN_X + g.TABLE_WIDTH, bandBottom, CutListColors.BAND)
        block.bandLines.forEachIndexed { i, line ->
            drawText(
                line, g.MARGIN_X + g.BAND_PADDING,
                bandTop + g.BAND_PADDING + (i + 1) * g.BAND_LINE_HEIGHT - 3f,
                g.BAND_SIZE, false, CutListColors.TEXT, CellAlign.LEFT
            )
        }
    }

    private fun drawLegend(block: LegendBlock) {
        block.chips.forEach { chip ->
            drawRect(chip.left, chip.top, chip.left + chip.width, chip.top + chip.height, chip.color)
            drawText(
                chip.label, chip.left + chip.width / 2f, chip.top + chip.height / 2f + g.ROOM_CELL_SIZE * 0.35f,
                g.ROOM_CELL_SIZE, true, CutListColors.TEXT, CellAlign.CENTER
            )
        }
    }

    private fun drawTableHeader(block: TableHeaderBlock) {
        var x = g.MARGIN_X
        val baseline = block.top + block.height / 2f + g.CELL_SIZE * 0.35f
        block.columns.forEach { column ->
            drawText(column.title, cellX(x, column), baseline, g.CELL_SIZE, false, CutListColors.TEXT, column.align)
            x += column.widthPt
        }
        drawLine(g.MARGIN_X, block.top + block.height, g.MARGIN_X + g.TABLE_WIDTH, block.top + block.height, 0.6f, CutListColors.NAVY)
    }

    private fun drawRow(block: RowBlock) {
        val bottom = block.top + block.height
        if (block.shaded) drawRect(g.MARGIN_X, block.top, g.MARGIN_X + g.TABLE_WIDTH, bottom, CutListColors.ROW_SHADE)
        var x = g.MARGIN_X
        block.columns.forEach { column ->
            val right = x + column.widthPt
            val fillColor = when (column.key) {
                ColumnKey.WIDTH -> block.widthBandColor
                ColumnKey.ROOM -> block.roomColor
                else -> null
            }
            if (fillColor != null) drawRect(x, block.top, right, bottom, fillColor)
            val isRoom = column.key == ColumnKey.ROOM
            val size = if (isRoom) g.ROOM_CELL_SIZE else g.CELL_SIZE
            val lines = block.cells[column.key].orEmpty()
            val firstBaseline = block.top + (block.height - lines.size * g.ROW_LINE_HEIGHT) / 2f + g.ROW_LINE_HEIGHT - 2.5f
            lines.forEachIndexed { i, line ->
                drawText(line, cellX(x, column), firstBaseline + i * g.ROW_LINE_HEIGHT, size, isRoom, CutListColors.TEXT, column.align)
            }
            if (column.key in SEPARATOR_AFTER) drawLine(right, block.top, right, bottom, 1.5f, CutListColors.WHITE)
            x = right
        }
        drawLine(g.MARGIN_X, bottom, g.MARGIN_X + g.TABLE_WIDTH, bottom, 1.2f, CutListColors.WHITE)
    }

    private fun cellX(left: Float, column: CutListColumn): Float = when (column.align) {
        CellAlign.LEFT -> left + g.CELL_HPAD
        CellAlign.CENTER -> left + column.widthPt / 2f
        CellAlign.RIGHT -> left + column.widthPt - g.CELL_HPAD
    }

    private fun drawRect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        fill.color = color
        canvas.drawRect(left, top, right, bottom, fill)
    }

    private fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = width
        canvas.drawLine(x1, y1, x2, y2, stroke)
    }

    private fun drawText(text: String, x: Float, baseline: Float, size: Float, isBold: Boolean, color: Int, align: CellAlign) {
        val paint = if (isBold) bold else regular
        paint.textSize = size
        paint.color = color
        paint.textAlign = when (align) {
            CellAlign.LEFT -> Paint.Align.LEFT
            CellAlign.CENTER -> Paint.Align.CENTER
            CellAlign.RIGHT -> Paint.Align.RIGHT
        }
        canvas.drawText(text, x, baseline, paint)
    }

    private companion object {
        /** White column separators, as in CV's layout. */
        val SEPARATOR_AFTER = setOf(ColumnKey.DESCRIPTION, ColumnKey.WIDTH, ColumnKey.LENGTH, ColumnKey.CABINET)
    }
}
```

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Re-run the feature tests (layout untouched, sanity)**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.doorcutlist.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListPdf.kt app/src/main/java/com/kkc/sheettracker/ui/components/PrintDocumentsBottomSheet.kt
git commit -m "feat(print): paint door cut list layout into PdfDocument

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Compose section in the print sheet

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListSection.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PrintDocumentsBottomSheet.kt:136-142` (insert section after the title `Text`)

**Interfaces:**
- Consumes: `JobRepository.getJobDirectory(jobFolderName: String): File`, `JobRepository.getCabinetSheetIndex(jobFolderName: String): CabinetSheetIndex?`; Task 2–3 data API; Task 5 `prepareCutListPrintFile`, `writeDoorPanelCutListPdf`, `printPdfFile`.
- Produces: `@Composable fun DoorPanelCutListSection(jobFolderName: String, jobRepository: JobRepository, onPrinted: () -> Unit)`

Compose UI is not unit-testable in this project (no Robolectric/compose-test); verification is compilation here and manual checks in Task 7.

- [ ] **Step 1: Write the section**

Create `DoorPanelCutListSection.kt`:

```kotlin
package com.kkc.sheettracker.ui.components.doorcutlist

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.ui.components.printPdfFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DoorPanelCutList"

private sealed interface CutListLoad {
    object Loading : CutListLoad
    object Unavailable : CutListLoad
    data class Ready(val source: DoorPanelCutListSource) : CutListLoad
}

/**
 * "Door Panel Cut List" section at the top of the print sheet. Hidden entirely when the job has
 * no sheet door-panel rows; the file list below never waits on it.
 */
@Composable
fun DoorPanelCutListSection(
    jobFolderName: String,
    jobRepository: JobRepository,
    onPrinted: () -> Unit,
) {
    val load by produceState<CutListLoad>(CutListLoad.Loading, jobFolderName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val basePath = jobRepository.getJobDirectory(jobFolderName).parent.orEmpty()
                val cabinetIndex = runCatching { jobRepository.getCabinetSheetIndex(jobFolderName) }.getOrNull()
                loadDoorPanelCutListSource(basePath, jobFolderName, cabinetIndex)
            }.onFailure { Log.d(TAG, "Door panel cut list unavailable for $jobFolderName", it) }
                .getOrNull()
                ?.let { CutListLoad.Ready(it) }
                ?: CutListLoad.Unavailable
        }
    }
    when (val state = load) {
        CutListLoad.Unavailable -> Unit
        CutListLoad.Loading -> SectionFrame {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Door Panel Cut List",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        is CutListLoad.Ready -> ReadySection(state.source, onPrinted)
    }
}

@Composable
private fun SectionFrame(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "GENERATED",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            content()
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
    }
}

@Composable
private fun ReadySection(source: DoorPanelCutListSource, onPrinted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember(source) { mutableStateOf(false) }
    var selection by remember(source) { mutableStateOf(defaultSelection(source)) }
    var building by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val dateText = remember { SimpleDateFormat("d MMMM, yyyy", Locale.US).format(Date()) }
    val model = remember(source, selection, dateText) { buildCutListModel(source, selection, dateText) }
    val materialOpts = remember(source, selection) { materialOptions(source, selection) }
    val roomOpts = remember(source, selection) { roomOptions(source, selection) }

    SectionFrame {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Door Panel Cut List",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = summaryText(model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CheckRow(label = "Room Tags", trailing = null, checked = selection.roomTags) {
                        selection = selection.copy(roomTags = it)
                    }
                    GroupLabel("Materials")
                    materialOpts.forEach { option ->
                        CheckRow(option.material, "${option.pieces} pcs", option.checked) { checked ->
                            selection = selection.copy(
                                materials = if (checked) selection.materials + option.material
                                else selection.materials - option.material
                            )
                        }
                    }
                    if (showRoomFilter(source)) {
                        GroupLabel("Rooms")
                        roomOpts.forEach { option ->
                            CheckRow(option.displayName, "${option.pieces} pcs", option.checked) { checked ->
                                selection = selection.copy(
                                    rooms = if (checked) selection.rooms + option.key else selection.rooms - option.key
                                )
                            }
                        }
                    }
                    errorText?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            building = true
                            errorText = null
                            val printModel = model
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) {
                                        prepareCutListPrintFile(context, source.jobFolderName).also {
                                            writeDoorPanelCutListPdf(printModel, it)
                                        }
                                    }
                                }
                                building = false
                                result.onSuccess { file ->
                                    printPdfFile(
                                        context,
                                        file,
                                        "KKC Sheet Tracker - ${cutListJobNumber(source.jobFolderName)} Door Cut List"
                                    )
                                    onPrinted()
                                }.onFailure { e ->
                                    Log.e(TAG, "Door cut list build failed for ${source.jobFolderName}", e)
                                    errorText = "Couldn't build cut list. Try again."
                                }
                            }
                        },
                        enabled = model.canPrint && !building,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        if (building) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Print")
                        }
                    }
                }
            }
        }
    }
}

private fun summaryText(model: DoorPanelCutListModel): String {
    val count = model.materials.size
    return "$count material${if (count == 1) "" else "s"} · ${model.totalPieces} pcs"
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun CheckRow(label: String, trailing: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Box(modifier = Modifier.padding(start = 8.dp, end = 4.dp)) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

- [ ] **Step 2: Insert the section into the print sheet**

In `PrintDocumentsBottomSheet.kt`, add import `com.kkc.sheettracker.ui.components.doorcutlist.DoorPanelCutListSection`, then directly after the title block:

```kotlin
            Text(
                text = "Print Job Documents",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
```

insert:

```kotlin

            DoorPanelCutListSection(
                jobFolderName = jobFolderName,
                jobRepository = jobRepository,
                onPrinted = onDismissRequest
            )
```

- [ ] **Step 3: Compile and run feature tests**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.
Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.doorcutlist.*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/doorcutlist/DoorPanelCutListSection.kt app/src/main/java/com/kkc/sheettracker/ui/components/PrintDocumentsBottomSheet.kt
git commit -m "feat(print): Door Panel Cut List section in print sheet

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: On-device verification and release

**Files:** none (verification only; fix-forward in the owning task's files if anything fails).

- [ ] **Step 1: Debug build and install**

```powershell
cd C:\Scripts\KKCSheetTracker
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Expected: `Success`.

- [ ] **Step 2: Ask the user to open the screens**

Ask the user to navigate the tablet to job 669 (`ARCHIVE_Ready Jobs\669 - WIECHERT 3146 NW CROSSINGS` if archived jobs are reachable, else a ready job with multiple rooms such as 684) and open the print sheet from each of: Specialty job detail, Hardwoods job detail, main Job Detail, Assembly job detail. Do not adb-tap through the app; take screenshots (`adb exec-out screencap -p > shot.png`) once the user is on each screen.

Expected on each: "GENERATED" overline, "Door Panel Cut List" row with summary, divider, then the file list unchanged.

- [ ] **Step 3: Exercise the section (user drives, you verify by screenshot)**

Check each item:
- Expanded section shows Room Tags (unchecked), Materials with counts (any MDF unchecked), Rooms with counts (684: KITCHEN 29 / LAUNDRY 6 / VANITIES 18 when MDF is checked).
- Unchecking a room changes material counts and the collapsed summary.
- With all materials unchecked, Print is disabled.
- **Review Focus 4:** on a job with many rooms (592 GIELISH has 5; 644d has 5), the expanded body scrolls inside the sheet and Print stays reachable.
- **Review Focus 5:** double-tap Print quickly — only one Android print screen opens.
- Print Standard and Room Tags (with one room unchecked) to "Save as PDF"; pull the PDFs (`adb pull /sdcard/Download/<name>.pdf`) and compare against the spike PDFs (`669 - Door Cut List - by size.pdf` / `by roomcol.pdf`): same rows and order, width shading in Standard, room colors and legend in Room Tags, header band shows `· Rooms: …` when filtered, footer `669 · Door Cut List · Page X of Y`.
- A job with no door cut list (any job lacking `.metadata/hardwoods/cutlist_index.json`) shows no section and an unchanged file list.

- [ ] **Step 4: Release build to tablets**

Per project memory, run the build and install directly (the `adb-install-release.ps1` script has an encoding problem under `powershell -File`):

```powershell
cd C:\Scripts\KKCSheetTracker
.\gradlew.bat assembleRelease
adb devices
adb -s <serial> install -r app\build\outputs\apk\release\app-release.apk
```

Repeat the install for each connected tablet serial. Expected: `Success` per device.

- [ ] **Step 5: Report**

Report to the user what was verified (with screenshots/PDF comparisons), anything that differed from the spike PDFs, and which tablets received the release build. Do not merge or push the branch without the user's go-ahead.
