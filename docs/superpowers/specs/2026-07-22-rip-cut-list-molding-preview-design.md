# Rip Cut List Molding Preview

**Date:** 2026-07-22
**Status:** Approved design

## Goal

The Hardwoods Rip Cut List already shows admin-entered board-stock rows
(name, material, "Need N boards · N ft") sourced from each job's
`board_stock.json` — the same file the backend tags with `moldingId`
(e.g. `"Crown:151"`) and `type` (`crown`/`base`/`scribe`/...). A shop
worker looking at a row like "3 1/4\" Flat" currently has no way to see
what that profile actually looks like without leaving the screen and
hunting through Standards → Molding. Add a small preview button per row
that pops a modal showing the profile drawing and measurements, using the
molding-library plumbing already built (`2026-07-22-standards-molding-library-design.md`).

## Scope

### In scope

- Extend `AdminBoardStockItem` (`data/models/Models.kt:853`) with
  `moldingId: String?` and `type: String?`, read from the existing JSON
  fields in `AdminBoardStockStore.kt`'s parser (both already present in
  `board_stock.json`, just not parsed today).
- A preview `IconButton` on each Rip Cut List row (`HardwoodsWorkspaceScreen.kt`,
  the admin-item row composable around line 2118-2200), shown only when
  `item.moldingId != null` — rows without a linked profile show no button.
- `MoldingPreviewDialog(category, fileId, name, repository, onDismiss)` — a
  new, lightweight modal (not a full-screen navigation route): profile
  drawing, a measurements show/hide toggle, name/category label. No job-usage
  list — irrelevant in this context.
- `moldingId` is split directly into `category`/`fileId` at the call site
  (`"Crown:151"` → `"Crown"`, `"151"`) — no `fetchLibrary()` call needed
  since the row already carries the profile's name.

### Out of scope

- No backend changes. The cache-publish pipeline, `moldings_cache/` tree,
  and `MoldingLibraryRepository` are unchanged — this reuses them as-is.
- No editing. Same read-only posture as the rest of the molding feature.
- No job-usage list in the preview dialog — that's the Standards → Molding
  detail screen's job, not a quick shop-floor reference popup.
- Rows whose `board_stock.json` entry has no `moldingId` (free-text
  material rows, or older data predating the field) simply get no preview
  button — no fallback name-matching against the library.

## Architecture and data flow

`AdminBoardStockStore.kt`'s `loadAdminBoardStock()` already parses each
`board_stock.json` item into an `AdminBoardStockItem`. Two more fields:

```kotlin
val type = obj.get("type")?.asString?.trim()?.takeIf { it.isNotBlank() }
val moldingId = obj.get("moldingId")?.asString?.trim()?.takeIf { it.isNotBlank() }
```

added to both the parse call and the `AdminBoardStockItem` data class.

In the row composable, next to the existing name/feet `Text`s, a small
`IconButton` (shown only when `item.moldingId != null`) opens
`MoldingPreviewDialog`. The dialog itself:

```kotlin
@Composable
fun MoldingPreviewDialog(
    category: String,
    fileId: String,
    name: String,
    repository: MoldingLibraryRepository,
    onDismiss: () -> Unit
)
```

constructed at the call site by splitting `item.moldingId!!.split(":", limit = 2)`
into `category`/`fileId`, with `repository` built the same way every other
Standards screen builds it: `remember(basePath) { MoldingLibraryRepository(File(basePath)) }`.
Internally it mirrors `MoldingDetailScreen`'s image-loading pattern —
`LaunchedEffect` + `Dispatchers.IO` + the shared `rememberSvgImageLoader()`
helper (already extracted during the Molding detail work) — just inside a
`Dialog`/`AlertDialog` instead of a full navigation route, with a
measurements `Switch` and no job-usage section.

## Error handling

- No `moldingId` on a row → no button, nothing to fail.
- `moldingId` present but the cache has no matching SVG (orphaned/pruned
  profile) → dialog shows the same "not available yet" placeholder state
  the Molding list/detail screens already use for cache misses.
- Malformed `moldingId` (no colon) → treated as absent; no button shown
  (defensive parse, not expected in practice since the backend always
  writes `"<Category>:<fileId>"`).

## Testing

- `AdminBoardStockStore`/`AdminBoardStockItem`: extend existing test
  coverage (if any exists for this parser — check `app/src/test/` for a
  matching test file) to cover `moldingId`/`type` parsing, including the
  case where they're absent (older data).
- Dialog itself: no automated test (Compose UI, no extractable logic beyond
  the category/fileId split, which is trivial inline logic) — verified
  manually on-device.
