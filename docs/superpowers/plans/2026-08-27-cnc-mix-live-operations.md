# CNC Mix Live Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run Manage Code mutations as durable CNC-service operations and present their live, job-specific progress after an operator leaves and returns to the screen.

**Architecture:** The service owns a JSON-backed operation registry and a single worker queue that invokes the existing mix/edit mutation functions. Existing write routes return `202` operation snapshots and new read routes expose them. The Android app persists an ordered per-job session in DataStore; an application-scoped coordinator starts/polls service operations and exposes a StateFlow consumed by Manage Code.

**Tech Stack:** Python 3.8 standard library HTTP server and `unittest`; Kotlin, Coroutines/Flow, Jetpack DataStore Preferences, Compose, OkHttp, JUnit/MockWebServer.

**Spec:** `docs/superpowers/specs/2026-08-27-cnc-mix-live-operation-design.md`

## Global Constraints

- Return `202` for every mix or PGM-edit mutation; there are no prior tablet clients to preserve.
- Persist each operation state transition in the service directory and each tablet session change in DataStore.
- Never automatically replay an active operation after service or tablet process restart.
- Show exact `completedPrograms / totalPrograms` only while preparation advances; compilation uses an indeterminate indicator.
- Retain the existing global WINXISO lock and exact job/material path validation.
- Do not uninstall the tablet app during deployment.

---

### Task 1: Service operation registry and worker

**Files:**
- Create: `C:/Scripts/PGM_BCR_Loader/mix_service/operations.py`
- Create: `C:/Scripts/PGM_BCR_Loader/tests/test_operations.py`
- Modify: `C:/Scripts/PGM_BCR_Loader/mix_service/config.py`
- Modify: `C:/Scripts/PGM_BCR_Loader/mix_service/mix_service.py`

**Interfaces:**
- Produces `OperationStore(path)`, `OperationManager(store, logger)`, `submit(kind, job, material, total_programs, work) -> dict`, `get(id) -> dict`, and `list_for_job(job) -> list`.
- An operation snapshot has `id`, `kind`, `job`, `material`, `state`, `stage`, `completedPrograms`, `totalPrograms`, `createdAt`, `startedAt`, `finishedAt`, `error`, and `result`.

- [ ] **Step 1: Write failing registry tests**

```python
def test_submit_persists_queued_snapshot_then_completes_work(tmp_path):
    store = operations.OperationStore(str(tmp_path / "operations.json"))
    manager = operations.OperationManager(store, logger=None)
    operation = manager.submit("mix_write", "648", "19mm", 2,
        lambda progress: (progress("preparing", 2), {"ok": True})[1])
    assert operation["state"] == "queued"
    terminal = wait_for(lambda: manager.get(operation["id"])["state"] == "completed")
    assert terminal["completedPrograms"] == 2
    assert json.loads((tmp_path / "operations.json").read_text())["operations"]

def test_store_marks_nonterminal_operations_interrupted_on_startup(tmp_path):
    (tmp_path / "operations.json").write_text(json.dumps({"operations": [{"id": "x", "state": "running"}]}))
    store = operations.OperationStore(str(tmp_path / "operations.json"))
    assert store.get("x")["state"] == "interrupted"
```

- [ ] **Step 2: Run the tests and verify they fail because `operations` is absent**

Run: `C:/Python38-win7/python.exe -m unittest tests.test_operations -v`

Expected: `ImportError` or `ModuleNotFoundError` for `mix_service.operations`.

- [ ] **Step 3: Implement atomic registry persistence and a daemon worker**

```python
class OperationManager(object):
    def submit(self, kind, job, material, total_programs, work):
        snapshot = self.store.create(kind, job, material, total_programs)
        self._queue.put((snapshot["id"], work))
        return snapshot

    def _run(self):
        while True:
            operation_id, work = self._queue.get()
            self.store.transition(operation_id, state="running", stage="preparing")
            try:
                result = work(lambda stage, completed: self.store.progress(operation_id, stage, completed))
                self.store.transition(operation_id, state="completed", stage="completed", result=result)
            except Exception as error:
                self.store.transition(operation_id, state="failed", stage="failed", error=str(error))
```

`OperationStore` writes a `.tmp` JSON file and uses `os.replace`; startup converts `queued` or `running` to `interrupted` with `finishedAt`.

- [ ] **Step 4: Construct the store/manager in `mix_service.py` and pass it through `ServiceContext`**

```python
operation_store = operations.OperationStore(
    cfg["operations_file"] or os.path.join(base, "operations.json"))
operation_manager = operations.OperationManager(operation_store, logger)
ctx = service.ServiceContext(..., operation_manager=operation_manager)
```

- [ ] **Step 5: Run registry tests**

Run: `C:/Python38-win7/python.exe -m unittest tests.test_operations -v`

Expected: PASS.

### Task 2: Asynchronous service routes and truthful stage updates

**Files:**
- Modify: `C:/Scripts/PGM_BCR_Loader/mix_service/service.py`
- Modify: `C:/Scripts/PGM_BCR_Loader/tests/test_service.py`

**Interfaces:**
- `POST /mixes`, `PUT /mixes/{name}`, and `POST /jobs/{job}/materials/{material}/pgm-edits` return `202` with `{"ok": true, "operation": snapshot}`.
- `GET /operations/{id}` returns one snapshot and `GET /jobs/{job}/operations` returns `{"ok": true, "operations": [...]}`.

- [ ] **Step 1: Write failing HTTP tests**

```python
def test_mix_post_returns_operation_and_get_reports_completion(self):
    status, body = self._post("/mixes", {"name": "Async", "job": "648 - X",
        "material": "19mm Pre_Finished", "programs": ["R590401N.pgm"]})
    self.assertEqual(status, 202)
    operation_id = body["operation"]["id"]
    terminal = self._wait_operation(operation_id)
    self.assertEqual(terminal["state"], "completed")
    self.assertEqual(terminal["stage"], "completed")

def test_job_operations_filter_other_jobs(self):
    # Submit operations for two jobs and assert only the requested job's id is returned.
    self.assertEqual(self._get_json("/jobs/648%20-%20X/operations")[1]["operations"][0]["job"], "648 - X")
```

- [ ] **Step 2: Run the tests and verify the mutation route still returns `200`**

Run: `C:/Python38-win7/python.exe -m unittest tests.test_service.ServiceTests.test_mix_post_returns_operation_and_get_reports_completion -v`

Expected: FAIL because the mutation response is synchronous and no operation route exists.

- [ ] **Step 3: Wrap existing mutation functions in operation work callbacks**

```python
def _submit_mix_operation(ctx, body, replace_name=None):
    def work(progress):
        progress("preparing", 0)
        result = _catalog_create_mix(ctx, body["job"], body["material"], body) if replace_name is None else \
                 _archive_replace_catalog_mix(ctx, body["job"], body["material"], replace_name, body)
        progress("compiling", len(body["programs"]))
        return result
    return ctx.operation_manager.submit("mix_write", body["job"], body["material"], len(body["programs"]), work)
```

Refactor `_compile_definition` to accept an optional progress callback, report `preparing` after each resolved program, then report `compiling` before invoking WINXISO and `syncing` before sidecar work. Existing validation and errors remain inside the worker so the terminal snapshot reports the actual error.

- [ ] **Step 4: Add GET routes, return 202 from all mutation routes, and add DEBUG operation-id/stage logging**

```python
if len(seg) == 2 and seg[0] == "operations":
    json_response(self, 200, {"ok": True, "operation": ctx.operation_manager.get(seg[1])})
    return
if len(seg) == 3 and seg[0] == "jobs" and seg[2] == "operations":
    json_response(self, 200, {"ok": True, "operations": ctx.operation_manager.list_for_job(seg[1])})
    return
```

- [ ] **Step 5: Run all service operation and regression tests**

Run: `C:/Python38-win7/python.exe -m unittest discover -s tests -p test_service.py -v`

Expected: PASS.

### Task 3: Tablet operation protocol and durable coordinator

**Files:**
- Create: `C:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/mixservice/MixOperationModels.kt`
- Create: `C:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/mixservice/MixOperationSessionStore.kt`
- Create: `C:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/mixservice/MixOperationCoordinator.kt`
- Modify: `C:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/mixservice/MixServiceClient.kt`
- Modify: `C:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/KKCApplication.kt`
- Create: `C:/Scripts/KKCSheetTracker/app/src/test/java/com/kkc/sheettracker/data/mixservice/MixOperationCoordinatorTest.kt`
- Modify: `C:/Scripts/KKCSheetTracker/app/src/test/java/com/kkc/sheettracker/data/mixservice/MixServiceClientTest.kt`

**Interfaces:**
- `MixServiceClient.submitMix(...)`, `submitPgmEdits(...)`, `getOperation(id)`, and `listJobOperations(job)` return typed snapshots.
- `MixOperationCoordinator.sessions: StateFlow<Map<String, ManageCodeSession>>`; `start(session)` and `retry(job, material)` do not depend on a Compose scope.

- [ ] **Step 1: Write failing client and coordinator tests**

```kotlin
@Test fun `submitMix unwraps a 202 operation`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"operation":{"id":"op1","state":"queued","stage":"queued","job":"648"}}"""))
    assertEquals("op1", client().submitMix("648", "M", "Mix", listOf("R1.pgm")).id)
}

@Test fun `coordinator retains active job session after observer cancellation`() = runTest {
    val coordinator = coordinator(fakeService = delayedCompletionService)
    coordinator.start(session("648"))
    coordinator.sessions.first { it["648"]!!.current.state == "running" }
    coordinator.sessions.first { it["648"]!!.isTerminal }
    assertEquals(1, persistedStore.load()["648"]!!.completedMaterials)
}
```

- [ ] **Step 2: Run the tests and verify the async methods and coordinator do not exist**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.data.mixservice.MixServiceClientTest --tests com.kkc.sheettracker.data.mixservice.MixOperationCoordinatorTest`

Expected: FAIL with unresolved `submitMix` and `MixOperationCoordinator` references.

- [ ] **Step 3: Implement protocol models and 202 client methods**

```kotlin
data class MixServiceOperation(
    val id: String, val job: String, val material: String, val state: String,
    val stage: String, val completedPrograms: Int = 0, val totalPrograms: Int = 0,
    val error: String? = null
)
```

The client parses only `202` for submit calls, parses the two status routes, and surfaces malformed/failed status responses without turning them into a new write.

- [ ] **Step 4: Implement DataStore session persistence and application-scoped polling**

Use a JSON string preference keyed `mix_operation_sessions`. `MixOperationCoordinator` owns an application `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, writes every queue/operation-id/terminal transition before emitting it, polls at two seconds while active, and restores persisted active sessions in `KKCApplication.onCreate()`. A restored server `interrupted` snapshot becomes a terminal retry-required item; it is not submitted again.

- [ ] **Step 5: Run focused tablet tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.data.mixservice.MixServiceClientTest --tests com.kkc.sheettracker.data.mixservice.MixOperationCoordinatorTest`

Expected: PASS.

### Task 4: Manage Code session binding and progress button

**Files:**
- Modify: `C:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/managecode/ManageCodeScreen.kt`
- Create: `C:/Scripts/KKCSheetTracker/app/src/test/java/com/kkc/sheettracker/ui/managecode/ManageCodeOperationUiStateTest.kt`

**Interfaces:**
- `ManageCodeOperationUiState.from(session, job)` returns `Idle`, `Preparing(progress)`, `Compiling`, `Syncing`, `Completed`, or `Failed` only for the requested job.

- [ ] **Step 1: Write failing job-scoping and stage-mapping tests**

```kotlin
@Test fun `compiling operation maps to indeterminate button state only for its job`() {
    val state = ManageCodeOperationUiState.from(session(job = "648", stage = "compiling"), "648")
    assertTrue(state is ManageCodeOperationUiState.Compiling)
    assertEquals(ManageCodeOperationUiState.Idle, ManageCodeOperationUiState.from(session(job = "648"), "649"))
}
```

- [ ] **Step 2: Run the test and verify it fails because the UI state mapper is absent**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.managecode.ManageCodeOperationUiStateTest`

Expected: FAIL with unresolved `ManageCodeOperationUiState`.

- [ ] **Step 3: Replace screen-owned generation coroutines with coordinator submission**

Build the existing duplicate-confirmed material changes into a `ManageCodeSession`, submit it to the injected application coordinator, and collect its job-specific state. Retain editing/selection state locally; do not call `loadMaterialState` until the coordinator reports the corresponding material completed.

- [ ] **Step 4: Render determinate and indeterminate progress on the action button**

```kotlin
Button(enabled = operationUiState == Idle, onClick = { coordinator.start(session) }) {
    if (operationUiState is Preparing) LinearProgressIndicator(progress = { operationUiState.fraction })
    if (operationUiState is Compiling) LinearProgressIndicator()
    Text(operationUiState.label)
}
```

The label includes `completedMaterials / totalMaterials` and stage text. Failed/interrupted state displays an explicit Retry button; navigation remains enabled throughout.

- [ ] **Step 5: Run UI and mix client tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.managecode.ManageCodeOperationUiStateTest --tests com.kkc.sheettracker.data.mixservice.MixServiceClientTest`

Expected: PASS.

### Task 5: End-to-end verification and deployment

**Files:**
- Modify: `C:/Scripts/KKCSheetTracker/app/build.gradle.kts` only to increment the release version code.
- Build: `C:/Scripts/PGM_BCR_Loader/dist/mix_service.exe`

- [ ] **Step 1: Run service suite and package the executable**

Run: `C:/Python38-win7/python.exe -m unittest discover -s tests -v`

Run: `C:/Python38-win7/Scripts/pyinstaller.exe --noconfirm mix_service.spec`

Expected: relevant operation tests pass; investigate and report any unrelated pre-existing failure separately.

- [ ] **Step 2: Build release APK and validate signing**

Run: `./gradlew.bat :app:assembleRelease`

Expected: `app/build/outputs/apk/release/app-release.apk` exists and Gradle reports `validateSigningRelease` successful.

- [ ] **Step 3: Deploy with the CNC service stopped, then install tablet release without uninstalling**

Copy the packaged EXE to `\\192.168.20.4\cnc\Scripts\PGM_MixService\mix_service.exe` only after the operator stops the tray service. Then run:

```powershell
adb -s R52T60FJ39V install -r app\build\outputs\apk\release\app-release.apk
```

- [ ] **Step 4: Live acceptance test**

Start a multi-program replacement from Manage Code, navigate away immediately, return to the same job, and verify the button reports the same operation. Confirm preparation count advances, compilation uses an indeterminate bar, a terminal success refreshes the material, and no second mix mutation was submitted. Restart the service during a separate test operation and verify the tablet displays `interrupted` with Retry instead of automatic resubmission.
