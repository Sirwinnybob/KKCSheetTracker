# Continuous PDF Viewer CPU Leak Fix Implementation Plan

## 2026-09-17: Bug B — MultiBackStackNavigation idle-CPU (debug-build-only, separate from the fix below)

This is a second, structurally different bug found while sweeping other screens for the search-publication
pattern below. It only affects `MultiBackStackNavigation` (`AppStateFeatureFlags.navMultiStackEnabled`,
default `isDebugBuild` — **production tablets use `LegacySingleStackNavigation` and never hit this**).

- Repro: fresh launch, sit idle on Dashboard, zero navigation. Under `MultiBackStackNavigation`,
  main-thread CPU sustained 70-100% (`adb shell top -H -p <pid>`, 10 samples over 30s). Under
  `LegacySingleStackNavigation` the same idle state is 0.0%.
- jdb thread-dump sampling (used successfully for the bug above) hit its limits here: every sample landed
  in generic Compose-runtime bookkeeping (`GapComposer`, `Recomposer`) with zero `com.kkc.sheettracker`
  frames, across repeated attempts — likely JIT-inlined frames jdb can't unwind at 120Hz. Switched to
  Android Studio's CPU Profiler (Callstack Sample / simpleperf), same tool and methodology as the fix
  above, parsed with the same `cpu-clock`-only filtering approach (`tmp/pbgen/parse_trace3.py`, extended
  from `parse_trace2.py` to auto-pick the main thread and scan full callchains for app-code frames).
- Root cause: `TabLayer` (`NavGraph.kt`, `MultiBackStackNavigation`) called `content()` unconditionally for
  all 8 tabs (Dashboard/Jobs/Search/Hours/Timecard/Settings/Standards/Supply) every frame, only hiding
  inactive tabs via `.alpha(0f)`/`.zIndex(0f)`. All 8 tabs' NavHosts and screens stayed fully composed,
  laid out, and drawn simultaneously regardless of which tab was visible — 8x the recomposition/layout/draw
  and Android's per-View frame-rate-voting (`View.setRequestedFrameRate`, which on this device's OS build
  internally calls `Debug.getCallers()` and is expensive) work that `LegacySingleStackNavigation` does with
  its single active `NavHost`. This produced a diffuse "death by a thousand cuts" profile — no single
  dominant app-code hotspot, just Compose-runtime bookkeeping repeated 8x per frame — which is exactly why
  jdb's coarse single-frame sampling never caught it.
- Fix: only compose the selected tab. Added a shared `rememberSaveableStateHolder()` in
  `MultiBackStackNavigation` and changed `TabLayer` to `if (visible) { stateHolder.SaveableStateProvider(tabKey) { content() } }` instead of always calling `content()`. This is Compose's documented pattern for
  exactly this case (tabs added/removed from composition that need `rememberSaveable` state — scroll
  position, expanded rows, etc. — preserved across the swap). Each tab's own `NavHostController` is hoisted
  above `TabLayer` (`rememberNavController()` at the top of `MultiBackStackNavigation`) and is unaffected
  either way, so each tab's navigation position (which screen/route it's on) was never at risk.
- Verification: same CPU Profiler capture, same idle-Dashboard repro, after the fix. Main thread's on-CPU
  samples dropped from 8502 to 23 in the profiler trace; a follow-up `top -H` sample showed a flat 0.0%
  main-thread CPU for 24s straight (TIME+ column frozen, not just a low percentage). Debug and release
  builds both compile; full unit suite passes.
- The only remaining background activity of note is `TrackerChangeMonitor.pollOnce`/`pollSignaturesLocked`
  on a `DefaultDispatch` (`Dispatchers.IO`) thread — that is by-design periodic file-watcher polling, a
  single shared instance regardless of nav host (`NavGraph.kt:245`), not on the main thread, and not part
  of this bug.
- Not independently re-verified: manual tab-switch-and-back state preservation (scroll position, etc.) on
  the physical tablet — the `SaveableStateHolder` mechanism is Compose's standard solution for this and the
  unit suite passed, but a live spot-check (scroll a list on one tab, switch away, switch back) would be
  worth doing before calling this fully closed for interactive use, not just idle CPU.

## 2026-09-17: extended sweep — same search-publication pattern found in 7 more locations

After the fix below landed for `UnifiedJobsScreen`, a sweep for the identical anti-pattern (a `SideEffect`
publishing a freshly-allocated `data class`-or-plain-lambda-bearing decoration into
`NavBarDecorationState` every recomposition, which then self-feeds because something reads that same
state) found and fixed the same bug in 7 more screens, all via the same `remember(keys)` fix pattern:
`SupplyDashboardScreen`, `MoldingListScreen`, `ArchiveLibraryScreen`, `AssemblyViewerScreen`,
`SpecialtyJobDetailScreen`, `UnifiedReferenceViewer`, and `SheetViewerScreen` (the last of these included a
plain, non-data-class `extendedControls` lambda that was initially and incorrectly ruled out of scope as "a
lambda, not a data class" — raw Kotlin function types also compare by reference, not structurally, so the
identical bug mechanism applies; caught and fixed in a final sweep). `ClassicCutListTable` was checked and
found already safe (uses `DisposableEffect` with an explicit, complete key list). Debug/release builds and
the full unit suite (`testDebugUnitTest`) passed after all fixes.

## 2026-09-17: confirmed search-publication feedback loop

This finding supersedes the animation, PDF coroutine, and profiler-location theories below.

- On the connected SM-X800 running Android 16 / app 8.4.81, the stuck process measured 58.6% RenderThread and 44.8% main-thread CPU.
- Temporary instrumentation in `NavBarDecorationState.searchDecoration` counted unequal publications. With unchanged search text, all three callback identities changed; publications increased by 120 approximately every second. The captured writer stack was `UnifiedJobsScreen`'s `SideEffect`, not the PDF viewer or `BasicTextField`.
- `NavBarSearchDecoration` is a data class, so newly allocated callbacks make each publication unequal. The Jobs screen reads this same state for bottom padding, and the navigation overlay reads it for rendering. Publishing a fresh decoration from every side effect therefore invalidates its readers and feeds the next composition. The diagnostic build reproduced this merely by opening Jobs, as well as after the PDF/back sequence; scrolling is not a necessary trigger for this loop.
- Fix: extract the Jobs search publication into `JobsSearchNavBar`, remember the decoration by the complete `TextFieldValue`, and retain callback wrappers that delegate through `rememberUpdatedState`. Keep owner-checked cleanup. No debounce, animation removal, nav transition change, or PDF renderer change is needed.
- `JobsSearchNavBarTest` uses a real Compose `Composition` / `Recomposer` with a controlled frame clock. Before the fix, 12 idle frames produced 12 additional compositions (13 -> 25). After the fix, the same check settles and also verifies search edits, current callbacks, unrelated recompositions, deactivation/reactivation, and disposal.
- First live confirmation: open Plans & Elevations, switch to continuous mode, scroll, Back twice. Main and RenderThread CPU both returned to 0.0%; rendered frames remained exactly 390 across a five-second idle interval. Temporary publication logs also stopped increasing.
- Final APK, with diagnostics removed: job 644d continuous scroll (416 -> 416 frames), job 106 paged navigation (627 -> 627), job 664 continuous scroll (907 -> 907). Each five-second idle check had 0.0% main-thread and RenderThread CPU. Searching for `664` showed one matching job; clearing restored all 14 jobs and remained idle.
- Longer idle observation still showed intermittent redraws, not a permanently frozen frame counter: 1,189 -> 1,273 frames over 35.5 seconds, with only 0.39 seconds of main-thread CPU and 0.68 seconds of RenderThread CPU accumulated. Subsequent samples were 0.0-1.5%. The sustained 120Hz feedback loop is gone; this observation does not establish that all background-triggered redraws have been eliminated.
- Debug and release builds passed. The full unit suite passed with 1,130 tests / zero failures on rerun. The initial full run had one failure in the unrelated `HardwoodsProgressStoreTest.compactionDuringPendingAsyncSaveFiltersInMemoryButKeepsRawHistoryOnDisk` (expected both rows on disk, observed only `row-stale`); it passed in isolation and in the complete rerun, without changes to that code or test.
- Assembly split view and the hardwood workspace were not independently exercised in this session. This fix addresses the demonstrated Jobs publication loop; it does not claim to eliminate every possible rendering issue in those viewers.

The pre-existing local `NavGraph.kt` edit forcing `LegacySingleStackNavigation` was present at session start and retained. Device verification uses that production-default navigation path. The installed APK used the local Android debug certificate despite being non-debuggable; matching that key allowed `adb install -r` without uninstalling or clearing app data.

Historical investigation follows; do not apply the invalidated Task 3 mitigation.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the app from getting stuck rendering at full display refresh rate (confirmed 120Hz, zero gaps) forever after a user scrolls the "Plans & Elevations" / assembly reference PDF viewer (`ContinuousReferencePdfPane`) and navigates back — the #1 confirmed driver of "slow tablet" complaints, reproduced on a Galaxy Tab S8 (a *fast* device), not just low-end units.

**Architecture:** This is a live-device bug (confirmed via `adb shell dumpsys gfxinfo` framestats, not reproducible from code reading alone), so the plan is diagnose-on-device-first, then fix. Task 1 adds temporary lifecycle logging to the exact suspect composable and its coroutine scopes, verified against the physical tablet, to conclusively prove whether the pane's coroutines survive navigation. Task 2 interprets that log and locks in the finding. Task 3 applies the fix to whichever gap Task 1/2 exposed, using the mechanism identified. Task 4 removes the temporary instrumentation. Task 5 is the on-device regression check (this class of bug has no meaningful JVM/Robolectric equivalent — it is real Android `Choreographer`/`RenderThread` behavior).

**Tech Stack:** Kotlin, Jetpack Compose (`LaunchedEffect`, `DisposableEffect`, `rememberCoroutineScope`), Android `Log`, ADB (`dumpsys gfxinfo`, `top -H`, `logcat`) against a connected physical tablet.

**Confirmed repro (validated 3x on a Galaxy Tab S8, release build):**
1. Launch app fresh (baseline: `RenderThread` / main thread both 0.0% CPU, confirmed via `adb shell top -H -p <pid>`).
2. Jobs tab → open any job → tap **Plans & Elevations**.
3. Static viewing only (no scroll): still 0.0% CPU. Opening the viewer is not the trigger.
4. Scroll the document at all — fast swipe *or* slow drag, doesn't matter (fling velocity ruled out as a variable).
5. Press Back twice (viewer → job detail → Jobs list).
6. **From this point on, forever:** `RenderThread` 55-75%, main thread 35-45%, confirmed via `adb shell dumpsys gfxinfo com.kkc.sheettracker framestats` to be continuous full-refresh-rate rendering — `FrameInterval` column is a rock-steady ~8,358,300ns (≈120Hz) with **zero gaps**, frame after frame, with no user interaction. This is not periodic background work; it is a real, live, continuously-invalidating animation or redraw source that never stops until the app is force-stopped.
7. Memory (RSS) also climbs from a ~190MB fresh baseline to a 220-260MB oscillating plateau and never returns to baseline.

**UPDATE 2026-09-16, post-plan: root cause confirmed via live thread dumps (jdb attach against a debug build, `run-as`/`kill -3` was blocked on this device's ART so JDWP was used instead). Tasks 1-2 below (log-based diagnosis) are now superseded — skip straight to the Task 3 rewrite beneath this note.**

Two independent `where all` stack captures — one under `MultiBackStackNavigation` (debug build default), one under `LegacySingleStackNavigation` (the actual production path, forced by temporarily hardcoding the branch in `NavGraph.kt:494`) — both caught the main thread mid-recompose/mid-measure inside the **same nav-bar region**, live inside a real `Choreographer.doFrame` → `ViewRootImpl.performTraversals` call chain (i.e. genuine per-frame work, not a sampling artifact):

- Capture 1 (MultiBackStack): stuck inside `animateDpAsState` for `minIconSize` at [AppScaffold.kt:415](app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt:415), inside `MorphingNavBar`.
- Capture 2 (LegacySingleStack, the production path): stuck inside `TextFieldScrollerPosition.update()` → `setOffset` → `setFloatValue` (`TextFieldScroll.kt:227,320`) — Compose Foundation's internal "keep the cursor visible" horizontal-scroll mechanism for the `BasicTextField` at [AppScaffold.kt:544](app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt:544) (the nav bar's "Search jobs..." field) — **writing to its own snapshot state from inside its measure pass**, the classic trigger for a self-sustaining remeasure loop when whatever it's measuring against hasn't stabilized either.

Both landing spots live in the exact same decoration `Row` in `MorphingNavBar`: the icon-size spring (`animateDpAsState`, line 415) and the search `BasicTextField` (line 544) are siblings inside a container whose overall size is *also* spring-driven (`SizeTransform` at line 523, `NavSpringSize`). The working theory: leaving the reference viewer flips `minimized` (line ~1177 in `NavGraph.kt`, `isInViewer` going false) and re-establishes the search decoration (`UnifiedJobsScreen`'s `SideEffect`, [UnifiedJobsScreen.kt:205-225](app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt:205)) at the same instant — two springs in the same row settling simultaneously, with the TextField's internal scroll-offset recalculating on every one of those in-flight remeasures, and never reporting a stable size back up because it's *also* still mid-adjustment. Confirmed **not** a one-off slow settle: CPU stayed pegged for 3+ minutes of continuous observation on both nav-host implementations.

**Immediate, low-risk mitigation available today:** `iconSizeSpec` at [AppScaffold.kt:414](app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt:414) already reads `LocalLowEndMode.current.animationsDisabled` and falls back to `snap()` (no spring at all) when true. The already-shipped "Low-end device mode → disable animations" toggle in Settings should mask this bug entirely as a same-day stopgap for the worst-hit tablets — **this was not yet verified on-device before the session ended; verify first** (Task 3, Step 0) before telling shop floor staff to flip it.

**Ruled out during investigation (do not re-litigate these — re-verify only if Task 1's log contradicts the current read of the code):**
- Fling velocity / `documentFlingScope`'s `while (isActive && abs(vel) > 10f) { withFrameNanos {...} }` loop in [ContinuousReferencePdfPane.kt:1222-1260](app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt:1222) — ruled out because a slow drag with no fling reproduces identically.
- `TrackerChangeMonitor`'s background poll loop — its interval is a hardcoded 10s ([TrackerChangeMonitor.kt:499](app/src/main/java/com/kkc/sheettracker/data/TrackerChangeMonitor.kt:499)), far too slow to produce a zero-gap 120Hz render stream.
- `ClockInOverlay`'s `tabPulse` infinite animation ([ClockInOverlay.kt:206](app/src/main/java/com/kkc/sheettracker/ui/components/ClockInOverlay.kt:206)) — gated behind `snapshot.isActive` in [ClockInState.kt:75-79](app/src/main/java/com/kkc/sheettracker/data/ClockInState.kt:75), and the repro was performed while clocked out.
- `NavBarDecorationState.cncDecoration`/`searchDecoration` going stale — `UnifiedReferenceViewer.kt` (the actual host of the "Plans & Elevations" route) never sets `cncDecoration`/`searchDecoration` at all, only `extendedControls`/`penDecoration` for markup, and both are correctly cleared in its existing `DisposableEffect(navBarDeco)` at [UnifiedReferenceViewer.kt:702-709](app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt:702). `SheetViewerScreen.kt` (a *different* viewer, the CNC markup one) also clears `cncDecoration` correctly on dispose.
- All standard Compose coroutine scopes in `ContinuousReferencePdfPane` (`LaunchedEffect(scrollDeltaChannel, listState)`, `LaunchedEffect(listState) { snapshotFlow {...} }`, the `rememberCoroutineScope()`-backed `documentFlingScope` with its own `DisposableEffect { onDispose { documentFlingScope.cancel() } }`) read as idiomatically correct — no obvious "forgot to cancel" bug on inspection. This is exactly why Task 1 instruments and *proves* behavior on-device instead of guessing further from static reading.

---

### Task 1: Add temporary on-device lifecycle logging

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt:570-663`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt:702-709`

- [ ] **Step 1: Add entry/dispose logging to `ContinuousReferencePdfPane`**

In `ContinuousReferencePdfPane.kt`, add the import and a log at the top of the composable, then log every dispose path:

```kotlin
// add near the other imports at the top of the file
import android.util.Log
```

```kotlin
@Composable
internal fun ContinuousReferencePdfPane(
    modifier: Modifier = Modifier,
    orientation: Orientation,
    totalPages: Int,
    resolvePage: (Int) -> ResolvedPageSource,
    pdfFileForFilename: (String) -> java.io.File?,
    fileIdentitySeed: Long = 0L,
    docKey: Any? = null,
    preferDarkMode: Boolean = false,
    onCenteredPageChange: (Int) -> Unit,
    scrollToPage: Int,
    markupEnabled: Boolean = false,
    markupToolState: PdfMarkupToolState? = null,
    markupStrokesForPage: (sourceFilename: String, sourcePage: Int) -> List<PdfInkStroke> = { _, _ -> emptyList() },
    onMarkupStrokeAdded: ((sourceFilename: String, sourcePage: Int, PdfInkStroke) -> Unit)? = null,
    onMarkupStrokeErased: ((sourceFilename: String, sourcePage: Int, strokeId: String) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onSingleTap: (() -> Unit)? = null,
    onEdgeOverscrollChange: (Float) -> Unit = {}
) {
    val instanceId = remember { System.identityHashCode(Any()) }
    Log.d("PdfLeakDiag", "ContinuousReferencePdfPane COMPOSED instance=$instanceId")
    DisposableEffect(Unit) {
        onDispose { Log.d("PdfLeakDiag", "ContinuousReferencePdfPane DISPOSED instance=$instanceId") }
    }
    val documentIdentity = remember(fileIdentitySeed, totalPages, docKey, preferDarkMode) {
        resolveContinuousPdfDocumentIdentity(totalPages, resolvePage, pdfFileForFilename)
    }
```

- [ ] **Step 2: Log the fling loop and the centered-page collector**

Still in `ContinuousReferencePdfPane.kt`, find the fling loop (around line 1234, inside `listState.scroll(MutatePriority.UserInput) { ... while (isActive && abs(vel) > 10f) { withFrameNanos { frameTime -> ... } } }`) and add one throttled log line at the top of the `withFrameNanos` block:

```kotlin
listState.scroll(MutatePriority.UserInput) {
    var lastFrameNanos = System.nanoTime()
    var frameLogCounter = 0
    while (isActive && abs(vel) > 10f) {
        withFrameNanos { frameTime ->
            frameLogCounter++
            if (frameLogCounter % 30 == 0) {
                Log.d("PdfLeakDiag", "fling tick instance=$instanceId vel=$vel")
            }
            val dt = ((frameTime - lastFrameNanos) / 1e9f).coerceIn(0.001f, 0.05f)
            lastFrameNanos = frameTime
            // ...existing body continues unchanged below this point...
```

Find the centered-page collector (around line 775) and log inside `collectLatest`:

```kotlin
LaunchedEffect(listState) {
    snapshotFlow {
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        continuousCurrentPage(
            firstVisibleIndex = visible.firstOrNull()?.index,
            lastVisibleIndex = visible.lastOrNull()?.index,
            canScrollForward = listState.canScrollForward
        )
    }
        .distinctUntilChanged()
        .collectLatest { centeredPage ->
            Log.d("PdfLeakDiag", "centeredPage collector fired instance=$instanceId page=$centeredPage")
            if (centeredPage != null && !programmaticScrollGuard.isActive) {
                lastReportedPage = centeredPage
                currentOnCenteredPageChange(centeredPage)
            }
        }
}
```

- [ ] **Step 3: Log the host screen's own dispose**

In `UnifiedReferenceViewer.kt`, extend the existing `DisposableEffect(navBarDeco)` at line 702 to also log:

```kotlin
DisposableEffect(navBarDeco) {
    onDispose {
        Log.d("PdfLeakDiag", "UnifiedReferenceViewer host DISPOSED")
        if (ownsNavBarMarkupControls) {
            navBarDeco.extendedControls = null
            navBarDeco.penDecoration = null
        }
    }
}
```

Add `import android.util.Log` to `UnifiedReferenceViewer.kt` if not already present (check with `grep -n "import android.util.Log" app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt` first).

- [ ] **Step 4: Build and install a profileable release (needed for `am profile`/logcat parity with prior investigation, but NOT required for plain `Log.d` — logcat works on any release build)**

```bash
cd C:\Scripts\KKCSheetTracker
./gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

Expected: `BUILD SUCCESSFUL`, `Success` (install preserves app data — same signing key, no `-uninstall` needed).

- [ ] **Step 5: Commit the diagnostic build**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt
git commit -m "debug: add temporary lifecycle logging to diagnose continuous PDF viewer CPU leak"
```

---

### Task 2: Reproduce on the tablet and read the log

**Files:** none (device-only task)

- [ ] **Step 1: Clear logcat and launch the app**

```bash
adb logcat -c
adb shell am start -n com.kkc.sheettracker/.MainActivity
```

- [ ] **Step 2: Drive the exact repro via adb (matches the validated sequence)**

Get real bottom-nav/job-card coordinates first with a UI dump (coordinates drift between app states, do not hardcode blindly):

```bash
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml
grep -o 'text="Jobs"[^/]*bounds="\[[0-9,]*\]\[[0-9,]*\]"' ui.xml
```

Then, using the second (bottom-nav) match's center coordinates, tap through: Jobs tab → first job card → Plans & Elevations button (re-dump `ui.xml` after each navigation to get that screen's real bounds — do not reuse coordinates across screens). After the viewer opens:

```bash
adb shell input swipe 600 2000 600 500 300
sleep 1
adb shell input swipe 600 2000 600 500 300
sleep 1
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell input keyevent KEYCODE_BACK
sleep 3
```

- [ ] **Step 3: Confirm the leak is active**

```bash
PID=$(adb shell pidof com.kkc.sheettracker)
adb shell top -H -n 1 -p $PID -b | grep -E "RenderThread|kc.sheettracker "
```

Expected (if reproduced): `RenderThread` and the main thread row both show sustained non-zero `%CPU` (40%+) with no further interaction.

- [ ] **Step 4: Pull and read the diagnostic log**

```bash
adb logcat -d -v time | grep "PdfLeakDiag"
```

- [ ] **Step 5: Interpret the result and record the finding**

Read the last 10 lines of output. Exactly one of these two patterns will appear — write down which one in the plan file's Task 3 heading before proceeding (edit this plan file to note the finding under Task 3):

- **Pattern A — pane never disposes:** `ContinuousReferencePdfPane COMPOSED instance=X` appears once, but no matching `ContinuousReferencePdfPane DISPOSED instance=X` line ever appears, and `fling tick instance=X` or `centeredPage collector fired instance=X` lines **keep appearing** in logcat after step 2's two Back presses (watch logcat live with `adb logcat -v time | grep PdfLeakDiag` for 10 more seconds to confirm it keeps ticking). This proves the composable is retained past navigation — go to **Task 3A**.
- **Pattern B — pane disposes correctly:** `ContinuousReferencePdfPane DISPOSED instance=X` and `UnifiedReferenceViewer host DISPOSED` both appear shortly after the second Back press, and no further `fling tick`/`centeredPage collector` lines appear for that instance. This proves the leak is NOT in this composable's own coroutines — the continuous frame requests are coming from somewhere else in the always-mounted shell (`AppScaffold`/`LegacySingleStackNavigation`). Go to **Task 3B**.

---

### 2026-09-17 continued: re-verified against the correct nav host, two more hypotheses eliminated

**Important methodology correction first:** all of the 2026-09-17 elimination testing below this note was *initially* run against `MultiBackStackNavigation` (the nav host debug builds default to — `navMultiStackEnabled` defaults to `isDebugBuild`), not `LegacySingleStackNavigation` (the actual production default, `isDebugBuild=false`). Partway through, idle CPU on a fresh Dashboard (no navigation at all) was also found sustained at ~40% under `MultiBackStackNavigation` — a *second*, separate problem specific to that nav host, not investigated further here since production tablets don't use it by default. Every finding below was re-run after forcing `LegacySingleStackNavigation` (temporarily hardcoding the branch at `NavGraph.kt:494`, matching production) to confirm it against the real path. All temporary code (this hack, the debounce fix, every diagnostic build) was reverted via `git checkout --` before ending the session; the working tree is clean except this plan file.

Confirmed, on the correct nav host, with direct instrumentation (not stack-trace inference this time):

- **`UnifiedJobsScreen` itself recomposes ~100+ times/second, indefinitely, after returning from the reference viewer.** Added a `SideEffect` counter logging every 20th recomposition (`Log.d` timestamps 190ms apart = ~105 recompositions/sec) — this is a direct measurement, not inferred from a sampled stack trace.
- **None of its own tracked state caused it.** Instrumented `scanStatus`, `scanGeneration`, `progressVersion` (the only three `StateFlow`s `UnifiedJobsSpec` exposes) and `adminMode` (`AdminModeController.enabled`) to log on every distinct value change. All four went quiet 10+ seconds before the recompose storm was still measured running at full rate — ruling out every piece of state the screen itself collects. `cards` (the derived job list) is properly `remember`-keyed on exactly those same values, so it isn't the source either.
- **Re-ran the full Task 3 elimination chain (debounce fix, no animation, no `BasicTextField` at all) against the correct nav host — same result as before, still reproduces.** The original elimination wasn't invalidated by the wrong-nav-host mistake; it happened to hold on both hosts.
- **Ruled out interrupted/overlapping back-transitions.** Spaced the two Back presses 2+ seconds apart (well past the 300ms tween) instead of pressing them back-to-back — first Back (viewer → job detail) settles clean at 0%; it's specifically the second Back (job detail → jobs list) that triggers the storm, regardless of timing between the two presses.
- **Ruled out the exact bug class documented elsewhere in this codebase for the Standards routes.** [CLAUDE.md](CLAUDE.md) describes a known Haze/`AnimatedContent`-transition interaction, fixed for `standards`/`standards/molding`/`standards/safety`/`standards/archive` by giving those four routes `EnterTransition.None`/`ExitTransition.None`/etc. The `jobs` route never got that treatment (confirmed via `grep` — only the four Standards routes have the override; `jobs` and `referenceViewer` use the NavHost's default 300ms slide+fade). Applying the identical no-op-transition override to the `jobs` route was a natural, low-risk thing to try since it's a proven pattern in this exact codebase — but it did **not** fix this bug either (still 62-88% sustained over 30s with the override in place).

**Net finding:** the driver is real, direct, and measured (not a snapshot artifact this time), but its source remains unidentified after four separate, evidence-based hypotheses (nav-bar decoration/search field, animation timing, back-press transition interruption, and the Standards-style transition bug) were each tested live on-device and eliminated. Whatever is forcing `UnifiedJobsScreen` to recompose at ~100Hz is neither inside the composable itself nor in the four things it collects — it must be something about how the `jobs` destination is hosted/recreated on this specific transition (viewer → jobs, but not other transitions tested), that doesn't show up as a repeatable stack-trace location across samples (all four `where all` dumps taken during confirmed-active storms landed in different, unrelated-looking places: `MorphingNavBar`'s animation, `TextFieldScrollerPosition`, `HardwareRenderer.syncAndDrawFrame`, `Snapshot.dispose`, `Api35Impl.setRequestedFrameRate` — consistent with genuine full recompose+layout+draw cycles rather than one hot loop, which is why single-sample stack dumps kept pointing at whatever composable happened to be mid-flight rather than the actual cause).

**Recommended next step for whoever picks this up:** stop guessing candidate fixes and instrument differently — either (a) get a proper Android Studio CPU profiler session (method trace with full symbols, not `jdb`'s coarse `where all`) attached during the stuck window, which can show a flame chart across the whole 30-second window instead of single-frame snapshots, or (b) bisect by commenting out large chunks of `UnifiedJobsScreen`'s body (everything after the state declarations, replaced with an empty `Box`) to binary-search which section of the composable is the one Compose considers "needs remeasure" every frame — if even an empty body still storms, the recomposition scope itself (the whole function being invalidated on every frame by its caller) is the target, which points back at whatever recreates/re-invokes this destination's content lambda in `LegacySingleStackNavigation` around `NavGraph.kt:2901`.

### Task 3 — INVALIDATED 2026-09-17, do not implement as written below

Implemented and tested live on-device. The debounce fix did **not** resolve the leak (CPU still pegged 80%+ minutes after backing out). Escalated with three further isolation builds, each installed and re-tested against the real repro:

1. Forced `iconSizeSpec = snap()` **and** gave `AnimatedContent` an explicit instant `sizeTransform` (my first test had accidentally left Compose's animated default size transform in place even in the "disabled" branch) — **still reproduced.**
2. Replaced the `BasicTextField`'s `Modifier.weight(1f)` with a fixed `Modifier.width(600.dp)` — **still reproduced.**
3. Removed the `BasicTextField` entirely, replaced with a static `Text` — **still reproduced.**

Step 3 is conclusive: the nav bar's search decoration, the icon-size spring, and `AnimatedContent`'s size transform are **not** the cause. All of Task 3's reasoning (and the original finding that motivated this whole plan) was wrong — the two thread-dump captures that showed `animateDpAsState`/`TextFieldScrollerPosition` on the stack were coincidental snapshots of normal per-frame work, not evidence of where the *drive* to keep producing frames comes from. All temporary edits from this test were reverted (`git checkout -- app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt`); `AppScaffold.kt` is back to its pre-investigation state.

Post-elimination thread dumps (with the search field entirely gone) show a normal, *diverse* spread of real per-frame work — `HardwareRenderer.syncAndDrawFrame`, `Recomposer.applyAndCheck`/`Snapshot.dispose` (end of an apply pass), `Api35Impl.setRequestedFrameRate` inside `dispatchDraw` — landing at a different point each capture. That diversity is itself the signal: this is a genuine, complete recompose+measure+layout+draw cycle running every single frame, and the search decoration Row was never the thing driving it. The real driver is upstream — most likely `UnifiedJobsScreen` itself being invalidated every frame by something it collects (`spec.scanStatus` / `spec.scanGeneration` / `spec.progressVersion` at [UnifiedJobsScreen.kt:236-238](app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt:236) are the next things to instrument), or a coroutine from the reference viewer that genuinely does outlive navigation after all (worth re-checking Task 3A's original ViewModel-scoping angle from the first draft of this plan, now that the nav-bar theory is dead). Next session should pick up with `where all` thread dumps taken several seconds apart during the stuck state, diffed against each other for any repeating `com.kkc.sheettracker` frame (not just `androidx`/`android` frames), and a fresh instrumented pass on `UnifiedJobsScreen`'s own recomposition rate specifically.

### Task 3 (original, do not use): decouple the search field from the in-flight nav bar size spring

Root cause confirmed live on-device (see the update note above) — skip Tasks 1-2's log-based diagnosis, it's superseded.

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt:392-530` (`MorphingNavBar`)

- [ ] **Step 0: Verify the existing low-end-mode toggle masks the bug (same-day mitigation, do this regardless of Steps 1+)**

Install a release build on an affected tablet, go to Settings → Appearance → Performance, enable "Low-end device mode" (master switch), leave "Animations" sub-toggle ON-effects-disabled (i.e. leave the master enabled — this flips `LocalLowEndMode.current.animationsDisabled` to true, which routes `iconSizeSpec` at [AppScaffold.kt:414](app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt:414) to `snap()` instead of the spring). Run the repro (open job → Plans & Elevations → scroll → Back twice) and check `adb shell top -H -p <pid>` for 30+ seconds. If CPU stays at 0%, tell shop-floor tablets to enable this toggle today while Steps 1-4 land properly. If it does NOT fix it, the `TextFieldScrollerPosition` side of the interaction is enough on its own to sustain the loop — note that in this file before continuing to Step 1.

- [ ] **Step 1: Add a settle-debounce before the search decoration can appear, mirroring the existing pattern in this codebase**

This codebase already has an established fix for exactly this class of bug — `ContinuousReferencePdfPane.kt`'s `settled` variable (`LaunchedEffect(isInteracting, ...) { if (...) settled = false else { delay(120); settled = true } }`) delays expensive work until layout has been stable for 120ms. Apply the same pattern to `MorphingNavBar` so the search `BasicTextField` doesn't mount while the icon-size spring (or the overall `SizeTransform`) is still mid-flight.

In `AppScaffold.kt`, inside `MorphingNavBar` (starts at line 375), add after the existing `showDecorations`/`showLabels` calculation (after line 411):

```kotlin
    val showDecorations = !showExtended && hasDecoration
    // Labels only in the roomy full bar (not minimized, not showing extended controls).
    val showLabels      = !minimized && !showExtended

    // The search BasicTextField's internal cursor-visibility scroll offset
    // (TextFieldScrollerPosition) recalculates on every measure of its Row. If that Row's
    // available width is itself still animating (the icon-size spring below, or this bar's
    // own SizeTransform settling after a minimized<->full transition), the two remeasure each
    // other forever — confirmed via on-device thread dump, 2026-09-16, both nav-host
    // implementations, CPU pegged 55-96% indefinitely with zero user interaction until the app
    // was force-killed. Delay showing the decoration content by one debounce window after
    // `minimized` last changed, so it never mounts mid-transition — same 120ms settle pattern
    // ContinuousReferencePdfPane uses for its own expensive-render gating.
    var minimizedSettled by remember { mutableStateOf(!minimized) }
    LaunchedEffect(minimized) {
        minimizedSettled = false
        delay(120)
        minimizedSettled = true
    }
    val showDecorationsSettled = showDecorations && minimizedSettled
```

Then change the `AnimatedContent`'s `targetState` (around line 500-507) to use `showDecorationsSettled` instead of the raw `showDecorations`:

```kotlin
    val activeDecor = when {
        !showDecorationsSettled     -> "none"
        penDecoration != null       -> "pen"
        searchDecoration != null    -> "search"
        cncDecoration != null       -> "cnc"
        specialtyDecoration != null -> "specialty"
        else                        -> "none"
    }
```

Add the `delay`/`LaunchedEffect` imports if not already present in this file:

```bash
grep -n "^import kotlinx.coroutines.delay\|^import androidx.compose.runtime.LaunchedEffect" app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt
```

If either is missing, add it next to the other `androidx.compose.runtime`/`kotlinx.coroutines` imports at the top of the file.

- [ ] **Step 2: Rebuild, reinstall, and rerun the exact repro**

```bash
cd C:\Scripts\KKCSheetTracker
./gradlew.bat assembleRelease
adb uninstall com.kkc.sheettracker
adb install app\build\outputs\apk\release\app-release.apk
```

(Use `uninstall` + plain `install`, not `install -r`, only if the previous install on that device was a debug build — signatures won't match. If the device already has a release build installed from this same signing key, `install -r` is fine and preserves data.)

Run the full repro: Jobs tab → open a job → Plans & Elevations → scroll (swipe or slow drag, either) → Back twice. Then:

```bash
PID=$(adb shell pidof com.kkc.sheettracker)
sleep 3
adb shell top -H -n 1 -p $PID -b | grep -E "RenderThread|kc.sheettracker "
```

Expected: both rows at `0.0` (or a brief single-digit transient that settles within 1-2 more seconds, matching the 120ms debounce plus one settle cycle — NOT sustained 40%+).

- [ ] **Step 3: If Step 2 still shows sustained CPU, add a hard backstop regardless of remaining root cause**

This is a defensive circuit-breaker, not a real fix — only add it if Step 1's debounce alone didn't resolve it. In the same `MorphingNavBar`, cap `minIconSize`'s spring so it can never chase forever: change the `animateDpAsState` at line 415 to include a `finishedListener` that force-syncs after a maximum duration, or — simpler and lower-risk — temporarily default `iconSizeSpec` to `snap()` unconditionally (removing the spring entirely for this one value) to confirm the icon animation specifically (not the search field) is the surviving half of the loop:

```kotlin
    val iconSizeSpec = snap<Dp>()  // TEMP: confirm icon spring vs text field as the surviving half
```

Rebuild/reinstall/repro again. If this alone fixes it, the icon-size spring itself (not just its timing relative to the text field) is the real problem, and `NavSpringDp`'s spec (check its definition: `grep -n "val NavSpringDp" app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt`) needs a bounded max settle time or a different spec — bring this specific finding back for a follow-up fix rather than shipping `snap()` permanently (it changes the nav bar's polish, which was deliberately hand-tuned per this file's own comments).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt
git commit -m "fix: debounce nav bar decoration content so it never mounts mid-transition, stopping the icon-size spring and search field's internal scroll offset from remeasuring each other forever"
```

---

### Task 4: Remove all temporary diagnostic logging

Only relevant if Task 1 was actually executed (the log-based diagnosis path). Since root cause was confirmed via live `jdb` thread dumps instead (see the update note near the top of this plan), Task 1 was never applied and this task is likely a no-op — run the grep in Step 2 to confirm before doing anything else.

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt`

- [ ] **Step 1: Revert every `Log.d("PdfLeakDiag", ...)` line and its supporting `instanceId` scaffolding added in Task 1, if present**

```bash
grep -rn "PdfLeakDiag" app/src/main/java/com/kkc/sheettracker/
```

Delete each matching line and any now-unused local variable it depended on (`instanceId`, `frameLogCounter`, `recomposeCount`). Do not remove the real fix code from Task 3A/3B — only the logging.

- [ ] **Step 2: Verify no diagnostic logging remains**

```bash
grep -rn "PdfLeakDiag" app/src/main/java/com/kkc/sheettracker/
```

Expected: no output.

- [ ] **Step 3: Build**

```bash
cd C:\Scripts\KKCSheetTracker
./gradlew.bat assembleRelease
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: remove temporary PDF viewer CPU leak diagnostic logging"
```

---

### Task 5: On-device regression verification

**Files:** none (device-only task)

- [ ] **Step 1: Install the final build**

```bash
adb install -r app\build\outputs\apk\release\app-release.apk
```

- [ ] **Step 2: Run the full repro sequence from Task 2, Step 2, three times in a row** (open job → Plans & Elevations → scroll → Back → Back; repeat against a *different* job each time to also rule out any per-job caching quirk)

- [ ] **Step 3: After each repro, confirm CPU returns to idle within 3 seconds**

```bash
PID=$(adb shell pidof com.kkc.sheettracker)
sleep 3
adb shell top -H -n 1 -p $PID -b | grep -E "RenderThread|kc.sheettracker "
```

Expected all 3 runs: both rows show `0.0` (or single-digit transient) `%CPU`.

- [ ] **Step 4: Confirm via framestats that rendering actually stops (not just CPU coincidentally low)**

```bash
adb shell dumpsys gfxinfo com.kkc.sheettracker framestats | grep "Total frames rendered"
sleep 5
adb shell dumpsys gfxinfo com.kkc.sheettracker framestats | grep "Total frames rendered"
```

Expected: the two "Total frames rendered" counts are identical or nearly identical (a handful of frames from taking the screenshot/adb overhead is fine) — proving no continuous background rendering, whereas before the fix this count climbed by hundreds every 5 seconds even at rest.

- [ ] **Step 5: Leave the app idle on the Jobs list for 60 seconds and recheck**

```bash
sleep 60
adb shell top -H -n 1 -p $PID -b | grep -E "RenderThread|kc.sheettracker "
```

Expected: still `0.0%` — confirms this isn't a slow decay, it's actually fixed.

---

## Broader sweep, 2026-09-16 (same session): which other screens share this exposure

Re-tested against a fresh app install (release build data was wiped by an earlier debug-build swap; base path auto-recovered on relaunch, no manual reconfig needed).

- **Paged mode (`ReferencePdfPane`, Settings → Appearance → Continuous Scroll toggled OFF) — reproduces identically.** Paged through 5 pages via the arrow button, backed out twice: same sustained 40-55% CPU, same nav bar region. This is a *different composable* than `ContinuousReferencePdfPane` entirely — confirms the bug is 100% in the shared `AppScaffold`/nav-bar code (Task 3's target), not anything specific to either PDF pane's scroll implementation. Re-toggled the setting back ON afterward.
- **Assembly split view (`assembly/viewer/...`, two stacked `Plans`/`Assembly` panes)** — did **not** reproduce in this session's test (scrolled both panes, backed out twice, CPU returned to 0% and stayed there over repeated checks). This route matches the same `isInViewer` condition in `NavGraph.kt` as the confirmed-buggy `referenceViewer/` route, so it is not inherently safe — this may just mean the exact trigger (which needs a job-detail screen in between, or a specific transition timing) wasn't hit by this particular test. Don't treat it as cleared; re-test after Task 3's fix lands, with more/faster scrolling in both panes.
- **Hardwoods cut-list workspace (`hardwoods/workspace/...`)** — could not reach the interactive `ClassicCutListTable` through UI automation this session (the per-job overview's "Rip Cut List" / "Face Frame Cut List" cards look expandable via accessibility dump but taps landed with zero effect, byte-identical screenshots before/after). **Confirmed via code, not live-tested:** this route matches the same `isInViewer` check at [NavGraph.kt:2692](app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:2692), so it drives the exact same nav bar `minimized` toggle as the two confirmed-buggy viewers. Treat as equally exposed until someone reaches it on-device (from the actual job workflow, not this dashboard summary card) and confirms either way.
- **Background file watchers (`TrackerChangeMonitor`'s 10s poll loop, `FileObserver` instances) — clean.** Sat idle on the Dashboard for 80 seconds after a fresh launch: RenderThread accumulated CPU time stayed flat (0:00.16 → 0:00.17), main thread ticked up only 0.08s of CPU total across the whole 80s window. No periodic spikes, no drift. This subsystem is not a contributor to the "tablets get slower over a shift" complaint.
- **Supply tab** — idle CPU clean (0%). Supply's own dashboard route doesn't match `isInViewer`, and plain tab-switching away/back (Dashboard ↔ Supply) doesn't touch the vulnerable code path — confirmed clean, as expected from the code (only the 4 `isInViewer`-matching routes are implicated).

**Net effect on Task 3's scope:** the fix target doesn't change — it's still `AppScaffold.kt`'s `MorphingNavBar`, since that's the one piece of code all four `isInViewer` routes funnel through. But Task 2's verification step should be broadened: after applying Task 3's debounce fix, re-run the repro against paged mode, the Hardwoods workspace, and the Assembly split view too (not just the continuous reference viewer), since a fix to the shared nav bar code should close all four at once — and if any of them still shows sustained CPU after the fix, that's evidence of a *second*, route-specific bug rather than confirmation the shared fix was incomplete.

### 2026-09-17, continued again: Layout Inspector + real CPU profiler data — located the scope, still no confirmed single trigger

Picked this up a third time in the same day using tools neither of the earlier passes had: Android Studio's Layout Inspector (recomposition counts) and its CPU Profiler (Callstack Sample / simpleperf), run live by the user against the tablet while the bug was active. This is real, aggregated, symbolicated evidence — a different category of data from the `jdb` single-frame snapshots used earlier.

**Layout Inspector — recomposition counts.** With the counter overlay on, reproduced the bug (Jobs → job → Plans & Elevations → scroll → Back twice) and read the live component tree. Two things this settled that no earlier evidence had:

1. `LegacySingleStackNavigation` itself shows only ~5 recompositions (stable). Its direct child `Box` (the one starting at [NavGraph.kt:2719](app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:2719), wrapping `Scaffold` → `NavHost` as one sibling and the nav-bar overlay `Box` → `AppBottomNavBar` as another) jumps to 900+ and climbs continuously (4700+ by a later screenshot, seconds later, in the same session). **This is the single scope the storm originates in** — `UnifiedJobsScreen` (844+) and `AppBottomNavBar`/`MorphingNavBar` (913+) are both just downstream siblings under this one recomposing ancestor, not two independent bugs as earlier sessions assumed.
2. Confirmed (again, this time visually) that `BasicTextField`/`Button` inside the nav bar's search decoration recompose in lockstep with everything else under that ancestor (4782 each) — consistent with "victim of the cascade," matching the elimination testing from earlier today.

**Root-caused the exact code at that scope** ([NavGraph.kt:2660-2719](app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:2660)) and checked every state value declared there:
- `specialtyProgressVersion` (`specialtyStateStore.progressVersion.collectAsState()`, line 2666) — collected unconditionally regardless of active mode. Worth double-checking independently, but nothing found tying it to a continuous emission.
- `currentNavDest` (`remember(currentRoute, workMode, flexibleModeEnabled)`, line 2668) — properly memoized; only recomputes if `currentRoute` itself changes.
- `isInViewer`, `navBarAlpha` (`animateFloatAsState` targeting a stable `1f`/`0f`, `tween(286)`) — a finite, non-looping animation; settles and stops requesting frames.
- `watcherRefreshEpoch` (`watcherRefreshSignal.collectAsState()`, fed by `TrackerChangeMonitor`, `StaticCachePoller`, and `LiveIndexClient` — all three call `watcherRefreshSignal.value = System.currentTimeMillis()`, which — unlike a boolean or counter — never gets conflated by `StateFlow` since every timestamp differs). This looked like the strongest lead of the day (a live WebSocket push client that could plausibly reconnect-loop or spam deltas) — **directly disproven** by grepping the running app's own existing logs: `LiveIndexClient` logged exactly one `snapshot` per connection (stable `revision=14`, no repeated `delta` messages), and `StaticCachePoller`/`TrackerChangeMonitor` only logged start/stop at real app-restart boundaries, not during a sustained storm window.
- The mysterious `Box(pointerInput(onIdleReset){ while(true) {...} })` sitting at the exact storming scope ([NavGraph.kt:2719-2730](app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:2719)) — investigated `IdleActivityTracker` fully; it ticks once per second and `MutableStateFlow.value =` conflates repeated `IdlePhase.ACTIVE` writes, so it cannot be a 100Hz source. Adjacency to the storming scope was coincidental, not causal.

**CPU Profiler (simpleperf Callstack Sample), parsed exactly, not eyeballed.** The exported `.trace` is simpleperf's own protobuf format (`cmd_report_sample.proto`, shipped in the Android NDK at `ndk/<ver>/simpleperf/proto/`) — compiled it with `grpc_tools.protoc` and wrote a parser (`tmp/pbgen/parse_trace2.py`, kept in the repo for reuse) that aggregates true on-CPU self-time per thread (filtering out `sched:sched_switch`/off-CPU samples, which is what made Android Studio's own default "Wall Clock Time" view show 1000%+ noise from idle worker threads park/wait — switch that dropdown to **"Thread Time"** and pick the specific thread if you re-run this by hand). Findings from a real ~15-20s capture during the stuck state:

- Main thread (`com.kkc.sheettracker`) and `RenderThread` both show ~11,000+ on-CPU samples each out of the capture — both genuinely CPU-bound, not idle.
- **The self-time profile is flat** — no single function exceeds 2.7% of main thread's on-CPU time. The top entries are `art::Thread::InternalStackTraceToStackTraceElementArray` (2.68%) + `StackVisitor::WalkStack` (0.88%) — the machinery for building a Java stack trace — alongside `write`/`writev` syscalls (~3% combined), `art_quick_alloc_object_*` (object allocation, ~1.4% combined), and a long tail of small `androidx.compose.runtime.GapComposer`/`CompositionImpl`/`SnapshotKt` entries (recomposition bookkeeping, each individually under 1.5%).
- Checked whether the app itself was logging an exception repeatedly (which would explain the stack-trace-capture cost directly) by grepping `adb logcat` for warnings/errors in the exact capture window (07:35:00-07:36:30) — **zero hits from `com.kkc.sheettracker`**; every `W`/`E` line in that window was Android system processes (Bluetooth stack, SystemUI). Leading theory instead: Kotlin coroutines' built-in stack-trace-recovery-on-`CancellationException`, which fires automatically whenever a coroutine (e.g. a `LaunchedEffect`) is cancelled and immediately relaunched — consistent with, but not sole proof of, something restarting a coroutine at high frequency.
- RenderThread's profile is dominated by real Skia/GL draw work (`RenderNode::prepareTreeImpl` 2.07%, `SkiaDisplayList::prepareListAndChildren` 1.42%, various `libGLESv2_adreno.so` shader entries ~2% each, `DisplayListData::draw` 1.53%) — i.e., it's genuinely re-drawing a real, non-trivial frame's worth of content every single frame, not spinning on nothing.

**Net conclusion:** the flat, no-single-hotspot shape of both profiles is itself the finding — this isn't "one expensive function stuck in a loop," it's "a full recompose-measure-layout-draw pass, of real non-trivial size, running every single frame indefinitely." That matches the Layout Inspector's tree evidence (a broad ancestor scope recomposing, cascading to multiple large subtrees) better than it matches any single missed state read. Every state value declared in the implicated scope has now been individually checked and cleared. What's left unidentified is the actual **trigger** that keeps requesting a new frame at all — likely a coroutine (`LaunchedEffect`, `animateXAsState`, or similar) somewhere in that scope's descendants that never reaches a stable/settled state, restarting every frame — but pinpointing which one needs either (a) instrumenting each remaining `LaunchedEffect`/`animate*AsState` in this scope's subtree with a one-time "started" log to see which restarts every frame, or (b) using the profiler's **Flame Chart** tab (not used this session) which visually aligns repeated call patterns across the capture timeline in a way the Top Down tree does not.

Reusable artifacts left in the repo for next time: `tmp/pbgen/parse_trace2.py` (simpleperf `.trace` → per-thread self-time report; regenerate `cmd_report_sample_pb2.py` from `<ndk>/simpleperf/proto/cmd_report_sample.proto` via `python -m grpc_tools.protoc` if missing).

## Other findings from this investigation (not actioned in this plan — flagging for separate follow-up)

- **Leftover native `RenderThread` instances:** each time the "Plans & Elevations" viewer was opened, 4 extra native `RenderThread`-named threads appeared (via `adb shell top -H`) and never went away for the rest of the process's life, though they sat at 0% CPU and didn't grow further per repeat. Worth a quick look at whatever creates a `SurfaceView`/hardware-accelerated sub-surface inside the PDF render path (likely `PdfEngineCache` or per-page bitmap decode) to see if it should be closing a `Surface`/`RenderNode` it currently isn't. Low priority — no confirmed CPU or memory cost, just resource-accumulation smell.
- **"Mix catalog unavailable; try again before opening a mix."** banner on every material row of job 644d's detail screen ([JobDetailScreen.kt:864](app/src/main/java/com/kkc/sheettracker/ui/detail/JobDetailScreen.kt:864)): this appears to be correct-by-design (a shared catalog resource failed to load once, so every row referencing it correctly shows the same warning) rather than a bug — but worth confirming with whoever owns the mix-catalog feature that the underlying catalog file is actually supposed to be present for that job, since a real user will see this as a wall of red errors.
