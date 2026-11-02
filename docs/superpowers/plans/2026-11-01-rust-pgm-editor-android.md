# Rust PGM Editor Android Implementation Plan

Execution status (2026-11-02): A1–A3 implemented in
`C:\Scripts\.worktrees\pgm-rust-android`; 193 affected tests and assembleDebug
pass. See `C:\Scripts\G_Code_2nd_Pass\docs\superpowers\2026-11-02-pgm-editor-verification.md`.
No device installation, publication or commits; device rendering remains acceptance.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Follow the selected execution method and this repository's route rules; the default light route uses no implementation subagents.

**Goal:** Extend existing second-pass controls with optional source preview and revision-aware Apply while preserving offline viewing, partial-result recovery, and the current restore-first workflow.

**Architecture:** Keep server-side source transformation and file ownership. Add typed preview/revision models and a small preview state holder; the existing runner carries hashes across restore/edit operations and the existing Mixes screen displays previews/notices.

**Tech Stack:** Existing Kotlin/Compose, OkHttp/Gson, coroutines, JVM JUnit/MockWebServer, Gradle debug build; no native Android converter or new dependencies.

**Spec:** [Approved design](C:/Scripts/G_Code_2nd_Pass/docs/superpowers/specs/2026-11-01-rust-pgm-editor-design.md); [coordinator and exact wire contracts](C:/Scripts/G_Code_2nd_Pass/docs/superpowers/plans/2026-11-01-rust-pgm-editor.md). Service S1-S3 supplies negotiated routes.

## Global Constraints

Inherit coordinator Global Constraints. Preserve `com.kkc.sheettracker`, existing minSdk/target/build settings and UI theme. Preview remains optional; no new feed/RPM/depth controls or arbitrary source editing. Do not publish APKs, install/uninstall devices, write live Ready Jobs, or edit project progress/handoff documents in the default light route. Consult applicable AGENTS.md and UI skills at implementation time.

## Review Focus

Own coordinator focus 1 (compound restore/preview), Android portion of 5 (uncertainty/reconnect), and old-service compatibility. A1/A2 test protocol/runner behavior; A3 tests preview invalidation, notices, and UI decisions. Treat non-success file statuses and uncertain outcomes conservatively.

## Files and commands

Production prefix `app/src/main/java/com/kkc/sheettracker/` (M); tests prefix `app/src/test/java/com/kkc/sheettracker/` (T). These prefixes are exact repository-relative paths, not new packages. Existing entry points: M`data/mixservice/MixServiceClient.kt`, M`data/mixfiles/SecondPass.kt`, M`ui/mixes/MixesSession.kt`, M`ui/mixes/MixesScreen.kt`. Create focused `data/mixservice/PgmPreviewModels.kt`, `data/mixfiles/SecondPassRevisions.kt`, `ui/mixes/PgmPreviewState.kt`, and `ui/mixes/PgmPreviewSheet.kt`.

Run `./gradlew.bat :app:testDebugUnitTest --tests '<fully.qualified.TestClass>'` for task red/green checks. Final checks use filtered tests covering `data.mixservice.*`, `data.mixfiles.*`, and `ui.mixes.*`, then `:app:assembleDebug`. Use current toolchain; do not upgrade dependencies to implement this feature.

### A1: Typed negotiated preview and revision protocol

**Files:** Modify M`data/mixservice/MixServiceModels.kt`, `MixServiceClient.kt`, `MixOperationModels.kt`, M`data/mixfiles/MixFilesClient.kt`; create M`data/mixservice/PgmPreviewModels.kt`; extend T`data/mixservice/MixServiceClientTest.kt`, T`data/mixfiles/MixFilesClientTest.kt`; create T`data/mixservice/PgmPreviewClientTest.kt`.

**Interfaces:** Add optional `sha256:String?`/`revisionError:String?` to `PgmInventoryItem` and optional `expectedSha256:String?`/`expectedSourceSha256:String?` to `PgmEditRow` with null defaults. Add typed `PgmPreviewRequest`, `PgmPreviewFile`, `PgmConversion`, `PgmNotice`, and `PgmPreviewResult` matching the coordinator. `MixServiceClient.listPgms(job,material,withSha256:Boolean=false)` keeps existing calls valid; `submitPgmPreview(job,material,requestId,row,restoreOriginal:Boolean=false):MixServiceOperation`; `decodePgmPreview(result:Any?):PgmPreviewResult` validates typed values. Operation envelope gains optional `mutation` string. Preserve existing `MixOperationService` compatibility using default methods or a separate preview interface rather than breaking all test doubles.

- [ ] Add `oldServiceReceivesNoNewFieldsOrPreviewMutation`, `previewPostsEncodedPathAndPollsReadOnlyOperation`, `previewRejectsMalformedSourceHashesOrOversizeResult`, `latin1SourceAndCrLfSurviveJson`, `shaInventoryRequestedOnlyWhenSupported`, and `refusedOutcomeIsNeverSuccess`. Assert missing/malformed features disable only the new functionality and nullable fields are omitted from legacy JSON.
- [ ] Run A1 test classes; new interface/behavior tests should FAIL. Implement typed models/client behavior with strict 64-lowercase-hex hashes and 1 MiB preview result validation; do not decode a PGM on Android. Reuse existing operation polling/cancellation semantics, keeping preview result handling separate from `successfulFiles` for edits.
- [ ] Extend `MixFilesClient` with a revision-aware revert submission accepting a typed `PgmRevertRow(name, expectedSha256:String?=null)` while retaining the existing `submitPgmEditRevert(...,pgms:List<String>)` wrapper. Use a distinct method name to avoid JVM generic-erasure overload clashes. Negotiate feature support before adding any hash fields or `restoreOriginal`.
- [ ] Re-run A1 tests and existing MixService/MixFiles client tests; expected PASS. Unknown optional fields from a future server may be ignored, but malformed recognized hashes/source/results fail closed.

### A2: Carry hashes through previews, Apply, and restore-then-edit

**Files:** Create M`data/mixfiles/SecondPassRevisions.kt`; modify M`data/mixfiles/SecondPass.kt`, M`ui/mixes/MixesSession.kt`; extend T`data/mixfiles/SecondPassTest.kt`, T`ui/mixes/SecondPassRefusalRecoveryTest.kt`, T`ui/mixes/MixesSessionTest.kt`; create T`data/mixfiles/SecondPassRevisionsTest.kt`.

**Interfaces:** `SecondPassRevisionSnapshot(hashes:Map<String,String>, previews:Map<String,PgmPreviewFile>)` is keyed with existing case-insensitive PGM identity. `previewRequest(draft,pgm,hash):PgmPreviewRequest` derives final intent and `restoreOriginal` using existing `planSecondPass`. Extend `SecondPassRunner` via optional revision support, preserving existing callers: provide callbacks for fresh hash inventory and revision-aware revert, and a snapshot of applicable previews. Do not replace existing intent planning with new cutting logic.

- [ ] Add `additiveApplyUsesCapturedRevisionNotSilentNewExport`, `superToStandardPreviewUsesOriginalAndHashesRefreshAfterRestore`, `restoreOnlyChecksCurrentHashAndNeedsNoEncoder`, `originalAlreadyEditedPreviewShowsActualState`, `postRestoreHashMustMatchPreviewBase`, `partialRevertPreventsFollowingEdits`, `conflictKeepsDraftAndInvalidatesPreview`, `lostResponseDoesNotCreateAnotherRequestId`, and `noPreviewBatchStillUsesHashes`. Assert the same accepted request keeps identical hashes/ID on recovery; explicit refresh/review starts a new intent/ID.
- [ ] Run task tests; new revision cases should FAIL. Store the initial revision snapshot when loading inventory, not as an unannounced last-second replacement during Apply. If a required hash is null, refuse only affected submissions with a refresh message. Draft changes invalidate source-preview hashes but preserve the original revision until explicit authoritative refresh; reconnect requires refresh before Apply.
- [ ] Bind additive rows to the captured original hash and, when present, preview proposed-source hash. For restore rows pass the current captured hash. After confirmed successful restore, explicitly refresh inventory/history before any following edit; require the restored hash to equal the preview's `baseSha256` for that row, then use that refreshed hash plus the final preview source hash for reapplication. If restore or readback is partial/uncertain, stop; do not reuse pre-restore hashes or proceed with remaining steps.
- [ ] Preserve `SecondPassOutcome.Done` and existing successful-file checks; add successful notice delivery through an optional runner callback, avoiding a breaking change to all outcome consumers. Add trusted `mutation` handling: only `refused_before_mutation` with no earlier completed plan step can retain confirmed pre-edit state; all other errors/late refused rows force authoritative history readback. Retain wanted draft intent after refusal. Cancellation never starts an automatic retry.
- [ ] Re-run A2 tests plus existing SecondPass/session/refusal tests; expected PASS. Include missing/corrupt history and re-export discontinuity so a fresh source hash never bypasses the existing ledger uncertainty checks.

### A3: Optional source preview and converter notices in the existing UI

**Files:** Create M`ui/mixes/PgmPreviewState.kt`, `PgmPreviewSheet.kt`; modify M`ui/mixes/MixesScreen.kt`, `MixesSheetList.kt`, `SecondPassControls.kt`, and narrowly `MixesPresentation.kt`/`MixesSession.kt` as needed; create T`ui/mixes/PgmPreviewStateTest.kt`; extend T`ui/mixes/MixesPresentationTest.kt`, `MixesLayoutTest.kt`.

**Interfaces:** `PgmPreviewKey(machine:String,job:String,material:String,pgm:String,originalSha256:String,intent:SecondPassState,restoreOriginal:Boolean)`; preview state holder exposes `loading`, `result`, `error`, and invalidation for key changes. Late responses are accepted only when their key still matches. `PgmPreviewSheet(result,onDismiss)` displays read-only Changes/Current/Proposed views using existing sheet/dialog conventions and theme tokens, with monospace horizontally scrollable source and accessible controls.

- [ ] Add `draftMaterialMachineOrRevisionChangeInvalidatesPreview`, `latePreviewResponseCannotReplaceAnotherFile`, `oversizePreviewLeavesApplyAvailable`, `offlineCachedPreviewIsMarkedStaleAndCannotAuthorizeApply`, `fallbackNoticeDoesNotTurnSuccessfulEditIntoFailure`, and `previewAbsentOnOldServiceKeepsExistingControls`. Assert no source-editor input and no automatic preview batch for a material-wide action.
- [ ] Run UI state/presentation tests to verify new behavior FAILS. Add an optional Preview action beside existing per-file history/edit actions, without changing drag/reorder gestures. Preview uses the selected final draft, including restore-first cases; a restore-only preview shows the exact authenticated original's source. Report source errors/provenance refusal honestly. Keep the separate second-pass Apply control.
- [ ] Render bounded source/diff without unbounded syntax-processing work; use lazy line rendering for large previews and highlight diff line types with accessible text/signs as well as colors. Display nonblocking fallback notices after successful Apply and actionable machine/revision/converter errors on failure. Opening preview never changes controls or performs Apply.
- [ ] Run filtered unit tests for `com.kkc.sheettracker.data.mixservice.*`, `com.kkc.sheettracker.data.mixfiles.*`, `com.kkc.sheettracker.ui.mixes.*`; expected PASS. Run `./gradlew.bat :app:assembleDebug`; expected BUILD SUCCESSFUL. Use an emulator/local mock service for manual portrait/landscape checks: preview, rapid file switch, close/reopen, long source, offline/reconnect, partial failure, and restore-then-edit. Do not run device uninstall or publish a release.
- [ ] Record validated behavior and pending real-device acceptance in the implementation report; update only relevant feature/API documentation if it exists. Do not edit project progress/handoff documents in light route or change canonical skill facts until implementation evidence exists.
