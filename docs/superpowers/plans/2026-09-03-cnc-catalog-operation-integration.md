# CNC Catalog Operation Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the revisioned CNC catalog feature onto current `main` while preserving restart-safe Manage Code mutations.

**Architecture:** Catalog reads remain cache-first in `MixCatalogRepository`. `MixOperationCoordinator` persists existing polled operations plus revisioned catalog actions. Job Detail maps active cached entries to the current explicit `ViewerMixSelection` route model.

**Tech Stack:** Kotlin, Jetpack Compose, coroutines/StateFlow, Gson, OkHttp, JUnit 4, MockWebServer.

**Spec:** docs/superpowers/specs/2026-09-03-cnc-catalog-operation-integration-design.md

## Global Constraints

- `MixOperationCoordinator` is the sole mutation owner.
- Persist every action before a network request; never silently retry an unacknowledged request.
- Retain `expectedRevision` on retry and require a new choice after `catalog_changed`.
- Cache is display-only while offline; keep the current viewer route and physical-page progress model.
- Do not install, uninstall, copy, or deploy an APK during this work.

---

### Task 1: Add and execute durable catalog actions

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/mixservice/MixOperationModels.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/mixservice/MixOperationCoordinator.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceClient.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/MixOperationCoordinatorTest.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/MixOperationSessionStoreTest.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/MixServiceClientTest.kt`

**Interfaces:** Produce `CATALOG_CREATE`, `CATALOG_REPLACE`, and `EXTERNAL_DELETE` actions with `expectedRevision` and `externalMixFilename`; extend `MixOperationService` with `submitCatalogMutation(action): MixCatalogMutationResult`.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun catalogReplace_persistsRevisionBeforeSubmission() = runTest {
    coordinator.start(session(ManageCodeOperationAction.catalogReplace("Maple", "Current", listOf("R2.pgm"), 7)))
    advanceUntilIdle()
    assertEquals(7, store.saved.single().actions.single().expectedRevision)
    assertEquals("submitting", store.saved.single().current.state)
}

@Test fun unknownCatalogSubmission_restoresInterruptedWithoutReplay() = runTest {
    store.restore(session(catalogReplace(), state = "submitting"))
    coordinator.restore(); advanceUntilIdle()
    assertEquals("interrupted", coordinator.sessions.value.getValue("100 - Alpha").current.state)
}
```

- [ ] **Step 2: Verify red**

Run: `./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.mixservice.MixOperationCoordinatorTest --tests com.kkc.sheettracker.data.mixservice.MixOperationSessionStoreTest`

Expected: compilation fails because catalog action factories and service submission do not exist.

- [ ] **Step 3: Implement minimal hybrid dispatch**

Add catalog action factories and encoded client calls. Keep MIX and PGM actions on the existing polling path. For catalog actions, persist `submitting`, submit once, advance only on `Success` or `SyncFailed`, and persist a mapped failure otherwise. Restore turns unacknowledged catalog submissions into `interrupted` without replaying them.

- [ ] **Step 4: Verify green**

Run: `./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.mixservice.MixOperationCoordinatorTest --tests com.kkc.sheettracker.data.mixservice.MixOperationSessionStoreTest --tests com.kkc.sheettracker.data.mixservice.MixServiceClientTest`

Expected: PASS for request encoding, stale revision, interrupted recovery, ordering, and legacy polling regressions.

- [ ] **Step 5: Commit**

Run: `git add app/src/main/java/com/kkc/sheettracker/data/mixservice/MixOperationModels.kt app/src/main/java/com/kkc/sheettracker/data/mixservice/MixOperationCoordinator.kt app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceClient.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/MixOperationCoordinatorTest.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/MixOperationSessionStoreTest.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/MixServiceClientTest.kt && git commit -m "feat: coordinate catalog mutations"`

### Task 2: Port catalog reads, projection, and action planning

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceModels.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixservice/MixCatalogCache.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixservice/MixCatalogProjection.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/mixservice/SheetOrderResolver.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestrator.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/MixCatalogCacheTest.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/MixCatalogProjectionTest.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestratorTest.kt`

**Interfaces:** Produce cache-first `MixCatalogRepository`, active-only projection, `pagesForMix`, and catalog targets that create durable actions instead of direct requests.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun offlineRefresh_keepsLastValidCatalogVisible() = runTest {
    cache.write(snapshot(revision = 7)); client.nextFetch = MixCatalogFetchResult.NetworkError
    assertEquals(7, repository.cached("100 - Alpha", "Maple")!!.revision)
    assertEquals(MixCatalogFetchResult.NetworkError, repository.refresh("100 - Alpha", "Maple"))
}

@Test fun activeCatalogTarget_queuesCatalogBeforePgmEdit() {
    assertEquals(listOf("catalog_replace", "pgm_edits"), actionsFor(replaceTarget(), changeWithEdits).map { it.kind })
}
```

- [ ] **Step 2: Verify red**

Run: `./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.mixservice.MixCatalogCacheTest --tests com.kkc.sheettracker.data.mixservice.MixCatalogProjectionTest --tests com.kkc.sheettracker.data.mixservice.ManageCodeOrchestratorTest`

Expected: FAIL because cache, projection, and action planning are absent.

- [ ] **Step 3: Implement cache-first primitives**

Port validated lifecycle DTOs, persistent cache, active/history/external projection, and exact PGM-to-page mapping from `codex/cnc-mix-catalog`. Convert catalog intents to Task 1 factories with their original revision; UI-facing repository APIs remain read-only.

- [ ] **Step 4: Verify green**

Run: `./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.mixservice.MixCatalogCacheTest --tests com.kkc.sheettracker.data.mixservice.MixCatalogProjectionTest --tests com.kkc.sheettracker.data.mixservice.ManageCodeOrchestratorTest --tests com.kkc.sheettracker.data.mixservice.SheetOrderResolverTest`

Expected: PASS for cache retention, external exclusion, exact page order, and action sequencing.

- [ ] **Step 5: Commit**

Run: `git add app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceModels.kt app/src/main/java/com/kkc/sheettracker/data/mixservice/MixCatalogCache.kt app/src/main/java/com/kkc/sheettracker/data/mixservice/MixCatalogProjection.kt app/src/main/java/com/kkc/sheettracker/data/mixservice/SheetOrderResolver.kt app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestrator.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/MixCatalogCacheTest.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/MixCatalogProjectionTest.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestratorTest.kt && git commit -m "feat: cache and plan CNC catalog actions"`

### Task 3: Integrate UI, record ancestry, and verify

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/managecode/MixActionDialog.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/detail/JobDetailScreen.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/managecode/MixActionDialogTest.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/managecode/ManageCodeOperationUiStateTest.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/detail/JobDetailLoadStateTest.kt`

**Interfaces:** Consume durable action planning and cache snapshots; produce queued sessions and current `ViewerMixSelection` values.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun externalEntry_blocksGenerationAndPlansExactDelete() {
    val content = mixActionDialogContent(snapshot(external("Manual.mix")))
    assertNull(content.automaticTarget)
    assertEquals("Manual.mix", content.externalMixes.single().mixFilename)
}

@Test fun activeCatalogRows_produceExplicitViewerSelections() {
    assertEquals(listOf(2, 1), catalogMaterialEntries(material, snapshot(active("Current", "R2.pgm", "R1.pgm"))).single().mixSelection!!.pageOrder)
}
```

- [ ] **Step 2: Verify red**

Run: `./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.managecode.MixActionDialogTest --tests com.kkc.sheettracker.ui.managecode.ManageCodeOperationUiStateTest --tests com.kkc.sheettracker.ui.detail.JobDetailLoadStateTest`

Expected: FAIL because UI still uses direct catalog mutations or `listMixes`-only cards.

- [ ] **Step 3: Implement and integrate**

Port cache-first catalog presentation and dialog selection. Keep main's coordinator status, restore, retry, and PGM behavior; replace direct mutations with queued sessions. Refresh only changed material after completion. Project active entries to `ViewerMixSelection`, retaining AppState counts and excluding history/external entries.

- [ ] **Step 4: Verify green and record ancestry**

Run focused Task 3 tests, then `git merge --no-ff -s ours codex/cnc-mix-catalog -m "Merge branch 'codex/cnc-mix-catalog'"`. Use the ancestry-only merge only after porting every required behavior and test, so the incompatible synchronous UI is not restored.

- [ ] **Step 5: Full verification**

Run: `./gradlew.bat testDebugUnitTest`

Run: `./gradlew.bat assembleRelease`

Run: `git diff --check` and `git status --short`

Expected: both Gradle commands report `BUILD SUCCESSFUL`; no whitespace errors; clean worktree; no APK copy or deployment.
