# Project Progress
# CNC catalog operation integration — active

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

Status: active. HCO-1 is the next executor task.

# Archive job detail screens — completed

Plan: `docs/superpowers/plans/2026-08-20-archive-job-detail-screens.md`.

Archive-backed, no-persist job detail and child viewers are available for all work modes. Archive is a Library tile immediately after Safety / SDS, not a bottom-navigation destination. Archived CNC history is rendered with an archive-only, byte-length-compatible tracker fingerprint fallback because ZIP extraction changes file modification times; writable/live jobs retain strict fingerprint matching.

Verification completed on 2026-08-20: focused archive/data/navigation tests, `:app:testDebugUnitTest`, and `:app:assembleDebug` all passed.
