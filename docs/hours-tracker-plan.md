# Hours Tracker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a full employee hours-tracking (clock in/out) experience accessible via a dedicated bottom navbar button, with name-based auto-login from settings or a manual name/PIN dialog when no name is configured.

**Architecture:** File-based `HoursStore` writes daily JSON files to `{basePath}/HoursData/YYYY-MM-DD.json` (synced across tablets via Syncthing). Auth is session-level: if `employee_name` is set in SharedPreferences the app auto-logs in; otherwise a name/PIN dialog is shown before entering the tracker. Navigation follows the existing multi-stack + legacy-stack dual pattern used throughout the app.

**Tech Stack:** Kotlin, Jetpack Compose, Gson (already a dependency), JUnit 4 for tests, SharedPreferences for employee name setting, file-based JSON for hours data.

---

## File Map

### New files
| Path | Responsibility |
|------|---------------|
| `app/src/main/java/com/kkc/sheettracker/data/models/HoursEntry.kt` | Data class for a single clock-in/out record |
| `app/src/main/java/com/kkc/sheettracker/data/models/HoursDayLog.kt` | Container for all entries in a day (JSON root) |
| `app/src/main/java/com/kkc/sheettracker/data/HoursStore.kt` | File-based persistence: clock in, clock out, read entries |
| `app/src/main/java/com/kkc/sheettracker/ui/hours/HoursLoginDialog.kt` | Modal dialog: prompts for name/PIN when no employee_name is set |
| `app/src/main/java/com/kkc/sheettracker/ui/hours/HoursTrackerScreen.kt` | Full hours tracker UI screen |
| `app/src/test/java/com/kkc/sheettracker/data/HoursStoreTest.kt` | Unit tests for HoursStore |

### Modified files
| Path | What changes |
|------|-------------|
| `app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt` | Add `HOURS` to `NavDestination` enum |
| `app/src/main/java/com/kkc/sheettracker/navigation/NavigationCoordinator.kt` | Add `HOURS` to `TopLevelTab` enum and `fromDestination`/`toDestination` |
| `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt` | Add `HoursTabHost`, wire it in both multi-stack and legacy-stack, pass `employeeName`/`hoursStore` |
| `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt` | Add employee name field to Tablet section |
| `app/src/main/java/com/kkc/sheettracker/MainActivity.kt` | Read/write `employee_name` pref; pass `employeeName` and `onEmployeeNameChanged` to `AppNavigation` |

---

## Task 1: Data model — HoursEntry and HoursDayLog

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/models/HoursEntry.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/data/models/HoursDayLog.kt`

- [ ] **Step 1: Write HoursEntry.kt**

```kotlin
package com.kkc.sheettracker.data.models

data class HoursEntry(
    val id: String,
    val employeeName: String,
    val clockInMs: Long,
    val clockOutMs: Long?,
    val tabletId: String
)
```

- [ ] **Step 2: Write HoursDayLog.kt**

```kotlin
package com.kkc.sheettracker.data.models

data class HoursDayLog(
    val date: String,              // "YYYY-MM-DD"
    val entries: List<HoursEntry> = emptyList()
)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/models/HoursEntry.kt \
        app/src/main/java/com/kkc/sheettracker/data/models/HoursDayLog.kt
git commit -m "feat: add HoursEntry and HoursDayLog data models"
```

---

## Task 2: HoursStore — file persistence

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/HoursStore.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/data/HoursStoreTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HoursEntry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class HoursStoreTest {

    private fun tempBaseDir(): File = Files.createTempDirectory("hours_test").toFile()

    @Test
    fun clockIn_createsEntryWithNullClockOut() {
        val baseDir = tempBaseDir()
        val store = HoursStore(baseDir, "tablet-1")
        val date = LocalDate.of(2026, 5, 12)

        val entry = store.clockIn("Alice", date)

        assertTrue(entry.clockOutMs == null)
        assertEquals("Alice", entry.employeeName)
        assertEquals("tablet-1", entry.tabletId)
    }

    @Test
    fun clockOut_setsClockOutMs() {
        val baseDir = tempBaseDir()
        val store = HoursStore(baseDir, "tablet-1")
        val date = LocalDate.of(2026, 5, 12)

        val entry = store.clockIn("Alice", date)
        val updated = store.clockOut(entry.id, date)

        assertNotNull(updated)
        assertNotNull(updated!!.clockOutMs)
        assertTrue(updated.clockOutMs!! >= entry.clockInMs)
    }

    @Test
    fun getEntriesForDate_returnsAllEntries() {
        val baseDir = tempBaseDir()
        val store = HoursStore(baseDir, "tablet-1")
        val date = LocalDate.of(2026, 5, 12)

        store.clockIn("Alice", date)
        store.clockIn("Bob", date)

        val entries = store.getEntriesForDate(date)
        assertEquals(2, entries.size)
    }

    @Test
    fun getActiveEntry_returnsOnlyOpenEntry() {
        val baseDir = tempBaseDir()
        val store = HoursStore(baseDir, "tablet-1")
        val date = LocalDate.of(2026, 5, 12)

        val entry = store.clockIn("Alice", date)
        store.clockOut(entry.id, date)
        store.clockIn("Alice", date)

        val active = store.getActiveEntry("Alice", date)
        assertNotNull(active)
        assertNull(active!!.clockOutMs)
    }

    @Test
    fun persistsAcrossInstances() {
        val baseDir = tempBaseDir()
        val date = LocalDate.of(2026, 5, 12)

        val store1 = HoursStore(baseDir, "tablet-1")
        val entry = store1.clockIn("Alice", date)

        val store2 = HoursStore(baseDir, "tablet-1")
        val entries = store2.getEntriesForDate(date)
        assertEquals(1, entries.size)
        assertEquals(entry.id, entries[0].id)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew :app:test --tests "com.kkc.sheettracker.data.HoursStoreTest"
```
Expected: FAIL with "cannot find symbol HoursStore"

- [ ] **Step 3: Write HoursStore.kt**

```kotlin
package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kkc.sheettracker.data.models.HoursDayLog
import com.kkc.sheettracker.data.models.HoursEntry
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

class HoursStore(
    private val baseDir: File,
    private val tabletId: String
) {
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    private fun fileForDate(date: LocalDate): File {
        val dir = File(baseDir, "HoursData")
        dir.mkdirs()
        return File(dir, "${date.format(DATE_FMT)}.json")
    }

    private fun readLog(date: LocalDate): HoursDayLog {
        val file = fileForDate(date)
        if (!file.exists()) return HoursDayLog(date = date.format(DATE_FMT))
        return runCatching {
            gson.fromJson(file.readText(), HoursDayLog::class.java)
        }.getOrDefault(HoursDayLog(date = date.format(DATE_FMT)))
    }

    private fun writeLog(log: HoursDayLog) {
        fileForDate(LocalDate.parse(log.date, DATE_FMT)).writeText(gson.toJson(log))
    }

    fun clockIn(employeeName: String, date: LocalDate = LocalDate.now()): HoursEntry {
        val log = readLog(date)
        val entry = HoursEntry(
            id = UUID.randomUUID().toString(),
            employeeName = employeeName,
            clockInMs = System.currentTimeMillis(),
            clockOutMs = null,
            tabletId = tabletId
        )
        writeLog(log.copy(entries = log.entries + entry))
        return entry
    }

    fun clockOut(entryId: String, date: LocalDate = LocalDate.now()): HoursEntry? {
        val log = readLog(date)
        val updated = log.entries.map { entry ->
            if (entry.id == entryId && entry.clockOutMs == null) {
                entry.copy(clockOutMs = System.currentTimeMillis())
            } else {
                entry
            }
        }
        val result = updated.find { it.id == entryId }
        writeLog(log.copy(entries = updated))
        return result
    }

    fun getEntriesForDate(date: LocalDate = LocalDate.now()): List<HoursEntry> {
        return readLog(date).entries
    }

    fun getActiveEntry(employeeName: String, date: LocalDate = LocalDate.now()): HoursEntry? {
        return readLog(date).entries
            .lastOrNull { it.employeeName == employeeName && it.clockOutMs == null }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :app:test --tests "com.kkc.sheettracker.data.HoursStoreTest"
```
Expected: All 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/HoursStore.kt \
        app/src/test/java/com/kkc/sheettracker/data/HoursStoreTest.kt
git commit -m "feat: add HoursStore with clock-in/out file persistence"
```

---

## Task 3: Add HOURS to NavDestination and TopLevelTab

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt:28-38`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavigationCoordinator.kt:12-37`

- [ ] **Step 1: Add HOURS to NavDestination enum in AppScaffold.kt**

Add import for `AccessTime` icons at the top of the imports block:
```kotlin
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.outlined.AccessTime
```

Replace the `NavDestination` enum (lines 28-38):
```kotlin
enum class NavDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
    JOBS("jobs", "Jobs", Icons.Filled.List, Icons.Outlined.List),
    SEARCH("search", "Search", Icons.Filled.Search, Icons.Outlined.Search),
    HOURS("hours", "Hours", Icons.Filled.AccessTime, Icons.Outlined.AccessTime),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}
```

- [ ] **Step 2: Update AppBottomNavBar to handle HOURS before SETTINGS**

In `AppBottomNavBar`, the current logic inserts Calculator before SETTINGS. HOURS is a regular tab — no special handling needed. The `forEach` loop already handles all entries.

- [ ] **Step 3: Add HOURS to TopLevelTab enum in NavigationCoordinator.kt**

Replace lines 12-37 (the `TopLevelTab` enum and its companion):
```kotlin
enum class TopLevelTab(val route: String) {
    DASHBOARD("dashboard"),
    JOBS("jobs"),
    SEARCH("search"),
    HOURS("hours"),
    SETTINGS("settings");

    companion object {
        fun fromDestination(destination: NavDestination): TopLevelTab {
            return when (destination) {
                NavDestination.DASHBOARD -> DASHBOARD
                NavDestination.JOBS -> JOBS
                NavDestination.SEARCH -> SEARCH
                NavDestination.HOURS -> HOURS
                NavDestination.SETTINGS -> SETTINGS
            }
        }

        fun toDestination(tab: TopLevelTab): NavDestination {
            return when (tab) {
                DASHBOARD -> NavDestination.DASHBOARD
                JOBS -> NavDestination.JOBS
                SEARCH -> NavDestination.SEARCH
                HOURS -> NavDestination.HOURS
                SETTINGS -> NavDestination.SETTINGS
            }
        }
    }
}
```

- [ ] **Step 4: Add HOURS to NavigationCoordinator.controllerFor**

In `NavigationCoordinator`, the constructor needs a `hoursNavController` param and `controllerFor` must handle `HOURS`. Replace the class definition and its `controllerFor` method:

```kotlin
class NavigationCoordinator(
    private val dashboardNavController: NavHostController,
    private val jobsNavController: NavHostController,
    private val searchNavController: NavHostController,
    private val hoursNavController: NavHostController,
    private val settingsNavController: NavHostController,
    private val getHomeTab: () -> TopLevelTab,
    private val getSelectedTab: () -> TopLevelTab,
    private val setSelectedTab: (TopLevelTab) -> Unit
) {
    // ... existing fields ...

    private fun controllerFor(tab: TopLevelTab): NavHostController {
        return when (tab) {
            TopLevelTab.DASHBOARD -> dashboardNavController
            TopLevelTab.JOBS -> jobsNavController
            TopLevelTab.SEARCH -> searchNavController
            TopLevelTab.HOURS -> hoursNavController
            TopLevelTab.SETTINGS -> settingsNavController
        }
    }
    // ... rest of class unchanged ...
}
```

- [ ] **Step 5: Build to verify compile**

```
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL (NavGraph.kt will have compile errors until Task 4)

Note: If `AppNavigation` / `NavGraph.kt` has exhaustive when clauses, they'll fail to compile until updated in Task 4. That's expected.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt \
        app/src/main/java/com/kkc/sheettracker/navigation/NavigationCoordinator.kt
git commit -m "feat: add HOURS destination to NavDestination and TopLevelTab"
```

---

## Task 4: HoursLoginDialog

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/hours/HoursLoginDialog.kt`

- [ ] **Step 1: Write HoursLoginDialog.kt**

```kotlin
package com.kkc.sheettracker.ui.hours

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun HoursLoginDialog(
    onLogin: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who are you?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter your name or employee PIN to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Name or PIN") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (input.trim().isNotBlank()) onLogin(input.trim()) }
                    ),
                    shape = MaterialTheme.shapes.medium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onLogin(input.trim()) },
                enabled = input.trim().isNotBlank(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hours/HoursLoginDialog.kt
git commit -m "feat: add HoursLoginDialog for name/PIN entry"
```

---

## Task 5: HoursTrackerScreen

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/hours/HoursTrackerScreen.kt`

- [ ] **Step 1: Write HoursTrackerScreen.kt**

```kotlin
package com.kkc.sheettracker.ui.hours

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.HoursStore
import com.kkc.sheettracker.data.models.HoursEntry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val TIME_FMT = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoursTrackerScreen(
    hoursStore: HoursStore,
    employeeName: String
) {
    var entries by remember { mutableStateOf(hoursStore.getEntriesForDate()) }
    var tick by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            tick++
            entries = hoursStore.getEntriesForDate()
        }
    }

    val todayEntries = entries.sortedByDescending { it.clockInMs }
    val myEntries = todayEntries.filter { it.employeeName == employeeName }
    val activeEntry = myEntries.firstOrNull { it.clockOutMs == null }
    val isClockedIn = activeEntry != null

    val myTotalMs = myEntries
        .filter { it.clockOutMs != null }
        .sumOf { it.clockOutMs!! - it.clockInMs }
    val activeMs = if (isClockedIn) System.currentTimeMillis() - (activeEntry?.clockInMs ?: 0L) else 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hours Tracker") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Employee header
            Text(
                "Hello, $employeeName",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            // Clock-in/out card
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isClockedIn) {
                        Text(
                            "Clocked in — ${formatMs(activeMs + myTotalMs)} today",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "Since ${formatEpochTime(activeEntry!!.clockInMs)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                hoursStore.clockOut(activeEntry.id)
                                entries = hoursStore.getEntriesForDate()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Clock Out")
                        }
                    } else {
                        Text(
                            "Clocked out — ${formatMs(myTotalMs)} today",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Button(
                            onClick = {
                                hoursStore.clockIn(employeeName)
                                entries = hoursStore.getEntriesForDate()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Clock In")
                        }
                    }
                }
            }

            // My sessions today
            if (myEntries.isNotEmpty()) {
                Text(
                    "My Sessions Today",
                    style = MaterialTheme.typography.titleMedium
                )
                myEntries.forEach { entry ->
                    HoursEntryRow(entry = entry)
                }
            }

            // All employees today
            val otherEmployees = todayEntries
                .filter { it.employeeName != employeeName }
                .groupBy { it.employeeName }
            if (otherEmployees.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "On the Clock Today",
                    style = MaterialTheme.typography.titleMedium
                )
                otherEmployees.forEach { (name, empEntries) ->
                    val empActive = empEntries.any { it.clockOutMs == null }
                    val empTotal = empEntries.filter { it.clockOutMs != null }
                        .sumOf { it.clockOutMs!! - it.clockInMs }
                    EmployeeSummaryRow(
                        name = name,
                        totalMs = empTotal,
                        isClockedIn = empActive
                    )
                }
            }
        }
    }
}

@Composable
private fun HoursEntryRow(entry: HoursEntry) {
    val start = formatEpochTime(entry.clockInMs)
    val end = if (entry.clockOutMs != null) formatEpochTime(entry.clockOutMs) else "Active"
    val duration = if (entry.clockOutMs != null) {
        formatMs(entry.clockOutMs - entry.clockInMs)
    } else {
        formatMs(System.currentTimeMillis() - entry.clockInMs)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$start → $end",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            duration,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun EmployeeSummaryRow(name: String, totalMs: Long, isClockedIn: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isClockedIn) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(50)
                        )
                )
            }
            Text(name, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            formatMs(totalMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatMs(ms: Long): String {
    val hours = ms / 3_600_000
    val minutes = (ms % 3_600_000) / 60_000
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun formatEpochTime(epochMs: Long): String {
    return Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(TIME_FMT)
}
```

- [ ] **Step 2: Build to verify compile**

```
./gradlew :app:assembleDebug
```
Expected: may still fail in NavGraph (Task 6). HoursTrackerScreen itself should be clean.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hours/HoursTrackerScreen.kt
git commit -m "feat: add HoursTrackerScreen with clock in/out UI"
```

---

## Task 6: Wire HOURS into NavGraph (both stacks)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

This task is the largest single change. The file is long — read it fully before editing. Both `MultiBackStackNavigation` and `LegacySingleStackNavigation` need identical changes.

- [ ] **Step 1: Add imports to NavGraph.kt**

Add these imports near the top of the import block:
```kotlin
import com.kkc.sheettracker.data.HoursStore
import com.kkc.sheettracker.ui.hours.HoursLoginDialog
import com.kkc.sheettracker.ui.hours.HoursTrackerScreen
```

- [ ] **Step 2: Add `employeeName`, `onEmployeeNameChanged`, `hoursStore` to `AppNavigation` signature**

Find the `AppNavigation` function signature (line 70) and add three parameters after `workMode`:
```kotlin
employeeName: String,
onEmployeeNameChanged: (String) -> Unit,
hoursStore: HoursStore,
```

Pass these through to both `MultiBackStackNavigation` and `LegacySingleStackNavigation` call sites inside `AppNavigation`.

- [ ] **Step 3: Add the same three params to `MultiBackStackNavigation` and `LegacySingleStackNavigation`**

In `MultiBackStackNavigation` (line 147), add to its parameter list:
```kotlin
employeeName: String,
onEmployeeNameChanged: (String) -> Unit,
hoursStore: HoursStore,
```

Do the same for `LegacySingleStackNavigation` (line 934).

- [ ] **Step 4: Add `hoursNavController` and `selectedTab` for HOURS in `MultiBackStackNavigation`**

Inside `MultiBackStackNavigation`, after `val settingsNavController = rememberNavController()`:
```kotlin
val hoursNavController = rememberNavController()
```

Update the `NavigationCoordinator` constructor call to include `hoursNavController = hoursNavController`.

Update `visibleDestinations` — HOURS is always visible (all work modes use it):
```kotlin
val visibleDestinations = remember(workMode) {
    if (workMode == WorkMode.ASSEMBLY) {
        listOf(NavDestination.JOBS, NavDestination.SEARCH, NavDestination.HOURS, NavDestination.SETTINGS)
    } else {
        NavDestination.entries
    }
}
```

- [ ] **Step 5: Add HoursTabLayer inside `MultiBackStackNavigation`**

Inside the `Box > Column > Box` that contains all tab layers (after the SETTINGS `TabLayer`), add:

```kotlin
TabLayer(visible = selectedTab == TopLevelTab.HOURS) {
    HoursTabHost(
        navController = hoursNavController,
        hoursStore = hoursStore,
        employeeName = employeeName
    )
}
```

- [ ] **Step 6: Add `HoursTabHost` private composable**

Add this function at the bottom of NavGraph.kt, alongside the other `*TabHost` functions:

```kotlin
@Composable
private fun HoursTabHost(
    navController: NavHostController,
    hoursStore: HoursStore,
    employeeName: String
) {
    var sessionName by remember { mutableStateOf(employeeName.ifBlank { null }) }
    var showLoginDialog by remember { mutableStateOf(sessionName == null) }

    NavHost(
        navController = navController,
        startDestination = "hours",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("hours") {
            if (showLoginDialog || sessionName == null) {
                HoursLoginDialog(
                    onLogin = { name ->
                        sessionName = name
                        showLoginDialog = false
                    },
                    onDismiss = { showLoginDialog = false }
                )
            }
            sessionName?.let { name ->
                HoursTrackerScreen(
                    hoursStore = hoursStore,
                    employeeName = name
                )
            }
        }
    }
}
```

- [ ] **Step 7: Replicate HOURS support in `LegacySingleStackNavigation`**

In `LegacySingleStackNavigation`:

1. Add `hoursNavController` or use the single navController with a `"hours"` route
2. Update `currentNavDest` mapping to include `currentRoute == "hours" -> NavDestination.HOURS`
3. Update `visibleDestinations` to include HOURS for all work modes
4. Add `composable("hours")` route inside the `NavHost`:

```kotlin
composable("hours") {
    var sessionName by remember { mutableStateOf(employeeName.ifBlank { null }) }
    var showLoginDialog by remember { mutableStateOf(sessionName == null) }

    if (showLoginDialog || sessionName == null) {
        HoursLoginDialog(
            onLogin = { name ->
                sessionName = name
                showLoginDialog = false
            },
            onDismiss = { showLoginDialog = false }
        )
    }
    sessionName?.let { name ->
        HoursTrackerScreen(
            hoursStore = hoursStore,
            employeeName = name
        )
    }
}
```

5. In the `onNavigate` callback for `AppBottomNavBar`, the existing logic pops to start destination. The `"hours"` route should be treated like other top-level routes — no pop needed, `launchSingleTop = true` suffices.

- [ ] **Step 8: Build**

```
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. Fix any remaining compile errors (exhaustive `when` in NavigationCoordinator).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: wire HoursTabHost into both navigation stacks"
```

---

## Task 7: Employee name in Settings

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add `employeeName` and `onEmployeeNameChanged` to `SettingsScreen` signature**

Add to `SettingsScreen` parameters (after `onBack: () -> Unit`):
```kotlin
employeeName: String,
onEmployeeNameChanged: (String) -> Unit,
```

- [ ] **Step 2: Add employee name state vars**

After the existing `var editSyncthingApiKey` line, add:
```kotlin
var editEmployeeName by remember { mutableStateOf(employeeName) }
var employeeNameDirty by remember { mutableStateOf(false) }
var employeeNameSaved by remember { mutableStateOf(false) }
```

Add the `LaunchedEffect` for saved flash:
```kotlin
LaunchedEffect(employeeNameSaved) {
    if (employeeNameSaved) {
        delay(1600)
        employeeNameSaved = false
    }
}
```

- [ ] **Step 3: Add employee name field to the Tablet section**

Inside the `SettingsSection(title = "Tablet", ...)` block, add BEFORE the existing tablet ID field:

```kotlin
OutlinedTextField(
    value = editEmployeeName,
    onValueChange = {
        editEmployeeName = it
        employeeNameDirty = it != employeeName
    },
    label = { Text("Your Name / PIN") },
    supportingText = { Text("Used for auto-login to the Hours Tracker. Leave blank to be prompted each time.") },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
    shape = MaterialTheme.shapes.medium
)

if (employeeNameDirty || employeeNameSaved) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (employeeNameDirty) {
            Button(
                onClick = {
                    onEmployeeNameChanged(editEmployeeName.trim())
                    employeeNameDirty = false
                    employeeNameSaved = true
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Save Name")
            }
        }
        if (employeeNameSaved) {
            Text(
                "Saved",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
```

- [ ] **Step 4: Update SettingsTabHost in NavGraph.kt to pass the new params**

In `SettingsTabHost`, add:
```kotlin
employeeName: String,
onEmployeeNameChanged: (String) -> Unit,
```

Pass them through to `SettingsScreen(...)`.

Update both calls to `SettingsTabHost` in `MultiBackStackNavigation` and `LegacySingleStackNavigation`.

- [ ] **Step 5: Build**

```
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/settings/SettingsScreen.kt \
        app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: add employee name field to Settings for Hours Tracker auto-login"
```

---

## Task 8: Wire everything up in MainActivity

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/MainActivity.kt`

- [ ] **Step 1: Read `employee_name` from SharedPreferences in onCreate**

After the `basePath` line (line 79), add:
```kotlin
val employeeNameFromPrefs = prefs.getString("employee_name", "") ?: ""
```

- [ ] **Step 2: Create `HoursStore` in onCreate**

After `appStateStore = AppStateStore(...)`, add:
```kotlin
val hoursStore = HoursStore(File(basePath), tabletId)
```

- [ ] **Step 3: Add `employeeName` state in setContent**

Inside `setContent { ... }`, after `var workMode by remember {...}`, add:
```kotlin
var employeeName by remember { mutableStateOf(employeeNameFromPrefs) }
```

- [ ] **Step 4: Pass new params to AppNavigation**

Inside `AppNavigation(...)`, add:
```kotlin
employeeName = employeeName,
onEmployeeNameChanged = { name ->
    employeeName = name
    prefs.edit().putString("employee_name", name).apply()
},
hoursStore = hoursStore,
```

Also pass `employeeName` and `onEmployeeNameChanged` into the SettingsScreen call chain (already threaded through in Task 7).

- [ ] **Step 5: Full build and run**

```
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/MainActivity.kt
git commit -m "feat: integrate HoursStore and employee name pref into MainActivity"
```

---

## Task 9: Verification

- [ ] **Step 1: Run all tests**

```
./gradlew :app:test
```
Expected: All tests pass including the 5 new HoursStoreTest tests.

- [ ] **Step 2: Manual smoke test — no name set**

1. Clear the app's SharedPreferences (or set `employee_name` to blank)
2. Tap the clock/Hours icon on the bottom nav bar
3. Expected: `HoursLoginDialog` appears asking "Who are you?"
4. Enter a name, tap Continue
5. Expected: `HoursTrackerScreen` shows with "Hello, [name]" and a Clock In button

- [ ] **Step 3: Manual smoke test — name pre-set in Settings**

1. Go to Settings → Tablet section → enter a name → Save
2. Navigate away, tap the Hours button
3. Expected: goes directly to `HoursTrackerScreen` with no dialog

- [ ] **Step 4: Clock in/out flow**

1. In HoursTrackerScreen, tap "Clock In"
2. Expected: button changes to "Clock Out", shows current session start time
3. Tap "Clock Out"
4. Expected: session appears in "My Sessions Today" with in/out times and duration

- [ ] **Step 5: Multi-employee view**

1. On a second device (or by editing the hours JSON file directly), add an entry for a different employee
2. Reload the hours screen
3. Expected: the other employee appears in "On the Clock Today" section

- [ ] **Step 6: Commit final**

```bash
git add -A
git commit -m "feat: complete Hours Tracker feature with navbar button, auth, and clock in/out"
```

---

## Self-Review Checklist

**Spec coverage:**
- [x] Bottom navbar button for hours → Task 3 (HOURS in NavDestination) + Task 6 (HoursTabHost wired)
- [x] Auto-login if name set in settings → Task 6 `HoursTabHost` checks `employeeName.ifBlank { null }`
- [x] Name/PIN dialog if no name set → Task 4 `HoursLoginDialog` + Task 6 shows it when `sessionName == null`
- [x] Same auth for clock in/out → auth happens before entering `HoursTrackerScreen`; clock in/out buttons are inside that screen
- [x] Settings: add employee name field → Task 7
- [x] Data persistence → Task 2 `HoursStore` with file-per-day in `{basePath}/HoursData/`

**Boundary conditions:**
- User enters Hours tab, dismisses login dialog → screen shows blank (no tracker rendered). Re-tapping the tab reopens the dialog because `showLoginDialog` is remembered in the composable, which persists for the session in the multi-stack version. In the legacy-stack, it's route-level state and resets on navigation. Both behaviors are acceptable.
- Clock in on one tablet, view on another → works because data is written to the shared `basePath` (synced by Syncthing)
- Employee name with trailing spaces → `trim()` on every save path prevents collisions

**Type consistency:**
- `HoursEntry.id: String` used consistently as `UUID.randomUUID().toString()` in `HoursStore.clockIn` and referenced by `id` in `clockOut`
- `HoursStore(baseDir, tabletId)` constructor matches usage in `MainActivity`
- `employeeName: String` (not nullable) threaded from `MainActivity` → `AppNavigation` → `NavGraph` → `HoursTabHost` → `HoursTrackerScreen`
