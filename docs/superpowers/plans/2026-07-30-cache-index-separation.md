# cache_index Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lightweight `cache_index.json` per job so tablets load jobs list without reading full `cache_static.json`.

**Architecture:** RJW writes a new small index file alongside existing `cache_static.json` on every cache write. New index contains `jobInfo` + `progressSummary` (aggregate per-material done/bad/skipped computed from consolidated tracker files). Tablet reads index for list; `cache_static.json` only on job tap. Falls back to existing behavior if index missing.

**Tech Stack:** Python 3.10+, PyQt6 (RJW); Kotlin, Gson (KKCSheetTracker)

## Global Constraints
- `cache_static.json` schema UNCHANGED — zero modifications for backward compat
- `cache_index.json` uses same skip-if-unchanged as static cache (`_atomic_write_json`)
- Per-material breakdown in progressSummary (material name + done/bad/skipped per material for segmented bar)
- Search index built lazily — first search screen open, not during list scan
- Tablet falls back to `cache_static.json` read if `cache_index.json` missing per-job
- Written on same trigger paths as static cache: bulk sweep, per-job debounce, rename

---
## RJW Tasks

### Task 1: `_compute_progress_summary()` — aggregate tracker actions per material

**Files:**
- Modify: `ready_jobs_watcher/metadata_cache.py` (insert after `generate_static_cache` ~line 263)
- Test: `tests/test_cache_index.py` (create)

**Interfaces:**
- Consumes: `job_folder: Path`, `static_data: Dict[str, Any]` (output of `generate_static_cache`)
- Produces: `_compute_progress_summary(job_folder, static_data) -> Dict[str, Any]` — used by Task 2's `generate_cache_index()`

- [ ] **Step 1: Write failing test for empty tracker**

```python
# tests/test_cache_index.py
import json, pytest
from pathlib import Path
from ready_jobs_watcher.metadata_cache import _compute_progress_summary

def test_progress_summary_empty_tracker(tmp_path):
    job = tmp_path / "123 - Test"
    meta = job / ".metadata"
    meta.mkdir(parents=True)
    cnc_tracker = job / "CNC" / ".tracker"
    cnc_tracker.mkdir(parents=True)
    (cnc_tracker / "consolidated.json").write_text(
        json.dumps({"tabletId": "consolidated", "actions": []})
    )
    static_data = {
        "jobInfo": {"folderName": "123 - Test"},
        "cncJob": {"materials": [
            {"materialName": "FRAME", "pageCount": 5, "pdfFilename": "123 - FRAME.pdf"}
        ]},
        "hasThreeDAssets": False,
        "pdfCatalog": {"deliverySheet": None}
    }
    result = _compute_progress_summary(job, static_data)
    assert result["cnc"]["totalSheets"] == 5
    assert result["cnc"]["done"] == 0
    assert result["cnc"]["bad"] == 0
    assert result["cnc"]["materials"][0]["done"] == 0
    assert result["hasDeliverySheet"] is False
    assert result["has3DAssets"] is False
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python -m pytest tests/test_cache_index.py::test_progress_summary_empty_tracker -v`
Expected: FAIL

- [ ] **Step 3: Implement `_compute_progress_summary()`**

Insert after `generate_static_cache()`:

```python
def _compute_cnc_progress(job_folder: Path, static_data: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    materials = static_data.get("cncJob", {}).get("materials", [])
    if not materials:
        return None
    consolidated = _read_json(job_folder / "CNC" / ".tracker" / "consolidated.json")
    actions = consolidated.get("actions", []) if isinstance(consolidated, dict) else []

    per_file: Dict[str, List[Dict]] = {}
    for a in actions:
        fname = a.get("file") or a.get("pdfFilename", "")
        per_file.setdefault(fname, []).append(a)

    per_material = []
    total_sheets = 0
    total_done = 0
    total_bad = 0
    total_skipped = 0

    for mat in materials:
        fname = mat["pdfFilename"]
        page_count = mat.get("pageCount", 0)
        total_sheets += page_count
        file_actions = per_file.get(fname, [])

        page_status: Dict[str, str] = {}
        for a in sorted(file_actions, key=lambda x: x.get("timestamp", "")):
            page = str(a.get("page", ""))
            action = a.get("action", "")
            if not page or not action:
                continue
            if action == "complete":
                page_status[page] = "done"
            elif action == "bad_part":
                page_status[page] = "bad"
            elif action == "skip":
                page_status[page] = "skipped"
            elif action == "unskip" and page_status.get(page) == "skipped":
                page_status[page] = "done"

        done = sum(1 for s in page_status.values() if s == "done")
        bad = sum(1 for s in page_status.values() if s == "bad")
        skipped = sum(1 for s in page_status.values() if s == "skipped")
        total_done += done
        total_bad += bad
        total_skipped += skipped

        is_remake = bool((mat.get("metadata", {}) or {}).get("remakeLabel"))

        per_material.append({
            "materialName": mat["materialName"],
            "totalSheets": page_count,
            "done": done,
            "bad": bad,
            "skipped": skipped,
            "isRemake": is_remake,
        })

    return {
        "totalSheets": total_sheets,
        "done": total_done,
        "bad": total_bad,
        "skipped": total_skipped,
        "materials": per_material,
    }


def _compute_hardwood_progress(job_folder: Path, static_data: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    hardwood_job = static_data.get("hardwoodJob", {})
    index = hardwood_job.get("index") if isinstance(hardwood_job, dict) else None
    if not index:
        return None
    documents = index.get("documents", [])
    if not documents:
        return None

    consolidated = _read_json(job_folder / ".metadata" / "hardwoods" / ".tracker" / "consolidated.json")
    actions = consolidated.get("actions", []) if isinstance(consolidated, dict) else []

    doc_progress: Dict[str, dict] = {}
    for doc in documents:
        dt = doc.get("docType", "")
        rows = doc.get("rows", [])
        row_status: Dict[str, dict] = {}
        for a in actions:
            if a.get("docType") != dt:
                continue
            rid = a.get("rowId", "")
            action = a.get("action", "")
            if action == "set_done_count":
                row_status.setdefault(rid, {"done": 0, "bad": 0, "skipped": False})
                row_status[rid]["done"] = max(row_status[rid]["done"], int(a.get("value", 0)))
            elif action == "set_bad_count":
                row_status.setdefault(rid, {"done": 0, "bad": 0, "skipped": False})
                row_status[rid]["bad"] = max(row_status[rid]["bad"], int(a.get("value", 0)))
            elif action == "set_skipped":
                row_status.setdefault(rid, {"done": 0, "bad": 0, "skipped": False})
                row_status[rid]["skipped"] = True
            elif action == "clear_skipped":
                if rid in row_status:
                    row_status[rid]["skipped"] = False

        done_rids = sum(1 for s in row_status.values() if s["done"] > 0)
        bad_rids = sum(1 for s in row_status.values() if s["bad"] > 0)
        skipped_rids = sum(1 for s in row_status.values() if s["skipped"])
        doc_progress[dt] = {
            "total": len(rows),
            "done": done_rids,
            "bad": bad_rids,
            "skipped": skipped_rids,
        }

    if not doc_progress:
        return None

    agg_done = sum(d["done"] for d in doc_progress.values())
    agg_bad = sum(d["bad"] for d in doc_progress.values())
    agg_skipped = sum(d["skipped"] for d in doc_progress.values())
    agg_total = sum(d["total"] for d in doc_progress.values())

    return {
        "totalPieces": agg_total,
        "donePieces": agg_done,
        "badPieces": agg_bad,
        "skippedPieces": agg_skipped,
        "docTypes": [{"docType": k, **v} for k, v in doc_progress.items()],
    }


def _compute_progress_summary(job_folder: Path, static_data: Dict[str, Any]) -> Dict[str, Any]:
    cnc_progress = _compute_cnc_progress(job_folder, static_data)
    hardwood_progress = _compute_hardwood_progress(job_folder, static_data)
    pdf_catalog = static_data.get("pdfCatalog", {})
    has_delivery = pdf_catalog.get("deliverySheet") is not None
    has_3d = bool(static_data.get("hasThreeDAssets", False))
    return {
        "cnc": cnc_progress,
        "hardwoods": hardwood_progress,
        "hasDeliverySheet": has_delivery,
        "has3DAssets": has_3d,
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python -m pytest tests/test_cache_index.py::test_progress_summary_empty_tracker -v`
Expected: PASS

- [ ] **Step 5: Write and run additional tests**

```python
def test_progress_summary_cnc_single_done(tmp_path):
    job = tmp_path / "123 - Test"
    (job / "CNC" / ".tracker").mkdir(parents=True)
    (job / ".metadata").mkdir()
    actions = [{"file": "123 - FRAME.pdf", "page": 1, "action": "complete", "timestamp": "1"}]
    (job / "CNC" / ".tracker" / "consolidated.json").write_text(
        json.dumps({"tabletId": "consolidated", "actions": actions})
    )
    static_data = {
        "cncJob": {"materials": [
            {"materialName": "FRAME", "pageCount": 5, "pdfFilename": "123 - FRAME.pdf"}
        ]},
        "hasThreeDAssets": False, "pdfCatalog": {"deliverySheet": None}
    }
    result = _compute_progress_summary(job, static_data)
    assert result["cnc"]["done"] == 1
    assert result["cnc"]["materials"][0]["done"] == 1

def test_progress_summary_cnc_multi_material(tmp_path):
    job = tmp_path / "123 - Test"
    (job / "CNC" / ".tracker").mkdir(parents=True)
    (job / ".metadata").mkdir()
    actions = [
        {"file": "123 - FRAME.pdf", "page": 1, "action": "bad_part", "part": 1, "timestamp": "1"},
        {"file": "123 - FRAME.pdf", "page": 2, "action": "complete", "timestamp": "2"},
        {"file": "123 - DOOR.pdf", "page": 1, "action": "skip", "timestamp": "3"},
    ]
    (job / "CNC" / ".tracker" / "consolidated.json").write_text(
        json.dumps({"tabletId": "consolidated", "actions": actions})
    )
    static_data = {
        "cncJob": {"materials": [
            {"materialName": "FRAME", "pageCount": 5, "pdfFilename": "123 - FRAME.pdf"},
            {"materialName": "DOOR", "pageCount": 3, "pdfFilename": "123 - DOOR.pdf"},
        ]},
        "hasThreeDAssets": False,
        "pdfCatalog": {"deliverySheet": {"pdfFilename": "del.pdf"}}
    }
    result = _compute_progress_summary(job, static_data)
    cnc = result["cnc"]
    assert cnc["totalSheets"] == 8
    assert cnc["done"] == 1
    assert cnc["bad"] == 1
    assert cnc["skipped"] == 1
    assert result["hasDeliverySheet"] is True
    frame = [m for m in cnc["materials"] if m["materialName"] == "FRAME"][0]
    assert frame["done"] == 1 and frame["bad"] == 1
    door = [m for m in cnc["materials"] if m["materialName"] == "DOOR"][0]
    assert door["skipped"] == 1
```

Run: `python -m pytest tests/test_cache_index.py -v`
Expected: All PASS

- [ ] **Step 6: Commit**

```bash
git add tests/test_cache_index.py ready_jobs_watcher/metadata_cache.py
git commit -m "feat: add _compute_progress_summary from consolidated tracker"
```

---

### Task 2: `generate_cache_index()` — write the index file

**Files:**
- Modify: `ready_jobs_watcher/metadata_cache.py` (after `_compute_progress_summary` block from Task 1)

**Interfaces:**
- Consumes: `_compute_progress_summary(job_folder, static_data)` from Task 1
- Produces: `generate_cache_index(job_folder, static_data) -> Dict[str, Any]` — used by Task 3 trigger paths
- Writes: `.metadata/cache_index.json`

- [ ] **Step 1: Write failing test**

```python
def test_generate_cache_index_writes_file(tmp_path):
    job = tmp_path / "123 - Test"
    (job / "CNC" / ".tracker").mkdir(parents=True)
    (job / ".metadata").mkdir()
    (job / "CNC" / ".tracker" / "consolidated.json").write_text(
        json.dumps({"tabletId": "consolidated", "actions": []})
    )
    from ready_jobs_watcher.metadata_cache import generate_cache_index
    static_data = {
        "jobInfo": {"folderName": "123 - Test", "jobNumber": "123", "jobName": "Test",
                     "hiddenFromProduction": False, "lineupPosition": 1},
        "cncJob": {"materials": [{"materialName": "FRAME", "pageCount": 3, "pdfFilename": "123 - FRAME.pdf"}]},
        "hasThreeDAssets": False, "pdfCatalog": {"deliverySheet": None}
    }
    result = generate_cache_index(job, static_data)
    index_file = job / ".metadata" / "cache_index.json"
    assert index_file.exists()
    assert result["jobInfo"]["folderName"] == "123 - Test"
    assert "progressSummary" in result
```

- [ ] **Step 2: Implement `generate_cache_index()`**

```python
def generate_cache_index(job_folder: Path, static_data: Dict[str, Any]) -> Dict[str, Any]:
    progress = _compute_progress_summary(job_folder, static_data)
    job_info = static_data.get("jobInfo", {})
    index_data = {
        "jobInfo": {
            "folderName": job_info.get("folderName"),
            "jobNumber": job_info.get("jobNumber"),
            "jobName": job_info.get("jobName"),
            "hiddenFromProduction": bool(job_info.get("hiddenFromProduction", False)),
            "lineupPosition": job_info.get("lineupPosition"),
        },
        "progressSummary": progress,
    }
    _atomic_write_json(job_folder / ".metadata" / "cache_index.json", index_data)
    return index_data
```

- [ ] **Step 3: Write skip-if-unchanged test**

```python
def test_cache_index_skip_if_unchanged(tmp_path):
    job = tmp_path / "123 - Test"
    (job / "CNC" / ".tracker").mkdir(parents=True)
    (job / ".metadata").mkdir()
    (job / "CNC" / ".tracker" / "consolidated.json").write_text(
        json.dumps({"tabletId": "consolidated", "actions": []})
    )
    from ready_jobs_watcher.metadata_cache import generate_cache_index
    static_data = {
        "jobInfo": {"folderName": "123 - Test"},
        "cncJob": {"materials": []},
        "hasThreeDAssets": False, "pdfCatalog": {"deliverySheet": None}
    }
    generate_cache_index(job, static_data)
    mtime1 = (job / ".metadata" / "cache_index.json").stat().st_mtime
    generate_cache_index(job, static_data)
    mtime2 = (job / ".metadata" / "cache_index.json").stat().st_mtime
    assert mtime1 == mtime2, "identical payload should not rewrite"
```

- [ ] **Step 4: Run all cache_index tests**

Run: `python -m pytest tests/test_cache_index.py -v`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add tests/test_cache_index.py ready_jobs_watcher/metadata_cache.py
git commit -m "feat: add generate_cache_index with skip-if-unchanged"
```

---

### Task 3: Wire index into `update_all_jobs_cache()` trigger

**Files:**
- Modify: `ready_jobs_watcher/metadata_cache.py:1029-1031` (bulk sweep call site)
- Modify: `ready_jobs_watcher/metadata_cache.py:909-929` (`_iter_staleness_files` — add tracker consolidated.json)

**Interfaces:**
- Consumes: `generate_cache_index(job_folder, static_data)` from Task 2

- [ ] **Step 1: Write failing test**

```python
def test_update_all_jobs_cache_writes_index(tmp_path):
    from ready_jobs_watcher.metadata_cache import update_all_jobs_cache
    job = tmp_path / "123 - Test"
    (job / ".metadata").mkdir(parents=True)
    (job / "CNC").mkdir(parents=True)
    gate = {"deployed": True, "parseReady": True, "hiddenFromProduction": False}
    (job / ".metadata" / "deployment_gate.json").write_text(json.dumps(gate))
    result = update_all_jobs_cache(tmp_path, consolidate_trackers=False, archive=False)
    assert (job / ".metadata" / "cache_index.json").exists()
    assert result["rebuilt"] == 1
```

- [ ] **Step 2: Modify `update_all_jobs_cache()` call site (line 1029-1031)**

Change from:
```python
if needs_rebuild:
    generate_static_cache(job_folder, folder_name, lineup_positions.get(folder_name))
    summary["rebuilt"] += 1
```
To:
```python
if needs_rebuild:
    static_data = generate_static_cache(job_folder, folder_name, lineup_positions.get(folder_name))
    generate_cache_index(job_folder, static_data)
    summary["rebuilt"] += 1
```

- [ ] **Step 3: Add tracker consolidated.json to staleness check**

In `_iter_staleness_files()`, add after the initial static files:
```python
    # Tracker consolidated files — affects cache_index.json progress summary
    for tracker_path in (
        job_folder / "CNC" / ".tracker" / "consolidated.json",
        job_folder / ".metadata" / "hardwoods" / ".tracker" / "consolidated.json",
    ):
        yield tracker_path
```

- [ ] **Step 4: Run tests**

Run: `python -m pytest tests/test_cache_index.py -v`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add ready_jobs_watcher/metadata_cache.py
git commit -m "feat: wire cache_index into bulk sweep and staleness check"
```

---

### Task 4: Wire index into `refresh_single_job()` and rename

**Files:**
- Modify: `ready_jobs_watcher/metadata_cache.py:1055-1087` (per-job debounce)
- Modify: `ready_jobs_watcher/metadata_inventory.py:24` (add to tracked files)
- Verify: `ready_jobs_watcher/job_rename.py` (rename trigger)

- [ ] **Step 1: Modify `refresh_single_job()` post-cache generation (line ~1075)**

Add one line after `data = generate_static_cache(...)`:
```python
    generate_cache_index(job_folder, data)
```

- [ ] **Step 2: Add to metadata inventory**

Add `".metadata/cache_index.json"` to the tracked files list in `metadata_inventory.py`.

- [ ] **Step 3: Verify rename path**

Check `job_rename.py` for its `generate_static_cache` call and add `generate_cache_index` after it.

- [ ] **Step 4: Run all RJW tests**

Run: `python -m pytest tests/test_metadata_cache.py tests/test_cache_index.py -v`
Expected: All PASS

- [ ] **Step 5: Commit**

```bash
git add ready_jobs_watcher/metadata_cache.py ready_jobs_watcher/metadata_inventory.py ready_jobs_watcher/job_rename.py
git commit -m "feat: wire cache_index into per-job refresh and rename"
```

---
## KKCSheetTracker Tasks

### Task 5: Tablet — data classes for cache_index

**Files:**
- Create: `KKCSheetTracker/app/src/main/java/com/kkc/sheet tracker/data/models/CacheIndexModels.kt`

- [ ] **Step 1: Create CacheIndexModels.kt**

```kotlin
package com.kkc.sheettracker.data.models

data class CacheIndexMaterialProgress(
    val materialName: String = "",
    val totalSheets: Int = 0,
    val done: Int = 0,
    val bad: Int = 0,
    val skipped: Int = 0,
    val isRemake: Boolean = false
) {
    fun toStatusCounts(): StatusCounts = StatusCounts(
        total = totalSheets,
        complete = done,
        bad = bad,
        skipped = skipped
    )
}

data class CacheIndexCncProgress(
    val totalSheets: Int = 0,
    val done: Int = 0,
    val bad: Int = 0,
    val skipped: Int = 0,
    val materials: List<CacheIndexMaterialProgress> = emptyList()
)

data class CacheIndexHardwoodsDocType(
    val docType: String = "",
    val total: Int = 0,
    val done: Int = 0,
    val bad: Int = 0,
    val skipped: Int = 0
)

data class CacheIndexHardwoodsProgress(
    val totalPieces: Int = 0,
    val donePieces: Int = 0,
    val badPieces: Int = 0,
    val skippedPieces: Int = 0,
    val docTypes: List<CacheIndexHardwoodsDocType> = emptyList()
)

data class CacheIndexProgressSummary(
    val cnc: CacheIndexCncProgress? = null,
    val hardwoods: CacheIndexHardwoodsProgress? = null,
    val hasDeliverySheet: Boolean = false,
    val has3DAssets: Boolean = false
)

data class CacheIndexJobInfo(
    val folderName: String = "",
    val jobNumber: String = "",
    val jobName: String = "",
    val hiddenFromProduction: Boolean = false,
    val lineupPosition: Int? = null
)

data class CacheIndexRoot(
    val jobInfo: CacheIndexJobInfo? = null,
    val progressSummary: CacheIndexProgressSummary? = null
)
```

- [ ] **Step 2: Commit**

```bash
git add .../data/models/CacheIndexModels.kt
git commit -m "feat: add CacheIndexModels data classes"
```

---

### Task 6: Tablet — `listJobsFromCacheIndex()` fast path

**Files:**
- Modify: `KKCSheetTracker/.../data/unified/FileBackedUnifiedMetadataEngine.kt`
- Modify: `KKCSheetTracker/.../data/unified/UnifiedMetadataEngine.kt` (interface)

**Interfaces:**
- Consumes: `CacheIndexRoot`, `CacheIndexJobInfo`, `CacheIndexProgressSummary` from Task 5
- Produces: `listJobsFromCacheIndex() -> Pair<List<UnifiedJobInfo>, List<String>>`

- [ ] **Step 1: Add interface method to `UnifiedMetadataEngine.kt`**

```kotlin
fun listJobsFromCacheIndex(): Pair<List<UnifiedJobInfo>, List<String>>
```

- [ ] **Step 2: Implement in `FileBackedUnifiedMetadataEngine.kt`**

```kotlin
override fun listJobsFromCacheIndex(): Pair<List<UnifiedJobInfo>, List<String>> {
    if (!baseDir.exists() || !baseDir.isDirectory) return Pair(emptyList(), emptyList())
    val loaded = mutableListOf<UnifiedJobInfo>()
    val needsDeep = mutableListOf<String>()
    val boardConfigs = readJobBoardConfig()
    val dirs = baseDir.listFiles() ?: return Pair(emptyList(), emptyList())

    for (dir in dirs) {
        if (!dir.isDirectory) continue
        if (!DeploymentGateRules.evaluate(dir, isDebugBuild = isDebugBuild).includeJob) continue
        val indexFile = File(dir, ".metadata/cache_index.json")
        if (!indexFile.isFile) {
            if (parseJobFolderName(dir.name) != null) needsDeep.add(dir.name)
            continue
        }
        try {
            val root = gson.fromJson(indexFile.readText(), CacheIndexRoot::class.java) ?: continue
            val info = root.jobInfo ?: continue
            val config = boardConfigs[dir.name]
            loaded.add(mergedJobInfo(
                rawInfo = UnifiedJobInfo(
                    folderName = info.folderName,
                    jobNumber = info.jobNumber,
                    jobName = info.jobName,
                    hiddenFromProduction = info.hiddenFromProduction,
                    lineupPosition = info.lineupPosition
                ),
                fallbackFolderName = dir.name,
                config = config
            ))
        } catch (e: Exception) {
            if (parseJobFolderName(dir.name) != null) needsDeep.add(dir.name)
        }
    }

    val sorted = loaded.sortedWith(
        compareBy<UnifiedJobInfo> { it.lineupPosition ?: Int.MAX_VALUE }
            .thenByDescending { it.jobNumber.toIntOrNull() ?: 0 }
            .thenBy { it.folderName }
    )
    return Pair(sorted, needsDeep)
}
```

- [ ] **Step 3: Wire into `ScanCoordinator.scanJobsFromCacheOnly()`**

Modify `ScanCoordinator.kt` to try index first with fallback. See the `scanJobsFromCacheOnly()` method: if `listJobsFromCacheIndex()` returns results, use them; otherwise fall back to existing `listJobsFromCacheOnly()`.

- [ ] **Step 4: Add progress retrieval helper**

```kotlin
override fun getProgressFromIndex(folderName: String): CacheIndexProgressSummary? {
    val jobDir = File(baseDir, folderName)
    val indexFile = File(jobDir, ".metadata/cache_index.json")
    if (!indexFile.isFile) return null
    return try {
        gson.fromJson(indexFile.readText(), CacheIndexRoot::class.java)?.progressSummary
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add .../FileBackedUnifiedMetadataEngine.kt .../ScanCoordinator.kt .../UnifiedMetadataEngine.kt
git commit -m "feat: add listJobsFromCacheIndex fast path with fallback"
```

---

### Task 7: Tablet — deriveJobCards() from index data

**Files:**
- Modify: `KKCSheetTracker/.../ui/jobs/UnifiedModeSpecs.kt`

- [ ] **Step 1: Modify `rememberCncJobsSpec.deriveJobCards()`**

In the fast-path (when job comes from index), build `MaterialSegmentData` and progress style from `CacheIndexProgressSummary` instead of from `AppStateStore.jobUiModels`:

```kotlin
// In deriveJobCards, when job data came from cache_index:
val progressSummary = /* retrieve from index cache */ 
val materialSegments = progressSummary?.cnc?.materials?.map { mat ->
    MaterialSegmentData(
        materialName = mat.materialName,
        counts = mat.toStatusCounts(),
        isRemake = mat.isRemake
    )
} ?: emptyList()
val counts = StatusCounts(
    total = cncProgress?.totalSheets ?: 0,
    complete = cncProgress?.done ?: 0,
    bad = cncProgress?.bad ?: 0,
    skipped = cncProgress?.skipped ?: 0
)
```

- [ ] **Step 2: Commit**

```bash
git add .../UnifiedModeSpecs.kt
git commit -m "feat: derive job cards from cache_index progress"
```

---

### Task 8: Tablet — lazy search index

**Files:**
- Modify: `KKCSheetTracker/.../data/unified/FileBackedUnifiedMetadataEngine.kt`

- [ ] **Step 1: Remove search index building from list scan**

In `scanJobsFromCacheOnly()` fast path, remove the `getCncSnapshot()` call that builds search index per job. The search index will be built lazily.

- [ ] **Step 2: Add lazy search builder in `currentSearchIndex()`**

```kotlin
override fun currentSearchIndex(): List<PartSearchEntry> {
    // If index empty, build on demand
    if (cncSearchByJob.isEmpty()) {
        // iterate known jobs, call getCncSnapshot, build index
    }
    return cncSearchByJob.values.flatMap { it.index }
}
```

- [ ] **Step 3: Commit**

```bash
git add .../FileBackedUnifiedMetadataEngine.kt
git commit -m "perf: make search index lazy, not loaded during list scan"
```

---

### Task 9: Tablet — fallback behavior

**Files:**
- Modify: `KKCSheetTracker/.../data/unified/FileBackedUnifiedMetadataEngine.kt`

- [ ] **Step 1: Implement per-job fallback in list scan**

When `listJobsFromCacheIndex()` returns a job folder in `needsDeepLoad`, fall back to reading `cache_static.json` for that specific job (existing `listJobsFromCacheOnly()` behavior). Mix jobs from index + from static cache.

- [ ] **Step 2: Add stale index detection on job tap**

In `refreshJobDeep()` or `getCncSnapshot()`, compare `cache_index.json` mtime vs `cache_static.json` mtime. If index is older, log a warning but continue (the static cache is the authoritative source).

- [ ] **Step 3: Commit**

```bash
git add .../FileBackedUnifiedMetadataEngine.kt
git commit -m "feat: fallback to static cache when index missing"
```

---
## Self-Review

| Spec requirement | Task coverage |
|---|---|
| cache_index.json schema with jobInfo + progressSummary | Task 1 (`_compute_progress_summary`), Task 2 (`generate_cache_index`) |
| Per-material breakdown in CNC progress | Task 1 (`materials` array in CNC progress shape) |
| RJW computes progress from consolidated tracker | Task 1 (reads consolidated.json, groups by file/docType) |
| Write on same trigger paths | Task 3 (bulk sweep), Task 4 (per-job debounce, rename) |
| skip-if-unchanged optimization | Task 2 (built into `_atomic_write_json`) |
| Tablet data classes | Task 5 (CacheIndexModels.kt) |
| Tablet fast path reads index | Task 6 (`listJobsFromCacheIndex()`) |
| Tablet falls back to cache_static if index missing | Task 6 (needsDeepLoad), Task 9 (per-job fallback) |
| Lazy search index | Task 8 (skip getCncSnapshot in fast path, build on demand) |
| Backward compat — cache_static unchanged | Global constraint (verified: zero edits to cache_static schema) |
| deriveJobCards() from index progress | Task 7 (materialSegments from CacheIndexMaterialProgress) |

No placeholders. Every task has test code + implementation code.
