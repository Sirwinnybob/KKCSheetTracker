# Cabinet Spillover Jump — Design

## Problem

Plans & Elevations and Assembly Sheets PDFs are Cabinet Vision exports. When too many
cabinets belong to one wall (Plans & Elevations) or one cabinet has too many BOM parts
(Assembly Sheets) to fit on the drawing page, Cabinet Vision spills the remainder onto a
following page that has the cabinet numbers / part rows but no elevation drawing.

`cabinet_sheet_index.json`'s `cabinetToPages` already maps a cabinet number to every page
its data appears on, including spillover pages — that part works correctly today (verified
against a live job, see Investigation below). The gap is navigation: when a tablet user
jumps to (or searches for) a cabinet whose row only exists on a spillover page,
`resolveJumpPage()` lands them on the spillover page itself — text/numbers only, no drawing.
They want the wall/cabinet's actual drawing.

## Investigation (verified against a live job)

Job `669 - WIECHERT 3146 NW CROSSINGS`, `669 - PLANS & ELEVATIONS.pdf`:

- Wall #5 (Room #1 Kitchen) draws cabinets 5–11 on page 5, with cabinets 12–16 spilling
  onto page 6 as a table-only continuation (same room/wall context, no drawing).
- `cabinetToPages["12"] = [6]` today: technically correct (that's the only page with
  cabinet 12's row), but not the page a user wants to land on.

Detection signal tested two ways against this real PDF:

1. **Raw vector-path count** (`page.get_drawings()`) — unreliable. Table borders and grid
   lines also produce vector paths; the spillover page (page 6) still shows 189 paths.
2. **`drawings + images` count, compared *within* the same page group (same room/wall, or
   same cabinet), taking the max** — reliable. Page 5 scores 7832 vs. page 6's 189; the
   drawing page wins by a wide margin every time it was checked, including on Assembly
   Sheets (cabinet 1's drawing page scores 1069 vs. its continuation page's 61).

This is a *relative* signal (max within group), not an absolute threshold — avoids needing
to tune a magic cutoff number that could drift with template changes.

## Design

### Where the work happens

All detection and resolution happens server-side, in the indexer that already builds
`cabinet_sheet_index.json` — currently Hours Tracker's ported worker
(`ready_jobs_worker_core/cabinet_reference_parser.py` + its adapters), the live production
writer of this file as of 2026-08-18 (see `kkc-metadata-map` skill). The tablet does zero
PDF processing; it only reads a field that's already there.

### Grouping key

- **Assembly Sheets:** the group is `cabinetToPages[cab]` — the existing per-cabinet page
  list. No new grouping logic needed; a cabinet's own pages are already known.
- **Plans & Elevations:** the group is a run of consecutive pages sharing the same
  `(room, wall)` in `pageDetails`. Adjacent pages with matching `(room, wall)` belong to one
  group; a change in either value starts a new group.

### Per-page signal

For every page already opened during indexing (both documents already open each page with
PyMuPDF/`fitz` to extract text):

```
signal = len(page.get_drawings()) + len(page.get_images())
```

No extra PDF pass — this reads from the same page object already in hand.

### Per-group resolution

Within each group, `drawingPage` = the page with the highest `signal` (ties broken by
earliest page number). Every page in the group — including the winning page itself — gets
`drawingPage` set to that value. A single-page group's only page points to itself.

### Output schema (additive)

Add two fields to every entry under `pageDetails` in `cabinet_sheet_index.json`, for the
`assembly`, `plansElevations`, and `virtualCombined` sections:

```json
"pageDetails": {
  "6": {
    "cabinets": ["12", "13", "14", "15", "16"],
    "room": "Room #1 (KITCHEN)",
    "wall": "Wall #5",
    "hasDrawing": false,
    "drawingPage": 5
  }
}
```

- `hasDrawing`: whether this specific page had the winning signal in its group.
- `drawingPage`: the page number to actually navigate to for any cabinet on this page.

No existing field changes shape or meaning. `cabinetToPages` is untouched — it still means
exactly what it means today (every page a cabinet's data appears on). Existing consumers
that don't know about the new fields keep working unmodified.

### Tablet consumption

`resolveJumpPage()` in `ReferenceModalOverlay.kt` changes from:

```kotlin
fun resolveJumpPage(cabinetToPages: Map<String, List<Int>>, cabinet: Int): Int? =
    cabinetToPages[cabinet.toString()]?.firstOrNull()
```

to preferring the resolved drawing page when available:

```kotlin
fun resolveJumpPage(
    cabinetToPages: Map<String, List<Int>>,
    pageDetails: Map<String, PageDetail>,
    cabinet: Int,
): Int? {
    val page = cabinetToPages[cabinet.toString()]?.firstOrNull() ?: return null
    return pageDetails[page.toString()]?.drawingPage ?: page
}
```

This is the only tablet-side change. It applies uniformly to page view and continuous
scroll, since both already navigate off this same resolver's return value.

### Edge cases

- **No drawing anywhere in a group** (a wall or cabinet that's entirely table/BOM, no
  elevation at all): every page's `drawingPage` still resolves to *some* page in the group
  (whichever has the highest signal, even if none has a "real" drawing) — never null when
  the cabinet has at least one page. Behavior is never worse than today; at worst it's a
  no-op (points to itself).
- **Cabinet-0 / appliance placeholders:** already excluded from `cabinetToPages` upstream
  (`cab_num > 0` filter) — never enter this resolution at all.
- **Multi-page drawing groups** (a wall's elevation itself spans 2+ pages): highest-signal
  page still gets picked; not a concern this design needs to solve differently, since the
  goal is just "best available page," not "every page with any drawing content."

## Out of scope

- The `||CAB:xx||` / `||WALL:...||` structured marker system (`assembly-pdf-format-spec.md`)
  is a separate, more deterministic long-term direction that requires editing Cabinet Vision
  templates. This design doesn't touch it and doesn't block it — if markers are adopted
  later, `hasDrawing`/`drawingPage` can be computed the same way regardless of which parse
  path (marker or heuristic) produced the page's cabinet list.
- Legacy Ready Jobs Watcher (`C:\Scripts\Ready Jobs Watcher`) is deprecated as of
  2026-08-18 and not the live writer. This design targets Hours Tracker's ported worker only.

## Testing

- Unit tests in `ready_jobs_worker_core` (Hours Tracker repo) for the pure resolution logic:
  given a set of per-page signals and room/wall groupings, correct `drawingPage` output,
  including single-page groups and ties.
- Regenerate `cabinet_sheet_index.json` for job 669 and confirm cabinet 12 → `drawingPage: 5`.
- Android unit test for `resolveJumpPage()` covering: cabinet on a drawing page (no-op),
  cabinet on a spillover page (redirects), cabinet with no `pageDetails` entry (falls back
  to raw page, current behavior).
- Manual tablet check: search/jump to cabinet 12 on job 669 in both page view and continuous
  scroll, confirm it lands on page 5.
