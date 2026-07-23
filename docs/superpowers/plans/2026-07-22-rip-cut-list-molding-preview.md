# Rip Cut List Molding Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a preview button to admin board-stock rows in the Rip Cut List that pops a modal showing the linked molding profile's drawing and measurements.

**Architecture:** Parse the already-present `moldingId`/`type` fields out of `board_stock.json` into `AdminBoardStockItem` (currently silently dropped). Add a lightweight `MoldingPreviewDialog` reusing the existing `MoldingLibraryRepository`/Coil SVG pipeline, opened from a new preview `IconButton` on rows that have a `moldingId`.

**Tech Stack:** Kotlin, Jetpack Compose, Gson (existing parser).

Full design: [`docs/superpowers/specs/2026-07-22-rip-cut-list-molding-preview-design.md`](../specs/2026-07-22-rip-cut-list-molding-preview-design.md).

---

### Task 1: Parse `moldingId`/`type` into `AdminBoardStockItem`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/models/Models.kt:853-863`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/AdminBoardStockStore.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/data/AdminBoardStockStoreTest.kt` (new file — none exists yet for this store)

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/java/com/kkc/sheettracker/data/AdminBoardStockStoreTest.kt
package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AdminBoardStockStoreTest {

    private fun writeBoardStock(json: String): File {
        val baseDir = Files.createTempDirectory("admin-board-stock-test").toFile()
        val metaDir = File(baseDir, "123 - Test Job/.metadata/admin").apply { mkdirs() }
        File(metaDir, "board_stock.json").writeText(json)
        return baseDir
    }

    @Test
    fun loadAdminBoardStock_parsesMoldingIdAndType() {
        val baseDir = writeBoardStock(
            """
            {"schemaVersion": 1, "items": [
              {"id": "x1", "material": "PG", "name": "3 1/4\" Flat", "type": "crown", "moldingId": "Crown:151", "feet": 80.0}
            ]}
            """.trimIndent()
        )

        val items = loadAdminBoardStock(baseDir, "123 - Test Job")

        assertEquals(1, items.size)
        assertEquals("Crown:151", items[0].moldingId)
        assertEquals("crown", items[0].type)
    }

    @Test
    fun loadAdminBoardStock_handlesMissingMoldingIdAndType() {
        val baseDir = writeBoardStock(
            """
            {"schemaVersion": 1, "items": [
              {"id": "x1", "material": "Maple", "name": "Face frame stock", "feet": 20.0}
            ]}
            """.trimIndent()
        )

        val items = loadAdminBoardStock(baseDir, "123 - Test Job")

        assertEquals(1, items.size)
        assertNull(items[0].moldingId)
        assertNull(items[0].type)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.AdminBoardStockStoreTest"` (from `C:\Scripts\KKCSheetTracker`)
Expected: FAIL to compile — `AdminBoardStockItem` has no `moldingId`/`type` constructor params

- [ ] **Step 3: Add the fields**

In `Models.kt`, change `AdminBoardStockItem` (lines 853-863) to:

```kotlin
data class AdminBoardStockItem(
    val id: String,
    val material: String,
    val name: String,
    /** null = admin explicitly marked NONE (not needed); 0 = blank/unfilled (hidden on tablets) */
    val feet: Double?,
    val mode: String = "bd_ft",
    val ripLength: Int = 10,
    val createdAt: String = "",
    val createdBy: String = "",
    /** "Crown:151" style id into the molding library — null if this row isn't linked to a profile. */
    val moldingId: String? = null,
    /** "crown" | "base" | "scribe" | ... — null if unset. */
    val type: String? = null
)
```

In `AdminBoardStockStore.kt`, in `loadAdminBoardStock`'s parse block (after the existing `ripLength` line, before constructing `AdminBoardStockItem`), add:

```kotlin
            val type = obj.get("type")?.asString?.trim()?.takeIf { it.isNotBlank() }
            val moldingId = obj.get("moldingId")?.asString?.trim()?.takeIf { it.isNotBlank() }
```

and pass both into the constructor call:

```kotlin
            AdminBoardStockItem(
                id        = id,
                material  = material,
                name      = name,
                feet      = feet,
                mode      = mode,
                ripLength = ripLength,
                createdAt = obj.get("createdAt")?.asString.orEmpty(),
                createdBy = obj.get("createdBy")?.asString.orEmpty(),
                moldingId = moldingId,
                type      = type
            )
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.AdminBoardStockStoreTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Run the full unit test suite to check for regressions**

Run: `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks`
Expected: BUILD SUCCESSFUL, no failures (adding optional constructor params with defaults is backward compatible with every existing `AdminBoardStockItem(...)` call site)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/models/Models.kt app/src/main/java/com/kkc/sheettracker/data/AdminBoardStockStore.kt app/src/test/java/com/kkc/sheettracker/data/AdminBoardStockStoreTest.kt
git commit -m "feat(molding): parse moldingId/type from board_stock.json admin items"
```

---

### Task 2: Preview button + modal on Rip Cut List rows

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/standards/MoldingPreviewDialog.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`

- [ ] **Step 1: Write the dialog**

```kotlin
// app/src/main/java/com/kkc/sheettracker/ui/standards/MoldingPreviewDialog.kt
package com.kkc.sheettracker.ui.standards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.kkc.sheettracker.data.MoldingLibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MoldingPreviewDialog(
    category: String,
    fileId: String,
    name: String,
    repository: MoldingLibraryRepository,
    onDismiss: () -> Unit
) {
    var showMeasurements by remember { mutableStateOf(true) }
    var svgFile by remember(category, fileId) { mutableStateOf<File?>(null) }
    val imageLoader = rememberSvgImageLoader()

    LaunchedEffect(category, fileId, showMeasurements) {
        svgFile = withContext(Dispatchers.IO) {
            repository.profileSvgFile(category, fileId, showMeasurements)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                        Text(category, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                    Text("Measurements", modifier = Modifier.padding(end = 4.dp))
                    Switch(checked = showMeasurements, onCheckedChange = { showMeasurements = it })
                }
                Card(modifier = Modifier.fillMaxWidth().height(180.dp).padding(top = 12.dp)) {
                    AsyncImage(
                        model = svgFile,
                        contentDescription = name,
                        imageLoader = imageLoader,
                        modifier = Modifier.fillMaxWidth().height(180.dp)
                    )
                }
            }
        }
    }
}
```

Before treating this as final: confirm `rememberSvgImageLoader()` (extracted during the Molding detail work, lives in `ui/standards/SvgImageLoader.kt`) is `internal` or public within the same package — `MoldingPreviewDialog.kt` is in the same `ui.standards` package, so an `internal` visibility modifier is fine; if it's `private`, widen it to `internal` in `SvgImageLoader.kt` (check first, don't guess).

If `repository.profileSvgFile(...)` returns `null` (cache miss — profile not yet published, or pruned), `AsyncImage(model = null, ...)` renders blank — matching the same "not available yet" behavior already accepted on the Molding list/detail screens (per the design spec's error-handling section, no dedicated placeholder text needed here, consistent with existing behavior).

- [ ] **Step 2: Wire the repository + preview button into the Rip Cut List row**

In `HardwoodsWorkspaceScreen.kt`, find where `MoldingLibraryRepository` isn't yet imported — add:
```kotlin
import com.kkc.sheettracker.data.MoldingLibraryRepository
import com.kkc.sheettracker.ui.standards.MoldingPreviewDialog
```

Near the top of `HardwoodsWorkspaceScreen` (around where `scanState.snapshot.basePath` is first used, e.g. near line 281-285), add:
```kotlin
val moldingLibraryRepository = remember(scanState.snapshot.basePath) {
    MoldingLibraryRepository(File(scanState.snapshot.basePath))
}
```

Thread it into `HardwoodsBoardStockList`'s call site (find where `HardwoodsBoardStockList(...)` is invoked with `adminItems = ...`) by adding a new parameter `repository = moldingLibraryRepository`.

Add the new parameter to `HardwoodsBoardStockList`'s signature (currently at line 1965-1974):
```kotlin
private fun HardwoodsBoardStockList(
    sections: List<BoardStockSourceSection>,
    jobFolderName: String,
    progressStore: HardwoodsProgressStore,
    totalsDoneMap: Map<String, Int>,
    modifier: Modifier = Modifier,
    adminItems: List<AdminBoardStockItem> = emptyList(),
    hideSections: Boolean = false,
    sectionTitle: String = "Board Stock",
    repository: MoldingLibraryRepository? = null
) {
```
(`repository` is nullable/optional so this is backward compatible with any other call site that doesn't need molding previews.)

In the row composable (inside `items(groupItems, key = { "admin-item:${it.id}" }) { item -> ... }`, after the `Column(modifier = Modifier.weight(1f)) { ... }` block that renders `item.name`/`"Need N boards..."` — right before the `if (isNoneItem) { ... } else { ... }` branch that renders the NONE badge or tally controls, around line 2201-2202), add:

```kotlin
                                        if (item.moldingId != null && repository != null) {
                                            var showPreview by remember(item.id) { mutableStateOf(false) }
                                            androidx.compose.material3.IconButton(onClick = { showPreview = true }) {
                                                androidx.compose.material3.Icon(
                                                    androidx.compose.material.icons.Icons.Filled.Visibility,
                                                    contentDescription = "Preview ${item.name}"
                                                )
                                            }
                                            if (showPreview) {
                                                val (category, fileId) = item.moldingId.split(":", limit = 2)
                                                    .let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
                                                MoldingPreviewDialog(
                                                    category = category,
                                                    fileId = fileId,
                                                    name = item.name,
                                                    repository = repository,
                                                    onDismiss = { showPreview = false }
                                                )
                                            }
                                        }
```

Use fully-qualified `IconButton`/`Icon`/`Icons.Filled.Visibility` references as shown (or add proper imports at the top of the file if you prefer — check whether `IconButton`/`Icon` are already imported in this file first, since it's a large file that likely already imports Material3 basics; don't add duplicate imports).

- [ ] **Step 3: Build to verify it compiles**

Run: `.\gradlew.bat :app:compileDebugKotlin` (from `C:\Scripts\KKCSheetTracker`)
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run the full unit test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks`
Expected: BUILD SUCCESSFUL, no failures

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/standards/MoldingPreviewDialog.kt app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt
git commit -m "feat(molding): add preview button and modal to Rip Cut List rows"
```

(If Step 2's `rememberSvgImageLoader()` visibility needed widening, include `SvgImageLoader.kt` in this commit too.)

---

### Task 3: On-device verification

**Files:** none (manual verification pass)

- [ ] **Step 1: Build and install a release APK, launch the app**

Same process as the Molding library feature's Task 15 — `assembleRelease`, `adb install -r`, launch.

- [ ] **Step 2: Open a real job's Rip Cut List with admin board-stock rows that have a `moldingId`**

Confirm the preview `IconButton` appears next to rows with a linked profile (e.g. "3 1/4\" Flat", "KKC Base", "KKC Scribe" in job "314 - BURNHAM 2135 GREEN VIEW ST") and does NOT appear on rows without one.

- [ ] **Step 3: Tap the preview button**

Confirm the modal opens showing the correct profile drawing, name, and category. Toggle Measurements and confirm the drawing swaps between plain/dimensioned. Dismiss and confirm it closes cleanly (tap outside, or a close affordance if one was added).

- [ ] **Step 4: Confirm no regression to the rest of the Rip Cut List row** (tally +/-, SKIP, progress pill still work as before).

No commit for this task — verification only.

## Self-Review Notes

- **Spec coverage:** Task 1 covers the data-model extension, Task 2 covers the button + dialog, Task 3 covers on-device verification — matches the spec's full scope. Out-of-scope items (no backend changes, no job-usage list in the dialog, no fallback name-matching) are correctly absent from the plan.
- **Placeholder scan:** no TBD/TODO. The two "check first, don't guess" notes (image-loader visibility, existing imports) are legitimate verify-then-act instructions, not vague placeholders — both have a concrete fallback action given.
- **Type consistency:** `AdminBoardStockItem.moldingId`/`type` (Task 1) match the field names used in Task 2's `item.moldingId` checks. `MoldingPreviewDialog`'s signature matches its call site in Task 2 exactly.
