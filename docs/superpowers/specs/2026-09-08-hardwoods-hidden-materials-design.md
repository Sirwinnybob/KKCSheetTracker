# Hardwoods Hidden Materials — Design

## Problem

Hardwoods-mode users want to hide cutlist materials that don't apply to a given job (or never apply at all) instead of scrolling past them. Hiding must be a cross-tablet feature — one tablet hides a material, every other tablet sees it hidden too, not a per-device preference.

## Scope

- Hide/unhide a material within one job.
- Auto-hide a material for all future jobs (global), and remove that auto-hide flag later.
- Toggle to reveal hidden materials on the cutlist screen and unhide from there.
- Hide scope is `(docType, material)`, not `material` alone — the same material name (e.g. "Maple") can appear across Face Frame, Nailer, Door, Closet Rod, Door List doc types within a job, and hiding it in one doc type does not hide it in the others. Material name matching is trimmed + case-insensitive.

## Non-goals

- No change to `ready_jobs_worker_core` or `cutlist_index.json` generation — the worker keeps publishing raw cutlist data exactly as today, unaware hiding exists.
- No retroactive rewrite of already-hidden state when a job's cutlist is reparsed; hidden state is independent of and layered on top of the parsed index.
- No per-tablet-only hiding — every hide/unhide is cross-tablet by design; there is no local-only hide.

## Ownership (per `kkc-metadata-map`)

Hours Tracker backend is the sole writer of both master files below (avoids two tablets racing a direct file write, matching the `production_order.json` pattern — tablets submit requests, the backend applies them). `ready_jobs_worker_core` is not involved.

## Data model

Global auto-hide list, one file, applies to every job going forward:

```
Y:\Ready Jobs\.metadata\hardwoods\hidden_materials_global.json
```

```json
{
  "entries": [
    {"docType": "NAILER_CUT_LIST", "material": "Maple", "hiddenAt": "2026-09-08T18:04:00Z", "tabletId": "tablet-3"}
  ]
}
```

Per-job override file, one per job, lives inside that job's own metadata folder (not the global library, per the bloat concern that motivated this design):

```
Y:\Ready Jobs\<job>\.metadata\hardwoods\hidden_materials.json
```

```json
{
  "hides":   [{"docType": "FACE_FRAME_CUT_LIST", "material": "Oak", "hiddenAt": "...", "tabletId": "..."}],
  "unhides": [{"docType": "NAILER_CUT_LIST", "material": "Maple", "hiddenAt": "...", "tabletId": "..."}]
}
```

Effective visibility for `(docType, material)` within a job:

1. Hidden if the pair is in the job's `unhides` → **visible** (job override always wins, regardless of global/job hide state).
2. Else hidden if the pair is in the global list OR the job's `hides` → **hidden**.
3. Else → **visible**.

"Remove from all-jobs auto-hide" deletes the entry from the global file only. It takes effect everywhere immediately (client-side recompute on next read) — no per-job files are touched or need migration.

## Write path (tablet → master)

Mirrors the existing production-order / job-board / delivery-schedule pattern (`ProductionOrderRequestStore` + `AdminSyncClient`):

- **Fast path:** tablet POSTs to a new Hours Tracker endpoint, e.g. `POST /api/admin-sync/hardwoods-hidden-materials`, body `{action: "hide"|"unhide", scope: "job"|"global", jobId?, docType, material, tabletId, requestedAt}`. Backend applies directly to the relevant master file (global or the job's override file) and appends to the `hides`/`unhides`/`entries` list, deduping by `(docType, material)`.
- **Backup path:** tablet also writes a durable per-tablet sidecar request file (e.g. `hardwoods_hidden_request.<tabletId>.json`, placed beside the master file it targets — global dir or job's hardwoods metadata dir) with the same payload shape as the request-sidecar files elsewhere in the app. This is Syncthing-replicated and consumed+deleted by the backend on its next poll cycle if the REST call never arrived (tablet offline, backend unreachable at the moment). Same oldest-first, malformed-vs-transient-failure handling as other request sidecars in the metadata map's Cross-System Contract Invariants.
- The two paths are not mutually exclusive — a tablet fires both on every hide/unhide action. The backend's dedup-by-key application makes applying the same action twice (once via REST, once via sidecar) a no-op the second time.

## Read/propagation path (master → tablets)

- **Fast:** the existing read-only live-index WebSocket (`/api/ready-jobs-worker/live-index`, see `ready_jobs_worker_live_index.py`) gets a sibling delta type for hidden-materials changes (or a small sibling channel using the same hello/snapshot/delta protocol shape). Tablets already hold this connection open for cache-index updates; hidden-materials deltas ride the same socket.
- **Backup:** tablet also refreshes by reading `hidden_materials_global.json` and the job's `hidden_materials.json` directly off the shared drive on a periodic interval, the same live+file-fallback split `DeliveryScheduleStateStore` already implements. This keeps hides propagating even when a tablet's WebSocket is disconnected, since the underlying files are Syncthing-replicated regardless of the live channel's state.

## Tablet-side filtering

The cutlist screen loads the raw `cutlist_index.json` (unchanged) plus the two hide-list sources, computes the effective-hidden set per the three-step rule above, and filters material sections at render time. Because filtering is entirely client-side, a hide/unhide takes effect immediately once the tablet has the updated hide-list data — it does not wait on any worker reparse or cache regeneration.

## UI

- Each material's section header (per doc type) gets a long-press (or overflow icon) action:
  - Not hidden → menu offers **"Hide for this job"** and **"Hide for all future jobs"**.
  - Hidden → menu offers **"Unhide for this job"** and **"Remove from all-jobs auto-hide"**.
- Cutlist toolbar gets a **"Show hidden materials"** toggle, persisted per-tablet (DataStore boolean, default off — same pattern as `assembly_viewer_defaults`/`specialty_viewer_defaults`). When on, hidden sections render inline with a "Hidden" badge; the long-press menu is available from there too.

## Error handling

- Malformed hide/unhide request sidecars are quarantined/discarded by the backend on consumption (bad JSON, missing required fields); transient I/O/lock/master-write failures leave the sidecar in place for retry, per the existing tablet-request-sidecar invariant.
- REST POST failure (network error, backend unreachable) is silently absorbed by the tablet — the sidecar backup guarantees eventual delivery; no user-facing error needed for the fast path failing alone.
- Backend dedupes by `(docType, material)` per list, so replays (REST + sidecar both landing, or a sidecar retried after a crash) are idempotent.

## Testing scope

- Unit: effective-visibility computation (three-step rule) given combinations of global/job hide/unhide entries.
- Unit: backend request application — dedup on repeat, quarantine malformed sidecar, preserve sidecar on simulated transient write failure.
- Unit: material-name matching (trim + case-insensitive) across doc types.
- Manual/device: hide a material on tablet A, confirm it disappears on tablet B within a few seconds (live path) and after killing A's WebSocket connection (fallback path); confirm "show hidden materials" toggle and unhide flow round-trip.
