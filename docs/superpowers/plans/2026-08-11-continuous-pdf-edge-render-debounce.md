# Continuous PDF Page Visibility Rendering Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Start a continuous-PDF page's full base render after it has remained on screen for 300 ms, even during slow scrolling or lingering fling momentum.

**Architecture:** Keep buffered `renderWindow` for immediate thumbnails, but derive an unbuffered visible-page range for full-quality rendering. Each page owns a cancellable 300 ms visibility dwell effect. The existing global settled gate remains only for zoom crop re-renders, so page base renders no longer wait for global motion to stop.

**Tech Stack:** Kotlin, Jetpack Compose, Kotlin coroutines, Android `SystemClock`, JUnit 4 JVM tests, Gradle Android build.

## Global Constraints

- Continuous PDF mode only; do not change paged PDF mode or other viewer screens.
- No dependencies, services, or configuration changes.
- Thumbnails remain immediate and buffered; a full base decode starts only after its page itself has been visible for 300 ms.
- Leaving the visible range before 300 ms cancels eligibility; slow scrolling and active fling do not otherwise delay it.
- Preserve existing zoom crop settling behavior and all engine/cache, navigation, and fling physics.

---

### Task 1: Replace global base-render settle with per-page visibility dwell

**Files:**

- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt`

**Interfaces:**

- Produces: `internal fun hasContinuousPdfPageRenderDwelled(visibleSinceMillis: Long?, nowMillis: Long): Boolean` using an exact `300L` ms dwell threshold.
- Consumes: existing `LazyListState.layoutInfo.visibleItemsInfo` for an unbuffered visible page range; existing buffered `renderWindow` remains the thumbnail window.
- Produces: page-local `baseRenderEligible` state reset on page invisibility and set only after a completed dwell.

- [ ] **Step 1: Write failing boundary tests**

Add JVM tests with literal timestamps:

```kotlin
@Test
fun continuousPdfPageRenderDwell_requiresFull300Milliseconds() {
    assertFalse(hasContinuousPdfPageRenderDwelled(visibleSinceMillis = 1_000L, nowMillis = 1_299L))
    assertTrue(hasContinuousPdfPageRenderDwelled(visibleSinceMillis = 1_000L, nowMillis = 1_300L))
}

@Test
fun continuousPdfPageRenderDwell_isIneligibleWithoutVisibleStartTime() {
    assertFalse(hasContinuousPdfPageRenderDwelled(visibleSinceMillis = null, nowMillis = 2_000L))
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"
```

Expected: compilation fails because `hasContinuousPdfPageRenderDwelled` is missing.

- [ ] **Step 3: Implement per-page dwell eligibility**

1. Remove the global motion-revision helpers introduced by commits `a5c2cde` and `9ee26bd`; restore the original 120 ms global `settled` effect for crop rendering only.
2. Derive `visiblePages` from `visibleItemsInfo` with no buffer. Keep `renderWindow` with its existing one-page buffer for thumbnails.
3. Add `hasContinuousPdfPageRenderDwelled`, returning true only when `nowMillis - visibleSinceMillis >= 300L`.
4. Inside each page composable, maintain `baseRenderEligible` and `visibleSinceMillis`. On `displayPage` entering `visiblePages`, capture `SystemClock.uptimeMillis()`, wait 300 ms, confirm the page is still visible and the dwell helper is true, then mark it eligible. Reset/cancel on exit.
5. Gate the base-page `LaunchedEffect` on `baseRenderEligible` instead of shared `settled`. Keep thumbnail effects on buffered `inWindow`; keep crop effects on restored global `settled`.

- [ ] **Step 4: Verify GREEN and build**

Run each command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"
.\gradlew.bat assembleDebug
git diff --check
```

Expected: focused tests and debug build succeed, with no whitespace errors.

- [ ] **Step 5: Commit only the implementation and focused tests**

Run:

```powershell
git add -- app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt
git commit -m "fix(viewer): render visible PDF pages after dwell"
```

Expected: the new commit supersedes the earlier motion-debounce implementation without staging unrelated work.
