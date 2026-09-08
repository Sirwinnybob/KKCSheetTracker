# Hidden Materials Android Data Layer — Implementation Plan (Plan 2 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Android tablet app a complete, independently-testable data layer for hidden materials — local file-fallback reading, a durable sidecar-request writer, a REST fast-path client, a live WebSocket client, a live+fallback merge store, lifecycle-safety plumbing, and a per-mode "show hidden materials" DataStore toggle. No UI changes in this plan.

**Architecture:** Every new class is a close mirror of this codebase's existing delivery-schedule feature (`DeliveryScheduleRepository`, `ProductionOrderRequestStore`, `AdminSyncClient`, `DeliveryScheduleLiveClient`, `DeliveryScheduleStateStore`, `DeliveryScheduleLifecycleGate`) — same threading model, same reconnect/backoff behavior, same live-vs-fallback arbitration. Every class is parametrized by `HiddenMaterialsMode` (`HARDWOODS`/`SPECIALTY`) so Plan 3 (Specialty UI) reuses every file in this plan unchanged, just instantiated a second time with a different mode — matching the backend's mode-segregation invariant (Hardwoods and Specialty never share state).

**Deliberate simplification vs. the delivery-schedule sibling:** the backend's `POST /api/admin-sync/hardwoods-hidden-materials` returns only `{"applied": true}`, not a canonical document (unlike delivery schedule's endpoint, which returns the full updated schedule). So there is no `applyImmediate`-style optimistic local update fed by the REST response here — a successful REST call means "don't fall back to the sidecar," and the UI picks up the actual change via the live WebSocket (which the backend pushes to synchronously, before the REST handler even returns) or, if disconnected, the next periodic fallback file re-read. This keeps `HiddenMaterialsStateStore` one method smaller than `DeliveryScheduleStateStore`.

**Non-goals (this plan):** No changes to `NavGraph.kt` or `HardwoodsWorkspaceScreen.kt` — wiring these classes into the app's two navigation stacks and the cutlist screen's UI is a separate follow-up plan, written after mapping how `AppNavigation` (legacy stack) and `MultiBackStackNavigation` (newer tab stack) relate. This plan produces a data layer that compiles, is fully unit-tested, and is ready for that follow-up to consume — but nothing in it is reachable from the running app yet.

**Tech Stack:** Kotlin, OkHttp (WebSocket + HTTP), Gson, AndroidX DataStore Preferences, JUnit4, Mockito-Kotlin.

**Spec:** `C:\Scripts\KKCSheetTracker\docs\superpowers\specs\2026-09-08-hardwoods-hidden-materials-design.md`
**Backend plan (shipped, source of truth for the wire contract):** `C:\Scripts\Hours Tracker\docs\superpowers\plans\2026-09-08-hidden-materials-backend.md`

---

## File Structure

- Create: `app/src/main/java/com/kkc/sheettracker/data/models/HiddenMaterialsModels.kt` — data classes + mode enum.
- Create: `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsRepository.kt` — path resolution, JSON parsing, the effective-visibility rule, and the file-fallback reader class.
- Create: `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsRequestStore.kt` — durable per-tablet sidecar writer.
- Modify: `app/src/main/java/com/kkc/sheettracker/data/AdminSyncClient.kt` — add the REST fast-path method.
- Create: `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsLiveClient.kt` — read-only WebSocket client, one instance per mode.
- Create: `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsStateStore.kt` — live+fallback arbitration, one instance per mode.
- Create: `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsLifecycleGate.kt` — stale-callback guard, one instance per mode.
- Create: `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsVisibilityPreferencesStore.kt` — per-mode "show hidden materials" DataStore toggle.
- Test: `app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsRepositoryTest.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsRequestStoreTest.kt`
- Test: additions to `app/src/test/java/com/kkc/sheettracker/data/AdminSyncClientTest.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsLiveClientTest.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsStateStoreTest.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsLifecycleGateTest.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsVisibilityPreferencesStoreTest.kt`

All paths are relative to `C:\Scripts\KKCSheetTracker`.

---

### Task 1: Data models

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/models/HiddenMaterialsModels.kt`

- [ ] **Step 1: Write the implementation**

Models get their own file in this codebase (see `DeliveryScheduleModels.kt` for the established pattern) rather than living in the catch-all `Models.kt`. This is a pure data-holder file with no logic, so there is no separate test file for it — its shape is exercised by every other task's tests.

```kotlin
// app/src/main/java/com/kkc/sheettracker/data/models/HiddenMaterialsModels.kt
package com.kkc.sheettracker.data.models

/**
 * Hardwoods and Specialty are two fully independent instances of the hidden-materials feature
 * -- SpecialtyDoorPanelsScreen reads rows from the same HardwoodCutlistIndex/HardwoodDocType
 * data as the Hardwoods cutlist screen, so hiding a material in one mode must never affect the
 * other (see the 2026-09-08 hidden-materials design doc). This enum's `.name` values
 * ("HARDWOODS"/"SPECIALTY") are sent verbatim to the backend and must match
 * routes/hidden_materials_store.py's VALID_MODES exactly.
 */
enum class HiddenMaterialsMode { HARDWOODS, SPECIALTY }

/** One hide/unhide record. `docType` matches HardwoodDocType.name (e.g. "NAILER_CUT_LIST"). */
data class HiddenMaterialEntry(
    val docType: String = "",
    val material: String = "",
    val hiddenAt: String = "",
    val tabletId: String = ""
)

data class HiddenMaterialsGlobalDoc(
    val entries: List<HiddenMaterialEntry> = emptyList()
)

data class HiddenMaterialsJobDoc(
    val hides: List<HiddenMaterialEntry> = emptyList(),
    val unhides: List<HiddenMaterialEntry> = emptyList()
)

/**
 * The aggregated shape broadcast by the live WebSocket's `hiddenMaterials` field and assembled
 * locally by HiddenMaterialsRepository from the two on-disk files. `jobs` only ever contains
 * entries for jobs that actually have an override file -- most jobs never touch this feature.
 */
data class HiddenMaterialsDocument(
    val global: HiddenMaterialsGlobalDoc = HiddenMaterialsGlobalDoc(),
    val jobs: Map<String, HiddenMaterialsJobDoc> = emptyMap()
)
```

- [ ] **Step 2: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/models/HiddenMaterialsModels.kt
git commit -m "$(cat <<'EOF'
feat: add hidden-materials Android data models

HiddenMaterialsMode plus the entry/global-doc/job-doc/document shapes
matching the backend's JSON contract exactly (routes/hidden_materials_store.py).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: HiddenMaterialsRepository (paths, parsing, effective visibility, file-fallback read)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsRepository.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsRepositoryTest.kt
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialEntry
import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import com.kkc.sheettracker.data.models.HiddenMaterialsGlobalDoc
import com.kkc.sheettracker.data.models.HiddenMaterialsJobDoc
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenMaterialsRepositoryTest {

    @Test
    fun globalAndJobPathsAreNamespacedByMode() {
        val baseDir = File("Y:/Ready Jobs")

        assertEquals(
            File(baseDir, ".metadata/hardwoods/hidden_materials_global.json"),
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS)
        )
        assertEquals(
            File(baseDir, ".metadata/specialty/hidden_materials_global.json"),
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.SPECIALTY)
        )
        assertEquals(
            File(baseDir, "123 - Job/.metadata/hardwoods/hidden_materials.json"),
            hiddenMaterialsJobPath(baseDir, HiddenMaterialsMode.HARDWOODS, "123 - Job")
        )
        assertEquals(
            File(baseDir, "123 - Job/.metadata/specialty/hidden_materials.json"),
            hiddenMaterialsJobPath(baseDir, HiddenMaterialsMode.SPECIALTY, "123 - Job")
        )
    }

    @Test
    fun fetchDocument_defaultsToEmptyWhenNeitherFileExists() {
        val baseDir = Files.createTempDirectory("hidden-materials-repo-empty").toFile()
        val repository = HiddenMaterialsRepository(baseDir)

        val document = repository.fetchDocument(HiddenMaterialsMode.HARDWOODS, "123 - Job")

        assertEquals(HiddenMaterialsDocument(), document)
    }

    @Test
    fun fetchDocument_readsGlobalListAndTheJobsOwnOverrideFile() {
        val baseDir = Files.createTempDirectory("hidden-materials-repo-valid").toFile()
        writeJson(
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS),
            """{"entries":[{"docType":"NAILER_CUT_LIST","material":"Maple","hiddenAt":"t1","tabletId":"tablet-1"}]}"""
        )
        writeJson(
            hiddenMaterialsJobPath(baseDir, HiddenMaterialsMode.HARDWOODS, "123 - Job"),
            """{"hides":[{"docType":"DOOR_LIST","material":"Oak","hiddenAt":"t2","tabletId":"tablet-1"}],"unhides":[]}"""
        )
        val repository = HiddenMaterialsRepository(baseDir)

        val document = repository.fetchDocument(HiddenMaterialsMode.HARDWOODS, "123 - Job")

        assertEquals("Maple", document.global.entries.single().material)
        assertEquals("Oak", document.jobs.getValue("123 - Job").hides.single().material)
    }

    @Test
    fun fetchDocument_omitsTheJobEntryWhenItsOverrideFileIsMissing() {
        val baseDir = Files.createTempDirectory("hidden-materials-repo-no-job-file").toFile()
        writeJson(
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS),
            """{"entries":[]}"""
        )
        val repository = HiddenMaterialsRepository(baseDir)

        val document = repository.fetchDocument(HiddenMaterialsMode.HARDWOODS, "123 - Job")

        assertTrue(document.jobs.isEmpty())
    }

    @Test
    fun fetchDocument_degradesToEmptyOnCorruptJson() {
        val baseDir = Files.createTempDirectory("hidden-materials-repo-corrupt").toFile()
        val globalFile = hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS)
        globalFile.parentFile?.mkdirs()
        globalFile.writeText("not json")
        val repository = HiddenMaterialsRepository(baseDir)

        val document = repository.fetchDocument(HiddenMaterialsMode.HARDWOODS, "123 - Job")

        assertEquals(HiddenMaterialsGlobalDoc(), document.global)
    }

    @Test
    fun fetchDocument_hardwoodsAndSpecialtyNeverShareState() {
        val baseDir = Files.createTempDirectory("hidden-materials-repo-segregation").toFile()
        writeJson(
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS),
            """{"entries":[{"docType":"DOOR_CUT_LIST","material":"Oak","hiddenAt":"t1","tabletId":"tablet-1"}]}"""
        )
        val repository = HiddenMaterialsRepository(baseDir)

        val hardwoods = repository.fetchDocument(HiddenMaterialsMode.HARDWOODS, "123 - Job")
        val specialty = repository.fetchDocument(HiddenMaterialsMode.SPECIALTY, "123 - Job")

        assertEquals(1, hardwoods.global.entries.size)
        assertTrue(specialty.global.entries.isEmpty())
    }

    @Test
    fun isHiddenIn_trueWhenGloballyHidden() {
        val document = HiddenMaterialsDocument(
            global = HiddenMaterialsGlobalDoc(
                entries = listOf(HiddenMaterialEntry(docType = "NAILER_CUT_LIST", material = "Maple"))
            )
        )

        assertTrue(isHiddenIn(document, "123 - Job", "NAILER_CUT_LIST", "maple"))
    }

    @Test
    fun isHiddenIn_falseWhenJobUnhideOverridesGlobalHide() {
        val document = HiddenMaterialsDocument(
            global = HiddenMaterialsGlobalDoc(
                entries = listOf(HiddenMaterialEntry(docType = "NAILER_CUT_LIST", material = "Maple"))
            ),
            jobs = mapOf(
                "123 - Job" to HiddenMaterialsJobDoc(
                    unhides = listOf(HiddenMaterialEntry(docType = "NAILER_CUT_LIST", material = "Maple"))
                )
            )
        )

        assertFalse(isHiddenIn(document, "123 - Job", "NAILER_CUT_LIST", "Maple"))
        // A different job is unaffected by the first job's override.
        assertTrue(isHiddenIn(document, "456 - Other Job", "NAILER_CUT_LIST", "Maple"))
    }

    @Test
    fun isHiddenIn_trueWhenJobOnlyHideWithoutGlobal() {
        val document = HiddenMaterialsDocument(
            jobs = mapOf(
                "123 - Job" to HiddenMaterialsJobDoc(
                    hides = listOf(HiddenMaterialEntry(docType = "DOOR_LIST", material = "Cherry"))
                )
            )
        )

        assertTrue(isHiddenIn(document, "123 - Job", "DOOR_LIST", "Cherry"))
        assertFalse(isHiddenIn(document, "456 - Other Job", "DOOR_LIST", "Cherry"))
    }

    @Test
    fun isHiddenIn_falseByDefault() {
        assertFalse(isHiddenIn(HiddenMaterialsDocument(), "123 - Job", "DOOR_LIST", "Cherry"))
    }

    private fun writeJson(file: File, json: String) {
        file.parentFile?.mkdirs()
        file.writeText(json)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterialsRepositoryTest"
```

Expected: FAIL to compile (`HiddenMaterialsRepository`, `hiddenMaterialsGlobalPath`, `hiddenMaterialsJobPath`, `isHiddenIn` do not exist yet)

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsRepository.kt
package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HiddenMaterialEntry
import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import com.kkc.sheettracker.data.models.HiddenMaterialsGlobalDoc
import com.kkc.sheettracker.data.models.HiddenMaterialsJobDoc
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import java.io.File

private val hiddenMaterialsGson = Gson()

private val HIDDEN_MATERIALS_MODE_SUBDIR = mapOf(
    HiddenMaterialsMode.HARDWOODS to "hardwoods",
    HiddenMaterialsMode.SPECIALTY to "specialty"
)

internal fun hiddenMaterialsModeSubdir(mode: HiddenMaterialsMode): String =
    HIDDEN_MATERIALS_MODE_SUBDIR.getValue(mode)

fun hiddenMaterialsGlobalPath(baseDir: File, mode: HiddenMaterialsMode): File =
    File(baseDir, ".metadata/${hiddenMaterialsModeSubdir(mode)}/hidden_materials_global.json")

fun hiddenMaterialsJobPath(baseDir: File, mode: HiddenMaterialsMode, jobFolderName: String): File =
    File(baseDir, "$jobFolderName/.metadata/${hiddenMaterialsModeSubdir(mode)}/hidden_materials.json")

/**
 * Parses the hidden-materials document JSON shape shared by the live WebSocket's
 * `hiddenMaterials` field and by [HiddenMaterialsRepository.fetchDocument]'s assembled shape.
 */
internal fun parseHiddenMaterialsDocument(json: String): HiddenMaterialsDocument =
    runCatching { hiddenMaterialsGson.fromJson(json, HiddenMaterialsDocument::class.java) }
        .getOrNull() ?: HiddenMaterialsDocument()

private fun normalizeMaterial(material: String): String = material.trim().lowercase()

private fun entryKey(entry: HiddenMaterialEntry): Pair<String, String> =
    entry.docType to normalizeMaterial(entry.material)

/**
 * Effective-visibility rule from the 2026-09-08 hidden-materials design doc: a job's own
 * `unhides` always wins, then the global list or the job's own `hides` hide it, else it is
 * visible. Mirrors routes/hidden_materials_store.py's `is_hidden` on the backend -- keep the
 * two in sync if this logic ever changes.
 */
fun isHiddenIn(document: HiddenMaterialsDocument, jobFolderName: String, docType: String, material: String): Boolean {
    val key = docType to normalizeMaterial(material)
    val jobDoc = document.jobs[jobFolderName]
    if (jobDoc != null && jobDoc.unhides.any { entryKey(it) == key }) return false
    if (jobDoc != null && jobDoc.hides.any { entryKey(it) == key }) return true
    return document.global.entries.any { entryKey(it) == key }
}

/**
 * Reads hidden-materials state from the shared network drive: the mode's global auto-hide list
 * plus one job's override file. Storage paths match hidden_materials_global_path/job_path on
 * the backend (routes/hidden_materials_store.py). Written by Hours Tracker; read-only on the
 * tablet -- see kkc-metadata-map's Ownership Map.
 * Call on Dispatchers.IO.
 */
class HiddenMaterialsRepository(private val baseDir: File) {

    /**
     * Assembles a [HiddenMaterialsDocument] scoped to one job: the mode's full global list, plus
     * that one job's override file (if any) under `jobs[jobFolderName]`. This is a strict subset
     * of the live channel's broadcast document (which includes every job with an override), but
     * [isHiddenIn] only ever needs the current job's entry, so scanning every job folder on the
     * tablet (unlike the backend, which owns the whole tree) would be needless I/O.
     */
    fun fetchDocument(mode: HiddenMaterialsMode, jobFolderName: String): HiddenMaterialsDocument {
        val global = readGlobal(mode)
        val jobDoc = readJob(mode, jobFolderName)
        val jobs = if (jobDoc == HiddenMaterialsJobDoc()) emptyMap() else mapOf(jobFolderName to jobDoc)
        return HiddenMaterialsDocument(global = global, jobs = jobs)
    }

    private fun readGlobal(mode: HiddenMaterialsMode): HiddenMaterialsGlobalDoc {
        val file = hiddenMaterialsGlobalPath(baseDir, mode)
        if (!file.exists() || !file.isFile) return HiddenMaterialsGlobalDoc()
        return runCatching {
            hiddenMaterialsGson.fromJson(file.readText(), HiddenMaterialsGlobalDoc::class.java)
        }.getOrNull() ?: HiddenMaterialsGlobalDoc()
    }

    private fun readJob(mode: HiddenMaterialsMode, jobFolderName: String): HiddenMaterialsJobDoc {
        val file = hiddenMaterialsJobPath(baseDir, mode, jobFolderName)
        if (!file.exists() || !file.isFile) return HiddenMaterialsJobDoc()
        return runCatching {
            hiddenMaterialsGson.fromJson(file.readText(), HiddenMaterialsJobDoc::class.java)
        }.getOrNull() ?: HiddenMaterialsJobDoc()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterialsRepositoryTest"
```

Expected: all tests passed

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsRepository.kt app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsRepositoryTest.kt
git commit -m "$(cat <<'EOF'
feat: add HiddenMaterialsRepository with effective-visibility rule

File-fallback reader (global list + one job's override file) plus
the three-tier is_hidden rule ported from the backend, and mode-
namespaced path resolution matching routes/hidden_materials_store.py.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: HiddenMaterialsRequestStore (durable sidecar writer)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsRequestStore.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsRequestStoreTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsRequestStoreTest.kt
package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenMaterialsRequestStoreTest {

    @Test
    fun writeRequest_globalScope_writesToTheModesGlobalDirWithExpectedContents() {
        val baseDir = Files.createTempDirectory("hidden-materials-request-global").toFile()
        val store = HiddenMaterialsRequestStore(baseDir)

        store.writeRequest(
            mode = HiddenMaterialsMode.HARDWOODS,
            action = "hide",
            scope = "global",
            jobId = null,
            docType = "NAILER_CUT_LIST",
            material = "Maple",
            tabletId = "tablet-1",
            requestedAt = "2026-09-08T18:00:00Z"
        )

        val file = File(
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS).parentFile,
            "hidden_materials_request.tablet-1.json"
        )
        assertTrue("expected per-tablet request file to exist", file.exists())
        val payload = Gson().fromJson(file.readText(), HiddenMaterialsRequest::class.java)
        assertEquals("HARDWOODS", payload.mode)
        assertEquals("hide", payload.action)
        assertEquals("global", payload.scope)
        assertEquals("NAILER_CUT_LIST", payload.docType)
        assertEquals("Maple", payload.material)
        assertEquals("tablet-1", payload.tabletId)
        assertEquals(null, payload.jobId)
        assertEquals("2026-09-08T18:00:00Z", payload.requestedAt)
    }

    @Test
    fun writeRequest_jobScope_writesUnderThatJobsModeDir() {
        val baseDir = Files.createTempDirectory("hidden-materials-request-job").toFile()
        val store = HiddenMaterialsRequestStore(baseDir)

        store.writeRequest(
            mode = HiddenMaterialsMode.SPECIALTY,
            action = "hide",
            scope = "job",
            jobId = "123 - Job",
            docType = "DOOR_LIST",
            material = "Oak",
            tabletId = "tablet-1",
            requestedAt = "2026-09-08T18:00:00Z"
        )

        val file = File(
            hiddenMaterialsJobPath(baseDir, HiddenMaterialsMode.SPECIALTY, "123 - Job").parentFile,
            "hidden_materials_request.tablet-1.json"
        )
        assertTrue("expected per-tablet request file to exist", file.exists())
        val payload = Gson().fromJson(file.readText(), HiddenMaterialsRequest::class.java)
        assertEquals("123 - Job", payload.jobId)
    }

    @Test
    fun writeRequest_overwritesThisTabletsOwnPriorRequest() {
        val baseDir = Files.createTempDirectory("hidden-materials-request-overwrite").toFile()
        val store = HiddenMaterialsRequestStore(baseDir)

        store.writeRequest(
            mode = HiddenMaterialsMode.HARDWOODS, action = "hide", scope = "global", jobId = null,
            docType = "NAILER_CUT_LIST", material = "Maple", tabletId = "tablet-1", requestedAt = "t1"
        )
        store.writeRequest(
            mode = HiddenMaterialsMode.HARDWOODS, action = "unhide", scope = "global", jobId = null,
            docType = "NAILER_CUT_LIST", material = "Maple", tabletId = "tablet-1", requestedAt = "t2"
        )

        val file = File(
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS).parentFile,
            "hidden_materials_request.tablet-1.json"
        )
        val payload = Gson().fromJson(file.readText(), HiddenMaterialsRequest::class.java)
        assertEquals("unhide", payload.action)
        assertEquals("t2", payload.requestedAt)
    }

    @Test
    fun writeRequest_twoTabletsQueuingProduceDistinctRequestFiles() {
        val baseDir = Files.createTempDirectory("hidden-materials-request-two-tablets").toFile()
        val store = HiddenMaterialsRequestStore(baseDir)

        store.writeRequest(
            mode = HiddenMaterialsMode.HARDWOODS, action = "hide", scope = "global", jobId = null,
            docType = "NAILER_CUT_LIST", material = "Maple", tabletId = "tablet-a", requestedAt = "t1"
        )
        store.writeRequest(
            mode = HiddenMaterialsMode.HARDWOODS, action = "hide", scope = "global", jobId = null,
            docType = "DOOR_LIST", material = "Oak", tabletId = "tablet-b", requestedAt = "t2"
        )

        val dir = hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS).parentFile
        assertTrue(File(dir, "hidden_materials_request.tablet-a.json").exists())
        assertTrue(File(dir, "hidden_materials_request.tablet-b.json").exists())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterialsRequestStoreTest"
```

Expected: FAIL to compile (`HiddenMaterialsRequestStore`, `HiddenMaterialsRequest` do not exist yet)

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsRequestStore.kt
package com.kkc.sheettracker.data

import com.google.gson.GsonBuilder
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import java.io.File

/**
 * The tablet's durable backup write for a hide/unhide action, consumed by Hours Tracker's
 * sidecar-request poller (main_v2.py's `_apply_hidden_materials_request`) when the fast REST
 * path (AdminSyncClient.applyHiddenMaterialsAction) can't be reached. Written beside the master
 * file it targets -- the mode's global dir for `scope == "global"`, or that job's mode dir for
 * `scope == "job"` -- one file per tablet (`hidden_materials_request.<tabletId>.json`) so two
 * tablets queuing an edit before the same poll cycle never collide, matching
 * ProductionOrderRequestStore's pattern (see METADATA_AUDIT.md M-04).
 */
data class HiddenMaterialsRequest(
    val mode: String,
    val action: String,
    val scope: String,
    val docType: String,
    val material: String,
    val tabletId: String,
    val jobId: String? = null,
    val requestedAt: String
)

class HiddenMaterialsRequestStore(private val baseDir: File) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** Atomically writes this tablet's own request file (temp + ATOMIC_MOVE, see AtomicFileWriter). */
    fun writeRequest(
        mode: HiddenMaterialsMode,
        action: String,
        scope: String,
        jobId: String?,
        docType: String,
        material: String,
        tabletId: String,
        requestedAt: String
    ) {
        val payload = HiddenMaterialsRequest(
            mode = mode.name,
            action = action,
            scope = scope,
            docType = docType,
            material = material,
            tabletId = tabletId,
            jobId = jobId,
            requestedAt = requestedAt
        )
        val dir = if (scope == "global") {
            hiddenMaterialsGlobalPath(baseDir, mode).parentFile!!
        } else {
            requireNotNull(jobId) { "job scope requires jobId" }
            hiddenMaterialsJobPath(baseDir, mode, jobId).parentFile!!
        }
        val dest = File(dir, "hidden_materials_request.$tabletId.json")
        atomicWriteFile(dest, gson.toJson(payload))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterialsRequestStoreTest"
```

Expected: all tests passed

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsRequestStore.kt app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsRequestStoreTest.kt
git commit -m "$(cat <<'EOF'
feat: add HiddenMaterialsRequestStore durable sidecar writer

Per-tablet request file written beside the master file it targets
(global dir or the job's mode dir), mirroring
ProductionOrderRequestStore's per-tablet-filename pattern.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: AdminSyncClient fast-path method

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/AdminSyncClient.kt`
- Test: additions to `app/src/test/java/com/kkc/sheettracker/data/AdminSyncClientTest.kt`

- [ ] **Step 1: Write the failing tests**

Read the current `app/src/test/java/com/kkc/sheettracker/data/AdminSyncClientTest.kt` first — it already has a `server`/`client` JUnit `@Before`/`@After` fixture using `MockWebServer` (`server = MockWebServer(); server.start(); client = AdminSyncClient(server.url("/").toString().trimEnd('/'))`). Add an import for `com.kkc.sheettracker.data.models.HiddenMaterialsMode` alongside the file's existing imports if it isn't already present, then add these three tests at the end of the class, immediately before its closing `}`:

```kotlin
    @Test
    fun `applyHiddenMaterialsAction returns true on success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"applied":true}"""))

        val result = client.applyHiddenMaterialsAction(
            mode = HiddenMaterialsMode.HARDWOODS,
            action = "hide",
            scope = "global",
            docType = "NAILER_CUT_LIST",
            material = "Maple",
            tabletId = "tablet-1",
            jobId = null,
            requestedAt = "2026-09-08T18:00:00Z"
        )

        assertTrue(result)
        val recorded = server.takeRequest()
        assertEquals("/api/admin-sync/hardwoods-hidden-materials", recorded.path)
        val bodyText = recorded.body.readUtf8()
        assertTrue(bodyText.contains("\"mode\":\"HARDWOODS\""))
        assertTrue(bodyText.contains("\"tabletId\":\"tablet-1\""))
        assertTrue(bodyText.contains("\"requestedAt\":\"2026-09-08T18:00:00Z\""))
        assertFalse(bodyText.contains("jobId"))
    }

    @Test
    fun `applyHiddenMaterialsAction includes jobId when scope is job`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"applied":true}"""))

        client.applyHiddenMaterialsAction(
            mode = HiddenMaterialsMode.SPECIALTY,
            action = "hide",
            scope = "job",
            docType = "DOOR_LIST",
            material = "Oak",
            tabletId = "tablet-1",
            jobId = "123 - Job",
            requestedAt = "2026-09-08T18:00:00Z"
        )

        val bodyText = server.takeRequest().body.readUtf8()
        assertTrue(bodyText.contains("\"jobId\":\"123 - Job\""))
    }

    @Test
    fun `applyHiddenMaterialsAction returns false on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))

        val result = client.applyHiddenMaterialsAction(
            mode = HiddenMaterialsMode.HARDWOODS,
            action = "hide",
            scope = "global",
            docType = "NAILER_CUT_LIST",
            material = "Maple",
            tabletId = "tablet-1",
            jobId = null,
            requestedAt = "2026-09-08T18:00:00Z"
        )

        assertFalse(result)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.AdminSyncClientTest"
```

Expected: FAIL to compile (`applyHiddenMaterialsAction` does not exist yet)

- [ ] **Step 3: Write the implementation**

Read the current `app/src/main/java/com/kkc/sheettracker/data/AdminSyncClient.kt` first. Add this method inside the `AdminSyncClient` class, after `applyDeliverySchedule` and before the class's closing `}`:

```kotlin
    /**
     * Fast path for a tablet-authored hide/unhide, covering both Hardwoods and Specialty (see
     * `mode`). Returns true on success, false on ANY failure (caller should fall back to
     * HiddenMaterialsRequestStore) -- same contract as applyJobBoardEdits, no retry loop here.
     */
    suspend fun applyHiddenMaterialsAction(
        mode: com.kkc.sheettracker.data.models.HiddenMaterialsMode,
        action: String,
        scope: String,
        docType: String,
        material: String,
        tabletId: String,
        jobId: String?,
        requestedAt: String
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("mode", mode.name)
            put("action", action)
            put("scope", scope)
            put("docType", docType)
            put("material", material)
            put("tabletId", tabletId)
            put("requestedAt", requestedAt)
            if (jobId != null) put("jobId", jobId)
        }.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/admin-sync/hardwoods-hidden-materials")
            .post(body)
            .build()
        runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.AdminSyncClientTest"
```

Expected: all tests passed (existing production-order/job-board/delivery-schedule tests plus the 3 new ones)

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/AdminSyncClient.kt app/src/test/java/com/kkc/sheettracker/data/AdminSyncClientTest.kt
git commit -m "$(cat <<'EOF'
feat: add AdminSyncClient.applyHiddenMaterialsAction fast path

POST /api/admin-sync/hardwoods-hidden-materials, same
succeed-true/fail-false-no-retry contract as the sibling
production-order/job-board/delivery-schedule methods.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: HiddenMaterialsLiveClient (WebSocket)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsLiveClient.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsLiveClientTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsLiveClientTest.kt
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HiddenMaterialsLiveClientTest {

    @Test
    fun `connects to hidden materials live URL and sends hello with mode and tablet id`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedRequest = AtomicReference<Request>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedRequest = capturedRequest,
            capturedListener = capturedListener
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onOpen(fakeSocket, mock())

        assertEquals(
            "http://192.168.1.15:47821/api/hidden-materials/live",
            capturedRequest.get().url.toString()
        )
        verify(fakeSocket).send("""{"type":"hello","mode":"HARDWOODS","tabletId":"tablet-7"}""")
        client.stop()
    }

    @Test
    fun `dispatches valid snapshot and reports connected`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val documents = CopyOnWriteArrayList<HiddenMaterialsDocument>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onDocument = { documents.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"snapshot","revision":1,"hiddenMaterials":{"global":{"entries":[{"docType":"NAILER_CUT_LIST","material":"Maple"}]},"jobs":{}}}"""
        )

        assertEquals("Maple", documents.single().global.entries.single().material)
        assertEquals(listOf(true), connectionStates)
        client.stop()
    }

    @Test
    fun `dispatches update frames without changing connection state`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val documents = CopyOnWriteArrayList<HiddenMaterialsDocument>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onDocument = { documents.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"snapshot","revision":1,"hiddenMaterials":{"global":{"entries":[]},"jobs":{}}}"""
        )
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"hiddenMaterials","revision":2,"hiddenMaterials":{"global":{"entries":[{"docType":"DOOR_LIST","material":"Oak"}]},"jobs":{}}}"""
        )

        assertEquals(2, documents.size)
        assertEquals("Oak", documents[1].global.entries.single().material)
        assertEquals(listOf(true), connectionStates)
        client.stop()
    }

    @Test
    fun `does not connect until a valid initial snapshot arrives`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val documents = CopyOnWriteArrayList<HiddenMaterialsDocument>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onDocument = { documents.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"hiddenMaterials","revision":1,"hiddenMaterials":{"global":{"entries":[]},"jobs":{}}}"""
        )
        assertTrue(documents.isEmpty())
        assertTrue(connectionStates.isEmpty())

        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"snapshot","revision":2,"hiddenMaterials":{"global":{"entries":[]},"jobs":{}}}"""
        )
        assertEquals(1, documents.size)
        assertEquals(listOf(true), connectionStates)
        client.stop()
    }

    @Test
    fun `applies update frames only when their revision is strictly newer`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val documents = CopyOnWriteArrayList<HiddenMaterialsDocument>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onDocument = { documents.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        val listener = capturedListener.get()
        listener.onMessage(
            fakeSocket,
            """{"type":"snapshot","revision":5,"hiddenMaterials":{"global":{"entries":[{"docType":"NAILER_CUT_LIST","material":"snapshot"}]},"jobs":{}}}"""
        )
        listener.onMessage(
            fakeSocket,
            """{"type":"hiddenMaterials","revision":5,"hiddenMaterials":{"global":{"entries":[{"docType":"NAILER_CUT_LIST","material":"duplicate"}]},"jobs":{}}}"""
        )
        listener.onMessage(
            fakeSocket,
            """{"type":"hiddenMaterials","revision":4,"hiddenMaterials":{"global":{"entries":[{"docType":"NAILER_CUT_LIST","material":"older"}]},"jobs":{}}}"""
        )
        listener.onMessage(
            fakeSocket,
            """{"type":"hiddenMaterials","revision":6,"hiddenMaterials":{"global":{"entries":[{"docType":"NAILER_CUT_LIST","material":"newer"}]},"jobs":{}}}"""
        )

        assertEquals(
            listOf("snapshot", "newer"),
            documents.map { it.global.entries.single().material }
        )
        client.stop()
    }

    @Test
    fun `not running reports disconnected`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(fakeSocket, """{"type":"not_running"}""")

        assertEquals(listOf(false), connectionStates)
        client.stop()
    }

    @Test
    fun `error reports disconnected without delivering a document`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val documents = CopyOnWriteArrayList<HiddenMaterialsDocument>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onDocument = { documents.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        capturedListener.get().onMessage(
            fakeSocket,
            """{"type":"error","message":"unknown mode"}"""
        )

        assertTrue(documents.isEmpty())
        assertEquals(listOf(false), connectionStates)
        client.stop()
    }

    @Test
    fun `close reports disconnected and reconnects`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val connectAttempts = AtomicInteger()
        val client = client(
            fakeSocket = fakeSocket,
            onConnectionState = { connectionStates.add(it) },
            reconnectDelayMs = { 0L },
            webSocketFactory = { _, listener ->
                connectAttempts.incrementAndGet()
                listeners.add(listener)
                fakeSocket
            }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onClosed(fakeSocket, 1001, "gone")

        waitUntil { connectAttempts.get() == 2 }
        assertEquals(listOf(false), connectionStates)
        client.stop()
    }

    @Test
    fun `failure reports disconnected and reconnects`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val connectAttempts = AtomicInteger()
        val client = client(
            fakeSocket = fakeSocket,
            onConnectionState = { connectionStates.add(it) },
            reconnectDelayMs = { 0L },
            webSocketFactory = { _, listener ->
                connectAttempts.incrementAndGet()
                listeners.add(listener)
                fakeSocket
            }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onFailure(fakeSocket, RuntimeException("boom"), null)

        waitUntil { connectAttempts.get() == 2 }
        assertEquals(listOf(false), connectionStates)
        client.stop()
    }

    @Test
    fun `backoff attempt counter resets after valid snapshot`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val recordedAttempts = CopyOnWriteArrayList<Int>()
        val client = client(
            fakeSocket = fakeSocket,
            reconnectDelayMs = { attempt -> recordedAttempts.add(attempt); 0L },
            webSocketFactory = { _, listener ->
                listeners.add(listener)
                fakeSocket
            }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onFailure(fakeSocket, RuntimeException("first"), null)
        waitUntil { listeners.size == 2 }
        listeners[1].onMessage(
            fakeSocket,
            """{"type":"snapshot","revision":1,"hiddenMaterials":{"global":{"entries":[]},"jobs":{}}}"""
        )
        listeners[1].onFailure(fakeSocket, RuntimeException("second"), null)

        waitUntil { recordedAttempts.size >= 2 }
        assertEquals(0, recordedAttempts[0])
        assertEquals(0, recordedAttempts[1])
        client.stop()
    }

    @Test
    fun `stop cancels pending reconnect`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val connectAttempts = AtomicInteger()
        val client = client(
            fakeSocket = fakeSocket,
            reconnectDelayMs = { 200L },
            webSocketFactory = { _, listener ->
                connectAttempts.incrementAndGet()
                listeners.add(listener)
                fakeSocket
            }
        )

        client.start()
        waitUntil { listeners.size == 1 }
        listeners[0].onFailure(fakeSocket, RuntimeException("boom"), null)
        client.stop()

        Thread.sleep(400L)
        assertEquals(1, connectAttempts.get())
    }

    @Test
    fun `concurrent starts create only one socket`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        val client = client(
            fakeSocket = fakeSocket,
            webSocketFactory = { _, listener ->
                listeners.add(listener)
                fakeSocket
            }
        )
        val starts = (1..8).map { Thread { client.start() } }

        starts.forEach(Thread::start)
        starts.forEach(Thread::join)
        waitUntil { listeners.size == 1 }

        Thread.sleep(100L)
        assertEquals(1, listeners.size)
        client.stop()
    }

    @Test
    fun `suppresses invalid or missing document frames`() = runBlocking {
        val fakeSocket = mock<WebSocket>()
        val capturedListener = AtomicReference<WebSocketListener>()
        val documents = CopyOnWriteArrayList<HiddenMaterialsDocument>()
        val connectionStates = CopyOnWriteArrayList<Boolean>()
        val client = client(
            fakeSocket = fakeSocket,
            capturedListener = capturedListener,
            onDocument = { documents.add(it) },
            onConnectionState = { connectionStates.add(it) }
        )

        client.start()
        waitUntil { capturedListener.get() != null }
        val listener = capturedListener.get()
        listener.onMessage(fakeSocket, "not json")
        listener.onMessage(fakeSocket, """{"type":"snapshot"}""")
        listener.onMessage(fakeSocket, """{"type":"hiddenMaterials","hiddenMaterials":null}""")
        listener.onMessage(fakeSocket, """{"type":"snapshot","revision":1,"hiddenMaterials":{"jobs":{}}}""")
        listener.onMessage(fakeSocket, """{"type":"other","hiddenMaterials":{"global":{"entries":[]},"jobs":{}}}""")

        assertTrue(documents.isEmpty())
        assertTrue(connectionStates.isEmpty())
        client.stop()
    }

    private fun client(
        fakeSocket: WebSocket,
        mode: HiddenMaterialsMode = HiddenMaterialsMode.HARDWOODS,
        capturedRequest: AtomicReference<Request> = AtomicReference(),
        capturedListener: AtomicReference<WebSocketListener> = AtomicReference(),
        onDocument: (HiddenMaterialsDocument) -> Unit = {},
        onConnectionState: (Boolean) -> Unit = {},
        reconnectDelayMs: (Int) -> Long = { 0L },
        webSocketFactory: ((Request, WebSocketListener) -> WebSocket)? = null
    ): HiddenMaterialsLiveClient {
        val factory = webSocketFactory ?: { request, listener ->
            capturedRequest.set(request)
            capturedListener.set(listener)
            fakeSocket
        }
        return HiddenMaterialsLiveClient(
            config = configWithIp("192.168.1.15"),
            mode = mode,
            tabletId = "tablet-7",
            onDocument = onDocument,
            onConnectionState = onConnectionState,
            reconnectDelayMs = reconnectDelayMs,
            webSocketFactory = factory
        )
    }

    private fun configWithIp(ip: String): AdminSyncConfig = mock {
        onBlocking { getManualIp() } doReturn ip
    }

    private fun waitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20L)
        }
        throw AssertionError("Timed out waiting for condition")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterialsLiveClientTest"
```

Expected: FAIL to compile (`HiddenMaterialsLiveClient` does not exist yet)

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsLiveClient.kt
package com.kkc.sheettracker.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

internal fun nextHiddenMaterialsBackoffDelayMs(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

internal data class HiddenMaterialsEnvelope(
    val type: String? = null,
    val revision: Long? = null,
    val hiddenMaterials: JsonObject? = null,
    val message: String? = null
)

/**
 * Connects to Hours Tracker's `/api/hidden-materials/live` read-only WebSocket for one mode
 * (Hardwoods or Specialty -- see the 2026-09-08 hidden-materials design doc's mode segregation;
 * a tablet screen showing both modes would run two independent instances of this client). The
 * socket carries complete document snapshots: the initial `snapshot` frame and subsequent
 * `hiddenMaterials` replacement frames. A connection is considered live only after a valid
 * initial snapshot has been parsed and delivered. Mirrors DeliveryScheduleLiveClient's
 * lifecycle/backoff/reconnect handling exactly -- see its kdoc for the full rationale.
 */
class HiddenMaterialsLiveClient(
    private val config: AdminSyncConfig,
    private val mode: HiddenMaterialsMode,
    private val tabletId: String,
    private val onDocument: (HiddenMaterialsDocument) -> Unit,
    private val onConnectionState: (connected: Boolean) -> Unit,
    private val reconnectDelayMs: (attempt: Int) -> Long = ::nextHiddenMaterialsBackoffDelayMs,
    private val webSocketFactory: (Request, WebSocketListener) -> WebSocket = { request, listener ->
        sharedClient.newWebSocket(request, listener)
    }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private var running = false
    private var generation = 0L
    private var socket: WebSocket? = null
    private var attempt = 0
    private var reconnectPending = false
    private var pendingJob: Job? = null

    companion object {
        private const val TAG = "HiddenMaterialsLiveClient"
        private val gson = Gson()
        private val sharedClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    fun start() {
        val startGeneration: Long
        synchronized(lifecycleLock) {
            if (running) return
            running = true
            generation += 1
            startGeneration = generation
            attempt = 0
            reconnectPending = false
            pendingJob?.cancel()
            pendingJob = null
        }
        connectNow(startGeneration)
    }

    fun stop() {
        val socketToClose: WebSocket?
        synchronized(lifecycleLock) {
            running = false
            generation += 1
            reconnectPending = false
            pendingJob?.cancel()
            pendingJob = null
            socketToClose = socket
            socket = null
        }
        socketToClose?.close(1000, "client stop")
    }

    private fun connectNow(expectedGeneration: Long) {
        synchronized(lifecycleLock) {
            if (!isCurrentGenerationLocked(expectedGeneration)) return
            pendingJob = scope.launch {
                connectForGeneration(expectedGeneration)
            }
        }
    }

    private suspend fun connectForGeneration(expectedGeneration: Long) {
        try {
            val baseUrl = buildAdminSyncUrl(config.getManualIp())
            if (baseUrl == null) {
                Log.d(TAG, "No server IP configured; skipping connect")
                scheduleReconnect(expectedGeneration)
                return
            }
            val wsUrl = baseUrl.replaceFirst("http://", "ws://") + "/api/hidden-materials/live"
            val request = Request.Builder().url(wsUrl).build()
            val listener = Listener(expectedGeneration)
            synchronized(lifecycleLock) {
                if (!isCurrentGenerationLocked(expectedGeneration)) return
            }
            val newSocket = webSocketFactory(request, listener)
            val closeImmediately = synchronized(lifecycleLock) {
                if (!isCurrentGenerationLocked(expectedGeneration)) {
                    true
                } else {
                    socket = newSocket
                    false
                }
            }
            if (closeImmediately) newSocket.close(1000, "stale client lifecycle")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "connectNow failed", e)
            scheduleReconnect(expectedGeneration)
        }
    }

    private fun scheduleReconnect(expectedGeneration: Long) {
        val currentAttempt: Int
        synchronized(lifecycleLock) {
            if (!isCurrentGenerationLocked(expectedGeneration)) return
            if (reconnectPending) {
                Log.d(TAG, "Reconnect already pending; ignoring duplicate schedule request")
                return
            }
            reconnectPending = true
            currentAttempt = attempt++
        }
        val delayMs = reconnectDelayMs(currentAttempt)
        Log.d(TAG, "Scheduling reconnect: attempt=$currentAttempt delayMs=$delayMs")
        synchronized(lifecycleLock) {
            if (!isCurrentGenerationLocked(expectedGeneration) || !reconnectPending) return
            pendingJob = scope.launch {
                delay(delayMs)
                val shouldConnect = synchronized(lifecycleLock) {
                    if (!isCurrentGenerationLocked(expectedGeneration) || !reconnectPending) {
                        false
                    } else {
                        reconnectPending = false
                        true
                    }
                }
                if (shouldConnect) connectNow(expectedGeneration)
            }
        }
    }

    private fun isCurrentGenerationLocked(expectedGeneration: Long): Boolean =
        running && generation == expectedGeneration

    private fun isCurrentSocket(expectedGeneration: Long, callbackSocket: WebSocket): Boolean =
        synchronized(lifecycleLock) {
            isCurrentGenerationLocked(expectedGeneration) && socket === callbackSocket
        }

    private fun parseValidDocument(hiddenMaterials: JsonObject): HiddenMaterialsDocument? {
        if (hiddenMaterials.get("global")?.isJsonObject != true) return null
        return runCatching { parseHiddenMaterialsDocument(gson.toJson(hiddenMaterials)) }.getOrNull()
    }

    private inner class Listener(
        private val listenerGeneration: Long
    ) : WebSocketListener() {
        private var receivedInitialSnapshot = false
        private var lastRevision: Long? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrentSocket(listenerGeneration, webSocket)) return
            Log.d(TAG, "WebSocket opened, sending hello")
            webSocket.send(gson.toJson(mapOf("type" to "hello", "mode" to mode.name, "tabletId" to tabletId)))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrentSocket(listenerGeneration, webSocket)) return
            val envelope = runCatching {
                gson.fromJson(text, HiddenMaterialsEnvelope::class.java)
            }.getOrNull() ?: return

            when (envelope.type) {
                "snapshot", "hiddenMaterials" -> {
                    val revision = envelope.revision
                    if (revision == null || revision < 0L) {
                        Log.d(TAG, "Ignoring ${envelope.type} frame without a valid revision")
                        return
                    }
                    if (envelope.type == "snapshot" && receivedInitialSnapshot) {
                        Log.d(TAG, "Ignoring duplicate snapshot in an established session")
                        return
                    }
                    if (envelope.type == "hiddenMaterials" && !receivedInitialSnapshot) {
                        Log.d(TAG, "Ignoring update before initial snapshot")
                        return
                    }
                    if (envelope.type == "hiddenMaterials" && revision <= (lastRevision ?: -1L)) {
                        Log.d(TAG, "Ignoring stale revision=$revision lastRevision=$lastRevision")
                        return
                    }
                    val documentJson = envelope.hiddenMaterials ?: return
                    val document = parseValidDocument(documentJson) ?: return
                    if (!isCurrentSocket(listenerGeneration, webSocket)) return
                    Log.d(TAG, "Received ${envelope.type} frame: revision=$revision")
                    lastRevision = revision
                    onDocument(document)
                    if (envelope.type == "snapshot") {
                        synchronized(lifecycleLock) {
                            if (!isCurrentGenerationLocked(listenerGeneration) || socket !== webSocket) return
                            attempt = 0
                        }
                        receivedInitialSnapshot = true
                        onConnectionState(true)
                    }
                }
                "not_running", "error" -> {
                    if (isCurrentSocket(listenerGeneration, webSocket)) onConnectionState(false)
                }
                else -> Unit
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrentSocket(listenerGeneration, webSocket)) return
            Log.d(TAG, "WebSocket closed: code=$code reason=$reason")
            synchronized(lifecycleLock) {
                if (!isCurrentGenerationLocked(listenerGeneration) || socket !== webSocket) return
                socket = null
            }
            onConnectionState(false)
            scheduleReconnect(listenerGeneration)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrentSocket(listenerGeneration, webSocket)) return
            Log.w(TAG, "Hidden materials socket failure", t)
            synchronized(lifecycleLock) {
                if (!isCurrentGenerationLocked(listenerGeneration) || socket !== webSocket) return
                socket = null
            }
            onConnectionState(false)
            scheduleReconnect(listenerGeneration)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterialsLiveClientTest"
```

Expected: all tests passed

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsLiveClient.kt app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsLiveClientTest.kt
git commit -m "$(cat <<'EOF'
feat: add HiddenMaterialsLiveClient WebSocket, one instance per mode

Mirrors DeliveryScheduleLiveClient's lifecycle/backoff/reconnect
handling exactly; hello carries mode (HARDWOODS/SPECIALTY) so the
backend's single shared channel returns the right mode's broadcasts.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: HiddenMaterialsStateStore (live + fallback arbitration)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsStateStore.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsStateStoreTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsStateStoreTest.kt
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialEntry
import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import com.kkc.sheettracker.data.models.HiddenMaterialsGlobalDoc
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenMaterialsStateStoreTest {

    private fun documentWith(material: String): HiddenMaterialsDocument =
        HiddenMaterialsDocument(
            global = HiddenMaterialsGlobalDoc(
                entries = listOf(HiddenMaterialEntry(docType = "NAILER_CUT_LIST", material = material))
            )
        )

    @Test
    fun coldStart_seedsFromFallbackLoader() {
        var fallback = documentWith("Maple")
        val store = HiddenMaterialsStateStore { fallback }

        assertEquals("Maple", store.document.value.global.entries.single().material)
        assertFalse(store.liveConnected)
    }

    @Test
    fun explicitInitialDocument_doesNotInvokeFallbackLoaderDuringConstruction() {
        var fallbackLoads = 0
        val store = HiddenMaterialsStateStore(
            fallbackLoader = { fallbackLoads += 1; documentWith("fallback") },
            initialDocument = documentWith("initial")
        )

        assertEquals(0, fallbackLoads)
        assertEquals("initial", store.document.value.global.entries.single().material)
    }

    @Test
    fun applyLive_replacesDocumentAndSetsConnected() {
        var fallback = documentWith("Maple")
        val store = HiddenMaterialsStateStore { fallback }

        store.applyLive(documentWith("Walnut"))

        assertEquals("Walnut", store.document.value.global.entries.single().material)
        assertTrue(store.liveConnected)
    }

    @Test
    fun refreshFallback_isNoOpWhileLiveConnected() {
        var fallback = documentWith("Maple")
        val store = HiddenMaterialsStateStore { fallback }

        store.applyLive(documentWith("Walnut"))
        fallback = documentWith("stale")
        store.refreshFallback()

        assertEquals("Walnut", store.document.value.global.entries.single().material)
        assertTrue(store.liveConnected)
    }

    @Test
    fun setLiveConnectedFalse_clearsFlagAndReloadsFallbackImmediately() {
        var fallback = documentWith("Maple")
        val store = HiddenMaterialsStateStore { fallback }

        store.applyLive(documentWith("Walnut"))
        fallback = documentWith("reloaded")
        store.setLiveConnected(false)

        assertFalse(store.liveConnected)
        assertEquals("reloaded", store.document.value.global.entries.single().material)
    }

    @Test
    fun refreshFallback_afterDisconnectReloadsAgain() {
        var fallback = documentWith("Maple")
        val store = HiddenMaterialsStateStore { fallback }

        store.applyLive(documentWith("Walnut"))
        store.setLiveConnected(false)
        fallback = documentWith("second reload")
        store.refreshFallback()

        assertEquals("second reload", store.document.value.global.entries.single().material)
        assertFalse(store.liveConnected)
    }

    @Test
    fun applyLive_afterDisconnectReplacesFallbackAndReconnects() {
        var fallback = documentWith("Maple")
        val store = HiddenMaterialsStateStore { fallback }

        store.applyLive(documentWith("Walnut"))
        store.setLiveConnected(false)
        store.applyLive(documentWith("live again"))

        assertEquals("live again", store.document.value.global.entries.single().material)
        assertTrue(store.liveConnected)
    }

    /**
     * Regression test for the atomicity fix in DeliveryScheduleStateStore, which this class
     * mirrors: a slow refreshFallback that is already mid-flight when a live update lands must
     * not clobber the live document once it finally commits.
     */
    @Test
    fun refreshFallback_doesNotOverwriteDocumentFromConcurrentApplyLive() {
        val fallbackThreadStartedLoading = CountDownLatch(1)
        val liveUpdateApplied = CountDownLatch(1)
        val fallback = documentWith("Maple")

        val store = HiddenMaterialsStateStore {
            if (Thread.currentThread().name == "fallback-refresh-thread") {
                fallbackThreadStartedLoading.countDown()
                assertTrue(liveUpdateApplied.await(5, TimeUnit.SECONDS))
            }
            fallback
        }

        val fallbackThread = Thread({ store.refreshFallback() }, "fallback-refresh-thread")
        fallbackThread.start()

        assertTrue(fallbackThreadStartedLoading.await(5, TimeUnit.SECONDS))
        store.applyLive(documentWith("live"))
        liveUpdateApplied.countDown()
        fallbackThread.join(5000)

        assertEquals("live", store.document.value.global.entries.single().material)
        assertTrue(store.liveConnected)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterialsStateStoreTest"
```

Expected: FAIL to compile (`HiddenMaterialsStateStore` does not exist yet)

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsStateStore.kt
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides which hidden-materials document (live WebSocket push vs. Syncthing-replicated file
 * fallback) is currently authoritative for one mode's tablet UI. Mirrors
 * DeliveryScheduleStateStore exactly -- see its kdoc for the full threading/concurrency
 * rationale, which applies identically here.
 *
 * Unlike DeliveryScheduleStateStore, there is no `applyImmediate` here: the hidden-materials
 * REST fast path's response carries no canonical document (just `{"applied": true}`), so a
 * successful call only means "don't fall back to the sidecar" -- the UI picks up the actual
 * change via the live push (which the backend sends synchronously, before the REST handler even
 * returns) or, if disconnected, the next [refreshFallback].
 */
class HiddenMaterialsStateStore(
    initialDocument: HiddenMaterialsDocument? = null,
    private val fallbackLoader: () -> HiddenMaterialsDocument
) {
    private val lock = Any()
    private var mutationVersion = 0L

    private val _document = MutableStateFlow(initialDocument ?: fallbackLoader())
    val document: StateFlow<HiddenMaterialsDocument> = _document.asStateFlow()

    @Volatile
    var liveConnected: Boolean = false
        private set

    /** Delivers a live document payload from the WebSocket and marks the connection as live. */
    fun applyLive(document: HiddenMaterialsDocument) {
        synchronized(lock) {
            _document.value = document
            liveConnected = true
            mutationVersion += 1
        }
    }

    /** Pure connection-state signal. Does not itself deliver a document payload. */
    fun setLiveConnected(value: Boolean) {
        synchronized(lock) {
            liveConnected = value
            mutationVersion += 1
        }
        if (!value) {
            refreshFallback()
        }
    }

    /**
     * Marks the live stream disconnected without reading the fallback files. Callers that are on
     * the main thread (for example, a Compose effect's disposal callback) can use this to publish
     * the state transition immediately, then schedule [refreshFallback] on an I/O dispatcher.
     */
    fun markLiveDisconnected() {
        synchronized(lock) {
            liveConnected = false
            mutationVersion += 1
        }
    }

    /**
     * Reloads the file fallback. No-ops while a live document is authoritative.
     *
     * Calls the (blocking) `fallbackLoader` synchronously on the calling thread -- must be
     * invoked off the main thread.
     *
     * The stale-data check is re-validated atomically at commit time, so a fallback load that was
     * already in flight when a concurrent [applyLive] lands will not overwrite the live document.
     */
    fun refreshFallback() {
        val loadVersion = synchronized(lock) {
            if (liveConnected) return
            mutationVersion
        }
        val loaded = fallbackLoader()
        synchronized(lock) {
            if (liveConnected || mutationVersion != loadVersion) return
            _document.value = loaded
            mutationVersion += 1
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterialsStateStoreTest"
```

Expected: all tests passed

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsStateStore.kt app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsStateStoreTest.kt
git commit -m "$(cat <<'EOF'
feat: add HiddenMaterialsStateStore live/fallback arbitration

Mirrors DeliveryScheduleStateStore's locking and atomicity guarantees
exactly, minus applyImmediate (the hidden-materials REST fast path
returns no canonical document to apply optimistically).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: HiddenMaterialsLifecycleGate + Binding

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsLifecycleGate.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsLifecycleGateTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsLifecycleGateTest.kt
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialEntry
import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import com.kkc.sheettracker.data.models.HiddenMaterialsGlobalDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenMaterialsLifecycleGateTest {

    private fun documentWith(material: String): HiddenMaterialsDocument =
        HiddenMaterialsDocument(
            global = HiddenMaterialsGlobalDoc(
                entries = listOf(HiddenMaterialEntry(docType = "NAILER_CUT_LIST", material = material))
            )
        )

    @Test
    fun startTokenBecomesStaleAfterStopAndSourceReplacement() {
        val gate = HiddenMaterialsLifecycleGate()
        val source = gate.bindSource()
        val start = gate.begin(source)

        assertTrue(gate.isCurrent(source, start))
        var starts = 0
        assertTrue(gate.runIfCurrent(source, start) { starts += 1 })
        assertEquals(1, starts)

        gate.stop(source)
        assertFalse(gate.isCurrent(source, start))
        assertFalse(gate.runIfCurrent(source, start) { starts += 1 })
        assertEquals(1, starts)

        val replacement = gate.bindSource()
        assertFalse(gate.isCurrent(source, start))
        val replacementStart = gate.begin(replacement)
        assertTrue(gate.isCurrent(replacement, replacementStart))
    }

    @Test
    fun disposalCleanupTokenIsInvalidatedByReplacement() {
        val gate = HiddenMaterialsLifecycleGate()
        val source = gate.bindSource()
        val cleanup = gate.dispose(source)

        assertTrue(gate.isCleanupCurrent(cleanup))

        gate.bindSource()
        assertFalse(gate.isCleanupCurrent(cleanup))
    }

    @Test
    fun staleClientDocumentAndConnectionCallbacksCannotMutateReplacementStore() {
        val gate = HiddenMaterialsLifecycleGate()
        val oldBinding = HiddenMaterialsClientBinding(gate)
        val store = HiddenMaterialsStateStore(
            initialDocument = documentWith("replacement"),
            fallbackLoader = { documentWith("fallback") }
        )
        val oldSource = gate.bindSource()
        oldBinding.bind(oldSource)
        val oldDocumentCallback = oldBinding.documentCallback(store)
        val oldConnectionCallback = oldBinding.connectionCallback(store)

        gate.dispose(oldSource)
        val replacementBinding = HiddenMaterialsClientBinding(gate)
        replacementBinding.bind(gate.bindSource())
        store.markLiveDisconnected()

        oldDocumentCallback(documentWith("stale"))
        oldConnectionCallback(true)

        assertEquals("replacement", store.document.value.global.entries.single().material)
        assertFalse(store.liveConnected)
    }

    @Test
    fun capturedCallbacksAfterStopCannotRestoreLiveStateOrReplaceFallback() {
        val gate = HiddenMaterialsLifecycleGate()
        val binding = HiddenMaterialsClientBinding(gate)
        val store = HiddenMaterialsStateStore(
            initialDocument = documentWith("initial"),
            fallbackLoader = { documentWith("fallback") }
        )
        val source = gate.bindSource()
        binding.bind(source)
        val documentCallback = binding.documentCallback(store)
        val connectionCallback = binding.connectionCallback(store)
        gate.begin(source)

        documentCallback(documentWith("live"))
        assertTrue(store.liveConnected)

        gate.stop(source)
        store.markLiveDisconnected()
        store.refreshFallback()
        documentCallback(documentWith("stale-after-stop"))
        connectionCallback(true)

        assertEquals("fallback", store.document.value.global.entries.single().material)
        assertFalse(store.liveConnected)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterialsLifecycleGateTest"
```

Expected: FAIL to compile (`HiddenMaterialsLifecycleGate`, `HiddenMaterialsClientBinding` do not exist yet)

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsLifecycleGate.kt
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import java.util.concurrent.atomic.AtomicLong

/**
 * Guards asynchronous hidden-materials lifecycle work against a stale client/effect instance.
 * Mirrors DeliveryScheduleLifecycleGate exactly -- see its kdoc for the full rationale, which
 * applies identically here. One gate per mode (Hardwoods and Specialty each get their own
 * instance, since each mode's live client/state store pair is fully independent).
 */
internal class HiddenMaterialsLifecycleGate {
    private val lock = Any()
    private var nextToken = 0L
    private var currentSourceToken = 0L
    private var currentLifecycleToken = 0L
    private var currentCleanupToken = 0L
    private var callbacksActive = false

    /** Claims the source identity for a newly installed lifecycle effect. */
    fun bindSource(): Long = synchronized(lock) {
        val token = ++nextToken
        currentSourceToken = token
        currentLifecycleToken = 0L
        currentCleanupToken = 0L
        callbacksActive = false
        token
    }

    /** Claims an ON_START interval for [sourceToken], or returns 0 if it is already stale. */
    fun begin(sourceToken: Long): Long = synchronized(lock) {
        if (sourceToken != currentSourceToken) return 0L
        val token = ++nextToken
        currentLifecycleToken = token
        currentCleanupToken = 0L
        callbacksActive = true
        token
    }

    /** Invalidates any in-flight ON_START work for [sourceToken]. */
    fun stop(sourceToken: Long): Long = synchronized(lock) {
        if (sourceToken != currentSourceToken) return 0L
        val token = ++nextToken
        currentLifecycleToken = token
        currentCleanupToken = 0L
        callbacksActive = false
        token
    }

    /** Invalidates [sourceToken] and returns a token for disposal-only cleanup work. */
    fun dispose(sourceToken: Long): Long = synchronized(lock) {
        if (sourceToken != currentSourceToken) return 0L
        val cleanupToken = ++nextToken
        currentSourceToken = cleanupToken
        currentLifecycleToken = 0L
        currentCleanupToken = cleanupToken
        callbacksActive = false
        cleanupToken
    }

    /** True only while the source and lifecycle interval are both still current. */
    fun isCurrent(sourceToken: Long, lifecycleToken: Long): Boolean = synchronized(lock) {
        sourceToken == currentSourceToken &&
            lifecycleToken != 0L &&
            lifecycleToken == currentLifecycleToken
    }

    /** Runs [action] only if a callback still belongs to the current client/effect source. */
    fun runIfSourceCurrent(sourceToken: Long, action: () -> Unit): Boolean = synchronized(lock) {
        if (sourceToken == 0L || sourceToken != currentSourceToken || !callbacksActive) return false
        action()
        true
    }

    /**
     * Runs [action] while holding the source/lifecycle guard, preventing disposal or stop from
     * interleaving between the final check and a client start.
     */
    fun runIfCurrent(sourceToken: Long, lifecycleToken: Long, action: () -> Unit): Boolean =
        synchronized(lock) {
            if (sourceToken != currentSourceToken ||
                lifecycleToken == 0L ||
                lifecycleToken != currentLifecycleToken
            ) {
                return false
            }
            action()
            true
        }

    /** True only while disposal cleanup has not been superseded by a replacement source. */
    fun isCleanupCurrent(cleanupToken: Long): Boolean = synchronized(lock) {
        cleanupToken != 0L && cleanupToken == currentCleanupToken
    }
}

/**
 * Identity captured by one HiddenMaterialsLiveClient instance. A replacement client gets a new
 * binding, so a callback that snapshots this token cannot be mistaken for the replacement source.
 */
internal class HiddenMaterialsClientBinding(
    private val lifecycleGate: HiddenMaterialsLifecycleGate
) {
    private val boundSourceToken = AtomicLong(0L)

    fun bind(sourceToken: Long) {
        require(sourceToken != 0L) { "source token must be non-zero" }
        check(boundSourceToken.compareAndSet(0L, sourceToken)) {
            "hidden materials client binding already claimed"
        }
    }

    /**
     * Produces the exact document callback supplied to this binding's live client. The lifecycle
     * gate and the store write run under the same lock, so source replacement cannot interleave
     * after the current-source check but before the shared store is mutated.
     */
    fun documentCallback(store: HiddenMaterialsStateStore): (HiddenMaterialsDocument) -> Unit = { document ->
        lifecycleGate.runIfSourceCurrent(boundSourceToken.get()) {
            store.applyLive(document)
        }
    }

    /** Produces the exact connection callback supplied to this binding's live client. */
    fun connectionCallback(store: HiddenMaterialsStateStore): (Boolean) -> Unit = { connected ->
        lifecycleGate.runIfSourceCurrent(boundSourceToken.get()) {
            store.setLiveConnected(connected)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterialsLifecycleGateTest"
```

Expected: all tests passed

- [ ] **Step 5: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsLifecycleGate.kt app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsLifecycleGateTest.kt
git commit -m "$(cat <<'EOF'
feat: add HiddenMaterialsLifecycleGate stale-callback guard

Mirrors DeliveryScheduleLifecycleGate exactly; one gate per mode
protects a HiddenMaterialsStateStore from a superseded live client's
in-flight callbacks after navigation/lifecycle changes.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: HiddenMaterialsVisibilityPreferencesStore ("show hidden materials" toggle)

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsVisibilityPreferencesStore.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsVisibilityPreferencesStoreTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsVisibilityPreferencesStoreTest.kt
package com.kkc.sheettracker.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenMaterialsVisibilityPreferencesStoreTest {

    @Test
    fun `defaults to false for both modes`() = runBlocking {
        val store = createStore()

        assertFalse(store.showHidden(HiddenMaterialsMode.HARDWOODS))
        assertFalse(store.showHidden(HiddenMaterialsMode.SPECIALTY))
    }

    @Test
    fun `setShowHidden persists and reads back`() = runBlocking {
        val store = createStore()

        store.setShowHidden(HiddenMaterialsMode.HARDWOODS, true)

        assertTrue(store.showHidden(HiddenMaterialsMode.HARDWOODS))
    }

    @Test
    fun `the two modes are independent`() = runBlocking {
        val store = createStore()

        store.setShowHidden(HiddenMaterialsMode.HARDWOODS, true)

        assertTrue(store.showHidden(HiddenMaterialsMode.HARDWOODS))
        assertFalse(store.showHidden(HiddenMaterialsMode.SPECIALTY))
    }

    private fun createStore(): HiddenMaterialsVisibilityPreferencesStore {
        val testDir = createTempDirectory("hidden-materials-visibility-${UUID.randomUUID()}").toFile()
        testDir.deleteOnExit()
        val testFile = File(testDir, "datastore.preferences_pb")
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { testFile }
        )
        return HiddenMaterialsVisibilityPreferencesStore(dataStore)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterialsVisibilityPreferencesStoreTest"
```

Expected: FAIL to compile (`HiddenMaterialsVisibilityPreferencesStore` does not exist yet)

- [ ] **Step 3: Write the implementation**

```kotlin
// app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsVisibilityPreferencesStore.kt
package com.kkc.sheettracker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.hiddenMaterialsVisibilityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "hidden_materials_visibility"
)

private object HiddenMaterialsVisibilityKeys {
    val showHiddenHardwoods = booleanPreferencesKey("show_hidden_hardwoods")
    val showHiddenSpecialty = booleanPreferencesKey("show_hidden_specialty")
}

/**
 * Per-tablet "show hidden materials" toggle for the cutlist screen, one independent boolean per
 * mode (Hardwoods/Specialty) -- matches the feature's mode segregation, so toggling one screen's
 * visibility has no effect on the other's.
 */
class HiddenMaterialsVisibilityPreferencesStore(private val dataStore: DataStore<Preferences>) {

    fun showHiddenFlow(mode: HiddenMaterialsMode): Flow<Boolean> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs -> prefs[keyFor(mode)] ?: false }

    suspend fun showHidden(mode: HiddenMaterialsMode): Boolean = showHiddenFlow(mode).first()

    suspend fun setShowHidden(mode: HiddenMaterialsMode, value: Boolean) {
        dataStore.edit { it[keyFor(mode)] = value }
    }

    private fun keyFor(mode: HiddenMaterialsMode) = when (mode) {
        HiddenMaterialsMode.HARDWOODS -> HiddenMaterialsVisibilityKeys.showHiddenHardwoods
        HiddenMaterialsMode.SPECIALTY -> HiddenMaterialsVisibilityKeys.showHiddenSpecialty
    }

    companion object {
        fun create(context: Context): HiddenMaterialsVisibilityPreferencesStore =
            HiddenMaterialsVisibilityPreferencesStore(context.hiddenMaterialsVisibilityDataStore)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterialsVisibilityPreferencesStoreTest"
```

Expected: all tests passed

- [ ] **Step 5: Run the full data-layer test set for this feature to check for regressions**

```powershell
cd "C:\Scripts\KKCSheetTracker"
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.HiddenMaterials*" --tests "com.kkc.sheettracker.data.AdminSyncClientTest"
```

Expected: all tests passed, no failures

- [ ] **Step 6: Commit**

```bash
cd "C:\Scripts\KKCSheetTracker"
git add app/src/main/java/com/kkc/sheettracker/data/HiddenMaterialsVisibilityPreferencesStore.kt app/src/test/java/com/kkc/sheettracker/data/HiddenMaterialsVisibilityPreferencesStoreTest.kt
git commit -m "$(cat <<'EOF'
feat: add HiddenMaterialsVisibilityPreferencesStore toggle

Per-tablet, per-mode "show hidden materials" boolean, independent
between Hardwoods and Specialty. Completes the hidden-materials
Android data layer -- UI wiring is a separate follow-up plan.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## End of Plan 2

The Android data layer is now complete, fully unit-tested, and compiles standalone — but nothing in it is wired into the running app yet (no `NavGraph.kt` changes, no `HardwoodsWorkspaceScreen.kt` changes). Every class is parametrized by `HiddenMaterialsMode`, so Plan 3 (Specialty UI) can reuse all eight files here unchanged.

**Before writing the UI-wiring follow-up plan**, map `NavGraph.kt`'s two navigation stacks:
- `AppNavigation` (legacy single-stack, production default per `CLAUDE.md`) — contains the `deliveryScheduleStore`/`DeliveryScheduleLiveClient`/`DeliveryScheduleLifecycleGate` wiring pattern this plan's classes are meant to mirror, and (at or near line 3415) one of the two `HardwoodsWorkspaceScreen` call sites.
- `MultiBackStackNavigation` (newer tab-based stack) — contains `JobsTabHost` and the other `HardwoodsWorkspaceScreen` call site (at or near line 2000).

Determine whether both stacks need independent wiring, or whether one delegates to (or shares state with) the other, before committing to task instructions for that plan.
