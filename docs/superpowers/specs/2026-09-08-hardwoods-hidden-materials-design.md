# Hardwoods Hidden Materials — Design

## Problem

Hardwoods-mode users want to hide cutlist materials that don't apply to a given job (or never apply at all) instead of scrolling past them. Hiding must be a cross-tablet feature — one tablet hides a material, every other tablet sees it hidden too, not a per-device preference.

## Scope

- Hide/unhide a material within one job.
- Auto-hide a material for all future jobs (global), and remove that auto-hide flag later.
- Toggle to reveal hidden materials on the cutlist screen and unhide from there.
- Hide scope is `(docType, material)`, not `material` alone — the same material name (e.g. "Maple") can appear across Face Frame, Nailer, Door, Closet Rod, Door List doc types within a job, and hiding it in one doc type does not hide it in the others. Material name matching is trimmed + case-insensitive.
- **Hardwoods and Specialty get fully independent hidden-materials settings** — same feature, separate state. `SpecialtyDoorPanelsScreen` (via `SpecialtyStateStore` → `hardwoodsProgressStore`) reads rows from the same `HardwoodCutlistIndex`/`HardwoodDocType` data as the Hardwoods cutlist screen (`DoorCutSheetFilter.kt` pulls `DOOR_CUT_LIST` rows, excludes only `FACE_FRAME_CUT_LIST`) — so the two screens share the exact same `(docType, material)` identity space. Without segregation, hiding a material in Hardwoods mode would silently hide it in Specialty mode too, which is the opposite of what's wanted (Specialty routinely needs a material visible that Hardwoods has hidden). Every file, endpoint payload, and live-delta below is namespaced by `mode: "HARDWOODS" | "SPECIALTY"` — two parallel, non-interacting instances of the same mechanism.

## Non-goals

- No change to `ready_jobs_worker_core` or `cutlist_index.json` generation — the worker keeps publishing raw cutlist data exactly as today, unaware hiding exists.
- No retroactive rewrite of already-hidden state when a job's cutlist is reparsed; hidden state is independent of and layered on top of the parsed index.
- No per-tablet-only hiding — every hide/unhide is cross-tablet by design; there is no local-only hide.

## Ownership (per `kkc-metadata-map`)

Hours Tracker backend is the sole writer of all four master files below — two per mode (avoids two tablets racing a direct file write, matching the `production_order.json` pattern — tablets submit requests, the backend applies them). `ready_jobs_worker_core` is not involved.

## Data model

Global auto-hide list — **one file per mode**, applies to every job going forward:

```
Y:\Ready Jobs\.metadata\hardwoods\hidden_materials_global.json           (Hardwoods mode)
Y:\Ready Jobs\.metadata\specialty\hidden_materials_global.json           (Specialty mode)
```

`.metadata\specialty\` is a new subtree, distinct from the existing `.metadata\admin\specialty_items.json`/`.tracker` domain (Hours-Tracker-owned custom specialty items) — this one is Android-tablet-owned hidden-materials state only, following the Hardwoods sibling's layout.

```json
{
  "entries": [
    {"docType": "NAILER_CUT_LIST", "material": "Maple", "hiddenAt": "2026-09-08T18:04:00Z", "tabletId": "tablet-3"}
  ]
}
```

Per-job override file — **one file per mode per job**, lives inside that job's own metadata folder (not the global library, per the bloat concern that motivated this design):

```
Y:\Ready Jobs\<job>\.metadata\hardwoods\hidden_materials.json            (Hardwoods mode)
Y:\Ready Jobs\<job>\.metadata\specialty\hidden_materials.json            (Specialty mode)
```

```json
{
  "hides":   [{"docType": "FACE_FRAME_CUT_LIST", "material": "Oak", "hiddenAt": "...", "tabletId": "..."}],
  "unhides": [{"docType": "NAILER_CUT_LIST", "material": "Maple", "hiddenAt": "...", "tabletId": "..."}]
}
```

Effective visibility for `(docType, material)` within a job **and a mode** — the mode selects which pair of files (global + per-job) to read; the two modes never read each other's files:

1. Hidden if the pair is in the job's `unhides` → **visible** (job override always wins, regardless of global/job hide state).
2. Else hidden if the pair is in the global list OR the job's `hides` → **hidden**.
3. Else → **visible**.

"Remove from all-jobs auto-hide" deletes the entry from that mode's global file only. It takes effect everywhere immediately for that mode (client-side recompute on next read) — no per-job files are touched or need migration, and the other mode's global list is untouched.

## Write path (tablet → master)

Mirrors the existing production-order / job-board / delivery-schedule pattern (`ProductionOrderRequestStore` + `AdminSyncClient`). Every payload below carries `mode: "HARDWOODS" | "SPECIALTY"`, which the backend uses to pick which pair of files (global/job, that mode's subtree) to apply the action to.

- **Fast path:** tablet POSTs to a new Hours Tracker endpoint, e.g. `POST /api/admin-sync/hardwoods-hidden-materials`, body `{mode, action: "hide"|"unhide", scope: "job"|"global", jobId?, docType, material, tabletId, requestedAt}`. Backend applies directly to the relevant master file (global or the job's override file, under that mode's subtree) and appends to the `hides`/`unhides`/`entries` list, deduping by `(docType, material)`.
- **Backup path:** tablet also writes a durable per-tablet sidecar request file (e.g. `hidden_materials_request.<tabletId>.json`, placed beside the master file it targets — that mode's global dir or that mode's job metadata dir) with the same payload shape as the request-sidecar files elsewhere in the app. This is Syncthing-replicated and consumed+deleted by the backend on its next poll cycle if the REST call never arrived (tablet offline, backend unreachable at the moment). Same oldest-first, malformed-vs-transient-failure handling as other request sidecars in the metadata map's Cross-System Contract Invariants.
- The two paths are not mutually exclusive — a tablet fires both on every hide/unhide action. The backend's dedup-by-key application (now keyed by `(mode, docType, material)`) makes applying the same action twice (once via REST, once via sidecar) a no-op the second time.

## Read/propagation path (master → tablets)

- **Fast:** the existing read-only live-index WebSocket (`/api/ready-jobs-worker/live-index`, see `ready_jobs_worker_live_index.py`) gets a sibling delta type for hidden-materials changes, carrying `mode` on each delta (or a small sibling channel using the same hello/snapshot/delta protocol shape). Tablets already hold this connection open for cache-index updates; hidden-materials deltas ride the same socket. Each screen (Hardwoods cutlist, Specialty door panels) only applies deltas for its own mode.
- **Backup:** tablet also refreshes by reading that mode's `hidden_materials_global.json` and the job's mode-scoped `hidden_materials.json` directly off the shared drive on a periodic interval, the same live+file-fallback split `DeliveryScheduleStateStore` already implements. This keeps hides propagating even when a tablet's WebSocket is disconnected, since the underlying files are Syncthing-replicated regardless of the live channel's state.

## Tablet-side filtering

Each screen loads the raw `cutlist_index.json` (unchanged) plus its own mode's two hide-list sources, computes the effective-hidden set per the three-step rule above, and filters material sections/rows at render time. Because filtering is entirely client-side, a hide/unhide takes effect immediately once the tablet has the updated hide-list data — it does not wait on any worker reparse or cache regeneration. The Hardwoods cutlist screen and the Specialty door panels screen run this computation independently against their own mode's files, even though both start from the same underlying `HardwoodCutlistIndex` rows.

## UI

- Each material's section/row header (per doc type) gets a long-press (or overflow icon) action, on both the Hardwoods cutlist screen and the Specialty door panels screen — each screen acts only on its own mode's hide state:
  - Not hidden → menu offers **"Hide for this job"** and **"Hide for all future jobs"**.
  - Hidden → menu offers **"Unhide for this job"** and **"Remove from all-jobs auto-hide"**.
- Each screen's toolbar gets its own **"Show hidden materials"** toggle, persisted per-tablet per-mode (DataStore boolean, default off — same pattern as `assembly_viewer_defaults`/`specialty_viewer_defaults`; e.g. two separate keys/stores, not one shared boolean). When on, hidden sections render inline with a "Hidden" badge; the long-press menu is available from there too. Turning it on in Hardwoods has no effect on Specialty's toggle or vice versa.

## Error handling

- Malformed hide/unhide request sidecars are quarantined/discarded by the backend on consumption (bad JSON, missing required fields); transient I/O/lock/master-write failures leave the sidecar in place for retry, per the existing tablet-request-sidecar invariant.
- REST POST failure (network error, backend unreachable) is silently absorbed by the tablet — the sidecar backup guarantees eventual delivery; no user-facing error needed for the fast path failing alone.
- Backend dedupes by `(mode, docType, material)` per list, so replays (REST + sidecar both landing, or a sidecar retried after a crash) are idempotent.

## Testing scope

- Unit: effective-visibility computation (three-step rule) given combinations of global/job hide/unhide entries.
- Unit: backend request application — dedup on repeat, quarantine malformed sidecar, preserve sidecar on simulated transient write failure.
- Unit: material-name matching (trim + case-insensitive) across doc types.
- Unit: mode segregation — hiding `(docType, material)` in Hardwoods mode does not affect Specialty mode's effective-visibility for the same `(docType, material)`, and vice versa.
- Manual/device: hide a material on tablet A, confirm it disappears on tablet B within a few seconds (live path) and after killing A's WebSocket connection (fallback path); confirm "show hidden materials" toggle and unhide flow round-trip; confirm hiding a material on the Hardwoods cutlist screen leaves it visible on the Specialty door panels screen.
