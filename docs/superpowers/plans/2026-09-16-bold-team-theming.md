# Bold Team Theming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in `boldMode` + `secondary` color to the theme schema, a shared gradient/glow chrome primitive, and apply it to the header, navbar, and the neutral/decorative surfaces of Dashboard, Supply, Timeclock, and Calculator — without ever overriding a color that already carries status or state meaning.

**Architecture:** `KKCThemePalette` gains an optional `secondary` color; `KKCThemeTokens` gains a `boldMode: Boolean` (defaults `false`, fully backward-compatible). A new `KKCBoldChrome.kt` file provides pure gradient/glow resolution functions plus one `@Composable` helper (`kkcFrostedBaseColor()`) that every frosted-glass surface in the app already has a near-identical `MaterialTheme.colorScheme.surface`-based call site for — so this becomes a small, consistent, repeated one-line swap rather than a bespoke change per screen. The Dashboard's `DashboardHeroSurface` and `DashboardSectionHeader` (already shared with Supply, confirmed by reading the code) get their own bold-gradient branches.

**Tech Stack:** Kotlin, Jetpack Compose, Gson (theme JSON parsing), JUnit (JVM unit tests).

---

## Important: scope adjustments discovered while planning

The approved design spec ([2026-09-16-bold-team-theming-design.md](../specs/2026-09-16-bold-team-theming-design.md)) describes two things that turned out, on reading the actual code, to need adjustment. Both are documented here rather than silently dropped:

1. **Status/state-meaning colors are never overridden, even where the spec's wording could be read either way.** Reading the actual Dashboard stat tiles confirmed their card *background* (not just the number text) IS how status meaning is conveyed (green wash = Completed, red wash = Bad Parts, etc. — confirmed against the real device screenshot from the prior plan). Bold mode does not touch these. Same reasoning applies to Timeclock's Clock In/Out button: its red/green fill is a meaningful "you are about to clock OUT (red) / IN (green)" signal, not decoration — reading `TimecardScreen.kt` confirmed `bgColor = if (isClockedIn) ClockOutRed else ClockInGreen`, a fixed semantic pair already deliberately excluded from theme migration in the prior plan. Bold mode does not touch it either. This is the same principle the spec itself states for Dashboard/Supply status chips, applied consistently everywhere the same conflict exists.

2. **Material3's standard `Button` and `LinearProgressIndicator` don't accept a gradient `Brush` for their fill — only a solid `Color`.** Making an individual button or the progress bar genuinely gradient-filled would mean replacing those standard widgets with hand-built composables at every call site (dozens, scattered across Supply and Calculator), which is unbounded scope for this plan. This plan instead focuses on the surfaces where a gradient/glow *is* a small, direct, low-risk swap: `Card`/`Box` backgrounds, frosted-glass tints, the header badge, and the navbar's selected-tab indicator (which is a plain `Modifier.background`, not a `Button`, so it *does* support a brush). Gradient-filled buttons and a gradient progress bar are a reasonable fast-follow, not part of this plan.

---

## File Structure

- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/theme/KKCThemeTokens.kt` — add `secondary` to `KKCThemePalette`, `boldMode` to `KKCThemeTokens`.
- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/theme/KKCThemeRepository.kt` — parse both new fields.
- **Modify** `app/src/test/java/com/kkc/sheettracker/ui/theme/KKCThemeRepositoryTest.kt` — new tests.
- **Create** `app/src/main/java/com/kkc/sheettracker/ui/theme/KKCBoldChrome.kt` — pure gradient/glow resolution + the shared `kkcFrostedBaseColor()` composable.
- **Create** `app/src/test/java/com/kkc/sheettracker/ui/theme/KKCBoldChromeTest.kt` — unit tests for the pure functions.
- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/components/KKCBrandedTitle.kt` — gradient chip for the header badge.
- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt` — bold frosted tint + gradient selected-tab indicator.
- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardSurfacePrimitives.kt` — `DashboardHeroSurface` gradient branch, `DashboardSectionHeader` gradient accent bar (shared with Supply).
- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/timecard/TimecardScreen.kt` — frosted tint swap on `DisplayCard`/`NumpadKey` (4 call sites).
- **Modify** `app/src/main/java/com/kkc/sheettracker/ui/components/CalculatorOverlay.kt` — frosted tint swap on the overlay panel.
- **Modify** `themes/generated/nfl-*.json` (all 32 files) — add `secondary` and `boldMode: true`.

---

## Task 1: Schema — `secondary` color and `boldMode`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/theme/KKCThemeTokens.kt:8-30`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/theme/KKCThemeRepository.kt:119-129, 308-315`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/theme/KKCThemeRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `KKCThemeRepositoryTest.kt` (after the last existing test, `headerBadgeLogoPathSiblingPrefixDirectoryIsRejected`):

```kotlin
    @Test
    fun paletteSecondaryIsNullByDefault() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(baseDir, "shop-blue.json", validThemeJson(id = "kkc-shop-blue"))

        val light = KKCThemeRepository(baseDir, FakeThemePreferences()).loadCatalog()
            .themes.first { it.id == "kkc-shop-blue" }.tokens.light

        assertEquals(null, light.secondary)
    }

    @Test
    fun paletteSecondaryParsesFromJsonWhenPresent() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(
            baseDir = baseDir,
            filename = "nfl-chiefs.json",
            body = validThemeJson(id = "nfl-chiefs").replace(
                """"light": { "primary": "#1E5FAF", "background": "#EFF4FA", "surface": "#FFFFFF" }""",
                """"light": { "primary": "#E31837", "secondary": "#FFB612", "background": "#EFF4FA", "surface": "#FFFFFF" }"""
            )
        )

        val light = KKCThemeRepository(baseDir, FakeThemePreferences()).loadCatalog()
            .themes.first { it.id == "nfl-chiefs" }.tokens.light

        assertEquals(Color(0xFFFFB612), light.secondary)
    }

    @Test
    fun boldModeDefaultsToFalseWhenAbsent() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(baseDir, "shop-blue.json", validThemeJson(id = "kkc-shop-blue"))

        val tokens = KKCThemeRepository(baseDir, FakeThemePreferences()).loadCatalog()
            .themes.first { it.id == "kkc-shop-blue" }.tokens

        assertEquals(false, tokens.boldMode)
    }

    @Test
    fun boldModeParsesFromJsonWhenPresent() {
        val baseDir = temp.newFolder("Ready Jobs")
        writeTheme(
            baseDir = baseDir,
            filename = "nfl-chiefs.json",
            body = validThemeJson(id = "nfl-chiefs", extra = """, "boldMode": true""")
        )

        val tokens = KKCThemeRepository(baseDir, FakeThemePreferences()).loadCatalog()
            .themes.first { it.id == "nfl-chiefs" }.tokens

        assertEquals(true, tokens.boldMode)
    }

    @Test
    fun builtInThemeBoldModeIsFalse() {
        assertEquals(false, BuiltInKKCThemeTokens.boldMode)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.theme.KKCThemeRepositoryTest`
Expected: FAIL — `secondary`/`boldMode` don't exist yet (compile error).

- [ ] **Step 3: Add the fields**

In `KKCThemeTokens.kt`, change `KKCThemePalette` (lines 26-30):

```kotlin
@Immutable
data class KKCThemePalette(
    val primary: Color,
    val background: Color,
    val surface: Color,
    val secondary: Color? = null
)
```

Change `KKCThemeTokens` (lines 8-19) to add `boldMode`:

```kotlin
@Immutable
data class KKCThemeTokens(
    val id: String,
    val name: String,
    val light: KKCThemePalette,
    val dark: KKCThemePalette,
    val lightStatus: KKCStatusColors,
    val darkStatus: KKCStatusColors,
    val surface: KKCThemeSurfaceTokens,
    val header: KKCThemeHeaderTokens,
    val frosted: KKCThemeFrostedTokens,
    val shape: KKCThemeShapeTokens,
    val spacingScale: Float,
    val boldMode: Boolean = false
) {
    fun palette(darkTheme: Boolean): KKCThemePalette = if (darkTheme) dark else light
    fun status(darkTheme: Boolean): KKCStatusColors = if (darkTheme) darkStatus else lightStatus
}
```

`BuiltInKKCThemeTokens`'s literal instantiation needs no change — `boldMode` defaults to `false`, matching today's only behavior.

- [ ] **Step 4: Parse both fields in `KKCThemeRepository.kt`**

Add a `boolean()` helper next to the existing `int`/`float` helpers (near line 351):

```kotlin
    private fun boolean(obj: JsonObject?, key: String): Boolean? {
        val value = obj?.get(key) ?: return null
        return runCatching {
            if (value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) value.asBoolean else null
        }.getOrNull()
    }
```

Change the `palette(root, key)` function (lines 308-315) to parse `secondary`:

```kotlin
    private fun palette(root: JsonObject, key: String): KKCThemePalette {
        val obj = root.getAsJsonObject(key) ?: throw IllegalArgumentException("Missing $key")
        return KKCThemePalette(
            primary = requiredColor(obj, "primary", "$key.primary"),
            background = requiredColor(obj, "background", "$key.background"),
            surface = requiredColor(obj, "surface", "$key.surface"),
            secondary = color(obj, "secondary")
        )
    }
```

In `parseThemeFile`, add `boldMode` parsing right after the existing `val category = ...` line (line 127):

```kotlin
            val boldMode = boolean(root, "boldMode") ?: false
```

Then add `boldMode = boldMode` to the `KKCThemeTokens(...)` construction — it's the last parameter, so add it right after the existing `spacingScale = float(root, "spacingScale") ?: BuiltInKKCThemeTokens.spacingScale` line:

```kotlin
                    spacingScale = float(root, "spacingScale") ?: BuiltInKKCThemeTokens.spacingScale,
                    boldMode = boldMode
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.theme.KKCThemeRepositoryTest`
Expected: PASS — all tests including the five new ones.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/theme/KKCThemeTokens.kt app/src/main/java/com/kkc/sheettracker/ui/theme/KKCThemeRepository.kt app/src/test/java/com/kkc/sheettracker/ui/theme/KKCThemeRepositoryTest.kt
git commit -m "feat: add secondary palette color and boldMode to theme schema"
```

---

## Task 2: Shared bold-chrome primitives

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/theme/KKCBoldChrome.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/theme/KKCBoldChromeTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/kkc/sheettracker/ui/theme/KKCBoldChromeTest.kt`:

```kotlin
package com.kkc.sheettracker.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class KKCBoldChromeTest {

    private val paletteWithSecondary = KKCThemePalette(
        primary = Color(0xFFE31837),
        secondary = Color(0xFFFFB612),
        background = Color.White,
        surface = Color.White
    )

    private val paletteWithoutSecondary = KKCThemePalette(
        primary = Color(0xFFE31837),
        secondary = null,
        background = Color.White,
        surface = Color.White
    )

    @Test
    fun boldGradientColorsUsesPrimaryAndSecondaryWhenBothSet() {
        assertEquals(listOf(Color(0xFFE31837), Color(0xFFFFB612)), boldGradientColors(paletteWithSecondary))
    }

    @Test
    fun boldGradientColorsFallsBackToPrimaryTwiceWhenSecondaryAbsent() {
        assertEquals(listOf(Color(0xFFE31837), Color(0xFFE31837)), boldGradientColors(paletteWithoutSecondary))
    }

    @Test
    fun boldGlowColorBlendsMidpointWhenSecondarySet() {
        val glow = boldGlowColor(paletteWithSecondary, alpha = 0.3f)
        val expectedBase = androidx.compose.ui.graphics.lerp(Color(0xFFE31837), Color(0xFFFFB612), 0.5f)
        assertEquals(expectedBase.copy(alpha = 0.3f), glow)
    }

    @Test
    fun boldGlowColorUsesPrimaryWhenSecondaryAbsent() {
        val glow = boldGlowColor(paletteWithoutSecondary, alpha = 0.4f)
        assertEquals(Color(0xFFE31837).copy(alpha = 0.4f), glow)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.theme.KKCBoldChromeTest`
Expected: FAIL — `boldGradientColors`/`boldGlowColor` don't exist yet (compile error).

- [ ] **Step 3: Create the primitive file**

Create `app/src/main/java/com/kkc/sheettracker/ui/theme/KKCBoldChrome.kt`:

```kotlin
package com.kkc.sheettracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The two stops a bold-mode gradient blends between: a theme's primary and, when set, secondary
 * color. Falls back to primary alone (a flat "gradient") when no secondary color is configured,
 * so callers never need a separate no-secondary code path.
 */
fun boldGradientColors(palette: KKCThemePalette): List<Color> {
    val end = palette.secondary ?: palette.primary
    return listOf(palette.primary, end)
}

/** A `Brush` built from [boldGradientColors] — the actual fill used by bold-mode chrome. */
fun boldGradientBrush(palette: KKCThemePalette): Brush = Brush.linearGradient(boldGradientColors(palette))

/**
 * A single blended tint for frosted-glass "glow" surfaces (navbar, Timeclock, Calculator): the
 * midpoint between primary and secondary, or plain primary when no secondary is set, at the
 * given alpha.
 */
fun boldGlowColor(palette: KKCThemePalette, alpha: Float): Color {
    val base = palette.secondary?.let { lerp(palette.primary, it, 0.5f) } ?: palette.primary
    return base.copy(alpha = alpha)
}

/**
 * The base color every frosted-glass surface in the app builds its tint from. Returns the
 * bold-mode glow (full alpha — callers apply their own `.copy(alpha = ...)` exactly as they do
 * today) when the active theme has `boldMode` on, otherwise the same neutral
 * `MaterialTheme.colorScheme.surface` every frosted surface already uses. This is a drop-in
 * replacement for that one expression, not a new modifier chain — see its call sites in
 * `AppScaffold.kt`, `TimecardScreen.kt`, and `CalculatorOverlay.kt`.
 */
@Composable
fun kkcFrostedBaseColor(): Color {
    val tokens = LocalKKCThemeTokens.current
    return if (tokens.boldMode) {
        boldGlowColor(tokens.palette(LocalKKCIsDarkTheme.current), alpha = 1f)
    } else {
        MaterialTheme.colorScheme.surface
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.theme.KKCBoldChromeTest`
Expected: PASS — all 4 tests.

- [ ] **Step 5: Compile the full app**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/theme/KKCBoldChrome.kt app/src/test/java/com/kkc/sheettracker/ui/theme/KKCBoldChromeTest.kt
git commit -m "feat: add shared bold-chrome gradient/glow primitives"
```

---

## Task 3: Header gradient chip

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/KKCBrandedTitle.kt`

- [ ] **Step 1: Replace the `TEXT` branch**

Replace the full file content with:

```kotlin
package com.kkc.sheettracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import com.kkc.sheettracker.ui.theme.KKCThemeHeaderTokens
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
import com.kkc.sheettracker.ui.theme.LocalKKCThemeTokens
import com.kkc.sheettracker.ui.theme.boldGradientBrush
import java.io.File

internal enum class BrandedTitleKind { LOGO, TEXT, DEFAULT }

/**
 * Priority when a theme sets both fields: an explicit logo image wins over styled text,
 * which wins over the literal "KKC Dashboard" default every theme had before badges existed.
 */
internal fun resolveBrandedTitleKind(header: KKCThemeHeaderTokens): BrandedTitleKind = when {
    !header.badgeLogoPath.isNullOrBlank() -> BrandedTitleKind.LOGO
    !header.badgeText.isNullOrBlank() -> BrandedTitleKind.TEXT
    else -> BrandedTitleKind.DEFAULT
}

/**
 * Replaces the literal "KKC Dashboard" title text with a theme's badge (logo image or styled
 * text) when one is set, appending " - $modeSuffix" either way. Intended for use by both the
 * Dashboard and Jobs screen top bars (wired in a later task) so the two don't duplicate this
 * priority logic. When the active theme's `boldMode` is on, styled text renders on a gradient
 * chip instead of plain colored text on the app bar background.
 */
@Composable
fun KKCBrandedTitle(modeSuffix: String, modifier: Modifier = Modifier) {
    val header = LocalKKCThemeTokens.current.header
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        when (resolveBrandedTitleKind(header)) {
            BrandedTitleKind.LOGO -> {
                val context = LocalContext.current
                val imageLoader = remember(context) {
                    ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build()
                }
                AsyncImage(
                    model = File(header.badgeLogoPath!!),
                    contentDescription = "Team logo",
                    imageLoader = imageLoader,
                    modifier = Modifier.height(28.dp)
                )
            }
            BrandedTitleKind.TEXT -> {
                val tokens = LocalKKCThemeTokens.current
                if (tokens.boldMode) {
                    val palette = tokens.palette(LocalKKCIsDarkTheme.current)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(boldGradientBrush(palette))
                            .padding(PaddingValues(horizontal = 10.dp, vertical = 3.dp))
                    ) {
                        Text(
                            text = header.badgeText!!.uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium.copy(
                                shadow = Shadow(
                                    color = Color.Black.copy(alpha = 0.4f),
                                    offset = Offset(0f, 1f),
                                    blurRadius = 3f
                                )
                            )
                        )
                    }
                } else {
                    Text(
                        text = header.badgeText!!.uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            BrandedTitleKind.DEFAULT -> {
                Text("KKC Dashboard", style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(" - $modeSuffix", style = MaterialTheme.typography.titleMedium)
    }
}
```

- [ ] **Step 2: Run the existing tests to verify no regression**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.components.KKCBrandedTitleTest`
Expected: PASS — all 5 existing tests (they test `resolveBrandedTitleKind`, which is unchanged).

- [ ] **Step 3: Compile the full app**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/KKCBrandedTitle.kt
git commit -m "feat: render header badge on a gradient chip when boldMode is on"
```

---

## Task 4: Navbar — bold frosted tint and gradient selected-indicator

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt:234, 247-250, 316-319, 448`

- [ ] **Step 1: Add the import**

Add near the other `com.kkc.sheettracker.ui.theme.*` imports in `AppScaffold.kt`:

```kotlin
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
import com.kkc.sheettracker.ui.theme.boldGradientBrush
import com.kkc.sheettracker.ui.theme.kkcFrostedBaseColor
```

(Check the file doesn't already import `LocalKKCIsDarkTheme` under a different alias before adding — it wasn't in the file as of the last completed plan's changes, so this should be a plain new addition.)

- [ ] **Step 2: Bold-ize the selected-tab indicator**

Find the line `val indicatorColor = MaterialTheme.colorScheme.surfaceVariant` (around line 234) and add right after it:

```kotlin
        val indicatorColor = MaterialTheme.colorScheme.surfaceVariant
        val navBoldTokens = LocalKKCThemeTokens.current
        val navBoldPalette = navBoldTokens.palette(LocalKKCIsDarkTheme.current)
        fun Modifier.navSelectionBackground(active: Boolean): Modifier {
            if (!active) return this
            return if (navBoldTokens.boldMode) {
                background(brush = boldGradientBrush(navBoldPalette), shape = indicatorShape, alpha = 0.55f)
            } else {
                background(color = indicatorColor, shape = indicatorShape)
            }
        }
```

Then replace both existing background calls that reference `indicatorColor`:

At the calculator slot (around line 247-250):

```kotlin
                            .clip(indicatorShape)
                            .navSelectionBackground(isCalculatorOpen)
```

(replacing the previous `.background(color = if (isCalculatorOpen) indicatorColor else Color.Transparent, shape = indicatorShape)`)

At the regular destination icon (around line 316-319):

```kotlin
                            .clip(indicatorShape)
                            .navSelectionBackground(selected)
```

(replacing the previous `.background(color = if (selected) indicatorColor else Color.Transparent, shape = indicatorShape)`)

- [ ] **Step 3: Bold-ize the navbar's frosted glass tint**

Find `val minHazeSurface = MaterialTheme.colorScheme.surface` (around line 448) and replace it with:

```kotlin
        val minHazeSurface = kkcFrostedBaseColor()
```

Leave every other use of `minHazeSurface` in this function (the `.copy(alpha = ...)` calls that follow it) exactly as they are — this is a drop-in replacement of the base color only.

- [ ] **Step 4: Compile and verify**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt
git commit -m "feat: bold navbar frosted tint and gradient selected-tab indicator"
```

---

## Task 5: Dashboard + Supply — hero card and section header

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardSurfacePrimitives.kt:1-30, 131-169`

- [ ] **Step 1: Add imports**

Add to the top of `DashboardSurfacePrimitives.kt`:

```kotlin
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import com.kkc.sheettracker.ui.theme.LocalKKCIsDarkTheme
import com.kkc.sheettracker.ui.theme.boldGradientBrush
```

- [ ] **Step 2: Give `DashboardHeroSurface` a bold branch**

Replace the existing `DashboardHeroSurface` function (currently a thin wrapper around `DashboardSurfaceCard`) with:

```kotlin
@Composable
fun DashboardHeroSurface(
    modifier: Modifier = Modifier,
    accent: DashboardAccent = DashboardAccent.INFO,
    content: @Composable ColumnScope.() -> Unit
) {
    val tokens = LocalKKCThemeTokens.current
    if (tokens.boldMode) {
        val lowEnd = LocalLowEndMode.current
        val shape = DashboardSurfaceDefaults.heroShape
        val palette = tokens.palette(LocalKKCIsDarkTheme.current)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .shadow(elevation = if (lowEnd.shadowsDisabled) 0.dp else 3.dp, shape = shape, clip = false)
                .clip(shape)
                .background(boldGradientBrush(palette))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    } else {
        DashboardSurfaceCard(
            modifier = modifier,
            accent = accent,
            shape = DashboardSurfaceDefaults.heroShape,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            content = content
        )
    }
}
```

This leaves `DashboardSurfaceCard` itself (and every status-tinted card that calls it with `tinted = true`, including the Dashboard stat tiles) completely untouched — `DashboardHeroSurface` only delegates to it in the non-bold case, exactly as before.

- [ ] **Step 3: Give `DashboardSectionHeader` a bold accent bar**

Replace the existing `DashboardSectionHeader` function with:

```kotlin
@Composable
fun DashboardSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    val tokens = LocalKKCThemeTokens.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (tokens.boldMode) {
            val palette = tokens.palette(LocalKKCIsDarkTheme.current)
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(boldGradientBrush(palette))
            )
            Spacer(modifier = Modifier.height(2.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

Since `SupplyDashboardScreen.kt` already calls this exact same `DashboardSectionHeader` (confirmed by reading its imports and call sites), this one change covers both Dashboard's and Supply's section headers — no separate Supply-specific edit needed.

- [ ] **Step 4: Compile and verify**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run existing dashboard/supply tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.dashboard.UnifiedDashboardFactoriesTest`
Expected: PASS — no regression.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardSurfacePrimitives.kt
git commit -m "feat: bold gradient for Dashboard hero card and shared section headers (Dashboard + Supply)"
```

---

## Task 6: Timeclock — bold frosted tint

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/timecard/TimecardScreen.kt:328, 333, 506, 511`

- [ ] **Step 1: Add the import**

```kotlin
import com.kkc.sheettracker.ui.theme.kkcFrostedBaseColor
```

- [ ] **Step 2: Swap all four frosted-tint base colors**

There are four occurrences of `MaterialTheme.colorScheme.surface` used as the base of a frosted-glass tint in this file (two in the numpad display card around lines 328/333, two in the individual numpad key around lines 506/511) — each currently followed by `.copy(alpha = ...)`. Replace each occurrence of `MaterialTheme.colorScheme.surface` in these four spots with `kkcFrostedBaseColor()`, keeping the surrounding `.copy(alpha = ...)` calls exactly as they are. For example, line 328 changes from:

```kotlin
                            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = frostedTokens.backgroundAlpha.coerceIn(0.72f, 0.95f)),
```

to:

```kotlin
                            backgroundColor = kkcFrostedBaseColor().copy(alpha = frostedTokens.backgroundAlpha.coerceIn(0.72f, 0.95f)),
```

Apply the identical pattern (swap only the `MaterialTheme.colorScheme.surface` part, leave everything after the first `.copy(` untouched) at lines 333, 506, and 511.

**Do not touch** the Clock In/Out action button's `bgColor = if (isClockedIn) ClockOutRed else ClockInGreen` logic anywhere in this file — that is a meaningful state color, explicitly out of scope per this plan's scope-adjustments note above.

- [ ] **Step 3: Compile and verify**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/timecard/TimecardScreen.kt
git commit -m "feat: bold frosted glass tint for Timeclock numpad surfaces"
```

---

## Task 7: Calculator — bold frosted tint

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/CalculatorOverlay.kt:421, 428`

- [ ] **Step 1: Add the import**

```kotlin
import com.kkc.sheettracker.ui.theme.kkcFrostedBaseColor
```

- [ ] **Step 2: Swap the two frosted-tint base colors**

In `FullscreenCalculator`, replace both occurrences of `MaterialTheme.colorScheme.surface` used as the panel's frosted base (line 421, inside the `hazeEffect` branch's `backgroundColor`; line 428, the non-haze fallback's `Modifier.background(...)` argument) with `kkcFrostedBaseColor()`. Line 421 changes from:

```kotlin
                backgroundColor = MaterialTheme.colorScheme.surface.copy(
                    alpha = frostedTokens.backgroundAlpha.coerceIn(0.72f, 0.95f)
                ),
```

to:

```kotlin
                backgroundColor = kkcFrostedBaseColor().copy(
                    alpha = frostedTokens.backgroundAlpha.coerceIn(0.72f, 0.95f)
                ),
```

Line 428 changes from `Modifier.background(MaterialTheme.colorScheme.surface)` to `Modifier.background(kkcFrostedBaseColor())`.

There's a second unrelated `MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f)` at line 441 (the calculator's title bar row) — leave that one alone, it's a different, non-frosted surface and out of scope for this task.

**Do not touch** the operator/equals button colors anywhere in this file — per this plan's scope-adjustments note, Material3 `Button` doesn't support a gradient fill, and reimplementing those buttons as custom composables is out of scope here.

- [ ] **Step 3: Compile and verify**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/CalculatorOverlay.kt
git commit -m "feat: bold frosted glass tint for the Calculator overlay panel"
```

---

## Task 8: Regenerate the 32 NFL themes with secondary colors and boldMode

**Files:**
- Modify: `themes/generated/nfl-*.json` (all 32 files)

- [ ] **Step 1: Write the one-off regeneration script**

Write to your scratchpad directory as `scratch-regenerate-nfl-themes.py`. This OVERWRITES the existing 32 files in place (adding `secondary` and `boldMode`, keeping every other field — `id`, `name`, `category`, `header.badgeText`, `background`/`surface` — unchanged) rather than regenerating from scratch, so the Cowboys primary-color fix from the prior plan is preserved automatically (the script reads each file's own current `primary` value instead of hardcoding it again):

```python
#!/usr/bin/env python3
"""One-off: adds secondary + boldMode to the 32 existing NFL theme files in place.
Not part of the repo - run once, then discard."""
import json
import os

# (team_id, secondary_hex) - primary/name/category/badgeText are read from each
# existing file, not restated here, so the prior Cowboys color fix isn't re-clobbered.
SECONDARY_COLORS = {
    "nfl-cardinals":  "#000000",
    "nfl-falcons":    "#000000",
    "nfl-ravens":     "#000000",
    "nfl-bills":      "#C60C30",
    "nfl-panthers":   "#101820",
    "nfl-bears":      "#C83803",
    "nfl-bengals":    "#000000",
    "nfl-browns":     "#FF3C00",
    "nfl-cowboys":    "#869397",
    "nfl-broncos":    "#002244",
    "nfl-lions":      "#B0B7BC",
    "nfl-packers":    "#FFB612",
    "nfl-texans":     "#A71930",
    "nfl-colts":      "#A2AAAD",
    "nfl-jaguars":    "#006778",
    "nfl-chiefs":     "#FFB612",
    "nfl-raiders":    "#A5ACAF",
    "nfl-chargers":   "#FFC20E",
    "nfl-rams":       "#FFA300",
    "nfl-dolphins":   "#FC4C02",
    "nfl-vikings":    "#FFC62F",
    "nfl-patriots":   "#C60C30",
    "nfl-saints":     "#101820",
    "nfl-giants":     "#A71930",
    "nfl-jets":       "#000000",
    "nfl-eagles":     "#A5ACAF",
    "nfl-steelers":   "#101820",
    "nfl-49ers":      "#B3995D",
    "nfl-seahawks":   "#69BE28",
    "nfl-buccaneers": "#34302B",
    "nfl-titans":     "#4B92DB",
    "nfl-commanders": "#FFB612",
}

THEMES_DIR = "themes/generated"


def main():
    updated = 0
    for team_id, secondary_hex in SECONDARY_COLORS.items():
        path = os.path.join(THEMES_DIR, f"{team_id}.json")
        with open(path, "r", encoding="utf-8") as f:
            theme = json.load(f)

        if theme.get("id") != team_id:
            raise ValueError(f"{path}: id mismatch, expected {team_id}, got {theme.get('id')}")

        theme["light"]["secondary"] = secondary_hex
        theme["dark"]["secondary"] = secondary_hex
        theme["boldMode"] = True

        with open(path, "w", encoding="utf-8") as f:
            json.dump(theme, f, indent=2)
            f.write("\n")
        updated += 1
        print(f"updated {path}")

    print(f"\n{updated} theme files updated in {THEMES_DIR}/")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Run it from the repo root**

Run: `python scratch-regenerate-nfl-themes.py` (or `python3`)
Expected: prints 32 `updated themes/generated/nfl-*.json` lines, then `32 theme files updated in themes/generated/`.

- [ ] **Step 3: Validate every file**

Run:
```bash
for f in themes/generated/nfl-*.json; do
  echo "-- $f --"
  python .claude/skills/kkc-theme-generator/scripts/validate_theme.py "$f"
done
```
Expected: every file prints `PASS` — the validator only checks status colors and background contrast, neither of which this task touches, so this should pass exactly as it did before.

- [ ] **Step 4: Spot-check the result and clean up**

Run: `cat themes/generated/nfl-chiefs.json` — confirm it now has `"secondary": "#FFB612"` in both `light` and `dark`, `"boldMode": true` at the top level, and its `"primary"` is unchanged (`"#E31837"`, its existing value) and its `id`/`name`/`category`/`header.badgeText` are all unchanged from before this task.

```bash
rm scratch-regenerate-nfl-themes.py
```

- [ ] **Step 5: Commit**

```bash
git add themes/generated/
git commit -m "feat: add secondary colors and boldMode to all 32 NFL team themes"
```

---

## Task 9: Full regression check and device verification

**Files:** none modified — verification only.

- [ ] **Step 1: Run the full unit test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 2: Assemble the debug APK**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual device check — confirm with the user before touching any tablet**

Ask the user whether it's OK to install this build on a connected device and, if a "go ahead" is given for the shared theme folder, whether to push the updated files there too — do not assume prior approval carries over to a new work session. Once approved:

1. Build and install a signed release (or debug) build on a connected tablet.
2. Select an NFL team theme via the Football Team picker (built in the prior plan).
3. Confirm: the header badge now sits on a gradient chip; the bottom navbar's frosted tint leans toward the team's colors and the selected tab shows a gradient pill instead of the plain gray one; the Dashboard's "Overall Progress" card and section headers show the gradient wash; the four status-colored stat tiles are UNCHANGED (still their status colors, not gradient — this is the intended, documented exclusion, not a bug); opening the Calculator and Timeclock shows the frosted panels tinted toward the team colors, while the Clock In/Out button's red/green stays exactly as before.
4. Switch back to a non-bold shop theme (e.g. "KKC Default") and confirm every one of the above surfaces looks pixel-identical to how it looked before this plan — this is the actual regression check, since `boldMode` defaults to `false` for every existing theme.

No commit for this task — verification only.
