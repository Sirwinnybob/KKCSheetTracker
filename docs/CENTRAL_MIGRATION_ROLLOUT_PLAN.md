# KKCSheetTracker: One-Time Central Migration + Tablet Rollout Plan

## Why This Exists
This plan defines a **single central migration** of the shared Ready Jobs dataset, followed by a coordinated tablet app update.  
It is written so any engineer/agent can implement the migration tooling and app guardrails without additional product decisions.

## Current Repo Context
- Project type: Android app (`:app`) with Kotlin + Compose.
- Existing tracker data (legacy):
  - CNC: `<basePath>/<jobFolder>/CNC/.tracker/<tabletId>.json`
  - Hardwoods: `<basePath>/<jobFolder>/.metadata/hardwoods/.tracker/<tabletId>.json`
  - Legacy hardwood fallback may also exist at `<basePath>/<jobFolder>/Hardwoods/.tracker/<tabletId>.json`
- Existing action shapes are in:
  - `app/src/main/java/com/kkc/sheettracker/data/models/Models.kt`
    - `TabletProgress` + `TrackerAction` (CNC)
    - `HardwoodTabletProgress` + `HardwoodTrackerAction` (Hardwoods)
- Base path is user-configured in settings and persisted as:
  - Shared prefs key: `base_path` (see `MainActivity.kt` and `SettingsScreen.kt`)

## Target Outcome
1. Migration runs **once** against the shared dataset (desktop machine).
2. Migrated dataset contains:
  - Per-device compact event streams (max 300 events/device/job/mode).
  - Global app refresh feed bootstrap under `<basePath>/.appupdates`.
  - Migration markers/reports.
3. New app version refuses editable operation unless the dataset has a migration-complete marker.
4. No legacy read fallback after cutover.

## Non-Goals
- No backward compatibility with old app versions after cutover.
- No per-tablet migration logic.
- No mixed-version support window.

---

## Deliverable 1: Desktop Migration Command

## Where To Add It
Add a non-UI desktop migration tool in this repo. Recommended structure:
- New Gradle module: `:tools-migration` (JVM module).
- Entry point: `main()` command runnable from Gradle.

Acceptable alternatives:
- Kotlin CLI under an existing tooling module if created.
- A deterministic script (PowerShell/Python) only if it still writes the exact artifacts below and includes parity validation.

## Command Contract
Provide a command similar to:

```bash
./gradlew :tools-migration:run --args="--base-path 'D:/Sync/Ready Jobs' --max-events 300 --write-marker"
```

Required args/options:
- `--base-path` (required): root Ready Jobs folder.
- `--max-events` (optional, default `300`).
- `--dry-run` (optional): read/validate only, no writes.
- `--jobs` (optional): comma-separated job folder filter.
- `--force` (optional): re-run even when per-job marker exists.
- `--write-marker` (optional): writes global completion marker when no failures.

Exit behavior:
- Exit `0` if all migrated/validated.
- Exit non-zero if any failure (or only warnings in strict mode if implemented).

---

## Deliverable 2: New Data Artifacts

## Global Refresh Bootstrap
Under `<basePath>/.appupdates`:
- `<basePath>/.appupdates/README.md` (optional but recommended).
- `<basePath>/.appupdates/migration_summary.json`.
- `<basePath>/.appupdates/migration_complete.json` (only when all jobs pass).
- `<basePath>/.appupdates/<tabletId>/signals.ndjson` (create empty file if tablet discovered but no signal to seed).

## Per-Job Migration Artifacts
For each job folder:
- Backup root:
  - CNC backups under job: `CNC/.tracker/.backup_migration_v1_<timestamp>/...`
  - Hardwoods backups under job: `.metadata/hardwoods/.tracker/.backup_migration_v1_<timestamp>/...`
- Marker/report files:
  - `<job>/CNC/.tracker/.migration_v1.json`
  - `<job>/.metadata/hardwoods/.tracker/.migration_v1.json`

Marker JSON minimum fields:
- `schemaVersion`
- `jobFolder`
- `mode` (`CNC` or `HARDWOODS`)
- `startedAt`
- `completedAt`
- `status` (`success` or `failed`)
- `sourceFiles`
- `sourceActionCount`
- `migratedActionCount`
- `parity` object
- `errors` (array)

---

## Event Stream Format (New)

Use NDJSON per device stream (one JSON object per line), sorted by deterministic replay order.

Required fields per event:
- `eventId`: globally unique string (suggest `"<tabletId>:<mode>:<job>:<ordinal>"` or UUID).
- `tabletId`
- `mode`: `CNC` or `HARDWOODS`
- `jobFolder`
- `targetKey`: logical target key.
- `op`: normalized operation name.
- `payload`: object (operation-specific values).
- `lamport`: monotonic integer within stream.
- `wallTime`: ISO-8601 timestamp (preserve source when possible).
- `source`: `"migration_v1"`.

Retention:
- Keep only newest `maxEvents` (default `300`) per device/job/mode stream.

Atomic writes:
- Always write `*.tmp` then atomic rename over final file.

---

## Legacy -> New Normalization Rules

## CNC Legacy
Source action: `TrackerAction(file,page,part,action,timestamp,fileFingerprint)`

Map to:
- `targetKey`:
  - sheet-level: `cnc|<file>|<page>|<fileFingerprintOrLegacy>`
  - part-level: `cnc_part|<file>|<page>|<part>|<fileFingerprintOrLegacy>`
- `op` mapping:
  - `complete` -> `set_complete_true`
  - `uncomplete` -> `set_complete_false`
  - `skip` -> `set_skipped_true`
  - `unskip` -> `set_skipped_false`
  - `bad_part` -> `set_bad_part_true`
  - `unbad_part` -> `set_bad_part_false`
- `payload`:
  - include `file`, `page`, `part` when present, and `fileFingerprint`.

## Hardwoods Legacy
Source action: `HardwoodTrackerAction(docType,rowId,totalsKey,action,value,timestamp)`

Map to:
- `targetKey`:
  - row-scoped: `hw_row|<docType>|<rowId>`
  - totals/board stock: `hw_totals|<totalsKey>`
- `op` mapping:
  - `set_done_count`
  - `set_bad_count`
  - `set_skipped`
  - `clear_skipped`
  - `add_totals_rip10_done_count`
  - `set_totals_rip10_done_count`
- `payload`:
  - include `docType`, `rowId`, `totalsKey`, `value`.

Unknown/invalid legacy actions:
- Skip and log in per-job errors.

---

## Parity Validation (Must Pass)

After converting each mode for each job:
1. Build **legacy-derived state** by replaying source actions with current app semantics.
2. Build **migrated-derived state** by replaying new events.
3. Compare parity:
   - CNC: per sheet/page status and bad-part sets (for each fingerprint domain).
   - Hardwoods: row progress map, skipped cabinet map, totals/rip counts.
4. If mismatch:
   - Mark mode/job migration as failed.
   - Keep backup.
   - Do not write mode success marker.

Global success criteria:
- All selected jobs and both modes pass parity.
- Only then write `<basePath>/.appupdates/migration_complete.json`.

---

## Fail-Closed Behavior in App (Startup Guard)

Implement in app startup:
- On app boot, compute `basePath` as currently done.
- Check for marker file:
  - `<basePath>/.appupdates/migration_complete.json`
- If marker missing:
  - App enters read-only gate screen with clear message:
    - Dataset not migrated.
    - Run central migration command.
    - Do not perform edits.
  - No legacy fallback reads.

Recommended implementation location:
- `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`

Recommended behavior detail:
- Either show a dedicated blocking screen or disable edit actions globally.
- Blocking screen is preferred for deterministic fail-closed enforcement.

---

## Operational Runbook

1. Freeze editing on all tablets (close app).
2. Run migration command on central/shared dataset.
3. Review global report:
   - `migration_summary.json`
   - Ensure zero failed jobs/modes.
4. Resume Syncthing and allow artifacts to propagate.
5. Update all tablets to new app version.
6. Launch app and verify migration marker is detected.
7. Re-enable normal shop workflow.

---

## Rollback Strategy

If migration fails or parity mismatches:
- Do not write global `migration_complete.json`.
- Restore per-job tracker files from `.backup_migration_v1_<timestamp>`.
- Re-run migration after fixing parser/normalization issue.

If tablets already updated and marker absent:
- App should remain blocked/read-only by design, preventing inconsistent edits.

---

## Acceptance Criteria Checklist

- [ ] Desktop migration command exists and is documented.
- [ ] Command supports dry-run and full run.
- [ ] Per-job backups are created before writes.
- [ ] Event streams are written atomically.
- [ ] Event streams compact to 300 entries/device/job/mode.
- [ ] Per-job mode markers include parity results and errors.
- [ ] Global summary is written.
- [ ] `migration_complete.json` is written only when all pass.
- [ ] App enforces migration marker at startup (fail-closed).
- [ ] No legacy fallback reads after cutover.

---

## Suggested Report Schemas

## Global Summary (`<basePath>/.appupdates/migration_summary.json`)
```json
{
  "schemaVersion": "migration_v1",
  "basePath": "D:/Sync/Ready Jobs",
  "startedAt": "2026-05-08T12:00:00Z",
  "completedAt": "2026-05-08T12:03:31Z",
  "maxEvents": 300,
  "jobsScanned": 142,
  "jobsSucceeded": 141,
  "jobsFailed": 1,
  "modeResults": {
    "CNC": { "succeeded": 141, "failed": 1 },
    "HARDWOODS": { "succeeded": 140, "failed": 2 }
  },
  "failures": [
    { "jobFolder": "1234 - Example", "mode": "HARDWOODS", "reason": "Parity mismatch on totals key ..." }
  ]
}
```

## Completion Marker (`<basePath>/.appupdates/migration_complete.json`)
```json
{
  "schemaVersion": "migration_v1",
  "completedAt": "2026-05-08T12:03:31Z",
  "jobsSucceeded": 142,
  "jobsFailed": 0,
  "maxEvents": 300
}
```

---

## Notes For Implementers
- Reuse existing model semantics from `ProgressStore` and `HardwoodsProgressStore` when building parity logic.
- Keep ordering deterministic:
  - Primary sort: timestamp
  - Tie-breaker: source filename + source index
- Preserve legacy timestamps where available.
- Never mutate source files before backup completes successfully.

---

## External Tooling Required for Cutover

This rollout also depends on external scripts outside this repo.  
Do not cut over production until these are verified against the migrated dataset.

## 1) Ready Jobs Watcher (`C:\Scripts\Ready Jobs Watcher`)

### Key Metadata Outputs
- `ready_jobs_watcher/cabinet_sheet_indexer.py`
  - Writes: `<job>/.metadata/cabinet_sheet_index.json`
  - Entry points:
    - `build_reference_index_for_job(...)`
    - `build_reference_index_for_pdf_event(...)`
- `ready_jobs_watcher/hardwoods_cutlist_indexer.py`
  - Writes: `<job>/.metadata/hardwoods/cutlist_index.json`
  - Entry points:
    - `build_hardwoods_cutlist_index_for_job(...)`
    - `build_hardwoods_cutlist_index_for_pdf_event(...)`

### Tracker-Driven Consumers
- `ready_jobs_watcher/tracker_bad_parts.py`
  - Reads CNC tracker streams from:
    - preferred: `<job>/CNC/.tracker/events/*.ndjson` (including nested event files)
    - fallback: `<job>/CNC/.tracker/*.json`
  - Uses CNC sidecar metadata at `<job>/CNC/.metadata/<pdf-base>.json`
- `ready_jobs_watcher/remake_candidates_indexer.py`
  - Reads CNC tracker streams from:
    - preferred: `<job>/CNC/.tracker/events/*.ndjson`
    - fallback: `<job>/CNC/.tracker/*.json`
  - Writes precomputed candidates:
    - `<job>/CNC/.metadata/remake_bad_parts_candidates.json`
- `ready_jobs_watcher/hardwoods_cutlist_indexer.py`
  - Reads Hardwoods tracker streams from:
    - preferred: `<job>/.metadata/hardwoods/.tracker/events/*.ndjson`
    - fallback: `<job>/.metadata/hardwoods/.tracker/*.json` and `<job>/Hardwoods/.tracker/*.json`
- `ready_jobs_watcher/watchers.py`
  - Tracker file trigger monitors:
    - legacy tracker JSON under `\\cnc\\.tracker\\*.json`
    - migrated event streams under `\\cnc\\.tracker\\events\\*.ndjson`
  - Debounces tracker scans and refreshes remake candidates.

### Cutover Implication
- If tracker format/path changes, update:
  - `tracker_bad_parts.py` file iteration and parser.
  - `remake_candidates_indexer.py` action collection.
  - `watchers.py` tracker path/type trigger logic.
- Validate that generated:
  - `cabinet_sheet_index.json`
  - `cutlist_index.json`
  - `remake_bad_parts_candidates.json`
  still match expected schema after cutover.

## 2) PGM Sorter (`C:\Scripts\PGM_Sorting\sort_pgm_all_runs_gui_v2.py`)

### Metadata/Config Touchpoints
- Reads/writes local material alias map:
  - `material_mappings.json`
- It does **not** produce CNC page sidecar metadata directly.
- Primary role is pre-split organization and naming pipeline.

### Cutover Implication
- Low-risk for tracker migration itself.
- Still include in runbook sanity because naming/material mapping can change downstream metadata labels.

## 3) PDF Splitter (`C:\Scripts\PGM_Sorting\split_pdfs_gui_v2.py`)

### Key Metadata Outputs
- Writes per-PDF CNC sidecar:
  - `<job>/CNC/.metadata/<pdf-base>.json`
- Sidecar includes fields used by app/watcher:
  - `jobNumber`, `jobName`, `material`, `pdfFilename`
  - `pages[]` with `pageNumber`, `sheetId`, `sheetFiles`, `sheetDimensions`, `parts[]`
  - optional `thumbnailPath`, `ocrBoxes`, `ocrSource`, `ocrGeneratedAt`, `ocrVersion`
  - continuation flags like `logicalSheetKey`, `isPartListContinuation`, `continuationHeadPage`, `trackingExcluded`, `hiddenInApp`
- Writes page thumbnail assets:
  - `<job>/CNC/.metadata/.thumbs/*.png`

### Remake Candidate Consumption
- Preferred source:
  - `<job>/CNC/.metadata/remake_bad_parts_candidates.json` (from Ready Jobs Watcher)
- Fallback source:
  - Reconstructs from tracker actions with precedence:
    - `<job>/CNC/.tracker/events/*.ndjson`
    - then `<job>/CNC/.tracker/*.json`

### Cutover Implication
- If tracker format/path changes, update fallback reconstruction code in `_load_unresolved_bad_parts(...)`.
- Keep candidate file schema aligned with `remake_candidates_indexer.py`.

## 4) Run Folder Processor (`C:\Scripts\PGM_Sorting\process_run_folders_v2.py`)

### Metadata/Tracker Touchpoints
- Remake resolution (`resolve_remade_bad_parts(...)`) deduplicates against existing tracker actions.
- Tracker read precedence:
  - `<job>/CNC/.tracker/events/*.ndjson`
  - fallback `<job>/CNC/.tracker/*.json`
- Tracker write behavior:
  - migrated mode: appends `set_bad_part_false` events to `<job>/CNC/.tracker/events/desktop-remake-processor.ndjson`
  - legacy mode fallback: writes to `<job>/CNC/.tracker/desktop_remake_processor.json`

### Cutover Implication
- Keep event op mapping aligned with app/watcher CNC semantics.
- Verify remake resolution still clears bad-part state correctly on migrated datasets.

---

## External Validation Checklist (Must Pass Before Tablet Update)

- [ ] Ready Jobs Watcher can still parse tracker/action source used post-migration.
- [ ] `remake_bad_parts_candidates.json` is produced for active jobs.
- [ ] `split_pdfs_gui_v2.py` remake flow works using watcher candidate file.
- [ ] `split_pdfs_gui_v2.py` fallback tracker parsing works or is intentionally disabled with clear operator guidance.
- [ ] `process_run_folders_v2.py` remake resolution writes expected tracker updates in migrated mode.
- [ ] `cabinet_sheet_index.json` and `hardwoods/cutlist_index.json` regenerate correctly.
- [ ] Sidecar metadata schema remains compatible with app consumers (`JobRepository`, viewer screens, bad-parts monitoring).
