# Supply Live WebSocket Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve the supply read model to tablets over a read-only Hours Tracker WebSocket so the tablet stops re-reading every `.supply` file on every refresh, with today's file reads as the fallback.

**Architecture:** Hours Tracker polls the shared `.supply` tree every 2 s, builds a complete resolved document read-only, and pushes full-document replacements over `/api/supply/live` (same shape as the delivery-schedule live feed). The tablet holds the latest document in a process-wide `SupplyLiveStateStore`, overlays its own recent file writes until the server echoes them, and `SupplyRepository` reads from that store while live and from files otherwise.

**Tech Stack:** Python 3.13 / FastAPI / pytest (Hours Tracker backend); Kotlin / Jetpack Compose / OkHttp WebSocket / Gson / JUnit4 + mockito-kotlin (KKCSheetTracker).

**Spec:** `docs/superpowers/specs/2026-09-28-supply-live-websocket-design.md` (this repo). Read it before starting any task.

## Global Constraints

- Two repos. Tasks 1–5: `C:\Scripts\Hours Tracker` (branch off `master`). Tasks 6–11: `C:\Scripts\KKCSheetTracker` (branch off `main`). Task 12: docs in both plus the metadata-map skill.
- Use a feature branch `feat/supply-live` in each repo. The KKCSheetTracker main checkout has unrelated uncommitted edits (`StatusBorderedCard.kt`, `SpecialtyKanbanBoard.kt`): never stage, commit, or revert them. Stage files by explicit path only.
- Edit code with the Edit/Write tools, not sed/python scripts.
- No write-ownership change: tablet writes to `.supply` stay file + Syncthing exactly as today.
- The server document builder must never write under the shared root: no `ensure_dirs()`, no `json_file_lock` sidecars, no `.corrupt` backups.
- Endpoint `/api/supply/live`. Frames: client `{"type":"hello","tabletId":...}`; server `{"type":"snapshot","revision":N,"supply":{...}}`, `{"type":"supply","revision":N,"supply":{...}}`, `{"type":"not_running"}`, `{"type":"error","message":...}`.
- Server poll interval `2.0` s. Tablet reconnect backoff `(1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)`, ping `15` s, overlay TTL `120_000` ms, subscription rescan debounce `250` ms.
- Timestamp ordering everywhere: parsed instant, then raw string (AUD-10). Unparseable sorts to the minimum.
- Exclude every name containing `.sync-conflict-`.
- Hours Tracker tests run from `C:\Scripts\Hours Tracker\backend` with the backend venv: `.\venv\Scripts\python.exe -m pytest <tests> -q`.
- Tablet tests: `.\gradlew.bat :app:testDebugUnitTest --tests "<FQCN>"` from `C:\Scripts\KKCSheetTracker`. One pre-existing PdfMarkup MotionEvent unit test fails off-device; it is environmental, not a regression.
- Tablets run release builds. Deploy with `.\gradlew.bat assembleRelease` then `adb install -r app\build\outputs\apk\release\app-release.apk` (not `adb-install-release.ps1`, whose Unicode breaks under `powershell -File`).
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. The server's `.supply` mount vanishes after a good publish (Syncthing/volume hiccup) — tablets must keep the last catalog, never receive an empty one. Test: Task 3 `test_vanished_tree_after_good_publish_retains_last_document`.
2. Hours Tracker is down for hours — tablets must not re-run the full file scan on every reconnect attempt. Test: Task 7 `second disconnect does not bump version`.
3. A tablet adds then deletes a comment (or creates then deletes an item) before the server sees either — it must not reappear. Tests: Task 7 `local comment add then delete is not resurrected`, `local item create then delete is not resurrected`.
4. Whole-second vs fractional-second timestamps (`…:00Z` vs `…:00.5Z`) — status and comment order must be chronological on both sides. Tests: Task 2 `test_latest_status_wins_by_parsed_instant`, `test_comments_sorted_by_parsed_instant_and_only_for_known_items`; Task 6 `comments are re-sorted by parsed instant`; Task 7 `fractional live statusAt satisfies whole-second local status`.
5. Item JSON from older writers with missing/null `fields`, `barcodes`, status keys — no server crash, no tablet NPE. Tests: Task 2 `test_null_fields_and_barcodes_do_not_break_the_build`; Task 6 `missing and null fields are normalized`.

---

## File Structure

Hours Tracker (`C:\Scripts\Hours Tracker\backend`):
- Create `ready_jobs_worker_core/adapters/supply_live_service.py` — in-memory revisioned document + wait/notify. No I/O.
- Create `routes/supply_live_document.py` — read-only document builder from the `.supply` tree.
- Create `ready_jobs_worker_core/adapters/supply_live_monitor.py` — tree signature + poll/settle/publish loop. Builder injected.
- Create `routes/supply_live.py` — WebSocket route + service registry.
- Modify `main_v2.py` — imports, router, globals, `_start_supply_live_once()`, startup call, shutdown block.
- Tests: `tests/test_supply_live_service.py`, `tests/test_supply_live_document.py`, `tests/test_supply_live_monitor.py`, `tests/test_supply_live_api.py`, `tests/test_supply_live_startup.py`.

KKCSheetTracker (`app/src/main/java/com/kkc/sheettracker`):
- Create `data/SupplyLiveDocument.kt` — `SupplyLiveSnapshot`, comment comparator, null-safe parser.
- Create `data/SupplyLiveStateStore.kt` — `SupplyView`, store, pending overlay.
- Create `data/SupplyLiveClient.kt` — OkHttp WebSocket client.
- Modify `data/SupplyRepository.kt` — `resolveStoredItem` extraction, live reads, overlay recording.
- Modify `data/SupplySubscriptionManager.kt` — rescan on store version.
- Modify `MainActivity.kt`, `navigation/NavGraph.kt`, `ui/supply/SupplyDashboardScreen.kt`, `ui/supply/SupplyItemDetailScreen.kt` — wiring.
- Tests (`app/src/test/java/com/kkc/sheettracker/data`): `SupplyLiveDocumentTest.kt`, `SupplyLiveStateStoreTest.kt`, `SupplyLiveClientTest.kt`, additions to `SupplyRepositoryTest.kt` and `SupplySubscriptionManagerTest.kt`.

---

## Task 1: SupplyLiveService (Hours Tracker)

**Files:**
- Create: `C:\Scripts\Hours Tracker\backend\ready_jobs_worker_core\adapters\supply_live_service.py`
- Test: `C:\Scripts\Hours Tracker\backend\tests\test_supply_live_service.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `class SupplyLiveService(initial_document: Mapping[str, object])` with `replace(document) -> int`, `snapshot() -> dict` (`{"type":"snapshot","revision":int,"supply":dict}`), `wait_for_update(after_revision: int, timeout: float | None = None) -> dict | None` (`{"type":"supply","revision":int,"supply":dict}` or `None` on timeout).

- [ ] **Step 1: Create the branch**

```powershell
cd "C:\Scripts\Hours Tracker"
git switch -c feat/supply-live
```

- [ ] **Step 2: Write the failing tests**

`tests/test_supply_live_service.py`:

```python
from __future__ import annotations

import threading
import time

from ready_jobs_worker_core.adapters.supply_live_service import SupplyLiveService


def _doc(name: str = "Screws") -> dict:
    return {
        "categories": [{"id": "c1", "name": "Hardware", "position": 0}],
        "schema": [],
        "items": [
            {
                "id": "i1",
                "categoryId": "c1",
                "name": name,
                "status": "IN STOCK",
                "statusBy": "",
                "statusAt": "",
            }
        ],
        "comments": {},
    }


def test_initial_snapshot_is_revision_zero():
    service = SupplyLiveService(_doc())
    assert service.snapshot() == {"type": "snapshot", "revision": 0, "supply": _doc()}


def test_replace_with_identical_content_keeps_revision():
    service = SupplyLiveService(_doc())
    assert service.replace(_doc()) == 0
    assert service.snapshot()["revision"] == 0


def test_replace_with_changed_content_bumps_revision():
    service = SupplyLiveService(_doc("Screws"))
    assert service.replace(_doc("Bolts")) == 1
    snapshot = service.snapshot()
    assert snapshot["revision"] == 1
    assert snapshot["supply"]["items"][0]["name"] == "Bolts"


def test_snapshot_is_a_deep_copy():
    service = SupplyLiveService(_doc())
    snapshot = service.snapshot()
    snapshot["supply"]["items"][0]["name"] = "mutated"
    assert service.snapshot()["supply"]["items"][0]["name"] == "Screws"


def test_replace_copies_the_incoming_document():
    service = SupplyLiveService(_doc())
    incoming = _doc("Bolts")
    service.replace(incoming)
    incoming["items"][0]["name"] = "mutated"
    assert service.snapshot()["supply"]["items"][0]["name"] == "Bolts"


def test_wait_for_update_times_out_with_none():
    service = SupplyLiveService(_doc())
    assert service.wait_for_update(0, timeout=0.05) is None


def test_wait_for_update_returns_immediately_when_already_newer():
    service = SupplyLiveService(_doc("Screws"))
    service.replace(_doc("Bolts"))
    started = time.monotonic()
    frame = service.wait_for_update(0, timeout=5.0)
    assert time.monotonic() - started < 1.0
    assert frame == {"type": "supply", "revision": 1, "supply": _doc("Bolts")}


def test_wait_for_update_wakes_on_replace():
    service = SupplyLiveService(_doc("Screws"))

    def replace_later():
        time.sleep(0.05)
        service.replace(_doc("Bolts"))

    thread = threading.Thread(target=replace_later)
    thread.start()
    frame = service.wait_for_update(0, timeout=2.0)
    thread.join()
    assert frame is not None
    assert frame["type"] == "supply"
    assert frame["revision"] == 1
```

- [ ] **Step 3: Run tests to verify they fail**

Run (from `C:\Scripts\Hours Tracker\backend`): `.\venv\Scripts\python.exe -m pytest tests/test_supply_live_service.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'ready_jobs_worker_core.adapters.supply_live_service'`.

- [ ] **Step 4: Implement**

`ready_jobs_worker_core/adapters/supply_live_service.py`:

```python
"""Thread-safe in-memory complete-snapshot supply state for live clients."""

from __future__ import annotations

import copy
import threading
import time
from collections.abc import Mapping


class SupplyLiveService:
    """Hold the latest complete supply document for live clients.

    The service owns no file I/O or supply business rules. Each replacement
    is a complete document and advances the in-process revision only when its
    content differs from the current document.
    """

    def __init__(self, initial_document: Mapping[str, object]) -> None:
        self._condition = threading.Condition()
        self._document = copy.deepcopy(dict(initial_document))
        self._revision = 0

    def replace(self, document: Mapping[str, object]) -> int:
        incoming = copy.deepcopy(dict(document))
        with self._condition:
            if incoming == self._document:
                return self._revision
            self._document = incoming
            self._revision += 1
            self._condition.notify_all()
            return self._revision

    def snapshot(self) -> dict[str, object]:
        with self._condition:
            return {
                "type": "snapshot",
                "revision": self._revision,
                "supply": copy.deepcopy(self._document),
            }

    def wait_for_update(
        self, after_revision: int, timeout: float | None = None
    ) -> dict[str, object] | None:
        """Wait for and return the newest complete supply frame.

        ``None`` indicates that the deadline elapsed without a revision newer
        than ``after_revision``. A revision that is already newer when called
        returns immediately.
        """
        deadline = None if timeout is None else time.monotonic() + max(0.0, timeout)
        with self._condition:
            while self._revision <= after_revision:
                if deadline is None:
                    self._condition.wait()
                    continue
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self._condition.wait(remaining)
            return {
                "type": "supply",
                "revision": self._revision,
                "supply": copy.deepcopy(self._document),
            }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_supply_live_service.py -q`
Expected: 8 passed.

- [ ] **Step 6: Commit**

```powershell
git add backend/ready_jobs_worker_core/adapters/supply_live_service.py backend/tests/test_supply_live_service.py
git commit -m "feat(supply): add in-memory live supply document service

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 2: Read-only supply document builder (Hours Tracker)

**Files:**
- Create: `C:\Scripts\Hours Tracker\backend\routes\supply_live_document.py`
- Test: `C:\Scripts\Hours Tracker\backend\tests\test_supply_live_document.py`

**Interfaces:**
- Consumes: `routes.supply_store.supply_data_dir()`, `routes.supply_store.DEFAULT_SCHEMA`, `routes.utils.is_sync_conflict`, `routes.utils.parse_instant`, `routes.utils.validate_filename`.
- Produces: `build_supply_document(root: Path | None = None) -> dict` returning `{"categories": list, "schema": list, "items": list, "comments": dict[str, list]}`; `class SupplyDocumentError(Exception)`.

- [ ] **Step 1: Write the failing tests**

`tests/test_supply_live_document.py`:

```python
from __future__ import annotations

import json
from pathlib import Path

import pytest

from routes import supply_store
from routes.supply_live_document import SupplyDocumentError, build_supply_document


def _write(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def _item(item_id: str = "i1", **extra) -> dict:
    base = {
        "id": item_id,
        "categoryId": "c1",
        "name": "Screws",
        "notes": None,
        "fields": {},
        "customFields": {},
        "attachmentIds": [],
        "barcodes": [],
        "createdAt": "2026-01-01T00:00:00Z",
        "updatedAt": "2026-01-01T00:00:00Z",
    }
    base.update(extra)
    return base


def _all_paths(root: Path) -> set[str]:
    return {str(p.relative_to(root)) for p in root.rglob("*")} if root.exists() else set()


def test_missing_tree_builds_empty_document_without_creating_anything(tmp_path):
    root = tmp_path / ".supply"
    document = build_supply_document(root)
    assert document == {
        "categories": [],
        "schema": supply_store.DEFAULT_SCHEMA,
        "items": [],
        "comments": {},
    }
    assert not root.exists()


def test_item_without_status_defaults_to_in_stock(tmp_path):
    root = tmp_path / ".supply"
    _write(root / "items" / "i1.json", _item())
    item = build_supply_document(root)["items"][0]
    assert (item["status"], item["statusBy"], item["statusAt"]) == ("IN STOCK", "", "")


def test_latest_status_wins_by_parsed_instant(tmp_path):
    root = tmp_path / ".supply"
    _write(root / "items" / "i1.json", _item())
    # Lexically "...:00Z" > "...:00.5Z", but .5 s is chronologically later.
    _write(root / "status" / "i1.tabA.json", {"status": "LOW", "by": "Sam", "at": "2026-01-01T00:00:00.5Z"})
    _write(root / "status" / "i1.tabB.json", {"status": "OUT", "by": "Kim", "at": "2026-01-01T00:00:00Z"})
    item = build_supply_document(root)["items"][0]
    assert (item["status"], item["statusBy"], item["statusAt"]) == ("LOW", "Sam", "2026-01-01T00:00:00.5Z")


def test_status_records_without_at_are_ignored(tmp_path):
    root = tmp_path / ".supply"
    _write(root / "items" / "i1.json", _item())
    _write(root / "status" / "i1.tabA.json", {"status": "OUT", "by": "Kim"})
    assert build_supply_document(root)["items"][0]["status"] == "IN STOCK"


def test_conflict_copies_are_ignored(tmp_path):
    root = tmp_path / ".supply"
    _write(root / "items" / "i1.json", _item())
    _write(root / "items" / "i2.sync-conflict-20260101-000000-ABCDEFG.json", _item("i2"))
    _write(root / "status" / "i1.tabA.json", {"status": "LOW", "by": "", "at": "2026-01-01T00:00:00Z"})
    _write(
        root / "status" / "i1.tabA.sync-conflict-20260101-000000-ABCDEFG.json",
        {"status": "OUT", "by": "", "at": "2027-01-01T00:00:00Z"},
    )
    _write(root / "comments" / "i1" / "c1.sync-conflict-20260101-000000-ABCDEFG.json",
           {"id": "c1", "author": "a", "text": "t", "createdAt": "2026-01-01T00:00:00Z"})
    document = build_supply_document(root)
    assert [i["id"] for i in document["items"]] == ["i1"]
    assert document["items"][0]["status"] == "LOW"
    assert document["comments"] == {}


def test_unreadable_item_status_and_comment_files_are_skipped_without_writing(tmp_path):
    root = tmp_path / ".supply"
    _write(root / "items" / "i1.json", _item())
    (root / "items" / "bad.json").write_text("{not json", encoding="utf-8")
    _write(root / "status" / "i1.tabA.json", {"status": "LOW", "by": "", "at": "2026-01-01T00:00:00Z"})
    (root / "status" / "i1.tabB.json").write_text("{", encoding="utf-8")
    _write(root / "comments" / "i1" / "ok.json", {"id": "ok", "author": "a", "text": "t", "createdAt": "2026-01-01T00:00:00Z"})
    (root / "comments" / "i1" / "bad.json").write_text("[", encoding="utf-8")
    before = _all_paths(root)

    document = build_supply_document(root)

    assert [i["id"] for i in document["items"]] == ["i1"]
    assert document["items"][0]["status"] == "LOW"
    assert [c["id"] for c in document["comments"]["i1"]] == ["ok"]
    assert _all_paths(root) == before


def test_sku_is_added_to_barcodes_once(tmp_path):
    root = tmp_path / ".supply"
    _write(root / "items" / "i1.json", _item("i1", fields={"sku": " SKU-1 "}, barcodes=[]))
    _write(root / "items" / "i2.json", _item("i2", fields={"sku": "SKU-2"}, barcodes=["SKU-2"]))
    items = {i["id"]: i for i in build_supply_document(root)["items"]}
    assert items["i1"]["barcodes"] == ["SKU-1"]
    assert items["i2"]["barcodes"] == ["SKU-2"]


def test_null_fields_and_barcodes_do_not_break_the_build(tmp_path):
    root = tmp_path / ".supply"
    _write(root / "items" / "i1.json", _item("i1", fields=None, barcodes=None))
    item = build_supply_document(root)["items"][0]
    assert item["barcodes"] == []
    assert item["fields"] is None


def test_unsafe_item_id_is_skipped(tmp_path):
    root = tmp_path / ".supply"
    _write(root / "items" / "ok.json", _item("../evil"))
    _write(root / "items" / "i1.json", _item("i1"))
    assert [i["id"] for i in build_supply_document(root)["items"]] == ["i1"]


def test_comments_sorted_by_parsed_instant_and_only_for_known_items(tmp_path):
    root = tmp_path / ".supply"
    _write(root / "items" / "i1.json", _item("i1"))
    _write(root / "comments" / "i1" / "b.json", {"id": "b", "author": "a", "text": "t", "createdAt": "2026-01-01T00:00:00.5Z"})
    _write(root / "comments" / "i1" / "a.json", {"id": "a", "author": "a", "text": "t", "createdAt": "2026-01-01T00:00:00Z"})
    _write(root / "comments" / "ghost" / "x.json", {"id": "x", "author": "a", "text": "t", "createdAt": "2026-01-01T00:00:00Z"})
    comments = build_supply_document(root)["comments"]
    assert list(comments) == ["i1"]
    assert [c["id"] for c in comments["i1"]] == ["a", "b"]


def test_categories_and_schema_are_passed_through(tmp_path):
    root = tmp_path / ".supply"
    categories = [{"id": "c1", "name": "Hardware", "position": 0}]
    schema = [{"id": "f1", "key": "sku", "label": "SKU", "type": "text", "builtin": True}]
    _write(root / "categories.json", categories)
    _write(root / "schema.json", schema)
    document = build_supply_document(root)
    assert document["categories"] == categories
    assert document["schema"] == schema


def test_corrupt_categories_raises(tmp_path):
    root = tmp_path / ".supply"
    root.mkdir()
    (root / "categories.json").write_text("{", encoding="utf-8")
    with pytest.raises(SupplyDocumentError):
        build_supply_document(root)


def test_non_list_schema_raises(tmp_path):
    root = tmp_path / ".supply"
    _write(root / "schema.json", {"not": "a list"})
    with pytest.raises(SupplyDocumentError):
        build_supply_document(root)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_supply_live_document.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'routes.supply_live_document'`.

- [ ] **Step 3: Implement**

`routes/supply_live_document.py`:

```python
"""Read-only builder for the live supply document.

Spec: KKCSheetTracker docs/superpowers/specs/2026-09-28-supply-live-websocket-design.md.

Unlike the ``supply_store`` loaders this module never writes: no
``ensure_dirs()``, no lock sidecars, no ``.corrupt`` backups. Unreadable item,
status and comment files are skipped (the tablet's file path does the same);
an unreadable ``categories.json`` or ``schema.json`` raises
``SupplyDocumentError`` so the live monitor keeps its last good document.
"""

from __future__ import annotations

import copy
import json
import logging
import os
from pathlib import Path
from typing import Any

from routes import supply_store
from routes.utils import is_sync_conflict, parse_instant, validate_filename

logger = logging.getLogger("uvicorn")

_DEFAULT_STATUS = {"status": "IN STOCK", "by": "", "at": ""}


class SupplyDocumentError(Exception):
    """categories.json or schema.json exists but cannot be used."""


def _json_files(directory: Path) -> list[os.DirEntry]:
    try:
        entries = list(os.scandir(directory))
    except FileNotFoundError:
        return []
    return sorted(
        (
            e
            for e in entries
            if e.is_file() and e.name.endswith(".json") and not is_sync_conflict(e.name)
        ),
        key=lambda e: e.name,
    )


def _read_optional(path: Path) -> Any:
    """Parsed JSON, or None when the file is missing or unreadable."""
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None
    except (OSError, ValueError) as exc:
        logger.warning("Supply live: skipping unreadable %s: %s", path, exc)
        return None


def _read_required_list(path: Path, default: list) -> list:
    if not path.exists():
        return copy.deepcopy(default)
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise SupplyDocumentError(f"{path.name} unreadable: {exc}") from exc
    if not isinstance(value, list):
        raise SupplyDocumentError(f"{path.name} is not a JSON list")
    return value


def _resolve_status(item_id: str, records: dict[str, Any]) -> dict[str, Any]:
    """Latest-wins by parsed instant; same rule as supply_store.get_status."""
    prefix = f"{item_id}."
    best: dict[str, Any] | None = None
    best_at = None
    for name, record in records.items():
        if not name.startswith(prefix):
            continue
        if not isinstance(record, dict) or "at" not in record:
            continue
        at = parse_instant(record["at"])
        if best is None or at > best_at or (at == best_at and str(record["at"]) > str(best["at"])):
            best = record
            best_at = at
    return best if best is not None else dict(_DEFAULT_STATUS)


def _with_sku_barcode(item: dict[str, Any]) -> dict[str, Any]:
    """Same rule as supply_store._inject_sku_barcode, tolerant of malformed fields."""
    fields = item.get("fields")
    sku = str(fields.get("sku") or "").strip() if isinstance(fields, dict) else ""
    raw_barcodes = item.get("barcodes")
    barcodes = list(raw_barcodes) if isinstance(raw_barcodes, list) else []
    if sku and sku not in barcodes:
        barcodes.append(sku)
    item["barcodes"] = barcodes
    return item


def _comment_sort_key(comment: dict[str, Any]):
    created = comment.get("createdAt", "")
    return (parse_instant(created), str(created))


def build_supply_document(root: Path | None = None) -> dict[str, Any]:
    root = supply_store.supply_data_dir() if root is None else Path(root)

    categories = _read_required_list(root / "categories.json", [])
    schema = _read_required_list(root / "schema.json", supply_store.DEFAULT_SCHEMA)

    status_records = {
        entry.name: _read_optional(Path(entry.path)) for entry in _json_files(root / "status")
    }

    items: list[dict[str, Any]] = []
    for entry in _json_files(root / "items"):
        stored = _read_optional(Path(entry.path))
        if not isinstance(stored, dict):
            continue
        item_id = stored.get("id")
        if not isinstance(item_id, str):
            continue
        try:
            validate_filename(item_id)
        except Exception:
            logger.warning("Supply live: skipping item with unsafe id %r", item_id)
            continue
        status = _resolve_status(item_id, status_records)
        item = dict(stored)
        item["status"] = status.get("status", "IN STOCK")
        item["statusBy"] = status.get("by", "")
        item["statusAt"] = status.get("at", "")
        items.append(_with_sku_barcode(item))

    comments: dict[str, list[dict[str, Any]]] = {}
    for item in items:
        item_comments = [
            c
            for c in (_read_optional(Path(e.path)) for e in _json_files(root / "comments" / item["id"]))
            if isinstance(c, dict)
        ]
        if item_comments:
            item_comments.sort(key=_comment_sort_key)
            comments[item["id"]] = item_comments

    return {"categories": categories, "schema": schema, "items": items, "comments": comments}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_supply_live_document.py -q`
Expected: 13 passed.

- [ ] **Step 5: Commit**

```powershell
git add backend/routes/supply_live_document.py backend/tests/test_supply_live_document.py
git commit -m "feat(supply): add read-only live supply document builder

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 3: SupplyLiveMonitor (Hours Tracker)

**Files:**
- Create: `C:\Scripts\Hours Tracker\backend\ready_jobs_worker_core\adapters\supply_live_monitor.py`
- Test: `C:\Scripts\Hours Tracker\backend\tests\test_supply_live_monitor.py`

**Interfaces:**
- Consumes: nothing from earlier tasks at import time (builder and publisher are injected).
- Produces: `supply_tree_signature(root: Path) -> tuple[tuple[str, int, int], ...]`; `class SupplyLiveMonitor(root, build_document: Callable[[], Mapping], on_document: Callable[[Mapping], Any], logger)` with `poll_once(initial: bool = False) -> bool` and `run_until(stop_event: threading.Event, poll_interval_seconds: float = 2.0) -> None`.

- [ ] **Step 1: Write the failing tests**

`tests/test_supply_live_monitor.py`:

```python
from __future__ import annotations

import json
import logging
import shutil
import threading
from pathlib import Path

from ready_jobs_worker_core.adapters.supply_live_monitor import (
    SupplyLiveMonitor,
    supply_tree_signature,
)

LOGGER = logging.getLogger("test.supply_live_monitor")


def _write(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def _seed(root: Path) -> None:
    _write(root / "categories.json", [{"id": "c1", "name": "Hardware", "position": 0}])
    _write(root / "items" / "i1.json", {"id": "i1", "name": "Screws"})


# ── signature ───────────────────────────────────────────────────────────────


def test_signature_of_missing_tree_is_empty(tmp_path):
    assert supply_tree_signature(tmp_path / ".supply") == ()


def test_signature_changes_on_item_add_edit_and_delete(tmp_path):
    root = tmp_path / ".supply"
    _seed(root)
    first = supply_tree_signature(root)
    _write(root / "items" / "i2.json", {"id": "i2"})
    added = supply_tree_signature(root)
    assert added != first
    _write(root / "items" / "i2.json", {"id": "i2", "name": "a much longer name"})
    edited = supply_tree_signature(root)
    assert edited != added
    (root / "items" / "i2.json").unlink()
    assert supply_tree_signature(root) != edited


def test_signature_changes_on_status_comment_and_schema(tmp_path):
    root = tmp_path / ".supply"
    _seed(root)
    before = supply_tree_signature(root)
    _write(root / "status" / "i1.tabA.json", {"status": "OUT", "at": "2026-01-01T00:00:00Z"})
    after_status = supply_tree_signature(root)
    assert after_status != before
    _write(root / "comments" / "i1" / "c1.json", {"id": "c1"})
    after_comment = supply_tree_signature(root)
    assert after_comment != after_status
    _write(root / "schema.json", [])
    assert supply_tree_signature(root) != after_comment


def test_signature_ignores_conflicts_locks_and_other_files(tmp_path):
    root = tmp_path / ".supply"
    _seed(root)
    before = supply_tree_signature(root)
    _write(root / "items" / "i1.sync-conflict-20260101-000000-ABCDEFG.json", {"id": "i1"})
    (root / "categories.json.lock").write_text("", encoding="utf-8")
    (root / "items" / "i1.json.corrupt").write_text("x", encoding="utf-8")
    _write(root / "barcodes.json", {})
    (root / "attachments" / "i1").mkdir(parents=True)
    (root / "attachments" / "i1" / "photo.jpg").write_bytes(b"x")
    assert supply_tree_signature(root) == before


# ── monitor ─────────────────────────────────────────────────────────────────


def test_initial_poll_builds_and_publishes(tmp_path):
    root = tmp_path / ".supply"
    _seed(root)
    published = []
    monitor = SupplyLiveMonitor(root, lambda: {"items": ["v1"]}, published.append, LOGGER)
    assert monitor.poll_once(initial=True) is True
    assert published == [{"items": ["v1"]}]


def test_initial_poll_with_missing_tree_publishes(tmp_path):
    published = []
    monitor = SupplyLiveMonitor(tmp_path / ".supply", lambda: {"items": []}, published.append, LOGGER)
    assert monitor.poll_once(initial=True) is True
    assert published == [{"items": []}]


def test_unchanged_tree_is_not_rebuilt(tmp_path):
    root = tmp_path / ".supply"
    _seed(root)
    builds = []

    def build():
        builds.append(1)
        return {"items": []}

    monitor = SupplyLiveMonitor(root, build, lambda doc: None, LOGGER)
    assert monitor.poll_once(initial=True) is True
    assert monitor.poll_once() is False
    assert len(builds) == 1


def test_changed_tree_is_rebuilt_and_published(tmp_path):
    root = tmp_path / ".supply"
    _seed(root)
    version = ["v1"]
    published = []
    monitor = SupplyLiveMonitor(root, lambda: {"items": list(version)}, published.append, LOGGER)
    monitor.poll_once(initial=True)
    version[0] = "v2"
    _write(root / "items" / "i2.json", {"id": "i2"})
    assert monitor.poll_once() is True
    assert published[-1] == {"items": ["v2"]}


def test_failed_build_is_retried_on_next_poll_without_a_tree_change(tmp_path):
    root = tmp_path / ".supply"
    _seed(root)
    published = []
    fail = [False]

    def build():
        if fail[0]:
            raise ValueError("torn file")
        return {"items": ["ok"]}

    monitor = SupplyLiveMonitor(root, build, published.append, LOGGER)
    monitor.poll_once(initial=True)
    _write(root / "items" / "i2.json", {"id": "i2"})
    fail[0] = True
    assert monitor.poll_once() is False
    fail[0] = False
    assert monitor.poll_once() is True
    assert len(published) == 2


def test_failed_publication_is_retried(tmp_path):
    root = tmp_path / ".supply"
    _seed(root)
    calls = []

    def publish(doc):
        calls.append(doc)
        if len(calls) == 1:
            raise RuntimeError("boom")

    monitor = SupplyLiveMonitor(root, lambda: {"items": []}, publish, LOGGER)
    assert monitor.poll_once(initial=True) is False
    assert monitor.poll_once() is True
    assert len(calls) == 2


def test_non_mapping_document_is_not_published(tmp_path):
    root = tmp_path / ".supply"
    _seed(root)
    published = []
    monitor = SupplyLiveMonitor(root, lambda: ["not", "a", "mapping"], published.append, LOGGER)
    assert monitor.poll_once(initial=True) is False
    assert published == []


def test_tree_change_during_build_is_not_published(tmp_path):
    root = tmp_path / ".supply"
    _seed(root)
    published = []
    counter = [0]

    def build():
        counter[0] += 1
        _write(root / "items" / f"new{counter[0]}.json", {"id": f"new{counter[0]}"})
        return {"items": []}

    monitor = SupplyLiveMonitor(root, build, published.append, LOGGER)
    assert monitor.poll_once(initial=True) is False
    assert published == []


def test_vanished_tree_after_good_publish_retains_last_document(tmp_path):
    root = tmp_path / ".supply"
    _seed(root)
    published = []
    monitor = SupplyLiveMonitor(root, lambda: {"items": ["i1"]}, published.append, LOGGER)
    assert monitor.poll_once(initial=True) is True
    shutil.rmtree(root)
    assert monitor.poll_once() is False
    assert published == [{"items": ["i1"]}]


def test_run_until_stops_when_event_is_set(tmp_path):
    root = tmp_path / ".supply"
    _seed(root)
    stop_event = threading.Event()
    published = []

    def publish(doc):
        published.append(doc)
        stop_event.set()

    monitor = SupplyLiveMonitor(root, lambda: {"items": []}, publish, LOGGER)
    monitor.run_until(stop_event, poll_interval_seconds=0.01)
    assert len(published) == 1
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_supply_live_monitor.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'ready_jobs_worker_core.adapters.supply_live_monitor'`.

- [ ] **Step 3: Implement**

`ready_jobs_worker_core/adapters/supply_live_monitor.py`:

```python
"""Poll the shared .supply tree and publish complete supply documents.

File I/O for the document itself lives in the injected ``build_document``
callable (production: ``routes.supply_live_document.build_supply_document``);
this module only decides *when* to rebuild. A new tree signature is committed
only after the build and publication both succeed, so a torn or unreadable
file is retried on the next poll while the last good document stays served.
"""

from __future__ import annotations

import os
import threading
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any

Signature = tuple[tuple[str, int, int], ...]


def _is_tracked(name: str) -> bool:
    # Same conflict rule as routes.utils.is_sync_conflict.
    return name.endswith(".json") and ".sync-conflict-" not in name


def supply_tree_signature(root: Path) -> Signature:
    """(relative path, mtime_ns, size) for every file the document is built from.

    Missing directories contribute nothing. A file that vanishes between
    listing and stat is skipped; the settle check in ``poll_once`` catches
    the in-flight change.
    """
    root = Path(root)
    entries: list[tuple[str, int, int]] = []

    def add(path: Path, rel: str) -> None:
        try:
            st = path.stat()
        except FileNotFoundError:
            return
        entries.append((rel, st.st_mtime_ns, st.st_size))

    def tracked_files(directory: Path) -> list[os.DirEntry]:
        try:
            return [e for e in os.scandir(directory) if e.is_file() and _is_tracked(e.name)]
        except FileNotFoundError:
            return []

    for name in ("categories.json", "schema.json"):
        add(root / name, name)
    for sub in ("items", "status"):
        for entry in tracked_files(root / sub):
            add(Path(entry.path), f"{sub}/{entry.name}")
    try:
        comment_dirs = [e for e in os.scandir(root / "comments") if e.is_dir()]
    except FileNotFoundError:
        comment_dirs = []
    for directory in comment_dirs:
        for entry in tracked_files(Path(directory.path)):
            add(Path(entry.path), f"comments/{directory.name}/{entry.name}")
    return tuple(sorted(entries))


class SupplyLiveMonitor:
    def __init__(
        self,
        root: Path | str,
        build_document: Callable[[], Mapping[str, object]],
        on_document: Callable[[Mapping[str, object]], Any],
        logger: Any,
    ) -> None:
        self._root = Path(root)
        self._build_document = build_document
        self._on_document = on_document
        self._logger = logger
        self._last_signature: Signature | None = None

    def _log_warning(self, message: str, *args: object) -> None:
        warning = getattr(self._logger, "warning", None)
        if callable(warning):
            warning(message, *args)

    def poll_once(self, initial: bool = False) -> bool:
        """Rebuild and publish once if the tree changed (always on ``initial``).

        On initial hydration a missing tree is the legitimate empty catalog.
        After that, a missing tree is treated as unavailable (for example a
        dropped mount) and the previously published document is retained.
        """
        if not initial and not self._root.is_dir():
            self._log_warning(
                "Supply tree unavailable; retaining last-known-good state: %s", self._root
            )
            return False

        try:
            signature = supply_tree_signature(self._root)
        except OSError as exc:
            self._log_warning("Supply tree unavailable; retaining last-known-good state: %s", exc)
            return False

        if not initial and signature == self._last_signature:
            return False

        try:
            document = self._build_document()
        except Exception as exc:
            self._log_warning(
                "Supply document build failed; retaining last-known-good state: %s", exc
            )
            return False

        if not isinstance(document, Mapping):
            self._log_warning(
                "Supply document build returned an invalid value; retaining last-known-good state"
            )
            return False

        try:
            settled = supply_tree_signature(self._root)
        except OSError as exc:
            self._log_warning("Supply tree changed during build; retrying: %s", exc)
            return False
        if settled != signature:
            self._log_warning("Supply tree changed during build; retrying before publication")
            return False

        try:
            self._on_document(document)
        except Exception as exc:
            self._log_warning(
                "Supply document publication failed; retaining last-known-good state: %s", exc
            )
            return False

        self._last_signature = signature
        return True

    def run_until(
        self,
        stop_event: threading.Event,
        poll_interval_seconds: float = 2.0,
    ) -> None:
        """Poll until ``stop_event`` is set, waking promptly for shutdown."""
        while not stop_event.is_set():
            self.poll_once()
            stop_event.wait(poll_interval_seconds)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_supply_live_monitor.py -q`
Expected: 14 passed.

- [ ] **Step 5: Commit**

```powershell
git add backend/ready_jobs_worker_core/adapters/supply_live_monitor.py backend/tests/test_supply_live_monitor.py
git commit -m "feat(supply): add .supply tree monitor for the live feed

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 4: `/api/supply/live` WebSocket route (Hours Tracker)

**Files:**
- Create: `C:\Scripts\Hours Tracker\backend\routes\supply_live.py`
- Test: `C:\Scripts\Hours Tracker\backend\tests\test_supply_live_api.py`

**Interfaces:**
- Consumes: `SupplyLiveService` (Task 1).
- Produces: `router` (FastAPI `APIRouter`, prefix `/api/supply`), `get_supply_live_service() -> SupplyLiveService | None`, `set_supply_live_service(service: SupplyLiveService | None) -> None`.

- [ ] **Step 1: Write the failing tests**

`tests/test_supply_live_api.py`:

```python
from __future__ import annotations

from fastapi import FastAPI
from fastapi.testclient import TestClient

import routes.supply_live as supply_live_route
from ready_jobs_worker_core.adapters.supply_live_service import SupplyLiveService


def _doc(name: str = "Screws") -> dict:
    return {
        "categories": [],
        "schema": [],
        "items": [{"id": "i1", "categoryId": "c1", "name": name, "status": "IN STOCK", "statusBy": "", "statusAt": ""}],
        "comments": {},
    }


def _client() -> TestClient:
    app = FastAPI()
    app.include_router(supply_live_route.router)
    return TestClient(app)


def test_service_registration_round_trip():
    service = SupplyLiveService(_doc())
    supply_live_route.set_supply_live_service(service)
    try:
        assert supply_live_route.get_supply_live_service() is service
    finally:
        supply_live_route.set_supply_live_service(None)


def test_connect_with_no_service_gets_not_running(monkeypatch):
    monkeypatch.setattr(supply_live_route, "get_supply_live_service", lambda: None)
    with _client().websocket_connect("/api/supply/live") as socket:
        socket.send_json({"type": "hello", "tabletId": "tablet-1"})
        message = socket.receive_json()
    assert message == {"type": "not_running"}


def test_hello_then_full_snapshot(monkeypatch):
    service = SupplyLiveService(_doc())
    monkeypatch.setattr(supply_live_route, "get_supply_live_service", lambda: service)
    with _client().websocket_connect("/api/supply/live") as socket:
        socket.send_json({"type": "hello", "tabletId": "tablet-1"})
        message = socket.receive_json()
    assert message == {"type": "snapshot", "revision": 0, "supply": _doc()}


def test_connect_without_hello_is_rejected(monkeypatch):
    service = SupplyLiveService(_doc())
    monkeypatch.setattr(supply_live_route, "get_supply_live_service", lambda: service)
    with _client().websocket_connect("/api/supply/live") as socket:
        socket.send_json({"type": "not_hello"})
        message = socket.receive_json()
    assert message["type"] == "error"


def test_replacement_after_connect_is_delivered_as_complete_frame(monkeypatch):
    service = SupplyLiveService(_doc("Screws"))
    monkeypatch.setattr(supply_live_route, "get_supply_live_service", lambda: service)
    with _client().websocket_connect("/api/supply/live") as socket:
        socket.send_json({"type": "hello", "tabletId": "tablet-1"})
        assert socket.receive_json()["type"] == "snapshot"
        service.replace(_doc("Bolts"))
        message = socket.receive_json()
    assert message["type"] == "supply"
    assert message["revision"] == 1
    assert message["supply"]["items"][0]["name"] == "Bolts"


def test_client_frames_after_hello_are_ignored(monkeypatch):
    service = SupplyLiveService(_doc("Screws"))
    monkeypatch.setattr(supply_live_route, "get_supply_live_service", lambda: service)
    with _client().websocket_connect("/api/supply/live") as socket:
        socket.send_json({"type": "hello", "tabletId": "tablet-1"})
        assert socket.receive_json()["type"] == "snapshot"
        socket.send_json({"type": "anything"})
        service.replace(_doc("Bolts"))
        message = socket.receive_json()
    assert message["type"] == "supply"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_supply_live_api.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'routes.supply_live'`.

- [ ] **Step 3: Implement**

`routes/supply_live.py`:

```python
"""Live supply WebSocket read path (read-only).

Spec: KKCSheetTracker docs/superpowers/specs/2026-09-28-supply-live-websocket-design.md.
Tablet writes to .supply are unchanged (files + Syncthing); this socket only
publishes the resolved read model built by routes.supply_live_document.
"""

from __future__ import annotations

import asyncio
import threading

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from ready_jobs_worker_core.adapters.supply_live_service import SupplyLiveService


router = APIRouter(prefix="/api/supply", tags=["supply"])

_service: SupplyLiveService | None = None
_service_lock = threading.Lock()


def get_supply_live_service() -> SupplyLiveService | None:
    with _service_lock:
        return _service


def set_supply_live_service(service: SupplyLiveService | None) -> None:
    global _service
    with _service_lock:
        _service = service


async def _cancel(task: asyncio.Task[object]) -> None:
    if not task.done():
        task.cancel()
    try:
        await task
    except (asyncio.CancelledError, Exception):
        pass


@router.websocket("/live")
async def supply_live(websocket: WebSocket) -> None:
    """Send a complete supply snapshot, then complete replacements."""
    await websocket.accept()
    service = get_supply_live_service()
    if service is None:
        await websocket.send_json({"type": "not_running"})
        await websocket.close()
        return

    try:
        hello = await websocket.receive_json()
    except WebSocketDisconnect:
        return
    if not isinstance(hello, dict) or hello.get("type") != "hello":
        await websocket.send_json({"type": "error", "message": "expected a hello message first"})
        await websocket.close()
        return

    snapshot = service.snapshot()
    await websocket.send_json(snapshot)
    after_revision = snapshot["revision"]

    try:
        while True:
            wait_task = asyncio.create_task(
                asyncio.to_thread(service.wait_for_update, after_revision, 1.0)
            )
            receive_task = asyncio.create_task(websocket.receive())
            done, _ = await asyncio.wait(
                (wait_task, receive_task), return_when=asyncio.FIRST_COMPLETED
            )

            if wait_task in done:
                update = wait_task.result()
                await _cancel(receive_task)
                if update is None:
                    continue
                await websocket.send_json(update)
                after_revision = update["revision"]
                continue

            try:
                message = receive_task.result()
            except WebSocketDisconnect:
                return
            if message.get("type") == "websocket.disconnect":
                return
            # Read-only socket: frames after hello are consumed and ignored.
            await _cancel(wait_task)
    except WebSocketDisconnect:
        return
    finally:
        for task in (locals().get("wait_task"), locals().get("receive_task")):
            if isinstance(task, asyncio.Task) and not task.done():
                task.cancel()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_supply_live_api.py -q`
Expected: 6 passed.

- [ ] **Step 5: Commit**

```powershell
git add backend/routes/supply_live.py backend/tests/test_supply_live_api.py
git commit -m "feat(supply): add read-only /api/supply/live WebSocket

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 5: Start/stop the supply live feed in `main_v2.py` (Hours Tracker)

**Files:**
- Modify: `C:\Scripts\Hours Tracker\backend\main_v2.py` (imports near line 190 and 246; `app.include_router` near line 266; globals near line 589; new function after `_start_delivery_schedule_live_once` (ends ~line 1193); `startup_event` ~line 1234; `shutdown_event` delivery block ~lines 1342–1370)
- Test: `C:\Scripts\Hours Tracker\backend\tests\test_supply_live_startup.py`

**Interfaces:**
- Consumes: `SupplyLiveService` (Task 1), `build_supply_document` (Task 2), `SupplyLiveMonitor` (Task 3), `supply_live_router` / `set_supply_live_service` (Task 4), existing `get_base_path`.
- Produces: `main_v2._start_supply_live_once()`, globals `_supply_live_thread`, `_supply_live_stop_event`, `_supply_live_start_attempted`, `_supply_live_start_lock`.

- [ ] **Step 1: Write the failing tests**

`tests/test_supply_live_startup.py`:

```python
from __future__ import annotations

import asyncio
import threading

import main_v2

DOC = {"categories": [], "schema": [], "items": [], "comments": {}}


class _FakeThread:
    instances = []

    def __init__(self, target=None, daemon=None, name=None):
        self.target = target
        self.daemon = daemon
        self.name = name
        self.started = False
        self.join_calls = []
        self._alive = False
        self.instances.append(self)

    def start(self):
        self.started = True
        self._alive = True

    def join(self, timeout=None):
        self.join_calls.append(timeout)
        self._alive = False

    def is_alive(self):
        return self._alive


class _FakeMonitor:
    poll_result = True

    def __init__(self, root, build_document, on_document, logger):
        self.root = root
        self.build_document = build_document
        self.on_document = on_document

    def poll_once(self, initial=False):
        assert initial is True
        if self.poll_result:
            self.on_document(self.build_document())
        return self.poll_result

    def run_until(self, stop_event, poll_interval_seconds=2.0):
        return None


def _reset(monkeypatch, tmp_path, registered):
    _FakeThread.instances = []
    _FakeMonitor.poll_result = True
    monkeypatch.setattr(main_v2, "_supply_live_thread", None, raising=False)
    monkeypatch.setattr(main_v2, "_supply_live_stop_event", None, raising=False)
    monkeypatch.setattr(main_v2, "_supply_live_start_attempted", False, raising=False)
    monkeypatch.setattr(main_v2.threading, "Thread", _FakeThread)
    monkeypatch.setattr(main_v2, "get_base_path", lambda: tmp_path, raising=False)
    monkeypatch.setattr(main_v2, "SupplyLiveMonitor", _FakeMonitor, raising=False)
    monkeypatch.setattr(main_v2, "set_supply_live_service", registered.append, raising=False)


def test_start_once_registers_service_and_starts_one_daemon_thread(monkeypatch, tmp_path):
    registered = []
    _reset(monkeypatch, tmp_path, registered)
    roots = []

    def build(root):
        roots.append(root)
        return DOC

    monkeypatch.setattr(main_v2, "build_supply_document", build, raising=False)

    main_v2._start_supply_live_once()
    main_v2._start_supply_live_once()

    assert len(_FakeThread.instances) == 1
    thread = _FakeThread.instances[0]
    assert thread.started and thread.daemon is True and thread.name == "SupplyLiveMonitor"
    assert len(registered) == 1
    assert registered[0].snapshot()["supply"] == DOC
    assert set(roots) == {tmp_path / ".supply"}
    assert main_v2._supply_live_start_attempted is True


def test_start_once_does_not_register_when_initial_build_fails(monkeypatch, tmp_path):
    registered = []
    _reset(monkeypatch, tmp_path, registered)

    def build(root):
        raise ValueError("corrupt categories.json")

    monkeypatch.setattr(main_v2, "build_supply_document", build, raising=False)

    main_v2._start_supply_live_once()

    assert registered == []
    assert _FakeThread.instances == []
    assert main_v2._supply_live_start_attempted is False


def test_start_once_does_not_register_when_initial_poll_fails(monkeypatch, tmp_path):
    registered = []
    _reset(monkeypatch, tmp_path, registered)
    monkeypatch.setattr(main_v2, "build_supply_document", lambda root: DOC, raising=False)
    _FakeMonitor.poll_result = False

    main_v2._start_supply_live_once()

    assert registered == []
    assert _FakeThread.instances == []


def test_startup_event_starts_supply_live(monkeypatch):
    calls = []
    monkeypatch.setattr(main_v2, "_start_supply_live_once", lambda: calls.append("supply"), raising=False)
    monkeypatch.setattr(main_v2, "_start_delivery_schedule_live_once", lambda: None, raising=False)
    monkeypatch.setattr(main_v2, "_start_hidden_materials_live_once", lambda: None, raising=False)
    monkeypatch.setattr(main_v2, "_start_ready_jobs_worker_once", lambda: None)
    monkeypatch.setattr(main_v2.asyncio, "create_task", lambda coro: (coro.close(), object())[1])
    monkeypatch.setattr(main_v2, "is_shared_read_only_mode", lambda: True)

    asyncio.run(main_v2.startup_event())

    assert calls == ["supply"]


def test_shutdown_signals_joins_and_clears_supply_live(monkeypatch):
    stop_event = threading.Event()
    thread = _FakeThread()
    thread._alive = True
    monkeypatch.setattr(main_v2, "_supply_live_stop_event", stop_event, raising=False)
    monkeypatch.setattr(main_v2, "_supply_live_thread", thread, raising=False)
    monkeypatch.setattr(main_v2, "_supply_live_start_attempted", True, raising=False)
    for name in (
        "_ready_jobs_worker_thread",
        "_ready_jobs_worker_stop_event",
        "_ready_jobs_live_index_relay_thread",
        "_ready_jobs_live_index_relay_stop_event",
        "_ready_jobs_archive_library_relay_thread",
        "_ready_jobs_archive_library_relay_stop_event",
        "_delivery_schedule_live_thread",
        "_delivery_schedule_live_stop_event",
    ):
        monkeypatch.setattr(main_v2, name, None)
    observed = []
    monkeypatch.setattr(main_v2, "set_supply_live_service", observed.append, raising=False)
    monkeypatch.setattr(main_v2, "set_delivery_schedule_live_service", lambda value: None, raising=False)
    monkeypatch.setattr(main_v2, "clear_dry_run_runtime", lambda: None)
    monkeypatch.setattr(main_v2, "set_live_index_service", lambda value: None)
    monkeypatch.setattr(main_v2, "set_index_publisher", lambda value: None)
    monkeypatch.setattr(main_v2, "set_bad_parts_live_service", lambda value: None)
    monkeypatch.setattr(main_v2, "set_archive_library_service", lambda value: None)

    asyncio.run(main_v2.shutdown_event())

    assert stop_event.is_set()
    assert thread.join_calls == [10.0]
    assert observed == [None]
    assert main_v2._supply_live_thread is None
    assert main_v2._supply_live_stop_event is None
    assert main_v2._supply_live_start_attempted is False
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_supply_live_startup.py -q`
Expected: FAIL — `AttributeError: module 'main_v2' has no attribute '_start_supply_live_once'`.

- [ ] **Step 3: Add imports**

In `main_v2.py`, directly after the `from routes.delivery_schedule_live import (...)` block (~line 190), add:

```python
from routes.supply_live import (
    router as supply_live_router,
    set_supply_live_service,
)
from routes.supply_live_document import build_supply_document
```

Directly after the `from ready_jobs_worker_core.adapters.delivery_schedule_live_service import (...)` block (~line 249), add:

```python
from ready_jobs_worker_core.adapters.supply_live_monitor import SupplyLiveMonitor
from ready_jobs_worker_core.adapters.supply_live_service import SupplyLiveService
```

- [ ] **Step 4: Register the router**

Directly after `app.include_router(delivery_schedule_live_router)` (~line 266), add:

```python
app.include_router(supply_live_router)
```

- [ ] **Step 5: Add lifecycle globals**

Directly after `_delivery_schedule_live_start_lock = threading.Lock()` (~line 592), add:

```python

# Read-only supply live feed lifecycle (KKCSheetTracker spec
# 2026-09-28-supply-live-websocket-design). Tablets fall back to reading
# .supply files whenever this feed is not running.
_supply_live_thread = None
_supply_live_stop_event = None
_supply_live_start_attempted = False
_supply_live_start_lock = threading.Lock()
```

- [ ] **Step 6: Add `_start_supply_live_once`**

Directly after the end of `_start_delivery_schedule_live_once` (the line `_delivery_schedule_live_start_attempted = True`, ~line 1193), add:

```python


def _start_supply_live_once() -> None:
    """Hydrate and start the read-only supply live monitor once.

    The service is registered only after a valid initial document has been
    published, so WebSocket clients never observe an unhydrated state.
    """

    global _supply_live_thread
    global _supply_live_stop_event
    global _supply_live_start_attempted

    with _supply_live_start_lock:
        if _supply_live_start_attempted:
            return

        import logging

        logger = logging.getLogger("uvicorn")
        supply_root = get_base_path() / ".supply"

        try:
            service = SupplyLiveService(build_supply_document(supply_root))
        except Exception as exc:
            logger.warning("Supply live monitor initial hydration failed; not starting: %s", exc)
            return

        monitor = SupplyLiveMonitor(
            supply_root,
            lambda: build_supply_document(supply_root),
            service.replace,
            logger,
        )
        try:
            if not monitor.poll_once(initial=True):
                logger.warning("Supply live monitor initial hydration was not valid; not starting")
                return
        except Exception as exc:
            logger.warning("Supply live monitor initial hydration failed; not starting: %s", exc)
            return

        stop_event = threading.Event()

        def _run_monitor() -> None:
            monitor.run_until(stop_event)

        try:
            thread = threading.Thread(
                target=_run_monitor,
                daemon=True,
                name="SupplyLiveMonitor",
            )
            _supply_live_thread = thread
            _supply_live_stop_event = stop_event
            thread.start()
        except Exception as exc:
            _supply_live_thread = None
            _supply_live_stop_event = None
            set_supply_live_service(None)
            logger.warning("Supply live monitor could not start: %s", exc)
            return

        set_supply_live_service(service)
        _supply_live_start_attempted = True
```

- [ ] **Step 7: Call it at startup**

In `startup_event`, directly after `_start_delivery_schedule_live_once()`, add:

```python
    _start_supply_live_once()
```

- [ ] **Step 8: Stop it at shutdown**

In `shutdown_event`, add to the `global` declarations:

```python
    global _supply_live_thread
    global _supply_live_stop_event
    global _supply_live_start_attempted
```

Then directly before `for mode in hidden_materials_store.VALID_MODES:` add:

```python
    supply_live_stop_event = _supply_live_stop_event
    supply_live_thread = _supply_live_thread
    if supply_live_stop_event is not None:
        supply_live_stop_event.set()
    if supply_live_thread is not None and supply_live_thread is not threading.current_thread():
        supply_live_join = getattr(supply_live_thread, "join", None)
        if supply_live_join is not None:
            await asyncio.to_thread(supply_live_join, 10.0)
    set_supply_live_service(None)
    supply_live_is_alive = getattr(supply_live_thread, "is_alive", None)
    if not (
        supply_live_thread is not None
        and callable(supply_live_is_alive)
        and supply_live_is_alive()
    ):
        _supply_live_thread = None
        _supply_live_stop_event = None
        _supply_live_start_attempted = False

```

- [ ] **Step 9: Run the new and neighbouring tests**

Run: `.\venv\Scripts\python.exe -m pytest tests/test_supply_live_startup.py tests/test_delivery_schedule_live_startup.py tests/test_supply_live_service.py tests/test_supply_live_document.py tests/test_supply_live_monitor.py tests/test_supply_live_api.py -q`
Expected: all pass.

- [ ] **Step 10: Run the full backend suite**

Run: `.\venv\Scripts\python.exe -m pytest -q`
Expected: every failure (if any) also fails on `master`. To check one, run it from a clean `master` checkout: `git worktree add ..\ht-master master`, then in `..\ht-master\backend` run `..\..\"Hours Tracker"\backend\venv\Scripts\python.exe -m pytest <failing test> -q`, then `git worktree remove ..\ht-master`. Any failure that passes on `master` is a regression and must be fixed before committing.

- [ ] **Step 11: Commit**

```powershell
git add backend/main_v2.py backend/tests/test_supply_live_startup.py
git commit -m "feat(supply): start and stop the supply live feed with the app

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 6: Live document parser (KKCSheetTracker)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/SupplyLiveDocument.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/SupplyLiveDocumentTest.kt`

**Interfaces:**
- Consumes: models in `data/models/SupplyModels.kt` (`SupplyCategory(id, name, position)`, `SupplySchemaField(id, key, label, type, builtin)`, `SupplyItem(...)`, `SupplyComment(id, author, text, createdAt)`, `SupplyAttachment(id, originalName, storedName)`); `SupplyRepository.parseInstantOrMin(String): Instant` (existing, `internal` in companion).
- Produces: `data class SupplyLiveSnapshot(revision: Long, categories: List<SupplyCategory>, schema: List<SupplySchemaField>, items: Map<String, SupplyItem>, comments: Map<String, List<SupplyComment>>)`; `internal val SUPPLY_COMMENT_ORDER: Comparator<SupplyComment>`; `internal fun parseSupplyLiveDocument(revision: Long, supply: JsonObject): SupplyLiveSnapshot?`.

- [ ] **Step 1: Create the branch**

```powershell
cd C:\Scripts\KKCSheetTracker
git switch -c feat/supply-live
```

(The two unrelated modified files come along uncommitted; do not stage them.)

- [ ] **Step 2: Write the failing tests**

`app/src/test/java/com/kkc/sheettracker/data/SupplyLiveDocumentTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplyLiveDocumentTest {

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    @Test
    fun `parses a full document`() {
        val snapshot = parseSupplyLiveDocument(
            4L,
            json(
                """
                {"categories":[{"id":"c1","name":"Hardware","position":2}],
                 "schema":[{"id":"f1","key":"sku","label":"SKU","type":"text","builtin":true}],
                 "items":[{"id":"i1","categoryId":"c1","name":"Screws","notes":"n",
                           "fields":{"sku":"S1"},"customFields":{"x":"y"},
                           "attachmentIds":[{"id":"a1","originalName":"p.jpg","storedName":"a1.jpg"}],
                           "barcodes":["S1"],"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z",
                           "status":"LOW","statusBy":"Sam","statusAt":"2026-01-03T00:00:00Z"}],
                 "comments":{"i1":[{"id":"k1","author":"Sam","text":"hi","createdAt":"2026-01-01T00:00:00Z"}]}}
                """
            )
        )
        assertNotNull(snapshot)
        snapshot!!
        assertEquals(4L, snapshot.revision)
        assertEquals("Hardware", snapshot.categories.single().name)
        assertEquals(2, snapshot.categories.single().position)
        assertEquals("sku", snapshot.schema.single().key)
        val item = snapshot.items.getValue("i1")
        assertEquals("LOW", item.status)
        assertEquals("Sam", item.statusBy)
        assertEquals("S1", item.fields["sku"])
        assertEquals("a1.jpg", item.attachmentIds.single().storedName)
        assertEquals(listOf("S1"), item.barcodes)
        assertEquals("hi", snapshot.comments.getValue("i1").single().text)
    }

    @Test
    fun `missing and null fields are normalized`() {
        val snapshot = parseSupplyLiveDocument(
            1L,
            json("""{"categories":[],"items":[{"id":"i1","notes":null,"fields":null,"barcodes":null}]}""")
        )!!
        val item = snapshot.items.getValue("i1")
        assertEquals("", item.categoryId)
        assertEquals("", item.name)
        assertNull(item.notes)
        assertTrue(item.fields.isEmpty())
        assertTrue(item.customFields.isEmpty())
        assertTrue(item.attachmentIds.isEmpty())
        assertTrue(item.barcodes.isEmpty())
        assertEquals("IN STOCK", item.status)
        assertEquals("", item.statusBy)
        assertEquals("", item.statusAt)
        assertEquals("", item.updatedAt)
        assertTrue(snapshot.schema.isEmpty())
        assertTrue(snapshot.comments.isEmpty())
    }

    @Test
    fun `entries without ids and non-object entries are skipped`() {
        val snapshot = parseSupplyLiveDocument(
            1L,
            json(
                """{"categories":[{"name":"x"},7,{"id":"c1","name":"ok"}],
                    "schema":[{"id":"f1"},{"id":"f2","key":"k"}],
                    "items":[{"name":"no id"},"text",{"id":"i1"}],
                    "comments":{"i1":[{"text":"no id"},{"id":"k1"}],"i2":"not a list"}}"""
            )
        )!!
        assertEquals(listOf("c1"), snapshot.categories.map { it.id })
        assertEquals(listOf("f2"), snapshot.schema.map { it.id })
        assertEquals(setOf("i1"), snapshot.items.keys)
        assertEquals(listOf("k1"), snapshot.comments.getValue("i1").map { it.id })
        assertEquals(setOf("i1"), snapshot.comments.keys)
    }

    @Test
    fun `comments are re-sorted by parsed instant`() {
        val snapshot = parseSupplyLiveDocument(
            1L,
            json(
                """{"categories":[],"items":[{"id":"i1"}],
                    "comments":{"i1":[{"id":"b","createdAt":"2026-01-01T00:00:00.5Z"},
                                      {"id":"a","createdAt":"2026-01-01T00:00:00Z"}]}}"""
            )
        )!!
        assertEquals(listOf("a", "b"), snapshot.comments.getValue("i1").map { it.id })
    }

    @Test
    fun `invalid document shapes return null`() {
        assertNull(parseSupplyLiveDocument(1L, json("""{"categories":[],"items":{}}""")))
        assertNull(parseSupplyLiveDocument(1L, json("""{"items":[]}""")))
        assertNull(parseSupplyLiveDocument(1L, json("""{"categories":[],"items":[],"comments":[]}""")))
        assertNull(parseSupplyLiveDocument(1L, json("""{"categories":[],"items":[],"schema":{}}""")))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplyLiveDocumentTest"`
Expected: FAIL — compilation error `Unresolved reference: parseSupplyLiveDocument`.

- [ ] **Step 4: Implement**

`app/src/main/java/com/kkc/sheettracker/data/SupplyLiveDocument.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.kkc.sheettracker.data.models.SupplyAttachment
import com.kkc.sheettracker.data.models.SupplyCategory
import com.kkc.sheettracker.data.models.SupplyComment
import com.kkc.sheettracker.data.models.SupplyItem
import com.kkc.sheettracker.data.models.SupplySchemaField

/** One complete document from Hours Tracker's `/api/supply/live` socket. */
data class SupplyLiveSnapshot(
    val revision: Long,
    val categories: List<SupplyCategory>,
    val schema: List<SupplySchemaField>,
    val items: Map<String, SupplyItem>,
    val comments: Map<String, List<SupplyComment>>
)

/** Tablet comment order (AUD-10): parsed instant, then raw string. */
internal val SUPPLY_COMMENT_ORDER: Comparator<SupplyComment> =
    compareBy({ SupplyRepository.parseInstantOrMin(it.createdAt) }, { it.createdAt })

// Gson bypasses Kotlin constructors (absent keys become null even for non-null types), so the
// wire format is read into all-nullable DTOs and normalized explicitly below.
private data class LiveCategoryDto(val id: String?, val name: String?, val position: Int?)
private data class LiveSchemaFieldDto(
    val id: String?, val key: String?, val label: String?, val type: String?, val builtin: Boolean?
)
private data class LiveCommentDto(val id: String?, val author: String?, val text: String?, val createdAt: String?)
private data class LiveAttachmentDto(val id: String?, val originalName: String?, val storedName: String?)
private data class LiveItemDto(
    val id: String?,
    val categoryId: String?,
    val name: String?,
    val notes: String?,
    val fields: Map<String, String?>?,
    val customFields: Map<String, String?>?,
    val attachmentIds: List<LiveAttachmentDto?>?,
    val barcodes: List<String?>?,
    val createdAt: String?,
    val updatedAt: String?,
    val status: String?,
    val statusBy: String?,
    val statusAt: String?
)

private val liveGson = Gson()

private fun <T> JsonElement.decodeOrNull(type: Class<T>): T? =
    if (!isJsonObject) null else runCatching { liveGson.fromJson(this, type) }.getOrNull()

/**
 * Parses the `supply` object of a `snapshot`/`supply` frame. Returns null when the document shape
 * is invalid (`items`/`categories` missing or not arrays, `schema`/`comments` of the wrong type),
 * so the caller drops the frame and keeps its prior state. Malformed individual entries are skipped.
 */
internal fun parseSupplyLiveDocument(revision: Long, supply: JsonObject): SupplyLiveSnapshot? {
    val itemsJson = supply.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    val categoriesJson = supply.get("categories")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    val schemaElement = supply.get("schema")?.takeUnless { it.isJsonNull }
    if (schemaElement != null && !schemaElement.isJsonArray) return null
    val commentsElement = supply.get("comments")?.takeUnless { it.isJsonNull }
    if (commentsElement != null && !commentsElement.isJsonObject) return null

    val categories = categoriesJson.mapNotNull { element ->
        val dto = element.decodeOrNull(LiveCategoryDto::class.java) ?: return@mapNotNull null
        val id = dto.id ?: return@mapNotNull null
        SupplyCategory(id = id, name = dto.name ?: "", position = dto.position ?: 0)
    }

    val schema = schemaElement?.asJsonArray?.mapNotNull { element ->
        val dto = element.decodeOrNull(LiveSchemaFieldDto::class.java) ?: return@mapNotNull null
        val id = dto.id ?: return@mapNotNull null
        val key = dto.key ?: return@mapNotNull null
        SupplySchemaField(
            id = id,
            key = key,
            label = dto.label ?: key,
            type = dto.type ?: "text",
            builtin = dto.builtin ?: false
        )
    }.orEmpty()

    val items = LinkedHashMap<String, SupplyItem>()
    for (element in itemsJson) {
        val dto = element.decodeOrNull(LiveItemDto::class.java) ?: continue
        val id = dto.id ?: continue
        items[id] = SupplyItem(
            id = id,
            categoryId = dto.categoryId ?: "",
            name = dto.name ?: "",
            status = dto.status ?: "IN STOCK",
            statusBy = dto.statusBy ?: "",
            statusAt = dto.statusAt ?: "",
            notes = dto.notes,
            fields = dto.fields.orEmpty().mapValues { it.value ?: "" },
            customFields = dto.customFields.orEmpty().mapValues { it.value ?: "" },
            attachmentIds = dto.attachmentIds.orEmpty().mapNotNull { attachment ->
                val attachmentId = attachment?.id ?: return@mapNotNull null
                SupplyAttachment(attachmentId, attachment.originalName ?: "", attachment.storedName ?: "")
            },
            barcodes = dto.barcodes.orEmpty().filterNotNull(),
            createdAt = dto.createdAt ?: "",
            updatedAt = dto.updatedAt ?: ""
        )
    }

    val comments = HashMap<String, List<SupplyComment>>()
    commentsElement?.asJsonObject?.entrySet()?.forEach { (itemId, listElement) ->
        if (!listElement.isJsonArray) return@forEach
        val list = listElement.asJsonArray.mapNotNull { element ->
            val dto = element.decodeOrNull(LiveCommentDto::class.java) ?: return@mapNotNull null
            val commentId = dto.id ?: return@mapNotNull null
            SupplyComment(commentId, dto.author ?: "", dto.text ?: "", dto.createdAt ?: "")
        }.sortedWith(SUPPLY_COMMENT_ORDER)
        if (list.isNotEmpty()) comments[itemId] = list
    }

    return SupplyLiveSnapshot(revision, categories, schema, items, comments)
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplyLiveDocumentTest"`
Expected: 5 tests pass.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/data/SupplyLiveDocument.kt app/src/test/java/com/kkc/sheettracker/data/SupplyLiveDocumentTest.kt
git commit -m "feat(supply): parse live supply documents with null-safe normalization

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 7: SupplyLiveStateStore with pending overlay (KKCSheetTracker)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt:60-76` (extract `resolveStoredItem` into the companion)
- Create: `app/src/main/java/com/kkc/sheettracker/data/SupplyLiveStateStore.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/SupplyLiveStateStoreTest.kt`

**Interfaces:**
- Consumes: `SupplyLiveSnapshot`, `SUPPLY_COMMENT_ORDER` (Task 6); `SupplyRepository.parseInstantOrMin`.
- Produces:
  - `SupplyRepository.resolveStoredItem(stored: StoredSupplyItem, status: SupplyStatusRecord): SupplyItem` (internal, companion).
  - `class SupplyView` with `categories: List<SupplyCategory>`, `schema: List<SupplySchemaField>`, `items: List<SupplyItem>`, `fun item(itemId: String): SupplyItem?`, `fun comments(itemId: String): List<SupplyComment>`.
  - `class SupplyLiveStateStore(nowMs: () -> Long = System::currentTimeMillis)` with `version: StateFlow<Long>`, `liveConnected: Boolean`, `applyLive(SupplyLiveSnapshot)`, `setDisconnected()`, `view(): SupplyView?`, `recordStatus(itemId: String, record: SupplyStatusRecord)`, `recordCommentAdded(itemId: String, comment: SupplyComment)`, `recordCommentDeleted(itemId: String, commentId: String)`, `recordItemUpserted(item: StoredSupplyItem)`, `recordItemDeleted(itemId: String)`, `recordCategoryCreated(category: SupplyCategory)`; `companion object { const val OVERLAY_TTL_MS = 120_000L; val shared: SupplyLiveStateStore }`.

- [ ] **Step 1: Extract `resolveStoredItem` (refactor, no behaviour change)**

In `SupplyRepository.kt`, replace the body of `private fun StoredSupplyItem.resolveWith(s: SupplyStatusRecord): SupplyItem { ... }` with a delegation:

```kotlin
    private fun StoredSupplyItem.resolveWith(s: SupplyStatusRecord): SupplyItem = resolveStoredItem(this, s)
```

and add to the `companion object` (after `SUPPLY_STATUS_RECENCY`):

```kotlin
        /** Stored item + resolved status -> UI item. SKU is folded into barcodes (shared with the live overlay). */
        internal fun resolveStoredItem(stored: StoredSupplyItem, s: SupplyStatusRecord): SupplyItem {
            val skuVal = stored.fields["sku"]?.trim()?.takeIf { it.isNotBlank() }
            val resolvedBarcodes = if (skuVal != null) {
                (stored.barcodes + skuVal).distinct()
            } else {
                stored.barcodes
            }
            return SupplyItem(
                id = stored.id, categoryId = stored.categoryId, name = stored.name,
                status = s.status, statusBy = s.by, statusAt = s.at,
                notes = stored.notes, fields = stored.fields, customFields = stored.customFields,
                attachmentIds = stored.attachmentIds, barcodes = resolvedBarcodes,
                createdAt = stored.createdAt, updatedAt = stored.updatedAt
            )
        }
```

Add the import `import com.kkc.sheettracker.data.models.StoredSupplyItem` only if the file does not already import `models.*` (it does: `import com.kkc.sheettracker.data.models.*`).

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplyRepositoryTest"`
Expected: PASS (unchanged behaviour).

- [ ] **Step 2: Write the failing tests**

`app/src/test/java/com/kkc/sheettracker/data/SupplyLiveStateStoreTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.StoredSupplyItem
import com.kkc.sheettracker.data.models.SupplyCategory
import com.kkc.sheettracker.data.models.SupplyComment
import com.kkc.sheettracker.data.models.SupplyItem
import com.kkc.sheettracker.data.models.SupplyStatusRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplyLiveStateStoreTest {

    private var now = 1_000_000L
    private val store = SupplyLiveStateStore(nowMs = { now })

    private fun item(
        id: String = "i1",
        name: String = "Screws",
        status: String = "IN STOCK",
        statusAt: String = "",
        updatedAt: String = "2026-01-01T00:00:00Z"
    ) = SupplyItem(
        id = id, categoryId = "c1", name = name, status = status, statusBy = "", statusAt = statusAt,
        notes = null, fields = emptyMap(), customFields = emptyMap(), attachmentIds = emptyList(),
        barcodes = emptyList(), createdAt = "2026-01-01T00:00:00Z", updatedAt = updatedAt
    )

    private fun stored(id: String = "i1", name: String = "Screws", updatedAt: String) = StoredSupplyItem(
        id = id, categoryId = "c1", name = name, notes = null, fields = emptyMap(),
        customFields = emptyMap(), attachmentIds = emptyList(), barcodes = emptyList(),
        createdAt = "2026-01-01T00:00:00Z", updatedAt = updatedAt
    )

    private fun comment(id: String, createdAt: String = "2026-01-01T00:00:00Z") =
        SupplyComment(id, "Sam", "text $id", createdAt)

    private fun snapshot(
        revision: Long = 1L,
        items: List<SupplyItem> = listOf(item()),
        comments: Map<String, List<SupplyComment>> = emptyMap(),
        categories: List<SupplyCategory> = listOf(SupplyCategory("c1", "Hardware", 0))
    ) = SupplyLiveSnapshot(revision, categories, emptyList(), items.associateBy { it.id }, comments)

    @Test
    fun `view is null until a live snapshot arrives`() {
        assertNull(store.view())
        store.recordStatus("i1", SupplyStatusRecord("OUT", "Sam", "2026-01-02T00:00:00Z"))
        assertNull(store.view())
        assertFalse(store.liveConnected)
    }

    @Test
    fun `applyLive exposes the snapshot and bumps version`() {
        val before = store.version.value
        store.applyLive(snapshot())
        assertTrue(store.liveConnected)
        assertTrue(store.version.value > before)
        val view = store.view()!!
        assertEquals("Screws", view.item("i1")!!.name)
        assertEquals(listOf("c1"), view.categories.map { it.id })
    }

    @Test
    fun `setDisconnected clears the view`() {
        store.applyLive(snapshot())
        store.setDisconnected()
        assertFalse(store.liveConnected)
        assertNull(store.view())
    }

    @Test
    fun `second disconnect does not bump version`() {
        store.applyLive(snapshot())
        store.setDisconnected()
        val afterFirst = store.version.value
        store.setDisconnected()
        store.setDisconnected()
        assertEquals(afterFirst, store.version.value)
    }

    @Test
    fun `local status shows until live statusAt catches up`() {
        store.applyLive(snapshot(items = listOf(item(statusAt = "2026-01-01T00:00:00Z"))))
        store.recordStatus("i1", SupplyStatusRecord("OUT", "Sam", "2026-01-02T00:00:00Z"))
        assertEquals("OUT", store.view()!!.item("i1")!!.status)

        // Stale live snapshot: overlay still wins.
        store.applyLive(snapshot(revision = 2, items = listOf(item(statusAt = "2026-01-01T00:00:00Z"))))
        assertEquals("OUT", store.view()!!.item("i1")!!.status)

        // Server caught up: overlay entry is pruned.
        store.applyLive(snapshot(revision = 3, items = listOf(item(status = "OUT", statusAt = "2026-01-02T00:00:00Z"))))
        // Prove the entry is gone: an older live state now shows through.
        store.applyLive(snapshot(revision = 4, items = listOf(item(status = "IN STOCK", statusAt = "2026-01-01T00:00:00Z"))))
        assertEquals("IN STOCK", store.view()!!.item("i1")!!.status)
    }

    @Test
    fun `fractional live statusAt satisfies whole-second local status`() {
        store.applyLive(snapshot(items = listOf(item(statusAt = "2026-01-01T00:00:00Z"))))
        store.recordStatus("i1", SupplyStatusRecord("OUT", "Sam", "2026-01-02T00:00:00Z"))
        store.applyLive(snapshot(revision = 2, items = listOf(item(status = "LOW", statusAt = "2026-01-02T00:00:00.5Z"))))
        assertEquals("LOW", store.view()!!.item("i1")!!.status)
    }

    @Test
    fun `local comment add shows until live contains it`() {
        store.applyLive(snapshot(comments = mapOf("i1" to listOf(comment("k1", "2026-01-01T00:00:00Z")))))
        store.recordCommentAdded("i1", comment("k2", "2026-01-01T00:00:00.5Z"))
        assertEquals(listOf("k1", "k2"), store.view()!!.comments("i1").map { it.id })

        store.applyLive(snapshot(revision = 2, comments = mapOf("i1" to listOf(comment("k1"), comment("k2", "2026-01-01T00:00:00.5Z")))))
        store.applyLive(snapshot(revision = 3, comments = mapOf("i1" to listOf(comment("k1")))))
        assertEquals(listOf("k1"), store.view()!!.comments("i1").map { it.id })
    }

    @Test
    fun `local comment delete hides until live drops it`() {
        store.applyLive(snapshot(comments = mapOf("i1" to listOf(comment("k1")))))
        store.recordCommentDeleted("i1", "k1")
        assertTrue(store.view()!!.comments("i1").isEmpty())
        store.applyLive(snapshot(revision = 2, comments = mapOf("i1" to listOf(comment("k1")))))
        assertTrue(store.view()!!.comments("i1").isEmpty())
    }

    @Test
    fun `local comment add then delete is not resurrected`() {
        store.applyLive(snapshot())
        store.recordCommentAdded("i1", comment("k9"))
        store.recordCommentDeleted("i1", "k9")
        store.applyLive(snapshot(revision = 2))
        assertTrue(store.view()!!.comments("i1").isEmpty())
    }

    @Test
    fun `local item edit shows until live updatedAt catches up`() {
        store.applyLive(snapshot(items = listOf(item(name = "Screws", updatedAt = "2026-01-01T00:00:00Z", status = "LOW"))))
        store.recordItemUpserted(stored(name = "Wood screws", updatedAt = "2026-01-02T00:00:00Z"))
        val edited = store.view()!!.item("i1")!!
        assertEquals("Wood screws", edited.name)
        assertEquals("LOW", edited.status)

        store.applyLive(snapshot(revision = 2, items = listOf(item(name = "Wood screws", updatedAt = "2026-01-02T00:00:00Z"))))
        store.applyLive(snapshot(revision = 3, items = listOf(item(name = "Screws", updatedAt = "2026-01-01T00:00:00Z"))))
        assertEquals("Screws", store.view()!!.item("i1")!!.name)
    }

    @Test
    fun `new local item appears with its local status`() {
        store.applyLive(snapshot(items = emptyList()))
        store.recordItemUpserted(stored(id = "new", name = "Glue", updatedAt = "2026-01-02T00:00:00Z"))
        assertEquals("IN STOCK", store.view()!!.item("new")!!.status)
        store.recordStatus("new", SupplyStatusRecord("OUT", "Sam", "2026-01-02T00:00:00Z"))
        assertEquals("OUT", store.view()!!.item("new")!!.status)
        assertEquals(listOf("new"), store.view()!!.items.map { it.id })
    }

    @Test
    fun `local item delete hides until live drops it`() {
        store.applyLive(snapshot(comments = mapOf("i1" to listOf(comment("k1")))))
        store.recordItemDeleted("i1")
        assertNull(store.view()!!.item("i1"))
        assertTrue(store.view()!!.comments("i1").isEmpty())
        store.applyLive(snapshot(revision = 2, items = emptyList()))
        assertNull(store.view()!!.item("i1"))
    }

    @Test
    fun `local item create then delete is not resurrected`() {
        store.applyLive(snapshot(items = emptyList()))
        store.recordItemUpserted(stored(id = "tmp", updatedAt = "2026-01-02T00:00:00Z"))
        store.recordStatus("tmp", SupplyStatusRecord("OUT", "Sam", "2026-01-02T00:00:00Z"))
        store.recordItemDeleted("tmp")
        store.applyLive(snapshot(revision = 2, items = emptyList()))
        assertNull(store.view()!!.item("tmp"))
    }

    @Test
    fun `local category shows until live contains it`() {
        store.applyLive(snapshot())
        store.recordCategoryCreated(SupplyCategory("c2", "Glue", 1))
        assertEquals(listOf("c1", "c2"), store.view()!!.categories.map { it.id })
    }

    @Test
    fun `overlay entries expire after the TTL`() {
        store.applyLive(snapshot(items = listOf(item(statusAt = "2026-01-01T00:00:00Z"))))
        store.recordStatus("i1", SupplyStatusRecord("OUT", "Sam", "2026-01-02T00:00:00Z"))
        now += SupplyLiveStateStore.OVERLAY_TTL_MS
        assertEquals("IN STOCK", store.view()!!.item("i1")!!.status)
    }

    @Test
    fun `record calls bump version`() {
        val before = store.version.value
        store.recordCategoryCreated(SupplyCategory("c2", "Glue", 1))
        assertTrue(store.version.value > before)
        assertNotNull(SupplyLiveStateStore.shared)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplyLiveStateStoreTest"`
Expected: FAIL — compilation error `Unresolved reference: SupplyLiveStateStore`.

- [ ] **Step 4: Implement**

`app/src/main/java/com/kkc/sheettracker/data/SupplyLiveStateStore.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.StoredSupplyItem
import com.kkc.sheettracker.data.models.SupplyCategory
import com.kkc.sheettracker.data.models.SupplyComment
import com.kkc.sheettracker.data.models.SupplyItem
import com.kkc.sheettracker.data.models.SupplySchemaField
import com.kkc.sheettracker.data.models.SupplyStatusRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Merged supply read model served to [SupplyRepository] while the live socket is connected. */
class SupplyView internal constructor(
    val categories: List<SupplyCategory>,
    val schema: List<SupplySchemaField>,
    val items: List<SupplyItem>,
    private val commentsByItemId: Map<String, List<SupplyComment>>
) {
    private val itemsById = items.associateBy { it.id }

    fun item(itemId: String): SupplyItem? = itemsById[itemId]

    fun comments(itemId: String): List<SupplyComment> = commentsByItemId[itemId].orEmpty()
}

/**
 * Process-wide supply read model fed by [SupplyLiveClient]
 * (spec docs/superpowers/specs/2026-09-28-supply-live-websocket-design.md).
 *
 * [view] returns null whenever the socket is not live; callers then read `.supply` files as before.
 * Local writes are recorded in a pending overlay so this tablet's own change stays visible until the
 * server's document contains it (Syncthing has to deliver the file to the server first) or
 * [OVERLAY_TTL_MS] passes.
 */
class SupplyLiveStateStore(private val nowMs: () -> Long = System::currentTimeMillis) {

    private sealed class Pending {
        abstract val itemId: String?
        abstract val createdAtMs: Long

        data class Status(override val itemId: String, val record: SupplyStatusRecord, override val createdAtMs: Long) : Pending()
        data class CommentAdded(override val itemId: String, val comment: SupplyComment, override val createdAtMs: Long) : Pending()
        data class CommentDeleted(override val itemId: String, val commentId: String, override val createdAtMs: Long) : Pending()
        data class ItemUpserted(val item: StoredSupplyItem, override val createdAtMs: Long) : Pending() {
            override val itemId: String get() = item.id
        }
        data class ItemDeleted(override val itemId: String, override val createdAtMs: Long) : Pending()
        data class CategoryCreated(val category: SupplyCategory, override val createdAtMs: Long) : Pending() {
            override val itemId: String? get() = null
        }
    }

    private val lock = Any()
    private var live: SupplyLiveSnapshot? = null
    private val pending = mutableListOf<Pending>()

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    @Volatile
    var liveConnected: Boolean = false
        private set

    fun applyLive(snapshot: SupplyLiveSnapshot) {
        synchronized(lock) {
            live = snapshot
            liveConnected = true
            pending.removeAll { isSatisfied(it, snapshot) }
            _version.value += 1
        }
    }

    /** Idempotent: only a real live-to-disconnected transition bumps [version]. */
    fun setDisconnected() {
        synchronized(lock) {
            if (!liveConnected && live == null) return
            liveConnected = false
            live = null
            _version.value += 1
        }
    }

    fun view(): SupplyView? {
        synchronized(lock) {
            if (!liveConnected) return null
            val snapshot = live ?: return null
            val now = nowMs()
            pending.removeAll { now - it.createdAtMs >= OVERLAY_TTL_MS }
            return merge(snapshot, pending)
        }
    }

    fun recordStatus(itemId: String, record: SupplyStatusRecord) {
        add(Pending.Status(itemId, record, nowMs()))
    }

    fun recordCommentAdded(itemId: String, comment: SupplyComment) {
        add(Pending.CommentAdded(itemId, comment, nowMs()))
    }

    /** Also drops a pending add of the same comment, so a local add-then-delete is not resurrected. */
    fun recordCommentDeleted(itemId: String, commentId: String) {
        synchronized(lock) {
            pending.removeAll { it is Pending.CommentAdded && it.itemId == itemId && it.comment.id == commentId }
            pending += Pending.CommentDeleted(itemId, commentId, nowMs())
            _version.value += 1
        }
    }

    fun recordItemUpserted(item: StoredSupplyItem) {
        add(Pending.ItemUpserted(item, nowMs()))
    }

    /** Also drops every pending entry for the item, so a local create-then-delete is not resurrected. */
    fun recordItemDeleted(itemId: String) {
        synchronized(lock) {
            pending.removeAll { it.itemId == itemId }
            pending += Pending.ItemDeleted(itemId, nowMs())
            _version.value += 1
        }
    }

    fun recordCategoryCreated(category: SupplyCategory) {
        add(Pending.CategoryCreated(category, nowMs()))
    }

    private fun add(entry: Pending) {
        synchronized(lock) {
            pending += entry
            _version.value += 1
        }
    }

    private fun instant(value: String) = SupplyRepository.parseInstantOrMin(value)

    private fun isSatisfied(entry: Pending, snapshot: SupplyLiveSnapshot): Boolean = when (entry) {
        is Pending.Status ->
            snapshot.items[entry.itemId]?.let { instant(it.statusAt) >= instant(entry.record.at) } ?: false
        is Pending.CommentAdded ->
            snapshot.comments[entry.itemId].orEmpty().any { it.id == entry.comment.id }
        is Pending.CommentDeleted ->
            snapshot.comments[entry.itemId].orEmpty().none { it.id == entry.commentId }
        is Pending.ItemUpserted ->
            snapshot.items[entry.item.id]?.let { instant(it.updatedAt) >= instant(entry.item.updatedAt) } ?: false
        is Pending.ItemDeleted ->
            !snapshot.items.containsKey(entry.itemId)
        is Pending.CategoryCreated ->
            snapshot.categories.any { it.id == entry.category.id }
    }

    /** Applies pending entries oldest-first, so a later local write wins over an earlier one. */
    private fun merge(snapshot: SupplyLiveSnapshot, entries: List<Pending>): SupplyView {
        val items = LinkedHashMap(snapshot.items)
        val comments = HashMap(snapshot.comments)
        val categories = snapshot.categories.toMutableList()
        for (entry in entries) {
            when (entry) {
                is Pending.ItemUpserted -> {
                    val current = items[entry.item.id]
                    val status = if (current != null) {
                        SupplyStatusRecord(current.status, current.statusBy, current.statusAt)
                    } else {
                        SupplyStatusRecord("IN STOCK")
                    }
                    items[entry.item.id] = SupplyRepository.resolveStoredItem(entry.item, status)
                }
                is Pending.Status -> items[entry.itemId]?.let { current ->
                    items[entry.itemId] = current.copy(
                        status = entry.record.status,
                        statusBy = entry.record.by,
                        statusAt = entry.record.at
                    )
                }
                is Pending.ItemDeleted -> {
                    items.remove(entry.itemId)
                    comments.remove(entry.itemId)
                }
                is Pending.CommentAdded -> {
                    val list = comments[entry.itemId].orEmpty()
                    if (list.none { it.id == entry.comment.id }) {
                        comments[entry.itemId] = (list + entry.comment).sortedWith(SUPPLY_COMMENT_ORDER)
                    }
                }
                is Pending.CommentDeleted -> comments[entry.itemId]?.let { list ->
                    comments[entry.itemId] = list.filterNot { it.id == entry.commentId }
                }
                is Pending.CategoryCreated ->
                    if (categories.none { it.id == entry.category.id }) categories += entry.category
            }
        }
        return SupplyView(categories, snapshot.schema, items.values.toList(), comments)
    }

    companion object {
        const val OVERLAY_TTL_MS = 120_000L

        /** App-wide instance; every [SupplyRepository] reads through it by default. */
        val shared = SupplyLiveStateStore()
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplyLiveStateStoreTest" --tests "com.kkc.sheettracker.data.SupplyRepositoryTest"`
Expected: all pass (16 store tests + existing repository tests).

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/data/SupplyLiveStateStore.kt app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt app/src/test/java/com/kkc/sheettracker/data/SupplyLiveStateStoreTest.kt
git commit -m "feat(supply): add live supply state store with own-write overlay

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 8: SupplyLiveClient (KKCSheetTracker)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/SupplyLiveClient.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/SupplyLiveClientTest.kt`

**Interfaces:**
- Consumes: `AdminSyncConfig.getManualIp()` (suspend), `buildAdminSyncUrl(String?)` (existing, `AdminSyncConfig.kt:30`), `parseSupplyLiveDocument` (Task 6).
- Produces: `class SupplyLiveClient(config: AdminSyncConfig, tabletId: String, onSupply: (SupplyLiveSnapshot) -> Unit, onConnectionState: (Boolean) -> Unit, reconnectDelayMs: (Int) -> Long = ::nextSupplyLiveBackoffDelayMs, webSocketFactory: (Request, WebSocketListener) -> WebSocket = ...)` with `start()` and `stop()`; `internal fun nextSupplyLiveBackoffDelayMs(attempt: Int): Long`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/kkc/sheettracker/data/SupplyLiveClientTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SupplyLiveClientTest {

    private val doc = """{"categories":[],"items":[{"id":"i1","name":"Screws"}],"comments":{}}"""

    @Test
    fun `connects to supply live URL and sends hello with tablet id`() {
        val fakeSocket = mock<WebSocket>()
        val capturedRequest = AtomicReference<Request>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val client = client(fakeSocket, capturedRequest, capturedListener)

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onOpen(fakeSocket, mock())

        assertEquals("http://192.168.1.15:47821/api/supply/live", capturedRequest.get().url.toString())
        verify(fakeSocket).send("""{"type":"hello","tabletId":"tablet-7"}""")
        client.stop()
    }

    @Test
    fun `valid snapshot is dispatched and reports connected`() {
        val fakeSocket = mock<WebSocket>()
        val listener = AtomicReference<WebSocketListener>()
        val snapshots = CopyOnWriteArrayList<SupplyLiveSnapshot>()
        val states = CopyOnWriteArrayList<Boolean>()
        val client = client(fakeSocket, capturedListener = listener, onSupply = { snapshots.add(it) }, onConnectionState = { states.add(it) })

        client.start()
        waitUntil { listener.get() != null }
        listener.get().onMessage(fakeSocket, """{"type":"snapshot","revision":3,"supply":$doc}""")

        assertEquals(3L, snapshots.single().revision)
        assertEquals("Screws", snapshots.single().items.getValue("i1").name)
        assertEquals(listOf(true), states)
        client.stop()
    }

    @Test
    fun `supply frames apply after snapshot and stale revisions are dropped`() {
        val fakeSocket = mock<WebSocket>()
        val listener = AtomicReference<WebSocketListener>()
        val revisions = CopyOnWriteArrayList<Long>()
        val client = client(fakeSocket, capturedListener = listener, onSupply = { revisions.add(it.revision) })

        client.start()
        waitUntil { listener.get() != null }
        val l = listener.get()
        l.onMessage(fakeSocket, """{"type":"supply","revision":1,"supply":$doc}""")        // before snapshot: ignored
        l.onMessage(fakeSocket, """{"type":"snapshot","revision":2,"supply":$doc}""")
        l.onMessage(fakeSocket, """{"type":"snapshot","revision":5,"supply":$doc}""")      // duplicate snapshot: ignored
        l.onMessage(fakeSocket, """{"type":"supply","revision":2,"supply":$doc}""")        // stale: ignored
        l.onMessage(fakeSocket, """{"type":"supply","revision":3,"supply":$doc}""")
        l.onMessage(fakeSocket, """{"type":"supply","revision":-1,"supply":$doc}""")       // invalid revision: ignored

        assertEquals(listOf(2L, 3L), revisions)
        client.stop()
    }

    @Test
    fun `invalid payload is ignored and does not report connected`() {
        val fakeSocket = mock<WebSocket>()
        val listener = AtomicReference<WebSocketListener>()
        val snapshots = CopyOnWriteArrayList<SupplyLiveSnapshot>()
        val states = CopyOnWriteArrayList<Boolean>()
        val client = client(fakeSocket, capturedListener = listener, onSupply = { snapshots.add(it) }, onConnectionState = { states.add(it) })

        client.start()
        waitUntil { listener.get() != null }
        listener.get().onMessage(fakeSocket, """{"type":"snapshot","revision":1,"supply":{"categories":[],"items":{}}}""")
        listener.get().onMessage(fakeSocket, """not json""")

        assertTrue(snapshots.isEmpty())
        assertTrue(states.isEmpty())
        client.stop()
    }

    @Test
    fun `not_running reports disconnected`() {
        val fakeSocket = mock<WebSocket>()
        val listener = AtomicReference<WebSocketListener>()
        val states = CopyOnWriteArrayList<Boolean>()
        val client = client(fakeSocket, capturedListener = listener, onConnectionState = { states.add(it) })

        client.start()
        waitUntil { listener.get() != null }
        listener.get().onMessage(fakeSocket, """{"type":"not_running"}""")

        assertEquals(listOf(false), states)
        client.stop()
    }

    @Test
    fun `failure reports disconnected and reconnects`() {
        val fakeSocket = mock<WebSocket>()
        val listener = AtomicReference<WebSocketListener>()
        val connects = AtomicInteger(0)
        val states = CopyOnWriteArrayList<Boolean>()
        val client = SupplyLiveClient(
            config = configWithIp("192.168.1.15"),
            tabletId = "tablet-7",
            onSupply = {},
            onConnectionState = { states.add(it) },
            reconnectDelayMs = { 0L },
            webSocketFactory = { _, l ->
                listener.set(l)
                connects.incrementAndGet()
                fakeSocket
            }
        )

        client.start()
        waitUntil { connects.get() == 1 }
        listener.get().onFailure(fakeSocket, IOException("reset"), null)
        waitUntil { connects.get() == 2 }

        assertEquals(false, states.first())
        client.stop()
    }

    @Test
    fun `backoff doubles to a 30 second cap`() {
        assertEquals(1_000L, nextSupplyLiveBackoffDelayMs(0))
        assertEquals(2_000L, nextSupplyLiveBackoffDelayMs(1))
        assertEquals(30_000L, nextSupplyLiveBackoffDelayMs(5))
        assertEquals(30_000L, nextSupplyLiveBackoffDelayMs(50))
    }

    private fun client(
        fakeSocket: WebSocket,
        capturedRequest: AtomicReference<Request> = AtomicReference(),
        capturedListener: AtomicReference<WebSocketListener> = AtomicReference(),
        onSupply: (SupplyLiveSnapshot) -> Unit = {},
        onConnectionState: (Boolean) -> Unit = {}
    ) = SupplyLiveClient(
        config = configWithIp("192.168.1.15"),
        tabletId = "tablet-7",
        onSupply = onSupply,
        onConnectionState = onConnectionState,
        reconnectDelayMs = { 0L },
        webSocketFactory = { request, listener ->
            capturedRequest.set(request)
            capturedListener.set(listener)
            fakeSocket
        }
    )

    private fun configWithIp(ip: String): AdminSyncConfig = mock {
        onBlocking { getManualIp() } doReturn ip
    }

    private fun waitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                // The client stores the socket just after the factory returns; callbacks for a
                // socket that is not yet stored are ignored as stale. Give that assignment time.
                Thread.sleep(100L)
                return
            }
            Thread.sleep(20L)
        }
        throw AssertionError("Timed out waiting for condition")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplyLiveClientTest"`
Expected: FAIL — compilation error `Unresolved reference: SupplyLiveClient`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/kkc/sheettracker/data/SupplyLiveClient.kt`:

```kotlin
package com.kkc.sheettracker.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

internal fun nextSupplyLiveBackoffDelayMs(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

internal data class SupplyLiveEnvelope(
    val type: String? = null,
    val revision: Long? = null,
    val supply: JsonObject? = null,
    val message: String? = null
)

/**
 * Connects to Hours Tracker's read-only `/api/supply/live` WebSocket
 * (spec docs/superpowers/specs/2026-09-28-supply-live-websocket-design.md).
 * The socket carries complete supply documents: one initial `snapshot`, then `supply`
 * replacements. The connection counts as live only after a valid snapshot was delivered.
 * Structural twin of [DeliveryScheduleLiveClient].
 */
class SupplyLiveClient(
    private val config: AdminSyncConfig,
    private val tabletId: String,
    private val onSupply: (SupplyLiveSnapshot) -> Unit,
    private val onConnectionState: (connected: Boolean) -> Unit,
    private val reconnectDelayMs: (attempt: Int) -> Long = ::nextSupplyLiveBackoffDelayMs,
    private val webSocketFactory: (Request, WebSocketListener) -> WebSocket = { request, listener ->
        sharedClient.newWebSocket(request, listener)
    }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private var running = false
    private var generation = 0L
    private var socket: WebSocket? = null
    private var attempt = 0
    private var reconnectPending = false
    private var pendingJob: Job? = null

    companion object {
        private const val TAG = "SupplyLiveClient"
        private val gson = Gson()
        private val sharedClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    fun start() {
        val startGeneration: Long
        synchronized(lifecycleLock) {
            if (running) return
            running = true
            generation += 1
            startGeneration = generation
            attempt = 0
            reconnectPending = false
            pendingJob?.cancel()
            pendingJob = null
        }
        connectNow(startGeneration)
    }

    fun stop() {
        val socketToClose: WebSocket?
        synchronized(lifecycleLock) {
            running = false
            generation += 1
            reconnectPending = false
            pendingJob?.cancel()
            pendingJob = null
            socketToClose = socket
            socket = null
        }
        socketToClose?.close(1000, "client stop")
    }

    private fun connectNow(expectedGeneration: Long) {
        synchronized(lifecycleLock) {
            if (!isCurrentGenerationLocked(expectedGeneration)) return
            pendingJob = scope.launch {
                connectForGeneration(expectedGeneration)
            }
        }
    }

    private suspend fun connectForGeneration(expectedGeneration: Long) {
        try {
            val baseUrl = buildAdminSyncUrl(config.getManualIp())
            if (baseUrl == null) {
                Log.d(TAG, "No server IP configured; skipping connect")
                scheduleReconnect(expectedGeneration)
                return
            }
            val wsUrl = baseUrl.replaceFirst("http://", "ws://") + "/api/supply/live"
            val request = Request.Builder().url(wsUrl).build()
            val listener = Listener(expectedGeneration)
            synchronized(lifecycleLock) {
                if (!isCurrentGenerationLocked(expectedGeneration)) return
            }
            val newSocket = webSocketFactory(request, listener)
            val closeImmediately = synchronized(lifecycleLock) {
                if (!isCurrentGenerationLocked(expectedGeneration)) {
                    true
                } else {
                    socket = newSocket
                    false
                }
            }
            if (closeImmediately) newSocket.close(1000, "stale client lifecycle")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "connectNow failed", e)
            scheduleReconnect(expectedGeneration)
        }
    }

    private fun scheduleReconnect(expectedGeneration: Long) {
        val currentAttempt: Int
        synchronized(lifecycleLock) {
            if (!isCurrentGenerationLocked(expectedGeneration)) return
            if (reconnectPending) return
            reconnectPending = true
            currentAttempt = attempt++
        }
        val delayMs = reconnectDelayMs(currentAttempt)
        Log.d(TAG, "Scheduling reconnect: attempt=$currentAttempt delayMs=$delayMs")
        synchronized(lifecycleLock) {
            if (!isCurrentGenerationLocked(expectedGeneration) || !reconnectPending) return
            pendingJob = scope.launch {
                delay(delayMs)
                val shouldConnect = synchronized(lifecycleLock) {
                    if (!isCurrentGenerationLocked(expectedGeneration) || !reconnectPending) {
                        false
                    } else {
                        reconnectPending = false
                        true
                    }
                }
                if (shouldConnect) connectNow(expectedGeneration)
            }
        }
    }

    private fun isCurrentGenerationLocked(expectedGeneration: Long): Boolean =
        running && generation == expectedGeneration

    private fun isCurrentSocket(expectedGeneration: Long, callbackSocket: WebSocket): Boolean =
        synchronized(lifecycleLock) {
            isCurrentGenerationLocked(expectedGeneration) && socket === callbackSocket
        }

    private inner class Listener(
        private val listenerGeneration: Long
    ) : WebSocketListener() {
        private var receivedInitialSnapshot = false
        private var lastRevision: Long? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrentSocket(listenerGeneration, webSocket)) return
            webSocket.send(gson.toJson(mapOf("type" to "hello", "tabletId" to tabletId)))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrentSocket(listenerGeneration, webSocket)) return
            val envelope = runCatching {
                gson.fromJson(text, SupplyLiveEnvelope::class.java)
            }.getOrNull() ?: return

            when (envelope.type) {
                "snapshot", "supply" -> {
                    val revision = envelope.revision
                    if (revision == null || revision < 0L) return
                    if (envelope.type == "snapshot" && receivedInitialSnapshot) return
                    if (envelope.type == "supply" && !receivedInitialSnapshot) return
                    if (envelope.type == "supply" && revision <= (lastRevision ?: -1L)) return
                    val supplyJson = envelope.supply ?: return
                    val snapshot = parseSupplyLiveDocument(revision, supplyJson) ?: run {
                        Log.w(TAG, "Ignoring invalid ${envelope.type} frame revision=$revision")
                        return
                    }
                    if (!isCurrentSocket(listenerGeneration, webSocket)) return
                    lastRevision = revision
                    onSupply(snapshot)
                    if (envelope.type == "snapshot") {
                        synchronized(lifecycleLock) {
                            if (!isCurrentGenerationLocked(listenerGeneration) || socket !== webSocket) return
                            attempt = 0
                        }
                        receivedInitialSnapshot = true
                        onConnectionState(true)
                    }
                }
                "not_running", "error" -> {
                    if (isCurrentSocket(listenerGeneration, webSocket)) onConnectionState(false)
                }
                else -> Unit
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrentSocket(listenerGeneration, webSocket)) return
            synchronized(lifecycleLock) {
                if (!isCurrentGenerationLocked(listenerGeneration) || socket !== webSocket) return
                socket = null
            }
            onConnectionState(false)
            scheduleReconnect(listenerGeneration)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrentSocket(listenerGeneration, webSocket)) return
            Log.w(TAG, "Supply live socket failure", t)
            synchronized(lifecycleLock) {
                if (!isCurrentGenerationLocked(listenerGeneration) || socket !== webSocket) return
                socket = null
            }
            onConnectionState(false)
            scheduleReconnect(listenerGeneration)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplyLiveClientTest"`
Expected: 7 tests pass.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/data/SupplyLiveClient.kt app/src/test/java/com/kkc/sheettracker/data/SupplyLiveClientTest.kt
git commit -m "feat(supply): add supply live WebSocket client

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 9: SupplyRepository reads live state and records own writes (KKCSheetTracker)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/SupplyRepositoryTest.kt` (append tests)

**Interfaces:**
- Consumes: `SupplyLiveStateStore` / `SupplyView` (Task 7), `SupplyLiveSnapshot` (Task 6).
- Produces: `class SupplyRepository(basePath: String, liveStore: SupplyLiveStateStore = SupplyLiveStateStore.shared)`. Public method signatures unchanged.

- [ ] **Step 1: Write the failing tests**

Add these imports at the top of `SupplyRepositoryTest.kt` (the file already imports `Gson`, `File`, `Files`, `SupplyCategory`, `SupplyComment`, `SupplyStatusRecord`, `StoredSupplyItem`, `assertEquals`, `assertNotNull`, `assertTrue`; `createTempBasePath()` returns a `String` path):

```kotlin
import com.kkc.sheettracker.data.models.SupplyItem
import com.kkc.sheettracker.data.models.SupplySchemaField
import org.junit.Assert.assertNull
```

Then append inside `class SupplyRepositoryTest`:

```kotlin
    private fun liveItem(id: String = "i1", name: String = "Live Screws", statusAt: String = "2026-01-01T00:00:00Z") = SupplyItem(
        id = id, categoryId = "c1", name = name, status = "IN STOCK", statusBy = "", statusAt = statusAt,
        notes = null, fields = emptyMap(), customFields = emptyMap(), attachmentIds = emptyList(),
        barcodes = emptyList(), createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z"
    )

    private fun liveStoreWith(vararg items: SupplyItem, comments: Map<String, List<SupplyComment>> = emptyMap()): SupplyLiveStateStore =
        SupplyLiveStateStore().apply {
            applyLive(
                SupplyLiveSnapshot(
                    revision = 1L,
                    categories = listOf(SupplyCategory("c1", "Live Hardware", 0)),
                    schema = listOf(SupplySchemaField("f1", "sku", "SKU", "text", true)),
                    items = items.associateBy { it.id },
                    comments = comments
                )
            )
        }

    @Test
    fun readsComeFromLiveStoreWhileConnected() {
        val basePath = createTempBasePath()   // no .supply files at all
        val store = liveStoreWith(liveItem(), comments = mapOf("i1" to listOf(SupplyComment("k1", "Sam", "hi", "2026-01-01T00:00:00Z"))))
        val repository = SupplyRepository(basePath, store)

        assertEquals(listOf("Live Screws"), repository.getItems().map { it.name })
        assertEquals("Live Screws", repository.getItem("i1")?.name)
        assertEquals(listOf("hi"), repository.getComments("i1").map { it.text })
        assertEquals(listOf("Live Hardware"), repository.getCategories().map { it.name })
        assertEquals(listOf("sku"), repository.getSchema().map { it.key })
    }

    @Test
    fun readsFallBackToFilesWhenNotLive() {
        val basePath = createTempBasePath()
        val itemsDir = File(basePath, ".supply/items").apply { mkdirs() }
        val stored = StoredSupplyItem(
            id = "i1", categoryId = "c1", name = "File Screws", notes = null,
            createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z"
        )
        File(itemsDir, "i1.json").writeText(gson.toJson(stored))
        val store = liveStoreWith(liveItem())
        store.setDisconnected()

        val repository = SupplyRepository(basePath, store)

        assertEquals(listOf("File Screws"), repository.getItems().map { it.name })
    }

    @Test
    fun ownStatusWriteIsVisibleImmediatelyWhileLive() {
        val basePath = createTempBasePath()
        val store = liveStoreWith(liveItem(statusAt = "2026-01-01T00:00:00Z"))
        val repository = SupplyRepository(basePath, store)

        repository.setStatus("i1", "OUT", "Sam", "tab1")

        assertEquals("OUT", repository.getItem("i1")?.status)
        assertTrue(File(basePath, ".supply/status/i1.tab1.json").exists())
    }

    @Test
    fun ownCommentIsVisibleImmediatelyWhileLive() {
        val basePath = createTempBasePath()
        val store = liveStoreWith(liveItem())
        val repository = SupplyRepository(basePath, store)

        val comment = repository.addComment("i1", "Sam", "restocked", "tab1")

        assertEquals(listOf(comment.id), repository.getComments("i1").map { it.id })
    }

    @Test
    fun ownItemEditIsVisibleImmediatelyWhileLive() {
        val basePath = createTempBasePath()
        val itemsDir = File(basePath, ".supply/items").apply { mkdirs() }
        val stored = StoredSupplyItem(
            id = "i1", categoryId = "c1", name = "Live Screws", notes = null,
            createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z"
        )
        File(itemsDir, "i1.json").writeText(gson.toJson(stored))
        val store = liveStoreWith(liveItem())
        val repository = SupplyRepository(basePath, store)

        repository.updateItem("i1", "Wood Screws", "c1", null, emptyMap())

        assertEquals("Wood Screws", repository.getItem("i1")?.name)
    }

    @Test
    fun ownDeleteHidesItemWhileLive() {
        val basePath = createTempBasePath()
        val itemsDir = File(basePath, ".supply/items").apply { mkdirs() }
        File(itemsDir, "i1.json").writeText(
            gson.toJson(StoredSupplyItem(id = "i1", categoryId = "c1", name = "x", notes = null))
        )
        val store = liveStoreWith(liveItem())
        val repository = SupplyRepository(basePath, store)

        repository.deleteItem("i1")

        assertNull(repository.getItem("i1"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplyRepositoryTest"`
Expected: FAIL — compilation error (`SupplyRepository` has no second constructor parameter).

- [ ] **Step 3: Implement — constructor and reads**

In `SupplyRepository.kt`:

```kotlin
class SupplyRepository(
    private val basePath: String,
    // Live read model (spec 2026-09-28-supply-live-websocket-design). When view() is null
    // (socket down, never connected) every read below uses the .supply files as before.
    private val liveStore: SupplyLiveStateStore = SupplyLiveStateStore.shared
) {
```

Replace the five public reads with:

```kotlin
    fun getCategories(): List<SupplyCategory> {
        liveStore.view()?.let { return it.categories }
        return readJson<List<SupplyCategory>>(File(supplyDir, "categories.json")) ?: emptyList()
    }

    fun getSchema(): List<SupplySchemaField> {
        liveStore.view()?.let { return it.schema }
        return readJson<List<SupplySchemaField>>(File(supplyDir, "schema.json")) ?: emptyList()
    }

    fun getItems(): List<SupplyItem> {
        liveStore.view()?.let { return it.items }
        if (!itemsDir.exists()) return emptyList()
        // List the status directory once and reuse it across all items, instead of
        // re-listing it inside resolve()/resolveStatus() for every item.
        val statusFiles = statusDir.listFiles()?.toList().orEmpty()
        return itemsDir.listFiles { f -> f.extension == "json" && !f.name.contains(".sync-conflict-") }
            ?.mapNotNull { file ->
                val stored = readJson<StoredSupplyItem>(file) ?: return@mapNotNull null
                stored.resolveWith(resolveStatusFrom(stored.id, statusFiles))
            }
            ?: emptyList()
    }

    fun getItem(itemId: String): SupplyItem? {
        liveStore.view()?.let { return it.item(itemId) }
        return readJson<StoredSupplyItem>(File(itemsDir, "$itemId.json"))?.resolve()
    }

    fun getComments(itemId: String): List<SupplyComment> {
        liveStore.view()?.let { return it.comments(itemId) }
        val dir = File(commentsDir, itemId)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" && !f.name.contains(".sync-conflict-") }
            ?.mapNotNull { readJson<SupplyComment>(it) }
            // AUD-10: parsed-Instant ordering (see resolveStatusFrom) so fractional-second
            // timestamps sort correctly relative to whole-second ones.
            ?.sortedWith(compareBy({ parseInstantOrMin(it.createdAt) }, { it.createdAt }))
            ?: emptyList()
    }
```

- [ ] **Step 4: Implement — record own writes**

In the write methods, after each successful file write, add the matching call (keep all existing comments and code):

`setStatus`:
```kotlin
    fun setStatus(itemId: String, status: String, by: String, tabletId: String) {
        statusDir.mkdirs()
        val file = File(statusDir, "$itemId.$tabletId.json")
        val record = SupplyStatusRecord(status, by, java.time.Instant.now().toString())
        // AUD-10: atomic write so a concurrent reader (getItems(), a peer tablet via Syncthing,
        // or the Hours backend) never observes a truncated status file and falls back to
        // "IN STOCK".
        atomicWriteFile(file, gson.toJson(record))
        liveStore.recordStatus(itemId, record)
    }
```

`addComment` — after `atomicWriteFile(File(dir, "$id.json"), gson.toJson(comment))`:
```kotlin
        liveStore.recordCommentAdded(itemId, comment)
```

`createCategory` — inside the `synchronized` block, after `atomicWriteFile(...)` and before `return cat`:
```kotlin
            liveStore.recordCategoryCreated(cat)
```

`createItem` — after `atomicWriteFile(File(itemsDir, "$id.json"), gson.toJson(stored))`:
```kotlin
        liveStore.recordItemUpserted(stored)
```

`updateItem`, `addAttachment`, `updateItemBarcodes` — after each `atomicWriteFile(..., gson.toJson(updated))`:
```kotlin
        liveStore.recordItemUpserted(updated)
```

`deleteItem` — before `return deletedItem`:
```kotlin
        if (deletedItem) liveStore.recordItemDeleted(itemId)
```

`deleteComment`:
```kotlin
    fun deleteComment(itemId: String, commentId: String): Boolean {
        val file = File(File(commentsDir, itemId), "$commentId.json")
        val deleted = if (file.exists()) file.delete() else false
        if (deleted) liveStore.recordCommentDeleted(itemId, commentId)
        return deleted
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplyRepositoryTest" --tests "com.kkc.sheettracker.data.SupplySubscriptionManagerTest" --tests "com.kkc.sheettracker.data.SupplyLiveStateStoreTest"`
Expected: all pass. (`SupplySubscriptionManagerTest` uses the default `shared` store, which never goes live in unit tests, so it still exercises the file path.)

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt app/src/test/java/com/kkc/sheettracker/data/SupplyRepositoryTest.kt
git commit -m "feat(supply): read supply from live state when connected, overlay own writes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 10: Rescan subscriptions on live changes (KKCSheetTracker)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/SupplySubscriptionManager.kt:23-52`
- Test: `app/src/test/java/com/kkc/sheettracker/data/SupplySubscriptionManagerTest.kt` (append a test)

**Interfaces:**
- Consumes: `SupplyLiveStateStore.version`, `SupplyLiveStateStore.shared` (Task 7).
- Produces: `class SupplySubscriptionManager(context: Context, repository: SupplyRepository, liveStore: SupplyLiveStateStore = SupplyLiveStateStore.shared)`; `companion object { const val LIVE_RESCAN_DEBOUNCE_MS = 250L }`.

- [ ] **Step 1: Write the failing test**

Append inside `class SupplySubscriptionManagerTest` (add imports `com.kkc.sheettracker.data.models.SupplySchemaField` if not covered — the test below does not need it):

```kotlin
    @Test
    fun `live push updates the notification count without any supply files`() = runBlocking {
        val emptyBase = tempFolder.newFolder("liveBase")   // no .supply directory at all
        val liveStore = SupplyLiveStateStore()
        val liveManager = SupplySubscriptionManager(context, SupplyRepository(emptyBase.absolutePath, liveStore), liveStore)
        liveManager.initDeferred.await()
        liveManager.toggleItemSubscription("i1")
        assertEquals(0, liveManager.notificationCount.value)

        liveStore.applyLive(
            SupplyLiveSnapshot(
                revision = 1L,
                categories = listOf(SupplyCategory("cat1", "Hardware", 0)),
                schema = emptyList(),
                items = mapOf(
                    "i1" to SupplyItem(
                        id = "i1", categoryId = "cat1", name = "Screws", status = "IN STOCK", statusBy = "",
                        statusAt = "", notes = null, fields = emptyMap(), customFields = emptyMap(),
                        attachmentIds = emptyList(), barcodes = emptyList(),
                        createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z"
                    )
                ),
                comments = emptyMap()
            )
        )

        val deadline = System.currentTimeMillis() + 3_000L
        while (liveManager.notificationCount.value != 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
        }
        assertEquals(1, liveManager.notificationCount.value)
        liveManager.close()
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplySubscriptionManagerTest"`
Expected: FAIL — compilation error (no third constructor parameter).

- [ ] **Step 3: Implement**

In `SupplySubscriptionManager.kt`, change the class header and `init`:

```kotlin
@OptIn(kotlinx.coroutines.FlowPreview::class)
class SupplySubscriptionManager(
    private val context: Context,
    private val repository: SupplyRepository,
    // While the supply live socket is connected, every push bumps version and the scan below
    // runs against in-memory state; a disconnect also bumps it once, giving one fallback file
    // scan. Spec: docs/superpowers/specs/2026-09-28-supply-live-websocket-design.md.
    private val liveStore: SupplyLiveStateStore = SupplyLiveStateStore.shared
) {
```

```kotlin
    init {
        scope.launch {
            try {
                val data = loadData()
                _subscriptionData.value = data
                scanForUpdatesInternal()
                initDeferred.complete(Unit)
            } catch (t: Throwable) {
                initDeferred.completeExceptionally(t)
            }
        }
        scope.launch {
            initDeferred.join()
            liveStore.version
                .drop(1)
                .debounce(LIVE_RESCAN_DEBOUNCE_MS)
                .collect {
                    try {
                        scanForUpdates()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        logError("Live supply rescan failed", t)
                    }
                }
        }
    }
```

Add imports (the file uses `kotlinx.coroutines.*`, which already covers `CancellationException`):

```kotlin
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
```

Add to the companion object:

```kotlin
        const val LIVE_RESCAN_DEBOUNCE_MS = 250L
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplySubscriptionManagerTest"`
Expected: all pass, including the new test.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/data/SupplySubscriptionManager.kt app/src/test/java/com/kkc/sheettracker/data/SupplySubscriptionManagerTest.kt
git commit -m "feat(supply): rescan subscriptions from live supply pushes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 11: Wire the live client into the app (KKCSheetTracker)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt` (field ~line 102; creation after line 305; `onStart` ~629; `onStop` ~646; `onDestroy` ~670)
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt` (two `TODO(supply-scan)` blocks, ~lines 902–908 and ~2768–2773)
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt` (after the `DisposableEffect(lifecycleOwner, active)` block ending ~line 408)
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemDetailScreen.kt` (`loadData` ~line 124, `LaunchedEffect(itemId)` line 149)

**Interfaces:**
- Consumes: `SupplyLiveClient` (Task 8), `SupplyLiveStateStore.shared` (Task 7), `AdminSyncConfig.create(Context)` (existing).
- Produces: nothing new for later tasks.

- [ ] **Step 1: MainActivity — own the client**

Add imports next to the other `com.kkc.sheettracker.data.*` imports:

```kotlin
import com.kkc.sheettracker.data.AdminSyncConfig
import com.kkc.sheettracker.data.SupplyLiveClient
import com.kkc.sheettracker.data.SupplyLiveStateStore
```

(Skip any that already exist.)

Next to `private lateinit var supplySubscriptionManager: SupplySubscriptionManager` add:

```kotlin
    private var supplyLiveClient: SupplyLiveClient? = null
```

Directly after `supplySubscriptionManager = SupplySubscriptionManager(applicationContext, supplyRepository)` add:

```kotlin
        // Read-only supply feed from Hours Tracker; SupplyRepository falls back to .supply files
        // whenever the store is not live (spec 2026-09-28-supply-live-websocket-design).
        supplyLiveClient = SupplyLiveClient(
            config = AdminSyncConfig.create(applicationContext),
            tabletId = tabletId,
            onSupply = { SupplyLiveStateStore.shared.applyLive(it) },
            onConnectionState = { connected ->
                if (!connected) SupplyLiveStateStore.shared.setDisconnected()
            }
        )
```

In `onStart()`, after `super.onStart()`:

```kotlin
        supplyLiveClient?.start()
```

In `onStop()`, before `super.onStop()`:

```kotlin
        supplyLiveClient?.stop()
        SupplyLiveStateStore.shared.setDisconnected()
```

In `onDestroy()`, before `super.onDestroy()`:

```kotlin
        supplyLiveClient?.stop()
```

The existing `onResume` scan stays unchanged.

- [ ] **Step 2: NavGraph — only file-scan when not live**

Add import:

```kotlin
import com.kkc.sheettracker.data.SupplyLiveStateStore
```

In BOTH `LaunchedEffect(watcherRefreshEpoch, basePath)` blocks, replace:

```kotlin
        // TODO(supply-scan, decided 2026-09-23): this reads every supply item + comments on EVERY
        // watcher refresh, on any screen, and shows up as bursts of ~10-18% of one core on the idle
        // coroutine pool. It only exists to keep the Supply nav badge / dashboard widget count fresh.
        // Owner is designing a cheaper way to keep that count current; left as-is until then.
        supplySubscriptionManager.scanForUpdates()
```

with:

```kotlin
        // Supply badge freshness: while the supply live socket is connected, SupplySubscriptionManager
        // rescans from in-memory state on every push (spec 2026-09-28-supply-live-websocket-design).
        // This full .supply file scan only runs as the fallback when the socket is down.
        if (!SupplyLiveStateStore.shared.liveConnected) {
            supplySubscriptionManager.scanForUpdates()
        }
```

- [ ] **Step 3: Supply dashboard — reload on live change**

Add imports:

```kotlin
import com.kkc.sheettracker.data.SupplyLiveStateStore
import kotlinx.coroutines.flow.drop
```

Directly after the `DisposableEffect(lifecycleOwner, active) { ... }` block that reloads on resume, add:

```kotlin
    // Another tablet or the Hours Tracker admin changed supply data: reload quietly from the
    // live store (in-memory while connected).
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        SupplyLiveStateStore.shared.version.drop(1).collectLatest {
            loadData(showLoading = false)
            reloadUpdates()
        }
    }
```

- [ ] **Step 4: Supply item detail — reload on live change**

Add imports:

```kotlin
import com.kkc.sheettracker.data.SupplyLiveStateStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
```

Change `fun loadData() {` to take a flag and only show the spinner when asked:

```kotlin
    fun loadData(showLoading: Boolean = true) {
        coroutineScope.launch {
            if (showLoading) isLoading = true
            errorMessage = null
```

(the rest of the function body is unchanged).

Directly after `LaunchedEffect(itemId) { loadData() }` add:

```kotlin
    LaunchedEffect(itemId) {
        SupplyLiveStateStore.shared.version.drop(1).collectLatest { loadData(showLoading = false) }
    }
```

- [ ] **Step 5: Compile and run the unit suite**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: all tests pass except the known off-device PdfMarkup MotionEvent test.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/kkc/sheettracker/MainActivity.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemDetailScreen.kt
git commit -m "feat(supply): connect the supply live feed and drop the idle file scan while live

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 12: Docs, release, field verification

**Files:**
- Modify: `C:\Users\chadc\.claude\skills\kkc-metadata-map\SKILL.md` (canonical copy; a PostToolUse hook syncs and commits the repo mirrors)
- Modify: `C:\Scripts\Hours Tracker\METADATA_AUDIT.md`
- Modify: `C:\Users\chadc\.claude\projects\C--Scripts-KKCSheetTracker\memory\supply_scan_todo.md` and `MEMORY.md`
- Modify: `app/build.gradle.kts:23-24` (version bump)

**Interfaces:**
- Consumes: everything above.
- Produces: shipped feature.

- [ ] **Step 1: Metadata map — ownership row**

In the canonical `SKILL.md` Ownership Map, directly after the `Y:\Ready Jobs\.supply\attachments\<itemId>\*` row, add:

```markdown
| Hours Tracker `ws://<hub>/api/supply/live` (read-only WebSocket, not a file) | Hours Tracker (`routes/supply_live.py`; `SupplyLiveMonitor` polls `.supply` every 2 s and `routes/supply_live_document.py` builds the resolved read model read-only); KKCSheetTracker reads via `SupplyLiveClient` → `SupplyLiveStateStore` | Publishes categories, schema, items with resolved status, and comments. Tablet writes to `.supply` are UNCHANGED (files + Syncthing); a tablet's own writes show via a 2-minute local overlay until the server echoes them. Tablets read `.supply` files whenever the socket is down. Stale on one tablet only: logcat tag `SupplyLiveClient`. Stale everywhere: HT logs "Supply document build failed" / "Supply tree unavailable" |
```

In the Code Entry Points table add:

```markdown
| How does the tablet get live supply data? | `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\data\SupplyLiveClient.kt`, `SupplyLiveStateStore.kt`; server `C:\Scripts\Hours Tracker\backend\routes\supply_live.py`, `supply_live_document.py` |
```

- [ ] **Step 2: METADATA_AUDIT.md**

Run `Select-String -Path "C:\Scripts\Hours Tracker\METADATA_AUDIT.md" -Pattern "\.supply" | Select-Object -First 5` to find the supply section, and add this paragraph at the end of that section:

```markdown
**Live read path (2026-09-28).** Hours Tracker publishes a read-only `/api/supply/live`
WebSocket built from `.supply` by `routes/supply_live_document.py` (never writes: no
`ensure_dirs()`, no lock sidecars, no `.corrupt` backups; unreadable item/status/comment files
are skipped, unreadable `categories.json`/`schema.json` keep the last good document).
KKCSheetTracker reads supply from it while connected and from files otherwise. Write ownership
for every `.supply` path above is unchanged.
```

- [ ] **Step 3: Commit the Hours Tracker doc**

```powershell
cd "C:\Scripts\Hours Tracker"
git add METADATA_AUDIT.md
git commit -m "docs(audit): record the read-only supply live feed

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 4: Update the memory note**

Replace the body of `supply_scan_todo.md` with a note that the fix is implemented on `feat/supply-live` in both repos (2026-09-28), not yet deployed or field-verified, and that the note should be deleted once Step 8 passes. Keep its frontmatter `name`; update `description` to "Supply idle scan replaced by /api/supply/live (2026-09-28); pending deploy + field verification". Update the matching `MEMORY.md` line.

- [ ] **Step 5: Bump the tablet version**

In `app/build.gradle.kts`, bump the patch version the same way as commit `336b7d6a` (`versionCode = 80603` → `80604`, `versionName = "8.6.3"` → `"8.6.4"`), then:

```powershell
cd C:\Scripts\KKCSheetTracker
git add app/build.gradle.kts
git commit -m "chore(version): bump patch to 8.6.4

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Deploy Hours Tracker (user action)**

The Hours Tracker server runs on the user's TrueNAS host, outside agent reach. Ask the user to merge `feat/supply-live` and redeploy Hours Tracker their usual way, then confirm with them that the container log shows no "Supply live monitor initial hydration failed" warning.

- [ ] **Step 7: Build and install the tablet release**

```powershell
.\gradlew.bat assembleRelease
adb devices -l
adb install -r app\build\outputs\apk\release\app-release.apk
```

Expected: `Success` for each connected tablet.

- [ ] **Step 8: Field verification (with the user)**

Ask the user to leave a tablet on a non-supply screen, then:
1. `adb logcat -d -s SupplyLiveClient` shows no repeating failures; the socket connected.
2. Change a supply item's status in the Hours Tracker web admin; the Supply nav badge on that tablet (subscribed to the item) updates within about 3 s without leaving the screen.
3. Idle CPU: repeat the 2026-09-23 idle profiling method; the supply scan no longer appears in samples.
4. Ask the user to stop the Hours Tracker container briefly; the Supply screen still loads (file fallback) and the badge still updates after a refresh; after restart the socket reconnects (logcat).

Report results faithfully. Only after all four pass, delete `supply_scan_todo.md` and its `MEMORY.md` line.
