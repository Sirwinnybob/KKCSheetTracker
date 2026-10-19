# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Cabinet-shop operators using shared Android tablets while tracking sheet materials, job work, specialty items, door assemblies, and time.

## Product Purpose

KKCSheetTracker gives operators offline-safe, job-specific production information and progress workflows. The wing viewer lets an operator inspect the exact captured island wing associated with the item or paneled end they selected.

## Positioning

The app consumes the shop's canonical Ready Jobs metadata and CV Cutlist Query export directly, retaining safe fallbacks instead of guessing at manufacturing identity, geometry, or materials.

## Operating Context

Operators use tablets at two shop locations connected by VPN. Job data is generated as `job_data.json`; Hours Tracker owns office specialty-item data; CV Cutlist Query is the only writer of `job_data.json`.

## Capabilities and Constraints

- The app must preserve current specialty progress/editing and door-assembly workflows.
- A selected item or paneled end may show only its resolved single wing; no unrelated fallback model is acceptable.
- Wing geometry is optional schema-v2 data, measured in inches and expressed in the retained CV parent hierarchy.
- TS and DE supports remain in source data but are hidden in every wing presentation.
- Door textures must use the established material-ID/name lookup, native JXL decoding/cache, physical scale, grain direction, hash checks, retry behavior, and white fallback.
- Wing boards use only exporter-published material/grain evidence; `sourcePid` is not a report-part identifier.
- The viewer must work without a remote CDN, room GLB, or new external service.

## Evidence on Hand

- Verified four-wing export: `C:\Scripts\CV Cutlist Query\target\verified-wing-export\job_data.json`.
- Data contract: `C:\Scripts\CV Cutlist Query\docs\job-data-contract-v2.md`.
- Existing 3D infrastructure: `app/src/main/java/com/kkc/sheettracker/viewer3d/` and `app/src/main/assets/viewer/`.

## Product Principles

1. Show the exact verified job data selected by the operator.
2. Prefer an explicit unavailable state to an unproven inference.
3. Preserve real geometry and material evidence without inventing machining or grain.
4. Keep tablet interactions direct, touch-friendly, and resource-conscious.

## Accessibility & Inclusion

Viewer controls must have clear labels and touch targets appropriate for tablet operation.
