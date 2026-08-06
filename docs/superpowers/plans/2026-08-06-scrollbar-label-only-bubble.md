# Scrollbar Label-Only Bubble Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Contacts-style label-only bubble as an alternative to the PDF scrollbar's thumbnail carousel — user-selectable via a new Appearance setting, and always forced on in split view.

**Architecture:** A new boolean `CompositionLocal` (`LocalScrollPreviewLabelOnly`), wired the same way the existing `LocalLowEndMode` is (computed once in `MainActivity` from a `SharedPreferences`-backed store, provided globally), lets `PdfLabelScrollbar` read the setting directly with no parameter threading through the 6 screens that host it. Inside `PdfLabelScrollbar`, an `effectiveLabelOnly = scrollPreviewLabelOnly || isSplitPaneActive` flag branches the existing drag-preview `AnimatedVisibility` content between the current thumbnail carousel and a new single-pill `ScrollLabelBubble` composable, which skips PDF page decoding entirely. `isSplitPaneActive` reuses a param `UnifiedReferenceViewer` already has and its callers already populate — only one new hop (`UnifiedReferenceViewer` → `PdfLabelScrollbar`) is needed.

**Tech Stack:** Kotlin, Jetpack Compose, `haze` blur library, JUnit + Mockito-Kotlin for the two testable data-layer files.

**Spec:** [docs/superpowers/specs/2026-08-06-scrollbar-label-only-bubble-design.md](../specs/2026-08-06-scrollbar-label-only-bubble-design.md)

---

## Task 1: `UiPreferencesStore` — persist the setting

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/UiPreferencesStore.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/UiPreferencesStoreTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `UiPreferencesStoreTest.kt`, after the existing `granularFlags_persistIndependently` test:

```kotlin
    @Test
    fun scrollPreviewLabelOnly_defaultsToFalse() {
        val store = UiPreferencesStore(context)
        assertFalse(store.getScrollPreviewLabelOnly())
    }

    @Test
    fun scrollPreviewLabelOnly_persists() {
        val store = UiPreferencesStore(context)
        store.setScrollPreviewLabelOnly(true)
        assertTrue(store.getScrollPreviewLabelOnly())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.UiPreferencesStoreTest"`
Expected: FAIL — `getScrollPreviewLabelOnly` / `setScrollPreviewLabelOnly` unresolved reference.

- [ ] **Step 3: Implement**

Add to `UiPreferencesStore.kt`, right before the class's final closing `}`:

```kotlin

    /**
     * Independent style preference (not tied to Low-end device mode) for the PDF scrollbar's
     * drag preview: shows only the current entry's text label instead of decoded page
     * thumbnails. Always forced on in split view regardless of this value — see
     * PdfLabelScrollbar's isSplitPaneActive handling.
     */
    fun getScrollPreviewLabelOnly(): Boolean =
        prefs.getBoolean("scroll_preview_label_only", false)

    fun setScrollPreviewLabelOnly(enabled: Boolean) =
        prefs.edit().putBoolean("scroll_preview_label_only", enabled).apply()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.UiPreferencesStoreTest"`
Expected: PASS (6 tests: the 4 existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/UiPreferencesStore.kt app/src/test/java/com/kkc/sheettracker/data/UiPreferencesStoreTest.kt
git commit -m "feat: add scroll-preview-label-only preference to UiPreferencesStore"
```

---

## Task 2: `AppStateFeatureFlags` — reactive snapshot for the setting

This is the piece that makes the setting take effect without an app restart (same reason `LowEndMode`'s flags go through this class instead of being read once from `UiPreferencesStore`): it listens for `SharedPreferences` changes and republishes a `StateFlow` snapshot that `MainActivity` collects.

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/AppStateFeatureFlags.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/AppStateFeatureFlagsTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `AppStateFeatureFlagsTest.kt`, after `snapshot_defaultsCorrect`:

```kotlin

    @Test
    fun snapshot_includesScrollPreviewLabelOnly() {
        storage["scroll_preview_label_only"] = true
        val flags = AppStateFeatureFlags(prefs, false).snapshot()
        assertTrue(flags.scrollPreviewLabelOnly)
    }

    @Test
    fun snapshot_scrollPreviewLabelOnlyDefaultsFalse() {
        storage.clear()
        val flags = AppStateFeatureFlags(prefs, false).snapshot()
        assertFalse(flags.scrollPreviewLabelOnly)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.AppStateFeatureFlagsTest"`
Expected: FAIL — `scrollPreviewLabelOnly` unresolved reference on `AppStateFlagsSnapshot`.

- [ ] **Step 3: Add the field to `AppStateFlagsSnapshot`**

In `AppStateFeatureFlags.kt`, change:

```kotlin
data class AppStateFlagsSnapshot(
    val shadowEnabled: Boolean,
    val dashboardEnabled: Boolean,
    val jobsEnabled: Boolean,
    val detailEnabled: Boolean,
    val viewerStatusEnabled: Boolean,
    val navMultiStackEnabled: Boolean,
    val lowEndMode: Boolean,
    val animationsEnabled: Boolean,
    val shadowsEnabled: Boolean,
    val blurEnabled: Boolean,
    val lazyLoadingEnabled: Boolean
)
```

to:

```kotlin
data class AppStateFlagsSnapshot(
    val shadowEnabled: Boolean,
    val dashboardEnabled: Boolean,
    val jobsEnabled: Boolean,
    val detailEnabled: Boolean,
    val viewerStatusEnabled: Boolean,
    val navMultiStackEnabled: Boolean,
    val lowEndMode: Boolean,
    val animationsEnabled: Boolean,
    val shadowsEnabled: Boolean,
    val blurEnabled: Boolean,
    val lazyLoadingEnabled: Boolean,
    val scrollPreviewLabelOnly: Boolean
)
```

- [ ] **Step 4: Populate it in `snapshot()`**

Change:

```kotlin
            lazyLoadingEnabled = prefs.getBoolean(KEY_LOW_END_LAZY_LOADING_ENABLED, true)
        )
    }
```

to:

```kotlin
            lazyLoadingEnabled = prefs.getBoolean(KEY_LOW_END_LAZY_LOADING_ENABLED, true),
            scrollPreviewLabelOnly = prefs.getBoolean(KEY_SCROLL_PREVIEW_LABEL_ONLY, false)
        )
    }
```

- [ ] **Step 5: Add the key constant and watch it for changes**

Change:

```kotlin
        const val KEY_LOW_END_LAZY_LOADING_ENABLED = "low_end_lazy_loading_enabled"

        private val LOW_END_KEYS = setOf(
            KEY_LOW_END_MODE,
            KEY_LOW_END_ANIMATIONS_ENABLED,
            KEY_LOW_END_SHADOWS_ENABLED,
            KEY_LOW_END_BLUR_ENABLED,
            KEY_LOW_END_LAZY_LOADING_ENABLED
        )
    }
}
```

to:

```kotlin
        const val KEY_LOW_END_LAZY_LOADING_ENABLED = "low_end_lazy_loading_enabled"
        const val KEY_SCROLL_PREVIEW_LABEL_ONLY = "scroll_preview_label_only"

        private val LOW_END_KEYS = setOf(
            KEY_LOW_END_MODE,
            KEY_LOW_END_ANIMATIONS_ENABLED,
            KEY_LOW_END_SHADOWS_ENABLED,
            KEY_LOW_END_BLUR_ENABLED,
            KEY_LOW_END_LAZY_LOADING_ENABLED
        )

        private val WATCHED_KEYS = LOW_END_KEYS + KEY_SCROLL_PREVIEW_LABEL_ONLY
    }
}
```

`KEY_SCROLL_PREVIEW_LABEL_ONLY`'s string value (`"scroll_preview_label_only"`) must exactly match the key used in `UiPreferencesStore.getScrollPreviewLabelOnly()`/`setScrollPreviewLabelOnly()` from Task 1 — both read/write the same `"kkc_tracker"` `SharedPreferences` file, and this is how a write through one class is observed by the other.

- [ ] **Step 6: Update the listener to watch the new key**

Change:

```kotlin
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in LOW_END_KEYS) notifyChanged()
    }
```

to:

```kotlin
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in WATCHED_KEYS) notifyChanged()
    }
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.AppStateFeatureFlagsTest"`
Expected: PASS (4 tests: the 2 existing + 2 new).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/AppStateFeatureFlags.kt app/src/test/java/com/kkc/sheettracker/data/AppStateFeatureFlagsTest.kt
git commit -m "feat: add reactive scroll-preview-label-only flag to AppStateFeatureFlags"
```

---

## Task 3: `LocalScrollPreviewLabelOnly` CompositionLocal

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/ScrollPreviewModeCompositionLocal.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.kkc.sheettracker.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Independent style preference for PdfLabelScrollbar's drag preview — true shows only the
 * current entry's text label, false shows the thumbnail carousel. Mirrors LocalLowEndMode's
 * wiring (see LowEndModeCompositionLocal.kt): computed once in MainActivity from
 * UiPreferencesStore/AppStateFeatureFlags, provided globally so no call site between
 * MainActivity and PdfLabelScrollbar needs to thread it through as an explicit parameter.
 */
val LocalScrollPreviewLabelOnly = staticCompositionLocalOf { false }
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ScrollPreviewModeCompositionLocal.kt
git commit -m "feat: add LocalScrollPreviewLabelOnly CompositionLocal"
```

---

## Task 4: Wire it up in `MainActivity`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`

- [ ] **Step 1: Import the new CompositionLocal**

Change (around line 57):

```kotlin
import com.kkc.sheettracker.ui.components.LowEndModeFlags
import com.kkc.sheettracker.ui.components.LocalLowEndMode
```

to:

```kotlin
import com.kkc.sheettracker.ui.components.LowEndModeFlags
import com.kkc.sheettracker.ui.components.LocalLowEndMode
import com.kkc.sheettracker.ui.components.LocalScrollPreviewLabelOnly
```

- [ ] **Step 2: Derive the value from the reactive snapshot**

Change:

```kotlin
            val lowEndFlags = remember(flagsSnapshot) {
                LowEndModeFlags(
                    masterEnabled = flagsSnapshot.lowEndMode,
                    animationsEnabled = !flagsSnapshot.lowEndMode || flagsSnapshot.animationsEnabled,
                    shadowsEnabled = !flagsSnapshot.lowEndMode || flagsSnapshot.shadowsEnabled,
                    blurEnabled = !flagsSnapshot.lowEndMode || flagsSnapshot.blurEnabled,
                    lazyLoadingEnabled = !flagsSnapshot.lowEndMode || flagsSnapshot.lazyLoadingEnabled,
                )
            }
```

to:

```kotlin
            val lowEndFlags = remember(flagsSnapshot) {
                LowEndModeFlags(
                    masterEnabled = flagsSnapshot.lowEndMode,
                    animationsEnabled = !flagsSnapshot.lowEndMode || flagsSnapshot.animationsEnabled,
                    shadowsEnabled = !flagsSnapshot.lowEndMode || flagsSnapshot.shadowsEnabled,
                    blurEnabled = !flagsSnapshot.lowEndMode || flagsSnapshot.blurEnabled,
                    lazyLoadingEnabled = !flagsSnapshot.lowEndMode || flagsSnapshot.lazyLoadingEnabled,
                )
            }
            val scrollPreviewLabelOnly = remember(flagsSnapshot) { flagsSnapshot.scrollPreviewLabelOnly }
```

- [ ] **Step 3: Provide it alongside `LocalLowEndMode`**

Change:

```kotlin
                    androidx.compose.runtime.CompositionLocalProvider(LocalLowEndMode provides lowEndFlags) {
```

to:

```kotlin
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalLowEndMode provides lowEndFlags,
                        LocalScrollPreviewLabelOnly provides scrollPreviewLabelOnly
                    ) {
```

- [ ] **Step 4: Verify it compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/MainActivity.kt
git commit -m "feat: provide LocalScrollPreviewLabelOnly from MainActivity"
```

---

## Task 5: Settings UI toggle

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add the switch to the Appearance card**

Change:

```kotlin
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Continuous Scroll", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Scroll reference PDFs page-to-page instead of tapping through them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = continuousScrollDefault,
                        onCheckedChange = onContinuousScrollDefaultChanged
                    )
                }

                HorizontalDivider()
```

to:

```kotlin
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Continuous Scroll", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Scroll reference PDFs page-to-page instead of tapping through them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = continuousScrollDefault,
                        onCheckedChange = onContinuousScrollDefaultChanged
                    )
                }

                var scrollPreviewLabelOnly by remember { mutableStateOf(uiPreferencesStore.getScrollPreviewLabelOnly()) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Label-only scroll preview", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Show just the sheet label while dragging the scrollbar, instead of page thumbnails.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = scrollPreviewLabelOnly,
                        onCheckedChange = {
                            scrollPreviewLabelOnly = it
                            uiPreferencesStore.setScrollPreviewLabelOnly(it)
                        }
                    )
                }

                HorizontalDivider()
```

(This is a `Switch` inside `SettingsCard(title = "Appearance")`, the same card as "Follow System Theme" and "Continuous Scroll" — not the "Performance" card lower in the file, which is for the unrelated Low-end device mode settings.)

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt
git commit -m "feat: add Label-only scroll preview switch to Appearance settings"
```

---

## Task 6: `PdfLabelScrollbar` — the bubble itself

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt`

All symbols used below (`Modifier`, `Box`, `Column`, `RoundedCornerShape`, `MaterialTheme`, `Text`, `Alignment`, `Dp`, `dp`, `LocalDensity`, `HazeState`, `HazeDefaults`, `hazeEffect`, `shadow`, `clip`, `offset`, `width`, `widthIn`, `padding`, `background`, `TextAlign`, `TextOverflow`, `LocalLowEndMode`, `LocalKKCThemeTokens`) are already imported in this file — no new imports needed except none.

- [ ] **Step 1: Add the `isSplitPaneActive` parameter**

Change:

```kotlin
internal fun PdfLabelScrollbar(
    modifier: Modifier = Modifier,
    rows: List<NavigatorRowModel>,
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    pdfFileForFilename: (String) -> java.io.File? = { null },
    defaultPdfFilename: String = "",
    hazeState: HazeState? = null
) {
```

to:

```kotlin
internal fun PdfLabelScrollbar(
    modifier: Modifier = Modifier,
    rows: List<NavigatorRowModel>,
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    pdfFileForFilename: (String) -> java.io.File? = { null },
    defaultPdfFilename: String = "",
    hazeState: HazeState? = null,
    isSplitPaneActive: Boolean = false
) {
```

- [ ] **Step 2: Read the setting and compute the effective mode**

Change:

```kotlin
    val density = LocalDensity.current
    val lowEnd = LocalLowEndMode.current
    val listState = rememberLazyListState()
```

to:

```kotlin
    val density = LocalDensity.current
    val lowEnd = LocalLowEndMode.current
    val scrollPreviewLabelOnly = LocalScrollPreviewLabelOnly.current
    val effectiveLabelOnly = scrollPreviewLabelOnly || isSplitPaneActive
    val listState = rememberLazyListState()
```

- [ ] **Step 3: Hoist `carouselEndPadding` so both the bubble and the carousel branch can use it**

Change:

```kotlin
    val carouselWidth = PDF_LABEL_SCROLLBAR_PANEL_WIDTH
    val rowSpacing = 5.dp
    val carouselPadding = 16.dp
    // So the track never renders into AppScaffold's floating bottom nav bar.
    val bottomClearance = 150.dp
```

to:

```kotlin
    val carouselWidth = PDF_LABEL_SCROLLBAR_PANEL_WIDTH
    val rowSpacing = 5.dp
    val carouselPadding = 16.dp
    // Asymmetric — small gap on the track side (end) so cards/the bubble sit close to the pill,
    // generous margin on the far side (start) so they don't crowd the PDF content.
    val carouselEndPadding = 50.dp
    // So the track never renders into AppScaffold's floating bottom nav bar.
    val bottomClearance = 150.dp
```

Then remove its now-duplicate declaration further down. Change:

```kotlin
            val chipShadowElevation = if (lowEnd.shadowsDisabled) 0.dp else 2.dp
            // Asymmetric — small gap on the track side (end) so cards sit close to the pill,
            // generous margin on the far side (start) so they don't crowd the PDF content.
            val carouselEndPadding = 50.dp
            val maxThumbWidthPx = with(density) { (carouselWidth - carouselPadding - carouselEndPadding).toPx() }
```

to:

```kotlin
            val chipShadowElevation = if (lowEnd.shadowsDisabled) 0.dp else 2.dp
            val maxThumbWidthPx = with(density) { (carouselWidth - carouselPadding - carouselEndPadding).toPx() }
```

- [ ] **Step 4: Skip thumbnail decoding entirely when the bubble is active**

Change:

```kotlin
    LaunchedEffect(carouselSlots, defaultPdfFilename, isDragging) {
        if (!isDragging) return@LaunchedEffect
        for ((_, entry) in carouselSlots) {
```

to:

```kotlin
    LaunchedEffect(carouselSlots, defaultPdfFilename, isDragging, effectiveLabelOnly) {
        if (!isDragging || effectiveLabelOnly) return@LaunchedEffect
        for ((_, entry) in carouselSlots) {
```

- [ ] **Step 5: Branch the drag-preview content between the bubble and the existing carousel**

Change:

```kotlin
        AnimatedVisibility(
            visible = isDragging,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = slideInHorizontally(
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) { fullWidth -> fullWidth } + fadeIn(tween(200)),
            exit = slideOutHorizontally(
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) { fullWidth -> fullWidth } + fadeOut(tween(200))
        ) {
            // Fixed size tiers, not a continuous falloff — the carousel only ever shows exactly
```

to:

```kotlin
        AnimatedVisibility(
            visible = isDragging,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = slideInHorizontally(
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) { fullWidth -> fullWidth } + fadeIn(tween(200)),
            exit = slideOutHorizontally(
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) { fullWidth -> fullWidth } + fadeOut(tween(200))
        ) {
          if (effectiveLabelOnly) {
            ScrollLabelBubble(
                entry = entries[focusIndex],
                touchYPx = touchYPx,
                trackHeightPx = trackHeightPx,
                carouselWidth = carouselWidth,
                carouselPadding = carouselPadding,
                carouselEndPadding = carouselEndPadding,
                hazeState = hazeState
            )
          } else {
            // Fixed size tiers, not a continuous falloff — the carousel only ever shows exactly
```

Then close the new `else` branch right before the `AnimatedVisibility` content lambda's own closing brace. Change (this is the literal tail of the file):

```kotlin
                        }
                    }
                    }
                }
            }
        }
    }
}
```

to:

```kotlin
                        }
                    }
                    }
                }
            }
          }
        }
    }
}
```

(The rest of the carousel's existing code between these two edits — `fun thumbHeightForDistance`, `fun footprintForDistance`, `fittedSlots`, the chip-styling `val`s, and the `Column { fittedSlots.forEach { ... } }` — is unchanged, just now nested one level deeper inside the `else` branch. Re-indenting it is optional and purely cosmetic.)

- [ ] **Step 6: Add the `ScrollLabelBubble` composable**

Append at the end of the file, after `PdfLabelScrollbar`'s closing `}`:

```kotlin

/**
 * Single floating label pill shown while dragging when the label-only scroll preview mode is
 * active (user setting, or forced by [isSplitPaneActive] on PdfLabelScrollbar) — the Contacts-
 * style alternative to the thumbnail carousel above. Shows only [entry]'s own label/range text,
 * no thumbnail decode, no neighbor slots.
 */
@Composable
private fun ScrollLabelBubble(
    entry: ScrollbarEntry,
    touchYPx: Float,
    trackHeightPx: Float,
    carouselWidth: Dp,
    carouselPadding: Dp,
    carouselEndPadding: Dp,
    hazeState: HazeState?
) {
    val density = LocalDensity.current
    val lowEnd = LocalLowEndMode.current
    val frostedTokens = LocalKKCThemeTokens.current.frosted
    val frostedAlpha = frostedTokens.backgroundAlpha.coerceIn(0.5f, 0.95f)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val hazeAvailable = hazeState != null && !lowEnd.blurDisabled
    val pillShape = RoundedCornerShape(10.dp)
    val pillElevation = if (lowEnd.shadowsDisabled) 0.dp else 2.dp
    val maxContentWidth = carouselWidth - carouselPadding - carouselEndPadding

    // Fixed height estimate (two text lines + padding), not a real measurement — matches this
    // file's footprintForDistance convention above: a known constant avoids any real-vs-estimate
    // drift in the position math, at the cost of the bubble not perfectly hugging taller/shorter
    // text (acceptable; label text is capped at one line and rarely needs more than this).
    val bubbleHeight = 64.dp
    val bubbleHeightPx = with(density) { bubbleHeight.toPx() }
    val bubbleTopPx = (touchYPx - bubbleHeightPx / 2f)
        .coerceIn(0f, (trackHeightPx - bubbleHeightPx).coerceAtLeast(0f))

    Box(
        modifier = Modifier
            .offset(y = with(density) { bubbleTopPx.toDp() })
            .width(carouselWidth),
        contentAlignment = Alignment.TopEnd
    ) {
        Box(
            modifier = Modifier
                .padding(end = carouselEndPadding, start = carouselPadding)
                .widthIn(max = maxContentWidth)
                .shadow(pillElevation, pillShape, clip = false)
                .clip(pillShape)
                .then(
                    if (hazeAvailable) {
                        Modifier.hazeEffect(
                            hazeState!!,
                            style = HazeDefaults.style(
                                backgroundColor = surfaceColor.copy(alpha = frostedAlpha),
                                blurRadius = frostedTokens.blurDp.coerceAtLeast(1f).dp
                            )
                        )
                    } else {
                        // Fully opaque, not a semi-transparent copy — combining shadow() with a
                        // semi-transparent fill is exactly the shadow-bleed bug documented in
                        // CLAUDE.md's "Frosted Glass Buttons" section; this fallback (no haze
                        // available) sidesteps it entirely rather than risking it.
                        Modifier.background(surfaceColor)
                    }
                )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entry.rangeLabel ?: "Sheet ${entry.page}",
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
```

- [ ] **Step 7: Verify it compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (If the brace-nesting in Step 5 is off by one, this is where it surfaces — read the compiler error's line number and check the `if`/`else`/closing-brace placement first.)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbar.kt
git commit -m "feat: add ScrollLabelBubble as label-only scroll preview mode"
```

---

## Task 7: Thread `isSplitPaneActive` the last hop into `PdfLabelScrollbar`

`UnifiedReferenceViewer` already receives `isSplitPaneActive` as a parameter (used today to pick the continuous-scroll pane's orientation) and its callers already populate it — `AssemblyViewerScreen.kt` passes `isSplitPaneActive = (fullscreenPane == FullscreenPane.NONE)` into `PdfPaneWithFloatingControls` at both its split-pane call sites (lines 725 and 831), which forwards it into `UnifiedReferenceViewer`. Specialty jobs' "Split View" button routes to this same `AssemblyViewerScreen` (see `specialtySplitViewRoute` in `NavGraph.kt`), so both are covered. No changes are needed upstream of `UnifiedReferenceViewer` — only its own call to `PdfLabelScrollbar` needs the value passed one hop further.

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt`

- [ ] **Step 1: Pass it through**

Change:

```kotlin
            PdfLabelScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd),
                rows = rowModels,
                currentPage = clampedDisplayPage,
                onPageSelected = onDisplayPageChange,
                pdfFileForFilename = pdfFileForFilename,
                defaultPdfFilename = defaultPdfFilename,
                hazeState = hazeState
            )
```

to:

```kotlin
            PdfLabelScrollbar(
                modifier = Modifier.align(Alignment.CenterEnd),
                rows = rowModels,
                currentPage = clampedDisplayPage,
                onPageSelected = onDisplayPageChange,
                pdfFileForFilename = pdfFileForFilename,
                defaultPdfFilename = defaultPdfFilename,
                hazeState = hazeState,
                isSplitPaneActive = isSplitPaneActive
            )
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt
git commit -m "feat: force label-only scroll preview in split view"
```

---

## Task 8: Manual on-device verification

No unit tests exist for `PdfLabelScrollbar` today (it's Compose UI with real drag-gesture and haze-blur behavior — see spec's Testing section for why this is manual-only). Build a debug APK and walk the spec's checklist on a connected tablet.

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit test suite once to confirm nothing else broke**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (including the 4 new ones from Tasks 1–2).

- [ ] **Step 2: Build and install the debug APK**

Run:
```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
Expected: install succeeds on the connected tablet.

- [ ] **Step 3: Walk the manual checklist from the spec**

On-device, in order:

1. Settings → Appearance → toggle "Label-only scroll preview" on.
2. Open a job with a long cab-number list; drag the scrollbar — primary line should ellipsize at the cap width, not overflow or wrap.
3. Drag across a plan-view page — plan label and "Sheet N" subtitle should show correctly.
4. Open a large job (bucketed display mode); drag — subtitle should show the page range, not a single page number.
5. Toggle the setting off in Settings, navigate back to the viewer, drag again — should revert to the thumbnail carousel.
6. Check the bubble's frosted background for any dark shadow ring bleeding through (the bug this design's `shadow()`+`clip()`+`hazeEffect()` ordering is meant to avoid).
7. With the setting OFF, open a job's Split View (Assembly or Specialty job detail → "Split View") — the bubble should show in both panes regardless, not the carousel.
8. Return from split view to single-pane — the carousel should come back (setting is still OFF).

- [ ] **Step 4: Fix anything that fails, re-verify, then this plan is complete.**

No commit for this task — it's verification, not a code change (unless Step 4 requires a fix, in which case commit that fix normally).
