# CNC Dashboard from Cache Index — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CNC dashboard loads instantly from cache_index + ProgressStore. No cache_static.json reads. No dependency on AppStateStore.derive().

**Architecture:** Dashboard reads `getCachedJobInfos()` for job list + `getProgressFromIndex().cnc` for aggregates. Bad parts/skipped from ProgressStore. `DashboardUiModel` populated directly.

**Tech Stack:** Kotlin, Jetpack Compose, Gson

## Global Constraints

- `DashboardUiModel` schema unchanged
- `DashboardWidgetFactories.buildCncDashboardWidgets()` unchanged
- Zero cache_static.json reads
- All 570 tests pass

---
### Task 1: Rewrite CNC dashboard data derivation

**Files:**
- Modify: `ui/dashboard/UnifiedModeDashboardScreen.kt:169-260` (CncDashboardContent)
- Modify: `data/AppStateStore.kt` (bypass derive for CNC dashboard)

- [ ] **Step 1: Add `engine` to CncDashboardContent parameters**

Add `engine: UnifiedMetadataEngine` parameter. Get it from `UnifiedMetadataEngineRegistry.getOrCreate(File(scanState.snapshot.basePath), BuildConfig.DEBUG)` inside the composable. Or add as parameter to `CncDashboardContent`.

- [ ] **Step 2: Replace dashboard derivation**

Replace the `appStateStore.dashboardUiModel` read with direct computation:

```kotlin
val dashboard = remember(scanState.snapshot.generation) {
    val jobInfos = engine.getCachedJobInfos()
    var totalSheets = 0
    var completedSheets = 0
    var badPartsSheets = 0
    var skippedSheets = 0
    val recentMaterials = mutableListOf<DashboardRecentMaterialItem>()
    val remakeMaterials = mutableListOf<DashboardRecentMaterialItem>()
    
    jobInfos.forEach { info ->
        val cnc = engine.getProgressFromIndex(info.folderName)?.cnc ?: return@forEach
        totalSheets += cnc.totalSheets
        completedSheets += cnc.done
        badPartsSheets += cnc.bad
        skippedSheets += cnc.skipped
        
        cnc.materials.forEach { mat ->
            if (mat.done < mat.totalSheets && mat.totalSheets > 0) {
                recentMaterials.add(DashboardRecentMaterialItem(info.folderName, info.jobNumber, mat.materialName, mat.totalSheets, mat.done, mat.bad, mat.skipped))
            }
            if (mat.isRemake && mat.done < mat.totalSheets && mat.totalSheets > 0) {
                remakeMaterials.add(DashboardRecentMaterialItem(info.folderName, info.jobNumber, mat.materialName, mat.totalSheets, mat.done, mat.bad, mat.skipped))
            }
        }
    }
    
    DashboardUiModel(
        totalJobs = jobInfos.size,
        totalSheets = totalSheets,
        completedSheets = completedSheets,
        badPartsSheets = badPartsSheets,
        skippedSheets = skippedSheets,
        recentInProgressMaterials = recentMaterials,
        incompleteRemakeMaterials = remakeMaterials
    )
}
```

Check `DashboardRecentMaterialItem` constructor to get the exact field names. Adjust if needed.

- [ ] **Step 3: Remove or adjust the `appStateStore.requestRecompute()` call**

The `LaunchedEffect` at line 197 calls `appStateStore.requestRecompute()` which derives from snapshot.jobs (empty). Replace with a simple recomposition trigger based on generation.

- [ ] **Step 4: Verify build + tests**

```bash
.\gradlew.bat :app:compileDebugKotlin --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

---
### Task 2: Wire engine parameter to CncDashboardContent

**Files:**
- Modify: `ui/dashboard/UnifiedModeDashboardScreen.kt:138` (call site)

- [ ] **Step 1: Pass `engine` at call site**

At line 138, add `engine = unifiedEngine` to the `CncDashboardContent(...)` call. Get `unifiedEngine` from `UnifiedMetadataEngineRegistry.getOrCreate()` if not already in scope.

- [ ] **Step 2: Verify build**

```bash
.\gradlew.bat :app:compileDebugKotlin --no-daemon
```

---
### Task 3: Build release and install

```bash
.\gradlew.bat :app:assembleRelease --no-daemon
adb install -r app\build\outputs\apk\release\app-release.apk
```
