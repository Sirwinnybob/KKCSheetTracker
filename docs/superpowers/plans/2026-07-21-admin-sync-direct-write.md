# Admin Sync Direct-Write Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a synchronous direct-write path from tablet to the Hours Tracker backend for job order, job board, and delivery schedule edits, so a tablet's own edit is reflected in the master files immediately instead of after a 120-second poll — while keeping the existing per-tablet request-file mechanism as the offline fallback.

**Architecture:** Backend gets three new endpoints under `/api/admin-sync/*`, each a thin wrapper around a newly-extracted core apply function that the existing 120s-poll fallback also uses (one implementation per data type, shared by both paths). Android gets one new `AdminSyncClient` (OkHttp, mirrors `TimecardRepository`'s shape) that each existing call site tries first, falling back to the existing `*RequestStore` write unchanged on any failure.

**Tech Stack:** Kotlin / Jetpack Compose (Android, `KKCSheetTracker`), Python / FastAPI (backend, `Hours Tracker/backend/main_v2.py`), OkHttp 4.12.0, pytest, JUnit4 + MockWebServer.

**Spec:** [docs/superpowers/specs/2026-07-21-admin-sync-direct-write-design.md](../specs/2026-07-21-admin-sync-direct-write-design.md)

**Scope note:** the design spec mentions reusing the mDNS-then-manual-IP pattern from `timeclock_config`. This plan implements manual-IP-only (Task 5) — building real mDNS discovery would require the Hours Tracker backend (a separate repo/service) to advertise an mDNS service, which nothing in evidence today does. Manual IP entry in Settings is sufficient to satisfy "must still work if Hours Tracker is offline" (leaving it blank simply means the fast path is always skipped in favor of the fallback that already works).

---

## Backend Tasks (`C:\Scripts\Hours Tracker\backend`)

### Task 1: Production order — extract core apply function, add sync endpoint

**Files:**
- Modify: `main_v2.py:955-1002` (`_apply_production_order_requests`)
- Create: `tests/test_admin_sync_production_order.py`

- [ ] **Step 1: Refactor — extract `_apply_production_order` and `_apply_production_order_request`**

Replace the current `_apply_production_order_requests` function (`main_v2.py:955-1002`) with:

```python
def _apply_production_order(base_path: Path, order: List[str]) -> None:
    """Core apply: rewrite the master production_order.json and sync job_board.json positions to
    match, under the board lock. Shared by the tablet request-file poller and the synchronous
    tablet-facing endpoint so both paths apply an edit identically.
    """
    import routes.job_store as job_store
    import routes.board as board
    with board._board_lock:
        job_store.save_production_order(base_path, order)
        board.sync_board_positions(base_path, order)


def _apply_production_order_request(base_path: Path, req_path: Path) -> bool:
    """Apply one tablet-authored production-order request file, then consume it.

    Admin tablets each write their own `production_order_request.<tabletId>.json` beside
    `production_order.json` (see METADATA_AUDIT.md M-04).
    """
    import logging
    log = logging.getLogger("uvicorn")

    # AUD-03: read/parse/validate. A malformed payload is quarantined; a transient read
    # failure (OSError) is left in place to retry next cycle.
    try:
        with open(req_path, "r", encoding="utf-8") as f:
            payload = json.load(f)
        order = payload.get("order") if isinstance(payload, dict) else payload
        tablet_id = payload.get("tabletId") if isinstance(payload, dict) else None
        if not isinstance(order, list) or not all(isinstance(x, str) for x in order):
            raise ValueError("'order' is not a list of strings")
    except (json.JSONDecodeError, ValueError, UnicodeDecodeError) as e:
        _quarantine_request(req_path, e, log)
        return False
    except OSError as e:
        log.warning(f"Deferring {req_path.name} for retry after read error: {e}")
        return False

    # AUD-03: apply. Any operational failure (master write, board sync, lock, SMB outage)
    # leaves the request in place so a valid tablet edit is retried, not discarded. The apply
    # is idempotent (rewrites the master to match the requested order), so retry is safe.
    try:
        _apply_production_order(base_path, order)
    except Exception as e:
        log.warning(f"Deferring {req_path.name} for retry after operational error: {e}")
        return False

    req_path.unlink(missing_ok=True)
    log.info(f"Applied tablet production order request ({len(order)} jobs) from {tablet_id or 'unknown'}")
    return True


def _apply_production_order_requests(base_path: Path) -> int:
    """Applies all tablet-authored job-lineup requests, one file per tablet, oldest first (see
    METADATA_AUDIT.md M-04), by calling _apply_production_order_request once per file.
    """
    applied = 0
    for req_path in _sorted_request_files(base_path, "production_order_request"):
        if _apply_production_order_request(base_path, req_path):
            applied += 1
    return applied
```

This is behavior-preserving — `_apply_production_order_requests`'s signature and behavior are
unchanged, only its body is now split across three functions.

- [ ] **Step 2: Verify the refactor didn't change behavior**

Run: `cd "C:\Scripts\Hours Tracker\backend" && python -m pytest tests/test_production_order_request.py -v`
Expected: all 5 existing tests still PASS, unchanged.

- [ ] **Step 3: Add the synchronous endpoint**

Add near the poller code (after `_production_order_thread.start()`, `main_v2.py:1021`):

```python
class AdminSyncProductionOrderRequest(BaseModel):
    order: List[str]
    tabletId: str


@app.post("/api/admin-sync/production-order")
def apply_production_order_sync(req: AdminSyncProductionOrderRequest):
    """Synchronous fast path for tablet-authored job-lineup edits. Applies immediately using the
    same _apply_production_order core the fallback poller (above) uses, so the two paths can
    never diverge in behavior. The per-tablet request-file poller remains the fallback for when a
    tablet can't reach this endpoint (offline backend, network partition, etc).
    """
    import routes.job_store as job_store
    from routes.utils import get_base_path
    base_path = get_base_path()
    try:
        _apply_production_order(base_path, req.order)
    except Exception as e:
        raise HTTPException(status_code=503, detail=f"Could not apply production order: {e}")
    return {"order": job_store.get_production_order(base_path)}
```

- [ ] **Step 4: Write the endpoint test**

Create `tests/test_admin_sync_production_order.py`:

```python
import json

from fastapi.testclient import TestClient
from lock_manager import LockManager

import routes.board as board
import routes.utils as utils


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(utils, "get_base_path", lambda: tmp_path)
    monkeypatch.setattr(board, "get_base_path", lambda: tmp_path)
    monkeypatch.setattr(board._board_lock, "lock_manager", LockManager(tmp_path / ".time_cards"))
    import main_v2
    return TestClient(main_v2.app)


def read_order(base_path):
    with open(base_path / "production_order.json", "r", encoding="utf-8") as f:
        return json.load(f)


def test_apply_production_order_sync_applies_immediately_and_returns_order(monkeypatch, tmp_path):
    client = _client(monkeypatch, tmp_path)

    response = client.post(
        "/api/admin-sync/production-order",
        json={"order": ["Job-A", "Job-B"], "tabletId": "tablet-1"},
    )

    assert response.status_code == 200
    assert response.json() == {"order": ["Job-A", "Job-B"]}
    assert read_order(tmp_path) == ["Job-A", "Job-B"]


def test_apply_production_order_sync_rejects_non_string_order_items(monkeypatch, tmp_path):
    client = _client(monkeypatch, tmp_path)

    response = client.post(
        "/api/admin-sync/production-order",
        json={"order": [1, 2], "tabletId": "tablet-1"},
    )

    # Pydantic body validation rejects this before _apply_production_order ever runs.
    assert response.status_code == 422
    assert not (tmp_path / "production_order.json").exists()
```

- [ ] **Step 5: Run the new test**

Run: `cd "C:\Scripts\Hours Tracker\backend" && python -m pytest tests/test_admin_sync_production_order.py -v`
Expected: both tests PASS.

- [ ] **Step 6: Commit**

```bash
cd "C:\Scripts\Hours Tracker"
git add backend/main_v2.py backend/tests/test_admin_sync_production_order.py
git commit -m "feat: add synchronous admin-sync endpoint for production order"
```

---

### Task 2: Job board — extract core apply function, add sync endpoint

**Files:**
- Modify: `main_v2.py:1024-1082` (`_apply_job_board_edit_requests`)
- Create: `tests/test_admin_sync_job_board.py`

- [ ] **Step 1: Refactor — extract `_apply_job_board_edits` and `_apply_job_board_edit_request`**

Replace the current `_apply_job_board_edit_requests` function (`main_v2.py:1024-1082`) with:

```python
def _apply_job_board_edits(base_path: Path, edits: List[Dict[str, Any]]) -> int:
    """Core apply: set labels and/or board section for a list of {folderName, labelIds?,
    boardSection?} edits, under one board-lock acquisition. Shared by the tablet request-file
    poller and the synchronous tablet-facing endpoint so both paths apply an edit identically.
    """
    import routes.board as board
    applied_edits = 0
    with board._board_lock:
        for edit in edits:
            if not isinstance(edit, dict):
                continue
            folder_name = edit.get("folderName")
            if not isinstance(folder_name, str) or not folder_name:
                continue
            label_ids = edit.get("labelIds")
            if isinstance(label_ids, list) and all(isinstance(x, int) for x in label_ids):
                board.update_folder_labels(folder_name, board.JobLabelsUpdateRequest(label_ids=label_ids))
            board_section = edit.get("boardSection")
            if isinstance(board_section, int):
                board.set_folder_board_section(folder_name, board_section)
            applied_edits += 1
    return applied_edits


def _apply_job_board_edit_request(base_path: Path, req_path: Path) -> bool:
    """Apply one tablet-authored job-board edit request file, then consume it."""
    import logging
    log = logging.getLogger("uvicorn")

    # AUD-03: read/parse/validate. Malformed -> quarantine; transient read error -> retry.
    try:
        with open(req_path, "r", encoding="utf-8") as f:
            payload = json.load(f)
        edits = payload.get("edits") if isinstance(payload, dict) else None
        tablet_id = payload.get("tabletId") if isinstance(payload, dict) else None
        if not isinstance(edits, list):
            raise ValueError("'edits' is not a list")
    except (json.JSONDecodeError, ValueError, UnicodeDecodeError) as e:
        _quarantine_request(req_path, e, log)
        return False
    except OSError as e:
        log.warning(f"Deferring {req_path.name} for retry after read error: {e}")
        return False

    # AUD-03: apply. Operational failures leave the request for retry. Each edit is idempotent
    # (sets labels/section to the requested values), so retry converges without duplication.
    try:
        applied_edits = _apply_job_board_edits(base_path, edits)
    except Exception as e:
        log.warning(f"Deferring {req_path.name} for retry after operational error: {e}")
        return False

    req_path.unlink(missing_ok=True)
    log.info(f"Applied tablet job-board edit request ({applied_edits} jobs) from {tablet_id or 'unknown'}")
    return True


def _apply_job_board_edit_requests(base_path: Path) -> int:
    """Applies all tablet-authored label / Pending-Delivery edit requests, one file per tablet,
    oldest first (see METADATA_AUDIT.md M-04), by calling _apply_job_board_edit_request once per
    file.
    """
    applied = 0
    for req_path in _sorted_request_files(base_path, "job_board_request"):
        if _apply_job_board_edit_request(base_path, req_path):
            applied += 1
    return applied
```

- [ ] **Step 2: Verify the refactor didn't change behavior**

Run: `cd "C:\Scripts\Hours Tracker\backend" && python -m pytest tests/test_job_board_request.py -v`
Expected: all existing tests still PASS, unchanged.

- [ ] **Step 3: Add the synchronous endpoint**

Add near the poller code (after `_job_board_edit_thread.start()`, `main_v2.py:1101`):

```python
class AdminSyncJobBoardEditItem(BaseModel):
    folderName: str
    labelIds: Optional[List[int]] = None
    boardSection: Optional[int] = None


class AdminSyncJobBoardEditsRequest(BaseModel):
    edits: List[AdminSyncJobBoardEditItem]
    tabletId: str


@app.post("/api/admin-sync/job-board-edits")
def apply_job_board_edits_sync(req: AdminSyncJobBoardEditsRequest):
    """Synchronous fast path for tablet-authored label/Pending-Delivery edits. Applies immediately
    using the same _apply_job_board_edits core the fallback poller (above) uses.
    """
    from routes.utils import get_base_path
    base_path = get_base_path()
    edits = [e.dict(exclude_none=True) for e in req.edits]
    try:
        applied_edits = _apply_job_board_edits(base_path, edits)
    except Exception as e:
        raise HTTPException(status_code=503, detail=f"Could not apply job board edits: {e}")
    return {"appliedCount": applied_edits}
```

- [ ] **Step 4: Write the endpoint test**

Create `tests/test_admin_sync_job_board.py`:

```python
import json

from fastapi.testclient import TestClient
from lock_manager import LockManager

import routes.board as board
import routes.utils as utils


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(utils, "get_base_path", lambda: tmp_path)
    monkeypatch.setattr(board, "get_base_path", lambda: tmp_path)
    monkeypatch.setattr(board._board_lock, "lock_manager", LockManager(tmp_path / ".time_cards"))
    import main_v2
    return TestClient(main_v2.app)


def job_by_folder(folder_name):
    with open(board.get_base_path() / "job_board.json", "r", encoding="utf-8") as f:
        data = json.load(f)
    return next((j for j in data.get("jobs", []) if j.get("folder_name") == folder_name), None)


def test_apply_job_board_edits_sync_sets_labels_and_returns_applied_count(monkeypatch, tmp_path):
    client = _client(monkeypatch, tmp_path)

    response = client.post(
        "/api/admin-sync/job-board-edits",
        json={"edits": [{"folderName": "Job-A", "labelIds": [1, 2]}], "tabletId": "tablet-1"},
    )

    assert response.status_code == 200
    assert response.json() == {"appliedCount": 1}
    assert job_by_folder("Job-A")["label_ids"] == [1, 2]


def test_apply_job_board_edits_sync_moves_board_section(monkeypatch, tmp_path):
    client = _client(monkeypatch, tmp_path)

    response = client.post(
        "/api/admin-sync/job-board-edits",
        json={"edits": [{"folderName": "Job-A", "boardSection": 1}], "tabletId": "tablet-1"},
    )

    assert response.status_code == 200
    assert job_by_folder("Job-A")["board_section"] == 1
```

- [ ] **Step 5: Run the new test**

Run: `cd "C:\Scripts\Hours Tracker\backend" && python -m pytest tests/test_admin_sync_job_board.py -v`
Expected: both tests PASS.

- [ ] **Step 6: Commit**

```bash
cd "C:\Scripts\Hours Tracker"
git add backend/main_v2.py backend/tests/test_admin_sync_job_board.py
git commit -m "feat: add synchronous admin-sync endpoint for job board edits"
```

---

### Task 3: Delivery schedule — extract core apply function, add sync endpoint

**Files:**
- Modify: `main_v2.py:1157-1235` (`_apply_delivery_schedule_request`)
- Create: `tests/test_admin_sync_delivery_schedule.py`

- [ ] **Step 1: Refactor — extract `_apply_delivery_schedule_edits`**

Replace the current `_apply_delivery_schedule_request` function (`main_v2.py:1157-1235`) with:

```python
def _apply_delivery_schedule_edits(
    base_path: Path, normalized_edits: "list[tuple[str, list[dict[str, str]]]]", reset_all: bool
) -> dict:
    """Core apply: merge slot edits (or a full reset) into the master delivery_schedule.json
    under the distributed delivery lock, and return the resulting schedule. Shared by the tablet
    request-file poller and the synchronous tablet-facing endpoint so both paths apply an edit
    identically.

    CROSS-PROGRAM (see METADATA_AUDIT.md H-05): delivery_schedule.json is also written by
    PUT /api/delivery-schedule (routes/delivery.py:update_delivery_slot), which holds the same
    distributed _delivery_lock() across its full read-modify-write — this function takes that
    same lock (imported from routes.delivery, not duplicated) so a concurrent web edit or a
    second backend instance can never silently clobber this write.
    """
    schema_path = base_path / ".metadata" / "delivery_schedule.json"
    with _delivery_lock():
        schedule = {"schemaVersion": 1, "slots": {}} if reset_all else _read_delivery_schedule_file(schema_path)
        slots = schedule.setdefault("slots", {})
        if not isinstance(slots, dict):
            slots = {}
            schedule["slots"] = slots
        for slot, jobs in normalized_edits:
            slots[slot] = {"jobs": jobs}
        atomic_write_json(schema_path, schedule)
    return schedule


def _apply_delivery_schedule_request(base_path: Path, req_path: Path) -> bool:
    """Apply one tablet-authored delivery schedule request file, then consume it.

    Returns True only when a valid request was applied. Malformed requests are consumed so they
    do not loop forever, matching the production-order and job-board request pollers.

    CROSS-PROGRAM (see METADATA_AUDIT.md M-04): [req_path] is one of possibly several
    `delivery_schedule_request.<tabletId>.json` sidecars (one per tablet) rather than a single
    shared filename — see `_apply_delivery_schedule_requests` below, which globs and sorts them
    and calls this function once per file, oldest first.
    """
    import logging
    log = logging.getLogger("uvicorn")
    if not req_path.exists():
        return False

    # AUD-03: read/parse/validate. A malformed payload is quarantined; a transient read failure
    # (OSError) is left in place to retry next cycle rather than discarding a valid tablet edit.
    try:
        with open(req_path, "r", encoding="utf-8") as f:
            payload = json.load(f)
        if not isinstance(payload, dict):
            raise ValueError("request must be an object")

        raw_edits = payload.get("slotEdits", [])
        if not isinstance(raw_edits, list):
            raise ValueError("slotEdits must be a list")
        normalized_edits = [_normalize_delivery_slot_edit(edit) for edit in raw_edits]
    except (json.JSONDecodeError, ValueError, UnicodeDecodeError) as e:
        _quarantine_request(req_path, e, log)
        return False
    except OSError as e:
        log.warning(f"Deferring {req_path.name} for retry after read error: {e}")
        return False

    try:
        _apply_delivery_schedule_edits(base_path, normalized_edits, payload.get("resetAll") is True)
    except HTTPException as e:
        # _delivery_lock() couldn't acquire the distributed "delivery" lock within its retry
        # window (another server or an in-process web request is holding it). This is transient
        # contention, not a malformed request — leave req_path in place so the next poll cycle
        # retries instead of silently dropping a valid tablet edit.
        log.warning(f"delivery_schedule_request poller: delivery lock busy for {req_path.name}, will retry next cycle: {e.detail}")
        return False
    except Exception as e:
        # AUD-03: any other operational failure (schedule read, atomic write, SMB/I-O outage) is
        # transient — leave the request for retry instead of deleting a valid edit. The apply is
        # idempotent (sets each slot to the requested jobs), so retry converges.
        log.warning(f"Deferring {req_path.name} for retry after operational error: {e}")
        return False

    tablet_id = payload.get("tabletId") or "unknown"
    log.info(f"Applied tablet delivery schedule request ({len(normalized_edits)} slots) from {tablet_id}")
    req_path.unlink(missing_ok=True)
    return True
```

`_apply_delivery_schedule_requests` (the loop below this function) is unchanged — it already calls
`_apply_delivery_schedule_request` once per file.

- [ ] **Step 2: Verify the refactor didn't change behavior**

Run: `cd "C:\Scripts\Hours Tracker\backend" && python -m pytest tests/test_delivery_schedule_request.py -v`
Expected: all existing tests still PASS, unchanged.

- [ ] **Step 3: Add the synchronous endpoint**

Add near the poller code (after `_delivery_schedule_thread.start()`, `main_v2.py:1272`):

```python
class AdminSyncDeliveryScheduleSlotEdit(BaseModel):
    slot: str
    jobs: List[Dict[str, Any]] = []


class AdminSyncDeliveryScheduleRequest(BaseModel):
    tabletId: str
    resetAll: bool = False
    slotEdits: List[AdminSyncDeliveryScheduleSlotEdit] = []


@app.post("/api/admin-sync/delivery-schedule")
def apply_delivery_schedule_sync(req: AdminSyncDeliveryScheduleRequest):
    """Synchronous fast path for tablet-authored delivery schedule edits. Applies immediately
    using the same _apply_delivery_schedule_edits core the fallback poller (above) uses, and
    returns the resulting schedule so the tablet can update its display without waiting for a
    rescan.
    """
    from routes.utils import get_base_path
    base_path = get_base_path()
    try:
        normalized_edits = [_normalize_delivery_slot_edit(e.dict()) for e in req.slotEdits]
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    try:
        schedule = _apply_delivery_schedule_edits(base_path, normalized_edits, req.resetAll)
    except HTTPException:
        raise HTTPException(status_code=423, detail="Delivery schedule is currently locked, try again")
    except Exception as e:
        raise HTTPException(status_code=503, detail=f"Could not apply delivery schedule: {e}")
    return schedule
```

- [ ] **Step 4: Write the endpoint test**

Create `tests/test_admin_sync_delivery_schedule.py`:

```python
from fastapi.testclient import TestClient
from lock_manager import LockManager

import routes.delivery as delivery
import routes.utils as utils


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(utils, "get_base_path", lambda: tmp_path)
    # routes.delivery._delivery_lock_manager is a module-level singleton built from the real
    # get_base_path() at import time (see isolate_delivery_lock in test_delivery_schedule_request.py)
    # — patching routes.utils.get_base_path alone does not affect it.
    monkeypatch.setattr(delivery, "_delivery_lock_manager", LockManager(tmp_path / ".time_cards"))
    import main_v2
    return TestClient(main_v2.app)


def test_apply_delivery_schedule_sync_sets_slot_and_returns_schedule(monkeypatch, tmp_path):
    client = _client(monkeypatch, tmp_path)

    response = client.post(
        "/api/admin-sync/delivery-schedule",
        json={
            "tabletId": "tablet-1",
            "slotEdits": [
                {"slot": "monday_am", "jobs": [{"jobNumber": "123", "description": "Test Job"}]}
            ],
        },
    )

    assert response.status_code == 200
    body = response.json()
    assert body["slots"]["monday_am"]["jobs"] == [{"jobNumber": "123", "description": "Test Job"}]

    schedule_path = tmp_path / ".metadata" / "delivery_schedule.json"
    assert schedule_path.exists()


def test_apply_delivery_schedule_sync_reset_clears_all_slots(monkeypatch, tmp_path):
    client = _client(monkeypatch, tmp_path)
    client.post(
        "/api/admin-sync/delivery-schedule",
        json={"tabletId": "tablet-1", "slotEdits": [{"slot": "monday_am", "jobs": [{"jobNumber": "1", "description": "x"}]}]},
    )

    response = client.post("/api/admin-sync/delivery-schedule", json={"tabletId": "tablet-1", "resetAll": True})

    assert response.status_code == 200
    assert response.json()["slots"] == {}
```

- [ ] **Step 5: Run the new test**

Run: `cd "C:\Scripts\Hours Tracker\backend" && python -m pytest tests/test_admin_sync_delivery_schedule.py -v`
Expected: both tests PASS.

- [ ] **Step 6: Commit**

```bash
cd "C:\Scripts\Hours Tracker"
git add backend/main_v2.py backend/tests/test_admin_sync_delivery_schedule.py
git commit -m "feat: add synchronous admin-sync endpoint for delivery schedule"
```

---

### Task 4: Shrink fallback poller intervals from 120s to 15s

**Files:**
- Modify: `main_v2.py:1011`, `main_v2.py:1091`, `main_v2.py:1262`

- [ ] **Step 1: Change the three poller sleep intervals**

These pollers are now a safety net (a request file is left behind only when a tablet couldn't
reach the fast-path endpoint at all), not the primary path, so the interval can drop safely —
each tablet still owns its own request file, so there's no new collision risk from polling more
often.

In `_production_order_request_poller` (`main_v2.py:1011`):
```python
        time.sleep(15)
```
(was `time.sleep(120)`)

In `_job_board_edit_request_poller` (`main_v2.py:1091`):
```python
        time.sleep(15)
```
(was `time.sleep(120)`)

In `_delivery_schedule_request_poller` (`main_v2.py:1262`):
```python
        time.sleep(15)
```
(was `time.sleep(120)`)

- [ ] **Step 2: Re-run all three request-poller test suites**

Run: `cd "C:\Scripts\Hours Tracker\backend" && python -m pytest tests/test_production_order_request.py tests/test_job_board_request.py tests/test_delivery_schedule_request.py -v`
Expected: all PASS (these tests call the apply functions directly, not the sleep loop, so the
interval change doesn't affect them).

- [ ] **Step 3: Commit**

```bash
cd "C:\Scripts\Hours Tracker"
git add backend/main_v2.py
git commit -m "perf: shrink admin-sync fallback poller interval from 120s to 15s"
```

---

## Android Tasks (`C:\Scripts\KKCSheetTracker`)

### Task 5: AdminSyncConfig + Settings UI field

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/AdminSyncConfig.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/data/AdminSyncConfigTest.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

- [ ] **Step 1: Create `AdminSyncConfig.kt`**

```kotlin
package com.kkc.sheettracker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.adminSyncDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "admin_sync_config"
)

private const val ADMIN_SYNC_PORT = 5002

private object AdminSyncConfigKeys {
    val serverIp = stringPreferencesKey("server_ip")
}

/** Pure so it's testable without a DataStore/Context. Null input or blank IP -> null URL. */
internal fun buildAdminSyncUrl(manualIp: String?): String? =
    manualIp?.trim()?.takeIf { it.isNotBlank() }?.let { "http://$it:$ADMIN_SYNC_PORT" }

/**
 * Manual-IP config for the Hours Tracker backend's direct-write admin-sync endpoints
 * (production order / job board / delivery schedule fast path). Unlike timeclock's
 * TimecardServerConfig, there is no mDNS auto-discovery here — Hours Tracker does not advertise
 * an mDNS service, so an admin must set this IP once in Settings for the fast path to be used.
 * Leaving it unset means AdminSyncClient calls are always skipped in favor of the existing
 * per-tablet request-file fallback (see ProductionOrderRequestStore, JobBoardRequestStore,
 * DeliveryScheduleRequestStore).
 */
class AdminSyncConfig(private val dataStore: DataStore<Preferences>) {

    val serverIpFlow: Flow<String?> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs -> prefs[AdminSyncConfigKeys.serverIp]?.takeIf { it.isNotBlank() } }

    suspend fun getManualIp(): String? =
        dataStore.data
            .map { prefs -> prefs[AdminSyncConfigKeys.serverIp]?.takeIf { it.isNotBlank() } }
            .first()

    suspend fun setManualIp(ip: String?) {
        dataStore.edit { prefs ->
            if (ip.isNullOrBlank()) prefs.remove(AdminSyncConfigKeys.serverIp)
            else prefs[AdminSyncConfigKeys.serverIp] = ip.trim()
        }
    }

    /** Returns "http://<ip>:5002", or null if no IP has been configured yet. */
    suspend fun getServerUrl(): String? = buildAdminSyncUrl(getManualIp())

    companion object {
        fun create(context: Context): AdminSyncConfig = AdminSyncConfig(context.adminSyncDataStore)
    }
}
```

- [ ] **Step 2: Write the test**

Create `app/src/test/java/com/kkc/sheettracker/data/AdminSyncConfigTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdminSyncConfigTest {

    @Test
    fun `builds url from a plain ip`() {
        assertEquals("http://192.168.1.20:5002", buildAdminSyncUrl("192.168.1.20"))
    }

    @Test
    fun `trims whitespace around the ip`() {
        assertEquals("http://192.168.1.20:5002", buildAdminSyncUrl("  192.168.1.20  "))
    }

    @Test
    fun `returns null for null input`() {
        assertNull(buildAdminSyncUrl(null))
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(buildAdminSyncUrl("   "))
    }
}
```

- [ ] **Step 3: Run the test**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.AdminSyncConfigTest"`
Expected: all 4 tests PASS.

- [ ] **Step 4: Wire the config into NavGraph.kt**

At `NavGraph.kt:426`, right after the existing line:
```kotlin
    val timecardConfig = remember { TimecardServerConfig.create(context) }
```
add:
```kotlin
    val adminSyncConfig = remember { AdminSyncConfig.create(context) }
```

- [ ] **Step 5: Add a "Hours Tracker Admin Sync" card to SettingsScreen.kt**

Add `adminSyncConfig: AdminSyncConfig` as a new parameter to `SettingsScreen` (`SettingsScreen.kt:43`,
alongside the existing `timecardConfig: TimecardServerConfig` at line 67), then pass it at every
call site in `NavGraph.kt` that currently passes `timecardConfig = timecardConfig` for
`SettingsScreen` (lines 822 and 1876) as `adminSyncConfig = adminSyncConfig`.

In `SettingsScreen.kt`, after the existing timeclock state block (after line 103,
`var serverIpSaved by remember { mutableStateOf(false) }`), add:

```kotlin
    val currentAdminSyncIp by adminSyncConfig.serverIpFlow.collectAsState(initial = null)
    var editAdminSyncIp by remember(currentAdminSyncIp) { mutableStateOf(currentAdminSyncIp ?: "") }
    var adminSyncIpDirty by remember(currentAdminSyncIp) { mutableStateOf(false) }
    var adminSyncIpSaved by remember { mutableStateOf(false) }
```

After the existing `LaunchedEffect(serverIpSaved) { ... }` block (after line 136), add:

```kotlin
    LaunchedEffect(adminSyncIpSaved) {
        if (adminSyncIpSaved) {
            delay(1600)
            adminSyncIpSaved = false
        }
    }
```

After the "Timeclock" `SettingsCard` block (after line 661), add a new card:

```kotlin
            // ── Admin Sync ───────────────────────────────────────────────
            SettingsCard(title = "Hours Tracker Admin Sync") {
                OutlinedTextField(
                    value = editAdminSyncIp,
                    onValueChange = {
                        editAdminSyncIp = it
                        adminSyncIpDirty = (it.trim() != (currentAdminSyncIp ?: ""))
                    },
                    label = { Text("Hours Tracker server IP address") },
                    placeholder = { Text("Not configured (fast path disabled)") },
                    supportingText = { Text("Enables instant job order / job board / delivery schedule sync. Leave blank to always use the existing (slower) sync mechanism.") },
                    colors = filledFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (adminSyncIpSaved) {
                        Text(
                            "Saved",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            timecardScope.launch {
                                adminSyncConfig.setManualIp(editAdminSyncIp.ifBlank { null })
                            }
                            adminSyncIpDirty = false
                            adminSyncIpSaved = true
                        },
                        enabled = adminSyncIpDirty
                    ) {
                        Text("Save")
                    }
                }
            }
```

(Reuses the existing `timecardScope` coroutine scope already declared at line 105 — no new scope
needed.)

- [ ] **Step 6: Build to confirm no compile errors**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/AdminSyncConfig.kt \
        app/src/test/java/com/kkc/sheettracker/data/AdminSyncConfigTest.kt \
        app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt \
        app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: add AdminSyncConfig and Settings field for Hours Tracker admin-sync IP"
```

---

### Task 6: Widen DeliveryScheduleRepository's schedule parser for reuse

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleRepository.kt`

- [ ] **Step 1: Move `parseSchedule` out of the class as a reusable top-level function**

Replace the whole file content with:

```kotlin
package com.kkc.sheettracker.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import com.kkc.sheettracker.data.models.DELIVERY_DAYS
import com.kkc.sheettracker.data.models.DELIVERY_PERIODS
import java.io.File

private val deliveryScheduleGson = GsonBuilder().create()

/**
 * Parses the delivery-schedule JSON shape (`{schemaVersion, slots: {...}}`) shared by the
 * on-disk master file and the `/api/admin-sync/delivery-schedule` endpoint's response body — both
 * use the identical shape, so this is reused by DeliveryScheduleRepository (file read) and
 * AdminSyncClient (HTTP response) rather than duplicated.
 */
internal fun parseDeliverySchedule(json: String): DeliverySchedule {
    val root = deliveryScheduleGson.fromJson(json, JsonObject::class.java) ?: return DeliverySchedule()
    val slotsObj = root.getAsJsonObject("slots") ?: return DeliverySchedule()
    val slots = mutableMapOf<String, DeliverySlot>()

    for (day in DELIVERY_DAYS) {
        for (period in DELIVERY_PERIODS) {
            val key = "${day}_${period}"
            val slotObj = slotsObj.getAsJsonObject(key)
            val jobs = mutableListOf<DeliveryJob>()
            if (slotObj != null) {
                val jobsArr = slotObj.getAsJsonArray("jobs")
                jobsArr?.forEach { elem ->
                    val obj = elem.asJsonObject
                    jobs.add(
                        DeliveryJob(
                            jobNumber = obj.get("jobNumber")?.takeIf { !it.isJsonNull }?.asString ?: "",
                            description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString ?: "",
                            address = obj.get("address")?.takeIf { !it.isJsonNull }?.asString ?: "",
                            folderName = obj.get("folderName")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        )
                    )
                }
            }
            slots[key] = DeliverySlot(jobs = jobs)
        }
    }
    return DeliverySchedule(slots = slots)
}

/**
 * Reads the delivery schedule from the shared network drive.
 * Storage path: {baseDir}/.metadata/delivery_schedule.json
 * Written by kkc-admin; read-only on the tablet.
 * Call on Dispatchers.IO.
 */
class DeliveryScheduleRepository(private val baseDir: File) {

    fun fetchSchedule(): DeliverySchedule {
        val file = File(baseDir, ".metadata/delivery_schedule.json")
        if (!file.exists() || !file.isFile) return DeliverySchedule()
        return runCatching { parseDeliverySchedule(file.readText()) }.getOrElse { DeliverySchedule() }
    }
}
```

- [ ] **Step 2: Run existing repository tests**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.DeliveryScheduleRepositoryTest"`
Expected: all existing tests still PASS (parsing behavior is unchanged, only its location moved).

- [ ] **Step 3: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/DeliveryScheduleRepository.kt
git commit -m "refactor: extract parseDeliverySchedule for reuse by AdminSyncClient"
```

---

### Task 7: AdminSyncClient

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/AdminSyncClient.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/data/AdminSyncClientTest.kt`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add the MockWebServer test dependency**

In `app/build.gradle.kts`, after line 115 (`testImplementation("org.mockito:mockito-core:5.3.1")`), add:

```kotlin
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

- [ ] **Step 2: Create `AdminSyncClient.kt`**

```kotlin
package com.kkc.sheettracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Direct-write fast path to the Hours Tracker backend for admin-mode job order, job board, and
 * delivery schedule edits. Every method returns null/false on ANY failure (timeout, connection
 * refused, non-2xx) so the caller can fall back to the existing per-tablet request-file stores
 * (ProductionOrderRequestStore / JobBoardRequestStore / DeliveryScheduleRequestStore) — no retry
 * loop here, since a stalled UI gesture is worse than an immediate fallback.
 */
class AdminSyncClient(private val serverUrl: String) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /** Returns the canonical order on success, null on any failure (caller should fall back). */
    suspend fun applyProductionOrder(order: List<String>, tabletId: String): List<String>? =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("order", JSONArray(order))
                put("tabletId", tabletId)
            }.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$serverUrl/api/admin-sync/production-order")
                .post(body)
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val obj = JSONObject(response.body?.string() ?: return@use null)
                    val arr = obj.getJSONArray("order")
                    (0 until arr.length()).map { arr.getString(it) }
                }
            }.getOrNull()
        }

    /** Returns true on success, false on any failure (caller should fall back). */
    suspend fun applyJobBoardEdits(edits: List<JobBoardEdit>, tabletId: String): Boolean =
        withContext(Dispatchers.IO) {
            val editsArray = JSONArray()
            edits.forEach { edit ->
                editsArray.put(JSONObject().apply {
                    put("folderName", edit.folderName)
                    edit.labelIds?.let { put("labelIds", JSONArray(it)) }
                    edit.boardSection?.let { put("boardSection", it) }
                })
            }
            val body = JSONObject().apply {
                put("edits", editsArray)
                put("tabletId", tabletId)
            }.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$serverUrl/api/admin-sync/job-board-edits")
                .post(body)
                .build()
            runCatching {
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /** Returns the canonical schedule on success, null on any failure (caller should fall back). */
    suspend fun applyDeliverySchedule(editRequest: DeliveryScheduleEditRequest): com.kkc.sheettracker.data.models.DeliverySchedule? =
        withContext(Dispatchers.IO) {
            val slotEditsArray = JSONArray()
            editRequest.slotEdits.forEach { edit ->
                val jobsArray = JSONArray()
                edit.jobs.forEach { job ->
                    jobsArray.put(JSONObject().apply {
                        put("jobNumber", job.jobNumber)
                        put("description", job.description)
                        if (job.address.isNotBlank()) put("address", job.address)
                        if (job.folderName.isNotBlank()) put("folderName", job.folderName)
                    })
                }
                slotEditsArray.put(JSONObject().apply {
                    put("slot", edit.slot)
                    put("jobs", jobsArray)
                })
            }
            val body = JSONObject().apply {
                put("tabletId", editRequest.tabletId)
                put("resetAll", editRequest.resetAll)
                put("slotEdits", slotEditsArray)
            }.toString().toRequestBody(jsonMediaType)
            val httpRequest = Request.Builder()
                .url("$serverUrl/api/admin-sync/delivery-schedule")
                .post(body)
                .build()
            runCatching {
                client.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    parseDeliverySchedule(response.body?.string() ?: return@use null)
                }
            }.getOrNull()
        }
}
```

- [ ] **Step 3: Write the test**

Create `app/src/test/java/com/kkc/sheettracker/data/AdminSyncClientTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdminSyncClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AdminSyncClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AdminSyncClient(server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `applyProductionOrder returns canonical order on success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"order":["Job-A","Job-B"]}"""))

        val result = client.applyProductionOrder(listOf("Job-A", "Job-B"), "tablet-1")

        assertEquals(listOf("Job-A", "Job-B"), result)
        val recorded = server.takeRequest()
        assertEquals("/api/admin-sync/production-order", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"tabletId\":\"tablet-1\""))
    }

    @Test
    fun `applyProductionOrder returns null on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = client.applyProductionOrder(listOf("Job-A"), "tablet-1")

        assertNull(result)
    }

    @Test
    fun `applyJobBoardEdits returns true on success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"appliedCount":1}"""))

        val result = client.applyJobBoardEdits(
            listOf(JobBoardEdit(folderName = "Job-A", labelIds = listOf(1, 2))),
            "tablet-1"
        )

        assertTrue(result)
    }

    @Test
    fun `applyJobBoardEdits returns false on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = client.applyJobBoardEdits(
            listOf(JobBoardEdit(folderName = "Job-A", boardSection = 1)),
            "tablet-1"
        )

        assertFalse(result)
    }

    @Test
    fun `applyDeliverySchedule returns parsed schedule on success`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"schemaVersion":1,"slots":{"monday_am":{"jobs":[{"jobNumber":"123","description":"Test Job"}]}}}"""
            )
        )

        val result = client.applyDeliverySchedule(
            DeliveryScheduleEditRequest(
                tabletId = "tablet-1",
                requestedAt = "2026-07-21T00:00:00Z",
                slotEdits = listOf(
                    DeliveryScheduleSlotEdit(
                        slot = "monday_am",
                        jobs = listOf(com.kkc.sheettracker.data.models.DeliveryJob(jobNumber = "123", description = "Test Job"))
                    )
                )
            )
        )

        assertEquals("123", result?.slot("monday", "am")?.jobs?.single()?.jobNumber)
    }

    @Test
    fun `applyDeliverySchedule returns null when server is unreachable`() = runBlocking {
        val unreachableUrl = server.url("/").toString().trimEnd('/')
        server.shutdown()

        val result = AdminSyncClient(unreachableUrl).applyDeliverySchedule(
            DeliveryScheduleEditRequest(tabletId = "tablet-1", requestedAt = "2026-07-21T00:00:00Z", resetAll = true)
        )

        assertNull(result)
    }
}
```

- [ ] **Step 4: Run the test**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.AdminSyncClientTest"`
Expected: all 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/build.gradle.kts \
        app/src/main/java/com/kkc/sheettracker/data/AdminSyncClient.kt \
        app/src/test/java/com/kkc/sheettracker/data/AdminSyncClientTest.kt
git commit -m "feat: add AdminSyncClient for the admin-sync direct-write fast path"
```

---

### Task 8: Wire production order (JobBrowserScreen.kt only)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/browser/JobBrowserScreen.kt`

- [ ] **Step 1: Construct AdminSyncClient**

After the existing store construction block (`JobBrowserScreen.kt:332-334`):
```kotlin
    val requestStore = remember(basePath) { ProductionOrderRequestStore(File(basePath)) }
    val jobBoardRequestStore = remember(basePath) { JobBoardRequestStore(File(basePath)) }
    val deliveryScheduleRequestStore = remember(basePath) { DeliveryScheduleRequestStore(File(basePath)) }
```
add:
```kotlin
    val adminSyncConfig = remember { AdminSyncConfig.create(context) }
    val adminSyncServerUrl by produceState<String?>(initialValue = null, adminSyncConfig) {
        value = adminSyncConfig.getServerUrl()
    }
    val adminSyncClient = remember(adminSyncServerUrl) { adminSyncServerUrl?.let { AdminSyncClient(it) } }
```

(`context` is already declared at `JobBrowserScreen.kt:150` via `LocalContext.current`.)

- [ ] **Step 2: Update `saveActiveOrder` to try the fast path first**

Replace the `saveActiveOrder` block (`JobBrowserScreen.kt:351-361`):
```kotlin
    val saveActiveOrder = {
        val newOrder = mergeActiveReorder(
            original = filteredJobs,
            reorderedActiveFolderNames = activeOrder,
            boardSectionOf = { it.boardSection },
            folderNameOf = { it.folderName }
        )
        saveScope.launch {
            withContext(Dispatchers.IO) { requestStore.writeRequest(newOrder, tabletId) }
        }
    }
```
with:
```kotlin
    val saveActiveOrder = {
        val newOrder = mergeActiveReorder(
            original = filteredJobs,
            reorderedActiveFolderNames = activeOrder,
            boardSectionOf = { it.boardSection },
            folderNameOf = { it.folderName }
        )
        saveScope.launch {
            val applied = adminSyncClient?.applyProductionOrder(newOrder, tabletId)
            if (applied == null) {
                withContext(Dispatchers.IO) { requestStore.writeRequest(newOrder, tabletId) }
            }
        }
    }
```

- [ ] **Step 3: Build to confirm no compile errors**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/ui/browser/JobBrowserScreen.kt
git commit -m "feat: try admin-sync fast path before file-drop for job order reorder"
```

---

### Task 9: Wire job-board edits across all 4 screens

The same duplicated block exists in `JobBrowserScreen.kt`, `AssemblyJobsScreen.kt`,
`HardwoodsJobsScreen.kt`, and `SpecialtyJobsScreen.kt`. Each gets the identical two changes:
construct `AdminSyncClient` (already done for `JobBrowserScreen.kt` in Task 8 — skip that file's
construction step here), then try it before the file-drop write in `onToggleLabel` /
`onSetPendingDelivery`.

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/browser/JobBrowserScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyJobsScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobsScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobsScreen.kt`

- [ ] **Step 1: JobBrowserScreen.kt — update the label/section editor callbacks**

Replace (`JobBrowserScreen.kt:638-660`):
```kotlin
                    onToggleLabel = { label ->
                        val newIds = if (label.id in labelEditJob.labels.map { it.id }) {
                            labelEditJob.labels.filterNot { it.id == label.id }.map { it.id }
                        } else {
                            labelEditJob.labels.map { it.id } + label.id
                        }
                        saveScope.launch {
                            withContext(Dispatchers.IO) {
                                jobBoardRequestStore.queueLabelEdit(labelEditJob.folderName, newIds, tabletId)
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(labels = allLabels.filter { it.id in newIds })
                    },
                    onSetPendingDelivery = { pending ->
                        val newSection = if (pending) 1 else 0
                        saveScope.launch {
                            withContext(Dispatchers.IO) {
                                jobBoardRequestStore.queueBoardSectionEdit(labelEditJob.folderName, newSection, tabletId)
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(boardSection = newSection)
                    },
```
with:
```kotlin
                    onToggleLabel = { label ->
                        val newIds = if (label.id in labelEditJob.labels.map { it.id }) {
                            labelEditJob.labels.filterNot { it.id == label.id }.map { it.id }
                        } else {
                            labelEditJob.labels.map { it.id } + label.id
                        }
                        saveScope.launch {
                            val applied = adminSyncClient?.applyJobBoardEdits(
                                listOf(JobBoardEdit(folderName = labelEditJob.folderName, labelIds = newIds)),
                                tabletId
                            ) ?: false
                            if (!applied) {
                                withContext(Dispatchers.IO) {
                                    jobBoardRequestStore.queueLabelEdit(labelEditJob.folderName, newIds, tabletId)
                                }
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(labels = allLabels.filter { it.id in newIds })
                    },
                    onSetPendingDelivery = { pending ->
                        val newSection = if (pending) 1 else 0
                        saveScope.launch {
                            val applied = adminSyncClient?.applyJobBoardEdits(
                                listOf(JobBoardEdit(folderName = labelEditJob.folderName, boardSection = newSection)),
                                tabletId
                            ) ?: false
                            if (!applied) {
                                withContext(Dispatchers.IO) {
                                    jobBoardRequestStore.queueBoardSectionEdit(labelEditJob.folderName, newSection, tabletId)
                                }
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(boardSection = newSection)
                    },
```

- [ ] **Step 2: AssemblyJobsScreen.kt — construct AdminSyncClient**

After the existing store construction (`AssemblyJobsScreen.kt:297-298`):
```kotlin
    val jobBoardRequestStore = remember(basePath) { JobBoardRequestStore(File(basePath)) }
    val deliveryScheduleRequestStore = remember(basePath) { DeliveryScheduleRequestStore(File(basePath)) }
```
add:
```kotlin
    val adminSyncConfig = remember { AdminSyncConfig.create(context) }
    val adminSyncServerUrl by produceState<String?>(initialValue = null, adminSyncConfig) {
        value = adminSyncConfig.getServerUrl()
    }
    val adminSyncClient = remember(adminSyncServerUrl) { adminSyncServerUrl?.let { AdminSyncClient(it) } }
```

(`context` is already declared at `AssemblyJobsScreen.kt:140` via `LocalContext.current`.)

- [ ] **Step 3: AssemblyJobsScreen.kt — update the label/section editor callbacks**

Replace (`AssemblyJobsScreen.kt:846-868`):
```kotlin
                    onToggleLabel = { label ->
                        val newIds = if (label.id in labelEditJob.labels.map { it.id }) {
                            labelEditJob.labels.filterNot { it.id == label.id }.map { it.id }
                        } else {
                            labelEditJob.labels.map { it.id } + label.id
                        }
                        saveScope.launch {
                            withContext(Dispatchers.IO) {
                                jobBoardRequestStore.queueLabelEdit(labelEditJob.folderName, newIds, tabletId)
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(labels = allLabels.filter { it.id in newIds })
                    },
                    onSetPendingDelivery = { pending ->
                        val newSection = if (pending) 1 else 0
                        saveScope.launch {
                            withContext(Dispatchers.IO) {
                                jobBoardRequestStore.queueBoardSectionEdit(labelEditJob.folderName, newSection, tabletId)
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(boardSection = newSection)
                    },
```
with:
```kotlin
                    onToggleLabel = { label ->
                        val newIds = if (label.id in labelEditJob.labels.map { it.id }) {
                            labelEditJob.labels.filterNot { it.id == label.id }.map { it.id }
                        } else {
                            labelEditJob.labels.map { it.id } + label.id
                        }
                        saveScope.launch {
                            val applied = adminSyncClient?.applyJobBoardEdits(
                                listOf(JobBoardEdit(folderName = labelEditJob.folderName, labelIds = newIds)),
                                tabletId
                            ) ?: false
                            if (!applied) {
                                withContext(Dispatchers.IO) {
                                    jobBoardRequestStore.queueLabelEdit(labelEditJob.folderName, newIds, tabletId)
                                }
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(labels = allLabels.filter { it.id in newIds })
                    },
                    onSetPendingDelivery = { pending ->
                        val newSection = if (pending) 1 else 0
                        saveScope.launch {
                            val applied = adminSyncClient?.applyJobBoardEdits(
                                listOf(JobBoardEdit(folderName = labelEditJob.folderName, boardSection = newSection)),
                                tabletId
                            ) ?: false
                            if (!applied) {
                                withContext(Dispatchers.IO) {
                                    jobBoardRequestStore.queueBoardSectionEdit(labelEditJob.folderName, newSection, tabletId)
                                }
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(boardSection = newSection)
                    },
```

- [ ] **Step 4: HardwoodsJobsScreen.kt — construct AdminSyncClient**

After the existing store construction (`HardwoodsJobsScreen.kt:360-361`):
```kotlin
    val jobBoardRequestStore = remember(basePath) { JobBoardRequestStore(File(basePath)) }
    val deliveryScheduleRequestStore = remember(basePath) { DeliveryScheduleRequestStore(File(basePath)) }
```
add:
```kotlin
    val adminSyncConfig = remember { AdminSyncConfig.create(context) }
    val adminSyncServerUrl by produceState<String?>(initialValue = null, adminSyncConfig) {
        value = adminSyncConfig.getServerUrl()
    }
    val adminSyncClient = remember(adminSyncServerUrl) { adminSyncServerUrl?.let { AdminSyncClient(it) } }
```

(`context` is already declared at `HardwoodsJobsScreen.kt:141` via `LocalContext.current`.)

- [ ] **Step 5: HardwoodsJobsScreen.kt — update the label/section editor callbacks**

Replace (`HardwoodsJobsScreen.kt:926-948`):
```kotlin
                    onToggleLabel = { label ->
                        val newIds = if (label.id in labelEditJob.labels.map { it.id }) {
                            labelEditJob.labels.filterNot { it.id == label.id }.map { it.id }
                        } else {
                            labelEditJob.labels.map { it.id } + label.id
                        }
                        saveScope.launch {
                            withContext(Dispatchers.IO) {
                                jobBoardRequestStore.queueLabelEdit(labelEditJob.folderName, newIds, tabletId)
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(labels = allLabels.filter { it.id in newIds })
                    },
                    onSetPendingDelivery = { pending ->
                        val newSection = if (pending) 1 else 0
                        saveScope.launch {
                            withContext(Dispatchers.IO) {
                                jobBoardRequestStore.queueBoardSectionEdit(labelEditJob.folderName, newSection, tabletId)
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(boardSection = newSection)
                    },
```
with:
```kotlin
                    onToggleLabel = { label ->
                        val newIds = if (label.id in labelEditJob.labels.map { it.id }) {
                            labelEditJob.labels.filterNot { it.id == label.id }.map { it.id }
                        } else {
                            labelEditJob.labels.map { it.id } + label.id
                        }
                        saveScope.launch {
                            val applied = adminSyncClient?.applyJobBoardEdits(
                                listOf(JobBoardEdit(folderName = labelEditJob.folderName, labelIds = newIds)),
                                tabletId
                            ) ?: false
                            if (!applied) {
                                withContext(Dispatchers.IO) {
                                    jobBoardRequestStore.queueLabelEdit(labelEditJob.folderName, newIds, tabletId)
                                }
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(labels = allLabels.filter { it.id in newIds })
                    },
                    onSetPendingDelivery = { pending ->
                        val newSection = if (pending) 1 else 0
                        saveScope.launch {
                            val applied = adminSyncClient?.applyJobBoardEdits(
                                listOf(JobBoardEdit(folderName = labelEditJob.folderName, boardSection = newSection)),
                                tabletId
                            ) ?: false
                            if (!applied) {
                                withContext(Dispatchers.IO) {
                                    jobBoardRequestStore.queueBoardSectionEdit(labelEditJob.folderName, newSection, tabletId)
                                }
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(boardSection = newSection)
                    },
```

- [ ] **Step 6: SpecialtyJobsScreen.kt — construct AdminSyncClient**

After the existing store construction (`SpecialtyJobsScreen.kt:238-239`):
```kotlin
    val jobBoardRequestStore = remember(basePath) { JobBoardRequestStore(File(basePath)) }
    val deliveryScheduleRequestStore = remember(basePath) { DeliveryScheduleRequestStore(File(basePath)) }
```
add:
```kotlin
    val adminSyncConfig = remember { AdminSyncConfig.create(context) }
    val adminSyncServerUrl by produceState<String?>(initialValue = null, adminSyncConfig) {
        value = adminSyncConfig.getServerUrl()
    }
    val adminSyncClient = remember(adminSyncServerUrl) { adminSyncServerUrl?.let { AdminSyncClient(it) } }
```

(`context` is already declared at `SpecialtyJobsScreen.kt:128` via `LocalContext.current`.)

- [ ] **Step 7: SpecialtyJobsScreen.kt — update the label/section editor callbacks**

Replace (`SpecialtyJobsScreen.kt:746-768`):
```kotlin
                    onToggleLabel = { label ->
                        val newIds = if (label.id in labelEditJob.labels.map { it.id }) {
                            labelEditJob.labels.filterNot { it.id == label.id }.map { it.id }
                        } else {
                            labelEditJob.labels.map { it.id } + label.id
                        }
                        saveScope.launch {
                            withContext(Dispatchers.IO) {
                                jobBoardRequestStore.queueLabelEdit(labelEditJob.folderName, newIds, tabletId)
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(labels = allLabels.filter { it.id in newIds })
                    },
                    onSetPendingDelivery = { pending ->
                        val newSection = if (pending) 1 else 0
                        saveScope.launch {
                            withContext(Dispatchers.IO) {
                                jobBoardRequestStore.queueBoardSectionEdit(labelEditJob.folderName, newSection, tabletId)
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(boardSection = newSection)
                    },
```
with:
```kotlin
                    onToggleLabel = { label ->
                        val newIds = if (label.id in labelEditJob.labels.map { it.id }) {
                            labelEditJob.labels.filterNot { it.id == label.id }.map { it.id }
                        } else {
                            labelEditJob.labels.map { it.id } + label.id
                        }
                        saveScope.launch {
                            val applied = adminSyncClient?.applyJobBoardEdits(
                                listOf(JobBoardEdit(folderName = labelEditJob.folderName, labelIds = newIds)),
                                tabletId
                            ) ?: false
                            if (!applied) {
                                withContext(Dispatchers.IO) {
                                    jobBoardRequestStore.queueLabelEdit(labelEditJob.folderName, newIds, tabletId)
                                }
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(labels = allLabels.filter { it.id in newIds })
                    },
                    onSetPendingDelivery = { pending ->
                        val newSection = if (pending) 1 else 0
                        saveScope.launch {
                            val applied = adminSyncClient?.applyJobBoardEdits(
                                listOf(JobBoardEdit(folderName = labelEditJob.folderName, boardSection = newSection)),
                                tabletId
                            ) ?: false
                            if (!applied) {
                                withContext(Dispatchers.IO) {
                                    jobBoardRequestStore.queueBoardSectionEdit(labelEditJob.folderName, newSection, tabletId)
                                }
                            }
                        }
                        editingLabelsFor = labelEditJob.copy(boardSection = newSection)
                    },
```

- [ ] **Step 8: Build to confirm no compile errors**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/ui/browser/JobBrowserScreen.kt \
        app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyJobsScreen.kt \
        app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobsScreen.kt \
        app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobsScreen.kt
git commit -m "feat: try admin-sync fast path before file-drop for job board label/section edits"
```

---

### Task 10: Wire delivery-schedule edits across all 4 screens

Same duplicated block, same 4 files. Each gets: `deliverySchedule` changed from a `val` reseeded
only by scan generation to a `var` that can ALSO be set directly from a successful sync-endpoint
response, plus the `onQueueSlotEdit`/`onQueueReset` callbacks trying the fast path first.

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/browser/JobBrowserScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyJobsScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobsScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobsScreen.kt`

- [ ] **Step 1: JobBrowserScreen.kt — make `deliverySchedule` mutable**

Replace (`JobBrowserScreen.kt:213-215`):
```kotlin
    val deliverySchedule = remember(scanState.snapshot.generation) {
        deliveryScheduleRepository.fetchSchedule()
    }
```
with:
```kotlin
    var deliverySchedule by remember(scanState.snapshot.generation) {
        mutableStateOf(deliveryScheduleRepository.fetchSchedule())
    }
```

- [ ] **Step 2: JobBrowserScreen.kt — update the dialog callbacks**

Replace (`JobBrowserScreen.kt:604-617`):
```kotlin
            onQueueSlotEdit = { slot, jobs ->
                saveScope.launch {
                    withContext(Dispatchers.IO) {
                        deliveryScheduleRequestStore.queueSlotEdit(slot, jobs, tabletId)
                    }
                }
            },
            onQueueReset = {
                saveScope.launch {
                    withContext(Dispatchers.IO) {
                        deliveryScheduleRequestStore.queueReset(tabletId)
                    }
                }
            }
```
with:
```kotlin
            onQueueSlotEdit = { slot, jobs ->
                saveScope.launch {
                    val applied = adminSyncClient?.applyDeliverySchedule(
                        DeliveryScheduleEditRequest(
                            tabletId = tabletId,
                            requestedAt = java.time.Instant.now().toString(),
                            slotEdits = listOf(
                                DeliveryScheduleSlotEdit(slot = slot.trim().lowercase(), jobs = jobs.take(3))
                            )
                        )
                    )
                    if (applied != null) {
                        deliverySchedule = applied
                    } else {
                        withContext(Dispatchers.IO) {
                            deliveryScheduleRequestStore.queueSlotEdit(slot, jobs, tabletId)
                        }
                    }
                }
            },
            onQueueReset = {
                saveScope.launch {
                    val applied = adminSyncClient?.applyDeliverySchedule(
                        DeliveryScheduleEditRequest(
                            tabletId = tabletId,
                            requestedAt = java.time.Instant.now().toString(),
                            resetAll = true
                        )
                    )
                    if (applied != null) {
                        deliverySchedule = applied
                    } else {
                        withContext(Dispatchers.IO) {
                            deliveryScheduleRequestStore.queueReset(tabletId)
                        }
                    }
                }
            }
```

- [ ] **Step 3: AssemblyJobsScreen.kt — make `deliverySchedule` mutable**

Replace (`AssemblyJobsScreen.kt:208-210`):
```kotlin
    val deliverySchedule = remember(scanState.snapshot.generation) {
        deliveryScheduleRepository.fetchSchedule()
    }
```
with:
```kotlin
    var deliverySchedule by remember(scanState.snapshot.generation) {
        mutableStateOf(deliveryScheduleRepository.fetchSchedule())
    }
```

- [ ] **Step 4: AssemblyJobsScreen.kt — update the dialog callbacks**

Replace (`AssemblyJobsScreen.kt:816-829`):
```kotlin
            onQueueSlotEdit = { slot, jobs ->
                saveScope.launch {
                    withContext(Dispatchers.IO) {
                        deliveryScheduleRequestStore.queueSlotEdit(slot, jobs, tabletId)
                    }
                }
            },
            onQueueReset = {
                saveScope.launch {
                    withContext(Dispatchers.IO) {
                        deliveryScheduleRequestStore.queueReset(tabletId)
                    }
                }
            }
```
with:
```kotlin
            onQueueSlotEdit = { slot, jobs ->
                saveScope.launch {
                    val applied = adminSyncClient?.applyDeliverySchedule(
                        DeliveryScheduleEditRequest(
                            tabletId = tabletId,
                            requestedAt = java.time.Instant.now().toString(),
                            slotEdits = listOf(
                                DeliveryScheduleSlotEdit(slot = slot.trim().lowercase(), jobs = jobs.take(3))
                            )
                        )
                    )
                    if (applied != null) {
                        deliverySchedule = applied
                    } else {
                        withContext(Dispatchers.IO) {
                            deliveryScheduleRequestStore.queueSlotEdit(slot, jobs, tabletId)
                        }
                    }
                }
            },
            onQueueReset = {
                saveScope.launch {
                    val applied = adminSyncClient?.applyDeliverySchedule(
                        DeliveryScheduleEditRequest(
                            tabletId = tabletId,
                            requestedAt = java.time.Instant.now().toString(),
                            resetAll = true
                        )
                    )
                    if (applied != null) {
                        deliverySchedule = applied
                    } else {
                        withContext(Dispatchers.IO) {
                            deliveryScheduleRequestStore.queueReset(tabletId)
                        }
                    }
                }
            }
```

- [ ] **Step 5: HardwoodsJobsScreen.kt — make `deliverySchedule` mutable**

Replace (`HardwoodsJobsScreen.kt:210-212`):
```kotlin
    val deliverySchedule = remember(scanState.snapshot.generation) {
        deliveryScheduleRepository.fetchSchedule()
    }
```
with:
```kotlin
    var deliverySchedule by remember(scanState.snapshot.generation) {
        mutableStateOf(deliveryScheduleRepository.fetchSchedule())
    }
```

- [ ] **Step 6: HardwoodsJobsScreen.kt — update the dialog callbacks**

Replace (`HardwoodsJobsScreen.kt:896-909`):
```kotlin
            onQueueSlotEdit = { slot, jobs ->
                saveScope.launch {
                    withContext(Dispatchers.IO) {
                        deliveryScheduleRequestStore.queueSlotEdit(slot, jobs, tabletId)
                    }
                }
            },
            onQueueReset = {
                saveScope.launch {
                    withContext(Dispatchers.IO) {
                        deliveryScheduleRequestStore.queueReset(tabletId)
                    }
                }
            }
```
with:
```kotlin
            onQueueSlotEdit = { slot, jobs ->
                saveScope.launch {
                    val applied = adminSyncClient?.applyDeliverySchedule(
                        DeliveryScheduleEditRequest(
                            tabletId = tabletId,
                            requestedAt = java.time.Instant.now().toString(),
                            slotEdits = listOf(
                                DeliveryScheduleSlotEdit(slot = slot.trim().lowercase(), jobs = jobs.take(3))
                            )
                        )
                    )
                    if (applied != null) {
                        deliverySchedule = applied
                    } else {
                        withContext(Dispatchers.IO) {
                            deliveryScheduleRequestStore.queueSlotEdit(slot, jobs, tabletId)
                        }
                    }
                }
            },
            onQueueReset = {
                saveScope.launch {
                    val applied = adminSyncClient?.applyDeliverySchedule(
                        DeliveryScheduleEditRequest(
                            tabletId = tabletId,
                            requestedAt = java.time.Instant.now().toString(),
                            resetAll = true
                        )
                    )
                    if (applied != null) {
                        deliverySchedule = applied
                    } else {
                        withContext(Dispatchers.IO) {
                            deliveryScheduleRequestStore.queueReset(tabletId)
                        }
                    }
                }
            }
```

- [ ] **Step 7: SpecialtyJobsScreen.kt — make `deliverySchedule` mutable**

Replace (`SpecialtyJobsScreen.kt:193-195`):
```kotlin
    val deliverySchedule = remember(scanState.snapshot.generation) {
        deliveryScheduleRepository.fetchSchedule()
    }
```
with:
```kotlin
    var deliverySchedule by remember(scanState.snapshot.generation) {
        mutableStateOf(deliveryScheduleRepository.fetchSchedule())
    }
```

- [ ] **Step 8: SpecialtyJobsScreen.kt — update the dialog callbacks**

Replace (`SpecialtyJobsScreen.kt:716-729`):
```kotlin
            onQueueSlotEdit = { slot, jobs ->
                saveScope.launch {
                    withContext(Dispatchers.IO) {
                        deliveryScheduleRequestStore.queueSlotEdit(slot, jobs, tabletId)
                    }
                }
            },
            onQueueReset = {
                saveScope.launch {
                    withContext(Dispatchers.IO) {
                        deliveryScheduleRequestStore.queueReset(tabletId)
                    }
                }
            }
```
with:
```kotlin
            onQueueSlotEdit = { slot, jobs ->
                saveScope.launch {
                    val applied = adminSyncClient?.applyDeliverySchedule(
                        DeliveryScheduleEditRequest(
                            tabletId = tabletId,
                            requestedAt = java.time.Instant.now().toString(),
                            slotEdits = listOf(
                                DeliveryScheduleSlotEdit(slot = slot.trim().lowercase(), jobs = jobs.take(3))
                            )
                        )
                    )
                    if (applied != null) {
                        deliverySchedule = applied
                    } else {
                        withContext(Dispatchers.IO) {
                            deliveryScheduleRequestStore.queueSlotEdit(slot, jobs, tabletId)
                        }
                    }
                }
            },
            onQueueReset = {
                saveScope.launch {
                    val applied = adminSyncClient?.applyDeliverySchedule(
                        DeliveryScheduleEditRequest(
                            tabletId = tabletId,
                            requestedAt = java.time.Instant.now().toString(),
                            resetAll = true
                        )
                    )
                    if (applied != null) {
                        deliverySchedule = applied
                    } else {
                        withContext(Dispatchers.IO) {
                            deliveryScheduleRequestStore.queueReset(tabletId)
                        }
                    }
                }
            }
```

- [ ] **Step 9: Build to confirm no compile errors**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Run the full unit test suite**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (except the pre-existing, unrelated PdfMarkup
MotionEvent test failure that only reproduces off-device — see project memory).

- [ ] **Step 11: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/ui/browser/JobBrowserScreen.kt \
        app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyJobsScreen.kt \
        app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobsScreen.kt \
        app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobsScreen.kt
git commit -m "feat: try admin-sync fast path before file-drop for delivery schedule edits"
```

---

## Manual On-Device Verification (after all tasks above)

- [ ] Deploy to an admin tablet (`.\adb-install-release.ps1` per this project's build convention).
- [ ] Leave the "Hours Tracker server IP" Settings field blank. Reorder jobs, edit a label, edit
      the delivery schedule. Confirm everything still works exactly as before (fallback path),
      just still on the old ~120s→15s cadence.
- [ ] Set the Hours Tracker server IP in Settings. Reorder jobs in admin mode — confirm the new
      order does NOT revert on the next rescan. Edit the delivery schedule — confirm the dialog
      reflects the change immediately, not after a delay.
- [ ] Stop the Hours Tracker backend container. Reorder jobs again — confirm it still queues (no
      crash, no hang) and is applied once the backend is back up (within ~15s of it returning).
