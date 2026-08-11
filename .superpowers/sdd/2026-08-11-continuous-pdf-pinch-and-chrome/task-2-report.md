# Task 2 report — continuous PDF chrome overlay

## Status

Implemented and committed Task 2 on `codex/continuous-pdf-pinch-chrome`.

## Exact changes

- `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt`
  - Passed the Scaffold `padding` only when paged mode is active:
    `Modifier.fillMaxSize().then(if (continuousScrollEnabled) Modifier else Modifier.padding(padding))`.
  - Continuous mode therefore remains under the transparent/fading top app bar without a layout inset; paged mode keeps its existing Scaffold inset.
- `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt`
  - Replaced the continuous-mode `Column`/weighted pane with a full-size `Box` padded by `innerPadding`.
  - Kept `ContinuousReferencePdfPane` as the first/background child and retained its `hazeSource`.
  - Removed the continuous pane's end padding reserved for the idle scrollbar width.
  - Moved the existing document controls/page label/sheet-list control into a top-centered overlay row.
  - Kept `PdfLabelScrollbar` as a sibling overlay aligned to `Alignment.CenterEnd`, preserving all existing arguments and haze consumer wiring.
  - Left the paged `ReferencePdfPane` branch unchanged.

## Commands and results

- `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"`
  - Failed at `:updater-agent:testDebugUnitTest`: the root task applies the app test filter to updater-agent, which has no matching test (`No tests found for given includes`).
- `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"`
  - Passed (`BUILD SUCCESSFUL`).
- `.\gradlew.bat assembleDebug`
  - Passed (`BUILD SUCCESSFUL`).
- `.\gradlew.bat testDebugUnitTest`
  - Passed (`BUILD SUCCESSFUL`) for both app and updater-agent unit-test tasks.
- `git diff --check`
  - Passed with no whitespace errors.
- `git status --short`
  - Before commit: only the two assigned viewer source files were modified (the report is also included in this task commit).

No ADB install or device verification was run; the task brief assigns that check to the controller.

## Self-review

- Continuous content now occupies the full available pane beneath overlay chrome, so top-bar visibility changes do not resize or re-render around a reduced layout viewport.
- The PDF remains the haze source and the scrollbar remains its sibling consumer; no self-referential source/effect nesting was introduced.
- Header controls and scrollbar remain above the PDF in the Box child order and retain their prior callbacks and state.
- Paged mode still passes Scaffold padding and retains its existing pane layout.

## Concerns

The exact root-level focused test command is not usable with this multi-module Gradle setup because the updater-agent module has no test matching the app class filter. The app-scoped equivalent passes, and the unfiltered root unit suite passes.
