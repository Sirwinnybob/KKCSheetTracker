# Project Progress
# CNC catalog operation integration — completed

Plan: `docs/superpowers/plans/2026-09-03-cnc-catalog-operation-integration.md`.

Goal: port the revisioned CNC catalog feature onto current `main` while preserving
restart-safe Manage Code mutations and the current explicit viewer-page model.

Execution: Heavy route. The main agent owns plan state, integration, Git-state
operations, and release-safe verification. Executors own one bounded implementation
task at a time; read-only reviewers validate each task before the next begins.

Work breakdown:
- HCO-1: durable revisioned catalog actions and hybrid coordinator/client dispatch.
- HCO-2: cache-first catalog reads, projections, page mapping, and durable action planning.
- HCO-3: cache-first Manage Code and Job Detail integration, then ancestry-only merge of
  `codex/cnc-mix-catalog` once all intended behavior is ported.
- HCO-4: focused and full test/build verification; no APK copy, deployment, or update-feed change.

Gates: every catalog request is persisted before submission; unacknowledged submissions
restore as interrupted without replay; stale revisions require a refreshed selection;
the main navigation/viewer contract stays authoritative.

Status: completed 2026-09-03.

Delivered:
- HCO-1 through HCO-3 are integrated in `5b64fc1` with durable revisioned catalog
  mutations, cache-first Manage Code/Job Detail, archive isolation, and restart-safe
  cache-publication recovery.
- `codex/cnc-mix-catalog` is recorded by the ancestry-only merge commit `38dd5c8`.
- Closing Luna/Terra gates passed after targeted regressions for cancellation, stale
  revisions, cache durability, cross-instance cache safety, and operator-edit retention.
- Fresh verification: `:app:testDebugUnitTest` (1,050 tests; zero failures/errors/skips)
  and `:app:assembleRelease` both passed. The APK was built locally only; no copy,
  deployment, or update-feed change was made.

# Archive job detail screens — completed

Plan: `docs/superpowers/plans/2026-08-20-archive-job-detail-screens.md`.

Archive-backed, no-persist job detail and child viewers are available for all work modes. Archive is a Library tile immediately after Safety / SDS, not a bottom-navigation destination. Archived CNC history is rendered with an archive-only, byte-length-compatible tracker fingerprint fallback because ZIP extraction changes file modification times; writable/live jobs retain strict fingerprint matching.

Verification completed on 2026-08-20: focused archive/data/navigation tests, `:app:testDebugUnitTest`, and `:app:assembleDebug` all passed.

# Cabinet spillover jump — awaiting manual verification

Plan: `docs/superpowers/plans/2026-09-08-cabinet-spillover-jump.md`.

Execution: Heavy route with isolated Android and backend worktrees. The automated implementation,
task-level reviews, and final review are complete; Task 8 is intentionally pending because it writes
the live Ready Jobs index and installs an APK on a connected tablet.

Delivered:
- Backend commit range `741d35a..e3e982d` reads per-page drawing/image signal, resolves a
cabinet's nearest drawing page, preserves Plans adjacency, and annotates both raw and virtual
combined indexes.
- Android commit range `686ca120..1e63c4e` preserves the additive index fields, resolves drawing
pages in the active navigator coordinate space, and uses them before the existing spillover fallback.

Verification:
- Fresh Android `:app:testDebugUnitTest` passed.
- Fresh Android `:app:assembleRelease` passed.
- Focused backend cabinet-reference regressions passed (62 tests); a fresh backend suite had
3,009 passed and 12 skipped. Its 16 failures exactly match the prior Handoff/PDFme baseline caused
by the absent `@pdfme/generator` worktree dependency.

Pending external verification: authorize refreshing job 669's live index and installing the tablet
APK with `adb install -r`, then confirm cabinet 12 jumps to drawing page 5, cabinet 5 retains the
spillover fallback, and continuous mode uses the same resolved page.
