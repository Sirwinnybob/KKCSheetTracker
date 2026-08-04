# Strip Specialty Background Scan

**Goal:** Specialty coordinator matches CNC pattern — returns empty from `scanJobsProvider()`, cards from cache_index + snapshot fallback, detail on tap.

**Current state:**
- Staleness guard already fixed (`generation > 0` at line 139) ✅
- Card reads `getCachedJobInfos()` + `specialtyStateStore.deriveJobCards()` fallback ✅
- `SpecialtyRepository.scanJobs()` already uses `listJobsFromCacheOnly()` ✅

**What to change:**

### 1. `SpecialtyScanCoordinator.kt:158` — skip `scanJobsProvider()`
Replace `val jobs = scanJobsProvider()` with `val jobs = emptyList<SpecialtyJob>()`. This stops the per-job `buildSpecialtyJob()` → `loadResolvedItems()` chain.

### 2. `SpecialtyScanCoordinator.kt:163` — fix unchanged check
`previous.snapshot.jobs == jobs` compares empty lists — always true after first scan. Add generation guard:
```kotlin
val unchanged = !force &&
    previous.status == ScanStatus.READY &&
    previous.snapshot.basePath == basePath &&
    previous.snapshot.jobs == jobs &&
    previous.snapshot.generation > 0
```

### 3. Verify detail screen
`SpecialtyJobDetailScreen.kt` — calls `specialtyStateStore.getResolvedItems()` or `refreshJobOnOpen()`. These go through `SpecialtyProgressStore.loadResolvedItems()` which reads admin JSONs directly. No dependency on snapshot.jobs.

**Files:** `SpecialtyScanCoordinator.kt:158,163`
