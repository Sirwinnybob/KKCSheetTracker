# Assembly Mode v1 Implementation Plan

## Summary
Assembly mode is a read-only production visibility mode that combines CNC and Hardwoods progress while centering around reference drawings. It provides:
- Dashboard: side-by-side CNC and Hardwoods progress per job
- Jobs: Assembly-mode job browser
- Viewer: Plans & Elevations + Assembly Sheets side-by-side with synchronized cabinet jump
- Parts panel: cabinet BOM from assembly `pageDetails.parts` mapped to CNC/Hardwoods progress
- Search: Assembly-focused search across cabinet numbers, parts, room/wall context

Parsing is already implemented in Ready Jobs Watcher and validated against live metadata at:
`Y:\Ready Jobs\300 - K2 DESIGN BLUE RIVER\.metadata\cabinet_sheet_index.json`

## Data Contract (Consumed by Android)
Assembly mode depends on `cabinet_sheet_index.json` under each job's `.metadata` folder:
- `documents.assembly.pdfFilename`
- `documents.plansElevations.pdfFilename`
- `documents.*.cabinetToPages`
- `documents.assembly.pageDetails[page].{cabinets, room, wall, parts}`
- `parts[]` entries:
  - `qty`, `width`, `length`, `description`, `material`, `sectionType`, `isPurchased`

Compatibility rules:
- Missing `pageDetails` and/or `parts` must not crash UI.
- When `parts` are missing, viewer still functions and parts panel falls back to indexed CNC/Hardwoods lists.

## UI + Behavior

### 1. Dashboard (Assembly)
- Show jobs sorted by job number descending.
- Each card shows:
  - CNC progress: completed sheets / total sheets
  - Hardwoods progress: done pieces / total pieces
- Read-only: no editing controls.

### 2. Jobs (Assembly)
- Filterable list of Assembly jobs.
- Tap job opens Assembly viewer route:
  - `assembly/viewer/{folderName}/{startPageAssembly}/{startPagePlans}`

### 3. Viewer (Assembly)
- Dual pane layout:
  - Left: Plans & Elevations
  - Right: Assembly Sheets
- Cabinet search:
  - Uses `cabinetToPages` in both documents
  - Jumps each pane independently
  - Shows room/wall context from assembly `pageDetails`
  - Missing-in-one-doc shows snackbar, still jumps other doc
- Parts button:
  - Opens bottom sheet for last searched cabinet

### 4. Parts Checklist Panel
- Primary mode: BOM from `pageDetails.parts`, grouped by `sectionType`
- Per-part status chip logic:
  - `isPurchased` => Purchased
  - CNC match => status from `ProgressStore` (`COMPLETE`, `HAS_BAD_PARTS`, etc.)
  - Hardwoods match => done/bad/skipped from `HardwoodsProgressStore`
  - no match => Not Indexed
- Legacy fallback when BOM empty:
  - Show counts from indexed CNC and Hardwoods matches

### 5. Assembly Search
- Search inputs:
  - job number/name/folder
  - cabinet number
  - part description/material/section
  - room/wall text
- Result click opens Assembly viewer at best available assembly/plans pages.

## Matching Rules (v1)
- Key normalization: `trim -> lowercase -> collapse whitespace`
- BOM to CNC: `part.description` matched to CNC `partName`
- BOM to Hardwoods: `part.description` matched to hardwood row `description`
- Multiple matches are allowed and surfaced (no fuzzy match in v1)

## Navigation and State
- WorkMode branching is explicit (`CNC`, `HARDWOODS`, `ASSEMBLY`) across multi-stack and legacy navigation.
- Assembly routes are first-class and do not fall through to CNC/Hardwoods rendering.
- New Assembly state layer:
  - `AssemblyScanCoordinator`: scans jobs and loads cabinet sheet indexes
  - `AssemblyStateStore`: derives dashboard cards, search index, jump targets, BOM/status mapping

## Verification Checklist
1. `./gradlew :app:compileDebugKotlin` passes.
2. Switch Settings -> Work Mode -> Assembly; Assembly screens render (not CNC/Hardwoods fallback).
3. Dashboard cards display CNC + Hardwoods metrics per job.
4. Jobs list opens Assembly viewer route.
5. Viewer cabinet jump updates both panes and room/wall subtitle.
6. Parts sheet shows BOM sections and status chips for a searched cabinet.
7. Assembly search opens viewer at expected pages.
