# Board Stock v1 (Hardwoods) - Agent Handoff

## Objective
Implement a new virtual Hardwoods cut list named **Board Stock** that replaces Totals UI usage and aggregates board-stock demand across cut lists.

This handoff is intended to be **decision-complete** so an implementation agent can begin immediately.

## Locked Product Decisions
- Add a new Board Stock cut-list tab in Hardwoods workspace.
- Move totals behavior to Board Stock (remove `Part List | Totals` toggle from normal cut-list tabs).
- Board Stock is synthetic/app-generated (no PDF backing).
- Source docs for Board Stock generation:
  - `FACE_FRAME_CUT_LIST`
  - `NAILER_CUT_LIST`
  - `DOOR_CUT_LIST`
- `DOOR_LIST` remains excluded from segmented progress bars.
- Board Stock rows are grouped by **material + width + source** (source-specific rows are not merged).
- Rows with same material+width from different sources should appear adjacent under material, not merged.
- Width sort: widest to narrowest.
- Tie-break for same width: source priority
  - `FRAME`
  - `NAILER`
  - `DOOR`
  - `MANUAL`
- Source chip labels must be exactly: `FRAME`, `DOOR`, `NAILER`, `MANUAL`.
- Length is not shown in Board Stock rows.
- Board Stock math:
  - `neededRips = ceil(totalFeet / 10.0)`
  - Ceiling is strict (e.g. `10.001 -> 2`).
- Progress integration:
  - Board Stock is a segment in Hardwoods segmented progress bars like other cut lists.
  - Segment width weighting is by each segment's own totals.
- Add Board Stock card in Hardwoods Job Detail.
- Dashboard should include Board Stock progress visibility.
- Existing per-doc totals tallies should be migrated once to Board Stock tallies.

## Scope Boundaries
In scope:
- KKC app model, repository aggregation, UI, navigation/presentation, progress integration, one-time tally migration.
- Manual-entry groundwork via a sidecar file contract only.

Out of scope:
- Building the external desktop/manual management tool.
- Watcher parser redesign for this feature (use current `cutlist_index.json` totals output).

## Current System Context (Important)
- Hardwoods index source today: `.metadata/hardwoods/cutlist_index.json`.
- Totals currently live per document in each `HardwoodDocumentIndex.totals`.
- Current tallies use keys of form `docType|blockIndex|lineIndex` in `HardwoodsProgressStore`.
- Workspace currently supports document tabs and had per-doc totals rendering.

## Target Data Model Additions
Add app-side types for Board Stock aggregation:
- `BoardStockSource` enum with values `{ FRAME, NAILER, DOOR, MANUAL }`.
- `BoardStockRow` model containing at minimum:
  - `stableKey`
  - `material`
  - `width`
  - `source` (`BoardStockSource`)
  - `sourceLabel` (for chip text)
  - `totalFeet` (Double)
  - `neededRips` (Int)
  - optional manual metadata (`manualCategory`, `manualSubtype`, `notes`).

Add optional manual sidecar contract at:
- `Y:\Ready Jobs\<job>\.metadata\hardwoods\board_stock_manual.json`

Recommended sidecar JSON schema (flat entries):
```json
{
  "entries": [
    {
      "material": "MAPLE",
      "category": "crown",
      "subtype": "Crown A",
      "width": "3",
      "totalFeet": 42.5,
      "notes": "optional"
    }
  ]
}
```

## Aggregation Rules
Build Board Stock rows from all totals blocks in the three source cut lists:
1. For each totals line, parse width and footage (from `ripsValues` / linear-feet value already represented in totals logic).
2. Normalize width for sorting/grouping as existing dimension parser does.
3. Group by:
   - material
   - normalized width
   - source (`FRAME|NAILER|DOOR|MANUAL`)
4. Sum `totalFeet` in each group.
5. Compute `neededRips = ceil(totalFeet / 10.0)`.

Display:
- Primary: width + `Need X rips`
- Secondary: `Total Y ft`
- Source chip.

## Progress & Migration Strategy
- Create Board Stock tally keys independent of legacy per-doc totals keys.
  - Suggested key: `board_stock|<material>|<normalizedWidth>|<source>`.
- One-time migration:
  - Read existing totals tallies (`docType|block|line`) from current tracker cache.
  - Map each old tally to generated Board Stock row key via source doc + totals line mapping.
  - Apply migrated done counts once.
  - Persist migration marker per job+tablet to prevent duplicate migration.
    - Suggested marker file: `Hardwoods/.tracker/.board_stock_migration_<tabletId>.json`.

## UI/Navigation Targets
Primary files to update:
- `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`
- `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobDetailScreen.kt`
- `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobsScreen.kt`
- `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsDashboardScreen.kt`
- `app/src/main/java/com/kkc/sheettracker/data/HardwoodsProgressStore.kt`
- `app/src/main/java/com/kkc/sheettracker/data/HardwoodsRepository.kt`
- `app/src/main/java/com/kkc/sheettracker/data/models/Models.kt`

Expected UI behavior:
- Workspace doc chips include new `Board Stock` tab.
- For normal cut-list docs: show `Part List` only.
- Board Stock tab: collapsible material sections, compact row tiles, source chip + rip tally controls.
- Job Detail: add Board Stock card and include it in segmented progress presentation.

## Acceptance Criteria
1. Board Stock tab appears and loads for Hardwoods jobs with totals.
2. Regular cut-list tabs no longer show totals mode.
3. Board Stock row math is correct (`ceil(totalFeet/10)`) for edge cases.
4. Same material+width from different sources are separate rows with source chips.
5. Width ordering and source tie-break ordering match locked rules.
6. Manual sidecar rows merge into Board Stock with `MANUAL` source chip.
7. Board Stock is included in segmented progress bars in Hardwoods flows.
8. One-time migration preserves existing tally progress into Board Stock and does not re-apply.
9. Door List remains excluded from segmented progress behavior.
10. CNC mode behavior unchanged.

## Suggested Implementation Order
1. Add Board Stock models + source enum + repository aggregation function.
2. Add manual sidecar loader and merge path.
3. Add Board Stock tab UI and remove old totals toggle for standard docs.
4. Add Board Stock tally keys and one-time migration in progress store.
5. Integrate Board Stock into Hardwoods progress summaries/cards/segments/dashboard.
6. Run build + targeted behavior checks.

## Verification Checklist for Agent
- Build: `./gradlew :app:compileDebugKotlin` and `./gradlew :app:assembleDebug`
- Manual checks:
  - Workspace tab behavior
  - Board Stock sorting/grouping
  - Rip math edge values
  - Progress segment presence
  - Existing tally migration once-only behavior

