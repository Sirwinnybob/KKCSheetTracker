# Project Progress
# Archive job detail screens — completed

Plan: `docs/superpowers/plans/2026-08-20-archive-job-detail-screens.md`.

Archive-backed, no-persist job detail and child viewers are available for all work modes. Archive is a Library tile immediately after Safety / SDS, not a bottom-navigation destination. Archived CNC history is rendered with an archive-only, byte-length-compatible tracker fingerprint fallback because ZIP extraction changes file modification times; writable/live jobs retain strict fingerprint matching.

Verification completed on 2026-08-20: focused archive/data/navigation tests, `:app:testDebugUnitTest`, and `:app:assembleDebug` all passed.
