# Flexible Mode — Design

## Problem

`WorkMode` (CNC / Hardwoods / Assembly / Specialty) is currently a single tablet-wide
setting picked once in Settings ([SettingsScreen.kt:211](../../../app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt)).
It drives which screen family every job opens into, and which Dashboard variant renders.
Some tablets (e.g. a shared floor tablet, or an admin/lead device) need to switch between
modes on the fly rather than staying locked to one, and re-visiting Settings to flip the
mode every time is too slow.

## Goal

A new "Flexible Mode" toggle in Settings. When ON, the Dashboard and Jobs screens each show
a small mode-switcher in their header. Tapping a mode button instantly swaps which mode's
content that screen shows. On Jobs, tapping a job opens it in whichever mode is currently
selected in that screen's switcher.

## Non-goals

- No per-tap "which mode?" dialog — superseded by the header switcher during brainstorming.
- No merged/unified job list across trackers — each mode still renders its own existing job
  list content via its own `UnifiedJobsSpec`; the switcher just changes which one is active.
- No persistence of the switcher's selection across app restarts, and no per-job "remembered
  mode." Each screen resets to the tablet's underlying `work_mode` setting as its default
  every time it's freshly entered.
- No change to Assembly/Specialty Dashboard variants — they're untouched, not part of the
  Dashboard switcher (see below).

## Design

### Settings toggle

New boolean pref `flexible_mode_enabled`, stored in the same `SharedPreferences` as
`work_mode` ([MainActivity.kt:306-311](../../../app/src/main/java/com/kkc/sheettracker/MainActivity.kt)).
Read/written the same way `workMode` is today ([MainActivity.kt:394-398](../../../app/src/main/java/com/kkc/sheettracker/MainActivity.kt)),
threaded down through `AppNavigation`/`NavGraph` as a new `flexibleModeEnabled: Boolean`
parameter alongside the existing `workMode: WorkMode` parameter.

New Switch row in `SettingsScreen.kt` near the existing `WorkModeIconTile` picker
(~line 211-234), labeled "Flexible Mode" with a short helper line explaining it swaps the
fixed Work Mode picker's effect for a live switcher on Dashboard and Jobs.

The existing `work_mode` setting is **not removed**. When Flexible Mode is OFF, behavior is
byte-for-byte what it is today. When ON, `work_mode` still supplies each screen's *default*
starting selection.

### New composable: `ModeSwitcherRow`

New file `ui/components/ModeSwitcherRow.kt`. A compact row of toggle buttons sized to live in
a `TopAppBar` `actions` slot — distinct from the existing `WorkModeIconTile`, which is a large
square Settings-grid tile and wrong shape for a header. Signature:

```kotlin
@Composable
fun ModeSwitcherRow(
    modes: List<WorkMode>,
    selected: WorkMode,
    onSelect: (WorkMode) -> Unit,
    modifier: Modifier = Modifier
)
```

Selected state highlighted consistent with existing selected-tile styling (`primaryContainer`
background). Reused by both Dashboard (2 modes) and Jobs (4 modes).

### Dashboard switcher (CNC + Hardwoods only)

Per product decision, only CNC and Hardwoods appear on the Dashboard switcher — Assembly and
Specialty Dashboard variants are left exactly as-is and are not reachable from the switcher.

`DashboardShell` already exposes a `topBarActions: @Composable RowScope.() -> Unit` slot
([DashboardWidgetFactories.kt:391](../../../app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardWidgetFactories.kt)),
so no new Scaffold/TopAppBar is needed. `CncDashboardContent` and `HardwoodsDashboardContent`
each get a new optional param `modeSwitcher: (@Composable RowScope.() -> Unit)? = null`,
passed straight through to `DashboardShell(topBarActions = { modeSwitcher?.invoke(this) })`.

In `DashboardTabHost`'s `"dashboard"` composable ([NavGraph.kt:1254-1309](../../../app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt)):

- When `flexibleModeEnabled` is `false`: unchanged existing `when (workMode)` branch.
- When `true`: build **both** `UnifiedModeDashboardSpec.Cnc(...)` and
  `UnifiedModeDashboardSpec.Hardwoods(...)` unconditionally (Assembly/Specialty specs are not
  built in this branch). Hold `var dashMode by remember { mutableStateOf(if (workMode == WorkMode.HARDWOODS) WorkMode.HARDWOODS else WorkMode.CNC) }`.
  Render the spec matching `dashMode`, passing `modeSwitcher = { ModeSwitcherRow(modes = listOf(WorkMode.CNC, WorkMode.HARDWOODS), selected = dashMode, onSelect = { dashMode = it }) }`.

### Jobs switcher (all 4 modes)

`UnifiedJobsScreen` is already mode-agnostic — it takes one `spec: UnifiedJobsSpec` and all
four modes render through this same screen today. New optional params:

```kotlin
flexibleModeEnabled: Boolean = false,
selectedFlexMode: WorkMode? = null,
onFlexModeSelected: ((WorkMode) -> Unit)? = null
```

In the `KKCTopAppBar` `actions` row ([UnifiedJobsScreen.kt:404-437](../../../app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt)),
when `flexibleModeEnabled && selectedFlexMode != null`, render
`ModeSwitcherRow(modes = WorkMode.entries, selected = selectedFlexMode, onSelect = onFlexModeSelected)`
in the space freed up by the sort-button removal (below), before the Refresh/board-view icons.

In the NavGraph `"jobs"` composable (~[NavGraph.kt:1393-1469](../../../app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt)):

- When `flexibleModeEnabled` is `false`: unchanged existing per-branch logic (one spec built,
  one `onJobClick` wired to that mode's route, exactly as today).
- When `true`: compute all four `rememberCncJobsSpec` / `rememberHardwoodsJobsSpec` /
  `rememberAssemblyJobsSpec` / `rememberSpecialtyJobsSpec` unconditionally (cheap — they all
  derive from the same cached `engine.getCachedJobInfos()`, no new scan). Hold
  `var flexMode by remember { mutableStateOf(workMode) }`. Select the active spec via
  `when (flexMode)`. Tapping a job uses that spec's own existing `onJobClick` untouched — no
  new navigation/routing code, since each spec already targets the correct per-mode detail
  route.

### Sort button removal (independent cleanup)

Unconditional, not gated on Flexible Mode — remove the sort-order toggle entirely from
`UnifiedJobsScreen.kt`: the `sortByName` state ([~line 182](../../../app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt)),
its `IconButton` (~line 415-422), and all downstream threading (~lines 196, 200, 249, 267-268,
444/453, and the `sortByName` parameter passed at ~564, 590, 660, 689, 719). The list always
sorts production-order (today's behavior when `sortByName == false`, which is effectively
always in practice). The board-view button's `enabled = !sortByName && !adminMode` guard
simplifies to `enabled = !adminMode`.

### Duplication

Both the Jobs `when (workMode)` block and the Dashboard `when (workMode)` block exist twice in
`NavGraph.kt` — Jobs at ~1393 and ~2736, Dashboard at ~1257 and ~2666 (two separate nav-graph
call sites, consistent with existing duplication in this file per [CLAUDE.md](../../../CLAUDE.md)'s
notes on `LegacySingleStackNavigation`). Both call sites get the same treatment, mirroring the
existing duplication pattern rather than unifying it (out of scope here).

## Data flow summary

```
Settings toggle (flexible_mode_enabled, SharedPreferences)
        │
        ▼
MainActivity → AppNavigation → NavGraph(flexibleModeEnabled, workMode)
        │
        ├─ DashboardTabHost "dashboard" composable
        │     flexibleModeEnabled=false → existing when(workMode) branch (unchanged)
        │     flexibleModeEnabled=true  → build Cnc+Hardwoods specs, local dashMode state,
        │                                  ModeSwitcherRow(CNC, HARDWOODS) via topBarActions
        │
        └─ "jobs" composable
              flexibleModeEnabled=false → existing when(workMode) branch (unchanged)
              flexibleModeEnabled=true  → build all 4 specs, local flexMode state,
                                           ModeSwitcherRow(all 4) in UnifiedJobsScreen's
                                           TopAppBar actions; job tap uses active spec's
                                           existing onJobClick
```

## Testing

- Unit/UI test that toggling Flexible Mode OFF preserves exact existing single-mode behavior
  (regression guard, mirrors existing coverage style e.g. `LegacyStandardsTransitionWiringTest`).
- Test that Dashboard switcher only ever offers CNC/Hardwoods regardless of underlying
  `work_mode` value.
- Test that Jobs switcher offers all 4 and that tapping a job while a given mode is selected
  navigates to that mode's existing detail route (reuse of existing per-mode `onJobClick`
  should make this mostly free — existing per-mode navigation tests should still cover route
  correctness; add a case that changes `flexMode` mid-session and confirms the next tap uses
  the new selection).
- Manual: confirm sort button is gone and list still sorts production-order on tablets with
  Flexible Mode off.
