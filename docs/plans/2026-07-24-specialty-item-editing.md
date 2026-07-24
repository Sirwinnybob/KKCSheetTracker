# Specialty Item Universal Editing Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Enable tablet users to edit any specialty item in a job (admin-synced and tablet-created) with full Hours Tracker data field parity and a redesigned section card modal UI.

**Architecture:** Merges master `specialty_items.json` with per-tablet sidecar files (`tablet_items_<tabletId>.json`) in `.metadata/admin/` to store device-scoped edits without Syncthing file conflicts.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Gson.

---

### Task 1: Add `deleted` property to `TabletSpecialtyItem`

**Files:**
- Modify: `c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/models/TabletSpecialtyItem.kt`

**Step 1: Update `TabletSpecialtyItem.kt` data class**
Add optional `val deleted: Boolean = false` parameter to `TabletSpecialtyItem`.

**Step 2: Verify code compiles**
Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**
Run: `git add app/src/main/java/com/kkc/sheettracker/data/models/TabletSpecialtyItem.kt ; git commit -m "feat: add deleted flag to TabletSpecialtyItem"`

---

### Task 2: Update `TabletSpecialtyItemsStore` for `deleted` flag support

**Files:**
- Modify: `c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/TabletSpecialtyItemsStore.kt`

**Step 1: Update `parseItem` and `writeItems`**
- In `parseItem(obj: JsonObject)`: Read `val deleted = runCatching { obj.get("deleted")?.asBoolean }.getOrDefault(false)` and set `deleted = deleted`.
- In `writeItems`: If `item.deleted` is true, write `addProperty("deleted", true)`.
- In `loadAllItems`: Include items with `deleted = true` in raw list so `SpecialtyStateStore` can apply tombstones, but provide `loadActiveItems` filtering out `deleted == true`.

**Step 2: Add `deleteItemTombstone` helper**
Write `deleteItemTombstone(jobFolderName: String, itemId: String)` to write a `{ id: itemId, deleted: true }` record into the device's `tablet_items_<tabletId>.json` sidecar.

**Step 3: Verify code compiles**
Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**
Run: `git add app/src/main/java/com/kkc/sheettracker/data/TabletSpecialtyItemsStore.kt ; git commit -m "feat: handle deleted tombstones in TabletSpecialtyItemsStore"`

---

### Task 3: Update `SpecialtyStateStore` for universal editing & sidecar merge

**Files:**
- Modify: `c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/SpecialtyStateStore.kt`

**Step 1: Merge admin items with tablet sidecar overrides**
In `loadSpecialtyItems(jobFolderName: String)`:
- Load admin items from `specialty_items.json`.
- Load all tablet items from `tablet_items_*.json` via `tabletItemsStore.loadAllItems(jobFolderName)`.
- Map each admin item to `SpecialtyItem`. If a tablet item with matching `id` exists:
  - If `tabletItem.deleted == true`, mark item deleted / omit from list.
  - Otherwise, override fields (`dimensions`, `quantity`, `material`, `supplier`, `modelNumber`, `orderDate`, `trackingNumber`, `orderUrl`, `notes`, `stations`, `category`) with tablet item values.

**Step 2: Implement universal `saveSpecialtyItem` & `deleteSpecialtyItem`**
- `saveSpecialtyItem(jobFolderName, tabletItem)`: Writes the updated item to `tabletItemsStore.saveItem(jobFolderName, tabletItem)`.
- `deleteSpecialtyItem(jobFolderName, itemId)`: Calls `tabletItemsStore.deleteItemTombstone(jobFolderName, itemId)`.

**Step 3: Verify code compiles**
Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**
Run: `git add app/src/main/java/com/kkc/sheettracker/data/SpecialtyStateStore.kt ; git commit -m "feat: support universal specialty item edits and tombstones"`

---

### Task 4: Rebuild `AddSpecialtyItemSheet` UI

**Files:**
- Modify: `c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/specialty/AddSpecialtyItemSheet.kt`

**Step 1: Update signature & state initialization**
- Accept `existingItem: com.kkc.sheettracker.data.models.SpecialtyItem? = null` (or `TabletSpecialtyItem?`).
- Pre-populate all fields from `existingItem`.
- Add `onDelete: ((String) -> Unit)? = null` callback parameter.

**Step 2: Build modern grouped card layout**
- Wrap inputs in `Surface` card containers with subtle rounded borders (`MaterialTheme.shapes.medium` and `colorScheme.surfaceVariant`).
- **Basic Info Card**: Item Name `OutlinedTextField`, Category segmented buttons (`CUSTOM` vs `TO ORDER`), Cabinet Numbers `OutlinedTextField`.
- **Station Routing Card**: 7 pill/chip station toggles (`FilterChip` or custom styled chips for Saw, Edge Bander, Assembly, CNC, Hardwoods, Specialty, Delivery).
- **Specifications Card** (*Custom*): Dimensions, Quantity, Material.
- **Order & Supply Card** (*To Order*): Supplier, Model Number, Order Date, Tracking Number, Order URL.
- **Notes Card**: Multiline `OutlinedTextField`.
- **Sticky Action Row**: Primary filled "Save Item" button, "Cancel" button, and red "Delete Item" outlined button (when editing existing item).

**Step 3: Verify code compiles**
Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**
Run: `git add app/src/main/java/com/kkc/sheettracker/ui/specialty/AddSpecialtyItemSheet.kt ; git commit -m "feat: overhaul AddSpecialtyItemSheet with section cards and full field support"`

---

### Task 5: Enable universal Edit/Delete in `SpecialtyJobDetailScreen`

**Files:**
- Modify: `c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt`

**Step 1: Show Edit & Delete buttons on all cards**
- In `CompactSpecialtyProgressCard` or `SpecialtyChecklistRow`: Display Edit (pencil) and Delete (trash) icon buttons on EVERY specialty card regardless of whether `isMyTabletItem` is true.
- Clicking Edit sets `editingItem = item` and opens `AddSpecialtyItemSheet`.

**Step 2: Connect save & delete callbacks**
- On save in `AddSpecialtyItemSheet`: Call `specialtyStateStore.saveSpecialtyItem(jobFolderName, updatedItem)` and refresh UI.
- On delete in `AddSpecialtyItemSheet`: Call `specialtyStateStore.deleteSpecialtyItem(jobFolderName, itemId)` and refresh UI.

**Step 3: Verify code compiles**
Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**
Run: `git add app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt ; git commit -m "feat: enable universal item editing and deletion in SpecialtyJobDetailScreen"`

---

### Task 6: Build, Deploy & ADB Verification

**Files:**
- Target APK: `app/build/outputs/apk/debug/app-debug.apk`

**Step 1: Build debug APK**
Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 2: Install on tablet**
Run: `adb install -r -d app\build\outputs\apk\debug\app-debug.apk`
Expected: Success

**Step 3: Capture screenshot & verify UI**
Run: `adb shell screencap -p /sdcard/screenshot.png ; adb pull /sdcard/screenshot.png C:\Users\chadc\.gemini\antigravity\brain\73993645-715f-4e7a-a5de-87f1aedb6c09\specialty_editing_verified.png`
Verify progress bar rendering and new modal design on tablet screenshot.
