# Mixes Screen Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Manage Code with a Settings-style **Mixes** screen built on the files-only `mix-files` API. It refreshes live, edits any `.mix`/`.FRZ`, matches sheets strictly, and applies and reverts second-pass edits separately.

**Architecture:**
- A new `data/mixfiles` package: typed `mix-files` client, a single sheet-matching rule, a draft model that builds every write request, and one lease-based live store per (machine, job, material). The Mixes screen, Job Detail and the sheet viewer all observe that store.
- Pure presentation builders hold the screen logic and are unit-tested. The composables in `ui/mixes` stay thin.
- Second-pass edits keep the existing async `pgm-edits` operation, plus a revert path gated on a service feature flag.

**Tech Stack:** Kotlin/JVM 17, Android API 26+, Jetpack Compose (Material 3, compose-bom 2026.06.01), coroutines/StateFlow, OkHttp 4.12.0, Gson, `sh.calvin.reorderable:reorderable:3.1.0`, JUnit 4, kotlinx-coroutines-test 1.9.0, MockWebServer 4.12.0. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-30-mix-screen-redesign-design.md` (approved). Mockups: `docs/superpowers/specs/2026-10-30-mix-screen-mockups/` (landscape = A, portrait = option P2). The wire contract is `C:\Scripts\PGM_BCR_Loader\docs\PGM_MIX_SERVICE_AGENT_GUIDE.md`, sections "The `mix-files` API" and "Second-Pass PGM API". Where the guide and the spec differ on a wire detail, the guide wins.

## Global Constraints

- **Data sources.** The tablet talks only to `mix-files` for mixes. No legacy mix routes (`/mixes`, `mix-catalog`, `.../mix`, `pgm-conflicts`, `external-mixes`) are called after Task 10.
- **Revisions.** Every mix write sends the `revision` of the exact file being edited, taken from the catalog the draft was built on. A pair edit also sends `completion.companionExpectedRevision`, the other file's `revision`.
- **Locks.** Locked sheet states are `COMPLETE`, `HAS_BAD_PARTS` and `RE_NESTED`. `SKIPPED`, `IN_PROGRESS` and `NOT_STARTED` are not locked. A locked A/Z page puts **both** PGM names in `completedPgms`.
- **No silent drops.** An entry whose PGM matches no page is shown as unmatched. Any unmatched entry disables Apply for an interrupted mix (FRZ or pair). A plain `.mix` save is allowed.
- **Live refresh.** Poll `GET .../mix-files/revision` every **3,000 ms**, only while a Mixes, Job Detail or sheet-viewer screen holds a lease and the app is in the foreground. Fetch `GET .../mix-files` only when `folderRevision` changes (compare for inequality). There is at most one request in flight per material.
- **Drafts.** Live refresh never changes a draft. With a draft open, a changed `folderRevision` shows the conflict banner, and Apply stays blocked until the user picks Load theirs or Re-apply.
- **Interrupted mixes.** Rows edit only `quantity` and `remaining` (1 ≤ remaining ≤ quantity). Parameters are editable only on a plain `.mix`.
- **Existing entries** are always sent back as full entries (`path`, `parameters`, `fields`), never as `{"pgm"}`. New PGMs are sent only as `{"pgm": name}`, which must be the only key.
- **Second pass** has its own draft and its own **Apply 2nd pass (n)** button, separate from the mix Apply. Revert shows only when `GET /status` `features` contains `"pgm-edits-revert"`.
- **Machine.** Every mix-service call goes through a `CncMachine`. Today there is one, the SCM at `MIX_SERVICE_BASE_URL` (`http://192.168.20.4:8477`). No CNC 2 placeholder appears in the UI.
- **Layout.** At width ≥ **1000dp** use the rail (landscape A). Below that use the top strip (portrait P2). The tablet is 1318dp wide in landscape and 824dp in portrait.
- **Style.** Use Settings chrome (`GroupCard`, `GroupCaption`, `kkcCardDepth`, 12dp card radius, `secondaryContainer` selection) and `LocalKKCStatusColors`. Respect `LocalLowEndMode`. Follow the CLAUDE.md frosted-glass rules: no Surface + shadowElevation on translucent backgrounds.
- **Encoding.** Job, material and PGM names go in URLs as `addPathSegment` segments. PGM names and parameters are opaque strings: send parameters back unchanged unless the user edited them.
- **Device safety.** Never uninstall the app from a tablet. Keep `android.injected.androidTest.leaveApksInstalledAfterRun=true`.
- **Unit tests:** `.\gradlew.bat :app:testDebugUnitTest --tests "<pattern>"` from `C:\Scripts\KKCSheetTracker`.

## Review Focus

1. **A catalog update arriving mid-drag.** A poll result must not reset the list under the operator's finger. Defer it until the drop, then treat it like any draft conflict. Test: Task 6, `catalogUpdateDuringDragIsDeferred`.
2. **The same PGM repeated in one mix.** Rows, draft keys and reorder must use the entry index, not the PGM name. `completedPgms` lists the name once and locks every repeat. Tests: Task 2, `repeatedPgmKeepsEveryOccurrence`; Task 4, `reorderWithRepeatedPgmMovesOnlyThatEntry`.
3. **Entries pointing outside the folder or at a moved PGM** (`pgmExists:false`, as in jobs moved to `Old Jobs 2024`). They still match by `pgm` name and are written back as full entries. Re-sending them as `{"pgm"}` would fail with `missing_program`. Test: Task 4, `existingOutsideEntryIsSentAsFullEntry`.
4. **The connection dropping during Apply.** The write may already have happened. The tablet must not retry blindly. Refetch the catalog; if `folderRevision` changed, show "Couldn't confirm the save — check the mix" and let the draft go through the conflict banner. Test: Task 6, `offlineDuringApplyRefetchesInsteadOfRetrying`.
5. **Job and material names with spaces, `#`, `%`, em dash or a leading digit** (`530a - DC BIGLEY 2`, `3_4 Elegant Black Super Matte`). They are path-segment encoded and decoded from the route exactly. Test: Task 1, `encodesJobAndMaterialAsPathSegments`.

---

## File Structure

| Path (under `app/src/main/java/com/kkc/sheettracker/`) | Responsibility |
|---|---|
| `data/cnc/CncMachine.kt` (new) | `CncMachine`, `CncCapability`, `CncMachines.scm` |
| `data/mixfiles/MixFilesModels.kt` (new) | Catalog, file row, entry, error and request models |
| `data/mixfiles/MixFilesJson.kt` (new) | Tolerant parse of the catalog and error bodies, plus request serialization |
| `data/mixfiles/MixFilesClient.kt` (new) | HTTP for `mix-files`, `/status` features, and the second-pass revert submit |
| `data/mixfiles/MixGroups.kt` (new) | Groups files into mix tabs (pair, FRZ-only, error) with their revision lists |
| `data/mixfiles/SheetMatching.kt` (new) | The one entry→page rule, A/Z collapse, lock rules, `completedPgms` |
| `data/mixfiles/MixFilesStore.kt` (new) | Per-material live state, lease poller, offline cache |
| `data/mixfiles/MixDraft.kt` (new) | Mix draft operations, rebase, and request builders |
| `data/mixfiles/SecondPass.kt` (new) | Second-pass draft, planner (add vs revert-then-add), runner |
| `ui/mixes/MixesPresentation.kt` (new) | Pure screen model: rail/strip items, tabs, card, row groups, button states, banners, error copy |
| `ui/mixes/MixesScreen.kt` (new) | Route entry, state holder, layout switch |
| `ui/mixes/MixesMaterialNav.kt` (new) | Landscape rail and portrait strip |
| `ui/mixes/MixesSheetList.kt` (new) | Mix tabs, mix card, grouped sheet list, drag |
| `ui/mixes/MixesDialogs.kt` (new) | Mismatch dialog, conflict banner, entry edit sheet, split, rename/delete confirms |
| `ui/mixes/MixesRevisions.kt` (new) | Revisions drawer / bottom sheet, view and restore |
| `ui/mixes/SecondPassControls.kt` (new) | Row checkboxes, material dropdown, 2nd-pass history |
| `navigation/NavGraph.kt` | `manage_code` route → `MixesScreen`; viewer route `mixFile` replaces `mixPages` |
| `ui/detail/JobDetailScreen.kt` | Mix rows from the store and the matcher, plus a live lease |
| `ui/viewer/SheetViewerScreen.kt` | Observes the store for live mix order |
| `KKCApplication.kt` | Wires the client, store registry and second-pass runner |
| Deleted in Task 10 | `ui/managecode/*`, `data/mixservice/{ManageCodeOrchestrator,ManageCodeSelection,MixCatalogCache,MixCatalogProjection,MixOperationCoordinator,MixOperationSessionStore,SheetOrderResolver(mix parts)}.kt` and their tests |

Tests mirror these paths under `app/src/test/java/com/kkc/sheettracker/`.

---

### Task 1: Machine, mix-files models and client

**Files:**
- Create: `data/cnc/CncMachine.kt`, `data/mixfiles/MixFilesModels.kt`, `data/mixfiles/MixFilesJson.kt`, `data/mixfiles/MixFilesClient.kt`
- Test: `data/mixfiles/MixFilesClientTest.kt` (MockWebServer), `data/mixfiles/MixFilesJsonTest.kt`

**Interfaces (Produces):**

```kotlin
enum class CncCapability { MIX_FILES, FRZ_EDITING, SECOND_PASS, MOVE_MATERIAL }
data class CncMachine(val id: String, val displayName: String, val baseUrl: String, val capabilities: Set<CncCapability>)
object CncMachines { val scm: CncMachine /* id "scm", "SCM CNC", MIX_SERVICE_BASE_URL, {MIX_FILES, FRZ_EDITING, SECOND_PASS} */ ; val all: List<CncMachine> }

enum class MixFileKind { MIX, FRZ, REVISION }
data class MixFileEntry(val path: String, val pgm: String, val parameters: LinkedHashMap<String, String>,
    val fields: String?, val quantity: Int, val remaining: Int?, val pgmExists: Boolean, val headerDrift: Boolean)
data class MixFileRow(val file: String, val kind: MixFileKind, val revisionOf: String?, val revisionNumber: Int?,
    val revision: String, val companion: String?, val companionMismatch: Boolean, val error: String?,
    val modifiedAt: String?, val entries: List<MixFileEntry>)
data class MixFilesCatalog(val job: String, val material: String, val folderRevision: String, val files: List<MixFileRow>)
data class FrzMismatch(val pgm: String, val index: Int, val tabletLocked: Boolean, val frzRemaining: Int)
data class MixFilesError(val status: Int, val code: String, val message: String, val current: MixFileRow?,
    val mismatches: List<FrzMismatch>, val edit: String?, val pgm: String?, val reason: String?, val frzRestored: Boolean?)
sealed interface MixFilesResult<out T> {
    data class Ok<T>(val value: T) : MixFilesResult<T>
    data class Rejected(val error: MixFilesError) : MixFilesResult<Nothing>
    data object ServiceOutdated : MixFilesResult<Nothing>   // 404 whose error starts "unknown route"
    data object Offline : MixFilesResult<Nothing>           // IO error, timeout, 503 server_busy, unparsable body
}

sealed interface EntrySpec {
    data class NewPgm(val pgm: String) : EntrySpec
    data class Existing(val path: String, val parameters: Map<String, String>, val fields: String?) : EntrySpec
    data class Pending(val pgm: String, val quantity: Int?, val remaining: Int?) : EntrySpec   // FRZ pending item; matches a pending entry by name, else adds from the folder
}
data class Completion(val completedPgms: List<String>, val resolutions: Map<String, String>, val companionExpectedRevision: String?)
data class NewMix(val file: String, val entries: List<EntrySpec>)

interface MixFilesApi {
    suspend fun catalog(job: String, material: String): MixFilesResult<MixFilesCatalog>
    suspend fun folderRevision(job: String, material: String): MixFilesResult<String>
    suspend fun write(job: String, material: String, file: String, expectedRevision: String?, entries: List<EntrySpec>, completion: Completion?): MixFilesResult<MixFilesCatalog>
    suspend fun frz(job: String, material: String, file: String, expectedRevision: String, completion: Completion, pending: List<EntrySpec>): MixFilesResult<MixFilesCatalog>
    suspend fun rename(job: String, material: String, file: String, newFile: String, expectedRevision: String): MixFilesResult<MixFilesCatalog>
    suspend fun delete(job: String, material: String, file: String, expectedRevision: String): MixFilesResult<MixFilesCatalog>
    suspend fun deleteRevision(job: String, material: String, file: String, expectedRevision: String): MixFilesResult<MixFilesCatalog>
    suspend fun restore(job: String, material: String, revisionFile: String, targetFile: String, expectedTargetRevision: String?): MixFilesResult<MixFilesCatalog>
    suspend fun split(job: String, material: String, file: String, expectedRevision: String, entries: List<EntrySpec>, newMixes: List<NewMix>, completion: Completion?): MixFilesResult<MixFilesCatalog>
    suspend fun features(): Set<String>   // GET /status "features"; empty on any failure
}
class MixFilesClient(private val machine: CncMachine, private val http: OkHttpClient = DEFAULT_HTTP) : MixFilesApi
```

- [ ] **Step 1: Write the failing tests.**
  - `parsesCatalogRowsEntriesAndFrzRemaining`: use the guide's sample. Assert the companion, `remaining == 1`, `parameters["T"] == "16973824"`, and that `parameters` key order is kept.
  - `parsesUndecodableFileWithErrorAndNoEntries`
  - `toleratesMissingOptionalFields`: no `modifiedAt` or `revisionNumber`; unknown keys are ignored.
  - `serializesEntrySpecs`: `NewPgm` → exactly `{"pgm":"R1.pgm"}`. `Existing` → `path`, `parameters` and `fields` only. `Pending` leaves out null keys.
  - `encodesJobAndMaterialAsPathSegments`: request path `/jobs/530a%20-%20DC%20BIGLEY%202/materials/3_4%20Elegant%20Black%20Super%20Matte/mix-files`.
  - `revisionConflictCarriesCurrentRow`: 409 → `Rejected` with `code == "revision_conflict"`, `current.revision` set.
  - `frzMismatchCarriesMismatches`: 409 → `mismatches[0] == FrzMismatch("R870107N.pgm", 3, true, 1)`.
  - `unknownRouteIsServiceOutdated`: 404 `{"ok":false,"code":"not_found","error":"unknown route: /jobs/..."}` → `ServiceOutdated`. A 404 with any other error → `Rejected(not_found)`.
  - `serverBusyAndSocketErrorAreOffline`
  - `writeSendsNullExpectedRevisionForCreate`: the body has `"expectedRevision":null`.
  - `pairWriteSendsCompanionExpectedRevision`
  - `featuresReadsStatusAndIsEmptyOnFailure`
- [ ] **Step 2: Run** `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.mixfiles.MixFiles*"`. Expected: FAIL (unresolved references).
- [ ] **Step 3: Implement** the models, `MixFilesJson` (Gson `JsonParser`, the same tolerance style as `MixCatalogJson`) and `MixFilesClient`. Use `connectTimeout 5 s` and `readTimeout 10 s`, as `MixServiceClient` does. `null` JSON fields must be serialized (`serializeNulls`) for `expectedRevision` and `expectedTargetRevision` only.
- [ ] **Step 4: Run** the same tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mixfiles): typed mix-files client and models`.

### Task 2: Mix groups and the one sheet-matching rule

**Files:**
- Create: `data/mixfiles/MixGroups.kt`, `data/mixfiles/SheetMatching.kt`
- Test: `data/mixfiles/MixGroupsTest.kt`, `data/mixfiles/SheetMatchingTest.kt`

**Interfaces:**
- Consumes: Task 1 models, `PageMetadata`, `SheetStatus`, `inferSheetFiles` (`data/cnc/CncSheetIdentity.kt`).
- Produces:

```kotlin
data class MixGroup(val key: String, val title: String, val mix: MixFileRow?, val frz: MixFileRow?,
    val mixRevisions: List<MixFileRow>, val frzRevisions: List<MixFileRow>) {
    val isInterrupted: Boolean; val primary: MixFileRow; val errorFile: MixFileRow? }
fun groupMixFiles(catalog: MixFilesCatalog): List<MixGroup>

fun isLockedStatus(status: SheetStatus): Boolean   // COMPLETE, HAS_BAD_PARTS, RE_NESTED

data class SheetPage(val pageNumber: Int, val pgmFiles: List<String>, val hidden: Boolean, val continuation: Boolean, val thumbnailPath: String?)
fun sheetPages(pages: List<PageMetadata>): List<SheetPage>   // every page with sheet files; A before Z

sealed interface MixItem {
    val entryIndices: List<Int>
    data class Sheet(val page: SheetPage, override val entryIndices: List<Int>, val pgms: List<String>, val quantity: Int, val remaining: Int?, val zOnly: Boolean?, val aOnly: Boolean?) : MixItem
    data class Unmatched(override val entryIndices: List<Int>, val pgm: String) : MixItem
}
fun matchEntries(entries: List<MixFileEntry>, pages: List<SheetPage>): List<MixItem>
fun completedPgms(items: List<MixItem>, statusOf: (Int) -> SheetStatus): List<String>
fun viewerPageOrder(items: List<MixItem>): List<Int>   // Sheet pages in order, distinct
```

Rules:
- **Grouping.**
  - A `MIX` and a `FRZ` that name each other as `companion` form one group.
  - An FRZ with no companion is its own group, and so is a `.mix` with none.
  - A `REVISION` joins the group whose `.mix` or `.frz` file equals its `revisionOf`, ignoring case. A revision whose original is gone forms a group with no active file, titled from `revisionOf`.
  - Revision lists are ordered by `revisionNumber` descending, then legacy archives by file name descending.
  - The group `title` is the `.mix` stem if there is one, otherwise the FRZ stem.
  - Groups are ordered by title, ignoring case.
- **Matching.**
  - PGM names compare case-insensitively.
  - When several pages carry the PGM, take the first page that is neither continuation nor hidden, then one that is hidden but not a continuation, then a continuation.
  - Adjacent entries `i`, `i+1` that match the same page, with A then Z and the same `quantity` (and the same `remaining` for an FRZ), collapse into one `Sheet` with both indices.
  - Any other entry is its own item. A lone A or Z sets `aOnly` or `zOnly` for the "Sheet 9 A" / "Sheet 9 Z" label.

- [ ] **Step 1: Write the failing tests.**
  - `pairsMixAndFrzCompanionsIntoOneGroup`
  - `frzOnlyIsItsOwnInterruptedGroup`
  - `revisionsJoinGroupNewestFirstLegacyLast`
  - `errorFileFormsGroupWithErrorFile`
  - `matchesCaseInsensitivelyAndKeepsMixOrder`
  - `unmatchedEntryStaysInPosition`: entries `[A, X, B]`, with X on no page → `[Sheet, Unmatched(X), Sheet]`.
  - `adjacentAzSameQuantityCollapses`
  - `splitAzShowsTwoRowsLabelled`: Z first, A later → two `Sheet` items with `zOnly` and `aOnly`.
  - `hiddenPageMatchesWithHiddenFlag`
  - `continuationLosesToHeadPage`
  - `repeatedPgmKeepsEveryOccurrence`: `[A, B, A]` → three items. `completedPgms` with A locked → `["A.pgm"]` once.
  - `lockedAzPageListsBothPgms`
  - `isLockedStatusTable`: exactly COMPLETE, HAS_BAD_PARTS and RE_NESTED are true.
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.data.mixfiles.MixGroupsTest" --tests "com.kkc.sheettracker.data.mixfiles.SheetMatchingTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** both files. `sheetPages` reuses `inferSheetFiles` and the A-before-Z sort from `SheetPgmMatcher.orderedPgmFiles` (move that helper here as `internal`).
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mixfiles): group mix files and match entries to sheets strictly`.

### Task 3: Live store with lease poller and offline cache

**Files:**
- Create: `data/mixfiles/MixFilesStore.kt`
- Modify: `KKCApplication.kt` (add `val mixFilesStores: MixFilesStoreRegistry`)
- Test: `data/mixfiles/MixFilesStoreTest.kt` (`runTest` + `StandardTestDispatcher`, fake `MixFilesApi`)

**Interfaces:**
- Consumes: `MixFilesApi`, `MixFilesCatalog`.
- Produces:

```kotlin
data class MaterialKey(val machineId: String, val job: String, val material: String)
sealed interface CatalogState {
    data object Loading : CatalogState
    data class Live(val catalog: MixFilesCatalog) : CatalogState
    data class Offline(val cached: MixFilesCatalog?) : CatalogState
    data class Outdated(val cached: MixFilesCatalog?) : CatalogState
    data object NoFolder : CatalogState               // Rejected not_found on catalog
}
class MixFilesStore(key: MaterialKey, api: MixFilesApi, cache: MixFilesCatalogCache, scope: CoroutineScope, pollMs: Long = 3_000L) {
    val state: StateFlow<CatalogState>
    fun acquire(): AutoCloseable          // first lease starts polling; last close stops it
    suspend fun refreshNow()             // full catalog fetch regardless of revision
    fun publish(catalog: MixFilesCatalog) // after a successful write
}
class MixFilesCatalogCache(dir: File) { fun read(key: MaterialKey): MixFilesCatalog?; fun write(key: MaterialKey, catalog: MixFilesCatalog) }
class MixFilesStoreRegistry(api: (CncMachine) -> MixFilesApi, cache: MixFilesCatalogCache, scope: CoroutineScope) {
    fun store(machine: CncMachine, job: String, material: String): MixFilesStore }
```

Poll loop: on acquire, emit the cached value as `Offline(cached)` until the first response, then fetch the full catalog. Each tick calls `folderRevision`. If the value differs from the held catalog's `folderRevision`, fetch the catalog. `Offline` keeps the last catalog. The cache stores the raw catalog JSON in `filesDir/mix-files-cache/<sha1(key)>.json`, written atomically (temp file plus rename).

- [ ] **Step 1: Write the failing tests.**
  - `firstAcquireFetchesCatalogAndPublishesLive`
  - `unchangedRevisionDoesNotRefetchCatalog`: the fake counts `catalog()` calls. After 3 ticks the count is still 1.
  - `changedRevisionRefetches`
  - `lastLeaseClosedStopsPolling`
  - `offlineKeepsLastCatalog`
  - `cachedCatalogShownBeforeFirstResponse`
  - `outdatedServiceIsOutdatedState`
  - `publishReplacesStateWithoutFetch`
  - `oneRequestInFlight`: a slow fake call is not overlapped by the next tick.
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.data.mixfiles.MixFilesStoreTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** it, modelled on `MachineStatusStore.acquire()` (synchronized observer count, generation counter, `scope.launch` loop). Wire the registry in `KKCApplication` with `CncMachines.scm` and `filesDir`.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mixfiles): live per-material mix catalog store`.

### Task 4: Mix draft and request builders

**Files:**
- Create: `data/mixfiles/MixDraft.kt`
- Test: `data/mixfiles/MixDraftTest.kt`

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces:

```kotlin
data class DraftEntry(val id: Long, val source: MixFileEntry?, val newPgm: String?, val quantity: Int, val remaining: Int?, val parameters: LinkedHashMap<String, String>?)
data class MixDraft(val groupKey: String, val baseMixRevision: String?, val baseFrzRevision: String?,
    val entries: List<DraftEntry>, val newFile: String?, val splitOff: List<NewMixDraft>, val changeCount: Int)
data class NewMixDraft(val file: String, val entries: List<DraftEntry>)
object MixDrafts {
    fun start(group: MixGroup): MixDraft                       // entries from the FRZ when interrupted, else the .mix
    fun startNew(file: String, pages: List<SheetPage>, inOtherMixes: Set<String>): MixDraft
    fun move(d: MixDraft, fromId: Long, toIndex: Int): MixDraft
    fun add(d: MixDraft, pgm: String): MixDraft
    fun remove(d: MixDraft, id: Long): MixDraft
    fun setQuantity(d: MixDraft, id: Long, quantity: Int, remaining: Int?): MixDraft
    fun setParameters(d: MixDraft, id: Long, parameters: LinkedHashMap<String, String>): MixDraft  // plain .mix only; throws on FRZ
    fun splitOff(d: MixDraft, ids: Set<Long>, newFile: String): MixDraft
    fun fromRevision(group: MixGroup, revision: MixFileRow): MixDraft   // the restore-into-draft rule from spec §6
    fun rebase(d: MixDraft, fresh: MixGroup): RebaseResult              // "Re-apply my changes on top"
}
data class RebaseResult(val draft: MixDraft, val dropped: List<String>)  // PGMs that no longer exist in fresh
sealed interface MixWrite {
    data class Write(val file: String, val expectedRevision: String?, val entries: List<EntrySpec>, val completion: Completion?) : MixWrite
    data class Frz(val file: String, val expectedRevision: String, val completion: Completion, val pending: List<EntrySpec>) : MixWrite
    data class Split(val file: String, val expectedRevision: String, val entries: List<EntrySpec>, val newMixes: List<NewMix>, val completion: Completion?) : MixWrite
}
fun buildWrite(d: MixDraft, group: MixGroup?, completedPgms: List<String>, resolutions: Map<String, String>): MixWrite
```

Rules:
- **Which write.**
  - Plain `.mix` → `Write`.
  - FRZ-only → `Frz`.
  - A pair edits through `Frz` (`file` = FRZ, `companionExpectedRevision` = `.mix` revision), because only FRZ semantics apply.
  - A split of a pair → `Split` on the `.mix` with `companionExpectedRevision` = FRZ revision.
  - A new mix → `Write` with `expectedRevision = null`.
- **Entry shapes.**
  - `Existing` entries carry `source.path`, `source.parameters` (or the edited copy) and `source.fields`.
  - A new PGM is `NewPgm`.
  - FRZ `pending` items are `Pending(pgm = source.pgm, quantity, remaining)`, with locked entries left out. `quantity` and `remaining` are sent only when changed. The service matches repeats in order (guide, "Re-sent locked PGMs are dropped").
- **Lock and id rules.** Locked entries (from `completedPgms`) can't be moved, edited or split off; those calls return the draft unchanged. `id`s are unique per draft, so repeats are distinct.
- **`changeCount`** = the number of operations applied since `start`, so the Apply label shows a non-zero count exactly when the draft differs.

- [ ] **Step 1: Write the failing tests.**
  - `plainMixReorderBuildsWriteWithFullEntries`
  - `existingOutsideEntryIsSentAsFullEntry`: path `C:\...\Old Jobs 2024\...\R1.pgm`, `pgmExists=false` → `Existing`, never `NewPgm`.
  - `addedPgmIsNewPgmOnly`
  - `reorderWithRepeatedPgmMovesOnlyThatEntry`
  - `pairEditUsesFrzWithCompanionRevision`
  - `frzPendingOmitsLockedAndUnchangedCounts`
  - `setParametersOnFrzIsRejected`
  - `quantityEditOnFrzDefaultsRemainingToQuantity`: 3 → 2 with remaining unset → `remaining = 2`. `remaining > quantity` is clamped to quantity.
  - `splitOfPairUsesSplitOnMixWithCompanionRevision`
  - `lockedEntryCannotBeSplitOff`
  - `newMixDraftHasNullExpectedRevisionAndNaturalOrder`
  - `fromRevisionOfPairKeepsOnlyOrderOfPendingSheets`: a revision listing a now-locked PGM does not re-add it.
  - `rebaseKeepsUserOrderAndReportsDropped`
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.data.mixfiles.MixDraftTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** `MixDraft.kt`. **Rebase:** take the fresh group's entries, put the PGMs the user had reordered into the user's relative order, keep the user's adds and removes when still possible, and list PGMs that disappeared in `dropped`.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mixfiles): mix draft and write builders`.

### Task 5: Second-pass draft, planner, runner and revert client

**Files:**
- Create: `data/mixfiles/SecondPass.kt`
- Modify: `data/mixservice/MixServiceModels.kt` (add `PgmEditHistoryEvent`; `PgmEditFileHistory.history: List<PgmEditHistoryEvent>`), `data/mixfiles/MixFilesClient.kt` (add `submitPgmEditRevert`)
- Test: `data/mixfiles/SecondPassTest.kt`

**Interfaces:**
- Consumes: `MixServiceClient.submitPgmEdits`, `getOperation`, `listPgmEdits` (existing); `MixFilesApi.features()`.
- Produces:

```kotlin
data class SecondPassState(val secondPass: Boolean, val superPass: Boolean, val removePUnload: Boolean)
data class SecondPassDraft(val wanted: Map<String, SecondPassState>, val current: Map<String, SecondPassState>) { val changeCount: Int }
object SecondPassDrafts {
    fun from(history: PgmEditHistoryView?, pgms: List<String>): SecondPassDraft
    fun set(d: SecondPassDraft, pgm: String, state: SecondPassState): SecondPassDraft
    fun setAll(d: SecondPassDraft, pgms: List<String>, field: SecondPassField, on: Boolean): SecondPassDraft  // material dropdown; caller passes unlocked pgms
    fun tri(d: SecondPassDraft, pgms: List<String>, field: SecondPassField): TriState                        // ON, OFF, PARTIAL(count)
}
enum class SecondPassField { PUNLOAD, SECOND, SUPER }
sealed interface SecondPassStep {
    data class Edit(val rows: List<PgmEditRow>) : SecondPassStep
    data class Revert(val pgms: List<String>) : SecondPassStep
}
fun planSecondPass(d: SecondPassDraft): List<SecondPassStep>   // Revert (if any) first, then one Edit
class SecondPassRunner(service: MixOperationService, revert: suspend (job: String, material: String, requestId: String, pgms: List<String>) -> MixServiceOperation) {
    suspend fun apply(job: String, material: String, plan: List<SecondPassStep>, onProgress: (String) -> Unit): SecondPassOutcome
}
sealed interface SecondPassOutcome { data object Done : SecondPassOutcome; data class Failed(val code: String?, val message: String, val partial: Boolean) : SecondPassOutcome }
```

Rules:
- **Add only.** A PGM whose wanted state only adds to the current one goes in `Edit` (`none`→`standard`/`super`, `standard`→`super`, P-unload removed on top).
- **Anything taken off.** The PGM goes in `Revert` and, if the wanted state still has edits, in the following `Edit`.
- **Super implies second pass.** Same rule as `toggleSuperPass` today.
- **Runner.**
  - Submit, then poll `getOperation` every 1 s until the state is `completed`, `failed` or `interrupted`.
  - A `completed` operation is checked per file. Any file `status` other than `succeeded`/`skipped` gives `Failed(partial = true)`.
  - Map `edit_failed` with "disabled" in the message to the copy "Second-pass editing is switched off on the CNC".
  - The revert submit posts to `pgm-edits/revert` with `{requestId, files:[{name, to:"original"}]}`. It is only reachable when `features()` contains `"pgm-edits-revert"`.

- [ ] **Step 1: Write the failing tests.**
  - `draftReadsCurrentFromHistory`
  - `addOnlyPlansSingleEdit`
  - `takingOff2ndPlansRevertThenEditForRemainingPun`: current {2ND, PUN}, wanted {PUN} → `[Revert([p]), Edit([p none true])]`.
  - `takingOffEverythingPlansRevertOnly`
  - `superToStandardIsRevertThenStandard`
  - `setAllSkipsPgmsNotPassed`
  - `triStateCountsPartial`
  - `runnerPollsUntilTerminalAndChecksFileStatuses`
  - `disabledSecondPassMapsToCopy`
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.data.mixfiles.SecondPassTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** `SecondPass.kt` and `submitPgmEditRevert`. `requestId` is `"tablet-<deviceId>-<epochMs>"`, the same source as today's orchestrator request ids.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mixfiles): second-pass draft with revert planning`.

### Task 6: Mixes screen presentation model

**Files:**
- Create: `ui/mixes/MixesPresentation.kt`
- Test: `ui/mixes/MixesPresentationTest.kt`

**Interfaces:**
- Consumes: Tasks 2–5, `Material`, `SheetStatus`, `MachineStatus`.
- Produces:

```kotlin
data class MaterialNavItem(val material: String, val machineId: String, val chip: MaterialChip, val selected: Boolean)
sealed interface MaterialChip { data class Left(val n: Int, val interrupted: Boolean); data object Done; data object NoMix; data object NoPgms; data object Offline }
data class MixTab(val key: String, val title: String, val interrupted: Boolean, val error: Boolean, val companionMismatch: Boolean)
data class RowModel(val key: Long, val item: MixItem, val label: String, val locked: Boolean, val tabletStatus: SheetStatus?,
    val frzRemaining: Int?, val disagreement: Boolean, val chips: List<RowChip>, val draggable: Boolean)
enum class RowChip { UNMATCHED, PGM_MISSING, NEEDS_REFRESH, HIDDEN_PAGE, IN_OTHER_MIX }
data class SheetGroups(val remaining: List<RowModel>, val unmatched: List<RowModel>, val notInMix: List<SheetPage>, val completed: List<RowModel>)
data class MixCardModel(val progress: Float, val summary: String, val applyEnabled: Boolean, val applyLabel: String, val applyBlockedReason: String?)
sealed interface Banner { data object ChangedOnCnc : Banner; data object SaveUnconfirmed : Banner; data object ServiceOutdated : Banner; data object Offline : Banner; data class Message(val text: String) : Banner }
data class MixesUiModel(val nav: List<MaterialNavItem>, val tabs: List<MixTab>, val selectedTab: String?, val card: MixCardModel?, val groups: SheetGroups?,
    val secondPassApply: String?, val banner: Banner?, val readOnly: Boolean)
fun buildMixesUi(input: MixesUiInput): MixesUiModel
fun errorCopy(error: MixFilesError): String
class CatalogGate { fun onCatalog(c: MixFilesCatalog, dragging: Boolean, hasDraft: Boolean): GateDecision }   // APPLY, DEFER, CONFLICT
```

Copy fixed by the spec:
- `machine_busy_with_mix` → "The CNC is cutting this mix. Pause it first."
- `frz_edit_disabled` → "Adding / removing / count changes on interrupted mixes are switched off on the CNC" (name the edit from `edit`).
- `state_unreadable` → "Can't read the CNC's state right now. Try again in a moment."
- After an FRZ or pair save → "Reload this mix in PanelMac".
- Outdated → "The CNC mix service needs updating".
- Unmatched block → "N sheets in this mix don't match the job — fix them before saving".

- [ ] **Step 1: Write the failing tests.**
  - `navChipsLeftDoneNoMixNoPgms`: the interrupted chip uses `Left(interrupted = true)`.
  - `groupsSplitRemainingUnmatchedNotInMixCompleted`
  - `hasBadPartsIsLockedSkippedIsNot`
  - `disagreementFlaggedWhenTabletAndFrzDiffer`
  - `unmatchedBlocksApplyOnInterruptedOnly`
  - `applyLabelShowsChangeCount`: "Apply 3 changes". Hidden with no draft.
  - `secondPassApplyIsSeparate`: a second-pass change shows `secondPassApply = "Apply 2nd pass (2)"`, while the mix Apply stays disabled.
  - `catalogUpdateDuringDragIsDeferred`
  - `changedRevisionWithDraftShowsBannerAndBlocksApply`
  - `offlineDuringApplyRefetchesInsteadOfRetrying`: the input `applyResult = Offline` → banner `SaveUnconfirmed`, plus a `refreshNow` request flag.
  - `outdatedServiceIsReadOnly`
  - `errorCopyForEachCode`: table-driven over every code in the guide's error table.
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.ui.mixes.MixesPresentationTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** `MixesPresentation.kt`. Pure Kotlin with no Compose imports.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mixes): screen presentation model`.

### Task 7: Mixes screen shell, navigation, sheet list and Apply

**Files:**
- Create: `ui/mixes/MixesScreen.kt`, `ui/mixes/MixesMaterialNav.kt`, `ui/mixes/MixesSheetList.kt`, `ui/mixes/SecondPassControls.kt`
- Modify: `navigation/NavGraph.kt` (both `manage_code` composables, ~`:2005` and ~`:3593`, now build `MixesScreen` with the same `folderName` / `material` args)
- Test: `ui/mixes/MixesLayoutTest.kt` (pure: `useRail(widthDp)` and `stripColumns(widthDp)`)

**Interfaces:**
- Consumes: Tasks 3–6, `MachineStatusStore.status`, `ProgressStore.getSheetStatus` / `progressVersion`, `MixServiceClient.listPgms` / `listPgmEdits`, thumbnail loading from `ManageCodeRowView` (move it to `MixesSheetList.kt`).
- Produces: `@Composable fun MixesScreen(scanCoordinator: ScanCoordinator, progressStore: ProgressStore, jobFolderName: String, initialMaterial: String?, onBack: () -> Unit)`. `internal fun useRail(widthDp: Int): Boolean = widthDp >= 1000`.

Behaviour:
- **Leases.** On entry, hold a `MixFilesStore.acquire()` lease for every job material, and `machineStatusStore.acquire()`, released in `DisposableEffect`.
- **Layout.** Landscape: a 220dp rail inside a `GroupCard` with a machine `GroupCaption`. Portrait: a wrapping tile strip card. Then the material header, `MixTab` chips, the mix card and the grouped `LazyColumn` with `ReorderableItem` keyed by `RowModel.key`.
- **Drag.** Drag start sets `dragging = true` for the `CatalogGate`. Drop calls `MixDrafts.move`.
- **Mix Apply.** Calls `MixFilesApi` with `buildWrite(...)`.
  - `Ok`: `store.publish(catalog)`, clear the draft, and show "Reload this mix in PanelMac" for an interrupted group.
  - `Rejected`: show `errorCopy` or the dialogs from Task 8. `Offline`: `store.refreshNow()`.
- **Second pass.** **Apply 2nd pass** runs `SecondPassRunner` and then reloads `listPgmEdits`.
- Leaving with either draft asks via an `AlertDialog` ("Discard unsaved changes?").

- [ ] **Step 1: Write the failing test.** `useRailAtAndAbove1000dp`: `useRail(1318)` is true, `useRail(824)` false, `useRail(1000)` true.
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.ui.mixes.MixesLayoutTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** the screen and wire both NavGraph call sites.
- [ ] **Step 4: Run** the test, then `.\gradlew.bat :app:testDebugUnitTest` and `.\gradlew.bat assembleDebug`. Expected: PASS, BUILD SUCCESSFUL.
- [ ] **Step 5: Tablet check** (skill `debug-android-tablet`). Install the debug APK with `adb install -r` and open a job's Manage code pill.
  - Portrait shows the strip; rotating shows the rail.
  - Drag a row on a test material's plain `.mix`, then Apply. The catalog shows the new order and an `OLDn_` file appears.
- [ ] **Step 6: Commit** `feat(mixes): Mixes screen replaces Manage Code`.

### Task 8: Dialogs, editing sheets and revisions

**Files:**
- Create: `ui/mixes/MixesDialogs.kt`, `ui/mixes/MixesRevisions.kt`
- Modify: `ui/mixes/MixesScreen.kt`, `ui/mixes/SecondPassControls.kt`
- Test: `ui/mixes/RevisionSummaryTest.kt`

**Interfaces:**
- Consumes: Tasks 4–7.
- Produces: `fun revisionSummary(older: MixFileRow, newer: MixFileRow): String`, which returns one of "reordered", "N sheets added", "N sheets removed", "quantities changed", "no change", or "made at CNC" for the oldest (`newer == null` handled by caller). Composables:
  - `FrzMismatchDialog(mismatches, statusOf, onResolve: (Map<String,String>) -> Unit)` with per-PGM **Tablet** / **FRZ**
  - `ConflictBanner(onLoadTheirs, onReapply)`
  - `EntryEditSheet(entry, interrupted, onSave)`: Quantity + Left on FRZ; Quantity + every parameter on a plain `.mix`
  - `SplitNameDialog(defaultName)`, where the default comes from the existing "next free `<Material>Mix N`" helper in `ManageCodeOrchestrator.kt` (move it to `MixDraft.kt` as `nextMixName(material, taken)`)
  - `RenameDialog`, `DeleteConfirm`
  - `RevisionsPanel(group, onView, onRestore, onDeleteRevision)`: an end drawer on the rail layout, a `ModalBottomSheet` in portrait. Two tabs for a pair (.mix / .FRZ). `.FRZ` revisions are view-only.
  - `SecondPassHistorySheet(pgm, events, canRevert, onRevert)`

Wiring:
- `frz_completion_mismatch` → `FrzMismatchDialog`, which resends with `resolutions`. Choosing FRZ never writes tablet status.
- `revision_conflict` or `CatalogGate.CONFLICT` → `ConflictBanner`. Re-apply calls `MixDrafts.rebase` and lists `dropped` in a snackbar.
- **Restore.**
  - Normal case: `MixDrafts.fromRevision` (a draft, then Apply).
  - Only when the current file has `error`: `MixFilesApi.restore(revisionFile, targetFile, current.revision)`.
- Delete-revision is confirmed and permanent.
- Rename calls `MixFilesApi.rename`, and Delete calls `MixFilesApi.delete`, which retires the file to `OLDn_` and can be restored. Each uses the primary file's `revision` and publishes the returned catalog. For a pair, the service renames or deletes both files.
- A `companionMismatch` group shows a warning in the card with **Compare**, which opens the `.mix` and `.FRZ` entry lists side by side in the revisions panel's View mode.

- [ ] **Step 1: Write the failing tests.**
  - `summaryReordered`
  - `summaryAddedRemoved`
  - `summaryQuantitiesChanged`
  - `summaryNoChange`
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.ui.mixes.RevisionSummaryTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** the composables and wiring.
- [ ] **Step 4: Run** the full unit suite and `assembleDebug`. Expected: PASS, BUILD SUCCESSFUL.
- [ ] **Step 5: Tablet check** on a test copy of an interrupted pair, with the machine idle and the owner present:
  - Reorder pending sheets and Apply. Both files are rewritten.
  - Mark a pending sheet complete on the tablet. Apply shows the mismatch dialog.
  - Restore an older `.mix` revision through the draft.
- [ ] **Step 6: Commit** `feat(mixes): mismatch, conflict, edit, split and revisions UI`.

### Task 9: Job Detail and sheet viewer on the live store

**Files:**
- Modify: `ui/detail/JobDetailScreen.kt` (`catalogMaterialEntries`, `canOpenCatalogMaterialEntry`, catalog loading at ~`:296-376`), `navigation/NavGraph.kt` (`viewerRoute`, both viewer composables at ~`:1931` and ~`:3510`, `isCurrentViewerTarget` ~`:4334`), `ui/viewer/SheetViewerScreen.kt` (`selectedMixPages` → observed order), `data/mixservice/SheetOrderResolver.kt` (`ViewerMixSelection`)
- Test: `ui/detail/JobDetailMixRowsTest.kt`, `navigation/ViewerRouteMixFileTest.kt`

**Interfaces:**
- Consumes: Tasks 2–3.
- Produces:
  - `data class ViewerMixSelection(val name: String, val mixFile: String)`. The route query becomes `mixName=…&mixFile=…`, and `mixPages` is removed.
  - `internal fun jobDetailMixRows(material: Material, catalog: MixFilesCatalog?, statusOf: (Int) -> SheetStatus): List<JobDetailMixRow>`
  - `data class JobDetailMixRow(val title: String, val selection: ViewerMixSelection?, val interrupted: Boolean, val unmatched: Int, val error: Boolean, val needsRefresh: Boolean)`
  - `internal fun liveViewerOrder(material: Material, catalog: MixFilesCatalog?, mixFile: String): ViewerOrder`
  - `sealed interface ViewerOrder { data class Mix(val pages: List<Int>, val unmatched: List<String>); data class Natural(val reason: String?) }`. `Natural("mix was renamed or deleted")` is returned when `mixFile` is no longer in the catalog.

Behaviour:
- Job Detail holds a store lease per material while in the foreground, and its rows update from `state`.
- The viewer holds a lease for its material. On each new `ViewerOrder` it keeps the current page if that page is still in the order, otherwise it goes to the nearest following page. Unmatched PGMs appear in the navigator as non-tappable rows.
- `canOpenCatalogMaterialEntry` no longer treats an empty match as unavailable.

- [ ] **Step 1: Write the failing tests.**
  - `rowPerActiveGroupWithUnmatchedCount`
  - `errorFileRowFlagged`
  - `viewerRouteCarriesMixFileNotPages`: round-trip encode and decode of `mixFile="19MM PRE_FINISHEDMIX.FRZ"`.
  - `liveOrderFollowsCatalog`
  - `renamedMixFallsBackToNatural`
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.ui.detail.JobDetailMixRowsTest" --tests "com.kkc.sheettracker.navigation.ViewerRouteMixFileTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** it. Update every `viewerRoute(...)` caller (`rg "viewerRoute\(|ViewerMixSelection\("`) to the new selection type.
- [ ] **Step 4: Run** the full unit suite and `assembleDebug`. Expected: PASS, BUILD SUCCESSFUL.
- [ ] **Step 5: Tablet check.**
  - Open a mix in the viewer on tablet A. Reorder it on tablet B, or through the Mixes screen in split view. Within ~3 s the viewer's navigator order changes and the current sheet stays.
  - With the service stopped, the last order still shows.
- [ ] **Step 6: Commit** `feat(mixes): live mix order in job detail and viewer`.

### Task 10: Remove Manage Code and the legacy mix client; acceptance

**Files:**
- Delete:
  - `ui/managecode/` (all four files) and `app/src/test/java/com/kkc/sheettracker/ui/managecode/`
  - `data/mixservice/ManageCodeOrchestrator.kt`, `ManageCodeSelection.kt`, `MixCatalogCache.kt`, `MixCatalogProjection.kt`, `MixOperationCoordinator.kt`, `MixOperationSessionStore.kt`, `SheetPgmMatcher.kt` (its helper moved in Task 2), and their tests
  - the mix functions in `SheetOrderResolver.kt` (`reorderVisiblePages`, `pagesForMix`, `resolveMaterialMixEntries`, `resolveMixPageOrder`). Keep `statusCountsForPages`, `resolveViewerPageOrder` and `shouldShowPendingBadPartAction` if still called.
- Modify:
  - `MixServiceClient.kt`: remove `listMixes`, `getMix`, `getPgmConflicts`, `submitMix`, `submitCatalogMutation`, `createCatalogMix`, `replaceMix`, `deleteExternalMix`, `getMixCatalog` and their envelopes. Keep `isReachable`, `listPgms`, `listPgmEdits`, `submitPgmEdits`, `getOperation`, `listJobOperations` if used.
  - `MixOperationModels.kt`: keep only what `pgm-edits` uses (`MixServiceOperation`, `MixOperationClientException`, the trimmed `MixOperationService`)
  - `KKCApplication.kt`: drop `mixCatalogCache`, `mixCatalogRepository`, `mixOperationCoordinator` and `restore()`
  - `navigation/ArchiveJobDetailHost.kt`, if it references removed types

- [ ] **Step 1: Delete and trim**, then confirm nothing references removed code: `rg -n "ManageCode|MixCatalogRepository|MixCatalogSnapshot|MixOperationCoordinator|mix-catalog|external-mixes|/mixes|pgm-conflicts|pagesForMix|mixPages" app/src`. Expected: no hits, apart from the `manage_code` route string (kept, so the Job Detail pill and deep links still work).
- [ ] **Step 2: Run** `.\gradlew.bat :app:testDebugUnitTest` and `.\gradlew.bat assembleDebug`. Expected: 0 failures, BUILD SUCCESSFUL.
- [ ] **Step 3: Acceptance on a tablet against the live service** (owner present, machine idle). Check each spec success criterion:
  - Portrait operator flow on a real job: open the material, see Cut and Remaining, drag, Apply, reload in PanelMac, and the order matches on a second tablet within ~3 s.
  - Create a mix, then rename, split, delete and restore it.
  - An unmatched entry shows and blocks the FRZ Apply.
  - With `second_pass_enabled` false, Apply 2nd pass shows the disabled message.
  - With no `pgm-edits-revert` feature, applied edits show greyed out with "Revert needs a service update".
- [ ] **Step 4: Commit** `refactor(mixes): remove Manage Code and legacy mix client`.

---

## Not in this plan (service work this build depends on)

The service's `pgm-edits/revert` route, the `/status` `features` flag and the optional `modifiedAt` file field are service work in `C:\Scripts\PGM_BCR_Loader`. They are written up in the shared notes (`tablet-design-service-asks.md`). The tablet ships without them: revert stays greyed out, and revisions show their number in place of a time.
