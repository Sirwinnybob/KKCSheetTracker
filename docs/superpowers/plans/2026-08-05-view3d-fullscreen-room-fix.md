# View 3D Fullscreen + Room Preference Fix

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clicking "View 3D" from the jobs list always opens the assembly viewer in fullscreen 3D mode (not split view), preferring Kitchen room when multiple rooms exist. The 3D view is transient — it must not overwrite the user's saved split-view pane layout.

**Architecture:** Two targeted fixes: (1) NavGraph's `resolveDefaultThreeDTarget` gets Kitchen-first room selection; (2) AssemblyViewerScreen forces fullscreen 3D on entry and skips the `LaunchedEffect` save when entered via `source=3d`, so the user's normal split-view preferences survive untouched.

**Tech Stack:** Kotlin, Jetpack Compose, Android Navigation

## Global Constraints

- No new dependencies or files
- Preserve existing session resume behavior for non-3D navigation
- Room normalization must handle case-insensitive matching
- Opening via 3D must not persist any state — `LaunchedEffect` save is skipped entirely

---

### Task 1: Kitchen-first room selection in resolveDefaultThreeDTarget

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:3270-3272`

**Interfaces:**
- Consumes: `assemblyRooms: List<Pair<String, Int>>` (room name to page number)
- Produces: `firstRoom: Pair<String, Int>?` — Kitchen if present, else first alphabetically

- [ ] **Step 1: Replace the alphabetical-sort-only room selection**

Current code at lines 3270-3272:
```kotlin
val firstRoom = assemblyRooms
    .sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
    .firstOrNull()
```

Replace with:
```kotlin
val firstRoom = assemblyRooms
    .firstOrNull { it.first.equals("Kitchen", ignoreCase = true) }
    ?: assemblyRooms
        .sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
        .firstOrNull()
```

Matches the pattern `resolveSpecialtyThreeDRoom` already uses: `rooms.firstOrNull { it.equals("Kitchen", ignoreCase = true) } ?: rooms.first()`.

- [ ] **Step 2: Build and verify**

```bash
cd C:\Scripts\KKCSheetTracker
.\gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "fix: prefer Kitchen room when opening 3D from jobs list"
```

---

### Task 2: Force fullscreen 3D without clobbering saved split-view state

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt:327-398`

**Interfaces:**
- Consumes: `initialSource: String?`, `initialPaneSource: PaneSource?`, `resumePrefix: String`
- Produces: `fullscreenPane`, `firstPaneSource`, `secondPaneSource` — always 3D-first when `source=3d`; save to prefs is suppressed for the entire 3D session so the user's split-view layout survives

- [ ] **Step 1: Derive a stable `enteredVia3D` flag**

Add right after `val initialPaneSource = parseInitialSource(initialSource)` (line 219):
```kotlin
val enteredVia3D = initialPaneSource == PaneSource.THREE_D
```

- [ ] **Step 2: Force fullscreenPane to FIRST when entered via 3D**

Current code at lines 327-337:
```kotlin
var fullscreenPane by rememberSaveable(initialSource) {
    val saved = prefs.getString("${resumePrefix}_fullscreen", null)
    mutableStateOf(
        runCatching { saved?.let { FullscreenPane.valueOf(it) } }.getOrNull()
            ?: when {
                initialPaneSource == PaneSource.THREE_D -> FullscreenPane.FIRST
                initialLayout == AssemblyViewLayout.SINGLE -> FullscreenPane.FIRST
                else -> FullscreenPane.NONE
            }
    )
}
```

Replace with:
```kotlin
var fullscreenPane by rememberSaveable(initialSource) {
    val saved = if (enteredVia3D) null else prefs.getString("${resumePrefix}_fullscreen", null)
    mutableStateOf(
        runCatching { saved?.let { FullscreenPane.valueOf(it) } }.getOrNull()
            ?: when {
                enteredVia3D -> FullscreenPane.FIRST
                initialLayout == AssemblyViewLayout.SINGLE -> FullscreenPane.FIRST
                else -> FullscreenPane.NONE
            }
    )
}
```

- [ ] **Step 3: Force firstPaneSource and secondPaneSource when entered via 3D**

Current code at lines 346-362:
```kotlin
var firstPaneSource by rememberSaveable(initialSource) {
    val saved = prefs.getString("${resumePrefix}_first_source", null)
    mutableStateOf(
        runCatching { saved?.let { PaneSource.valueOf(it) } }.getOrNull()
            ?: initialPaneSource
            ?: initialFirstPane?.toPaneSource()
            ?: PaneSource.PLANS
    )
}
var secondPaneSource by rememberSaveable(initialSource) {
    val saved = prefs.getString("${resumePrefix}_second_source", null)
    mutableStateOf(
        runCatching { saved?.let { PaneSource.valueOf(it) } }.getOrNull()
            ?: initialSecondPane?.toPaneSource()
            ?: PaneSource.ASSEMBLY
    )
}
```

Replace with:
```kotlin
var firstPaneSource by rememberSaveable(initialSource) {
    val saved = if (enteredVia3D) null else prefs.getString("${resumePrefix}_first_source", null)
    mutableStateOf(
        runCatching { saved?.let { PaneSource.valueOf(it) } }.getOrNull()
            ?: initialPaneSource
            ?: initialFirstPane?.toPaneSource()
            ?: PaneSource.PLANS
    )
}
var secondPaneSource by rememberSaveable(initialSource) {
    val saved = if (enteredVia3D) null else prefs.getString("${resumePrefix}_second_source", null)
    mutableStateOf(
        runCatching { saved?.let { PaneSource.valueOf(it) } }.getOrNull()
            ?: initialSecondPane?.toPaneSource()
            ?: PaneSource.ASSEMBLY
    )
}
```

- [ ] **Step 4: Skip the LaunchedEffect save when entered via 3D**

Current code at lines 390-398:
```kotlin
LaunchedEffect(assemblyPage, plansPage, firstPaneSource, secondPaneSource, fullscreenPane) {
    prefs.edit()
        .putInt("${resumePrefix}_assembly_page", assemblyPage)
        .putInt("${resumePrefix}_plans_page", plansPage)
        .putString("${resumePrefix}_first_source", firstPaneSource.name)
        .putString("${resumePrefix}_second_source", secondPaneSource.name)
        .putString("${resumePrefix}_fullscreen", fullscreenPane.name)
        .apply()
}
```

Replace with:
```kotlin
LaunchedEffect(assemblyPage, plansPage, firstPaneSource, secondPaneSource, fullscreenPane) {
    if (enteredVia3D) return@LaunchedEffect
    prefs.edit()
        .putInt("${resumePrefix}_assembly_page", assemblyPage)
        .putInt("${resumePrefix}_plans_page", plansPage)
        .putString("${resumePrefix}_first_source", firstPaneSource.name)
        .putString("${resumePrefix}_second_source", secondPaneSource.name)
        .putString("${resumePrefix}_fullscreen", fullscreenPane.name)
        .apply()
}
```

This is the critical piece: the `enteredVia3D` flag suppresses the entire save, so whatever the user does during a 3D session (switching rooms, toggling panes, etc.) never overwrites their normal split-view preferences in SharedPreferences.

- [ ] **Step 5: Build and verify**

```bash
cd C:\Scripts\KKCSheetTracker
.\gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt
git commit -m "fix: force fullscreen 3D when opening via source=3d, preserve split-view saved state"
```