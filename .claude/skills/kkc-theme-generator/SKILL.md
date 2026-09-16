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
   | `notStarted` | any hue, saturation <= 0.17 | not started (gray) |

   Note: `inProgress` and `miscBg` intentionally share the same locked band
   — `miscBg` is meant to read as a close, lighter relative of `inProgress`,
   not a separately-distinguishable hue. The validator's pairwise-distance
   check is calibrated to allow that one same-family pair while still
   catching genuine collisions between other statuses.

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
