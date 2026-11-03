# Smart mix order — design

Date: 2026-11-03. Status: draft for owner review.

## Why

The CNC operator resurfaces the spoilboard before a job. A fresh spoilboard holds parts best,
so he hand-reorders each new mix to cut first the sheets whose small parts sit near the sheet
edge, where vacuum hold is weakest. Doing this by hand takes time and depends on him remembering
every sheet. The tablet already has every part's size and position in the job's CNC
`.metadata`, so it can propose that order itself and learn from his corrections.

## Goals

- **New mix** opens with sheets pre-sorted into a smart order.
- A **Smart suggestion | Default** toggle on New mix. Smart is selected by default.
  Default restores today's order (metadata page order).
- He can still drag sheets to adjust the smart order.
- Each time he applies a new mix, the tablet saves what was suggested and what he actually
  applied. All tablets share these records and learn from them, so the order gets closer to
  his habits over time.
- Everything runs on the tablet. The Win7 mix-service PCs only receive the final order through
  the existing apply path.

## Non-goals

- No toggle or suggestion when editing an **existing** mix (owner decision).
- **No fallback for jobs without part placements** (owner decision). If any sheet in the new
  draft lacks placement data (see Qualifying below), New mix behaves exactly as today: no toggle,
  default order, nothing logged.
- No changes to the mix service, the PGM Sorting pipeline, or the metadata producers.
- No server-side model, hub endpoint, or cloud training.

## Anti-goals (failure even if it works)

- Moving a locked or completed sheet. The existing completed-PGM lock still applies.
- Splitting a double-sided sheet's A and Z PGMs apart, or reversing their order.
- A slow New mix. Scoring and training must not visibly delay opening the draft.
- Learning that silently degrades the order from a handful of odd mixes. Learned weights stay
  anchored to the hand rule until enough data exists.
- Shared-file conflicts between tablets (Syncthing conflict copies).

## Definitions (owner-confirmed)

- **Small part**: width < 6" or length < 6" (either or both).
- **Edge band**: starting value 3" from any sheet edge (my call, from the conversation's
  guess; the owner accepted the playback). A small part is "on edge" when its footprint on the
  sheet comes within the band of any of the four edges. This value is a constant, not learned.

## Current code (where this plugs in)

- `MixesSession.startNew(material, pages, locked)` → `MixDrafts.startNew(file, pages, inOtherMixes)`
  (`data/mixfiles/MixDraft.kt`) builds entries from `pages.sortedBy { pageNumber }.flatMap { pgmFiles }`,
  deduped and excluding PGMs already in other mixes. That page order is **Default**.
- `MixesSession.saveMix` is the **Apply change(s)** path. On `MixFilesResult.Ok` the draft is
  cleared. The learning record is written there, only when the saved draft was a new mix
  (`draft.newFile != null`).
- `SheetPage` (`data/mixfiles/SheetMatching.kt`) is built from `PageMetadata`. `PageMetadata` has
  `sheetDimensions` and `parts`, but `Part` (`data/models/Models.kt`) does **not** parse
  `sheetPlacement` yet. Add `sheetPlacement: SheetPlacement? = null` (`x`, `y`, `angle`).
- Shared per-tablet write precedent: `perf/CpuSpikeLogStore.kt`. It writes one file per record into
  `Ready Jobs\.metadata\cpu_spikes`, with the tablet ID in the file name, and uses a local pending
  folder that is flushed later when the share is offline. Follow the same pattern.

## Qualifying (my call)

Only the sheets that actually land in the new draft count: the pages whose PGMs survive
`startNew`'s dedupe and other-mix exclusion. Hidden pages are counted when their PGMs are in the
draft, because `startNew` includes them. Continuation pages are merged into their head sheet, not
checked separately. A sheet qualifies when it has `sheetDimensions` and at least one part, and
**every** part on it has `sheetPlacement`. The draft qualifies only when every sheet in it
qualifies. A partially placed sheet disqualifies the whole draft (strict, matching "no fallback").

## Scoring

Score per **sheet** (logical sheet), not per PGM:

- A double-sided page's PGMs stay together in `orderedPgmFiles` order (A before Z).
- Part-list continuation pages (`isPartListContinuation` / `continuationHeadPage`) merge their
  parts into the head page's sheet. The dedupe in `startNew` already shares their PGMs.

Features per sheet (computed from `.metadata` only):

| Feature | Meaning |
|---|---|
| `smallEdge` | count of small parts on edge |
| `smallInterior` | count of small parts not on edge |
| `smallEdgeArea` | summed area of small on-edge parts ÷ sheet area |
| `minDim` | smallest part dimension on the sheet (inches) |
| `partCount` | all parts |

Score = w · f. Sheets sort by score, highest first; ties keep their default order. Locked sheets
keep their slots (reuse `MixDrafts.move` semantics: only unlocked rows move).

**Starting rule (prior weights):** `smallEdge` dominates, `smallInterior` breaks ties, others 0.
This is the order used until enough learning records exist.

## Learning

### Capture (on Apply of a new mix)

Write one JSON file per applied new mix, only for jobs that qualified for Smart. Path:
`Ready Jobs\.metadata\mix_order_learning\<timestamp>_<tabletId>_mixorder.json`.
Use a local pending folder while the share is offline, as `CpuSpikeLogStore` does.

Record (`version: 1`):

- `recordId` (random UUID; the identity used for dedupe and consolidation)
- `job`, `material`, `mixFile`, `tabletId`, `appliedAt`, `appVersion`
- `mode`: `"smart"` or `"default"` (toggle state at Apply)
- `edgeBandIn`, `smallPartIn` (constants used)
- `weightsUsed` (the weight vector that produced the suggestion)
- `defaultOrder`, `suggestedOrder`, `finalOrder`: lists of sheet keys (head page number + PGM list)
- `sheets`: map of sheet key → feature values from the table above
- `lockedSheets`

Features are stored in the record so training never has to re-read job metadata (jobs move to
archive).

The owner asked to log mixes that differ from the default order. With Smart on by default, almost
every new mix will differ. Smart applied with no changes (the model got it right), Smart with
drags (the model got it wrong in places), and a switch to Default (Smart got it wrong for that
mix) are all logged, as the owner accepted in playback.

### Training (on the tablet)

- When the Mixes screen opens, a background coroutine reads every record in
  `mix_order_learning\` (all tablets' files), caching parsed records by file name.
- Which records train (my call, consistent with the owner's original "save when out of default
  order" rule): Smart-mode records always train. Default-mode records train only when
  `finalOrder != defaultOrder` (he dragged in Default). An untouched Default apply is logged for
  review but never trained on and does not count toward N, because page order says nothing about
  edge parts.
- Training data is pairwise. For each pair of unlocked sheets present in both `suggestedOrder`
  and `finalOrder`, the sheet earlier in `finalOrder` should outscore the later one. Sheets added
  or removed after the suggestion produce no pairs. Fit the weights with linear pairwise logistic regression
  (RankNet-style) and an L2 penalty that pulls them toward the prior weights.
- Use learned weights only once there are at least N records (start with N = 5, my call). Below
  that, use the prior.
- Group by material in a later version. v1 has one shared model.
- Must run in well under 100 ms for hundreds of records. It must not block the main thread.
- The trained weights are cached in memory only; nothing besides the records is persisted.
- Retention (my call): records are the learning asset, so no auto-deletion (unlike
  `CpuSpikeLogStore`'s cap). Each tablet keeps a local parse cache keyed by file name, so an open
  lists the folder but reads only new files. Consolidation (below) keeps the file count down.

### Consolidation (owner request)

Many single-record files can be merged into one bundle, run either **on a tablet** or **from the
development PC**. Both produce the same format.

- Bundle: `mix_order_learning\bundles\<timestamp>_<source>_bundle.json`,
  `{ "version": 1, "kind": "bundle", "createdAt", "source", "records": [ ...records ] }`.
  `source` is the tablet ID or `pc`.
- Readers (the trainer) read single-record files **and** bundles, and dedupe by `recordId`, so a
  record that appears twice (crash mid-consolidation, two consolidators at once) is counted once.
- Steps: gather every single-record file and every existing bundle, dedupe by `recordId`, write the
  new bundle via temp file + rename, read it back and verify every gathered `recordId` is present,
  and only then delete the single files and old bundles it absorbed. A file that has already
  disappeared (another consolidator took it) is skipped, not an error. Files with unknown or newer
  `version` are left untouched. A file that fails to parse is moved to `mix_order_learning\bad\`
  instead of deleted.
- Tablet: an admin-only action in Settings → Admin pane, "Consolidate mix learning data", showing
  how many files were merged. It runs off the main thread and needs the share online. The local
  pending folder is flushed first.
- PC: `tools\consolidate-mix-learning.ps1 [-Root "Y:\Ready Jobs\.metadata\mix_order_learning"] [-WhatIf]`,
  PowerShell only (no Python), with the same steps and verification. `-WhatIf` prints counts
  without writing.
- Unit tests (tablet side): dedupe across singles + bundles, verify-before-delete, missing file
  skipped, unknown version left alone, bad file moved. The PC script gets a `-WhatIf` dry run
  against a copied folder before first real use.

## UI

- New mix draft: a two-option segmented control at the top, **Smart suggestion | Default**,
  using the existing KKC sliding-pill/segmented style (see the slider theming memory: primary
  track, secondary pill).
- Switching modes re-sorts the draft's current sheets (smart score or page order) and discards
  manual drags, with no confirmation (my call; owner did not object). It is an order change only:
  a rename and added or removed entries are kept. Added non-sheet PGMs (no page match) stay at
  the end in their current order.
- Drag-to-reorder works in both modes as today.
- In Smart mode each sheet row shows a small tag, for example `5 small · 3 on edge`. No tag in Default.
- The toggle is absent when the job's material does not qualify (any page missing placements) and
  absent for existing mixes.

## Open for the builder

- ~~Placement origin and axes~~ **Verified 2026-11-03** against job 720 `partBoxes`:
  `sheetDimensions = [y height, x length]`; `(x, y)` is the part's local origin, local rect is
  `width` along x and `length` along y, rotated CCW by `angle` about the origin (angle 90 →
  x ∈ [x−length, x], y ∈ [y, y+width]). Details and test values are in the plan.
- Exact layout of the toggle and tag within the existing mix card and row composables.
- Learning-rate, L2 strength, and iteration count for the fit, within the speed limit above.

## Testing

- Unit: feature extraction from real 720 metadata (edge test on known parts); A/Z pairing kept;
  continuation merge; locked sheets fixed; tie order stable; qualify check rejects a page without
  placements.
- Unit: trainer recovers a known weight vector from synthetic orders; stays at prior with < N records.
- Unit: record writer file name, pending-folder flush, JSON round trip.
- Unit: `saveMix` writes a record only for new mixes on qualifying jobs, and only on `Ok`.
- On device: owner checks New mix on a job with placements (release build per tablet rules).
