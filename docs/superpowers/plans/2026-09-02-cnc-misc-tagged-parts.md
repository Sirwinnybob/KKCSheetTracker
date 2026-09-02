# CNC Miscellaneous Tagged Parts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Miscellaneous" CNC part category (blue) that mirrors the existing "Remake" category (purple) end-to-end: PDF splitter trigger → sidecar metadata → Hours Tracker worker cache/queue publication → Android tablet dashboard.

**Architecture:** Three independent repos, touched in dependency order (splitter writes the field first, since nothing downstream has anything to read otherwise): `C:\Scripts\PGM_Sorting` (PDF splitter/run processor), `C:\Scripts\Hours Tracker` (`ready_jobs_worker_core`, the live production Ready Jobs worker), `C:\Scripts\KKCSheetTracker` (Android tablet). Miscellaneous is a second, independently-named field with the same shape as Remake (`miscLabel`/`isMisc` alongside `remakeLabel`/`isRemake`) — not a generalization into a shared category enum, matching the existing pattern where Remake itself has no such abstraction. No part-level tracking (no `RemadePartMetadata`/selected-parts equivalent) — Miscellaneous is a plain label.

**Tech Stack:** Python 3 + pytest (PGM_Sorting splitter, Hours Tracker worker), Kotlin + JUnit + Gson (KKCSheetTracker Android/Jetpack Compose).

**Spec:** [`docs/superpowers/specs/2026-09-02-cnc-misc-tagged-parts-design.md`](../specs/2026-09-02-cnc-misc-tagged-parts-design.md)

**Correction from spec during verification:** the spec listed `AppScaffold.kt:678` as a "job-card status segment misc row (parallel remake row)" to mirror. On inspection, that line only reuses the `remakeBg` color token for an unrelated "Mark Re-nested" toggle button — there is no `isRemake`-driven row in `AppScaffold.kt` to mirror. The real job-card status-segment mirror is `UnifiedModeSpecs.kt:83` (builds `MaterialSegmentData.isRemake`) and `StatusComponents.kt:80-84,729-730` (`isRemakeIncomplete` → `colors.remakeBg`) — both covered by Task 11 below. No `AppScaffold.kt` changes are needed.

**Correction from spec during verification:** the spec implied `process_run_folders_v2.py`'s `REMAKE_STATE_FILE` handling should be mirrored for Miscellaneous. On inspection, every `REMAKE_STATE_FILE` use in that file (`load_remake_state`, `save_remake_state`, `find_pending_remake_label`, `find_pending_state_key_for_batch`, `update_remake_state_after_copy`) exists solely to consume the "pending remake batch" — the record of which *specific selected parts* still need to be copied into a remake output, created by the part-selection dialog (`prompt_for_remake_parts`) in the splitter. Miscellaneous has no part-selection step (per the "simple label only" design decision), so it has no pending batch to consume — `process_run_folders_v2.py` needs no changes at all. `misc_state.json`'s only job is sequential label numbering at split time, entirely inside `split_pdfs_gui_v3.py`.

---

## Part A: PGM_Sorting (PDF splitter)

### Task 1: Miscellaneous label numbering (`misc_state.json`)

**Files:**
- Modify: `C:\Scripts\PGM_Sorting\split_pdfs_gui_v3.py:88` (new constant), `:1308` (new methods, inserted after `_next_remake_label`)
- Test: `C:\Scripts\PGM_Sorting\test_splitter_v3.py`

- [ ] **Step 1: Write the failing tests**

Add to `C:\Scripts\PGM_Sorting\test_splitter_v3.py` (place near the other `ProgressApp`-instantiation tests, e.g. after `test_find_job_folder_uses_a_letter_phase_fallback_only_when_unambiguous`):

```python
def test_next_misc_label_increments_and_shares_state_with_lettered_job_phases(tmp_path, monkeypatch):
    """Numbering must match _next_remake_label's behavior: '530' and its
    lettered split phase '530b' share the same base-number state entry, so
    the counter keeps climbing across split jobs instead of colliding."""
    state_path = tmp_path / "misc_state.json"
    monkeypatch.setattr(splitter, "MISC_STATE_FILE", str(state_path))

    app = object.__new__(splitter.ProgressApp)

    first = app._next_misc_label("530", "530 - MAIN JOB")
    second = app._next_misc_label("530", "530 - MAIN JOB")
    third_from_lettered_phase = app._next_misc_label("530b", "530b - ADD ON")

    assert first == "001 MISC"
    assert second == "002 MISC"
    assert third_from_lettered_phase == "003 MISC"


def test_next_misc_label_is_independent_per_unrelated_job(tmp_path, monkeypatch):
    state_path = tmp_path / "misc_state.json"
    monkeypatch.setattr(splitter, "MISC_STATE_FILE", str(state_path))

    app = object.__new__(splitter.ProgressApp)

    app._next_misc_label("530", "530 - MAIN JOB")
    other_job_first = app._next_misc_label("640", "640 - OTHER JOB")

    assert other_job_first == "001 MISC"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `C:\Scripts\PGM_Sorting\.venv_ocr\Scripts\pytest.exe test_splitter_v3.py -v -k next_misc_label` (from `C:\Scripts\PGM_Sorting`)
Expected: FAIL with `AttributeError: 'ProgressApp' object has no attribute '_next_misc_label'` (and `MISC_STATE_FILE` not defined on the module).

- [ ] **Step 3: Write minimal implementation**

In `split_pdfs_gui_v3.py`, add the constant next to `REMAKE_STATE_FILE` (line 88):

```python
REMAKE_STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "remake_state.json")
MISC_STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "misc_state.json")
```

Then, immediately after `_next_remake_label` (after line 1308, before `_append_pending_remake_batch`), add:

```python
    def _load_misc_state(self):
        if not os.path.exists(MISC_STATE_FILE):
            return {"jobs": {}}
        try:
            with open(MISC_STATE_FILE, "r", encoding="utf-8") as f:
                raw = json.load(f)
            if not isinstance(raw, dict):
                return {"jobs": {}}
            raw.setdefault("jobs", {})
            return raw
        except Exception as e:
            logging.error(f"Failed to load misc state: {e}")
            return {"jobs": {}}

    def _save_misc_state(self, state):
        try:
            with open(MISC_STATE_FILE, "w", encoding="utf-8") as f:
                json.dump(state, f, indent=2, ensure_ascii=False)
        except Exception as e:
            logging.error(f"Failed to save misc state: {e}")

    def _next_misc_label(self, job_number, job_folder_name):
        state = self._load_misc_state()
        jobs = state.setdefault("jobs", {})
        keys = {
            self._normalize_job_key(job_number),
            self._normalize_job_key(job_folder_name),
            self._normalize_job_key(self._job_base_number(job_number)),
            self._normalize_job_key(self._job_base_number(job_folder_name)),
        }
        keys = {k for k in keys if k}
        next_idx = 1
        for k in keys:
            entry = jobs.get(k, {})
            idx = int(entry.get("nextIndex", 1))
            if idx > next_idx:
                next_idx = idx
        label = f"{next_idx:03d} MISC"
        for k in keys:
            entry = jobs.setdefault(k, {})
            entry["nextIndex"] = next_idx + 1
        self._save_misc_state(state)
        return label
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `C:\Scripts\PGM_Sorting\.venv_ocr\Scripts\pytest.exe test_splitter_v3.py -v -k next_misc_label`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\PGM_Sorting"
git add split_pdfs_gui_v3.py test_splitter_v3.py
git commit -m "feat: add misc_state.json sequential numbering for Miscellaneous labels"
```

---

### Task 2: `m`/`M` filename prefix detection and label assignment

**Files:**
- Modify: `C:\Scripts\PGM_Sorting\split_pdfs_gui_v3.py:3306-3382`
- Test: `C:\Scripts\PGM_Sorting\test_splitter_v3.py`

- [ ] **Step 1: Write the failing test**

Add to `test_splitter_v3.py`:

```python
def test_misc_prefix_regex_matches_m_and_capital_m_but_not_remake_or_custom():
    misc_pattern = re.compile(r'^[mM]\s?')
    assert misc_pattern.match("m123")
    assert misc_pattern.match("M 123")
    assert not misc_pattern.match("r123")
    assert not misc_pattern.match("c123")
    assert not misc_pattern.match("123")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `C:\Scripts\PGM_Sorting\.venv_ocr\Scripts\pytest.exe test_splitter_v3.py -v -k misc_prefix_regex`
Expected: FAIL — this test is self-contained (asserts against a locally-defined pattern, not splitter code yet), so it should actually PASS immediately since it doesn't touch splitter internals. Skip to Step 3 anyway: the real regression coverage comes from Task 3's sidecar-metadata test, which exercises the actual `is_misc` detection inside `split_pdfs_gui_v3.py`. Keep this test as a fast, isolated documentation of the exact pattern being added to the module.

- [ ] **Step 3: Write minimal implementation**

In `split_pdfs_gui_v3.py`, replace lines 3308-3319:

```python
        # Check for prefix at the beginning (e.g., 'r123' for remake or 'c123' for custom)
        custom_prefix = None
        remake_label = None
        selected_remake_parts = []
        is_remake = bool(re.match(r'^[rR]\s?', job_number_raw))
        is_custom = bool(re.match(r'^[cC]\s?', job_number_raw))

        # Clean the job number by removing the prefix
        if is_remake or is_custom:
            job_number = re.sub(r'^[rcRC]\s?', '', job_number_raw)
        else:
            job_number = job_number_raw
```

with:

```python
        # Check for prefix at the beginning (e.g., 'r123' for remake, 'm123'
        # for miscellaneous, or 'c123' for custom)
        custom_prefix = None
        remake_label = None
        misc_label = None
        selected_remake_parts = []
        is_remake = bool(re.match(r'^[rR]\s?', job_number_raw))
        is_misc = bool(re.match(r'^[mM]\s?', job_number_raw))
        is_custom = bool(re.match(r'^[cC]\s?', job_number_raw))

        # Clean the job number by removing the prefix
        if is_remake or is_misc or is_custom:
            job_number = re.sub(r'^[rcmRCM]\s?', '', job_number_raw)
        else:
            job_number = job_number_raw
```

Then insert a new `elif is_misc:` branch between the `if is_remake:` block (ends line 3367) and `elif is_custom:` (line 3368):

```python
        elif is_misc:
            resolved_job_folder = job_folder_name
            if not resolved_job_folder:
                try:
                    resolved_job_folder = os.path.basename(os.path.dirname(output_dir))
                except Exception:
                    resolved_job_folder = None

            misc_label = sanitize_path_component(
                self._next_misc_label(job_number, resolved_job_folder or job_number)
            )
            custom_prefix = misc_label
            self.queue.put(
                (
                    f"'{os.path.basename(pdf_path)}' is a miscellaneous batch: {misc_label}",
                    False
                )
            )
        elif is_custom:
```

(Only the `elif is_custom:` line already exists — this inserts the new branch immediately before it, and the rest of the existing `elif is_custom:` body is unchanged.)

- [ ] **Step 4: Run test to verify it passes**

Run: `C:\Scripts\PGM_Sorting\.venv_ocr\Scripts\pytest.exe test_splitter_v3.py -v -k misc_prefix_regex`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\PGM_Sorting"
git add split_pdfs_gui_v3.py test_splitter_v3.py
git commit -m "feat: detect m/M filename prefix and assign Miscellaneous label"
```

---

### Task 3: Write `miscLabel` into the CNC sidecar metadata JSON

**Files:**
- Modify: `C:\Scripts\PGM_Sorting\split_pdfs_gui_v3.py:2935-2947` (`generate_sidecar_metadata` signature), `:3134-3136` (metadata write), `:3184-3226` (caller wiring), `:3606-3612` (task dict)
- Test: `C:\Scripts\PGM_Sorting\test_splitter_v3.py`

- [ ] **Step 1: Write the failing test**

Add to `test_splitter_v3.py`:

```python
def test_generate_sidecar_metadata_writes_misc_label(tmp_path, monkeypatch):
    """miscLabel must land in the sidecar JSON exactly like remakeLabel does,
    with no page-level 'misc' block (Miscellaneous has no part-level tracking)."""
    app = object.__new__(splitter.ProgressApp)
    app.emit_status = lambda *_a, **_k: None
    app.queue = type("Q", (), {"put": lambda self, *_a, **_k: None})()

    output_dir = tmp_path / "100 - Job"
    output_dir.mkdir()
    output_filename = "100 - Misc Maple.pdf"
    doc = fitz.open()
    doc.new_page()
    doc.save(str(output_dir / output_filename))
    doc.close()
    reopened = fitz.open(str(output_dir / output_filename))

    app.generate_sidecar_metadata(
        reopened,
        str(output_dir),
        output_filename,
        job_number="100",
        job_name="Job",
        material_name="Misc Maple",
        misc_label="001 MISC",
        skip_ocr=True,
    )

    metadata_path = output_dir / ".metadata" / "100 - Misc Maple.json"
    payload = json.loads(metadata_path.read_text(encoding="utf-8"))
    assert payload["miscLabel"] == "001 MISC"
    assert "misc" not in payload.get("pages", [{}])[0]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `C:\Scripts\PGM_Sorting\.venv_ocr\Scripts\pytest.exe test_splitter_v3.py -v -k generate_sidecar_metadata_writes_misc_label`
Expected: FAIL with `TypeError: generate_sidecar_metadata() got an unexpected keyword argument 'misc_label'`

- [ ] **Step 3: Write minimal implementation**

In `split_pdfs_gui_v3.py`, update the `generate_sidecar_metadata` signature (line 2935-2947):

```python
    def generate_sidecar_metadata(
        self,
        output_doc,
        output_dir,
        output_filename,
        job_number,
        job_name,
        material_name,
        remake_context=None,
        misc_label=None,
        image_post_scale: float = 1.0,
        skip_ocr: bool = False,
        is_tagged_format: bool = False
    ):
```

Then, near the existing metadata write at line 3134-3135:

```python
            if remake_label:
                metadata["remakeLabel"] = remake_label
```

add immediately after:

```python
            if misc_label:
                metadata["miscLabel"] = misc_label
```

Update the caller (lines 3184-3226): add the `misc_label` local pull next to `remake_label` (line 3187) —

```python
        remake_label = task.get("remake_label")
        remake_selected_parts = task.get("remake_selected_parts", [])
        misc_label = task.get("misc_label")
```

— and pass it into the `generate_sidecar_metadata(...)` call (which currently passes `remake_context=remake_context,` around line 3224):

```python
            metadata_ok = self.generate_sidecar_metadata(
                doc,
                output_dir,
                output_filename,
                job_number,
                job_name,
                material_name,
                remake_context=remake_context,
                misc_label=misc_label,
```

(leave the remaining existing arguments on the following lines unchanged.)

Finally, update the task dict construction (lines 3606-3612):

```python
                        "job_number": output_job_number,
                        "job_name": job_name,
                        "material_name": final_material_name,
                        "remake_label": remake_label,
                        "remake_selected_parts": selected_for_material,
                        "misc_label": misc_label,
                        "is_tagged_format": is_tagged_format
                    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `C:\Scripts\PGM_Sorting\.venv_ocr\Scripts\pytest.exe test_splitter_v3.py -v -k generate_sidecar_metadata_writes_misc_label`
Expected: PASS

- [ ] **Step 5: Run the full splitter test file to check for regressions**

Run: `C:\Scripts\PGM_Sorting\.venv_ocr\Scripts\pytest.exe test_splitter_v3.py -v`
Expected: All PASS (no regressions in remake/custom-prefix behavior)

- [ ] **Step 6: Commit**

```bash
cd "C:\Scripts\PGM_Sorting"
git add split_pdfs_gui_v3.py test_splitter_v3.py
git commit -m "feat: write miscLabel into CNC sidecar metadata JSON"
```

---

## Part B: Hours Tracker worker (`ready_jobs_worker_core`)

### Task 4: `isMisc` in `cache_index_payload.py`

**Files:**
- Modify: `C:\Scripts\Hours Tracker\backend\ready_jobs_worker_core\cache_index_payload.py:89-104`
- Test: `C:\Scripts\Hours Tracker\backend\tests\test_ready_jobs_worker_core_cache_index_payload.py`

- [ ] **Step 1: Write the failing test**

Add to `test_ready_jobs_worker_core_cache_index_payload.py` (mirror whatever existing `isRemake` assertion pattern that file already uses for `compute_cnc_progress`; if none exists yet, add this self-contained test):

```python
def test_compute_cnc_progress_reports_is_misc_alongside_is_remake():
    from ready_jobs_worker_core.cache_index_payload import compute_cnc_progress

    materials = [
        {
            "materialName": "Misc Maple",
            "metadata": {"miscLabel": "001 MISC"},
        },
        {
            "materialName": "Remake Oak",
            "metadata": {"remakeLabel": "001 REMAKE"},
        },
        {
            "materialName": "Plain Birch",
            "metadata": {},
        },
    ]

    result = compute_cnc_progress(materials, actions=[])

    by_name = {m["materialName"]: m for m in result["materials"]}
    assert by_name["Misc Maple"]["isMisc"] is True
    assert by_name["Misc Maple"]["isRemake"] is False
    assert by_name["Remake Oak"]["isRemake"] is True
    assert by_name["Remake Oak"]["isMisc"] is False
    assert by_name["Plain Birch"]["isMisc"] is False
    assert by_name["Plain Birch"]["isRemake"] is False
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `C:\Scripts\Hours Tracker`): `backend\venv\Scripts\python.exe -m pytest backend\tests\test_ready_jobs_worker_core_cache_index_payload.py -v -k is_misc`
Expected: FAIL with `KeyError: 'isMisc'`

- [ ] **Step 3: Write minimal implementation**

In `cache_index_payload.py`, update `compute_cnc_progress` (around line 91-103):

```python
        material_meta = material.get("metadata") or {}
        is_remake = bool(material_meta.get("remakeLabel"))
        is_misc = bool(material_meta.get("miscLabel"))

        per_material.append(
            {
                "materialName": material.get("materialName", ""),
                "totalSheets": total,
                "done": done,
                "bad": bad,
                "skipped": skipped,
                "renested": renested,
                "isRemake": is_remake,
                "isMisc": is_misc,
            }
        )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `backend\venv\Scripts\python.exe -m pytest backend\tests\test_ready_jobs_worker_core_cache_index_payload.py -v -k is_misc`
Expected: PASS

- [ ] **Step 5: Run the full test file to check for regressions**

Run: `backend\venv\Scripts\python.exe -m pytest backend\tests\test_ready_jobs_worker_core_cache_index_payload.py -v`
Expected: All PASS

- [ ] **Step 6: Commit**

```bash
cd "C:\Scripts\Hours Tracker"
git add backend/ready_jobs_worker_core/cache_index_payload.py backend/tests/test_ready_jobs_worker_core_cache_index_payload.py
git commit -m "feat: report isMisc alongside isRemake in cache_index.json CNC progress"
```

---

### Task 5: `misc_bad_parts_candidates.json` publisher

**Files:**
- Create: `C:\Scripts\Hours Tracker\backend\ready_jobs_worker_core\adapters\misc_candidates_publish.py`
- Test: `C:\Scripts\Hours Tracker\backend\tests\test_ready_jobs_worker_core_misc_candidates_publish.py`

- [ ] **Step 1: Write the failing tests**

Create `test_ready_jobs_worker_core_misc_candidates_publish.py` — a direct mirror of `test_ready_jobs_worker_core_remake_candidates_publish.py`, filename and output changed:

```python
import json
from pathlib import Path

from ready_jobs_worker_core.adapters.misc_candidates_publish import (
    build_misc_candidates,
    refresh_misc_candidates_for_job,
)


def _tracker_dir(job: Path) -> Path:
    tracker = job / "CNC" / ".tracker"
    tracker.mkdir(parents=True)
    return tracker


def _write_consolidated(job: Path, actions: list[dict]) -> None:
    (job / "CNC" / ".tracker" / "consolidated.json").write_text(
        json.dumps({"actions": actions}), encoding="utf-8"
    )


def _action(action, *, file="Doors.pdf", page=1, part=None, ts="2026-08-12T00:00:00+00:00", fp="fp1"):
    a = {"action": action, "file": file, "page": page, "timestamp": ts, "fileFingerprint": fp}
    if part is not None:
        a["part"] = part
    return a


def test_complete_sheet_with_active_bad_part_is_a_candidate():
    actions = [
        _action("complete"),
        _action("bad_part", part=7),
    ]
    candidates = build_misc_candidates("100 - Job", actions, {})
    assert len(candidates) == 1
    c = candidates[0]
    assert c["id"] == "Doors.pdf|1|7"
    assert c["jobFolderName"] == "100 - Job"
    assert c["partNumber"] == 7


def test_skipped_sheet_is_never_a_candidate():
    actions = [
        _action("complete"),
        _action("skip"),
        _action("bad_part", part=7),
    ]
    assert build_misc_candidates("100 - Job", actions, {}) == []


def test_refresh_writes_file_when_job_has_candidates(tmp_path):
    job = tmp_path / "100 - Job"
    _tracker_dir(job)
    _write_consolidated(job, [_action("complete"), _action("bad_part", part=7)])
    (job / "CNC" / ".metadata").mkdir(parents=True)

    refresh_misc_candidates_for_job(job, "100 - Job")

    out = job / "CNC" / ".metadata" / "misc_bad_parts_candidates.json"
    assert out.exists()
    payload = json.loads(out.read_text(encoding="utf-8"))
    assert payload["jobFolderName"] == "100 - Job"
    assert len(payload["candidates"]) == 1


def test_refresh_removes_file_when_job_has_no_tracker_actions(tmp_path):
    job = tmp_path / "100 - Job"
    _tracker_dir(job)
    _write_consolidated(job, [])
    out_dir = job / "CNC" / ".metadata"
    out_dir.mkdir(parents=True)
    out = out_dir / "misc_bad_parts_candidates.json"
    out.write_text('{"jobFolderName": "100 - Job", "candidates": []}', encoding="utf-8")

    refresh_misc_candidates_for_job(job, "100 - Job")

    assert not out.exists()


def test_refresh_skips_rewrite_when_candidates_unchanged(tmp_path):
    job = tmp_path / "100 - Job"
    _tracker_dir(job)
    _write_consolidated(job, [_action("complete"), _action("bad_part", part=7)])
    (job / "CNC" / ".metadata").mkdir(parents=True)

    refresh_misc_candidates_for_job(job, "100 - Job")
    out = job / "CNC" / ".metadata" / "misc_bad_parts_candidates.json"
    first_mtime = out.stat().st_mtime_ns

    refresh_misc_candidates_for_job(job, "100 - Job")
    assert out.stat().st_mtime_ns == first_mtime
```

- [ ] **Step 2: Run tests to verify they fail**

Run (from `C:\Scripts\Hours Tracker`): `backend\venv\Scripts\python.exe -m pytest backend\tests\test_ready_jobs_worker_core_misc_candidates_publish.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'ready_jobs_worker_core.adapters.misc_candidates_publish'`

- [ ] **Step 3: Write minimal implementation**

Create `C:\Scripts\Hours Tracker\backend\ready_jobs_worker_core\adapters\misc_candidates_publish.py` — a direct copy of `remake_candidates_publish.py` with names changed and the docstring corrected (Miscellaneous is a new feature, not ported from RJW):

```python
"""Publish/refresh a job's `misc_bad_parts_candidates.json`.

Sibling of `remake_candidates_publish.py`, kept as a separate output file
(not merged into `remake_bad_parts_candidates.json`) so that file's schema
stays unchanged for existing consumers. Precomputes the per-job list of CNC
parts that are complete, not skipped, and have an active (unresolved)
bad-part flag, so downstream tools don't have to re-derive it from raw
tracker actions on every read.

Like `refresh_remake_candidates_for_job`, this takes an explicit
`job_folder: Path` + `folder_name: str` and does NOT re-check the deployment
gate -- `refresh_misc_candidates_for_job` is only ever called from
`sweep.py`'s per-job loop, which already filters to production-visible jobs
before iterating.
"""
from __future__ import annotations

import glob
import os
from pathlib import Path

from .atomic_write import atomic_write_json
from .fs_reader import read_json_or_none, read_tracker_actions

MISC_CANDIDATES_FILENAME = "misc_bad_parts_candidates.json"


def _candidates_output_path(job_folder: Path) -> Path:
    return job_folder / "CNC" / ".metadata" / MISC_CANDIDATES_FILENAME


def _load_metadata_by_pdf(metadata_dir: Path) -> dict[str, dict]:
    metadata_by_pdf: dict[str, dict] = {}
    if not metadata_dir.is_dir():
        return metadata_by_pdf
    for mf in glob.glob(str(metadata_dir / "*.json")):
        name = os.path.basename(mf)
        if ".sync-conflict-" in name.lower():
            continue
        raw = read_json_or_none(Path(mf))
        if isinstance(raw, dict):
            pdf_name = raw.get("pdfFilename")
            if pdf_name:
                metadata_by_pdf[str(pdf_name)] = raw
    return metadata_by_pdf


def build_misc_candidates(
    job_folder_name: str,
    actions: list[dict],
    metadata_by_pdf: dict[str, dict],
) -> list[dict]:
    sheets: dict[tuple[str, int], dict] = {}
    for action in actions:
        pdf = action.get("file")
        page = action.get("page")
        action_name = str(action.get("action", "") or "")
        if not pdf or not isinstance(page, int) or not action_name:
            continue
        key = (str(pdf), page)
        entry = sheets.setdefault(
            key,
            {"complete": False, "skipped": False, "badParts": {}, "lastTs": ""},
        )
        if action_name == "complete":
            entry["complete"] = True
        elif action_name == "uncomplete":
            entry["complete"] = False
        elif action_name == "skip":
            entry["skipped"] = True
        elif action_name == "unskip":
            entry["skipped"] = False
        elif action_name in ("bad_part", "unbad_part"):
            part = action.get("part")
            if isinstance(part, int):
                entry["badParts"][part] = {
                    "isBad": action_name == "bad_part",
                    "fileFingerprint": str(action.get("fileFingerprint", "") or ""),
                }
        entry["lastTs"] = str(action.get("timestamp", entry["lastTs"]) or entry["lastTs"])

    unresolved: list[dict] = []
    for (pdf, page), entry in sheets.items():
        active_bad_parts = sorted(
            p for p, status in entry["badParts"].items() if status.get("isBad")
        )
        if not entry["complete"] or entry["skipped"] or not active_bad_parts:
            continue

        md = metadata_by_pdf.get(pdf, {})
        material_name = md.get("material") or os.path.splitext(pdf)[0]
        page_meta = None
        for pmd in md.get("pages", []):
            if pmd.get("pageNumber") == page:
                page_meta = pmd
                break
        parts_lookup = {}
        if page_meta:
            for part in page_meta.get("parts", []):
                num = part.get("number")
                if isinstance(num, int):
                    parts_lookup[num] = part

        for part_num in active_bad_parts:
            part_obj = parts_lookup.get(part_num, {})
            part_status = entry["badParts"].get(part_num, {})
            part_fp = str(part_status.get("fileFingerprint", "") or "")
            unresolved.append(
                {
                    "id": f"{pdf}|{page}|{part_num}",
                    "jobFolderName": job_folder_name,
                    "materialName": material_name,
                    "pdfFilename": pdf,
                    "sheetPage": page,
                    "partNumber": part_num,
                    "partName": part_obj.get("name", f"Part {part_num}"),
                    "fileFingerprint": part_fp,
                    "lastTouchedAt": entry["lastTs"],
                }
            )

    unresolved.sort(key=lambda x: (x["materialName"], x["sheetPage"], x["partNumber"]))
    return unresolved


def refresh_misc_candidates_for_job(job_folder: Path, folder_name: str) -> bool:
    """Publish or remove this job's misc_bad_parts_candidates.json.

    Returns True if the file was written or removed (a real change), False
    if nothing needed to change (already absent, or unchanged content)."""
    out_path = _candidates_output_path(job_folder)
    tracker_dir = job_folder / "CNC" / ".tracker"
    metadata_dir = job_folder / "CNC" / ".metadata"

    actions = list(read_tracker_actions(tracker_dir / "consolidated.json"))
    if not actions:
        if out_path.exists():
            out_path.unlink()
            return True
        return False

    metadata_by_pdf = _load_metadata_by_pdf(metadata_dir)
    candidates = build_misc_candidates(folder_name, actions, metadata_by_pdf)

    existing = read_json_or_none(out_path)
    if isinstance(existing, dict) and existing.get("candidates") == candidates:
        return False

    out_path.parent.mkdir(parents=True, exist_ok=True)
    atomic_write_json(
        out_path,
        {"jobFolderName": folder_name, "candidates": candidates},
        indent=2,
        ensure_ascii=False,
    )
    return True
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `backend\venv\Scripts\python.exe -m pytest backend\tests\test_ready_jobs_worker_core_misc_candidates_publish.py -v`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\Hours Tracker"
git add backend/ready_jobs_worker_core/adapters/misc_candidates_publish.py backend/tests/test_ready_jobs_worker_core_misc_candidates_publish.py
git commit -m "feat: publish misc_bad_parts_candidates.json, mirroring the remake queue file"
```

---

### Task 6: Wire `refresh_misc_candidates_for_job` into `sweep.py`

**Files:**
- Modify: `C:\Scripts\Hours Tracker\backend\ready_jobs_worker_core\adapters\sweep.py:26,45,325,346-348,393`
- Test: `C:\Scripts\Hours Tracker\backend\tests\test_ready_jobs_worker_core_sweep.py`

- [ ] **Step 1: Write the failing test**

Add to `test_ready_jobs_worker_core_sweep.py`, using the file's own `_write_json`/`_write_device_file`/`_DEPLOYED_VISIBLE_GATE` fixture helpers (mirrors `test_single_deployed_job_happy_path` exactly, with a bad-part action added so a candidate actually gets published):

```python
def test_single_deployed_job_publishes_misc_candidates_alongside_remake(tmp_path):
    base = tmp_path / "Ready Jobs"
    job_folder = base / "100 - Alpha"
    _write_json(job_folder / ".metadata" / "deployment_gate.json", _DEPLOYED_VISIBLE_GATE)
    _write_device_file(
        job_folder / "CNC" / ".tracker",
        [
            {"file": "a.pdf", "page": 1, "action": "complete", "timestamp": "2026-06-09T10:00:00Z"},
            {
                "file": "a.pdf",
                "page": 1,
                "action": "bad_part",
                "part": 1,
                "timestamp": "2026-06-09T10:01:00Z",
                "fileFingerprint": "fp1",
            },
        ],
    )

    results = sweep_all_production_jobs(base)

    assert len(results) == 1
    result = results[0]
    assert result.error is None
    assert result.remake_candidates_published is True
    assert result.misc_candidates_published is True
    assert (job_folder / "CNC" / ".metadata" / "misc_bad_parts_candidates.json").exists()
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `C:\Scripts\Hours Tracker`): `backend\venv\Scripts\python.exe -m pytest backend\tests\test_ready_jobs_worker_core_sweep.py -v -k misc_candidates_published`
Expected: FAIL with `AttributeError` (no `misc_candidates_published` field on `JobSweepResult`)

- [ ] **Step 3: Write minimal implementation**

In `sweep.py`, add the import next to line 26:

```python
from .remake_candidates_publish import refresh_remake_candidates_for_job
from .misc_candidates_publish import refresh_misc_candidates_for_job
```

Add the field to `JobSweepResult` next to line 45:

```python
    remake_candidates_published: bool = False
    misc_candidates_published: bool = False
```

Initialize the local next to line 325:

```python
        remake_candidates_published = False
        misc_candidates_published = False
```

Call it next to the existing remake call (lines 346-348):

```python
            if not legacy_write_blocked:
                remake_candidates_published = refresh_remake_candidates_for_job(
                    job_folder, folder_name
                )
                misc_candidates_published = refresh_misc_candidates_for_job(
                    job_folder, folder_name
                )
```

Pass it into the result construction next to line 393:

```python
                remake_candidates_published=remake_candidates_published,
                misc_candidates_published=misc_candidates_published,
```

- [ ] **Step 4: Run test to verify it passes**

Run: `backend\venv\Scripts\python.exe -m pytest backend\tests\test_ready_jobs_worker_core_sweep.py -v -k misc_candidates_published`
Expected: PASS

- [ ] **Step 5: Run the full sweep test file to check for regressions**

Run: `backend\venv\Scripts\python.exe -m pytest backend\tests\test_ready_jobs_worker_core_sweep.py -v`
Expected: All PASS

- [ ] **Step 6: Commit**

```bash
cd "C:\Scripts\Hours Tracker"
git add backend/ready_jobs_worker_core/adapters/sweep.py backend/tests/test_ready_jobs_worker_core_sweep.py
git commit -m "feat: publish misc_bad_parts_candidates.json in the per-job sweep loop"
```

---

## Part C: KKCSheetTracker Android tablet

### Task 7: `miscLabel` and `incompleteMiscMaterials` data model fields

**Files:**
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\data\models\Models.kt:32,770`

- [ ] **Step 1: Write the failing test**

Create `C:\Scripts\KKCSheetTracker\app\src\test\java\com\kkc\sheettracker\data\models\MaterialMetadataMiscTest.kt`:

```kotlin
package com.kkc.sheettracker.data.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialMetadataMiscTest {
    private val gson = Gson()

    @Test
    fun materialMetadataDecodesMiscLabelFromSidecarJson() {
        val metadata = gson.fromJson(
            """{"jobNumber":"100","material":"Misc Maple","miscLabel":"001 MISC"}""",
            MaterialMetadata::class.java
        )

        assertEquals("001 MISC", metadata.miscLabel)
    }

    @Test
    fun materialMetadataMiscLabelDefaultsToNullWhenAbsent() {
        val metadata = gson.fromJson(
            """{"jobNumber":"100","material":"Plain Birch"}""",
            MaterialMetadata::class.java
        )

        assertEquals(null, metadata.miscLabel)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.models.MaterialMetadataMiscTest"` (from `C:\Scripts\KKCSheetTracker`)
Expected: FAIL — compile error, `MaterialMetadata` has no `miscLabel` property.

- [ ] **Step 3: Write minimal implementation**

In `Models.kt`, add `miscLabel` next to `remakeLabel` (line 32):

```kotlin
    val remakeLabel: String? = null,
    val miscLabel: String? = null,
```

Add `incompleteMiscMaterials` to `DashboardUiModel` next to `incompleteRemakeMaterials` (line 770):

```kotlin
    val incompleteRemakeMaterials: List<DashboardRecentMaterialItem> = emptyList(),
    val incompleteMiscMaterials: List<DashboardRecentMaterialItem> = emptyList()
```

(Remember to move the trailing comma correctly — `incompleteRemakeMaterials` was previously the last property in that data class before its closing `)`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.models.MaterialMetadataMiscTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/models/Models.kt app/src/test/java/com/kkc/sheettracker/data/models/MaterialMetadataMiscTest.kt
git commit -m "feat: add miscLabel and incompleteMiscMaterials data model fields"
```

---

### Task 8: `isMisc` in `CacheIndexMaterialProgress`

**Files:**
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\data\models\CacheIndexModels.kt:3-11`
- Test: `C:\Scripts\KKCSheetTracker\app\src\test\java\com\kkc\sheettracker\data\models\CacheIndexModelsTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `CacheIndexModelsTest.kt` (mirrors `cncRenestedCountsDecodeFromCacheIndexAndStaySeparateFromSkipped` exactly, for `isMisc`):

```kotlin
    @Test
    fun cncIsMiscDecodesFromCacheIndexAndStaysSeparateFromIsRemake() {
        val root = gson.fromJson(
            """{"progressSummary":{"cnc":{"totalSheets":1,"materials":[{"materialName":"Misc Maple","totalSheets":1,"isMisc":true},{"materialName":"Remake Oak","totalSheets":1,"isRemake":true}]}}}""",
            CacheIndexRoot::class.java
        )

        val materials = requireNotNull(root.progressSummary?.cnc?.materials)
        val misc = materials.single { it.materialName == "Misc Maple" }
        val remake = materials.single { it.materialName == "Remake Oak" }
        assertTrue(misc.isMisc)
        assertTrue(!misc.isRemake)
        assertTrue(remake.isRemake)
        assertTrue(!remake.isMisc)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.models.CacheIndexModelsTest"`
Expected: FAIL — compile error, `CacheIndexMaterialProgress` has no `isMisc` property.

- [ ] **Step 3: Write minimal implementation**

In `CacheIndexModels.kt`, add `isMisc` next to `isRemake`:

```kotlin
data class CacheIndexMaterialProgress(
    val materialName: String = "",
    val totalSheets: Int = 0,
    val done: Int = 0,
    val bad: Int = 0,
    val skipped: Int = 0,
    val renested: Int = 0,
    val isRemake: Boolean = false,
    val isMisc: Boolean = false
) {
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.models.CacheIndexModelsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/models/CacheIndexModels.kt app/src/test/java/com/kkc/sheettracker/data/models/CacheIndexModelsTest.kt
git commit -m "feat: decode isMisc from cache_index.json CNC material progress"
```

---

### Task 9: `sanitizeMaterialMetadata` copy-through + engine-level parity test

**Files:**
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\data\unified\FileBackedUnifiedMetadataEngine.kt:1654-1662`
- Test: `C:\Scripts\KKCSheetTracker\app\src\test\java\com\kkc\sheettracker\data\UnifiedFacadeParityTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `UnifiedFacadeParityTest.kt`, mirroring `scanCoordinator_targetedDeepRefreshLoadsNewCncMaterial` (lines 40-67) and its `seedRemake` helper (lines 360-389):

```kotlin
    @Test
    fun scanCoordinator_targetedDeepRefreshLoadsNewMiscMaterial() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        seedInitialStaticCache(baseDir)
        val repository = JobRepository(baseDir, isDebugBuild = true)
        val coordinator = ScanCoordinator(baseDir, repository)

        coordinator.refresh(RefreshReason.USER_REFRESH, force = true)
        waitUntilReady {
            coordinator.state.value.status == ScanStatus.READY
        }
        val initialJob = coordinator.unifiedEngine.getCncSnapshot(jobFolder)?.job
        assertTrue("Expected initial job to be loaded via getCncSnapshot", initialJob != null)
        assertEquals(1, initialJob!!.materials.size)

        seedMisc(baseDir)

        coordinator.refreshJobsDeep(listOf(jobFolder))
        val sawMisc = waitUntil(timeoutMs = 5_000L) {
            coordinator.unifiedEngine.getCncSnapshot(jobFolder)?.job?.materials
                ?.any { it.pdfFilename == "1234 - Misc Maple.pdf" } == true
        }
        val miscMaterial = coordinator.unifiedEngine.getCncSnapshot(jobFolder)?.job?.materials
            ?.firstOrNull { it.pdfFilename == "1234 - Misc Maple.pdf" }
        assertTrue("Expected targeted deep refresh to load misc material", sawMisc)
        assertEquals("001 MISC", miscMaterial?.metadata?.miscLabel)
    }
```

Add the `seedMisc` helper next to `seedRemake` (after line 389):

```kotlin
    private fun seedMisc(baseDir: File) {
        val cncDir = File(baseDir, "$jobFolder/CNC")
        File(cncDir, "1234 - Misc Maple.pdf").writeText("pdf-misc")
        File(cncDir, ".metadata/1234 - Misc Maple.json").writeText(
            """
            {
              "jobNumber": "1234",
              "jobName": "Test Job",
              "material": "Misc Maple",
              "pdfFilename": "1234 - Misc Maple.pdf",
              "miscLabel": "001 MISC",
              "pages": [
                {
                  "pageNumber": 1,
                  "parts": [
                    {
                      "number": 3,
                      "width": 4.0,
                      "length": 8.0,
                      "name": "Misc Panel",
                      "cabNumber": 12,
                      "room": "Kitchen"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.UnifiedFacadeParityTest"`
Expected: FAIL — `miscMaterial?.metadata?.miscLabel` is `null` (not yet copied through `sanitizeMaterialMetadata`).

- [ ] **Step 3: Write minimal implementation**

In `FileBackedUnifiedMetadataEngine.kt`, update `sanitizeMaterialMetadata` (line 1654-1662):

```kotlin
    private fun sanitizeMaterialMetadata(metadata: MaterialMetadata?): MaterialMetadata {
        if (metadata == null) return MaterialMetadata()
        return MaterialMetadata(
            jobNumber = gsonNullable(metadata.jobNumber) ?: "",
            jobName = gsonNullable(metadata.jobName) ?: "",
            material = gsonNullable(metadata.material) ?: "",
            pdfFilename = gsonNullable(metadata.pdfFilename) ?: "",
            remakeLabel = metadata.remakeLabel,
            miscLabel = metadata.miscLabel,
            partGraphicsArchive = metadata.partGraphicsArchive,
```

(Only the `miscLabel = metadata.miscLabel,` line is new; the rest of the function body after `partGraphicsArchive` is unchanged.)

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.UnifiedFacadeParityTest"`
Expected: PASS (all tests in the file, including the pre-existing remake ones)

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt app/src/test/java/com/kkc/sheettracker/data/UnifiedFacadeParityTest.kt
git commit -m "feat: copy miscLabel through sanitizeMaterialMetadata"
```

---

### Task 10: Dashboard derivation — `incompleteMiscMaterials` and `isMisc` candidate filtering

**Files:**
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\data\AppStateStore.kt:108-113,199-333`

**No dedicated automated test:** `AppStateStore.kt` has no existing unit test file in this repo (its `derive()` function is exercised only indirectly, and dashboard candidate filtering at lines 108-113 runs inside a `StateFlow`-collecting coroutine that isn't unit-tested today either). Task 9's engine-level parity test already proves `miscLabel` survives into the material metadata this derivation reads from. This task is implementation-only, verified by the manual on-device check in Task 13.

- [ ] **Step 1: Add `isMisc` to the dashboard-candidate filter (lines 108-113)**

```kotlin
                    val dashboardCandidates = jobInfos.filter { info ->
                        val materials = info.indexProgress?.cnc?.materials.orEmpty()
                        materials.any { material ->
                            isRecentInProgressMaterial(material.toStatusCounts()) ||
                                (material.isRemake && (material.done + material.renested) < material.totalSheets) ||
                                (material.isMisc && (material.done + material.renested) < material.totalSheets)
                        }
                    }
```

- [ ] **Step 2: Thread `incompleteMiscMaterials` through the dashboard copy (around line 137)**

```kotlin
                        recentInProgressMaterials = derivation.dashboard.recentInProgressMaterials,
                        incompleteRemakeMaterials = derivation.dashboard.incompleteRemakeMaterials,
                        incompleteMiscMaterials = derivation.dashboard.incompleteMiscMaterials
```

- [ ] **Step 3: Add the `incompleteMiscMaterials` accumulator to `derive()` (line 204)**

```kotlin
        val recentInProgressMaterials = mutableListOf<DashboardRecentMaterialItem>()
        val incompleteRemakeMaterials = mutableListOf<DashboardRecentMaterialItem>()
        val incompleteMiscMaterials = mutableListOf<DashboardRecentMaterialItem>()
```

- [ ] **Step 4: Mirror the remake derivation block for misc (insert after line 295, i.e. after the remake block's closing `}`, still inside the same `materials.forEach` — before the enclosing loop's own closing brace at line 296)**

The existing block (lines 270-295):

```kotlin
                val remakeLabel = material.metadata?.remakeLabel
                if (remakeLabel != null &&
                    (materialUiModel.counts.complete + materialUiModel.counts.reNested) < materialUiModel.counts.total
                ) {
                    val visiblePages = trackablePages(material)
                    val nextIncompletePage = nextIncompletePage(
                        trackablePages = visiblePages,
                        pageStatusByNumber = materialDerivation.pageStatusByNumber,
                        fallbackPage = visiblePages.firstOrNull() ?: 1
                    )
                    val pageMeta = material.metadata.pages.firstOrNull { it.pageNumber == nextIncompletePage }
                        ?: material.metadata.pages.getOrNull((nextIncompletePage - 1).coerceAtLeast(0))
                    incompleteRemakeMaterials += DashboardRecentMaterialItem(
                        jobFolderName = job.folderName,
                        jobNumber = job.jobNumber,
                        materialName = material.materialName,
                        pdfFilename = material.pdfFilename,
                        fileFingerprint = material.fileFingerprint,
                        lastTouchedPage = nextIncompletePage,
                        nextIncompletePage = nextIncompletePage,
                        lastTouchedAtMs = 0L,
                        counts = materialUiModel.counts,
                        completionFraction = materialUiModel.completionFraction,
                        thumbnailPath = pageMeta?.thumbnailPath
                    )
                }
```

Add immediately after this block (still inside the same `materials.forEach { material -> ... }` iteration, so `material`, `materialUiModel`, `materialDerivation`, and `job` are all in scope):

```kotlin
                val miscLabel = material.metadata?.miscLabel
                if (miscLabel != null &&
                    (materialUiModel.counts.complete + materialUiModel.counts.reNested) < materialUiModel.counts.total
                ) {
                    val visiblePages = trackablePages(material)
                    val nextIncompletePage = nextIncompletePage(
                        trackablePages = visiblePages,
                        pageStatusByNumber = materialDerivation.pageStatusByNumber,
                        fallbackPage = visiblePages.firstOrNull() ?: 1
                    )
                    val pageMeta = material.metadata.pages.firstOrNull { it.pageNumber == nextIncompletePage }
                        ?: material.metadata.pages.getOrNull((nextIncompletePage - 1).coerceAtLeast(0))
                    incompleteMiscMaterials += DashboardRecentMaterialItem(
                        jobFolderName = job.folderName,
                        jobNumber = job.jobNumber,
                        materialName = material.materialName,
                        pdfFilename = material.pdfFilename,
                        fileFingerprint = material.fileFingerprint,
                        lastTouchedPage = nextIncompletePage,
                        nextIncompletePage = nextIncompletePage,
                        lastTouchedAtMs = 0L,
                        counts = materialUiModel.counts,
                        completionFraction = materialUiModel.completionFraction,
                        thumbnailPath = pageMeta?.thumbnailPath
                    )
                }
```

- [ ] **Step 5: Fold `incompleteMiscMaterials` into the `DerivationOutput.dashboard` construction (near line 325-332)**

```kotlin
            incompleteRemakeMaterials = incompleteRemakeMaterials
                .sortedWith(compareBy({ it.jobFolderName }, { it.materialName }))
                .take(DASHBOARD_RECENT_LIMIT),
            incompleteMiscMaterials = incompleteMiscMaterials
                .sortedWith(compareBy({ it.jobFolderName }, { it.materialName }))
                .take(DASHBOARD_RECENT_LIMIT)
```

- [ ] **Step 6: Build to confirm no compile errors**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/AppStateStore.kt
git commit -m "feat: derive incompleteMiscMaterials and isMisc dashboard candidate filtering"
```

---

### Task 11: Job-card status segment — `isMisc` + `miscBg`

**Files:**
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\ui\jobs\UnifiedModeSpecs.kt:80-85`
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\ui\components\StatusComponents.kt:80-84,725-730`

**No dedicated automated test:** neither file has existing test coverage for the `isRemake`/`remakeBg` segment logic this mirrors (confirmed — no test file references `MaterialSegmentData` or `isRemakeIncomplete`), and both changes live inside `@Composable` function bodies not covered by this repo's JVM unit tests. Implementation-only, verified by the manual on-device check in Task 13. (Task 12 depends on `miscBg` existing first.)

- [ ] **Step 1: Add `isMisc` to `MaterialSegmentData` (`StatusComponents.kt:80-84`)**

```kotlin
data class MaterialSegmentData(
    val materialName: String,
    val counts: StatusCounts,
    val isRemake: Boolean = false,
    val isMisc: Boolean = false
)
```

- [ ] **Step 2: Build `MaterialSegmentData.isMisc` in `UnifiedModeSpecs.kt:80-85`**

```kotlin
                        val materialSegments = cncProgress.materials.map { material ->
                            MaterialSegmentData(
                                materialName = material.materialName,
                                counts = material.toStatusCounts(),
                                isRemake = material.isRemake,
                                isMisc = material.isMisc
                            )
                        }
```

- [ ] **Step 3: Add `isMiscIncomplete` → `colors.miscBg` in `StatusComponents.kt:725-730`**

Current:

```kotlin
            val isRemakeIncomplete = segment.isRemake && remaining > 0
            val remainingColor = if (isRemakeIncomplete) colors.remakeBg else MaterialTheme.colorScheme.outlineVariant
```

Replace with:

```kotlin
            val isRemakeIncomplete = segment.isRemake && remaining > 0
            val isMiscIncomplete = segment.isMisc && remaining > 0
            val remainingColor = when {
                isRemakeIncomplete -> colors.remakeBg
                isMiscIncomplete -> colors.miscBg
                else -> MaterialTheme.colorScheme.outlineVariant
            }
```

- [ ] **Step 4: Build to confirm no compile errors**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: FAILS at this point — `colors.miscBg` does not exist yet (Task 12 adds it). This is expected; proceed to Task 12 before attempting a clean build, or do Task 12 first if executing tasks out of order.

- [ ] **Step 5: Commit** (after Task 12 makes the build pass — see Task 12's own commit step; if executing strictly in order, hold this commit until Task 12 is done and squash, or commit here anyway since Task 12 is next and will fix compilation)

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedModeSpecs.kt app/src/main/java/com/kkc/sheettracker/ui/components/StatusComponents.kt
git commit -m "feat: add isMisc job-card status segment, mirroring isRemake"
```

---

### Task 12: `miscBg` theme color tokens

**Files:**
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\ui\theme\KKCColors.kt:9-27,29-53,55-79`

- [ ] **Step 1: Add `miscBg` to the `KKCStatusColors` data class (line 9-27)**

```kotlin
data class KKCStatusColors(
    val complete: Color,
    val bad: Color,
    // ... (existing fields unchanged)
    val completeBorder: Color,
    val skipBorder: Color,
    val remakeBg: Color,
    val miscBg: Color,
    val widthBandPalette: List<Color>,
    val progressGradientStart: Color,
    // ... (any remaining existing fields unchanged)
)
```

- [ ] **Step 2: Add the light value (line 43)**

```kotlin
val LightStatusColors = KKCStatusColors(
    // ... (existing fields unchanged)
    remakeBg = Color(0xFFCE93D8),
    miscBg = Color(0xFF90CAF9),
    // ... (remaining fields unchanged)
)
```

- [ ] **Step 3: Add the dark value (line 69)**

```kotlin
val DarkStatusColors = KKCStatusColors(
    // ... (existing fields unchanged)
    remakeBg = Color(0xFFBA68C8),
    miscBg = Color(0xFF64B5F6),
    // ... (remaining fields unchanged)
)
```

- [ ] **Step 4: Build to confirm no compile errors**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL (this also resolves Task 11's `colors.miscBg` reference)

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/ui/theme/KKCColors.kt
git commit -m "feat: add miscBg theme color tokens (Blue 200/300, analogue of remakeBg's Purple 200/300)"
```

---

### Task 13: `CncMiscsSection` dashboard composable

**Files:**
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\ui\dashboard\UnifiedModeDashboardScreen.kt:238-260,357-528`

**No dedicated automated test:** there is no existing test for `CncRemakesSection` either (Compose UI, not exercised by JVM unit tests in this repo). Implementation-only; verified manually per the steps below.

- [ ] **Step 1: Add `CncMiscsSection` rendering below `CncRemakesSection` (after line 260, before `TextButton(onClick = onNavigateToJobs)`)**

Current (lines 238-261):

```kotlin
        if (lowEnd.animationsDisabled) {
            if (!hasLoadedOnce || dashboard.incompleteRemakeMaterials.isNotEmpty()) {
                CncRemakesSection(
                    items = dashboard.incompleteRemakeMaterials,
                    hasLoadedOnce = hasLoadedOnce,
                    jobRepository = jobRepository,
                    onOpenSheet = onOpenSheet
                )
            }
        } else {
            AnimatedVisibility(
                visible = !hasLoadedOnce || dashboard.incompleteRemakeMaterials.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                CncRemakesSection(
                    items = dashboard.incompleteRemakeMaterials,
                    hasLoadedOnce = hasLoadedOnce,
                    jobRepository = jobRepository,
                    onOpenSheet = onOpenSheet
                )
            }
        }
        TextButton(onClick = onNavigateToJobs) { Text("View All Jobs") }
```

Insert a parallel block for Miscellaneous between the `CncRemakesSection` wiring and the `TextButton`:

```kotlin
        if (lowEnd.animationsDisabled) {
            if (!hasLoadedOnce || dashboard.incompleteRemakeMaterials.isNotEmpty()) {
                CncRemakesSection(
                    items = dashboard.incompleteRemakeMaterials,
                    hasLoadedOnce = hasLoadedOnce,
                    jobRepository = jobRepository,
                    onOpenSheet = onOpenSheet
                )
            }
            if (!hasLoadedOnce || dashboard.incompleteMiscMaterials.isNotEmpty()) {
                CncMiscsSection(
                    items = dashboard.incompleteMiscMaterials,
                    hasLoadedOnce = hasLoadedOnce,
                    jobRepository = jobRepository,
                    onOpenSheet = onOpenSheet
                )
            }
        } else {
            AnimatedVisibility(
                visible = !hasLoadedOnce || dashboard.incompleteRemakeMaterials.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                CncRemakesSection(
                    items = dashboard.incompleteRemakeMaterials,
                    hasLoadedOnce = hasLoadedOnce,
                    jobRepository = jobRepository,
                    onOpenSheet = onOpenSheet
                )
            }
            AnimatedVisibility(
                visible = !hasLoadedOnce || dashboard.incompleteMiscMaterials.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                CncMiscsSection(
                    items = dashboard.incompleteMiscMaterials,
                    hasLoadedOnce = hasLoadedOnce,
                    jobRepository = jobRepository,
                    onOpenSheet = onOpenSheet
                )
            }
        }
        TextButton(onClick = onNavigateToJobs) { Text("View All Jobs") }
```

- [ ] **Step 2: Add `CncMiscsSection` + `CncMiscMaterialCard` composables (after `CncRemakeMaterialCard`, i.e. after line 528)**

Direct mirror of `CncRemakesSection`/`CncRemakeMaterialCard` (lines 357-528), renamed and re-colored:

```kotlin
@Composable
private fun CncMiscsSection(
    items: List<DashboardRecentMaterialItem>,
    hasLoadedOnce: Boolean,
    jobRepository: JobRepository,
    onOpenSheet: (jobFolderName: String, pdfFilename: String, page: Int) -> Unit
) {
    val miscColor = KKCThemeColors.statusColors.miscBg
    DashboardSurfaceCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(miscColor, CircleShape)
            )
            DashboardSectionHeader(
                title = "Incomplete Miscellaneous",
                subtitle = if (!hasLoadedOnce) null else "${items.size} miscellaneous item${if (items.size == 1) "" else "s"} pending"
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasLoadedOnce) {
                CncMiscMaterialCard(item = null, miscColor = miscColor, thumbnail = null, onClick = {})
            } else {
                items.forEach { item ->
                    key(item.jobFolderName, item.pdfFilename) {
                        val thumbnail by produceState<Bitmap?>(
                            initialValue = null,
                            item.jobFolderName,
                            item.pdfFilename,
                            item.thumbnailPath,
                            item.nextIncompletePage
                        ) {
                            value = withContext(Dispatchers.IO) {
                                loadRecentMaterialThumbnail(jobRepository, item)
                            }
                        }
                        CncMiscMaterialCard(
                            item = item,
                            miscColor = miscColor,
                            thumbnail = thumbnail,
                            onClick = { onOpenSheet(item.jobFolderName, item.pdfFilename, item.nextIncompletePage) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CncMiscMaterialCard(
    item: DashboardRecentMaterialItem?,
    miscColor: androidx.compose.ui.graphics.Color,
    thumbnail: Bitmap?,
    onClick: () -> Unit
) {
    val lowEnd = LocalLowEndMode.current
    val tileShape = DashboardSurfaceDefaults.sectionShape
    DashboardSurfaceCard(
        modifier = Modifier
            .width(268.dp)
            .bounceClick(onClick = onClick)
            .border(width = 2.dp, color = miscColor, shape = tileShape),
        shape = tileShape,
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Miscellaneous material preview",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    androidx.compose.material3.Icon(
                        Icons.Default.Description,
                        contentDescription = "Description icon",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                item?.materialName ?: " ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = miscColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                item?.let { "${it.jobFolderName} • Next sheet ${it.nextIncompletePage}" } ?: " ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item?.let { "${it.counts.complete}/${it.counts.total} complete" } ?: " ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ProgressPill(
                    done = item?.counts?.complete ?: 0,
                    total = item?.counts?.total ?: 0,
                    state = ProgressState.from(item?.counts?.complete ?: 0, item?.counts?.total ?: 0)
                )
            }
            val fraction = item?.completionFraction?.coerceIn(0f, 1f) ?: 0f
            val animSpec = if (lowEnd.animationsDisabled) {
                androidx.compose.animation.core.snap<Float>()
            } else {
                androidx.compose.animation.core.spring(
                    stiffness = androidx.compose.animation.core.Spring.StiffnessLow,
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy
                )
            }
            val animatedFraction by androidx.compose.animation.core.animateFloatAsState(
                targetValue = fraction,
                animationSpec = animSpec,
                label = "miscProgress"
            )
            LinearProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier.fillMaxWidth(),
                color = miscColor,
                trackColor = miscColor.copy(alpha = 0.2f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DashboardAccentPill(
                    item?.let { "C ${it.counts.complete}" } ?: "C",
                    if (item != null) DashboardAccent.SUCCESS else DashboardAccent.NEUTRAL
                )
                DashboardAccentPill(
                    item?.let { "B ${it.counts.bad}" } ?: "B",
                    if (item != null) DashboardAccent.DANGER else DashboardAccent.NEUTRAL
                )
                DashboardAccentPill(
                    item?.let { "S ${it.counts.skipped}" } ?: "S",
                    if (item != null) DashboardAccent.WARNING else DashboardAccent.NEUTRAL
                )
                DashboardAccentPill(
                    item?.let { "R ${it.counts.notStarted}" } ?: "R",
                    if (item != null) DashboardAccent.INFO else DashboardAccent.NEUTRAL
                )
            }
        }
    }
}
```

- [ ] **Step 3: Build to confirm no compile errors**

Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run the full unit test suite to check for regressions across all tablet changes in this plan**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt
git commit -m "feat: add CncMiscsSection dashboard card, rendered below Incomplete Remakes"
```

- [ ] **Step 6: Manual on-device verification (release build, per this repo's CLAUDE.md)**

1. Deploy: `.\adb-install-release.ps1` (or, if the encoding issue in memory `adb_release_script_encoding` recurs, run `gradlew assembleRelease` + `adb install` directly).
2. On a test job's PDF, name/rename a source PDF with an `m`-prefix (e.g. `m1234.pdf`) and run it through the splitter.
3. Confirm the CNC sidecar JSON (`CNC\.metadata\<pdf-stem>.json`) contains `"miscLabel": "001 MISC"`.
4. Wait for (or manually trigger) the Hours Tracker worker's next sweep; confirm `cache_static.json`'s `cncJob.materials[].metadata.miscLabel` and `cache_index.json`'s `progressSummary.cnc.materials[].isMisc: true` are present, and `CNC\.metadata\misc_bad_parts_candidates.json` reflects any bad-part state as expected.
5. On the tablet, open the CNC dashboard: confirm a blue "Incomplete Miscellaneous" section renders directly below "Incomplete Remakes", and the job-card status segment shows a blue remaining-sheets marker for the misc material.

---

## Self-Review Notes

- **Spec coverage:** Splitter trigger (Tasks 1-3), sidecar metadata (Task 3), HT worker cache_index/cache_static (Task 4), HT worker queue file (Task 5), HT worker sweep wiring (Task 6), tablet data models (Tasks 7-8), tablet sanitize copy-through (Task 9), tablet dashboard derivation (Task 10), tablet job-card segment (Task 11), tablet color tokens (Task 12), tablet dashboard section UI (Task 13) — every component in the spec's mapping table has a task.
- **Two corrections made during verification** (both called out at the top of this plan): `AppScaffold.kt` needs no change (the spec's cited line was an unrelated re-nested-toggle color reuse, not an `isRemake` row), and `process_run_folders_v2.py` needs no change (its `REMAKE_STATE_FILE` usage is entirely part-selection/pending-batch consumption, which Miscellaneous's "simple label only" design deliberately excludes).
- **Untested layers:** Tasks 10, 11, and 13 (AppStateStore derivation, job-card segment wiring, dashboard section Compose UI) have no automated test because the equivalent existing Remake code has none either in this codebase — these are verified by Task 13's manual on-device check instead of inventing new test infrastructure the codebase doesn't otherwise use for this layer.
- **Type consistency check:** `miscLabel`/`isMisc`/`miscBg`/`incompleteMiscMaterials`/`CncMiscsSection`/`CncMiscMaterialCard` names are used identically across every task that touches them (Tasks 7-13) — verified by re-reading each cross-reference while writing this plan.
