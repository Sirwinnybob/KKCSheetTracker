# Cabinet-front implementation and verification

Date: 2026-10-16. Implements the amended cabinet-front plan, including Tasks 6b and optional 8b.

Implementation is committed on `codex/cabinet-front-fullscreen` in both KKCSheetTracker and CV Cutlist Query. The existing uncommitted work was first preserved on main as requested: KST `2de36b5`, exporter `cfe46f9`. The Hours Tracker ownership audit is committed separately as `00df4c70`. No Ready Jobs metadata, OTA updates, or CV catalog records were changed.

## What ships

- CV Cutlist Query saves the exact live v1.3 UCS reference, validates all cabinet notes and report frame parts, and publishes additive schema-v2 `cabinetLayouts`. Unsupported geometry omits the entire cabinet. Cabinet 15's sloped frame is independently proved; duplicate opening claims and every nonzero tilt reject. Job 592 stile extensions are allowed only through the reported toe, with matching cut-list lengths.
- KKCSheetTracker reads layouts, preserves hidden fronts' actual shapes, fits face/front/member bounds, paints neutral context at 45% alpha, and draws the selected door with its own construction and grain. Unproved fronts remain blank. Cabinet/door-only controls, front navigation, frame inspection and zoom/pan/reset are integrated.
- The cabinet panel measures actual reveals, overhangs, overlays, insets and nearest neighbor gaps. Angled or unproved fronts have no invented measurements. Grain-orientation checks add warnings only; `_HGRAIN` remains the source.
- The canonical metadata map and Hours Tracker audit describe the single writer and read-only consumer.

## Verification evidence

- `cargo test`: **104 passed**, including 32 cabinet-layout tests and 14 door-geometry tests. Regression tests were observed failing before fixes for note parsing, cabinet validation, slope proof, toe extensions, publication, grain warnings, nonzero tilt and duplicate opening claims.
- `cargo build --release`: passed. Executable: `C:\Scripts\CV Cutlist Query\target\release\cv-cutlist-query.exe`.
- A fresh read-only `job-data --out` export of the currently open CV job **592** produced **128 doors and 33 cabinet layouts**, schema 2, with no grain-orientation warnings. Output was saved under the Windows temporary directory, not Ready Jobs.
- Job 690 fixture: **13 layouts**, including cabinet 15 at **36.6897 degrees**. Changing its top-rail length, origin or stile lengths rejects it; doorless sloped openings remain empty.
- Android parser/door-preview unit tests: **119 passed**. `compileDebugAndroidTestKotlin`, debug APK build and signed release APK build passed.
- Full Android suite: **2,089 passed, zero failures/errors** on the final run. The first full run had timing failures in `HardwoodsProgressStoreTest.compactionDuringPendingAsyncSaveFiltersInMemoryButKeepsRawHistoryOnDisk` and `HotStackAggregatorTest.liveCaptureSkipsCallerAndMainAndSeesARunnableThread`; both passed individually and in the final full run. Their code and assertions were not changed.
- Connected **SM-T738U, Android 13**, release build: **8/8 fullscreen instrumentation tests passed**, including the toggle, measured overlay panel, door switching, frame cut/final sizes, sloped cut-size-only inspection, and rejection of taps above the sloped stile. The two screenshot checkpoint tests also passed separately.
- Screenshots were inspected together: `C:\Users\chadc\.codex\visualizations\2026\10\16\01a14525-4a0f-7b21-8f40-ed82bd788a54\cabinet9-preview.png` and `cabinet15-preview.png`. They show the selected wood door, grey context, exact overlay and angled outline.
- Release instrumentation used a temporary Gradle init script to supply the existing Compose test-host dependency. The ordinary signed release APK was then rebuilt, installed with `adb install -r`, and launched. App version remains **8.9.0**; first install time remains **2026-05-12**, confirming replacement preserved the installed app. The required `leaveApksInstalledAfterRun=true` stayed enabled. No uninstall or data clear was performed.

## Field checklist from Task 9

`FAIL / pending` means that exact field operation is still outstanding; automated or fixture evidence is identified separately.

| Check | Result | Evidence / remaining work |
|---|---|---|
| 1. UCS installed with DOR and DWR | FAIL / pending exact settings verification | Live UCS ID 20180 is enabled, Add, Part ID, with the exact v1.3 code. Its old name is `Copy of Java  AI  Hinge Location Notes`. SQL reports an empty `LibraryPart` and wildcard assembly, so the requested DOR/DWR selection was not claimed verified or silently edited. Current job 592 reports do contain both notes. |
| 2. Regenerate job 690 and export | FAIL / pending | CV currently has job 592 open. It was read and exported to a temporary file. Job 690 was not opened/regenerated or published to the share. |
| 3. 690 export has 13 layouts and cabinet 15 slope | PASS for saved real-data fixture; FAIL / pending refreshed field export | Fixture tests and serialized job-data tests prove 13 layouts and slope 36.6897. A refreshed live 690 export is still needed. |
| 4. Tablet cabinet 9 and cabinet 13 | PASS cabinet 9; FAIL / pending cabinet 13 field screen | Release tablet tests verified cabinet 9's toggle, overlay and stile cut/final sizes. Cabinet 13's actual void is retained by fixture tests, but its field screen was not opened. |
| 5. Cabinet 15 doors 1A, 1B, 1C | PASS automated shape and 1A tablet checks; FAIL / pending complete field walkthrough | 1A rendered as a trapezoid on the tablet; cut-off stile taps did nothing. Parser tests keep 1B/1C rectangular and independent of cabinet slope. The full three-door walkthrough on a regenerated live job remains. |
| 6. Tampered top-rail copy falls back to door-only | PASS automated contracts; FAIL / pending field-copy walkthrough | Rust mutations omit the cabinet, and tablet tests show no toggle without a layout. No live-share notes were changed. The exact copied-job manual workflow remains. |
| 7. Straight/differently angled door and doorless sloped opening created in CV | PASS automated shape/empty-opening tests; FAIL / pending CV construction and re-export | Independent door/cabinet angles, refused fronts, missing door rows and empty sloped openings have regression coverage. New CV examples were not constructed. |

## Rulings and corrections

1. **One front per opening is enforced.** An early interpretation that paired doors shared a tag was wrong and was withdrawn. Both fixtures give each front a distinct opening tag. A regression now rejects duplicate claims. Cost of the corrected rule: unsupported future CV constructions stay door-only until evidenced.
2. **Use absolute 0.002-inch differences**, rather than rounding matching keys to three decimals. This follows the global tolerance and avoids rounding-boundary rejection; identical matching parts are assigned in stable part-ID order. Angles do not use an inch tolerance: plain rotations and zero tilt must be exact, or the TR slope must be proved. Cost: unsupported approximate rotations are omitted.
3. **The sloped-door spec and Task 3b supersede the stale deferred-slope paragraph.** Verified cabinet slopes ship now. Cost: the refreshed job 690 field export still needs verification.
4. **Locate the UCS by code/header**, because the proposed name predicate found no row. The exact live code was copied without changing CV. Cost: the entry's deployment settings require the remaining field check.
5. **Fixture geometry governs contradictory prose.** Cabinet 13 has four members and a void at x=0..11.25, not an MR; cabinet 17 tests the MR case. Cabinet 15 has one opening touching the sloped top rail. Job 592 also has no real frame members in cabinet 62, in addition to 20/66/67. Cost: these corrections need confirmation on the corresponding field screens, not invented pieces.
6. **Retain hidden doors for shape resolution, separately from selectable doors.** This prevents an angled hidden door from becoming an invented rectangle. Cost: callers must use the visible list for navigation; tests cover this split.
7. **Fit member bounds as well as face/front bounds**, as required by the toe amendment. Cost: a long stile can reduce the initial drawing scale; zoom remains available.
8. **Use the theme neutral for grey context**, at the specified 45% alpha, instead of the plan recipe's wood member color. Cost: only the displayed tablet theme was visually checked.
9. **Panel grain comparisons include published grooves** around the visible opening, as required by the amendment's test case. Missing groove data skips the independent check. Cost: some older exports cannot be cross-checked; their grain flags stay unchanged.
10. **Use temporary release instrumentation configuration**, then restore the ordinary APK, to preserve release signing and app data. Cost: the test-host build is unsuitable as a rollout APK; it was replaced after tests.
11. **Review inline**, honoring the user-provided Light-route prohibition on subagents. The final pass checked all listed Review Focus items and fixed the two validation gaps using failing regressions. Cost: this is an author review, without an independent reviewer.

No deferred minor code findings. The remaining work is the field checklist above and normal release integration/publication.

## Repeating the release UI tests

The normal test configuration targets debug. To test without replacing a shop tablet's release signing key, save this temporary Gradle init script outside the repository:

```groovy
allprojects { p ->
    p.plugins.withId('com.android.application') {
        p.android.testBuildType = 'release'
        p.dependencies.add('releaseImplementation', 'androidx.compose.ui:ui-test-manifest')
    }
}
```

Set `ANDROID_SERIAL` to the intended tablet, then run `gradlew.bat --init-script <that-file> connectedReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.kkc.sheettracker.ui.doorpreview.DoorPreviewFullscreenTest`. Keep `android.injected.androidTest.leaveApksInstalledAfterRun=true`. Afterward run `gradlew.bat assembleRelease` without the init script, install the ordinary release APK with `adb -s <serial> install -r`, and launch the app. Never uninstall or clear data to resolve a signature mismatch.
