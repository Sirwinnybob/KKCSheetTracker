# CNC mix catalog, versioning, and mixed-sheet navigation

## Purpose

Make the CNC tablet a safe operator interface for existing and newly generated PGM mix files. Operators must be able to see active mix names and memberships, create a distinct additional mix, update a selected mix without losing its prior order, and navigate a job according to a selected mix's PGM order.

The CNC PGM Mix Service remains the sole owner of `definitions.json`, compiled `.mix` files, validation, WINXISO compilation, and file deletion. KKCSheetTracker never touches the CNC share directly.

## Scope

This work spans `C:\Scripts\PGM_BCR_Loader` (the deployed CNC Mix Service) and KKCSheetTracker. It does not change Ready Jobs metadata, PGM source files, or the physical-page progress identity.

## Domain model

A material catalog represents every relevant mix file in exactly one of these states:

| State | Source | Production behavior |
| --- | --- | --- |
| `active` | Service definition and its compiled `.mix` file | Selectable, visible in job cards, has an ordered PGM list. |
| `history` | Service definition preserved during a replacement | Retained for recovery only; never creates a production card or viewer route. |
| `external` | A `.mix` found on disk but not owned by a service definition | Blocks all generation and updates for that material until removed. |

Each active or history entry exposes its display name, filename, ordered PGM program list, compile status, and timestamps. External entries expose their exact filename and ownership state; the service does not guess their program membership.

The catalog includes a stable material revision/ETag that changes whenever the service's definitions or discovered `.mix` files for that job/material change.

## Service contract

Add a material-scoped catalog endpoint, for example `GET /jobs/{job}/materials/{material}/mix-catalog`. It returns the catalog entries and revision in one response. It supersedes Android's independent combination of `GET /mixes` and folder inventory for this feature.

The existing folder inventory route remains available, but catalog responses classify ownership at the server where both the filesystem and definitions store are authoritative.

### Create additional mix

Create uses the current create-and-compile safeguards. When at least one active service mix exists, Android opens a name dialog with the selected/original mix name prefilled. Create remains disabled until the operator has changed it to a valid, unique name. The service also enforces this uniqueness; client validation is feedback, not the safety boundary.

The first mix retains current one-tap behavior and uses the existing default generated name without a naming dialog.

### Replace current mix with history

Provide one service operation that performs the entire replacement while holding the existing mix/compile coordination lock:

1. Validate the requested PGM order and selected active definition.
2. Derive an archive name from the old name in CNC local time: `Original Name — YYYY-MM-DD HH-mm`. If that exact timestamped filename already exists, append the next unused numeric suffix (for example, `Original Name — 2026-08-26 14-30 (2)`); it never overwrites an existing file.
3. Rename the compiled old file and move its old definition to that archive name, preserving its old ordered PGM list and recording `history` lifecycle state.
4. Compile and persist the requested new content under the original name as an `active` definition.
5. Invalidate catalog/inventory revisions and return the new catalog or its revision.

The operation is atomic from the client's point of view: it either leaves the prior active mix intact or returns one active original-name mix plus one history entry. Existing history-sidecar error semantics remain intact: a completed mutation with failed sidecar sync is not retried as a mix mutation.

### External deletion

Add a material-scoped delete operation for external `.mix` files. It accepts an exact safe filename, resolves it within the named material folder, verifies it is still external, and deletes only that file. It must reject service-owned, history, path-traversal, stale-revision, and missing-file requests. The endpoint returns the refreshed catalog/revision.

Android shows a permanent-delete confirmation with the exact external filename. It must not offer any generation/update action while an external catalog entry exists.

## Tablet catalog cache

Add a persistent `MixCatalogCache` owned by the Android mix-service data layer. It is keyed by job folder and material name and retains the last successful catalog plus revision and fetch timestamp. It has an in-memory front cache and durable local backing so the job screen can render immediately after app restart.

Job Detail, Manage Code, and Sheet Viewer read cached data synchronously, then request a background conditional refresh. A successful refresh replaces the cached snapshot only when its revision differs. Failed refreshes retain the visible cache and show a non-blocking stale/unavailable indication; they do not delay opening a job or viewer.

Successful create, replace, and external-delete responses write their returned catalog/revision to the cache immediately. Destructive controls require an online service response and cannot rely on a stale cached ownership decision.

## Manage Code experience

Manage Code shows active mix names, membership, and compile state for each material. It retains the existing row selection and PGM edit workflow.

- With no active service mix and no external file, Generate makes the normal first/default mix without prompting.
- With active mix entries, Generate asks whether to update one selected active mix or create another.
- Updating displays an explicit confirmation that the old content will be archived under its timestamped history name and the edited content will retain the original name.
- Creating another prepopulates the original name but requires an operator change before it enables submission.
- Duplicate PGM membership continues to warn before submission. If the operator confirms it, duplicate pages remain intentionally shared physical progress.
- With any external entry, show the blocking file and its confirmed deletion action instead of generation/update controls.
- History can be shown in a history-only view but is never selectable for production editing or viewing.

## Job Detail and viewer navigation

Derive a presentation row from each physical CNC material and its cached active catalog:

- No active service mix: one normal material row, natural visible page order.
- One active service mix: one material row and mix-based page order.
- Two or more active service mixes: one row per mix named `MIXNAME - MATERIAL`, each opening the selected mix.

Each mix row derives its own `StatusCounts` from only its mapped physical pages, so its progress bar accurately reflects only that mix. Completion is still stored by the existing physical job/PDF/page identity. Consequently, a deliberately duplicated page appears completed in every active mix containing it immediately after it is completed in any one viewer.

Viewer navigation receives an optional active mix identity in its route. The viewer resolves the exact mix catalog entry, maps its PGM list to physical sheet pages using the established `ManageCodeRow` matching rules, retains the stored PGM order, and deduplicates physical pages. It does not append unrelated pages. Its navigator, TOC, current position, total, auto-advance, and completion labels all use this filtered ordered list. Thus a physical PDF page 2 first in a mix displays as `Sheet 1 of N`.

The current first-match mix lookup must be removed; the viewer may use only the explicitly selected active mix. If the cached mix is missing or no longer active at refresh time, the viewer returns to the material detail rather than selecting a substitute.

## Error handling and concurrency

- Catalog mutations use the expected catalog revision and return a conflict if another operator changed the material first. Android refreshes and asks the operator to choose again; it never selects a different mix implicitly.
- Invalid names, missing PGM files, compilation lock/timeout, and history-sync outcomes continue to surface their specific service errors.
- Cache reads remain usable offline; catalog-changing actions do not.
- External inventory can change independently. Every delete/replacement request rechecks server ownership under the service lock.

## Verification

Service tests cover catalog classification/revision, active/history filtering, safe external deletion, default-name and unique-name validation, stale revision conflicts, and all-or-nothing archive-and-replace behavior.

Android tests cover cache persistence and background refresh, mutation cache updates, generation blocking for external files, naming-dialog validation, service response/error mapping, active mix row derivation, per-mix status counts, shared progress for duplicate membership, exact PGM-to-page filtering/order, viewer Sheet `X of N` labels, TOC order, and missing/stale selected-mix recovery.

Manual acceptance tests cover a job with one mix, two distinct active mixes, deliberate duplicate sheet membership, one external `.mix` blocker/deletion, a timestamped historical replacement, and a cold tablet start with the CNC service temporarily unreachable.
