# Metadata-Only Cutlists on the Tablet — Design

Date: 2026-10-14
Status: Design approved in conversation; written spec awaiting review. No implementation started.
Upstream: Hours Tracker spec `docs/superpowers/specs/2026-10-14-cv-export-worker-ingestion-design.md` (worker publishes the docs this tablet reads; merged to Hours Tracker `master`). Supersedes the Codex draft `cv-cutlist-tablet-plan.md`, which assumed gates and files that do not exist in this code.

## 1. Intent

The Hours Tracker worker now publishes hardwood cutlist docs built from Cabinet Vision metadata exports, with no PDFs. The tablet must show, track and navigate those docs offline exactly like PDF-derived ones, support several named batches of the same document type, and never crash when the export later carries data the tablet does not know yet.

Success: a job whose `cutlist_index.json` contains only metadata-backed docs appears in the Hardwoods and Specialty cutlist screens (active and archived jobs), rows can be marked done, batches can be switched, door assemblies can be read, and legacy PDF jobs behave identically.

## 2. What the code does today (verified 2026-10-14)

- The index is decoded with Gson (`FileBackedUnifiedMetadataEngine.kt` ~1043), so unknown JSON keys are ignored and missing keys take Kotlin defaults.
- The cutlist workspace is a table of `selectedDoc.rows`. A PDF is required only by two identical availability gates: `HardwoodsWorkspaceScreen.kt` ~610 and `HardwoodsJobDetailScreen.kt` ~230 (`doc.pdfFilename.isNotBlank() && jobRepository.getJobRootPdfFile(...) != null`).
- Navigation routes carry `docType` and an initial `rowId` (`hardwoods/workspace/{folderName}/{docType}/{startPage}/{hiddenMaterialsMode}`), on both the live graph and the archive host.
- About 20 sites assume one doc per `docType` (`associateBy { it.docType }`, `firstOrNull { it.docType == ... }`): workspace, job detail, `HardwoodsProgressStore` (~782, 886, 1015), `SpecialtyStateStore` (~224, 256), `DoorPanelCutListData` (~340), `SpecialtyDoorPanelsScreen` (~276), `HardwoodsRepository.findHardwoodsPdfFilename`, `UnifiedModeSpecs` progress segments.
- Gson maps an unknown enum string to `null` in a non-null Kotlin enum field, so an unknown `docType` would crash code that dereferences it.

## 3. Decisions

| # | Decision |
|---|---|
| T1 | Add optional `sourceKind`, `batchId`, `batchName`, `revisionId`, `doors` to `HardwoodDocumentIndex`; old JSON decodes identically (all defaults). |
| T2 | After decode, drop any doc whose `docType` is null (unknown to this build). Same rule protects every consumer. |
| T3 | One shared availability rule, used by both gates: a doc is available if it is metadata-backed (`sourceKind == "CV_METADATA"`) or has a PDF that exists; `DOOR_LIST` stays excluded. Legacy unchanged. |
| T4 | Batch identity is `(docType, batchId)`; legacy docs have `batchId == ""`. A helper returns the docs of a type. The workspace tracks the selected doc by that key; opening a row (search, archive, intent) selects the doc that owns the `rowId`, so routes do not change. |
| T5 | When a type has more than one batch, the workspace shows batch chips (named batches) above the table. |
| T6 | Roll-ups (specialty closet rods and door panels, door-panel list, job progress counts, search index) aggregate rows across all batches of a type. Board stock already iterates every doc. |
| T7 | Progress needs no format change: tracker actions are keyed `(docType, rowId)` and CV row IDs embed the batch ID (`cv:<batchId>:<hash>`), so batches cannot share completion. |
| T8 | A plain read-only door-assemblies list (style, size, room/cabinet, hinge, grain, notes, profiles) is shown for door cut-list docs that carry `doors`. |
| T9 | Forward compatibility: unknown keys are ignored (Gson); unknown docs dropped (T2); the tablet never requires fields the worker marks optional. |

## 4. Out of scope

Rip-totals for metadata docs (the worker publishes `totals: []`; the rip tab stays empty for them); PDF markup for metadata docs (cutlist docs have none); install/uninstall on shop tablets; changing worker or Hours Tracker code.

## 5. Verification

- JVM unit tests (`app/src/test/...`, existing JUnit setup): decode old and future-shaped JSON; unknown `docType` dropped; availability rule; doc lookup by key; per-batch isolation of progress; roll-ups across batches; door assembly mapping.
- `gradlew.bat testDebugUnitTest` and `assembleDebug` pass. Existing tests stay unchanged.
- A real-device check of a metadata-only test job (active and archived, offline) is a human step after the APK is built; nothing is installed on shop tablets by this work.

## 6. Execution constraints

- Work in an isolated git worktree of KKCSheetTracker `main`; the main tree holds unrelated uncommitted edits (including `Models.kt` and `FileBackedUnifiedMetadataEngine.kt`) that must not be committed or lost. Stage only the files named by each task.
- Follow the project's existing code style and test layout; no new dependencies.
