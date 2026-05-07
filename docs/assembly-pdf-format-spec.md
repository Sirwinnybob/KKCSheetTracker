# Assembly/Plans PDF Parsing Contract Spec

## Purpose
Defines the formatting and JSON contract used by Ready Jobs Watcher parsing so Android Assembly mode can reliably:
- map cabinet numbers to pages
- show room/wall context
- render cabinet BOM parts and status overlays

## Marker Format
Recommended explicit markers in Cabinet Vision templates:
- Cabinet marker: `||CAB:<number>||`
- Wall/room marker (optional but preferred): `||WALL:<text>||`

Examples:
- `||CAB:42||`
- `||CAB:42||  ||WALL:Room 1 - Wall A||`

Rules:
- Cabinet number is integer (1-4+ digits as needed).
- Multiple cabinets on one page: include multiple `||CAB:...||` markers.
- If wall text includes room information, parser may split to `room` and `wall` fields.

## JSON Output Contract
File path per job:
- `.metadata/cabinet_sheet_index.json`

Required top-level fields:
- `generatedAt`
- `documents.assembly`
- `documents.plansElevations`

Each document block:
- `pdfFilename: string`
- `cabinetToPages: { [cabinetNumber: string]: number[] }`
- `pageDetails: { [page: string]: CabinetPageDetail }` (required for assembly UI features)

`CabinetPageDetail`:
- `cabinets: string[]`
- `room: string | null`
- `wall: string | null`
- `parts: AssemblySheetPart[]` (assembly document pages)

`AssemblySheetPart`:
- `qty: number`
- `width: number`
- `length: number`
- `description: string`
- `material: string`
- `sectionType: string`
- `isPurchased: boolean`

## Assembly Parts Parsing Shape (Current)
The parser recognizes assembly part rows in 5-line groups under section headers, filtered to avoid diagram noise:
1. qty (`N` or `N P`)
2. width (decimal)
3. length (decimal)
4. description (contains letters)
5. material (contains letters)

Section headers are pipe-delimited (for example `| Frame |`, `| Panel Stock |`).

## Backward Compatibility Expectations
- If explicit CAB/WALL markers are absent, parser fallback heuristics are used.
- Android must tolerate missing `pageDetails` and missing `parts` without crashing.
- Viewer remains functional with cabinet/page jumps when only `cabinetToPages` exists.

## Validation Checklist
For each newly formatted template export:
1. Open generated `.metadata/cabinet_sheet_index.json`.
2. Confirm both `assembly` and `plansElevations` have `cabinetToPages`.
3. Confirm assembly `pageDetails` includes `cabinets`, `room`, `wall`, and `parts`.
4. Spot-check at least one multi-page cabinet merge.
5. Spot-check purchased parts (`isPurchased=true`).
6. Verify at least one known cabinet jumps correctly in app viewer.
