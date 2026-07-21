# Deliveries Banner Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the small, hard-to-read `DeliveryScheduleWidget` banner on the four job-board screens with a collapsible, animated dropdown that shows the full week at a glance and expands per-day sideways, plus give the address field explicit coordinate support.

**Architecture:** A new `DeliveryScheduleBanner` composable (Jetpack Compose, Kotlin) replaces `DeliveryScheduleWidget` at all 4 call sites. It owns two pieces of local UI state — whether the banner itself is open, and which of the 5 weekday segments are individually expanded — and renders a horizontal, individually-expandable day strip when open. `DeliveryScheduleDialog` (the existing full-screen editor) is untouched except that its "Open in Maps" action and address field labels now go through a small shared coordinate-aware util, and it's reached only via an explicit admin edit icon instead of by tapping the banner.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), JUnit4 (local/unit tests only — this module has no Compose UI test harness, confirmed via `app/build.gradle.kts`: no `androidTestImplementation`/Espresso/Compose-test deps, and `testOptions.unitTests.isReturnDefaultValues = true`, meaning Android SDK stubs like `Uri.parse` return `null` in unit tests rather than throwing). Pure logic gets TDD unit tests; Compose UI is verified manually on a connected tablet, matching how `DeliveryScheduleWidget`/`DeliveryScheduleDialog` are tested today (only their pure helper functions have tests).

---

## File Structure

**Create:**
- `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBanner.kt` — the new banner composable + its pure helper functions (show/hide rule, total count, per-day delivery detection, day past/today/future state).
- `app/src/test/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBannerTest.kt` — unit tests for the pure helpers above.
- `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryAddressUtil.kt` — shared coordinate parsing + maps-`Uri` construction, used by both the new banner and the existing dialog.
- `app/src/test/java/com/kkc/sheettracker/ui/components/DeliveryAddressUtilTest.kt` — unit tests for coordinate parsing.

**Modify:**
- `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleDialog.kt` — route "Open in Maps" through `deliveryMapsUri`; update 2 address field labels.
- `app/src/main/java/com/kkc/sheettracker/ui/browser/JobBrowserScreen.kt` — swap widget for banner.
- `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyJobsScreen.kt` — swap widget for banner.
- `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobsScreen.kt` — swap widget for banner.
- `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobsScreen.kt` — swap widget for banner.

**Delete:**
- `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleWidget.kt`
- `app/src/test/java/com/kkc/sheettracker/ui/components/DeliveryScheduleWidgetTest.kt`

---

### Task 1: Pure helper functions for the banner (show/hide, count, day state)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBanner.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBannerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryScheduleBannerTest {

    private val populatedSchedule = DeliverySchedule(
        slots = mapOf(
            "monday_am" to DeliverySlot(
                jobs = listOf(
                    DeliveryJob(jobNumber = "1", description = "A"),
                    DeliveryJob(jobNumber = "2", description = "B")
                )
            ),
            "wednesday_pm" to DeliverySlot(
                jobs = listOf(DeliveryJob(jobNumber = "3", description = "C"))
            )
        )
    )

    @Test
    fun shouldShowDeliveryScheduleBanner_hidesEmptyScheduleForNonAdmins() {
        assertFalse(shouldShowDeliveryScheduleBanner(DeliverySchedule(), showWhenEmpty = false))
    }

    @Test
    fun shouldShowDeliveryScheduleBanner_showsEmptyScheduleForAdmins() {
        assertTrue(shouldShowDeliveryScheduleBanner(DeliverySchedule(), showWhenEmpty = true))
    }

    @Test
    fun shouldShowDeliveryScheduleBanner_showsPopulatedScheduleForEveryone() {
        assertTrue(shouldShowDeliveryScheduleBanner(populatedSchedule, showWhenEmpty = false))
    }

    @Test
    fun totalDeliveryCount_sumsJobsAcrossAllSlots() {
        assertEquals(3, totalDeliveryCount(populatedSchedule))
    }

    @Test
    fun totalDeliveryCount_zeroForEmptySchedule() {
        assertEquals(0, totalDeliveryCount(DeliverySchedule()))
    }

    @Test
    fun daysWithDeliveries_returnsOnlyDaysThatHaveAtLeastOneJob() {
        assertEquals(setOf("monday", "wednesday"), daysWithDeliveries(populatedSchedule))
    }

    @Test
    fun daysWithDeliveries_emptyForEmptySchedule() {
        assertTrue(daysWithDeliveries(DeliverySchedule()).isEmpty())
    }

    @Test
    fun deliveryDayState_pastForDayBeforeToday() {
        // Monday(0) vs today=Wednesday -> PAST
        assertEquals(DeliveryDayState.PAST, deliveryDayState(0, DayOfWeek.WEDNESDAY))
    }

    @Test
    fun deliveryDayState_todayForMatchingDay() {
        // Wednesday(2) vs today=Wednesday -> TODAY
        assertEquals(DeliveryDayState.TODAY, deliveryDayState(2, DayOfWeek.WEDNESDAY))
    }

    @Test
    fun deliveryDayState_futureForDayAfterToday() {
        // Friday(4) vs today=Wednesday -> FUTURE
        assertEquals(DeliveryDayState.FUTURE, deliveryDayState(4, DayOfWeek.WEDNESDAY))
    }

    @Test
    fun deliveryDayState_allPastOnSaturday() {
        assertEquals(DeliveryDayState.PAST, deliveryDayState(0, DayOfWeek.SATURDAY))
        assertEquals(DeliveryDayState.PAST, deliveryDayState(4, DayOfWeek.SATURDAY))
    }

    @Test
    fun deliveryDayState_allPastOnSunday() {
        assertEquals(DeliveryDayState.PAST, deliveryDayState(0, DayOfWeek.SUNDAY))
        assertEquals(DeliveryDayState.PAST, deliveryDayState(4, DayOfWeek.SUNDAY))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.DeliveryScheduleBannerTest"`
Expected: FAIL — compile error, `shouldShowDeliveryScheduleBanner`/`totalDeliveryCount`/`daysWithDeliveries`/`DeliveryDayState`/`deliveryDayState` are unresolved references (the file doesn't exist yet).

- [ ] **Step 3: Create the file with the pure helpers**

```kotlin
package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.data.models.DELIVERY_DAYS
import com.kkc.sheettracker.data.models.DELIVERY_PERIODS
import com.kkc.sheettracker.data.models.DeliverySchedule
import java.time.DayOfWeek

internal fun shouldShowDeliveryScheduleBanner(
    schedule: DeliverySchedule,
    showWhenEmpty: Boolean
): Boolean = showWhenEmpty || !schedule.isEmpty

internal fun totalDeliveryCount(schedule: DeliverySchedule): Int =
    schedule.slots.values.sumOf { it.jobs.size }

internal fun daysWithDeliveries(schedule: DeliverySchedule): Set<String> =
    DELIVERY_DAYS.filter { day ->
        DELIVERY_PERIODS.any { period -> schedule.slot(day, period).jobs.isNotEmpty() }
    }.toSet()

internal enum class DeliveryDayState { PAST, TODAY, FUTURE }

internal fun deliveryDayState(dayIndex: Int, today: DayOfWeek): DeliveryDayState {
    val todayIndex = today.value - 1 // DayOfWeek.MONDAY.value == 1 -> index 0
    return when {
        todayIndex !in DELIVERY_DAYS.indices -> DeliveryDayState.PAST // weekend: whole week is past
        dayIndex < todayIndex -> DeliveryDayState.PAST
        dayIndex == todayIndex -> DeliveryDayState.TODAY
        else -> DeliveryDayState.FUTURE
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.DeliveryScheduleBannerTest"`
Expected: PASS (13 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBanner.kt app/src/test/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBannerTest.kt
git commit -m "feat: add pure helpers for deliveries banner (count, show rule, day state)"
```

---

### Task 2: Coordinate parsing for the address field

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryAddressUtil.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/DeliveryAddressUtilTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryAddressUtilTest {

    @Test
    fun parseDeliveryCoordinates_parsesStandardCommaSpaceFormat() {
        assertEquals(45.523 to -122.676, parseDeliveryCoordinates("45.523, -122.676"))
    }

    @Test
    fun parseDeliveryCoordinates_parsesWithoutSpace() {
        assertEquals(45.523 to -122.676, parseDeliveryCoordinates("45.523,-122.676"))
    }

    @Test
    fun parseDeliveryCoordinates_acceptsBoundaryValues() {
        assertEquals(90.0 to 180.0, parseDeliveryCoordinates("90, 180"))
        assertEquals(-90.0 to -180.0, parseDeliveryCoordinates("-90, -180"))
    }

    @Test
    fun parseDeliveryCoordinates_rejectsOutOfRangeLatitude() {
        assertNull(parseDeliveryCoordinates("91, 0"))
        assertNull(parseDeliveryCoordinates("-91, 0"))
    }

    @Test
    fun parseDeliveryCoordinates_rejectsOutOfRangeLongitude() {
        assertNull(parseDeliveryCoordinates("0, 181"))
        assertNull(parseDeliveryCoordinates("0, -181"))
    }

    @Test
    fun parseDeliveryCoordinates_rejectsPlainStreetAddress() {
        assertNull(parseDeliveryCoordinates("123 Main St, Springfield"))
    }

    @Test
    fun parseDeliveryCoordinates_rejectsPlusCode() {
        assertNull(parseDeliveryCoordinates("8FVC9G8V+5V"))
        assertNull(parseDeliveryCoordinates("8FVC9G8V+5V Portland, OR"))
    }

    @Test
    fun parseDeliveryCoordinates_rejectsWrongPartCount() {
        assertNull(parseDeliveryCoordinates("45.5,-122.6,10"))
        assertNull(parseDeliveryCoordinates("45.5"))
    }

    @Test
    fun parseDeliveryCoordinates_rejectsBlank() {
        assertNull(parseDeliveryCoordinates(""))
        assertNull(parseDeliveryCoordinates("   "))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.DeliveryAddressUtilTest"`
Expected: FAIL — `parseDeliveryCoordinates` is an unresolved reference.

- [ ] **Step 3: Create the file**

```kotlin
package com.kkc.sheettracker.ui.components

import android.net.Uri
import java.net.URLEncoder

internal fun parseDeliveryCoordinates(address: String): Pair<Double, Double>? {
    val parts = address.split(",").map { it.trim() }
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lng = parts[1].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return lat to lng
}

/**
 * Not unit tested: `Uri.parse` is an Android SDK stub under this module's plain JUnit
 * setup (`isReturnDefaultValues = true`), so it returns null in local tests regardless
 * of input. Covered by manual device verification instead (see Task 6).
 */
internal fun deliveryMapsUri(address: String): Uri {
    val coords = parseDeliveryCoordinates(address)
    return if (coords != null) {
        Uri.parse("geo:${coords.first},${coords.second}?q=${coords.first},${coords.second}")
    } else {
        Uri.parse("geo:0,0?q=${URLEncoder.encode(address, "UTF-8")}")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.DeliveryAddressUtilTest"`
Expected: PASS (9 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryAddressUtil.kt app/src/test/java/com/kkc/sheettracker/ui/components/DeliveryAddressUtilTest.kt
git commit -m "feat: add coordinate-aware maps URI builder for delivery addresses"
```

---

### Task 3: Wire the shared address util into the existing dialog

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleDialog.kt:850-870` (`DeliveryAddressActionsRow`)
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleDialog.kt:787-793` (edit sheet address field label)
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleDialog.kt:1036-1042` (add panel address field label)

No new tests here — `deliveryMapsUri` itself is covered by Task 2's `parseDeliveryCoordinates` tests, and the dialog's UI has no existing automated coverage (matches this file's current state). Verified manually in Task 6.

- [ ] **Step 1: Replace the inline URI construction in `DeliveryAddressActionsRow`**

Current code (lines 850-870):

```kotlin
@Composable
private fun DeliveryAddressActionsRow(address: String, context: Context) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            val encoded = URLEncoder.encode(address, "UTF-8")
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")))
        }) {
```

Replace with:

```kotlin
@Composable
private fun DeliveryAddressActionsRow(address: String, context: Context) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, deliveryMapsUri(address)))
        }) {
```

The rest of the function (icon, text, the Copy button) is unchanged. The `java.net.URLEncoder` import at the top of `DeliveryScheduleDialog.kt` (line 110) is now unused by this function specifically, but stays — `DeliveryAddressUtil.kt` has its own copy of that import and this file's import isn't otherwise referenced, so check for other usages before removing (there are none elsewhere in this file); remove the now-unused `import java.net.URLEncoder` line from `DeliveryScheduleDialog.kt`.

- [ ] **Step 2: Update the edit-sheet address field label**

In `DeliveryJobDetailSheet` (around line 790), change:

```kotlin
                OutlinedTextField(
                    value = editAddress,
                    onValueChange = { editAddress = it },
                    label = { Text("Address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
```

to:

```kotlin
                OutlinedTextField(
                    value = editAddress,
                    onValueChange = { editAddress = it },
                    label = { Text("Address, coordinates, or Plus Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
```

- [ ] **Step 3: Update the add-panel address field label**

In `DeliveryAddJobPanel` (around line 1039), change:

```kotlin
            OutlinedTextField(
                value = manualAddress,
                onValueChange = { manualAddress = it },
                label = { Text("Address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
```

to:

```kotlin
            OutlinedTextField(
                value = manualAddress,
                onValueChange = { manualAddress = it },
                label = { Text("Address, coordinates, or Plus Code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
```

- [ ] **Step 4: Build to confirm it compiles**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL, no unresolved-reference or unused-import errors.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleDialog.kt
git commit -m "refactor: route dialog's Open in Maps through shared coordinate-aware URI builder"
```

---

### Task 4: Banner composable — collapsed header

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBanner.kt` (append composables below the pure helpers from Task 1)

This and the next task are Compose UI — no unit test harness exists in this module (see Tech Stack note). Verified manually in Task 6.

- [ ] **Step 1: Add imports and the collapsed-header composable**

Append to `DeliveryScheduleBanner.kt` (after the existing pure-function code from Task 1):

```kotlin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.ui.theme.KKCSpacing
import java.time.LocalDate

private val DeliveryBannerSizeSpring: FiniteAnimationSpec<IntSize> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
private val DeliveryBannerFadeInTween = tween<Float>(220, delayMillis = 60, easing = FastOutSlowInEasing)
private val DeliveryBannerFadeOutTween = tween<Float>(180, easing = FastOutSlowInEasing)

/**
 * Collapsible, animated dropdown replacing the old always-expanded [DeliveryScheduleWidget]
 * grid. Collapsed: a single header row showing the week's total delivery count. Expanded:
 * a horizontally-scrollable strip of 5 day segments (see [DeliveryDayStrip]), each
 * independently expandable sideways.
 */
@Composable
fun DeliveryScheduleBanner(
    schedule: DeliverySchedule,
    isAdminMode: Boolean,
    onEditRequested: () -> Unit,
    modifier: Modifier = Modifier,
    showWhenEmpty: Boolean = false
) {
    if (!shouldShowDeliveryScheduleBanner(schedule, showWhenEmpty)) return

    var bannerExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedDays by remember(schedule) { mutableStateOf(daysWithDeliveries(schedule)) }
    val today = remember { LocalDate.now().dayOfWeek }
    val totalCount = remember(schedule) { totalDeliveryCount(schedule) }

    Surface(
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.shadow(elevation = 2.dp, shape = RoundedCornerShape(9.dp), clip = false)
    ) {
        Row(Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.04f))) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
            Column(Modifier.weight(1f, fill = true)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { bannerExpanded = !bannerExpanded }
                        .padding(horizontal = KKCSpacing.l, vertical = KKCSpacing.inCardSpacing),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$totalCount — Deliveries This Week",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (isAdminMode) {
                        IconButton(onClick = onEditRequested) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit delivery schedule")
                        }
                    }
                    Icon(
                        imageVector = if (bannerExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = if (bannerExpanded) "Collapse deliveries" else "Expand deliveries",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AnimatedVisibility(
                    visible = bannerExpanded,
                    enter = expandVertically(DeliveryBannerSizeSpring) + fadeIn(DeliveryBannerFadeInTween),
                    exit = shrinkVertically(DeliveryBannerSizeSpring) + fadeOut(DeliveryBannerFadeOutTween)
                ) {
                    DeliveryDayStrip(
                        schedule = schedule,
                        today = today,
                        expandedDays = expandedDays,
                        onToggleDay = { day ->
                            expandedDays = if (day in expandedDays) expandedDays - day else expandedDays + day
                        }
                    )
                }
            }
        }
    }
}
```

Note: this references `DeliveryDayStrip`, added in Task 5 — the file won't compile until that task is done. That's fine, Task 5 is next and no commit happens until compilation succeeds (Step 2 below is deferred to the end of Task 5, not this task).

- [ ] **Step 2: Move on to Task 5 before building or committing**

This task's code is not independently compilable (missing `DeliveryDayStrip`). Continue directly to Task 5; the build/verify/commit steps there cover both.

---

### Task 5: Banner composable — expanded day strip

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBanner.kt` (append)

- [ ] **Step 1: Add the remaining imports**

Append to the import block added in Task 4:

```kotlin
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import com.kkc.sheettracker.data.models.DELIVERY_DAYS
import com.kkc.sheettracker.data.models.DELIVERY_PERIODS
import com.kkc.sheettracker.data.models.DeliveryJob
import java.time.DayOfWeek
```

(`RoundedCornerShape` and `DeliverySchedule` are already imported from Task 4's block and Task 1 respectively — not repeated here.)

- [ ] **Step 2: Add the day strip, day segment, and job row composables**

```kotlin
private val DeliveryDaySegmentCollapsedWidth = 48.dp
private val DeliveryDaySegmentMinWidth = 140.dp
private val DeliveryDaySegmentMaxWidth = 260.dp

@Composable
private fun DeliveryDayStrip(
    schedule: DeliverySchedule,
    today: DayOfWeek,
    expandedDays: Set<String>,
    onToggleDay: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = KKCSpacing.l, vertical = KKCSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(KKCSpacing.xs)
    ) {
        DELIVERY_DAYS.forEachIndexed { dayIdx, day ->
            val dayLabel = day.replaceFirstChar { it.uppercase() }
            val dayCount = DELIVERY_PERIODS.sumOf { period -> schedule.slot(day, period).jobs.size }
            val state = deliveryDayState(dayIdx, today)
            DeliveryDaySegment(
                day = day,
                dayLabel = dayLabel,
                dayCount = dayCount,
                state = state,
                isExpanded = day in expandedDays,
                schedule = schedule,
                onToggle = { onToggleDay(day) }
            )
        }
    }
}

@Composable
private fun DeliveryDaySegment(
    day: String,
    dayLabel: String,
    dayCount: Int,
    state: DeliveryDayState,
    isExpanded: Boolean,
    schedule: DeliverySchedule,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val isToday = state == DeliveryDayState.TODAY
    val contentAlpha = if (state == DeliveryDayState.PAST) 0.45f else 1f
    val borderColor = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val containerColor = if (isToday) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Box(
        modifier = Modifier
            .animateContentSize()
            .widthIn(
                min = if (isExpanded) DeliveryDaySegmentMinWidth else DeliveryDaySegmentCollapsedWidth,
                max = if (isExpanded) DeliveryDaySegmentMaxWidth else DeliveryDaySegmentCollapsedWidth
            )
            .fillMaxHeight()
            .clip(MaterialTheme.shapes.medium)
            .background(containerColor.copy(alpha = contentAlpha))
            .border(
                width = if (isToday) 2.dp else 1.dp,
                color = borderColor.copy(alpha = contentAlpha),
                shape = MaterialTheme.shapes.medium
            )
            .clickable(onClick = onToggle)
            .padding(KKCSpacing.s)
    ) {
        if (isExpanded) {
            Column {
                Text(
                    text = buildString {
                        append(dayCount)
                        append(" — ")
                        append(dayLabel)
                        if (isToday) append(" — Today")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(KKCSpacing.xs))
                DELIVERY_PERIODS.forEach { period ->
                    val slot = schedule.slot(day, period)
                    Text(
                        text = period.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                    )
                    if (slot.jobs.isEmpty()) {
                        Text(
                            text = "No deliveries",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha * 0.7f)
                        )
                    } else {
                        slot.jobs.forEach { job ->
                            DeliveryBannerJobRow(
                                job = job,
                                contentAlpha = contentAlpha,
                                onOpenMaps = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, deliveryMapsUri(job.address)))
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(KKCSpacing.xxs))
                }
            }
        } else {
            Text(
                text = "$dayCount — $dayLabel",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Visible,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { rotationZ = -90f }
            )
        }
    }
}

@Composable
private fun DeliveryBannerJobRow(
    job: DeliveryJob,
    contentAlpha: Float,
    onOpenMaps: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = KKCSpacing.xxs)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = job.jobNumber.ifBlank { "(no job #)" },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (job.description.isNotBlank()) {
                Text(
                    text = job.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (job.address.isNotBlank()) {
            IconButton(onClick = onOpenMaps, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Open in Maps",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
```

- [ ] **Step 3: Build to confirm the whole file compiles**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full unit test suite to confirm nothing broke**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: PASS, including the 13 tests from Task 1 and 9 from Task 2.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleBanner.kt
git commit -m "feat: build DeliveryScheduleBanner collapsible dropdown UI"
```

---

### Task 6: Swap the widget for the banner at all 4 call sites, delete the old widget

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/browser/JobBrowserScreen.kt:93-94,439-446`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyJobsScreen.kt:108-109,404-411`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobsScreen.kt:113-114,449-456`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobsScreen.kt:104-105,328-335`
- Delete: `app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleWidget.kt`
- Delete: `app/src/test/java/com/kkc/sheettracker/ui/components/DeliveryScheduleWidgetTest.kt`

All 4 screens follow the identical existing pattern, so each gets the identical edit.

- [ ] **Step 1: `JobBrowserScreen.kt` — swap the import**

Change (line 94):
```kotlin
import com.kkc.sheettracker.ui.components.DeliveryScheduleWidget
```
to:
```kotlin
import com.kkc.sheettracker.ui.components.DeliveryScheduleBanner
```

- [ ] **Step 2: `JobBrowserScreen.kt` — swap the call site**

Change (lines 439-446):
```kotlin
            DeliveryScheduleWidget(
                schedule = deliverySchedule,
                onTap = { showScheduleDialog = true },
                showWhenEmpty = adminMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
```
to:
```kotlin
            DeliveryScheduleBanner(
                schedule = deliverySchedule,
                isAdminMode = adminMode,
                onEditRequested = { showScheduleDialog = true },
                showWhenEmpty = adminMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
```

- [ ] **Step 3: `AssemblyJobsScreen.kt` — same two changes**

Change (line 109):
```kotlin
import com.kkc.sheettracker.ui.components.DeliveryScheduleWidget
```
to:
```kotlin
import com.kkc.sheettracker.ui.components.DeliveryScheduleBanner
```

Change (lines 404-411):
```kotlin
            DeliveryScheduleWidget(
                schedule = deliverySchedule,
                onTap = { showScheduleDialog = true },
                showWhenEmpty = adminMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
```
to:
```kotlin
            DeliveryScheduleBanner(
                schedule = deliverySchedule,
                isAdminMode = adminMode,
                onEditRequested = { showScheduleDialog = true },
                showWhenEmpty = adminMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
```

- [ ] **Step 4: `HardwoodsJobsScreen.kt` — same two changes**

Change (line 114):
```kotlin
import com.kkc.sheettracker.ui.components.DeliveryScheduleWidget
```
to:
```kotlin
import com.kkc.sheettracker.ui.components.DeliveryScheduleBanner
```

Change (lines 449-456):
```kotlin
            DeliveryScheduleWidget(
                schedule = deliverySchedule,
                onTap = { showScheduleDialog = true },
                showWhenEmpty = adminMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
```
to:
```kotlin
            DeliveryScheduleBanner(
                schedule = deliverySchedule,
                isAdminMode = adminMode,
                onEditRequested = { showScheduleDialog = true },
                showWhenEmpty = adminMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
```

- [ ] **Step 5: `SpecialtyJobsScreen.kt` — same two changes**

Change (line 105):
```kotlin
import com.kkc.sheettracker.ui.components.DeliveryScheduleWidget
```
to:
```kotlin
import com.kkc.sheettracker.ui.components.DeliveryScheduleBanner
```

Change (lines 328-335):
```kotlin
            DeliveryScheduleWidget(
                schedule = deliverySchedule,
                onTap = { showScheduleDialog = true },
                showWhenEmpty = adminMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
```
to:
```kotlin
            DeliveryScheduleBanner(
                schedule = deliverySchedule,
                isAdminMode = adminMode,
                onEditRequested = { showScheduleDialog = true },
                showWhenEmpty = adminMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
```

- [ ] **Step 6: Delete the old widget and its test**

```bash
git rm app/src/main/java/com/kkc/sheettracker/ui/components/DeliveryScheduleWidget.kt app/src/test/java/com/kkc/sheettracker/ui/components/DeliveryScheduleWidgetTest.kt
```

- [ ] **Step 7: Build to confirm no remaining references**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL — confirms no other file still imports `DeliveryScheduleWidget`.

- [ ] **Step 8: Run the full unit test suite**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: PASS (old `DeliveryScheduleWidgetTest` is gone; `DeliveryScheduleBannerTest` and `DeliveryAddressUtilTest` from Tasks 1-2 pass).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: replace DeliveryScheduleWidget with DeliveryScheduleBanner on all 4 job screens"
```

---

### Task 7: Manual device verification

**Files:** none (verification only)

This module has no Compose UI test harness, so the interactive/visual behavior from the design spec gets verified by hand on a connected tablet, per this repo's existing convention (`CLAUDE.md` build instructions) and the `debug-android-tablet` skill if issues come up.

- [ ] **Step 1: Build and install the debug APK**

```powershell
cd C:\Scripts\KKCSheetTracker
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

- [ ] **Step 2: Walk the verification checklist from the design spec**

Open the job board on the connected device/tablet and confirm, against `docs/superpowers/specs/2026-07-20-deliveries-banner-redesign-design.md` → Verification:

1. Collapsed banner shows correct total count, accent-bar/chevron styling.
2. Tap header → day strip expands with spring/fade; tap again → collapses, no flicker.
3. Days with ≥1 job start expanded; empty days start collapsed (thin rotated chip).
4. Tap an empty day's chip → expands showing "No deliveries" for AM and PM. Tap a busy day's card → collapses.
5. On the device's current weekday, that day (and only that day) is highlighted; earlier weekdays greyed; later weekdays normal. (If today is Sat/Sun, change the device date forward to a weekday to check this, then set it back — or just confirm all 5 render greyed on the weekend per check 6.)
6. On Sat/Sun, all 5 days render greyed, none highlighted.
7. With 3+ days expanded (add test data via admin mode if needed), the day strip scrolls horizontally instead of clipping.
8. A job with an address shows the location icon; tapping it opens Maps.
9. Enter `"45.523, -122.676"` as a job's address (admin edit sheet) → tapping the maps icon on that job (in the banner or the dialog's "Open in Maps") opens Maps centered exactly on that point. Enter a Plus Code or street address → still opens via search, unchanged from before.
10. Both address fields in the admin dialog (edit sheet, add panel) show the label "Address, coordinates, or Plus Code".
11. `isAdminMode = true` (toggle admin mode in the app) shows the edit icon on the banner header; tapping it opens `DeliveryScheduleDialog`. `isAdminMode = false` shows no edit icon.
12. With an all-empty schedule: non-admin sees no banner at all; admin sees `"0 — Deliveries This Week"` with all days collapsed.
13. Repeat checks 1-4 on all four screens: Job Browser, Assembly, Hardwoods, Specialty.

- [ ] **Step 3: Fix anything that doesn't match, then re-run Step 2 for the affected checks**

If a fix is needed, make it, re-run `.\gradlew.bat assembleDebug && adb install -r app\build\outputs\apk\debug\app-debug.apk`, and re-check only the affected items — no need to redo the whole list.

No commit for this task — it's verification only. If Step 3 required code changes, commit those under a `fix:` message describing what was wrong.
