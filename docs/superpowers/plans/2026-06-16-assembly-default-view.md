# Assembly Viewer Default View — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-device, Assembly-mode setting that controls layout (split / single), pane assignments (Assembly / Plans / Delivery / 3D / Checklist), and initial UI-hidden state when a user taps a job in the Assembly Jobs tab.

**Architecture:** New DataStore-backed config (`AssemblyViewerDefaultsStore`) mirrors the existing `TimecardServerConfig` pattern. Two new optional nav arguments on `assembly/viewer/...` route carry the resolved defaults. A new settings sub-page lets the user edit them. The viewer reads the nav args at first composition and applies them to its existing `firstPaneSource` / `secondPaneSource` / `fullscreenPane` / `showUi` state. All deep-link entry points (3D from JobDetail, Specialty's "Open Split View", cabinet jumps) keep working unchanged because they pass none of the new args.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX DataStore Preferences, AndroidX Navigation Compose.

**Spec:** [docs/superpowers/specs/2026-06-16-assembly-default-view-design.md](../specs/2026-06-16-assembly-default-view-design.md)

**Testing strategy:** The codebase has no automated UI/Compose tests for these screens. Verification per task is: (a) Gradle assemble succeeds (`.\gradlew.bat assembleDebug`), and (b) a manual sanity step. The final task's verification step is the full manual test plan from the spec.

---

## File Map

**New files:**
- `app/src/main/java/com/kkc/sheettracker/data/AssemblyViewerDefaults.kt` — enums + data class
- `app/src/main/java/com/kkc/sheettracker/data/AssemblyViewerDefaultsStore.kt` — DataStore wrapper
- `app/src/main/java/com/kkc/sheettracker/ui/settings/AssemblyViewerDefaultsScreen.kt` — settings sub-page

**Modified files:**
- `app/src/main/java/com/kkc/sheettracker/MainActivity.kt` — instantiate store, pass through navigation
- `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt` — extend `assemblyViewerRoute`, route composable, `WorkMode.ASSEMBLY` job-tap handlers (two places), add settings sub-route, thread store down
- `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt` — accept four new optional params, apply them at state init
- `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt` — add row that navigates to `settings/assemblyViewerDefaults`

---

## Task 1: Data model + DataStore

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/AssemblyViewerDefaults.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/data/AssemblyViewerDefaultsStore.kt`

- [ ] **Step 1: Create the data model file**

Create `app/src/main/java/com/kkc/sheettracker/data/AssemblyViewerDefaults.kt`:

```kotlin
package com.kkc.sheettracker.data

enum class AssemblyViewLayout { SPLIT, SINGLE }

enum class AssemblyPaneView { ASSEMBLY, PLANS, DELIVERY, THREE_D, CHECKLIST }

data class AssemblyViewerDefaults(
    val layout: AssemblyViewLayout = AssemblyViewLayout.SPLIT,
    val firstPane: AssemblyPaneView = AssemblyPaneView.PLANS,
    val secondPane: AssemblyPaneView = AssemblyPaneView.ASSEMBLY,
    val hideUiOnOpen: Boolean = false,
)
```

`hideUiOnOpen` corresponds to the spec's "fullscreen" toggle. It controls the initial value of `showUi` in `AssemblyViewerScreen` (system bars are already always hidden by the viewer's existing `ImmersiveSystemBars()` call — the user-visible toggle is the in-app top bar / bottom nav / floating controls).

- [ ] **Step 2: Create the DataStore wrapper**

Create `app/src/main/java/com/kkc/sheettracker/data/AssemblyViewerDefaultsStore.kt`:

```kotlin
package com.kkc.sheettracker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.assemblyViewerDefaultsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "assembly_viewer_defaults"
)

private object AssemblyViewerDefaultsKeys {
    val layout = stringPreferencesKey("layout")
    val firstPane = stringPreferencesKey("first_pane")
    val secondPane = stringPreferencesKey("second_pane")
    val hideUiOnOpen = booleanPreferencesKey("hide_ui_on_open")
}

class AssemblyViewerDefaultsStore(
    private val dataStore: DataStore<Preferences>,
) {
    val defaults: Flow<AssemblyViewerDefaults> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs ->
            AssemblyViewerDefaults(
                layout = prefs[AssemblyViewerDefaultsKeys.layout]
                    ?.let { runCatching { AssemblyViewLayout.valueOf(it) }.getOrNull() }
                    ?: AssemblyViewLayout.SPLIT,
                firstPane = prefs[AssemblyViewerDefaultsKeys.firstPane]
                    ?.let { runCatching { AssemblyPaneView.valueOf(it) }.getOrNull() }
                    ?: AssemblyPaneView.PLANS,
                secondPane = prefs[AssemblyViewerDefaultsKeys.secondPane]
                    ?.let { runCatching { AssemblyPaneView.valueOf(it) }.getOrNull() }
                    ?: AssemblyPaneView.ASSEMBLY,
                hideUiOnOpen = prefs[AssemblyViewerDefaultsKeys.hideUiOnOpen] ?: false,
            )
        }

    suspend fun current(): AssemblyViewerDefaults = defaults.first()

    suspend fun setLayout(layout: AssemblyViewLayout) {
        dataStore.edit { it[AssemblyViewerDefaultsKeys.layout] = layout.name }
    }

    suspend fun setFirstPane(view: AssemblyPaneView) {
        dataStore.edit { it[AssemblyViewerDefaultsKeys.firstPane] = view.name }
    }

    suspend fun setSecondPane(view: AssemblyPaneView) {
        dataStore.edit { it[AssemblyViewerDefaultsKeys.secondPane] = view.name }
    }

    suspend fun setHideUiOnOpen(value: Boolean) {
        dataStore.edit { it[AssemblyViewerDefaultsKeys.hideUiOnOpen] = value }
    }

    companion object {
        fun create(context: Context): AssemblyViewerDefaultsStore =
            AssemblyViewerDefaultsStore(context.assemblyViewerDefaultsDataStore)
    }
}
```

The `secondPane` value is read back even when the user has SINGLE selected — toggling SPLIT → SINGLE → SPLIT preserves the prior right-pane choice.

- [ ] **Step 3: Verify it builds**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. No new warnings beyond pre-existing ones.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/AssemblyViewerDefaults.kt app/src/main/java/com/kkc/sheettracker/data/AssemblyViewerDefaultsStore.kt
git commit -m "feat(assembly): add AssemblyViewerDefaultsStore"
```

---

## Task 2: Instantiate store + thread through MainActivity / NavGraph

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

This task only adds the store reference and passes it down. It does NOT yet read defaults at the job-tap site — that's Task 5. After this task the store exists and is reachable from `NavGraph`'s `WorkMode.ASSEMBLY` block, but nothing consumes it yet, so behavior is unchanged.

- [ ] **Step 1: Instantiate store in MainActivity**

In `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`, find the line that creates `TimecardServerConfig`:

```kotlin
val timecardConfig = remember { TimecardServerConfig.create(context) }
```

Add immediately after it:

```kotlin
val assemblyViewerDefaultsStore = remember { AssemblyViewerDefaultsStore.create(context) }
```

Add the import at the top of the file:

```kotlin
import com.kkc.sheettracker.data.AssemblyViewerDefaultsStore
```

- [ ] **Step 2: Pass the store into the NavGraph entry point**

Still in `MainActivity.kt`, locate the call to the top-level `NavGraph(...)` composable (or whatever function in `NavGraph.kt` is invoked from `MainActivity`). Add `assemblyViewerDefaultsStore = assemblyViewerDefaultsStore` to the argument list, matching the call style of the existing `timecardConfig =` argument.

- [ ] **Step 3: Add the parameter to the NavGraph function signature**

In `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`, find the function called by MainActivity (search for the signature that already accepts `timecardConfig: TimecardServerConfig`). Add a parameter:

```kotlin
assemblyViewerDefaultsStore: AssemblyViewerDefaultsStore,
```

Add the import:

```kotlin
import com.kkc.sheettracker.data.AssemblyViewerDefaultsStore
```

Do NOT yet wire the store into any composable destination. Just adding the parameter.

- [ ] **Step 4: Verify build**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/MainActivity.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "chore(assembly): thread AssemblyViewerDefaultsStore through MainActivity"
```

---

## Task 3: Extend the viewer route + composable to carry new args

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

Extend `assemblyViewerRoute` and the route composable so they can carry four optional nav arguments. Existing deep-link callers will pass none, so the route string is unchanged for them; only the Assembly Jobs onJobClick (Task 5) will pass them.

- [ ] **Step 1: Extend the route builder**

In `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`, replace the existing `assemblyViewerRoute` (currently around line 2926):

```kotlin
private fun assemblyViewerRoute(
    jobFolderName: String,
    assemblyPage: Int,
    plansPage: Int,
    source: String? = null,
    cabinet: String? = null,
    room: String? = null,
    layout: AssemblyViewLayout? = null,
    firstPane: AssemblyPaneView? = null,
    secondPane: AssemblyPaneView? = null,
    hideUiOnOpen: Boolean? = null,
): String {
    val base = "assembly/viewer/${URLEncoder.encode(jobFolderName, "UTF-8")}/$assemblyPage/$plansPage"
    val query = buildList {
        if (!source.isNullOrBlank()) add("source=${URLEncoder.encode(source, "UTF-8")}")
        if (!cabinet.isNullOrBlank()) add("cab=${URLEncoder.encode(cabinet, "UTF-8")}")
        if (!room.isNullOrBlank()) add("room=${URLEncoder.encode(room, "UTF-8")}")
        if (layout != null) add("layout=${layout.name}")
        if (firstPane != null) add("first=${firstPane.name}")
        if (secondPane != null) add("second=${secondPane.name}")
        if (hideUiOnOpen != null) add("hideUi=${if (hideUiOnOpen) 1 else 0}")
    }
    return if (query.isEmpty()) base else "$base?${query.joinToString("&")}"
}
```

Add imports near the top of the file:

```kotlin
import com.kkc.sheettracker.data.AssemblyPaneView
import com.kkc.sheettracker.data.AssemblyViewLayout
```

- [ ] **Step 2: Extend the route composable (regular nav)**

In the same file, find the `composable(...)` for the assembly viewer route (around line 1499). Update the route string and the `arguments` list to declare the four new query params. Also extract them and pass them into `AssemblyViewerScreen`:

```kotlin
composable(
    "assembly/viewer/{folderName}/{startPageAssembly}/{startPagePlans}?source={source}&cab={cab}&room={room}&layout={layout}&first={first}&second={second}&hideUi={hideUi}",
    arguments = listOf(
        navArgument("folderName") { type = NavType.StringType },
        navArgument("startPageAssembly") { type = NavType.IntType },
        navArgument("startPagePlans") { type = NavType.IntType },
        navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null },
        navArgument("cab") { type = NavType.StringType; nullable = true; defaultValue = null },
        navArgument("room") { type = NavType.StringType; nullable = true; defaultValue = null },
        navArgument("layout") { type = NavType.StringType; nullable = true; defaultValue = null },
        navArgument("first") { type = NavType.StringType; nullable = true; defaultValue = null },
        navArgument("second") { type = NavType.StringType; nullable = true; defaultValue = null },
        navArgument("hideUi") { type = NavType.StringType; nullable = true; defaultValue = null },
    ),
) { backStack ->
    val jobFolderName = URLDecoder.decode(backStack.arguments?.getString("folderName") ?: "", "UTF-8")
    LaunchedEffect(jobFolderName) { assemblyScanCoordinator.refreshJobOnOpen(jobFolderName) }
    val startPageAssembly = backStack.arguments?.getInt("startPageAssembly") ?: 1
    val startPagePlans = backStack.arguments?.getInt("startPagePlans") ?: 1
    val initialSource = backStack.arguments?.getString("source")?.let { URLDecoder.decode(it, "UTF-8") }
    val initialCabinet = backStack.arguments?.getString("cab")?.let { URLDecoder.decode(it, "UTF-8") }
    val initialRoom = backStack.arguments?.getString("room")?.let { URLDecoder.decode(it, "UTF-8") }
    val initialLayout = backStack.arguments?.getString("layout")
        ?.let { runCatching { AssemblyViewLayout.valueOf(it) }.getOrNull() }
    val initialFirstPane = backStack.arguments?.getString("first")
        ?.let { runCatching { AssemblyPaneView.valueOf(it) }.getOrNull() }
    val initialSecondPane = backStack.arguments?.getString("second")
        ?.let { runCatching { AssemblyPaneView.valueOf(it) }.getOrNull() }
    val initialHideUi = backStack.arguments?.getString("hideUi") == "1"
    val refreshGeneration = assemblyScanCoordinator.state.collectAsState().value.snapshot.generation
    val isClockedInHere = clockInState.snapshot.isActive &&
        clockInState.snapshot.folderName == jobFolderName
    AssemblyViewerScreen(
        jobRepository = jobRepository,
        assemblyStateStore = assemblyStateStore,
        specialtyStateStore = specialtyStateStore,
        jobFolderName = jobFolderName,
        basePath = basePath,
        startPageAssembly = startPageAssembly,
        startPagePlans = startPagePlans,
        initialSource = initialSource,
        initialCabinet = initialCabinet,
        initialRoom = initialRoom,
        initialLayout = initialLayout,
        initialFirstPane = initialFirstPane,
        initialSecondPane = initialSecondPane,
        initialHideUi = initialHideUi,
        refreshGeneration = refreshGeneration,
        isDarkTheme = isDarkTheme,
        isClockedInHere = isClockedInHere,
        onClockIn = { jobNumber, jobName -> onClockIn(jobNumber, jobName, jobFolderName, "assembly") },
        onLeaveWhileClockedIn = { if (isClockedInHere) clockInState.triggerPrompt() },
        onBack = { navController.popBackStack() },
        onUiVisibilityChanged = onUiVisibilityChanged,
    )
}
```

- [ ] **Step 3: Repeat the route composable update for the search-tab variant**

There's a second `composable("assembly/viewer/...")` block around line 2562 (inside `SearchTabHost`). Apply the **same** route string, `arguments` list, and parameter extraction as Step 2. Pass the four new params into `AssemblyViewerScreen` the same way.

- [ ] **Step 4: Verify build**

Note: the new `initial*` parameters on `AssemblyViewerScreen` don't exist yet — this step is expected to FAIL to compile. That's OK; it confirms the next task is needed. If it succeeds (because someone already added params), great.

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: FAIL with "unresolved reference: initialLayout" (or similar). This validates Task 4 is required next.

- [ ] **Step 5: Do NOT commit yet**

We'll commit after Task 4 since the code is intentionally broken between these two tasks.

---

## Task 4: Apply the defaults inside `AssemblyViewerScreen`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt`

- [ ] **Step 1: Add the four new params to the composable signature**

In `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt`, locate the `AssemblyViewerScreen` composable (around line 154). Add four new optional parameters right after `initialRoom: String? = null,`:

```kotlin
initialLayout: AssemblyViewLayout? = null,
initialFirstPane: AssemblyPaneView? = null,
initialSecondPane: AssemblyPaneView? = null,
initialHideUi: Boolean = false,
```

Add imports at the top of the file:

```kotlin
import com.kkc.sheettracker.data.AssemblyPaneView
import com.kkc.sheettracker.data.AssemblyViewLayout
```

- [ ] **Step 2: Add a mapping helper from `AssemblyPaneView` to `PaneSource`**

Inside the same file, near the top of the `AssemblyViewerScreen` function body (next to the existing `parseInitialSource` function around line 187), add:

```kotlin
fun AssemblyPaneView.toPaneSource(): PaneSource = when (this) {
    AssemblyPaneView.ASSEMBLY -> PaneSource.ASSEMBLY
    AssemblyPaneView.PLANS -> PaneSource.PLANS
    AssemblyPaneView.DELIVERY -> PaneSource.DELIVERY
    AssemblyPaneView.THREE_D -> PaneSource.THREE_D
    AssemblyPaneView.CHECKLIST -> PaneSource.CHECKLIST
}
```

- [ ] **Step 3: Inject `initialFirstPane` into the `firstPaneSource` init**

Find the existing block (around line 315):

```kotlin
var firstPaneSource by rememberSaveable(initialSource) {
    val saved = prefs.getString("${resumePrefix}_first_source", null)
    mutableStateOf(
        runCatching { saved?.let { PaneSource.valueOf(it) } }.getOrNull()
            ?: (initialPaneSource ?: PaneSource.PLANS)
    )
}
```

Replace with:

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
```

Priority: explicit `source` deep-link query param > saved per-job state > new default from settings > hardcoded fallback.

- [ ] **Step 4: Inject `initialSecondPane` into the `secondPaneSource` init**

Find (around line 322):

```kotlin
var secondPaneSource by rememberSaveable { mutableStateOf(PaneSource.ASSEMBLY) }
```

Replace with:

```kotlin
var secondPaneSource by rememberSaveable {
    mutableStateOf(initialSecondPane?.toPaneSource() ?: PaneSource.ASSEMBLY)
}
```

- [ ] **Step 5: Apply SINGLE layout to `fullscreenPane`**

Find (around line 300):

```kotlin
var fullscreenPane by rememberSaveable(initialSource) {
    val saved = prefs.getString("${resumePrefix}_fullscreen", null)
    mutableStateOf(
        runCatching { saved?.let { FullscreenPane.valueOf(it) } }.getOrNull()
            ?: if (initialPaneSource == PaneSource.THREE_D) FullscreenPane.FIRST else FullscreenPane.NONE
    )
}
```

Replace with:

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

- [ ] **Step 6: Apply `initialHideUi` to `showUi`**

Find (around line 311):

```kotlin
var showUi by rememberSaveable { mutableStateOf(true) }
```

Replace with:

```kotlin
var showUi by rememberSaveable { mutableStateOf(!initialHideUi) }
```

- [ ] **Step 7: Verify build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL. The four new viewer parameters are now defined, so Task 3's calls compile.

- [ ] **Step 8: Commit Tasks 3 + 4 together**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt
git commit -m "feat(assembly): accept default layout/panes/hide-ui via nav args"
```

---

## Task 5: Read defaults at the Assembly Jobs job-tap site

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

After this task, tapping a job in Assembly mode will read the stored defaults and pass them into `assemblyViewerRoute`. Until the settings UI ships in Task 6 the user can't change them, but the data flow is real and exercised by the data-class defaults (which match today's behavior).

- [ ] **Step 1: Update the regular-nav `WorkMode.ASSEMBLY` job-tap handler**

In `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt` around line 1082, replace:

```kotlin
onJobClick = { card ->
    navController.navigate(assemblyViewerRoute(card.folderName, 1, 1)) {
        launchSingleTop = true
    }
},
```

with:

```kotlin
onJobClick = { card ->
    coroutineScope.launch {
        val d = assemblyViewerDefaultsStore.current()
        navController.navigate(
            assemblyViewerRoute(
                jobFolderName = card.folderName,
                assemblyPage = 1,
                plansPage = 1,
                layout = d.layout,
                firstPane = d.firstPane,
                secondPane = d.secondPane,
                hideUiOnOpen = d.hideUiOnOpen,
            )
        ) { launchSingleTop = true }
    }
},
```

You will need a `coroutineScope` in scope. If one doesn't already exist near this block (the function containing the `NavHost`), add at the top of that function:

```kotlin
val coroutineScope = rememberCoroutineScope()
```

Also add imports if needed:

```kotlin
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
```

- [ ] **Step 2: Repeat for the search-tab variant**

In the same file around line 2152 there is a second `WorkMode.ASSEMBLY -> AssemblyJobsScreen(...)` with its own `onJobClick`. Apply the identical replacement. Reuse the existing `coroutineScope` in that function or add one as in Step 1.

- [ ] **Step 3: Verify build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Sanity check on a tablet (or emulator)**

Install: `adb install -r app\build\outputs\apk\debug\app-debug.apk`

Manual:
1. Switch to Assembly mode.
2. Tap any job in the Jobs tab.
3. Verify the viewer opens with Plans (left) + Assembly (right), top bar / bottom nav visible — i.e. **identical to current behavior** since no settings UI exists yet to change the defaults.

If anything differs, the defaults wiring is wrong; debug before continuing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat(assembly): apply viewer defaults at job-tap site"
```

---

## Task 6: Settings sub-page UI + route + entry row

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/settings/AssemblyViewerDefaultsScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Create the settings sub-page**

Create `app/src/main/java/com/kkc/sheettracker/ui/settings/AssemblyViewerDefaultsScreen.kt`:

```kotlin
package com.kkc.sheettracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.AssemblyPaneView
import com.kkc.sheettracker.data.AssemblyViewLayout
import com.kkc.sheettracker.data.AssemblyViewerDefaults
import com.kkc.sheettracker.data.AssemblyViewerDefaultsStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssemblyViewerDefaultsScreen(
    store: AssemblyViewerDefaultsStore,
    onBack: () -> Unit,
) {
    val defaults by store.defaults.collectAsState(initial = AssemblyViewerDefaults())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assembly Viewer Defaults") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionLabel("Layout")
            Column(Modifier.selectableGroup()) {
                LayoutRadio(
                    label = "Split view",
                    selected = defaults.layout == AssemblyViewLayout.SPLIT,
                    onSelect = { scope.launch { store.setLayout(AssemblyViewLayout.SPLIT) } },
                )
                LayoutRadio(
                    label = "Single view",
                    selected = defaults.layout == AssemblyViewLayout.SINGLE,
                    onSelect = { scope.launch { store.setLayout(AssemblyViewLayout.SINGLE) } },
                )
            }

            SectionLabel("Panes")
            when (defaults.layout) {
                AssemblyViewLayout.SPLIT -> {
                    PaneDropdown(
                        label = "Left pane",
                        current = defaults.firstPane,
                        onSelect = { scope.launch { store.setFirstPane(it) } },
                    )
                    PaneDropdown(
                        label = "Right pane",
                        current = defaults.secondPane,
                        onSelect = { scope.launch { store.setSecondPane(it) } },
                    )
                }
                AssemblyViewLayout.SINGLE -> {
                    PaneDropdown(
                        label = "View",
                        current = defaults.firstPane,
                        onSelect = { scope.launch { store.setFirstPane(it) } },
                    )
                }
            }

            SectionLabel("Display")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Open in fullscreen (hide UI)", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = defaults.hideUiOnOpen,
                    onCheckedChange = { value ->
                        scope.launch { store.setHideUiOnOpen(value) }
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun LayoutRadio(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.height(0.dp))
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun PaneDropdown(
    label: String,
    current: AssemblyPaneView,
    onSelect: (AssemblyPaneView) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { expanded = true }) {
            Text(current.displayName())
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AssemblyPaneView.values().forEach { view ->
                DropdownMenuItem(
                    text = { Text(view.displayName()) },
                    onClick = {
                        expanded = false
                        onSelect(view)
                    },
                )
            }
        }
    }
}

private fun AssemblyPaneView.displayName(): String = when (this) {
    AssemblyPaneView.ASSEMBLY -> "Assembly"
    AssemblyPaneView.PLANS -> "Plans"
    AssemblyPaneView.DELIVERY -> "Delivery"
    AssemblyPaneView.THREE_D -> "3D"
    AssemblyPaneView.CHECKLIST -> "Checklist"
}
```

- [ ] **Step 2: Add the nav route**

In `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`, inside the main `NavHost` block (the same one that contains the `"assembly/viewer/..."` composable), add a new composable destination:

```kotlin
composable("settings/assemblyViewerDefaults") {
    AssemblyViewerDefaultsScreen(
        store = assemblyViewerDefaultsStore,
        onBack = { navController.popBackStack() },
    )
}
```

Add the import:

```kotlin
import com.kkc.sheettracker.ui.settings.AssemblyViewerDefaultsScreen
```

If the search-tab `NavHost` also exposes a settings entry, decide whether the sub-page should also be reachable from there. Default: only register in the primary `NavHost` — the row in `SettingsScreen` is what triggers navigation, and `SettingsScreen` lives on the primary nav stack.

- [ ] **Step 3: Add the row in `SettingsScreen`**

In `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt`, add a new parameter:

```kotlin
onOpenAssemblyViewerDefaults: () -> Unit = {},
```

Inside the screen body, in a sensible spot (e.g. near other navigation rows or near the work-mode / theme controls), add a tappable row:

```kotlin
androidx.compose.material3.ListItem(
    headlineContent = { Text("Assembly viewer defaults") },
    supportingContent = { Text("Layout, panes, fullscreen") },
    modifier = Modifier.clickable { onOpenAssemblyViewerDefaults() },
)
```

Add imports if missing:

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
```

If the existing screen uses a different row style (cards, plain `Surface(onClick = ...)`, etc.), match that style instead — the substance is "a tappable row with the title and a one-liner that fires `onOpenAssemblyViewerDefaults`".

- [ ] **Step 4: Wire the navigation handler through to `SettingsScreen`**

In `NavGraph.kt`, find the `SettingsScreen(...)` call site(s). There are two (around line 1642 and 2712). At each, add:

```kotlin
onOpenAssemblyViewerDefaults = {
    navController.navigate("settings/assemblyViewerDefaults") {
        launchSingleTop = true
    }
},
```

- [ ] **Step 5: Verify build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Full manual test plan**

Install: `adb install -r app\build\outputs\apk\debug\app-debug.apk`

Run each of these and confirm:

1. **Fresh defaults round-trip.** Clear DataStore (or first install). Switch to Assembly mode → tap any job → opens Split (Plans left, Assembly right) with UI visible. ✓
2. **Single + 3D + hide UI.** Settings → "Assembly viewer defaults" → set Single, View = 3D, toggle "Open in fullscreen". Back twice. Tap an Assembly job. → Opens single 3D pane, in-app UI hidden (tap to reveal). ✓
3. **Split with Checklist + Delivery.** Set Split, Left = Checklist, Right = Delivery. Tap a job. → Both panes correct. ✓
4. **Toggle Single ↔ Split preserves right pane.** Set Split, Left = Plans, Right = Delivery. Switch to Single. Switch back to Split. → Right dropdown still shows Delivery. ✓
5. **JobDetail "Open 3D" unchanged.** In CNC mode, open a job's detail, tap "Open 3D" → still opens 3D pane regardless of the Assembly default. ✓
6. **Specialty "Open Split View" unchanged.** In Specialty mode, open a job detail, tap "Open Split View" → still opens the legacy Plans+Assembly split. ✓
7. **Cabinet jump unchanged.** From a CNC JobDetail with a cabinet, jump to a cabinet → still opens the viewer with that cabinet, regardless of the Assembly default. ✓
8. **Restart persists.** Kill the app, relaunch, tap an Assembly job → still uses the saved defaults. ✓

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/settings/AssemblyViewerDefaultsScreen.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt
git commit -m "feat(settings): Assembly Viewer Defaults sub-page"
```

---

## Self-Review Checklist (controller, before dispatching)

- ✅ Spec coverage: data model (T1), store (T1), settings UI (T6), route extension (T3), viewer consumption (T4), job-tap default read (T5), deep-link non-interference (T3+T5 only touch the Assembly Jobs `onJobClick`).
- ✅ Placeholder scan: every step has concrete code or concrete commands.
- ✅ Type consistency: `AssemblyViewLayout`, `AssemblyPaneView`, `AssemblyViewerDefaults`, `AssemblyViewerDefaultsStore`, `toPaneSource()` used identically across tasks. `hideUiOnOpen` consistent between data class, store keys, route arg, and viewer param.
- ✅ Edge case: SPLIT/SINGLE toggle preserves `secondPane` (Task 1 store keeps it; Task 6 UI does not clear it on toggle).
- ✅ Edge case: 3D default with no rooms — existing viewer fallback logic untouched.
