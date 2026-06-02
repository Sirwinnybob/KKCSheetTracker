# Specialty Tablet Custom Items — Design Spec

**Date:** 2026-06-02

## Problem

Specialty custom items can only be created through the admin web page. Shop staff at the tablet (especially SAW) need to add their own items on the fly — additional rips, custom pieces, TO_ORDER notes — without involving the admin. Those tablet-created items must also be visible on the admin page so office staff have a complete picture of what's been added.

## Goals

1. Tablet users can create, edit, and delete specialty items directly from `SpecialtyJobDetailScreen`.
2. All fields available on the admin form are available on the tablet (no attachments).
3. Tablet-created items appear on the admin page with a "Tablet" badge.
4. Admin can delete tablet-created items.
5. No Syncthing conflicts — ever.

## Out of Scope

- File attachments from tablet (can be added via admin page later)
- Admin editing tablet items inline (delete only from admin side for now)
- Tablet editing items created by *other* tablets (read-only)

---

## Storage

### File per device

Each tablet writes its items to:
```
{jobFolder}/.metadata/admin/tablet_items_{deviceId}.json
```

This mirrors the existing per-device `.tracker/{deviceId}.json` pattern and eliminates all Syncthing write conflicts — each device owns exactly one file.

### Schema (array of `TabletSpecialtyItem`)

```json
[
  {
    "id": "uuid-v4",
    "name": "Oak Shelf 36\"",
    "category": "CUSTOM",
    "cabinetNumbers": [12, 13],
    "stations": ["SAW", "ASSEMBLY"],
    "dimensions": "36x12x0.75",
    "quantity": 3,
    "material": "Red Oak",
    "supplier": null,
    "modelNumber": null,
    "orderDate": null,
    "trackingNumber": null,
    "orderUrl": null,
    "notes": "Match grain direction on 12",
    "createdAt": "2026-06-02T14:30:00Z",
    "createdByDevice": "tablet-saw-01"
  }
]
```

Fields mirror `SpecialtyItem` exactly, minus `attachments`. `category` is `"CUSTOM"` or `"TO_ORDER"`. All non-required fields are nullable.

### Completion tracking

Tablet-created items use the **same** `.tracker/{deviceId}.json` completion mechanism as admin items. The item's `id` is the completion key. No new infrastructure needed.

---

## Android (Tablet)

### New file: `AddSpecialtyItemSheet.kt`

A `ModalBottomSheet` composable with a scrollable form. Keeps `SpecialtyJobDetailScreen.kt` from growing.

**Form layout:**

1. **Category** — Segmented button: `CUSTOM` | `TO_ORDER`
2. **Common fields** (always shown):
   - Name (required, `OutlinedTextField`)
   - Cabinet Numbers (chip-style multi-value input)
   - Stations (multi-select checkboxes: CNC, SAW, EDGE_BANDER, ASSEMBLY, HARDWOODS, SPECIALTY)
   - Notes (`OutlinedTextField`, multiline)
3. **CUSTOM-only fields** (visible when CUSTOM selected):
   - Dimensions (`OutlinedTextField`)
   - Quantity (`OutlinedTextField`, numeric)
   - Material (`OutlinedTextField`)
4. **TO_ORDER-only fields** (visible when TO_ORDER selected):
   - Supplier (`OutlinedTextField`)
   - Model Number (`OutlinedTextField`)
   - Order Date (date picker)
   - Tracking Number (`OutlinedTextField`)
   - Order URL (`OutlinedTextField`)
5. **Save** button (disabled until Name is non-empty) / **Cancel**

Sheet is reused for both create (empty form) and edit (pre-filled).

### New file: `TabletSpecialtyItemsStore.kt`

Handles all file I/O for tablet items. Responsibilities:

- `loadItems(jobFolderPath: String, deviceId: String): List<TabletSpecialtyItem>` — reads own device's file (returns empty list if not yet created)
- `loadAllItems(jobFolderPath: String): List<TabletSpecialtyItem>` — globs `tablet_items_*.json`, merges all into one list (for display)
- `saveItem(jobFolderPath: String, deviceId: String, item: TabletSpecialtyItem)` — read-modify-write own file atomically (add or replace by id)
- `deleteItem(jobFolderPath: String, deviceId: String, id: String)` — remove from own file

Atomic write: write to `.tmp` file, then rename, same pattern as existing stores.

### `SpecialtyJobDetailScreen.kt` changes

- Adds a **FAB ("+" icon)** at bottom-right of the screen.
- On load, calls `TabletSpecialtyItemsStore.loadAllItems()` alongside existing admin item load. Items are merged into one list for display.
- Admin items and own-device tablet items render identically as `ProgressCard` rows.
- Items created by **this device**: show a small pencil icon in the card header. Tapping opens `AddSpecialtyItemSheet` pre-filled. Long-press (or swipe) shows a delete confirmation dialog.
- Items created by **other tablets**: no edit/delete affordance, same appearance as admin items.
- FAB taps open `AddSpecialtyItemSheet` with an empty form.

### Model: `TabletSpecialtyItem.kt`

Data class in the `data` package matching the JSON schema above.

---

## Admin Server (Node.js / TypeScript)

### New file: `tabletSpecialtyItemsStore.ts`

```typescript
// Reads all tablet_items_*.json files for a job and returns merged items
// tagged with source + sourceFile for write-back routing.
function loadTabletItems(jobFolderPath: string): TabletSpecialtyItemWithMeta[]

// Deletes an item by id from its source file
function deleteTabletItem(sourceFile: string, id: string): void
```

Uses `glob` (already a project dependency) to discover files. Each returned item carries an internal `_sourceFile: string` field (stripped before sending to client).

### `specialtyStore.ts` — no changes to existing logic

The admin specialty store continues to own `specialty_items.json` exclusively. No merging logic added here.

### Route changes (`specialty.ts`)

**GET `/api/specialty/:jobFolderName`**

After loading admin items, also calls `loadTabletItems()`. Merges both arrays. Tablet items include `source: "tablet"` and `createdByDevice` in the response. Admin items have no `source` field (or `source: "admin"`).

**DELETE `/api/specialty/:jobFolderName/:id`**

Check whether the id belongs to an admin item or a tablet item. If tablet item, call `deleteTabletItem(sourceFile, id)`. If admin item, existing logic unchanged.

No PATCH route for tablet items from admin — admin can delete, tablet owners can edit.

### Admin UI (`SpecialtyTab.tsx`)

- Tablet items appear in the same table as admin items.
- A small **"Tablet"** chip badge (grey, beside the category badge) on tablet-sourced rows.
- Tablet item rows are **read-only** (no inline edit cells) since the tablet owns those files. All other columns render as text.
- Delete button works normally (calls the same DELETE endpoint).

---

## Data Flow

```
Tablet A creates item
  → writes to .metadata/admin/tablet_items_tablet-a.json
  → Syncthing propagates to all other devices + server

Tablet B opens same job
  → TabletSpecialtyItemsStore.loadAllItems() globs tablet_items_*.json
  → shows Tablet A's item (read-only, no edit affordance)

Admin page loads job
  → GET /api/specialty/:job merges specialty_items.json + tablet_items_*.json
  → Tablet A's item shown with "Tablet" badge

Admin deletes Tablet A's item
  → DELETE /api/specialty/:job/:id routes to tablet_items_tablet-a.json
  → Syncthing propagates deletion back to Tablet A
```

---

## Verification

1. **Create on tablet** — Open a specialty job, tap FAB, fill Name + CUSTOM fields, save. Item appears in the job list immediately. File `tablet_items_{deviceId}.json` exists in the job's `.metadata/admin/` directory.

2. **Other tablet sees it** — After Syncthing sync, open the same job on a different tablet. The item appears without an edit pencil (read-only).

3. **Admin sees it** — Reload the admin specialty tab for the job. Item appears with "Tablet" badge. No inline edit fields. Delete button present.

4. **Admin deletes it** — Click delete on admin page. Item disappears. After Syncthing sync, item is gone on tablet too.

5. **Edit on owning tablet** — Tap pencil icon on own item. Form opens pre-filled. Change a field, save. Item updates in the list, file updated on disk.

6. **TO_ORDER fields** — Switch category to TO_ORDER. CUSTOM fields hide, TO_ORDER fields appear. Save. Reload — correct fields persisted.

7. **No conflict** — With two tablets disconnected from Syncthing, each adds a different item. Reconnect. Both tablets and admin show both items, each in their respective device file.
