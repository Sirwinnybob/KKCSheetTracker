# Metadata-only cutlists on the tablet: release candidate note

Date: 2026-10-14
Branch: `feat/cv-cutlists-tablet` (worktree `.claude/worktrees/cv-cutlists`), base `9001dbc1`
Spec: `docs/superpowers/specs/2026-10-14-metadata-only-cutlists-tablet-design.md`
Plan: `docs/superpowers/plans/2026-10-14-metadata-only-cutlists-tablet.md`

## Commits (base 9001dbc1..aaf3d55a)

- `ee441aa9` feat(hardwoods): decode metadata-backed cutlist docs with batch fields; drop unknown doc types
- `16c4b5f5` feat(hardwoods): roll up cutlist docs across batches; metadata docs need no PDF in job detail
- `aaf3d55a` feat(hardwoods): workspace shows metadata cutlists with batch chips and door list

Diff vs base: 17 files, +626 / -40 (11 source files, 4 new test files).

## Verification run

| Step | Command | Result |
|---|---|---|
| Full unit suite, first run | `.\gradlew.bat testDebugUnitTest` | 1923 tests, 1 failed: `SyncthingSupervisorTest > failed active resume keeps paused status until a retry succeeds` (SyncthingSupervisorTest.kt:226) |
| Full unit suite, rerun | `.\gradlew.bat testDebugUnitTest` | BUILD SUCCESSFUL, 1923 tests, 0 skipped, 0 failures, 0 errors (counted from the XML reports) |
| Debug APK | `.\gradlew.bat assembleDebug` | BUILD SUCCESSFUL (all 38 tasks up to date from the earlier build of this same tree) |
| Real-data decode | scratch JVM test (deleted, not committed) | passed, see below |

### The one Syncthing failure

`SyncthingSupervisorTest` is a timing-based test (`waitUntil(2_000L)` on `Dispatchers.Default` coroutines). Nothing in this branch touches Syncthing code or that test (`git diff --stat 9001dbc1..HEAD` lists none of it). It failed once under whole-suite load and then passed on: the isolated class run, six further forced isolated reruns of the class (`--rerun`), and a complete second suite run. It was not reproduced on base commit `9001dbc1`: a base-commit full build was not performed, so "pre-existing" is an inference from the untouched code plus non-reproduction, not a demonstrated fact. Treat it as an intermittent, unrelated flake and fix the test's timing separately.

### APK

- Path: `C:\Scripts\KKCSheetTracker\.claude\worktrees\cv-cutlists\app\build\outputs\apk\debug\app-debug.apk`
- Size 88,670,945 bytes, version 8.8.2 (versionCode 80802), applicationId `com.kkc.sheettracker`
- SHA-256 `3a13ddb04b3d94c4f7f5464dc362eddb6bfdb2c5938b00df9fd7f1417a03478f`
- Rebuilt after the final-review fix below; the size and hash above supersede the earlier build's (88,149,703 bytes, `398ea79e...`).

### Real-data decode check

Input: the actual `cutlist_index.json` the Hours Tracker worker published for a scratch metadata-only job (3 documents). Loaded through the real `FileBackedUnifiedMetadataEngine` with `deployment_gate.json` `{"deployed": true}`. Asserted and passed:

- 3 documents: DOOR_CUT_LIST "Main", FACE_FRAME_CUT_LIST "Main", FACE_FRAME_CUT_LIST "Second"
- every row ID starts with `cv:` (`cv:11111111-...:4999e9af95d224da`, `cv:11111111-...:7fdda095d0ad7a21`, `cv:22222222-...:7fdda095d0ad7a21`)
- door doc has exactly 1 door: style `2 1/4" Flat Panel`, 15 x 30, hinge Blum, grain Vertical, notes "Paint grade"
- all docs `sourceKind` CV_METADATA, empty `pdfFilename`, `pageCount` 0
- `HardwoodDocuments.isAvailable(doc) { false }` is true for all three (available with no PDF on disk)

## Verified by unit tests

- Decode of legacy PDF-backed indexes (empty batch fields), metadata docs with batch fields and doors, future keys ignored, unknown doc types dropped (`CvCutlistIndexDecodeTest`).
- Availability rules, doc selection by owning row, merge and summary logic (`HardwoodDocumentsTest`).
- Roll-ups across batches and progress isolation between batches (`MetadataBatchRollupTest`).
- Batch chip helper logic and door summary lines, including labelled profiles (`HardwoodsBatchUiTest`).
- Real worker output decodes correctly (scratch check above).

## Needs a human check on a test tablet (not verified by any automated test)

1. A metadata-only job (no cutlist PDFs) is visible in the Hardwoods list and the Specialty list.
2. It opens from both the active route and the archived route.
3. Cutlist rows can be completed with the tablet offline, and the completion survives restart and sync.
4. Batch chips switch between the Main and Second tables, and progress stays separate per batch.
5. The door list expands and shows style, size, room/cabinet, hinge, grain, profiles and notes. It is a capped (240dp) scrollable panel: with a 30-60 door job, confirm the list scrolls inside its cap and the cutlist table stays visible and usable below it.
6. A legacy PDF-backed job is unchanged (PDF still renders, rows, rip totals and progress as before).
7. The rip-totals tab is empty for metadata docs (the worker publishes `totals: []`); confirm that is acceptable.

## Final-review fixes (after the verification above)

- Door list: the expanded list is now a capped (240dp) scrollable panel, so a 30-60 door job no longer pushes the cutlist table off screen. Compose layout; verified by compiling only (`:app:compileDebugKotlin` re-run and `assembleDebug` succeeded), not by a unit test.
- Door profiles are now shown. `profiles` is outside edge, inside edge, panel detail, route pattern (in that order, blanks skipped); a door line reads for example `D2 · profiles: outside Ogee, panel Raised, route Cove · x`. Covered by `HardwoodsBatchUiTest`.
- `testDebugUnitTest --tests "com.kkc.sheettracker.ui.*"` passed after the change. The full-suite run in the table above predates this fix.

## Known limits

`completedRemovedRowsForDocument` and collapsed-section state are keyed by `docType`, so they are shared across batches of one type (cosmetic).

## Device safety statement

Nothing was installed on any device. `adb` was not used at all (no install, no uninstall, no device commands). Only Gradle unit tests and `assembleDebug` were run, on this worktree.
