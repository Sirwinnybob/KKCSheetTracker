# Specialty Tablet Custom Items — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let tablet users create, edit, and delete specialty items directly from `SpecialtyJobDetailScreen`, stored in per-device JSON files so Syncthing never conflicts, and visible on the admin page with a "Tablet" badge.

**Architecture:** Per-device `tablet_items_{deviceId}.json` files (mirrors the existing `.tracker/{deviceId}.json` pattern). Android reads all tablet files and merges with admin items inside `SpecialtyProgressStore`. A new `TabletSpecialtyItemsStore` handles the write path. The admin server globs for all tablet files, merges with admin items on GET, and routes DELETE to the correct source file.

**Tech Stack:** Android: Kotlin, Jetpack Compose, Material3, Gson, Coroutines. Server: Node.js, TypeScript, Express. Admin UI: React, TypeScript, Tailwind.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `app/.../data/models/Models.kt` | Modify | Add `TabletSpecialtyItem` data class |
| `app/.../data/TabletSpecialtyItemsStore.kt` | Create | Glob-read + atomic-write tablet item files |
| `app/.../data/SpecialtyProgressStore.kt` | Modify | Extend `loadMergedSpecialtyItems()` to also read tablet files |
| `app/.../data/SpecialtyStateStore.kt` | Modify | Add `tabletItemsStore` param; expose `tabletId`, `saveTabletItem`, `deleteTabletItem` |
| `app/.../ui/specialty/AddSpecialtyItemSheet.kt` | Create | `ModalBottomSheet` form — all admin fields, category-dependent visibility |
| `app/.../ui/specialty/SpecialtyJobDetailScreen.kt` | Modify | FAB, edit pencil + delete on own items, `AddSpecialtyItemSheet` wiring |
| `app/.../navigation/NavGraph.kt` | Modify | Create `TabletSpecialtyItemsStore`, pass to `SpecialtyStateStore` |
| `server/src/lib/tabletSpecialtyItemsStore.ts` | Create | Glob-read + delete by sourceFile for server side |
| `server/src/types.ts` | Modify | Add `source?: 'admin' \| 'tablet'`, `createdByDevice?` to `SpecialtyItemWithStatus` |
| `server/src/routes/specialty.ts` | Modify | GET merges both sources; DELETE routes to correct file |
| `client/src/types.ts` | Modify | Add `source?: 'admin' \| 'tablet'` to `SpecialtyItemWithStatus` |
| `client/src/components/SpecialtyTab.tsx` | Modify | "Tablet" badge on tablet rows; read-only cells for tablet items |

---

## Task 1: Android — `TabletSpecialtyItem` data class + `TabletSpecialtyItemsStore`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/models/Models.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/data/TabletSpecialtyItemsStore.kt`

- [ ] **Step 1: Add `TabletSpecialtyItem` to `Models.kt`**

Open `app/src/main/java/com/kkc/sheettracker/data/models/Models.kt`. Find the block containing `data class SpecialtyItem`. Add the new data class immediately after `SpecialtyItem` (before `SpecialtyCompletionState`):

```kotlin
data class TabletSpecialtyItem(
    val id: String,                          // raw UUID — NO "tablet:" prefix stored in JSON
    val name: String,
    val category: SpecialtyItemCategory = SpecialtyItemCategory.CUSTOM,
    val cabinetNumbers: List<String> = emptyList(),
    val stations: List<SpecialtyStation> = emptyList(),
    val dimensions: String? = null,
    val quantity: Int? = null,
    val material: String? = null,
    val supplier: String? = null,
    val modelNumber: String? = null,
    val orderDate: String? = null,
    val trackingNumber: String? = null,
    val orderUrl: String? = null,
    val notes: String? = null,
    val createdAt: String = "",
    val createdByDevice: String = ""
)
```

- [ ] **Step 2: Create `TabletSpecialtyItemsStore.kt`**

Create `app/src/main/java/com/kkc/sheettracker/data/TabletSpecialtyItemsStore.kt`:

```kotlin
package com.kkc.sheettracker.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.TabletSpecialtyItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TabletSpecialtyItemsStore(
    private val baseDir: File,
    val tabletId: String        // public so SpecialtyStateStore can expose it
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val writeMutexByJob = ConcurrentHashMap<String, Mutex>()

    /** Returns items from ALL tablet files for this job, merged into one list. */
    fun loadAllItems(jobFolderName: String): List<TabletSpecialtyItem> {
        val dir = adminDir(jobFolderName)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("tablet_items_") && it.extension.equals("json", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.flatMap { parseFile(it) }
            .orEmpty()
    }

    /** Saves (create or update by id) an item in this tablet's own file. */
    suspend fun saveItem(jobFolderName: String, item: TabletSpecialtyItem) {
        val mutex = writeMutexByJob.getOrPut(jobFolderName) { Mutex() }
        mutex.withLock {
            val existing = loadOwnItems(jobFolderName).toMutableList()
            val idx = existing.indexOfFirst { it.id == item.id }
            if (idx >= 0) existing[idx] = item else existing += item
            writeItems(jobFolderName, existing)
        }
    }

    /** Deletes an item from this tablet's own file. `itemId` may include the "tablet:" prefix. */
    suspend fun deleteItem(jobFolderName: String, itemId: String) {
        val rawId = itemId.removePrefix("tablet:")
        val mutex = writeMutexByJob.getOrPut(jobFolderName) { Mutex() }
        mutex.withLock {
            val updated = loadOwnItems(jobFolderName).filter { it.id != rawId }
            writeItems(jobFolderName, updated)
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────────

    private fun loadOwnItems(jobFolderName: String): List<TabletSpecialtyItem> {
        val file = ownFile(jobFolderName)
        if (!file.exists()) return emptyList()
        return parseFile(file)
    }

    private fun parseFile(file: File): List<TabletSpecialtyItem> {
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return emptyList()
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.getAsJsonArray("items")
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { el -> parseItem(el as? JsonObject ?: return@mapNotNull null) }
    }

    private fun parseItem(obj: JsonObject): TabletSpecialtyItem? {
        val id = obj.getStr("id").takeIf { it.isNotBlank() } ?: return null
        val name = obj.getStr("name").takeIf { it.isNotBlank() } ?: return null
        val category = when (obj.getStr("category").uppercase(Locale.US)) {
            "TO_ORDER" -> SpecialtyItemCategory.TO_ORDER
            else -> SpecialtyItemCategory.CUSTOM
        }
        val stations = obj.get("stations")?.asJsonArray?.mapNotNull { el ->
            runCatching { SpecialtyStation.valueOf(el.asString.trim().uppercase(Locale.US)) }.getOrNull()
        }.orEmpty()
        val cabinetNumbers = obj.get("cabinetNumbers")?.asJsonArray?.mapNotNull { el ->
            runCatching { el.asString.trim().takeIf { it.isNotBlank() } }.getOrNull()
        }.orEmpty()
        return TabletSpecialtyItem(
            id = id,
            name = name,
            category = category,
            cabinetNumbers = cabinetNumbers,
            stations = stations,
            dimensions = obj.getNullStr("dimensions"),
            quantity = runCatching { obj.get("quantity")?.takeIf { !it.isJsonNull }?.asInt }.getOrNull(),
            material = obj.getNullStr("material"),
            supplier = obj.getNullStr("supplier"),
            modelNumber = obj.getNullStr("modelNumber"),
            orderDate = obj.getNullStr("orderDate"),
            trackingNumber = obj.getNullStr("trackingNumber"),
            orderUrl = obj.getNullStr("orderUrl"),
            notes = obj.getNullStr("notes"),
            createdAt = obj.getStr("createdAt"),
            createdByDevice = obj.getStr("createdByDevice")
        )
    }

    private fun writeItems(jobFolderName: String, items: List<TabletSpecialtyItem>) {
        val array = JsonArray()
        items.forEach { item ->
            array.add(JsonObject().apply {
                addProperty("id", item.id)
                addProperty("name", item.name)
                addProperty("category", item.category.name)
                add("cabinetNumbers", JsonArray().also { arr -> item.cabinetNumbers.forEach { arr.add(it) } })
                add("stations", JsonArray().also { arr -> item.stations.forEach { arr.add(it.name) } })
                item.dimensions?.let { addProperty("dimensions", it) }
                item.quantity?.let { addProperty("quantity", it) }
                item.material?.let { addProperty("material", it) }
                item.supplier?.let { addProperty("supplier", it) }
                item.modelNumber?.let { addProperty("modelNumber", it) }
                item.orderDate?.let { addProperty("orderDate", it) }
                item.trackingNumber?.let { addProperty("trackingNumber", it) }
                item.orderUrl?.let { addProperty("orderUrl", it) }
                item.notes?.let { addProperty("notes", it) }
                addProperty("createdAt", item.createdAt)
                addProperty("createdByDevice", item.createdByDevice)
            })
        }
        atomicWrite(ownFile(jobFolderName), gson.toJson(array))
    }

    private fun atomicWrite(target: File, body: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
        temp.writeText(body)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun adminDir(jobFolderName: String) = File(baseDir, "$jobFolderName/.metadata/admin")
    private fun ownFile(jobFolderName: String) = File(adminDir(jobFolderName), "tablet_items_$tabletId.json")

    private fun JsonObject.getStr(key: String): String = get(key)?.asString?.trim().orEmpty()
    private fun JsonObject.getNullStr(key: String): String? = get(key)?.asString?.trim()?.takeIf { it.isNotBlank() }
}
```

- [ ] **Step 3: Build to confirm no compile errors**

```
cd C:/Scripts/KKCSheetTracker && ./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/models/Models.kt \
        app/src/main/java/com/kkc/sheettracker/data/TabletSpecialtyItemsStore.kt
git commit -m "feat: add TabletSpecialtyItem model and TabletSpecialtyItemsStore"
```

---

## Task 2: Android — Extend `SpecialtyProgressStore` + update `SpecialtyStateStore`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/SpecialtyProgressStore.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/SpecialtyStateStore.kt`

- [ ] **Step 1: Add `loadTabletItems` and `parseTabletItemsFile` to `SpecialtyProgressStore`**

In `SpecialtyProgressStore.kt`, add these two private functions (place them near the end of the class, before the extension functions section):

```kotlin
private fun loadTabletItems(jobFolderName: String): List<SpecialtyItem> {
    val dir = File(baseDir, "$jobFolderName/.metadata/admin")
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles()
        ?.filter { it.isFile && it.name.startsWith("tablet_items_") && it.extension.equals("json", ignoreCase = true) }
        ?.sortedBy { it.name.lowercase(Locale.US) }
        ?.flatMap { parseTabletItemsFile(it) }
        .orEmpty()
}

private fun parseTabletItemsFile(file: File): List<SpecialtyItem> {
    val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
    val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return emptyList()
    val array = when {
        root.isJsonArray -> root.asJsonArray
        root.isJsonObject -> root.asJsonObject.getAsJsonArray("items")
        else -> null
    } ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.getString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val name = obj.getString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SpecialtyItem(
            id = "tablet:$id",
            name = name,
            cabinetNumbers = obj.getFlexibleStringList("cabinetNumbers"),
            category = parseCategory(obj.getString("category")),
            stations = parseStations(obj.get("stations")),
            supplier = obj.getNullableString("supplier"),
            model = obj.getFirstNonBlankString("modelNumber", "model"),
            orderDate = obj.getNullableString("orderDate"),
            tracking = obj.getFirstNonBlankString("trackingNumber", "tracking"),
            orderUrl = obj.getNullableString("orderUrl"),
            notes = obj.getNullableString("notes"),
            attachments = emptyList(),
            autoDetected = false,
            createdAt = obj.getNullableString("createdAt"),
            createdBy = obj.getNullableString("createdByDevice"),
            dimensions = obj.getNullableString("dimensions"),
            quantity = runCatching {
                obj.get("quantity")?.let { e ->
                    if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asInt else null
                }
            }.getOrNull(),
            material = obj.getNullableString("material")
        )
    }
}
```

- [ ] **Step 2: Update `loadMergedSpecialtyItems` to include tablet items**

Find the existing `loadMergedSpecialtyItems` function. Replace it with:

```kotlin
private fun loadMergedSpecialtyItems(jobFolderName: String): List<SpecialtyItem> {
    val specialtyItems = specialtyItemsFile(jobFolderName)
        .takeIf { it.exists() && it.isFile }
        ?.let { parseSpecialtyItems(it.readText()) }
        .orEmpty()
    val checklistItems = checklistFile(jobFolderName)
        .takeIf { it.exists() && it.isFile }
        ?.let { parseChecklistAsSpecialtyItems(it.readText()) }
        .orEmpty()
    val tabletItems = loadTabletItems(jobFolderName)

    val allItems = specialtyItems + checklistItems + tabletItems
    if (allItems.isEmpty()) return emptyList()

    return allItems
        .distinctBy { it.id }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
```

- [ ] **Step 3: Update `SpecialtyStateStore` to accept `tabletItemsStore` and expose helpers**

In `SpecialtyStateStore.kt`, add `tabletItemsStore: TabletSpecialtyItemsStore` as a constructor parameter:

```kotlin
class SpecialtyStateStore(
    private val specialtyScanCoordinator: SpecialtyScanCoordinator,
    private val specialtyProgressStore: SpecialtyProgressStore,
    private val sheetRipProgressStore: SheetRipProgressStore,
    private val tabletItemsStore: TabletSpecialtyItemsStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
```

Then add three new members after the existing `sheetRipDoneVersion` declaration:

```kotlin
/** The deviceId of this tablet — used by the UI to identify own items. */
val tabletId: String get() = tabletItemsStore.tabletId

/** Creates or updates a tablet-created specialty item, then invalidates the cache. */
suspend fun saveTabletItem(jobFolderName: String, item: TabletSpecialtyItem) =
    withContext(ioDispatcher) {
        tabletItemsStore.saveItem(jobFolderName, item)
        specialtyProgressStore.invalidateJobCache(jobFolderName)
    }

/** Deletes a tablet-created specialty item (by id, "tablet:" prefix optional), then invalidates the cache. */
suspend fun deleteTabletItem(jobFolderName: String, itemId: String) =
    withContext(ioDispatcher) {
        tabletItemsStore.deleteItem(jobFolderName, itemId)
        specialtyProgressStore.invalidateJobCache(jobFolderName)
    }
```

Add the import at the top of `SpecialtyStateStore.kt`:
```kotlin
import com.kkc.sheettracker.data.models.TabletSpecialtyItem
```

- [ ] **Step 4: Build to confirm no compile errors**

```
cd C:/Scripts/KKCSheetTracker && ./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL (NavGraph.kt will fail because `SpecialtyStateStore` constructor changed — that's fixed in Task 5).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/SpecialtyProgressStore.kt \
        app/src/main/java/com/kkc/sheettracker/data/SpecialtyStateStore.kt
git commit -m "feat: read tablet specialty items into SpecialtyProgressStore; expose save/delete via SpecialtyStateStore"
```

---

## Task 3: Android — `AddSpecialtyItemSheet` composable

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/specialty/AddSpecialtyItemSheet.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.TabletSpecialtyItem
import java.time.Instant
import java.util.UUID

private val SPECIALTY_STATIONS = listOf(
    SpecialtyStation.CNC,
    SpecialtyStation.SAW,
    SpecialtyStation.EDGE_BANDER,
    SpecialtyStation.ASSEMBLY,
    SpecialtyStation.HARDWOODS,
    SpecialtyStation.SPECIALTY
)

private fun stationLabel(station: SpecialtyStation) = when (station) {
    SpecialtyStation.CNC -> "CNC"
    SpecialtyStation.SAW -> "SAW"
    SpecialtyStation.EDGE_BANDER -> "Edge Bander"
    SpecialtyStation.ASSEMBLY -> "Assembly"
    SpecialtyStation.HARDWOODS -> "Hardwoods"
    SpecialtyStation.SPECIALTY -> "Specialty"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSpecialtyItemSheet(
    existingItem: TabletSpecialtyItem?,   // null = create new
    tabletId: String,
    onDismiss: () -> Unit,
    onSave: (TabletSpecialtyItem) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf(existingItem?.name ?: "") }
    var category by remember { mutableStateOf(existingItem?.category ?: SpecialtyItemCategory.CUSTOM) }
    var cabinetNumbersText by remember { mutableStateOf(existingItem?.cabinetNumbers?.joinToString(", ") ?: "") }
    var selectedStations by remember { mutableStateOf(existingItem?.stations?.toSet() ?: emptySet<SpecialtyStation>()) }
    var notes by remember { mutableStateOf(existingItem?.notes ?: "") }
    // CUSTOM fields
    var dimensions by remember { mutableStateOf(existingItem?.dimensions ?: "") }
    var quantityText by remember { mutableStateOf(existingItem?.quantity?.toString() ?: "") }
    var material by remember { mutableStateOf(existingItem?.material ?: "") }
    // TO_ORDER fields
    var supplier by remember { mutableStateOf(existingItem?.supplier ?: "") }
    var modelNumber by remember { mutableStateOf(existingItem?.modelNumber ?: "") }
    var orderDate by remember { mutableStateOf(existingItem?.orderDate ?: "") }
    var trackingNumber by remember { mutableStateOf(existingItem?.trackingNumber ?: "") }
    var orderUrl by remember { mutableStateOf(existingItem?.orderUrl ?: "") }

    fun buildItem(): TabletSpecialtyItem {
        val rawId = existingItem?.id ?: UUID.randomUUID().toString()
        val cabs = cabinetNumbersText.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return TabletSpecialtyItem(
            id = rawId,
            name = name.trim(),
            category = category,
            cabinetNumbers = cabs,
            stations = selectedStations.toList(),
            notes = notes.trim().takeIf { it.isNotBlank() },
            dimensions = dimensions.trim().takeIf { it.isNotBlank() },
            quantity = quantityText.trim().toIntOrNull(),
            material = material.trim().takeIf { it.isNotBlank() },
            supplier = supplier.trim().takeIf { it.isNotBlank() },
            modelNumber = modelNumber.trim().takeIf { it.isNotBlank() },
            orderDate = orderDate.trim().takeIf { it.isNotBlank() },
            trackingNumber = trackingNumber.trim().takeIf { it.isNotBlank() },
            orderUrl = orderUrl.trim().takeIf { it.isNotBlank() },
            createdAt = existingItem?.createdAt ?: Instant.now().toString(),
            createdByDevice = tabletId
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (existingItem == null) "Add Item" else "Edit Item",
                style = MaterialTheme.typography.titleMedium
            )

            // ── Category ─────────────────────────────────────────────────────────
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = category == SpecialtyItemCategory.CUSTOM,
                    onClick = { category = SpecialtyItemCategory.CUSTOM },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    label = { Text("CUSTOM") }
                )
                SegmentedButton(
                    selected = category == SpecialtyItemCategory.TO_ORDER,
                    onClick = { category = SpecialtyItemCategory.TO_ORDER },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    label = { Text("TO ORDER") }
                )
            }

            // ── Common fields ─────────────────────────────────────────────────────
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = cabinetNumbersText,
                onValueChange = { cabinetNumbersText = it },
                label = { Text("Cabinet Numbers (comma-separated)") },
                placeholder = { Text("e.g. 12, 13, 14") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Stations checkboxes
            Text("Stations", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SPECIALTY_STATIONS.forEach { station ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Checkbox(
                            checked = station in selectedStations,
                            onCheckedChange = { checked ->
                                selectedStations = if (checked) selectedStations + station
                                else selectedStations - station
                            }
                        )
                        Text(stationLabel(station), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // ── CUSTOM-only fields ────────────────────────────────────────────────
            if (category == SpecialtyItemCategory.CUSTOM) {
                OutlinedTextField(
                    value = dimensions,
                    onValueChange = { dimensions = it },
                    label = { Text("Dimensions") },
                    placeholder = { Text("e.g. 36x12x0.75") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    label = { Text("Quantity") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = material,
                    onValueChange = { material = it },
                    label = { Text("Material") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // ── TO_ORDER-only fields ──────────────────────────────────────────────
            if (category == SpecialtyItemCategory.TO_ORDER) {
                OutlinedTextField(
                    value = supplier,
                    onValueChange = { supplier = it },
                    label = { Text("Supplier") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = modelNumber,
                    onValueChange = { modelNumber = it },
                    label = { Text("Model Number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = orderDate,
                    onValueChange = { orderDate = it },
                    label = { Text("Order Date (YYYY-MM-DD)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = trackingNumber,
                    onValueChange = { trackingNumber = it },
                    label = { Text("Tracking Number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = orderUrl,
                    onValueChange = { orderUrl = it },
                    label = { Text("Order URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // ── Notes ─────────────────────────────────────────────────────────────
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            // ── Action row ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = { onSave(buildItem()) },
                    enabled = name.isNotBlank()
                ) { Text("Save") }
            }
        }
    }
}
```

- [ ] **Step 2: Build**

```
cd C:/Scripts/KKCSheetTracker && ./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/specialty/AddSpecialtyItemSheet.kt
git commit -m "feat: add AddSpecialtyItemSheet bottom sheet form for tablet-created items"
```

---

## Task 4: Android — FAB + edit/delete in `SpecialtyJobDetailScreen` + NavGraph wiring

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

- [ ] **Step 1: Fix NavGraph — create `TabletSpecialtyItemsStore` and pass to `SpecialtyStateStore`**

In `NavGraph.kt`, find the block that creates `specialtyStateStore` (~line 307):

```kotlin
val specialtyStateStore = remember(specialtyScanCoordinator, specialtyProgressStore, sheetRipProgressStore) {
    SpecialtyStateStore(
        specialtyScanCoordinator = specialtyScanCoordinator,
        specialtyProgressStore = specialtyProgressStore,
        sheetRipProgressStore = sheetRipProgressStore
    )
}
```

Replace it with:

```kotlin
val tabletSpecialtyItemsStore = remember(basePath, tabletId) {
    TabletSpecialtyItemsStore(File(basePath), tabletId)
}
val specialtyStateStore = remember(specialtyScanCoordinator, specialtyProgressStore, sheetRipProgressStore, tabletSpecialtyItemsStore) {
    SpecialtyStateStore(
        specialtyScanCoordinator = specialtyScanCoordinator,
        specialtyProgressStore = specialtyProgressStore,
        sheetRipProgressStore = sheetRipProgressStore,
        tabletItemsStore = tabletSpecialtyItemsStore
    )
}
```

Add this import at the top of `NavGraph.kt` (near the other data imports):
```kotlin
import com.kkc.sheettracker.data.TabletSpecialtyItemsStore
```

- [ ] **Step 2: Add FAB and sheet state vars to `SpecialtyJobDetailScreen`**

At the top of the `SpecialtyJobDetailScreen` composable (after the existing `var toggleErrorMessage` line), add:

```kotlin
var showAddSheet by remember(jobFolderName) { mutableStateOf(false) }
var editingItem by remember(jobFolderName) { mutableStateOf<com.kkc.sheettracker.data.models.TabletSpecialtyItem?>(null) }
var deleteTargetItemId by remember(jobFolderName) { mutableStateOf<String?>(null) }
```

- [ ] **Step 3: Add FAB to `Scaffold`**

Find the `Scaffold(` call. Add `floatingActionButton` parameter (after `topBar`):

```kotlin
Scaffold(
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = { /* existing */ },
    floatingActionButton = {
        androidx.compose.material3.FloatingActionButton(
            onClick = { editingItem = null; showAddSheet = true }
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.Add,
                contentDescription = "Add Item"
            )
        }
    }
) { padding ->
    // existing content unchanged
```

Add to imports at the top of the file:
```kotlin
import androidx.compose.material.icons.filled.Add
```

- [ ] **Step 4: Render `AddSpecialtyItemSheet` and delete confirmation dialog**

At the end of the `Scaffold`'s `content` lambda (after the `LazyColumn` closing brace, but still inside the Scaffold content block), add:

```kotlin
// Add / Edit sheet
if (showAddSheet) {
    AddSpecialtyItemSheet(
        existingItem = editingItem,
        tabletId = specialtyStateStore.tabletId,
        onDismiss = { showAddSheet = false; editingItem = null },
        onSave = { item ->
            coroutineScope.launch {
                specialtyStateStore.saveTabletItem(jobFolderName, item)
                showAddSheet = false
                editingItem = null
            }
        }
    )
}

// Delete confirmation dialog
val deletingItemId = deleteTargetItemId
if (deletingItemId != null) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { deleteTargetItemId = null },
        title = { Text("Delete Item") },
        text = { Text("Delete this item? This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = {
                deleteTargetItemId = null
                coroutineScope.launch {
                    specialtyStateStore.deleteTabletItem(jobFolderName, deletingItemId)
                }
            }) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = { deleteTargetItemId = null }) { Text("Cancel") }
        }
    )
}
```

- [ ] **Step 5: Add edit pencil + delete affordance to own items**

Find the function that renders individual specialty items (around line 460, the `ProgressCard(` call). The `headerActions` lambda already renders the "View" button, "To Order" chip, and station chips. Add edit/delete controls at the **beginning** of that lambda for tablet-owned items:

```kotlin
headerActions = {
    // Edit/Delete for own tablet items
    val isMyTabletItem = item.id.startsWith("tablet:") &&
        item.createdBy == specialtyStateStore.tabletId
    if (isMyTabletItem) {
        val rawId = item.id.removePrefix("tablet:")
        val tabletItem = specialtyStateStore /* resolve TabletSpecialtyItem from resolved */ .let {
            // Build a TabletSpecialtyItem from the SpecialtyItem fields for pre-filling the edit form
            com.kkc.sheettracker.data.models.TabletSpecialtyItem(
                id = rawId,
                name = item.name,
                category = item.category,
                cabinetNumbers = item.cabinetNumbers,
                stations = item.stations,
                dimensions = item.dimensions,
                quantity = item.quantity,
                material = item.material,
                supplier = item.supplier,
                modelNumber = item.model,
                orderDate = item.orderDate,
                trackingNumber = item.tracking,
                orderUrl = item.orderUrl,
                notes = item.notes,
                createdAt = item.createdAt.orEmpty(),
                createdByDevice = item.createdBy.orEmpty()
            )
        }
        androidx.compose.material3.IconButton(
            onClick = { editingItem = tabletItem; showAddSheet = true },
            modifier = Modifier.heightIn(min = 24.dp, max = 32.dp)
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.Edit,
                contentDescription = "Edit item",
                modifier = androidx.compose.ui.Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        androidx.compose.material3.IconButton(
            onClick = { deleteTargetItemId = item.id },
            modifier = Modifier.heightIn(min = 24.dp, max = 32.dp)
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.Delete,
                contentDescription = "Delete item",
                modifier = androidx.compose.ui.Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
    // existing headerActions content below...
```

Add missing imports:
```kotlin
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.unit.dp  // already present
import androidx.compose.foundation.layout.size
```

- [ ] **Step 6: Build the full app**

```
cd C:/Scripts/KKCSheetTracker && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Install and smoke test on tablet**

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Verify:
1. Open a specialty job — FAB "+" appears at bottom-right
2. Tap FAB — `AddSpecialtyItemSheet` opens with CUSTOM selected by default
3. Fill in a name, select SAW station, tap Save — item appears in the list
4. Check that `tablet_items_{tabletId}.json` was created in `.metadata/admin/` for the job
5. Tap the pencil icon on the new item — form opens pre-filled
6. Tap the trash icon — confirmation dialog appears, confirm — item removed

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt \
        app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat: add FAB and tablet item edit/delete to SpecialtyJobDetailScreen; wire TabletSpecialtyItemsStore in NavGraph"
```

---

## Task 5: Server — `tabletSpecialtyItemsStore.ts` + routes + types

**Files:**
- Create: `server/src/lib/tabletSpecialtyItemsStore.ts`
- Modify: `server/src/types.ts`
- Modify: `server/src/routes/specialty.ts`

- [ ] **Step 1: Create `tabletSpecialtyItemsStore.ts`**

```typescript
import fs from 'fs';
import path from 'path';
import { SpecialtyItem } from '../types';

const adminDir = (jobFolder: string) => path.join(jobFolder, '.metadata', 'admin');

function readJson<T>(p: string, def: T): T {
  if (!fs.existsSync(p)) return def;
  try { return JSON.parse(fs.readFileSync(p, 'utf-8')); } catch { return def; }
}

function writeJson(p: string, data: unknown): void {
  const tmp = p + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2));
  fs.renameSync(tmp, p);
}

export interface TabletItemWithMeta extends SpecialtyItem {
  /** Internal only — stripped before sending to client. */
  _sourceFile: string;
}

/**
 * Reads all tablet_items_*.json files for a job and returns a merged list.
 * Each item has `source: "tablet"`, `createdByDevice`, and internal `_sourceFile`.
 */
export function getTabletItems(jobFolder: string): TabletItemWithMeta[] {
  const dir = adminDir(jobFolder);
  if (!fs.existsSync(dir)) return [];

  const files = fs.readdirSync(dir).filter(
    f => f.startsWith('tablet_items_') && f.endsWith('.json')
  );

  const result: TabletItemWithMeta[] = [];
  for (const filename of files.sort()) {
    const filePath = path.join(dir, filename);
    const raw = readJson<unknown[]>(filePath, []);
    const items = Array.isArray(raw) ? raw : (raw as { items?: unknown[] }).items ?? [];
    for (const item of items) {
      if (typeof item !== 'object' || item === null) continue;
      const obj = item as Record<string, unknown>;
      const id = String(obj.id ?? '').trim();
      const name = String(obj.name ?? '').trim();
      if (!id || !name) continue;
      const category = String(obj.category ?? 'CUSTOM') === 'TO_ORDER' ? 'TO_ORDER' : 'CUSTOM';
      result.push({
        id: `tablet:${id}`,
        name,
        cabinetNumbers: Array.isArray(obj.cabinetNumbers)
          ? (obj.cabinetNumbers as string[]).map(Number).filter(n => !isNaN(n))
          : [],
        category,
        stations: Array.isArray(obj.stations) ? (obj.stations as string[]) as SpecialtyItem['stations'] : [],
        supplier: obj.supplier as string | undefined,
        modelNumber: obj.modelNumber as string | undefined,
        orderDate: obj.orderDate as string | undefined,
        trackingNumber: obj.trackingNumber as string | undefined,
        orderUrl: obj.orderUrl as string | undefined,
        notes: obj.notes as string | undefined,
        dimensions: obj.dimensions as string | undefined,
        quantity: typeof obj.quantity === 'number' ? obj.quantity : undefined,
        material: obj.material as string | undefined,
        attachments: [],
        autoDetected: false,
        createdAt: String(obj.createdAt ?? new Date().toISOString()),
        createdBy: String(obj.createdByDevice ?? ''),
        _sourceFile: filePath,
      });
    }
  }
  return result;
}

/**
 * Deletes an item by raw id (no "tablet:" prefix) from the given source file.
 */
export function deleteTabletItem(sourceFile: string, rawId: string): void {
  const items = readJson<unknown[]>(sourceFile, []);
  const arr = Array.isArray(items) ? items : [];
  const updated = arr.filter(item => {
    if (typeof item !== 'object' || item === null) return false;
    return (item as Record<string, unknown>).id !== rawId;
  });
  writeJson(sourceFile, updated);
}
```

- [ ] **Step 2: Add `source` and `createdByDevice` to server `types.ts`**

In `server/src/types.ts`, find `SpecialtyItemWithStatus` and update it:

```typescript
export interface SpecialtyItemWithStatus extends SpecialtyItem {
  completedAt: string | null;
  completedBy: string | null;
  source?: 'admin' | 'tablet';
  createdByDevice?: string;
}
```

- [ ] **Step 3: Update the GET route in `specialty.ts` to merge tablet items**

At the top of `specialty.ts`, add the import:
```typescript
import { getTabletItems, deleteTabletItem, TabletItemWithMeta } from '../lib/tabletSpecialtyItemsStore';
```

Replace the GET `'/'` handler:

```typescript
router.get('/', (req, res) => {
  try {
    const jobFolder = folder(req);
    const adminItems: SpecialtyItemWithStatus[] = getSpecialtyItemsWithStatus(jobFolder).map(i => ({
      ...i,
      source: 'admin' as const,
    }));

    const tabletItems: SpecialtyItemWithStatus[] = getTabletItems(jobFolder).map(({ _sourceFile, ...item }) => ({
      ...item,
      completedAt: null,
      completedBy: null,
      source: 'tablet' as const,
      createdByDevice: item.createdBy,
    }));

    res.json([...adminItems, ...tabletItems]);
  } catch (err) { res.status(errStatus(err)).json({ error: String(err) }); }
});
```

- [ ] **Step 4: Update the DELETE route to handle tablet items**

Replace the DELETE `'/:id'` handler:

```typescript
router.delete('/:id', (req, res) => {
  try {
    const jobFolder = folder(req);
    const itemId = req.params.id as string;

    if (itemId.startsWith('tablet:')) {
      // Tablet item: find the source file and delete from it
      const rawId = itemId.replace(/^tablet:/, '');
      const tabletItems = getTabletItems(jobFolder);
      const target = tabletItems.find(i => i.id === itemId);
      if (!target) return res.status(404).json({ error: 'Item not found' });
      deleteTabletItem(target._sourceFile, rawId);
      return res.json({ ok: true });
    }

    // Admin item: existing logic
    deleteSpecialtyItem(jobFolder, itemId);
    const attDir = path.join(jobFolder, '.metadata', 'admin', 'specialty_attachments', itemId);
    if (fs.existsSync(attDir)) fs.rmSync(attDir, { recursive: true });
    res.json({ ok: true });
  } catch (err) { res.status(errStatus(err)).json({ error: String(err) }); }
});
```

- [ ] **Step 5: Build the server**

```
cd C:/Scripts/kkc-admin && npm run build
```

Expected: BUILD SUCCESSFUL (0 TypeScript errors)

- [ ] **Step 6: Commit**

```bash
cd C:/Scripts/kkc-admin
git add server/src/lib/tabletSpecialtyItemsStore.ts \
        server/src/types.ts \
        server/src/routes/specialty.ts
git commit -m "feat: merge tablet specialty items in GET; route DELETE to correct source file"
```

---

## Task 6: Admin UI — Tablet badge + read-only rows in `SpecialtyTab.tsx`

**Files:**
- Modify: `client/src/types.ts`
- Modify: `client/src/components/SpecialtyTab.tsx`

- [ ] **Step 1: Add `source` to client `SpecialtyItemWithStatus`**

In `client/src/types.ts`, find `SpecialtyItemWithStatus` and update:

```typescript
export interface SpecialtyItemWithStatus extends SpecialtyItem {
  completedAt: string | null;
  completedBy: string | null;
  source?: 'admin' | 'tablet';
  createdByDevice?: string;
}
```

- [ ] **Step 2: Add "Tablet" badge and read-only rows to `SpecialtyTab.tsx`**

In `SpecialtyTab.tsx`, find the `renderRow` function. Replace the entire `renderRow` with:

```tsx
const renderRow = (item: SpecialtyItemWithStatus) => {
  const isUnordered = item.category === 'TO_ORDER' && !item.orderDate;
  const isTablet = item.source === 'tablet';
  return (
    <tr
      key={item.id}
      className={`border-b border-gray-50 dark:border-kkc-border/50 hover:bg-gray-50/50 dark:hover:bg-kkc-surface2/30 ${isUnordered ? 'bg-red-50/30 dark:bg-red-900/10' : ''}`}
    >
      <td className="px-2 py-1.5 w-7">
        <button
          onClick={() => handleToggle(item.id)}
          disabled={toggling === item.id}
          className={`w-4 h-4 rounded border flex items-center justify-center transition-colors ${
            item.completedAt ? 'bg-kkc-complete border-kkc-complete text-white' : 'border-gray-300 dark:border-kkc-border hover:border-kkc-primary'
          }`}
        >
          {item.completedAt && <span className="text-[8px] leading-none">✓</span>}
        </button>
      </td>
      <td className="px-2 py-1.5 w-16">
        {isTablet
          ? <span className="text-xs text-gray-600 dark:text-gray-400">{item.cabinetNumbers.join(', ') || '—'}</span>
          : <EditableCell
              value={item.cabinetNumbers.join(', ')}
              placeholder="—"
              onSave={v => {
                const nums = v.split(',').map(s => parseInt(s.trim())).filter(n => !isNaN(n));
                patch(item.id, 'cabinetNumbers', nums);
              }}
            />
        }
      </td>
      <td className="px-2 py-1.5 min-w-[140px]">
        {isTablet
          ? <span className="text-xs text-gray-700 dark:text-gray-300">{item.name}</span>
          : <EditableCell value={item.name} placeholder="Item name" onSave={v => patch(item.id, 'name', v)} />
        }
      </td>
      <td className="px-2 py-1.5 w-20">
        <div className="flex items-center gap-1">
          <span className={`text-[9px] font-mono px-1 py-0.5 rounded ${
            item.category === 'TO_ORDER'
              ? 'bg-orange-100 dark:bg-orange-900/30 text-orange-600 dark:text-orange-400'
              : 'bg-blue-100 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400'
          }`}>
            {item.category === 'TO_ORDER' ? 'TO ORDER' : 'CUSTOM'}
          </span>
          {isTablet && (
            <span className="text-[8px] font-mono px-1 py-0.5 rounded bg-gray-100 dark:bg-kkc-surface2 text-gray-500 dark:text-gray-400">
              Tablet
            </span>
          )}
        </div>
      </td>
      <td className="px-2 py-1.5 w-28">
        {isTablet
          ? <span className="text-xs text-gray-600 dark:text-gray-400">
              {item.stations.map(s => STATION_LABEL[s] ?? s).join(', ') || <span className="text-gray-300 dark:text-gray-600">none</span>}
            </span>
          : <StationSelect value={item.stations} onChange={v => patch(item.id, 'stations', v)} />
        }
      </td>
      <td className="px-2 py-1.5 w-24">
        {item.category === 'TO_ORDER'
          ? isTablet
            ? <span className="text-xs text-gray-600 dark:text-gray-400">{item.supplier || <span className="text-gray-300 dark:text-gray-600">—</span>}</span>
            : <EditableCell value={item.supplier} placeholder="Supplier" onSave={v => patch(item.id, 'supplier', v)} />
          : <span className="text-gray-200 dark:text-gray-700">—</span>
        }
      </td>
      <td className="px-2 py-1.5 w-24">
        {item.category === 'TO_ORDER'
          ? isTablet
            ? <span className="text-xs text-gray-600 dark:text-gray-400">{item.modelNumber || <span className="text-gray-300 dark:text-gray-600">—</span>}</span>
            : <EditableCell value={item.modelNumber} placeholder="Model#" onSave={v => patch(item.id, 'modelNumber', v)} />
          : <span className="text-gray-200 dark:text-gray-700">—</span>
        }
      </td>
      <td className="px-2 py-1.5 w-24">
        {item.category === 'TO_ORDER'
          ? isTablet
            ? <span className="text-xs text-gray-600 dark:text-gray-400">{item.orderDate || <span className="text-gray-300 dark:text-gray-600">—</span>}</span>
            : <EditableCell value={item.orderDate} placeholder="Order date" type="date" onSave={v => patch(item.id, 'orderDate', v)} />
          : <span className="text-gray-200 dark:text-gray-700">—</span>
        }
      </td>
      <td className="px-2 py-1.5 w-24">
        {item.category === 'TO_ORDER'
          ? isTablet
            ? <span className="text-xs text-gray-600 dark:text-gray-400">{item.trackingNumber || <span className="text-gray-300 dark:text-gray-600">—</span>}</span>
            : <EditableCell value={item.trackingNumber} placeholder="Tracking#" onSave={v => patch(item.id, 'trackingNumber', v)} />
          : <span className="text-gray-200 dark:text-gray-700">—</span>
        }
      </td>
      <td className="px-2 py-1.5 w-36">
        {isTablet
          ? <span className="text-[9px] text-gray-400 dark:text-kkc-muted italic">Added from tablet</span>
          : <AttachmentCell folderName={folderName} item={item} onChanged={onChanged} />
        }
      </td>
      <td className="px-2 py-1.5 w-10">
        <div className="flex items-center gap-1">
          {isUnordered && <span title="Not yet ordered" className="text-orange-400 text-xs">⚠</span>}
          <button
            onClick={() => handleDelete(item.id)}
            className="text-gray-300 dark:text-gray-600 hover:text-red-400 text-xs transition-colors"
            title="Delete item"
          >
            ×
          </button>
        </div>
      </td>
    </tr>
  );
};
```

- [ ] **Step 3: Build client**

```
cd C:/Scripts/kkc-admin && npm run build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Smoke test in browser**

Start the server (`npm start` or however it runs in dev). Open a job's Specialty tab.

Verify:
1. Admin-created items look identical to before (no regression)
2. If any `tablet_items_*.json` files exist for the job, those items appear with a grey "Tablet" badge
3. Tablet item rows show static text instead of inline-edit inputs
4. Delete (×) button on a tablet item removes it from the correct file

- [ ] **Step 5: Commit**

```bash
cd C:/Scripts/kkc-admin
git add client/src/types.ts \
        client/src/components/SpecialtyTab.tsx
git commit -m "feat: show tablet-added specialty items with Tablet badge; read-only cells for tablet rows"
```

---

## Verification (end-to-end)

1. **Create on tablet** — Open a specialty job, tap FAB, fill Name + CUSTOM fields, save. Item appears in list immediately. File `tablet_items_{tabletId}.json` exists in `{job}/.metadata/admin/`.

2. **Other tablet sees it** — After Syncthing sync, open the same job on a different tablet. Item appears without the edit/delete icons.

3. **Admin sees it** — Reload the admin specialty tab. Item appears with grey "Tablet" badge; cells are read-only; delete (×) button present.

4. **Admin deletes it** — Click (×) on the tablet item in admin. Reload — item gone. After Syncthing, item gone on tablet too.

5. **Edit on owning tablet** — Tap pencil. Form opens pre-filled. Change a field, save. Item updated in list.

6. **TO_ORDER item** — Create with TO_ORDER category. Supplier/Model/Order Date fields shown; CUSTOM fields hidden. Admin shows in TO ORDER section with Tablet badge.

7. **No Syncthing conflict** — Two tablets disconnected, each adds an item. Reconnect. Both items visible on both tablets and admin — each in their own `tablet_items_{deviceId}.json` file.
