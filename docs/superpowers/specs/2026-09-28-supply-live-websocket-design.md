# Supply Live WebSocket Read Path

Date: 2026-09-28
Status: Design approved, not yet planned.
Repos: Hours Tracker (`C:\Scripts\Hours Tracker\backend`) and KKCSheetTracker (this repo).

## Goal

Remove the tablet's repeated full supply file scan by serving the supply read model over a
read-only WebSocket from Hours Tracker, with today's file reads as the fallback whenever the
socket is not live.

Today `SupplySubscriptionManager.scanForUpdates()` runs on every watcher refresh on every screen
(two `TODO(supply-scan)` blocks in `NavGraph.kt`, plus `MainActivity.onResume`). Each run lists
`.supply/items`, lists `.supply/status`, parses every item and status file, and lists and parses
every comment directory of subscribed items. In the 2026-09-23 idle profiling it accounted for
roughly 3 of 7 active samples on the idle coroutine pool (bursts of ~10-18% of one core).

## Success criteria

- While the socket is live, an idle tablet performs no supply file reads. The supply scan no
  longer appears in idle CPU profiles.
- A supply change made anywhere (Hours Tracker web admin or another tablet, once Syncthing has
  delivered it to the server) updates the Supply nav badge and dashboard widget count within
  about 3 seconds, on any screen.
- With the server unreachable, every supply screen and the badge behave exactly as today.
- A tablet's own supply write is visible on that tablet immediately and is never visibly reverted
  by an older live payload.

## Decisions

| Question | Decision |
|---|---|
| Which reads use live state | The whole supply read model: badge scan, dashboard list, item detail, comments, edit screen reads |
| Own-write visibility | Local pending overlay on the tablet; writes stay file + Syncthing |
| Protocol shape | Full-snapshot replacement (delivery-schedule pattern), not per-item deltas |
| Subscriptions | Stay tablet-owned in `filesDir/supply_subscriptions.json`; notifications recompute in memory from live state |
| Barcodes (`barcodes.json`) | Out of scope; scanner reads it only on scan |

Rationale for full-snapshot replacement: the resolved supply model is about 55 KB today
(105 items, 227 status files, 21 comment files; measured 2026-09-28). Supply changes are a few
per hour. Resending the whole model per change is negligible and avoids delta ordering logic.
Revisit per-item deltas only if the supply catalog grows into the thousands of items.

## Ownership (unchanged)

No write ownership changes. Every `.supply` writer listed in the KKC metadata map keeps writing
the same files the same way. Hours Tracker gains a read-only publisher of state it already reads.
Tablets gain an in-memory read source. Attachment binaries stay file-only.

## Server (Hours Tracker)

### `ready_jobs_worker_core/adapters/supply_live_service.py` — `SupplyLiveService`

Structural copy of `DeliveryScheduleLiveService`:

- Holds the latest complete supply document under a `threading.Condition`.
- `replace(document) -> int`: deep-copies; bumps the in-process revision and notifies waiters only
  if the content differs from the current document; returns the current revision.
- `snapshot() -> {"type": "snapshot", "revision": N, "supply": {...}}`.
- `wait_for_update(after_revision, timeout) -> {"type": "supply", "revision": N, "supply": {...}} | None`.
  `None` means the timeout elapsed. A revision already newer than `after_revision` returns
  immediately.
- No file I/O and no supply business rules.

### `ready_jobs_worker_core/adapters/supply_live_monitor.py` — `SupplyLiveMonitor`

Polls every 2 seconds (`run_until(stop_event)`, same shape as `DeliveryScheduleLiveMonitor`).

- **Signature:** a sorted tuple of `(relative path, st_mtime_ns, st_size)` for
  `categories.json`, `schema.json`, `items/*.json`, `status/*.json` and `comments/*/*.json`
  under `supply_store.supply_data_dir()`. Entries where `is_sync_conflict(name)` is true are
  excluded. Directory entries that vanish between listing and `stat` are skipped.
- **Build:** when the signature differs from the last committed one, call the injected
  `build_document` callable (production: `routes/supply_live_document.build_supply_document`).
  The builder is **read-only**; it does not use the `supply_store` loaders, because
  `get_items()` calls `ensure_dirs()` (creates directories, initializes `schema.json` and
  `barcodes.json`) and `read_json()` writes `.corrupt` backups and raises on one bad file. The
  builder reuses `supply_store` path helpers and `DEFAULT_SCHEMA`, `routes.utils.parse_instant`,
  `is_sync_conflict` and `validate_filename`; mirrors `get_status` latest-wins resolution and
  SKU barcode injection; skips unreadable item/status/comment files (as the tablet does); and
  raises `SupplyDocumentError` only when `categories.json` or `schema.json` exists but is
  unreadable or not a list.
- **Settle check:** the signature is recomputed after the build; if it changed, nothing is
  published and the next poll retries.
- **Vanished tree:** after a good document has been published, a missing `.supply` directory is
  treated as unavailable (warning, last good document retained), never as an empty catalog.
- **Commit rule:** the new signature is committed only after the build and `on_document`
  (`service.replace`) both succeed. A torn or unreadable file is retried on the next poll, and the
  service keeps serving its last good document.
- `poll_once(initial=True)` always builds. A missing `.supply` directory on initial hydration is
  the legitimate empty state (empty lists/maps, default schema).

### Document shape

```json
{
  "categories": [ { "id": "...", "name": "...", "position": 0 } ],
  "schema":     [ { "id": "...", "key": "...", "label": "...", "type": "...", "builtin": true } ],
  "items": [
    {
      "id": "...", "categoryId": "...", "name": "...", "notes": null,
      "fields": {}, "customFields": {}, "attachmentIds": [], "barcodes": [],
      "createdAt": "...", "updatedAt": "...",
      "status": "IN STOCK", "statusBy": "...", "statusAt": "..."
    }
  ],
  "comments": { "<itemId>": [ { "id": "...", "author": "...", "text": "...", "createdAt": "..." } ] }
}
```

- `status`/`statusBy`/`statusAt` use the same rule as `supply_store.get_status`: latest-wins
  by parsed instant, conflict copies excluded, matching the tablet's `SUPPLY_STATUS_RECENCY`
  rule. Missing status resolves to `IN STOCK` with blank `by`/`at`, the
  same default as the tablet.
- `barcodes` include the SKU barcode (same rule as `_inject_sku_barcode`, tolerant of a
  missing or non-object `fields`), matching the tablet's `resolveWith` behaviour.
- `comments` contains an entry only for items that have at least one comment.
- Field names match the tablet's Gson models (verified 2026-09-28): item keys are the stored
  `items/<id>.json` keys both programs already share, plus `status`/`statusBy`/`statusAt`;
  categories are `{id, name, position}`; comments `{id, author, text, createdAt}`; schema
  `{id, key, label, type, builtin}`.
- Known server/tablet differences the implementation must handle:
  - `supply_store.get_comments` sorts by raw `createdAt` string; the tablet sorts by parsed
    instant (AUD-10). The tablet re-sorts every received comment list with its own comparator;
    the server order is not relied on.
  - `supply_store.get_status` ignores status records with no `at`; the tablet ranks them as
    `Instant.MIN`. The two only disagree when every record for an item lacks `at`. The live
    document follows the server rule; this is accepted.
  - The builder calls `validate_filename(item_id)`, which raises on an unsafe id, and skips such
    an item (logs a warning) instead of failing the whole build.
  - Gson skips Kotlin defaults for these models, so absent JSON keys become `null`. The tablet
    parser normalizes null `fields`/`customFields` to empty maps, null `attachmentIds`/`barcodes`
    to empty lists, and null strings to `""` (except `notes`) before exposing items.

### `routes/supply_live.py`

`APIRouter(prefix="/api/supply")`, `@router.websocket("/live")`. Same flow as
`routes/delivery_schedule_live.py`:

1. Accept. If no service is registered, send `{"type": "not_running"}` and close.
2. Require a first frame `{"type": "hello", "tabletId": "..."}`; otherwise send
   `{"type": "error", "message": "expected a hello message first"}` and close.
3. Send `service.snapshot()`.
4. Loop: race `asyncio.to_thread(service.wait_for_update, after_revision, 1.0)` against
   `websocket.receive()`. Send each update and advance `after_revision`. Frames after `hello` are
   ignored (read-only socket). Return on disconnect; cancel outstanding tasks in `finally`.

Module-level `get_supply_live_service()` / `set_supply_live_service()` guarded by a lock.

### Startup — `main_v2.py`

New `_start_supply_live_once()`, modelled on the delivery-schedule live startup block:

- Build the initial document; on failure log a warning and return (service stays unset, socket
  answers `not_running`).
- Create `SupplyLiveService`, `SupplyLiveMonitor`; `poll_once(initial=True)` must succeed or the
  feed stays off.
- Start a daemon thread `SupplyLiveMonitor` running `run_until(stop_event)`; register the
  service only after the thread starts.
- Include the router in the app next to `delivery_schedule_live_router`.

No publish hook is added to the supply REST write endpoints. Admin writes land in `.supply` and
the 2-second poll publishes them.

## Tablet (KKCSheetTracker)

### `data/SupplyLiveClient.kt`

Near-clone of `DeliveryScheduleLiveClient`:

- URL: `buildAdminSyncUrl(config.getManualIp())` with `http://` → `ws://`, plus `/api/supply/live`.
- Shared `OkHttpClient` with `readTimeout(0)` and `pingInterval(15s)`.
- Generation-guarded `start()` / `stop()`; stale sockets and callbacks from an old generation are
  ignored.
- Reconnect backoff `(1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)`; attempt counter
  resets on a valid snapshot.
- `onOpen` sends `{"type": "hello", "tabletId": ...}`.
- Envelope `{type, revision, supply, message}`:
  - `snapshot`: accepted once per connection; a duplicate is ignored.
  - `supply`: ignored before the initial snapshot and when `revision <= lastRevision`.
  - A revision that is null or negative is ignored.
  - Payload validation: `items` and `categories` must be JSON arrays, `comments` (if present) a JSON
    object. Invalid payloads are dropped without changing state.
  - `not_running` / `error`: report disconnected.
- Reports `onConnectionState(true)` only after the first valid snapshot is delivered.

### `data/SupplyLiveStateStore.kt`

One process-wide instance (`SupplyLiveStateStore.shared`, constructor also public for tests).

State, all mutated under one lock:

- `live: SupplyLiveSnapshot?` — categories, schema, `itemsById`, `commentsByItemId`, revision.
- `liveConnected: Boolean`.
- `overlay` — this tablet's pending writes (see below), each with a creation time.
- `version: StateFlow<Long>` — incremented on every live apply, overlay change, and connection
  change.

API:

- `applyLive(snapshot)`: replace live state, mark connected, prune satisfied overlay entries.
- `setDisconnected()`: `liveConnected = false`, clear `live`. No file I/O (safe on the main thread).
- `view(): SupplyView?` — `null` when not live; otherwise the live state merged with non-expired
  overlay entries. Callers treat `null` as "use files".
- `recordStatus`, `recordCommentAdded`, `recordCommentDeleted`, `recordItemUpserted`,
  `recordItemDeleted`, `recordCategoryCreated` — called by `SupplyRepository` after a successful
  file write, whether or not the socket is live (so a write made just before connecting is still
  protected). `recordCommentDeleted` drops any pending add of the same comment, and
  `recordItemDeleted` drops every pending entry for that item, so a local create-then-delete is
  never resurrected by a leftover overlay entry.
- `setDisconnected()` is idempotent: it bumps `version` only on a real live-to-disconnected
  transition, so repeated reconnect failures do not trigger repeated fallback scans.

Overlay satisfaction rules (an entry is removed when live state shows):

| Local write | Removed when live state has |
|---|---|
| status (`itemId`, `at`) | that item with parsed `statusAt >= at` |
| comment added (`itemId`, comment) | that comment id under that item |
| comment deleted (`itemId`, `commentId`) | no comment with that id under that item |
| item created / updated / barcodes / attachment (`StoredSupplyItem`) | that item with parsed `updatedAt >= updatedAt` |
| item deleted (`itemId`) | no item with that id |
| category created (category) | that category id |

Every overlay entry also expires 2 minutes after creation; after that the live state wins. The
file on the tablet is untouched, so Syncthing still delivers it and the server republishes it.
Timestamp comparisons use `SupplyRepository.parseInstantOrMin`.

Merge semantics for `view()`: overlay item upserts replace the live item's stored fields but keep
the overlay status if a newer status entry exists; status entries override the item's
`status`/`statusBy`/`statusAt`; comment adds are appended then re-sorted with the existing
comment order; deletes hide the item or comment.

### `data/SupplyRepository.kt`

- New constructor parameter `liveStore: SupplyLiveStateStore = SupplyLiveStateStore.shared`.
  Existing `SupplyRepository(basePath)` call sites compile unchanged.
- Reads `getItems`, `getItem`, `getComments`, `getCategories`, `getSchema` (and therefore
  `schemaOrDefault`) return from `liveStore.view()` when non-null, otherwise run today's file
  code unchanged.
- Writes are unchanged file writes, followed by the matching `liveStore.record*` call.
- `getAttachmentFile` / `attachmentPath` stay file-based.

### `data/SupplySubscriptionManager.kt`

- Scan logic unchanged; its repository reads become in-memory while live.
- New: collect `SupplyLiveStateStore.version`, debounce 250 ms, then `scanForUpdates()`. This keeps
  the badge current from pushes on any screen.

### Wiring

- `MainActivity` owns the live client (store is `SupplyLiveStateStore.shared`):
  - `onStart`: `supplyLiveClient.start()`.
  - `onStop`: `supplyLiveClient.stop()`, then `store.setDisconnected()`.
  - Client `onConnectionState(false)`: `store.setDisconnected()`, then one fallback
    `supplySubscriptionManager.scanForUpdates()` on `Dispatchers.IO`.
  - The existing `onResume` scan stays (in-memory when live).
  - Because this lives in `MainActivity`, both navigation stacks (`NavGraph` and
    `LegacySingleStackNavigation`) get it without duplicate wiring.
- `NavGraph.kt`: both `LaunchedEffect(watcherRefreshEpoch, basePath)` blocks call
  `scanForUpdates()` only when `!SupplyLiveStateStore.shared.liveConnected`. Replace the
  `TODO(supply-scan)` comments with a short note pointing to the live path and this spec.
- `SupplyDashboardScreen` and `SupplyItemDetailScreen`: add `store.version` as a key of their data
  load so another tablet's edit appears without leaving the screen.

## Error handling

| Failure | Behaviour |
|---|---|
| Initial server build fails | Feed not started; socket sends `not_running`; tablets read files |
| Torn/corrupt supply file on server | Last good document retained; retried next poll |
| Server unreachable / VPN down | Client backs off to 30 s; tablet reads files throughout |
| Socket drops mid-session | Store clears live state, one fallback scan runs, client reconnects and gets a fresh snapshot |
| Hours Tracker restarts (revision resets to 0) | New connection starts with a new snapshot; revision checks are per connection |
| Tablet write never reaches server | Overlay expires after 2 min; live state wins; Syncthing delivers the file later |
| Invalid frame from server | Ignored; store keeps prior state |

## Testing

Hours Tracker (`backend/tests`), mirroring the delivery-schedule live suites:

- `test_supply_live_service.py`: revision bumps only on content change; `wait_for_update` timeout,
  immediate-return, and wake-up cases; snapshot and update frame shapes.
- `test_supply_live_monitor.py`: signature changes on item add/edit/delete, status add, comment
  add/delete, categories and schema edits; conflict copies ignored; failed build does not commit
  the signature; initial hydration with a missing tree; retained state after a later failure.
- `test_supply_live_api.py`: hello required; snapshot then update after `replace`; `not_running`
  when unregistered.
- `test_supply_live_startup.py`: feed stays off when initial hydration fails.
- Document builder: status resolution, SKU barcode injection, field names, unsafe item id
  skipped without failing the build.

KKCSheetTracker (`app/src/test`):

- `SupplyLiveClientTest`: snapshot gating, duplicate snapshot, stale revision, invalid payload,
  stale-generation callbacks, backoff, null-field normalization, comment re-sort by parsed
  instant.
- `SupplyLiveStateStoreTest`: each overlay satisfaction rule, 2-minute expiry (injected clock),
  merge semantics, `setDisconnected` clears live state, `version` increments.
- `SupplyRepositoryTest` additions: reads from store while live and from files while not; writes
  record overlay entries.
- `SupplySubscriptionManagerTest` addition: a live apply changes `notificationCount` with no file
  reads (repository reads backed by a store with no files present).

## Rollout

1. Deploy Hours Tracker first. Tablets on older builds never connect; nothing changes for them.
2. Build and install the tablet release (`gradlew assembleRelease` + `adb install -r`).
3. Field check:
   - Profile an idle tablet; confirm the supply scan is absent from samples.
   - Change a supply item on the Hours Tracker web admin; confirm the badge updates on a non-supply
     screen within about 3 seconds.
   - Stop the Hours Tracker container; confirm the tablet falls back to files and the badge still
     works; restart it and confirm reconnect.

## Documentation

- Add `/api/supply/live` to the KKC metadata map skill (canonical copy; mirrors sync
  automatically) and to `C:\Scripts\Hours Tracker\METADATA_AUDIT.md`: Hours Tracker publishes a
  read-only supply feed; `.supply` write ownership unchanged.
- After ship, retire the supply-scan TODO memory note.

## Out of scope

- Server-owned subscriptions or server-computed notifications.
- Write-through of tablet supply writes over REST or the socket.
- `barcodes.json` and the barcode scanner.
- Attachment binaries.
- Safety subscriptions (`SafetySubscriptionManager`) and other file-based systems.
