# Sheet Rip Checklist — Design Spec

## What & Why

SAW operators work in SPECIALTY mode. They can already tap "Rip List" to see a full-screen view of sheet-mode board stock. This feature adds a lightweight inline checklist of those same items directly on the Specialty job detail screen — no navigation required. Each item shows material, name, footage, rip count, and a single checkbox. The admin page gains a corresponding "done" indicator so shop leads can confirm tablet progress at a glance.

---

## Architecture

### Persistence — Android

**New file: `SheetRipProgressStore.kt`**
- Location: `app/.../data/SheetRipProgressStore.kt`
- Stores completion state per job in `.metadata/admin/sheet_rip_done.json`
- Format: flat JSON object — `{ "itemId": true, "itemId2": false }`
- Two public methods:
  - `loadDone(jobFolderName: String): Map<String, Boolean>`
  - `setDone(jobFolderName: String, itemId: String, done: Boolean)`
- `setDone` uses read-modify-write with temp-file + rename for atomic writes (same pattern as other stores in this codebase)
- No dependencies on specialty items or hardwoods tracker

**`SpecialtyStateStore` additions**
- Holds a `SheetRipProgressStore` instance
- Exposes `sheetRipDoneVersion: StateFlow<Long>` — increments after every `setDone` call so the screen recomposes reactively
- New suspend method: `setSheetRipDone(jobFolderName, itemId, done)` — delegates to `SheetRipProgressStore`, then increments version
- New method: `loadSheetRipDone(jobFolderName): Map<String, Boolean>` — delegates to `SheetRipProgressStore`

### Persistence — Server

**Extend `BoardStockProgress`** (both server and client types)
- Add field: `sheetRipDone?: Record<string, boolean>` (optional — absent from older responses defaults to `{}`)

**Extend `boardStockProgressReader.ts`**
- After reading the hardwoods tracker files for tally data, also read `.metadata/admin/sheet_rip_done.json`
- Parse it as a flat `Record<string, boolean>` with a safe fallback to `{}` if the file doesn't exist
- Merge into the returned `BoardStockProgress` object as `sheetRipDone`

No new endpoint. The admin page's existing 10-second poll on `/board-stock/progress` picks up sheet rip done state automatically.

---

## UI

### Android — `SpecialtyJobDetailScreen`

**Placement:** Between `"actions-specialty"` item and the first specialty checklist section.

**New lazy items added:**

1. `stickyHeader(key = "sheet-rips-header")` — shows label `"Sheet Rips"` with a `done / total` counter (e.g. `2 / 3`). Hidden if no sheet items exist for the job.

2. `items(sheetRipItems, key = { "sheet-rip:${it.id}" })` — one row per sheet-mode board stock item. Each row:
   - Checkbox on the left (checked = `sheetRipDone[item.id] == true`)
   - Material name
   - Item name
   - Total footage (e.g. `120 ft`)
   - Calculated rip count: `ceil(feet / ripLength)` rips
   - Completed rows render at reduced alpha (same dimming pattern used for skipped items elsewhere)
   - Tapping anywhere on the row (or the checkbox directly) toggles completion via `specialtyStateStore.setSheetRipDone(...)`

**Data loading in the screen:**
```kotlin
val sheetRipDoneVersion by specialtyStateStore.sheetRipDoneVersion.collectAsState()
val sheetRipItems = remember(scanState.snapshot.basePath, jobFolderName) {
    loadAdminBoardStock(File(scanState.snapshot.basePath), jobFolderName)
        .filter { it.mode == "sheet" && it.feet != null && it.feet > 0 }
}
val sheetRipDone = remember(scanState.snapshot.basePath, jobFolderName, sheetRipDoneVersion) {
    specialtyStateStore.loadSheetRipDone(jobFolderName)
}
```

Section is skipped entirely if `sheetRipItems.isEmpty()`.

### Admin — `BoardStockTab.tsx`

The existing `Tablet` column's `TabletProgress` component currently renders `—` for `mode === 'sheet'` items (no tally applies). Change that logic:

Update `TabletProgress` to accept an additional `sheetDone?: boolean` prop. Pass `sheetDone={progress.sheetRipDone?.[item.id] ?? false}` for every row.

Inside `TabletProgress`:
- For `mode === 'sheet'` items (detected via a new `isSheet: boolean` prop passed from the row): check `sheetDone`
  - If `true`: show a green `DONE` badge (same style as the existing tally DONE badge)
  - If `false`: show `—`
- For `mode === 'bd_ft'` items: existing tally logic unchanged (DONE / SKIP / X/Y / —)

---

## Backwards Compatibility

- Jobs with no `.metadata/admin/sheet_rip_done.json` → `loadDone` returns `{}` → all checkboxes unchecked → no visible change until a worker taps one
- `BoardStockProgress.sheetRipDone` missing from older server responses → admin client treats it as `{}` (safe default in `TabletProgress`)

---

## Verification

1. **Android:** Open a job in SPECIALTY mode that has sheet-mode board stock items. Confirm the "Sheet Rips" section appears between the action tiles and the specialty checklist, showing correct footage and rip counts. Tap a checkbox — row dims. Kill and relaunch the app — checkbox remains checked.

2. **Admin page:** Within ~10 seconds of checking an item on the tablet, the Tablet column for that sheet item switches from `—` to a green `DONE` badge.

3. **No sheet items:** Open a job with no sheet-mode board stock items. Confirm the "Sheet Rips" section does not appear.

4. **Backwards compat:** Open a job that has never had `sheet_rip_done.json` written. All sheet rip rows show unchecked. No crash or error.
