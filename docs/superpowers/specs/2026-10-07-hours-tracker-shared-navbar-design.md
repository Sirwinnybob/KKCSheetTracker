# Hours Tracker Shared Navbar — Design

Date: 2026-10-07
Repos: KKCSheetTracker (`C:\Scripts\KKCSheetTracker`, package `com.kkc.sheettracker`) and
Hours Tracker Android (`C:\Scripts\Hours Tracker\AndroidApp`, package `com.example.timecard`, separate git repo).

## Goal

Tapping **Hours** on the KKCSheetTracker bottom navbar opens the separate Hours Tracker app. Today the
KKC navbar disappears and the only way back is Hours Tracker's "← KKC" button, which returns to
whatever KKC page was showing before.

After this change, when Hours Tracker is launched by KKC it shows a navbar that is visually identical to
KKC's (same layout, KKC's theme colors, bold mode, frosted glass, badges, low-end behavior). The one
deliberate difference (decided after the first on-tablet check): the bar follows **Hours Tracker's**
light/dark mode, live, instead of KKC's. Tapping any other
destination on it closes Hours Tracker and lands KKC on that destination, exactly as if the user had
tapped it inside KKC.

### What the user asked for (verbatim intent)

- Keep the navbar while in Hours Tracker; tapping another navbar button navigates back to that KKC page.
- Option 1 (mirrored navbar in Hours Tracker) was chosen over a system overlay or merging the apps.
- Include the badge counts.
- Same look, color, animations — or no animations when low-end mode is enabled.
- Both copies must carry extensive "keep in sync" notes.
- Keep the existing "← KKC" buttons in Hours Tracker as a fallback.

### Assumptions (confirm or correct)

- The mirrored bar appears only when Hours Tracker is launched by KKC (`extra_launched_by_kkc`). Opening
  Hours Tracker from the launcher is unchanged.
- Badge counts are a snapshot taken at launch. KKC already stops its supply live client in `onStop`
  when another app is in front, so KKC's own counts do not advance during that time either; KKC shows
  live counts again on return.
- The mirrored bar is always in KKC's **full** state (labels shown, 22 dp icons) — the state KKC uses on
  every top-level tab. Viewer-only states (minimized pill, search/CNC/specialty/pen decorations, extended
  controls) are never shown in Hours Tracker.

## Non-goals

- No live badge updates while Hours Tracker is in front.
- No system overlay (`SYSTEM_ALERT_WINDOW`), no activity embedding, no merging the apps.
- No change to Hours Tracker behavior when launched standalone.
- No change to KKC's own navbar look or behavior.

## Current state (evidence)

- KKC `NavGraph.kt` `launchTimecardApp()` (≈ line 4279) starts `com.example.timecard.MainActivity` with
  `extra_launched_by_kkc`, optional `extra_auto_login`, `extra_job_number`, `extra_hours`. No
  `FLAG_ACTIVITY_NEW_TASK`, so Hours Tracker runs inside KKC's task on top of KKC's `MainActivity`.
- KKC navbar tap on HOURS calls `launchTimecardApp` in both nav hosts: the tab-layer host (≈ line 1252)
  and `LegacySingleStackNavigation` (≈ line 4017, production default). The selected KKC tab does not change.
- KKC `MainActivity` is `launchMode="singleTop"` and overrides `onNewIntent` (calls
  `handleNotificationIntent`).
- KKC navbar: `ui/components/AppScaffold.kt` — `AppBottomNavBar` → `MorphingNavBar` → `MorphingNavIconRow`;
  icons in `ui/components/icons/NavIcons.kt` built with `IconDsl.kt`; low-end flags
  `LowEndModeFlags` (`LocalLowEndMode`); frosted helpers `ui/theme/KKCBoldChrome.kt`; spacing
  `ui/theme/Spacing.kt`; typography `ui/theme/Type.kt` (`labelSmall` = Inter Medium 11 sp / 16 sp line /
  0.5 sp tracking, Inter via downloadable Google Fonts); indicator shape = `MaterialTheme.shapes.medium`
  = `RoundedCornerShape(themeTokens.shape.mediumDp)` (default 9 dp).
- Hours Tracker `MainActivity` reads `EXTRA_LAUNCHED_BY_KKC`; `TimecardApp` root is a full-size `Box`
  inside `TimecardTheme`. "← KKC" buttons in `TimesheetScreen.kt` (≈ line 553) and `NameCard.kt`
  (≈ line 290) call `activity.finish()`.
- Hours Tracker has no Haze dependency and no Google Fonts dependency today. Compose BOM 2026.03.01
  (KKC: 2026.06.01).

## Design

### 1. Intent contract (KKC → Hours Tracker)

A new contract object exists in both repos with identical keys and values:

- KKC: `app/src/main/java/com/kkc/sheettracker/navigation/KkcNavBarContract.kt`
- Hours Tracker: `app/src/main/java/com/example/timecard/kkcnav/KkcNavBarContract.kt`

Extras added by `launchTimecardApp()` (all prefixed `extra_kkc_navbar_`):

**Contract version 2** (v1 sent a single color set plus a `dark` flag and followed KKC's mode; v2 sends
both sets so the bar can follow Hours Tracker's mode). 27 keys: 11 theme-independent + 16 per-theme.

Theme-independent:

| Key | Type | Value |
|---|---|---|
| `version` | Int | Contract version, currently `2` |
| `destinations` | String[] | Visible destination routes in bar order (e.g. `jobs, hours, timecard, supply, standards`) |
| `supply_count` | Int | Supply badge count at launch |
| `safety_count` | Int | Safety badge count at launch (shown on Library/`standards`) |
| `frosted_alpha`, `frosted_blur_dp` | Float | Theme frosted tokens (same coercions applied on the Hours side as KKC applies) |
| `bold_mode` | Boolean | Theme tokens `boldMode` |
| `indicator_corner_dp` | Float | `shapes.medium` corner radius (theme `shape.mediumDp`) |
| `anim_disabled`, `blur_disabled`, `shadows_disabled` | Boolean | `LowEndModeFlags` derived values |

Per-theme — each key exists twice, with suffix `_light` and `_dark` (e.g. `primary_light`,
`primary_dark`); the old `dark` key is gone:

| Key (+ `_light` / `_dark`) | Type | Value |
|---|---|---|
| `primary`, `on_surface_variant`, `surface_variant` | Int (ARGB) | `tokens.toColorScheme(dark)` values |
| `frosted_base`, `frosted_content` | Int (ARGB) | `kkcFrostedBaseColor(tokens, scheme, dark)`, `kkcFrostedContentColor(tokens, scheme, dark)` |
| `bold_gradient` | Int[] (ARGB) | `boldGradientColors(tokens.palette(dark))` |
| `badge_container`, `badge_content` | Int (ARGB) | Material3 `Badge` colors (`scheme.error` / `scheme.onError`) |

Colors are resolved **inside KKC composition** from the live theme tokens (which already reflect a theme
override or synced theme), for BOTH modes regardless of which mode KKC is currently in, so Hours Tracker
never needs to parse KKC theme JSON. The launch call sites that open Hours from the navbar (both nav hosts) build the
payload; the clock-out and login-dialog launch paths also pass it so the bar appears no matter how Hours
was opened from KKC.

Compatibility:
- Hours Tracker shows the mirrored bar only when `version` is present and equal to a version it supports.
  Otherwise (old KKC build, unknown version, missing extras) it behaves exactly as today.
- An old Hours Tracker build ignores the extras; KKC behaves exactly as today.

### 2. Return path (Hours Tracker → KKC)

- Tapping a destination other than Hours in the mirrored bar starts
  `com.kkc.sheettracker.MainActivity` with `extra_kkc_nav_destination=<route>` and
  `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`, then `finish()`es Hours Tracker. Since Hours
  Tracker sits on top of KKC in the same task, this pops Hours Tracker and delivers `onNewIntent` to the
  existing KKC activity (no KKC restart, KKC state preserved). If the KKC process was killed while Hours
  Tracker was in front, KKC starts fresh and reads the extra in `onCreate`.
- Tapping **Calc** sends `extra_kkc_nav_destination=calculator`; KKC returns to its current page and opens
  the calculator overlay.
- Tapping **Hours** (already selected) does nothing.
- Hours Tracker manifest adds `<queries><package android:name="com.kkc.sheettracker" /></queries>`.
- If KKC cannot be started (not installed, `ActivityNotFoundException`), Hours Tracker just `finish()`es —
  same as the "← KKC" fallback.

KKC side:
- `MainActivity.onNewIntent` (and `onCreate`, for the cold-start edge case) reads
  `extra_kkc_nav_destination`, validates it against an allowlist (`NavDestination` routes plus
  `calculator`; anything else is ignored), and posts it to a process-wide `ExternalNavRequests`
  single-consumer flow, then clears the extra from the intent so it isn't replayed on recreation.
- Both nav hosts collect `ExternalNavRequests` and run the **same code path as their own navbar
  `onNavigate`** (including the `keepSearchDeco` rule and, in `LegacySingleStackNavigation`, the
  existing `popUpTo(start)` / `launchSingleTop` navigation and Standards no-op transitions). A
  destination not in the currently visible list is ignored.

### 3. Mirrored navbar in Hours Tracker

New file `app/src/main/java/com/example/timecard/kkcnav/KkcNavBar.kt` — a copy of KKC
`MorphingNavBar` + `MorphingNavIconRow` restricted to the full state:

- Outer `Box`: `fillMaxWidth().navigationBarsPadding().padding(start/end = 24.dp, bottom = 12.dp)`
  (KKC `floatingNavMinSideMargin`, `floatingNavBottomGap`).
- `Surface(shape = RoundedCornerShape(20.dp))`, color transparent when blur active else
  `frosted_base.copy(alpha = frosted_alpha.coerceIn(0.5f, 0.95f))`, `shadowElevation = 3.dp` unless
  shadows disabled, `tonalElevation = 0.dp`.
- Haze 1.5.1 `hazeEffect(HazeDefaults.style(backgroundColor = frosted_base @ coerced alpha,
  blurRadius = frosted_blur_dp.coerceAtLeast(1f).dp))` when blur is not disabled.
- Icon row: `heightIn(min = 44.dp)`, `padding(horizontal = 24.dp, vertical = 4.dp)`,
  `Arrangement.SpaceEvenly`, every slot `weight(1f)`; Calc slot inserted before Hours; item padding
  14 dp × 8 dp; `spacedBy(3.dp)`; icon size 22 dp; label `labelSmall`, Bold when selected.
- All color rules below read the ACTIVE set: `payload.colorsFor(darkTheme)` where `darkTheme` is Hours
  Tracker's own mode (`themeState.mode != ThemeMode.Light`, i.e. Dark and Oled count as dark). Because
  `themeState.mode` is Compose state, toggling Hours Tracker's theme (moon button) re-colors the bar
  immediately, with no relaunch.
- Tints: selected = `frosted_content` in bold mode else `primary`; unselected = `frosted_content @ 0.8`
  in bold mode else `on_surface_variant`.
- Selection background: bold mode → linear gradient of `bold_gradient` at alpha 0.55; else
  `surface_variant`; shape `RoundedCornerShape(indicator_corner_dp)`.
- Badges: Material3 `BadgedBox`/`Badge` with `badge_container`/`badge_content` colors on Supply (supply
  count) and Library (safety count), only when > 0.
- Timeclock edit badge: never shown (Hours is the selected destination).
- Animation specs copied verbatim (`NavSpringDp`, `NavSpringSize`, `NavAnimEnter/Exit`, easing) and
  `snap()` when `anim_disabled`, so any future morph stays in sync.

Icons: KKC `NavIcons.kt` (only the nav destination + calculator icons) and `IconDsl.kt` copied verbatim
to `kkcnav/KkcNavIcons.kt` / `kkcnav/KkcIconDsl.kt`, package changed only.

Font: Hours Tracker adds `androidx.compose.ui:ui-text-google-fonts` and the Google Fonts certificate
array; Inter is declared exactly as KKC `Type.kt` does. The label `TextStyle` is copied from KKC
`labelSmall`.

The mirrored bar is rendered with its own colors from the payload, not Hours Tracker's `TimecardTheme`,
so Hours Tracker's accent/palette cannot restyle it. Hours Tracker's light/dark MODE only selects which
of KKC's two color sets is drawn.

### 4. Hours Tracker layout

- When the payload is present, `TimecardApp`'s root content is wrapped as the Haze source
  (`hazeSource(hazeState)`) and the `KkcNavBar` is drawn as an overlay aligned bottom-center, on top of
  every screen, including the login `NameCard`.
- There is NO bottom inset. Like KKC, the bar floats over full-height content: Hours Tracker's content
  runs to the bottom of the screen and the glass blurs the actual content behind the bar (the content Box
  is the Haze source). (The first iteration reserved the measured bar height as bottom padding, which left
  an unblurred strip of the root background brush under the bar; that was removed after the on-tablet
  check.) Screens that need bottom clearance for their last controls must handle it themselves, as KKC
  screens do.
- Existing "← KKC" buttons stay as a fallback, unchanged.

### 5. App-switch transition

- Both directions use a short cross-fade (`ActivityOptions.makeCustomAnimation` on launch,
  `overridePendingTransition` / `overrideActivityTransition` on finish). The two bars are pixel-matched, so
  the bar appears to stay in place while content changes.
- When `anim_disabled` is true, both directions use no window animation (instant switch).

### 6. Keep-in-sync documentation

Large `KEEP IN SYNC` header comments are added at:

- KKC: `AppScaffold.kt` (above `MorphingNavBar` and `MorphingNavIconRow`), `NavIcons.kt`, `IconDsl.kt`,
  `Spacing.kt` (nav entries), `Type.kt` (`labelSmall`), `KkcNavBarContract.kt`, `launchTimecardApp()`.
- Hours Tracker: `KkcNavBar.kt`, `KkcNavIcons.kt`, `KkcIconDsl.kt`, `KkcNavBarContract.kt`, the
  `TimecardApp` overlay site.

Each header lists the exact counterpart file path(s) in the other repo and the specific values that must
match (margins, radii, padding, icon sizes, tint rules, badge rules, animation specs, extra keys, contract
version). KKC `CLAUDE.md` and Hours Tracker `AGENTS.md` (its agent doc; it has no CLAUDE.md) gain a "KKC navbar mirror" section with the same file map and the rule:
any change to KKC's full-state navbar or icons must be mirrored in Hours Tracker in the same session, and
a change to the extras must bump `version` on both sides.

## Error handling

- Missing/invalid payload → no mirrored bar (current behavior).
- Unknown destination route on the KKC side → ignored, logged under `KKC_NAV`.
- Destination not visible in the current work mode → ignored.
- KKC not installed / cannot start → Hours Tracker finishes (same as "← KKC").

## Testing

KKC (unit, JVM):
- Contract round-trip: payload builder produces every key with expected types; version constant.
- `ExternalNavRequests` allowlist: valid routes accepted, unknown/blank rejected, `calculator` accepted.
- Extend `LegacyStandardsTransitionWiringTest`-style wiring test so external requests go through the same
  navigate function as the navbar.

Hours Tracker (unit, JVM):
- Payload parser: valid v2 → model (both color sets); missing version / v1 / wrong version / missing
  any color of EITHER theme / empty gradient → null (no bar). `colorsFor(dark)` picks the right set.
- Wiring: `TimecardApp` passes `darkTheme = themeState.mode != ThemeMode.Light` to `KkcNavBar` and
  has no bottom inset.
- Return-intent builder: target component, flags, extra value per destination; Hours tap → no intent.

Device (release builds on a tablet, user navigates per tablet-screenshot feedback rule):
- Visual parity screenshot pair (KKC tab vs Hours Tracker) in light, dark, and a bold-mode theme.
- Low-end mode on: instant switch, no blur, no shadow.
- Tap each destination from Hours Tracker → correct KKC page, KKC state preserved.
- Calc tap → KKC calculator opens.
- Hours Tracker launched from the home screen → no bar.
- Old/new build mix: new Hours Tracker + old KKC → no bar, "← KKC" works.

## Constraints / risks

- Both repos have uncommitted work in files this touches (KKC `NavGraph.kt`; Hours Tracker
  `TimecardApp.kt`, `app/build.gradle.kts`). Implementation must not overwrite or commit that work
  unintentionally; the plan decides between working on top of it or in a worktree.
- Two copies of the navbar can drift. Mitigated by the sync headers, CLAUDE.md rules, and the contract
  version; not eliminated.
- Haze snapshot of the Hours Tracker content adds GPU cost on low-end tablets; the low-end blur flag
  disables it.
- Release builds must be installed on both apps for the feature to appear (Hours Tracker via its own
  deploy path; KKC via the release install path).
