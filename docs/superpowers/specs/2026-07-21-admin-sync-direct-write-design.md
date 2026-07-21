# Admin Sync Direct-Write — Design Spec

## What & Why

Admin-mode editing of the job lineup (`production_order.json`), the job board (`job_board.json`
— labels, active/pending section moves), and the delivery schedule (`.metadata/delivery_schedule.json`)
currently goes through a per-tablet request-file drop that the Hours Tracker backend polls and
applies every 120 seconds. This causes two observed problems:

1. **Job order sometimes reverts.** The tablet's local drag-reorder state is reseeded from the
   on-disk master data every time a background rescan detects *any* change in the job root or
   `.metadata\` (not just production-order-related changes) — which happens roughly every 10–20
   seconds. Since the backend only consumes queued reorder requests every 120 seconds, a rescan
   almost always lands before the backend has applied the tablet's own edit, and the still-stale
   master data clobbers the local reorder. This is not intermittent bad luck — given the interval
   math, it is close to guaranteed on a busy share.
2. **Delivery schedule edits feel slow.** `.metadata/delivery_schedule.json` is read-only on the
   tablet and is only rewritten by the backend's 120-second poller, so there is an unavoidable
   up-to-2-minute lag between an admin's edit and it appearing anywhere — including on the editing
   tablet's own screen.

This spec adds a synchronous direct-write path from tablet to backend as the primary channel,
keeping the existing request-file mechanism as an offline-tolerant fallback — the requirement is
that job order, job board, and delivery schedule edits must keep working even if the Hours Tracker
backend is temporarily unreachable.

### Evidence (current code)

- `activeOrder` reseed on every scan generation bump:
  `app/src/main/java/com/kkc/sheettracker/ui/browser/JobBrowserScreen.kt:327`
- Rescan triggers every ~10s on any change under baseDir root or `.metadata\`:
  `app/src/main/java/com/kkc/sheettracker/data/TrackerChangeMonitor.kt:207-218,475`
- Static cache poll every 20s (secondary rescan trigger):
  `app/src/main/java/com/kkc/sheettracker/data/StaticCachePoller.kt:41`
- Backend applies queued production-order requests every 120s:
  `C:\Scripts\Hours Tracker\backend\main_v2.py:1011` (`_production_order_request_poller`)
- Backend applies queued job-board edit requests every 120s:
  `C:\Scripts\Hours Tracker\backend\main_v2.py:1091` (`_job_board_edit_request_poller`)
- Backend applies queued delivery-schedule requests every 120s:
  `C:\Scripts\Hours Tracker\backend\main_v2.py:1262` (`_delivery_schedule_request_poller`)
- Delivery schedule is read-only on tablet, written only by the backend:
  `app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleRepository.kt:15`
- Existing web-frontend endpoints already do synchronous apply-and-return for this same data:
  `C:\Scripts\Hours Tracker\backend\routes\board.py:488` (`PUT /api/board/reorder`),
  `routes/board.py:797` (`PUT /api/board/folder-labels/{folder_name}`),
  and the `/delivery-schedule` routes referenced from `frontend/lib/api_kkc.ts:373-383`.

The per-tablet request-file naming (`production_order_request.<tabletId>.json`, etc.) was
introduced to fix METADATA_AUDIT.md issue M-04 — two tablets writing one shared request file
before the same poll cycle could collide. That fix stays; this spec does not reintroduce
multi-writer access to the master files. The backend remains the only process that ever writes
`production_order.json`, `job_board.json`, or `delivery_schedule.json`.

---

## Architecture

Keep the existing per-tablet request-file stores exactly as they are today — they are the
offline-tolerant fallback layer and already solve the cross-tablet collision problem. Add a
synchronous HTTP path in front of them:

1. On a job-order drag, job-board edit (label or section), or delivery-schedule slot edit, the
   tablet first tries a direct HTTP call to the Hours Tracker backend, with a short timeout
   (~5 seconds — the same timeout shape already used by `TimecardRepository` for its calls to
   timeclock-hub).
2. The backend applies the edit **synchronously, inside the HTTP request handler**, calling the
   exact same apply logic the 120-second pollers already use for that request type
   (`job_store.save_production_order` + `board.sync_board_positions` for order;
   `board.update_folder_labels` / `board.set_folder_board_section` for job board; the existing
   per-slot delivery-schedule apply), under the same lock the poller uses. It returns the updated
   master JSON in the response body.
3. On success, the tablet updates its local UI state directly from the response — it does not
   wait for the next background rescan. This is what eliminates the revert: the master file is
   already correct by the time any rescan fires, so a reseed-from-master can only ever reseed to
   the correct value.
4. On any failure (timeout, connection refused, non-2xx) the tablet falls back to writing the
   per-tablet request file, exactly as it does today. No new behavior is needed for the "backend
   is offline" case — this is the code path that already works.
5. The backend's fallback pollers keep running as a safety net (a request file can still be left
   behind if the app is killed between the file write and a retry, or during an extended backend
   outage). Their interval drops from 120s to ~15s, since they are no longer the primary path —
   this is a plain constant change, not a new concurrency concern, since each tablet still owns
   its own request file.

Explicitly out of scope: no new locking model, no direct multi-writer access to the master files,
no change to how conflicting edits from two different tablets are resolved (oldest-request-first,
newer edit wins on the same slot/job — unchanged).

---

## Components

### Backend (`C:\Scripts\Hours Tracker\backend`)

Add three thin synchronous endpoints. Each one wraps the *same* single-request apply function the
corresponding poller already calls, so there is exactly one implementation of "what an edit means"
per data type — used by both the fast path and the poller fallback, so the two paths cannot drift
apart in behavior.

- `POST /api/board/production-order/apply` — body `{ order: [folderName], tabletId }`, wraps the
  same two calls `_apply_production_order_requests` already makes
  (`job_store.save_production_order`, `board.sync_board_positions`), under `board._board_lock`.
  Returns the resulting order.
- `POST /api/board/job-board-edits/apply` — body `{ edits: [{folderName, labelIds?, boardSection?}], tabletId }`,
  wraps the same per-edit logic `_apply_job_board_edit_requests` already runs, under the same lock.
  Returns the resulting job-board state for the touched folders.
- `POST /api/delivery-schedule/apply` — body matching `DeliveryScheduleEditRequest` (slot edits or
  a full reset), wraps the same per-request apply `_apply_delivery_schedule_requests` already runs.
  Returns the updated schedule.

These are new routes, not a repurposing of the existing web-frontend endpoints
(`PUT /api/board/reorder`, `PUT /api/board/folder-labels/{folder_name}`) — those operate on the
web board's internal job-id/position model, while the tablet's request stores already operate on
plain folder-name lists and label/section values. Wrapping the poller's existing apply functions
directly avoids a translation layer and keeps one apply implementation per data type instead of
three (poller, tablet endpoint, web endpoint).

### Android (`KKCSheetTracker`)

- New `AdminSyncClient` — a thin OkHttp client mirroring `TimecardRepository`'s shape (5s
  connect/read timeout), with one method per endpoint above.
- New config for the Hours Tracker backend's base URL. This does not exist today — the tablet has
  never called this backend directly (only the shared network drive and, separately,
  timeclock-hub). Reuse the existing `timeclock_config` DataStore pattern (mDNS auto-discovery
  with a manual-IP override) rather than inventing a second discovery mechanism.
- Each existing call site tries `AdminSyncClient` first and falls back to the existing store call
  unchanged on failure:
  - `JobBrowserScreen.kt:359` (`requestStore.writeRequest`) — try
    `AdminSyncClient.applyProductionOrder` first.
  - Job-board label/section edit call sites using `JobBoardRequestStore.queueLabelEdit` /
    `queueBoardSectionEdit` — try `AdminSyncClient.applyJobBoardEdits` first.
  - `DeliveryScheduleDialog.kt` call sites using `DeliveryScheduleRequestStore.queueSlotEdit` /
    `queueReset` — try `AdminSyncClient.applyDeliverySchedule` first.

---

## Data Flow (job order example)

1. Admin drags a job card. `activeOrder` updates locally (unchanged from today).
2. `saveActiveOrder()` computes `newOrder` (unchanged), then:
   - Tries `AdminSyncClient.applyProductionOrder(newOrder, tabletId)`.
   - **Success:** response contains the canonical order; the tablet's state is set to match it
     (normally identical to what was sent — this just closes the loop on the true master value).
     Other tablets pick up the new order on their next rescan (10–20s later) with no revert, since
     master is already correct.
   - **Failure:** falls back to `requestStore.writeRequest(newOrder, tabletId)`, exactly as today.
     The safety-net poller (now ~15s) applies it whenever the backend is reachable again.

Job-board edits and delivery-schedule edits follow the identical shape: try the direct call, use
the response to update local state on success, fall back to the existing request-file write on
failure.

---

## Error Handling

- Timeout, connection failure, and non-2xx responses are all treated identically: fall back to the
  file-drop write. There is no client-side retry loop before falling back — a stalled drag gesture
  or a stuck dialog is worse than an immediate fallback to the mechanism that already works.
- The backend's apply functions are already idempotent by design (documented in
  `_apply_production_order_requests`: "the apply is idempotent, rewrites the master to match the
  requested order"). So in the rare case where the HTTP call actually succeeded on the backend but
  the response was lost in transit, a redundant fallback file write is harmless — it reapplies the
  same already-current state, not a duplicate or conflicting one.
- No new conflict-resolution rule is introduced. Two tablets editing the same job/slot concurrently
  still resolve exactly as today (oldest-request-first ordering, newer edit wins). This change only
  affects how quickly a single tablet's own edit becomes visible, not who wins a genuine conflict.

---

## Testing

- **Backend:** add tests asserting the new synchronous endpoints and the existing poller path
  produce identical master-file output for the same input, using the same fixtures as
  `tests/test_production_order_request.py`, `tests/test_job_board_request.py`, and
  `tests/test_delivery_schedule_request.py`. This proves the fast path and the fallback path can
  never behave differently for the same request.
- **Android:** unit tests for `AdminSyncClient` fallback behavior — mock an HTTP failure and assert
  the corresponding `*RequestStore` call still fires; mock a success and assert local state updates
  from the response body and no request file is written. Existing tests
  (`ProductionOrderRequestStoreTest.kt`, `JobBoardRequestStoreTest.kt`,
  `DeliveryScheduleRequestStoreTest.kt`) stay as-is, since the fallback path itself is unchanged.
- **On-device:** reorder jobs on the admin tablet with the Hours Tracker backend reachable —
  confirm no revert, and confirm a second tablet sees the new order within one rescan cycle. Then
  stop the Hours Tracker backend container and repeat the reorder — confirm it still queues via
  the existing file-drop mechanism and applies once the backend is back up.

---

## Out of Scope

- Any change to how the backend serializes concurrent writes internally (`board._board_lock`
  stays as-is).
- Any change to the web frontend's own board/order/delivery-schedule endpoints.
- Push-based (e.g. WebSocket) propagation to *other* (non-editing) tablets — they still rely on the
  existing 10–20s rescan of the shared drive to notice a master-file change. This spec only fixes
  the editing tablet's own round trip and the resulting revert bug.
