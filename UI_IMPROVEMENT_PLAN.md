# KKCSheetTracker UI Improvement Plan

## What This Document Is

This is a **handoff plan** for improving the KKCSheetTracker UI across the entire application. It is written so that any developer or AI agent can pick it up, understand the reasoning behind each change, and execute it without needing to ask "why?"

The plan is organized into phases. Each phase builds on the previous one. Start from the top; don't skip ahead.

---

## The Problem We're Solving

KKCSheetTracker is a tablet app used by woodworkers on the shop floor to track cutting and assembly progress. It works, but:

1. **The Hardwoods workspace lags** when part lists exceed ~100 rows. Workers have to wait for the UI to catch up after every tap. This is the most urgent issue.

2. **Visual feedback is too subtle.** When a worker marks a part as done or skips it, the only visual change is a small text update ("2 of 5") or a button color shift. In a busy shop with sawdust and noise, you need to glance at your tablet and instantly know: "which parts am I done with?" That's not possible today.

3. **The app looks functional but not polished.** It was built feature-first (correctly), but now needs a visual pass to make it feel professional and intentional.

4. **No design system exists for future development.** Each screen was built somewhat independently. We need guidelines so new screens look and behave consistently.

---

## Aesthetic Direction

**Industrial/Utilitarian with Refined Polish**

Think: a well-designed workshop tool. Not flashy, not minimal — purposeful. Every pixel communicates something. The app should feel like a quality instrument: reliable, readable in harsh lighting, operable with dirty hands or gloves.

Key principles:
- **State at a glance**: Color-coded left borders, background tints, and icons make status scannable without reading text
- **Workshop-ready**: Large touch targets (28dp+ buttons), high contrast, readable under fluorescent lighting
- **Monospace for measurements**: Dimensions (widths, lengths) in monospace font so columns align naturally
- **Depth through purpose**: Elevation and shadow only where it communicates hierarchy, not for decoration

---

## PHASE 1: Performance Optimization (Hardwoods Workspace)

### Why This Is First

A beautiful UI that lags is worse than an ugly UI that's responsive. Workers tap a button and expect immediate feedback. If the UI freezes for 200ms+ after each tap, it breaks trust. Performance comes first.

### The Root Cause

The `HardwoodsWorkspaceScreen.kt` (1061 lines) renders parts in a `LazyColumn` — which is correct for virtualization. But Compose's recomposition system is being defeated by three patterns:

1. **Every row subscribes to the animation clock.** `animateColorAsState` (line 518) creates an animation subscription for ALL rows, even when only 0-1 rows are actually highlighted. With 100+ rows, every frame triggers 100+ unnecessary recompositions.

2. **Any single progress change rebuilds ALL rows.** When a worker taps "+1" on a single part, `progressVersion` increments. This causes `rowProgressMap` (line 175) to rebuild entirely (O(n) over all actions). Since this map is read at the parent scope, Compose marks ALL rows as potentially dirty and recomposites them all.

3. **Button callbacks are unstable.** Each row's onClick lambdas capture local variables (`done`, `qty`). Compose sees new lambda instances every recompose and can't skip the Button composable, even if nothing changed for that row.

### What To Do

#### 1.1 — Extract `HardwoodsPartRow` composable

**File**: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`

**What**: Pull lines 507-654 (the row rendering inside the `items` block) into a separate `@Composable private fun HardwoodsPartRow(...)` function.

**Why**: Compose's smart recomposition works at the function boundary. When a composable function's parameters haven't changed, Compose skips it entirely. Right now, everything is in one giant lambda — Compose can't tell which rows changed and which didn't, so it recomposes all of them.

**Parameters the extracted function should take** (all stable types):
```kotlin
row: HardwoodsRowUiModel        // The row data (stable data class)
progress: HardwoodRowProgress    // Done count, bad count, skipped boolean
skippedCabs: Set<String>         // Which cabinets are skipped (for multi-cab rows)
isHighlighted: Boolean           // Whether this row is the scroll-target
widthBand: Color                 // The width-based color band for this row
isDoorListDoc: Boolean           // Whether current doc type is "Door List"
onIncrement: () -> Unit          // Callback: add 1 to done count
onDecrement: () -> Unit          // Callback: subtract 1 from done count
onSkip: () -> Unit               // Callback: toggle skip state
onJump: () -> Unit               // Callback: navigate to reference doc
onCabSkip: () -> Unit            // Callback: open cabinet skip dialog
```

**Result**: When worker taps "+1" on row #47, only row #47 recomposes. The other 99+ rows are skipped because their parameters haven't changed.

#### 1.2 — Conditional animation (only on highlighted row)

**File**: Same file, inside the new `HardwoodsPartRow`

**What**: Replace the unconditional `animateColorAsState` with a conditional:
```kotlin
val rowColor = if (isHighlighted) {
    // This subscribes to animation clock — but only 1 row does this at a time
    val animated by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.52f),
        label = "highlight"
    )
    animated
} else {
    // Static color — no animation subscription, no per-frame recomposition
    widthBand.copy(alpha = 0.06f)
}
```

**Why**: `animateColorAsState` hooks into the animation frame loop. Every frame (16ms), it triggers recomposition for the composable it's in. With 100 rows all subscribing, that's 100 recompositions per frame — even while the user is just scrolling. By making it conditional, only the 0-1 highlighted rows pay this cost.

#### 1.3 — Stable callback references

**File**: Same file, at the `items()` call site in the parent

**What**: Wrap callbacks in `remember` keyed on the values they capture:
```kotlin
items(displayRows, key = { it.rowId }) { row ->
    val progress = rowProgressMap[Pair(selectedDoc.name, row.rowId)] ?: HardwoodRowProgress()
    val onIncrement = remember(row.rowId, progress.doneCount, row.qty) {
        { hardwoodsProgressStore.setDoneCount(jobFolderName, docTypeName, row.rowId, row.qty, progress.doneCount + 1) }
    }
    // ... same pattern for other callbacks
    HardwoodsPartRow(row = ..., onIncrement = onIncrement, ...)
}
```

**Why**: Without `remember`, every recompose creates a new lambda object. Compose compares the old and new lambda by reference — they're different objects, so it can't skip the Button. With `remember`, the lambda is only recreated when its captured values actually change. For 99 rows where nothing changed, the same lambda instance is reused, and Compose skips the button.

#### 1.4 — Granular progress observation

**File**: Same file, lines 175-239

**What**: Keep the `rowProgressMap` at the parent BUT ensure each row only recomposes when ITS specific progress changes:
```kotlin
items(displayRows, key = { it.rowId }) { row ->
    val progress = remember(progressVersion, row.rowId) {
        rowProgressMap[Pair(selectedDoc.name, row.rowId)] ?: HardwoodRowProgress()
    }
    // The `remember` block re-executes when progressVersion changes,
    // but returns the same value if THIS row's progress is unchanged.
    // Compose's structural equality check then skips the row.
}
```

Alternative (more work but better): Add a `getRowProgress(folder, doc, rowId)` method to `HardwoodsProgressStore` that returns a `State<HardwoodRowProgress>` per-row, so each row independently observes only its own state.

**Why**: The current pattern reads `rowProgressMap` (a new Map reference every time `progressVersion` changes) at the parent level. Even if only row #47's progress changed, the map reference itself is new, so Compose considers all rows dirty. By deriving per-row state inside each row's scope with structural equality, unchanged rows produce the same value and get skipped.

### How To Verify Phase 1 Worked

1. Open Android Studio Layout Inspector
2. Enable "Show Recomposition Counts" overlay
3. Navigate to a Hardwoods workspace with 100+ parts
4. Tap "+1" on a single row
5. **Before fix**: All visible rows show recomposition count incrementing
6. **After fix**: Only the tapped row (and possibly its neighbors) recomposes
7. Scroll through the full list — frame time should stay under 4ms (check with Profiler)

---

## PHASE 2: Visual Feedback Overhaul (Hardwoods Workspace)

### Why This Matters

The core question a worker asks when looking at their tablet: **"What's left to do?"**

Currently they have to read small text on each row: "2 of 5", "3 of 3", check if a button says "Skip" vs "Skipped" in slightly different yellow shades. In a glance, all rows look the same.

After Phase 2, a quick scroll should make status obvious: green-bordered rows are done, orange-bordered are skipped, blue-bordered are in progress, and plain rows haven't been touched. No reading required.

### 2.1 — Define the visual state system

**New file**: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsRowVisuals.kt`

**What**: Create an enum and style mapping:

```kotlin
enum class HardwoodsRowState {
    NOT_STARTED,    // 0 of N done, not skipped
    IN_PROGRESS,    // 1..N-1 done, not skipped
    COMPLETE,       // N of N done (all parts cut)
    SKIPPED,        // Entire row skipped
    PARTIAL_SKIP    // Multi-cabinet row with some cabs skipped but not all
}

data class RowVisualStyle(
    val leftBorderColor: Color,     // Bold colored stripe on the left edge
    val leftBorderWidth: Dp,        // 0dp = no border, 3dp = visible indicator
    val backgroundTint: Color,      // Subtle row background overlay
    val backgroundAlpha: Float,     // How strong the tint is (0.06-0.12)
    val statusIcon: ImageVector?,   // Optional icon in the leading column
    val progressFillColor: Color,   // Color of the progress pill fill
    val textDecoration: TextDecoration? // Strikethrough for completed items
)
```

**Why a separate file**: This is the "single source of truth" for visual states. When anyone asks "what does a completed row look like?" they read this file. It also makes the row composable simpler — it just asks for the style and applies it, rather than having conditional color logic scattered through the layout code.

### 2.2 — Add status colors to theme

**File**: `app/src/main/java/com/kkc/sheettracker/ui/theme/KKCColors.kt`

**What**: Add new colors specifically for row-level feedback:

```kotlin
// Row background tints (very subtle — 8-12% alpha)
val completeBgRow: Color       // Faint green wash over completed rows
val skipBgRow: Color           // Faint orange/amber wash over skipped rows

// Row left-border colors (bold — full opacity)
val inProgressBorder: Color    // Blue — work is happening
val completeBorder: Color      // Green — done
val skipBorder: Color          // Orange — intentionally skipped

// Light theme values:
// completeBgRow = Color(0x14388E3C)     // Green at 8% alpha
// skipBgRow = Color(0x1AE65100)         // Orange at 10% alpha
// inProgressBorder = Color(0xFF1565C0)  // Solid blue
// completeBorder = Color(0xFF388E3C)    // Solid green
// skipBorder = Color(0xFFE65100)        // Solid orange

// Dark theme equivalents (brighter to maintain contrast):
// completeBgRow = Color(0x1466BB6A)
// skipBgRow = Color(0x1AFFB74D)
// inProgressBorder = Color(0xFF64B5F6)
// completeBorder = Color(0xFF66BB6A)
// skipBorder = Color(0xFFFFB74D)
```

**Why separate from existing `KKCStatusColors`**: The existing status colors are for badges and chips (full-opacity backgrounds with contrasting text). Row backgrounds need much subtler tints (8-12% alpha) so they don't overwhelm the row content. And borders need full opacity but at narrow widths. These are distinct visual needs.

### 2.3 — Replace text progress with visual progress pill

**File**: Extracted `HardwoodsPartRow` composable

**What**: Replace `Text("$done of $qty")` with a compact visual indicator:

```
[====----] 3/5     (IN_PROGRESS: blue fill proportional to done/qty)
[========] ✓       (COMPLETE: green fill + checkmark replaces numbers)
[  SKIP  ]         (SKIPPED: orange filled pill, white text)
[--------] 0/5     (NOT_STARTED: gray empty pill)
```

Implementation: A 40x22dp `Box` with `RoundedCornerShape(11.dp)`:
- Background: gray (track)
- Foreground fill: `Modifier.fillMaxWidth(fraction = done.toFloat() / qty)` with status color
- Text overlay: "$done/$qty" in white/dark with `labelSmall` typography
- When COMPLETE: replace text with a 14dp checkmark icon

**Why not just keep text**: Text requires reading. A progress bar communicates proportion through spatial encoding — the brain processes it pre-attentively (before conscious reading). A half-filled blue bar says "halfway done" faster than "3 of 6" requires parsing.

### 2.4 — Add left border status indicator

**File**: Extracted `HardwoodsPartRow` composable

**What**: Replace the current 6dp-wide color-band Box (line 535-539) with a 3dp-wide status-colored border on the left edge of the row:

```
NOT_STARTED:  No border (or 1dp gray hairline)
IN_PROGRESS:  3dp blue left border
COMPLETE:     3dp green left border + faint green background
SKIPPED:      3dp orange left border + faint orange background
PARTIAL_SKIP: 3dp orange dashed/dotted left border
```

Keep the existing width-based color banding as a SECONDARY signal (it helps workers find rows of the same width). The status border is the PRIMARY signal.

Implementation: `Modifier.drawBehind` drawing a filled rect on the left edge, OR `Modifier.border(start = BorderStroke(...))` using a custom shape.

**Why left border specifically**: In a vertical list, the left edge is the most natural scan line. Workers scroll and their eye follows the left margin. A colored stripe there creates a visual "status column" that's readable during fast scrolling. This pattern is proven in tools like Jira, Linear, and GitHub.

### 2.5 — Row background tint for terminal states

**File**: Extracted `HardwoodsPartRow` composable

**What**: Apply a subtle background color to rows in COMPLETE or SKIPPED state:
- COMPLETE: `completeBgRow` (green at 8% alpha) — gives a "finished, move on" feel
- SKIPPED: `skipBgRow` (orange at 10% alpha) — visually distinct, clearly intentional

NOT_STARTED and IN_PROGRESS rows keep their current width-band color at 6% alpha (unchanged).

**Why**: Background tint creates visual grouping. When scrolling a long list, completed rows form green "bands" and skipped rows form orange "bands." Your eye naturally focuses on the rows WITHOUT tinting — those are the ones that need attention.

### 2.6 — Skip button redesign

**File**: Extracted `HardwoodsPartRow` composable

**What**: Make the skip button's state dramatically more obvious:

**Not skipped** (actionable):
- Outlined button style: transparent background, 1dp orange border
- Text: "Skip" in orange
- Size: current

**Skipped** (state indicator):
- Filled button: solid orange background
- Text: "SKIPPED" in white, SemiBold weight
- Slightly wider to accommodate text

**Multi-cabinet partial skip**:
- Filled button with orange background
- Text: "2/4" (skipped count / total cabs)
- Small skip-forward icon prefix

**Why the dramatic difference**: The current design uses two nearly-identical yellows (0xFFF3D98C vs 0xFFF8E8A6). In workshop lighting with screen glare, these are indistinguishable. Outlined vs. filled is a structural difference that's visible regardless of lighting conditions.

### 2.7 — Section header progress bar

**File**: `HardwoodsWorkspaceScreen.kt` (sticky header section, lines 486-505)

**What**: Add a `LinearProgressIndicator` and summary count to each material section header:

```
┌─────────────────────────────────────────────────┐
│ Oak 3/4"  •  24 parts    [████████░░░░] 18/24   │
└─────────────────────────────────────────────────┘
```

- Progress bar: 4dp height, rounded ends
- Fill color: `completeBorder` green
- Track color: `outlineVariant` at 30% alpha
- Count text: "$done/$total" right-aligned, `labelMedium`

**Why**: Workers often care about section-level progress ("am I done with all the Oak cuts?"). Currently they have to mentally sum up individual rows. A section-level bar gives instant aggregate awareness.

### 2.8 — Completion flash animation

**File**: Extracted `HardwoodsPartRow` composable

**What**: When `progress.doneCount` reaches `row.qty` (row transitions to COMPLETE), briefly flash the row background to a brighter green (300ms total: 100ms fade in, 200ms fade out).

Implementation: `LaunchedEffect(progress.doneCount == row.qty)` triggering an `Animatable<Color>` from `completeBorder.copy(alpha = 0.3f)` to `completeBgRow`.

**Why**: Tactile feedback. When a worker taps "+1" and hits the last piece, the flash confirms: "You're done with this row." Without it, the transition from "4/5" to "complete" is just a color shift that might not register consciously.

### How To Verify Phase 2 Worked

1. Load a workspace with mixed-state rows (some complete, some skipped, some in progress)
2. **Glance test**: Can you identify all completed rows in under 2 seconds without reading text? (Should see green borders + green tint)
3. **Skip test**: Are skipped rows obviously different from in-progress rows? (Orange vs blue, filled vs outlined button)
4. **Section test**: Can you tell which material section is closest to done without counting rows?
5. **Flash test**: Tap "+1" on a row that's at "4 of 5" — does the completion flash provide satisfying feedback?
6. **Color-blind test**: Turn on Android's color-blind simulation (Settings > Accessibility). States should still be distinguishable by border width, icon presence, and button fill vs outline — not color alone.

---

## PHASE 3: Theme and Polish (Hardwoods)

### Why This Phase Exists

Phases 1-2 fix performance and communication. Phase 3 makes it feel like a quality tool rather than a prototype. It's the difference between a tape measure from Harbor Freight and one from Starrett — both measure, but one feels right.

### 3.1 — Monospace font for dimensions

**File**: `app/src/main/java/com/kkc/sheettracker/ui/theme/Type.kt`

**What**: Add a monospace font family (JetBrains Mono or Roboto Mono from Google Fonts) and define a `DimensionTextStyle`:

```kotlin
val MonoFontFamily = FontFamily(
    Font(googleFont = GoogleFont("JetBrains Mono"), fontProvider = provider, weight = FontWeight.Medium)
)

val DimensionTextStyle = TextStyle(
    fontFamily = MonoFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.sp
)
```

Apply to all width/length displays in `HardwoodsPartRow`.

**Why**: Dimensions like "3 1/2" and "11 3/4" are critical data. In a proportional font, "1" is narrower than "3", so columns of numbers don't align. With monospace, "3 1/2" and "11 3/4" stack neatly, making it easy to scan a column of widths and spot the one you're looking for. This is a subtle but significant readability improvement for a data-heavy screen.

### 3.2 — Touch target sizing for shop use

**File**: `HardwoodsWorkspaceScreen.kt` (extracted row composable)

**What**:
- Increase +/- button height from 22dp to 32dp (fingers with calluses are less precise)
- Add `Modifier.heightIn(min = 48dp)` to each row (Android accessibility minimum)
- Reduce row vertical spacing from 5dp to 3dp (compensates for taller rows — net density stays similar)
- Increase horizontal padding from 8dp to 12dp (breathing room)

**Why**: This app is used on a tablet in a woodworking shop. Hands may be dusty, wearing thin gloves, or fatigued. Bigger targets reduce mis-taps. The 48dp minimum is also an Android accessibility guideline — passing this makes the app usable by workers with motor difficulties.

### 3.3 — Haptic feedback

**File**: Extracted row composable

**What**: Add haptic feedback on button interactions:
```kotlin
val haptic = LocalHapticFeedback.current
Button(onClick = {
    haptic.performHapticFeedback(HapticFeedbackType.LightClick)
    onIncrement()
}) { ... }
```

Apply to: +/- buttons, skip button, jump button.

**Why**: In a noisy shop, visual feedback alone might not be noticed if you're looking at the wood piece, not the screen. A tiny vibration confirms "your tap registered" without requiring visual attention. This is a significant UX improvement for eyes-busy workflows.

### 3.4 — Row dividers and surface treatment

**File**: Extracted row composable

**What**:
- Add a 1px bottom divider between rows: `Modifier.drawBehind { drawLine(outlineVariant.copy(alpha = 0.3f), ...) }`
- Use `Surface(tonalElevation = 0.5.dp)` as the row container instead of raw `Modifier.background`
- Section headers: add a left accent line (3dp × full height) in `primary` color

**Why**: Dividers create visual rhythm. Without them, dense rows blur together. The surface composable integrates properly with Material3's tonal elevation system (row backgrounds automatically adjust in dark mode). Section header accents create visual hierarchy.

### How To Verify Phase 3

1. Compare width/length columns before and after monospace — numbers should align vertically
2. Tap buttons 20 times rapidly — feel haptic on each tap, no missed inputs
3. Use the app at arm's length — can you still identify buttons and tap them accurately?
4. Check dark mode — does the surface treatment adapt automatically?

---

## PHASE 4: CNC Mode Screens

### Context

CNC mode is the "other half" of the app — it tracks cabinet sheet materials (plywood, melamine) through a PDF-viewing workflow. It shares the same theme and components but has different screens. The improvements here are lighter because CNC mode has fewer performance issues (it's page-based, not row-based) but needs the same visual polish.

### 4.1 — Dashboard Screen Enhancement

**File**: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardScreen.kt` (725 lines)

**What**:
- Apply the new status border pattern to recent material cards (left border colored by completion state)
- Replace text-only progress on recent cards with the mini progress pill from Phase 2.3
- Add monospace font to any numeric displays (page counts, dimensions)
- Ensure the circular progress indicator uses the refined status colors from Phase 2.2
- Quality alert card: add a warning icon and use `errorContainer` background for stronger visibility

**Why**: The dashboard is the first screen workers see. It sets expectations for the rest of the app. Applying the same visual vocabulary (borders, pills, colors) creates consistency.

### 4.2 — Job Browser Enhancement

**File**: `app/src/main/java/com/kkc/sheettracker/ui/browser/JobBrowserScreen.kt` (215 lines)

**What**:
- Apply left-border status coloring to each `ProgressCard` (green for complete jobs, blue for in-progress)
- Improve the segmented progress bar with proper rounded corners and 2dp segment gaps
- Add job count badge to the search field ("Showing 12 of 45 jobs")
- Ensure expanded state shows `StatusSummaryRow` with the new color values

**Why**: The job browser is a navigation screen — workers need to quickly find the job they're working on. Status coloring helps them skip past completed jobs visually.

### 4.3 — Job Detail Enhancement

**File**: `app/src/main/java/com/kkc/sheettracker/ui/detail/JobDetailScreen.kt` (233 lines)

**What**:
- Apply left-border pattern to material cards (same as job browser)
- Enhance `PageStatusBar` with slightly larger boxes (10dp instead of 8dp) and rounded corners (2dp)
- Add material section progress percentage text
- Ensure reference doc buttons use consistent button styling

**Why**: Job detail is the drill-down from the browser. It should feel like a natural continuation of the same visual language.

### 4.4 — Sheet Viewer Polish

**File**: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt` (2386 lines)

**What**:
- Status badge at top: make it larger and more prominent (it's the main status indicator on this screen)
- Parts table: apply monospace font to dimension columns
- Action buttons (Mark Complete, Mark Bad, Mark Skipped): use the status colors from Phase 2.2 as button backgrounds (currently they may use generic Material3 colors)
- Loading state: improve the render indicator with a proper skeleton loading animation instead of just a spinner

**Why**: The sheet viewer is where workers spend 80% of their time. Small polish improvements here have outsized impact on daily experience.

### 4.5 — Settings Screen Polish

**File**: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt` (163 lines)

**What**:
- Add section headers with the same left-accent style from the workspace
- Group settings visually: Appearance | Data Source | Device | Debug
- Use consistent input field styling (outlined text fields with proper labels)
- Add visual confirmation when settings save (brief green flash or checkmark)

**Why**: Settings is low-traffic but should feel cohesive. It's also the first place users go when something seems wrong — it should be easy to scan.

### How To Verify Phase 4

1. Navigate the full flow: Dashboard -> Jobs -> Job Detail -> Sheet Viewer -> back
2. At each screen, verify: left-border colors match status, progress indicators use the same pill/bar style
3. Check dark mode throughout the flow
4. Verify no visual inconsistencies between CNC and Hardwoods dashboard styles

---

## PHASE 5: Hardwoods Secondary Screens

### Context

The Hardwoods mode has its own Dashboard, Jobs, Job Detail, and Search screens that mirror CNC mode but with Hardwoods-specific data. They need the same visual treatment.

### 5.1 — Hardwoods Dashboard

**File**: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsDashboardScreen.kt`

**What**: Apply same enhancements as CNC Dashboard (4.1) — status borders on cards, progress pills, refined colors.

### 5.2 — Hardwoods Job Browser

**File**: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobBrowserScreen.kt`

**What**: Same as CNC Job Browser (4.2) — left-border status on cards, improved segmented bars.

### 5.3 — Hardwoods Job Detail

**File**: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobDetailScreen.kt`

**What**: Same as CNC Job Detail (4.3) — left borders, section progress percentages.

### 5.4 — Hardwoods Search

**File**: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsSearchScreen.kt`

**What**: Apply status coloring to search results. A completed part should show as green-bordered in results so workers know it's already done.

### How To Verify Phase 5

Same as Phase 4 but navigating through Hardwoods mode. Cross-mode consistency: switch between CNC and Hardwoods — the visual language should be identical (colors, borders, pills) even though the data differs.

---

## PHASE 6: Shared Components Refinement

### Context

The `ui/components/` directory contains reusable building blocks. After Phases 2-5 introduce new patterns (progress pills, status borders), these should be formalized as shared components so future development uses them by default.

### 6.1 — New shared component: `ProgressPill`

**New file**: `app/src/main/java/com/kkc/sheettracker/ui/components/ProgressPill.kt`

A reusable compact progress indicator (the 40x22dp pill from Phase 2.3):
```kotlin
@Composable
fun ProgressPill(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
    state: ProgressState = ProgressState.from(done, total),
    showCheckOnComplete: Boolean = true
)
```

### 6.2 — New shared component: `StatusBorderedCard`

**New file**: `app/src/main/java/com/kkc/sheettracker/ui/components/StatusBorderedCard.kt`

A card with a configurable left status border:
```kotlin
@Composable
fun StatusBorderedCard(
    status: SheetStatus,  // or a more generic ProgressState
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
)
```

### 6.3 — Update `StatusComponents.kt`

**File**: `app/src/main/java/com/kkc/sheettracker/ui/components/StatusComponents.kt`

- Update `ProgressCard` to use the new status border pattern internally
- Update `StatusChip` colors to use the refined palette from Phase 2.2
- Add `SectionProgressHeader` composable for the section header + progress bar pattern

### How To Verify Phase 6

- Ensure all usages of progress display across the app use `ProgressPill` (no manual reimplementations)
- Ensure all card-with-status patterns use `StatusBorderedCard`
- Run a text search for raw status color hex values — they should only appear in `KKCColors.kt`, not in screen files

---

## Implementation Sequencing Summary

| Order | Phase | Focus | Estimated Time |
|-------|-------|-------|---------------|
| 1 | Phase 1 | Performance (Hardwoods lag fix) | 4-6 hours |
| 2 | Phase 2 | Visual feedback (Hardwoods rows) | 6-8 hours |
| 3 | Phase 3 | Theme & polish (Hardwoods) | 3-4 hours |
| 4 | Phase 6 | Extract shared components | 2-3 hours |
| 5 | Phase 4 | CNC screens | 4-5 hours |
| 6 | Phase 5 | Hardwoods secondary screens | 3-4 hours |

**Total: ~22-30 hours**

Note: Phase 6 (shared components) is done BEFORE Phases 4-5 so the CNC/secondary screen work can use the new components rather than reimplementing patterns.

---

## Risk Notes

1. **Phase 1 regression risk**: Extracting the row composable is a structural change. All interactive features (buttons, dialogs, scroll-to-row) must be tested. Pay special attention to the cabinet skip dialog flow and the "Jump" button navigation.

2. **Dark mode**: Every new color needs a dark variant. Test both themes after each phase.

3. **Memory with status icons**: Adding per-row icons increases composable tree depth. LazyColumn handles recycling, but monitor memory usage with 200+ row lists.

4. **Workshop testing**: Phase 3 touch targets and haptics MUST be tested on an actual tablet in shop conditions. Emulator testing is insufficient for these.
