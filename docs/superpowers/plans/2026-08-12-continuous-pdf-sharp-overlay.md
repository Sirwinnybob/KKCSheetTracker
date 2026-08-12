# Continuous PDF Sharp Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep zoomed continuous PDF content sharp after the idle timeout swaps to the dark PDF source.

**Architecture:** Keep the transformed LazyColumn for PDF layout, scrolling, and gestures. Publish completed crops with screen-space bounds, then draw them in a sibling overlay outside the parent `graphicsLayer` so they are not scaled a second time.

**Tech Stack:** Kotlin, Jetpack Compose, Android `PdfRenderer`, JUnit 4, Gradle.

## Global Constraints

- Continuous PDF mode only; paged PDF mode is out of scope.
- Preserve source selection, crop pixel budget, gestures, scroll physics, and base/thumbnail behavior.
- Draw a crop only for its current source variant and while it overlaps the pane viewport.
- Add no dependencies or services.
- Verify focused JVM tests, a release build, and in-place signed release install.

---

### Task 1: Compose sharp crop outside transformed content

**Files:**

- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt`

**Interfaces:**

- Produces: `ContinuousCropOverlayBounds` and `resolveContinuousCropOverlayBounds(...)`.
- Consumes: transformed page bounds and `PdfRenderEngine.renderCropFraction` output.

- [ ] Write a failing test: a page spanning `(-2000,-500)` to `(4000,4000)` clips to the `1718x2766` viewport as bounds `(0,0,1718,2766)`.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"`; expect missing type/resolver.
- [ ] Add the bounds resolver, publish completed crops with identity and clipped bounds, remove crop drawing from `PageBitmapLayers`, and draw only current crops from a sibling unscaled overlay using `ContentScale.FillBounds`.
- [ ] Clear overlay crops on source swap, viewport exit, and reset to minimum zoom; retain `PdfRenderTrace` logging.
- [ ] Re-run the focused test, then `./gradlew.bat :app:assembleRelease`, `adb install -r app/build/outputs/apk/release/app-release.apk`, and `git diff --check`.
- [ ] Commit only the viewer implementation and focused test with message `fix(viewer): compose sharp PDF crops outside zoom layer`.
