# Supply Custom-Field Parity on Tablets — Design

**Date:** 2026-07-11
**Repo:** KKCSheetTracker (Android tablet app)
**Issue:** METADATA_AUDIT.md **M-13** — deferred "full tablet parity" arm

## Problem

Custom supply fields added via the admin portal's schema editor are invisible and
uneditable on tablets. The tablet UI hardcodes the 4 builtin fields
(`sku`, `quantity`, `vendorLink`, `trackingNumber`), never reads `.supply/schema.json`,
and never populates `StoredSupplyItem.customFields`. Any admin-added custom field is
silently dropped from the shop-floor experience.

The M-13 fix already shipped the "document + drop write-on-read" arm (admin banner +
`ensure_dirs` init). This spec covers the remaining arm: real tablet feature parity.

## Goal

Make the tablet Supply **Edit** and **Detail** screens **fully schema-driven**: render
every field dynamically from the synced `.supply/schema.json`, so custom fields appear,
are editable, and round-trip into `customFields`.

## Current state (verified)

- `.supply/schema.json` is a Syncthing-replicated file already present on the tablet;
  `SupplyRepository.getSchema()` reads it → `List<SupplySchemaField>`. No backend call
  needed; works offline.
- Models already carry the needed shapes:
  - `SupplySchemaField(id, key, label, type, builtin)`
  - `StoredSupplyItem` / `SupplyItem` both have `fields: Map<String,String>` and
    `customFields: Map<String,String>`.
- Admin schema editor allows 4 field types: `text`, `number`, `url`, `date`.
- Gaps:
  - `SupplyItemEditScreen.kt` hardcodes 4 `var`s + 4 `OutlinedTextField`s → `fields`;
    never reads schema; never writes `customFields`.
  - `SupplyItemDetailScreen.kt` hardcodes the same 4 as read-only rows from `fields`.
  - `SupplyRepository.createItem` hardcodes `customFields = emptyMap()`;
    `updateItem` never accepts/sets `customFields` (it preserves existing on copy).

## Approach: fully schema-driven

Render all fields from `schema.json`, ordered as in the schema. Route each field's value
by its `builtin` flag:

- builtin field → value stored in `StoredSupplyItem.fields`
- custom field → value stored in `StoredSupplyItem.customFields`

This keeps the backend/web contract intact (web reads `fields` for the builtins) while
adding custom-field support.

### Data flow

1. **Load (Edit):** `schemaOrDefault()` → ordered fields. For each, seed editor state
   from `item.fields[key]` (builtin) or `item.customFields[key]` (custom). New item →
   blank.
2. **Edit:** per-field editor writes into a single `mutableStateMapOf<String,String>`
   keyed by field `key`.
3. **Save:** split the edited value map into `(fields, customFields)` by each field's
   `builtin` flag (via the pure routing helper), then call the repository. Blank values
   are omitted (matches today's `buildMap { if (isNotBlank()) ... }`).
4. **Detail:** render one row per schema field with a non-blank value, in schema order,
   plus any orphan values (see below).

## Robustness

1. **Empty/missing-schema fallback.** If `getSchema()` returns empty (schema.json not yet
   synced or unreadable), fall back to an in-app `DEFAULT_SUPPLY_SCHEMA` constant — the 4
   builtins, mirroring the backend `DEFAULT_SCHEMA`. Guarantees the editor never renders
   zero fields and can never blank an item on save. Implemented as a `schemaOrDefault()`
   helper so `getSchema()` stays a pure file reader.
2. **Orphan values.** An item may hold a value for a key the current schema no longer
   lists (field deleted after the item was created). On save, orphan keys in *both*
   `fields` and `customFields` are preserved untouched (round-trip), never silently
   dropped. In Detail, any non-blank orphan value is still shown (label = the raw key) so
   nothing disappears from view. Orphans are not rendered as editable inputs in Edit
   (their type is unknown).

## Type handling (balanced)

| type   | Edit input                         | Detail render              |
|--------|------------------------------------|----------------------------|
| text   | plain `OutlinedTextField`          | plain text                 |
| number | numeric keyboard                   | plain text                 |
| url    | URI keyboard                       | tappable link              |
| date   | plain text, `YYYY-MM-DD` hint      | plain text                 |

- Values are stored as `String` throughout (matches existing `Map<String,String>`; avoids
  the M-12 float-coercion trap).
- No "required" concept — the admin schema has no required flag. `name` + `category` stay
  the only required inputs, as today.
- A Material date-picker widget is an explicit follow-up, not in this spec.

## Components changed

1. **`data/models/SupplyModels.kt`** — add `DEFAULT_SUPPLY_SCHEMA: List<SupplySchemaField>`
   (the 4 builtins, matching backend order/keys/types).
2. **`data/SupplyRepository.kt`**
   - `createItem(...)` gains a `customFields: Map<String,String> = emptyMap()` param and
     persists it (instead of hardcoded `emptyMap()`).
   - `updateItem(...)` gains a `customFields: Map<String,String>` param and sets it on the
     `copy(...)` (currently untouched). Keep the atomic-write + H-07 CROSS-PROGRAM contract.
   - add `schemaOrDefault(): List<SupplySchemaField>` = `getSchema().ifEmpty { DEFAULT_SUPPLY_SCHEMA }`.
3. **`ui/supply/SupplyItemEditScreen.kt`** — replace the 4 hardcoded fields with a dynamic
   loop over `schemaOrDefault()`; per-field state in a `mutableStateMapOf`; split into
   fields/customFields via the routing helper on save; preserve orphan values.
4. **`ui/supply/SupplyItemDetailScreen.kt`** — replace the 4 hardcoded rows with dynamic
   rows (schema fields + orphans); url rows tappable.
5. **New small composables** — `SupplyFieldInput` (typed edit input) and `SupplyFieldRow`
   (detail row) to keep the screens focused and readable.
6. **New pure helper** — field routing/splitting + fallback, e.g. in a
   `SupplyFieldRouting.kt` (or top-level funcs in the data layer), so the logic is unit
   testable without Compose.

## Testing

Logic lives in pure, testable helpers; Compose wiring is verified by build + manual.

- **New `SupplyFieldRoutingTest`** (pure JVM):
  - split an edited value map into `(fields, customFields)` correctly by schema `builtin`
    flag;
  - blank values omitted;
  - orphan keys (present on item, absent from schema) preserved in the correct map;
  - `schemaOrDefault()` fallback returns `DEFAULT_SUPPLY_SCHEMA` when schema is empty.
- **`SupplyRepositoryTest`** (extend): `createItem`/`updateItem` persist and round-trip
  `customFields`; orphan `customFields` survive an `updateItem`; builtin values land in
  `fields`, custom in `customFields`.
- Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.Supply*"`
  and `:app:compileDebugKotlin`. (Use the `:app:` module prefix — a bare
  `testDebugUnitTest` runs against `:updater-agent`.)
- Manual walkthrough on a tablet: add a custom field in admin → confirm it renders in
  Edit (typed input), saves, and shows in Detail; url field is tappable; delete the field
  in admin → confirm the value still shows in Detail and survives an edit.

## Out of scope

- Material date-picker widget (follow-up).
- Field reordering, required flags, per-type validation rules (admin schema has none).
- Web/backend rendering of `customFields` — the backend already models the field; confirm
  it displays (verification item, no code change expected here).
