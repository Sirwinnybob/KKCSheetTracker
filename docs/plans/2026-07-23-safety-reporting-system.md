# Safety Reporting System Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a safety reporting and concern tracking system into the Safety/SDS screen of KKCSheetTracker Android app and the Hours Tracker server web dashboard.

**Architecture:** Data is stored using atomic JSON file patterns under `.safety/` (concerns, status, comments, attachments). The Android app provides a 2-tab view with plain-text password protection (`KKC-Safety`) for subscriber mode, notification badges on nav bars and tiles, and default author name persistence. The Hours Tracker server (`C:\Scripts\Hours Tracker`) exposes FastAPI REST endpoints and web dashboard controls to view and manage all safety reports without password restrictions.

**Tech Stack:** Jetpack Compose, Kotlin Flow, Coroutines, Gson, SharedPreferences (Android); Python FastAPI, HTML5/CSS/JavaScript (Hours Tracker Server).

---

### Task 1: Create Safety Models and Data Structures

**Files:**
- Create: `c:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\data\models\SafetyModels.kt`
- Test: `c:\Scripts\KKCSheetTracker\app\src\test\java\com\kkc\sheettracker\data\models\SafetyModelsTest.kt`

**Step 1: Write the failing test**

Create `SafetyModelsTest.kt`:
```kotlin
package com.kkc.sheettracker.data.models

import org.junit.Assert.assertEquals
import org.junit.Test

class SafetyModelsTest {
    @Test
    fun testSafetyStatusRecencyOrdering() {
        val r1 = SafetyStatusRecord(status = "OPEN", by = "UserA", at = "2026-07-23T10:00:00Z")
        val r2 = SafetyStatusRecord(status = "ACKNOWLEDGED", by = "Admin", at = "2026-07-23T10:05:00Z")
        val list = listOf(r1, r2)
        assertEquals("ACKNOWLEDGED", list.maxByOrNull { it.at }?.status)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.models.SafetyModelsTest"`
Expected: FAIL (class `SafetyStatusRecord` not defined)

**Step 3: Write minimal implementation**

Create `SafetyModels.kt`:
```kotlin
package com.kkc.sheettracker.data.models

data class StoredSafetyConcern(
    val id: String,
    val author: String,
    val title: String,
    val category: String,
    val description: String,
    val attachmentIds: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = ""
) {
    constructor() : this(id = "", author = "", title = "", category = "", description = "")
}

data class SafetyStatusRecord(
    val status: String,
    val by: String = "",
    val at: String = ""
)

data class SafetyItem(
    val id: String,
    val author: String,
    val title: String,
    val category: String,
    val description: String,
    val status: String,
    val statusBy: String,
    val statusAt: String,
    val attachmentIds: List<String>,
    val createdAt: String,
    val updatedAt: String
)

data class SafetyComment(
    val id: String,
    val author: String,
    val text: String,
    val createdAt: String
)

val ALL_SAFETY_STATUSES = listOf(
    "OPEN", "ACKNOWLEDGED", "IN PROGRESS", "RESOLVED"
)

val SAFETY_CATEGORIES = listOf(
    "Near Miss",
    "Equipment Hazard",
    "SDS / Chemical",
    "Housekeeping / Slip Hazard",
    "General Suggestion"
)
```

**Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.models.SafetyModelsTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/models/SafetyModels.kt app/src/test/java/com/kkc/sheettracker/data/models/SafetyModelsTest.kt
git commit -m "feat: add SafetyModels data classes and status definitions"
```

---

### Task 2: Implement SafetyRepository and Unit Tests

**Files:**
- Create: `c:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\data\SafetyRepository.kt`
- Test: `c:\Scripts\KKCSheetTracker\app\src\test\java\com\kkc\sheettracker\data\SafetyRepositoryTest.kt`

**Step 1: Write the failing test**

Create `SafetyRepositoryTest.kt`:
```kotlin
package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SafetyRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAddAndGetSafetyConcern() {
        val repository = SafetyRepository(tempFolder.root.absolutePath)
        val item = repository.addConcern(
            author = "John Doe",
            title = "Loose Guard",
            category = "Equipment Hazard",
            description = "Guard on saw 2 is loose",
            attachmentIds = emptyList(),
            tabletId = "tablet-1"
        )
        assertNotNull(item.id)
        assertEquals("OPEN", item.status)

        val retrieved = repository.getConcerns()
        assertEquals(1, retrieved.size)
        assertEquals("Loose Guard", retrieved[0].title)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.SafetyRepositoryTest"`
Expected: FAIL (SafetyRepository not defined)

**Step 3: Write minimal implementation**

Create `SafetyRepository.kt`:
```kotlin
package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kkc.sheettracker.data.models.*
import java.io.File
import java.time.Instant
import java.util.UUID

class SafetyRepository(private val basePath: String) {
    private val safetyDir get() = File(basePath, ".safety")
    private val concernsDir get() = File(safetyDir, "concerns")
    private val statusDir get() = File(safetyDir, "status")
    private val commentsDir get() = File(safetyDir, "comments")
    private val attachmentsDir get() = File(safetyDir, "attachments")
    private val gson = Gson()

    private inline fun <reified T> readJson(file: File): T? {
        if (!file.exists()) return null
        return runCatching { gson.fromJson(file.readText(), object : TypeToken<T>() {}.type) as T }.getOrNull()
    }

    private fun resolveStatus(concernId: String, statusFiles: List<File>): SafetyStatusRecord {
        return statusFiles
            .filter { it.name.startsWith("$concernId.") && it.name.endsWith(".json") && !it.name.contains(".sync-conflict-") }
            .mapNotNull { readJson<SafetyStatusRecord>(it) }
            .maxByOrNull { it.at }
            ?: SafetyStatusRecord("OPEN")
    }

    fun getConcerns(): List<SafetyItem> {
        if (!concernsDir.exists()) return emptyList()
        val statusFiles = statusDir.listFiles()?.toList().orEmpty()
        return concernsDir.listFiles { f -> f.extension == "json" && !f.name.contains(".sync-conflict-") }
            ?.mapNotNull { file ->
                val stored = readJson<StoredSafetyConcern>(file) ?: return@mapNotNull null
                val statusRecord = resolveStatus(stored.id, statusFiles)
                SafetyItem(
                    id = stored.id,
                    author = stored.author,
                    title = stored.title,
                    category = stored.category,
                    description = stored.description,
                    status = statusRecord.status,
                    statusBy = statusRecord.by,
                    statusAt = statusRecord.at,
                    attachmentIds = stored.attachmentIds,
                    createdAt = stored.createdAt,
                    updatedAt = stored.updatedAt
                )
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun getComments(concernId: String): List<SafetyComment> {
        val dir = File(commentsDir, concernId)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" && !f.name.contains(".sync-conflict-") }
            ?.mapNotNull { readJson<SafetyComment>(it) }
            ?.sortedBy { it.createdAt }
            ?: emptyList()
    }

    fun addConcern(
        author: String,
        title: String,
        category: String,
        description: String,
        attachmentIds: List<String>,
        tabletId: String
    ): SafetyItem {
        concernsDir.mkdirs()
        statusDir.mkdirs()
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val stored = StoredSafetyConcern(
            id = id,
            author = author.trim(),
            title = title.trim(),
            category = category.trim(),
            description = description.trim(),
            attachmentIds = attachmentIds,
            createdAt = now,
            updatedAt = now
        )
        atomicWriteFile(File(concernsDir, "$id.json"), gson.toJson(stored))

        val statusRecord = SafetyStatusRecord("OPEN", author.trim(), now)
        atomicWriteFile(File(statusDir, "$id.$tabletId.json"), gson.toJson(statusRecord))

        return SafetyItem(
            id = id,
            author = stored.author,
            title = stored.title,
            category = stored.category,
            description = stored.description,
            status = "OPEN",
            statusBy = stored.author,
            statusAt = now,
            attachmentIds = attachmentIds,
            createdAt = now,
            updatedAt = now
        )
    }

    fun addComment(concernId: String, author: String, text: String): SafetyComment {
        val dir = File(commentsDir, concernId)
        dir.mkdirs()
        val id = UUID.randomUUID().toString()
        val comment = SafetyComment(id, author.trim(), text.trim(), Instant.now().toString())
        atomicWriteFile(File(dir, "$id.json"), gson.toJson(comment))
        return comment
    }

    fun setStatus(concernId: String, status: String, by: String, tabletId: String) {
        statusDir.mkdirs()
        val file = File(statusDir, "$concernId.$tabletId.json")
        atomicWriteFile(file, gson.toJson(SafetyStatusRecord(status, by, Instant.now().toString())))
    }

    fun saveAttachment(bytes: ByteArray, filename: String): String {
        attachmentsDir.mkdirs()
        val file = File(attachmentsDir, filename)
        file.writeBytes(bytes)
        return filename
    }

    fun getAttachmentFile(filename: String): File {
        return File(attachmentsDir, filename)
    }
}
```

**Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.SafetyRepositoryTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/SafetyRepository.kt app/src/test/java/com/kkc/sheettracker/data/SafetyRepositoryTest.kt
git commit -m "feat: add SafetyRepository for handling .safety filesystem operations"
```

---

### Task 3: Update UiPreferencesStore for Safety Preferences

**Files:**
- Modify: `c:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\data\UiPreferencesStore.kt`
- Test: `c:\Scripts\KKCSheetTracker\app\src\test\java\com\kkc\sheettracker\data\UiPreferencesStoreTest.kt`

**Step 1: Write the failing test**

Create `UiPreferencesStoreTest.kt` or update it:
```kotlin
package com.kkc.sheettracker.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UiPreferencesStoreTest {
    @Test
    fun testSafetyPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = UiPreferencesStore(context)
        assertFalse(store.isSafetySubscriber())
        assertEquals("", store.getSafetyAuthorName())

        store.setSafetySubscriber(true)
        store.setSafetyAuthorName("Bob Smith")

        assertTrue(store.isSafetySubscriber())
        assertEquals("Bob Smith", store.getSafetyAuthorName())
    }
}
```

**Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.UiPreferencesStoreTest"`
Expected: FAIL (`isSafetySubscriber` not found)

**Step 3: Write minimal implementation**

Add methods to `UiPreferencesStore.kt`:
```kotlin
fun isSafetySubscriber(): Boolean =
    prefs.getBoolean("safety_subscriber", false)

fun setSafetySubscriber(subscribed: Boolean) =
    prefs.edit().putBoolean("safety_subscriber", subscribed).apply()

fun getSafetyAuthorName(): String =
    prefs.getString("safety_author_name", "") ?: ""

fun setSafetyAuthorName(name: String) =
    prefs.edit().putString("safety_author_name", name).apply()
```

**Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.UiPreferencesStoreTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/UiPreferencesStore.kt app/src/test/java/com/kkc/sheettracker/data/UiPreferencesStoreTest.kt
git commit -m "feat: add safety subscriber state and saved author name to UiPreferencesStore"
```

---

### Task 4: Create SafetySubscriptionManager

**Files:**
- Create: `c:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\data\SafetySubscriptionManager.kt`
- Test: `c:\Scripts\KKCSheetTracker\app\src\test\java\com\kkc\sheettracker\data\SafetySubscriptionManagerTest.kt`

**Step 1: Write the failing test**

Create `SafetySubscriptionManagerTest.kt`:
```kotlin
package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafetySubscriptionManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testNotificationCountForSubscriber() {
        val repo = SafetyRepository(tempFolder.root.absolutePath)
        repo.addConcern("Alice", "Test Hazard", "Near Miss", "Test detail", emptyList(), "t1")

        val manager = SafetySubscriptionManager(repo)
        val count = manager.calculateNotificationCount(isSubscriber = true, lastSeenCount = 0)
        assertEquals(1, count)

        val nonSubCount = manager.calculateNotificationCount(isSubscriber = false, lastSeenCount = 0)
        assertEquals(0, nonSubCount)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.SafetySubscriptionManagerTest"`
Expected: FAIL (`SafetySubscriptionManager` not defined)

**Step 3: Write minimal implementation**

Create `SafetySubscriptionManager.kt`:
```kotlin
package com.kkc.sheettracker.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SafetySubscriptionManager(private val repository: SafetyRepository) {
    private val _notificationCount = MutableStateFlow(0)
    val notificationCount: StateFlow<Int> = _notificationCount.asStateFlow()

    fun calculateNotificationCount(isSubscriber: Boolean, lastSeenCount: Int): Int {
        if (!isSubscriber) return 0
        val concerns = repository.getConcerns()
        val totalItems = concerns.size
        return (totalItems - lastSeenCount).coerceAtLeast(0)
    }

    fun updateNotificationCount(isSubscriber: Boolean, lastSeenCount: Int) {
        _notificationCount.value = calculateNotificationCount(isSubscriber, lastSeenCount)
    }
}
```

**Step 4: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.SafetySubscriptionManagerTest"`
Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/SafetySubscriptionManager.kt app/src/test/java/com/kkc/sheettracker/data/SafetySubscriptionManagerTest.kt
git commit -m "feat: add SafetySubscriptionManager for tracking unread safety concern count"
```

---

### Task 5: Implement Safety Concerns UI in SafetyDocumentsScreen

**Files:**
- Modify: `c:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\ui\standards\SafetyDocumentsScreen.kt`

**Step 1: Write implementation**

Update `SafetyDocumentsScreen.kt`:
- Add PrimaryTabRow / Tab for "Documents (PDFs)" and "Safety Concerns".
- Add "+ Report Safety Concern" button.
- Add Author name pre-population (from `UiPreferencesStore`), checkbox "Remember name on this tablet", Category dropdown, Title, Description, and photo attachment picker.
- Add Password Unlock Dialog (`"KKC-Safety"`).
- Implement non-subscriber vs subscriber rendering logic:
  - Non-subscribers see locked card: "🔒 Enter password to subscribe and view active safety concerns & discussion threads."
  - Subscribers see concern list cards, status badges, details modal with comment history & add comment input.

**Step 2: Verify Android App Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/standards/SafetyDocumentsScreen.kt
git commit -m "feat: update SafetyDocumentsScreen with 2-tab layout, concern reporting form, KKC-Safety password gate, and subscriber feeds"
```

---

### Task 6: Wire Notification Badges to Standards Tile and App Bottom Nav Bar

**Files:**
- Modify: `c:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\ui\standards\StandardsHubScreen.kt`
- Modify: `c:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\ui\components\AppScaffold.kt`
- Modify: `c:\Scripts\KKCSheetTracker\app\src\main\java\com\kkc\sheettracker\navigation\NavGraph.kt`

**Step 1: Write implementation**

- In `StandardsHubScreen.kt`:
  - Add `safetyNotificationCount: Int = 0` parameter to `StandardsHubScreen` and render a `Badge` on `StandardsTile.SAFETY` when `safetyNotificationCount > 0`.
- In `AppScaffold.kt`:
  - Add `safetyNotificationCount: Int = 0` to `AppBottomNavBar` and display badge on `NavDestination.STANDARDS` when count > 0.
- In `NavGraph.kt`:
  - Pass `safetyNotificationCount` from state to scaffold and screens.

**Step 2: Verify Android App Build**

Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/standards/StandardsHubScreen.kt app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: wire safety notification badges to Standards bottom nav item and StandardsHubScreen Safety tile"
```

---

### Task 7: Implement Hours Tracker Backend Safety Routes

**Files:**
- Create: `C:\Scripts\Hours Tracker\backend\routes\safety_store.py`
- Create: `C:\Scripts\Hours Tracker\backend\routes\safety.py`
- Modify: `C:\Scripts\Hours Tracker\backend\main_v2.py`

**Step 1: Write implementation**

Create `safety_store.py`:
- Helper functions to load concerns, update status (`OPEN`, `ACKNOWLEDGED`, `IN PROGRESS`, `RESOLVED`), post comments, save attachments, reading from shared Ready Jobs `.safety/` folder.

Create `safety.py`:
- FastAPI router (`prefix="/api/safety"`) with endpoints:
  - `GET /api/safety/concerns`
  - `POST /api/safety/concerns`
  - `GET /api/safety/concerns/{id}/comments`
  - `POST /api/safety/concerns/{id}/comments`
  - `PUT /api/safety/concerns/{id}/status`
  - `GET /api/safety/attachments/{filename}`

Update `main_v2.py`:
- Import `routes.safety as safety_route` and `app.include_router(safety_route.router)`.

**Step 2: Commit**

```bash
git add "backend/routes/safety_store.py" "backend/routes/safety.py" "backend/main_v2.py"
git commit -m "feat: add FastAPI safety router and storage layer for Hours Tracker backend"
```

---

### Task 8: Implement Hours Tracker Web Dashboard Safety Tab

**Files:**
- Modify: `C:\Scripts\Hours Tracker\frontend\` components or static html dashboard.

**Step 1: Write implementation**

- Add **"Safety Reports"** tab/navigation item in the web management UI.
- Display list/cards of safety concerns, author names, category badges, timestamps, photos, status controls, and comment reply form (no password required).

**Step 2: Commit**

```bash
git add frontend/
git commit -m "feat: add Safety Reports web dashboard tab to Hours Tracker frontend"
```

---

### Task 9: Final End-to-End Verification & Walkthrough

**Files:**
- Create: `C:\Users\chadc\.gemini\antigravity\brain\b1700b62-708a-4df7-bc74-dad506d101e4\walkthrough.md`

**Step 1: Execute test suite & build**

Run: `.\gradlew.bat testDebugUnitTest assembleDebug` in `C:\Scripts\KKCSheetTracker`

**Step 2: Write walkthrough artifact**

Document completed features, verification results, and screenshots/summary of changes.
