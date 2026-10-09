# Settings Facelift — Design

Date: 2026-10-08
Status: Approved in brainstorming, pending spec review

## Goal

Replace the Settings screen's single endless scroll of identical cards with a structured,
good-looking layout that:

- keeps the four big work-mode buttons (CNC / Hardwoods / Assembly / Specialty) as the hero —
  their logo slot will later hold comic-style logos drawn by the owner's sister;
- makes it easy to find settings (sections instead of one long scroll);
- separates shop-floor settings from IT plumbing (plumbing is tucked away, not locked);
- surfaces status (sync health, pending updates, tablet, version) at a glance.

Non-goals: no new settings, no changes to how any setting is stored or applied, no admin gating
of IT settings (they stay reachable by anyone, just tucked into an "Advanced" group).

## Decisions made in brainstorming

| Topic | Decision |
|---|---|
| Pain points | All: too long, plain looking, worker/IT mixed, status buried |
| IT plumbing access | Visible but tucked away ("Advanced" rail group), no password |
| Layout | Mode tiles full-width on top; sidebar rail + detail pane below |
| Mode tile shape | Wide, pinned row; tile height = min(tile width, 168dp); logo canvas stays square |
| Section icons | LF-a palette, V-a sheet+eye, Me-b ID badge, Up-a refresh-down, TD-a tablet+folder, SN-b cloud, PP-b gauge, Ad-b shield |
| Mode placeholders | Existing app icons until the comic logos arrive |
| Theme picker | Swatch cards instead of a dropdown |
| Pane header | Big icon + title + one-line description; settings in captioned grouped cards |

Mockups (fragments for the brainstorm companion server, kept for reference):
`docs/superpowers/specs/2026-10-08-settings-facelift/` — `full-mockup-v2.html` (dark + light),
`size-check.html` (true-dp landscape vs portrait), `icons.html` (icon option sheet).

## Screen layout

Tablets rotate freely. The connected shop tablet reports 824×1318dp portrait (1318×824dp
landscape). One layout serves both orientations.

```
┌ Top bar: ← Settings ················· [● Sync running] [⟳ 1 update] [Tablet CNC-2] [v8.7.13] ┐
│ WORK MODE ································································ Flexible mode ◯ │
│ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐                                         │
│ │  [logo]  │ │  [logo]  │ │  [logo]  │ │  [logo]  │   ← pinned, never scrolls               │
│ │   CNC    │ │HARDWOODS │ │ ASSEMBLY │ │SPECIALTY │                                         │
│ └──────────┘ └──────────┘ └──────────┘ └──────────┘                                         │
│ ┌ Rail ─────────────┐ ┌ Pane ──────────────────────────────────────────────┐                │
│ │ ▣ Look & Feel     │ │ [icon] Look & Feel                                  │                │
│ │ ▢ Viewers         │ │        Theme and light/dark for this tablet         │                │
│ │ ▢ Me              │ │ BRIGHTNESS                                          │                │
│ │ ▢ Updates & About①│ │ ┌ grouped card ──────────────────────────────────┐ │                │
│ │ ─ ADVANCED ─      │ │ │ Follow system theme                         ◉ │ │                │
│ │ ▢ Tablet & Data   │ │ └────────────────────────────────────────────────┘ │                │
│ │ ▢ Sync & Network  │ │ THEME                                               │                │
│ │ ▢ Performance & … │ │ ┌ swatch cards ─────────────────────────────────┐  │                │
│ │ ▢ Admin           │ │ └────────────────────────────────────────────────┘ │                │
│ └───────────────────┘ └─────────────────────────────────────────────────────┘                │
│                       (floating app navbar overlays the bottom)                             │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Top bar + status chips

`KKCTopAppBar` with back arrow and "Settings" title; chips in the `actions` slot.

| Chip | Shown | Look | Tap |
|---|---|---|---|
| Sync | always | dot colored by `SyncthingServiceStatus` (RUNNING green, CHECKING/PAUSED/API_KEY_REQUIRED amber, NOT_RUNNING/START_FAILED red) + short label | selects Sync & Network |
| Updates | only when `pendingSelfUpdate != null` or `pendingExternalUpdates` non-empty | amber, refresh-down icon, "N update(s)" | selects Updates & About |
| Tablet | only when wide (`showWideChips`) | neutral, "Tablet {tabletId}" | selects Tablet & Data |
| Version | only when wide | neutral, "v{VERSION_NAME}" | selects Updates & About |
| Admin | only when admin mode is ON | tertiary color, "Admin ON" | selects Admin |

`showWideChips(widthDp) = widthDp >= 1000`. In portrait (824dp) only sync/updates/admin show;
tablet ID and version remain visible in their own panes.

### Work mode row (kept, restyled)

- Header line: "WORK MODE" caption left; "Flexible mode" label + `Switch` right
  (`flexibleModeEnabled` / `onFlexibleModeChanged`). The old Flexible Mode card is removed.
- Four tiles in a `Row`, equal weight, 12dp gaps, 16dp side padding.
- Tile height = `modeTileHeight(tileWidth) = min(tileWidth, 168.dp)` — square in portrait
  (~190dp), wide in landscape (168dp).
- Selected tile: `primary` fill, `onPrimary` content. Unselected: `surface` fill, 1dp
  `outlineVariant` border. Corner 12dp. Shadow via existing `kkcCardDepth` rules (none when
  `LocalLowEndMode.current.shadowsDisabled`).
- Content: logo (square, 52% of tile height, centered) above the mode name (bold, uppercase,
  letter-spaced). Logo comes from `workModeLogo(mode)`.
- Tap → `onWorkModeChanged(mode)` (unchanged behavior).

### Rail

- Fixed width 240dp, `surface` background, 12dp corners, own vertical scroll, bottom padding
  so the last item clears the floating navbar (same 160dp clearance the screen uses today).
- Items: icon (24dp) + label. Selected item: `secondaryContainer` background,
  `onSecondaryContainer` content, bold label, **Selected** (duotone) icon. Unselected: **Unselected**
  (outline) icon.
- "ADVANCED" caption with a top divider precedes the advanced items; advanced items use
  `onSurfaceVariant` for label/icon when unselected.
- Updates & About shows an amber count badge when updates are pending.

### Pane

- Fills remaining width, own vertical scroll, same bottom clearance.
- `SectionHeader`: 48dp rounded square in `secondaryContainer` holding the section's Selected
  icon, title (titleLarge), one-line subtitle (`onSurfaceVariant`).
- Content is grouped: small uppercase caption + `GroupCard` (surface, 12dp corners, rows separated
  by 1dp `outlineVariant` dividers, 16dp row padding).
- Switching section resets the pane scroll to top.

## Section contents

Shop-floor group:

1. **Look & Feel** — "Theme and light/dark for this tablet"
   - BRIGHTNESS: Follow system theme (switch); Dark mode (switch, only when not following system).
   - THEME: `ThemeSwatchGrid` — one card per `customThemes(catalog.themes)`; each card shows the
     theme's primary/secondary as a two-color bar (dark palette when `isDarkTheme`, else light)
     and its name; the active one (`overrideThemeId ?: activeTheme.id`) gets a `primary` border.
     Tap → `onThemeFollowSyncedDefaultChanged(false)` + `onThemeOverrideChanged(id)`.
     If `footballTeamThemes(...)` is non-empty, a final "NFL team…" card opens the existing
     search (text field + filtered list using `filterThemesByQuery`), same selection callbacks.
   - Footer row: "Using fleet default" / "Applies only to this tablet" hint; "Reload themes"
     (outlined) → `onThemeCatalogReload`; "Use fleet default" (filled, enabled only when an
     override exists) → `onThemeOverrideChanged(null)` + `onThemeFollowSyncedDefaultChanged(true)`.
   - Theme `loadMessages` and `invalidThemes` render below in `error` color, as today.
2. **Viewers** — "How sheets and references display"
   - SHEETS: Use standard sheets (only when `isDarkTheme`); Continuous scroll; Label-only scroll
     preview (reads/writes `uiPreferencesStore` exactly as today).
   - VIEWER DEFAULTS: nav rows "Assembly viewer defaults ›" (`onOpenAssemblyViewerDefaults`) and
     "Specialty viewer defaults ›" (`onOpenSpecialtyViewerDefaults`).
3. **Me** — "Who is using this tablet"
   - Your Name / PIN field with employee autocomplete dropdown (same `EmployeeDirectory` filter
     logic), using `SaveableField`, → `onEmployeeNameChanged`.
4. **Updates & About** — "App version and pending installs"
   - PENDING (only when any): Update All (when both self and external), each update row with
     Update/Install button — same callbacks as today.
   - ABOUT: "KKC Sheet Tracker v{VERSION_NAME}"; "Check for updates" button → `onCheckForUpdates`;
     "Reinstall Latest Debug APK" when `isDebugBuild`.

Advanced group:

5. **Tablet & Data** — "Identity and data folder"
   - Tablet ID (`SaveableField`, non-blank) → `onTabletIdChanged`.
   - Ready Jobs folder path (`SaveableField`, non-blank, button text "Save path (app will
     restart)") → `onBasePathChanged`.
6. **Sync & Network** — "Syncthing, timeclock hub, Hours Tracker"
   - SYNCTHING: status badge, last check / last restart times, API key (`SaveableField`, password)
     → `onSyncthingApiKeySave`; Check Now / Start Now (enabled when key set).
   - TIMECLOCK: server IP (`SaveableField`, blank allowed = mDNS) → `timecardConfig.setManualIp`.
   - HOURS TRACKER ADMIN SYNC: server IP (`SaveableField`, blank allowed) →
     `adminSyncConfig.setManualIp`.
7. **Performance & Power** — "Low-end mode and idle power saving"
   - PERFORMANCE: Low-end device mode, with the four sub-toggles shown only when on, and turning
     it on forces the same defaults as today.
   - IDLE POWER SAVING: enable switch; when enabled, the two numeric fields with the existing
     500ms debounce into `idlePowerSaveStore`.
8. **Admin** — "Advanced controls for the office"
   - Locked: description + Unlock → existing `AdminPasswordDialog`.
   - Unlocked: "Admin mode is ON" + explanation + Lock admin.

### Initial section

`initialSection(hasPendingUpdates) = if (hasPendingUpdates) UPDATES_ABOUT else LOOK_AND_FEEL`.
Held in `rememberSaveable` so rotation keeps the current section.

## Code structure

All new code under `app/src/main/java/com/kkc/sheettracker/ui/settings/` unless noted.

| File | Responsibility |
|---|---|
| `SettingsScreen.kt` | Public `SettingsScreen(...)` — **signature unchanged**. Owns screen-level effects (employee refresh + `onCheckForUpdates` on open), admin dialog, selected section; lays out top bar, chips, mode row, rail, pane; dispatches to panes. |
| `SettingsSection.kt` | `enum class SettingsSection(title, subtitle, isAdvanced)`; pure `initialSection`, `showWideChips`, `modeTileHeight`. |
| `SettingsSectionIcons.kt` | `SettingsSection.icon(selected: Boolean): ImageVector` mapping to the icon set. |
| `SettingsChrome.kt` | `StatusChip`, `WorkModeRow`, `ModeTile`, `SettingsRail`, `RailItem`, `SectionHeader`, `GroupCaption`, `GroupCard`, `ToggleRow`, `NavRow`, `SaveableField`. |
| `WorkModeArt.kt` | `workModeLogo(mode: WorkMode): Painter` — the single swap point for the comic logos. |
| `panes/LookAndFeelPane.kt` | + `ThemeSwatchGrid`, NFL search. |
| `panes/ViewersPane.kt`, `MePane.kt`, `UpdatesAboutPane.kt`, `TabletDataPane.kt`, `SyncNetworkPane.kt`, `PerformancePowerPane.kt`, `AdminPane.kt` | One pane each; take only the params they need. |
| `ui/components/icons/SettingsIcons.kt` | 8 icons × Selected/Unselected, built with `IconDsl.kt` (`kkcIcon`, `line`, `solid`, `block`, `roundRect`, `circle`, `DUOTONE`). Unselected = outline only; Selected = add `DUOTONE` body fill; "solid" hero details are solid in both states. |

`ThemePickerLogic.kt` is kept as-is. `SpecialtyViewerDefaultsScreen.kt` and
`AssemblyViewerDefaultsScreen.kt` are untouched.

### SaveableField

Replaces six copies of the dirty/saved pattern:

```kotlin
@Composable
fun SaveableField(
    label: String,
    savedValue: String,
    onSave: (String) -> Unit,
    supportingText: String? = null,
    placeholder: String? = null,
    saveLabel: String = "Save",
    allowBlank: Boolean = false,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
)
```

Holds the edit text keyed on `savedValue`; Save button appears when trimmed text differs from
`savedValue` (disabled when blank and `!allowBlank`); after save shows "Saved" for 1600ms.
Calls `onSave(text.trim())` (blank → caller maps to `null` for the IP fields, as today).
The Me pane's employee autocomplete wraps its own field but reuses the same Save/Saved row.

### Logo swap (future)

`workModeLogo` today returns `rememberVectorPainter` for `StationCncIcon`, `HardwoodsPlankIcon`,
`ReferenceAssemblyIcon`, `HardwoodsSpecialtyIcon` (tinted with tile content color). When the comic
logos arrive: add `res/drawable-nodpi/mode_logo_{cnc,hardwoods,assembly,specialty}.png` (square,
≥ 512px, transparent background) and return `painterResource(...)`; `ModeTile` draws painters
untinted when they come from a bitmap (a `tint: Color?` returned alongside, or a sealed
`ModeLogo` — implementer's choice, keep it to this one file + `ModeTile`).

## Icon geometry (24×24, stroke 2 unless noted)

`f` = DUOTONE fill only when Selected; `s` = solid always; `sd` = solid only when Selected;
`line15` = stroke 1.5.

- **Look & Feel (palette):** body `M12 3C7 3 3 6.8 3 11.5S6.8 20 11.5 20c1.5 0 2-1 1.5-2-.6-1.2.2-2.5 1.5-2.5h2c2.5 0 4.5-2 4.5-4.5C21 6.6 17 3 12 3Z` (f + line); s circles r1.3 at (7.5,11.5) (9.5,7.3) (14.5,7) (17.3,10.5).
- **Viewers (sheet + eye):** roundRect(4.5,2,19.5,22,r2) (f + line); line `M8 6.5h5`; line15 eye `M7.5 14.5Q12 9.5 16.5 14.5Q12 19.5 7.5 14.5Z`; s pupil circle(12,14.5,r1.6).
- **Me (ID badge):** roundRect(3.5,5,20.5,21,r2) (f + line); block(w1.5) roundRect(10,2.5,14,6.5,r1); line15 circle(9,12,r2); line15 `M6 17.5c0-1.7 1.3-2.7 3-2.7s3 1 3 2.7`; line `M15 12h2.5M15 15.5h2.5`.
- **Updates & About (refresh-down):** f circle(12,12,r8); line `M20 12a8 8 0 1 1-2.3-5.7`; line `M18.5 2.5v4h-4`; line `M12 7.5v8M9 12.5l3 3 3-3`.
- **Tablet & Data (tablet + folder):** line `M13 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v5`; folder `M11 13h3l1.5 1.5H21V21H11Z` (f + line); line `M6.5 7h5`.
- **Sync & Network (cloud):** cloud `M7 19h10.5a4 4 0 0 0 .6-8A6 6 0 0 0 6.4 10.2 4.5 4.5 0 0 0 7 19Z` (f + line); line15 `M10 16.5V12M8.5 13.5 10 12l1.5 1.5M14 12v4.5M12.5 15l1.5 1.5 1.5-1.5`.
- **Performance & Power (gauge):** f `M3.5 17a9 9 0 1 1 17 0Z`; line `M3.5 17a9 9 0 1 1 17 0`; line15 ticks `M6 12.5h1.2M12 6.5v1.2M18 12.5h-1.2M7.8 8.3l.9.9`; line needle `M12 15.5l4-5.5`; s hub circle(12,15.5,r1.8).
- **Admin (shield + keyhole):** shield `M12 2.5 19.5 5.5V11c0 5-3.2 8.6-7.5 10.5C7.7 19.6 4.5 16 4.5 11V5.5Z` (f + line); s circle(12,10.5,r2); line `M12 12v3.5`.

Translate SVG relative commands to `PathBuilder` absolute calls (`moveTo`, `lineTo`, `arcTo`,
`curveTo`, `reflectiveCurveTo`, `quadTo`) when implementing.

## Error handling / edge cases

- No custom themes → swatch grid shows only the active (built-in) theme card.
- Theme catalog load errors → messages under the grid (unchanged behavior).
- Syncthing key blank → Check/Start disabled; Sync chip shows "API key required" amber.
- Low-end mode → no shadows on tiles/cards/rail; animations off follows existing `LocalLowEndMode`.
- Navbar Haze: Settings root keeps painting `MaterialTheme.colorScheme.background` (Scaffold does)
  so the transparent navbar has a complete Haze source (see CLAUDE.md navbar note).

## Testing

Unit (JVM):
- `SettingsSectionTest`: `initialSection` both branches; `showWideChips` at 999/1000dp;
  `modeTileHeight` returns width when < 168dp and 168dp otherwise; enum has 8 entries, 4 advanced,
  in the specified order.
- `SettingsPanesWiringTest` (source-reading, like existing wiring tests): every
  `SettingsSection` entry is dispatched to a pane in `SettingsScreen.kt`; `SettingsScreen(`
  parameter list still contains every parameter it had before this change.
- Existing `ThemePickerLogicTest`, `FlexibleModeWiringTest`, `PendingUpdatesSettingsWiringTest`
  pass unchanged.

Manual (release build on tablet via `gradlew assembleRelease` + `adb install -r`):
- Portrait and landscape: tiles square vs 168dp; rail fully reachable above navbar.
- Every setting in every pane still saves (spot-check theme swap, tablet ID, idle timeout,
  low-end toggle, admin unlock/lock).
- Chips jump to their sections; updates badge + initial section when an update is pending.

## Out of scope

- The comic logos themselves (swap point only).
- Any change to settings storage, the nav graph, or the Hours Tracker navbar mirror.
- Admin gating of Advanced sections.
