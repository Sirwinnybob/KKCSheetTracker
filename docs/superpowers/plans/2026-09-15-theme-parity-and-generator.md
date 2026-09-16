# Theme Color Parity & Generator Skill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every status color in `KKCStatusColors` theme-overridable, migrate hardcoded status-meaning colors (dashboard accents, supply tiers, battery, safety badges) onto that single source of truth, and ship a `kkc-theme-generator` skill that produces new theme JSON files with hue-locked, validated status colors.

**Architecture:** `KKCThemeRepository` parses a theme JSON into `KKCThemeTokens` (which embeds `KKCStatusColors`); `KKCTheme` provides both via `CompositionLocalProvider`; screens read `KKCThemeColors.statusColors`. Today only 4 of ~11 status fields are theme-overridable, and several UI surfaces (dashboard accent cards, supply status chips, battery indicator, safety badges) bypass the token system with raw hex literals — some of which don't even use the "right" hue for their meaning (e.g. `DashboardAccent.SUCCESS` currently renders the theme's primary blue, not green). This plan closes both gaps, then adds a skill that generates future themes (e.g. sports-team themes) without a human hand-picking every hex value.

**Tech Stack:** Kotlin, Jetpack Compose, Gson (theme JSON parsing), JUnit (JVM unit tests, no Robolectric in this codebase), Python 3 (skill's validator script).

---

## File Structure

- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/theme/KKCThemeRepository.kt` — extend JSON `status` object parsing to cover every `KKCStatusColors` field.
- **Modify** `app/src/test/java/com/kkc/sheettracker/ui/theme/KKCThemeRepositoryTest.kt` — new tests for the extended parsing.
- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardSurfacePrimitives.kt` — route `DashboardAccent` → color resolution through `KKCThemeColors.statusColors` instead of raw `MaterialTheme.colorScheme` slots. This is the single highest-leverage fix: ~25 call sites across Dashboard and Supply screens pick up correct, theme-aware colors from one edit.
- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt` — `supplyStatusColor`/`supplyStatusHeaderTint` delegate to the same `DashboardAccent` resolver instead of a separate hardcoded tier→hex table.
- **Create** `app/src/test/java/com/kkc/sheettracker/ui/supply/SupplyStatusAccentTest.kt` — unit tests for the new pure tier→accent mapping.
- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardWidgetFactories.kt` — `getSoftStatusColors` delegates to the same resolver instead of a separate hardcoded pastel table (also removes a fragile `Color` equality check).
- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/components/BatteryIndicator.kt` — charging/low-battery colors read `KKCThemeColors.statusColors`.
- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/standards/SafetyDocumentsScreen.kt` — `StatusBadge` colors read `KKCThemeColors.statusColors`.
- **Create** `.claude/skills/kkc-theme-generator/SKILL.md` and `.claude/skills/kkc-theme-generator/scripts/validate_theme.py` — the generator skill and its deterministic validator.
- **Create** `.agents/skills/kkc-theme-generator/SKILL.md` and `.agents/skills/kkc-theme-generator/scripts/validate_theme.py` — mirror copies, matching the existing `kkc-metadata-map`/`debug-android-tablet` dual-location convention in this repo.
- **Create** `app/src/test/java/com/kkc/sheettracker/ui/theme/KKCThemeGeneratorValidatorTest.kt` is **not** needed — the validator is a standalone Python script (not part of the Kotlin app), so its self-tests live in Python (Task 7, Step 2) instead of JUnit.

Excluded from migration (reviewed, not status-meaning): `SheetViewerScreen.kt:3282` (rotation-marker asterisk — a print-marking indicator, not a job/part status) and `TimecardScreen.kt:52` `ClockOutRed` (a button accent, not a status). `PdfMarkupUi.kt` ink colors are excluded per the approved spec (user-chosen drawing tool colors).

---

## Task 1: Theme JSON schema parity

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/theme/KKCThemeRepository.kt:118-190`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/theme/KKCThemeRepositoryTest.kt`

- [x] **Step 1: Write the failing tests**

Add to `KKCThemeRepositoryTest.kt` (after the existing `statusBgAndBorderFallBackToBaseKeyWhenNoDedicatedKeyGiven` test):

```kotlin
    @Test
    fun statusColorsSupportFullParityFields() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(
            baseDir = baseDir,
            filename = "full-parity.json",
            body = validThemeJson(id = "kkc-full-parity").replace(
                """"status": { "complete": "#388E3C", "bad": "#C62828", "skip": "#E65100", "inProgress": "#1565C0" }""",
                """"status": {
                    "complete": "#388E3C", "bad": "#C62828", "skip": "#E65100", "inProgress": "#1565C0",
                    "notStarted": "#123456", "remakeBg": "#654321", "miscBg": "#ABCDEF",
                    "widthBand": ["#111111", "#222222", "#333333", "#444444", "#555555"],
                    "progressGradientStart": "#33334455", "progressGradientEnd": "#1A334455"
                  }"""
            )
        )

        val status = KKCThemeRepository(baseDir, FakeThemePreferences()).loadCatalog()
            .themes.first { it.id == "kkc-full-parity" }.tokens.lightStatus

        assertEquals(Color(0xFF123456), status.notStarted)
        assertEquals(Color(0xFF654321), status.remakeBg)
        assertEquals(Color(0xFFABCDEF), status.miscBg)
        assertEquals(
            listOf(
                Color(0xFF111111), Color(0xFF222222), Color(0xFF333333),
                Color(0xFF444444), Color(0xFF555555)
            ),
            status.widthBandPalette
        )
        assertEquals(Color(0x33334455), status.progressGradientStart)
        assertEquals(Color(0x1A334455), status.progressGradientEnd)
    }

    @Test
    fun statusColorsFallBackToBuiltInWhenNewFieldsAreAbsent() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(baseDir, "shop-blue.json", validThemeJson(id = "kkc-shop-blue"))

        val status = KKCThemeRepository(baseDir, FakeThemePreferences()).loadCatalog()
            .themes.first { it.id == "kkc-shop-blue" }.tokens.lightStatus

        assertEquals(LightStatusColors.notStarted, status.notStarted)
        assertEquals(LightStatusColors.remakeBg, status.remakeBg)
        assertEquals(LightStatusColors.miscBg, status.miscBg)
        assertEquals(LightStatusColors.widthBandPalette, status.widthBandPalette)
        assertEquals(LightStatusColors.progressGradientStart, status.progressGradientStart)
        assertEquals(LightStatusColors.progressGradientEnd, status.progressGradientEnd)
    }
```

- [x] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.theme.KKCThemeRepositoryTest`
Expected: FAIL — `statusColorsSupportFullParityFields` fails because `notStarted`/`remakeBg`/etc. still resolve to built-in defaults instead of the JSON values (the second test, `...FallBackToBuiltIn...`, will actually pass already since fallback already works today — that's expected and fine, it locks in the no-regression behavior before the parsing change).

- [x] **Step 3: Extend the JSON parsing**

In `KKCThemeRepository.kt`, replace the `lightStatus`/`darkStatus` construction (lines 133–156) with:

```kotlin
            val widthBand = colorArray(statusObj, "widthBand")
            val lightStatus = LightStatusColors.copy(
                complete = color(statusObj, "complete") ?: LightStatusColors.complete,
                completeBg = color(statusObj, "completeBg") ?: color(statusObj, "complete") ?: LightStatusColors.completeBg,
                completeBorder = color(statusObj, "completeBorder") ?: color(statusObj, "complete") ?: LightStatusColors.completeBorder,
                bad = color(statusObj, "bad") ?: LightStatusColors.bad,
                badBg = color(statusObj, "badBg") ?: color(statusObj, "bad") ?: LightStatusColors.badBg,
                skip = color(statusObj, "skip") ?: LightStatusColors.skip,
                skipBg = color(statusObj, "skipBg") ?: color(statusObj, "skip") ?: LightStatusColors.skipBg,
                skipBorder = color(statusObj, "skipBorder") ?: color(statusObj, "skip") ?: LightStatusColors.skipBorder,
                inProgress = color(statusObj, "inProgress") ?: LightStatusColors.inProgress,
                inProgressBorder = color(statusObj, "inProgressBorder") ?: color(statusObj, "inProgress") ?: LightStatusColors.inProgressBorder,
                notStarted = color(statusObj, "notStarted") ?: LightStatusColors.notStarted,
                remakeBg = color(statusObj, "remakeBg") ?: LightStatusColors.remakeBg,
                miscBg = color(statusObj, "miscBg") ?: LightStatusColors.miscBg,
                widthBandPalette = widthBand ?: LightStatusColors.widthBandPalette,
                progressGradientStart = color(statusObj, "progressGradientStart") ?: LightStatusColors.progressGradientStart,
                progressGradientEnd = color(statusObj, "progressGradientEnd") ?: LightStatusColors.progressGradientEnd
            )
            val darkStatus = DarkStatusColors.copy(
                complete = color(statusObj, "complete") ?: DarkStatusColors.complete,
                completeBg = color(statusObj, "completeBg") ?: color(statusObj, "complete") ?: DarkStatusColors.completeBg,
                completeBorder = color(statusObj, "completeBorder") ?: color(statusObj, "complete") ?: DarkStatusColors.completeBorder,
                bad = color(statusObj, "bad") ?: DarkStatusColors.bad,
                badBg = color(statusObj, "badBg") ?: color(statusObj, "bad") ?: DarkStatusColors.badBg,
                skip = color(statusObj, "skip") ?: DarkStatusColors.skip,
                skipBg = color(statusObj, "skipBg") ?: color(statusObj, "skip") ?: DarkStatusColors.skipBg,
                skipBorder = color(statusObj, "skipBorder") ?: color(statusObj, "skip") ?: DarkStatusColors.skipBorder,
                inProgress = color(statusObj, "inProgress") ?: DarkStatusColors.inProgress,
                inProgressBorder = color(statusObj, "inProgressBorder") ?: color(statusObj, "inProgress") ?: DarkStatusColors.inProgressBorder,
                notStarted = color(statusObj, "notStarted") ?: DarkStatusColors.notStarted,
                remakeBg = color(statusObj, "remakeBg") ?: DarkStatusColors.remakeBg,
                miscBg = color(statusObj, "miscBg") ?: DarkStatusColors.miscBg,
                widthBandPalette = widthBand ?: DarkStatusColors.widthBandPalette,
                progressGradientStart = color(statusObj, "progressGradientStart") ?: DarkStatusColors.progressGradientStart,
                progressGradientEnd = color(statusObj, "progressGradientEnd") ?: DarkStatusColors.progressGradientEnd
            )
```

Add the `colorArray` helper next to the existing `color(obj, key)` helper (near line 264):

```kotlin
    private fun colorArray(obj: JsonObject?, key: String): List<Color>? {
        val array = obj?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val hexStrings = array.mapNotNull { runCatching { it.asString }.getOrNull() }
        val colors = hexStrings.mapNotNull { hex ->
            if (!HEX_COLOR.matches(hex)) return@mapNotNull null
            val normalized = hex.removePrefix("#")
            val argb = when (normalized.length) {
                6 -> "FF$normalized"
                8 -> normalized
                else -> return@mapNotNull null
            }
            Color(argb.toLong(16).toInt())
        }
        return colors.takeIf { it.isNotEmpty() }
    }
```

- [x] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.theme.KKCThemeRepositoryTest`
Expected: PASS — all tests including the two new ones.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/theme/KKCThemeRepository.kt app/src/test/java/com/kkc/sheettracker/ui/theme/KKCThemeRepositoryTest.kt
git commit -m "fix: extend theme JSON parsing to cover all KKCStatusColors fields"
```

---

## Task 2: Route dashboard accents through theme status colors

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardSurfacePrimitives.kt:1-79`

- [x] **Step 1: Replace the three accent-resolution functions**

Add the import (near the other `com.kkc.sheettracker.ui.theme` import):

```kotlin
import com.kkc.sheettracker.ui.theme.KKCThemeColors
```

Replace `accentWash`, `outlineColor`, and `accentColor` in `DashboardSurfaceDefaults` (lines 46–78) with:

```kotlin
    @Composable
    fun accentWash(accent: DashboardAccent): Color {
        val status = KKCThemeColors.statusColors
        return when (accent) {
            DashboardAccent.NEUTRAL -> status.notStarted.copy(alpha = 0.18f)
            DashboardAccent.INFO -> status.inProgress.copy(alpha = 0.22f)
            DashboardAccent.SUCCESS -> status.complete.copy(alpha = 0.24f)
            DashboardAccent.WARNING -> status.skip.copy(alpha = 0.20f)
            DashboardAccent.DANGER -> status.bad.copy(alpha = 0.16f)
        }
    }

    @Composable
    fun outlineColor(accent: DashboardAccent): Color {
        val status = KKCThemeColors.statusColors
        val base = when (accent) {
            DashboardAccent.NEUTRAL -> status.notStarted.copy(alpha = 0.4f)
            DashboardAccent.INFO -> status.inProgress.copy(alpha = 0.22f)
            DashboardAccent.SUCCESS -> status.complete.copy(alpha = 0.18f)
            DashboardAccent.WARNING -> status.skip.copy(alpha = 0.2f)
            DashboardAccent.DANGER -> status.bad.copy(alpha = 0.2f)
        }
        return base.copy(alpha = base.alpha * 0.9f)
    }

    @Composable
    fun accentColor(accent: DashboardAccent): Color {
        val status = KKCThemeColors.statusColors
        return when (accent) {
            DashboardAccent.NEUTRAL -> status.notStarted
            DashboardAccent.INFO -> status.inProgress
            DashboardAccent.SUCCESS -> status.complete
            DashboardAccent.WARNING -> status.skip
            DashboardAccent.DANGER -> status.bad
        }
    }
```

Note: `outlineColor`'s original NEUTRAL case used `scheme.outlineVariant` (a fixed, low-alpha gray line), not an alpha-scaled accent. `status.notStarted.copy(alpha = 0.4f)` is the closest theme-aware equivalent — a muted gray outline — and keeps the same final `* 0.9f` scaling the original applied to every branch.

- [x] **Step 2: Build and verify no compile errors**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. This function is called from ~25 sites across `DashboardWidgetFactories.kt`, `SupplyDashboardScreen.kt`, `SupplyItemDetailScreen.kt`, and others — a successful compile confirms none of them broke.

- [x] **Step 3: Run existing dashboard/supply unit tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.dashboard.UnifiedDashboardFactoriesTest --tests com.kkc.sheettracker.ui.supply.SupplyModalChromeTest`

(Note: `SupplyModalChromeTest` was later deleted in Task 3, once `supplyStatusColor`/`supplyStatusHeaderTint` became `@Composable` and could no longer be called from plain JUnit — see Task 3's commit message. Re-running this exact command after Task 3 lands will report "no tests found" for that class; that's expected, not a regression.)
Expected: PASS — these tests exercise status/accent logic and must show no regression from the color-sourcing change (they assert on data/state, not exact pixel colors, so they should be unaffected).

- [x] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardSurfacePrimitives.kt
git commit -m "fix: resolve dashboard accent colors from theme status tokens, not fixed MaterialTheme slots"
```

---

## Task 3: Migrate supply tier colors onto the same resolver

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt:1236-1257`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/supply/SupplyStatusAccentTest.kt` (new)

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/com/kkc/sheettracker/ui/supply/SupplyStatusAccentTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.supply

import com.kkc.sheettracker.ui.dashboard.DashboardAccent
import org.junit.Assert.assertEquals
import org.junit.Test

class SupplyStatusAccentTest {

    @Test
    fun criticalAndUrgentTiersMapToDanger() {
        assertEquals(DashboardAccent.DANGER, supplyTierAccent(1))
        assertEquals(DashboardAccent.DANGER, supplyTierAccent(2))
    }

    @Test
    fun lowAndNotOrderedTiersMapToWarning() {
        assertEquals(DashboardAccent.WARNING, supplyTierAccent(3))
        assertEquals(DashboardAccent.WARNING, supplyTierAccent(6))
    }

    @Test
    fun orderedTierMapsToInfo() {
        assertEquals(DashboardAccent.INFO, supplyTierAccent(4))
    }

    @Test
    fun inStockAndToOrderTiersMapToSuccess() {
        assertEquals(DashboardAccent.SUCCESS, supplyTierAccent(5))
        assertEquals(DashboardAccent.SUCCESS, supplyTierAccent(7))
    }

    @Test
    fun unknownTierDefaultsToSuccess() {
        assertEquals(DashboardAccent.SUCCESS, supplyTierAccent(99))
    }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.supply.SupplyStatusAccentTest`
Expected: FAIL with "unresolved reference: supplyTierAccent" (function doesn't exist yet).

- [x] **Step 3: Replace the hardcoded tier table**

In `SupplyDashboardScreen.kt`, replace `supplyStatusColor`/`supplyStatusHeaderTint` (lines 1236–1249) with:

```kotlin
fun supplyTierAccent(tier: Int): DashboardAccent = when (tier) {
    1, 2 -> DashboardAccent.DANGER     // OUT / ASAP / NEED
    3, 6 -> DashboardAccent.WARNING    // LOW / NOT ORDERED
    4 -> DashboardAccent.INFO          // ORDERED / IN PROCESS
    5, 7 -> DashboardAccent.SUCCESS    // IN STOCK / COMPLETE / ORDERED (To Order)
    else -> DashboardAccent.SUCCESS    // default, matches prior fallback
}

@Composable
fun supplyStatusColor(tier: Int): Color = DashboardSurfaceDefaults.accentColor(supplyTierAccent(tier))

@Composable
fun supplyStatusHeaderTint(status: String?): Color? {
    val normalized = status?.takeIf { it.isNotBlank() } ?: return null
    return supplyStatusColor(SUPPLY_STATUS_PRIORITY[normalized] ?: 99)
}
```

Add the import for `DashboardSurfaceDefaults` if not already present in this file (it's in the same `com.kkc.sheettracker.ui.dashboard` package as `DashboardAccent`, which this file already imports — check the existing `import com.kkc.sheettracker.ui.dashboard.*` or add `import com.kkc.sheettracker.ui.dashboard.DashboardSurfaceDefaults` explicitly if the file uses individual imports).

- [x] **Step 4: Run the test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.supply.SupplyStatusAccentTest`
Expected: PASS.

- [x] **Step 5: Build and verify all ~25 call sites still compile**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `supplyStatusColor`/`supplyStatusHeaderTint` became `@Composable`; every existing call site (`SupplyDashboardScreen.kt`, `SupplyItemDetailScreen.kt`, `SupplyItemEditScreen.kt`, `SupplyBarcodeResultSheets.kt`, `DashboardWidgetFactories.kt`) already calls them from inside other `@Composable` functions, so this should compile without touching those call sites. If any call site errors with "@Composable invocations can only happen from the context of a @Composable function", that call site needs its enclosing function marked `@Composable` — none are expected, but this is the check that catches it.

- [x] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt app/src/test/java/com/kkc/sheettracker/ui/supply/SupplyStatusAccentTest.kt
git commit -m "fix: derive supply tier colors from the shared theme-aware accent resolver"
```

---

## Task 4: Migrate dashboard soft-status pastel colors

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardWidgetFactories.kt:889-916`

- [x] **Step 1: Replace `getSoftStatusColors`**

The existing function hardcodes pastel bg/text pairs and includes a fragile `Color` equality check (`baseColor == Color(0xFF388E3C)`) to special-case one status. Now that `supplyStatusColor` (Task 3) and `getSoftStatusColors`'s callers both resolve through the same `DashboardAccent` system, this collapses to a direct delegation. Replace lines 889–916:

```kotlin
@Composable
fun getSoftStatusColors(status: String, baseColor: Color): Pair<Color, Color> {
    // baseColor is kept for call-site compatibility (it's still passed at every call site)
    // but is no longer used — the status string alone now determines the accent, via the
    // same DashboardAccent resolver used everywhere else, so the two can't drift apart again.
    val accent = supplyAccent(status)
    return DashboardSurfaceDefaults.accentWash(accent) to DashboardSurfaceDefaults.accentColor(accent)
}
```

- [x] **Step 2: Build and verify**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `baseColor` becomes an unused parameter — expect a compiler warning, not an error, at every call site; this is intentional per the comment above (removing the parameter would require touching ~10 call sites across 4 files for no behavioral gain).

- [x] **Step 3: Run existing dashboard tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.dashboard.UnifiedDashboardFactoriesTest`
Expected: PASS.

- [x] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardWidgetFactories.kt
git commit -m "fix: derive soft status chip colors from the shared accent resolver, drop hardcoded pastel table"
```

---

## Task 5: Migrate battery indicator colors

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/BatteryIndicator.kt:65-81`

- [x] **Step 1: Replace the hardcoded literals**

Add the import:

```kotlin
import com.kkc.sheettracker.ui.theme.KKCThemeColors
```

Replace the `defaultColor` block (lines 75–80):

```kotlin
    val status = KKCThemeColors.statusColors
    val defaultColor = when {
        isCharging -> status.complete
        level <= 15 -> MaterialTheme.colorScheme.error
        level <= 30 -> status.skip
        else -> MaterialTheme.colorScheme.onSurface
    }
```

(The `level <= 15` critical case already uses the theme's `error` color and is left as-is — it's already theme-aware, just not through `KKCStatusColors`. Unifying it with `status.bad` is a further option but changes behavior for themes where `error` and `bad` diverge; out of scope for this mechanical migration.)

- [x] **Step 2: Build and verify**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/BatteryIndicator.kt
git commit -m "fix: route battery indicator colors through theme status tokens"
```

---

## Task 6: Migrate safety document status badges

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/standards/SafetyDocumentsScreen.kt:1066-1087`

- [x] **Step 1: Replace the hardcoded literals**

Add the import:

```kotlin
import com.kkc.sheettracker.ui.theme.KKCThemeColors
```

Replace `StatusBadge` (lines 1067–1074). This reuses `remakeBg`/`miscBg` — tokens that previously only served hardwoods "remake"/"misc" concepts — giving the purple/blue hue bands a second, consistent use as "active" and "acknowledged" respectively, instead of Safety inventing its own separate purple/blue:

```kotlin
@Composable
private fun StatusBadge(status: String) {
    val statusColors = KKCThemeColors.statusColors
    val (bgColor, textColor) = when (status.uppercase()) {
        "OPEN" -> statusColors.skip.copy(alpha = 0.15f) to statusColors.skip
        "ACKNOWLEDGED" -> statusColors.miscBg.copy(alpha = 0.25f) to statusColors.miscBg
        "IN PROGRESS" -> statusColors.remakeBg.copy(alpha = 0.25f) to statusColors.remakeBg
        "RESOLVED" -> statusColors.complete.copy(alpha = 0.15f) to statusColors.complete
        else -> statusColors.notStarted.copy(alpha = 0.2f) to statusColors.notStarted
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = textColor
        )
    }
}
```

- [x] **Step 2: Build and verify**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/standards/SafetyDocumentsScreen.kt
git commit -m "fix: route safety status badge colors through theme status tokens"
```

---

## Task 7: Visual regression check across all migrated surfaces

This is the "throwaway distinctive theme" verification called for in the spec's testing section — it catches any spot still reading a hardcoded literal instead of a token, across every file touched in Tasks 2–6, in one pass.

**Files:**
- Create (temporary, not committed): a test theme JSON on a connected device or emulator's synced-themes folder equivalent (see `debug-android-tablet` skill for how to push files to a connected tablet's app-private storage; if no device is connected, this step can run against the debug build in an emulator using the same `.metadata/themes` path under the app's external files directory).

- [x] **Step 1: Author a distinctive test theme**

Create a local scratch file (not committed — this is a manual verification aid) with deliberately clashing, easy-to-spot colors:

```json
{
  "id": "kkc-verify-parity",
  "name": "Parity Verification",
  "version": 1,
  "light": { "primary": "#1E5FAF", "background": "#FFFFFF", "surface": "#FFFFFF" },
  "dark": { "primary": "#79B2FF", "background": "#000000", "surface": "#162438" },
  "status": {
    "complete": "#00FF00",
    "bad": "#FF00FF",
    "skip": "#FFFF00",
    "inProgress": "#00FFFF",
    "notStarted": "#FF8800",
    "remakeBg": "#8800FF",
    "miscBg": "#FF0088"
  }
}
```

- [x] **Step 2: Push it to a connected tablet and select it**

Follow the `debug-android-tablet` skill to push this file into the app's synced themes folder on a connected device, then select "Parity Verification" from Settings' theme dropdown.

- [x] **Step 3: Visually confirm every migrated surface picked up the neon colors**

Check: Dashboard accent cards (danger/warning/info/success tiles), Supply dashboard status dots and chips, Supply item detail/edit status chips, Battery indicator (drain to ≤30% or force-charge to see the tint, or read the code path to confirm — physical battery state isn't always reproducible on demand), Safety document status badges (OPEN/ACKNOWLEDGED/IN PROGRESS/RESOLVED). Anything still showing the old muted colors instead of the neon test values means a spot was missed — go back to the relevant task and find it.

- [x] **Step 4: Remove the test theme from the device**

Delete the test theme file from the tablet's synced themes folder and re-select the normal production theme, so the tablet isn't left on the verification theme.

(No commit for this task — it's a manual verification pass, nothing in the repo changes.)

---

## Task 8: `kkc-theme-generator` skill

**Files:**
- Create: `.claude/skills/kkc-theme-generator/SKILL.md`
- Create: `.claude/skills/kkc-theme-generator/scripts/validate_theme.py`
- Create: `.agents/skills/kkc-theme-generator/SKILL.md` (mirror)
- Create: `.agents/skills/kkc-theme-generator/scripts/validate_theme.py` (mirror)

- [x] **Step 1: Write the validator script**

Create `.claude/skills/kkc-theme-generator/scripts/validate_theme.py`:

```python
#!/usr/bin/env python3
"""Validates a KKCSheetTracker theme JSON's status colors: hue-locked bands,
pairwise distinctness, and background/text contrast. Exits 0 on pass, 1 on
fail, printing every violation found (not just the first)."""
import colorsys
import json
import sys

# (min_hue, max_hue) in degrees, 0-360. bad wraps around 0/360.
HUE_BANDS = {
    "complete": [(90, 150)],
    "bad": [(0, 15), (345, 360)],
    "skip": [(20, 45)],
    "inProgress": [(200, 230)],
    "remakeBg": [(265, 300)],
    "miscBg": [(200, 230)],
}
# notStarted is gray: checked by low saturation instead of a hue band.
NOT_STARTED_MAX_SATURATION = 0.15

MIN_PAIRWISE_DISTANCE = 60.0  # Euclidean distance in 0-255 RGB space
MIN_CONTRAST_RATIO = 4.5  # WCAG AA for normal text

# Fixed onBackground/onSurface from Theme.kt — these never move with a theme,
# so a generated background/surface must stay legible against them.
FIXED_TEXT_COLORS = {
    "light": {"onBackground": "#122033", "onSurface": "#162236"},
    "dark": {"onBackground": "#E8F0FA", "onSurface": "#EBF2FC"},
}


def hex_to_rgb(hex_str):
    h = hex_str.lstrip("#")
    if len(h) == 8:
        h = h[2:]  # drop leading alpha
    if len(h) != 6:
        raise ValueError(f"Not a 6 or 8 digit hex color: {hex_str}")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def rgb_to_hue_sat(rgb):
    r, g, b = (c / 255.0 for c in rgb)
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    return h * 360.0, s


def hue_in_bands(hue, bands):
    return any(lo <= hue <= hi for lo, hi in bands)


def euclidean_distance(rgb_a, rgb_b):
    return sum((a - b) ** 2 for a, b in zip(rgb_a, rgb_b)) ** 0.5


def relative_luminance(rgb):
    def channel(c):
        c = c / 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (channel(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast_ratio(rgb_a, rgb_b):
    l1, l2 = relative_luminance(rgb_a), relative_luminance(rgb_b)
    lighter, darker = max(l1, l2), min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)


def validate_status_object(status_obj, label):
    errors = []
    parsed = {}
    for key, bands in HUE_BANDS.items():
        if key not in status_obj:
            continue
        rgb = hex_to_rgb(status_obj[key])
        parsed[key] = rgb
        hue, _ = rgb_to_hue_sat(rgb)
        if not hue_in_bands(hue, bands):
            errors.append(
                f"[{label}] '{key}' hue {hue:.1f} deg is outside its locked band {bands}"
            )
    if "notStarted" in status_obj:
        rgb = hex_to_rgb(status_obj["notStarted"])
        parsed["notStarted"] = rgb
        _, sat = rgb_to_hue_sat(rgb)
        if sat > NOT_STARTED_MAX_SATURATION:
            errors.append(
                f"[{label}] 'notStarted' saturation {sat:.2f} exceeds gray threshold "
                f"{NOT_STARTED_MAX_SATURATION}"
            )

    keys = list(parsed.keys())
    for i in range(len(keys)):
        for j in range(i + 1, len(keys)):
            dist = euclidean_distance(parsed[keys[i]], parsed[keys[j]])
            if dist < MIN_PAIRWISE_DISTANCE:
                errors.append(
                    f"[{label}] '{keys[i]}' and '{keys[j]}' are too close "
                    f"(distance {dist:.1f}, need >= {MIN_PAIRWISE_DISTANCE})"
                )
    return errors


def validate_palette_contrast(palette_obj, mode, errors):
    if "background" not in palette_obj:
        return
    bg_rgb = hex_to_rgb(palette_obj["background"])
    for text_key, text_hex in FIXED_TEXT_COLORS[mode].items():
        ratio = contrast_ratio(bg_rgb, hex_to_rgb(text_hex))
        if ratio < MIN_CONTRAST_RATIO:
            errors.append(
                f"[{mode}] background contrast against fixed {text_key} is {ratio:.2f}, "
                f"need >= {MIN_CONTRAST_RATIO}"
            )


def validate_theme(theme):
    errors = []
    status = theme.get("status", {})
    errors += validate_status_object(status, "light/dark status")
    for mode in ("light", "dark"):
        palette = theme.get(mode, {})
        validate_palette_contrast(palette, mode, errors)
    return errors


def main():
    if len(sys.argv) != 2:
        print("Usage: validate_theme.py <theme.json>", file=sys.stderr)
        sys.exit(2)
    with open(sys.argv[1], "r", encoding="utf-8") as f:
        theme = json.load(f)
    errors = validate_theme(theme)
    if errors:
        print(f"FAIL — {len(errors)} issue(s):")
        for e in errors:
            print(f"  - {e}")
        sys.exit(1)
    print("PASS")
    sys.exit(0)


if __name__ == "__main__":
    main()
```

- [x] **Step 2: Write the validator's self-tests**

Create `.claude/skills/kkc-theme-generator/scripts/test_validate_theme.py`:

```python
#!/usr/bin/env python3
"""Self-tests for validate_theme.py — run with: python3 test_validate_theme.py"""
import sys
import unittest

sys.path.insert(0, ".")
from validate_theme import validate_theme, hex_to_rgb, contrast_ratio, euclidean_distance


GOOD_THEME = {
    "light": {"primary": "#1E5FAF", "background": "#FFFFFF", "surface": "#FFFFFF"},
    "dark": {"primary": "#79B2FF", "background": "#000000", "surface": "#162438"},
    "status": {
        "complete": "#388E3C",
        "bad": "#C62828",
        "skip": "#E65100",
        "inProgress": "#1565C0",
        "notStarted": "#78909C",
        "remakeBg": "#8E24AA",
        "miscBg": "#1976D2",
    },
}


class ValidateThemeTest(unittest.TestCase):
    def test_good_theme_passes(self):
        self.assertEqual([], validate_theme(GOOD_THEME))

    def test_bad_status_hue_outside_locked_band_fails(self):
        theme = {**GOOD_THEME, "status": {**GOOD_THEME["status"], "bad": "#00FF00"}}
        errors = validate_theme(theme)
        self.assertTrue(any("'bad' hue" in e for e in errors))

    def test_two_status_colors_too_close_fails(self):
        theme = {**GOOD_THEME, "status": {**GOOD_THEME["status"], "skip": GOOD_THEME["status"]["bad"]}}
        errors = validate_theme(theme)
        self.assertTrue(any("too close" in e for e in errors))

    def test_notstarted_too_saturated_fails(self):
        theme = {**GOOD_THEME, "status": {**GOOD_THEME["status"], "notStarted": "#FF0000"}}
        errors = validate_theme(theme)
        self.assertTrue(any("saturation" in e for e in errors))

    def test_low_contrast_background_fails(self):
        theme = {**GOOD_THEME, "light": {**GOOD_THEME["light"], "background": "#DDDDDD"}}
        errors = validate_theme(theme)
        self.assertTrue(any("contrast" in e for e in errors))

    def test_hex_to_rgb_handles_argb_alpha_prefix(self):
        self.assertEqual((0x33, 0x44, 0x55), hex_to_rgb("#AA334455"))

    def test_contrast_ratio_is_symmetric(self):
        a, b = hex_to_rgb("#FFFFFF"), hex_to_rgb("#000000")
        self.assertAlmostEqual(contrast_ratio(a, b), contrast_ratio(b, a))

    def test_euclidean_distance_zero_for_identical_colors(self):
        rgb = hex_to_rgb("#123456")
        self.assertEqual(0.0, euclidean_distance(rgb, rgb))


if __name__ == "__main__":
    unittest.main()
```

- [x] **Step 3: Run the self-tests**

Run: `python .claude/skills/kkc-theme-generator/scripts/test_validate_theme.py -v`
Expected: `OK` with 8 tests passed. If `test_good_theme_passes` fails, the hue bands or thresholds in `validate_theme.py` are miscalibrated against the app's own built-in colors — fix the bands/thresholds, not the test.

- [x] **Step 4: Write the skill file**

Create `.claude/skills/kkc-theme-generator/SKILL.md`:

```markdown
---
name: kkc-theme-generator
description: >-
  Use when creating or editing a KKCSheetTracker theme JSON from seed brand
  colors (e.g. "make a theme for the Kansas City Chiefs", "generate a KKC
  theme from these two hex colors"). Produces a theme JSON with hue-locked,
  validated status colors so job/part status (complete, bad parts, skipped,
  remake, misc, not started) stays recognizable across every theme.
---

# KKC Theme Generator

## Overview

KKCSheetTracker themes carry operational meaning, not just decoration: orange
always means "skipped", red always means "bad parts", green always means
"complete", purple always means "remake", blue always means "misc", and gray
always means "not started" — a shop worker learns this once and it must hold
in every theme. This skill generates a new theme JSON from 1-2 seed brand
colors while preserving that rule, and validates the result before handing
it back.

## Inputs

- A theme id (kebab-case, e.g. `nfl-chiefs`) and display name.
- 1-2 seed colors (hex), e.g. a team's primary and secondary brand colors.

## Process

1. **Seed color placement.** The seed color drives `primary` and the
   decorative `widthBand`/`progressGradientStart`/`progressGradientEnd`
   fields — never `background` or `surface` directly. `background`/`surface`
   stay near-white (light) / near-black (dark), lightly tinted toward the
   seed hue at low saturation. Reason: the app's base `ColorScheme` fixes
   `onBackground`/`onSurface` text colors independent of the theme; a vivid
   background would break contrast against that fixed text.

2. **Status colors — hue-locked, seed-toned.** For each status, pick a hue
   inside its locked band (below), then set saturation/lightness to match
   the seed color's mood (warmer/cooler, more/less saturated). Never leave
   a locked band, even for a strongly-colored team.

   | Status | Locked hue band | Meaning |
   |---|---|---|
   | `complete` | 90-150 deg (green) | job/part complete |
   | `bad` | 345-360 or 0-15 deg (red) | bad parts |
   | `skip` | 20-45 deg (orange) | skipped |
   | `inProgress` | 200-230 deg (blue) | in progress |
   | `remakeBg` | 265-300 deg (purple) | remake |
   | `miscBg` | 200-230 deg (blue, lighter than inProgress) | misc |
   | `notStarted` | any hue, saturation <= 0.15 | not started (gray) |

3. **Write the theme JSON**, following the schema in
   `app/src/main/java/com/kkc/sheettracker/ui/theme/KKCThemeRepository.kt`
   (the `light`/`dark`/`status`/`surface`/`header`/`frosted`/`shape` object
   shape). Leave `surface`/`header`/`frosted`/`shape` at built-in defaults
   unless the request specifically asks for a custom shape or header image.

4. **Validate.** Run:

   ```
   python .claude/skills/kkc-theme-generator/scripts/validate_theme.py <path-to-theme.json>
   ```

   If it fails, read the printed violations, adjust saturation/lightness
   *within the locked hue band* (never change the band itself), and
   re-run. Repeat until it prints `PASS`.

5. **Save the output** to `themes/generated/<team-id>.json` (create the
   `themes/generated/` directory if it doesn't exist yet). Do not sync it to
   a tablet automatically — that's a separate deployment step the user
   controls (see `debug-android-tablet` skill for pushing files to a
   connected device).

## Example

Seed colors: `#E31837` (red) and `#FFB612` (gold) — Kansas City Chiefs.

- `primary` (light): a saturated red-gold blend leaning toward `#C8102E`.
- `status.complete`: a green inside 90-150 deg, e.g. `#3F8F3F` (kept fairly
  neutral in saturation since the seed colors are red/gold, not green —
  don't force team colors onto a locked band that has nothing to do with
  them).
- `status.bad`: since the team's own red (`#E31837`) already sits in the
  bad-parts locked band (345-360/0-15 deg), it's tempting to reuse it
  directly — but check contrast against its own `badBg`/text pairing before
  doing so; a mismatch here is a common validator failure.
- Run the validator, fix anything it flags, save to
  `themes/generated/nfl-chiefs.json`.
```

- [x] **Step 5: Mirror to `.agents/skills`**

```bash
mkdir -p .agents/skills/kkc-theme-generator/scripts
cp .claude/skills/kkc-theme-generator/SKILL.md .agents/skills/kkc-theme-generator/SKILL.md
cp .claude/skills/kkc-theme-generator/scripts/validate_theme.py .agents/skills/kkc-theme-generator/scripts/validate_theme.py
cp .claude/skills/kkc-theme-generator/scripts/test_validate_theme.py .agents/skills/kkc-theme-generator/scripts/test_validate_theme.py
```

- [x] **Step 6: Dry-run the skill end to end**

Run the validator against the known-good theme used in the self-tests, saved to a scratch file, to confirm the CLI invocation documented in the skill actually works as written:

```bash
python -c "import json; json.dump({'light':{'primary':'#1E5FAF','background':'#FFFFFF','surface':'#FFFFFF'},'dark':{'primary':'#79B2FF','background':'#000000','surface':'#162438'},'status':{'complete':'#388E3C','bad':'#C62828','skip':'#E65100','inProgress':'#1565C0','notStarted':'#78909C','remakeBg':'#8E24AA','miscBg':'#1976D2'}}, open('scratch-theme.json','w'))"
python .claude/skills/kkc-theme-generator/scripts/validate_theme.py scratch-theme.json
rm scratch-theme.json
```

Expected: prints `PASS`.

- [x] **Step 7: Commit**

```bash
git add .claude/skills/kkc-theme-generator .agents/skills/kkc-theme-generator
git commit -m "feat: add kkc-theme-generator skill with hue-locked status color validator"
```

---

## Task 9: Full test suite regression check

**Files:** none modified — verification only.

- [x] **Step 1: Run the full unit test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures. This confirms `HardwoodsRowHelpersTest` and every other existing suite stayed green — Tasks 1-6 changed color *sourcing* only, never status-derivation logic, so no existing test should need updating.

- [x] **Step 2: Assemble the debug APK**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

No commit for this task — if it fails, go back to the task that caused the failure and fix it there, then re-run this task.

---

## Open follow-up (not part of this plan — tracked as a todo)

- Populate the actual NFL + college football team theme catalog by running the finished `kkc-theme-generator` skill once per team.
- Redesign the theme picker: add a "Football Team" option to the dropdown in `SettingsScreen.kt` that opens a search-with-autofill picker over the generated team themes in `themes/generated/`.
