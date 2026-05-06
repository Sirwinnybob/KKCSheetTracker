# KKCSheetTracker UI Design Guidelines

## What This Document Is

This is the **design system reference** for KKCSheetTracker. Every screen, component, and feature built for this app should follow these guidelines. The goal is visual and behavioral consistency — when a worker learns one screen, every other screen should feel familiar.

This is a living document. Update it when new patterns are established.

---

## Design Philosophy

### Who Uses This App

Woodworkers in a cabinet-making shop. They use tablets (Android, 10-12" screens) mounted on carts or held in one hand. Their other hand is often holding a part, a pencil, or a tool. Their environment is:

- **Noisy** (saws, routers, dust collection) — they can't hear audio feedback
- **Bright** (overhead fluorescents, sometimes sunlight from bay doors) — screens fight glare
- **Dusty** — screens get fingerprints and sawdust; contrast must overcome smudges
- **Hands are occupied** — taps must be accurate with minimal precision; no swiping complex gestures

### The Aesthetic: Industrial Utilitarian

Think of a precision measuring tool — a digital caliper, a laser level readout. Not flashy, not minimal for minimalism's sake. Every element communicates something. Nothing is decorative.

**The app should feel like:**
- A well-organized tool chest (everything in its place)
- A control panel (status visible at a glance)
- A quality instrument (precise, reliable, satisfying to use)

**The app should NOT feel like:**
- A social media app (no cards for cards' sake, no empty states with cute illustrations)
- A marketing website (no gradient hero sections, no floating elements)
- A consumer productivity app (no gamification, no streaks, no badges for completing work)

---

## Color System

### Status Colors (Primary Communication)

These five colors are the app's core visual vocabulary. Every progress state maps to exactly one color. Never use these colors for non-status purposes.

| Status | Light Mode | Dark Mode | Meaning |
|--------|-----------|-----------|---------|
| Complete | `#388E3C` | `#66BB6A` | Work is finished. Move on. |
| Bad/Error | `#C62828` | `#FF6B6B` | Something is wrong. Needs attention. |
| Skip | `#E65100` | `#FFB74D` | Intentionally bypassed. Not an error. |
| In Progress | `#1565C0` | `#64B5F6` | Work is happening. Partially done. |
| Not Started | `#78909C` | `#90A4AE` | Hasn't been touched yet. Neutral. |

### How Status Colors Are Applied

| Context | Application | Alpha/Intensity |
|---------|-------------|-----------------|
| Left border on rows/cards | Full opacity, 3dp width | 100% |
| Row/card background tint | Very subtle wash | 8-12% alpha |
| Badges/chips | Full opacity background | 100% bg, white text |
| Progress bar fill | Full opacity | 100% |
| Icons | Full opacity | 100% |
| Text color | Never used for body text | — |

**Rule**: Status colors appear at full intensity ONLY in small areas (borders, icons, chips). Large areas (backgrounds) use 8-12% alpha to avoid overwhelming the content.

### Theme Colors (Material3 Scheme)

| Role | Light | Dark | Usage |
|------|-------|------|-------|
| Primary | `#1E5FAF` | `#79B2FF` | Action buttons, active filters, links |
| Secondary | `#3C6EA8` | `#9BC3F3` | Secondary actions, less prominent UI |
| Tertiary | `#4F7D99` | `#9BC7D8` | Accents, tertiary information |
| Background | `#F4F8FD` | `#0E1621` | App background |
| Surface | `#FFFFFF` | `#111B27` | Card/row backgrounds |
| Error | `#C62828` | `#FF7A7A` | System errors (distinct from "bad parts" status) |

### Color Rules

1. **Never use raw hex values in screen code.** Always reference `MaterialTheme.colorScheme.*` or `KKCStatusColors.*`
2. **Never invent new colors.** If a new status or state needs a color, add it to `KKCColors.kt` with both light and dark variants.
3. **Maintain color-blind accessibility.** Every state must be distinguishable by BOTH color AND shape/icon/weight. Never rely on color alone.
4. **Test in both themes.** Every color usage must work in light AND dark mode.

---

## Typography

### Font Families

| Family | Usage | Why |
|--------|-------|-----|
| Inter (Google Fonts) | All UI text: labels, buttons, headings, body | Clean, highly readable, professional |
| Monospace (JetBrains Mono / Roboto Mono) | Dimensions, measurements, numeric data | Columns of numbers align vertically; feels workshop-appropriate |

### Type Scale

| Style | Font | Weight | Size | Usage |
|-------|------|--------|------|-------|
| Display | Inter | Normal | 34sp | Large stat numbers (dashboard circular progress) |
| Headline | Inter | SemiBold | 22sp | Screen titles |
| Title Large | Inter | SemiBold | 18sp | Section headers, card titles |
| Title Medium | Inter | Medium | 16sp | Sub-headings, dialog titles |
| Body Large | Inter | Normal | 16sp | Primary content text |
| Body Medium | Inter | Normal | 14sp | Secondary content text |
| Body Small | Inter | Normal | 12sp | Tertiary info, timestamps |
| Label Large | Inter | SemiBold | 14sp | Button text, chip text |
| Label Medium | Inter | Medium | 12sp | Small buttons, status counts |
| Label Small | Inter | Medium | 11sp | Captions, tiny annotations |
| Dimension | Mono | SemiBold | 13sp | Width, length, measurements |

### Typography Rules

1. **Dimensions are always monospace.** Any display of width, length, or numeric measurement uses the monospace font.
2. **Status text uses SemiBold.** "COMPLETE", "SKIPPED", counts like "3/5" — always SemiBold for scanability.
3. **Don't use more than 2 weights on a single row.** Typically: SemiBold for the key info, Normal for supporting text.
4. **Line height is tight.** We optimize for density. Use `lineHeight = fontSize * 1.2` as default.

---

## Spacing and Layout

### Spacing Scale

| Token | Value | Usage |
|-------|-------|-------|
| `xs` | 2dp | Divider padding, icon-to-text gap within a badge |
| `sm` | 4dp | Tight internal padding (inside chips/pills) |
| `md` | 8dp | Standard gap between sibling elements |
| `lg` | 12dp | Section internal padding, card content padding |
| `xl` | 16dp | Screen edge padding, section spacing |
| `xxl` | 24dp | Major section breaks |

### Layout Patterns

#### Row Items (Parts List, Job List)
```
┌─[3dp border]─────────────────────────────────────────┐
│ [status]  [dimensions]  [description]  [actions]      │
│  column    monospace      body text     buttons right  │
└───────────────────────────────────────────────────────┘
  3dp gap
┌─[3dp border]─────────────────────────────────────────┐
│ ...                                                   │
```
- Min height: 48dp (touch target)
- Internal padding: 12dp horizontal, 8dp vertical
- Between rows: 3dp spacing
- Left border: 3dp width, full row height
- Bottom divider: 1px, `outlineVariant` at 30% alpha

#### Cards (Dashboard, Browser)
```
┌─[3dp border]──────────────────────────────┐
│                                           │
│  Title              Progress Indicator    │
│  Subtitle/Detail                          │
│                                           │
│  [Optional expanded content]              │
│                                           │
└───────────────────────────────────────────┘
  8dp gap
```
- Elevation: 1dp (tonalElevation)
- Corner radius: 12dp (Material3 medium shape)
- Internal padding: 16dp
- Left border: 3dp status colored stripe

#### Section Headers
```
┌─[3dp primary accent]────────────────────────────────────┐
│  Section Title  •  Item Count    [====░░] done/total    │
└─────────────────────────────────────────────────────────┘
```
- Background: `surfaceVariant`
- Left accent: 3dp in `primary` color
- Internal padding: 12dp horizontal, 8dp vertical
- Sticky behavior in scrollable lists

### Touch Targets

| Element | Minimum Size | Recommended |
|---------|-------------|-------------|
| Buttons (action) | 48dp × 32dp | 48dp × 36dp |
| Increment/Decrement | 32dp × 32dp | 36dp × 36dp |
| List items (tappable) | 48dp height | 52dp height |
| Icons (standalone) | 44dp tap area | 48dp tap area |
| Filter chips | 32dp height | 36dp height |

**Rule**: If a worker with calloused fingers would struggle to tap it, it's too small.

---

## Visual Feedback Patterns

### State Communication Hierarchy

When communicating status, use these signals in order of strength:

1. **Left border color** (strongest — visible during scroll)
2. **Background tint** (strong — visible at rest)
3. **Status icon** (medium — confirms what color says)
4. **Progress pill** (medium — communicates proportion)
5. **Button state** (medium — filled vs outlined shows toggled state)
6. **Text** (weakest — requires conscious reading)

Never rely on text alone to communicate state. Always pair with at least one stronger signal.

### Progress Indicators

#### Progress Pill (compact, inline)
- Size: 40dp × 22dp
- Shape: fully rounded (11dp radius)
- Track: gray background
- Fill: proportional to completion, colored by state
- Text: "$done/$total" centered, white on fill or dark on track
- Complete state: replace text with checkmark icon

#### Linear Progress Bar (section-level)
- Height: 4dp
- Shape: fully rounded (2dp radius)
- Track: `outlineVariant` at 30% alpha
- Fill: `completeBorder` green

#### Circular Progress (dashboard)
- Size: 80dp
- Stroke: 8dp
- Track: `outlineVariant` at 20% alpha
- Fill: `primary` (non-status, represents overall)
- Center text: percentage, Display style

### Button States

#### Action Buttons (one-time actions: Mark Complete, Mark Bad)
- Style: Filled (`FilledTonalButton` or `Button`)
- Color: Status-appropriate (green for complete, red for bad, orange for skip)
- Text: Clear verb ("Mark Complete", not just "Complete")

#### Toggle Buttons (skip/unskip, check/uncheck)
- **OFF state**: Outlined style, status color border, transparent fill
- **ON state**: Filled style, status color background, white text
- The structural difference (outlined vs filled) must be visible regardless of color perception

#### Increment/Decrement Buttons
- Shape: small rounded square or circle
- "-" button: `error` color (red) — removing progress feels "destructive"
- "+" button: `completeBorder` color (green) — adding progress feels "constructive"
- Haptic: `LightClick` on every press

### Animations and Transitions

| Trigger | Animation | Duration | Purpose |
|---------|-----------|----------|---------|
| Row completes | Background flash to green | 300ms (100 in, 200 out) | Confirms completion registered |
| Row highlighted (scroll-to) | Background fade to tertiary | 400ms (in), holds until cleared | Shows navigation target |
| Section expand/collapse | Content size animation | 200ms | Smooth reveal |
| Skip state toggle | Button fill animation | 150ms | Confirms state change |
| Nav bar show/hide | Slide up/down | 200ms | Non-jarring appearance |

**Rules**:
1. Never animate more than 1-2 elements simultaneously per user action
2. Only the directly-affected element should animate (don't animate sibling rows)
3. Animation must serve communication, not decoration
4. If a device is in "reduce motion" accessibility mode, skip all non-essential animations

### Haptic Feedback

| Action | Haptic Type | Why |
|--------|-------------|-----|
| +/- button tap | LightClick | Confirms input without looking at screen |
| Skip button toggle | LightClick | Confirms state change |
| Row completion | HeavyClick | Milestone celebration (optional) |
| Error/invalid action | Reject pattern | Something went wrong |

---

## Component Patterns

### When to Use Each Component

| Need | Component | Notes |
|------|-----------|-------|
| Show progress of a single item | `ProgressPill` | Inline, compact |
| Show progress of a section/group | `LinearProgressIndicator` in section header | |
| Show overall job/app progress | Circular progress | Dashboard only |
| List items with status | `StatusBorderedCard` or row with left border | |
| Filter/select options | `FilterChip` | Material3 standard |
| Status summary (counts) | `StatusSummaryRow` | 4-chip row: T/D/B/S |
| Page-level status map | `PageStatusBar` | Grid of colored boxes |
| Status label | `StatusChip` / `SheetStatusBadge` | Pill-shaped, full color |
| Expandable detail | `ProgressCard` | Animated expand with divider |

### Composable Architecture Rules

1. **Extract rows into separate composable functions.** Any item rendered inside a `LazyColumn`/`LazyRow` `items` block must be a separate `@Composable fun` — never inline the full row logic.

2. **Pass only stable types to list item composables.** Parameters should be primitives, data classes (which are structurally stable), or `() -> Unit` callbacks. Never pass mutable state holders directly.

3. **Use `key = { item.id }` on all `items()` calls.** This is critical for LazyColumn performance and correct animation behavior.

4. **Wrap callbacks in `remember`.** Any lambda passed to a list item composable should be wrapped in `remember(dependencies)` at the call site to prevent unnecessary recompositions.

5. **Conditional animations.** If only 1 out of N items needs animation (e.g., a highlighted row), make the animation conditional inside the item composable so the other N-1 items don't subscribe to the animation clock.

---

## Screen Structure

### Standard Screen Template

```kotlin
@Composable
fun XxxScreen(
    // Navigation/state params
) {
    Scaffold(
        topBar = { /* TopAppBar with screen title, action buttons */ },
        // bottomBar handled by parent AppScaffold
    ) { padding ->
        // Content
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)  // or 8.dp for cards
        ) {
            // Section headers (sticky if applicable)
            stickyHeader { SectionHeader(...) }
            // Items
            items(data, key = { it.id }) { item ->
                ItemRow(item = item, ...)
            }
        }
    }
}
```

### Navigation Pattern

- **Bottom navigation**: 4 tabs (Dashboard, Jobs, Search, Settings)
- **Drill-down**: Jobs -> Job Detail -> Workspace/Viewer (push navigation)
- **Back behavior**: Standard Android back, clears to tab root
- **Mode switching**: CNC <-> Hardwoods switches entire navigation stack

---

## Accessibility Requirements

### Contrast

- Text on backgrounds: minimum 4.5:1 ratio (WCAG AA)
- UI elements (borders, icons): minimum 3:1 ratio
- Status colors on surface: verified for both light and dark themes

### Color Independence

Every status must be communicable WITHOUT color:
- COMPLETE: border + checkmark icon + "DONE" text available
- SKIPPED: border + skip icon + "SKIPPED" text + italic treatment
- IN PROGRESS: border + partial progress bar shape
- BAD: border + warning icon + count

### Content Descriptions

- All action buttons: content description explaining the action
- Status icons: content description stating the status
- Progress pills: content description like "3 of 5 complete"

### Motion

- Respect `Settings.Global.ANIMATOR_DURATION_SCALE` — if 0, skip animations
- All animations serve function, not decoration — they communicate state change

---

## Dark Mode

### Rules

1. **All custom colors need dark variants.** Never hardcode a color without providing both light and dark values in `KKCColors.kt`.
2. **Use `MaterialTheme.colorScheme` references** — they auto-switch with theme.
3. **Status colors are already defined for both themes** in `KKCStatusColors`. Use them.
4. **Background tints (8-12% alpha) work in both themes** because they overlay on the surface color.
5. **Test dark mode explicitly.** It's not optional — some workers prefer dark mode for reduced eye strain.

---

## Performance Rules

### For List Screens (LazyColumn)

1. **Never read shared state at the parent scope if it forces child recomposition.** Derive per-item state inside each item's scope.
2. **Use `key` parameter on ALL `items()` calls.**
3. **Extract item composables** — never inline complex layouts in `items {}`.
4. **Avoid `animateXAsState` on every list item.** Only the active/highlighted item should animate.
5. **Stabilize lambdas with `remember`** to prevent unnecessary child recompositions.
6. **Use `remember(version, itemId)` for expensive derivations** inside item composables.

### For PDF Rendering

1. **Cache rendered bitmaps** (existing pattern: 6-page cache)
2. **Prewarm adjacent pages** (existing pattern: ±2 pages)
3. **Render on IO dispatcher** — never block the main thread with PDF operations
4. **Debounce viewport renders** (existing pattern: 120ms)

### For State Updates

1. **Debounce rapid updates** (existing pattern: 120ms on progressVersion)
2. **Use `distinctUntilChanged()`** on StateFlow emissions
3. **Avoid O(n) recomputation** when only one item changed — design state access patterns that are O(1) per item

---

## File Organization

### Where Things Go

| What | Where |
|------|-------|
| Screen composable | `ui/{feature}/{Feature}Screen.kt` |
| Screen-specific subcomponents | Same file if small, or `ui/{feature}/{Component}.kt` |
| Shared/reusable components | `ui/components/{ComponentName}.kt` |
| Theme colors | `ui/theme/KKCColors.kt` |
| Typography | `ui/theme/Type.kt` |
| Theme configuration | `ui/theme/Theme.kt` |
| Visual state definitions | `ui/{feature}/{Feature}Visuals.kt` |
| Data models | `data/models/Models.kt` |
| State management | `data/{Feature}Store.kt` |

### Naming Conventions

- Screen files: `{Feature}{View}Screen.kt` (e.g., `HardwoodsWorkspaceScreen.kt`)
- Component files: `{ComponentName}.kt` (e.g., `ProgressPill.kt`)
- Visual state files: `{Feature}Visuals.kt` (e.g., `HardwoodsRowVisuals.kt`)
- Colors: descriptive of purpose, not appearance (`completeBorder`, not `greenLine`)
- Composables: PascalCase functions (`HardwoodsPartRow`, `ProgressPill`)

---

## Checklist for New Features

When building any new screen or component, verify:

- [ ] Uses status colors from `KKCStatusColors` (never raw hex in screen code)
- [ ] Typography uses defined styles from `Type.kt` (never inline `fontSize = 14.sp`)
- [ ] Touch targets are minimum 48dp
- [ ] Works in both light and dark mode
- [ ] List items are extracted composables with stable parameters
- [ ] Status is communicated through border + icon + text (not color alone)
- [ ] Dimensions/measurements use monospace font
- [ ] Haptic feedback on primary actions
- [ ] Animations are conditional (only active element animates)
- [ ] Content descriptions on interactive elements
- [ ] Tested on physical tablet at arm's length

---

## Revision History

| Date | Change | Reason |
|------|--------|--------|
| 2026-05-05 | Initial creation | Establishing design system for consistent future development |
