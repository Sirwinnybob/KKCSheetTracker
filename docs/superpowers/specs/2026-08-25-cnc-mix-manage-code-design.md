# Design Spec: CNC Mix / Second-Pass "Manage Code" Feature

Date: 2026-08-25
Status: approved for planning

## 1. Goal

Let CNC-mode tablets call the PGM Mix Service (`C:\Scripts\PGM_BCR_Loader\mix_service`,
running on the CNC PC at `192.168.20.4:8477`) to reorder PGM cut order per material,
generate/update `.mix` files, and mark PGMs for second-pass processing
(`PUNLOAD` removal, `SUPER`/`2ND` pass), from two entry points:

1. A new **Manage Code** screen reached from the job detail screen, covering every
   material in the job.
2. The same screen, scoped to a single material, reached from a button on the
   Sheet Viewer screen.

Sheet Viewer's page navigation order also follows the applied mix order once a
material has one.

## 2. Dependencies and known gap

- `POST/GET /mixes*` (mix CRUD) is live today per
  `docs/specs/2026-08-14-pgm-mix-service-design.md`.
- `GET/POST /jobs/{job}/materials/{mat}/pgm-edits*` (second-pass CLI routes) is
  **Task 4** of `docs/superpowers/plans/2026-08-25-pgm-second-pass-cli-api.md` and is
  **not yet implemented** (steps unchecked as of this spec's date). This Android
  design is built against the documented contract in that plan; PUNLOAD/SUPER/2ND
  calls will fail until Task 4 ships. Decision: build the full UI now anyway (not
  a stub), per explicit approval.

## 3. Connectivity

- Single hardcoded Mix Service instance: `http://192.168.20.4:8477`. No per-tablet
  config (unlike timeclock-hub) — only one CNC PC runs this service today.
- New client `data/mixservice/MixServiceClient.kt`, same shape as
  `LiveIndexClient.kt` / `ArchiveAdminClient.kt`.
- Service unreachable → Manage Code screen shows an inline "Mix service
  unreachable" state; all materials render greyed/disabled. No crash, no retry loop.

## 4. Sheet ↔ PGM matching

No new metadata. `PageMetadata.sheetFiles` (`data/models/Models.kt:57`) already
carries bare PGM stems per page (e.g. `["R280602A","R280602Z"]`), populated from
the existing `Y:\Ready Jobs\<job>\CNC\.metadata\<pdf-stem>.json` sidecar (Ready
Jobs Watcher-owned). PGM path on the CNC PC = `<mix service cnc_root>\<job>\
<material>\<stem>.pgm`. `Material.materialName` is used directly as the `{mat}`
path segment (already matches CNC folder naming).

A/Z pairing reuses the existing fallback convention in
`SheetViewerScreen.kt:3322` (`inferSheetFiles`): A always immediately precedes its
Z partner and is treated as one row/one thumbnail (the combined-image PDF page).

## 5. Grey-out rule

A material greys out (non-interactive) when `GET /jobs/{job}/materials/{mat}/pgms`
returns empty or 404 — i.e. no PGMs exist in that material's folder on this CNC
PC (they may be on the shop's other CNC, out of scope for this service).

## 6. Locking rule

A row (single PGM or combined A/Z) is locked (no drag, no checkboxes, dimmed,
lock icon) when its page's `SheetStatus` (from `ProgressStore.kt`) is `COMPLETE`,
or `SKIPPED` with `reNested == true`. Locked rows are excluded from drag reorder
and from any Generate payload.

## 7. Mix cardinality and initial order

- One ordered sequence per material per Generate click. The Mix Service API
  supports multiple named mixes per material, but this screen manages exactly
  one at a time per material (looked up via `GET /mixes?job=&material=`, not by
  a name convention). If that query unexpectedly returns more than one
  definition (edit made by another tool), show a conflict state rather than
  guessing which one is "the" mix.
- Dropdown initial order: if a mix already exists for the material, load its
  saved `programs` order. Otherwise default to PDF page order.

## 8. Checkbox model

Per row: **MIX**, **PUNLOAD**, **2ND**, and **SUPER** (SUPER hidden until 2ND is
checked; checking SUPER implies 2ND; unchecking 2ND hides and clears SUPER).
Material-dropdown header carries 4 select-all checkboxes, same semantics.

- MIX → row's PGM is included in the material's `programs` list sent to
  `POST`/`PUT /mixes`.
- PUNLOAD / 2ND / SUPER → per-file second-pass API request row:
  `{pgm, secondPass: none|standard|super, removePUnload}`. 2ND alone = `standard`;
  SUPER = `super`. For a combined A/Z row, these apply only to the Z file — the A
  file is immutable for second-pass and is never included in a pgm-edits request.
- Defaults: nothing marked second-pass. MIX defaults checked for every row whose
  material has no existing mix; for a material with an existing mix, MIX/PUNLOAD/
  SUPER/2ND all default from the live server state (`GET pgm-edits` +
  the loaded mix's `programs`), not blank.

## 9. Cross-mix duplicate warning

Before Generate, for each material with changes, scan other mix definitions
(any material/job, via `GET /mixes`) for PGMs already present in this material's
new `programs` list under a different mix name. If found: non-blocking warning
dialog naming the file and the other mix, with **Continue anyway** / **Go back
and edit**. Duplicate PGM membership across mixes is allowed by the service; this
is purely an operator heads-up.

## 10. Screen structure

- New route `manage_code/{jobFolderName}` (optional `?material=` to scope to one
  material, used by the Sheet Viewer entry point), new file
  `ui/managecode/ManageCodeScreen.kt`.
- `JobDetailScreen.kt:447` — `Row` wraps the existing `CompactSpecialtySection`
  (`.weight(0.75f)` when it has content) and a new **Manage Code** button
  (`.weight(0.25f)`, same height); if specialty is empty, the button alone is
  `fillMaxWidth()`.
- Materials render as collapsed-by-default dropdowns: header (material name + 4
  select-all checkboxes, greyed if no PGMs), body = drag-reorderable list of rows
  (thumbnail, filename(s), per-row checkboxes per Section 8, lock state per
  Section 6).
- Sheet Viewer entry point (new button near the existing Print/3D buttons,
  `SheetViewerScreen.kt:420-443`) opens the same screen pre-filtered to
  `currentMaterial`, single dropdown, always expanded.
- Bottom bar: **Generate mixes and edit code** button.

## 11. Generate orchestration

Per material with any change, sequential (Mix Service serializes WINXISO/CLI
globally, no benefit to parallelizing):

1. If row order or MIX-membership changed vs. loaded state:
   `POST /mixes` (new) or `PUT /mixes/{name}` (existing) with `programs` = current
   row order expanded to bare filenames, A always immediately before its Z.
2. Run the cross-mix duplicate check (Section 9); resolve per user choice.
3. If any row has PUNLOAD/2ND/SUPER checked: batch into one
   `POST /jobs/{job}/materials/{mat}/pgm-edits`, excluding A files and locked rows.
4. Progress UI: per-material spinner → success/error icon. One material's
   failure (`409 edit_busy`, `503 compile_busy`, network error, etc.) doesn't
   block others; end-of-run summary lists per-material outcome with retry for
   failed ones only.
5. On success: refresh the material's mix order (drives Sheet Viewer paging) and
   pgm-edit ledger state (drives checkbox defaults on next open).

## 12. Sheet Viewer page order override

Once a material has a saved mix, `SheetViewerScreen`'s page navigation (swipe,
page list, `currentPage` progression) for that material follows the mix's
`programs` order — mapped back PGM stem → `sheetFiles` → PDF page number —
instead of natural PDF page order. Pages not covered by the mix (no mix yet, or
a partial mix) fall back to PDF order, appended after mapped pages rather than
dropped. Order updates live after a successful Generate (Section 11 step 5).

## 13. Error handling summary

- Mix Service unreachable: greyed screen, inline message, no crash (Section 3).
- `GET /mixes?job=&material=` returns >1 definition: conflict state, no auto-pick
  (Section 7).
- Per-material Generate failure: isolated, retryable, doesn't block sibling
  materials (Section 11 step 4).
- Cross-mix duplicate: non-blocking warning, user choice (Section 9).
