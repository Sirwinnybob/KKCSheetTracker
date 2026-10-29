# CNC Live Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the CNC's loaded sheet and last finished sheet on tablets, with direct navigation to the loaded sheet and quiet recovery from service outages.

**Architecture:** Add a read-only HTTP client and one process-scoped, visibility-driven polling store. Derive an immutable program index and dashboard presentation inputs in `AppStateStore`, hydrate only relevant active jobs when needed, and match programs outside Compose. Dashboard, viewer header, and navigator consume the same matched status without changing tracker data.

**Tech Stack:** Existing Kotlin/JVM 17, Android API 26+, Jetpack Compose, StateFlow/coroutines, OkHttp 4.12.0, Gson 2.13.2, JUnit 4, MockWebServer, and Compose instrumentation tests. Add only `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")`, matching the runtime resolved by `:app:dependencyInsight` on 2026-10-29.

**Spec:** `docs/superpowers/specs/2026-10-29-cnc-live-status-design.md`. Read both documents; the confirmed decisions below supersede conflicting draft wording.

## Global Constraints

- **Never writes anything.** No tracker status changes, Ready Jobs metadata writes, or HTTP methods other than GET from this feature. No server changes or new service/deployment dependency.
- No noisy failure UI. CNC off / service down must never produce error banners or toasts. Log availability transitions only.
- No X/Y/Z position, percentage through a sheet, alarms, totals, history beyond the last finished program, or dedicated machine screen.
- Poll every **5 s**, only while the CNC dashboard or a sheet viewer is on screen and the app is foreground. One process-wide poller; at most one request in flight.
- Use connect timeout **2 s**, read timeout **3 s**, and total call timeout **5 s**. Suspend cancellation cancels the underlying HTTP call.
- Reuse one shared constant for `http://192.168.20.4:8477`. No settings screen or DataStore; the second shop uses the existing VPN.
- Parse tolerantly: unknown fields ignored; any field may be missing or null. `ok` must explicitly be true for a successful snapshot.
- Only active jobs are eligible. Exact folder match precedes job-number fallback; fallback requires exactly one active folder for that number. Preserve letter suffixes such as `530a`.
- Compare program stems case-insensitively, stripping only a trailing `.pgm`; use existing Z/A inference and continuation-head resolution.
- Loaded status wins over finished status for the same sheet. Every active sheet viewer, including mix-scoped instances, gets the same marking rules.
- Respect `LocalLowEndMode.current.animationsDisabled`; no pulse when disabled. Use existing theme colors and solid pill fills; preserve the project's glass/shadow rules.
- Never uninstall the app. Preserve `android.injected.androidTest.leaveApksInstalledAfterRun=true` for any connected tests.

### Confirmed decisions and precise planning defaults

- **No age limit for a loaded file**, cutting or idle. Do not filter by `loadedAt`, `asOf`, report date, midnight, or weekend. A valid `currentProgram` remains visible as long as successful responses keep reporting it.
- **Offline grace: 180,000 ms (three minutes)** since the last successful response, measured with a monotonic clock. This makes the user's “a few minutes” concrete and replaces the draft's 30 seconds and immediate failure clearing. A transient failure preserves the previous loaded/finished snapshot; at the deadline all live marks and extra dashboard content disappear silently. First launch without a success starts unavailable. Recovery applies the next valid response immediately.
- A successful response with `currentProgram:null` clears loaded status immediately; `lastProgram:null` clears finished status immediately. A valid `currentProgram` is independent of `haveReport`: `haveReport:false` suppresses report-derived finished status, but must not discard a loaded program or introduce an age limit. The server can report a loaded file without today's report.
- **Unknown/ambiguous programs show their filename without a link**, as the user explicitly confirmed while planning. No guessed job, thumbnail, or progress. Unavailable status hides quietly.
- Keep spec copy: dashboard `ON CNC` while cutting, `LOADED · IDLE` while idle; viewer `ON CNC · CUTTING`, `LOADED · IDLE`, or `CUT ON CNC h:mm`; navigator `ON CNC` or `CUT h:mm`. Idle uses grey/no pulse; cutting blue/pulse; finished green. “Disable the pill” means remove it after the offline grace, without an offline warning.
- The matched material appears first even if untouched or complete. Its counts remain real, its thumbnail and tap target use the loaded sheet, and insertion does not alter the original recent-material count or truncate existing recent items.

## Review Focus

1. A brief outage, then a background weekend: retain marks below 180 seconds, expire before presenting stale state on resume, and accept an old loaded file again after a fresh success. Task 2 tests this.
2. Index-only jobs and duplicate job numbers: an untouched/complete job can match, while an unloaded duplicate still blocks numeric fallback. Task 4 tests this.
3. Saved viewer position and reordered mixes: a CNC tap opens the exact loaded head page despite an older saved position; ordinary resume and mix scoping retain their existing behavior. Task 6 tests this.
4. Windows paths, lettered jobs, duplicate program metadata, and continuation pages: resolve only one eligible sheet, never silently select the first collision or an archived sheet. Task 3 tests this.
5. Removing/replacing metadata while a screen stays open: drop obsolete marks, counts, and thumbnails without retaining a previous match or modifying tracker state. Tasks 4–6 test this.

---

## Repository findings and file boundaries

This is one feature with a shared state contract; use one plan. Work is planning-only until reviewed. Default project route remains Light; an execution choice must also respect the route rules in `AGENTS.md`.

Current code has two details that the draft does not account for:

- `AppStateStore.startDerivation` hydrates only dashboard candidates plus jobs already in the scan snapshot, not every active job. Its existing `deriveTriggers` deduplicates only scan generation/progress version; `requestRecompute()` alone cannot reliably load a newly reported machine job.
- `resolveSheetViewerRequestedPage` prefers `localTouchPage` over `startPage`. A new explicit-page option is necessary for the CNC tap requirement. Both navigation graphs must carry it.

There is no `Job.isArchived` property. Active membership comes from the normal Ready Jobs engine. `ArchiveSession` constructs separate scan/app-state stores against its restored cache, so exclude that scope explicitly.

All paths below are relative to `C:/Scripts/KKCSheetTracker`. Define these focused files; do not broadly restructure the large screen/model files:

| File(s) | Responsibility |
|---|---|
| `data/mixservice/MixServiceEndpoint.kt` | Shared base URL constant |
| `data/cnc/MachineStateModels.kt`, `MachineStateClient.kt` | Tolerant transport/domain snapshot and GET boundary |
| `data/cnc/MachineStatusStore.kt` | Ref-counted polling, cancellation, monotonic expiry |
| `data/cnc/CncSheetIdentity.kt` | Existing sheet-file and head-page helpers moved out of UI |
| `data/cnc/CncProgramIndex.kt`, `CncSheetMatcher.kt` | Immutable lookup data and pure matching |
| `data/cnc/CncAppStateProjection.kt` | Targeted hydration selection and atomic index/dashboard inputs |
| `ui/cnc/CncStatusPresentation.kt`, `CncStatusPill.kt`, `RememberCncSheetMarks.kt` | Pure copy/presentation, shared pill, lifecycle subscription |
| Existing `AppStateStore.kt`, `KKCApplication.kt`, `ArchiveSession.kt` | Derivation publication, singleton ownership, archive exclusion |
| Existing dashboard/viewer screens and `navigation/NavGraph.kt` | Rendering and exact-page navigation |

Production prefixes in task file lists: `M = app/src/main/java/com/kkc/sheettracker/`; JVM tests: `T = app/src/test/java/com/kkc/sheettracker/`; device tests: `A = app/src/androidTest/java/com/kkc/sheettracker/`. Expand these prefixes literally; they are not new directories.

### Task 1: Read-only machine snapshot boundary

**Files:** Create `M/data/mixservice/MixServiceEndpoint.kt`, `M/data/cnc/MachineStateModels.kt`, `M/data/cnc/MachineStateClient.kt`, `T/data/cnc/MachineStateClientTest.kt`, and `app/src/test/resources/cnc/machine-state.json` (the spec sample). Modify `M/data/mixservice/MixServiceClient.kt:29` to use the shared default.

**Interfaces:**
- `const val MIX_SERVICE_BASE_URL: String = "http://192.168.20.4:8477"` in package `data.mixservice`.
- `MachineState(asOf: String? = null, reportDate: String? = null, haveReport: Boolean? = null, state: String? = null, producing: Boolean? = null, currentProgram: LoadedProgram? = null, lastProgram: FinishedProgram? = null)`.
- `LoadedProgram(name: String? = null, running: Boolean? = null, loadedAt: String? = null, path: String? = null, jobFolder: String? = null, ambiguous: Boolean = false, candidates: List<ProgramCandidate> = emptyList())`; `ProgramCandidate(path: String? = null, jobFolder: String? = null)`.
- `FinishedProgram(name: String? = null, path: String? = null, start: String? = null, end: String? = null)`.
- `fun interface MachineStateSource { suspend fun fetch(): Result<MachineState> }`.
- `MachineStateClient(baseUrl: String = MIX_SERVICE_BASE_URL, client: OkHttpClient = machineHttpClient)` implements `MachineStateSource`.

- [ ] **Step 1: Write `MachineStateClientTest` against MockWebServer.** Use the spec resource for `parsesRealLoadedProgram`; assert the contract below. Add named cases `nullAndMissingFieldsAreSafe`, `extraFieldsAreIgnored`, `parsesAmbiguousCandidates`, `finishedProgramNeedsNoJobFolder`, `invalidEnvelopesFail`, and `cancellationCancelsCall`. `invalidEnvelopesFail` covers HTTP 201/500, empty body, JSON null, malformed JSON, `ok:false`, and absent/null `ok`.

```kotlin
assertEquals("R82010BN", snapshot.currentProgram?.name)
assertEquals("709 - BRAD TAYLOR", snapshot.currentProgram?.jobFolder)
assertEquals("GET", request.method)
assertEquals("/machine/state", request.path)
assertNull(parseSuccessful("""{"ok":true,"currentProgram":null}""").currentProgram)
assertTrue(fetchBody("""{"ok":false}""").isFailure)
```

`parseSuccessful(body)` and `fetchBody(body)` are test-local helpers that enqueue a 200 response and invoke the injected client; `snapshot`/`request` come from the resource fetch and `takeRequest()`. For explicit-null candidates, normalize to an empty list; missing program names become absent programs, not literal `null` labels. Include a test that an unrelated malformed optional value is ignored safely instead of crashing a later matcher.

- [ ] **Step 2: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "*MachineStateClientTest" --console=plain`. Expected: compilation fails for the absent client/models.
- [ ] **Step 3: Implement the interfaces and shared constant.** Parse via Gson's JSON tree with checked primitive/object/array access and normalization into non-null collections; constructor defaults alone do not protect Gson fields from explicit JSON null. Require HTTP 200 and `ok:true`. Use an independently configured short-timeout OkHttp client so mix operations keep their existing timeouts. Bridge `enqueue` with cancellable suspension; rethrow `CancellationException`, close response bodies, and cancel the call on coroutine cancellation. No per-request logging.
- [ ] **Step 4: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "*MachineStateClientTest" --tests "*MixServiceClientTest" --console=plain`. Expected: all tests pass; existing mix URLs/behavior unchanged.
- [ ] **Step 5: Commit only this task's files:** `git commit -m "feat: add read-only CNC machine state client"` after an explicit `git add` of the files listed above. Do not stage unrelated working-tree changes.

### Task 2: Shared polling with a three-minute quiet grace

**Files:** Create `M/data/cnc/MachineStatusStore.kt`, `T/data/cnc/MachineStatusStoreTest.kt`; modify `M/KKCApplication.kt:12-35` and `app/build.gradle.kts` (test dependency only).

**Interfaces:**
- `sealed interface MachineStatus` with `data object Unavailable` and `Live(loaded: LoadedProgram?, lastFinished: FinishedProgram?, asOf: String?, reportDate: String?, producing: Boolean?, state: String?)`.
- `MachineStatusStore(source: MachineStateSource, scope: CoroutineScope, elapsedRealtimeMs: () -> Long, onAvailabilityChanged: (Boolean) -> Unit = {})` exposes `val status: StateFlow<MachineStatus>` and `fun acquire(): AutoCloseable` (each returned handle closes idempotently).
- Constants `MACHINE_POLL_INTERVAL_MS = 5_000L`, `MACHINE_OFFLINE_GRACE_MS = 180_000L`.
- `KKCApplication.machineStatusStore` owns one lazy instance, with `SupervisorJob() + Dispatchers.IO`, `SystemClock.elapsedRealtime`, and transition-only logging. No polling in `Application.onCreate()`.

- [ ] **Step 1: Add coroutines-test 1.9.0 and write `MachineStatusStoreTest` using `runTest`, fake `MachineStateSource`, test scope, and `{ testScheduler.currentTime }`.** Test names and required assertions:

```kotlin
// failureRetainsSnapshotUntilDeadline: success at monotonic time 0, subsequent requests fail
advanceTimeBy(179_999); runCurrent(); assertEquals(live, store.status.value)
advanceTimeBy(1); runCurrent(); assertEquals(MachineStatus.Unavailable, store.status.value)
// twoObserversSharePoller: immediate first request; no overlap; closing one keeps polling
assertEquals(1, maximumConcurrentRequests)
// noAgeLimit: successful response still reports Friday's loadedAt after a weekend
assertEquals("R82010BN", (store.status.value as MachineStatus.Live).loaded?.name)
```

Also test `startsUnavailable`, `pollsAtFiveSecondIntervals`, `successResetsDeadline`, `validNullClearsImmediately`, `noReportStillAllowsLoadedProgram`, `lastObserverCancelsInflightRequest`, `expiredStateIsHiddenBeforeResumeFetchCompletes`, `oldGenerationCannotPublishAfterRestart`, and `logsAvailabilityTransitionsOnly`. Hold a fake fetch open to prove deadline expiry is independent of fetch completion. Assert no requests without observers and no wall-clock/date dependency. Use `runCurrent()`/bounded `advanceTimeBy`, not `advanceUntilIdle()` on an infinite poller.

- [ ] **Step 2: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "*MachineStatusStoreTest" --console=plain`. Expected: absent-store compilation failure.
- [ ] **Step 3: Implement the store and application ownership.** Schedule serial requests on a five-second start cadence, skipping elapsed slots rather than overlapping/catching up. A separate deadline coroutine expires the last success at exactly 180,000 ms. Failures retain status during the grace; valid nulls replace it immediately. Suppress finished data on `haveReport:false`; do not suppress a valid loaded program. On final release cancel polling, expiry timer, and HTTP work; retain only in-memory snapshot/timestamp. On acquire expire cached state synchronously before starting a fetch. Guard publications by generation so cancelled work cannot overwrite a restarted store.
- [ ] **Step 4: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "*MachineStatusStoreTest" --tests "*MachineStateClientTest" --console=plain`. Expected: all pass with virtual time; no actual sleeps or network.
- [ ] **Step 5: Stage only the listed task files and commit:** `git commit -m "feat: poll CNC status with quiet offline grace"`.

### Task 3: Shared sheet identity, index, and conservative matching

**Files:** Create `M/data/cnc/CncSheetIdentity.kt`, `M/data/cnc/CncProgramIndex.kt`, `M/data/cnc/CncSheetMatcher.kt`, `T/data/cnc/CncSheetIdentityTest.kt`, `T/data/cnc/CncSheetMatcherTest.kt`. Modify `M/ui/viewer/SheetViewerScreen.kt:236-241,3464-3481` and its existing JVM test imports.

**Interfaces:**
- Move existing `internal fun inferSheetFiles(pageMeta: PageMetadata?): List<String>` and `internal fun Material.resolveHeadPage(page: Int): Int` into `data.cnc`; import/reuse them from the viewer. Preserve current behavior and existing tests.
- `CncSheetRef(jobFolderName: String, pdfFilename: String, page: Int)` uses canonical tablet names and one-based head page.
- `CncProgramIndex(foldersByNumber: Map<String, Set<String>>, programsByFolder: Map<String, Map<String, Set<CncSheetRef>>>, headPagesByMaterial: Map<JobMaterialKey, Map<Int, Int>>, thumbnailsBySheet: Map<CncSheetRef, String?>)`; folder/stem lookup keys use `Locale.ROOT` lowercase; number-map values are canonical folders. `CncProgramIndex.EMPTY` is empty.
- `buildCncProgramIndex(activeJobs: List<UnifiedJobInfo>, loadedJobs: List<Job>): CncProgramIndex`; membership/number uniqueness use the **entire active roster**, not just hydrated jobs.
- `sealed interface CncLoadedMark`: `Matched(sheet: CncSheetRef, name: String, running: Boolean)` / `Unmatched(name: String, running: Boolean)`; `CncFinishedMark(sheet: CncSheetRef, endLabel: String)`; `CncSheetMarks(loaded: CncLoadedMark? = null, lastFinished: CncFinishedMark? = null)`.
- `matchCncSheets(status: MachineStatus, index: CncProgramIndex): CncSheetMarks`; `formatCncEndTime(end: String?): String?` accepts valid `HH:mm:ss`/`HH:mm`, returns `h:mm` without timezone conversion, returns null on invalid time.
- `cncActiveFoldersByNumber(activeJobs: List<UnifiedJobInfo>): Map<String, Set<String>>` builds the normalized-number/canonical-folder roster; `resolveCncProgramFolders(jobFolder: String?, path: String?, foldersByNumber: Map<String, Set<String>>): Set<String>` is shared by matching and hydration. The matcher passes `index.foldersByNumber`; hydration passes `cncActiveFoldersByNumber(activeJobs)`. Return empty for multiple plausible job-folder path segments; never choose the first.

- [ ] **Step 1: Write identity and matcher tests using direct `Job`, `Material`, and `PageMetadata` constructors.** Baseline fixture: `Job("709 - BRAD TAYLOR", "709", "BRAD TAYLOR", listOf(Material("709 - 19mm Pre_Finished.pdf", "19mm Pre_Finished", 11, metadata = MaterialMetadata(pages = listOf(PageMetadata(pageNumber = 11, sheetId = "R82010BN", sheetFiles = listOf("R82010BN")))))))`; build a matching `UnifiedJobInfo` roster entry. Assert:

```kotlin
val expected = CncSheetRef("709 - BRAD TAYLOR", "709 - 19mm Pre_Finished.pdf", 11)
assertEquals(expected, (marks.loaded as CncLoadedMark.Matched).sheet)
assertEquals("7:04", formatCncEndTime("07:04:59"))
assertNull(formatCncEndTime("25:99:00"))
```

Use named cases `realBareStemMatchesPage11`, `caseAndPgmExtensionNormalize`, `exactFolderBeatsDuplicateNumber`, `uniqueNumberFallbackPreservesLetter`, `unloadedDuplicateBlocksFallback`, `windowsFinishedPathFindsFolder`, `windowsPathAllowsUniqueNumberFallback`, `noJobHintNeverGuessesByStem`, `ambiguousNeedsExactlyOneResolvingCandidate`, `zaPairAndContinuationShareHead`, `excludedHeadDoesNotMatch`, `duplicateStemAcrossMaterialsDoesNotGuess`, `malformedTimeOmitsFinishedMark`, and `unavailableClearsMarks`. Candidate tests cover zero, one, and two resolving candidates, null entries, and repeated identical candidates (deduplicate identical path/folder pairs first). Missing name produces no mark; a named unresolved program produces `Unmatched`. Index replacement and roster removal must invalidate the old match.

- [ ] **Step 2: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "*CncSheetIdentityTest" --tests "*CncSheetMatcherTest" --console=plain`. Expected: missing index/matcher compilation failure.
- [ ] **Step 3: Implement identity/index/matcher interfaces.** Resolve exact `jobFolder` case-insensitively, then unique leading job number (`^\d+[A-Za-z]?` with a folder-token boundary); for Windows paths split on either slash regardless of Android's path rules. Prefer an exact active-folder segment; otherwise apply number fallback only to an unambiguous job-shaped segment. Only index hydrated jobs still in the active roster. Resolve continuation metadata to an eligible visible head; never independently mark excluded/hidden pages or a nonexistent head. Use sets to detect conflicting targets rather than last-write-wins. Infer Z/A with the shared helper before lowercase normalization. If `ambiguous:true`, use candidates only; exactly one successfully resolving candidate is required. For running wording use `running`, then `producing`, then `state == "cutting"`, with false as the final default. Invalid finished times omit only the finished mark.
- [ ] **Step 4: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "*CncSheetIdentityTest" --tests "*CncSheetMatcherTest" --tests "*SheetViewerScreenTest" --console=plain`. Expected: all pass, including pre-existing Z/A and resume behavior tests.
- [ ] **Step 5: Stage only task files and commit:** `git commit -m "feat: match CNC programs to canonical tablet sheets"`.

### Task 4: Publish accurate CNC inputs from active app state

**Files:** Create `M/data/cnc/CncAppStateProjection.kt`, `T/data/cnc/CncAppStateProjectionTest.kt`, `T/data/AppStateCncProjectionTest.kt`. Modify `M/data/AppStateStore.kt:41-69,74-108,142-183,247-388,490-505`, `M/data/ArchiveSession.kt:146`, `T/data/DeriveTriggersTest.kt`, and `T/data/ArchiveSessionTest.kt`.

**Interfaces:**
- `CncAppStateProjection(programIndex: CncProgramIndex = CncProgramIndex.EMPTY, materialRows: Map<JobMaterialKey, DashboardRecentMaterialItem> = emptyMap())`; publish atomically so marks cannot combine a new index with old progress/thumbnail inputs.
- `CncProgramHints(programs: List<ProgramCandidate>)` with `EMPTY`; `cncProgramHints(status: MachineStatus): CncProgramHints` extracts/deduplicates/sorts current, ambiguous candidates, and finished path hints. Ignore asOf/running/loadedAt so routine polls do not trigger derivation.
- `cncHydrationFolders(hints: CncProgramHints, activeJobs: List<UnifiedJobInfo>): Set<String>` uses Task 3's folder resolver.
- Extend `AppStateStore` constructor with `val machineStatusEnabled: Boolean = true`; expose `val cncProjection: StateFlow<CncAppStateProjection>` and `fun setCncProgramHints(hints: CncProgramHints): Unit`. Set `machineStatusEnabled = false` in `ArchiveSession.create`.
- Extend `DeriveInput` with `cncProgramHints: CncProgramHints = EMPTY`; extend `deriveTriggers` with `cncProgramHints: Flow<CncProgramHints>` (default empty StateFlow). Include hints in the distinct trigger key alongside generation/progress.

- [ ] **Step 1: Write projection/derivation tests with a fake `UnifiedMetadataEngine`, existing ProgressStore test setup, and coroutines-test.** Required names: `untouchedLoadedMaterialHydratesWithoutRecentTouch`, `completeLoadedMaterialUsesRealCounts`, `duplicateActiveNumberNeverHydratesAGuess`, `onlyHintedJobsAreAdditionallyHydrated`, `sameHintsDoNotRederiveOnEveryPoll`, `hintChangeDerivesWithoutScanOrProgressChange`, `sourceRefreshReplacesIndexAndThumbnail`, `removedActiveJobDropsMarks`, `archiveScopeHasEmptyProjection`, and existing trigger regression tests. Assert:

```kotlin
assertEquals(setOf("709 - BRAD TAYLOR"), additionalHydratedFolders)
assertEquals(11, matchedRef.page)
assertEquals(0, dashboard.recentInProgressMaterials.size) // untouched fixture
assertEquals(realCounts, projection.materialRows.getValue(key).counts)
assertEquals(beforeProgressVersion, progressStore.progressVersion.value)
```

Also assert tracker event files remain byte-identical for fixtures, no synthetic local touch is created, complete counts are retained, an index-only duplicate prevents fallback, and repeated status timestamp/running changes do not hydrate or rebuild anything. Archive tests must prove both hints and projection stay empty, even if the archive folder name equals an active machine job.

- [ ] **Step 2: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "*CncAppStateProjectionTest" --tests "*AppStateCncProjectionTest" --tests "*DeriveTriggersTest" --tests "*ArchiveSessionTest" --console=plain`. Expected: new projection/trigger contract fails before implementation.
- [ ] **Step 3: Implement targeted hydration and immutable publication.** Union existing dashboard candidate folders with `cncHydrationFolders`, using `getCncSnapshot` off the UI thread. Retain the full active roster for uniqueness; remove stale snapshot jobs absent from that roster. Reuse `deriveMaterial` counts and local-touch reads to build presentation rows for **all hydrated materials**, while keeping existing recent eligibility/sort/limit unchanged. Do not run `pruneLocalStateForJob` merely because a CNC hint additionally hydrated a job; preserve existing pruning for the original derivation jobs. Build the index after hydration and publish projection in `emitDistinctSnapshots`; thumbnails are indexed per head sheet. Cache structural index work by scan generation plus hint identity; refresh progress rows on progress changes. Reuse the engine cache, never hydrate the whole roster per poll. Loading failure yields an unmatched name; retry on the next scan generation or changed hint. Replace the old projection atomically after derivation.
- [ ] **Step 4: Run the Step 2 command again.** Expected: all pass, including existing one-derive-per-generation/progress guarantees; no progress writes or archive leakage.
- [ ] **Step 5: Stage only task files and commit:** `git commit -m "feat: derive CNC program lookup and loaded material inputs"`.

### Task 5: Lifecycle subscription and CNC dashboard presentation

**Files:** Create `M/ui/cnc/RememberCncSheetMarks.kt`, `M/ui/cnc/CncStatusPresentation.kt`, `M/ui/cnc/CncStatusPill.kt`, `T/ui/cnc/CncStatusPresentationTest.kt`, `A/ui/cnc/CncStatusLifecycleTest.kt`, `A/ui/dashboard/CncLiveDashboardTest.kt`. Modify `M/ui/dashboard/UnifiedModeDashboardScreen.kt:106-119,181-224,352-420,630-773` and CNC section call sites in that file.

**Interfaces:**
- `@Composable internal fun rememberCncSheetMarks(appStateStore: AppStateStore, store: MachineStatusStore = applicationMachineStatusStore()): CncSheetMarks`; the composable `applicationMachineStatusStore(): MachineStatusStore` reads `KKCApplication` through `LocalContext`. Acquire inside `repeatOnLifecycle(Lifecycle.State.RESUMED)`, forward normalized hints, and combine status with `cncProjection`. Tests inject their own store. Disabled archive scopes return empty marks without acquiring. Release in `finally` on navigation/background/disposal. No per-recomposition acquisition or permanent subscriber that starts polling.
- `enum class CncPillKind { CUTTING, IDLE, FINISHED }`; `CncPill(kind: CncPillKind, headerText: String, navigatorText: String, dashboardText: String)`.
- `cncPillForSheet(marks: CncSheetMarks, sheet: CncSheetRef): CncPill?`; `enum class CncPillPlacement { DASHBOARD, HEADER, NAVIGATOR }`; `@Composable internal fun CncStatusPill(pill: CncPill, placement: CncPillPlacement = CncPillPlacement.HEADER, modifier: Modifier = Modifier)` selects dashboardText/headerText/navigatorText respectively, with compact sizing for NAVIGATOR.
- `CncDashboardRow.Material(item: DashboardRecentMaterialItem, openPage: Int, pill: CncPill?)` / `.Unknown(name: String, running: Boolean)`; `CncDashboardProjection(rows: List<CncDashboardRow>, recentCount: Int)`; `projectCncDashboard(recent: List<DashboardRecentMaterialItem>, projection: CncAppStateProjection, marks: CncSheetMarks): CncDashboardProjection`.
- Extend `UnifiedModeDashboardSpec.Cnc`, `CncDashboardContent`, and `CncRecentMaterialsSection` with `onOpenLoadedSheet: (String, String, Int) -> Unit`, defaulting temporarily to `onOpenSheet`; Task 6 wires exact navigation. Regular rows continue to use `onOpenSheet`.

- [ ] **Step 1: Write pure presentation tests plus focused Compose tests.** Test `loadedMaterialMovesFirstWithoutDuplication`, `insertedMaterialKeepsRecentCountAndAllRecentRows`, `loadedThumbnailAndTapUseLoadedPage`, `unknownHasNoClickActionOrProgress`, `emptyRecentListStillShowsLoadedOrUnknown`, `missingProjectionNeverFabricatesProgress`, `clearingMarksRestoresOriginalList`, and `loadedPillWinsAfterReload`. For a four-item recent fixture plus a new loaded material assert:

```kotlin
assertEquals(4, result.recentCount)
assertEquals(5, result.rows.size)
assertEquals(11, (result.rows.first() as CncDashboardRow.Material).openPage)
assertEquals("ON CNC · CUTTING", cncPillForSheet(cuttingMarks, loadedRef)?.headerText)
assertEquals("LOADED · IDLE", cncPillForSheet(idleMarks, loadedRef)?.headerText)
```

Compose tests render real extracted section/pill composables with supplied state and callbacks: assert unknown rows have no click action; a loaded row emits `(709 - BRAD TAYLOR, 709 - 19mm Pre_Finished.pdf, 11)`; loaded thumbnail input changes from the old next-incomplete page; removal restores ordinary recents. Lifecycle test uses a fake store/source with two consumers to prove one shared request sequence, no requests when both owners stop, and release on disposal. With animations disabled, advance the Compose clock and assert the dot remains steady; do not rely on wall-clock screenshots. Make section/card internal only as necessary for these tests.

- [ ] **Step 2: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "*CncStatusPresentationTest" --console=plain`, then `.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain`. Expected: absent presentation symbols fail before implementation.
- [ ] **Step 3: Implement lifecycle helper, projection, and dashboard rendering.** Acquire only for resumed eligible screens, combine immutable inputs, and use the section's original count for its subtitle. Loaded copies of presentation items set `nextIncompletePage` to the loaded head solely for the existing thumbnail loader and replace `thumbnailPath` from `thumbnailsBySheet`; do not persist or alter canonical recent items. Keep unpinned ordering and ordinary tap behavior. A matched reference missing row inputs temporarily renders as the plain filename row rather than a guessed material. Unknown row copy is `Running` / `Loaded · idle`, no bounce/click modifier. Show live content even when original recents are empty; retain existing initial loading behavior for ordinary rows. Pill pulse is created only for cutting with animations enabled; no transparent-fill shadows.
- [ ] **Step 4: Run the JVM command from Step 2 and** `.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`. Expected: tests pass and both APKs compile. When a development tablet is connected, run `.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.kkc.sheettracker.ui.cnc.CncStatusLifecycleTest,com.kkc.sheettracker.ui.dashboard.CncLiveDashboardTest --console=plain`; expected all focused device tests pass with both APKs retained. Record unavailable device verification for final handoff; do not substitute fake JVM assertions for rendered semantics.
- [ ] **Step 5: Stage only task files and commit:** `git commit -m "feat: show live CNC material on dashboard"`.

### Task 6: Viewer/navigator marks and exact loaded-sheet navigation

**Files:** Modify `M/ui/viewer/SheetViewerScreen.kt:244-253,315-355,1475-1510,2057-2077,2093-2240`, `M/navigation/NavGraph.kt` (both dashboard branches, both viewer route declarations/call sites, `viewerRoute` at ~4191, and the dashboard host callback boundary), `T/ui/viewer/SheetViewerScreenTest.kt`, `T/navigation/ViewerMixRouteTest.kt`. Create `T/navigation/CncLoadedSheetRouteTest.kt` and `A/ui/viewer/CncLiveViewerTest.kt`. Extend `T/ui/cnc/CncStatusPresentationTest.kt` for viewer/navigator cases.

**Interfaces:**
- Extend `viewerRoute(jobFolderName: String, pdfFilename: String, page: Int, mixSelection: ViewerMixSelection? = null, preferStartPage: Boolean = false): String` with optional `preferStartPage=true` query parameter. Preserve byte-for-byte existing route strings when false, including mix encoding.
- Both route patterns accept `&preferStartPage={preferStartPage}` with `NavType.BoolType`, default false; plumb into `SheetViewerScreen(..., preferStartPage: Boolean = false)` and `resolveSheetViewerRequestedPage(..., preferStartPage: Boolean = false): Int`.
- On explicit opens choose `material.resolveHeadPage(startPage)` ahead of the saved local touch. Retain existing visible-page/mix validation; ordinary opens keep existing resume semantics. Dashboard loaded callback navigates unscoped (`mixSelection = null`) with `preferStartPage = true` in both graphs. Thread the callback through the dashboard host into every `UnifiedModeDashboardSpec.Cnc` construction, including flexible mode. Never silently reuse a selected mix that excludes the loaded sheet.
- Header and navigator use Task 5's `cncPillForSheet` after comparing canonical folder/PDF and `Material.resolveHeadPage(displayedPage)`. Pass a computed `Map<Int, CncPill>` to `SheetNavigatorSheet` as `cncPillsByPage`, default empty; it must not acquire its own polling handle.

- [ ] **Step 1: Write route, resume, and viewer presentation regression tests.** Add `explicitCncOpenBeatsSavedPosition`, `explicitContinuationOpenUsesHead`, `ordinaryResumeUnchanged`, `loadedRouteEncodesNamesAndExplicitFlag`, `mixRouteDefaultsRemainUnchanged`, `headerAndNavigatorAgree`, `mixReorderingUsesPhysicalHeadPage`, and `reloadedFinishedSheetShowsLoadedOnly`. The loaded-page fixture has saved page 6, start page 11, and visible pages 1–11:

```kotlin
assertEquals(11, resolveSheetViewerRequestedPage(material, (1..11).toList(), 6, 11, false, true))
assertEquals(6, resolveSheetViewerRequestedPage(material, (1..11).toList(), 6, 11, false))
assertTrue(viewerRoute("709 - BRAD TAYLOR", "709 - 19mm Pre_Finished.pdf", 11,
    preferStartPage = true).endsWith("?preferStartPage=true"))
assertEquals("CUT ON CNC 7:04", cncPillForSheet(finishedMarks, loadedRef)?.headerText)
assertEquals("CUT 7:04", cncPillForSheet(finishedMarks, loadedRef)?.navigatorText)
```

Device tests render the real extracted header/navigator presentation inside a minimal screen harness: assert cutting/idle/finished copy, loaded outline/tag, continuation-head agreement, mark changes while navigator is open, a mix order `[11, 3, 6]` marking physical page 11, different job/material hiding all marks, and unavailable removing marks without error UI. Assert tracker version/files unchanged through these transitions. Include explicit navigation wiring assertions for both graph branches in `CncLoadedSheetRouteTest`; do not rely solely on a helper route test to prove call-site wiring.

- [ ] **Step 2: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "*CncLoadedSheetRouteTest" --tests "*ViewerMixRouteTest" --tests "*SheetViewerScreenTest" --tests "*CncStatusPresentationTest" --console=plain`. Expected: new explicit-page contract tests fail before implementation.
- [ ] **Step 3: Implement the explicit-page route contract and both graph callbacks.** Preserve ordinary route defaults, avoid a second question mark with mix parameters, and respect launch-single-top behavior without suppressing the new explicit navigation request. Apply explicit selection only during initial material/page resolution, so subsequent user navigation or metadata refresh does not snap back to page 11. Do not change tracking/local-touch persistence to simulate opening a page.
- [ ] **Step 4: Implement viewer header and navigator rendering.** Call `rememberCncSheetMarks(appStateStore)` once per viewer; use the current projection head map/material helper rather than mix position or navigator display ordinal. Add the pill next to sheet IDs/dimensions with layout that remains readable on narrow tablets, and allow the header row to appear when it has a pill even if ordinary labels are blank. Give loaded navigator tiles an outline plus compact tag; finished tiles get `CUT h:mm`. Preserve existing tracker-derived tile colors/status. When loaded and last-finished targets match, render only the loaded pill/tag.
- [ ] **Step 5: Run** `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`. Expected: all existing and new JVM tests pass and both APKs build. Run focused connected tests from Task 5 plus `com.kkc.sheettracker.ui.viewer.CncLiveViewerTest` when a development tablet is available; expected all pass with the installed app retained. If an unrelated pre-existing failure occurs, report its exact test/output and prove it exists on the unchanged baseline; never weaken assertions.
- [ ] **Step 6: Verify on a development tablet using only live GETs.** Known real fixture: `709 - BRAD TAYLOR`, `R82010BN`, `709 - 19mm Pre_Finished.pdf`, page 11. Confirm matched pin, actual loaded thumbnail, tap to page 11 despite saved page 6, viewer/navigator agreement, and mix-scoped display. Observe a safely available unknown file and idle/cutting transitions; do not operate the CNC or load machine programs to manufacture test conditions. Test outage behavior through a test-configured client/MockWebServer or tablet network isolation: below three minutes keep marks, at three minutes clear quietly, reconnect restores. Do not stop the shop service. Check light/dark themes, low-end no-pulse, app background, CNC/non-CNC dashboard switching, completion reload priority, and absence of tracker writes. Record any live conditions not available for verification rather than claiming them tested.
- [ ] **Step 7: Stage only task files and commit:** `git commit -m "feat: mark CNC sheets and open exact loaded page"`.

## Completion and review checklist

- [ ] Reconcile every requirement with Tasks 1–6, including the confirmed no-age-limit, three-minute grace, and unknown filename decisions.
- [ ] Review availability, projection, and matching boundaries together; no per-screen HTTP clients or whole-job scans on every poll.
- [ ] Confirm both navigation implementations use exact loaded-page opens, while ordinary resume and existing mix route tests still pass.
- [ ] Confirm archive app states cannot participate, stale generations cannot publish, and metadata replacement removes obsolete marks.
- [ ] Inspect the final diff for accidental tracker/metadata writes, service mutations, settings, unrelated files, and changed instrumentation cleanup settings.
- [ ] Report actual JVM/build/device/manual results and any unperformed hardware scenarios. Update only feature documentation with verified outcomes; do not edit `agent_docs/project_progress.md` or `agent_docs/latest_session_work.md` under the default Light route.

Plan self-review completed against the draft spec and the user's corrections: all behavior sections have owning tasks; Review Focus cases are assigned tests; interface names/types are consistent across tasks. No implementation or live-device changes were performed while writing this plan.
