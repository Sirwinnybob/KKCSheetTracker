# Bold "All Out" Team Theming (Phase 3)

## Background

Phase 2 ([2026-09-15-nfl-team-themes-and-picker-design.md](2026-09-15-nfl-team-themes-and-picker-design.md)) added a searchable NFL team picker and a text header badge, but deliberately kept each team's visual footprint small — only `primary` changed, background/surface stayed identical across every theme. After trying it on a real tablet, the result was too subtle: "you would not be able to tell." This phase makes team theming actually visually distinctive across the app's chrome, without touching status-color meaning or any screen's layout/information architecture.

## Goals

- The header, bottom navbar, and four screens (Dashboard, Supply, Timeclock, Calculator) visibly reflect the active theme's colors with a bold, two-tone gradient/glow treatment ("scoreboard" style) when a theme opts into it.
- The treatment is a generic, opt-in theme capability (`boldMode`) — any theme, not just NFL themes, can turn it on. Every existing shop-custom theme keeps today's exact appearance untouched (opt-in defaults to off).
- Job/part status color meaning (complete=green, bad=red, skip=orange, etc.) is never touched by this work — only decorative chrome (headers, card backgrounds, button fills, navbar tint) changes.
- All 32 NFL team themes are regenerated with real two-color brand palettes (primary + secondary) and `boldMode: true`.

## Non-goals

- **Live NFL score streaming.** Considered and explicitly dropped by the user ("nobody works on the weekend anyways") — not part of this or any near-term phase.
- **College team themes** — already out of scope from Phase 2.
- **Layout or information-architecture changes** to any screen. This is chrome/color only — nothing moves, nothing is added or removed structurally.
- **Real team logo images** — still out of scope per Phase 2's non-goals (trademark risk); the badge stays text-based, now on a gradient chip instead of plain colored text.

## Design

### 1. Schema additions

Two additions to the theme JSON schema, both additive/backward-compatible:

- `secondary` — a second seed color per palette (`light.secondary`, `dark.secondary`), analogous to how `primary` already works. Optional; when absent, any bold-chrome gradient falls back to a solid fill using `primary` alone (so a theme can set `boldMode: true` without necessarily supplying a second color, though the 32 NFL themes will always supply one).
- `boldMode` — a top-level `Boolean`, defaults to `false`. Any theme (shop-custom or team) can set it. When `false` (every existing theme today, and any theme that doesn't set it), every surface covered by this phase renders exactly as it does today — solid `primary`, neutral glass tint, flat card backgrounds. This is the single switch that gates all of Section 2-4's behavior.

### 2. Shared bold-chrome primitive

One shared building block, defined once (in the `ui/theme` package alongside the existing token types), rather than reimplementing gradient/glow logic per screen:

- A pure function resolving a theme's `primary`/`secondary`/`boldMode` into either "solid" (today's behavior) or "gradient" (a `Brush` built from `primary`→`secondary`, or `primary`→a slightly-darkened `primary` when `secondary` is absent) — testable without Compose, following this project's established pure-function-plus-thin-composable pattern.
- A parallel resolution for a low-alpha "glow/tint" variant of the same colors, used for frosted-glass surfaces (navbar, Timeclock numpad, Calculator overlay) where a solid gradient would be too heavy — respects this codebase's existing frosted-glass rule (blur/haze only, never `Surface(shadowElevation)` with transparency, which causes a documented dark-ring bleed bug).

Every consuming screen below calls this shared primitive at the exact spots it already reads `MaterialTheme.colorScheme.primary` for an accent/selected/active-button color — confirmed by inspecting the current code for each screen before writing this spec, not assumed.

### 3. Header + navbar

- **Header (`KKCBrandedTitle`):** when `boldMode` is on, the badge text renders on a gradient chip (the shared primitive's "gradient" resolution) with a subtle text shadow, instead of plain colored text on the app bar's default background. The " - {Mode}" suffix stays plain text next to the chip.
- **Bottom navbar (`AppScaffold.kt`):** the frosted-glass tint (currently `MaterialTheme.colorScheme.surface`, confirmed in code as the reason team color barely shows here today) blends toward the shared primitive's "glow" resolution instead, when `boldMode` is on. The selected tab's indicator becomes a thin gradient underline (replacing today's plain `surfaceVariant` pill) — unselected tabs and their icons are untouched.

### 4. Dashboard + Supply

- **Dashboard:** the "Overall Progress" hero card and the four stat tiles (Completed/Bad Parts/Skipped/Jobs) get the shared primitive's gradient background wash plus a matching border, replacing today's flat `surfaceVariant`/status-tint background, when `boldMode` is on. The progress bar fill becomes a gradient instead of flat. Status-color meaning inside these cards (the green/red/orange numbers themselves) is untouched — only the card chrome around them changes.
- **Supply:** status chips keep their exact semantic color (untouched — that's meaning, not decoration). Category/section card headers get the same gradient wash as Dashboard's hero card; action buttons (e.g. Add Item) get the gradient fill instead of flat `primary`.

### 5. Timeclock + Calculator

- **Timeclock:** the numpad's frosted glass surfaces (`NumpadGrid`, `DisplayCard`) get the shared primitive's "glow" tint instead of neutral, respecting the existing haze-only frosted-glass rule. The solid Clock In/Out action button gets the gradient fill instead of flat `primary`, via the same `shadow(clip=false) + clip() + background(...)` pattern already used there today — a gradient `Brush` drops into that existing `background(...)` call with no structural change.
- **Calculator:** operator/equals buttons (already reading `primary` as their container color today) get the gradient fill; the overlay's frosted background surfaces get the "glow" tint instead of neutral.

### 6. Data regeneration

All 32 NFL theme JSON files are regenerated with real primary+secondary brand color pairs (e.g. Chiefs `#E31837`/`#FFB612`, Eagles `#004C54`/`#A5ACAF`) and `boldMode: true`. Existing shop-custom themes (`kkc-forest-shop.json`, etc.) are not touched — no `secondary`, no `boldMode`, both absent/false exactly as today, so they render identically before and after this phase ships.

### 7. Testing

- Schema: unit tests for `secondary` and `boldMode` parsing and their fallback behavior (absent `secondary` → gradient primitive degrades to a `primary`-only treatment; absent/`false` `boldMode` → every touched surface renders exactly as it does today).
- Bold-chrome primitive: unit tests for the pure gradient/glow resolution function across the on/off and with/without-secondary combinations.
- Manual device check: push 2-3 regenerated team themes (with real secondary colors) to a test tablet; confirm the header, navbar, Dashboard, Supply, Timeclock, and Calculator all show the gradient/glow treatment; confirm a `boldMode`-off shop theme is visually unchanged (no regression) on the same device.
