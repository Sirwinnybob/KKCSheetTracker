# Task 3 report — hidden Jobs work and specialty availability IO

## Result

Completed the hidden Jobs work gate and specialty availability IO move.

- `UnifiedJobsScreen` exposes `shouldRunUnifiedJobsBackgroundWork(active)` and keys the
  foreground refresh and all badge effects by `active`; inactive hidden Jobs hosts remain
  composed but do not refresh or resolve badges.
- Specialty badge filesystem reads run inside `withContext(Dispatchers.IO)`.
- `SpecialtyAvailability` provides the immutable five-field result, a pure resolver that invokes
  each supplied check once, and the blocking `JobRepository` adapter.
- Both specialty route copies load availability with `produceState` on `Dispatchers.IO` and pass
  the resulting fields to `SpecialtyJobDetailScreen`.

## Verification

- TDD RED: the two new focused tests failed to compile because the requested helpers/result were
  absent (`Unresolved reference`).
- Focused GREEN suite:
  `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.SpecialtyAvailabilityTest" --tests "com.kkc.sheettracker.navigation.SpecialtyRouteTest" --tests "com.kkc.sheettracker.ui.jobs.UnifiedJobsScreenTest"`
  — `BUILD SUCCESSFUL`.
- Kotlin compile:
  `.\gradlew.bat :app:compileDebugKotlin` — `BUILD SUCCESSFUL`.
- `git diff --check` — clean.

## Scope

Changed only the six assigned source/test files plus this report.
