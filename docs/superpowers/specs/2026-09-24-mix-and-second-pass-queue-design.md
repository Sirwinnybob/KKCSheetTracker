# Mix + Second Pass Queue and Existing-Mix Prompt — Design

Date: 2026-09-24
Status: Approved (brainstorm), pending spec review

## Problem

1. **Mix + second pass in one run silently drops the second pass.** Operator checks MIX and 2ND
   on the Manage Code screen and submits. The mix is created (`mix_write` completes on the CNC
   mix service) but no `pgm_edit` operation is ever submitted. Evidence: the service's
   `/jobs/<job>/operations` history for every recent job shows no `pgm_edit` following a
   `mix_write`; operators have been working around it by running edits as a separate submission
   (jobs 681, 592).
2. **Existing mixes are not surfaced clearly.** When a material already has an active mix, MIX
   checkboxes are pre-checked to mirror that mix's membership, and any submission — even an
   edits-only one — forces the Replace/Create dialog.

## Current architecture (unchanged)

- `buildManageCodeActions` (`data/mixservice/ManageCodeOrchestrator.kt`) already orders actions
  per material: `catalog_create | catalog_replace` then `pgm_edits`.
- `MixOperationCoordinator` runs a durable `ManageCodeSession` action list sequentially,
  surviving navigation and process restart. Catalog completion calls `completeCatalogAction`,
  which should hand off to `submitCurrentAction` for the next action.
- Mix service (`C:\Scripts\PGM_BCR_Loader\mix_service\service.py`) treats each as an independent
  async operation. No server change is required by this design.

## Design

### 1. Queue execution (bug fix)

- Per material the queue is: mix step (create or replace, if any MIX change) → second-pass/PUNLOAD
  step (if any edit rows). Materials run one after another in screen order.
- **Root cause first.** Static reading of the coordinator does not reveal the drop, so the first
  implementation task is reproduction:
  - Coordinator test with a fake `MixOperationService`: a session of
    `[catalog_create, pgm_edits]`; the catalog operation completes; assert `submitPgmEdits` is
    called with the queued rows and the session ends `isCompletedSuccessfully`.
  - Screen-level test of session preparation (`buildSession` logic, extracted if needed): MIX +
    2ND selections on a material without a mix produce both a `CATALOG_CREATE` and a `PGM_EDITS`
    action.
  - Fix whichever fails (coordinator hand-off, action construction, or selection state lost
    during the post-catalog rehydrate).
- If the edit step fails after the mix step succeeded, the session stops on the failed step and
  **Retry re-runs only the edit step** (existing `retry()` behavior; the mix is never redone).
- An edits-only submission on a material that already has a mix queues only `pgm_edits` — no
  catalog target is required and no mix dialog is shown.
- The operation panel lists each queued step with state, e.g.
  `Mix — 19mm Pre_Finished ✓`, `2nd pass (6 PGMs) — running`.

### 2. Existing mix display

- Material card header shows one line per active mix:
  `Existing mix: <name> — N PGMs, <compile status>`.
- Rows whose PGM belongs to an active mix show a small `in <name>` tag.
- When a material has an active mix, **all MIX checkboxes start unchecked**. A material with no
  mix keeps today's default (all unlocked rows checked).
- 2ND / SUP / PUNLOAD defaults are unchanged (derived from edit history).

### 3. Prompt on first MIX check

Applies to a material that has at least one active mix. Fires **once per material per screen
session**, on the first MIX box the operator checks.

- Dialog buttons: **Replace Mix**, **Additional Mix**, **Cancel**.
- **Replace Mix**: if more than one active mix exists, the operator picks which. That mix's PGMs
  are auto-checked **except locked rows** (sheet status COMPLETE or RE_NESTED), plus the tapped
  box. Existing archive-before-replace confirmation and `ReplaceActive` revision checks apply.
- **Additional Mix**: name field pre-filled with the next free name (`<Material>Mix 2`,
  `<Material>Mix 3`, …), editable, validated by `isCatalogMixNameAvailable`. Only the tapped box
  is checked.
- **Cancel**: the tapped box reverts to unchecked; no choice is stored, so the next MIX check
  prompts again.
- Unchecking every MIX box on the material clears the stored choice.
- If external mix files are present, the existing "delete external mix first" dialog takes
  precedence and create/replace remain blocked.
- The chosen target feeds the existing `selectedTargets` / `resolveMixGenerationTarget` path, so
  submit no longer opens a second mix dialog for that material. A catalog revision change before
  submit still invalidates the choice and re-prompts (existing `reconcileCatalogTargetAfterRefresh`).

Locked rows are never auto-checked and never included in mix programs or edit rows (existing
`buildManageCodeChange` rule).

## Out of scope

- Server-side combined mix+edit operation.
- Changing mix-name validation or archive behavior.
- Cross-material parallel execution.

## Testing

- Unit: coordinator sequence (catalog → edits), edit failure + retry resumes edits only.
- Unit: action building for mix-only, edits-only (existing mix, no target), mix + edits.
- Unit: selection defaults with an active mix (MIX unchecked) and without; locked rows excluded
  on Replace auto-check.
- Unit: prompt state reducer — first check prompts, Cancel reverts and re-prompts, choice sticks,
  unchecking all clears.
- Manual (release build on tablet): fresh material MIX + 2ND → both `mix_write` and `pgm_edit`
  appear completed in `GET http://192.168.20.4:8477/jobs/<job>/operations`; existing-mix material
  shows header + tags, unchecked MIX boxes, prompt behavior.
