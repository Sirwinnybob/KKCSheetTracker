---
name: kkc-metadata-map
description: >-
  Use when tracing KKC Ready Jobs metadata ownership or parity, stale tablet
  job data, missing or archived jobs, CNC or hardwood tracker streams
  (ndjson events), Syncthing conflicts, tablet request sidecars, Hours
  Tracker live WebSockets or admin-sync, the ported Ready Jobs worker
  (ready_jobs_worker_core, worker.log, job_errors.json), supply
  schema/status/comments/barcodes, safety concern reports/status/comments,
  .time_cards, timeclock API fields, production_order.json,
  delivery_schedule.json, update-feed metadata, admin metadata,
  molding/moulding profile, dimension-override, or frame-style tag files,
  PGM Mix Service definitions/operations/API, or deciding whether
  KKCSheetTracker, Hours Tracker (web app or worker), Ready Jobs Watcher,
  timeclock-hub, updater-agent, PGM Sorting, the CNC PGM Mix Service, or
  cv-molding-sync owns a file.
metadata:
  sync:
    version: 3
---
# KKC Metadata Map

## Overview

Use this skill to answer: "Which system owns this metadata file, where is the source of truth, and where should debugging start?" Prefer owner evidence over guessing from symptoms.

> **Deep reference:** a full evidence-backed cross-program audit of the shared metadata contract
> (with write-safety analysis and an open-issue register) lives at
> `C:\Scripts\Hours Tracker\METADATA_AUDIT.md`. When this map and the code disagree, trust the code,
> fix the code, then update BOTH this skill and that audit doc (rules are in the audit's §1.4).
> Last full verification of every row against code HEAD + live `Y:\Ready Jobs`: 2026-09-30
> (audit SK-09..SK-16).

> **Mirror sync:** canonical file is `C:\Users\chadc\.sync-skills\skills\kkc-metadata-map\SKILL.md`;
> `C:\Users\chadc\.claude\skills\kkc-metadata-map` is a junction to that folder, so editing either
> path edits the same file. The six per-repo `.claude`/`.agents` copies of this skill (Hours Tracker,
> Ready Jobs Watcher, KKCSheetTracker) and the global Codex copy (`~\.codex\skills\kkc-metadata-map`)
> are kept byte-identical to it automatically by `C:\Users\chadc\.sync-skills\sync-kkc-metadata-map.ps1`,
> triggered on every edit via a PostToolUse hook in the global Claude Code settings. Only `SKILL.md`
> is mirrored (each mirror's `agents\` subfolder is not). Edit only the canonical copy — the mirrors
> are overwritten (repo mirrors auto-committed), so manual edits to a mirror will be silently replaced.

**Abbreviations used below:** **HT** = `C:\Scripts\Hours Tracker\backend\` (web app `main_v2.py` + `routes\`). **W** = HT's ported Ready Jobs worker, `C:\Scripts\Hours Tracker\backend\ready_jobs_worker_core\` (`adapters\` is under it). **RJW** = deprecated Ready Jobs Watcher. **KST** = KKCSheetTracker `app\src\main\java\com\kkc\sheettracker\`.

## System Boundaries

| System | Role | First Path |
|---|---|---|
| KKCSheetTracker Android | Reads shared Ready Jobs metadata, writes tablet progress (ndjson event streams), requests, markup, crash/perf reports, local app state | `C:\Scripts\KKCSheetTracker` |
| Hours Tracker web app | Digital hours/admin metadata, global Ready Jobs admin files, operator API for gates/archive, live WebSockets | `C:\Scripts\Hours Tracker` (`backend\main_v2.py`, `backend\routes\`) |
| Hours Tracker worker (W) | **Live writer since the 2026-08-18/19 cutover** for every Ready-Jobs-publishing row below (gates, caches, trackers, cutlists, cabinet index, dark mode, GLB, sync conflicts, archive moves) | `C:\Scripts\Hours Tracker\backend\ready_jobs_worker_core`; container `hourtracker-worker` |
| Hours Tracker Android app | Digital timecard tablets (`com.example.timecard`); **separate git repo**, gitignored by HT, so `git grep` from the HT root never sees it | `C:\Scripts\Hours Tracker\AndroidApp` |
| Ready Jobs Watcher | **DEPRECATED** — reference only (kept so old logic can be ported). Only relevant if the user explicitly says they're working on old RJW | `C:\Scripts\Ready Jobs Watcher` |
| timeclock-hub | RTC-1000 punch clock REST hub and SQLite source of truth for punch-clock timeclock; pushes the employee roster to HT | `C:\Scripts\timeclock-hub` |
| updater-agent | Android helper for installs/silent update behavior | `C:\Scripts\KKCSheetTracker\updater-agent` |
| PGM Sorting | PDF splitter (CNC page/part sidecars, part-graphic zips) and run-folder remake processor (writes into CNC tracker streams) | `C:\Scripts\PGM_Sorting` (`split_pdfs_gui_v3.py`, `process_run_folders_v2.py`) |
| CNC PGM Mix Service | CNC-side REST service that orders existing `.pgm` files into compiled `.mix` files through WINXISO; does **not** write Ready Jobs metadata | `C:\Scripts\PGM_BCR_Loader\docs\PGM_MIX_SERVICE_AGENT_GUIDE.md`; deployed on CNC at `C:\Scripts\PGM_MixService` |
| cv-molding-sync | Scheduled script on the Cabinet Vision PC; sole writer of `.metadata\moldings\*.xml` from CV's local SQL Server | `C:\Scripts\cv-molding-sync` |

Shared Ready Jobs appears on the PC as `Y:\Ready Jobs`, on the HT Docker server as `/mnt/KKC/Syncthing/KKC Jobs/Ready Jobs` (mounted `/jobs`), and to cv-molding-sync as `\\192.168.1.15\KKC Jobs\Ready Jobs`. Archived jobs live in `/mnt/KKC/Syncthing/KKC Jobs/ARCHIVE_Ready Jobs` (`ARCHIVE_READY_JOBS_PATH`, mounted `/archive-jobs`).

Cabinet Vision (external CAD database, not one of the systems above) is the source of truth for molding profile geometry. **As of 2026-09-28 the molding sync is `C:\Scripts\cv-molding-sync\cv_molding_sync.py`** on the CV PC (CV's SQL Server `.\CV24`/`CVData` exists only there), run every 15 min and at logon by Task Scheduler task `KKC CV Molding Sync`. It is NOT part of W: moldings sync was descoped from the port, and nothing wrote `.metadata\moldings` from 2026-08-18 until 2026-09-28. Fail-safe: exit codes 0/1/2; an unreachable share aborts first; zero rows or a CV error writes/deletes nothing.

> **RJW → W cutover:** W (`backend\ready_jobs_worker_core`) is a from-scratch port of RJW, merged 2026-08-18 (`d35f635e`) and cut over in production via `ops\docker-compose.production-cutover.yml` (first committed 2026-08-19) after a shadow-write soak (full 4.4 GB real-tree cold boot, 0 errors; 15/16 jobs byte-exact `cache_static`/`cache_index`). It is THE live writer for gates, cache_static/cache_index, CNC/hardwoods consolidation, cabinet index, dark-mode PDFs, 3D GLB, sync-conflict resolution, duplicate-folder guard, rename/reparse, gate timers, hidden-gate bootstrap, construction-mode detection, remake/misc bad-parts candidates, bad-parts monitor, CNC orphan cleanup, file-prefix rename, and the Ready Jobs archive/restore mover. It runs as its own container (`hourtracker-worker`, `python -m ready_jobs_worker_core`; healthcheck `ready_jobs_worker_core.healthcheck`; `stop_grace_period: 60s`), sharing the web app's `/data` state dir and `/jobs` mount. Real writes need `READY_JOBS_WORKER_ENABLED=1`, `READY_JOBS_DRY_RUN=false`, `HOURS_TRACKER_SHARED_READ_ONLY=0`. The web container (`hourtracker`, host port 47821 → 5002) refuses to run a real writer in-process (`main_v2.py` `_start_ready_jobs_worker_once`); it serves the operator API and relays worker data to WebSockets via relay files in the state dir. **An agent cannot verify the live server state** — default to "W is live"; only work on old RJW if the user explicitly says so. Old RJW rows/paths are kept as reference.

## Hours Tracker Live/API Channels (not files)

| Channel | Server | Tablet side | Notes |
|---|---|---|---|
| WS `/api/supply/live` | `routes\supply_live.py`; `SupplyLiveMonitor` (`W\adapters\supply_live_monitor.py`, started in-process by `main_v2.py`) polls `.supply` every 2 s; `routes\supply_live_document.py` builds a read-only model | `SupplyLiveClient` → `SupplyLiveStateStore` | Tablet `.supply` writes are unchanged (files + Syncthing); own writes show via a 2-minute local overlay. Socket down → tablet reads files. Stale on one tablet: logcat `SupplyLiveClient`; everywhere: HT logs "Supply document build failed" / "Supply tree unavailable" |
| WS `/api/ready-jobs-worker/live-index` | `routes\ready_jobs_worker_live_index.py` (relay `live_index_relay.json` from W) | `LiveIndexClient` | Live `cache_index` progress for the tablet job list |
| WS `/api/delivery-schedule/live`, `/api/hidden-materials/live` | `routes\delivery_schedule_live.py`, `routes\hidden_materials_live.py` (W `adapters\*_live_service.py`) | tablet live clients | Stale schedule / hidden materials on one tablet: check the socket before the files |
| WS `/api/ready-jobs-archive/library/live` | archive library relay (`archive_library_relay.json`) | `ArchiveLibraryClient` | Archived-job library |
| WS `/api/ready-jobs-worker/live`, `/bad-parts-live` | `routes\ready_jobs_worker_live.py`, `ready_jobs_worker_bad_parts_live.py` | web UI only | — |
| `POST /api/admin-sync/{production-order,job-board-edits,delivery-schedule,hardwoods-hidden-materials}` | `main_v2.py` | tablet tries these first (DataStore `admin_sync_config`), falls back to request sidecar files | Direct-write fast path for the request files in the Ownership Map |
| `/api/ready-jobs-worker/{status,logs?tail=N,jobs}`, `POST .../jobs/{folder}/{release,hide,show,mode,detected-mode,rename,reparse,schedule-deploy,remind-later,...}` | `routes\ready_jobs_worker_{status,jobs,operations}.py` | web UI (replaces RJW GUI's per-job dialog) | Mutations need `READY_JOBS_MUTATIONS_ENABLED=1`; each is queued/logged in `hours.db` table `ready_jobs_operations` |
| `POST /api/ready-jobs-archive/{archive,restore}/{folder}` (+ `collision-preview`) | `routes\ready_jobs_archive_lifecycle.py` → `ready_jobs_operations` queue → W `adapters\archive_lifecycle.py`, `archive_scheduling.py` | `ArchiveAdminClient`; 24 h cache `cacheDir/archive-cache/<id>` | Moves job folder to/from the archive root and removes/restores it in `production_order.json`, `job_board.json`, `delivery_schedule.json` |

## CNC PGM Mix Service: Agent Routing

Use this service when a CNC/tablet workflow needs to create, inspect, reorder, or regenerate a **`.mix` sequence** from existing PGM files. It is not a Ready Jobs metadata publisher and does not generate replacement `.pgm` source files. The tablet uses it via `KST data\mixservice\MixServiceClient.kt` (session state DataStore `mix_operation_sessions`).

- **Agent API reference and examples:** `C:\Scripts\PGM_BCR_Loader\docs\PGM_MIX_SERVICE_AGENT_GUIDE.md`.
- **Deployed service on the CNC:** `C:\Scripts\PGM_MixService` (network: `\\192.168.20.4\cnc\Scripts\PGM_MixService`), Windows service `PGMMixService` under NSSM (`--no-tray`; `deploy\install_service.ps1`, remote start/stop scripts).
- **Runtime endpoint:** `http://<cnc-ip>:8477`; first check `GET /status`. Unauthenticated on the CNC LAN — do not expose it beyond that network.
- **Mutations are async (since 2026-09-09):** `POST /mixes`, `PUT /mixes/{name}` and the revision-safe `POST /jobs/{job}/materials/{material}/mixes` / `.../{name}/replace` return **202 + an operation**; poll `GET /operations/{id}` (or `GET /jobs/{job}/operations`) before assuming the `.mix` exists.
- **State ownership:** `definitions.json` owns mix definitions and ordered PGM lists; `operations.json` persists operation history; `config.json` owns paths/limits; `work\` is scratch. Logs: `logs\service.log` (primary), NSSM `logs\service-stdout.log`/`service-stderr.log`. There is **no** `error.log` (the guide is wrong on that).
- **PGM files:** the service itself never writes PGMs. The opt-in second-pass API (`POST /jobs/{job}/materials/{material}/pgm-edits`, `second_pass_enabled` default **False**) has `2nd Pass Only.exe` mutate them, and mix changes sync `.pgm_edit_history.json` sidecars (writer `C:\Scripts\G_Code_2nd_Pass\pgm_edit_history.py`). `tools\backfill_active_pgm_history.py` reads `Y:\Ready Jobs\production_order.json` read-only.
- **Safe reorder pattern:** send the complete desired remaining order, or create a new named mix; prefer a new descriptive name when the prior cut order matters. WINXISO's transient `.xxl` and sibling `.bmp` previews are service cleanup artifacts, not tablet metadata.

## Ownership Map

| Metadata / Path Pattern | Owner | First Debug Check |
|---|---|---|
| `Y:\Ready Jobs\<job>\.metadata\deployment_gate.json` | W (`adapters\deployment_gate_write.py`, bootstrap `deployment_gate_bootstrap.py`, timers `auto_release.py`) **and** HT operator API (release/hide/show/mode/rename/reparse/schedule-deploy via `routes\ready_jobs_worker_jobs.py`; office "sent to floor" uses the same release). Tablet reads (`DeploymentGate.kt`) | Schema is frozen (Android reads it). Visibility = `deployed` (`W\gate_contract.py`); released jobs normally stay `parseReady:false` (W sets it true only on re-parse). HT `hide` DOES set `hiddenFromProduction=true`, and release tablet builds hide those jobs. Cross-host lock `.deployment_gate.lock`. Check fields, then `job_errors.json`/`worker.log`, then the `ready_jobs_operations` row |
| `Y:\Ready Jobs\<job>\.metadata\cache_static.json` | W (`adapters\cache_publish.py`, skip-if-unchanged; `publish_jobs.py`, `job_read.py`) | HT web app only reads (legacy writer gated off, `HOURS_TRACKER_ENABLE_LEGACY_CACHE_WRITES=1`). Re-parse deletes it. Check mtime/content, then `job_errors.json`/`worker.log`. Source PDFs, CNC sidecars, and non-tracker job metadata promote the job to a 30 s cache refresh (`READY_JOBS_SOURCE_CACHE_REFRESH_DELAY_SECONDS`); tracker bursts retain the ~180 s debounce and ~300 s sweep. |
| `Y:\Ready Jobs\<job>\.metadata\cache_index.json` | W (`adapters\cache_index_publish.py`; payload `cache_index_payload.py`: `compute_cnc_progress`, `compute_hardwood_progress`, `build_cache_index`) | Small list index (a few KB vs MBs for cache_static), written separately with skip-if-unchanged, so its mtime need not match cache_static. `progressSummary`: per-material CNC done/bad/skipped/renested; hardwoods per docType incl. `*Pieces`, **excluding hidden hardwood materials** (reads both `hidden_materials` files). `totalSheets` = trackable metadata pages (excludes `hiddenInApp`/`trackingExcluded`/`isPartListContinuation`; falls back to physical `pageCount`). `jobInfo` adds `isMisc`, `hasDeliverySheet`, `has3DAssets`, `lineupPosition`. Tablet list reads it (and live via `/live-index`); **no cache_static fallback** — a gated-in job without an index goes to the `needsDeep` list (`FileBackedUnifiedMetadataEngine.listJobsFromCacheIndex`) |
| `Y:\Ready Jobs\<job>\CNC\.metadata\<pdf-stem>.json` | PGM Sorting PDF splitter (`split_pdfs_gui_v3.py`) | Canonical CNC page/part/OCR sidecar; W/RJW consume but do not author it. Tagged-v1 sidecars intentionally omit legacy OCR boxes. W deletes orphan sidecars/thumbs with no matching PDF after a 30-min grace (`adapters\cnc_orphan_cleanup.py`). Per page: `thumbnailPath`, `diagramPath` (since 2026-09-30: lossless gray PNG of the sheet image, same pixels as the PDF's), `ocrBoxes` in `ocrImageWidth` x `ocrImageHeight` pixels. Since 2026-09-30 the splitter embeds the sheet image as gray PNG at CV source size (~2450 wide) and OCR boxes are 1:1; older jobs embed a 2x-upscaled ~5100-wide JPEG with 2x boxes. Tablet maps boxes by bitmap width / `ocrImageWidth` (`SheetViewerScreen.kt` `loadCncSidecarDiagram`) |
| `Y:\Ready Jobs\<job>\CNC\.metadata\parts\<stem>.zip`, `parts\*` | PGM Sorting splitter | Part-graphic archive; same owner as the sidecar |
| `Y:\Ready Jobs\<job>\CNC\.metadata\remake_bad_parts_candidates.json` | W (`adapters\remake_candidates_publish.py`) | Read by PGM Sorting (`split_pdfs_gui_v3.py`); HT specialty skips it; tablet ignores it. Check `worker.log` |
| `Y:\Ready Jobs\<job>\CNC\.metadata\misc_bad_parts_candidates.json` | W (`adapters\misc_candidates_publish.py`, added 2026-09-02) | Misc-job remake candidates. Tablet builds without the fix treat a change to this file as making `cache_static.json` stale (the stale-check skip list only names `remake_bad_parts_candidates.json`) |
| `Y:\Ready Jobs\<job>\CNC\.tracker\events\<writerId>.ndjson` | **LIVE channel.** KST tablets append (`ProgressStore.kt`, since `633c182e` 2026-07-09); PGM Sorting appends `desktop-remake-processor.ndjson` (`unbad_part`, `process_run_folders_v2.py`); W reads (`adapters\tracker_action_reader.py`) | Append-only per writer. Ordered by `(timestamp, lamport, eventId)`. W never rotates/deletes these (compaction was not ported; streams grow). `reNested: true` on a skip = sheet re-exported into a REMAKE PDF |
| `Y:\Ready Jobs\<job>\CNC\.tracker\<tablet>.json` | **Legacy** — tablets no longer write it (read-only union input, `ProgressStore.kt`); PGM Sorting still writes `desktop_remake_processor.json` | W folds any present into `consolidated.json` then deletes them. A missing `<tablet>.json` on a current tablet is expected, not a bug |
| `Y:\Ready Jobs\<job>\CNC\.tracker\consolidated.json` | W (`adapters\tracker_consolidated_publish.py`; merge `cnc_tracker_merge.merge_cnc_actions`), under `.tracker\.consolidate.lock` | Merged actions incl. `reNested`; C-01 fix holds (re-emits `bad_part` + `bad_part_submitted` with original timestamps). Check `worker.log` |
| `Y:\Ready Jobs\<job>\CNC\.tracker\watcher_refresh_watcher.json` | W (`adapters\refresh_signal_publish.py`) | Change signal (`source`/`reason`/`jobFolderName`/`updatedAt`) after a publish — not a heartbeat, not progress |
| `Y:\Ready Jobs\<job>\.metadata\hardwoods\cutlist_index.json`, `cutlist_revisions.json` | W (`adapters\hardwoods_cutlist_publish.py`, transactional with journal `.cutlist_publication_transaction.json`) | Compare against hardwood source PDFs, then `worker.log` / `worker_status.json` `hardwoodsPublicationOutcomes` |
| `Y:\Ready Jobs\<job>\<job#> - {Face Frame,Door,Nailer,Closet Rod} Cut List.pdf` (root) | Cabinet Vision export by hand, **or** W (`adapters\combined_cutlist_split.py`, rules `combined_cutlist.py`, added 2026-10-06) when the job has `<job#> - Combined Cut List.pdf` | W splits the combined export at each page whose top line is a section title (untitled pages continue the section) BEFORE hardwoods discovery, in sweep, per-job refresh and reparse. Ownership ledger `.metadata\.combined_cutlist_split.json` (dot file = no refresh trigger); W replaces/removes a per-type file only if the ledger says it wrote those exact bytes, so a hand-placed file of the same type blocks that section with a job error naming it (delete it to apply). Door List is not a combined section. Discovery's closet-rod text fallback skips the combined file. Split output is byte-stable for an unchanged source |
| `Y:\Ready Jobs\<job>\.metadata\hardwoods\cutlist_job_mismatch.json` | W (`hardwoods_cutlist_publish.py`) | Printed job number on a cutlist PDF's page 1 ≠ folder; mismatched doc's rows excluded from the index for that pass. Deleted once every mismatch clears. Operator overrides live in `cutlist_job_mismatch_overrides.json` (below). Badge is in the HT web UI |
| `Y:\Ready Jobs\<job>\.metadata\hardwoods\cutlist_job_mismatch_overrides.json` | HT API → W `adapters\cutlist_job_mismatch_store.py` | Operator "accept anyway" ledger for mismatches |
| `Y:\Ready Jobs\<job>\.metadata\hardwoods\blank_hardwoods_documents.json` | HT API / W (`adapters\hardwoods_blank_document_store.py`) | Dismissible warnings for genuinely blank required docs (W publishes with a warning instead of aborting) |
| `Y:\Ready Jobs\<job>\.metadata\hardwoods\board_stock_manual.json` | **writer external/unconfirmed**; W, HT, tablet READ-ONLY | Manual board-stock input folded into `cache_static.json` (audit SK-05) |
| `Y:\Ready Jobs\<job>\.metadata\hardwoods\.tracker\events\<tabletId>.ndjson` | **LIVE channel.** KST tablets (`HardwoodsProgressStore.kt`, atomic rewrite, since `6a282e54` 2026-07-10); W reads | Legacy `<tablet>.json` is folded into ndjson and deleted by the tablet. A `batch-sync-worker` writer is NOT a tablet: it was the old KST unit test `BatchSyncTest` (removed on main `d9fbd52c`, 2026-09-30). Its streams in jobs 106/592/644d/669 were deleted 2026-09-30; 59 entries it left in `consolidated.json` (7-digit fractional-second timestamps) all duplicate real tablet values |
| `Y:\Ready Jobs\<job>\.metadata\hardwoods\.tracker\<tablet>.markup.json` | KKCSheetTracker tablets (`HardwoodsProgressStore.saveTabletMarkup`, from `HardwoodsWorkspaceScreen`) | Hardwood ink markup; never action input. **Fixed 2026-09-30** (Hours Tracker `a620f8f1`): `W\adapters\tracker_device_file_cleanup.py` `_is_legacy_device_file_name` whitelists `<tabletId>.json`, so W's consolidation no longer deletes markup; W's legacy action reader and hardwoods cutlist read set also skip it. RJW's blacklist still deletes it. Live share had 0 on 2026-09-29, so markup saved before the W fix deployed is gone |
| `Y:\Ready Jobs\<job>\.metadata\hardwoods\.tracker\.board_stock_*_<tablet>.json` | KKCSheetTracker tablets | Hardwood board-stock migration markers |
| `Y:\Ready Jobs\<job>\.metadata\hardwoods\.tracker\watcher_refresh_watcher.json` | W (`hardwoods_cutlist_publish.py`) | Change signal after cutlist publish |
| `Y:\Ready Jobs\<job>\.metadata\hardwoods\hidden_materials.json` | HT (`routes\hidden_materials_store.py`); tablets (Hardwoods mode) read-only; W reads for cache_index | Per-job `hides`/`unhides`; the job's `unhides` always wins over a global or job hide |
| `Y:\Ready Jobs\<job>\.metadata\specialty\hidden_materials.json` | HT (same store, specialty mode subdir); tablets (Specialty mode) read-only | Independent of the Hardwoods sibling (same key space, never shares entries). Not the same subtree as `.metadata\admin\` specialty items |
| `Y:\Ready Jobs\<job>\.metadata\cabinet_sheet_index.json` | W (`adapters\cabinet_reference_publish.py`, parser `cabinet_reference_parser.py`) | Re-parse deletes it. Check root PDF mtimes, then `worker.log` |
| `Y:\Ready Jobs\<job>\.metadata\duplicate_suspect.json` | W (`adapters\duplicate_job_guard.py`) | Duplicate-folder guard marker; cleared via HT `DELETE .../duplicate-suspect` |
| `Y:\Ready Jobs\<job>\.metadata\sync_conflicts\<id>\manifest.json` (and `manifest-N.json`) | W (`adapters\sync_conflict_resolver.py`) | Per-job Syncthing conflict archive; same at root `Y:\Ready Jobs\.metadata\sync_conflicts\`. Orphan `manifest-N.json.<pid>.<hex>.tmp` files are leftovers |
| `Y:\Ready Jobs\.metadata\moldings\{Crown,Scribe,Base}\<profileId>.xml` | `cv-molding-sync` on the CV PC; HT reads only | Check `C:\Scripts\cv-molding-sync\cv_molding_sync.log` (one line per run), then `Get-ScheduledTaskInfo "KKC CV Molding Sync"`, then CV DB. Byte-compatible with RJW's output; skips rewrite when bytes match; prunes profiles that disappear or move category (audit SK-06, SK-08) |
| `Y:\Ready Jobs\.metadata\moldings_cache\{Crown,...}\<profileId>.svg`, `<profileId>_dim.svg`, `library.json`, `usage_index.json` | HT (`routes\molding_cache_publish.py`); tablet reads directly | SIBLING of `moldings\`, not a child (else it would appear as a CV category). The only bridge from HT's molding sidecars to the tablet: `library.json` carries `frameStyle` and `hidden` (from `molding_hidden.json`); `_dim.svg` bakes in dimensions. Rebuilt by `publish_library_cache()` on dimension/frame-style/hidden edits plus a 5-minute sweep; `usage_index.json` by `publish_usage_index()` (audit SK-07) |
| `Y:\Ready Jobs\<job>\.metadata\pdf_markup\.tracker\<tablet>.markup.json` | KKCSheetTracker tablets (`PdfMarkupStore.kt`) | Root/reference PDF markup |
| `Y:\Ready Jobs\<job>\.metadata\pdf_markup\.tracker\<tablet>.json` | Legacy — tablet only reads it as a fallback | Old PDF markup |
| `Y:\Ready Jobs\.metadata\crashes\<ts>_<tabletId>_crash.json` | KKCSheetTracker (`crash\CrashReportStore.kt`) | Root, not per-job. Match app version and route/screen |
| `Y:\Ready Jobs\.metadata\cpu_spikes\<ts>_<tabletId>_cpuspike.json` | KKCSheetTracker (`perf\CpuSpikeLogStore.kt`, added 2026-09-17) | Performance counterpart of `crashes\` |
| `Y:\Ready Jobs\.metadata\material_mappings.json` | **writer external/unconfirmed**; HT + tablet READ-ONLY | Door-panel/specialty mapping. A stray `material_mappings.json.lock` on Y suggests an unknown locking writer; PGM Sorting has a same-named LOCAL file (audit SK-04) |
| `Y:\Ready Jobs\.metadata\themes\active_theme.json`, `themes\*.json`, `themes\graphics\*.svg` | `kkc-theme-generator` skill → `C:\Scripts\KKCSheetTracker\themes\generated\*.json`, copied to the share **by hand**; tablet reads (`KKCThemeRepository.kt`) | Not HT-owned. Schema includes `category`, `boldMode`, `secondary`, `header.badgeText`/`badgeLogoPath` |
| `Y:\Ready Jobs\.metadata\timeclock_messages.json` | **external message tool** (no writer in any repo); tablet reads | Global shop/tablet timeclock messages (audit SK-03) |
| `C:\Scripts\PGM_MixService\definitions.json`, `operations.json` | CNC PGM Mix Service | Mix definitions + async operation history; inspect `GET /mixes`, `GET /operations/{id}` before recompiling |
| `C:\Scripts\PGM_MixService\config.json`, `logs\service.log`, `logs\service-{stdout,stderr}.log` | CNC PGM Mix Service | `GET /status`, then `service.log`; `config.json` must point at the CNC root and `WINXISO.EXE` |
| `Y:\Ready Jobs\production_order.json` | HT admin **and** W (archive removal/rollback `adapters\archive_lifecycle.py`, `archive_scheduling.py`; rename `job_rename.py`); W reads via `adapters\lineup_read.py` | Check HT admin state, then worker lineup refresh / archive operations |
| `Y:\Ready Jobs\production_order_request.<tabletId>.json` | KST tablet writes (after `/api/admin-sync` fails); HT consumes (`main_v2._apply_production_order_requests`) | Per-tablet; malformed → quarantined as `<name>.rejected`; transient failure must leave it for retry |
| `Y:\Ready Jobs\job_board.json` | HT admin (`routes\board.py`, `board` lock) **and** W archive/rename | Check HT admin UI/backend, then archive operations |
| `Y:\Ready Jobs\job_board_request.<tabletId>.json` | KST tablet writes; HT consumes (`_apply_job_board_edit_requests`) | Oldest-first; preserve on transient failure |
| `Y:\Ready Jobs\.metadata\delivery_schedule.json` | HT (`main_v2.py`, `routes\delivery.py`) **and** W archive removal; live WS `/api/delivery-schedule/live` | Check HT backend/admin, then the socket |
| `Y:\Ready Jobs\delivery_schedule_request.<tabletId>.json` | KST tablet writes; HT consumes (`_apply_delivery_schedule_request`) | Request at root; master under `.metadata`; preserve on transient failure |
| `Y:\Ready Jobs\.metadata\{hardwoods,specialty}\hidden_materials_global.json` | HT (`routes\hidden_materials_store.py`); tablets read-only; W reads (hardwoods) for cache_index | Global auto-hide lists per mode, never shared between modes. Check the live socket and the request poller before assuming a stale hide/unhide |
| `...\{hardwoods,specialty}\hidden_materials_request.<tabletId>.json` (global under `Y:\Ready Jobs\.metadata\`, job scope under `<job>\.metadata\`) | KST tablet writes; HT consumes (`main_v2.py` poller) | Durable per-tablet sidecar beside the targeted master; quarantine malformed, retry transient |
| `Y:\Ready Jobs\.supply\categories.json` | HT **and** tablets (tablets append categories, whole-list read-modify-write, audit H-07) | Supply category list/order |
| `Y:\Ready Jobs\.supply\schema.json` | HT; tablets read-only | Custom supply field schema |
| `Y:\Ready Jobs\.supply\items\<itemId>.json` | HT and tablets (create/update/**delete**) | Supply item record |
| `Y:\Ready Jobs\.supply\status\<itemId>.<tabletId>.json`, `<itemId>.admin.<hostname>.json` | Tablets and HT (per-writer names) | Latest parsed instant wins; conflict copies ignored |
| `Y:\Ready Jobs\.supply\comments\<itemId>\<commentId>.json` | Tablets and HT (per-UUID) | Supply item comments |
| `Y:\Ready Jobs\.supply\attachments\<itemId>\*` | HT **and** tablets (binary copy) | Supply item attachments |
| `Y:\Ready Jobs\.supply\barcodes.json` | HT (`supply_store.link_barcode`/`unlink_barcode`) **and** tablets (`SupplyBarcodeStore.kt`) | Whole-map read-modify-write, same risk class as H-07 |
| `Y:\Ready Jobs\.safety\concerns\<id>.json` | Tablets (`SafetyRepository.kt`) and HT (`POST /api/safety/concerns`) | Both use `safety_store.get_safety_dir()` → `get_base_path()` |
| `Y:\Ready Jobs\.safety\status\<id>.<tabletId\|server>.json` | Tablets and HT | Latest `at` wins across `<id>.*.json` (legacy `<id>.json` accepted; falls back to `updatedAt`/`createdAt`) |
| `Y:\Ready Jobs\.safety\comments\<id>\<commentId>.json`, `.safety\attachments\*` | Tablets and HT | Comment thread / photos |
| `Y:\Ready Jobs\.safety\safety_meetings\*.pdf`, `.safety\*.pdf` | Manual (SDS book, plans); tablet reads (`SafetyDocumentsScreenLogic.kt`) | Reference PDFs, not concerns |
| `Y:\Ready Jobs\<job>\.metadata\admin\rip_items.json` | HT (`routes\admin_store.py`) | Check HT admin state before Android |
| `Y:\Ready Jobs\<job>\.metadata\admin\checklist.json` | HT; consumes tablet `checklist_patch` sidecars at read time | `admin_store.get_checklist` merges `checklist_patch.<tablet>.json` and DELETES them — except inside `read_only_context()` (handoff `checklist` source / `GET /api/handoff/sources`), which merges in memory only (`admin_store.py:128,152,167`) |
| `Y:\Ready Jobs\<job>\.metadata\admin\checklist_patch.<tablet>.json` | Tablets write; HT admin GET CONSUMES | Tablet **item-field edit** overlay (text, cabinetNumbers, supplier, modelNumber, orderDate, trackingNumber, orderUrl, notes, qty, material, dims; H-04 fix). Completion itself goes to `admin\.tracker\<tablet>.json` |
| `Y:\Ready Jobs\<job>\.metadata\admin\specialty_items.json` | HT only (tablet edits arrive via the sidecar below; HT also stores `deletedTabletItemIds` here) | Check admin state, then pending `specialty_patch` sidecars |
| `Y:\Ready Jobs\<job>\.metadata\admin\specialty_patch.<tablet>.json` | Tablets write (`SpecialtyProgressStore.kt`); HT merges + DELETES (`specialty_store._apply_specialty_patches`) | Same consume-on-read hazard as `checklist_patch` |
| `Y:\Ready Jobs\<job>\.metadata\admin\deleted_specialty_items.json` | HT (`specialty_store.py`) | Deleted-item ledger |
| `Y:\Ready Jobs\<job>\.metadata\admin\rule_applications.json` | HT | Check HT admin rule code |
| `Y:\Ready Jobs\<job>\.metadata\admin\board_stock.json` | HT (`board_stock_store.py`); tablet + W read | Board stock/admin |
| `Y:\Ready Jobs\<job>\.metadata\admin\.tracker\<device>.json` | Tablets **and** HT (`.tracker\Admin.json`) | Specialty item/station completion state |
| `Y:\Ready Jobs\<job>\.metadata\admin\tablet_items_<tablet>.json` | Tablets (HT rewrites on delete, audit M-02b) | Tablet-created specialty items |
| `Y:\Ready Jobs\<job>\.metadata\admin\sheet_rip_done.json` | **Tablets (writer); HT READ-ONLY** (`board_stock_store.py:271-287`) | Shared filename, lost-update risk (H-04/SK-02) |
| `Y:\Ready Jobs\<job>\.metadata\admin\checklist_attachments\<itemId>\*` | HT; tablet reads | Checklist attachments |
| `Y:\Ready Jobs\<job>\.metadata\admin\specialty_attachments\<itemId>\*` | HT (`routes\admin.py`); tablet reads | Tablet resolves these via `resolveSpecialtyAttachmentFile` (`SpecialtyJobDetailScreen.kt`; on main since 2026-09-30, merge `d1b28702`). Older tablet builds look only in `checklist_attachments` and cannot open them |
| `Y:\Ready Jobs\.time_cards\employees.json` | HT (only writer), fed by timeclock-hub roster push (`POST /api/employees/sync`) and HT pull | Fields include `displayName`, `rtcId`, `addedBy`, `timeclockInactive`; tablet reads `displayName`. Logs prefixed `timeclock-hub sync:`. Pre-PIN backups `employees.*.pre-pin-migration.json`, `employees.json.bak-*` |
| `Y:\Ready Jobs\.time_cards\<PIN>\...` | — | Employee folders are keyed by **PIN** (migration 2026-09-22, `HT\scripts\migrate_employee_directories_to_pin.py`); leftover name folders still exist. Rows below use `<PIN>` |
| `...\.time_cards\<PIN>\<YYYY-MM-DD>.json` (+ `.json.lock` lease `ts,deviceId`, 5-min expiry) | HT Android/backend | Weekly JSON is truth; `hours.db` is reporting cache |
| `...\.time_cards\<PIN>\profile.json` (+ `.lock`) | HT Android primary; backend reads/limited writes (avatar, merge) | Profile, coins, stats, avatar, shop history |
| `...\.time_cards\<PIN>\granted_badges.json`, `alerts.json` | HT backend; Android reads | Server-granted badges/XP; server alerts |
| `...\.time_cards\<PIN>\activity_events.json` | HT Android/backend | Badge/streak/shop activity feed |
| `...\.time_cards\<PIN>\acknowledgements.json` | HT Android | Alert acknowledgements |
| `...\.time_cards\<PIN>\notes\<id>.json` (recipient's folder), `remote_acks\<id>.json` (sender's folder), legacy `notes.json` | HT Android writes into OTHER employees' folders | Cross-employee notes/acks |
| `...\.time_cards\<PIN>\avatar_pending.jpg` → `.avatar.jpg` | Backend stages; Android copies to `.avatar.jpg` and truncates the pending file | Avatar adoption |
| `...\.time_cards\<PIN>\backups\<week>.json`, `backups\merged_from_*` | Android / HT merge | Per-employee backups |
| `Y:\Ready Jobs\.time_cards\badges_config.json`, `.badge_images\*`, `challenges.json` | HT backend; Android reads | Badge definitions/art, weekly challenges (`custom_badges.json` = legacy migration source) |
| `Y:\Ready Jobs\.time_cards\shop_catalog.json`, `shop_images\` | HT backend; Android reads | Shop catalog |
| `Y:\Ready Jobs\.time_cards\limited_purchases\<claimId>.json` | Android writes; HT processes | Limited-item claims |
| `Y:\Ready Jobs\.time_cards\pending_edits.json`, `loaded_cards.json`, `pending_deletions.json` | HT | Queued edits / export double-count state / deletions |
| `Y:\Ready Jobs\.time_cards\.archive\<PIN>_<Name>_<stamp>\` | HT (`employee_archive_store.py`, from hub sync) | Archived employees |
| `Y:\Ready Jobs\.time_cards\.locks\{shop,timecards,alerts,badges,employees,board,challenges,delivery,handoff,sync_conflicts}.lock` | HT (`lock_manager.py`) | Multi-server admin edit locks |
| `.time_cards` temp files: `.<name>.<rand>.tmp` (HT backend), `*.json.tmp` / `<date>.tmp` (HT Android) | HT backend / Android | Transient. Root 1-byte `*.json.lock` files are pre-fix leftovers (locks now live in the OS temp dir) |
| `.time_cards\timeclock_sync_state.json`, `.metadata\cache_static.json`, `CNC\`, `.badges.json`, `.json` | **No writer at any repo HEAD** — orphans | Ignore / candidates for cleanup |
| `Y:\TimeCardUpdater\version.json`, `TimeCardTracker.exe` | HT updater publishing (manual copy) | HT PC app only, not KKCSheetTracker |
| `Y:\Ready Jobs\.Updates\*.apk`, `.Testing_Updates\*.apk` | `KKCSheetTracker\deploy_update.ps1` copies release APKs; KST and HT Android updaters read | Verify package name. `Y:\Ready Jobs\Updates` does not exist |
| `Y:\Ready Jobs\.appupdates\device_policy.json` | Writer manual/unconfirmed; **updater-agent only** reads | Silent-update policy |
| `Y:\Ready Jobs\.appupdates\apps\manifest.json`, `apps\<packageName>\<apk>` | `KKCSheetTracker\deploy_update.ps1` (tmp + Move-Item); updater-agent reads/installs | Update feed with package/version/apk/hash/channel |
| `Y:\Ready Jobs\.appupdates\<tabletId>\install-log.ndjson` | updater-agent | Per-tablet install audit log |
| `Y:\Ready Jobs\.appupdates\<tabletId>\updater-fallback-required.json` | updater-agent writes; **no reader** (KST fallback popups removed 2026-09-22, `d11e463f`) | Informational only |
| `Y:\Ready Jobs\.appupdates\migration_complete.json`, `migration_summary.json`, `<tabletId>\signals.ndjson` | KST `tools-migration` CLI (`MigrationCli.kt`); the app only reads `migration_complete.json` as its view-only gate | Migration markers. `tablet_id.txt`, `desktop-remake-processor\`, `unknown-tablet\` have no known writer |

Caveat: the HT **web app** never writes `cache_static.json`/`cache_index.json` unless emergency legacy writes are enabled (`HOURS_TRACKER_ENABLE_LEGACY_CACHE_WRITES=1`); HT's **worker** (W) is their normal writer.

Second caveat: `moldingId` includes the category (`"Crown:105"`). When a CV profile is removed or moves category, `cv-molding-sync` deletes the old `.xml`; dimensions saved under the old `moldingId` in `molding_dimensions.json` are orphaned, not deleted.

## Cross-System Contract Invariants

- Every metadata reader that globs shared files must exclude `.sync-conflict-*` (all three programs do since H-03, 2026-07-09; W also archives them via `scan_and_resolve_sync_conflicts`). The risk is a NEW glob that forgets the filter.
- CNC and hardwood event ordering is `(timestamp, lamport, eventId, stable tie-breakers)` everywhere (tablet `TrackerEventLog.kt`, W `tracker_action_reader.py`). The merge functions re-sort by timestamp only, which is safe only because Python's sort is stable — keep it that way.
- A compactor must not unlink a live per-writer event stream after only an mtime/size check. W never deletes `.ndjson`; its legacy `<tablet>.json` deletion still uses a re-stat mtime/size check (accepted race, audit M-10). Any cleanup of a tracker dir must whitelist `<tabletId>.json` and never touch `*.markup.json` or other sidecars.
- Tablet request sidecars are per-tablet and consumed oldest-first. Quarantine malformed payloads (`<name>.rejected`); retry transient I/O, lock, or master-write failures without deleting the request.
- Supply schema field `id`/`key` values must be nonblank and unique; built-in definitions identical across HT and Android. Per-writer status/comment JSON must be atomic; latest-status resolution ignores conflict copies and compares parsed instants.
- timeclock-hub SQLite is the punch source of truth. Punch duration rounds up to 15 minutes; sessions under 7 minutes are deleted (logged; a live punch-out already pushed to the RTC is first kept as a 0-hour record and removed next sync). Hub timezone `America/Los_Angeles`.
- In the hub DB, `employees.display_name` is the numeric RTC display ID, `nickname` is the RTC "Display Name" label, and `app_display_name` is the Hours Tracker custom name (`employees.json` `displayName`, pulled every 5 min from HT `GET /api/employees/display-names`, `X-Admin-Token`). In API responses `display_name` is the effective human name: `app_display_name` first, then `nickname` (`''` when it equals the real name; duplicate-diagnostics payloads return the raw DB ID under the same key).
- Custom/purchased names are display-only. Nothing may write them to the RTC-1000: its Display Name is exported for payroll and must stay the employee's real name.
- Updater policy/manifest data is privileged input: non-empty signer allowlist per managed package, reject duplicate/blank package entries, resolved APK paths must stay under `.appupdates\apps\<packageName>`.
- Nothing but a tablet (or an explicitly approved tool) may write tablet-owned progress into live `Y:\Ready Jobs`. Tests must never target the live share.

## Local State

| Path / State | Owner | First Debug Check |
|---|---|---|
| `READY_JOBS_*` env vars in `ops\docker-compose.production-cutover.yml` (`READY_JOBS_STATE_DIR`, `READY_JOBS_DRY_RUN`, `READY_JOBS_MUTATIONS_ENABLED`, `READY_JOBS_WORKER_ENABLED`, `READY_JOBS_METADATA_CACHE_DEBOUNCE_SECONDS`, `READY_JOBS_SOURCE_CACHE_REFRESH_DELAY_SECONDS`, ...; parsed by `W\adapters\env_config.py`) | W / HT web | Live replacement for RJW `config.json` |
| `$READY_JOBS_STATE_DIR` (prod `/data/ready-jobs-worker`; local `run_ready_jobs_worker.bat` uses `backend\ready_jobs_worker_core\_state`; required, legacy fallback `READY_JOBS_WORKER_STATE_DIR`) | W | Holds the files below |
| `worker_status.json` | W | Per-cycle heartbeat: `lastSweep.jobsProcessed/jobsErrored/jobsArchived`, `hardwoodsPublicationOutcomes`, `modeTemplateMismatchJobs`, `daeGlb*`. `GET /api/ready-jobs-worker/status` reports `not_started`/`stale`/`job_errors`/`running`; stale after `max(600, 2*interval+60)` s |
| `job_errors.json` | W | Per-job error TEXT keyed by folder; overwritten wholesale each sweep (absent = clean this cycle) |
| `worker.log` (+ `.1`, `.2`) | W | Rotating 2 MB x2; one INFO per sweep + one ERROR per failed job. Written by BOTH the standalone worker and main_v2's in-process dry-run worker (`configure_worker_logging`), except when the state dir is blocked (dry-run/shared-read-only with state dir inside the Ready Jobs root) or in tests. `GET /api/ready-jobs-worker/logs?tail=N` |
| `bad_parts_state.json` | W | Worker `BadPartsMonitor` state (successor of RJW `tracker_bad_parts_state.json`) |
| `live_index_relay.json`, `archive_library_relay.json` | W → HT web | Worker-to-web relay behind the live WebSockets |
| `last_pdf_open.txt` breadcrumb (path = `READY_JOBS_PDF_OPEN_BREADCRUMB_PATH`, **off by default**) | W | Last PDF opened before a native crash with no traceback |
| Metadata snapshots under `READY_JOBS_METADATA_SNAPSHOT_ARCHIVE_DIR` (+ `.archive.lock`; off when unset) | W | Successor of RJW `metadata_snapshots\` |
| `%DATA_DIR%\rename_history.json` | W (`adapters\rename_history.py`) | Job rename history (successor of RJW's) |
| `hours.db` table `ready_jobs_operations` | HT web | Queue + history of every gate/archive/rename operation — source of truth for "who released/hid/archived this job" |
| `%APPDATA%\TimeCardTracker\TimeCardTracker\hours.db` (platformdirs doubles the name; `backend\hours.db` only if `db_path` points there), Docker `/data/hours.db` (+ `-wal`, `-shm`) | HT | Reporting cache for digital hours (JSON is truth) AND the Ready Jobs operations queue |
| `C:\Scripts\Hours Tracker\config.json`, `%APPDATA%\TimeCardTracker\config.json` | HT | Local paths; in Docker (`DOCKER=1`) config comes from env only |
| `backend\weekly_backup_log.json`, `results\*.json` (frozen: `<exe dir>\results`), `employee_mapping.json` | HT | Backup log, import/export results, alias mapping |
| `backend\` or `%DATA_DIR%\` `checklist_rules.json`, `board_stock_materials.json`, `crown_library.json` + `crown_profiles\`, `handoff_{config,rules,pdfme_templates}.json` | HT | Global admin stores |
| `backend\` or `%DATA_DIR%\` `molding_dimensions.json` | HT | Dimension overrides keyed by `moldingId`; never leaves `DATA_DIR` (reaches tablets only baked into `_dim.svg`) (audit SK-06) |
| `backend\` or `%DATA_DIR%\` `molding_frame_style.json`, `molding_hidden.json` | HT | Crown Face Frame/Frameless tag and hidden flag; both DO reach tablets via `moldings_cache\library.json` (audit SK-07) |
| `C:\Scripts\cv-molding-sync\config.json` (gitignored; CV DB creds + `root_dir`), `cv_molding_sync.log`, task `KKC CV Molding Sync` | cv-molding-sync (CV PC) | Runs only while the user is logged on (interactive logon type) |
| `C:\Scripts\timeclock-hub\data\timeclock.db` | timeclock-hub | Punch source of truth ON THE HUB HOST; the PC copy may be stale |
| `timeclock.db.backup_*` (from `cleanup_local_db.py`), `downloaded-timeclock.db` (from `/api/db/download`; tracked in git) | timeclock-hub admin | Backups/debug copies |
| `C:\Scripts\timeclock-hub\.env` | timeclock-hub | RTC URL/user/pass, poll interval, hub IP/port, `HUB_ADMIN_TOKEN` (also gates roster push), `HOURS_TRACKER_URL`; never paste secrets |
| `C:\Scripts\timeclock-hub\docker-compose.yml`; Docker logs | timeclock-hub | Port 8765, `./data:/app/data`, `TZ=America/Los_Angeles`; logs only on the hub host |
| RJW reference only: `config.json`, `pending_queue.json`, `tracker_bad_parts_state.json`, `metadata_snapshots\`, `rename_history.json`, `polling_snapshot.json`, `ready_jobs_watcher.lock`, `*.log` (`ready_jobs_watcher`, `cnc_scan`, `backup`, `bad_parts`, `send_notification`), `bad_parts_blacklist.json`, `permanently_ignored_blacklist.json` | RJW (deprecated) | Frozen; not live evidence |

## Android Local State

| State | Owner | Purpose |
|---|---|---|
| `SharedPreferences/kkc_tracker` | KKCSheetTracker | `base_path`, `tablet_id`, `work_mode`, `admin_mode`, `board_view_*`, `supply_tab_order`, `supply_categories_cache`, crash context |
| `SharedPreferences/kkc_ui_prefs` | KKCSheetTracker | Viewer/edge/resume UI state |
| `SharedPreferences/kkc_clock_in` | KKCSheetTracker | Job clock-in overlay state |
| `SharedPreferences/UpdateManagerPrefs` | KKCSheetTracker legacy updater | `custom_update_path` only (no skipped-versions key) |
| DataStores `syncthing_settings`, `timeclock_config` (`server_ip` default `192.168.1.15`, `cached_server_url`, `hub_device_token` → `X-Hub-Token`), `timeclock_background`, `pinned_jobs`, `assembly_viewer_defaults`, `specialty_viewer_defaults` | KKCSheetTracker | Settings/defaults |
| DataStores `admin_sync_config`, `hidden_materials_visibility`, `mix_operation_sessions`, `screensaver_settings`, `scanner_settings` | KKCSheetTracker | Admin-sync endpoint, hidden-material visibility, Mix Service sessions, idle/scanner settings |
| `filesDir\state\tracker_lamport.txt` | KKCSheetTracker (`TrackerEventLog.kt`) | Persisted Lamport counter for event ordering |
| `filesDir\state\drafts\<job>\<tablet>.json` | KKCSheetTracker | Local bad-part drafts |
| `filesDir\state\mix_catalog\` | KKCSheetTracker | Mix catalog cache |
| `filesDir\crash_reports\pending\*.json`, `filesDir\cpu_spike_logs\pending\` | KKCSheetTracker | Pending crash / CPU-spike reports before the share is reachable |
| `filesDir\timeclock_bg\*`, `filesDir\supply_subscriptions.json` | KKCSheetTracker | Timeclock background media; supply subscriptions |
| `cacheDir\archive-cache\<archiveJobId>\` (+ `.state`), `cacheDir\supply_temp\` | KKCSheetTracker | Archived-job 24 h cache; supply temp files |
| updater-agent's own `SharedPreferences/kkc_tracker`, key `updater_tablet_id` | updater-agent (`com.kkc.updateragent`) | Same file NAME as the app's prefs, different package, no shared data |
| WorkManager unique work `kkc_updater_periodic` | updater-agent | Periodic silent update worker |
| (removed) `filesDir\state\ocr\...` | — | Tablet OCR cache removed 2026-08-17 (`e62a2966`) |

## Generated Or Cache Artifacts

| Path Pattern | Owner | How To Treat It |
|---|---|---|
| `Y:\Ready Jobs\<job>\DARK MODE\*.pdf` | W (`adapters\dark_mode_publish.py`) | Generated copies; re-parse regenerates |
| `Y:\Ready Jobs\<job>\3D\<room>\3d_medium.glb` | W (`adapters\dae_glb_publish.py`) from `3d.dae` | Android 3D asset; counts `daeGlb*` in `worker_status.json` |
| `Y:\Ready Jobs\<job>\CNC\.metadata\.thumbs\*`, `.fullimages\*` | PGM Sorting splitter writes `.thumbs\<stem>_pNNN.png` (TOC thumbnail) and, since 2026-09-30, `.thumbs\<stem>_pNNN.diagram.png` (full-size gray sheet diagram the tablet viewer loads before falling back to the PDF image); both match the `_p*.png` globs the splitter stages/cleans and W prunes as orphans | Missing previews/diagrams only; not source metadata |
| `Y:\Ready Jobs\<job>\.metadata\.thumbs\*`, `.fullimages\*` | HT (`routes\pdf.py`) renders root-PDF page images | Per-job, not global |
| `Y:\Ready Jobs\<job>\**\*.tmp`, `*.ocr.tmp`, `.tmp_assimp_*`, root `production_order.json.*.tmp` | Atomic writers/converters | Transient; July-dated root leftovers are safe to clean |
| `Y:\Ready Jobs\<job>\CNC\.tracker\watcher_refresh.json`, `watcher_refresh_splitter.json` | Legacy markers (no writer) | Current signal is `watcher_refresh_watcher.json` |

## Symptom Routing

| Symptom | Start Here |
|---|---|
| Tablet does not show a job | `deployment_gate.json` (`deployed`, `hiddenFromProduction`), then `cache_index.json`, then `job_errors.json`/`worker.log`, then `ready_jobs_operations` (hidden or archived by someone?) and the archive root. Tablet filter: `DeploymentGateRules.evaluate` |
| Job vanished / lineup entry removed | Archive flow: `ready_jobs_operations`, `ARCHIVE_Ready Jobs`, `worker_status.json` `jobsArchived` (W `archive_lifecycle.py`, `archive_scheduling.py`) |
| Worker not sweeping / everything stale | `GET /api/ready-jobs-worker/status` (`not_started`/`stale`/`job_errors`/`running`), `docker inspect` health, `worker.log`, kill-switch env vars |
| Worker died with no traceback | `last_pdf_open.txt` (only if its env var is set), `docker logs hourtracker-worker` |
| Jobs list progress bars stale/empty | Live `/live-index` socket, then `cache_index.json` (no cache_static fallback). Logcat `CacheIndex` warns when the index is older than cache_static |
| Job appears but material counts/pages are wrong | `cache_static.json`, CNC sidecars, then `worker.log`/`job_errors.json` |
| CNC sidecar missing or legacy OCR boxes absent | PGM Sorting splitter (owner). Tagged-v1 OCR omission is intentional |
| CNC progress/bad parts stale | `CNC\.tracker\events\*.ndjson` (live), `consolidated.json`, then `bad_parts_state.json` / `/api/ready-jobs-worker/bad-parts` |
| CNC skipped/re-nested confusion | `consolidated.json` `reNested` on skips; `cache_index` `progressSummary.cnc.renested`; `cache_static` sidecar `remakeLabel`; tablet `ProgressStore.kt` skip write |
| Hardwoods rows/revisions wrong | `cutlist_index.json`, `cutlist_revisions.json`, `worker_status.json` `hardwoodsPublicationOutcomes`/`modeTemplateMismatchJobs` |
| Combined cut list export not applied / per-type cut list PDFs missing | `job_errors.json` for `<job#> - Combined Cut List.pdf: ...` (layout error, two combined files, or a hand-placed per-type file blocking), then `.metadata\.combined_cutlist_split.json`, then `worker.log` `combined_cutlist=` |
| Hardwoods doc type missing, siblings fine | `cutlist_job_mismatch.json` (+ `_overrides.json`), then `blank_hardwoods_documents.json` |
| Hardwoods markup disappeared | RJW, or W older than 2026-09-30 (`a620f8f1`), deleting `*.markup.json` during consolidation — check which worker build runs the sweep |
| Cutlist rip width looks swapped with length | Readable "3.0" template? Width column is authoritative, never swapped by size (W `board_stock.compute_board_stock_rows`, exact port of RJW); a wrong width means the source PDF/OCR |
| Assembly/cabinet view wrong | `cabinet_sheet_index.json` (W `cabinet_reference_publish.py`) |
| Molding profile geometry missing/wrong | `.metadata\moldings\<category>\<profileId>.xml`, `cv_molding_sync.log`, CV `Profile`/`Shape` |
| Molding dimension lines missing/reset | `molding_dimensions.json`, `molding_dimensions_store.py`, then `_dim.svg` republish |
| Crown Face Frame/Frameless tag or hidden flag wrong on tablet | `molding_frame_style.json`/`molding_hidden.json`, the PUT route, `publish_library_cache()`, then `library.json` `frameStyle`/`hidden` |
| Specialty/admin items wrong | HT admin files + pending `checklist_patch`/`specialty_patch` sidecars, then tablet `SpecialtyRepository`/`SpecialtyProgressStore` |
| Specialty attachment won't open on tablet | Tablet build has `resolveSpecialtyAttachmentFile`? Otherwise it looks in `checklist_attachments` |
| PDF markup missing | `pdf_markup\.tracker\<tablet>.markup.json`, then tablet app version |
| Supply item/status wrong | Live socket (logcat `SupplyLiveClient`, HT `routes\supply_live.py`), then `.supply\items`/`status`/`comments`/`barcodes.json` |
| Safety concern from tablet not on HT | Same base path? `safety_store.get_safety_dir()` must use `get_base_path()` |
| Hidden material still visible on another tablet | Mode's `hidden_materials*.json`, the `/api/hidden-materials/live` socket, then the request poller |
| Hide in Hardwoods also hid Specialty (or vice versa) | Mode-segregation bug: `hiddenMaterialsModeSubdir`, the route's `hiddenMaterialsMode`, backend `(mode, docType, material)` key |
| Production order/lineup wrong | `production_order.json`, HT admin, `/api/admin-sync/production-order`, then W lineup refresh and archive operations |
| Delivery schedule wrong | `.metadata\delivery_schedule.json`, `/api/delivery-schedule/live`, HT, archive removals |
| Digital hours wrong | `.time_cards\<PIN>\<week>.json`, locks, `pending_edits.json` |
| Badge/profile/shop wrong | `.time_cards\<PIN>\profile.json`, `badges_config.json`, `shop_catalog.json`, locks |
| Employee missing/renamed | `employees.json`, timeclock-hub roster push (`timeclock-hub sync:` log lines), `.time_cards\.archive\` |
| Punch-clock timeclock wrong | timeclock-hub DB on the hub host + hub logs, not HT |
| Hub name differs between browser and tablet | Hub API `display_name`, Android `TimecardRepository`, HT profile-name precedence |
| Install/update wrong | `.appupdates\<tabletId>\install-log.ndjson`, versions, logcat `KKCUpdaterWorker`/`TriggerUpdateReceiver` |
| App crashed / sluggish | `Y:\Ready Jobs\.metadata\crashes` / `cpu_spikes`, then ADB `AndroidRuntime` |
| Mix not created/updated | Mix Service `GET /operations/{id}`, `service.log`, `definitions.json` |

## First Commands

Hours Tracker worker (live; run on the Docker server host, not the PC):

```powershell
curl http://<server>:47821/api/ready-jobs-worker/status
docker exec hourtracker-worker cat /data/ready-jobs-worker/worker_status.json
docker exec hourtracker-worker cat /data/ready-jobs-worker/job_errors.json
docker exec hourtracker-worker tail -n 200 /data/ready-jobs-worker/worker.log
docker inspect --format '{{.State.Health.Status}}' hourtracker-worker
docker logs hourtracker-worker --tail 200
```

Kill switch: set `READY_JOBS_WORKER_ENABLED=0`, or `docker compose -f ops/docker-compose.production-cutover.yml stop hourtracker-worker`.

Ready Jobs files (PC):

```powershell
Get-Content "Y:\Ready Jobs\<job>\.metadata\deployment_gate.json"
Get-Item "Y:\Ready Jobs\<job>\.metadata\cache_static.json", "Y:\Ready Jobs\<job>\.metadata\cache_index.json"
Get-ChildItem "Y:\Ready Jobs\<job>\CNC\.tracker" -Recurse
Get-ChildItem "Y:\Ready Jobs\<job>\.metadata\hardwoods\.tracker" -Recurse
```

Hours Tracker:

```powershell
Get-ChildItem "Y:\Ready Jobs\.time_cards"
Get-Content "Y:\Ready Jobs\.time_cards\employees.json"
Get-Content "Y:\Ready Jobs\.time_cards\badges_config.json"
Get-Content "Y:\Ready Jobs\.time_cards\pending_edits.json"
Get-ChildItem "Y:\Ready Jobs\.time_cards\.locks"
Get-ChildItem "Y:\Ready Jobs\.supply" -Recurse -Depth 2
Get-ChildItem "Y:\Ready Jobs\.safety" -Recurse -Depth 2
```

KKCSheetTracker tablet:

```powershell
adb devices -l
adb shell dumpsys package com.kkc.sheettracker | Select-String "versionName|versionCode"
adb logcat -d -v time AndroidRuntime:E KKC_CRASH_REPORTER:* KKC_APP_STATE:* KKC_NAV:* SupplyLiveClient:* CacheIndex:* *:S
```

Updater-agent:

```powershell
adb shell dumpsys package com.kkc.updateragent | Select-String "versionName|versionCode"
adb logcat -d -v time KKCUpdaterWorker:* TriggerUpdateReceiver:* *:S
Get-Content "Y:\Ready Jobs\.appupdates\device_policy.json"
Get-Content "Y:\Ready Jobs\.appupdates\apps\manifest.json"
Get-ChildItem "Y:\Ready Jobs\.appupdates" -Recurse -Filter install-log.ndjson
```

timeclock-hub (on the hub host):

```powershell
docker compose -f "C:\Scripts\timeclock-hub\docker-compose.yml" logs --tail 200
```

Hours Tracker Android app:

```powershell
adb shell dumpsys package com.example.timecard | Select-String "versionName|versionCode"
```

Old RJW (reference only):

```powershell
Get-Content "C:\Scripts\Ready Jobs Watcher\ready_jobs_watcher.log" -Tail 200
Get-Content "C:\Scripts\Ready Jobs Watcher\cnc_scan.log" -Tail 200
```

## Code Entry Points

| Question | Read |
|---|---|
| How does the worker start and sweep? | `W\__main__.py` → `adapters\service_runner.py` (`main`, `run_service`, `_sweep_and_prune`, `configure_worker_logging`) → `adapters\sweep.py` `sweep_all_production_jobs` |
| How are gates written? | `W\adapters\deployment_gate_write.py`, `gate_contract.py`, `adapters\deployment_gate_bootstrap.py`, `adapters\auto_release.py`; operator API `HT\routes\ready_jobs_worker_jobs.py` |
| How are cache_static / cache_index built? | `W\cache_payload.py` + `adapters\cache_publish.py`; `W\cache_index_payload.py` + `adapters\cache_index_publish.py` |
| How are CNC/hardwood tracker events read and consolidated? | `W\adapters\tracker_action_reader.py`, `cnc_tracker_merge.py`, `hardwoods_tracker_merge.py`, `adapters\tracker_consolidation.py`, `tracker_orchestration.py`, `tracker_consolidated_publish.py`, `tracker_device_file_cleanup.py` |
| Hardwoods cutlist index / mismatch / blank docs / combined export split? | `W\combined_cutlist.py`, `adapters\combined_cutlist_split.py`, `W\hardwoods_cutlist_parser.py`, `adapters\hardwoods_cutlist_publish.py`, `cutlist_job_mismatch_store.py`, `hardwoods_blank_document_store.py` |
| Cabinet index, dark mode, GLB, rename, reparse, bad parts, remake/misc candidates, duplicate guard, archive? | `W\adapters\`: `cabinet_reference_publish.py`, `dark_mode_publish.py`, `dae_glb_publish.py`, `job_rename.py`, `job_reparse.py`, `bad_parts_detection.py`, `remake_candidates_publish.py`, `misc_candidates_publish.py`, `duplicate_job_guard.py`, `archive_lifecycle.py`, `archive_scheduling.py` |
| How does KKCSheetTracker read job metadata? | `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\data` (list: `unified\FileBackedUnifiedMetadataEngine.kt`) |
| How are crash / CPU-spike files written? | `KST crash\`, `KST perf\CpuSpikeLogStore.kt` |
| How does Android append/order CNC and hardwood events? | `KST data\ProgressStore.kt`, `HardwoodsProgressStore.kt`, `TrackerEventLog.kt` |
| How is the Cabinet Vision molding library published? | `C:\Scripts\cv-molding-sync\cv_molding_sync.py` (RJW reference: `ready_jobs_watcher\moldings_sync.py`) |
| HT molding stores / tablet cache? | `HT\routes\molding_dimensions_store.py`, `molding_frame_style_store.py`, `molding_hidden_store.py`, `molding_library_store.py`, `molding_cache_publish.py` |
| HT JSON → reporting DB? | `HT\db.py` |
| Which API serves HT admin data? | `HT\routes\admin.py` and the other `routes\*` modules; `main_v2.py` is the app entry |
| How are tablet lineup/board/delivery/hidden-material requests consumed? | `HT\main_v2.py`: `/api/admin-sync/*` routes, `_apply_production_order_requests`, `_apply_job_board_edit_requests`, `_apply_delivery_schedule_request`, hidden-materials request poller |
| Supply schema / shared supply state / live socket? | `HT\routes\supply_store.py`, `supply_live.py`, `supply_live_document.py`, `W\adapters\supply_live_monitor.py`; `C:\Scripts\Hours Tracker\frontend\components\JobManager\supply\SupplySchemaEditor.tsx`; tablet `KST data\SupplyRepository.kt`, `SupplyLiveClient.kt`, `SupplyLiveStateStore.kt` |
| What frontend calls HT APIs? | `C:\Scripts\Hours Tracker\frontend\lib\api_kkc.ts` |
| How does the RTC punch clock work? | `C:\Scripts\timeclock-hub\app.py` |
| How does Android interpret hub names/status? | `KST data\TimecardRepository.kt` |
| Silent / legacy Android updates? | `C:\Scripts\KKCSheetTracker\updater-agent\src\main\java\com\kkc\updateragent\update`; `KST update\` |
| PDF markup files? | `KST data\PdfMarkupStore.kt` |
| Mix Service from the tablet? | `KST data\mixservice\MixServiceClient.kt`; server `C:\Scripts\PGM_BCR_Loader\mix_service\` |
| Old RJW reference equivalents | `C:\Scripts\Ready Jobs Watcher\ready_jobs_watcher\`: `deployment_gate.py`, `metadata_cache.py`, `tracker_action_stream.py`, `cabinet_sheet_indexer.py`, `hardwoods_cutlist_indexer.py`, `moldings_sync.py` |

## Common Mistakes

| Mistake | Correction |
|---|---|
| Blaming Android for a missing job before checking `deployment_gate.json` | Gate, then cache_index, then worker errors and the operations/archive trail |
| Treating old RJW as the writer / reading RJW logs as live evidence | W is the live writer for every former RJW row; RJW files and logs are frozen reference. Only treat RJW as active if the user explicitly says so |
| Blaming the HT web app for stale `cache_static.json` | The HT **worker** publishes caches; the web app only reads |
| Assuming every cache refresh waits for the normal debounce | Source PDFs, CNC sidecars, and non-tracker job metadata use the 30 s priority delay; tracker bursts retain the ~180 s debounce and ~300 s sweep |
| Assuming CNC/hardwood actions flow through `<tablet>.json` | Tablets write only `events\<tabletId>.ndjson` since 2026-07-09/10; `<tablet>.json` is retired legacy input. Don't "fix" a missing one |
| Deleting or "cleaning" files in a `.tracker` dir by blacklist | Whitelist `<tabletId>.json` only; `*.markup.json` and other sidecars are tablet data |
| Running KKCSheetTracker unit tests on a PC with `Y:` mapped from a checkout older than `d9fbd52c` | That old `BatchSyncTest` wrote `batch-sync-worker` events into live jobs; it was removed on main 2026-09-30. Tests must never target the live share |
| Treating Hours Tracker and timeclock-hub as the same thing | HT = digital timecards/admin/worker; timeclock-hub = RTC punch clock |
| Using HT APK/version paths for KKCSheetTracker | Package names: `com.example.timecard`, `com.kkc.sheettracker`, `com.kkc.updateragent` |
| `git grep` from the HT root for Android timecard code | The HT Android app is a separate repo at `C:\Scripts\Hours Tracker\AndroidApp` |
| Trusting SQLite first for digital hours | `.time_cards` JSON is truth; `hours.db` is reporting cache (but IS truth for the Ready Jobs operations queue) |
| Trusting `.time_cards` for punch-clock data | Punch truth is the hub's `timeclock.db` |
| Looking for `.time_cards\<Employee Name>\` | Folders are keyed by PIN since 2026-09-22 (some name folders linger) |
| Assuming one Syncthing conflict filter covers every format | Audit every new JSON and NDJSON glob independently |
| Deleting a valid tablet request after any exception | Quarantine invalid payloads only; retry transient failures |
| Sorting tracker events only by timestamp | Preserve `lamport` and `eventId` through every decoder |
| Treating API `display_name` as the numeric RTC display ID | Only hub storage has that; API `display_name` is the human name |
| Trusting stat/size before unlink during tracker compaction | Late appends can land between stat and unlink; never delete `.ndjson` that way |
| Allowing empty updater signer policy because SHA-256 matches | A writable feed can replace manifest and hash; signer allowlist + path containment required |
| Searching all hidden Syncthing folders as jobs | Filter to real job folders like `<jobnum> - <name>` |
| Treating thumbnails/fullimages as source metadata | Render caches; debug source JSON first |
| Assuming mDNS works for timeclock | mDNS is disabled at hub HEAD; use manual/default IP |
| Assuming a bad-part alert was lost because consolidation deletes legacy files | C-01 fix holds in W's `merge_cnc_actions` |
| Assuming a "read-only" HT read never mutates | `get_checklist` / specialty reads consume `checklist_patch`/`specialty_patch` sidecars unless inside `read_only_context()` (audit H-04) |
| Assuming "HT UI never sets `hiddenFromProduction`" (old RJW rule) | HT's `hide` operation sets it true; tablets hide those jobs in release builds |
| Assuming the molding sync can overwrite HT's saved dimensions | Different trees: `cv-molding-sync` writes only `.metadata\moldings\*.xml`; `molding_dimensions.json` lives in HT's `DATA_DIR` (audit SK-06) |
| Assuming W syncs Cabinet Vision moldings | It never did; the live writer is `C:\Scripts\cv-molding-sync` on the CV PC (audit SK-08) |
| Assuming an HT-local molding sidecar never reaches the tablet | `frameStyle` and `hidden` flow into `moldings_cache\library.json`; dimensions are baked into `_dim.svg` (audit SK-07) |
| A new HT store module derives its own base path | Every store resolves `get_base_path() / "<name>"` (`board.py`, `delivery.py`, `supply_store.py`, `molding_library_store.py`, `safety_store.py`); bespoke paths hid `.safety\concerns` writes once (fixed 2026-07-23) |
| Assuming `cutlist_index.json` reflects every hardwood PDF | W publishes with a dismissible warning for a genuinely BLANK required doc (`blank_hardwoods_documents.json`); old RJW aborted the whole index on a malformed required 3.0 doc. Check `worker_status.json` `hardwoodsPublicationOutcomes` |
| Assuming Hardwoods and Specialty have separate cutlist screens | One composable, `HardwoodsWorkspaceScreen`, reached by `hardwoods/workspace/{folderName}/{docType}/{startPage}/{hiddenMaterialsMode}` (Hardwoods + Specialty menus) AND by the archive-job route `hardwoods/workspace/{docType}/{rowId}/{hiddenMaterialsMode}` (`ArchiveJobDetailHost.kt`). Changes must cover both routes and both `HiddenMaterialsMode` values |
| Assuming the Mix Service `PUT` finished when it returned | Mutations are async (202 + operation); poll `/operations/{id}` |
