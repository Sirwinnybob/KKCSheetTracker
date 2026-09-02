# CNC Miscellaneous Tagged Parts — Design

## Overview

Add a "Miscellaneous" CNC part category, mirrored end-to-end alongside the existing "Remake"
category, across three repos:

- **PGM_Sorting** (PDF splitter / run processor) — creates the metadata
- **Hours Tracker** (`ready_jobs_worker_core`, the live production Ready Jobs worker as of
  2026-08-18) — computes/publishes it into shared caches and a bad-parts queue file
- **KKCSheetTracker** (Android tablet) — reads and displays it on the CNC dashboard

Miscellaneous is a **single fixed new category**, not a generic multi-tag system. It behaves
exactly like Remake (same plumbing shape), with two differences: a different trigger prefix, a
different color (blue instead of purple), and no part-level "replaces these original parts"
tracking (Remake has this via `RemadePartMetadata`; Miscellaneous is a plain label).

## Why this shape

Remake today has **no shared category abstraction** — it's a hardcoded string/boolean checked at
each layer (`remakeLabel` nullable string on the tablet, `bool(material_meta.get("remakeLabel"))`
in the worker). There is no enum or "part category" type to extend. Building Miscellaneous as a
second, independently-named field with the same shape (not a generalization of Remake into a
category enum) matches the existing pattern and is the smallest change that gets a working feature
shipped, per the scope decision below.

The PDF splitter already has an unrelated "custom prefix" feature (`c`-prefix filename triggers a
free-text naming dialog), but it only affects the output filename/folder — it does not write
anything into the CNC sidecar metadata JSON. It is a different feature and is not reused here.

## Scope decision

Fixed "Miscellaneous" category only (not a generic configurable multi-tag system). Rationale:
matches the literal request, ships with the same effort Remake already required, and a generic
tag system would require new splitter config UI, a new enum threaded through all three repos, and
speculative design for tags that don't exist yet.

## Field/file mapping (Remake → Miscellaneous)

| Concern | Remake | Miscellaneous (new) |
|---|---|---|
| Splitter trigger | `r`/`R` filename prefix | `m`/`M` filename prefix |
| Sidecar field | `metadata.remakeLabel` (string, `"NNN REMAKE"`) | `metadata.miscLabel` (string, `"NNN MISC"`) |
| Splitter state file | `remake_state.json` | `misc_state.json` |
| Splitter page-level block | `page_data["remake"]` (label + remade parts) | none — simple label only |
| cache_index field | `isRemake: bool` | `isMisc: bool` |
| Queue file | `remake_bad_parts_candidates.json` | `misc_bad_parts_candidates.json` (separate file) |
| Tablet model field | `MaterialMetadata.remakeLabel` | `MaterialMetadata.miscLabel` |
| Tablet part-level struct | `RemadePartMetadata` | none |
| Dashboard section | `CncRemakesSection` (purple), rendered first | `CncMiscsSection` (blue), rendered directly below Remakes |
| Theme token | `KKCStatusColors.remakeBg` | `KKCStatusColors.miscBg` |

### Colors

Direct Material-palette analogue of the existing purple tokens, same tone depth, blue hue:

- Light: `0xFF90CAF9` (Blue 200 — analogue of Purple 200 `0xFFCE93D8`)
- Dark: `0xFF64B5F6` (Blue 300 — analogue of Purple 300 `0xFFBA68C8`)

## Component changes

### 1. PGM_Sorting (`C:\Scripts\PGM_Sorting`)

- `split_pdfs_gui_v3.py:3258` — add `is_misc` detection alongside existing `is_remake`/`is_custom`
  (`m`/`M` prefix check on source PDF filename). Mutually exclusive with remake/custom by
  construction (filename has one prefix letter).
- New `_next_misc_label()` (mirrors `_next_remake_label`, line 1287) — format
  `f"{next_idx:03d} MISC"`, persisted via new `MISC_STATE_FILE = "misc_state.json"` (mirrors
  `REMAKE_STATE_FILE`, line 88).
- Mirror of lines 3295-3298: when `is_misc`, `custom_prefix = misc_label`.
- Mirror of lines 3080-3081: write `metadata["miscLabel"] = misc_label` into the CNC sidecar JSON
  (`CNC\.metadata\<pdf-stem>.json`).
- `final_material_name = f"{custom_prefix} - {material_folder}"` (line 3429) — unchanged, already
  generic.
- No mirror of lines 3033-3036 (`page_data["remake"]` block) — Miscellaneous has no page-level
  part-detail block, per the simple-label decision.
- `process_run_folders_v2.py:150` — add `MISC_STATE_FILE` alongside `REMAKE_STATE_FILE` wherever
  remake state is read/displayed; same treatment, parallel code path.

### 2. KKCSheetTracker tablet (`C:\Scripts\KKCSheetTracker`)

- `Models.kt:32` — add `miscLabel: String?` to `MaterialMetadata` (sibling of `remakeLabel`; no
  `RemakePageMetadata` equivalent).
- `Models.kt:770` — add `incompleteMiscMaterials: List<DashboardRecentMaterialItem>` to
  `DashboardUiModel` (sibling of `incompleteRemakeMaterials`).
- `CacheIndexModels.kt:10` — add `isMisc: Boolean` to `CacheIndexMaterialProgress` (sibling of
  `isRemake`).
- `FileBackedUnifiedMetadataEngine.kt:1661` — `sanitizeMaterialMetadata()` copy-through
  `miscLabel` (no equivalent of the remake block at 1702-1715 needed).
- `AppStateStore.kt:270-295` — derive `incompleteMiscMaterials` from `miscLabel`, parallel to the
  existing remake derivation.
- `AppStateStore.kt:108-113` — `isMisc` candidate filtering for jobs-list segment building.
- `UnifiedModeSpecs.kt:83` — segment building reads `isMisc`.
- `StatusComponents.kt:83,729-730` — `MaterialSegmentData.isMisc`; `isMiscIncomplete` →
  `colors.miscBg` job-card status row.
- `AppScaffold.kt:678` — job-card status segment misc row (parallel remake row).
- `KKCColors.kt:23,43,69` — add `miscBg: Color` to `KKCStatusColors`; light `0xFF90CAF9`, dark
  `0xFF64B5F6`.
- `UnifiedModeDashboardScreen.kt:239-254,358-411,414+` — new `CncMiscsSection`/
  `CncMiscMaterialCard` composables, gated on `dashboard.incompleteMiscMaterials.isNotEmpty()`,
  mirroring `CncRemakesSection`/`CncRemakeMaterialCard` exactly (border/icon/progress-bar all use
  `miscBg`). Rendered immediately after `CncRemakesSection` in the dashboard's section list.

### 3. Hours Tracker worker (`ready_jobs_worker_core`, live production writer)

- `fs_reader.py:156-219` — no change. The sidecar dict is already passed through verbatim;
  `miscLabel` flows automatically once the splitter writes it.
- `cache_index_payload.py:92` (`compute_cnc_progress`) — add
  `is_misc = bool(material_meta.get("miscLabel"))`, emit `"isMisc": is_misc` per material
  (parallel line 102).
- `cache_index_payload.py:177-199,219` (`compute_progress_summary`, `build_cache_index`) — fold
  into `progressSummary.cnc.materials[].isMisc`, written to `cache_index.json`.
- `cache_payload.py:80` — no change; `cncJob.materials[].metadata` already publishes the raw
  sidecar (including `miscLabel`) verbatim into `cache_static.json`.
- New `misc_candidates_publish.py` (mirrors `remake_candidates_publish.py:1-157`) —
  `refresh_misc_candidates_for_job()`, writes `CNC/.metadata/misc_bad_parts_candidates.json`
  (complete-but-unresolved-bad-part misc sheets, same shape as the remake queue file). Kept as a
  **separate file**, not folded into `remake_bad_parts_candidates.json`, to avoid changing that
  file's schema for existing consumers.
- `sweep.py:346-348` — add a call to `refresh_misc_candidates_for_job()` alongside the existing
  remake call, in the per-job loop.
- Old Ready Jobs Watcher (deprecated as of 2026-08-18) — no change; not the live writer.

## Data flow

Splitter writes `miscLabel` into `CNC\.metadata\<pdf-stem>.json` at split time → HT worker's
per-job sweep reads the sidecar verbatim into `cache_static.json` and computes `isMisc` into
`cache_index.json`, and separately refreshes `misc_bad_parts_candidates.json` → tablet reads
`miscLabel` (full detail, dashboard "Incomplete Miscellaneous" derivation) from
`cache_static.json`-sourced material metadata, and `isMisc` (lightweight, jobs-list segment) from
`cache_index.json` — same dual-path pattern Remake already uses (`AppStateStore.kt:270-295` vs.
`108-113`).

## Error handling / compatibility

No backward-compat shim needed. `miscLabel` absent on a sidecar is equivalent to `isMisc: false`
— the same null-safe behavior `remakeLabel`/`isRemake` already have. Existing jobs and PDFs
without an `m`-prefix are entirely unaffected.

## Rollout order

Splitter (writes the field) → HT worker (computes/publishes `isMisc` + queue file) → tablet
(reads/renders). Each downstream stage depends on the upstream field existing, so this order
avoids a stage shipping ahead of data it needs.

## Testing

- **PGM_Sorting**: unit test for `m`-prefix detection → `miscLabel` written to sidecar JSON;
  `misc_state.json` numbering. Mirror whatever existing remake-label test coverage exists (confirm
  exact test file at plan time).
- **Tablet**: `sanitizeMaterialMetadata()` copy-through test for `miscLabel`; `AppStateStore`
  derivation test for `incompleteMiscMaterials`/`isMisc`; Compose test confirming
  `CncMiscsSection` renders only when `incompleteMiscMaterials` is non-empty. Mirror existing
  `CncRemakesSection` test coverage if present.
- **HT worker**: `compute_cnc_progress` unit test for `isMisc` parity with `isRemake`;
  `misc_candidates_publish` test mirroring `remake_candidates_publish` tests.
- **Manual on-device**: split a PDF with an `m`-prefixed filename → confirm sidecar `miscLabel`
  written → confirm HT worker `cache_static.json`/`cache_index.json` show `isMisc: true` →
  confirm tablet dashboard shows a blue "Miscellaneous" section below Remakes, and the job-card
  status segment shows a blue misc marker.
