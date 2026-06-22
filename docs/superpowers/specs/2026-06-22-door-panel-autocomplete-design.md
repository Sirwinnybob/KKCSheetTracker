# Door-Panel Auto-Complete Design

**Date:** 2026-06-22
**Repo:** KKCSheetTracker (Android app)
**Status:** Approved

## Goal

When an operator checks off an auto-detected "Door panels — <material>" specialty item,
automatically mark complete every matching door-panel row in the hardwoods Door Cut List
(and clear them when the item is unchecked).

## Background

- The server-side automation creates specialty items for door panels. Example record
  (`<job>/.metadata/admin/specialty_items.json`):

  ```json
  {
    "id": "f2fc7ae7-...",
    "name": "Door panels - 1/4 2s Hickory Rustic",
    "material": "1/4 2s Hickory Rustic",
    "stations": ["SAW"],
    "category": "CUSTOM",
    "autoDetected": true,
    "automationKey": "door_panels_auto|1/4 2S HICKORY RUSTIC|flat",
    "createdBy": "automation"
  }
  ```

- The hardwoods Door Cut List (`<job>/.metadata/hardwoods/cutlist_index.json`,
  `docType == "DOOR_CUT_LIST"`) contains the panel rows. The flat-panel sheets carry
  `unitType == "SHEETS"` and a `material` equal to the item's material; rails/stiles carry
  a *different* material (e.g. `"3/4 Solid Hickory Rustic"`) and `unitType == "BD_FT"`.
  Verified on job 575: 27 `SHEETS` rows of `"1/4 2s Hickory Rustic"` are the panels.

- Material names exist in two forms: the **real** name used in the cut list
  (`"1/4 2s Hickory Rustic"`) and the **sanitized** CNC-filename-safe name
  (`"1_4 2s Rustic Hickory"`). The mapping lives at
  `Y:\Ready Jobs\.metadata\material_mappings.json` as `realName -> sanitizedName`.

- Completion of a hardwoods row is recorded via
  `HardwoodsProgressStore.setDoneCount(jobFolderName, docType, rowId, qty, doneCount)`.
  A row is "done" when `doneCount == qty`; "cleared" when `doneCount == 0`.

## Match rule (server owns classification; app matches material exactly)

A Door Cut List row is a target of door-panel item `I` iff:

1. `row.docType == "DOOR_CUT_LIST"`, and
2. `row.unitType == "SHEETS"` (the server's door-panel classification), and
3. `canonical(row.material) == canonical(I.material)`.

`canonical(name)`:
- trim, then resolve through `material_mappings.json`:
  - if `name` is a real-name **key** → use its sanitized value;
  - else if `name` is already a sanitized **value** → use it as-is;
  - else → use `name` unchanged;
- case-fold the result for comparison.

This makes matching robust whether either side stores the real or sanitized form.

## Architecture (app-side, imperative at check-time)

Pure, testable core + thin integration:

- `MaterialMappings` (new): loads `baseDir/.metadata/material_mappings.json` once; exposes
  `canonical(name: String): String`. Missing/unreadable file → identity canonicalization
  (trim + case-fold), so the feature degrades to plain exact-match rather than failing.

- `DoorPanelAutoComplete` (new, pure): 
  `matchingDoorPanelRows(item: SpecialtyItem, doorCutRows: List<HardwoodCutlistRow>, mappings: MaterialMappings): List<DoorPanelTarget>`
  where `DoorPanelTarget(rowId: String, qty: Int)`. Returns `[]` unless
  `item.automationKey` starts with `"door_panels_auto|"`. Applies the match rule above.

- Integration: at the point where an auto door-panel specialty item's completion is
  toggled, after the existing specialty completion write, call the helper and then, for
  each target, `HardwoodsProgressStore.setDoneCount(job, "DOOR_CUT_LIST", rowId, qty, doneCount)`
  with `doneCount = qty` on check and `doneCount = 0` on uncheck. The exact toggle
  call-site is identified during planning; door-cut rows come from the already-loaded
  hardwoods cutlist index.

## Data flow

1. Operator toggles auto door-panel item completion in the Specialty UI.
2. Existing path records the specialty item completion (unchanged).
3. Coordinator loads `MaterialMappings` + the job's Door Cut List rows.
4. `matchingDoorPanelRows(...)` returns the target `(rowId, qty)` set.
5. For each target: `setDoneCount(..., doneCount = checked ? qty : 0)`.

## Error handling

- Missing/unparseable `material_mappings.json` → identity canonicalization (plain exact match).
- No Door Cut List for the job, or no matching rows → no-op.
- Non-auto / manual specialty items (no `door_panels_auto|` prefix) → no-op.

## Testing

Pure-function unit tests (`DoorPanelAutoCompleteTest`):
1. For job-575-shaped data, selects exactly the 27 `SHEETS` rows of `"1/4 2s Hickory Rustic"`,
   excludes the `BD_FT` rails of `"3/4 Solid Hickory Rustic"`.
2. Matches when the item stores the sanitized form and rows store the real form (via mapping),
   and vice-versa.
3. Non-door-panel / manual item (no `door_panels_auto|` automationKey) → empty result.
4. `MaterialMappings` with a missing file → identity canonicalization still matches identical
   raw strings.

Integration behavior (store-level test where feasible):
5. Check → each target row recorded `doneCount == qty`; uncheck → `doneCount == 0`.

## Out of scope

- The "ITEM-rollup" station-split completion hardening (door panels are single-station;
  no evidence of file-level loss). Can be specced separately if multi-station custom items
  ever exhibit the issue.
- Any change to the server automation or on-disk schema.
