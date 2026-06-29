# PDF Markup Auto-Calibration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Surface the per-page drawing scale that Ready Jobs Watcher will detect automatically from PDF vector geometry (see companion plan `2026-06-27-vector-scale-detection.md` in the Ready Jobs Watcher repo) into KKCSheetTracker's data layer, and display it read-only in the PDF markup/viewer screens — laying the groundwork for an on-device measuring tool without requiring any manual calibration step today.

**Current state (confirmed by reading the code, not assumed):** `PdfMarkupStore.kt` and its models (`PdfInkStroke`, `PdfPageMarkup`, `PdfTabletMarkup` in `Models.kt:450-468`) have **zero** scale or calibration concept — ink strokes are stored as raw point lists with no PDF-point mapping at all. There is currently no way for the app to know "how big is this drawing in real units" anywhere in the codebase.

**Architecture:** `cabinet_sheet_index.json` (written by Ready Jobs Watcher, read here via `FileBackedUnifiedMetadataEngine.kt`) already carries per-page metadata in `CabinetPageDetail` (`Models.kt:120`), keyed by page number, for both `documents.assembly` and `documents.plansElevations`. The companion Ready Jobs Watcher plan adds a `scale` field to that same per-page dict. This plan only needs to: (1) add a matching nullable field to the `CabinetPageDetail` Kotlin model, (2) thread it through the existing sanitize/passthrough functions in `FileBackedUnifiedMetadataEngine.kt`, and (3) read it from wherever Plans & Elevations / Assembly pages are currently displayed (`AssemblyViewerScreen.kt` and whatever screen renders the PDF markup overlay) to show a small "Scale: ..." readout. No new measuring UI is built in this plan — that's a separate, larger feature this merely unblocks.

**Tech Stack:** Kotlin, Gson, Jetpack Compose UI.

---

### Task 1: Add the `scale` field to the data model

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/models/Models.kt`

**Step 1: Write the failing test / verify compilation**

Add a small unit test (or extend an existing model test file if one exists for `Models.kt`) asserting the new field parses from a JSON fixture matching Ready Jobs Watcher's schema:

```kotlin
@Test
fun `CabinetPageDetail parses scale field when present`() {
    val json = """{"cabinets":["1"],"room":"Room #1 (KITCHEN)","wall":"Wall #1",
        "scale":{"pdfPointsPerInch":1.8730,"agreeing":12,"total":16}}"""
    val detail = Gson().fromJson(json, CabinetPageDetail::class.java)
    assertEquals(1.8730, detail.scale?.pdfPointsPerInch)
}

@Test
fun `CabinetPageDetail tolerates missing scale field`() {
    val json = """{"cabinets":["1"],"room":null,"wall":null}"""
    val detail = Gson().fromJson(json, CabinetPageDetail::class.java)
    assertNull(detail.scale)
}
```

Run: `.\gradlew.bat testDebugUnitTest --tests "*CabinetPageDetail*"`
Expected: FAIL to compile (`scale` is not a member of `CabinetPageDetail`).

**Step 2: Write minimal implementation**

In `Models.kt`, add a new data class near `CabinetPageDetail` (`Models.kt:120`):

```kotlin
data class PdfPageScale(
    val pdfPointsPerInch: Double? = null,
    val agreeing: Int = 0,
    val total: Int = 0
)
```

Add the field to `CabinetPageDetail`:

```kotlin
data class CabinetPageDetail(
    val cabinets: List<String> = emptyList(),
    val room: String? = null,
    val wall: String? = null,
    val parts: List<AssemblySheetPart> = emptyList(),
    val sourceVariant: String? = null,
    val sourcePdfFilename: String? = null,
    val sourcePage: Int? = null,
    val scale: PdfPageScale? = null
)
```

Gson defaults missing/null JSON fields to the Kotlin default (`null`) automatically — no extra null-handling needed, matching how `room`/`wall` already behave.

**Step 3: Run test to verify it passes**
Run: `.\gradlew.bat testDebugUnitTest --tests "*CabinetPageDetail*"`
Expected: PASS

**Step 4: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/data/models/Models.kt
git commit -m "feat: add PdfPageScale field to CabinetPageDetail model"
```

---

### Task 2: Thread the field through the sanitize pipeline

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt`

**Step 1: Write the failing test**

Find (or add) the existing test coverage for `sanitizeCabinetPageDetail` (search the test source tree for `sanitizeCabinetSheetIndex` or similar — the production function lives at `FileBackedUnifiedMetadataEngine.kt:1239` per the current file). Add a case asserting a `scale` value survives sanitization unchanged, and that a malformed/negative `pdfPointsPerInch` is dropped to `null` rather than trusted blindly — Ready Jobs Watcher is a separate process writing this file; defend against partially-written or stale JSON the same way the rest of this sanitize pipeline already does for other fields.

**Step 2: Run test to verify it fails**
Run: `.\gradlew.bat testDebugUnitTest --tests "*FileBackedUnifiedMetadataEngine*"`

**Step 3: Write minimal implementation**

Locate `sanitizeCabinetPageDetail` (referenced from `FileBackedUnifiedMetadataEngine.kt:1261`, `:1287`, `:1309` — find its actual definition near those call sites) and pass `scale` through, with a guard:

```kotlin
scale = detail.scale?.takeIf { (it.pdfPointsPerInch ?: 0.0) > 0.0 }
```

**Step 4: Run test to verify it passes**
Run: `.\gradlew.bat testDebugUnitTest --tests "*FileBackedUnifiedMetadataEngine*"`
Expected: PASS

**Step 5: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt
git commit -m "feat: pass scale through cabinet page detail sanitization"
```

---

### Task 3: Surface a read-only scale readout in the viewer

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt` (and the Plans & Elevations equivalent screen, if it's a separate file — confirm by checking what renders `documents.plansElevations` pages; `AssemblyViewerScreen.kt` was confirmed to reference `cabinet_sheet_index` directly, but plans-elevations sheets are the higher-confidence source per the companion Ready Jobs Watcher plan, so that's the more important screen to cover first if they differ)

**Step 1: Write the failing test**

Add a Compose UI test (or whatever pattern this screen's existing tests use — check for a `*ViewerScreenTest.kt` sibling first) asserting that when the current page's `CabinetPageDetail.scale` is non-null, a text node containing the scale ratio is displayed; when null, no such node exists and nothing else breaks.

**Step 2: Run test to verify it fails**
Run: `.\gradlew.bat testDebugUnitTest --tests "*AssemblyViewerScreen*"` (adjust to whatever the actual test class ends up being named)

**Step 3: Write minimal implementation**

Add a small composable, e.g.:

```kotlin
detail.scale?.pdfPointsPerInch?.let { ptPerInch ->
    Text(
        text = "Scale: ${"%.4f".format(ptPerInch)} pt/in (auto-detected, ${detail.scale.agreeing}/${detail.scale.total})",
        style = MaterialTheme.typography.labelSmall
    )
}
```

Placement: wherever the existing room/wall header text is rendered for the current page — keep it visually subordinate (label-small, not a primary UI element) since this is informational only in this plan, not yet a calibrated measuring tool. Convert `pdfPointsPerInch` to a human-readable architectural ratio (e.g. `1/4" = 1'-0"`) only if a quick nearest-standard-preset lookup is trivial to add here; otherwise leave the raw ratio — don't over-build the display in this plan.

**Step 4: Run test to verify it passes**
Run: `.\gradlew.bat testDebugUnitTest --tests "*AssemblyViewerScreen*"`
Expected: PASS

**Step 5: Manual verification**
Run the app against a job with a known-good detection result from the companion plan (e.g. Harshbarger, expected ~1.8730 pt/in on Plans & Elevations page 1) and confirm the readout matches.

**Step 6: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt
git commit -m "feat: display auto-detected page scale in PDF viewer"
```

---

## Explicitly out of scope for this plan

- Any actual on-device measuring/tap-to-measure tool — this plan only makes the scale value available and visible; building a measuring UI on top of `PdfInkStroke`'s raw point lists is a separate, larger feature.
- Manual calibration fallback UI for pages where `scale` is null — today's behavior (no calibration at all) is unchanged for those pages; this plan is additive only.
- Trusting assembly-sheet `scale` values as strongly as plans-elevations ones — per the companion plan's empirical coverage numbers (20%-82% of assembly pages yield a result, often from only 1 unconfirmed candidate), prefer `documents.plansElevations` scale for the same cabinet when both exist, once that comparison is needed by a future feature.
