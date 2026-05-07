# Assembly PDF Format Specification

This document defines the structured text markers to add to Cabinet Vision report templates for Assembly Sheets and Plans & Elevations PDFs. These markers enable reliable, geometry-independent cabinet number extraction in `cabinet_sheet_indexer.py`.

## Background

The existing parser used heuristics (`Assembly # 42` text patterns and `#`/`UNIT NAME` table geometry) that can fail when PDF layout changes. Adding explicit markers makes parsing deterministic and format-independent.

## Marker Format

Markers follow the same `||KEY:VALUE||` double-pipe convention used by cut list column delimiters.

### Cabinet Number Marker

```
||CAB:42||
```

- Double pipe on both sides — `||` prefix and `||` suffix
- `CAB:` label (case-insensitive, but use uppercase in templates)
- Cabinet number: 1–4 digits, no leading zeros needed
- Exactly one marker per cabinet number per section start

### Wall/Room Context Marker (optional but recommended)

```
||WALL:Room 1 - Wall A||
```

- `WALL:` label
- Value format: `{room name} - {wall name}` using ` - ` (space-dash-space) as separator
- If no room context: just `||WALL:Wall A||`
- Room and wall names can include letters, numbers, spaces, `#`, and `-` but **not** `|`

## Where to Place Markers

### Assembly Sheets

Place markers in the **cabinet section header** area — the title/label that appears at the top of each cabinet's assembly drawing. Both markers should appear on the same page as the drawing they describe.

**Minimum required:**
```
||CAB:42||
```

**Recommended (with room/wall context):**
```
||CAB:42||  ||WALL:Room 1 - Wall A||
```

**Multiple cabinets on one page:** repeat the `||CAB:xx||` marker for each:
```
||CAB:42||  ||CAB:43||  ||WALL:Room 1 - Wall A||
```

**Cabinet spanning multiple pages:** place the marker on **every page** of the drawing:
```
Page 5: ||CAB:42||  ||WALL:Room 1 - Wall A||
Page 6: ||CAB:42||  ||WALL:Room 1 - Wall B||
```

### Plans & Elevations

Place the cabinet marker adjacent to or immediately after the existing cabinet number in the elevation table. The `||CAB:xx||` marker replaces the need for exact table column geometry.

```
||CAB:42||
42
Base Cabinet 24" Wide
```

Or on the same line as the cabinet number entry is acceptable.

## Examples

### Assembly Sheet page header
```
Job 12345 — Smith Residence
||CAB:42||  ||WALL:Kitchen - Wall 3||
Assembly: Base Cabinet 36" Sink
Room: Kitchen  Wall: 3
```

### Plans & Elevations entry
```
#    UNIT NAME              WIDTH   HEIGHT  DEPTH
||CAB:42||
42   Base Cabinet 36" Sink  36"     34.5"   24"
```

## What the Parser Does With These Markers

1. Scans every page for `||CAB:(\d{1,4})\|\|` — if found anywhere in the document, uses markers exclusively (does not run legacy heuristics)
2. Builds `cabinetToPages` mapping: `{"42": [5, 6], "43": [7]}`
3. Builds `pageDetails` mapping: `{"5": {"cabinets": ["42"], "room": "Kitchen", "wall": "Wall 3"}}`
4. Writes both to `cabinet_sheet_index.json` in `.metadata/`

If **no markers are present** anywhere in a document, the parser falls back to the legacy heuristic approach automatically — so existing un-updated PDFs continue to work.

## Validation

After updating a template and exporting a test PDF:

1. Copy the PDF to a test job folder
2. Run: `python -m ready_jobs_watcher.cabinet_sheet_indexer` (or trigger via the watcher)
3. Open `.metadata/cabinet_sheet_index.json` and verify:
   - `cabinetToPages` contains the expected cabinet numbers
   - `pageDetails` entries have correct `room` and `wall` values
   - Page numbers match the actual pages in the PDF

Run the test suite to confirm no regressions:
```
cd "C:\Scripts\Ready Jobs Watcher"
python -m unittest tests.test_cabinet_sheet_indexer -v
```
