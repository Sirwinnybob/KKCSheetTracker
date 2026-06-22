# Door-Panel Auto-Complete Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When an auto-detected "Door panels — <material>" specialty item is checked/unchecked, mark the matching Door Cut List SHEETS rows done (= qty) / cleared (= 0), matching by exact material name (canonicalized via `material_mappings.json`).

**Architecture:** A pure matcher (`DoorPanelAutoComplete`) over already-SHEETS-filtered cut-list rows, a `MaterialMappings` canonicalizer, and an integration hook in `SpecialtyStateStore.setItemCompletion` that calls `HardwoodsProgressStore.setDoneCount` for each match. Reuses existing `DoorCutSheetFilter` for SHEETS classification.

**Tech Stack:** Kotlin, Android, JUnit (`org.junit.Test`, `org.junit.Assert.*`), Gson.

**Repo:** `C:\Scripts\KKCSheetTracker`. Run tests: `./gradlew test`.

**Branch:** `feature/door-panel-autocomplete` (already created). Only stage files this plan creates/edits; leave pre-existing working-tree changes (`CLAUDE.md`, `app/build.gradle.kts`, `ProgressStore.kt`, `AssemblyViewerScreen.kt`) untouched.

---

### Task 1: Add `automationKey` to SpecialtyItem and parse it

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/models/Models.kt` (data class `SpecialtyItem`, ends at line ~790)
- Modify: `app/src/main/java/com/kkc/sheettracker/data/SpecialtyProgressStore.kt` (function `parseSpecialtyItems`, ~line 283)
- Test: `app/src/test/java/com/kkc/sheettracker/data/SpecialtyProgressStoreTest.kt`

- [ ] **Step 1: Write the failing test** — append to `SpecialtyProgressStoreTest`:

```kotlin
@Test
fun parsesAutomationKeyOnSpecialtyItem() {
    val baseDir = createTempBaseDir()
    writeSpecialtyItems(
        baseDir = baseDir,
        jobFolderName = jobFolderName,
        body = """
            {
              "items": [
                {
                  "id": "auto-door",
                  "name": "Door panels - 1/4 2s Hickory Rustic",
                  "category": "CUSTOM",
                  "stations": ["SAW"],
                  "autoDetected": true,
                  "automationKey": "door_panels_auto|1/4 2S HICKORY RUSTIC|flat",
                  "material": "1/4 2s Hickory Rustic"
                }
              ]
            }
        """.trimIndent()
    )
    val store = SpecialtyProgressStore(baseDir = baseDir, tabletId = tabletId)
    val item = store.loadSpecialtyItems(jobFolderName).first { it.id == "auto-door" }
    assertEquals("door_panels_auto|1/4 2S HICKORY RUSTIC|flat", item.automationKey)
    assertEquals("1/4 2s Hickory Rustic", item.material)
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew test --tests "com.kkc.sheettracker.data.SpecialtyProgressStoreTest"`
Expected: FAIL — `SpecialtyItem` has no `automationKey` property (compile error or null).

- [ ] **Step 3: Add the field to `SpecialtyItem`** in `Models.kt` — add after `material`:

```kotlin
    val material: String? = null,
    val automationKey: String? = null
)
```

- [ ] **Step 4: Parse it** in `SpecialtyProgressStore.parseSpecialtyItems`, inside the `SpecialtyItem(...)` constructor (alongside the existing `material = obj.getNullableString("material")` line), add:

```kotlin
                material = obj.getNullableString("material"),
                automationKey = obj.getNullableString("automationKey")
```

(Mirror the same `automationKey = obj.getNullableString("automationKey")` addition in `parseChecklistAsSpecialtyItems` and `parseTabletItems`/`parseTabletItemsFile` only if those constructors are missing a trailing field; door-panel items come from `specialty_items.json` → `parseSpecialtyItems`, which is the required one.)

- [ ] **Step 5: Run test, verify it passes**

Run: `./gradlew test --tests "com.kkc.sheettracker.data.SpecialtyProgressStoreTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/models/Models.kt app/src/main/java/com/kkc/sheettracker/data/SpecialtyProgressStore.kt app/src/test/java/com/kkc/sheettracker/data/SpecialtyProgressStoreTest.kt
git commit -m "feat: parse automationKey on SpecialtyItem"
```

---

### Task 2: MaterialMappings canonicalizer

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/MaterialMappings.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/MaterialMappingsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MaterialMappingsTest {
    private fun tempBase(): File = Files.createTempDirectory("mm").toFile()

    private fun writeMappings(baseDir: File, json: String) {
        val f = File(baseDir, ".metadata/material_mappings.json")
        f.parentFile.mkdirs()
        f.writeText(json)
    }

    @Test
    fun realNameAndSanitizedNameCanonicalizeEqual() {
        val base = tempBase()
        writeMappings(base, """{"1/4 2s Hickory Rustic":"1_4 2s Rustic Hickory"}""")
        val mm = MaterialMappings.load(base)
        assertEquals(mm.canonical("1/4 2s Hickory Rustic"), mm.canonical("1_4 2s Rustic Hickory"))
    }

    @Test
    fun caseAndWhitespaceInsensitive() {
        val base = tempBase()
        writeMappings(base, """{"1/4 2s Hickory Rustic":"1_4 2s Rustic Hickory"}""")
        val mm = MaterialMappings.load(base)
        assertEquals(mm.canonical("  1/4 2S HICKORY RUSTIC "), mm.canonical("1/4 2s Hickory Rustic"))
    }

    @Test
    fun missingFileFallsBackToIdentity() {
        val base = tempBase() // no mappings file
        val mm = MaterialMappings.load(base)
        assertEquals(mm.canonical("Foo Bar"), mm.canonical(" foo bar "))
    }

    @Test
    fun unknownNameUsesItself() {
        val base = tempBase()
        writeMappings(base, """{"1/4 2s Hickory Rustic":"1_4 2s Rustic Hickory"}""")
        val mm = MaterialMappings.load(base)
        assertEquals(mm.canonical("Totally Unknown"), mm.canonical("totally unknown"))
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew test --tests "com.kkc.sheettracker.data.MaterialMappingsTest"`
Expected: FAIL — `MaterialMappings` does not exist.

- [ ] **Step 3: Implement** `MaterialMappings.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.google.gson.JsonParser
import java.io.File
import java.util.Locale

/**
 * Canonicalizes material names so the real cut-list name and the sanitized
 * CNC-filename-safe name compare equal. Source: <baseDir>/.metadata/material_mappings.json
 * (real name -> sanitized name).
 */
class MaterialMappings private constructor(
    private val realToSanitized: Map<String, String>
) {
    private fun norm(value: String): String = value.trim().lowercase(Locale.US)

    /** Canonical key for comparison: resolves to the sanitized form when known, else the
     *  name itself, always trimmed + case-folded. */
    fun canonical(name: String?): String {
        val raw = name?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        // real-name key -> sanitized value
        realToSanitized[raw]?.let { return norm(it) }
        // already sanitized? leave as-is. unknown? use itself.
        return norm(raw)
    }

    companion object {
        fun load(baseDir: File): MaterialMappings {
            val file = File(baseDir, ".metadata/material_mappings.json")
            val map = runCatching {
                if (!file.isFile) return@runCatching emptyMap<String, String>()
                val root = JsonParser.parseString(file.readText())
                if (!root.isJsonObject) return@runCatching emptyMap<String, String>()
                root.asJsonObject.entrySet()
                    .mapNotNull { (k, v) ->
                        if (v.isJsonPrimitive && v.asJsonPrimitive.isString) k to v.asString else null
                    }
                    .toMap()
            }.getOrDefault(emptyMap())
            return MaterialMappings(map)
        }

        /** For tests / callers that already hold a map. */
        fun of(realToSanitized: Map<String, String>): MaterialMappings =
            MaterialMappings(realToSanitized)
    }
}
```

Note on `realNameAndSanitizedNameCanonicalizeEqual`: `canonical("1/4 2s Hickory Rustic")` resolves the real key → `norm("1_4 2s Rustic Hickory")`; `canonical("1_4 2s Rustic Hickory")` is not a key → `norm("1_4 2s Rustic Hickory")`. Both equal. ✓

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew test --tests "com.kkc.sheettracker.data.MaterialMappingsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/MaterialMappings.kt app/src/test/java/com/kkc/sheettracker/data/MaterialMappingsTest.kt
git commit -m "feat: add MaterialMappings canonicalizer"
```

---

### Task 3: DoorPanelAutoComplete pure matcher

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/DoorPanelAutoComplete.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/DoorPanelAutoCompleteTest.kt`

Context: callers pass the Door Cut List rows **already filtered to SHEETS** (via the existing `DoorCutSheetFilter.filterDoorCutRowsToSheets`). This matcher only applies the auto-door-panel gate and the canonical material match. `SpecialtyItem` fields available: `automationKey: String?`, `material: String?`. `HardwoodCutlistRow` fields: `rowId: String`, `qty: Int`, `material: String?`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.SpecialtyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorPanelAutoCompleteTest {
    private val mappings = MaterialMappings.of(mapOf("1/4 2s Hickory Rustic" to "1_4 2s Rustic Hickory"))

    private fun item(material: String?, automationKey: String?) =
        SpecialtyItem(id = "i", name = "Door panels", material = material, automationKey = automationKey)

    private fun sheetRow(rowId: String, material: String?, qty: Int) =
        HardwoodCutlistRow(rowId = rowId, qty = qty, material = material)

    @Test
    fun matchesSheetRowsOfSameMaterialExcludesOthers() {
        val sheetRows = listOf(
            sheetRow("r1", "1/4 2s Hickory Rustic", 3),
            sheetRow("r2", "1/4 2s Hickory Rustic", 1),
            sheetRow("r3", "Some Other Material", 2)
        )
        val targets = matchingDoorPanelRows(
            item = item("1/4 2s Hickory Rustic", "door_panels_auto|1/4 2S HICKORY RUSTIC|flat"),
            sheetRows = sheetRows,
            mappings = mappings
        )
        assertEquals(listOf("r1" to 3, "r2" to 1), targets.map { it.rowId to it.qty })
    }

    @Test
    fun matchesAcrossRealAndSanitizedForms() {
        val sheetRows = listOf(sheetRow("r1", "1_4 2s Rustic Hickory", 5)) // sanitized on the row
        val targets = matchingDoorPanelRows(
            item = item("1/4 2s Hickory Rustic", "door_panels_auto|x|flat"), // real on the item
            sheetRows = sheetRows,
            mappings = mappings
        )
        assertEquals(listOf("r1" to 5), targets.map { it.rowId to it.qty })
    }

    @Test
    fun nonAutoDoorPanelItemMatchesNothing() {
        val sheetRows = listOf(sheetRow("r1", "1/4 2s Hickory Rustic", 3))
        // manual item (no automationKey)
        assertTrue(matchingDoorPanelRows(item("1/4 2s Hickory Rustic", null), sheetRows, mappings).isEmpty())
        // auto item but different automation
        assertTrue(matchingDoorPanelRows(item("1/4 2s Hickory Rustic", "some_other_auto|x"), sheetRows, mappings).isEmpty())
    }

    @Test
    fun itemWithoutMaterialMatchesNothing() {
        val sheetRows = listOf(sheetRow("r1", "1/4 2s Hickory Rustic", 3))
        assertTrue(matchingDoorPanelRows(item(null, "door_panels_auto|x|flat"), sheetRows, mappings).isEmpty())
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew test --tests "com.kkc.sheettracker.data.DoorPanelAutoCompleteTest"`
Expected: FAIL — `matchingDoorPanelRows` / `DoorPanelTarget` do not exist.

- [ ] **Step 3: Implement** `DoorPanelAutoComplete.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.SpecialtyItem

/** A Door Cut List row to auto-complete: its rowId and full quantity. */
data class DoorPanelTarget(val rowId: String, val qty: Int)

private const val DOOR_PANELS_AUTO_PREFIX = "door_panels_auto|"

/**
 * Returns the Door Cut List SHEETS rows that belong to an auto-detected door-panel
 * specialty [item], matched by exact (canonicalized) material name.
 *
 * @param sheetRows Door Cut List rows ALREADY filtered to SHEETS by the caller
 *                  (via DoorCutSheetFilter.filterDoorCutRowsToSheets).
 */
fun matchingDoorPanelRows(
    item: SpecialtyItem,
    sheetRows: List<HardwoodCutlistRow>,
    mappings: MaterialMappings
): List<DoorPanelTarget> {
    val key = item.automationKey?.trim().orEmpty()
    if (!key.startsWith(DOOR_PANELS_AUTO_PREFIX, ignoreCase = true)) return emptyList()
    val itemMaterial = item.material?.takeIf { it.isNotBlank() } ?: return emptyList()
    val itemCanonical = mappings.canonical(itemMaterial)
    if (itemCanonical.isEmpty()) return emptyList()
    return sheetRows
        .filter { row ->
            val rowMaterial = row.material?.takeIf { it.isNotBlank() } ?: return@filter false
            mappings.canonical(rowMaterial) == itemCanonical
        }
        .map { DoorPanelTarget(rowId = it.rowId, qty = it.qty) }
}
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew test --tests "com.kkc.sheettracker.data.DoorPanelAutoCompleteTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/DoorPanelAutoComplete.kt app/src/test/java/com/kkc/sheettracker/data/DoorPanelAutoCompleteTest.kt
git commit -m "feat: add door-panel auto-complete matcher"
```

---

### Task 4: Wire auto-complete into specialty completion toggle

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/SpecialtyStateStore.kt` (constructor + `setItemCompletion`, line ~101)
- Modify: the construction site of `SpecialtyStateStore` (DI/composition root — locate with `git grep -n "SpecialtyStateStore("`; likely an app container/module)
- Reuse (read, do not modify): `app/src/main/java/com/kkc/sheettracker/data/DoorCutSheetFilter.kt` (`parseDoorCutUnitTypeMetadata`, `filterDoorCutRowsToSheets`), and `HardwoodsProgressStore.setDoneCount(jobFolderName, docType, rowId, qty, doneCount)`.
- Test: `app/src/test/java/com/kkc/sheettracker/data/DoorPanelAutoCompleteWiringTest.kt` (store-level)

**Behavior to implement:** In `SpecialtyStateStore.setItemCompletion(jobFolderName, itemId, completed)`, after the existing `specialtyProgressStore.setCompletions(...)` call, if the toggled item is an auto door-panel item, compute targets and write hardwoods done counts:

```kotlin
suspend fun setItemCompletion(
    jobFolderName: String,
    itemId: String,
    completed: Boolean
) = withContext(ioDispatcher) {
    val resolvedItem = getResolvedItems(jobFolderName).firstOrNull { it.item.id == itemId }
    val completionKeys = resolvedItem
        ?.item
        ?.let(::completionKeysForItem)
        ?: listOf(SpecialtyProgressStore.ITEM_COMPLETION_KEY)

    specialtyProgressStore.setCompletions(
        jobFolderName = jobFolderName,
        itemId = itemId,
        completionKeys = completionKeys,
        completed = completed
    )

    val item = resolvedItem?.item
    if (item != null) {
        autoCompleteDoorPanels(jobFolderName, item, completed)
    }
}
```

Add a private helper that loads SHEETS rows + mappings and writes done counts. It must obtain the Door Cut List rows and the raw cutlist JSON for the job; reuse whatever `SpecialtyDoorPanelsScreen` uses (`loadHardwoodsCutlistIndexRawJson` and the `HardwoodCutlistIndex` parse that yields `documents` with `docType == HardwoodDocType.DOOR_CUT_LIST` and `rows`). The store needs two new constructor dependencies: the `HardwoodsProgressStore` and the app `baseDir: File` (for `MaterialMappings.load`).

```kotlin
private fun autoCompleteDoorPanels(
    jobFolderName: String,
    item: SpecialtyItem,
    completed: Boolean
) {
    if (item.automationKey?.startsWith("door_panels_auto|", ignoreCase = true) != true) return

    val rawJson = loadHardwoodsCutlistIndexRawJson(baseDir.absolutePath, jobFolderName) ?: return
    val index = parseHardwoodCutlistIndex(rawJson) ?: return   // existing parser used by the door-panels screen
    val doorCutRows = index.documents
        .firstOrNull { it.docType == HardwoodDocType.DOOR_CUT_LIST }
        ?.rows
        ?: return

    val sheetRows = filterDoorCutRowsToSheets(doorCutRows, parseDoorCutUnitTypeMetadata(rawJson))
    val mappings = MaterialMappings.load(baseDir)
    val targets = matchingDoorPanelRows(item, sheetRows, mappings)

    targets.forEach { target ->
        hardwoodsProgressStore.setDoneCount(
            jobFolderName = jobFolderName,
            docType = HardwoodDocType.DOOR_CUT_LIST.name,
            rowId = target.rowId,
            qty = target.qty,
            doneCount = if (completed) target.qty else 0
        )
    }
}
```

- [ ] **Step 1: Locate the exact cutlist-index loader/parser** the door-panels screen uses. Open `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyDoorPanelsScreen.kt` (`loadHardwoodsCutlistIndexRawJson`, `buildDoorPanelsViewModel`) and follow `sharedLoadHardwoodsCutlistIndexRawJson` + the `HardwoodCutlistIndex` parse. Note the exact function names/signatures (the `parseHardwoodCutlistIndex` above is a placeholder for whatever the real parser is named). If the loader is `@Composable`/UI-bound, extract the plain function it delegates to (it is shared — `SpecialtyDoorPanelsScreen.loadHardwoodsCutlistIndexRawJson` calls `sharedLoadHardwoodsCutlistIndexRawJson`).

- [ ] **Step 2: Write the failing wiring test** — `DoorPanelAutoCompleteWiringTest.kt`. Set up a temp `baseDir` with: `.metadata/material_mappings.json`; `<job>/.metadata/hardwoods/cutlist_index.json` containing a `DOOR_CUT_LIST` doc with two SHEETS rows of `"1/4 2s Hickory Rustic"` (qty 3 and 1) plus one `BD_FT` rail row of `"3/4 Solid Hickory Rustic"`, mirroring the on-disk shape verified on job 575 (fields: `rowId,page,rowOrdinal,qty,material,description,unitType`); `<job>/.metadata/admin/specialty_items.json` with the auto door-panel item (material `"1/4 2s Hickory Rustic"`, `automationKey` `door_panels_auto|…|flat`, `autoDetected:true`, `stations:["SAW"]`). Construct the real `SpecialtyStateStore` with real `SpecialtyProgressStore`, `HardwoodsProgressStore`, and `baseDir`, then:

```kotlin
@Test
fun checkingAutoDoorPanelMarksMatchingSheetRowsDone() = runBlocking {
    // ... build baseDir fixtures as described above ...
    store.setItemCompletion(jobFolderName, "auto-door", completed = true)

    val done = hardwoodsProgressStore.getRowDoneCount(jobFolderName, "DOOR_CUT_LIST", "r1") // see note
    assertEquals(3, done)
    assertEquals(1, hardwoodsProgressStore.getRowDoneCount(jobFolderName, "DOOR_CUT_LIST", "r2"))
    assertEquals(0, hardwoodsProgressStore.getRowDoneCount(jobFolderName, "DOOR_CUT_LIST", "rail")) // untouched

    store.setItemCompletion(jobFolderName, "auto-door", completed = false)
    assertEquals(0, hardwoodsProgressStore.getRowDoneCount(jobFolderName, "DOOR_CUT_LIST", "r1"))
}
```

Note: use whatever read API `HardwoodsProgressStore` exposes for a row's done count (e.g. via its job cache / `rowProgressMap`). If no direct getter exists, assert on the persisted tracker JSON file under `<job>/.metadata/hardwoods/.tracker/` instead. Do NOT add a production getter solely for the test if a file/JSON assertion suffices.

- [ ] **Step 3: Run test, verify it fails**

Run: `./gradlew test --tests "com.kkc.sheettracker.data.DoorPanelAutoCompleteWiringTest"`
Expected: FAIL — `SpecialtyStateStore` has no `baseDir`/`hardwoodsProgressStore` deps and no auto-complete behavior.

- [ ] **Step 4: Implement** — add `private val hardwoodsProgressStore: HardwoodsProgressStore` and `private val baseDir: File` to the `SpecialtyStateStore` constructor; add the `autoCompleteDoorPanels` helper and the call in `setItemCompletion` (code above); update the DI construction site to pass the existing `HardwoodsProgressStore` instance and `baseDir`. Use the real loader/parser names found in Step 1.

- [ ] **Step 5: Run the test + full suite**

Run: `./gradlew test --tests "com.kkc.sheettracker.data.DoorPanelAutoCompleteWiringTest"` then `./gradlew test`
Expected: PASS; no other tests broken.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/SpecialtyStateStore.kt <DI-construction-site-file> app/src/test/java/com/kkc/sheettracker/data/DoorPanelAutoCompleteWiringTest.kt
git commit -m "feat: auto-complete matching door cut list rows when door-panel item toggled"
```

---

## Notes for the executor

- Door panels are single-station (`["SAW"]`) so `completionKeysForItem` yields the single `ITEM` key; the existing completion path is unchanged. This plan only ADDS the cross-store side effect.
- Bidirectional by design: unchecking clears matched rows to 0 (overrides manual progress on those rows — accepted).
- Conservative: if `material_mappings.json` is missing, `MaterialMappings` degrades to identity (plain exact match). If there's no Door Cut List or no SHEETS match, it's a no-op.
- Do not modify the server automation or any on-disk schema.
