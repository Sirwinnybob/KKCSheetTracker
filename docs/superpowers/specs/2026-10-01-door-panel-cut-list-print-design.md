# Door Panel Cut List Print

Date: 2026-10-01
Status: Design approved, not yet planned.
Repo: KKCSheetTracker (this repo). No server, worker, or hub changes.

## Goal

Let the specialty door-panel worker print a door panel cut list from any tablet, generated from
job metadata, in either CV's standard size order or the same order tagged with a colored room
column, filtered to the sheet materials and rooms he needs.

Today he prints `NNN - Door Cut List.pdf` straight from the job folder. That file mixes sheet
panels with board-foot slabs, stiles, and rails, has no room information, and cannot be
filtered.

## Background: spike results (2026-10-01)

A throwaway Python generator (`door_cutlist_print.py`, session scratchpad) proved the approach
on jobs 690, 684, and 669:

- `.metadata/hardwoods/cutlist_index.json` (`DOOR_CUT_LIST` document) holds every CV row
  (qty, description, width, length, cabinets, raw cabinet text, material, unit type). For 690
  all 84 rows match `export.pdf` exactly in order and value, so rows sorted by
  `(page, rowOrdinal)` *are* CV's size order. No re-sorting is needed.
- `.metadata/cabinet_sheet_index.json` maps cabinets to rooms. Jobs with split assembly sets
  (669: FF sheets cover cabinets 1–55, FL sheets cover 56–73) only expose the first PDF in
  top-level `assembly.pageDetails`; `assembly.virtualCombined.pageDetails` covers all of them
  and agrees with Plans & Elevations on every cabinet.
- Rows whose cabinets span rooms exist (669 has 4) and split correctly by per-cabinet counts;
  per-room quantities sum back to CV's material totals.
- CV's totals footer (Width/Length/Rips) is out of scope; the user rejected it.

## Success criteria

- In the print sheet of every screen that uses it (Specialty, Hardwoods, main Job Detail,
  Assembly job detail), a job with sheet door panels shows a **Door Panel Cut List** section
  above the file list.
- Standard mode prints sheet-unit rows in CV's order with CV-style width shading, matching CV's
  Door Cut List row for row for the selected materials.
- Room Tags mode prints the same order with a colored room column and legend; every cabinet
  covered by any assembly set (FF or FL) or Plans & Elevations gets its room.
- Unchecking a material or room removes exactly those parts; rows spanning rooms keep only the
  checked rooms' share of qty and cabinets.
- Tapping Print opens the Android print screen, the same flow as printing a job file today.
- A job with missing or corrupt index files behaves exactly as today (section hidden, file list
  works).

## Decisions

| Question | Decision |
|---|---|
| Print variants | Two: Standard (size order, width shading) and Room Tags (size order, room column, no width shading). "Sorted by room" sections were tried in the spike and dropped. |
| Which rows | Door Cut List rows with unit type Sheet only (CV "Units: Sheet"). Board-foot slabs, stiles, rails are excluded. |
| Where it appears | Inside `PrintDocumentsBottomSheet`, so all four callers get it. Hidden when the job has no sheet door-panel rows. |
| Material selection | Multi-select checkboxes, one PDF with one section per checked material. Materials whose name contains "MDF" (case-insensitive) default unchecked; all others default checked. |
| Room selection | Multi-select checkboxes, all default checked; hidden when the job has only one room. Applies in both modes. |
| Totals | None. |
| Title | "Door Cut List" (no "2.0"). |
| Persistence | None. Options reset to defaults each time the sheet opens. |
| PDF engine | Android `PdfDocument` + Canvas, with a pure-Kotlin layout pass. Off-screen WebView and third-party PDF libraries rejected (async WebView fragility and untestable pagination; iText AGPL, others add APK weight for a fixed table). |

## UI

New section in `PrintDocumentsBottomSheet`, between the "Print Job Documents" title and the file
tree, separated from the file list by a divider, with a small "Generated" overline.

**Collapsed:** one row with a list icon, the label **Door Panel Cut List**, a summary on the
right (for example "2 materials · 132 pcs", reflecting current selections), and an expand
chevron.

**Expanded (tap to toggle):**

1. **Room Tags** checkbox, default unchecked.
2. **Materials:** one checkbox row per detected sheet material, labelled with the CV material
   name and live piece count, for example `3/4 2s White Oak Rift — 124 pcs`. MDF default rule as
   above.
3. **Rooms:** one checkbox row per room found among the sheet rows, ordered by room number,
   labelled with the room name and live piece count (for example `KITCHEN — 50 pcs`). An
   `Unassigned` entry appears only when some cabinets have no room. The whole group is hidden
   when there is exactly one room (including the all-Unassigned case).
4. **Print** button, full width. Disabled when no material is checked, no room is checked, or
   the current selection yields zero rows. While building it shows a spinner; on success the
   Android print screen opens and the sheet dismisses, matching file printing.

Piece counts on materials, rooms, and the collapsed summary all reflect the other filter (a
material's count excludes unchecked rooms, a room's count excludes unchecked materials).

**Loading:** the section shows a placeholder row while its data loads on `Dispatchers.IO`; the
file tree loads independently and is not delayed.

**Hidden when:** `cutlist_index.json` is missing or unparseable, it has no `DOOR_CUT_LIST`
document, unit-type metadata is absent (`hasUnitTypeMetadata == false`, same rule as
`SpecialtyDoorPanelsScreen`), or no sheet rows remain.

## Data

New file `ui/components/doorcutlist/DoorPanelCutListData.kt`. Pure functions; no Android or
Compose types so it runs under plain JUnit.

**Loading** (once per sheet open, on IO), using the sheet's existing `jobRepository`:

- Job directory from `jobRepository.getJobDirectory(jobFolderName)`.
- Cut list: read `.metadata/hardwoods/cutlist_index.json`, parse with Gson into
  `HardwoodCutlistIndex`, take the `DOOR_CUT_LIST` document. Sheet rows via the existing
  `parseDoorCutUnitTypeMetadata(rawJson)` + `filterDoorCutRowsToSheets(rows, metadata)` in
  `data/DoorCutSheetFilter.kt`, so the sheet rule is identical to the Specialty door panels
  screen. Rows sorted by `(page, rowOrdinal)`.
- Rooms: `jobRepository.getCabinetSheetIndex(jobFolderName)`.

**Cabinet → room map.** First room found wins, scanning page-detail sets in this order:

1. `assembly.virtualCombined.pageDetails`
2. each `assembly.sources[i].pageDetails`
3. `assembly.pageDetails`
4. `plansElevations.pageDetails`

Pages with a blank room are skipped. Room keys keep CV's full label (`Room #3 (VANITIES)`) so
they sort by number; display text is the parenthesized name (`VANITIES`), falling back to the
full label when it does not match `Room #N (NAME)`. Cabinets with no match map to
`Unassigned`, which sorts last.

**Cabinet text parsing.** `rawCabinetText` is parsed into `(cabinet, count)` pairs:
`"9 (3), 11 (2), 13"` becomes `[(9,3), (11,2), (13,1)]`. If it yields nothing, fall back to
`cabinets` with count 1 each. If that is also empty, the row is a single `Unassigned` entry
carrying its original qty and empty cabinet text.

**Room split per row.** Group the pairs by room.

- Single room: keep CV's `qty` and original cabinet text untouched.
- Multiple rooms: each room's qty is the sum of its counts; its cabinet text is re-formatted
  from its pairs (`"20"`, `"9 (3), 11 (2)"`).

**Applying selections:**

- Material filter removes whole materials.
- Room filter drops room groups whose room is unchecked.
- **Room Tags on:** emit one output row per remaining room group, adjacent, in room-number
  order, each carrying its room.
- **Room Tags off:** emit one output row per CV row. If every room group remains, the row is
  unchanged. If some were dropped, qty is the sum of the remaining groups and cabinet text is
  re-formatted from their pairs. If none remain, the row is omitted.
- Output rows keep CV order within each material; materials keep first-appearance order.

**Model** handed to the renderer:

```kotlin
data class DoorPanelCutListModel(
    val jobTitle: String,            // job folder name
    val dateText: String,            // e.g. "1 October, 2026"
    val roomTags: Boolean,
    val filteredRoomNames: List<String>,   // checked room display names for the header note;
                                           // empty when every room is checked
    val rooms: List<CutListRoom>,          // checked rooms present in output, room-number order
    val materials: List<CutListMaterialSection>
)
data class CutListMaterialSection(val material: String, val unitsLabel: String, val rows: List<CutListRow>)
data class CutListRow(val qty: Int, val description: String, val width: String,
                      val length: String, val cabinetText: String, val roomKey: String?)
data class CutListRoom(val key: String, val displayName: String)
```

Dimensions are passed through exactly as CV wrote them, never reformatted.

The same functions also produce the UI's option lists and live counts (materials with counts,
rooms with counts, MDF defaults, "can print" flag) so the sheet and the PDF can never disagree.

## Rendering

Two files in `ui/components/doorcutlist/`. Units are PDF points (72 per inch).

### `DoorPanelCutListLayout.kt` (pure Kotlin)

Input: the model plus a `TextMeasurer` interface (`width(text, sizePt, bold): Float`). The app
supplies a `Paint`-backed implementation; tests supply a fixed-advance fake. Output:
`List<CutListPage>`, each a list of positioned draw items (title block, legend, material header,
table header, row) with resolved text, colors (ARGB `Int`), and rectangles. No Android imports.

- **Page:** US Letter portrait 612 × 792; margins 72 left/right, 40 top/bottom.
- **Columns (pt):** Standard `Qty 34 · Description 140 · Width 78 · * 16 · Length 92 ·
  Cabinet (Qty) 107`. Room Tags `Qty 28 · Description 116 · Width 60 · * 12 · Length 62 ·
  Cabinet (Qty) 97 · Room 92`. Same overall table width in both modes. Qty and `*` centered;
  Width and Cabinet right-aligned; Room centered.
- **Rows:** base height ~19 pt, 8.5 pt regular text. Cabinet text and room names wrap rather
  than truncate; a wrapped row grows to fit its tallest cell. Alternating row shading
  (`#EBEFF6` on odd rows) with thin white column separators, as in the spike.
- **Title block (page 1):** "Door Cut List" 26 pt bold navy (`#16375E`); then a light gray band
  (`#F3F3F3`) reading `{jobTitle} · {dateText} · Standard` or `· Room Tags`, plus
  `· Rooms: A, B` listing checked rooms when any room is unchecked.
- **Legend (Room Tags, page 1):** one colored chip per room in `rooms`, below the band.
- **Material header:** `Material: '{material}' | Units: Sheet`, 12 pt bold navy.
- **Pagination:** tables flow across pages; the column header row repeats at the top of each
  continuation page. A material header is never orphaned: if the header, its column header row,
  and at least 2 data rows do not fit, it starts on the next page.
- **Footer (every page):** small gray text `{jobNumber} · Door Cut List · Page X of Y`, where
  `jobNumber` is the text before the first " - " of the job folder name.
- **Width bands (Standard only):** shade the Width cell per run of equal widths, cycling CV's
  five colors `#EADBC8, #DDEAFB, #FCE4E4, #F1ECFB, #E4F7E4`. The counter is global across the
  document and also advances at the start of each table. Room Tags mode has no width shading.
- **Room colors (Room Tags only):** palette `#F6C85F, #9FD8A0, #8EC5F0, #F4A3A3, #C7A6EA,
  #7FD3CF, #F7B077, #E9A6D2, #C9D66B, #B5B5E8`, shuffled with
  `java.util.Random(jobFolderName.hashCode().toLong())` (deterministic across devices), assigned
  to rooms in room-number order, cycling if more than ten. `Unassigned` is `#D9D9D9`. The Room
  cell shows the display name in 7.5 pt bold on its room color.
- **No totals block.**

### `DoorPanelCutListPdf.kt` (Android)

Thin renderer: walks the layout pages and draws them onto `android.graphics.pdf.PdfDocument`
pages with `Canvas`, `Paint`, and `Typeface.DEFAULT` / `Typeface.DEFAULT_BOLD`. Print colors
are fixed; the app theme (including dark mode) is ignored.

- Writes to `context.cacheDir/print/door_cut_list_{jobNumber}.pdf`, clearing older files in
  that directory first.
- Hands the file to the existing `printPdfFile` in `PrintDocumentsBottomSheet.kt` (visibility
  changed from `private` to `internal`). Print job name:
  `KKC Sheet Tracker - {jobNumber} Door Cut List`.
- Build runs on `Dispatchers.IO`; the print call happens on the main thread afterwards.

## Error handling

| Case | Behaviour |
|---|---|
| Cut list index missing, unparseable, no door doc, no unit-type metadata, or no sheet rows | Section hidden; logged at debug. File tree unaffected. |
| Cabinet sheet index missing or has no rooms | All cabinets `Unassigned`; Rooms group hidden; Room Tags shows gray cells. |
| Unparseable cabinet text | Fallbacks described under Data. |
| PDF build or write throws | Inline error in the section ("Couldn't build cut list. Try again."), sheet stays open, partial file deleted, exception logged. |
| User cancels the print screen | Nothing special; the next build clears old temp files. |

## Testing

Plain JUnit (the project has no Robolectric). Data and layout files avoid Android types so they
are fully unit-testable.

`DoorPanelCutListDataTest`:

- Sheet filter keeps `SHEETS` rows and drops `BD_FT`.
- Room map priority: `virtualCombined` first; FF+FL fixture puts cabinets 56–73 in CLOSET;
  Plans & Elevations fills cabinets missing from every assembly set; blank rooms skipped.
- Cabinet text parsing: counts, no counts, mixed, blank (falls back to `cabinets`), empty.
- Split: single-room row keeps CV qty/text; multi-room row splits by counts (Room Tags on).
- Room filter with Room Tags off: partial qty and re-formatted text; fully excluded row omitted.
- MDF default-unchecked rule (`1/4 MDF`, `1/4 PG Maple MDF`; `3/4 2s White Oak Rift` checked).
- Option counts reflect the opposite filter; "can print" false on zero rows.
- Output preserves CV row order and material first-appearance order.

`DoorPanelCutListLayoutTest` (fake measurer):

- Column header repeats on continuation pages.
- Material header not orphaned (fewer than header + column header + 2 rows left starts a new page).
- Long cabinet text wraps and increases row height.
- Width band colors cycle across runs and advance at each new table; absent in Room Tags mode.
- Room colors distinct per room and stable for the same job name; Unassigned gray.
- Footer page numbering `Page X of Y` correct across a multi-page document.
- Header band lists checked rooms only when some room is unchecked.

Fixtures: small synthetic `cutlist_index.json` / `cabinet_sheet_index.json` files in
`app/src/test/resources/doorcutlist/`, modelled on 669 (FF+FL split, cross-room rows), 684
(three rooms, MDF), and 690 (single room); no customer names.

Manual verification (not unit-testable: Canvas drawing, print intent, Compose UI): debug build
on a tablet; open the print sheet for 669 from all four screens; print Standard and Room Tags
with a room unchecked and the MDF default to "Save as PDF"; compare against the spike PDFs.
Then release build to tablets via the normal process.

## Files

| File | Change |
|---|---|
| `ui/components/doorcutlist/DoorPanelCutListData.kt` | New: loading, room map, parsing, split/filter, option lists, model |
| `ui/components/doorcutlist/DoorPanelCutListLayout.kt` | New: pure layout and pagination |
| `ui/components/doorcutlist/DoorPanelCutListPdf.kt` | New: Canvas renderer, temp file, print hand-off |
| `ui/components/doorcutlist/DoorPanelCutListSection.kt` | New: Compose section (collapsed row, checkboxes, Print button) |
| `ui/components/PrintDocumentsBottomSheet.kt` | Insert section above the file tree; `printPdfFile` becomes `internal` |
| `app/src/test/.../doorcutlist/*Test.kt`, `app/src/test/resources/doorcutlist/*` | New tests and fixtures |

## Out of scope

- Totals / rips footer.
- Sorted-by-room sections (spike variant 2).
- Remembering options between sheet opens.
- Printing other cut lists (face frame, nailer) through this path.
- Changing the Hardwoods screen's own room grouping (it already reads `virtualCombined`; it does
  not gain the Plans & Elevations fallback here).
