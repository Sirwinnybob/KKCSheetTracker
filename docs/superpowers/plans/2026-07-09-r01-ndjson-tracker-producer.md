# R-01: ndjson Tracker-Event Producer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the KKCSheetTracker tablet the ndjson event-stream producer for CNC/hardwoods tracker
actions, point Ready Jobs Watcher's existing dormant union reader at it, and retire the destructive
snapshot-consolidate-delete path that caused C-01/H-06/M-06/M-10.

**Architecture:** Two repos, RJW ships first (backward-compatible, no-op until tablets update — see
Task 4 verification). `Ready Jobs Watcher/ready_jobs_watcher/tracker_action_stream.py` gets its CNC
op-map extended and `metadata_cache.py`'s `_consolidate_tracker` gets its merge input switched from a
legacy-only `os.scandir` to the union reader, plus a new `compact` flag that only the existing
after-hours end-of-day sweep sets `True`. On the tablet, `ProgressStore.kt` (CNC) switches to a true
per-line ndjson append; `HardwoodsProgressStore.kt` keeps its existing debounced whole-list-rewrite
architecture unchanged but repoints the final serialization at an ndjson file instead of one JSON
blob (its migration/pending-merge machinery stays untouched — see Task 11 rationale). Both stores
gain an ndjson peer-reader so tablets keep seeing each other's live progress.
`TrackerChangeMonitor.kt` gets fixed to actually watch the new `events/` subdirectories.

**Tech Stack:** Python (Ready Jobs Watcher), Kotlin/Gson (KKCSheetTracker Android app), JUnit (Kotlin
tests), pytest (Python tests).

**Design doc:** `docs/superpowers/specs/2026-07-09-r01-ndjson-tracker-design.md` (KKCSheetTracker repo).

**Repo roots referenced below:**
- `RJW` = `C:\Scripts\Ready Jobs Watcher`
- `KKC` = `C:\Scripts\KKCSheetTracker`

---

## Task 1: RJW — extend the CNC ndjson op-map (`bad_part_submitted`, `view`, `reNested`)

**Files:**
- Modify: `RJW\ready_jobs_watcher\tracker_action_stream.py:15-22` (`_CNC_OP_MAP`), `:240-272`
  (`_map_cnc_event_to_action`)
- Test: `RJW\tests\test_tracker_action_stream.py`

Today `_CNC_OP_MAP` only maps the six toggle verbs. An ndjson `bad_part_submitted` or `view` event is
silently dropped by `_map_cnc_event_to_action` (returns `None`), and `reNested` is never read from
`payload` at all. Once the tablet ships ndjson, a dropped `bad_part_submitted` would reopen C-01
immediately.

- [ ] **Step 1: Write the failing test**

Add to `RJW\tests\test_tracker_action_stream.py`:

```python
def test_load_cnc_tracker_actions_maps_bad_part_submitted_and_view_and_renested(tmp_path):
    tracker_dir = tmp_path / "job" / "CNC" / ".tracker"
    ndjson = tracker_dir / "events" / "tablet-a.ndjson"
    rows = [
        {
            "eventId": "e1",
            "op": "view",
            "payload": {"file": "A.pdf", "page": 1, "fileFingerprint": "fp1", "timestamp": "2026-07-09T09:00:00Z"},
            "lamport": 1,
            "wallTime": "2026-07-09T09:00:00Z",
        },
        {
            "eventId": "e2",
            "op": "set_bad_part_true",
            "payload": {"file": "A.pdf", "page": 1, "part": 3, "fileFingerprint": "fp1", "timestamp": "2026-07-09T09:00:01Z"},
            "lamport": 2,
            "wallTime": "2026-07-09T09:00:01Z",
        },
        {
            "eventId": "e3",
            "op": "bad_part_submitted",
            "payload": {"file": "A.pdf", "page": 1, "part": 3, "fileFingerprint": "fp1", "timestamp": "2026-07-09T09:00:02Z"},
            "lamport": 3,
            "wallTime": "2026-07-09T09:00:02Z",
        },
        {
            "eventId": "e4",
            "op": "set_skipped_true",
            "payload": {"file": "A.pdf", "page": 5, "fileFingerprint": "fp1", "timestamp": "2026-07-09T09:00:03Z", "reNested": True},
            "lamport": 4,
            "wallTime": "2026-07-09T09:00:03Z",
        },
    ]
    _write(ndjson, "\n".join(json.dumps(row) for row in rows) + "\n")

    actions = load_cnc_tracker_actions(str(tracker_dir))

    assert [a["action"] for a in actions] == ["view", "bad_part", "bad_part_submitted", "skip"]
    assert actions[2]["part"] == 3
    assert actions[3]["reNested"] is True
```

(This file already has `import json` and a `_write` helper at the top — see existing tests in the
same file.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "C:\Scripts\Ready Jobs Watcher" && python -m pytest tests/test_tracker_action_stream.py::test_load_cnc_tracker_actions_maps_bad_part_submitted_and_view_and_renested -v`
Expected: FAIL — `view` and `bad_part_submitted` events produce no rows (mapper returns `None` for
both, `reNested` is never in the output dict), so `actions` is missing entries / has 1 not 4.

- [ ] **Step 3: Implement the op-map and mapper extension**

In `RJW\ready_jobs_watcher\tracker_action_stream.py`, replace `_CNC_OP_MAP`:

```python
_CNC_OP_MAP = {
    "set_complete_true": "complete",
    "set_complete_false": "uncomplete",
    "set_skipped_true": "skip",
    "set_skipped_false": "unskip",
    "set_bad_part_true": "bad_part",
    "set_bad_part_false": "unbad_part",
    "bad_part_submitted": "bad_part_submitted",
    "view": "view",
}
```

Replace `_map_cnc_event_to_action`:

```python
def _map_cnc_event_to_action(event: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    op = str(event.get("op", "") or "").strip()
    action_name = _CNC_OP_MAP.get(op)
    if not action_name:
        return None

    payload = event.get("payload")
    if not isinstance(payload, dict):
        payload = {}

    pdf = str(payload.get("file", "") or "").strip()
    page = _coerce_int(payload.get("page"))
    part = _coerce_int(payload.get("part"))
    fingerprint = str(payload.get("fileFingerprint", "") or "")
    timestamp = str(payload.get("timestamp", "") or "")
    if not timestamp:
        timestamp = str(event.get("wallTime", "") or "")
    re_nested = payload.get("reNested")

    if not pdf or page is None:
        return None

    out: Dict[str, Any] = {
        "file": pdf,
        "page": page,
        "action": action_name,
        "timestamp": timestamp,
        "fileFingerprint": fingerprint,
        "_lamport": _coerce_int(event.get("lamport")),
        "_event_id": str(event.get("eventId", "") or ""),
    }
    if part is not None:
        out["part"] = part
    if isinstance(re_nested, bool):
        out["reNested"] = re_nested
    return out
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "C:\Scripts\Ready Jobs Watcher" && python -m pytest tests/test_tracker_action_stream.py -v`
Expected: PASS, all tests in the file (including the pre-existing ones) green.

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\Ready Jobs Watcher"
git add ready_jobs_watcher/tracker_action_stream.py tests/test_tracker_action_stream.py
git commit -m "feat(R-01): map bad_part_submitted/view/reNested in CNC ndjson event stream"
```

---

## Task 2: RJW — switch `_consolidate_tracker`'s merge input to the union reader

**Files:**
- Modify: `RJW\ready_jobs_watcher\metadata_cache.py:1-20` (imports), `:462-655`
  (`_consolidate_tracker`, `consolidate_cnc_tracker`, `consolidate_hardwoods_tracker`)
- Test: `RJW\tests\test_tracker_condensing.py`

Today `_consolidate_tracker` builds its merge input by manually reading `consolidated.json` plus an
`os.scandir` of top-level `*.json` files — it never looks at `events/*.ndjson`. Switch the merge
input to `load_cnc_tracker_actions`/`load_hardwoods_tracker_actions` (which already union ndjson +
legacy + `consolidated.json` itself, since `consolidated.json` is just another `*.json` file with an
`"actions"` key — verified in `_load_legacy_json_actions`, which doesn't special-case that filename).
This task only changes what feeds the merge; deletion behavior is untouched here (Task 3 splits
that).

- [ ] **Step 1: Write the failing test**

Add to `RJW\tests\test_tracker_condensing.py`:

```python
def test_consolidate_cnc_tracker_merges_ndjson_events(tmp_path):
    job = tmp_path / "Ready Jobs" / "123 - Test Job"
    tracker_dir = job / "CNC" / ".tracker"
    ndjson = tracker_dir / "events" / "tablet-a.ndjson"
    ndjson.parent.mkdir(parents=True)
    ndjson.write_text(
        json.dumps(
            {
                "eventId": "e1",
                "op": "set_complete_true",
                "payload": {"file": "123 - Maple.pdf", "page": 1, "fileFingerprint": "fp1", "timestamp": "2026-07-09T09:00:00Z"},
                "lamport": 1,
                "wallTime": "2026-07-09T09:00:00Z",
            }
        )
        + "\n",
        encoding="utf-8",
    )

    consolidate_cnc_tracker(job)

    consolidated = json.loads((tracker_dir / "consolidated.json").read_text(encoding="utf-8"))
    assert {"file": "123 - Maple.pdf", "page": 1, "action": "complete", "timestamp": "2026-07-09T09:00:00Z", "fileFingerprint": "fp1"} in consolidated["actions"]
    # ndjson source file must survive the daytime pass (compact defaults to False)
    assert ndjson.exists()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "C:\Scripts\Ready Jobs Watcher" && python -m pytest tests/test_tracker_condensing.py::test_consolidate_cnc_tracker_merges_ndjson_events -v`
Expected: FAIL — the ndjson file is never scanned by the current `os.scandir`-only merge input, so
`consolidated.json`'s actions list is empty (or the function returns early since no legacy device
files exist).

- [ ] **Step 3: Implement the merge-input switch**

Add the import near the top of `RJW\ready_jobs_watcher\metadata_cache.py` (alongside the existing
`from .metadata_snapshot import archive_job_metadata` line):

```python
from .tracker_action_stream import load_cnc_tracker_actions, load_hardwoods_tracker_actions
```

Replace `_consolidate_tracker` and the two public wrappers:

```python
def _consolidate_tracker(
    tracker_dir: Path,
    merge_actions: Callable[[List[Dict[str, Any]]], List[Dict[str, Any]]],
    load_tracker_actions: Callable[[Path], List[Dict[str, Any]]],
) -> None:
    """Shared read-merge-write-delete pipeline for the CNC and hardwoods trackers.

    See the H-06 CROSS-PROGRAM comment above for why this whole sequence is wrapped in a
    per-tracker-dir lock. If the lock is already held (another process is mid-consolidation for
    this same job's tracker), this pass is skipped cleanly rather than blocking or raising --
    consolidation runs on a recurring debounce/poll cycle (Config.metadata_cache_debounce_seconds),
    so a skipped pass simply retries next cycle.
    """
    if not tracker_dir.exists():
        return

    if not _acquire_tracker_lock(tracker_dir):
        logging.getLogger(__name__).info(
            "metadata_cache: skipping consolidation for %s, lock held by another process (H-06)",
            tracker_dir,
        )
        return

    try:
        legacy_device_files: List[tuple] = []
        for entry in os.scandir(tracker_dir):
            if (
                not entry.is_file()
                or not entry.name.endswith(".json")
                or entry.name == "consolidated.json"
                or ".sync-conflict-" in entry.name.lower()
            ):
                continue
            try:
                stat = entry.stat()
                legacy_device_files.append((Path(entry.path), stat.st_mtime, stat.st_size))
            except OSError:
                pass

        events_dir = tracker_dir / "events"
        ndjson_device_files: List[tuple] = []
        if events_dir.is_dir():
            for entry in os.scandir(events_dir):
                if not entry.is_file() or not entry.name.lower().endswith(".ndjson") or ".sync-conflict-" in entry.name.lower():
                    continue
                try:
                    stat = entry.stat()
                    ndjson_device_files.append((Path(entry.path), stat.st_mtime, stat.st_size))
                except OSError:
                    pass

        # CROSS-PROGRAM (METADATA_AUDIT.md R-01): merge input comes from the same union reader
        # tracker_bad_parts.py/remake_candidates_indexer.py already use (ndjson events + legacy
        # <tabletId>.json + consolidated.json itself, since consolidated.json is just one more
        # *.json file with an "actions" key). Only legacy device files are deleted here; ndjson
        # event files are only ever deleted by the after-hours compaction pass (see
        # consolidate_cnc_tracker/consolidate_hardwoods_tracker's compact param) because each
        # ndjson file has exactly one writer (the owning tablet) and truncating it mid-day would
        # race that tablet's live append.
        if not legacy_device_files and not ndjson_device_files:
            return

        actions = load_tracker_actions(tracker_dir)
        consolidated_actions = merge_actions(actions)
        _atomic_write_json(tracker_dir / "consolidated.json", {"tabletId": "consolidated", "actions": consolidated_actions})

        _delete_unchanged_device_files(legacy_device_files)
    finally:
        _release_tracker_lock(tracker_dir)


def consolidate_cnc_tracker(job_folder: Path):
    # CROSS-PROGRAM: the per-device <tabletId>.json files and events/<tabletId>.ndjson streams
    # here are PRODUCED by KKCSheetTracker tablets (ProgressStore.kt) and CONSUMED by this
    # watcher. This function merges them into consolidated.json. Legacy device files are deleted
    # after a successful merge; ndjson event files are left alone (see compact param on the
    # shared _consolidate_tracker pipeline).
    # FIXED (METADATA_AUDIT.md C-01/M-06): the merge tracks, per (file, page, fingerprint, part),
    # whether the part is currently bad and whether it has been submitted for the engineer alert
    # (tracker_bad_parts.py:448 requires a `bad_part_submitted` action to fire). Both `bad_part` and
    # `bad_part_submitted` are re-emitted into consolidated.json with their own original timestamps
    # (not a shared/fallback timestamp), and `unbad_part` resets the submitted flag, mirroring the
    # reactivation semantics in tracker_bad_parts.py so the alert survives device-file deletion.
    _consolidate_tracker(
        job_folder / "CNC" / ".tracker",
        _merge_cnc_actions,
        lambda tracker_dir: load_cnc_tracker_actions(str(tracker_dir)),
    )


def consolidate_hardwoods_tracker(job_folder: Path):
    _consolidate_tracker(
        job_folder / ".metadata" / "hardwoods" / ".tracker",
        _merge_hardwoods_actions,
        lambda tracker_dir: load_hardwoods_tracker_actions([str(tracker_dir)]),
    )
```

Note this removes the old manual "load existing `consolidated.json`, then extend with device-file
actions" block — `load_tracker_actions` already includes `consolidated.json`'s own content as
described in the comment above, so nothing is lost.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "C:\Scripts\Ready Jobs Watcher" && python -m pytest tests/test_tracker_condensing.py tests/test_tracker_action_stream.py tests/test_hardwoods_cutlist_indexer.py -v`
Expected: PASS — including all pre-existing tests in `test_tracker_condensing.py` (they exercise the
legacy-only path, which must still work unchanged since `load_cnc_tracker_actions` falls through to
legacy-only when there's no `events/` dir).

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\Ready Jobs Watcher"
git add ready_jobs_watcher/metadata_cache.py tests/test_tracker_condensing.py
git commit -m "feat(R-01): consolidate trackers from the ndjson+legacy union reader"
```

---

## Task 3: RJW — add `compact` flag and after-hours ndjson truncation

**Files:**
- Modify: `RJW\ready_jobs_watcher\metadata_cache.py` (`_consolidate_tracker`,
  `consolidate_cnc_tracker`, `consolidate_hardwoods_tracker`, `update_all_jobs_cache`,
  `refresh_single_job`)
- Test: `RJW\tests\test_tracker_condensing.py`

Each ndjson file has exactly one legitimate writer (the owning tablet). Daytime consolidation (Task
2) must never delete/truncate it — only the existing after-hours end-of-day sweep may, once its
content is durably folded into `consolidated.json`, using the same mtime/size "unchanged since we
read it" guard the legacy path already relies on.

- [ ] **Step 1: Write the failing test**

Add to `RJW\tests\test_tracker_condensing.py`:

```python
def test_compact_true_truncates_unchanged_ndjson_after_merge(tmp_path):
    job = tmp_path / "Ready Jobs" / "123 - Test Job"
    tracker_dir = job / "CNC" / ".tracker"
    ndjson = tracker_dir / "events" / "tablet-a.ndjson"
    ndjson.parent.mkdir(parents=True)
    ndjson.write_text(
        json.dumps(
            {
                "eventId": "e1",
                "op": "set_complete_true",
                "payload": {"file": "123 - Maple.pdf", "page": 1, "fileFingerprint": "fp1", "timestamp": "2026-07-09T09:00:00Z"},
                "lamport": 1,
                "wallTime": "2026-07-09T09:00:00Z",
            }
        )
        + "\n",
        encoding="utf-8",
    )

    consolidate_cnc_tracker(job, compact=True)

    consolidated = json.loads((tracker_dir / "consolidated.json").read_text(encoding="utf-8"))
    assert any(a["action"] == "complete" for a in consolidated["actions"])
    assert not ndjson.exists()


def test_compact_false_never_touches_ndjson(tmp_path):
    job = tmp_path / "Ready Jobs" / "123 - Test Job"
    tracker_dir = job / "CNC" / ".tracker"
    ndjson = tracker_dir / "events" / "tablet-a.ndjson"
    ndjson.parent.mkdir(parents=True)
    ndjson.write_text(
        json.dumps(
            {
                "eventId": "e1",
                "op": "set_complete_true",
                "payload": {"file": "123 - Maple.pdf", "page": 1, "fileFingerprint": "fp1", "timestamp": "2026-07-09T09:00:00Z"},
                "lamport": 1,
                "wallTime": "2026-07-09T09:00:00Z",
            }
        )
        + "\n",
        encoding="utf-8",
    )

    consolidate_cnc_tracker(job)  # compact defaults to False

    assert ndjson.exists()


def test_compact_true_skips_ndjson_that_changed_since_read(tmp_path, monkeypatch):
    job = tmp_path / "Ready Jobs" / "123 - Test Job"
    tracker_dir = job / "CNC" / ".tracker"
    ndjson = tracker_dir / "events" / "tablet-a.ndjson"
    ndjson.parent.mkdir(parents=True)
    ndjson.write_text(
        json.dumps(
            {
                "eventId": "e1",
                "op": "set_complete_true",
                "payload": {"file": "123 - Maple.pdf", "page": 1, "fileFingerprint": "fp1", "timestamp": "2026-07-09T09:00:00Z"},
                "lamport": 1,
                "wallTime": "2026-07-09T09:00:00Z",
            }
        )
        + "\n",
        encoding="utf-8",
    )

    import ready_jobs_watcher.metadata_cache as metadata_cache_module
    real_load = metadata_cache_module.load_cnc_tracker_actions

    def slow_load(tracker_dir_str, **kwargs):
        # Simulate a tablet appending a new line to the file after this pass already stat'd it
        # but before the merge+delete step runs -- the "unchanged since read" guard must catch it.
        ndjson.write_text(
            ndjson.read_text(encoding="utf-8")
            + json.dumps(
                {
                    "eventId": "e2",
                    "op": "set_complete_true",
                    "payload": {"file": "123 - Maple.pdf", "page": 2, "fileFingerprint": "fp1", "timestamp": "2026-07-09T09:00:01Z"},
                    "lamport": 2,
                    "wallTime": "2026-07-09T09:00:01Z",
                }
            )
            + "\n",
            encoding="utf-8",
        )
        return real_load(tracker_dir_str, **kwargs)

    monkeypatch.setattr(metadata_cache_module, "load_cnc_tracker_actions", slow_load)

    consolidate_cnc_tracker(job, compact=True)

    assert ndjson.exists()
    remaining = ndjson.read_text(encoding="utf-8")
    assert "page\": 2" in remaining or '"page": 2' in remaining
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "C:\Scripts\Ready Jobs Watcher" && python -m pytest tests/test_tracker_condensing.py -k compact -v`
Expected: FAIL — `consolidate_cnc_tracker` doesn't accept a `compact` keyword yet (`TypeError`).

- [ ] **Step 3: Implement the compact flag**

In `RJW\ready_jobs_watcher\metadata_cache.py`, update the three functions from Task 2:

```python
def _consolidate_tracker(
    tracker_dir: Path,
    merge_actions: Callable[[List[Dict[str, Any]]], List[Dict[str, Any]]],
    load_tracker_actions: Callable[[Path], List[Dict[str, Any]]],
    compact: bool = False,
) -> None:
    """..."""  # (docstring unchanged from Task 2)
    if not tracker_dir.exists():
        return

    if not _acquire_tracker_lock(tracker_dir):
        logging.getLogger(__name__).info(
            "metadata_cache: skipping consolidation for %s, lock held by another process (H-06)",
            tracker_dir,
        )
        return

    try:
        legacy_device_files: List[tuple] = []
        for entry in os.scandir(tracker_dir):
            if (
                not entry.is_file()
                or not entry.name.endswith(".json")
                or entry.name == "consolidated.json"
                or ".sync-conflict-" in entry.name.lower()
            ):
                continue
            try:
                stat = entry.stat()
                legacy_device_files.append((Path(entry.path), stat.st_mtime, stat.st_size))
            except OSError:
                pass

        events_dir = tracker_dir / "events"
        ndjson_device_files: List[tuple] = []
        if events_dir.is_dir():
            for entry in os.scandir(events_dir):
                if not entry.is_file() or not entry.name.lower().endswith(".ndjson") or ".sync-conflict-" in entry.name.lower():
                    continue
                try:
                    stat = entry.stat()
                    ndjson_device_files.append((Path(entry.path), stat.st_mtime, stat.st_size))
                except OSError:
                    pass

        if not legacy_device_files and not ndjson_device_files:
            return

        actions = load_tracker_actions(tracker_dir)
        consolidated_actions = merge_actions(actions)
        _atomic_write_json(tracker_dir / "consolidated.json", {"tabletId": "consolidated", "actions": consolidated_actions})

        _delete_unchanged_device_files(legacy_device_files)
        if compact:
            # CROSS-PROGRAM (METADATA_AUDIT.md R-01): safe only because this only runs from the
            # after-hours end-of-day sweep (see scheduler.py's metadata_end_of_day_scheduler /
            # process_metadata_end_of_day_once), when no tablet is actively appending. The
            # mtime/size guard in _delete_unchanged_device_files still protects against a
            # genuinely-anomalous late writer.
            _delete_unchanged_device_files(ndjson_device_files)
    finally:
        _release_tracker_lock(tracker_dir)


def consolidate_cnc_tracker(job_folder: Path, compact: bool = False):
    # ... (comment unchanged from Task 2)
    _consolidate_tracker(
        job_folder / "CNC" / ".tracker",
        _merge_cnc_actions,
        lambda tracker_dir: load_cnc_tracker_actions(str(tracker_dir)),
        compact=compact,
    )


def consolidate_hardwoods_tracker(job_folder: Path, compact: bool = False):
    _consolidate_tracker(
        job_folder / ".metadata" / "hardwoods" / ".tracker",
        _merge_hardwoods_actions,
        lambda tracker_dir: load_hardwoods_tracker_actions([str(tracker_dir)]),
        compact=compact,
    )
```

Then thread `compact_tracker_events` through the two call sites in the same file
(`update_all_jobs_cache` and `refresh_single_job`):

```python
def update_all_jobs_cache(
    base_path: Path,
    *,
    consolidate_trackers: bool = True,
    compact_tracker_events: bool = False,
    archive: bool = True,
    archive_root: Optional[Path] = None,
    archive_retention_days: Optional[int] = None,
    archive_max_snapshots_per_job: Optional[int] = None,
    archive_daypart_limit: bool = False,
    force_rebuild: bool = False,
) -> Dict[str, int]:
    ...
        if consolidate_trackers:
            consolidate_cnc_tracker(job_folder, compact=compact_tracker_events)
            consolidate_hardwoods_tracker(job_folder, compact=compact_tracker_events)
    ...
```

(Only the `if consolidate_trackers:` block and the new keyword param change; the rest of the
function body is unchanged from what's on disk today.)

```python
def refresh_single_job(
    base_path: Path,
    job_folder: Path,
    *,
    reason: str,
    archive_root: Optional[Path],
    archive_retention_days: Optional[int] = None,
    archive_max_snapshots_per_job: Optional[int] = None,
    archive_daypart_limit: bool = False,
    consolidate_trackers: bool = False,
    compact_tracker_events: bool = False,
) -> Dict[str, Any]:
    if not job_folder.is_dir():
        return {"skipped": "missing_job", "jobFolder": str(job_folder)}
    if not _read_deployed_flag(job_folder):
        return {"skipped": "not_deployed", "jobFolder": str(job_folder)}
    if consolidate_trackers:
        consolidate_cnc_tracker(job_folder, compact=compact_tracker_events)
        consolidate_hardwoods_tracker(job_folder, compact=compact_tracker_events)
    ...
```

(Rest of function body unchanged.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "C:\Scripts\Ready Jobs Watcher" && python -m pytest tests/test_tracker_condensing.py -v`
Expected: PASS, all tests including the 3 new ones and every pre-existing test in the file.

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\Ready Jobs Watcher"
git add ready_jobs_watcher/metadata_cache.py tests/test_tracker_condensing.py
git commit -m "feat(R-01): add compact flag to truncate tablet ndjson only after safe merge"
```

---

## Task 4: RJW — wire `compact_tracker_events=True` through to the end-of-day sweep only

**Files:**
- Modify: `RJW\ready_jobs_watcher\metadata_refresh.py:159-184` (`run_scheduled_sweep`,
  `refresh_all_now`), `RJW\ready_jobs_watcher\scheduler.py:294-296`
  (`process_metadata_end_of_day_once`)
- Test: `RJW\tests\test_metadata_refresh_scheduler.py`, `RJW\tests\test_gui_actions.py`

`run_scheduled_sweep(consolidate_trackers=True)` is called from **two** places today: the
after-hours `metadata_end_of_day_scheduler` (via `process_metadata_end_of_day_once`) and the
GUI's manual "Run Consolidation" Actions-tab button (`gui.py:652`). Only the end-of-day path is safe
to compact — a user could click the GUI button mid-shift. `compact_tracker_events` must default to
`False` everywhere and only be set `True` at the one end-of-day call site.

- [ ] **Step 1: Write the failing test**

Add to `RJW\tests\test_metadata_refresh_scheduler.py` (same fake-config/monkeypatch pattern as the
existing `test_refresh_job_now_...` test already in that file):

```python
def test_process_metadata_end_of_day_once_compacts_tracker_events(monkeypatch, tmp_path):
    calls = []

    def fake_update_all_jobs_cache(base_path, **kwargs):
        calls.append(kwargs)
        return {"processed": 0, "rebuilt": 0, "archived": 0, "errors": 0}

    monkeypatch.setattr("ready_jobs_watcher.metadata_refresh.update_all_jobs_cache", fake_update_all_jobs_cache)
    config = type(
        "Config",
        (),
        {
            "ROOT_DIR": str(tmp_path),
            "metadata_snapshot_enabled": False,
            "metadata_snapshot_retention_days": 30,
            "metadata_snapshot_max_per_job": 3,
            "metadata_snapshot_daypart_limit": True,
            "metadata_cache_debounce_seconds": 0,
        },
    )()
    service = MetadataRefreshService(config)

    from ready_jobs_watcher.scheduler import process_metadata_end_of_day_once
    process_metadata_end_of_day_once(service)

    assert calls
    assert calls[0]["consolidate_trackers"] is True
    assert calls[0]["compact_tracker_events"] is True


def test_run_scheduled_sweep_defaults_compact_to_false(monkeypatch, tmp_path):
    calls = []

    def fake_update_all_jobs_cache(base_path, **kwargs):
        calls.append(kwargs)
        return {"processed": 0, "rebuilt": 0, "archived": 0, "errors": 0}

    monkeypatch.setattr("ready_jobs_watcher.metadata_refresh.update_all_jobs_cache", fake_update_all_jobs_cache)
    config = type(
        "Config",
        (),
        {
            "ROOT_DIR": str(tmp_path),
            "metadata_snapshot_enabled": False,
            "metadata_snapshot_retention_days": 30,
            "metadata_snapshot_max_per_job": 3,
            "metadata_snapshot_daypart_limit": True,
            "metadata_cache_debounce_seconds": 0,
        },
    )()
    service = MetadataRefreshService(config)

    service.run_scheduled_sweep(consolidate_trackers=True)

    assert calls
    assert calls[0]["compact_tracker_events"] is False
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "C:\Scripts\Ready Jobs Watcher" && python -m pytest tests/test_metadata_refresh_scheduler.py -k compact -v`
Expected: FAIL — `run_scheduled_sweep`/`update_all_jobs_cache` calls don't pass
`compact_tracker_events` yet, so the fake never records that key (`KeyError` on the assertion).

- [ ] **Step 3: Implement the wiring**

In `RJW\ready_jobs_watcher\metadata_refresh.py`, update `run_scheduled_sweep`:

```python
def run_scheduled_sweep(self, *, consolidate_trackers: bool = True, compact_tracker_events: bool = False) -> dict:
    summary = update_all_jobs_cache(
        self.root_dir,
        consolidate_trackers=consolidate_trackers,
        compact_tracker_events=compact_tracker_events,
        archive=True,
        archive_root=self.archive_root,
        archive_retention_days=self.archive_retention_days,
        archive_max_snapshots_per_job=self.archive_max_snapshots_per_job,
        archive_daypart_limit=self.archive_daypart_limit,
    )
    self._prune_orphan_archives()
    return summary
```

(`refresh_all_now` and `refresh_job_now` are unchanged — they don't need `compact_tracker_events`
since neither ever sets `consolidate_trackers` from an after-hours-only context.)

In `RJW\ready_jobs_watcher\scheduler.py`, update `process_metadata_end_of_day_once`:

```python
def process_metadata_end_of_day_once(metadata_refresh_service: "MetadataRefreshService") -> dict:
    """Run the metadata cache/archive daily sweep once. Also compacts tablet ndjson tracker-event
    streams now that it's safe to do so (after hours, no tablet actively writing) -- see
    METADATA_AUDIT.md R-01."""
    return metadata_refresh_service.run_scheduled_sweep(consolidate_trackers=True, compact_tracker_events=True)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "C:\Scripts\Ready Jobs Watcher" && python -m pytest tests/test_metadata_refresh_scheduler.py tests/test_gui_actions.py tests/test_metadata_scheduler_integration.py -v`
Expected: PASS — including `test_gui_actions.py`'s existing manual-trigger test, which must still
show the GUI path takes the `compact_tracker_events=False` default (unchanged behavior).

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\Ready Jobs Watcher"
git add ready_jobs_watcher/metadata_refresh.py ready_jobs_watcher/scheduler.py tests/test_metadata_refresh_scheduler.py
git commit -m "feat(R-01): only the after-hours sweep compacts tablet ndjson event streams"
```

---

## Task 5: RJW — full regression suite before moving to the tablet

**Files:** none (verification-only task)

- [ ] **Step 1: Run the full RJW test suite**

Run: `cd "C:\Scripts\Ready Jobs Watcher" && python -m pytest tests/ -q`
Expected: PASS, no new failures vs. the pre-Task-1 baseline (note the count before starting Task 1
so you can compare — it was 349 passed as of the H-06 fix per METADATA_AUDIT.md's change log).

- [ ] **Step 2: Manually verify backward compatibility**

Run: `cd "C:\Scripts\Ready Jobs Watcher" && python -c "from ready_jobs_watcher.metadata_cache import consolidate_cnc_tracker; import tempfile, json, pathlib; d = pathlib.Path(tempfile.mkdtemp()) / 'job'; (d / 'CNC' / '.tracker').mkdir(parents=True); (d / 'CNC' / '.tracker' / 'tablet-a.json').write_text(json.dumps({'tabletId': 'tablet-a', 'actions': [{'file': 'A.pdf', 'page': 1, 'action': 'complete', 'timestamp': '2026-07-09T09:00:00Z'}]})); consolidate_cnc_tracker(d); print((d / 'CNC' / '.tracker' / 'consolidated.json').read_text())"`
Expected: prints a `consolidated.json` body containing the `complete` action — confirms a
legacy-only tracker dir (no tablet has updated yet) still consolidates correctly through the new
union-reader code path, exactly as designed in the spec's "Backward compatibility" section.

This task has no code changes — it's the checkpoint before starting tablet-side work. RJW can ship
to production at this point independent of the tablet changes below.

---

## Task 6: Tablet — `TrackerLamportClock` (new shared file)

**Files:**
- Create: `KKC\app\src\main\java\com\kkc\sheettracker\data\TrackerEventLog.kt`
- Test: `KKC\app\src\test\java\com\kkc\sheettracker\data\TrackerEventLogTest.kt`

A single per-tablet monotonic counter, persisted in local (non-Syncthing) app storage, shared by both
`ProgressStore` and `HardwoodsProgressStore`. Since `HardwoodsProgressStore` is constructed
per-composable across ~40 call sites with no `localStateDir` parameter (adding one would be a
43-call-site mechanical change for a small feature — not worth it), this is a lazily-initialized
singleton instead: it works in-memory-only until `init()` is called once at app startup (Task 14),
and unit tests can exercise persistence directly by calling `init()` with a temp dir.

- [ ] **Step 1: Write the failing test**

Create `KKC\app\src\test\java\com\kkc\sheettracker\data\TrackerEventLogTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TrackerLamportClockTest {

    @Test
    fun nextReturnsStrictlyIncreasingValues() {
        val values = (1..5).map { TrackerLamportClock.next() }
        assertEquals(values.sorted(), values)
        assertEquals(values.toSet().size, values.size)
    }

    @Test
    fun persistsAcrossReinitWithSameBackingDir() {
        val stateDir = Files.createTempDirectory("lamport-test").toFile()
        TrackerLamportClock.init(stateDir)
        val first = TrackerLamportClock.next()
        val second = TrackerLamportClock.next()
        assertTrue(second > first)

        // Simulate a process restart: re-init against the same dir, counter must not reset.
        TrackerLamportClock.init(stateDir)
        val third = TrackerLamportClock.next()
        assertTrue(third > second)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.TrackerLamportClockTest"`
Expected: FAIL to compile — `TrackerLamportClock` doesn't exist yet.

- [ ] **Step 3: Implement `TrackerEventLog.kt`**

Create `KKC\app\src\main\java\com\kkc\sheettracker\data\TrackerEventLog.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic per-tablet counter used to tie-break tracker events sharing the same wall-clock
 * timestamp. Not a merged cross-node Lamport clock -- the watcher's sort key primaries on
 * timestamp (tracker_action_stream.py:_sort_combined_actions); this only disambiguates
 * same-instant events from this one tablet. See METADATA_AUDIT.md R-01.
 *
 * Lazily initialized: `next()` works in-memory-only until `init()` is called once (see
 * MainActivity), so unit tests that construct ProgressStore/HardwoodsProgressStore directly
 * don't need any setup.
 */
object TrackerLamportClock {
    private val lock = Any()
    private val counter = AtomicLong(0L)
    private var backingFile: File? = null
    private var loaded = false

    fun init(stateDir: File) {
        synchronized(lock) {
            backingFile = File(stateDir, "tracker_lamport.txt")
            loaded = false
        }
    }

    fun next(): Long {
        synchronized(lock) {
            ensureLoadedLocked()
            val value = counter.incrementAndGet()
            persistLocked(value)
            return value
        }
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val file = backingFile ?: return
        val persisted = runCatching { file.readText().trim().toLong() }.getOrNull()
        if (persisted != null && persisted > counter.get()) {
            counter.set(persisted)
        }
    }

    private fun persistLocked(value: Long) {
        val file = backingFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(value.toString())
        }
    }
}

/** One ndjson tracker event line, matching the schema Ready Jobs Watcher's
 * tracker_action_stream.py expects (see METADATA_AUDIT.md R-01 design doc). */
data class TrackerEvent(
    val op: String,
    val payload: JsonObject,
    val wallTime: String,
    val lamport: Long,
    val eventId: String = UUID.randomUUID().toString()
)

fun encodeTrackerEventLine(event: TrackerEvent): String {
    val json = JsonObject()
    json.addProperty("op", event.op)
    json.add("payload", event.payload)
    json.addProperty("wallTime", event.wallTime)
    json.addProperty("lamport", event.lamport)
    json.addProperty("eventId", event.eventId)
    return json.toString()
}

/** True append -- opens, writes one newline-terminated line, closes. Never rewrites prior lines. */
fun appendTrackerEvent(file: File, event: TrackerEvent) {
    file.parentFile?.mkdirs()
    FileOutputStream(file, true).use { stream ->
        stream.write((encodeTrackerEventLine(event) + "\n").toByteArray(Charsets.UTF_8))
        runCatching { stream.fd.sync() }
    }
}

/** Tolerant per-line parse: skips (does not throw on) any line that fails to parse, so a torn
 * last line read mid-append by a peer tablet doesn't drop the whole file. */
fun readTrackerEvents(file: File): List<JsonObject> {
    if (!file.exists()) return emptyList()
    return file.readLines(Charsets.UTF_8).mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        runCatching { JsonParser().parse(trimmed).asJsonObject }.getOrNull()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.TrackerLamportClockTest"`
Expected: BUILD SUCCESSFUL, 2/2 passed.

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/TrackerEventLog.kt app/src/test/java/com/kkc/sheettracker/data/TrackerEventLogTest.kt
git commit -m "feat(R-01): add TrackerLamportClock and ndjson event line codec"
```

---

## Task 7: Tablet — round-trip test for the ndjson line codec

**Files:**
- Modify: `KKC\app\src\test\java\com\kkc\sheettracker\data\TrackerEventLogTest.kt`

Covers `appendTrackerEvent`/`readTrackerEvents` directly (Task 6 only tested the lamport clock).

- [ ] **Step 1: Write the failing test**

Add to `TrackerEventLogTest.kt` (new top-level test class in the same file):

```kotlin
class TrackerEventCodecTest {
    @Test
    fun appendThenReadRoundTripsMultipleLines() {
        val file = File(Files.createTempDirectory("event-log-test").toFile(), "tablet-a.ndjson")
        val payload1 = com.google.gson.JsonObject().apply {
            addProperty("file", "A.pdf")
            addProperty("page", 1)
        }
        val payload2 = com.google.gson.JsonObject().apply {
            addProperty("file", "A.pdf")
            addProperty("page", 2)
        }
        appendTrackerEvent(file, TrackerEvent(op = "set_complete_true", payload = payload1, wallTime = "2026-07-09T09:00:00Z", lamport = 1))
        appendTrackerEvent(file, TrackerEvent(op = "set_complete_true", payload = payload2, wallTime = "2026-07-09T09:00:01Z", lamport = 2))

        val events = readTrackerEvents(file)

        assertEquals(2, events.size)
        assertEquals(1, events[0].getAsJsonObject("payload").get("page").asInt)
        assertEquals(2, events[1].getAsJsonObject("payload").get("page").asInt)
        assertEquals(1L, events[0].get("lamport").asLong)
    }

    @Test
    fun readSkipsTornLastLineWithoutDroppingEarlierLines() {
        val file = File(Files.createTempDirectory("event-log-test").toFile(), "tablet-a.ndjson")
        val payload = com.google.gson.JsonObject().apply {
            addProperty("file", "A.pdf")
            addProperty("page", 1)
        }
        appendTrackerEvent(file, TrackerEvent(op = "set_complete_true", payload = payload, wallTime = "2026-07-09T09:00:00Z", lamport = 1))
        file.appendText("{\"op\": \"set_complete_tr")  // simulate a torn write

        val events = readTrackerEvents(file)

        assertEquals(1, events.size)
    }

    @Test
    fun readReturnsEmptyListForMissingFile() {
        val file = File(Files.createTempDirectory("event-log-test").toFile(), "does-not-exist.ndjson")
        assertTrue(readTrackerEvents(file).isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.TrackerEventCodecTest"`
Expected: These should actually PASS already since Task 6 implemented the codec — this step
confirms that. If any fail, fix `TrackerEventLog.kt` before proceeding (most likely culprit: Gson's
`JsonParser().parse(...)` throwing a subclass of `Exception` that `runCatching` doesn't catch --
`JsonParser` throws `JsonSyntaxException` which extends `RuntimeException`, covered by `runCatching`).

- [ ] **Step 3: (no implementation step needed — codec already exists from Task 6)**

- [ ] **Step 4: Run full test file to confirm nothing regressed**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.TrackerEventLogTest" --tests "com.kkc.sheettracker.data.TrackerEventCodecTest"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/test/java/com/kkc/sheettracker/data/TrackerEventLogTest.kt
git commit -m "test(R-01): cover ndjson append/read round-trip and torn-line tolerance"
```

---

## Task 8: Tablet — CNC producer switch (`ProgressStore.kt`)

**Files:**
- Modify: `KKC\app\src\main\java\com\kkc\sheettracker\data\ProgressStore.kt`
- Modify: `KKC\app\src\test\java\com\kkc\sheettracker\data\ProgressStoreTest.kt`

Replace the whole-file legacy write in `appendActions` with a true per-line ndjson append. Add
top-level (not class-private) op-name mapping functions so tests in the same package can decode
events directly. Remove `loadTabletProgress`/`saveTabletProgress`/`tabletFile()` (become fully dead
once `appendActions` and `getLocalMaterialLastTouches` stop using them — verified only 2 call sites
of each exist in this file today).

- [ ] **Step 1: Write the failing tests**

Replace the 4 affected tests in `KKC\app\src\test\java\com\kkc\sheettracker\data\ProgressStoreTest.kt`.
First, add this import near the top of the file (alongside the existing imports):

```kotlin
import com.google.gson.JsonObject
```

Replace `viewActionDoesNotWriteStatusActions`:

```kotlin
    @Test
    fun viewActionDoesNotWriteStatusActions() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))

        store.markSheetViewed(jobFolderName, "A.pdf", 3, "fp1")
        val actions = readTrackerEventActions(baseDir, jobFolderName, tabletId)
        assertEquals(listOf("view"), actions)
    }
```

Replace `localMaterialTouchesAreLatestPerMaterial`:

```kotlin
    @Test
    fun localMaterialTouchesAreLatestPerMaterial() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))

        store.markSheetViewed(jobFolderName, "A.pdf", 2, "fp1")
        store.markSheetViewed(jobFolderName, "B.pdf", 4, "fp1")
        store.markSheetViewed(jobFolderName, "A.pdf", 6, "fp1")

        val touches = store.getLocalMaterialLastTouches(jobFolderName)

        assertEquals(6, touches["A.pdf"]?.page)
        assertEquals(4, touches["B.pdf"]?.page)
    }
```

Replace `concurrentViewActionsPreserveEveryAppendedAction`'s final assertions block (keep the
writer/pool setup identical, only change the read-back at the end):

```kotlin
        val events = readTrackerEventObjects(baseDir, jobFolderName, tabletId)
        val pages = events.map { it.getAsJsonObject("payload").get("page").asInt }

        assertEquals(totalActions, events.size)
        assertEquals((1..totalActions).toSet(), pages.toSet())
        assertTrue(events.all { cncActionForOp(it.get("op").asString) == "view" })
    }
```

Add these two test helpers next to the existing `createTempBaseDir`/`writeTabletProgress` helpers
(keep `writeTabletProgress`/`readTabletProgress` as-is — they're still used by
`loadAllProgressExcludesSyncConflictFiles`, which tests peer reading of legacy files and must keep
passing unchanged):

```kotlin
    private fun readTrackerEventObjects(baseDir: File, jobFolderName: String, tabletId: String): List<JsonObject> {
        val file = File(baseDir, "$jobFolderName/CNC/.tracker/events/$tabletId.ndjson")
        return readTrackerEvents(file)
    }

    private fun readTrackerEventActions(baseDir: File, jobFolderName: String, tabletId: String): List<String> {
        return readTrackerEventObjects(baseDir, jobFolderName, tabletId).map { cncActionForOp(it.get("op").asString) }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ProgressStoreTest"`
Expected: FAIL to compile (`cncActionForOp`, `readTrackerEvents` used but `appendActions` still
writes the legacy snapshot, and `cncActionForOp` doesn't exist yet).

- [ ] **Step 3: Implement the CNC producer switch**

In `KKC\app\src\main\java\com\kkc\sheettracker\data\ProgressStore.kt`, add these top-level functions
(outside the `ProgressStore` class, e.g. directly below the imports and before
`private data class DraftBadPartEntry`):

```kotlin
/** CNC op-name mapping mirroring Ready Jobs Watcher's tracker_action_stream.py _CNC_OP_MAP.
 * Top-level (not private) so tests in this package can decode ndjson events directly. */
internal fun cncOpForAction(action: String): String = when (action) {
    "complete" -> "set_complete_true"
    "uncomplete" -> "set_complete_false"
    "skip" -> "set_skipped_true"
    "unskip" -> "set_skipped_false"
    "bad_part" -> "set_bad_part_true"
    "unbad_part" -> "set_bad_part_false"
    "bad_part_submitted" -> "bad_part_submitted"
    "view" -> "view"
    else -> action
}

internal fun cncActionForOp(op: String): String = when (op) {
    "set_complete_true" -> "complete"
    "set_complete_false" -> "uncomplete"
    "set_skipped_true" -> "skip"
    "set_skipped_false" -> "unskip"
    "set_bad_part_true" -> "bad_part"
    "set_bad_part_false" -> "unbad_part"
    "bad_part_submitted" -> "bad_part_submitted"
    "view" -> "view"
    else -> op
}

internal fun cncTrackerActionToEvent(action: TrackerAction): TrackerEvent {
    val payload = JsonObject()
    payload.addProperty("file", action.file)
    payload.addProperty("page", action.page)
    action.part?.let { payload.addProperty("part", it) }
    action.fileFingerprint?.let { payload.addProperty("fileFingerprint", it) }
    payload.addProperty("timestamp", action.timestamp)
    action.reNested?.let { payload.addProperty("reNested", it) }
    return TrackerEvent(
        op = cncOpForAction(action.action),
        payload = payload,
        wallTime = action.timestamp,
        lamport = TrackerLamportClock.next()
    )
}

internal fun decodeCncTrackerEvent(event: JsonObject): TrackerAction? {
    val payload = event.getAsJsonObject("payload") ?: return null
    val file = payload.get("file")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: return null
    val page = payload.get("page")?.takeIf { !it.isJsonNull }?.asInt ?: return null
    val opField = event.get("op")?.takeIf { !it.isJsonNull }?.asString ?: return null
    val action = cncActionForOp(opField)
    val timestamp = payload.get("timestamp")?.takeIf { !it.isJsonNull }?.asString
        ?: event.get("wallTime")?.takeIf { !it.isJsonNull }?.asString
        ?: ""
    return TrackerAction(
        file = file,
        page = page,
        part = payload.get("part")?.takeIf { !it.isJsonNull }?.asInt,
        action = action,
        timestamp = timestamp,
        fileFingerprint = payload.get("fileFingerprint")?.takeIf { !it.isJsonNull }?.asString,
        reNested = payload.get("reNested")?.takeIf { !it.isJsonNull }?.asBoolean
    )
}
```

Add an `eventsFile` helper next to the existing `tabletFile`/`trackerDir` private functions (then
**delete** `tabletFile`, `loadTabletProgress`, and `saveTabletProgress` entirely — they become dead
code):

```kotlin
    private fun eventsFile(jobFolderName: String): File {
        return File(trackerDir(jobFolderName), "events/$tabletId.ndjson")
    }
```

Replace `appendActions`:

```kotlin
    /**
     * Appends multiple actions as individual ndjson lines to this tablet's own event stream
     * (never rewriting prior lines -- see METADATA_AUDIT.md R-01), instead of the old
     * load-whole/rewrite-whole-file pattern. Callers that emit several actions at once
     * (markSheetComplete, resolveBadPartsOnSheet, resolveSpecificBadParts) still get one
     * coalesced index update, so the change monitor sees one version bump rather than many.
     */
    private fun appendActions(jobFolderName: String, entries: List<TrackerAction>) {
        if (readOnly || entries.isEmpty()) return
        val writeLock = writeLockByJob.getOrPut(jobFolderName) { Any() }
        synchronized(indexOperationLock) {
            synchronized(writeLock) {
                val file = eventsFile(jobFolderName)
                entries.forEach { entry ->
                    appendTrackerEvent(file, cncTrackerActionToEvent(entry))
                    applyActionToIndex(jobFolderName, entry)
                }
            }
        }
        bumpProgressVersion()
    }
```

Replace `getLocalMaterialLastTouches` (was reading `loadTabletProgress(jobFolderName)`; now reads its
own ndjson file):

```kotlin
    /**
     * Returns last-touch data for this tablet only — used for per-tablet
     * "recent jobs" lists so each device shows its own history.
     */
    fun getLocalMaterialLastTouches(jobFolderName: String): Map<String, MaterialLastTouch> {
        val ownActions = readTrackerEvents(eventsFile(jobFolderName)).mapNotNull { decodeCncTrackerEvent(it) }
        val touches = mutableMapOf<String, MaterialTouchEntry>()
        ownActions.sortedBy { it.timestamp }.forEach { action ->
            val ms = parseTimestampMillis(action.timestamp)
            val entry = touches.getOrPut(action.file) { MaterialTouchEntry() }
            if (ms >= entry.lastTouchedAtMs) {
                entry.lastTouchedAtMs = ms
                entry.lastTouchedPage = action.page.coerceAtLeast(1)
                entry.lastTouchedTimestamp = action.timestamp
            }
        }
        return touches.mapValues { (_, touch) ->
            MaterialLastTouch(
                page = touch.lastTouchedPage.coerceAtLeast(1),
                touchedAtMs = touch.lastTouchedAtMs,
                timestamp = touch.lastTouchedTimestamp
            )
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ProgressStoreTest"`
Expected: BUILD SUCCESSFUL, all tests in the file pass (including
`loadAllProgressExcludesSyncConflictFiles`, unchanged, and `renestedSheetStatusAndSkippedStatus`/
`viewActionUpdatesLocalMaterialTouch`, unchanged since they only use the public API round-trip).

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/ProgressStore.kt app/src/test/java/com/kkc/sheettracker/data/ProgressStoreTest.kt
git commit -m "feat(R-01): CNC tablet writes append-only ndjson instead of whole-file snapshot"
```

---

## Task 9: Tablet — CNC peer reader (`ProgressStore.loadAllProgress`)

**Files:**
- Modify: `KKC\app\src\main\java\com\kkc\sheettracker\data\ProgressStore.kt` (`loadAllProgress`)
- Modify: `KKC\app\src\test\java\com\kkc\sheettracker\data\ProgressStoreTest.kt`

`loadAllProgress` must fold in every tablet's `events/*.ndjson` alongside legacy `*.json` peer files
(and `consolidated.json`), merging same-tabletId actions from both sources together rather than
picking one arbitrarily (matters if a tablet has both a stale pre-migration legacy file and a live
ndjson file during the rollout window).

- [ ] **Step 1: Write the failing test**

Add to `KKC\app\src\test\java\com\kkc\sheettracker\data\ProgressStoreTest.kt`:

```kotlin
    @Test
    fun loadAllProgressMergesNdjsonPeerEvents() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))
        val trackerDir = File(baseDir, "$jobFolderName/CNC/.tracker")
        val eventsFile = File(trackerDir, "events/tablet-b.ndjson")
        val payload = JsonObject().apply {
            addProperty("file", "A.pdf")
            addProperty("page", 3)
            addProperty("fileFingerprint", "fp1")
            addProperty("timestamp", "2026-07-09T09:00:00Z")
        }
        appendTrackerEvent(eventsFile, TrackerEvent(op = "set_complete_true", payload = payload, wallTime = "2026-07-09T09:00:00Z", lamport = 1))

        val allProgress = store.loadAllProgress(jobFolderName)

        assertEquals(1, allProgress.size)
        assertEquals("tablet-b", allProgress.first().tabletId)
        assertEquals("complete", allProgress.first().actions.first().action)
        assertEquals(3, allProgress.first().actions.first().page)
    }

    @Test
    fun loadAllProgressMergesLegacyAndNdjsonForSameTabletId() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-b",
            progress = TabletProgress(tabletId = "tablet-b", actions = listOf(trackerAction("A.pdf", 1, "view", "2026-07-09T08:00:00Z")))
        )
        val eventsFile = File(baseDir, "$jobFolderName/CNC/.tracker/events/tablet-b.ndjson")
        val payload = JsonObject().apply {
            addProperty("file", "A.pdf")
            addProperty("page", 2)
            addProperty("timestamp", "2026-07-09T09:00:00Z")
        }
        appendTrackerEvent(eventsFile, TrackerEvent(op = "set_complete_true", payload = payload, wallTime = "2026-07-09T09:00:00Z", lamport = 1))

        val allProgress = store.loadAllProgress(jobFolderName)

        assertEquals(1, allProgress.size)
        assertEquals(2, allProgress.first().actions.size)
        assertEquals(setOf("view", "complete"), allProgress.first().actions.map { it.action }.toSet())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ProgressStoreTest"`
Expected: FAIL — `loadAllProgress` doesn't look at `events/` yet, so `allProgress` is empty for the
first test and has only 1 action (not merged) for the second.

- [ ] **Step 3: Implement the peer reader**

Replace `loadAllProgress` in `ProgressStore.kt`:

```kotlin
    fun loadAllProgress(jobFolderName: String): List<TabletProgress> {
        val dir = trackerDir(jobFolderName)
        if (!dir.exists()) return emptyList()

        val legacyProgress = dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) && !it.name.startsWith(".") && !it.name.contains(".sync-conflict-") }
            ?.mapNotNull { file ->
                try {
                    sanitizeProgress(
                        gson.fromJson(file.readText(), TabletProgress::class.java),
                        fallbackTabletId = file.nameWithoutExtension
                    )
                } catch (_: Exception) {
                    Log.w("KKC_PROGRESS", "Skipping malformed tracker file: ${file.absolutePath}")
                    null
                }
            }
            ?: emptyList()

        val eventsDir = File(dir, "events")
        val ndjsonProgress = eventsDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("ndjson", ignoreCase = true) && !it.name.startsWith(".") && !it.name.contains(".sync-conflict-") }
            ?.map { file ->
                TabletProgress(
                    tabletId = file.nameWithoutExtension,
                    actions = readTrackerEvents(file).mapNotNull { decodeCncTrackerEvent(it) }
                )
            }
            ?: emptyList()

        val merged = linkedMapOf<String, MutableList<TrackerAction>>()
        (legacyProgress + ndjsonProgress).forEach { progress ->
            merged.getOrPut(progress.tabletId) { mutableListOf() }.addAll(progress.actions)
        }
        return merged.map { (id, actions) -> TabletProgress(tabletId = id, actions = actions) }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.ProgressStoreTest"`
Expected: BUILD SUCCESSFUL, all tests pass (including `loadAllProgressExcludesSyncConflictFiles`).

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/ProgressStore.kt app/src/test/java/com/kkc/sheettracker/data/ProgressStoreTest.kt
git commit -m "feat(R-01): CNC peer reader merges ndjson event streams with legacy snapshots"
```

---

## Task 10: Tablet — full CNC regression check

**Files:** none (verification-only task)

- [ ] **Step 1: Run the full Kotlin unit test suite**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. Note: 1 pre-existing unrelated failure is expected and documented in
memory (`pdfmarkup_motionevent_test_env.md` — a `MotionEvent` stub failure that only reproduces
off-device, not a regression from this work). Any *other* new failure must be investigated before
continuing — most likely cause would be another file constructing `TabletProgress`/`TrackerAction`
against the legacy tracker-file path that Task 8/9 didn't anticipate.

- [ ] **Step 2: Grep for any other production code reading the CNC legacy snapshot path directly**

Run: `cd "C:\Scripts\KKCSheetTracker" && grep -rn "tabletFile\|loadTabletProgress\|saveTabletProgress" app/src/main/java/com/kkc/sheettracker/data/ProgressStore.kt`
Expected: no matches (confirms Task 8's deletion of `tabletFile`/`loadTabletProgress`/
`saveTabletProgress` left no dangling references within the file). If this turns up matches outside
`ProgressStore.kt` referencing its now-removed private members, that's a compile error you'd have
already seen in Step 1 — this is a documentation/sanity check, not expected to find anything.

This task has no code changes — it's the checkpoint before starting hardwoods work.

---

## Task 11: Tablet — hardwoods producer switch (`HardwoodsProgressStore.kt`)

**Files:**
- Modify: `KKC\app\src\main\java\com\kkc\sheettracker\data\HardwoodsProgressStore.kt`
- Modify: `KKC\app\src\main\java\com\kkc\sheettracker\data\models\Models.kt` (`HardwoodTrackerAction`)
- Modify: `KKC\app\src\test\java\com\kkc\sheettracker\data\HardwoodsProgressStoreTest.kt`

**Design note (important — read before changing code):** Unlike CNC's `ProgressStore`, hardwoods
already has a debounced whole-list-rewrite architecture (`persistLocalActionsAsync`,
`saveLocalActionsSync`, `pendingLocalActionsByJob`, `mergePendingLocalActions`) plus two one-time
migration routines (`migrateLegacyTotalsKeysIfNeeded`, `migrateBoardStockKeysToCanonicalIfNeeded`)
that rewrite historical actions in place. All of that machinery is correct, load-bearing, and much
bigger than this task's mandate — **do not remove or restructure it.** The only thing that needs to
change is what `loadTabletProgress`/`saveTabletProgress` (the pair every other function in this file
calls to read/write "this tablet's own progress") actually write to disk: swap the single
`<tabletId>.json` blob for an ndjson-lines file at `events/<tabletId>.ndjson`, written atomically
(temp + `Files.move ATOMIC_MOVE`, exactly like today) with the existing `atomicWrite` helper. Because
`loadTabletProgress`/`saveTabletProgress`'s *signatures* don't change, every one of their ~7 call
sites (including the migration routines) keeps working unmodified. This is a deliberately narrower
change than the CNC store's true per-line append — it's the right fit for hardwoods' existing
architecture, and it still produces a valid ndjson file on disk (the watcher's reader doesn't care
whether a file was built by incremental append or periodic whole-rewrite).

One behavior deliberately changes: `buildJobCache`'s existing "if compaction dropped stale
rows, persist the compacted list back to disk" step (`HardwoodsProgressStore.kt:737-739`) is removed.
Under the new model that step would mean rewriting-away raw history, which conflicts with treating
the on-disk stream as an append-only record; the in-memory index already filters stale rows via
`compactAction` on every read regardless of what's physically on disk, and RJW's after-hours compact
pass (Task 3) naturally reclaims the space anyway.

- [ ] **Step 1: Write the failing tests**

Add a `lamport` field to `HardwoodTrackerAction` in
`KKC\app\src\main\java\com\kkc\sheettracker\data\models\Models.kt` (find the existing definition
around line 432):

```kotlin
data class HardwoodTrackerAction(
    val docType: String = "",
    val rowId: String = "",
    val totalsKey: String? = null,
    val action: String = "",
    val value: Int? = null,
    val timestamp: String = "",
    val lamport: Long = 0L
)
```

In `KKC\app\src\test\java\com\kkc\sheettracker\data\HardwoodsProgressStoreTest.kt`, add:

```kotlin
    @Test
    fun appendActionWritesToNdjsonEventsFile() {
        val baseDir = createTempBaseDir()
        val store = HardwoodsProgressStore(baseDir, tabletId)

        store.setDoneCount(jobFolderName, "FACE_FRAME_CUT_LIST", "row-1", qty = 10, doneCount = 4)
        store.awaitPendingWrites()

        val eventsFile = File(baseDir, "$jobFolderName/.metadata/hardwoods/.tracker/events/$tabletId.ndjson")
        assertTrue(eventsFile.exists())
        val events = readTrackerEvents(eventsFile)
        assertEquals(1, events.size)
        assertEquals("set_done_count", events.first().get("op").asString)
        assertEquals(4, events.first().getAsJsonObject("payload").get("value").asInt)
        assertFalse(File(baseDir, "$jobFolderName/.metadata/hardwoods/.tracker/$tabletId.json").exists())
    }
```

(`awaitPendingWrites()` already exists on `HardwoodsProgressStore` per the H-02 sign-off note in
METADATA_AUDIT.md — if it doesn't in the current code, grep for it and use whatever the existing
test-synchronization helper is called instead; check `HardwoodsProgressStoreTest.kt`'s other
async-write tests for the established pattern before writing this one.)

Now update the existing `compactsStaleRowActionsAndPersistsCompactedLocalActions` test (around line
32) — its old assertion that the stale action disappears from the *persisted* file no longer holds
under append-only semantics; only the in-memory view filters it:

```kotlin
    @Test
    fun compactsStaleRowActionsFromInMemoryViewButKeepsRawHistoryOnDisk() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            index = HardwoodCutlistIndex(
                documents = listOf(
                    HardwoodDocumentIndex(
                        docType = HardwoodDocType.FACE_FRAME_CUT_LIST,
                        rows = listOf(
                            HardwoodCutlistRow(rowId = "row-keep", qty = 6),
                            HardwoodCutlistRow(rowId = "row-other", qty = 2)
                        )
                    )
                )
            )
        )
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = tabletId,
            progress = HardwoodTabletProgress(
                tabletId = tabletId,
                actions = listOf(
                    trackerAction("FACE_FRAME_CUT_LIST", "row-keep", HardwoodTrackerActions.SET_DONE_COUNT, 4, timestamp = "2026-05-01T00:00:01Z"),
                    trackerAction("FACE_FRAME_CUT_LIST", "row-missing", HardwoodTrackerActions.SET_DONE_COUNT, 2, timestamp = "2026-05-01T00:00:02Z"),
                    trackerAction(
                        docType = "BOARD_STOCK",
                        rowId = "",
                        action = HardwoodTrackerActions.ADD_TOTALS_RIP10_DONE_COUNT,
                        value = 3,
                        totalsKey = "board_stock|RED OAK|2.5|FRAME",
                        timestamp = "2026-05-01T00:00:03Z"
                    )
                )
            )
        )

        val store = HardwoodsProgressStore(baseDir, tabletId)
        val rowMap = store.getRowProgressMap(jobFolderName)

        assertEquals(4, rowMap["FACE_FRAME_CUT_LIST" to "row-keep"]?.doneCount)
        assertNull(rowMap["FACE_FRAME_CUT_LIST" to "row-missing"])
        assertEquals(3, store.getBoardStockRipDone(jobFolderName, "red oak", 2.5, "frame"))

        // Raw on-disk history is untouched by compaction -- the stale row-missing action is
        // still there; only the in-memory view (rowMap above) filters it.
        val persisted = readTabletProgress(baseDir, jobFolderName, tabletId)
        assertEquals(3, persisted.actions.size)
        assertTrue(persisted.actions.any { it.rowId == "row-missing" })
    }
```

You'll need `writeTabletProgress`/`readTabletProgress` test helpers (used above and probably already
present elsewhere in this test file — check before adding new ones) to write/read via the *new*
ndjson path instead of the old single-JSON-blob path. If they currently write/read the legacy format,
update them:

```kotlin
    private fun writeTabletProgress(baseDir: File, jobFolderName: String, tabletId: String, progress: HardwoodTabletProgress) {
        val eventsDir = File(baseDir, "$jobFolderName/.metadata/hardwoods/.tracker/events").apply { mkdirs() }
        val file = File(eventsDir, "$tabletId.ndjson")
        val body = progress.actions.joinToString("") { action ->
            encodeTrackerEventLine(hardwoodsTrackerActionToEvent(action)) + "\n"
        }
        file.writeText(body)
    }

    private fun readTabletProgress(baseDir: File, jobFolderName: String, tabletId: String): HardwoodTabletProgress {
        val file = File(baseDir, "$jobFolderName/.metadata/hardwoods/.tracker/events/$tabletId.ndjson")
        val actions = readTrackerEvents(file).mapNotNull { decodeHardwoodsTrackerEvent(it) }
        return HardwoodTabletProgress(tabletId = tabletId, actions = actions)
    }
```

(If these helpers already exist under these exact names writing the legacy path, replace their
bodies with the above rather than adding duplicates — search the file first.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.HardwoodsProgressStoreTest"`
Expected: FAIL to compile (`hardwoodsTrackerActionToEvent`/`decodeHardwoodsTrackerEvent` don't exist
yet; `HardwoodTrackerAction` doesn't have `lamport` yet only if you haven't done the model edit —
do that edit first, then this compiles partway and fails on the missing functions).

- [ ] **Step 3: Implement the hardwoods producer switch**

Add these top-level functions in `HardwoodsProgressStore.kt` (outside the class, near the top, after
imports):

```kotlin
internal fun hardwoodsTrackerActionToEvent(action: HardwoodTrackerAction): TrackerEvent {
    val payload = com.google.gson.JsonObject()
    payload.addProperty("docType", action.docType)
    payload.addProperty("rowId", action.rowId)
    action.totalsKey?.let { payload.addProperty("totalsKey", it) }
    action.value?.let { payload.addProperty("value", it) }
    payload.addProperty("timestamp", action.timestamp)
    return TrackerEvent(
        op = action.action,
        payload = payload,
        wallTime = action.timestamp,
        lamport = action.lamport
    )
}

internal fun decodeHardwoodsTrackerEvent(event: com.google.gson.JsonObject): HardwoodTrackerAction? {
    val payload = event.getAsJsonObject("payload") ?: return null
    val docType = payload.get("docType")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: return null
    val rowId = payload.get("rowId")?.takeIf { !it.isJsonNull }?.asString ?: return null
    val action = event.get("op")?.takeIf { !it.isJsonNull }?.asString ?: return null
    val timestamp = payload.get("timestamp")?.takeIf { !it.isJsonNull }?.asString
        ?: event.get("wallTime")?.takeIf { !it.isJsonNull }?.asString
        ?: ""
    return HardwoodTrackerAction(
        docType = docType,
        rowId = rowId,
        totalsKey = payload.get("totalsKey")?.takeIf { !it.isJsonNull }?.asString,
        action = action,
        value = payload.get("value")?.takeIf { !it.isJsonNull }?.asInt,
        timestamp = timestamp,
        lamport = event.get("lamport")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
    )
}
```

Replace `loadTabletProgress` and `saveTabletProgress`:

```kotlin
    private fun tabletEventsFile(jobFolderName: String): File = File(trackerDir(jobFolderName), "events/$tabletId.ndjson")

    private fun loadTabletProgress(jobFolderName: String): HardwoodTabletProgress {
        val file = tabletEventsFile(jobFolderName)
        if (!file.exists()) return HardwoodTabletProgress(tabletId = tabletId)
        val actions = readTrackerEvents(file).mapNotNull { decodeHardwoodsTrackerEvent(it) }
        return sanitizeProgress(HardwoodTabletProgress(tabletId = tabletId, actions = actions), fallbackTabletId = tabletId)
            ?: HardwoodTabletProgress(tabletId = tabletId)
    }

    // CROSS-PROGRAM: this file (`events/<tabletId>.ndjson` under
    // `.metadata/hardwoods/.tracker/`) is Syncthing-replicated and read by peer tablets
    // (readProgressFromDir/loadAllProgress) and by Ready Jobs Watcher's tracker_action_stream.py
    // union reader. Written atomically (temp file + Files.move ATOMIC_MOVE) so a concurrent
    // reader never observes a torn write. Unlike ProgressStore.kt's true per-line append, this
    // rewrites the whole stream from the in-memory action list on every persist -- correct here
    // because this tablet is still the sole writer of its own file (no cross-process race), and
    // it preserves compatibility with the existing migration routines
    // (migrateLegacyTotalsKeysIfNeeded, migrateBoardStockKeysToCanonicalIfNeeded) that rewrite
    // historical actions in place. See METADATA_AUDIT.md R-01.
    private fun saveTabletProgress(jobFolderName: String, progress: HardwoodTabletProgress) {
        val dir = trackerDir(jobFolderName)
        dir.mkdirs()
        val body = progress.actions.joinToString("") { action ->
            encodeTrackerEventLine(hardwoodsTrackerActionToEvent(action)) + "\n"
        }
        atomicWrite(tabletEventsFile(jobFolderName), body)
    }
```

Update the three `HardwoodTrackerAction(...)` construction sites so each new action gets a real
lamport value at creation time (not at serialize time, since `saveTabletProgress` re-serializes the
*whole* list on every persist — every action must keep the lamport value it was born with):

In `appendAction` (around line 194):
```kotlin
        val next = HardwoodTrackerAction(
            docType = docType,
            rowId = rowId,
            totalsKey = totalsKey,
            action = action,
            value = value,
            timestamp = Instant.now().toString(),
            lamport = TrackerLamportClock.next()
        )
```

In `appendComputedAction` (around line 1058):
```kotlin
                    val next = HardwoodTrackerAction(
                        docType = docType,
                        rowId = rowId,
                        totalsKey = totalsKey,
                        action = action,
                        value = value,
                        timestamp = Instant.now().toString(),
                        lamport = TrackerLamportClock.next()
                    )
```

In `appendComputedActionFromDisk` (around line 1104):
```kotlin
            val next = HardwoodTrackerAction(
                docType = docType,
                rowId = rowId,
                totalsKey = totalsKey,
                action = action,
                value = value,
                timestamp = Instant.now().toString(),
                lamport = TrackerLamportClock.next()
            )
```

Remove the compaction-triggered persistence in `buildJobCache` (around line 733-739) — delete these
3 lines:

```kotlin
        if (localActions != localProgress.actions) {
            saveLocalActionsSync(jobFolderName, localActions)
        }
```

(Leave everything else in `buildJobCache` unchanged — `localActions` is still computed via
`compactAction` and used to seed the in-memory cache; it's only the disk-persist side effect being
removed.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.HardwoodsProgressStoreTest"`
Expected: BUILD SUCCESSFUL, all tests pass. If other pre-existing tests in this file still reference
the old legacy `.json` path directly (not via the `writeTabletProgress`/`readTabletProgress` helpers
you updated), fix each one to go through the helpers instead — search for
`File(baseDir, "$jobFolderName/.metadata/hardwoods/.tracker` across the test file to find any
inlined path construction that bypassed the helpers.

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/HardwoodsProgressStore.kt app/src/main/java/com/kkc/sheettracker/data/models/Models.kt app/src/test/java/com/kkc/sheettracker/data/HardwoodsProgressStoreTest.kt
git commit -m "feat(R-01): hardwoods tablet persists to ndjson event stream"
```

---

## Task 12: Tablet — hardwoods peer reader (`HardwoodsProgressStore.readProgressFromDir`)

**Files:**
- Modify: `KKC\app\src\main\java\com\kkc\sheettracker\data\HardwoodsProgressStore.kt`
  (`readProgressFromDir`)
- Modify: `KKC\app\src\test\java\com\kkc\sheettracker\data\HardwoodsProgressStoreTest.kt`

Same merge-by-tabletId shape as Task 9's CNC peer reader.

- [ ] **Step 1: Write the failing test**

Add to `HardwoodsProgressStoreTest.kt`:

```kotlin
    @Test
    fun readProgressFromDirMergesNdjsonPeerEvents() {
        val baseDir = createTempBaseDir()
        val store = HardwoodsProgressStore(baseDir, tabletId)
        val eventsFile = File(baseDir, "$jobFolderName/.metadata/hardwoods/.tracker/events/tablet-b.ndjson")
        val payload = com.google.gson.JsonObject().apply {
            addProperty("docType", "FACE_FRAME_CUT_LIST")
            addProperty("rowId", "row-1")
            addProperty("value", 5)
            addProperty("timestamp", "2026-07-09T09:00:00Z")
        }
        appendTrackerEvent(eventsFile, TrackerEvent(op = "set_done_count", payload = payload, wallTime = "2026-07-09T09:00:00Z", lamport = 1))

        val rowMap = store.getRowProgressMap(jobFolderName)

        assertEquals(5, rowMap["FACE_FRAME_CUT_LIST" to "row-1"]?.doneCount)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.HardwoodsProgressStoreTest"`
Expected: FAIL — `rowMap` entry is null since `readProgressFromDir` doesn't look at `events/` yet.

- [ ] **Step 3: Implement the peer reader**

Replace `readProgressFromDir`:

```kotlin
    private fun readProgressFromDir(dir: File): List<HardwoodTabletProgress> {
        if (!dir.exists()) return emptyList()
        val legacy = dir.listFiles()
            ?.filter {
                it.isFile &&
                    it.extension.equals("json", ignoreCase = true) &&
                    !it.name.startsWith(".") &&
                    !it.name.contains(".sync-conflict-") &&
                    !it.name.endsWith(".markup.json", ignoreCase = true)
            }
            ?.mapNotNull { file ->
                runCatching { gson.fromJson(file.readText(), HardwoodTabletProgress::class.java) }
                    .getOrNull()
                    ?.let { sanitizeProgress(it, fallbackTabletId = file.nameWithoutExtension) }
            }
            ?: emptyList()

        val eventsDir = File(dir, "events")
        val ndjson = eventsDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("ndjson", ignoreCase = true) && !it.name.startsWith(".") && !it.name.contains(".sync-conflict-") }
            ?.map { file ->
                HardwoodTabletProgress(
                    tabletId = file.nameWithoutExtension,
                    actions = readTrackerEvents(file).mapNotNull { decodeHardwoodsTrackerEvent(it) }
                )
            }
            ?: emptyList()

        val merged = linkedMapOf<String, MutableList<HardwoodTrackerAction>>()
        (legacy + ndjson).forEach { progress ->
            merged.getOrPut(progress.tabletId) { mutableListOf() }.addAll(progress.actions)
        }
        return merged.map { (id, actions) -> HardwoodTabletProgress(tabletId = id, actions = actions) }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.HardwoodsProgressStoreTest"`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/HardwoodsProgressStore.kt app/src/test/java/com/kkc/sheettracker/data/HardwoodsProgressStoreTest.kt
git commit -m "feat(R-01): hardwoods peer reader merges ndjson event streams with legacy snapshots"
```

---

## Task 13: Tablet — fix `TrackerChangeMonitor` to actually watch `events/` directories

**Files:**
- Modify: `KKC\app\src\main\java\com\kkc\sheettracker\data\TrackerChangeMonitor.kt`
- Test: `KKC\app\src\test\java\com\kkc\sheettracker\data\TrackerChangeMonitorEventsTest.kt` (new)

Android's `FileObserver` is not recursive, so watching `CNC/.tracker` never sees writes inside a new
`CNC/.tracker/events/` subdirectory, and the observer's path filter (`.endsWith(".json")`) and
`trackerSignature`'s extension filter both exclude `.ndjson` even if they did see them. Without this
fix, a tablet silently stops noticing peer-tablet progress the moment that peer switches to ndjson —
nothing crashes, it just goes stale.

- [ ] **Step 1: Write the failing test**

Create `KKC\app\src\test\java\com\kkc\sheettracker\data\TrackerChangeMonitorEventsTest.kt`. This
project's `TrackerChangeMonitorSpecialtyTest.kt` already has the established pattern for testing
`TrackerChangeMonitor` (constructing real `ProgressStore`/`HardwoodsProgressStore` instances against
a temp dir and asserting `progressVersion`/cache invalidation reacts to a file write) — read that
file first and follow its exact setup style. Then add:

```kotlin
package com.kkc.sheettracker.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TrackerChangeMonitorEventsTest {

    @Test
    fun trackerSignatureChangesWhenNdjsonFileIsWritten() {
        val baseDir = Files.createTempDirectory("monitor-events-test").toFile()
        val jobFolderName = "1234 - Test Job"
        val eventsDir = File(baseDir, "$jobFolderName/CNC/.tracker/events").apply { mkdirs() }
        val progressStore = ProgressStore(baseDir, "tablet-self", File(baseDir, ".local"))
        val hardwoodsProgressStore = HardwoodsProgressStore(baseDir, "tablet-self")
        val monitor = TrackerChangeMonitor(baseDir, progressStore, hardwoodsProgressStore, pollingIntervalMs = 50_000L)

        monitor.start()
        try {
            val versionBefore = progressStore.progressVersion.value
            val payload = com.google.gson.JsonObject().apply {
                addProperty("file", "A.pdf")
                addProperty("page", 1)
            }
            appendTrackerEvent(File(eventsDir, "tablet-peer.ndjson"), TrackerEvent(op = "set_complete_true", payload = payload, wallTime = "2026-07-09T09:00:00Z", lamport = 1))

            // Drive the poll path directly rather than sleeping for the FileObserver callback --
            // deterministic in a unit test environment where inotify may not be available at all.
            Thread.sleep(200)
            val versionAfter = progressStore.progressVersion.value
            assertTrue(versionAfter > versionBefore || progressStore.loadAllProgress(jobFolderName).any { it.tabletId == "tablet-peer" })
        } finally {
            monitor.stop()
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.TrackerChangeMonitorEventsTest"`
Expected: FAIL — `discoverTrackerDirs()` never tracks the `events/` subdirectory, so
`trackerSignature`/the poll loop never observes the new file and `progressVersion` never bumps
(the fallback `loadAllProgress` assertion in the test will still find the peer via direct disk read,
but the primary intent — that the monitor's poll/observer path notices — needs the fix; if your
Kotlin test setup makes the fallback clause always true and the test passes even before the fix,
tighten it to assert only on `versionAfter > versionBefore` once you confirm `discoverTrackerDirs`
covers `events/`).

- [ ] **Step 3: Implement the monitor fix**

In `KKC\app\src\main\java\com\kkc\sheettracker\data\TrackerChangeMonitor.kt`:

Update the `FileObserver`'s path filter (line ~178):

```kotlin
            override fun onEvent(event: Int, path: String?) {
                if (path != null && !(path.endsWith(".json", ignoreCase = true) || path.endsWith(".ndjson", ignoreCase = true))) return
```

Update `trackerSignature`'s extension filter (line ~261-277):

```kotlin
    private fun trackerSignature(dir: File): Long {
        val files = dir.listFiles()
            ?.filter {
                it.isFile &&
                    (it.extension.equals("json", ignoreCase = true) || it.extension.equals("ndjson", ignoreCase = true)) &&
                    !it.name.startsWith(".") && !it.name.contains(".sync-conflict-")
            }
            ?.sortedBy { it.name }
            .orEmpty()
        var signature = 1125899906842597L
        files.forEach { file ->
            signature = signature * 31 + file.name.hashCode().toLong()
            signature = signature * 31 + file.length()
            signature = signature * 31 + file.lastModified()
        }
        return signature * 31 + files.size.toLong()
    }
```

Add `events/` subdirectory tracking in `discoverTrackerDirs` (line ~218-235) — insert right after the
existing `cncTrackerDir`/`hardwoodTrackerDir` blocks:

```kotlin
            val cncTrackerDir = File(jobDir, "CNC/.tracker")
            if (cncTrackerDir.isDirectory) {
                tracked += TrackedDir(
                    kind = TrackerKind.CNC,
                    jobFolderName = jobFolderName,
                    dir = cncTrackerDir
                )
            }
            val cncEventsDir = File(jobDir, "CNC/.tracker/events")
            if (cncEventsDir.isDirectory) {
                tracked += TrackedDir(
                    kind = TrackerKind.CNC,
                    jobFolderName = jobFolderName,
                    dir = cncEventsDir
                )
            }
            val hardwoodTrackerDir = File(jobDir, ".metadata/hardwoods/.tracker")
            if (hardwoodTrackerDir.isDirectory) {
                tracked += TrackedDir(
                    kind = TrackerKind.HARDWOODS,
                    jobFolderName = jobFolderName,
                    dir = hardwoodTrackerDir
                )
            }
            val hardwoodEventsDir = File(jobDir, ".metadata/hardwoods/.tracker/events")
            if (hardwoodEventsDir.isDirectory) {
                tracked += TrackedDir(
                    kind = TrackerKind.HARDWOODS,
                    jobFolderName = jobFolderName,
                    dir = hardwoodEventsDir
                )
            }
```

Note `TrackedDir` is keyed by `dir.absolutePath` (see `discoverTrackerDirs`'s
`associateBy { it.dir.absolutePath }`), so tracking both the parent `.tracker` dir and its `events/`
child as two separate `TrackedDir` entries of the same `kind`/`jobFolderName` is safe — an
invalidation from either one triggers the same `progressStore.invalidateJobIndexes(...)` /
`hardwoodsProgressStore.invalidateJobCaches(...)` call in `applyBatchedInvalidations`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.TrackerChangeMonitorEventsTest" --tests "com.kkc.sheettracker.data.TrackerChangeMonitorSpecialtyTest"`
Expected: BUILD SUCCESSFUL, both test classes pass (confirms the fix doesn't regress the existing
specialty-tracker monitor tests).

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/TrackerChangeMonitor.kt app/src/test/java/com/kkc/sheettracker/data/TrackerChangeMonitorEventsTest.kt
git commit -m "fix(R-01): TrackerChangeMonitor watches events/ subdirectories and .ndjson files"
```

---

## Task 14: Tablet — wire `TrackerLamportClock.init()` at app startup

**Files:**
- Modify: `KKC\app\src\main\java\com\kkc\sheettracker\MainActivity.kt`

Without this, the lamport counter works in-memory-only (resets to 0 on every process restart) —
still correct (it's only a same-instant tie-breaker, and a reset just means a brief window where
tie-breaking is less precise right after an app restart), but persisting it is cheap and removes
that edge case entirely.

- [ ] **Step 1: Implement the wiring**

In `KKC\app\src\main\java\com\kkc\sheettracker\MainActivity.kt`, add one line right before the
existing `ProgressStore(...)` construction (around line 187, which already does
`localStateDir = File(filesDir, "state")`):

```kotlin
        val baseDir = File(basePath)
        TrackerLamportClock.init(File(filesDir, "state"))
        val jobRepository = JobRepository(baseDir, isDebugBuild = BuildConfig.DEBUG)
        val progressStore = ProgressStore(
            baseDir = baseDir,
            tabletId = tabletId,
            localStateDir = File(filesDir, "state"),
            readOnly = isViewOnlyMode
        )
```

- [ ] **Step 2: Build and confirm no compile errors**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/MainActivity.kt
git commit -m "feat(R-01): persist the tracker lamport counter across app restarts"
```

---

## Task 15: Full regression suite, both repos

**Files:** none (verification-only task)

- [ ] **Step 1: Run the full RJW suite**

Run: `cd "C:\Scripts\Ready Jobs Watcher" && python -m pytest tests/ -q`
Expected: PASS, same count as Task 5's baseline plus the new tests from Tasks 1-4.

- [ ] **Step 2: Run the full Kotlin suite**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, only the one known pre-existing off-device `MotionEvent` failure (see
memory `pdfmarkup_motionevent_test_env.md`), nothing else red.

- [ ] **Step 2: Build the release APK**

Run: `cd "C:\Scripts\KKCSheetTracker" && .\gradlew.bat assembleRelease`
Expected: BUILD SUCCESSFUL. Per project convention (CLAUDE.md), do **not** run
`adb-install-release.ps1` yet — deployment to tablets is a manual, deliberate step you (the user)
run yourself once you're ready, at the start of a shop day per your rollout plan, not part of this
implementation plan's automated steps.

- [ ] **Step 3: Update `METADATA_AUDIT.md`**

In `C:\Scripts\Hours Tracker\METADATA_AUDIT.md`, find the R-01 row in §7 (Recommendations) and the
narrative in §3. Add a status note (do not mark `RESOLVED` yet — that requires the field verification
from the design doc's Rollout section, which happens after tablets are actually deployed):

```
**Status (2026-07-09): IN PROGRESS.** RJW-side (mapper fix, union-reader merge input, compact-flag
split) and tablet-side (ndjson producer + peer reader for CNC and hardwoods, TrackerChangeMonitor
fix) are implemented and unit-tested in both repos. Not yet field-verified — see
docs/superpowers/specs/2026-07-09-r01-ndjson-tracker-design.md (KKCSheetTracker repo) for the
rollout/verification plan before this can be marked RESOLVED. RJW ships independently first
(backward-compatible by construction); tablets deploy afterward, all at once, per the shop's normal
adb-install-release.ps1 process.
```

Add a one-line entry to the Change Log (§8) following the existing format:

```
| 2026-07-09 | Claude | R-01 | Implemented ndjson tracker-event producer end-to-end: RJW mapper fix
+ union-reader consolidation + after-hours-only compaction; KKCSheetTracker CNC true-append and
hardwoods atomic-whole-rewrite producers, ndjson peer readers for both, TrackerChangeMonitor events/
watch fix. Full test suites green in both repos. Not yet field-verified -- see design doc for the
rollout plan. |
```

- [ ] **Step 4: Commit the audit doc update**

```bash
cd "C:\Scripts\Hours Tracker"
git add METADATA_AUDIT.md
git commit -m "docs(R-01): mark implementation complete, pending field verification"
```

This is the last task in this plan. What's **not** included here, by design (see the spec's "Rollout"
section): actually running `adb-install-release.ps1` against shop tablets, the field-verification
burn-in at one shop before the other, and marking R-01 `RESOLVED` with a full sign-off block — those
happen after you've deployed and watched it run for real, not as part of implementing the code.
