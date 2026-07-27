# Sheet Rip tallies final fix report

## Status

Final review findings are fixed in the authorized `main` checkout. The commit containing this report is:

- `fix: preserve sheet rip tally ordering`

No APK was installed, deployed, copied, or uninstalled.

## Red evidence

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.data.HardwoodsProgressStoreTest --tests com.kkc.sheettracker.data.SpecialtyStateStoreTest --tests com.kkc.sheettracker.data.SheetRipProgressStoreTest
```

Result: expected failure with 40 tests run and 4 regressions failing:

- absent canonical zero did not persist;
- an above-target count of 9 with target 2 decremented to 2 instead of 1;
- Specialty could not clear a legacy-only completion into an explicit canonical zero;
- an older queued Hardwood Boolean projection overwrote a newer Specialty clear.

The legacy-only decrement API test was then added separately and failed compilation as expected because `fallbackDoneCount` did not yet exist.

## Green evidence

Focused command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.data.HardwoodsProgressStoreTest --tests com.kkc.sheettracker.data.SheetRipTallyTest --tests com.kkc.sheettracker.data.SpecialtyStateStoreTest --tests com.kkc.sheettracker.data.SheetRipProgressStoreTest --tests com.kkc.sheettracker.ui.hardwoods.HardwoodsRowHelpersTest --tests com.kkc.sheettracker.ui.specialty.SpecialtyJobDetailScreenLogicTest
```

Result: `BUILD SUCCESSFUL`.

Full unit-test evidence:

- `app/build/test-results/testDebugUnitTest`: 90 suites, 558 tests, 0 failures, 0 errors, 0 skipped.
- A serialized `.\gradlew.bat :app:testDebugUnitTest --no-daemon` verification completed with `BUILD SUCCESSFUL`.
- `git diff --check` produced no errors.

One forced rerun overlapped an earlier timed-out Gradle client and hit a Windows `classes.jar` file lock. Gradle status then showed only idle daemons; the serialized no-daemon verification completed successfully. This was build-process contention, not a test failure.

## Files

- `app/src/main/java/com/kkc/sheettracker/data/HardwoodsProgressStore.kt`
- `app/src/main/java/com/kkc/sheettracker/data/SheetRipProgressStore.kt`
- `app/src/main/java/com/kkc/sheettracker/data/SpecialtyStateStore.kt`
- `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`
- `app/src/test/java/com/kkc/sheettracker/data/HardwoodsProgressStoreTest.kt`
- `app/src/test/java/com/kkc/sheettracker/data/SheetRipProgressStoreTest.kt`
- `app/src/test/java/com/kkc/sheettracker/data/SpecialtyStateStoreTest.kt`

## Rationale

- Canonical zero is now persisted when the tally key is absent, so it overrides a legacy-only `true` even if the Boolean projection is delayed or fails.
- Admin tally adjustment accepts the resolved fallback, clamps the current count to the target before applying delta, and persists every effective change so `progressVersion` refreshes the UI.
- Saw Sheet decrements pass the already-resolved tally count as the absent-canonical fallback.
- Every Boolean compatibility write receives a per-item revision. Specialty reserves its revision before switching to the IO dispatcher, and the store rejects older delayed projections.
- Specialty version increments use atomic `MutableStateFlow.update`.
- Boolean tests now prove a `false` entry is explicitly stored, not merely absent.

## Constraints retained

- Sheet tally controls remain Saw-only.
- Regular Hardwood Sheet rows remain display-only.
- Sheet rows still expose no material or item Skip controls.
- Specialty remains checkbox-only.
- `sheet_rip_done.json` remains the Android-owned Boolean compatibility file; no progress file was added.
- Live tablet smoke testing was not performed because installation/progress mutation was outside this fix wave.
