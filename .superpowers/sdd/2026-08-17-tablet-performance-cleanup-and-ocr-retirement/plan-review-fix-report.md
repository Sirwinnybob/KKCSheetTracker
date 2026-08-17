# Whole-plan review fix wave: label loading gate

## Finding addressed

`UnifiedJobsScreen` loaded the full label catalog from `listAllLabels()` on initial composition and on every `scanGeneration` change, even when its NavHost tab was inactive. The effect now includes `active` in its key and exits before the metadata-engine call when inactive.

## TDD evidence

### RED

Added `labelLoadingRunsOnlyForTheActiveTab` to `app/src/test/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreenTest.kt` before the production gate existed.

Command:

```text
.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.jobs.UnifiedJobsScreenTest
```

Result: failed as expected during test compilation with two unresolved references to `shouldLoadUnifiedJobsLabels` (test lines 16 and 17). This demonstrated the focused regression assertion was exercising the missing production contract.

### GREEN

Implemented the smallest production change in `UnifiedJobsScreen.kt`:

- Added `shouldLoadUnifiedJobsLabels(active: Boolean)` as the focused active-tab gate.
- Changed the label-loading effect key from `(basePath, scanGeneration)` to `(active, basePath, scanGeneration)`.
- Returned from the effect before `listAllLabels()` when `active` is false.

Command:

```text
.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.jobs.UnifiedJobsScreenTest
```

Result: `BUILD SUCCESSFUL`; focused test task completed with no failures.

## Required compile verification

Command:

```text
.\gradlew.bat :app:compileDebugKotlin
```

Result: `BUILD SUCCESSFUL` (7 actionable tasks up-to-date).

## Diff and ownership review

`git diff --check` completed successfully (only Git's existing LF-to-CRLF working-copy warnings were emitted).

Intended changed files:

- `app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt`
- `app/src/test/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreenTest.kt`
- This report

No NavHost, dependency, OCR, badge/refresh, offline-flow, or unrelated behavior was changed. Active-tab behavior remains unchanged: active screens still load labels and refresh; inactive screens skip this label metadata I/O. No concerns remain within the assigned scope.
