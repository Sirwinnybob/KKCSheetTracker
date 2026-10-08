# KKCSheetTracker — Development Notes

## Project
Android app for KKC Custom Cabinets. Tracks sheet materials, jobs, and employee time.
Two shop locations connected via Omada site-to-site VPN.

## Timeclock Feature

### Architecture
- Hub server (`C:\Scripts\timeclock-hub\`) runs in Docker on TrueNAS Scale
- Polls one RTC-1000 device every 3 minutes; SQLite is the source of truth
- Android tablets talk to the hub via REST (mDNS auto-discovery or manual IP)
- Per-tablet background config stored in DataStore (`timeclock_background` prefs file)

### Frosted Glass Buttons — DO NOT use Surface + shadowElevation or semi-transparent background with shadow
Using `Surface(shadowElevation)` or `Modifier.shadow()` + `background(color.copy(alpha < 1f))` causes
the shadow to bleed through the transparent fill as a dark inner ring. This is a fundamental Android
hardware compositing issue — the compositor draws the shadow behind the layer and it shows through.

**The correct pattern for frosted semi-transparent elements:**
```kotlin
// hazeSource must be on the background layer (TimeclockBackground)
// Elements on top use hazeEffect — fills at full opacity so shadow cannot bleed through
modifier
    .shadow(elevation, shape, clip = false)   // external shadow only
    .clip(shape)
    .hazeEffect(state = hazeState, style = HazeDefaults.style(
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        blurRadius = 14.dp
    ))
```

For solid-color elements (action button): use `shadow(clip = false)` + `clip()` + `background(solidColor)`.
Never use `Surface(shadowElevation)` with any semi-transparent color — same bleed issue.

### Bottom navbar Haze: Standards / Library transition regression
- Library (`StandardsHubScreen`) and Safety/SDS (`SafetyDocumentsScreen`) must each paint
  `MaterialTheme.colorScheme.background` at their root. These direct `Column` roots otherwise
  leave the Haze source incomplete and create a persistent rectangular inner band in the
  transparent bottom navbar.
- Production tablets use `LegacySingleStackNavigation` by default. Its root `NavHost` applies a
  slide/fade transition to routes. Haze 1.5.1 captures its source in a graphics layer and can
  show one stale/partial frame while a direct Standards route enters through that transition.
- Keep the navbar transparent. Do not make it opaque, change its shadow, or move the Standards
  background onto the `NavHost` modifier; those changes either hide the intended glass effect or
  reintroduce the persistent band.
- The targeted fix is route-local no-op transitions for `standards`, `standards/molding`,
  `standards/safety`, and `standards/archive` in `LegacySingleStackNavigation`:
  `EnterTransition.None` / `ExitTransition.None` for enter, exit, pop-enter, and pop-exit.
  Other app routes retain their slide/fade transitions.
- `LegacyStandardsTransitionWiringTest` guards this wiring.

### hazeState wiring
`hazeState` lives in `TimecardScreen` and is applied to `TimeclockBackground` as `.hazeSource()`.
It flows down: `TimecardScreen` → `TimecardReadyState` → `NumpadGrid` → `NumpadKey`.
DisplayCard also receives it. Do NOT re-create a local hazeState inside `TimecardReadyState`.

### Hours display
Format with `"%.2f"` (two decimal places), never `"%.1f"`. Hub rounds up to nearest 15 minutes.

### Punch business rules
- Duration rounds UP to nearest 15-minute increment (`math.ceil(minutes / 15) * 15 / 60`)
- Punches under 7 minutes are deleted silently (accidental clock-in/out)
- Hub timezone: `TZ=America/Los_Angeles` in docker-compose — handles DST automatically

### KKC navbar mirror (Hours Tracker)
- Hours Tracker (`com.example.timecard`, repo `C:\Scripts\Hours Tracker\AndroidApp`) draws a copy of
  this app's bottom navbar when KKC launches it. Copy lives in HT `app/src/main/java/com/example/timecard/kkcnav/`.
- KKC sends the resolved look as `extra_kkc_navbar_*` extras (`navigation/KkcNavBarContract.kt`, built by
  `currentKkcNavBarPayload` in `KkcNavBarPayloadBuilder.kt`). Every `launchTimecardApp(` call must pass
  `navBar = kkcNavBarPayload` (`HoursNavBarMirrorWiringTest`).
- Contract v2: the bar follows **Hours Tracker's** light/dark mode (live, via its own theme toggle), so KKC
  sends BOTH resolved color sets (`*_light` / `*_dark` extras; no single `dark` flag) — computed from the
  active tokens via `toColorScheme(false/true)` and the non-composable `kkcFrostedBaseColor/ContentColor`
  overloads in `KKCBoldChrome.kt`. HT floats the bar over full-height content (no bottom inset), like KKC.
- HT-only styling (no KKC counterpart, do NOT mirror into KKC): KKC's colors are used only for HT's standard
  theme; an equipped HT immersive theme colors the bar from that theme, and the LCARS theme replaces the bar
  with destination blocks in its LCARS frame band. HT also picks bold-mode on-glass text against its own
  canvas, so the payload's `frostedContent` is not what HT draws. See HT `AGENTS.md` "KKC navbar mirror".
- Taps come back as `extra_kkc_nav_destination` → `MainActivity.handleKkcNavIntent` → `ExternalNavRequests`
  → `ExternalNavEffect` in BOTH nav hosts, which call the same `navigateFromBar` their own navbar uses.
- The payload's `blurDisabled`/`shadowsDisabled` are the user's low-end settings only; KKC's own bar also
  folds in the transient `webViewBlurSuppressed` (3D pane) flag, the mirror intentionally does not.
- A return tap resets KKC's `IdleActivityTracker` (`handleKkcNavIntent`) so KKC doesn't wake up dimmed.
- Any change to `AppScaffold.kt` `MorphingNavBar`/`MorphingNavIconRow` (full state), `NavIcons.kt`,
  `IconDsl.kt`, nav entries in `Spacing.kt`, or `labelSmall` in `Type.kt` must be mirrored in HT `kkcnav/`
  in the same session. Contract changes update both `KkcNavBarContract.kt` files and both
  `KkcNavBarContractTest` CANONICAL_KEYS lists, and bump `VERSION` on both sides.

## Build

**Tablet deployment (release build — default for production tablets):**
```
.\adb-install-release.ps1
```
Builds release APK and installs to all connected devices with `-r` flag (preserves data).

**Local development (debug build):**
```
cd C:\Scripts\KKCSheetTracker
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
