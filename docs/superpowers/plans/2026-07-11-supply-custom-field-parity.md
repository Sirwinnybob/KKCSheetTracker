# Supply Custom-Field Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the tablet Supply Edit/Detail screens fully schema-driven so admin-added custom supply fields render, are editable, and round-trip into `customFields`.

**Architecture:** Read the synced `.supply/schema.json` via `SupplyRepository.schemaOrDefault()` (falling back to an in-app `DEFAULT_SUPPLY_SCHEMA` when unsynced). A pure routing helper splits the editor's value map into builtin `fields` vs custom `customFields` by each schema field's `builtin` flag, preserving orphan keys untouched. Edit and Detail screens loop over the schema instead of hardcoding the 4 builtins.

**Tech Stack:** Kotlin, Jetbrains Compose (Material3), Gson, JUnit4. Build: `./gradlew.bat` (use the `:app:` module prefix — a bare `testDebugUnitTest` runs against `:updater-agent`).

**Spec:** `docs/superpowers/specs/2026-07-11-supply-custom-field-parity-design.md`

**Repo:** all paths are under `C:\Scripts\KKCSheetTracker`. Branch `main`. NOTE: the working tree already has many unrelated modified UI files (theme/corner-radius/supply-tab WIP) — do NOT stage them; `git add` only the exact files named in each task.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `app/src/main/java/com/kkc/sheettracker/data/models/SupplyModels.kt` | add `DEFAULT_SUPPLY_SCHEMA` constant | 1 |
| `app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt` | persist `customFields`; `schemaOrDefault()` | 1 |
| `app/src/main/java/com/kkc/sheettracker/data/SupplyFieldRouting.kt` (new) | pure split of edited values → fields/customFields, orphan-preserving | 2 |
| `app/src/test/java/com/kkc/sheettracker/data/SupplyFieldRoutingTest.kt` (new) | unit tests for routing + fallback | 2 |
| `app/src/test/java/com/kkc/sheettracker/data/SupplyRepositoryTest.kt` | round-trip `customFields`, orphan preservation | 1 |
| `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyFieldInput.kt` (new) | typed edit input composable | 3 |
| `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemEditScreen.kt` | schema-driven Edit | 3 |
| `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemDetailScreen.kt` | schema-driven Detail | 4 |

---

## Task 1: Data layer — DEFAULT_SUPPLY_SCHEMA, customFields persistence, schemaOrDefault

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/models/SupplyModels.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/SupplyRepositoryTest.kt`

- [ ] **Step 1: Add the `DEFAULT_SUPPLY_SCHEMA` constant**

In `SupplyModels.kt`, directly after the `SupplySchemaField` data class (currently ends line 47), add:

```kotlin
// The 4 builtin fields, mirroring the backend DEFAULT_SCHEMA in
// Hours Tracker backend/routes/supply_store.py. Used as a fallback when
// .supply/schema.json has not synced to the tablet yet, so the editor never
// renders zero fields.
val DEFAULT_SUPPLY_SCHEMA: List<SupplySchemaField> = listOf(
    SupplySchemaField("builtin-sku", "sku", "SKU", "text", true),
    SupplySchemaField("builtin-quantity", "quantity", "Quantity", "text", true),
    SupplySchemaField("builtin-vendorLink", "vendorLink", "Vendor Link", "url", true),
    SupplySchemaField("builtin-trackingNumber", "trackingNumber", "Tracking #", "text", true),
)
```

- [ ] **Step 2: Write the failing repository tests**

Append these two tests inside the existing `SupplyRepositoryTest` class in `SupplyRepositoryTest.kt` (before the closing brace). They assume the existing test file's setup pattern (a `repository` bound to a temp base path with `.supply/` under it — mirror how the existing `updateItem` tests at lines 37 and 68 obtain their `repository`, category id, and item id; reuse the same helpers/fixtures already in that file):

```kotlin
    @Test
    fun createItem_persistsCustomFields() {
        val cat = repository.createCategory("Blades")
        val created = repository.createItem(
            categoryId = cat.id,
            name = "Saw blade",
            notes = null,
            fields = mapOf("sku" to "SB-1"),
            customFields = mapOf("diameter" to "10in"),
            status = "IN STOCK",
            tabletId = "tablet-A"
        )

        val reloaded = repository.getItem(created.id)!!
        assertEquals("SB-1", reloaded.fields["sku"])
        assertEquals("10in", reloaded.customFields["diameter"])
    }

    @Test
    fun updateItem_setsCustomFieldsAndPreservesOrphans() {
        val cat = repository.createCategory("Blades")
        val created = repository.createItem(
            categoryId = cat.id,
            name = "Saw blade",
            notes = null,
            fields = mapOf("sku" to "SB-1"),
            customFields = mapOf("diameter" to "10in", "legacyKerf" to "0.1"),
            tabletId = "tablet-A"
        )

        // Update passes only the currently-known custom field; the orphan (legacyKerf)
        // must be carried in by the caller's map to survive — here we simulate the routing
        // helper's output which round-trips orphans.
        val updated = repository.updateItem(
            created.id,
            "Saw blade v2",
            cat.id,
            null,
            mapOf("sku" to "SB-2"),
            mapOf("diameter" to "12in", "legacyKerf" to "0.1")
        )!!

        assertEquals("SB-2", updated.fields["sku"])
        assertEquals("12in", updated.customFields["diameter"])
        assertEquals("0.1", updated.customFields["legacyKerf"])
    }
```

If the existing tests reference `assertEquals` via `org.junit.Assert.assertEquals` or `kotlin.test`, match whichever import the file already uses. Do not add a second import.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplyRepositoryTest"`
Expected: FAIL — compilation error, because `createItem` has no `customFields` parameter and `updateItem` takes 5 args, not 6.

- [ ] **Step 4: Add `customFields` to `createItem`**

In `SupplyRepository.kt`, change the `createItem` signature (currently lines 134-139) to insert a `customFields` param with a default, and use it in the `StoredSupplyItem` (currently line 146 hardcodes `customFields = emptyMap()`):

```kotlin
    fun createItem(
        categoryId: String, name: String, notes: String?,
        fields: Map<String, String>,
        customFields: Map<String, String> = emptyMap(),
        status: String = "IN STOCK",
        tabletId: String = "tablet"
    ): SupplyItem {
```

and in the `StoredSupplyItem(...)` construction change `customFields = emptyMap(),` to:

```kotlin
            fields = fields, customFields = customFields,
```

- [ ] **Step 5: Add `customFields` to `updateItem` (nullable, preserve-on-null)**

In `SupplyRepository.kt`, change `updateItem` (currently lines 159-172). Add a nullable `customFields` param defaulting to `null` (so the two existing 5-arg test callers and any future caller that omits it keep their current behavior of preserving the stored value), and apply it in the `copy`:

```kotlin
    fun updateItem(
        itemId: String,
        name: String,
        categoryId: String,
        notes: String?,
        fields: Map<String, String>,
        customFields: Map<String, String>? = null
    ): SupplyItem? {
        val file = File(itemsDir, "$itemId.json")
        val existing = readJson<StoredSupplyItem>(file) ?: return null
        val updated = existing.copy(
            name = name, categoryId = categoryId,
            notes = notes?.takeIf { it.isNotBlank() },
            fields = fields,
            customFields = customFields ?: existing.customFields,
            updatedAt = java.time.Instant.now().toString()
        )
        // CROSS-PROGRAM: see METADATA_AUDIT.md H-07 — items/<id>.json is also written by the
        // Hours Tracker backend (atomic+locked). Atomic write here prevents a concurrent reader
        // (backend, peer tablet, or this app's own getItems()) from observing a torn file.
        atomicWriteFile(file, gson.toJson(updated))
        return updated.resolve()
    }
```

- [ ] **Step 6: Add `schemaOrDefault()`**

In `SupplyRepository.kt`, directly after the existing `getSchema()` (currently lines 73-74) add:

```kotlin
    // Schema for rendering item fields. Falls back to the builtin defaults when
    // schema.json is missing/empty (e.g. not yet synced to this tablet), so the
    // Edit/Detail screens never render zero fields.
    fun schemaOrDefault(): List<SupplySchemaField> =
        getSchema().ifEmpty { DEFAULT_SUPPLY_SCHEMA }
```

`DEFAULT_SUPPLY_SCHEMA` is in `com.kkc.sheettracker.data.models`; confirm the file already imports that package (it references `SupplySchemaField`, so the import exists).

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplyRepositoryTest"`
Expected: PASS (all existing + 2 new).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/models/SupplyModels.kt \
        app/src/main/java/com/kkc/sheettracker/data/SupplyRepository.kt \
        app/src/test/java/com/kkc/sheettracker/data/SupplyRepositoryTest.kt
git commit -m "feat(M-13): persist supply customFields + DEFAULT_SUPPLY_SCHEMA fallback"
```

---

## Task 2: Pure field-routing helper

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/SupplyFieldRouting.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/SupplyFieldRoutingTest.kt`

- [ ] **Step 1: Write the failing test**

Create `SupplyFieldRoutingTest.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DEFAULT_SUPPLY_SCHEMA
import com.kkc.sheettracker.data.models.SupplySchemaField
import org.junit.Assert.assertEquals
import org.junit.Test

class SupplyFieldRoutingTest {

    private val schema = listOf(
        SupplySchemaField("builtin-sku", "sku", "SKU", "text", true),
        SupplySchemaField("c1", "diameter", "Diameter", "text", false),
    )

    @Test
    fun routesBuiltinToFieldsAndCustomToCustomFields() {
        val routed = routeSupplyFieldValues(
            schema = schema,
            editedValues = mapOf("sku" to "SB-1", "diameter" to "10in"),
            existingFields = emptyMap(),
            existingCustomFields = emptyMap()
        )
        assertEquals(mapOf("sku" to "SB-1"), routed.fields)
        assertEquals(mapOf("diameter" to "10in"), routed.customFields)
    }

    @Test
    fun omitsBlankValues() {
        val routed = routeSupplyFieldValues(
            schema = schema,
            editedValues = mapOf("sku" to "  ", "diameter" to ""),
            existingFields = emptyMap(),
            existingCustomFields = emptyMap()
        )
        assertEquals(emptyMap<String, String>(), routed.fields)
        assertEquals(emptyMap<String, String>(), routed.customFields)
    }

    @Test
    fun preservesOrphanKeysNotInSchema() {
        val routed = routeSupplyFieldValues(
            schema = schema,
            editedValues = mapOf("sku" to "SB-1"),
            existingFields = mapOf("legacyBuiltin" to "x"),
            existingCustomFields = mapOf("legacyKerf" to "0.1")
        )
        assertEquals(mapOf("legacyBuiltin" to "x", "sku" to "SB-1"), routed.fields)
        assertEquals(mapOf("legacyKerf" to "0.1"), routed.customFields)
    }

    @Test
    fun trimsValues() {
        val routed = routeSupplyFieldValues(
            schema = schema,
            editedValues = mapOf("sku" to "  SB-1  "),
            existingFields = emptyMap(),
            existingCustomFields = emptyMap()
        )
        assertEquals("SB-1", routed.fields["sku"])
    }

    @Test
    fun defaultSchemaHasFourBuiltins() {
        assertEquals(4, DEFAULT_SUPPLY_SCHEMA.size)
        assertEquals(listOf("sku", "quantity", "vendorLink", "trackingNumber"),
            DEFAULT_SUPPLY_SCHEMA.map { it.key })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplyFieldRoutingTest"`
Expected: FAIL — `routeSupplyFieldValues` / `RoutedSupplyFields` unresolved.

- [ ] **Step 3: Write the routing helper**

Create `SupplyFieldRouting.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.SupplySchemaField

/** Result of splitting editor values into the two persisted maps. */
data class RoutedSupplyFields(
    val fields: Map<String, String>,
    val customFields: Map<String, String>
)

/**
 * Split the editor's current values into the builtin `fields` map and the custom
 * `customFields` map according to [schema] (routing by each field's `builtin` flag).
 *
 * Orphan keys — present on the existing item but absent from the current schema — are
 * carried over untouched so they round-trip instead of being silently dropped. Blank
 * edited values are omitted (an emptied field drops its key, matching prior behavior).
 */
fun routeSupplyFieldValues(
    schema: List<SupplySchemaField>,
    editedValues: Map<String, String>,
    existingFields: Map<String, String>,
    existingCustomFields: Map<String, String>
): RoutedSupplyFields {
    val schemaKeys = schema.map { it.key }.toSet()

    // Seed with orphan values (keys the current schema no longer lists) so they survive.
    val fields = existingFields.filterKeys { it !in schemaKeys }.toMutableMap()
    val customFields = existingCustomFields.filterKeys { it !in schemaKeys }.toMutableMap()

    for (field in schema) {
        val value = editedValues[field.key]?.trim().orEmpty()
        if (value.isEmpty()) continue
        if (field.builtin) fields[field.key] = value else customFields[field.key] = value
    }
    return RoutedSupplyFields(fields, customFields)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.SupplyFieldRoutingTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/SupplyFieldRouting.kt \
        app/src/test/java/com/kkc/sheettracker/data/SupplyFieldRoutingTest.kt
git commit -m "feat(M-13): pure supply field routing helper (builtin/custom split, orphan-safe)"
```

---

## Task 3: Schema-driven Edit screen

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyFieldInput.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemEditScreen.kt`

This task is Compose UI; the logic it depends on (routing/fallback) is already unit-tested in Tasks 1-2. Verify via `:app:compileDebugKotlin` and the manual walkthrough at the end.

- [ ] **Step 1: Create the typed input composable**

Create `SupplyFieldInput.kt`:

```kotlin
package com.kkc.sheettracker.ui.supply

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.SupplySchemaField

/**
 * One schema-driven field editor. Chooses the keyboard by field type; date renders as a
 * plain text input with a YYYY-MM-DD hint (a Material date picker is a follow-up). Values
 * are always plain strings.
 */
@Composable
fun SupplyFieldInput(
    field: SupplySchemaField,
    value: String,
    onValueChange: (String) -> Unit
) {
    val keyboardType = when (field.type) {
        "number" -> KeyboardType.Number
        "url" -> KeyboardType.Uri
        else -> KeyboardType.Text
    }
    val label = if (field.type == "date") "${field.label} (YYYY-MM-DD)" else field.label
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(4.dp)
    )
}
```

- [ ] **Step 2: Replace the hardcoded field state in `SupplyItemEditScreen.kt`**

Remove the four builtin `var`s (currently lines 53-56):

```kotlin
    var sku by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var vendorLink by remember { mutableStateOf("") }
    var trackingNumber by remember { mutableStateOf("") }
```

and replace with schema + per-field value state + the existing-item maps needed for orphan-safe routing:

```kotlin
    var schema by remember { mutableStateOf<List<SupplySchemaField>>(emptyList()) }
    val fieldValues = remember { mutableStateMapOf<String, String>() }
    var existingFields by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var existingCustomFields by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
```

Add the imports near the top of the file (the file already imports other `com.kkc.sheettracker.data.models.*` members individually and `androidx.compose.runtime.*` which includes `mutableStateMapOf`):

```kotlin
import com.kkc.sheettracker.data.models.SupplySchemaField
import com.kkc.sheettracker.data.routeSupplyFieldValues
```

- [ ] **Step 3: Load the schema and seed values in the `LaunchedEffect`**

Replace the body of the existing `LaunchedEffect(itemId, initialCategoryId)` (currently lines 64-84) with:

```kotlin
    LaunchedEffect(itemId, initialCategoryId) {
        val cats = withContext(Dispatchers.IO) { repository.getCategories() }
        categories = cats.sortedBy { it.position }
        val loadedSchema = withContext(Dispatchers.IO) { repository.schemaOrDefault() }
        schema = loadedSchema
        if (itemId != null) {
            isLoading = true
            val item = withContext(Dispatchers.IO) { repository.getItem(itemId) }
            if (item != null) {
                name = item.name
                notes = item.notes ?: ""
                existingFields = item.fields
                existingCustomFields = item.customFields
                val seed = item.fields + item.customFields
                fieldValues.clear()
                loadedSchema.forEach { field -> fieldValues[field.key] = seed[field.key] ?: "" }
                selectedCategoryId = item.categoryId
                selectedStatus = item.status
            }
            isLoading = false
        } else {
            selectedCategoryId = initialCategoryId ?: ""
            fieldValues.clear()
            loadedSchema.forEach { field -> fieldValues[field.key] = "" }
        }
    }
```

- [ ] **Step 4: Rewrite `save()` to route by schema**

Replace the `fields`/`customFields` construction and the repository calls inside `save()` (currently lines 97-119). Replace the `val fields = buildMap { ... }` block and both repository calls with:

```kotlin
                val routed = routeSupplyFieldValues(
                    schema = schema,
                    editedValues = fieldValues,
                    existingFields = existingFields,
                    existingCustomFields = existingCustomFields
                )
                val author = employeeName.ifBlank { "Floor" }
                val savedId = withContext(Dispatchers.IO) {
                    if (itemId == null) {
                        repository.createItem(
                            categoryId = selectedCategoryId,
                            name = name.trim(),
                            notes = notes.takeIf { it.isNotBlank() },
                            fields = routed.fields,
                            customFields = routed.customFields,
                            status = selectedStatus,
                            tabletId = tabletId
                        ).id
                    } else {
                        repository.updateItem(
                            itemId, name.trim(), selectedCategoryId,
                            notes.takeIf { it.isNotBlank() },
                            routed.fields, routed.customFields
                        )
                        repository.setStatus(itemId, selectedStatus, author, tabletId)
                        itemId
                    }
                }
```

- [ ] **Step 5: Replace the hardcoded input UI with a schema loop**

Replace the four hardcoded `OutlinedTextField`s for SKU/Quantity/Vendor Link/Tracking # (currently lines 235-266) with:

```kotlin
            schema.forEach { field ->
                SupplyFieldInput(
                    field = field,
                    value = fieldValues[field.key] ?: "",
                    onValueChange = { fieldValues[field.key] = it }
                )
            }
```

Leave the `HorizontalDivider` + `"Details"` header (lines 228-233) as-is above this loop.

- [ ] **Step 6: Compile**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If an unused import remains (e.g. nothing else references a removed symbol), remove it until clean.

- [ ] **Step 7: Run the full data-layer test slice (regression guard)**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.Supply*"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyFieldInput.kt \
        app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemEditScreen.kt
git commit -m "feat(M-13): schema-driven supply Edit screen with typed inputs"
```

---

## Task 4: Schema-driven Detail screen

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemDetailScreen.kt`

Compose UI task; verify via `:app:compileDebugKotlin` + manual walkthrough. Preserves the existing per-type rendering: `url` fields tappable (open URI), the `trackingNumber` field keeps its tap-to-Google-search affordance, everything else plain.

- [ ] **Step 1: Load the schema into screen state**

Add a schema state var alongside the other `remember` states (near line 74, after `var item by remember ...`):

```kotlin
    var schema by remember { mutableStateOf(DEFAULT_SUPPLY_SCHEMA) }
```

`DEFAULT_SUPPLY_SCHEMA` comes from `com.kkc.sheettracker.data.models.*`, already wildcard-imported at line 45.

In `loadData()`, after the item is loaded (near line 92 where `repository.getItem(itemId)` is called), also load the schema on the IO dispatcher and assign it. Add, right after the `val loadedItem = withContext(Dispatchers.IO) { repository.getItem(itemId) }` line:

```kotlin
                schema = withContext(Dispatchers.IO) { repository.schemaOrDefault() }
```

- [ ] **Step 2: Replace the hardcoded `builtinFields` list with a schema-driven list**

Replace the `builtinFields` `buildList` block (currently lines 271-276) with a schema-driven list carrying each field's type and key:

```kotlin
                    // Fields, rendered from the current schema (non-blank values only).
                    // Orphan values (keys not in the current schema) are intentionally hidden.
                    data class DetailField(val label: String, val value: String, val type: String, val key: String)
                    val detailFields = schema.mapNotNull { f ->
                        val v = (currentItem.fields[f.key] ?: currentItem.customFields[f.key])
                            ?.takeIf { it.isNotBlank() }
                        v?.let { DetailField(f.label, it, f.type, f.key) }
                    }
```

Then change the guard `if (builtinFields.isNotEmpty())` (line 277) to `if (detailFields.isNotEmpty())`, and the loop `builtinFields.forEachIndexed { index, (label, value) ->` (line 285) to:

```kotlin
                                    detailFields.forEachIndexed { index, df ->
                                        val label = df.label
                                        val value = df.value
```

(Keep the rest of the row body — the `HorizontalDivider`, the label `Text`, and the value rendering — unchanged except Step 3.)

- [ ] **Step 3: Key the url / tracking special-cases off type/key instead of label**

The current row derives `isUrl`/`isTracking` from the hardcoded labels (lines 305-306):

```kotlin
                                            val isUrl = label == "Vendor Link" && (value.startsWith("http://") || value.startsWith("https://"))
                                            val isTracking = label == "Tracking #" && value.isNotBlank()
```

Replace those two lines with type/key-driven equivalents so any `url`-typed field is tappable and the builtin tracking-number keeps its Google-search tap:

```kotlin
                                            val isUrl = df.type == "url" && (value.startsWith("http://") || value.startsWith("https://"))
                                            val isTracking = df.key == "trackingNumber" && value.isNotBlank()
```

Leave the `if (isUrl) { ... } else if (isTracking) { ... } else { ... }` rendering branches (lines 308-353) unchanged.

- [ ] **Step 4: Compile**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Remove any now-unused import until clean.

- [ ] **Step 5: Full app unit-test run (regression guard)**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (no regressions across the ~67 test classes).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/supply/SupplyItemDetailScreen.kt
git commit -m "feat(M-13): schema-driven supply Detail screen (type-aware, orphans hidden)"
```

---

## Task 5: Manual verification + audit sign-off update

**Files:**
- Modify: `C:\Scripts\Hours Tracker\METADATA_AUDIT.md`

- [ ] **Step 1: Build a debug APK / install to a tablet**

Run: `./gradlew.bat :app:assembleDebug` (or the repo's `adb-install-*.ps1` if a tablet is connected).
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Manual walkthrough (requires a tablet + admin portal)**

Confirm, and note results:
1. Add a custom field (e.g. `Diameter`, type `text`) in the admin schema editor → it renders in the tablet Supply Edit screen, saves, and shows in Detail.
2. A `url`-typed field is tappable in Detail (opens the URI); the builtin Tracking # still taps to a Google search.
3. A `number`-typed field shows the numeric keyboard.
4. Delete the custom field in admin → its previously-saved value is now hidden in both Edit and Detail, but editing-and-saving the item does not drop the stored value (inspect `items/<id>.json` — the orphan key is still present under `customFields`).

- [ ] **Step 3: Update the M-13 audit entry**

In `C:\Scripts\Hours Tracker\METADATA_AUDIT.md`, update the M-13 section: change the Status line from `RESOLVED (write-on-read + admin-copy arm; full tablet parity intentionally deferred)` to `RESOLVED` and append a follow-up sign-off note recording that full tablet parity now shipped (fully schema-driven Edit/Detail, `customFields` round-trip, orphan-safe), with the KKC commit SHAs from Tasks 1-4 and the manual-verification result. Also add a change-log row at the bottom of §8.

- [ ] **Step 4: Commit the audit update**

```bash
cd "/c/Scripts/Hours Tracker"
git add METADATA_AUDIT.md
git commit -m "docs(M-13): sign off full tablet custom-field parity"
```

---

## Self-Review

- **Spec coverage:** fully schema-driven render (Tasks 3-4) ✓; builtin/custom routing (Task 2) ✓; `DEFAULT_SUPPLY_SCHEMA` fallback (Tasks 1-2) ✓; orphan preserve-but-hide (Task 2 routing + Task 4 render filter) ✓; type handling text/number/url/date (Task 3 input + Task 4 render) ✓; `customFields` persistence (Task 1) ✓; tests on pure helpers + repo (Tasks 1-2) ✓; manual walkthrough + out-of-scope items (Task 5) ✓.
- **Type consistency:** `routeSupplyFieldValues` / `RoutedSupplyFields` (`.fields`, `.customFields`) used identically in Tasks 2-3; `schemaOrDefault()` defined in Task 1, consumed in Tasks 3-4; `SupplyFieldInput(field,value,onValueChange)` defined Task 3 Step 1, called Task 3 Step 5; `DEFAULT_SUPPLY_SCHEMA` defined Task 1, used in Tasks 2 & 4. Consistent.
- **Placeholder scan:** none — every code step carries complete code.
- **Web-side note:** the spec's out-of-scope item (backend already models `customFields`) is a verification concern; if Step 2 shows custom values do NOT appear in the web admin, that is a separate backend finding, not part of this plan.
