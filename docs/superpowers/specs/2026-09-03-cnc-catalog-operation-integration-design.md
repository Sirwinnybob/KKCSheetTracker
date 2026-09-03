# CNC catalog operation integration

## Goal

Integrate `codex/cnc-mix-catalog` into current `main` without bypassing the durable, process-scoped mutation workflow introduced after that branch forked.

## Architecture

`MixOperationCoordinator` remains the only mutation owner. Catalog reads are cache-first and may be rendered offline, but Compose screens only assemble persisted actions and observe coordinator state; they never call catalog mutation endpoints directly.

`ManageCodeOperationAction` gains revisioned catalog-create, catalog-replace, and exact external-delete variants. `MixOperationService` returns existing polled `MixServiceOperation` values for legacy mix and PGM actions, and `MixCatalogMutationResult` for synchronous catalog variants. The coordinator persists a submitting action before any request; a successful catalog response advances and persists the session, while an unknown submission restores as interrupted and requires an operator retry.

Retries retain the original catalog revision. A request that succeeded before process death returns `catalog_changed` on retry, forcing cache refresh and a new operator choice rather than replaying stale intent. `Success` and history-sync warning responses refresh the material cache only after the completed action is persisted.

Current `ViewerMixSelection` routes remain authoritative. Job Detail projects cached active catalog entries to those explicit page selections; history and external entries never create production cards or routes.

## Constraints

- Never mutate CNC files or definitions directly from Android.
- Preserve current durable MIX and PGM edit operation semantics.
- Do not auto-retry unacknowledged catalog submissions.
- Use cache only for display; all mutations require a service response and revision guard.
- Preserve physical-page progress identity and existing viewer page-order behavior.

## Verification

Add JUnit coverage for catalog action persistence, catalog-plus-PGM ordering, interrupted recovery, stale revision failure, cache-first offline UI, and active-catalog viewer selections. Then run `testDebugUnitTest`, `assembleRelease`, `git diff --check`, and a clean-status check. No APK installation or feed deployment is in scope.
