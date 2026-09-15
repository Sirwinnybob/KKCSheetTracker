# Theme Color Parity & Theme-Generator Skill

## Problem

The theming system (`ui/theme/KKCColors.kt`, `KKCThemeTokens.kt`, `KKCThemeRepository.kt`) is out of parity with the app it themes:

1. **Schema gap.** `KKCThemeRepository.parseThemeFile` only reads `complete`/`bad`/`skip`/`inProgress` (and their derived `Bg`/`Border` variants) out of a synced theme JSON's `status` object. `notStarted`, `remakeBg`, `miscBg`, `widthBandPalette`, and `progressGradientStart`/`progressGradientEnd` always fall back to the hardcoded built-in defaults — a custom theme can never actually change them.
2. **Bypass gap.** Multiple screens hardcode raw `Color(0x...)` literals for status-meaning concepts (job/part status, battery level, supply stock level) instead of reading `KKCThemeColors.statusColors`. These never respond to theme changes at all, parity fix or not.
3. **No systematic way to generate new themes.** The user wants to eventually add a theme per NFL and college football team (100+ themes). Hand-authoring each theme JSON is infeasible, and without a consistent rule, status colors (which carry real operational meaning on the shop floor — skipped, remake, bad parts, misc) could drift or collide between themes.

## Goals

- Every field in `KKCStatusColors` is overridable from a theme JSON, with backward-compatible fallback for existing theme files that only set the original 4 keys.
- Status-meaning colors that are currently hardcoded outside the theme system get migrated to read from `KKCThemeColors.statusColors` (extended with new fields where a hardcoded concept — e.g. battery level, supply stock level — isn't tokenized yet).
- A repeatable, validated method (a Claude Code skill) exists for generating a new theme from a pair of seed brand colors, so status colors stay recognizable (hue-locked) and legible in any theme without a human hand-picking every hex value.

## Non-goals (deferred as follow-up work)

- Actually generating the 100+ NFL/college team theme JSONs.
- Redesigning the theme picker UI. Current picker is a flat dropdown (`SettingsScreen.kt`); it won't scale to 150+ entries. Planned follow-up: add a "Football Team" entry to the existing dropdown that opens a search-with-autofill picker. Not designed here — noted as a todo so the generator skill's output (deterministic team-id slugs) is forward-compatible with it.

## Design

### 1. Schema parity fix

Extend `KKCThemeRepository.parseThemeFile` to read every `KKCStatusColors` field from the JSON `status` object, using the same fallback chain already established (dedicated key → base key where one exists → built-in default):

- `notStarted` — new key, falls back straight to built-in default (no base key to inherit from).
- `remakeBg` — new key, same fallback pattern.
- `miscBg` — new key, same fallback pattern.
- `widthBand` — new key, JSON array of hex strings; falls back to the built-in 5-color list if absent or shorter than expected.
- `progressGradientStart` / `progressGradientEnd` — new keys, fall back to built-in defaults.

No schema version bump. Theme JSONs already in the field that only set the original 4 keys continue to parse and render exactly as before — every new field is additive and optional.

### 2. Hardcoded-color audit & migration

Scope: any raw `Color(0x...)` literal that represents **status or state**, specifically:
- Job/part status already modeled in `KKCStatusColors` but used raw somewhere instead of via the token (audit for these first — they're the most likely to already have a home).
- Battery level color (`BatteryIndicator.kt`) — add new `KKCStatusColors` fields if no equivalent exists yet.
- Supply stock-level color (`SupplyItemDetailScreen.kt`, `SupplyDashboardScreen.kt`) — same treatment.

Explicitly excluded: `PdfMarkupUi.kt` ink/drawing colors (user-chosen tool colors, not theme identity) and any other clearly decorative/non-semantic hardcoded color.

Process per file/area:
1. Identify the raw color's semantic meaning.
2. If `KKCStatusColors` already has a field for it, replace the literal with `KKCThemeColors.statusColors.<field>`.
3. If not, add a new field to `KKCStatusColors` (light + dark defaults matching current hardcoded values, so behavior is unchanged until a theme overrides it), then extend the Section 1 JSON parsing to cover it, then replace the literal.

Suggested execution strategy: independent per feature area (hardwoods, supply, dashboard, timecard, standards) — a natural fit for parallel sub-agents, one per area, each verified by building and running that area's existing tests.

### 3. `kkc-theme-generator` skill

New project skill at `.claude/skills/kkc-theme-generator/SKILL.md` (mirrored to `.agents/skills/`, matching the existing `kkc-metadata-map` convention). Single-repo skill — no cross-repo sync needed, this is KKCSheetTracker-only.

**Trigger:** creating or editing a KKCSheetTracker theme JSON from brand/seed colors (e.g. "make a theme for the Kansas City Chiefs").

**Inputs:** theme id/name, 1-2 seed colors (e.g. a team's primary + secondary brand colors).

**Core rule — hue-locked status colors:** every theme keeps status colors within a fixed hue band regardless of the seed color, so meaning stays constant across themes:
- complete → green band
- bad (bad parts) → red band
- skip (skipped) → orange band
- remake → purple band
- misc → blue band
- notStarted → gray/desaturated band

Only saturation and lightness shift per theme, to match the seed color's mood (warmer/cooler, more/less saturated). This is a deliberate constraint: a shop worker who learns "orange = skipped" reads it correctly in every theme, no matter how different the theme looks otherwise.

**Core rule — seed color placement:** the seed color(s) drive `primary` (accent) and the decorative `widthBandPalette`/progress gradient. They do **not** drive `background`/`surface` directly. `KKCThemeTokens.toColorScheme` only overrides `primary`/`background`/`surface` on top of a fixed base `ColorScheme` — `onBackground`/`onSurface` text colors never move with the theme. A vivid, fully-saturated team color used raw as a background would break contrast against that fixed text color. Instead: light themes keep a near-white background, dark themes a near-black background, each lightly tinted toward the seed hue at low saturation — consistent with how the app's existing frosted/tinted surfaces already work.

**Deterministic validation, not LLM eyeballing:** a Python script in the skill's `scripts/` dir that the skill runs against the generated theme JSON before finalizing it:
- Each status color's hue falls inside its locked band.
- Pairwise color distance between all status colors (and each vs. background/surface) clears a minimum threshold, so nothing collides.
- Contrast of the generated background/surface against the app's fixed `onBackground`/`onSurface` clears a legibility threshold.

The skill loops — adjust saturation/lightness within the locked band, re-run the validator — until it passes, then writes the final JSON.

**Output:** `themes/generated/<team-id>.json` (e.g. `nfl-chiefs.json`), matching the full schema from Section 1. Deterministic slug naming so the deferred search/picker UI can key off it directly without redesigning anything here.

### 4. Testing & verification

- **Schema parity:** unit tests on `KKCThemeRepository` — a theme JSON setting all 7 new/extended keys parses correctly into `KKCStatusColors`; a theme JSON with only the original 4 keys still falls back to built-in defaults for everything else (no regression for themes already synced to tablets).
- **Hardcoded-color migration:** build a throwaway test theme JSON with deliberately distinctive (e.g. neon) values for every `KKCStatusColors` field and run a debug build against it. Any UI spot still reading a hardcoded literal instead of the token will visibly fail to change color — a direct catch for missed migrations, not just code review.
- **Generator skill validator:** a handful of known-good/known-bad hex sets as self-tests for the validator script itself, so a bug in the checker can't silently pass a bad theme.
- Existing tests (e.g. `HardwoodsRowHelpersTest`) must stay green — this work changes color *sourcing*, not status logic.

## Open follow-up (tracked as todo, not designed here)

- Populate the actual NFL + college football team theme catalog by running the finished generator skill once per team.
- Redesign the theme picker: add a "Football Team" option to the existing dropdown in `SettingsScreen.kt` that opens a search-with-autofill picker over the generated team themes.
