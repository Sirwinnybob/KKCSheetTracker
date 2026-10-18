# Live sheets from the cabinet app (CV_Site_measuring_app)

Status: **parked**. Written 2026-10-18 so that the idea isn't lost. Winston said this won't be needed "for a good while". Nothing here is approved for building yet. Brainstorm it again with Winston before planning.

## Why

Today the CNC sheets reach the tablets through a long chain:

1. Cabinet Vision nests the job and prints a nest PDF.
2. `split_pdfs_gui_v3.py` splits that PDF and extracts the part metadata, using upstream OCR for the bounds.
3. Syncthing copies `.metadata/{material}.json`, the thumbnails and the part graphics to each tablet.
4. KKCSheetTracker shows the sheet picture and the parts table.

Every hop loses exactness: part numbers and sizes are read back off a PDF.

Winston is building his own cabinet and CNC program, [CV_Site_measuring_app](https://github.com/Sirwinnybob/CV_Site_measuring_app) (React + Three.js, self-hosted on TrueNAS, Yjs realtime collaboration). That app already knows every part exactly: its cabinet number, room, size, rotation, grain, banding and position on the sheet. Its CNC screen will show the same picture-plus-table sheet view that KKCSheetTracker shows today.

The goal is vertical integration. The tablets read sheets live from the cabinet app, with no PDF, no OCR and no splitter.

## What Winston wants on the tablet

These are his words from the 2026-10-18 brainstorm:

- Tap a part on the sheet picture to highlight it.
- From that part, choose:
  - **re-nest it onto the next sheet(s)**,
  - or **put it onto its own sheet**,
  - see its info and what part of the cabinet it is,
  - with a **3D view of the entire cabinet**.

Keep what already works on the tablet:
- Sheet / Plans & Elev. / Assembly tabs.
- Part-name group chips.
- The parts table: #, Width, Length, Name, Cab, Room, rotation mark, banding mark, bad-part flag.
- Sheet N of M navigation.
- Re-Nested / Skip / Done.
- Clock-in and Manage code.

## Current tablet data model (for reference)

- `MaterialMetadata.pages[]`: `PageMetadata` with a `sheetId`, a thumbnail or diagram image, and `parts[]`.
- `Part`: `number`, `width`, `length`, `name`, `cabNumber`, `room`, `rotated`, `graphicPath`, `banding`.
- Progress: per-tablet `TrackerAction` event streams (Lamport-ordered), and `SheetStatus` (`NOT_STARTED`, `IN_PROGRESS`, `COMPLETE`, `SKIPPED`, `HAS_BAD_PARTS`, `RE_NESTED`).

## What the cabinet app has (2026-10-18)

- **Server:** Express plus a Yjs relay, with JWT auth and job documents in SQLite on the TrueNAS `/data` volume.
- **Nest intent per job:** `job.nestedSheets` (`JobNestedSheetsV1`). Each part is placed, unplaced or excluded, with its sheet, X/Y, rotation and face up.
- **Edits are explicit nest commands:** `move`, `rotate`, `turnOver`, `exclude`, `restore`, `reNest`. Each is bound to a source fingerprint and an intent hash, so a stale edit is refused instead of silently applied.
- **CNC programs:** generated and verified in a web worker, and downloaded as a reviewed bundle.
- **Not there yet:**
  - "move to the next sheet with automatic placement",
  - "put on its own sheet",
  - room or cabinet filtering for a run.

  These are planned as nest engine additions on the cabinet-app side, and should land before this work.

## Approach: a rebuild, not a retrofit

Winston expects to **rebuild or fork KKCSheetTracker with the new program in mind**, rather than jerry-rigging the current PDF and metadata pipeline to read from the cabinet app. Keep the parts of the tablet UI that work (listed above). Design the data layer around the cabinet app's live job, not around `.metadata` files and Syncthing.

## Likely shape (a guess, to confirm with Winston)

1. **Read path.** The cabinet app server publishes a read-only "run" per job and material: sheets, numbered parts, the sheet drawing as vector geometry rather than an image, and links to the part's cabinet in 3D. The tablet reads it over the LAN or Tailscale. Server discovery could reuse the timeclock hub's mDNS pattern.
2. **Status path.** Done, Skip, Re-Nested and bad-part flags stay tablet events, keeping the existing Lamport ordering. They are mirrored to the cabinet app so the office sees shop progress.
3. **Edit path.** "Next sheet" and "own sheet" are sent as nest commands to the cabinet app. The cabinet app validates them, re-generates the affected programs, and publishes a new run revision. The tablet never edits geometry locally.
4. **3D.** Reuse the tablet's existing WebView three.js viewer (`assets/viewer`) to show the cabinet model that the cabinet app exports. Highlight the selected part.

## Open questions for later

- Does the PDF and splitter path stay as a fallback for jobs still drawn in Cabinet Vision, and for how long?
- Who may re-nest from the shop floor: anyone, or only someone holding Manage code?
- What happens to sheets that have already been cut when a part is re-nested? This matches the current `RE_NESTED` and remake semantics.
- Offline shop floor: are tablets allowed to queue edit requests, or only status?
