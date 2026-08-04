# Strip Background Loads from Hardwoods + Assembly

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hardwoods and Assembly coordinators match CNC: list from cache_index only, zero background I/O, single-job load on tap. All detail/dashboard screens that read `snapshot.jobs` switch to direct engine calls.

**Architecture:** `scanJobsFromCacheOnly()` / `scanAssemblyJobs()` call `listJobsFromCacheOnly()` and return empty results. No `getHardwoodsSnapshot()` / `getAssemblySnapshot()` in background. Cards from `getCachedJobInfos()` + `getProgressFromIndex()`. Detail screens load on tap via direct engine calls. Dashboard screens switch to engine reads.

**Tech Stack:** Kotlin, Jetpack Compose, Gson

## Global Constraints

- All 570 tests must pass
- Detail/dashboard screens must work without snapshot.jobs
- Cards use `getCachedJobInfos()` + `getProgressFromIndex()` — already done
- Build zero new warnings
- Fix all 7 gaps found in adversarial review

## Per-Task Agent Passes

Each task requires 3 sequential agent passes:
1. **Implementer** — writes/edits code, runs compile check
2. **Spec reviewer** — verifies implementation matches exactly what the plan specifies
3. **Verification** — runs full test suite (`testDebugUnitTest`), confirms build clean

Task completes only when all 3 passes succeed. Findings from pass 2 or 3 trigger fix round before re-verifying.

---
### Task 1: Fix Hardwoods screens before stripping

**Files:**
- Modify: `ui/hardwoods/HardwoodsJobDetailScreen.kt:110-113` (G2)
- Modify: `ui/hardwoods/HardwoodsWorkspaceScreen.kt:382-383` (G5)
- Modify: `ui/dashboard/UnifiedModeDashboardScreen.kt:746` (G1)

**G2 — HardwoodsJobDetailScreen needs direct engine call.**

Line 110: `scanState.snapshot.jobs.firstOrNull { it.folderName == jobFolderName } ?: HardwoodJob(...)` → stub. Replace with:
```kotlin
val job = engine.getHardwoodsSnapshot(jobFolderName)?.job
    ?: HardwoodJob(jobFolderName, "", "")
```
Add `engine: UnifiedMetadataEngine` parameter. Get engine from `UnifiedMetadataEngineRegistry.getOrCreate(File(scanState.snapshot.basePath), BuildConfig.DEBUG)`.

**G5 — HardwoodsWorkspaceScreen needs direct engine call.**

Line 382: `scanState.snapshot.jobs.firstOrNull { it.folderName == jobFolderName }`. Replace with `engine.getHardwoodsSnapshot(jobFolderName)?.job`. Same engine pattern as above.

**G1 — Hardwoods dashboard needs engine call.**

Line 746: `val jobs = scanState.snapshot.jobs`. Replace with engine call to get hardwood list:
```kotlin
val engine = UnifiedMetadataEngineRegistry.getOrCreate(File(basePath), BuildConfig.DEBUG)
val jobs = engine.getCachedJobInfos().mapNotNull { info ->
    engine.getHardwoodsSnapshot(info.folderName)?.job
}
```

- [ ] **Step 1: Fix G2 (JobDetail)**

- [ ] **Step 2: Fix G5 (Workspace)**

- [ ] **Step 3: Fix G1 (Dashboard)**

- [ ] **Step 4: Verify build and tests**
```bash
.\gradlew.bat :app:compileDebugKotlin --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

---
### Task 2: Fix Assembly screens before stripping

**Files:**
- Modify: `data/AssemblyStateStore.kt:52,59,178` (G3, G4, G6)

**G3 — AssemblyStateStore.getJobs() reads empty snapshot.**
`app/src/main/java/com/kkc/sheettracker/data/AssemblyStateStore.kt:52`: `assemblyScanCoordinator.state.value.snapshot.jobs` → empty.

Replace with engine call. AssemblyStateStore needs engine access. Add `unifiedEngine` parameter or obtain from registry.

**G4 — CNC/hardwoods progress from empty snapshots.**
Lines 52-59 read CNC and hardwoods snapshots for progress. Both now empty. Since cards already use `getProgressFromIndex()`, keep this path but with engine fallback:
```kotlin
val cncCounts = scanCoordinator.unifiedEngine.getProgressFromIndex(job.folderName)?.cnc?.let {
    StatusCounts(it.totalSheets, it.done, it.bad, it.skipped)
}
```

**G6 — Assembly search reads empty snapshot.**
Line 178: `getJobs()` → empty. Use `engine.getCachedJobInfos()` + `getAssemblySnapshot()` per job instead.

- [ ] **Step 1: Fix G3 (getJobs)**

- [ ] **Step 2: Fix G4 (progress from engine)**

- [ ] **Step 3: Fix G6 (search index)**

- [ ] **Step 4: Verify build and tests**
```bash
.\gradlew.bat :app:compileDebugKotlin --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

---
### Task 3: Strip HardwoodsScanCoordinator background load

**Files:**
- Modify: `data/HardwoodsScanCoordinator.kt:85-137`

- [ ] **Step 1: Fix staleness guard (line 95)**

`previous.snapshot.jobs.isNotEmpty()` → `previous.snapshot.generation > 0`

- [ ] **Step 2: Strip `HardwoodsRepository.scanJobsFromCacheOnly()` to return empty**

`data/HardwoodsRepository.kt:63-78`:
```kotlin
fun scanJobsFromCacheOnly(): HardwoodsCacheScanResult {
    val (_, needsDeepLoad) = engine().listJobsFromCacheOnly()
    return HardwoodsCacheScanResult(jobs = emptyList(), searchIndex = emptyList(), needsDeepLoad = needsDeepLoad)
}
```

- [ ] **Step 3: Remove background deep load (lines 129-137)**

```kotlin
// Jobs load on per-tap via HardwoodsJobDetailScreen. No background deep-load needed.
```

- [ ] **Step 4: Verify**
```bash
.\gradlew.bat :app:compileDebugKotlin --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

---
### Task 4: Strip AssemblyScanCoordinator background load

**Files:**
- Modify: `data/AssemblyScanCoordinator.kt:108-174`

- [ ] **Step 1: Fix staleness guard (line 113)**

`previous.snapshot.jobs.isNotEmpty()` → `previous.snapshot.generation > 0`

- [ ] **Step 2: Strip `scanAssemblyJobs()` to return empty (lines 153-174)**

```kotlin
private fun scanAssemblyJobs(): List<AssemblyJob> {
    if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
    val (_, _) = unifiedEngine.listJobsFromCacheOnly()
    return emptyList()
}
```

- [ ] **Step 3: Verify**
```bash
.\gradlew.bat :app:compileDebugKotlin --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

---
### Task 5: Build release and install

```bash
.\gradlew.bat :app:assembleRelease --no-daemon
adb install -r app\build\outputs\apk\release\app-release.apk
```
