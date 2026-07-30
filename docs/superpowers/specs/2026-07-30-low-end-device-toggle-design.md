# Low-End Device Toggle — Design Spec

**Date:** 2026-07-30  
**Status:** Draft — awaiting review

---

## Summary

Add a per-device "Low-end device mode" toggle in **Settings → Appearance** with a master switch + 4 granular overrides. When enabled, the app disables animations, shadows, frosted glass/blur, and switches data lists to lazy pagination — improving performance on older shop tablets.

---

## Requirements

| Category | Behavior |
|----------|----------|
| **Scope** | Animations + shadows + blur + lazy loading |
| **Granularity** | Master toggle + 4 independent overrides |
| **Persistence** | Per-device only (SharedPreferences `kkc_tracker`) |
| **Sync** | No Syncthing sync — each tablet decides independently |
| **Default** | OFF (full effects) |

---

## Architecture

### Data Layer

1. **UiPreferencesStore** (`kkc_tracker` SharedPreferences) — adds 5 boolean keys:
   - `low_end_mode` (master)
   - `low_end_animations_enabled`
   - `low_end_shadows_enabled`
   - `low_end_blur_enabled`
   - `low_end_lazy_loading_enabled`

2. **AppStateFeatureFlags** — reads prefs and exposes `LowEndModeFlags` snapshot via existing `snapshot()` flow.

3. **LowEndModeCompositionLocal** — `CompositionLocalProvider(LocalLowEndMode provides flags)` in `MainActivity`, consumed via `LocalLowEndMode.current` anywhere in Compose tree.

### UI Layer

**Settings → Appearance → Performance** subsection:

```
Low-end device mode          [☐]  ← master
  Animations                 [☑]  ← shown only when master ON
  Shadows                    [☑]
  Frosted glass / blur       [☑]
  Lazy data loading          [☑]
```

Granular toggles default ON (i.e., effects enabled) when master is toggled ON, allowing user to re-enable specific effects.

---

## Component Consumption Patterns

### Animations
```kotlin
val lowEnd = LocalLowEndMode.current
val spec = if (lowEnd.animationsDisabled) snap() else spring(...)
animate*AsState(target, animationSpec = spec)
```
**Files:** `ClockInButton`, `NumpadKey`, `NumpadGrid`, `CalculatorOverlay`, `KKCTheme` (navigation animations)

### Shadows
```kotlin
val lowEnd = LocalLowEndMode.current
val elevation = if (lowEnd.shadowsDisabled) 0.dp else 4.dp
Modifier.shadow(elevation, shape, clip = false)
```
**Files:** `UnifiedJobCard`, `SupplyItem`, `DashboardSurfacePrimitives`, `SettingsCard`, `WorkModeIconTile`, `TimeclockBackground`

### Blur / Frosted Glass
```kotlin
val lowEnd = LocalLowEndMode.current
Modifier
  .shadow(elevation, shape, clip = false)
  .clip(shape)
  .let { if (!lowEnd.blurDisabled) it.hazeEffect(...) else it }
```
**Files:** `TimeclockBackground`, `NumpadKey`, `DisplayCard`

### Lazy Loading
- Repositories: add `pageSize` param (default 50, low-end 25)
- Screens: `LazyColumn` + `LaunchedEffect(scrollState) { if (nearEnd) loadMore() }`
- Read `lowEnd.lazyLoadingActive` to choose page size
**Files:** `JobRepository`, `SupplyRepository`, `HardwoodsRepository`, `UnifiedJobsScreen`, `SupplyDashboardScreen`, `HardwoodsDashboardScreen`

---

## Implementation Tasks

| # | Task | Files | Test Type |
|---|------|-------|-----------|
| 1 | Add prefs keys to `UiPreferencesStore` | `UiPreferencesStore.kt` + test | Unit |
| 2 | Extend `AppStateFeatureFlags` | `AppStateFeatureFlags.kt` + test | Unit |
| 3 | Create `LowEndModeCompositionLocal` + provide in `MainActivity` | New file + `MainActivity.kt` + test | Compose |
| 4 | Settings UI in `SettingsScreen` | `SettingsScreen.kt` + `MainActivity.kt` call site + test | Compose |
| 5 | Consume in animation components | `ClockInButton`, `NumpadKey`, `NumpadGrid`, `CalculatorOverlay`, `KKCTheme` | Screenshot |
| 6 | Consume in shadow components | `UnifiedJobCard`, `SupplyItem`, `DashboardSurfacePrimitives`, `SettingsScreen` (cards), `TimeclockBackground` | Screenshot |
| 7 | Consume in blur components | `TimeclockBackground`, `NumpadKey`, `DisplayCard` | Screenshot |
| 8 | Implement lazy loading | Repositories + 3 dashboard screens | Integration |
| 9 | Integration test & manual verify | — | Manual |

---

## Acceptance Criteria

1. Toggle appears in Settings → Appearance → Performance
2. Master OFF → all effects enabled (current behavior)
3. Master ON → all 4 effects disabled by default
4. Each granular toggle re-enables its effect independently
5. Settings persist across app restarts
6. No Syncthing sync — each tablet independent
7. Animations: `snap()` used when disabled (no spring/tween)
8. Shadows: `0.dp` elevation when disabled
9. Blur: `hazeEffect()` omitted when disabled
10. Lists: page size 25 + pagination when lazy loading active

---

## Non-Goals

- Battery saver integration
- Automatic device capability detection
- Remote config / fleet-wide rollout
- Migration of existing prefs (new keys default to OFF/ON as specified)

---

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Forgetting a component that uses animation/shadow/blur | Grep for `animate*AsState`, `spring`, `tween`, `shadow(`, `hazeEffect(` — audit all hits |
| CompositionLocal not provided in test | Provide `LocalLowEndMode` in test `CompositionLocalProvider` |
| Lazy loading breaks existing scroll position | Use `LazyColumn` with `rememberLazyListState()` and `animateScrollToItem` |
| Per-device pref not cleared on uninstall | SharedPreferences auto-cleared with app data |

---

## Rollout

1. Merge feature behind toggle (default OFF)
2. Deploy to one shop tablet for manual validation
3. Enable for remaining low-end tablets via Settings