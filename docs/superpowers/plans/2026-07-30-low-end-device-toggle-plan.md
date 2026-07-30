# Low-End Device Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-device "Low-end device mode" toggle in Settings > Appearance that disables animations, shadows, blur/frosted glass, and enables lazy data loading — with granular per-effect overrides.

**Architecture:** Extend existing `UiPreferencesStore` (SharedPreferences) + `AppStateFeatureFlags` pattern. Provide flags via `CompositionLocal` (`LocalLowEndMode`). Components consume flags to conditionally disable animations (`snap()` vs `spring()`), shadows, `hazeEffect`, and use smaller page sizes for lists.

**Tech Stack:** Kotlin, Jetpack Compose, SharedPreferences, DataStore (existing patterns)

## Global Constraints

- Stored in `SharedPreferences("kkc_tracker")` per-device — no Syncthing sync
- Observed via `AppStateFeatureFlags.snapshotFlow().collectAsState()`
- Consumed via `CompositionLocal` (`LocalLowEndMode`)
- Default: master OFF, all granular ON. Master ON → granular default OFF but user-togglable
- Granular toggles persist independently
- Follow existing code patterns in `UiPreferencesStore`, `AppStateFeatureFlags`, `SettingsScreen`

---

### Task 1: Add Preferences Keys to UiPreferencesStore

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/UiPreferencesStore.kt`

**Interfaces:**
- Produces: `getLowEndMode()`, `setLowEndMode(Boolean)`, `getAnimationsEnabled()`, `setAnimationsEnabled(Boolean)`, `getShadowsEnabled()`, `setShadowsEnabled(Boolean)`, `getBlurEnabled()`, `setBlurEnabled(Boolean)`, `getLazyLoadingEnabled()`, `setLazyLoadingEnabled(Boolean)`

- [ ] **Step 1: Write failing test**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/UiPreferencesStoreTest.kt
@Test
fun lowEndMode_defaultsToFalse() {
    val store = UiPreferencesStore(context)
    assertFalse(store.getLowEndMode())
}

@Test
fun lowEndMode_persists() {
    val store = UiPreferencesStore(context)
    store.setLowEndMode(true)
    assertTrue(store.getLowEndMode())
}

@Test
fun granularFlags_defaultToTrue() {
    val store = UiPreferencesStore(context)
    assertTrue(store.getAnimationsEnabled())
    assertTrue(store.getShadowsEnabled())
    assertTrue(store.getBlurEnabled())
    assertTrue(store.getLazyLoadingEnabled())
}

@Test
fun granularFlags_persistIndependently() {
    val store = UiPreferencesStore(context)
    store.setAnimationsEnabled(false)
    store.setShadowsEnabled(true)
    store.setBlurEnabled(false)
    store.setLazyLoadingEnabled(true)
    assertFalse(store.getAnimationsEnabled())
    assertTrue(store.getShadowsEnabled())
    assertFalse(store.getBlurEnabled())
    assertTrue(store.getLazyLoadingEnabled())
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd C:\Scripts\KKCSheetTracker && .\gradlew.bat :app:testDebugUnitTests --tests "com.kkc.sheettracker.data.UiPreferencesStoreTest.lowEndMode_defaultsToFalse"
```
Expected: FAIL — methods don't exist

- [ ] **Step 3: Implement in UiPreferencesStore.kt**

Add keys and get/set methods following existing pattern (e.g., `admin_mode`, `supply_tab_order`)

- [ ] **Step 4: Run test to verify it passes**

```bash
cd C:\Scripts\KKCSheetTracker && .\gradlew.bat :app:testDebugUnitTests --tests "com.kkc.sheettracker.data.UiPreferencesStoreTest.lowEndMode*"
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/UiPreferencesStore.kt app/src/test/java/com/kkc/sheettracker/data/UiPreferencesStoreTest.kt
git commit -m "feat: add low-end mode prefs keys to UiPreferencesStore"
```

---

### Task 2: Extend AppStateFeatureFlags

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/AppStateFeatureFlags.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/AppStateFeatureFlagsTest.kt`

**Interfaces:**
- Consumes: `UiPreferencesStore` keys from Task 1
- Produces: `AppStateFlagsSnapshot` with `lowEndMode`, `animationsEnabled`, `shadowsEnabled`, `blurEnabled`, `lazyLoadingEnabled` fields

- [ ] **Step 1: Write failing test**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/AppStateFeatureFlagsTest.kt
@Test
fun snapshot_includesLowEndFlags() {
    val prefs = context.getSharedPreferences("kkc_tracker", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("low_end_mode", true).apply()
    prefs.edit().putBoolean("low_end_animations_enabled", false).apply()
    prefs.edit().putBoolean("low_end_shadows_enabled", false).apply()
    prefs.edit().putBoolean("low_end_blur_enabled", false).apply()
    prefs.edit().putBoolean("low_end_lazy_loading_enabled", false).apply()

    val flags = AppStateFeatureFlags(prefs, false).snapshot()
    assertTrue(flags.lowEndMode)
    assertFalse(flags.animationsEnabled)
    assertFalse(flags.shadowsEnabled)
    assertFalse(flags.blurEnabled)
    assertFalse(flags.lazyLoadingEnabled)
}

@Test
fun snapshot_defaultsCorrect() {
    val prefs = context.getSharedPreferences("kkc_tracker", Context.MODE_PRIVATE)
    val flags = AppStateFeatureFlags(prefs, false).snapshot()
    assertFalse(flags.lowEndMode)
    assertTrue(flags.animationsEnabled)
    assertTrue(flags.shadowsEnabled)
    assertTrue(flags.blurEnabled)
    assertTrue(flags.lazyLoadingEnabled)
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd C:\Scripts\KKCSheetTracker && .\gradlew.bat :app:testDebugUnitTests --tests "com.kkc.sheettracker.data.AppStateFeatureFlagsTest.snapshot_includesLowEndFlags"
```
Expected: FAIL — fields don't exist in snapshot

- [ ] **Step 3: Implement in AppStateFeatureFlags.kt**

Add 5 fields to `AppStateFlagsSnapshot` data class and read from prefs in `snapshot()` using keys from Task 1

- [ ] **Step 4: Run test to verify it passes**

```bash
cd C:\Scripts\KKCSheetTracker && .\gradlew.bat :app:testDebugUnitTests --tests "com.kkc.sheettracker.data.AppStateFeatureFlagsTest.snapshot*"
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/AppStateFeatureFlags.kt app/src/test/java/com/kkc/sheettracker/data/AppStateFeatureFlagsTest.kt
git commit -m "feat: extend AppStateFeatureFlags with low-end mode flags"
```

---

### Task 3: Create LowEndModeCompositionLocal

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/LowEndModeCompositionLocal.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt` (provide composition local)
- Test: `app/src/test/java/com/kkc/sheettracker/ui/components/LowEndModeCompositionLocalTest.kt`

**Interfaces:**
- Consumes: `AppStateFeatureFlags` snapshot from MainActivity
- Produces: `LocalLowEndMode: CompositionLocal<LowEndModeFlags>` where `LowEndModeFlags` is data class with 5 booleans

- [ ] **Step 1: Write failing test**

```kotlin
// app/src/test/java/com/kkc/sheettracker/ui/components/LowEndModeCompositionLocalTest.kt
@Test
fun compositionLocal_providesFlags() {
    val flags = LowEndModeFlags(true, false, false, false, false)
    val compositionLocal = LocalLowEndMode
    assertNotNull(compositionLocal)
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd C:\Scripts\KKCSheetTracker && .\gradlew.bat :app:testDebugUnitTests --tests "com.kkc.sheettracker.ui.components.LowEndModeCompositionLocalTest.compositionLocal_providesFlags"
```
Expected: FAIL — file doesn't exist

- [ ] **Step 3: Create LowEndModeCompositionLocal.kt**

```kotlin
package com.kkc.sheettracker.ui.components

import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

data class LowEndModeFlags(
    val masterEnabled: Boolean,
    val animationsEnabled: Boolean,
    val shadowsEnabled: Boolean,
    val blurEnabled: Boolean,
    val lazyLoadingEnabled: Boolean
)

val LocalLowEndMode = staticCompositionLocalOf<LowEndModeFlags> {
    LowEndModeFlags(false, true, true, true, true)
}
```

- [ ] **Step 4: Provide in MainActivity.kt**

In `setContent` block, collect `AppStateFeatureFlags.snapshotFlow()` and provide `LocalLowEndMode` with derived flags:
- `masterEnabled = snapshot.lowEndMode`
- `animationsEnabled = snapshot.lowEndMode.not() || snapshot.animationsEnabled`
- `shadowsEnabled = snapshot.lowEndMode.not() || snapshot.shadowsEnabled`
- `blurEnabled = snapshot.lowEndMode.not() || snapshot.blurEnabled`
- `lazyLoadingEnabled = snapshot.lowEndMode.not() || snapshot.lazyLoadingEnabled`

- [ ] **Step 5: Run test to verify it passes**

```bash
cd C:\Scripts\KKCSheetTracker && .\gradlew.bat :app:testDebugUnitTests --tests "com.kkc.sheettracker.ui.components.LowEndModeCompositionLocalTest*"
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/LowEndModeCompositionLocal.kt app/src/main/java/com/kkc/sheettracker/MainActivity.kt app/src/test/java/com/kkc/sheettracker/ui/components/LowEndModeCompositionLocalTest.kt
git commit -m "feat: add LowEndMode CompositionLocal and provide in MainActivity"
```

---

### Task 4: Add Settings UI in SettingsScreen

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `UiPreferencesStore` (passed as new param), `LowEndModeFlags` via `LocalLowEndMode.current` for live preview
- Produces: Performance subsection under Appearance with master toggle + 4 granular toggles

- [ ] **Step 1: Write failing test**

```kotlin
// app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsScreenTest.kt
@Test
fun settingsScreen_showsPerformanceSection() {
    // Compose test: render SettingsScreen, verify "Low-end device mode" text exists
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd C:\Scripts\KKCSheetTracker && .\gradlew.bat :app:testDebugUnitTests --tests "com.kkc.sheettracker.ui.settings.SettingsScreenTest.settingsScreen_showsPerformanceSection"
```
Expected: FAIL — section doesn't exist

- [ ] **Step 3: Modify SettingsScreen.kt**

1. Add `uiPreferencesStore: UiPreferencesStore` param to `SettingsScreen`
2. Inside Appearance `SettingsCard`, after theme section, add `HorizontalDivider()` then Performance subsection:
   - Master toggle: `Switch(checked = lowEndMode, onCheckedChange = { uiPreferencesStore.setLowEndMode(it) })`
   - If master ON, show 4 granular toggles with `Switch` each calling respective `uiPreferencesStore.set*Enabled()`
   - Read current values from `uiPreferencesStore.get*Enabled()`

- [ ] **Step 4: Update MainActivity.kt call site**

Pass `UiPreferencesStore(context)` to `SettingsScreen` in `AppNavigation`

- [ ] **Step 5: Run test to verify it passes**

```bash
cd C:\Scripts\KKCSheetTracker && .\gradlew.bat :app:testDebugUnitTests --tests "com.kkc.sheettracker.ui.settings.SettingsScreenTest*"
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt app/src/main/java/com/kkc/sheettracker/MainActivity.kt app/src/test/java/com/kkc/sheettracker/ui/settings/SettingsScreenTest.kt
git commit -m "feat: add low-end mode toggle UI in Settings > Appearance"
```

---

### Task 5: Consume LowEndMode in Animation Components

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ClockInButton.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/timecard/NumpadKey.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/timecard/NumpadGrid.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/CalculatorOverlay.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/theme/KKCTheme.kt`

**Pattern:**
```kotlin
val lowEnd = LocalLowEndMode.current
val spec = if (lowEnd.animationsDisabled) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy)
val animatedValue by animate*AsState(target, animationSpec = spec)
```

- [ ] **Step 1: Write failing test** (screenshot test for each component with `animationsDisabled=true` → no animation)
- [ ] **Step 2: Modify each component** to read `LocalLowEndMode.current` and use `snap()` when disabled
- [ ] **Step 3: Run tests**
- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ClockInButton.kt app/src/main/java/com/kkc/sheettracker/ui/timecard/NumpadKey.kt app/src/main/java/com/kkc/sheettracker/ui/timecard/NumpadGrid.kt app/src/main/java/com/kkc/sheettracker/ui/components/CalculatorOverlay.kt app/src/main/java/com/kkc/sheettracker/ui/theme/KKCTheme.kt
git commit -m "feat: disable animations when low-end mode enabled"
```

---

### Task 6: Consume LowEndMode for Shadows

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobCard.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItem.kt` (or equivalent)
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardSurfacePrimitives.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt` (SettingsCard, WorkModeIconTile)
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/timecard/TimeclockBackground.kt`

**Pattern:**
```kotlin
val lowEnd = LocalLowEndMode.current
val elevation = if (lowEnd.shadowsDisabled) 0.dp else 4.dp
Surface(modifier = Modifier.shadow(elevation, shape, clip = false), ...)
```

- [ ] **Step 1: Write failing tests**
- [ ] **Step 2: Modify each component** to conditionally apply `shadow()` based on `lowEnd.shadowsDisabled`
- [ ] **Step 3: Run tests**
- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobCard.kt app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItem.kt app/src/main/java/com/kkc/sheettracker/ui/dashboard/DashboardSurfacePrimitives.kt app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt app/src/main/java/com/kkc/sheettracker/ui/timecard/TimeclockBackground.kt
git commit -m "feat: disable shadows when low-end mode enabled"
```

---

### Task 7: Consume LowEndMode for Blur/Frosted Glass

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/timecard/TimeclockBackground.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/timecard/NumpadKey.kt`
- Modify: Any other components using `hazeEffect()`

**Pattern:**
```kotlin
val lowEnd = LocalLowEndMode.current
val modifier = Modifier
    .shadow(elevation, shape, clip = false)
    .clip(shape)
if (!lowEnd.blurDisabled) {
    modifier.hazeEffect(hazeState, HazeDefaults.style(...))
}
```

- [ ] **Step 1: Write failing tests**
- [ ] **Step 2: Modify components** to conditionally apply `hazeEffect()` based on `lowEnd.blurDisabled`
- [ ] **Step 3: Run tests**
- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/timecard/TimeclockBackground.kt app/src/main/java/com/kkc/sheettracker/ui/timecard/NumpadKey.kt
git commit -m "feat: disable frosted glass/blur when low-end mode enabled"
```

---

### Task 8: Implement Lazy Loading in Repositories and Screens

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/JobRepository.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/HardwoodsRepository.kt` (or equivalent)
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsDashboardScreen.kt`

**Changes:**
1. Add `pageSize` param to repository list functions (default 50, low-end = 25)
2. Screens: convert eager `LaunchedEffect(Unit) { loadAll() }` to `LazyColumn` + pagination with `LaunchedEffect(scrollState) { if (nearEnd) loadMore() }`
3. Read `LocalLowEndMode.current.lazyLoadingActive` to determine page size

- [ ] **Step 1: Write failing tests** (repository pagination, screen lazy loading)
- [ ] **Step 2: Modify repositories** to accept `pageSize` and return paginated results
- [ ] **Step 3: Modify screens** to use pagination when `lazyLoadingActive`
- [ ] **Step 4: Run tests**
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/JobRepository.kt app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt app/src/main/java/com/kkc/sheettracker/data/HardwoodsRepository.kt app/src/main/java/com/kkc/sheettracker/ui/jobs/UnifiedJobsScreen.kt app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyDashboardScreen.kt app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsDashboardScreen.kt
git commit -m "feat: implement lazy loading for job/supply lists in low-end mode"
```

---

### Task 9: Integration Test & Polish

- [ ] Run full test suite
- [ ] Manual test on low-end tablet: enable mode, verify all 4 categories disabled, verify granular re-enable works
- [ ] Verify settings persist across app restarts
- [ ] Commit any fixes

```bash
cd C:\Scripts\KKCSheetTracker && .\gradlew.bat test
```

---

## Summary

| Task | Files Modified | Test Focus |
|------|----------------|------------|
| 1. UiPreferencesStore prefs keys | UiPreferencesStore.kt | Unit: get/set persist |
| 2. AppStateFeatureFlags | AppStateFeatureFlags.kt | Unit: snapshot reads prefs |
| 3. LowEndMode CompositionLocal | LowEndModeCompositionLocal.kt, MainActivity.kt | Compose: provides flags |
| 4. Settings UI | SettingsScreen.kt | UI: toggles appear & persist |
| 5. Animations | ClockInButton, NumpadKey, NumpadGrid, CalculatorOverlay, KKCTheme | Screenshot: no motion when disabled |
| 6. Shadows | UnifiedJobCard, SupplyItem, DashboardSurfacePrimitives, SettingsScreen, TimeclockBackground | Screenshot: no elevation when disabled |
| 7. Blur/Frosted glass | TimeclockBackground, NumpadKey | Screenshot: no hazeEffect when disabled |
| 8. Lazy loading | JobRepository, SupplyRepository, HardwoodsRepository, UnifiedJobsScreen, SupplyDashboardScreen, HardwoodsDashboardScreen | Integration: pageSize=25, pagination works |
| 9. Integration | — | Manual: all 4 categories work together |