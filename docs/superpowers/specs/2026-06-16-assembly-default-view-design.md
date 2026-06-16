# Assembly Viewer Default View — Design

**Date:** 2026-06-16
**Mode scope:** Assembly mode only
**Storage scope:** Per-device

## Summary

Add a Settings sub-page that lets a tablet owner configure what happens when an
Assembly-mode user taps a job in the Jobs tab: split or single layout, which
view occupies each pane, and whether the viewer opens in immersive (fullscreen)
mode.

Defaults match the current hardcoded behavior — existing tablets see no change
until the user opts in.

## Goals

- One-time, per-device configuration that survives app restart.
- Zero impact on non-Assembly job-tap flows (CNC, Specialty, Hardwoods).
- Zero impact on deep-link entries into `AssemblyViewerScreen` (JobDetail "Open
  3D", Specialty "Open Split View", cabinet jumps).

## Non-goals

- Per-job remembered layout.
- Synced / cross-device defaults.
- A separate default for each WorkMode (only Assembly has split view).
- Renaming or removing the existing in-viewer pane pickers.

## Data Model

New file: `app/src/main/java/com/kkc/sheettracker/data/AssemblyViewerDefaults.kt`

```kotlin
enum class AssemblyViewLayout { SPLIT, SINGLE }

enum class AssemblyPaneView { ASSEMBLY, PLANS, DELIVERY, THREE_D, CHECKLIST }

data class AssemblyViewerDefaults(
    val layout: AssemblyViewLayout = AssemblyViewLayout.SPLIT,
    val firstPane: AssemblyPaneView = AssemblyPaneView.PLANS,
    val secondPane: AssemblyPaneView = AssemblyPaneView.ASSEMBLY,
    val immersive: Boolean = false,
)
```

Mapping to existing `PaneSource` (in `AssemblyViewerScreen.kt`): identity for
all five values. `PaneSource.OTHER` is not exposed in the settings UI.

### Store

New file: `app/src/main/java/com/kkc/sheettracker/data/AssemblyViewerDefaultsStore.kt`

DataStore prefs file name: `assembly_viewer_defaults`.

API:

```kotlin
class AssemblyViewerDefaultsStore(context: Context) {
    val defaults: Flow<AssemblyViewerDefaults>
    suspend fun setLayout(layout: AssemblyViewLayout)
    suspend fun setFirstPane(view: AssemblyPaneView)
    suspend fun setSecondPane(view: AssemblyPaneView)
    suspend fun setImmersive(value: Boolean)
}
```

Pattern mirrors the existing `TimecardServerConfig` per-device DataStore.
Single instance constructed in `MainActivity` and threaded into nav scope (same
pattern as the other stores).

When the DataStore is empty, reads return the data-class defaults — i.e. SPLIT,
PLANS + ASSEMBLY, immersive off — which matches today's hardcoded behavior.

## Settings UI

New file: `app/src/main/java/com/kkc/sheettracker/ui/settings/AssemblyViewerDefaultsScreen.kt`

Reached from a new row in `SettingsScreen.kt`: **"Assembly viewer defaults"** →
navigates to route `settings/assemblyViewerDefaults`.

Layout (top to bottom):

```
TopAppBar: "Assembly Viewer Defaults" [back]

Section "Layout"
  RadioGroup:
    ( ) Split view
    ( ) Single view

Section "Panes"
  When SPLIT:
    Dropdown "Left pane"  : Assembly | Plans | Delivery | 3D | Checklist
    Dropdown "Right pane" : Assembly | Plans | Delivery | 3D | Checklist
  When SINGLE:
    Dropdown "View"       : Assembly | Plans | Delivery | 3D | Checklist

Section "Display"
  Switch: "Open in fullscreen (hide system bars)"
```

- Changes write to the store immediately (no "Save" button — matches other
  settings screens).
- Same-pane on both sides is allowed without validation; viewer already handles
  it.
- The "Right pane" dropdown is hidden when SINGLE is selected. The previously
  saved `secondPane` value is preserved in the store so toggling back to SPLIT
  restores it.

## Wiring

### 1. Nav route extension

Extend `assemblyViewerRoute(...)` and the corresponding `composable("assembly/viewer/...")`
in `NavGraph.kt` to carry four new optional nav arguments:

- `layout: AssemblyViewLayout?`
- `firstPane: AssemblyPaneView?`
- `secondPane: AssemblyPaneView?`
- `immersive: Boolean?`

All nullable / defaulted in the route builder. Deep-link callers
(`onOpenThreeD`, Specialty `onOpenSplitView`, `onJumpToCabinet`) keep calling
the existing positional overload and pass none of the new args. Only the
Assembly Jobs-tab `onJobClick` populates them.

### 2. Assembly Jobs tap

In `NavGraph.kt` at the two `WorkMode.ASSEMBLY -> AssemblyJobsScreen(...)`
blocks (around lines 1071 and 2141), change `onJobClick`:

```kotlin
onJobClick = { card ->
    coroutineScope.launch {
        val d = assemblyViewerDefaultsStore.defaults.first()
        navController.navigate(
            assemblyViewerRoute(
                jobFolderName = card.folderName,
                assemblyPage = 1,
                plansPage = 1,
                layout = d.layout,
                firstPane = d.firstPane,
                secondPane = d.secondPane,
                immersive = d.immersive,
            )
        ) { launchSingleTop = true }
    }
}
```

Reading via `.first()` is safe — DataStore Flow emits the cached value within
a frame and the user-perceived latency is sub-frame.

### 3. Viewer consumption — `AssemblyViewerScreen.kt`

Three changes:

**a. Pane source init.** Where `firstPaneSource` and `secondPaneSource` are
initialized via `rememberSaveable`, layer the nav-arg in front of the existing
fallback chain:

```kotlin
// before: initialPaneSource ?: PaneSource.PLANS
// after:  initialPaneSource ?: navFirstPane?.toPaneSource() ?: PaneSource.PLANS
```

Same for `secondPaneSource`.

**b. Single layout.** When `layout == SINGLE`, set
`fullscreenPane = FullscreenPane.FIRST` during the same initial
`rememberSaveable` block so the viewer enters with one pane expanded.

**c. Immersive.** When `immersive == true`, on viewer entry call
`WindowInsetsControllerCompat.hide(systemBars())` (extract from the existing
`SheetViewerScreen.kt` pattern) and restore on `onDispose`. Wrap in a
`DisposableEffect(Unit)` keyed to the lifecycle owner so rotation does not
double-toggle.

### Scope guard

Defaults apply only when the Assembly Jobs-tab `onJobClick` populates the new
nav args. Every other entry point passes the legacy positional args, so:

- JobDetail → Open 3D: unchanged (always opens 3D, never immersive unless the
  3D path itself requests it).
- Specialty Job Detail → Open Split View: unchanged.
- Jump to cabinet from JobDetail: unchanged.
- Resume from `rememberSaveable` after process death: unchanged (uses the
  saved values, not the nav args, on second entry).

## Edge cases

| Case | Behavior |
| --- | --- |
| Same pane on both sides | Allowed; viewer already supports this. |
| Default first pane = 3D but job has no rooms | Existing 3D path already falls back to PLANS for the session. No new code needed. |
| First install / DataStore empty | Returns data-class defaults — identical to today's hardcoded behavior. |
| Immersive on rotate | DisposableEffect re-applies; reset on viewer exit. |
| User toggles SINGLE → SPLIT in settings | `secondPane` value preserved in store; UI re-shows the second dropdown. |

## Files Changed

New:
- `app/src/main/java/com/kkc/sheettracker/data/AssemblyViewerDefaults.kt`
- `app/src/main/java/com/kkc/sheettracker/data/AssemblyViewerDefaultsStore.kt`
- `app/src/main/java/com/kkc/sheettracker/ui/settings/AssemblyViewerDefaultsScreen.kt`

Modified:
- `app/src/main/java/com/kkc/sheettracker/MainActivity.kt` — instantiate store
- `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt` — extend route, settings sub-route, Assembly job-tap handler
- `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt` — add row linking to the new sub-page
- `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt` — accept new nav args, apply layout / pane / immersive on entry

## Testing

No automated tests (no existing Compose UI test infrastructure for these
screens). Manual test plan:

1. Fresh install (or clear `assembly_viewer_defaults`) → tap Assembly job →
   opens Split with Plans on left and Assembly on right, system bars visible.
2. Settings → set Single + 3D + immersive → back → tap Assembly job → opens
   single 3D pane, system bars hidden.
3. Settings → set Split + Checklist (left) + Delivery (right) → tap Assembly
   job → both panes correct.
4. With Single + immersive active in viewer, press back → system bars return
   on the Jobs screen.
5. From JobDetail in CNC mode, tap "Open 3D" → still opens 3D pane regardless
   of the Assembly default (deep-link not affected).
6. From Specialty Job Detail, tap "Open Split View" → still opens the legacy
   Plans+Assembly split (deep-link not affected).
7. Toggle SINGLE → SPLIT in settings → second dropdown reappears with the
   previously saved right-pane value still selected.
