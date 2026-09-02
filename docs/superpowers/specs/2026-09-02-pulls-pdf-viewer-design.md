# Pulls PDF Viewer — Design

## Summary

Add "Pulls" as a new reference-document type: a marked-up delivery sheet PDF
living at the job root, e.g. `Y:\Ready Jobs\596 - HARSHBARGER 2793 TOMAHAWK\596 - PULLS.pdf`.
No parsing — pure PDF viewing, same tier as the existing Delivery (Cover Sheet)
document. Also renames the job-details reference-doc buttons for clarity while
we're touching that row.

## Scope

- KKCSheetTracker (this repo) only. Hours Tracker (separate repo, not open in
  this session) is explicitly out of scope here — see "Hours Tracker handoff"
  below for what a follow-up session needs.
- Job details screens: CNC (`JobDetailScreen.kt`), Hardwoods
  (`HardwoodsJobDetailScreen.kt`), Specialty (`SpecialtyJobDetailScreen.kt`) —
  all three get the button rename + new Pulls button.
- Assembly PDF viewer (`AssemblyViewerScreen.kt`, dual-pane, used when there's
  no job-details screen) gets a Pulls chip alongside its existing
  Plans/Assembly/Delivery/3D/Checklist chips.

## File detection

`JobPdfCatalog` (in `data/models/Models.kt`) gains a new field:

```kotlin
data class JobPdfCatalog(
    val deliverySheet: JobPdfRef? = null,
    val pullsSheet: JobPdfRef? = null,
    val managedDocs: List<JobPdfRef> = emptyList(),
    val otherDocs: List<JobPdfRef> = emptyList()
)
```

`FileBackedUnifiedMetadataEngine.buildPdfCatalog` detects it the same way it
detects `"delivery sheets"`, `"door list"`, etc. — filename (lowercased)
contains `"pulls"` → `managedLabel = "Pulls"`, first match becomes
`pullsSheet`.

`ReferenceDocType` (in `data/models/Models.kt`) gains `PULLS`:

```kotlin
enum class ReferenceDocType { ASSEMBLY, PLANS_ELEVATIONS, DELIVERY_SHEETS, SHEET, PULLS }
```

`findReferencePdfFilename` in `FileBackedUnifiedMetadataEngine.kt` special-cases
`PULLS` the same way it already special-cases `DELIVERY_SHEETS` — read straight
from `getPdfCatalog(jobFolderName).catalog.pullsSheet?.pdfFilename`, skipping
the cabinet-sheet-index / generic-file-scan path entirely (Pulls has no index,
no parsing). `hasReferenceDocument` gets Pulls existence for free through this
path.

Every other exhaustive `when (docType)` this enum addition touches gets a
no-op branch (`null` / `emptyMap()` / `Unit`, matching the existing
`DELIVERY_SHEETS`/`SHEET` no-op branches in the same blocks) purely for
compile-safety — these are blocks unrelated to the actual Pulls feature:

- `FileBackedUnifiedMetadataEngine.findReferencePdfFilename` — the internal
  `fromIndex` `when` and the target-string `when` (label fallback, unreachable
  for PULLS due to the early return above, but must stay exhaustive).
- `ReferenceViewerData.kt` — `documentIndex` and `navigatorCabinetToPages`
  `when` blocks.
- `HardwoodsWorkspaceScreen.kt` — its four `ReferenceDocType` `when` blocks
  (this screen doesn't get a Pulls UI affordance; just needs to keep compiling).

## Job details screens (CNC / Hardwoods / Specialty)

Applies identically to `JobDetailScreen.kt`, `HardwoodsJobDetailScreen.kt`,
`SpecialtyJobDetailScreen.kt`:

- Button text: strip the leading `"View "` — `Assembly`, `Plans & Elevations`,
  `3D`.
- Rename `"View Cover Sheet"` → `"Delivery"`.
- New `Pulls` button placed immediately after `Delivery`, visible only when
  `jobRepository.getJobPdfCatalog(jobFolderName).pullsSheet != null` (same
  existence-gating style already used for the Delivery button today).
- Clicking it calls `onOpenReferenceDocument(ReferenceDocType.PULLS, 1)`
  — same navigation path every other reference doc button already uses, routes
  to `ReferencePdfViewerScreen`.

`ReferencePdfViewerScreen.kt`'s title map changes:

```kotlin
ReferenceDocType.DELIVERY_SHEETS -> "Delivery"   // was "Cover Sheet"
ReferenceDocType.PULLS -> "Pulls"                // new
```

## Assembly PDF viewer (`AssemblyViewerScreen.kt`)

This screen has its own local `PaneSource` enum (PLANS, ASSEMBLY, DELIVERY,
OTHER, THREE_D, CHECKLIST) driving its dual-pane chip picker. Gains
`PaneSource.PULLS`:

- `isPdfSource()` includes `PULLS`.
- `sourceLabel`, `sourceFilename`, `sourcePage`, `setSourcePage` each get a
  `PULLS` branch, mirroring the existing `DELIVERY` branch.
- New `pullsFilename` val, sourced from
  `pdfCatalog?.pullsSheet?.pdfFilename`, mirroring `deliveryFilename`.
- New `firstPanePullsPage` / `secondPanePullsPage` (`rememberSaveable`,
  default `1`), mirroring `...DeliveryPage`.
- New `"Pulls"` `FilterChip` in `PaneSourceControlsInline`, placed after the
  `Delivery` chip. Shown **unconditionally**, matching how `Plans` / `Assembly`
  / `Delivery` chips already behave in this screen (a missing file shows the
  existing "PDF not found" empty state rather than hiding the chip) — this
  intentionally differs from the job-details-screen button, which stays
  existence-gated per that screen's existing convention. Two different,
  already-coexisting UI conventions in this codebase; each surface keeps its
  own.

Out of scope: `AssemblyPaneView` (the separate small enum used for
default-pane user preference / deep-link args in `AssemblyViewerDefaults.kt`)
is not touched — no default-pane preference or deep-link support for Pulls.
Not requested; adding it would pull in unrelated settings-UI surface.

## Hours Tracker handoff (not implemented here)

Hours Tracker lives in `C:\Scripts\Hours Tracker`, a separate repo not open
in this session. After KKCSheetTracker lands, a follow-up session in that repo
needs a self-contained prompt covering:

- The Pulls filename convention (`<job> - PULLS.pdf` at job root, substring
  match on `"pulls"`).
- Pointers into KKCSheetTracker's `FileBackedUnifiedMetadataEngine.kt`
  (`buildPdfCatalog`, `findReferencePdfFilename`) as the reference
  implementation for the detection logic, since Hours Tracker's own PDF-viewing
  code will need the equivalent lookup against the same shared `Y:\Ready Jobs`
  tree (per the KKC metadata map: Hours Tracker reads job-root PDFs the same
  way KKCSheetTracker does — no dedicated owner conflict here, just a second
  reader).
- That no parsing/metadata write is needed, purely additive read+view.

## Testing

- Existing Kotlin unit tests around `FileBackedUnifiedMetadataEngine` /
  `JobPdfCatalog` (if any) get a Pulls-detection case.
- Manual verification on a tablet/emulator: job with a `PULLS.pdf` shows the
  button on all three job-details screens and opens it; job without one hides
  the button; Assembly viewer's Pulls chip opens the same file in both panes.
