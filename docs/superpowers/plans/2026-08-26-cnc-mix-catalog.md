
# CNC Mix Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Safely manage CNC mix files from the tablet, cache their catalog, and make Job Detail and the viewer follow the selected active mix's physical-page order.

**Architecture:** The CNC PGM Mix Service is the sole owner of definitions and compiled mix files. It publishes a revisioned per-material catalog of active, history, and external files. Android persists that catalog, shows active mixes as virtual material rows, and routes an explicit mix name to the viewer while keeping progress keyed to the existing physical PDF page.

**Tech Stack:** Python unittest service, Kotlin/Compose Android app, Gson, OkHttp, MockWebServer, JUnit 4.

**Spec:** docs/superpowers/specs/2026-08-26-cnc-mix-catalog-design.md

## Global Constraints

- Never write CNC mix files or definitions directly from Android.
- Preserve existing WINXISO locking and never alter PGM source files.
- Archive name format is Original Name — YYYY-MM-DD HH-mm, then (2), (3), etc. when required.
- External mix files block mutation until the service confirms deletion of that exact external filename.
- History mixes are recovery-only; never put them in production cards or viewer navigation.
- A completed physical page is completed in every active mix where it appears.
- Preserve unrelated worktree changes and never run adb uninstall.

---

## File Structure

Service repo C:\Scripts\PGM_BCR_Loader:
- Create mix_service/catalog.py and tests/test_catalog.py for pure lifecycle/revision logic.
- Modify mix_service/definitions.py, paths.py, service.py, tests/test_definitions.py, tests/test_service.py, and docs/PGM_MIX_SERVICE_AGENT_GUIDE.md.

Tablet repo C:\Scripts\KKCSheetTracker:
- Modify data/mixservice/MixServiceModels.kt, MixServiceClient.kt, SheetOrderResolver.kt, ManageCodeOrchestrator.kt, ProgressStore.kt, JobDetailScreen.kt, ManageCodeScreen.kt, SheetViewerScreen.kt, and NavGraph.kt.
- Create MixCatalogCache.kt, MixCatalogProjection.kt, MixActionDialog.kt, MixCatalogCacheTest.kt, MixCatalogProjectionTest.kt, MixActionDialogTest.kt, and ViewerRouteTest.kt.
- Extend MixServiceClientTest.kt, SheetOrderResolverTest.kt, ManageCodeOrchestratorTest.kt, ProgressStoreTest.kt, JobDetailLoadStateTest.kt, and SheetViewerScreenTest.kt.

## Task 1: Service catalog and storage primitives

**Files:**
- Create: C:\Scripts\PGM_BCR_Loader\mix_service\catalog.py
- Modify: mix_service\definitions.py and mix_service\paths.py
- Test: tests\test_catalog.py and tests\test_definitions.py

**Interfaces:**
- Produces build_material_catalog(cnc_root, definitions, job, material, revision), allocate_archive_name(name, existing_names, stamp), resolve_bare_mix_path(...), and DefinitionsStore.replace_active_with_history(...).

- [ ] **Step 1: Write failing tests**

~~~python
def test_catalog_classifies_service_and_external_files(tmp_path):
    material = tmp_path / "100 - Job" / "Mat"
    material.mkdir(parents=True)
    for name in ("Current.mix", "Current — 2026-08-26 14-30.mix", "Manual.mix"):
        (material / name).write_bytes(b"mix")
    entries = catalog.build_material_catalog(str(tmp_path), [
        {"name": "Current", "job": "100 - Job", "material": "Mat",
         "mixFilename": "Current.mix", "programs": ["R2.pgm"], "lifecycle": "active"},
        {"name": "Current — 2026-08-26 14-30", "job": "100 - Job", "material": "Mat",
         "mixFilename": "Current — 2026-08-26 14-30.mix", "programs": ["R1.pgm"], "lifecycle": "history"},
    ], "100 - Job", "Mat", "r7")["entries"]
    assert [(item["name"], item["lifecycle"]) for item in entries] == [
        ("Current", "active"), ("Current — 2026-08-26 14-30", "history"), ("Manual.mix", "external")]
~~~

~~~python
def test_replace_active_with_history_is_one_snapshot_write(self):
    self.store.upsert(active("Current", ["R1.pgm"]))
    self.store.replace_active_with_history(
        "Current", active("Current", ["R2.pgm"]), history("Current — 2026-08-26 14-30", ["R1.pgm"]))
    self.assertEqual(self.store.get("Current")["programs"], ["R2.pgm"])
    self.assertEqual(self.store.get("Current — 2026-08-26 14-30")["lifecycle"], "history")
~~~

- [ ] **Step 2: Run and confirm failure**

Run: python -m unittest discover -s tests -p "test_catalog.py" -v
Run: python -m unittest discover -s tests -p "test_definitions.py" -v

Expected: FAIL because the helpers and transaction do not exist.

- [ ] **Step 3: Implement minimal primitives**

~~~python
def allocate_archive_name(name, existing_names, stamp):
    base = "%s — %s" % (paths.sanitize_mix_name(name), stamp)
    names = {value.casefold() for value in existing_names}
    candidate, suffix = base, 2
    while candidate.casefold() in names:
        candidate = "%s (%d)" % (base, suffix)
        suffix += 1
    return candidate
~~~

Classify legacy definitions without lifecycle as active. Emit only safe, unclaimed on-disk mix files as external. Add a one-lock, one-write store transaction. resolve_bare_mix_path must require a safe bare mix filename and resolve through resolve_job_material_dir. Extend MIX_NAME_RE to accept the Windows-safe em dash used by archive names, then prove `Current — 2026-08-26 14-30` is accepted while `bad/name` remains rejected.

- [ ] **Step 4: Run and confirm success**

Run: python -m unittest discover -s tests -p "test_catalog.py" -v
Run: python -m unittest discover -s tests -p "test_definitions.py" -v

Expected: PASS; include suffix collision, legacy active default, containment, and invalid replacement coverage.

- [ ] **Step 5: Commit**

~~~powershell
git add mix_service/catalog.py mix_service/definitions.py mix_service/paths.py tests/test_catalog.py tests/test_definitions.py
git commit -m "feat: add CNC mix catalog primitives"
~~~

## Task 2: Service routes and atomic archive-and-replace

**Files:**
- Modify: C:\Scripts\PGM_BCR_Loader\mix_service\service.py
- Modify: tests\test_service.py and docs\PGM_MIX_SERVICE_AGENT_GUIDE.md

**Interfaces:**
- Produces GET /jobs/{job}/materials/{material}/mix-catalog.
- Produces POST /jobs/{job}/materials/{material}/mixes/{name}/replace with programs and expectedRevision.
- Produces DELETE /jobs/{job}/materials/{material}/external-mixes/{filename}.

- [ ] **Step 1: Write failing API tests**

~~~python
def test_catalog_returns_revision_and_external_blocker(self):
    self.server.ctx.store.upsert(definition("Current", "100 - Alpha", "Mat", ["R1.pgm"]))
    open(os.path.join(self.root, "100 - Alpha", "Mat", "Manual.mix"), "wb").write(b"manual")
    status, body = self._get("/jobs/100%20-%20Alpha/materials/Mat/mix-catalog")
    self.assertEqual(status, 200)
    self.assertEqual([entry["lifecycle"] for entry in body["entries"]], ["active", "external"])

def test_replace_keeps_original_name_and_archives_old_membership(self):
    status, body = self._post("/jobs/100%20-%20Alpha/materials/Mat/mixes/Current/replace", {
        "programs": ["R2.pgm"], "expectedRevision": self._catalog_revision("100 - Alpha", "Mat")})
    self.assertEqual(status, 200)
    self.assertEqual(body["catalog"]["entries"][0]["name"], "Current")
    self.assertIn("history", [entry["lifecycle"] for entry in body["catalog"]["entries"]])
~~~

- [ ] **Step 2: Run and confirm failure**

Run: python -m unittest discover -s tests -p "test_service.py" -v

Expected: FAIL with 404 for the new URLs.

- [ ] **Step 3: Implement server behavior**

Make service.py build entries with computed status and revision. Check expectedRevision under the existing compile lock and map CatalogChangedError to 409 code catalog_changed. Refactor _compile_definition to accept persist=False, then perform replacement in this order:

~~~python
os.replace(old_path, archive_path)
try:
    compiled = _compile_definition(ctx, replacement, work_dir, material_dir, persist=False)
    if not compiled["lastCompileOk"]:
        raise ValueError(compiled["lastCompileError"])
    ctx.store.replace_active_with_history(original_name, compiled, archived_old_definition)
except Exception:
    if os.path.isfile(archive_path):
        os.replace(archive_path, old_path)
    raise
~~~

Only return after invalidating inventory caches and rebuilding the catalog. The external-delete route must reclassify the target under the lock and reject service-owned/history/missing/unsafe names before deleting. Document request and response payloads.

- [ ] **Step 4: Run service regression tests**

Run: python -m unittest discover -s tests -p "test_service.py" -v

Expected: PASS, including compile rollback, stale revision, timestamp suffix, service-owned delete rejection, path traversal rejection, and existing history sync cases.

- [ ] **Step 5: Commit**

~~~powershell
git add mix_service/service.py tests/test_service.py docs/PGM_MIX_SERVICE_AGENT_GUIDE.md
git commit -m "feat: expose versioned CNC mix catalog"
~~~

## Task 3: Android catalog client and persistent cache

**Files:**
- Modify: app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceModels.kt and MixServiceClient.kt
- Create: app/src/main/java/com/kkc/sheettracker/data/mixservice/MixCatalogCache.kt
- Test: MixServiceClientTest.kt and new MixCatalogCacheTest.kt

**Interfaces:**
- Produces MixLifecycle, MixCatalogEntry, MixCatalogSnapshot, MixCatalogFetchResult, and MixCatalogRepository.

- [ ] **Step 1: Write failing tests**

~~~kotlin
@Test fun getMixCatalog_parses_active_history_and_external() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"ok":true,"revision":"r7","entries":[
      {"name":"Current","mixFilename":"Current.mix","lifecycle":"active","programs":["R2.pgm"]},
      {"name":"Old","mixFilename":"Old.mix","lifecycle":"history","programs":["R1.pgm"]},
      {"name":"Manual.mix","mixFilename":"Manual.mix","lifecycle":"external"}]}"""))
    assertEquals(MixLifecycle.EXTERNAL, client().getMixCatalog("100 - Alpha", "Mat").snapshot!!.entries.last().lifecycle)
}

@Test fun cache_persists_across_a_new_instance() {
    val root = createTempDirectory("mix-catalog").toFile()
    MixCatalogCache(root).write(snapshot("r1"))
    assertEquals("r1", MixCatalogCache(root).read("100 - Alpha", "Mat")!!.revision)
}
~~~

- [ ] **Step 2: Run and confirm failure**

Run: .\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.mixservice.MixServiceClientTest --tests com.kkc.sheettracker.data.mixservice.MixCatalogCacheTest

Expected: FAIL because catalog DTOs and cache are absent.

- [ ] **Step 3: Implement models, calls, and cache**

~~~kotlin
enum class MixLifecycle { ACTIVE, HISTORY, EXTERNAL }
data class MixCatalogSnapshot(val job: String, val material: String, val revision: String, val entries: List<MixCatalogEntry>)

class MixCatalogRepository(private val client: MixServiceClient, private val cache: MixCatalogCache) {
    fun cached(job: String, material: String) = cache.read(job, material)
    suspend fun refresh(job: String, material: String) =
        client.getMixCatalog(job, material).also { result -> result.snapshot?.let(cache::write) }
}
~~~

Use a SHA-256 job/material file key under injected filesDir/state/mix_catalog and verify embedded keys before returning a file. URL-encode all service path segments. Add catalog get, replace, and external delete calls; map catalog_changed to a distinct result. Mutation success writes the returned catalog before the UI receives it.

- [ ] **Step 4: Run and confirm success**

Run: .\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.mixservice.MixServiceClientTest --tests com.kkc.sheettracker.data.mixservice.MixCatalogCacheTest

Expected: PASS, including URL/body assertions, conflict mapping, durable read, and unchanged revision no-rewrite behavior.

- [ ] **Step 5: Commit**

~~~powershell
git add app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceModels.kt app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceClient.kt app/src/main/java/com/kkc/sheettracker/data/mixservice/MixCatalogCache.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/MixServiceClientTest.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/MixCatalogCacheTest.kt
git commit -m "feat: cache CNC mix catalogs on tablet"
~~~

## Task 4: Active-mix projection, exact page mapping, and scoped counts

**Files:**
- Create: app/src/main/java/com/kkc/sheettracker/data/mixservice/MixCatalogProjection.kt
- Modify: SheetOrderResolver.kt and ProgressStore.kt
- Test: new MixCatalogProjectionTest.kt; modify SheetOrderResolverTest.kt and ProgressStoreTest.kt

**Interfaces:**
- Produces activeMixRows(material, catalog), resolveSelectedActiveMix(catalog, name), pagesForMix(...), and ProgressStore.getStatusCountsForPages(...).

- [ ] **Step 1: Write failing pure tests**

~~~kotlin
@Test fun activeMixes_split_one_material_but_history_and_external_do_not() {
    val rows = activeMixRows(material("19mm"), snapshot(active("First", "R2.pgm"), active("Second", "R1.pgm"), history("Old"), external("Manual.mix")))
    assertEquals(listOf("First - 19mm", "Second - 19mm"), rows.map { it.title })
}

@Test fun pagesForMix_keeps_only_mapped_pages_in_program_order() {
    assertEquals(listOf(2, 1), pagesForMix(pages, listOf(1, 2, 3), listOf("R2.pgm", "R1.pgm", "R2.pgm")))
}
~~~

- [ ] **Step 2: Run and confirm failure**

Run: .\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.mixservice.MixCatalogProjectionTest --tests com.kkc.sheettracker.data.mixservice.SheetOrderResolverTest --tests com.kkc.sheettracker.data.ProgressStoreTest

Expected: FAIL because exact mapping and projection do not exist.

- [ ] **Step 3: Implement projections**

~~~kotlin
fun pagesForMix(pages: List<PageMetadata>, naturalOrder: List<Int>, programs: List<String>): List<Int> {
    val allowed = naturalOrder.toSet()
    val byProgram = buildManageCodeRows(pages).filter { it.pageNumber in allowed }
        .flatMap { row -> row.pgmFiles.map { it to row.pageNumber } }
        .groupBy({ it.first }, { it.second })
    return programs.flatMap { byProgram[it].orEmpty() }.filter { it in allowed }.distinct()
}
~~~

Keep reorderVisiblePages for legacy callers by appending unresolved pages there. Only selected-mix flow calls pagesForMix. Extract getMaterialStatusCounts' per-page loop into getStatusCountsForPages, then delegate the old method to natural pages.

- [ ] **Step 4: Run and confirm success**

Run: .\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.mixservice.MixCatalogProjectionTest --tests com.kkc.sheettracker.data.mixservice.SheetOrderResolverTest --tests com.kkc.sheettracker.data.ProgressStoreTest

Expected: PASS; each mix has scoped totals and duplicated physical pages share completion.

- [ ] **Step 5: Commit**

~~~powershell
git add app/src/main/java/com/kkc/sheettracker/data/mixservice/MixCatalogProjection.kt app/src/main/java/com/kkc/sheettracker/data/mixservice/SheetOrderResolver.kt app/src/main/java/com/kkc/sheettracker/data/ProgressStore.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/MixCatalogProjectionTest.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/SheetOrderResolverTest.kt app/src/test/java/com/kkc/sheettracker/data/ProgressStoreTest.kt
git commit -m "feat: project active CNC mixes into sheet pages"
~~~

## Task 5: Job Detail, route propagation, and Sheet Viewer

**Files:**
- Modify: app/src/main/java/com/kkc/sheettracker/ui/detail/JobDetailScreen.kt, ui/viewer/SheetViewerScreen.kt, navigation/NavGraph.kt
- Test: new navigation/ViewerRouteTest.kt; modify ui/detail/JobDetailLoadStateTest.kt and ui/viewer/SheetViewerScreenTest.kt

**Interfaces:**
- Changes onMaterialClick to (Material, Int, String?) -> Unit.
- Changes viewerRoute(jobFolderName, pdfFilename, page, mixName: String? = null).
- SheetViewerScreen consumes mixName and exactly resolves active catalog entry.

- [ ] **Step 1: Write failing route/viewer tests**

~~~kotlin
@Test fun viewerRoute_encodes_optional_mix_name() {
    assertEquals("viewer/100+-+Alpha/Sheet.pdf/2?mixName=Cut+Order+2",
        viewerRoute("100 - Alpha", "Sheet.pdf", 2, "Cut Order 2"))
}

@Test fun selectedMixMakesPhysicalPageTwoDisplayAsSheetOne() {
    val order = pagesForMix(pages, listOf(1, 2, 3), listOf("R2.pgm", "R1.pgm"))
    assertEquals(1, viewerDisplayNumber(order, 2))
    assertEquals(2, viewerTotal(order))
}
~~~

- [ ] **Step 2: Run and confirm failure**

Run: .\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.navigation.ViewerRouteTest --tests com.kkc.sheettracker.ui.detail.JobDetailLoadStateTest --tests com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest

Expected: FAIL because routes lack mixName and viewer chooses listMixes().firstOrNull().

- [ ] **Step 3: Integrate cached catalog**

JobDetail creates a repository from LocalContext.current.applicationContext.filesDir, shows the cached catalog immediately, and refreshes it in LaunchedEffect. No/one active mix stays one card; multiple active mixes become MIXNAME - MATERIAL cards with explicit-page segmented counts. Cards pass mix name and first mapped page.

Add nullable mixName nav argument to both live viewer composables and ensure archive/view-only calls use null. Viewer loads cached catalog then refreshes; mixName null means natural pages, otherwise resolve only that active mix and use pagesForMix. If missing/history/external, call onMaterialUnavailable rather than substituting another mix. Extract viewerDisplayNumber and viewerTotal helpers. Keep completion/skip writes on physical currentPage.

- [ ] **Step 4: Run and confirm success**

Run: .\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.navigation.ViewerRouteTest --tests com.kkc.sheettracker.ui.detail.JobDetailLoadStateTest --tests com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest

Expected: PASS; cached cards do not wait for HTTP, page 2 displays Sheet 1 of N, and invalid selected mix never falls back.

- [ ] **Step 5: Commit**

~~~powershell
git add app/src/main/java/com/kkc/sheettracker/ui/detail/JobDetailScreen.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/test/java/com/kkc/sheettracker/navigation/ViewerRouteTest.kt app/src/test/java/com/kkc/sheettracker/ui/detail/JobDetailLoadStateTest.kt app/src/test/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreenTest.kt
git commit -m "feat: navigate CNC sheets by selected mix"
~~~

## Task 6: Manage Code selection, versioning, and external deletion

**Files:**
- Create: app/src/main/java/com/kkc/sheettracker/ui/managecode/MixActionDialog.kt
- Modify: ui/managecode/ManageCodeScreen.kt and data/mixservice/ManageCodeOrchestrator.kt
- Test: modify ManageCodeOrchestratorTest.kt; create ui/managecode/MixActionDialogTest.kt

**Interfaces:**
- Produces MixGenerationTarget.FirstDefault, CreateAdditional(name), and ReplaceActive(name, expectedRevision).

- [ ] **Step 1: Write failing validation tests**

~~~kotlin
@Test fun additionalNameMustChangeFromPrefilledOriginalAndBeUnique() {
    assertFalse(validateAdditionalMixName("Current", "Current", setOf("Current")).canSubmit)
    assertTrue(validateAdditionalMixName("Second Cut", "Current", setOf("Current")).canSubmit)
}

@Test fun replaceTargetRetainsNameAndRevision() {
    assertEquals(MixGenerationTarget.ReplaceActive("Current", "r7"),
        replaceTarget(active("Current"), "r7"))
}
~~~

- [ ] **Step 2: Run and confirm failure**

Run: .\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.mixservice.ManageCodeOrchestratorTest --tests com.kkc.sheettracker.ui.managecode.MixActionDialogTest

Expected: FAIL because targets and dialog validation do not exist.

- [ ] **Step 3: Implement actions**

~~~kotlin
sealed interface MixGenerationTarget {
    data object FirstDefault : MixGenerationTarget
    data class CreateAdditional(val name: String) : MixGenerationTarget
    data class ReplaceActive(val name: String, val expectedRevision: String) : MixGenerationTarget
}
~~~

No active/external entry: run first default without a prompt. Active entries: show their name, membership, and status; choose an active mix for replace with archive confirmation or create another with original name prefilled but disabled until changed and unique. External entry: hide mutation controls and show confirmed permanent deletion of exact filename. Generate takes the target, uses the target programs as baseline, preserves duplicate PGM warning/Continue, and updates only the changed material cache. On catalog_changed, refresh and require a new choice.

- [ ] **Step 4: Run and confirm success**

Run: .\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.mixservice.ManageCodeOrchestratorTest --tests com.kkc.sheettracker.ui.managecode.MixActionDialogTest --tests com.kkc.sheettracker.data.mixservice.MixServiceClientTest

Expected: PASS; first no-prompt, changed additional name, replace confirmation, external block/delete, duplicate warning, and stale revision are covered.

- [ ] **Step 5: Commit**

~~~powershell
git add app/src/main/java/com/kkc/sheettracker/ui/managecode/MixActionDialog.kt app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt app/src/main/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestrator.kt app/src/test/java/com/kkc/sheettracker/data/mixservice/ManageCodeOrchestratorTest.kt app/src/test/java/com/kkc/sheettracker/ui/managecode/MixActionDialogTest.kt
git commit -m "feat: manage versioned CNC mixes from tablet"
~~~

## Task 7: Full verification and operator acceptance

**Files:**
- Modify documentation only if acceptance exposes an approved contract clarification: C:\Scripts\PGM_BCR_Loader\docs\PGM_MIX_SERVICE_AGENT_GUIDE.md or docs\superpowers\specs\2026-08-26-cnc-mix-catalog-design.md.

- [ ] **Step 1: Run complete service suite**

Run: python -m unittest discover -s tests -v

Expected: PASS. Preserve every existing assertion.

- [ ] **Step 2: Run complete Android tests and build**

Run: .\gradlew.bat testDebugUnitTest
Run: .\gradlew.bat assembleDebug

Expected: BUILD SUCCESSFUL and app\build\outputs\apk\debug\app-debug.apk exists. Do not install or uninstall without user direction.

- [ ] **Step 3: Execute the acceptance matrix**

~~~powershell
Invoke-RestMethod 'http://192.168.20.4:8477/jobs/<job>/materials/<material>/mix-catalog' | ConvertTo-Json -Depth 8
~~~

Verify automatic first mix, two active custom mixes, shared PGM completion, external blocker/deletion, timestamped replacement history, cold cache while service is unavailable, and physical page 2 as Sheet 1.

- [ ] **Step 4: Commit only an acceptance-driven documentation clarification**

~~~powershell
git add docs/PGM_MIX_SERVICE_AGENT_GUIDE.md docs/superpowers/specs/2026-08-26-cnc-mix-catalog-design.md
git commit -m "docs: clarify CNC mix catalog operation"
~~~

Run this only when acceptance uncovers an approved documentation change; otherwise make no documentation commit.
