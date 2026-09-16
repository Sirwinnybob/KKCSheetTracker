# NFL Team Themes & Searchable Picker (Phase 2)

## Background

This is the deferred follow-up from [2026-09-15-theme-parity-and-generator-design.md](2026-09-15-theme-parity-and-generator-design.md), which built the `kkc-theme-generator` skill and made every job/part status color theme-overridable, but explicitly deferred populating a real team-theme catalog and redesigning the theme picker UI (the existing flat dropdown doesn't scale past a handful of shop-custom themes).

## Scope

- **32 NFL team themes** (not college — scoped down from the original "all NFL and college" idea; FBS/FCS/D2/D3 would push past 700 teams and needs a real roster data source, not a hand-authored list — a possible future phase, not this one).
- A **searchable picker** for choosing a team theme, without disrupting the existing shop-custom theme dropdown.
- A **header team badge** (text-based, not logo images — see Non-goals) shown when a team theme is active.

## Non-goals

- **Real team logo images.** NFL team logos are trademarked; even internal, non-commercial, undistributed use doesn't carry a clean legal exemption the way copyright fair use might, and this project won't source or generate logo artwork. The header badge is text-only for now (team name/abbreviation, styled in the theme's color). The schema and rendering path support an optional logo image for any theme (shop or team) so this isn't a dead end if the business later decides to supply licensed or original artwork — but no logo files are part of this phase.
- **College teams.** Explicitly out of scope this phase (see Scope above).
- **Automatic sync/deployment of the generated theme files.** Copying the 32 output JSONs to the shared `Y:\Ready Jobs\.metadata\themes` folder is a manual, one-time step, same as any shop-custom theme today — no new sync tooling is built.

## Design

### 1. Theme category

Add an optional `"category"` field to the theme JSON schema, parsed in `KKCThemeRepository` the same way other optional fields already are. Defaults to `"custom"` when absent — every existing shop theme (`kkc-forest-shop.json`, etc.) keeps working with zero edits. The generator skill's NFL output sets `"category": "nfl"`. `KKCThemeDefinition`/`KKCThemeTokens` gains a `category: String` field.

### 2. Picker UI

The existing "This tablet" dropdown in `SettingsScreen.kt` (currently a plain `ExposedDropdownMenuBox` listing `themeCatalog.themes`) changes to:

- List only `category == "custom"` themes as it does today, plus one synthetic extra row appended at the end — **"Football Team"** — shown only when at least one `category == "nfl"` theme is loaded in the catalog.
- Selecting a normal (custom) theme behaves exactly as today: sets `overrideThemeId`, closes.
- Selecting **"Football Team"** swaps the same control into the editable, live-filtered variant already used elsewhere in this same file for employee-name search (`SettingsScreen.kt:596-630`, an `OutlinedTextField` + `ExposedDropdownMenu` pair) — scoped to just the `nfl`-category themes, filtered as the user types (e.g. typing "chief" narrows to "Kansas City Chiefs").
- Selecting a team sets `overrideThemeId` to that team's id and the control reverts to its normal closed state, now showing that team's name as the current selection.
- Reopening the dropdown shows the same list again (custom themes + "Football Team" row); the currently-active team isn't highlighted as a row in that list (it isn't literally one of the rows) — a known, accepted rough edge rather than merging two very different theme vocabularies into one flat searchable list.

### 3. Header team badge

Two new optional fields on a theme's `header` block: `badgeText` (short label, e.g. `"CHIEFS"`) and `badgeLogoPath` (an image path, resolved and sandbox-validated the same way `header.background` already is). Neither is NFL-specific — any theme can set either.

Rendering priority in the dashboard header, replacing the literal "KKC Dashboard" text (the " - {Mode}" suffix, e.g. "- CNC", is kept unchanged — it's operational context, not branding):

1. `badgeLogoPath` set → render that image.
2. Else `badgeText` set → render that text, styled in the theme's `primary` color.
3. Else (every theme today) → unchanged literal "KKC Dashboard" text.

The generator skill sets `badgeText` on every NFL theme it produces (e.g. team nickname, "CHIEFS"); `badgeLogoPath` stays unset until real logo files exist for some theme, someday.

### 4. Content generation & rollout

Run the `kkc-theme-generator` skill once per NFL team (32 runs) using each team's public primary/secondary brand colors as seed input, producing `themes/generated/nfl-<team-id>.json` — each with `category: "nfl"` and `badgeText` set, each passing the existing hue-lock validator (`validate_theme.py`) before being accepted. Team-id slugs are deterministic kebab-case (e.g. `nfl-chiefs`), matching the naming convention already established in the Phase 1 skill.

Rollout is a manual one-time copy of the 32 files into `Y:\Ready Jobs\.metadata\themes\` — the same shared folder shop-custom themes already sync from. No new sync mechanism; tablets pick up the new files the next time they load the theme catalog (existing "Reload Themes" mechanism, unchanged).

### 5. Testing

- **Schema:** unit tests for `category` and the two new `header` badge fields parsing correctly, and falling back to today's behavior (no category → `"custom"`; no badge fields → literal "KKC Dashboard" text) when absent — mirroring the exact test pattern from the Phase 1 parity-fix plan.
- **Picker UI:** the "Football Team" row appears only when the catalog has at least one `nfl`-category theme (test with zero and with several); typing into the search field filters correctly; selecting a team sets the override and the control reverts to showing that team's name.
- **Header badge:** unit-level checks that the three-way priority (logo → text → default) resolves correctly.
- **Manual device check:** push a couple of real generated team theme JSONs to a connected tablet (same procedure as Phase 1's Task 7 — push to `.metadata/themes`, select via Settings, verify, then clean up), confirming the picker flow and header badge both work end-to-end before considering this phase done.
