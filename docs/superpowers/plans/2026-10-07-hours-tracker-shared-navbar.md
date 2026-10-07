# Hours Tracker Shared Navbar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When KKCSheetTracker opens Hours Tracker, Hours Tracker shows a pixel-matched copy of KKC's bottom navbar (same theme colors, glass, badges, low-end behavior); tapping a destination on it returns to that KKC page.

**Architecture:** KKC resolves its navbar look inside composition and sends it as versioned intent extras (`extra_kkc_navbar_*`). Hours Tracker parses them into the same `KkcNavBarPayload` data class and draws `KkcNavBar` (a copy of KKC's full-state `MorphingNavBar`) over its content. A tap sends `extra_kkc_nav_destination=<route>` back to KKC's `MainActivity` (`CLEAR_TOP|SINGLE_TOP`), which posts it to `ExternalNavRequests`; both KKC nav hosts consume it through the same function their own navbar uses.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Haze 1.5.1, Compose Google Fonts (Inter), JUnit4 JVM unit tests (source-text wiring tests where composition can't run on the JVM).

**Spec:** `docs/superpowers/specs/2026-10-07-hours-tracker-shared-navbar-design.md` (KKCSheetTracker repo). Read it before starting.

**Repos:**
- KKC = `C:\Scripts\KKCSheetTracker` (branch base `main`), package `com.kkc.sheettracker`.
- HT = `C:\Scripts\Hours Tracker\AndroidApp` (separate git repo, branch base `master`), package/namespace `com.example.timecard`.

## Global Constraints

- Contract version `1`; every navbar extra key starts with `extra_kkc_navbar_`; return extra is `extra_kkc_nav_destination`; calculator route is `calculator`.
- Exactly 20 navbar extra keys (canonical list in Task 1 / Task 5 tests) — identical strings in both repos.
- Hours Tracker shows the bar only when launched by KKC **and** `extra_kkc_navbar_version == 1` **and** all required extras parse; otherwise behavior is unchanged.
- Mirrored bar = KKC full state only: side margin 24.dp, bottom gap 12.dp, Surface corner 20.dp, row `heightIn(min = 44.dp)` + padding 24.dp×4.dp, `Arrangement.SpaceEvenly`, every slot `weight(1f)`, item padding 14.dp×8.dp, `spacedBy(3.dp)`, icon 22.dp, Calc slot immediately before Hours, shadow 3.dp unless disabled, frosted alpha `coerceIn(0.5f, 0.95f)`, blur `coerceAtLeast(1f)`.
- Label text style = Inter Medium 11.sp, lineHeight 16.sp, letterSpacing 0.5.sp; Bold when selected.
- Low-end: `anim_disabled` → `snap()` specs and no window animation; `blur_disabled` → no Haze, semi-transparent fill; `shadows_disabled` → 0.dp shadow.
- App switch both directions: `android.R.anim.fade_in` / `android.R.anim.fade_out`; `0`/`0` when `anim_disabled`.
- Hours Tracker "← KKC" buttons (`TimesheetScreen.kt`, `NameCard.kt`) stay unchanged as fallback.
- Haze version `1.5.1` in HT (same as KKC).
- Edit code with Edit/Write tools, not sed/python scripts (user rule). Whole-file copies with `cp` are fine.
- Tests must never touch `Y:\Ready Jobs`.
- Tablets get **release** builds (`assembleRelease` + `adb install -r`), never debug.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Old KKC build launches new Hours Tracker** (no extras, or a future version number) → no bar, "← KKC" still works. Pinned in Task 5 (`parse` returns null for missing / unsupported version / missing required color).
2. **KKC process was killed while Hours Tracker was in front** → KKC cold-starts with the extra in `onCreate`; the request must survive until a nav host composes, and a later configuration-change recreation must not replay it. Pinned in Task 2 (request retained until `take()`) and Task 4 (wiring test asserts `savedInstanceState == null` guard).
3. **Destination not visible in KKC's current work mode** (e.g. Dashboard while in Assembly mode) → ignored, logged, no crash. Pinned in Task 2 (`resolveExternalDestination`).
4. **Double tap on the Hours Tracker bar** while it is already finishing → only one KKC launch. Pinned in Task 8 (wiring test asserts the `isFinishing` guard).
5. **Tapping Hours (already selected) or a zero badge count** → no navigation, no badge drawn. Pinned in Task 5 (`requestFor("hours") == null`) and Task 7 (`kkcBadgeCount` returns 0, model test).

---

### Task 0: Pre-flight (both repos)

Both repos had uncommitted user work in files this plan edits (KKC `NavGraph.kt` clock-in/billing WIP; HT `TimecardApp.kt` + `app/build.gradle.kts`). Do not proceed on a dirty tree.

- [ ] **Step 1: Check both trees**

```bash
git -C /c/Scripts/KKCSheetTracker status --short
git -C "/c/Scripts/Hours Tracker/AndroidApp" status --short
```

Expected: empty output for tracked files in both. If anything is modified/untracked, STOP and ask the user how to handle it (commit it themselves, or explicitly authorize you to commit it as-is). Do not stash or commit user work unasked.

- [ ] **Step 2: Create feature branches**

```bash
git -C /c/Scripts/KKCSheetTracker switch -c feat/hours-navbar-mirror
git -C "/c/Scripts/Hours Tracker/AndroidApp" switch -c feat/hours-navbar-mirror
```

- [ ] **Step 3: Baseline KKC tests**

Run (PowerShell, from `C:\Scripts\KKCSheetTracker`): `.\gradlew.bat :app:testDebugUnitTest`
Expected: PASS except the known off-device `PdfMarkup` MotionEvent test (environment-only, not a regression). Record any other failure before changing code.

- [ ] **Step 4: Baseline HT tests**

Run (from `C:\Scripts\Hours Tracker\AndroidApp`): `.\gradlew.bat :app:testDebugUnitTest`
Expected: PASS. Record any failure before changing code.

---

### Task 1: KKC navbar contract + payload model

**Files:**
- Create: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\navigation\KkcNavBarContract.kt`
- Test: `C:\Scripts\KKCSheetTracker\app\src\test\java\com\kkc\sheettracker\navigation\KkcNavBarContractTest.kt`

**Interfaces:**
- Produces: `object KkcNavBarContract` (constants below), `data class KkcNavBarPayload(...)` with `fun toExtras(): Map<String, Any>`, and `internal fun android.content.Intent.putKkcNavBarExtras(payload: KkcNavBarPayload)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_BOLD_GRADIENT
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_BOLD_MODE
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_DESTINATIONS
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_FROSTED_ALPHA
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_PRIMARY
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_SUPPLY_COUNT
import com.kkc.sheettracker.navigation.KkcNavBarContract.EXTRA_VERSION
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KkcNavBarContractTest {

    private val sample = KkcNavBarPayload(
        destinations = listOf("jobs", "hours", "timecard", "supply", "standards"),
        supplyCount = 3,
        safetyCount = 1,
        dark = true,
        primary = 0xFF112233.toInt(),
        onSurfaceVariant = 0xFF445566.toInt(),
        surfaceVariant = 0xFF778899.toInt(),
        frostedBase = 0xFFAABBCC.toInt(),
        frostedContent = 0xFF000000.toInt(),
        frostedAlpha = 0.72f,
        frostedBlurDp = 14f,
        boldMode = true,
        boldGradient = listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
        badgeContainer = 0xFFB3261E.toInt(),
        badgeContent = 0xFFFFFFFF.toInt(),
        indicatorCornerDp = 9f,
        animDisabled = false,
        blurDisabled = false,
        shadowsDisabled = true
    )

    @Test
    fun extrasUseTheCanonicalKeySet() {
        assertEquals(CANONICAL_KEYS, sample.toExtras().keys)
    }

    @Test
    fun versionIsOne() {
        assertEquals(1, KkcNavBarContract.VERSION)
        assertEquals(1, sample.toExtras()[EXTRA_VERSION])
    }

    @Test
    fun destinationsKeepBarOrder() {
        assertArrayEquals(
            arrayOf("jobs", "hours", "timecard", "supply", "standards"),
            sample.toExtras()[EXTRA_DESTINATIONS] as Array<*>
        )
    }

    @Test
    fun valueTypesMatchTheHoursTrackerParser() {
        val extras = sample.toExtras()
        assertTrue(extras[EXTRA_PRIMARY] is Int)
        assertTrue(extras[EXTRA_SUPPLY_COUNT] is Int)
        assertTrue(extras[EXTRA_FROSTED_ALPHA] is Float)
        assertTrue(extras[EXTRA_BOLD_MODE] is Boolean)
        assertTrue(extras[EXTRA_BOLD_GRADIENT] is IntArray)
        assertTrue(extras[EXTRA_DESTINATIONS] is Array<*>)
    }

    @Test
    fun returnPathConstantsMatchHoursTracker() {
        assertEquals("extra_kkc_nav_destination", KkcNavBarContract.EXTRA_NAV_DESTINATION)
        assertEquals("calculator", KkcNavBarContract.DEST_CALCULATOR)
        assertEquals("hours", KkcNavBarContract.DEST_HOURS)
        assertEquals("com.kkc.sheettracker", KkcNavBarContract.KKC_PACKAGE)
        assertEquals("com.kkc.sheettracker.MainActivity", KkcNavBarContract.KKC_ACTIVITY)
    }

    companion object {
        // KEEP IN SYNC with Hours Tracker
        // app/src/test/java/com/example/timecard/kkcnav/KkcNavBarContractTest.kt CANONICAL_KEYS.
        val CANONICAL_KEYS = setOf(
            "extra_kkc_navbar_version",
            "extra_kkc_navbar_destinations",
            "extra_kkc_navbar_supply_count",
            "extra_kkc_navbar_safety_count",
            "extra_kkc_navbar_dark",
            "extra_kkc_navbar_primary",
            "extra_kkc_navbar_on_surface_variant",
            "extra_kkc_navbar_surface_variant",
            "extra_kkc_navbar_frosted_base",
            "extra_kkc_navbar_frosted_content",
            "extra_kkc_navbar_frosted_alpha",
            "extra_kkc_navbar_frosted_blur_dp",
            "extra_kkc_navbar_bold_mode",
            "extra_kkc_navbar_bold_gradient",
            "extra_kkc_navbar_badge_container",
            "extra_kkc_navbar_badge_content",
            "extra_kkc_navbar_indicator_corner_dp",
            "extra_kkc_navbar_anim_disabled",
            "extra_kkc_navbar_blur_disabled",
            "extra_kkc_navbar_shadows_disabled"
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.KkcNavBarContractTest"`
Expected: FAIL — compilation error, `KkcNavBarPayload` / `KkcNavBarContract` unresolved.

- [ ] **Step 3: Write the implementation**

`KkcNavBarContract.kt`:

```kotlin
package com.kkc.sheettracker.navigation

import android.content.Intent

/**
 * Intent contract between KKCSheetTracker and Hours Tracker (com.example.timecard) for the
 * mirrored bottom navbar. KKC sends the navbar's resolved look; Hours Tracker draws a copy and
 * sends the tapped destination back. Sync header is expanded in Task 9.
 */
object KkcNavBarContract {
    const val VERSION = 1

    const val EXTRA_VERSION = "extra_kkc_navbar_version"
    const val EXTRA_DESTINATIONS = "extra_kkc_navbar_destinations"
    const val EXTRA_SUPPLY_COUNT = "extra_kkc_navbar_supply_count"
    const val EXTRA_SAFETY_COUNT = "extra_kkc_navbar_safety_count"
    const val EXTRA_DARK = "extra_kkc_navbar_dark"
    const val EXTRA_PRIMARY = "extra_kkc_navbar_primary"
    const val EXTRA_ON_SURFACE_VARIANT = "extra_kkc_navbar_on_surface_variant"
    const val EXTRA_SURFACE_VARIANT = "extra_kkc_navbar_surface_variant"
    const val EXTRA_FROSTED_BASE = "extra_kkc_navbar_frosted_base"
    const val EXTRA_FROSTED_CONTENT = "extra_kkc_navbar_frosted_content"
    const val EXTRA_FROSTED_ALPHA = "extra_kkc_navbar_frosted_alpha"
    const val EXTRA_FROSTED_BLUR_DP = "extra_kkc_navbar_frosted_blur_dp"
    const val EXTRA_BOLD_MODE = "extra_kkc_navbar_bold_mode"
    const val EXTRA_BOLD_GRADIENT = "extra_kkc_navbar_bold_gradient"
    const val EXTRA_BADGE_CONTAINER = "extra_kkc_navbar_badge_container"
    const val EXTRA_BADGE_CONTENT = "extra_kkc_navbar_badge_content"
    const val EXTRA_INDICATOR_CORNER_DP = "extra_kkc_navbar_indicator_corner_dp"
    const val EXTRA_ANIM_DISABLED = "extra_kkc_navbar_anim_disabled"
    const val EXTRA_BLUR_DISABLED = "extra_kkc_navbar_blur_disabled"
    const val EXTRA_SHADOWS_DISABLED = "extra_kkc_navbar_shadows_disabled"

    // Return path (Hours Tracker → KKC)
    const val EXTRA_NAV_DESTINATION = "extra_kkc_nav_destination"
    const val DEST_CALCULATOR = "calculator"
    const val DEST_HOURS = "hours"
    const val KKC_PACKAGE = "com.kkc.sheettracker"
    const val KKC_ACTIVITY = "com.kkc.sheettracker.MainActivity"
}

/** Resolved navbar look sent to Hours Tracker. Colors are ARGB ints. Same class exists in HT. */
data class KkcNavBarPayload(
    val destinations: List<String>,
    val supplyCount: Int,
    val safetyCount: Int,
    val dark: Boolean,
    val primary: Int,
    val onSurfaceVariant: Int,
    val surfaceVariant: Int,
    val frostedBase: Int,
    val frostedContent: Int,
    val frostedAlpha: Float,
    val frostedBlurDp: Float,
    val boldMode: Boolean,
    val boldGradient: List<Int>,
    val badgeContainer: Int,
    val badgeContent: Int,
    val indicatorCornerDp: Float,
    val animDisabled: Boolean,
    val blurDisabled: Boolean,
    val shadowsDisabled: Boolean
) {
    fun toExtras(): Map<String, Any> = with(KkcNavBarContract) {
        mapOf(
            EXTRA_VERSION to VERSION,
            EXTRA_DESTINATIONS to destinations.toTypedArray(),
            EXTRA_SUPPLY_COUNT to supplyCount,
            EXTRA_SAFETY_COUNT to safetyCount,
            EXTRA_DARK to dark,
            EXTRA_PRIMARY to primary,
            EXTRA_ON_SURFACE_VARIANT to onSurfaceVariant,
            EXTRA_SURFACE_VARIANT to surfaceVariant,
            EXTRA_FROSTED_BASE to frostedBase,
            EXTRA_FROSTED_CONTENT to frostedContent,
            EXTRA_FROSTED_ALPHA to frostedAlpha,
            EXTRA_FROSTED_BLUR_DP to frostedBlurDp,
            EXTRA_BOLD_MODE to boldMode,
            EXTRA_BOLD_GRADIENT to boldGradient.toIntArray(),
            EXTRA_BADGE_CONTAINER to badgeContainer,
            EXTRA_BADGE_CONTENT to badgeContent,
            EXTRA_INDICATOR_CORNER_DP to indicatorCornerDp,
            EXTRA_ANIM_DISABLED to animDisabled,
            EXTRA_BLUR_DISABLED to blurDisabled,
            EXTRA_SHADOWS_DISABLED to shadowsDisabled
        )
    }
}

internal fun Intent.putKkcNavBarExtras(payload: KkcNavBarPayload) {
    payload.toExtras().forEach { (key, value) ->
        when (value) {
            is Int -> putExtra(key, value)
            is Float -> putExtra(key, value)
            is Boolean -> putExtra(key, value)
            is IntArray -> putExtra(key, value)
            is Array<*> -> putExtra(key, value.map { it as String }.toTypedArray())
            else -> error("Unsupported navbar extra type for $key: ${value::class}")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.KkcNavBarContractTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/KkcNavBarContract.kt app/src/test/java/com/kkc/sheettracker/navigation/KkcNavBarContractTest.kt
git commit -m "feat(hours-navbar): add KKC navbar intent contract

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: KKC external navigation requests

**Files:**
- Create: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\navigation\ExternalNavRequests.kt`
- Test: `C:\Scripts\KKCSheetTracker\app\src\test\java\com\kkc\sheettracker\navigation\ExternalNavRequestsTest.kt`

**Interfaces:**
- Consumes: `KkcNavBarContract.DEST_CALCULATOR` (Task 1); `com.kkc.sheettracker.ui.components.NavDestination`.
- Produces:
  - `sealed interface ExternalNavTarget { data class Destination(val destination: NavDestination); data object Calculator }`
  - `object ExternalNavRequests { val pending: StateFlow<ExternalNavTarget?>; fun parse(route: String?): ExternalNavTarget?; fun postFromRoute(route: String?): Boolean; fun take(): ExternalNavTarget? }`
  - `internal fun resolveExternalDestination(target: ExternalNavTarget.Destination, visible: List<NavDestination>): NavDestination?`
  - `@Composable internal fun ExternalNavEffect(visibleDestinations: List<NavDestination>, navigate: (NavDestination) -> Unit, openCalculator: () -> Unit)`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.ui.components.NavDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExternalNavRequestsTest {

    @Before
    fun clear() {
        ExternalNavRequests.take()
    }

    @Test
    fun parsesEveryTopLevelRouteExceptHours() {
        NavDestination.entries.filter { it != NavDestination.HOURS }.forEach { dest ->
            assertEquals(ExternalNavTarget.Destination(dest), ExternalNavRequests.parse(dest.route))
        }
    }

    @Test
    fun hoursIsRejectedSoHoursTrackerCannotRelaunchItself() {
        assertNull(ExternalNavRequests.parse("hours"))
    }

    @Test
    fun calculatorParses() {
        assertEquals(ExternalNavTarget.Calculator, ExternalNavRequests.parse("calculator"))
    }

    @Test
    fun unknownBlankAndNullAreRejected() {
        assertNull(ExternalNavRequests.parse("job/123"))
        assertNull(ExternalNavRequests.parse(""))
        assertNull(ExternalNavRequests.parse(null))
    }

    @Test
    fun postedRequestIsRetainedUntilTakenOnce() {
        assertTrue(ExternalNavRequests.postFromRoute("supply"))
        // No consumer yet (cold start): the request must still be there.
        assertEquals(ExternalNavTarget.Destination(NavDestination.SUPPLY), ExternalNavRequests.pending.value)
        assertEquals(ExternalNavTarget.Destination(NavDestination.SUPPLY), ExternalNavRequests.take())
        assertNull(ExternalNavRequests.take())
        assertNull(ExternalNavRequests.pending.value)
    }

    @Test
    fun invalidRouteIsNotPosted() {
        assertFalse(ExternalNavRequests.postFromRoute("bogus"))
        assertNull(ExternalNavRequests.pending.value)
    }

    @Test
    fun destinationNotVisibleInCurrentWorkModeIsIgnored() {
        val visible = listOf(NavDestination.JOBS, NavDestination.HOURS, NavDestination.SUPPLY)
        assertNull(resolveExternalDestination(ExternalNavTarget.Destination(NavDestination.DASHBOARD), visible))
        assertEquals(
            NavDestination.SUPPLY,
            resolveExternalDestination(ExternalNavTarget.Destination(NavDestination.SUPPLY), visible)
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.ExternalNavRequestsTest"`
Expected: FAIL — `ExternalNavRequests` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.kkc.sheettracker.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.kkc.sheettracker.logging.AppLog
import com.kkc.sheettracker.ui.components.NavDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** A navigation request that arrived from outside the app (Hours Tracker's mirrored navbar). */
sealed interface ExternalNavTarget {
    data class Destination(val destination: NavDestination) : ExternalNavTarget
    data object Calculator : ExternalNavTarget
}

/**
 * Process-wide, single-consumer holder for navbar taps made in Hours Tracker. MainActivity posts;
 * whichever nav host is composed takes it. A StateFlow (not a SharedFlow) so a request posted
 * during a cold start, before any nav host is composed, is still there when one is.
 */
object ExternalNavRequests {
    private val _pending = MutableStateFlow<ExternalNavTarget?>(null)
    val pending: StateFlow<ExternalNavTarget?> = _pending.asStateFlow()

    fun parse(route: String?): ExternalNavTarget? {
        if (route.isNullOrBlank()) return null
        if (route == KkcNavBarContract.DEST_CALCULATOR) return ExternalNavTarget.Calculator
        val destination = NavDestination.entries.firstOrNull { it.route == route } ?: return null
        // HOURS would relaunch Hours Tracker in a loop; it is never a valid return target.
        if (destination == NavDestination.HOURS) return null
        return ExternalNavTarget.Destination(destination)
    }

    fun postFromRoute(route: String?): Boolean {
        val target = parse(route) ?: return false
        _pending.value = target
        return true
    }

    fun take(): ExternalNavTarget? = _pending.getAndUpdate { null }
}

internal fun resolveExternalDestination(
    target: ExternalNavTarget.Destination,
    visible: List<NavDestination>
): NavDestination? = target.destination.takeIf { it in visible }

/**
 * Consumes [ExternalNavRequests] in a nav host. [navigate] must be the SAME function the host's
 * own AppBottomNavBar uses for onNavigate, so a tap in Hours Tracker behaves exactly like a tap
 * in KKC.
 */
@Composable
internal fun ExternalNavEffect(
    visibleDestinations: List<NavDestination>,
    navigate: (NavDestination) -> Unit,
    openCalculator: () -> Unit
) {
    val pending by ExternalNavRequests.pending.collectAsState()
    val latestVisible by rememberUpdatedState(visibleDestinations)
    val latestNavigate by rememberUpdatedState(navigate)
    val latestOpenCalculator by rememberUpdatedState(openCalculator)
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        when (val target = ExternalNavRequests.take()) {
            is ExternalNavTarget.Destination -> {
                val destination = resolveExternalDestination(target, latestVisible)
                if (destination != null) {
                    AppLog.d("KKC_NAV", "external_nav target=${destination.route}")
                    latestNavigate(destination)
                } else {
                    AppLog.d("KKC_NAV", "external_nav_ignored reason=not_visible target=${target.destination.route}")
                }
            }
            ExternalNavTarget.Calculator -> {
                AppLog.d("KKC_NAV", "external_nav target=calculator")
                latestOpenCalculator()
            }
            null -> Unit
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.ExternalNavRequestsTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/ExternalNavRequests.kt app/src/test/java/com/kkc/sheettracker/navigation/ExternalNavRequestsTest.kt
git commit -m "feat(hours-navbar): add external navigation request queue

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: KKC sends the payload on every Hours Tracker launch

**Files:**
- Create: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\navigation\KkcNavBarPayloadBuilder.kt`
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\navigation\NavGraph.kt` (MultiBackStackNavigation ≈ lines 679–1263; LegacySingleStackNavigation ≈ 2633–4037; `HoursTabHost` ≈ 4250; `launchTimecardApp` ≈ 4279 — line numbers approximate, search by name)
- Test: `C:\Scripts\KKCSheetTracker\app\src\test\java\com\kkc\sheettracker\navigation\HoursNavBarMirrorWiringTest.kt`

**Interfaces:**
- Consumes: `KkcNavBarPayload`, `putKkcNavBarExtras` (Task 1).
- Produces: `@Composable internal fun currentKkcNavBarPayload(destinations: List<NavDestination>, supplyCount: Int, safetyCount: Int): KkcNavBarPayload`; `launchTimecardApp(context, autoLoginInput, jobNumber = null, hours = null, navBar: KkcNavBarPayload? = null)`; a `val kkcNavBarPayload` local in each nav host.

- [ ] **Step 1: Write the failing wiring test**

```kotlin
package com.kkc.sheettracker.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HoursNavBarMirrorWiringTest {

    private val navGraph by lazy { source("navigation/NavGraph.kt") }

    @Test
    fun everyTimecardLaunchCarriesTheNavBarPayload() {
        val calls = navGraph.lines().filter {
            it.contains("launchTimecardApp(") && !it.contains("fun launchTimecardApp(")
        }
        assertTrue("expected the Hours launch call sites, found ${calls.size}", calls.size >= 7)
        calls.forEach { assertTrue("launch without navBar payload: ${it.trim()}", it.contains("navBar = ")) }
    }

    @Test
    fun bothNavHostsBuildThePayloadOnce() {
        assertEquals(2, Regex("currentKkcNavBarPayload\\(").findAll(navGraph).count())
    }

    @Test
    fun hoursTabHostReceivesThePayload() {
        val start = navGraph.indexOf("private fun HoursTabHost(")
        assertTrue("HoursTabHost not found", start >= 0)
        assertTrue(navGraph.substring(start, start + 400).contains("navBar: KkcNavBarPayload"))
    }

    @Test
    fun launchUsesTheLowEndAwareSwitchAnimation() {
        val start = navGraph.indexOf("private fun launchTimecardApp(")
        assertTrue("launchTimecardApp not found", start >= 0)
        val body = navGraph.substring(start, minOf(start + 1500, navGraph.length))
        assertTrue(body.contains("putKkcNavBarExtras(navBar)"))
        assertTrue(body.contains("ActivityOptions.makeCustomAnimation"))
    }

    companion object {
        fun source(relative: String): String {
            var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
            repeat(6) {
                val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/$relative")
                if (candidate.exists()) return candidate.readText()
                val direct = File(dir, "src/main/java/com/kkc/sheettracker/$relative")
                if (direct.exists()) return direct.readText()
                dir = dir.parentFile ?: return@repeat
            }
            error("Unable to locate $relative from ${System.getProperty("user.dir")}")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.HoursNavBarMirrorWiringTest"`
Expected: FAIL — call sites lack `navBar = `, no `currentKkcNavBarPayload(`.

- [ ] **Step 3: Create the payload builder**

`KkcNavBarPayloadBuilder.kt`:

```kotlin
package com.kkc.sheettracker.navigation

import androidx.compose.material3.BadgeDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.components.NavDestination
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import com.kkc.sheettracker.ui.theme.boldGradientColors
import com.kkc.sheettracker.ui.theme.kkcFrostedBaseColor
import com.kkc.sheettracker.ui.theme.kkcFrostedContentColor

/**
 * Resolves the bottom navbar's current look from the live KKC theme, exactly as
 * AppScaffold.kt MorphingNavBar / MorphingNavIconRow read it, so Hours Tracker never has to parse
 * KKC theme JSON. Must be called inside KKC's theme composition.
 */
@Composable
internal fun currentKkcNavBarPayload(
    destinations: List<NavDestination>,
    supplyCount: Int,
    safetyCount: Int
): KkcNavBarPayload {
    val tokens = LocalKKCThemeTokens.current
    val dark = LocalKKCIsDarkTheme.current
    val lowEnd = LocalLowEndMode.current
    val scheme = MaterialTheme.colorScheme
    val badgeContainer = BadgeDefaults.containerColor
    return KkcNavBarPayload(
        destinations = destinations.map { it.route },
        supplyCount = supplyCount,
        safetyCount = safetyCount,
        dark = dark,
        primary = scheme.primary.toArgb(),
        onSurfaceVariant = scheme.onSurfaceVariant.toArgb(),
        surfaceVariant = scheme.surfaceVariant.toArgb(),
        frostedBase = kkcFrostedBaseColor().toArgb(),
        frostedContent = kkcFrostedContentColor().toArgb(),
        frostedAlpha = tokens.frosted.backgroundAlpha,
        frostedBlurDp = tokens.frosted.blurDp,
        boldMode = tokens.boldMode,
        boldGradient = boldGradientColors(tokens.palette(dark)).map { it.toArgb() },
        badgeContainer = badgeContainer.toArgb(),
        badgeContent = contentColorFor(badgeContainer).toArgb(),
        indicatorCornerDp = tokens.shape.mediumDp,
        animDisabled = lowEnd.animationsDisabled,
        blurDisabled = lowEnd.blurDisabled,
        // Same condition MorphingNavBar uses for its 0.dp shadow.
        shadowsDisabled = lowEnd.shadowsDisabled || lowEnd.webViewBlurSuppressed
    )
}
```

- [ ] **Step 4: Update `launchTimecardApp` in NavGraph.kt**

Replace the existing function (search `private fun launchTimecardApp(`) with:

```kotlin
private fun launchTimecardApp(
    context: android.content.Context,
    autoLoginInput: String?,
    jobNumber: String? = null,
    hours: String? = null,
    navBar: KkcNavBarPayload? = null
) {
    val intent = android.content.Intent().apply {
        setClassName("com.example.timecard", "com.example.timecard.MainActivity")
        putExtra("extra_launched_by_kkc", true)
        if (autoLoginInput != null) putExtra("extra_auto_login", autoLoginInput)
        if (jobNumber != null) putExtra("extra_job_number", jobNumber)
        if (hours != null) putExtra("extra_hours", hours)
        if (navBar != null) putKkcNavBarExtras(navBar)
    }
    // Cross-fade so the identical navbar in both apps reads as staying put; instant in low-end mode.
    val animationsDisabled = navBar?.animDisabled == true
    val options = android.app.ActivityOptions.makeCustomAnimation(
        context,
        if (animationsDisabled) 0 else android.R.anim.fade_in,
        if (animationsDisabled) 0 else android.R.anim.fade_out
    )
    context.startActivity(intent, options.toBundle())
}
```

- [ ] **Step 5: Build the payload in MultiBackStackNavigation**

Directly after the `val visibleDestinations = remember(workMode, flexibleModeEnabled) { ... }` block inside `MultiBackStackNavigation` (≈ line 746), add:

```kotlin
    val kkcNavBarPayload = currentKkcNavBarPayload(
        destinations = visibleDestinations,
        supplyCount = supplyNotificationCount,
        safetyCount = safetyNotificationCount
    )
```

Then change each `launchTimecardApp(` call inside `MultiBackStackNavigation` to pass `navBar = kkcNavBarPayload` on the same line:
- HoursLoginDialog `onLogin`: `launchTimecardApp(context, EmployeeDirectory.resolveNameOrPin(name), navBar = kkcNavBarPayload)`
- ClockOutDialog `onConfirm`: `launchTimecardApp(context, employeeName.ifBlank { null }, pending.jobNumber, hours.toString(), navBar = kkcNavBarPayload)`
- AppBottomNavBar `onNavigate` HOURS branch: `launchTimecardApp(context, employeeName.takeIf { it.isNotBlank() }, navBar = kkcNavBarPayload)`

Change the `HoursTabHost(` call (≈ line 1090) to add `navBar = kkcNavBarPayload,` and change `HoursTabHost` itself:

```kotlin
@Composable
private fun HoursTabHost(
    navController: NavHostController,
    employeeName: String,
    isTabSelected: Boolean,
    navBar: KkcNavBarPayload
) {
    val context = LocalContext.current

    LaunchedEffect(isTabSelected) {
        if (isTabSelected) {
            launchTimecardApp(context, employeeName.takeIf { it.isNotBlank() }, navBar = navBar)
        }
    }

    NavHost(navController = navController, startDestination = "hours", modifier = Modifier.fillMaxSize()) {
        composable("hours") { Box(modifier = Modifier.fillMaxSize()) }
    }
}
```

- [ ] **Step 6: Build the payload in LegacySingleStackNavigation**

Directly after `val visibleDestinations = remember(workMode, flexibleModeEnabled) { ... }` inside `LegacySingleStackNavigation` (≈ line 2679), add the same block:

```kotlin
    val kkcNavBarPayload = currentKkcNavBarPayload(
        destinations = visibleDestinations,
        supplyCount = supplyNotificationCount,
        safetyCount = safetyNotificationCount
    )
```

Change each `launchTimecardApp(` call inside `LegacySingleStackNavigation`:
- `composable("hours")` LaunchedEffect: `launchTimecardApp(context, legacySessionName, navBar = kkcNavBarPayload)`
- `composable("hours")` HoursLoginDialog: `launchTimecardApp(context, name, navBar = kkcNavBarPayload)`
- HoursLoginDialog (≈ 3959): `launchTimecardApp(legacyContext, EmployeeDirectory.resolveNameOrPin(name), navBar = kkcNavBarPayload)`
- ClockOutDialog (≈ 3988): `launchTimecardApp(legacyContext, employeeName.ifBlank { null }, pending.jobNumber, hours.toString(), navBar = kkcNavBarPayload)`
- AppBottomNavBar HOURS branch (≈ 4018): `launchTimecardApp(legacyContext, employeeName.takeIf { it.isNotBlank() }, navBar = kkcNavBarPayload)`

If any call site you find is not listed here, give it `navBar = kkcNavBarPayload` too — the test enforces all of them.

- [ ] **Step 7: Run tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.*"`
Expected: PASS, including `LegacyStandardsTransitionWiringTest` (it slices NavGraph.kt between `LegacySingleStackNavigation(` and `HoursTabHost(` — keep those markers intact).

- [ ] **Step 8: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/KkcNavBarPayloadBuilder.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/test/java/com/kkc/sheettracker/navigation/HoursNavBarMirrorWiringTest.kt
git commit -m "feat(hours-navbar): send resolved navbar look to Hours Tracker

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: KKC receives return taps

**Files:**
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\MainActivity.kt` (`onCreate` ≈ line 326 where `handleNotificationIntent(intent)` is called; `onNewIntent` ≈ 672)
- Modify: `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\navigation\NavGraph.kt` (both hosts' `AppBottomNavBar(... onNavigate = ...)`)
- Test: `C:\Scripts\KKCSheetTracker\app\src\test\java\com\kkc\sheettracker\navigation\HoursNavBarMirrorWiringTest.kt` (add tests)

**Interfaces:**
- Consumes: `ExternalNavRequests.postFromRoute`, `ExternalNavEffect` (Task 2); `KkcNavBarContract.EXTRA_NAV_DESTINATION` (Task 1); `kkcNavBarPayload` (Task 3); `CalculatorOverlayState.setOpen(Boolean)` (existing).
- Produces: a local `val navigateFromBar: (NavDestination) -> Unit` in each host, used by both `AppBottomNavBar(onNavigate = navigateFromBar)` and `ExternalNavEffect`.

- [ ] **Step 1: Add the failing wiring tests**

Append to `HoursNavBarMirrorWiringTest`:

```kotlin
    private val mainActivity by lazy { source("MainActivity.kt") }

    @Test
    fun bothNavHostsRouteExternalRequestsThroughTheBarNavigator() {
        assertEquals(2, Regex("ExternalNavEffect\\(").findAll(navGraph).count())
        assertEquals(2, Regex("onNavigate = navigateFromBar").findAll(navGraph).count())
        assertEquals(2, Regex("openCalculator = \\{ calculatorState\\.setOpen\\(true\\) \\}").findAll(navGraph).count())
    }

    @Test
    fun mainActivityReadsTheReturnExtraOnColdStartAndNewIntent() {
        assertTrue(mainActivity.contains("private fun handleKkcNavIntent("))
        assertTrue(mainActivity.contains("KkcNavBarContract.EXTRA_NAV_DESTINATION"))
        assertTrue(mainActivity.contains("removeExtra(KkcNavBarContract.EXTRA_NAV_DESTINATION)"))
        assertEquals(2, Regex("handleKkcNavIntent\\(intent\\)").findAll(mainActivity).count())
        // Recreation after a configuration change re-delivers the original intent; never replay it.
        assertTrue(mainActivity.contains("if (savedInstanceState == null) handleKkcNavIntent(intent)"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.HoursNavBarMirrorWiringTest"`
Expected: the two new tests FAIL.

- [ ] **Step 3: MainActivity intake**

Add the import `import com.kkc.sheettracker.navigation.ExternalNavRequests` and `import com.kkc.sheettracker.navigation.KkcNavBarContract` (and `com.kkc.sheettracker.logging.AppLog` if not already imported).

In `onCreate`, on the line after the existing `handleNotificationIntent(intent)` (≈ 326), add:

```kotlin
        if (savedInstanceState == null) handleKkcNavIntent(intent)
```

In `onNewIntent`, after `handleNotificationIntent(intent)`, add:

```kotlin
        handleKkcNavIntent(intent)
```

Add next to `handleNotificationIntent`:

```kotlin
    /** Hours Tracker's mirrored navbar sends the tapped destination here (see KkcNavBarContract). */
    private fun handleKkcNavIntent(intent: Intent?) {
        val route = intent?.getStringExtra(KkcNavBarContract.EXTRA_NAV_DESTINATION) ?: return
        intent.removeExtra(KkcNavBarContract.EXTRA_NAV_DESTINATION)
        if (!ExternalNavRequests.postFromRoute(route)) {
            AppLog.d("KKC_NAV", "external_nav_rejected route=$route")
        }
    }
```

- [ ] **Step 4: MultiBackStackNavigation — share the bar navigator**

Directly above the `// Nav bar as true overlay` comment (≈ line 1233), add:

```kotlin
        val navigateFromBar: (NavDestination) -> Unit = { dest ->
            if (dest == NavDestination.HOURS) {
                launchTimecardApp(context, employeeName.takeIf { it.isNotBlank() }, navBar = kkcNavBarPayload)
            } else {
                val targetTab = TopLevelTab.fromDestination(dest)
                if ((selectedTab == TopLevelTab.JOBS || selectedTab == TopLevelTab.SUPPLY) &&
                    (targetTab == TopLevelTab.JOBS || targetTab == TopLevelTab.SUPPLY)) {
                    navBarDeco.keepSearchDeco = true
                }
                coordinator.navigateTopLevel(targetTab)
            }
        }
        ExternalNavEffect(
            visibleDestinations = visibleDestinations,
            navigate = navigateFromBar,
            openCalculator = { calculatorState.setOpen(true) }
        )
```

Replace the whole `onNavigate = { dest -> ... }` lambda of that host's `AppBottomNavBar(` with `onNavigate = navigateFromBar`. (The HOURS `launchTimecardApp` line from Task 3 moves into `navigateFromBar`; the wiring test still sees it with `navBar = `.)

- [ ] **Step 5: LegacySingleStackNavigation — share the bar navigator**

Directly above that host's `// Nav bar as true overlay` comment (≈ line 3998), add:

```kotlin
        val navigateFromBar: (NavDestination) -> Unit = navigateFromBar@{ dest ->
            if (dest == NavDestination.HOURS) {
                launchTimecardApp(legacyContext, employeeName.takeIf { it.isNotBlank() }, navBar = kkcNavBarPayload)
                return@navigateFromBar
            }
            if (currentRoute == dest.route) return@navigateFromBar
            check(dest.route in visibleDestinations.map { it.route }) {
                "Invalid top-level destination route: ${dest.route}"
            }
            if ((currentNavDest == NavDestination.JOBS || currentNavDest == NavDestination.SUPPLY) &&
                (dest == NavDestination.JOBS || dest == NavDestination.SUPPLY)) {
                navBarDeco.keepSearchDeco = true
            }
            navController.navigate(dest.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = false
                }
                launchSingleTop = true
                restoreState = false
            }
        }
        ExternalNavEffect(
            visibleDestinations = visibleDestinations,
            navigate = navigateFromBar,
            openCalculator = { calculatorState.setOpen(true) }
        )
```

Replace that host's `AppBottomNavBar(` `onNavigate = { dest -> ... }` lambda with `onNavigate = navigateFromBar`. The body above is the existing lambda verbatim with `return@AppBottomNavBar` → `return@navigateFromBar`; diff it against the old lambda to confirm nothing else changed. The Standards routes' no-op transitions are untouched because navigation still goes through `navController.navigate(dest.route)`.

- [ ] **Step 6: Run tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.*"`
Expected: PASS.

- [ ] **Step 7: Full KKC unit suite + compile**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: same result as the Task 0 baseline (only the known PdfMarkup MotionEvent env failure).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/MainActivity.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/test/java/com/kkc/sheettracker/navigation/HoursNavBarMirrorWiringTest.kt
git commit -m "feat(hours-navbar): route Hours Tracker navbar taps back into KKC

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: HT contract mirror, parser, and return request

All HT paths below are under `C:\Scripts\Hours Tracker\AndroidApp`. Run HT gradle commands from that directory.

**Files:**
- Create: `app/src/main/java/com/example/timecard/kkcnav/KkcNavBarContract.kt`
- Create: `app/src/main/java/com/example/timecard/kkcnav/KkcReturnNavigation.kt`
- Test: `app/src/test/java/com/example/timecard/kkcnav/KkcNavBarContractTest.kt`
- Test: `app/src/test/java/com/example/timecard/kkcnav/KkcReturnNavigationTest.kt`

**Interfaces:**
- Produces: `object KkcNavBarContract` (same constants as KKC Task 1); `data class KkcNavBarPayload` (same fields as KKC) with `companion object { fun parse(get: (String) -> Any?): KkcNavBarPayload? }`; `data class KkcReturnRequest(packageName: String, className: String, flags: Int, destination: String)`; `object KkcReturnNavigation { fun requestFor(route: String): KkcReturnRequest?; fun navigate(activity: Activity, route: String, animationsDisabled: Boolean) }`.

- [ ] **Step 1: Write the failing contract test**

```kotlin
package com.example.timecard.kkcnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KkcNavBarContractTest {

    /** Exactly what KKC's KkcNavBarPayload.toExtras() produces for its test sample. */
    private fun kkcExtras(): MutableMap<String, Any> = mutableMapOf(
        "extra_kkc_navbar_version" to 1,
        "extra_kkc_navbar_destinations" to arrayOf("jobs", "hours", "timecard", "supply", "standards"),
        "extra_kkc_navbar_supply_count" to 3,
        "extra_kkc_navbar_safety_count" to 1,
        "extra_kkc_navbar_dark" to true,
        "extra_kkc_navbar_primary" to 0xFF112233.toInt(),
        "extra_kkc_navbar_on_surface_variant" to 0xFF445566.toInt(),
        "extra_kkc_navbar_surface_variant" to 0xFF778899.toInt(),
        "extra_kkc_navbar_frosted_base" to 0xFFAABBCC.toInt(),
        "extra_kkc_navbar_frosted_content" to 0xFF000000.toInt(),
        "extra_kkc_navbar_frosted_alpha" to 0.72f,
        "extra_kkc_navbar_frosted_blur_dp" to 14f,
        "extra_kkc_navbar_bold_mode" to true,
        "extra_kkc_navbar_bold_gradient" to intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
        "extra_kkc_navbar_badge_container" to 0xFFB3261E.toInt(),
        "extra_kkc_navbar_badge_content" to 0xFFFFFFFF.toInt(),
        "extra_kkc_navbar_indicator_corner_dp" to 9f,
        "extra_kkc_navbar_anim_disabled" to false,
        "extra_kkc_navbar_blur_disabled" to false,
        "extra_kkc_navbar_shadows_disabled" to true
    )

    @Test
    fun parsesTheKkcSample() {
        val payload = KkcNavBarPayload.parse { kkcExtras()[it] }
        assertEquals(
            KkcNavBarPayload(
                destinations = listOf("jobs", "hours", "timecard", "supply", "standards"),
                supplyCount = 3,
                safetyCount = 1,
                dark = true,
                primary = 0xFF112233.toInt(),
                onSurfaceVariant = 0xFF445566.toInt(),
                surfaceVariant = 0xFF778899.toInt(),
                frostedBase = 0xFFAABBCC.toInt(),
                frostedContent = 0xFF000000.toInt(),
                frostedAlpha = 0.72f,
                frostedBlurDp = 14f,
                boldMode = true,
                boldGradient = listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
                badgeContainer = 0xFFB3261E.toInt(),
                badgeContent = 0xFFFFFFFF.toInt(),
                indicatorCornerDp = 9f,
                animDisabled = false,
                blurDisabled = false,
                shadowsDisabled = true
            ),
            payload
        )
    }

    @Test
    fun keySetMatchesKkc() {
        assertEquals(CANONICAL_KEYS, kkcExtras().keys)
        assertEquals(CANONICAL_KEYS, KkcNavBarContract.ALL_NAVBAR_KEYS)
    }

    @Test
    fun oldKkcWithoutExtrasShowsNoBar() {
        assertNull(KkcNavBarPayload.parse { null })
    }

    @Test
    fun unsupportedVersionShowsNoBar() {
        val extras = kkcExtras().apply { put("extra_kkc_navbar_version", 2) }
        assertNull(KkcNavBarPayload.parse { extras[it] })
    }

    @Test
    fun missingRequiredColorShowsNoBar() {
        val extras = kkcExtras().apply { remove("extra_kkc_navbar_primary") }
        assertNull(KkcNavBarPayload.parse { extras[it] })
    }

    @Test
    fun emptyDestinationsShowsNoBar() {
        val extras = kkcExtras().apply { put("extra_kkc_navbar_destinations", emptyArray<String>()) }
        assertNull(KkcNavBarPayload.parse { extras[it] })
    }

    @Test
    fun missingBadgeCountsDefaultToZero() {
        val extras = kkcExtras().apply {
            remove("extra_kkc_navbar_supply_count")
            remove("extra_kkc_navbar_safety_count")
        }
        val payload = KkcNavBarPayload.parse { extras[it] }!!
        assertEquals(0, payload.supplyCount)
        assertEquals(0, payload.safetyCount)
    }

    companion object {
        // KEEP IN SYNC with KKCSheetTracker
        // app/src/test/java/com/kkc/sheettracker/navigation/KkcNavBarContractTest.kt CANONICAL_KEYS.
        val CANONICAL_KEYS = setOf(
            "extra_kkc_navbar_version",
            "extra_kkc_navbar_destinations",
            "extra_kkc_navbar_supply_count",
            "extra_kkc_navbar_safety_count",
            "extra_kkc_navbar_dark",
            "extra_kkc_navbar_primary",
            "extra_kkc_navbar_on_surface_variant",
            "extra_kkc_navbar_surface_variant",
            "extra_kkc_navbar_frosted_base",
            "extra_kkc_navbar_frosted_content",
            "extra_kkc_navbar_frosted_alpha",
            "extra_kkc_navbar_frosted_blur_dp",
            "extra_kkc_navbar_bold_mode",
            "extra_kkc_navbar_bold_gradient",
            "extra_kkc_navbar_badge_container",
            "extra_kkc_navbar_badge_content",
            "extra_kkc_navbar_indicator_corner_dp",
            "extra_kkc_navbar_anim_disabled",
            "extra_kkc_navbar_blur_disabled",
            "extra_kkc_navbar_shadows_disabled"
        )
    }
}
```

- [ ] **Step 2: Write the failing return test**

```kotlin
package com.example.timecard.kkcnav

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KkcReturnNavigationTest {

    @Test
    fun destinationTapTargetsKkcMainActivityOnTopOfItsTask() {
        val request = KkcReturnNavigation.requestFor("supply")!!
        assertEquals("com.kkc.sheettracker", request.packageName)
        assertEquals("com.kkc.sheettracker.MainActivity", request.className)
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, request.flags)
        assertEquals("supply", request.destination)
    }

    @Test
    fun calculatorTapIsSentAsCalculator() {
        assertEquals("calculator", KkcReturnNavigation.requestFor("calculator")!!.destination)
    }

    @Test
    fun hoursTapDoesNothing() {
        assertNull(KkcReturnNavigation.requestFor("hours"))
    }

    @Test
    fun blankTapDoesNothing() {
        assertNull(KkcReturnNavigation.requestFor(""))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.timecard.kkcnav.*"`
Expected: FAIL — `KkcNavBarPayload` / `KkcReturnNavigation` unresolved.

- [ ] **Step 4: Write `KkcNavBarContract.kt`**

```kotlin
package com.example.timecard.kkcnav

/**
 * Mirror of KKCSheetTracker navigation/KkcNavBarContract.kt. Keys, VERSION and the payload
 * fields must be identical on both sides. Sync header is expanded in Task 9.
 */
object KkcNavBarContract {
    const val VERSION = 1

    const val EXTRA_VERSION = "extra_kkc_navbar_version"
    const val EXTRA_DESTINATIONS = "extra_kkc_navbar_destinations"
    const val EXTRA_SUPPLY_COUNT = "extra_kkc_navbar_supply_count"
    const val EXTRA_SAFETY_COUNT = "extra_kkc_navbar_safety_count"
    const val EXTRA_DARK = "extra_kkc_navbar_dark"
    const val EXTRA_PRIMARY = "extra_kkc_navbar_primary"
    const val EXTRA_ON_SURFACE_VARIANT = "extra_kkc_navbar_on_surface_variant"
    const val EXTRA_SURFACE_VARIANT = "extra_kkc_navbar_surface_variant"
    const val EXTRA_FROSTED_BASE = "extra_kkc_navbar_frosted_base"
    const val EXTRA_FROSTED_CONTENT = "extra_kkc_navbar_frosted_content"
    const val EXTRA_FROSTED_ALPHA = "extra_kkc_navbar_frosted_alpha"
    const val EXTRA_FROSTED_BLUR_DP = "extra_kkc_navbar_frosted_blur_dp"
    const val EXTRA_BOLD_MODE = "extra_kkc_navbar_bold_mode"
    const val EXTRA_BOLD_GRADIENT = "extra_kkc_navbar_bold_gradient"
    const val EXTRA_BADGE_CONTAINER = "extra_kkc_navbar_badge_container"
    const val EXTRA_BADGE_CONTENT = "extra_kkc_navbar_badge_content"
    const val EXTRA_INDICATOR_CORNER_DP = "extra_kkc_navbar_indicator_corner_dp"
    const val EXTRA_ANIM_DISABLED = "extra_kkc_navbar_anim_disabled"
    const val EXTRA_BLUR_DISABLED = "extra_kkc_navbar_blur_disabled"
    const val EXTRA_SHADOWS_DISABLED = "extra_kkc_navbar_shadows_disabled"

    val ALL_NAVBAR_KEYS = setOf(
        EXTRA_VERSION, EXTRA_DESTINATIONS, EXTRA_SUPPLY_COUNT, EXTRA_SAFETY_COUNT, EXTRA_DARK,
        EXTRA_PRIMARY, EXTRA_ON_SURFACE_VARIANT, EXTRA_SURFACE_VARIANT, EXTRA_FROSTED_BASE,
        EXTRA_FROSTED_CONTENT, EXTRA_FROSTED_ALPHA, EXTRA_FROSTED_BLUR_DP, EXTRA_BOLD_MODE,
        EXTRA_BOLD_GRADIENT, EXTRA_BADGE_CONTAINER, EXTRA_BADGE_CONTENT, EXTRA_INDICATOR_CORNER_DP,
        EXTRA_ANIM_DISABLED, EXTRA_BLUR_DISABLED, EXTRA_SHADOWS_DISABLED
    )

    // Return path (Hours Tracker → KKC)
    const val EXTRA_NAV_DESTINATION = "extra_kkc_nav_destination"
    const val DEST_CALCULATOR = "calculator"
    const val DEST_HOURS = "hours"
    const val KKC_PACKAGE = "com.kkc.sheettracker"
    const val KKC_ACTIVITY = "com.kkc.sheettracker.MainActivity"
}

/** Resolved KKC navbar look. Colors are ARGB ints. Same class exists in KKC. */
data class KkcNavBarPayload(
    val destinations: List<String>,
    val supplyCount: Int,
    val safetyCount: Int,
    val dark: Boolean,
    val primary: Int,
    val onSurfaceVariant: Int,
    val surfaceVariant: Int,
    val frostedBase: Int,
    val frostedContent: Int,
    val frostedAlpha: Float,
    val frostedBlurDp: Float,
    val boldMode: Boolean,
    val boldGradient: List<Int>,
    val badgeContainer: Int,
    val badgeContent: Int,
    val indicatorCornerDp: Float,
    val animDisabled: Boolean,
    val blurDisabled: Boolean,
    val shadowsDisabled: Boolean
) {
    companion object {
        /**
         * Returns null (no bar, current behavior) unless the version matches and every required
         * value is present with the right type. [get] reads one intent extra by key.
         */
        fun parse(get: (String) -> Any?): KkcNavBarPayload? = with(KkcNavBarContract) {
            if (get(EXTRA_VERSION) as? Int != VERSION) return null
            val destinations = (get(EXTRA_DESTINATIONS) as? Array<*>)
                ?.mapNotNull { it as? String }
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            val gradient = (get(EXTRA_BOLD_GRADIENT) as? IntArray)?.toList()?.takeIf { it.isNotEmpty() }
                ?: return null
            fun int(key: String) = get(key) as? Int
            fun float(key: String) = get(key) as? Float
            fun bool(key: String) = get(key) as? Boolean
            KkcNavBarPayload(
                destinations = destinations,
                supplyCount = int(EXTRA_SUPPLY_COUNT) ?: 0,
                safetyCount = int(EXTRA_SAFETY_COUNT) ?: 0,
                dark = bool(EXTRA_DARK) ?: return null,
                primary = int(EXTRA_PRIMARY) ?: return null,
                onSurfaceVariant = int(EXTRA_ON_SURFACE_VARIANT) ?: return null,
                surfaceVariant = int(EXTRA_SURFACE_VARIANT) ?: return null,
                frostedBase = int(EXTRA_FROSTED_BASE) ?: return null,
                frostedContent = int(EXTRA_FROSTED_CONTENT) ?: return null,
                frostedAlpha = float(EXTRA_FROSTED_ALPHA) ?: return null,
                frostedBlurDp = float(EXTRA_FROSTED_BLUR_DP) ?: return null,
                boldMode = bool(EXTRA_BOLD_MODE) ?: return null,
                boldGradient = gradient,
                badgeContainer = int(EXTRA_BADGE_CONTAINER) ?: return null,
                badgeContent = int(EXTRA_BADGE_CONTENT) ?: return null,
                indicatorCornerDp = float(EXTRA_INDICATOR_CORNER_DP) ?: return null,
                animDisabled = bool(EXTRA_ANIM_DISABLED) ?: return null,
                blurDisabled = bool(EXTRA_BLUR_DISABLED) ?: return null,
                shadowsDisabled = bool(EXTRA_SHADOWS_DISABLED) ?: return null
            )
        }
    }
}
```

- [ ] **Step 5: Write `KkcReturnNavigation.kt`**

```kotlin
package com.example.timecard.kkcnav

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.util.Log

data class KkcReturnRequest(
    val packageName: String,
    val className: String,
    val flags: Int,
    val destination: String
)

/**
 * Sends a tap on the mirrored navbar back to KKCSheetTracker. Hours Tracker runs on top of KKC's
 * MainActivity in KKC's task, so CLEAR_TOP|SINGLE_TOP pops Hours Tracker and delivers onNewIntent
 * to the existing KKC activity (state preserved).
 */
object KkcReturnNavigation {

    fun requestFor(route: String): KkcReturnRequest? {
        if (route.isBlank() || route == KkcNavBarContract.DEST_HOURS) return null
        return KkcReturnRequest(
            packageName = KkcNavBarContract.KKC_PACKAGE,
            className = KkcNavBarContract.KKC_ACTIVITY,
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            destination = route
        )
    }

    fun navigate(activity: Activity, route: String, animationsDisabled: Boolean) {
        // A second tap while the first is closing Hours Tracker must not launch KKC twice.
        if (activity.isFinishing) return
        val request = requestFor(route) ?: return
        val intent = Intent().apply {
            setClassName(request.packageName, request.className)
            addFlags(request.flags)
            putExtra(KkcNavBarContract.EXTRA_NAV_DESTINATION, request.destination)
        }
        val enter = if (animationsDisabled) 0 else android.R.anim.fade_in
        val exit = if (animationsDisabled) 0 else android.R.anim.fade_out
        try {
            activity.startActivity(intent, ActivityOptions.makeCustomAnimation(activity, enter, exit).toBundle())
        } catch (e: ActivityNotFoundException) {
            Log.w("KkcNav", "KKCSheetTracker not available; closing Hours Tracker", e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, enter, exit)
        }
        activity.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(enter, exit)
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.timecard.kkcnav.*"`
Expected: PASS (11 tests).

- [ ] **Step 7: Commit (HT repo)**

```bash
git add app/src/main/java/com/example/timecard/kkcnav app/src/test/java/com/example/timecard/kkcnav
git commit -m "feat(kkc-navbar): mirror KKC navbar contract and return navigation

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: HT dependencies, icons, and font

**Files:**
- Modify: `app/build.gradle.kts` (dependencies block)
- Create (copy): `app/src/main/java/com/example/timecard/kkcnav/KkcNavIcons.kt` ← KKC `ui/components/icons/NavIcons.kt`
- Create (copy): `app/src/main/java/com/example/timecard/kkcnav/KkcIconDsl.kt` ← KKC `ui/components/icons/IconDsl.kt`
- Create (copy): `app/src/main/res/values/font_certs.xml` ← KKC `app/src/main/res/values/font_certs.xml`
- Create: `app/src/main/java/com/example/timecard/kkcnav/KkcNavTypography.kt`
- Test: `app/src/test/java/com/example/timecard/kkcnav/KkcNavIconsTest.kt`

**Interfaces:**
- Produces: icon vals `NavDashboardSelected/Unselected`, `NavJobs…`, `NavSearch…`, `NavHours…`, `NavTimeclock…`, `NavSupply…`, `NavLibrary…`, `NavSettings…`, `NavCalculator…` in package `com.example.timecard.kkcnav`; `internal val KkcNavLabelStyle: TextStyle`.

- [ ] **Step 1: Add dependencies**

In `app/build.gradle.kts`, under the Compose BOM `implementation("androidx.compose.foundation:foundation")` line, add:

```kotlin
    implementation("androidx.compose.ui:ui-text-google-fonts")

    // KKC navbar mirror — same Haze version as KKCSheetTracker (keep in sync)
    implementation("dev.chrisbanes.haze:haze:1.5.1")
```

- [ ] **Step 2: Copy icon, DSL, and font-cert files**

```bash
cp /c/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/components/icons/NavIcons.kt "/c/Scripts/Hours Tracker/AndroidApp/app/src/main/java/com/example/timecard/kkcnav/KkcNavIcons.kt"
cp /c/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/components/icons/IconDsl.kt "/c/Scripts/Hours Tracker/AndroidApp/app/src/main/java/com/example/timecard/kkcnav/KkcIconDsl.kt"
mkdir -p "/c/Scripts/Hours Tracker/AndroidApp/app/src/main/res/values"
cp /c/Scripts/KKCSheetTracker/app/src/main/res/values/font_certs.xml "/c/Scripts/Hours Tracker/AndroidApp/app/src/main/res/values/font_certs.xml"
```

Then, with the Edit tool, change the first line of both copied Kotlin files from `package com.kkc.sheettracker.ui.components.icons` to `package com.example.timecard.kkcnav`. Change nothing else in them (the Task 9 sync header goes on top later).

- [ ] **Step 3: Write the failing icon test**

```kotlin
package com.example.timecard.kkcnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KkcNavIconsTest {

    private val pairs = listOf(
        NavDashboardSelected to NavDashboardUnselected,
        NavJobsSelected to NavJobsUnselected,
        NavSearchSelected to NavSearchUnselected,
        NavHoursSelected to NavHoursUnselected,
        NavTimeclockSelected to NavTimeclockUnselected,
        NavSupplySelected to NavSupplyUnselected,
        NavLibrarySelected to NavLibraryUnselected,
        NavSettingsSelected to NavSettingsUnselected,
        NavCalculatorSelected to NavCalculatorUnselected
    )

    @Test
    fun selectedAndUnselectedDiffer() {
        pairs.forEach { (s, u) -> assertNotEquals(s.name, u.name) }
    }

    @Test
    fun allIconsAreTheKkcNavFamilyOnA24UnitViewport() {
        pairs.flatMap { listOf(it.first, it.second) }.forEach {
            assertTrue(it.name, it.name.startsWith("Nav"))
            assertEquals(it.name, 24f, it.viewportWidth)
            assertEquals(it.name, 24f, it.viewportHeight)
        }
    }

    @Test
    fun labelStyleMatchesKkcLabelSmall() {
        assertEquals(11f, KkcNavLabelStyle.fontSize.value)
        assertEquals(16f, KkcNavLabelStyle.lineHeight.value)
        assertEquals(0.5f, KkcNavLabelStyle.letterSpacing.value)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Medium, KkcNavLabelStyle.fontWeight)
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.timecard.kkcnav.KkcNavIconsTest"`
Expected: FAIL — `KkcNavLabelStyle` unresolved (icons resolve from the copies).

- [ ] **Step 5: Write `KkcNavTypography.kt`**

```kotlin
package com.example.timecard.kkcnav

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp

// Copy of KKCSheetTracker ui/theme/Type.kt InterFontFamily + KKCTypography.labelSmall.
private val kkcFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = com.example.timecard.R.array.com_google_android_gms_fonts_certs
)

private val kkcInterFont = GoogleFont("Inter")

internal val KkcInterFontFamily = FontFamily(
    Font(googleFont = kkcInterFont, fontProvider = kkcFontProvider, weight = FontWeight.Normal),
    Font(googleFont = kkcInterFont, fontProvider = kkcFontProvider, weight = FontWeight.Medium),
    Font(googleFont = kkcInterFont, fontProvider = kkcFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = kkcInterFont, fontProvider = kkcFontProvider, weight = FontWeight.Bold)
)

internal val KkcNavLabelStyle = TextStyle(
    fontFamily = KkcInterFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.5.sp
)
```

- [ ] **Step 6: Run tests to verify they pass, then compile**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.timecard.kkcnav.*"`
Expected: PASS.
Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If `internal` helper names in `KkcIconDsl.kt` (`line`, `solid`, `block`, `roundRect`, `circle`, `kkcIcon`, `STROKE`, `DUOTONE`) clash with existing HT declarations in the same package, there are none today (the package is new) — a clash means the file landed in the wrong package.

- [ ] **Step 7: Commit (HT repo)**

```bash
git add app/build.gradle.kts app/src/main/java/com/example/timecard/kkcnav app/src/main/res/values/font_certs.xml app/src/test/java/com/example/timecard/kkcnav/KkcNavIconsTest.kt
git commit -m "feat(kkc-navbar): add Haze, Inter font and KKC nav icons

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: HT `KkcNavBar` composable

**Files:**
- Create: `app/src/main/java/com/example/timecard/kkcnav/KkcNavBarModel.kt`
- Create: `app/src/main/java/com/example/timecard/kkcnav/KkcNavBar.kt`
- Test: `app/src/test/java/com/example/timecard/kkcnav/KkcNavBarModelTest.kt`

**Interfaces:**
- Consumes: `KkcNavBarPayload`, `KkcNavBarContract` (Task 5); icons + `KkcNavLabelStyle` (Task 6).
- Produces: `@Composable fun KkcNavBar(payload: KkcNavBarPayload, hazeState: HazeState?, onNavigate: (String) -> Unit, modifier: Modifier = Modifier)`; model helpers `KKC_NAV_LABELS`, `kkcNavSlots`, `kkcNavLabel`, `kkcNavIcon`, `kkcBadgeCount`, `kkcNavTints`, `kkcFrostedFillAlpha`.

- [ ] **Step 1: Write the failing model test**

```kotlin
package com.example.timecard.kkcnav

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class KkcNavBarModelTest {

    private val payload = KkcNavBarPayload(
        destinations = listOf("jobs", "hours", "timecard", "supply", "standards"),
        supplyCount = 4, safetyCount = 2, dark = false,
        primary = 0xFF1565C0.toInt(), onSurfaceVariant = 0xFF444444.toInt(), surfaceVariant = 0xFFE0E0E0.toInt(),
        frostedBase = 0xFFFFFFFF.toInt(), frostedContent = 0xFF000000.toInt(),
        frostedAlpha = 0.72f, frostedBlurDp = 14f, boldMode = false,
        boldGradient = listOf(0xFF1565C0.toInt()),
        badgeContainer = 0xFFB3261E.toInt(), badgeContent = 0xFFFFFFFF.toInt(),
        indicatorCornerDp = 9f, animDisabled = false, blurDisabled = false, shadowsDisabled = false
    )

    @Test
    fun calculatorSlotSitsImmediatelyBeforeHours() {
        assertEquals(
            listOf("jobs", "calculator", "hours", "timecard", "supply", "standards"),
            kkcNavSlots(payload.destinations)
        )
    }

    @Test
    fun unknownRoutesFromANewerKkcAreDropped() {
        assertEquals(listOf("jobs", "calculator", "hours"), kkcNavSlots(listOf("jobs", "bogus", "hours")))
    }

    @Test
    fun labelsMatchKkcNavDestination() {
        // KEEP IN SYNC with KKC ui/components/AppScaffold.kt enum NavDestination labels.
        assertEquals(
            mapOf(
                "dashboard" to "Dashboard", "jobs" to "Jobs", "search" to "Search", "hours" to "Hours",
                "timecard" to "Timeclock", "supply" to "Supply", "settings" to "Settings", "standards" to "Library"
            ),
            KKC_NAV_LABELS
        )
        assertEquals("Calc", kkcNavLabel("calculator"))
    }

    @Test
    fun everySlotHasBothIconStates() {
        (KKC_NAV_LABELS.keys + "calculator").forEach { route ->
            kkcNavIcon(route, selected = true)
            kkcNavIcon(route, selected = false)
        }
    }

    @Test
    fun badgesOnlyOnSupplyAndLibrary() {
        assertEquals(4, kkcBadgeCount("supply", payload))
        assertEquals(2, kkcBadgeCount("standards", payload))
        assertEquals(0, kkcBadgeCount("jobs", payload))
        assertEquals(0, kkcBadgeCount("supply", payload.copy(supplyCount = 0)))
    }

    @Test
    fun normalTintsUsePrimaryAndOnSurfaceVariant() {
        val tints = kkcNavTints(payload)
        assertEquals(Color(payload.primary), tints.selected)
        assertEquals(Color(payload.onSurfaceVariant), tints.unselected)
    }

    @Test
    fun boldTintsUseTheFrostedContentColor() {
        val tints = kkcNavTints(payload.copy(boldMode = true))
        assertEquals(Color(payload.frostedContent), tints.selected)
        assertEquals(Color(payload.frostedContent).copy(alpha = 0.8f), tints.unselected)
    }

    @Test
    fun frostedAlphaIsClampedLikeKkc() {
        assertEquals(0.5f, kkcFrostedFillAlpha(payload.copy(frostedAlpha = 0.3f)))
        assertEquals(0.95f, kkcFrostedFillAlpha(payload.copy(frostedAlpha = 0.99f)))
        assertEquals(0.72f, kkcFrostedFillAlpha(payload))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.timecard.kkcnav.KkcNavBarModelTest"`
Expected: FAIL — model functions unresolved.

- [ ] **Step 3: Write `KkcNavBarModel.kt`**

```kotlin
package com.example.timecard.kkcnav

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** KKC NavDestination route → label (AppScaffold.kt enum NavDestination). */
internal val KKC_NAV_LABELS: Map<String, String> = linkedMapOf(
    "dashboard" to "Dashboard",
    "jobs" to "Jobs",
    "search" to "Search",
    "hours" to "Hours",
    "timecard" to "Timeclock",
    "supply" to "Supply",
    "settings" to "Settings",
    "standards" to "Library"
)

/** KKC draws the Calc slot immediately before HOURS (MorphingNavIconRow). Unknown routes are dropped. */
internal fun kkcNavSlots(destinations: List<String>): List<String> = buildList {
    destinations.filter { it in KKC_NAV_LABELS }.forEach { route ->
        if (route == KkcNavBarContract.DEST_HOURS) add(KkcNavBarContract.DEST_CALCULATOR)
        add(route)
    }
}

internal fun kkcNavLabel(route: String): String =
    if (route == KkcNavBarContract.DEST_CALCULATOR) "Calc" else KKC_NAV_LABELS.getValue(route)

internal fun kkcNavIcon(route: String, selected: Boolean): ImageVector = when (route) {
    "dashboard" -> if (selected) NavDashboardSelected else NavDashboardUnselected
    "jobs" -> if (selected) NavJobsSelected else NavJobsUnselected
    "search" -> if (selected) NavSearchSelected else NavSearchUnselected
    "hours" -> if (selected) NavHoursSelected else NavHoursUnselected
    "timecard" -> if (selected) NavTimeclockSelected else NavTimeclockUnselected
    "supply" -> if (selected) NavSupplySelected else NavSupplyUnselected
    "settings" -> if (selected) NavSettingsSelected else NavSettingsUnselected
    "standards" -> if (selected) NavLibrarySelected else NavLibraryUnselected
    "calculator" -> if (selected) NavCalculatorSelected else NavCalculatorUnselected
    else -> error("Unknown KKC nav route: $route")
}

/** MorphingNavIconRow: SUPPLY → supply count, STANDARDS (Library) → safety count, else none. */
internal fun kkcBadgeCount(route: String, payload: KkcNavBarPayload): Int = when (route) {
    "supply" -> payload.supplyCount
    "standards" -> payload.safetyCount
    else -> 0
}

internal data class KkcNavTints(val selected: Color, val unselected: Color)

/** MorphingNavIconRow tint rules: bold mode uses the contrast-picked frosted content color. */
internal fun kkcNavTints(payload: KkcNavBarPayload): KkcNavTints =
    if (payload.boldMode) {
        val onGlass = Color(payload.frostedContent)
        KkcNavTints(selected = onGlass, unselected = onGlass.copy(alpha = 0.8f))
    } else {
        KkcNavTints(selected = Color(payload.primary), unselected = Color(payload.onSurfaceVariant))
    }

/** MorphingNavBar: frostedTokens.backgroundAlpha.coerceIn(0.5f, 0.95f). */
internal fun kkcFrostedFillAlpha(payload: KkcNavBarPayload): Float = payload.frostedAlpha.coerceIn(0.5f, 0.95f)
```

- [ ] **Step 4: Run model test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.timecard.kkcnav.KkcNavBarModelTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Write `KkcNavBar.kt`**

```kotlin
package com.example.timecard.kkcnav

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

// Animation specs copied verbatim from KKC AppScaffold.kt (NavEasing / NavSpringDp). The mirrored
// bar only ever shows KKC's full state, so these targets never change today; they are kept so any
// future morph moves exactly like KKC's, and snap() in low-end mode matches KKC.
private val KkcNavEasing = FastOutSlowInEasing
private val KkcNavSpringDp = spring<Dp>(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow)

/**
 * Copy of KKCSheetTracker AppScaffold.kt MorphingNavBar + MorphingNavIconRow in the FULL state
 * (labels shown, not minimized, no decorations / extended controls). Hours is always the
 * selected destination. Colors come only from [payload], never from Hours Tracker's theme.
 */
@Composable
fun KkcNavBar(
    payload: KkcNavBarPayload,
    hazeState: HazeState?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val navSpec = if (!payload.animDisabled) KkcNavSpringDp else snap()
    val iconSize by animateDpAsState(22.dp, navSpec, label = "kkcNavIconSize")
    val hPad by animateDpAsState(14.dp, navSpec, label = "kkcNavItemHPad")
    val vPad by animateDpAsState(8.dp, navSpec, label = "kkcNavItemVPad")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
    ) {
        val barShape = remember { RoundedCornerShape(20.dp) }
        val frostedBase = Color(payload.frostedBase)
        val fillAlpha = kkcFrostedFillAlpha(payload)
        val blurActive = hazeState != null && !payload.blurDisabled
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = barShape,
            color = if (blurActive) Color.Transparent else frostedBase.copy(alpha = fillAlpha),
            shadowElevation = if (payload.shadowsDisabled) 0.dp else 3.dp,
            tonalElevation = 0.dp
        ) {
            val hazeModifier = if (blurActive) {
                Modifier.hazeEffect(
                    hazeState!!,
                    style = HazeDefaults.style(
                        backgroundColor = frostedBase.copy(alpha = fillAlpha),
                        blurRadius = payload.frostedBlurDp.coerceAtLeast(1f).dp
                    )
                )
            } else {
                Modifier
            }
            Box(modifier = Modifier.fillMaxWidth().then(hazeModifier)) {
                KkcNavIconRow(payload, onNavigate, iconSize, hPad, vPad)
            }
        }
    }
}

@Composable
private fun KkcNavIconRow(
    payload: KkcNavBarPayload,
    onNavigate: (String) -> Unit,
    iconSize: Dp,
    hPad: Dp,
    vPad: Dp
) {
    val indicatorShape = remember(payload.indicatorCornerDp) { RoundedCornerShape(payload.indicatorCornerDp.dp) }
    val tints = kkcNavTints(payload)
    val boldBrush = remember(payload.boldGradient) {
        val stops = payload.boldGradient.map { Color(it) }
        // KKC boldGradientColors always returns two stops (secondary falls back to primary).
        Brush.linearGradient(if (stops.size == 1) stops + stops else stops)
    }
    fun Modifier.navSelectionBackground(active: Boolean): Modifier {
        if (!active) return this
        return if (payload.boldMode) {
            background(brush = boldBrush, shape = indicatorShape, alpha = 0.55f)
        } else {
            background(color = Color(payload.surfaceVariant), shape = indicatorShape)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        kkcNavSlots(payload.destinations).forEach { route ->
            val isCalculator = route == KkcNavBarContract.DEST_CALCULATOR
            // Hours is the destination being shown; the calculator is never open in Hours Tracker.
            val selected = route == KkcNavBarContract.DEST_HOURS
            val tint = if (selected) tints.selected else tints.unselected
            val label = kkcNavLabel(route)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .clip(indicatorShape)
                        .navSelectionBackground(selected)
                        .clickable { onNavigate(route) }
                        .padding(horizontal = hPad, vertical = vPad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val iconContent = @Composable {
                        Icon(
                            kkcNavIcon(route, selected),
                            contentDescription = if (isCalculator) "Calculator" else label,
                            tint = tint,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                    val badgeCount = kkcBadgeCount(route, payload)
                    if (badgeCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge(
                                    containerColor = Color(payload.badgeContainer),
                                    contentColor = Color(payload.badgeContent)
                                ) { Text(badgeCount.toString(), style = KkcNavLabelStyle) }
                            }
                        ) { iconContent() }
                    } else {
                        iconContent()
                    }
                    if (isCalculator) {
                        Text(
                            text = label,
                            style = KkcNavLabelStyle,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = tint
                        )
                    } else {
                        Text(
                            text = label,
                            style = KkcNavLabelStyle,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
```

Note: `KkcNavEasing` is unused today; if the compiler warns, keep it — it documents the KKC spec for the next morph. If lint fails the build on unused privals (it should not; `abortOnError = false`), delete only `KkcNavEasing`.

- [ ] **Step 6: Compile and run HT tests**

Run: `.\gradlew.bat :app:compileDebugKotlin` → BUILD SUCCESSFUL.
Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.timecard.kkcnav.*"` → PASS.

- [ ] **Step 7: Commit (HT repo)**

```bash
git add app/src/main/java/com/example/timecard/kkcnav/KkcNavBarModel.kt app/src/main/java/com/example/timecard/kkcnav/KkcNavBar.kt app/src/test/java/com/example/timecard/kkcnav/KkcNavBarModelTest.kt
git commit -m "feat(kkc-navbar): add mirrored KKC navbar composable

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: HT wiring — parse, overlay, inset, return

**Files:**
- Modify: `app/src/main/java/com/example/timecard/MainActivity.kt` (≈ lines 65–79)
- Modify: `app/src/main/java/com/example/timecard/TimecardApp.kt` (signature ≈ 56–64; root `Box` ≈ 223–342)
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/example/timecard/kkcnav/KkcNavBarWiringTest.kt`

**Interfaces:**
- Consumes: `KkcNavBarPayload.parse` (Task 5), `KkcReturnNavigation.navigate` (Task 5), `KkcNavBar` (Task 7).
- Produces: `TimecardApp(..., kkcNavBar: KkcNavBarPayload? = null, ...)`.

- [ ] **Step 1: Write the failing wiring test**

```kotlin
package com.example.timecard.kkcnav

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class KkcNavBarWiringTest {

    private fun read(relative: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/$relative")
            if (candidate.exists()) return candidate.readText()
            val direct = File(dir, "src/main/$relative")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Unable to locate $relative")
    }

    @Test
    fun mainActivityParsesThePayloadOnlyWhenLaunchedByKkc() {
        val src = read("java/com/example/timecard/MainActivity.kt")
        assertTrue(src.contains("if (launchedByKkc) KkcNavBarPayload.parse"))
        assertTrue(src.contains("kkcNavBar = kkcNavBar"))
    }

    @Test
    fun timecardAppIsTheHazeSourceAndDrawsTheBarOverlay() {
        val src = read("java/com/example/timecard/TimecardApp.kt")
        assertTrue(src.contains("hazeSource(kkcHazeState)"))
        assertTrue(src.contains("KkcNavBar("))
        assertTrue(src.contains("KkcReturnNavigation.navigate("))
        assertTrue(src.contains("padding(bottom = kkcNavBarInset)"))
    }

    @Test
    fun manifestCanSeeKkcPackage() {
        val manifest = read("AndroidManifest.xml")
        assertTrue(manifest.contains("<package android:name=\"com.kkc.sheettracker\" />"))
    }

    @Test
    fun doubleTapGuard() {
        val src = read("java/com/example/timecard/kkcnav/KkcReturnNavigation.kt")
        assertTrue(src.contains("if (activity.isFinishing) return"))
    }

    @Test
    fun kkcFallbackButtonsStillFinish() {
        assertTrue(read("java/com/example/timecard/ui/timesheet/TimesheetScreen.kt").contains("Text(\"← KKC\""))
        assertTrue(read("java/com/example/timecard/ui/login/NameCard.kt").contains("onClick = { activity?.finish() }"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.timecard.kkcnav.KkcNavBarWiringTest"`
Expected: FAIL on the first three tests; `doubleTapGuard` and `kkcFallbackButtonsStillFinish` already PASS.

- [ ] **Step 3: Manifest**

In `app/src/main/AndroidManifest.xml`, directly after the last `<uses-permission ... />` line and before `<application`, add:

```xml
    <!-- KKC navbar mirror: start com.kkc.sheettracker.MainActivity when a KKC destination is tapped -->
    <queries>
        <package android:name="com.kkc.sheettracker" />
    </queries>
```

- [ ] **Step 4: MainActivity**

Add `import com.example.timecard.kkcnav.KkcNavBarPayload`. After the `val launchedByKkc = ...` line, add:

```kotlin
        @Suppress("DEPRECATION") // Bundle.get(key): one generic read for every extra type in the contract
        val kkcNavBar = if (launchedByKkc) KkcNavBarPayload.parse { key -> intent?.extras?.get(key) } else null
```

In `setContent { TimecardApp( ... ) }`, add the argument `kkcNavBar = kkcNavBar,` after `launchedByKkc = launchedByKkc,`.

- [ ] **Step 5: TimecardApp signature and state**

Add parameter `kkcNavBar: KkcNavBarPayload? = null,` after `launchedByKkc: Boolean = false,`.

Add imports:

```kotlin
import android.app.Activity
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.timecard.kkcnav.KkcNavBar
import com.example.timecard.kkcnav.KkcNavBarPayload
import com.example.timecard.kkcnav.KkcReturnNavigation
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
```

(Skip any already imported; `padding` is already imported.)

Inside `TimecardTheme { ... }`, right after `val colors = LocalTimecardColors.current`, add:

```kotlin
        val kkcHazeState = remember { HazeState() }
        var kkcNavBarHeightPx by remember { mutableIntStateOf(0) }
        val kkcNavBarInset = if (kkcNavBar != null) with(LocalDensity.current) { kkcNavBarHeightPx.toDp() } else 0.dp
```

- [ ] **Step 6: TimecardApp root layout**

The root `Box(` at ≈ line 223 currently starts:

```kotlin
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.landingBrush)
                .pointerInput(Unit) {
```

and ends after the splash `AnimatedVisibility { VideoSplash(...) }` block with `        }`.

Wrap it in a new outer `Box` and change its modifier chain to:

```kotlin
        Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (kkcNavBar != null) Modifier.hazeSource(kkcHazeState) else Modifier)
                .background(colors.landingBrush)
                .pointerInput(Unit) {
                    // ... existing pointerInput body unchanged ...
                }
                // Background stays full-height (the glass frosts it); content stops above the KKC bar.
                .padding(bottom = kkcNavBarInset)
        ) {
            // ... existing content unchanged (layers, NameCard, splash) ...
        }

            if (kkcNavBar != null) {
                val activity = LocalContext.current as? Activity
                KkcNavBar(
                    payload = kkcNavBar,
                    hazeState = kkcHazeState,
                    onNavigate = { route ->
                        activity?.let { KkcReturnNavigation.navigate(it, route, kkcNavBar.animDisabled) }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { kkcNavBarHeightPx = it.height }
                )
            }
        }
```

Only the three marked modifier changes, the outer `Box`, and the `if (kkcNavBar != null)` overlay are new; do not move or edit anything inside the existing root `Box`.

- [ ] **Step 7: Run tests and compile**

Run: `.\gradlew.bat :app:testDebugUnitTest` → PASS (whole HT suite, same as Task 0 baseline plus new tests).
Run: `.\gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 8: Commit (HT repo)**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/example/timecard/MainActivity.kt app/src/main/java/com/example/timecard/TimecardApp.kt app/src/test/java/com/example/timecard/kkcnav/KkcNavBarWiringTest.kt
git commit -m "feat(kkc-navbar): show mirrored KKC navbar when launched by KKC

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Keep-in-sync documentation (both repos)

**Files (KKC):** `ui/components/AppScaffold.kt`, `ui/components/icons/NavIcons.kt`, `ui/components/icons/IconDsl.kt`, `ui/theme/Spacing.kt`, `ui/theme/Type.kt`, `navigation/KkcNavBarContract.kt`, `navigation/KkcNavBarPayloadBuilder.kt`, `navigation/NavGraph.kt` (above `launchTimecardApp`), `CLAUDE.md`.
**Files (HT):** `kkcnav/KkcNavBar.kt`, `kkcnav/KkcNavBarModel.kt`, `kkcnav/KkcNavIcons.kt`, `kkcnav/KkcIconDsl.kt`, `kkcnav/KkcNavTypography.kt`, `kkcnav/KkcNavBarContract.kt`, `TimecardApp.kt` (above the overlay), `AGENTS.md`.

No code behavior changes in this task.

- [ ] **Step 1: KKC header block — "Header K"**

Insert this exact block (Kotlin `//` comments) directly above `private fun MorphingNavIconRow(`'s KDoc and directly above `@Composable private fun MorphingNavBar(` in `AppScaffold.kt`, and at the top of `NavIcons.kt`, `IconDsl.kt` (below the `package` line), and above `val navBarHorizontal` in `Spacing.kt` and above `labelSmall = TextStyle(` in `Type.kt`:

```kotlin
// ═══════════════════════════════════════════════════════════════════════════════════════════
// KEEP IN SYNC — HOURS TRACKER MIRRORS THIS NAVBAR
// When KKC opens Hours Tracker (com.example.timecard) it sends this bar's resolved look as
// intent extras and Hours Tracker draws a copy of the FULL (labels shown) state of this bar.
// Mirror lives in C:\Scripts\Hours Tracker\AndroidApp\app\src\main\java\com\example\timecard\kkcnav\
//   KkcNavBar.kt          ← MorphingNavBar + MorphingNavIconRow (full state)
//   KkcNavBarModel.kt     ← labels, Calc-before-Hours slot order, badge rules, tint rules, alpha clamp
//   KkcNavIcons.kt        ← NavIcons.kt (verbatim copy, package changed)
//   KkcIconDsl.kt         ← IconDsl.kt (verbatim copy, package changed)
//   KkcNavTypography.kt   ← Type.kt InterFontFamily + labelSmall
//   KkcNavBarContract.kt  ← navigation/KkcNavBarContract.kt
// Values that MUST match: side margin 24dp, bottom gap 12dp, bar corner 20dp, row min height
// 44dp + padding 24x4dp, SpaceEvenly + weight(1f) slots, item padding 14x8dp, spacedBy 3dp,
// icon 22dp, Calc slot before Hours, tints (bold: frosted content / @0.8; else primary /
// onSurfaceVariant), selection bg (bold: gradient @0.55; else surfaceVariant; shape
// shapes.medium), badges (Supply = supply count, Library = safety count), Surface color
// (transparent under Haze, else frosted base @ alpha.coerceIn(0.5,0.95)), shadow 3dp (0 when
// shadows disabled or WebView blur suppressed), Haze style (blur coerceAtLeast 1dp), label
// style Inter Medium 11sp/16sp/0.5sp (Bold when selected), animation specs (NavSpringDp,
// snap() when low-end animations are off).
// Change any of these here → change the mirror in the SAME session. A new value the mirror
// needs goes through KkcNavBarContract and bumps VERSION in BOTH repos.
// Full rules: KKCSheetTracker CLAUDE.md "KKC navbar mirror (Hours Tracker)".
// ═══════════════════════════════════════════════════════════════════════════════════════════
```

- [ ] **Step 2: KKC contract/payload/launch headers**

Replace the KDoc on `object KkcNavBarContract` with:

```kotlin
/**
 * KEEP IN SYNC — byte-identical keys, VERSION and payload fields with Hours Tracker
 * C:\Scripts\Hours Tracker\AndroidApp\app\src\main\java\com\example\timecard\kkcnav\KkcNavBarContract.kt
 * Tests pin both sides to the same CANONICAL_KEYS list:
 *   KKC  app/src/test/java/com/kkc/sheettracker/navigation/KkcNavBarContractTest.kt
 *   HT   app/src/test/java/com/example/timecard/kkcnav/KkcNavBarContractTest.kt
 * Adding/removing/renaming a key or changing a value's type: update both files AND both tests,
 * and bump VERSION on both sides (an HT build that sees an unknown VERSION shows no bar, so old
 * and new builds stay safe in either order).
 */
```

Add above `currentKkcNavBarPayload` (keep its existing KDoc below this line):

```kotlin
// KEEP IN SYNC — reads the same values AppScaffold.kt MorphingNavBar/MorphingNavIconRow read.
// If the navbar starts reading a new theme value, add it here, to KkcNavBarContract (both repos)
// and to Hours Tracker kkcnav/KkcNavBar.kt.
```

Add above `private fun launchTimecardApp(` in NavGraph.kt:

```kotlin
// KEEP IN SYNC — every Hours Tracker launch passes navBar = kkcNavBarPayload so Hours Tracker can
// draw the mirrored KKC navbar (HoursNavBarMirrorWiringTest enforces it). Return taps arrive in
// MainActivity.handleKkcNavIntent → ExternalNavRequests → ExternalNavEffect in both nav hosts.
```

- [ ] **Step 3: HT header block — "Header H"**

Insert at the top (below `package`) of `KkcNavBar.kt`, `KkcNavBarModel.kt`, `KkcNavIcons.kt`, `KkcIconDsl.kt`, `KkcNavTypography.kt`, and above the `if (kkcNavBar != null) {` overlay in `TimecardApp.kt`:

```kotlin
// ═══════════════════════════════════════════════════════════════════════════════════════════
// KEEP IN SYNC — THIS IS A COPY OF KKCSHEETTRACKER'S BOTTOM NAVBAR
// Source of truth: C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\
//   ui/components/AppScaffold.kt   MorphingNavBar + MorphingNavIconRow (full state) → KkcNavBar.kt
//   ui/components/AppScaffold.kt   enum NavDestination labels / badge + tint rules → KkcNavBarModel.kt
//   ui/components/icons/NavIcons.kt + IconDsl.kt  → KkcNavIcons.kt + KkcIconDsl.kt (verbatim copies)
//   ui/theme/Type.kt  InterFontFamily + labelSmall → KkcNavTypography.kt
//   ui/theme/Spacing.kt  navBarHorizontal 24dp, floatingNavMinSideMargin 24dp, floatingNavBottomGap 12dp
//   navigation/KkcNavBarContract.kt + KkcNavBarPayloadBuilder.kt → KkcNavBarContract.kt
// Do not restyle this bar from Hours Tracker's theme: every color comes from KkcNavBarPayload.
// Any change to the KKC files above must be copied here in the same session; any contract
// change bumps VERSION in both repos. Full rules: Hours Tracker AGENTS.md "KKC navbar mirror".
// Fallback "← KKC" buttons (TimesheetScreen.kt, NameCard.kt) are intentionally kept.
// ═══════════════════════════════════════════════════════════════════════════════════════════
```

Replace the KDoc on HT `object KkcNavBarContract` with the KKC Step 2 contract KDoc, changing the first path line to `C:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\navigation\KkcNavBarContract.kt`.

- [ ] **Step 4: KKC `CLAUDE.md` section**

Append to `C:\Scripts\KKCSheetTracker\CLAUDE.md`, under `## Timeclock Feature` (before `## Build`):

```markdown
### KKC navbar mirror (Hours Tracker)
- Hours Tracker (`com.example.timecard`, repo `C:\Scripts\Hours Tracker\AndroidApp`) draws a copy of
  this app's bottom navbar when KKC launches it. Copy lives in HT `app/src/main/java/com/example/timecard/kkcnav/`.
- KKC sends the resolved look as `extra_kkc_navbar_*` extras (`navigation/KkcNavBarContract.kt`, built by
  `currentKkcNavBarPayload` in `KkcNavBarPayloadBuilder.kt`). Every `launchTimecardApp(` call must pass
  `navBar = kkcNavBarPayload` (`HoursNavBarMirrorWiringTest`).
- Taps come back as `extra_kkc_nav_destination` → `MainActivity.handleKkcNavIntent` → `ExternalNavRequests`
  → `ExternalNavEffect` in BOTH nav hosts, which call the same `navigateFromBar` their own navbar uses.
- Any change to `AppScaffold.kt` `MorphingNavBar`/`MorphingNavIconRow` (full state), `NavIcons.kt`,
  `IconDsl.kt`, nav entries in `Spacing.kt`, or `labelSmall` in `Type.kt` must be mirrored in HT `kkcnav/`
  in the same session. Contract changes update both `KkcNavBarContract.kt` files and both
  `KkcNavBarContractTest` CANONICAL_KEYS lists, and bump `VERSION` on both sides.
```

- [ ] **Step 5: HT `AGENTS.md` section**

Append to `C:\Scripts\Hours Tracker\AndroidApp\AGENTS.md`:

```markdown
## KKC navbar mirror
- When launched by KKCSheetTracker with `extra_kkc_navbar_version == 1`, `TimecardApp` overlays
  `kkcnav/KkcNavBar.kt`, a copy of KKC's bottom navbar (full state). Without a valid payload nothing changes.
- Source of truth is KKCSheetTracker (`C:\Scripts\KKCSheetTracker`): `ui/components/AppScaffold.kt`
  (`MorphingNavBar`, `MorphingNavIconRow`, `NavDestination`), `ui/components/icons/NavIcons.kt` + `IconDsl.kt`,
  `ui/theme/Type.kt` (`labelSmall`), `ui/theme/Spacing.kt`, `navigation/KkcNavBarContract.kt`.
- Never restyle `KkcNavBar` from Hours Tracker's theme — every color comes from `KkcNavBarPayload`.
- Taps call `KkcReturnNavigation.navigate`, which starts KKC `MainActivity` with `extra_kkc_nav_destination`
  (`CLEAR_TOP|SINGLE_TOP`) and finishes. Hours tap = no-op. The "← KKC" buttons stay as fallback.
- Any change on the KKC side must be copied here in the same session. Contract changes update both
  `KkcNavBarContract.kt` files and both `KkcNavBarContractTest` CANONICAL_KEYS lists, and bump `VERSION` in both repos.
```

- [ ] **Step 6: Verify nothing broke**

KKC: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.*" --tests "com.kkc.sheettracker.ui.components.icons.*"` → PASS.
HT: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.timecard.kkcnav.*"` → PASS.

- [ ] **Step 7: Commit both repos**

```bash
git -C /c/Scripts/KKCSheetTracker add -A app/src/main/java CLAUDE.md
git -C /c/Scripts/KKCSheetTracker commit -m "docs(hours-navbar): keep-in-sync notes for the Hours Tracker navbar mirror

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git -C "/c/Scripts/Hours Tracker/AndroidApp" add -A app/src/main/java AGENTS.md
git -C "/c/Scripts/Hours Tracker/AndroidApp" commit -m "docs(kkc-navbar): keep-in-sync notes for the KKC navbar mirror

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

Before each `add -A`, run `git status --short` and confirm only the files listed in this task changed.

---

### Task 10: Release builds and on-tablet verification

**Files:**
- Modify: `C:\Scripts\KKCSheetTracker\app\build.gradle.kts` (`versionCode 80707 → 80708`, `versionName "8.7.7" → "8.7.8"`)
- Modify: `C:\Scripts\Hours Tracker\AndroidApp\app\build.gradle.kts` (`versionCode` +1 and `versionName` patch +1 relative to the committed value after Task 0 — e.g. `160 / "3.11.0"` → `161 / "3.11.1"`)

- [ ] **Step 1: Bump versions and commit each repo**

```bash
git -C /c/Scripts/KKCSheetTracker commit -am "chore: bump version to 8.7.8 (80708)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

(HT: same pattern with its new numbers.) Run `git status --short` first in each repo; only `app/build.gradle.kts` may be modified.

- [ ] **Step 2: Build release APKs**

KKC (PowerShell, `C:\Scripts\KKCSheetTracker`): `.\gradlew.bat assembleRelease`
HT (PowerShell, `C:\Scripts\Hours Tracker\AndroidApp`): `.\gradlew.bat assembleRelease`
Expected: BUILD SUCCESSFUL for both. (Do not use `adb-install-release.ps1` via `powershell -File`; its unicode output breaks — build and install directly.)

- [ ] **Step 3: Install on the connected tablet(s)**

```bash
adb devices -l
adb install -r "C:/Scripts/Hours Tracker/AndroidApp/app/build/outputs/apk/release/app-release.apk"
adb install -r C:/Scripts/KKCSheetTracker/app/build/outputs/apk/release/app-release.apk
```

Install Hours Tracker first (installing KKC can restart it). Expected: `Success` for each.

- [ ] **Step 4: Device checks (ask the user to navigate; take screenshots with `adb exec-out screencap -p > shot.png`)**

Ask the user to perform each and confirm; screenshot the navbar region for the parity pairs:
1. Normal theme, light: screenshot the KKC Jobs tab bar, then tap Hours → screenshot Hours Tracker. Bars match (position, glass, colors, labels, Hours selected, badges).
2. Dark theme: same pair.
3. A bold-mode theme: same pair (gradient selection highlight, black/white content color).
4. In Hours Tracker tap Jobs, Supply, Library, Timeclock in turn (relaunching Hours each time) → KKC lands on that tab; the KKC page you came from is still intact when you return to it.
5. Tap Calc in Hours Tracker → KKC opens with the calculator overlay open.
6. Tap Hours in Hours Tracker → nothing happens.
7. Low-end mode on (animations, blur, shadows off): switch is instant both ways, bar has no blur/shadow.
8. Open Hours Tracker from the home screen launcher → no KKC bar.
9. Hours Tracker login screen (not auto-logged in): bar shows, login card is not covered.
10. Clock-out dialog → confirm → Hours Tracker opens with the bar.

Record each result. Any mismatch → fix in the owning task's files (mirror side unless KKC is wrong), re-run that task's tests, rebuild, re-check.

- [ ] **Step 5: Report**

Summarize results per check with the screenshots. Do not merge the branches or delete them; ask the user how to integrate (superpowers:finishing-a-development-branch).
