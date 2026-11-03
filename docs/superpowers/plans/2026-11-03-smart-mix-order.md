# Smart Mix Order Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** New mixes open pre-sorted so sheets with small parts near the edge cut first. A Smart/Default toggle switches the order, and every applied new mix is logged so all tablets learn the operator's ordering.

**Architecture:** A new pure-Kotlin package `com.kkc.sheettracker.data.mixorder` holds the placement geometry, sheet features, scoring, the learning record store, the pairwise trainer, and the consolidator. `MixesSession`/`MixesScreen` seed new drafts from it and write a record after a successful Apply. A PowerShell script on the dev PC mirrors the consolidator.

**Tech Stack:** Kotlin, Jetpack Compose, Gson (already used: `MixFilesJson.kt`, `CpuSpikeLogStore.kt`), JUnit 4 + kotlinx-coroutines-test, PowerShell (dev-PC script only).

**Spec:** `docs/superpowers/specs/2026-11-03-smart-mix-order-design.md`

**Workspace:** `main` has unrelated uncommitted work (CNC two-machine status, mix files). Execute in a fresh worktree from `main` HEAD (superpowers:using-git-worktrees). Commit only when the owner has asked for commits in this execution.

**Test command (all tasks):** `.\gradlew.bat :app:testDebugUnitTest --tests "<class or package pattern>"` from `C:\Scripts\KKCSheetTracker`. One known off-device failure exists in a PdfMarkup MotionEvent test; it is not a regression.

## Global Constraints

- Small part: `width < 6.0 || length < 6.0` (inches).
- Edge band: `3.0` inches from any of the four sheet edges. A constant, never learned.
- Smart appears **only on New mix**, never on existing mixes.
- **No fallback**: if any sheet in the new draft fails qualification, there's no toggle, Default order, and no record.
- Locked/completed sheets never move. A double-sided sheet's PGMs stay together in `orderedPgmFiles` order (A before Z).
- Learned weights are used only with at least `5` trainable records. Otherwise the prior is used.
- Shared folder: `<Ready Jobs>\.metadata\mix_order_learning\`. Bundles go in `bundles\` and unreadable files in `bad\`. Local pending folder: `filesDir/mix_order_learning/pending`.
- Single record file name: `<yyyy-MM-dd'T'HH-mm-ss-SSS>_<tabletId>_mixorder.json`. Bundle file name: `<timestamp>_<source>_bundle.json`, where `source` is the tablet ID or `pc`.
- Record and bundle JSON `version: 1`. Readers dedupe by `recordId`.
- Logging must never fail or delay Apply: it runs off the main thread, and errors are swallowed.
- Scoring and training run off the main thread. Training takes < 100 ms for 500 records.
- No Python anywhere. The PC script is ASCII-only PowerShell and writes UTF-8 without BOM.

## Placement convention (verified 2026-11-03 against job 720 `partBoxes`)

- `sheetDimensions = [48.0, 96.0]` means `[height(y), length(x)]`: x runs 0 to `sheetDimensions[1]`, y runs 0 to `sheetDimensions[0]`.
- `sheetPlacement (x, y)` is the part's local origin. The local rectangle is `width` along local x and `length` along local y, rotated counter-clockwise by `angle` degrees about that origin.
  - Angle 0 gives a footprint of x ∈ [x, x+width], y ∈ [y, y+length].
  - Angle 90 gives x ∈ [x−length, x], y ∈ [y, y+width].
  - Checks: 19mm Un_Finished page 2 part 1 has w=14.768, l=17.02, x=92.9152, y=0.4036, angle 90, giving footprint x 75.895..92.915, y 0.4036..15.1716. Page 3 parts 1–2 (angle 0, w=4) step 4.395 in x.
- Footprint = axis-aligned bounding box of the 4 rotated corners (handles any angle).

## Review Focus

1. A sheet whose PGMs are already in another mix is excluded from the draft. It must not disqualify the draft or appear in the record. The test goes in Task 2.
2. Double-sided A/Z PGMs stay adjacent and in A-then-Z order after any smart sort or toggle. The test goes in Task 3.
3. Toggling Smart → Default → Smart with locked sheets: locked rows keep their slots, and the result equals a fresh seed of that mode. The test goes in Task 3.
4. Share offline at Apply: the record goes to pending, flushes on the next record or screen open, and Apply returns at its normal speed. The tests go in Tasks 4 and 7.
5. A half-written file, a Syncthing `*.sync-conflict-*` copy, or a non-JSON file in the folder never breaks training or the Mixes screen. The reader skips it, duplicates dedupe by `recordId`, and the consolidator moves unreadable files to `bad\`. The tests go in Tasks 4 and 6.

---

### Task 1: Part placement model and footprint

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/models/Models.kt` (`Part`)
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixorder/PartFootprint.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixorder/PartFootprintTest.kt`

**Interfaces:**
- Produces: `data class SheetPlacement(val x: Double = 0.0, val y: Double = 0.0, val angle: Double = 0.0)`, and `Part.sheetPlacement: SheetPlacement? = null` (Gson field name `sheetPlacement`, which matches the JSON).
- Produces: `data class Footprint(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)`, plus `fun Part.footprint(): Footprint?` (null when `sheetPlacement` is null) and `fun Part.isSmall(): Boolean` (`width < SMALL_PART_IN || length < SMALL_PART_IN`).
- Produces: `fun Footprint.onEdge(sheetLengthX: Double, sheetHeightY: Double, band: Double = EDGE_BAND_IN): Boolean` (any side within `band` of 0 or of the sheet's max).
- Produces: constants `const val SMALL_PART_IN = 6.0`, `const val EDGE_BAND_IN = 3.0` in `PartFootprint.kt`.

- [ ] **Step 1: Write failing tests** in `PartFootprintTest`:
  - `angle90_matchesJob720Page2Part1`: `Part(width=14.768, length=17.02, sheetPlacement=SheetPlacement(92.9152, 0.4036, 90.0)).footprint()` equals `(75.8952, 0.4036, 92.9152, 15.1716)` within 1e-3.
  - `angle0_widthAlongX`: `Part(width=4.0, length=17.02, sheetPlacement=SheetPlacement(82.4956, 0.4036, 0.0)).footprint()` equals `(82.4956, 0.4036, 86.4956, 17.4236)` within 1e-3.
  - `missingPlacement_null`.
  - `isSmall`: (5.9, 30) true; (30, 5.9) true; (6.0, 6.0) false.
  - `onEdge`: footprint (10, 2.9, 15, 8) on a 96×48 sheet is true; (10, 3.1, 15, 8) is false; (90, 20, 93.1, 25) is false; (90, 20, 93.0, 25) is true (≤ band counts).
  - `gsonParsesSheetPlacement`: Gson parses `{"number":1,"width":4,"length":17,"sheetPlacement":{"x":1.5,"y":2.5,"angle":90.0}}` into a `Part` with that placement.
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.data.mixorder.PartFootprintTest"`. Expected: compile failure / FAIL.
- [ ] **Step 3: Implement** per the Placement convention above (rotate the 4 corners of `[0,width]×[0,length]` by `angle` around the origin, then take the bounds).
- [ ] **Step 4: Run** the same tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mix-order): parse part sheet placement and footprint`.

### Task 2: Sheet features and qualification

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixorder/SheetFeatures.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixorder/SheetFeaturesTest.kt`

**Interfaces:**
- Consumes: Task 1; `PageMetadata` (`Models.kt`); `sheetPages`/`SheetPage` (`data/mixfiles/SheetMatching.kt`).
- Produces: `data class SheetFeatures(val smallEdge: Int, val smallInterior: Int, val smallEdgeArea: Double, val minDim: Double, val partCount: Int)` with `fun vector(): DoubleArray` in that field order. Also `const val FEATURE_COUNT = 5`.
- Produces: `data class SmartSheet(val headPage: Int, val pgms: List<String>, val features: SheetFeatures)` and `val SmartSheet.key: String get() = "$headPage|${pgms.joinToString("+")}"`.
- Produces: `fun smartSheets(pages: List<PageMetadata>, draftPgms: Set<String>): List<SmartSheet>?`. It returns sheets (head-page order) for pages whose PGMs intersect `draftPgms` (lowercase compare). It returns `null` if any of those sheets fails qualification.

Rules: continuation pages (`isPartListContinuation`, `continuationHeadPage`) add their parts to the head page and are not checked separately. A sheet qualifies when its head page has `sheetDimensions` of size 2, it has ≥ 1 part after the merge, and every part has `sheetPlacement`. `smallEdgeArea` = Σ(width×length of small on-edge parts) ÷ (dims[0]×dims[1]). `minDim` = min over parts of `min(width, length)`. `pgms` comes from `sheetPages(...)` for the head page.

- [ ] **Step 1: Write failing tests:**
  - `countsSmallEdgeAndInterior`: a 96×48 page with a small part at the edge, a small part in the middle, and a large part gives `smallEdge=1, smallInterior=1, partCount=3`, and `minDim` and `smallEdgeArea` equal the hand-computed values.
  - `continuationPartsMergeIntoHead`: page 2 is a continuation of page 1 with no PGMs of its own, so the result is one sheet whose `partCount` is the sum.
  - `onePartMissingPlacement_null`.
  - `pageNotInDraft_ignored` (Review Focus 1): page 2 lacks placements but its PGMs are not in `draftPgms`, so page 1's sheet is returned and the result is non-null.
  - `missingDims_null`, `noParts_null`.
  - `doubleSidedPgmsKeptInOrder`: a page with `sheetFiles=["R1Z","R1A"]` gives `pgms == ["R1A.pgm","R1Z.pgm"]`.
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.data.mixorder.SheetFeaturesTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** `smartSheets`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(mix-order): compute per-sheet small/edge features`.

### Task 3: Weights, smart order, and draft seeding

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixorder/SmartOrder.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/mixfiles/MixDraft.kt` (`MixDrafts`)
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixorder/SmartOrderTest.kt`, plus additions to `app/src/test/java/com/kkc/sheettracker/data/mixfiles/MixDraftTest.kt`

**Interfaces:**
- Consumes: Task 2.
- Produces: `data class MixOrderWeights(val values: List<Double>)` (size `FEATURE_COUNT`), and `val PRIOR_WEIGHTS = MixOrderWeights(listOf(1.0, 0.001, 0.0, 0.0, 0.0))`. These reproduce the hand rule: `smallEdge` first, `smallInterior` breaks ties.
- Produces: `fun MixOrderWeights.score(f: SheetFeatures): Double` (dot product).
- Produces: `fun smartOrder(sheets: List<SmartSheet>, weights: MixOrderWeights): List<SmartSheet>`. This is a stable sort by score descending, so ties keep head-page order.
- Produces: `fun smartTag(f: SheetFeatures): String` = `"${f.smallEdge + f.smallInterior} small · ${f.smallEdge} on edge"`.
- Produces in `MixDrafts`: `fun reorderSheets(d: MixDraft, sheetPgmOrder: List<List<String>>): MixDraft`. Unlocked entries matching a sheet's PGMs are laid out in the given sheet order, each sheet's PGMs kept in their listed order. Locked entries keep their slots (same slot rule as `move`). Entries matching no listed sheet keep their relative order after the listed ones. `changeCount` is unchanged. A no-op returns `d`.
- `MixDrafts.startNew` is unchanged (Default = page order).

- [ ] **Step 1: Write failing tests:**
  - `priorPutsMostEdgePartsFirst`: three sheets with smallEdge counts (0, 4, 2) are ordered (4, 2, 0).
  - `priorTieBreaksOnInterior` and `equalScoresKeepPageOrder`.
  - `smartTagFormat`: smallEdge=3, smallInterior=2 gives `"5 small · 3 on edge"`.
  - `MixDraftTest.reorderSheets_keepsLockedSlots`: entries A, B(locked), C, D with order [D, C, A] give D, B, C, A.
  - `MixDraftTest.reorderSheets_keepsDoubleSidedPair` (Review Focus 2): sheet [R1A, R1Z] moved first stays adjacent, A before Z.
  - `MixDraftTest.toggleRoundTrip` (Review Focus 3): starting from `startNew` with one locked entry, reorder to the smart order, then to the page order, then to the smart order again. Each result equals a direct reorder to that mode.
  - `MixDraftTest.reorderSheets_unmatchedEntriesStayAfter`: an added non-sheet PGM stays after the listed sheets.
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.data.mixorder.SmartOrderTest" --tests "com.kkc.sheettracker.data.mixfiles.MixDraftTest"`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS, and existing `MixDraftTest` still passes.
- [ ] **Step 5: Commit** `feat(mix-order): score sheets and reorder new-mix drafts`.

### Task 4: Learning record and store

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixorder/MixOrderRecord.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixorder/MixOrderLearningStore.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixorder/MixOrderRecordTest.kt`, `MixOrderLearningStoreTest.kt`

**Interfaces:**
- Consumes: Tasks 2–3.
- Produces: `enum class MixOrderMode { SMART, DEFAULT }`. It serializes lowercase as `"smart"`/`"default"`.
- Produces: `data class MixOrderRecord(val version: Int = 1, val kind: String = "record", val recordId: String, val job: String, val material: String, val mixFile: String, val tabletId: String, val appliedAt: String, val appVersion: String, val mode: String, val edgeBandIn: Double, val smallPartIn: Double, val weightsUsed: List<Double>, val defaultOrder: List<String>, val suggestedOrder: List<String>, val finalOrder: List<String>, val sheets: Map<String, SheetFeatures>, val lockedSheets: List<String>)`. Orders hold `SmartSheet.key`.
- Produces: `data class SmartMixContext(val mode: MixOrderMode, val sheets: List<SmartSheet>, val defaultOrder: List<String>, val suggestedOrder: List<String>, val weights: MixOrderWeights)`.
- Produces: `fun buildMixOrderRecord(submitted: MixDraft, context: SmartMixContext?, saved: Boolean, lockedPgms: Collection<String>, job: String, material: String, tabletId: String, appVersion: String, now: Long, recordId: String = UUID.randomUUID().toString()): MixOrderRecord?`. It returns null unless `saved && submitted.newFile != null && context != null`. `finalOrder` = sheet keys in the order of their first PGM in `submitted.entries`. A sheet counts as locked if any of its PGMs is in `lockedPgms`.
- Produces: `class MixOrderLearningStore(pendingDir: File, clock: () -> Long = System::currentTimeMillis)` with:
  - `fun record(baseDir: File?, record: MixOrderRecord)`: writes to shared, falls back to pending, never throws. It uses the temp-then-rename and unique-name pattern from `CpuSpikeLogStore`. It flushes pending first when shared is reachable.
  - `fun flushPending(baseDir: File): Int`
  - `fun readAll(baseDir: File): List<MixOrderRecord>`: single files in the root plus every `bundles/*.json`. It dedupes by `recordId`, skips unparseable files, non-`.json` files, `.tmp` files, and records with `version > 1`. An in-memory parse cache in the companion object, keyed by absolute path + `lastModified` + `length`, means unchanged files aren't reparsed.
  - Constant `const val LEARNING_DIR = ".metadata/mix_order_learning"`.

- [ ] **Step 1: Write failing tests:**
  - `MixOrderRecordTest`:
    - `nullForExistingMix`, `nullWhenNotSaved`, `nullWithoutContext`.
    - `finalOrderFollowsEntries`.
    - `lockedSheetsFromPgms`.
    - `jsonRoundTrip`: the mode serializes as `"smart"`, and the feature map round-trips.
  - `MixOrderLearningStoreTest` (temp folders):
    - `writesSharedWithTabletIdInName`.
    - `offlineGoesPendingThenFlushes` (Review Focus 4): `baseDir = null` writes to pending, and the next `record` with a real base dir moves both files.
    - `readAllDedupesSinglesAndBundles`.
    - `readAllSkipsGarbage` (Review Focus 5): a truncated JSON file, `x.sync-conflict-20261103-ABC.json` holding a duplicate `recordId`, and `notes.txt` give one record and no exception.
    - `readAllSkipsNewerVersion`.
    - `readAllUsesCache`: overwrite a file with garbage but restore its `lastModified` and `length`, and the cached record is still returned.
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.data.mixorder.MixOrder*"`. Expected: FAIL.
- [ ] **Step 3: Implement.** Use one Gson instance with `disableHtmlEscaping()` and pretty printing, as in `CpuSpikeLogStore`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(mix-order): learning record and shared store`.

### Task 5: Pairwise trainer

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixorder/MixOrderTrainer.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixorder/MixOrderTrainerTest.kt`

**Interfaces:**
- Consumes: Tasks 3–4.
- Produces: `fun isTrainable(r: MixOrderRecord): Boolean`. True for mode `"smart"`. True for mode `"default"` only when `finalOrder != defaultOrder`.
- Produces: `fun trainMixOrderWeights(records: List<MixOrderRecord>, minRecords: Int = 5): MixOrderWeights`. It returns `PRIOR_WEIGHTS` when fewer than `minRecords` records are trainable. Otherwise it fits linear pairwise logistic regression. For each trainable record, take every pair of unlocked sheets present in both `suggestedOrder` and `finalOrder`. The earlier sheet in `finalOrder` should outscore the later one, with features from `record.sheets`. Use L2 toward `PRIOR_WEIGHTS`. Scale features internally to comparable ranges and return weights in raw feature units. Learning rate, L2 strength, and iterations are the implementer's choice, within the < 100 ms budget.

- [ ] **Step 1: Write failing tests:**
  - `belowMinimumReturnsPrior`: 4 trainable records give `PRIOR_WEIGHTS`.
  - `untouchedDefaultNotTrainable`: 10 untouched default records give `PRIOR_WEIGHTS`.
  - `learnsInteriorPreference`: 30 synthetic records with random features, whose `finalOrder` sorts by `smallInterior` desc while ignoring `smallEdge`. `smartOrder` with the trained weights reproduces ≥ 90% of the pairs on 20 held-out synthetic sheets.
  - `consistentWithPriorStaysClose`: records ordered exactly by the prior keep the prior's ordering on a held-out set.
  - `addedOrRemovedSheetsIgnored`: a key present only in `finalOrder` is ignored without error.
  - `fastEnough`: 500 records with 20 sheets each train in < 100 ms. Assert on the median of 5 runs after one warm-up.
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.data.mixorder.MixOrderTrainerTest"`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `feat(mix-order): pairwise trainer anchored to prior`.

### Task 6: Consolidator and admin action

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/mixorder/MixOrderConsolidator.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/settings/panes/AdminPane.kt` and its call site (find with `grep -rn "AdminPane(" app/src/main`)
- Test: `app/src/test/java/com/kkc/sheettracker/data/mixorder/MixOrderConsolidatorTest.kt`

**Interfaces:**
- Consumes: Task 4 (`MixOrderLearningStore`, `LEARNING_DIR`, record JSON).
- Produces: `data class ConsolidationResult(val records: Int, val filesAbsorbed: Int, val badMoved: Int, val leftUnknownVersion: Int, val bundle: File?)`.
- Produces: `fun consolidateMixOrderLearning(baseDir: File, source: String, store: MixOrderLearningStore, now: Long = System.currentTimeMillis()): ConsolidationResult`. It runs these steps in order:
  1. `store.flushPending(baseDir)`.
  2. List single files in the root plus `bundles/*.json`. That fixed list is the input; files arriving later are untouched.
  3. Parse the files. Unparseable ones move to `bad/`, and files with `version > 1` stay where they are.
  4. Dedupe by `recordId`.
  5. If there is nothing to merge (≤ 1 input file and no bad files), return without writing.
  6. Write `bundles/<timestamp>_<source>_bundle.json` (`{version:1, kind:"bundle", createdAt, source, records}`) via temp + rename.
  7. Re-read the bundle and verify that every gathered `recordId` is present. On mismatch, delete the new bundle and return with `bundle = null` and no other deletions.
  8. Delete the absorbed inputs, except the new bundle. A file that is already missing is skipped.
- Admin UI: in `AdminPane`, when admin mode is on, show an `OutlinedButton("Consolidate mix learning data")`. It calls a new parameter `onConsolidateMixLearning: suspend () -> ConsolidationResult?` (null means the share is unavailable) on `Dispatchers.IO`, and shows `"Merged ${records} records from ${filesAbsorbed} files"` (append `", ${badMoved} unreadable moved to bad"` when > 0), or `"Ready Jobs share unavailable"`. The button is disabled while running. The call site passes the base path from the scan snapshot and the `tablet_id` pref (`kkc_tracker` prefs) as `source`.

- [ ] **Step 1: Write failing tests:**
  - `mergesSinglesAndBundlesDedupe`.
  - `verifyFailureDeletesNothing`: inject a failing verify through an internal `verify` parameter or a writable seam; all inputs survive.
  - `missingFileSkipped`: delete an input after listing, through a test hook; no exception.
  - `unknownVersionLeftInPlace`.
  - `badFileMovedToBad` (Review Focus 5).
  - `nothingToDoWritesNothing`.
  - `idempotent`: a second run with only the bundle present writes nothing.
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.data.mixorder.MixOrderConsolidatorTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** the consolidator, then the AdminPane button and call-site wiring.
- [ ] **Step 4: Run** the consolidator tests and `.\gradlew.bat assembleDebug`. Expected: PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(mix-order): consolidate learning records on tablet`.

### Task 7: New-mix toggle, tags, weights loading, record on Apply

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/mixes/MixesSession.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/mixes/MixesScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/mixes/MixesPresentation.kt` (`RowModel`, `buildSheetGroups`, `MixesUiInput`)
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/mixes/MixesSheetList.kt` (`MixesSheetRow`)
- Test: additions to `app/src/test/java/com/kkc/sheettracker/ui/mixes/MixesSessionTest.kt` and `MixesPresentationTest.kt`

**Interfaces:**
- Consumes: Tasks 2–5.
- Produces in `MixesSession`:
  - `var smart by mutableStateOf<SmartMixContext?>(null); private set`
  - `var smartWeights: MixOrderWeights = PRIOR_WEIGHTS`, set by the screen once training finishes.
  - `startNew(material: String, pages: List<SheetPage>, locked: List<String>, metadataPages: List<PageMetadata> = emptyList())`: after building the Default draft, calls `smartSheets(metadataPages, draftPgms)`. If non-null, it sets `smart` to mode `SMART` with `defaultOrder`/`suggestedOrder`/`weights = smartWeights`, and applies `MixDrafts.reorderSheets` to the suggested order. If null, `smart = null`.
  - `fun setSmartMode(mode: MixOrderMode)`: no-op unless `draft?.newFile != null && smart != null && !applying`. Reorders the current draft to the suggested or default sheet order and updates `smart.mode`.
  - `smart` is cleared wherever `draft` is cleared (`discardMix`, a successful `saveMix`, `mutateCatalog`, `stageRevision`).
- Produces in `MixesUiInput`: `smartTags: Map<Int, String> = emptyMap()` (head page → `smartTag`). `RowModel` gets `smartTag: String?`, filled only for sheet rows whose page is in the map. The screen passes tags only when `session.smart?.mode == SMART`.
- `MixesScreen`:
  - Show a two-option segmented control **"Smart suggestion" | "Default"** in the new-mix card area when `session.smart != null`, using the existing KKC sliding-pill/segmented component (`KKCSlidingPill`).
  - `onNew` passes `material?.metadata?.pages.orEmpty()`.
  - On open, a `LaunchedEffect(Unit)` on `Dispatchers.IO` runs `trainMixOrderWeights(store.readAll(baseDir))` into `session.smartWeights`, with failures ignored and the prior kept. Base dir = `File(scan.snapshot.basePath)`.
  - In `applyMix`, capture `session.draft` and `session.smart` before `saveMix`. After the result, call `buildMixOrderRecord(...)`. When non-null, write it with `scope.launch(Dispatchers.IO) { store.record(baseDir, it) }`. The tablet ID resolution is the same as the existing second-pass `deviceId` code. App version comes from `BuildConfig.VERSION_NAME`. The store is `remember { MixOrderLearningStore(File(context.filesDir, "mix_order_learning/pending")) }`.
- `MixesSheetRow` shows `row.smartTag` as a small `labelSmall` secondary-text chip when non-null.

- [ ] **Step 1: Write failing tests:**
  - `MixesSessionTest.startNewQualifyingJobIsSmartSorted`.
  - `MixesSessionTest.startNewNonQualifyingHasNoSmart`.
  - `MixesSessionTest.setSmartModeDefaultRestoresPageOrder` and `setSmartModeKeepsRenameAndAddedEntries`.
  - `MixesSessionTest.setSmartModeIgnoredForExistingMix`.
  - `MixesSessionTest.smartClearedOnDiscardAndSave`.
  - `MixesSessionTest.saveOkReturnsWhileRecordWriteHangs` (Review Focus 4): save through a store whose shared dir write blocks, using a fake `baseDir` on a latch. `saveMix` completes without waiting, because writing is launched separately. Assert ordering with `runTest` and an unreleased latch.
  - `MixesPresentationTest.smartTagOnSheetRowsOnly`.
- [ ] **Step 2: Run** `--tests "com.kkc.sheettracker.ui.mixes.*"`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement** the session, presentation, screen, and row changes.
- [ ] **Step 4: Run** `--tests "com.kkc.sheettracker.ui.mixes.*" --tests "com.kkc.sheettracker.data.mix*"` and `.\gradlew.bat assembleDebug`. Expected: PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(mixes): smart suggestion toggle for new mixes`.

### Task 8: PC consolidation script

**Files:**
- Create: `tools/consolidate-mix-learning.ps1`

**Interfaces:**
- Consumes: the JSON format from Tasks 4 and 6 (must match the Kotlin consolidator's steps 2–8).
- Produces: `.\tools\consolidate-mix-learning.ps1 [-Root "Y:\Ready Jobs\.metadata\mix_order_learning"] [-WhatIf]`. It prints `Records: N  Files absorbed: N  Bad moved: N  Left (newer version): N  Bundle: <path|none>`. `source` is `pc`. Use `[CmdletBinding(SupportsShouldProcess)]`. Write UTF-8 without BOM through `[IO.File]::WriteAllText($p, $json, [Text.UTF8Encoding]::new($false))`. Use `ConvertTo-Json -Depth 20`. ASCII-only source, and it must work in Windows PowerShell 5.1 and pwsh 7.

- [ ] **Step 1: Make a test copy.** Copy a folder of 3 single records, 1 bundle with one duplicate `recordId`, 1 garbage file and 1 `version: 2` file (generate these with the Task 4 tests' JSON or by hand) to the scratchpad.
- [ ] **Step 2: Run `-WhatIf`** with `powershell -NoProfile -File tools\consolidate-mix-learning.ps1 -Root <copy> -WhatIf`. Expected: the counts print, and no files change (compare `Get-ChildItem -Recurse` before and after).
- [ ] **Step 3: Run for real** on the copy. Expected: one new bundle, the garbage file in `bad\`, the v2 file in place, and all other inputs gone.
- [ ] **Step 4: Cross-check.** Add a Kotlin test, `MixOrderConsolidatorTest.readsPowerShellBundle`, that reads a checked-in bundle produced by the script (`app/src/test/resources/mixorder/pc_bundle.json`) through `MixOrderLearningStore.readAll`. Run it. Expected: PASS (proves no BOM and matching field names).
- [ ] **Step 5: Commit** `feat(mix-order): PC consolidation script`.

### Task 9: Docs and ownership

**Files:**
- Modify: `CLAUDE.md` (add a short "Smart mix order" section: package, shared folder, the no-fallback rule, and the record/bundle format pointer to the spec)
- Modify: the `kkc-metadata-map` skill's canonical `SKILL.md`. Locate it under `C:\Users\chadc\.sync-skills\skills\` if present, else `C:\Users\chadc\.claude\skills\kkc-metadata-map`. Add the row: `Ready Jobs\.metadata\mix_order_learning\` is owned by KKCSheetTracker (per-tablet single records, bundles from the tablet admin action or the PC script).

- [ ] **Step 1: Edit both docs.**
- [ ] **Step 2: Run the full unit suite**: `.\gradlew.bat :app:testDebugUnitTest`. Expected: PASS except the known PdfMarkup MotionEvent off-device failure.
- [ ] **Step 3: Commit** `docs: smart mix order ownership and notes`.
- [ ] **Step 4: Hand off for on-device check.** Build the release APK (`gradlew assembleRelease`, then `adb install -r`, per the release-build memory). Ask the owner to open New mix on job 720 and confirm the toggle, the order, and the tags. Ask whether a record appears in `Y:\Ready Jobs\.metadata\mix_order_learning\` after Apply.
