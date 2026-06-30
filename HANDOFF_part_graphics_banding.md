# Handoff: Part graphics + edge-banding indicator in the CNC nest-parts view

## Context

KKCSheetTracker is an Android (Kotlin/Jetpack Compose) app used by CNC operators. It reads `.metadata/{material}.json` files (per job, per material, in a job's `CNC` subfolder) produced by `split_pdfs_gui_v2.py` (soon `v3` — see below), which parses Cabinet Vision nest cut-list PDFs. Each JSON has `pages[].parts[]` — individual cut parts on a sheet — rendered in a table in the sheet viewer.

A companion effort is updating the PDF pipeline (`split_pdfs_gui_v3.py`, see `C:\Scripts\PGM_Sorting\HANDOFF_splitter_v3.md` if you need that context) to additionally extract, per part:
- **`graphicPath`** — a relative path to a small (~10-20KB) JPEG image of that specific part, written to a new `.metadata/parts/` folder (sibling to the existing `.metadata/.thumbs/` sheet-thumbnail folder). Full resolution, losslessly extracted from the source PDF. Not every job will have this yet — old jobs and any job processed before the v3 cutover will have `null`/missing `graphicPath`.
- **`banding`** — an edge-banding code string (e.g. `2WD2LD`), or `null`/empty if the part has no banding (most parts won't — it's specific to edge-banded fronts/doors, not hidden box parts).

This task is the **app-side** work: surface both fields in the CNC nest-parts view. It does not touch the hardwoods/Face-Frame/Door table (`ClassicCutListTable.kt`) — that's a separate, unrelated view in this same app.

## Current state (verified this session, exact locations)

- **Part data model:** `app/src/main/java/com/kkc/sheettracker/data/models/Models.kt:76-84`
  ```kotlin
  data class Part(
      val number: Int = 0,
      val width: Double = 0.0,
      val length: Double = 0.0,
      val name: String = "",
      val cabNumber: Int = 0,
      val room: String = "",
      val rotated: Boolean = false
  )
  ```
- **JSON parsing:** `app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt:860` (Gson `fromJson`) and a sanitization/defaulting layer at lines 1490-1552 that explicitly maps each `Part` field with `?:` defaults. Gson already ignores unknown JSON keys and Kotlin data class defaults handle missing keys — this is safe for additive fields with **zero migration risk** in either direction (old app + new JSON, new app + old JSON).
- **Row rendering:** `PartsTable()` composable, `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt:3165-3315`. `LazyColumn` + `itemsIndexed`. Each row: rotation marker (`*`, 20dp) → `#` → width → length → name (flex weight) → cabinet → room → 40dp warning-flag action button. Column widths are user-resizable and persisted to `SharedPreferences` (lines 281-284, drag logic ~1523-1528).
- **Tap/long-press handlers:** `SheetViewerScreen.kt:1530-1557`.
  - Single tap (line ~1530): toggles row selection highlight only.
  - **Long-press (line 1540-1548) already opens an `AlertDialog`** ("Open Reference Sheet", lines 1586-1659) offering Assembly Sheets / Plans & Elevations / 3D Room view buttons — **this is the dialog you're extending, not replacing.**
- **Coil** is already a dependency (`coil-compose:2.7.0`, `app/build.gradle.kts:92`) and already used elsewhere (e.g. `ui/supply/SupplyItemDetailScreen.kt:36`, `AsyncImage` + `ImageRequest`) — no new imports/dependencies needed.
- **Modal pattern precedent:** `ui/components/HardwoodsRevisionHistorySheet.kt` uses `ModalBottomSheet` — reference if you need a bottom-sheet pattern elsewhere, but for this task you're extending the **existing `AlertDialog`**, not introducing a new modal type.

## Design (agreed, implement as specified — do not re-derive)

1. **Data model:** add two nullable fields to `Part`:
   ```kotlin
   val graphicPath: String? = null,   // relative path into .metadata/parts/
   val banding: String? = null,       // banding code; null/blank = no banding
   ```
   Update the sanitization layer (`FileBackedUnifiedMetadataEngine.kt` ~line 1512-1520) to map these two new fields the same defensive way the existing fields are mapped (`?:` to null/default, do not throw if absent).

2. **Row-level banding indicator — exact spec:**
   - **Replace the existing rotation marker character.** The rotation cell currently shows `"*"` when `part.rotated == true` — change this glyph to **`🗘`** (same condition, same cell, just a new character).
   - **Stack the banding glyph below it, in the same cell.** When `part.banding != null` and non-blank, show **`𖦹`** directly underneath the rotation glyph, vertically stacked within the existing rotation-marker cell (currently 20dp wide, single line — change this cell from a single `Text` to a small vertical `Column` holding up to two lines: rotation glyph on top, banding glyph below).
   - **Nudge the rotation glyph up slightly** (a small negative/reduced top padding or top-alignment within the cell) so both glyphs fit comfortably without the cell growing tall enough to throw off row height/alignment with the rest of the row.
   - Each glyph is shown **independently and conditionally** — a part can have neither, either, or both: rotated-only shows just `🗘`, banding-only shows just `𖦹` (with no empty rotation slot above it — don't reserve dead space when not rotated), both-shows both stacked, neither shows nothing (the cell collapses back to empty, exactly like today when a part isn't rotated).
   - No new column, no width renegotiation — both glyphs live inside the existing rotation-marker cell's footprint, just made slightly taller via the vertical stack when both are present.

3. **Long-press dialog gets a new top section, existing buttons stay below it.** In the existing `AlertDialog` (`SheetViewerScreen.kt:1586`), before the Assembly/Plans/3D buttons, add:
   - The part graphic, if `graphicPath != null`: `AsyncImage` loading from the resolved local file path (same path-resolution pattern already used for `thumbnailPath` elsewhere in this file — find and reuse it, don't invent a new one). Reasonable size for a dialog (e.g. fits within ~200-300dp, maintain aspect ratio).
   - The banding code as text, if `banding != null` and non-blank (e.g. "Banding: 2WD2LD").
   - If both are null/absent (older job, or part with no banding), **omit that whole section entirely** — don't show an empty placeholder or "no graphic available" message. The dialog should look exactly as it does today for such parts.
   - The dialog title/existing reference-doc logic does not change.

4. **Data flow:** images arrive via the same Syncthing-based file sync that already delivers `thumbnailPath` images today — `.metadata/parts/` is a new folder but uses the identical sync mechanism (verify the Syncthing folder scope/ignore-patterns aren't restricted to specifically exclude new subfolders under `.metadata/`; if there's an explicit allowlist rather than a recursive sync of the whole `.metadata` tree, you'll need to add `.metadata/parts/` to it — check whatever config governs this, likely near wherever `.metadata/.thumbs/` sync is configured).

## Explicitly out of scope for this task

- The hardwoods/Face-Frame/Door table (`ClassicCutListTable.kt`) — unrelated view, not touched.
- A dedicated sortable/filterable banding *column* in the table — the user has called that a separate future slice; this task is the row glyph + dialog only.
- Any change to the PDF pipeline itself (that's `split_pdfs_gui_v3.py`, a separate handoff).
- Editing/annotating banding from the app — display only.

## Verification

1. With a job whose JSON has `graphicPath`/`banding` populated on some parts (test data will be available once `split_pdfs_gui_v3.py` has processed a job — coordinate with that work, or hand-craft a test JSON with these fields if you need to unblock sooner): confirm `🗘` shows only when rotated, `𖦹` shows only when banded, both stack correctly when a part is both rotated and banded, and the long-press dialog shows the graphic + banding text above the existing reference buttons.
2. With an **older** job JSON (no `graphicPath`/`banding` keys at all): confirm the app doesn't crash, rotation still shows `🗘` exactly as before (just the new glyph), no banding glyph ever appears, and the long-press dialog looks identical to its current (pre-this-change) behavior aside from the new glyph character.
3. Confirm column widths/layout are unaffected by the new glyphs, including when both are stacked (row height shouldn't visibly jump compared to neighboring rows).
4. Confirm Coil correctly resolves and caches the local file path the same way it already does for sheet thumbnails (no need to reinvent caching — verify it's using the same pattern, not adding a second one).

## Done

### 2026-06-30 - App-side part graphics and banding display

- Completed the app-side model, metadata parsing, CNC parts-table glyph, and long-press dialog work for `graphicPath` and `banding`.
- Key decisions + rationale:
  - Reused a shared `resolveCncSidecarFile()` helper for both sheet thumbnails and part graphics so `.metadata/parts/` paths resolve exactly like existing `.metadata/.thumbs/` paths.
  - Kept rotation and banding in the existing fixed marker cell; `partMarkerGlyphs()` returns independent glyphs in display order (`🗘`, then `𖦹`) and `PartMarkers` uses tight 20-22dp marker heights to avoid visibly changing row rhythm.
  - Left the existing reference-document `AlertDialog` intact and inserted an optional detail block above the Assembly/Plans/3D buttons only when a graphic path or nonblank banding code is present.
  - Checked app-side Syncthing code; it only starts/checks the external Syncthing app and stores the API key. No app-side folder allowlist or `.metadata/.thumbs/` ignore rule was found, so no `.metadata/parts/` sync-code change was needed.
- Files changed:
  - `app/src/main/java/com/kkc/sheettracker/data/models/Models.kt`
  - `app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt`
  - `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`
  - `app/src/test/java/com/kkc/sheettracker/data/unified/UnifiedMetadataEngineTest.kt`
  - `app/src/test/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreenTest.kt`
  - `HANDOFF_part_graphics_banding.md`
- Verification:
  - `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.unified.UnifiedMetadataEngineTest" --tests "com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest"` passed.
  - `.\gradlew.bat assembleDebug` passed.
- Learnings for future iterations:
  - The app does not own external Syncthing folder inclusion/exclusion rules; if a tablet ever lacks `.metadata/parts/` files, inspect the Syncthing folder config outside this repo.

### 2026-06-30 - Completion audit after handoff resume

- Re-verified the current worktree against the original app-side objective and found the requested state present: nullable part fields, sanitized metadata preservation, fixed-cell rotation/banding glyphs, optional long-press dialog part detail, shared sidecar path resolution, and no app-side Syncthing allowlist requiring `.metadata/parts/`.
- Key decisions + rationale:
  - No code changes were needed during this resume because the authoritative current state already matched the requested scope.
  - Kept the prior implementation shape: additive nullable JSON fields for compatibility, no new banding column, and no changes to the hardwoods/classic cut-list UI or PDF splitter.
- Files changed:
  - `HANDOFF_part_graphics_banding.md`
- Verification:
  - `.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.unified.UnifiedMetadataEngineTest" --tests "com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest"` passed with `BUILD SUCCESSFUL`.
  - `.\gradlew.bat assembleDebug` passed with `BUILD SUCCESSFUL`.
- Learnings for future iterations:
  - The implementation is already committed in `b886ac9 feat: show CNC part graphics and banding`; this resume added only the fresh completion audit.
