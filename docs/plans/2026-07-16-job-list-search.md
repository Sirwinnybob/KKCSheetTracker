# Job List Search Bar Enhancement Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Enhance the job list search interface across all modes (CNC, Hardwoods, Assembly, Specialty) by replacing the ugly on-screen `OutlinedTextField` search box with the built-in, animated `AppScaffold` search bar. One critical difference from the Supply search mode is that the bottom navigation bar icons and text labels will remain full-sized (not minimized) during job searching.

**Architecture:** 
1. Modify `AppScaffold.kt`'s `showDecorations` logic to be active whenever a decoration is present (independent of `minimized`), and ensure that `minIconSize` and `showLabels` respect `minimized` separately, so we can render decorations with full-sized icons and text labels when `minimized` is false.
2. Update `NavGraph.kt` to only set `minimized = true` when in viewer mode or when searching outside the Jobs tab (e.g. Supply tab).
3. Update the four job screens (`JobBrowserScreen`, `AssemblyJobsScreen`, `HardwoodsJobsScreen`, `SpecialtyJobsScreen`) to use `TextFieldValue` state for the search query and assign `LocalNavBarDecoration.current.searchDecoration` via a `SideEffect` to register with `AppScaffold`.
4. Remove the redundant `OutlinedTextField` search boxes from all four job list screens.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Android Navigation

---

### Task 1: Update AppScaffold Layout for Non-Minimized Decorations

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt:364-383`

**Step 1: Update showDecorations and minIconSize**
Modify the logic in `app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt` to allow decorations to show when `minimized` is false, and to keep icons at full size in that state.

Change the initialization of `showDecorations` and `minIconSize`:
```kotlin
    val showExtended    = extendedControls != null
    val hasDecoration   = searchDecoration != null || cncDecoration != null ||
                          specialtyDecoration != null || penDecoration != null
    // Decorations belong to the non-extended bar (can be minimized or full size).
    val showDecorations = !showExtended && hasDecoration
    // Labels only in the roomy full bar (not minimized, not showing extended controls).
    val showLabels      = !minimized && !showExtended

    // Icons morph: 22 (full+labels) → 20 (minimized pill) → 18 (decoration/extended when minimized).
    val minIconSize by animateDpAsState(
        targetValue   = when {
            showExtended    -> 18.dp
            showDecorations && minimized -> 18.dp
            minimized       -> 20.dp
            else            -> 22.dp
        },
        animationSpec = NavSpringDp,
        label         = "navIconSize"
    )
```

**Step 2: Verify the project builds**
Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 2: Update NavGraph Minimization Logic

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:890-894`

**Step 1: Prevent minimization when searching on the Jobs tab**
Update the `minimized` parameter passed to `AppBottomNavBar` inside `NavGraph.kt`. It should be minimized if in a viewer, or when `searchDecoration` is active and we are NOT on the Jobs tab.

Change:
```kotlin
                minimized = isInViewer || navBarDeco.searchDecoration != null,
```
To:
```kotlin
                minimized = isInViewer || (navBarDeco.searchDecoration != null && selectedTab != TopLevelTab.JOBS),
```

**Step 2: Verify the project builds**
Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 3: Replace Search in JobBrowserScreen (CNC Mode)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/browser/JobBrowserScreen.kt`

**Step 1: Update imports**
Ensure the following imports are present at the top of the file:
```kotlin
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalFocusManager
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration
```

**Step 2: Change searchQuery state to TextFieldValue**
Change the local search query state declaration from `String` to `TextFieldValue`:
```kotlin
    var searchQuery by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
```
And inside the `LaunchedEffect(adminMode)`:
```kotlin
    LaunchedEffect(adminMode) {
        if (adminMode) {
            searchQuery = TextFieldValue("")
            sortByName = false
            boardView = false
        }
    }
```

**Step 3: Update filteredJobs calculation**
Change `filteredJobs` to check `searchQuery.text`:
```kotlin
    val filteredJobs = remember(jobs, searchQuery, sortByName, progressVersion) {
        val queryStr = searchQuery.text
        val base = if (queryStr.isBlank()) {
            jobs
        } else {
            jobs.filter { job ->
                job.jobNumber.contains(queryStr, ignoreCase = true) ||
                    job.jobName.contains(queryStr, ignoreCase = true)
            }
        }
        if (sortByName) {
            base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
        } else {
            base // already in production order from listJobs
        }
    }
```

**Step 4: Register searchDecoration via SideEffect**
Add the `SideEffect` and `DisposableEffect` to set `navBarDeco.searchDecoration`:
```kotlin
    val navBarDeco = LocalNavBarDecoration.current
    val focusManager = LocalFocusManager.current
    val currentSearchQuery = searchQuery
    SideEffect {
        if (!adminMode) {
            navBarDeco.searchDecoration = NavBarSearchDecoration(
                searchTextValue    = currentSearchQuery,
                onSearchTextChange = { searchQuery = it },
                onGo               = { focusManager.clearFocus() },
                isPartsEnabled     = false,
                onParts            = {},
                contextLine        = if (currentSearchQuery.text.isNotBlank())
                                         "Filtering jobs by \"${currentSearchQuery.text}\"" else "",
                placeholder        = "Search jobs...",
                showParts          = false,
                onScan             = null
            )
        } else {
            navBarDeco.searchDecoration = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { navBarDeco.searchDecoration = null }
    }
```

**Step 5: Remove the redundant OutlinedTextField**
Remove the `OutlinedTextField` block from the `Column(modifier = Modifier.padding(padding))` layout (lines 394-404).

**Step 6: Update the jobs size text display**
Update:
```kotlin
            Text(
                text = if (searchQuery.text.isBlank()) {
                    "${filteredJobs.size} jobs"
                } else {
                    "Showing ${filteredJobs.size} of ${jobs.size} jobs"
                },
```

**Step 7: Verify compilation**
Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 4: Replace Search in AssemblyJobsScreen

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyJobsScreen.kt`

**Step 1: Update imports**
Ensure the following imports are present:
```kotlin
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalFocusManager
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration
import androidx.compose.runtime.SideEffect
```

**Step 2: Change query state to TextFieldValue**
Modify the local state:
```kotlin
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
```
And inside `LaunchedEffect(adminMode)`:
```kotlin
    LaunchedEffect(adminMode) {
        if (adminMode) {
            query = TextFieldValue("")
            sortByName = false
            boardView = false
        }
    }
```

**Step 3: Update filtered calculation**
Change `filtered` to check `query.text`:
```kotlin
    val filtered = remember(allCards, query, sortByName) {
        val queryStr = query.text
        val base = if (queryStr.isBlank()) {
            allCards
        } else {
            allCards.filter { card ->
                card.jobNumber.contains(queryStr, ignoreCase = true) ||
                    card.jobName.contains(queryStr, ignoreCase = true)
            }
        }
        if (sortByName) {
            base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
        } else {
            base // already in production order
        }
    }
```

**Step 4: Register searchDecoration via SideEffect**
Add the `SideEffect` and `DisposableEffect` to hook up the decoration:
```kotlin
    val navBarDeco = LocalNavBarDecoration.current
    val focusManager = LocalFocusManager.current
    val currentQuery = query
    SideEffect {
        if (!adminMode) {
            navBarDeco.searchDecoration = NavBarSearchDecoration(
                searchTextValue    = currentQuery,
                onSearchTextChange = { query = it },
                onGo               = { focusManager.clearFocus() },
                isPartsEnabled     = false,
                onParts            = {},
                contextLine        = if (currentQuery.text.isNotBlank())
                                         "Filtering jobs by \"${currentQuery.text}\"" else "",
                placeholder        = "Search jobs...",
                showParts          = false,
                onScan             = null
            )
        } else {
            navBarDeco.searchDecoration = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { navBarDeco.searchDecoration = null }
    }
```

**Step 5: Remove the redundant OutlinedTextField**
Remove the `OutlinedTextField` block from the UI layout.

**Step 6: Update the jobs size text display**
Update the text checks to refer to `query.text.isBlank()` instead of `query.isBlank()`.

**Step 7: Verify compilation**
Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 5: Replace Search in HardwoodsJobsScreen

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobsScreen.kt`

**Step 1: Update imports**
Ensure the following imports are present:
```kotlin
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalFocusManager
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration
import androidx.compose.runtime.SideEffect
```

**Step 2: Change query state to TextFieldValue**
Modify local state:
```kotlin
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
```
And inside `LaunchedEffect(adminMode)`:
```kotlin
    LaunchedEffect(adminMode) {
        if (adminMode) {
            query = TextFieldValue("")
            sortByName = false
            boardView = false
        }
    }
```

**Step 3: Update filtered calculation**
Change `filtered` to check `query.text`:
```kotlin
    val filtered = remember(jobs, query, sortByName) {
        val queryStr = query.text
        val base = if (queryStr.isBlank()) jobs else jobs.filter {
            it.jobNumber.contains(queryStr, ignoreCase = true) ||
                it.jobName.contains(queryStr, ignoreCase = true) ||
                it.folderName.contains(queryStr, ignoreCase = true)
        }
        if (sortByName) {
            base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.folderName })
        } else {
            base // already in production order
        }
    }
```

**Step 4: Register searchDecoration via SideEffect**
Add the `SideEffect` and `DisposableEffect` to hook up the decoration:
```kotlin
    val navBarDeco = LocalNavBarDecoration.current
    val focusManager = LocalFocusManager.current
    val currentQuery = query
    SideEffect {
        if (!adminMode) {
            navBarDeco.searchDecoration = NavBarSearchDecoration(
                searchTextValue    = currentQuery,
                onSearchTextChange = { query = it },
                onGo               = { focusManager.clearFocus() },
                isPartsEnabled     = false,
                onParts            = {},
                contextLine        = if (currentQuery.text.isNotBlank())
                                         "Filtering jobs by \"${currentQuery.text}\"" else "",
                placeholder        = "Search jobs...",
                showParts          = false,
                onScan             = null
            )
        } else {
            navBarDeco.searchDecoration = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { navBarDeco.searchDecoration = null }
    }
```

**Step 5: Remove the redundant OutlinedTextField**
Remove the `OutlinedTextField` block from the UI layout.

**Step 6: Update any query text display checks**
Update the text checks to refer to `query.text.isBlank()` or `query.text` as appropriate.

**Step 7: Verify compilation**
Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 6: Replace Search in SpecialtyJobsScreen

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobsScreen.kt`

**Step 1: Update imports**
Ensure the following imports are present:
```kotlin
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalFocusManager
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.NavBarSearchDecoration
import androidx.compose.runtime.SideEffect
```

**Step 2: Change query state to TextFieldValue**
Modify local state:
```kotlin
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
```
And inside `LaunchedEffect(adminMode)`:
```kotlin
    LaunchedEffect(adminMode) {
        if (adminMode) {
            query = TextFieldValue("")
            sortByName = false
            boardView = false
        }
    }
```

**Step 3: Update filtered calculation**
Change `filtered` to check `query.text`:
```kotlin
    val filtered = remember(cards, query) {
        val queryStr = query.text
        if (queryStr.isBlank()) cards else cards.filter {
            it.jobNumber.contains(queryStr, ignoreCase = true) ||
                it.jobName.contains(queryStr, ignoreCase = true)
        }
    }
```

**Step 4: Register searchDecoration via SideEffect**
Add the `SideEffect` and `DisposableEffect` to hook up the decoration:
```kotlin
    val navBarDeco = LocalNavBarDecoration.current
    val focusManager = LocalFocusManager.current
    val currentQuery = query
    SideEffect {
        if (!adminMode) {
            navBarDeco.searchDecoration = NavBarSearchDecoration(
                searchTextValue    = currentQuery,
                onSearchTextChange = { query = it },
                onGo               = { focusManager.clearFocus() },
                isPartsEnabled     = false,
                onParts            = {},
                contextLine        = if (currentQuery.text.isNotBlank())
                                         "Filtering jobs by \"${currentQuery.text}\"" else "",
                placeholder        = "Search jobs...",
                showParts          = false,
                onScan             = null
            )
        } else {
            navBarDeco.searchDecoration = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { navBarDeco.searchDecoration = null }
    }
```

**Step 5: Remove the redundant OutlinedTextField**
Remove the `OutlinedTextField` block from the UI layout.

**Step 6: Update any query text display checks**
Update the text checks to refer to `query.text.isBlank()` or `query.text` as appropriate.

**Step 7: Verify compilation and run tests**
Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

Run: `.\gradlew.bat testDebugUnitTest`
Expected: ALL TESTS PASS

---

## Verification Plan

### Automated Tests
- Build verification: `.\gradlew.bat assembleDebug`
- Unit tests: `.\gradlew.bat testDebugUnitTest`

### Manual Verification
- Deploy to an Android device/emulator:
  `adb install -r app\build\outputs\apk\debug\app-debug.apk`
- Navigate to the Jobs tab.
- Click in the search field in the bottom bar, type a query, verify the list filters in real-time, and verify the bottom navigation icons/labels stay full-size.
- Switch to different job modes (CNC, Hardwoods, Assembly, Specialty) and verify search works and has smooth slide/expand animations.
- Verify transitioning to other tabs (Dashboard, Supply, Settings, etc.) clears/collapses the search bar.
