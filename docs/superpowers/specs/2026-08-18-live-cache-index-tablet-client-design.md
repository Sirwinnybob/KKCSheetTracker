# Live Cache-Index Tablet Client Design

**Status:** Approved design — ready for implementation planning on 2026-08-18.

## Context

Hours Tracker's Ready Jobs worker migration added a WebSocket read path for the
jobs-list cache index (`/api/ready-jobs-worker/live-index`, server-side design:
`2026-08-03-ready-jobs-live-cache-index-design.md` in the Hours Tracker repo).
The server side is already implemented (`LiveIndexService`,
`ready_jobs_worker_live_index.py`) and sends the same `jobInfo` +
`progressSummary` payload shape that `.metadata/cache_index.json` already
carries on disk.

KKCSheetTracker's jobs list currently learns about cache changes from
`StaticCachePoller`, which polls each job folder's `cache_index.json` and
`deployment_gate.json` mtimes on a 20-second interval (`app/src/main/java/com/kkc/sheettracker/data/StaticCachePoller.kt`).
That interval, plus Syncthing replication lag, is the visible-delay problem
the live socket solves. This design is the tablet-side (Android) half of
that migration: connect to the existing socket, feed its data into the jobs
list, and keep the polling/file path as the disconnected fallback exactly as
the server-side design's Non-goals and Testing sections require.

Scope is the jobs-list index only. Opening a job still hydrates full
`cache_static.json` data through the existing per-job load path — unaffected
by this design.

## Decision

### New components

- **`LiveIndexClient`** (`data/LiveIndexClient.kt`) — OkHttp `WebSocketListener`
  wrapper. Connects to `ws://<configured-ip>:47821/api/ready-jobs-worker/live-index`,
  built from the existing `AdminSyncConfig` (same server, same manual-IP
  setting, same default `192.168.1.15`; no new settings surface). Sends the
  `hello`/`tabletId` handshake, parses `snapshot`/`delta`/`not_running`/`error`
  frames, and reports connection state and parsed payloads via callbacks.
- **`LiveAwareUnifiedMetadataEngine`** (`data/unified/LiveAwareUnifiedMetadataEngine.kt`)
  — decorator implementing `UnifiedMetadataEngine`, using Kotlin interface
  delegation (`by delegate`) against the existing `FileBackedUnifiedMetadataEngine`.
  Overrides only `listJobsFromCacheIndex`, `getProgressFromIndex`, and
  `getCachedJobInfos`; every other method passes straight through unchanged.
  This keeps the already-large `FileBackedUnifiedMetadataEngine` untouched and
  keeps socket-state concerns out of the file-parsing implementation.

### LiveIndexClient behavior

- `start()`/`stop()` mirror `StaticCachePoller`'s lifecycle shape (called from
  the same `ON_START`/`ON_STOP` `DisposableEffect` pattern used throughout
  `NavGraph.kt`).
- On open: send `{"type":"hello","tabletId":tabletId}` (same tabletId
  convention already used by `AdminSyncClient` and the request-file stores).
- On message: `snapshot` → full replace via `onSnapshot(jobs)`; `delta` with a
  non-null `index` → `onDelta(folderName, entry)` upsert; `delta` with a null
  `index` → `onDelta(folderName, null)` removal; `not_running` or `error` →
  treated as a disconnect.
- Each job's `index` object is parsed with the same data class
  `FileBackedUnifiedMetadataEngine` already uses to deserialize
  `cache_index.json`'s root (`jobInfo` + `progressSummary`) — no new DTO, since
  the server-side design deliberately keeps that schema identical over the
  socket.
- On close/failure: report disconnected, then reconnect on exponential backoff
  (1s → 2s → 4s → … capped at 30s), resetting to 1s after a successful open.
  Retries only while the client has been `start()`-ed; `stop()` cancels any
  pending retry.
- A server-sent `snapshot` is always treated as a full replace regardless of
  why it arrived (initial connect, revision-gap resync, or a server-instance
  change after restart) — the server already resolves gap detection per its
  own design, so the client needs no separate gap-tracking logic.

### LiveAwareUnifiedMetadataEngine behavior

- Holds a `ConcurrentHashMap` of live-parsed entries and a `connected` flag,
  both defaulting to empty/false — a tablet that never wires up
  `LiveIndexClient` (e.g. mid-rollout) gets a strict no-op decorator that
  always defers to the delegate.
- `applySnapshot(jobs)` replaces the live map wholesale and sets
  `connected = true`. Connected state only flips true once a real snapshot has
  landed — not merely on socket open — so there's never a window where
  `connected` is true but the live map is still empty.
- `applyDelta(folderName, entry)` upserts or removes a single entry.
- `setConnected(false)` clears the live map immediately. This is called both
  from the client's disconnect callback and explicitly from `NavGraph`'s
  `ON_STOP` handler, so backgrounding the app drops stale live state right
  away rather than waiting on OkHttp's close notification.
- `listJobsFromCacheIndex`, `getProgressFromIndex`, and `getCachedJobInfos`
  check `connected` first: true → build from the live map; false → delegate
  to the wrapped `FileBackedUnifiedMetadataEngine`'s existing file-parsed
  behavior, unchanged.

### Wiring (NavGraph.kt)

- `scanCoordinator.unifiedEngine` is wrapped once in a remembered
  `LiveAwareUnifiedMetadataEngine`; jobs-list-facing code reads through that
  wrapper instead of the raw engine.
- A remembered `LiveIndexClient` is constructed alongside it. Its callbacks:
  - `onSnapshot`/`onDelta` apply to the engine wrapper, then bump the existing
    `watcherRefreshSignal` (the same signal `StaticCachePoller` and
    `TrackerChangeMonitor` already use to trigger a jobs-list re-read) so no
    new observation mechanism is needed downstream.
  - `onConnectionState(true)` calls `staticCachePoller.stop()`; `false` calls
    `staticCachePoller.start()`. The poller itself is unmodified — it is
    simply paused while the socket is live and resumed the instant it isn't,
    so there's no redundant file polling while connected but no gap in
    coverage once disconnected.
- The same `ON_START`/`ON_STOP` `DisposableEffect` pattern already used for
  `trackerChangeMonitor` and `staticCachePoller` starts/stops the client;
  `ON_STOP` also calls `liveIndexEngine.setConnected(false)` directly.

### Fallback semantics

- Cold start: before the first snapshot arrives, `connected` is false, so the
  jobs list reads the existing file-parsed path immediately — no blank list
  while waiting on the handshake.
- Old app versions or a tablet where the socket never connects: engine
  defaults to delegate behavior, identical to today.
- Opening a job is untouched by this design; only the jobs-list index read
  path changes.

## Testing

- `LiveIndexClientTest`: a fake `WebSocketListener`/fake socket feeds
  snapshot, upsert delta, removal delta, `not_running`, and error frames;
  assert the corresponding `onSnapshot`/`onDelta`/`onConnectionState` calls,
  the backoff sequence across repeated failures, and backoff reset after a
  successful reconnect.
- `LiveAwareUnifiedMetadataEngineTest`: snapshot application changes
  `listJobsFromCacheIndex`'s result; `setConnected(false)` reverts to the
  delegate's (fake) return value; delta upsert/remove correctness; passthrough
  of an arbitrary unrelated interface method proves the `by delegate` wiring
  is intact.
- `StaticCachePollerTest` is unchanged — only who calls `start()`/`stop()`
  changes, not the poller's own logic.
- Deployment verification (per the server-side design's own Android test
  list): a release tablet connects on the shop LAN, observes a changed CNC and
  hardwood index within one worker refresh cycle, then confirms the jobs list
  remains usable after the socket is intentionally killed.

## Known limitations

- ~~Dashboard-family screens stay on the file-backed path even while the
  socket is connected.~~ **Resolved 2026-08-19** by
  `docs/superpowers/specs/2026-08-19-dashboard-live-index-wiring-design.md`.
  The offending call sites were `HardwoodsDashboardContent` and
  `AssemblyStateStore.engine()` (both in `UnifiedModeDashboardScreen.kt` /
  `AssemblyStateStore.kt`) — not `AssemblyDashboardScreen.kt` as originally
  noted here, which the follow-up design's research found to be dead code
  with no caller. Both now read through the same shared live-aware engine
  instance the Jobs tab uses.
- The debug-build `hiddenFromProduction` visibility allowance
  (`DeploymentGateRules`, only applies to non-live-connected reads) has no
  equivalent on the live path — see the tablet-client implementation plan's
  self-review for detail. Debug builds lose that visibility while connected.

## Non-goals

- Any change to job-detail/viewer screens or full job data hydration — this
  is the jobs-list index only, matching the server-side design's own
  non-goals.
- A new settings/config surface for the socket URL — reuses `AdminSyncConfig`
  as-is.
- Tablet-to-server action submission over this socket (CNC/hardwood tracker
  actions) — the server-side design already scopes that as a separate, later
  slice; this design does not touch it either.
- Removing or modifying `StaticCachePoller`'s own polling logic — it is only
  paused/resumed from outside, never changed internally.
