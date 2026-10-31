# Mix screen redesign on the files-only mix service: design

Date: 2026-10-30. Owner: Winston. Status: approved design, before planning.

Depends on: the files-only mix service (`mix-files` API) and, for
second-pass revert only, a new service route (section 6a).

Companion to the service design
`C:\Scripts\PGM_BCR_Loader\docs\superpowers\specs\2026-10-30-mix-service-winxiso-design.md`
(section 7 holds the tablet requirements the owner already approved) and its plan
`C:\Scripts\PGM_BCR_Loader\docs\superpowers\plans\2026-10-30-mix-service-winxiso.md`
(Task 7: the `mix-files` API; Task 8: the legacy routes today's tablet keeps using).

Mockups: `2026-10-30-mix-screen-mockups/landscape.html` (layout A, approved) and
`portrait.html` (option **P2** is the approved one; P1 was rejected). Open them in a
browser. Their "CNC 2" and drag-to-move parts are placeholders for later work.

## Intent

The Manage Code screen is clunky and very basic. The owner wants it rebuilt to
match the modern look of the rest of the app, especially the Settings page:
simple and elegant. At the machine, an operator needs to reorder PGMs easily,
view mix and interrupted-mix revisions easily, and see which sheets are
remaining and which are completed.

The service is being rebuilt so the `.mix` and `.frz` files on the CNC are the
only truth. The tablet must follow: show exactly what is on disk, refresh live,
and edit any mix (including interrupted ones) safely.

The screen must also have room to grow. A second CNC with its own logic and its
own file type is coming later. Materials and single files will move between the
two machines, and mixes will be generated for both.

Success: an operator in portrait at the CNC can open a job, pick a material,
see what is cut and what is left, drag the remaining sheets into a new order,
press Apply, and reload in PanelMac, and every tablet then shows that order
within a few seconds.

## Goals

1. One new **Mixes** screen that replaces `ManageCodeScreen`, styled like
   Settings (white cards, pale-blue selection, uppercase group captions, theme
   tokens).
2. Full edit control of every `.mix` in a material folder, including ones made
   at the CNC. That covers create, reorder, add, remove, changing quantity and
   any other parameter, rename, delete, split, and restoring `OLDn_` and legacy
   revisions.
3. A `.mix` and its companion `.FRZ` show as one mix ("Interrupted") and are
   always edited together.
4. Completed sheets are locked in FRZ edits. When the tablet and the FRZ
   disagree, the user chooses per sheet.
5. Live refresh. The job screen, the Mixes screen and an open sheet viewer
   update within about 3 s of a change on disk, in place.
6. Strict sheet-to-PGM matching with one rule. Nothing is silently dropped.
7. The second-pass controls (PUN, 2ND, SUP) stay on each sheet row, and a new
   material-level dropdown applies them to every file in the material.
8. A layout and data model that can take a second machine later without a
   rewrite.

## Non-goals

- **CNC 2.** Moving materials or files between machines, PUNLOAD stripping and
  restoring on move, decoding CNC 2's file type, and generating CNC 2 mixes are
  later work, and mostly service work. The owner said not to worry about it now.
  Section 9 says what room to leave.
- Changes to how second-pass edits are made (`pgm-edits` stays as it is). The
  one addition is a revert route (section 6a), which is service work this
  build depends on.
- PanelMac integration. The operator still reloads the mix in PanelMac.
- Supporting the old service. The new screen needs the `mix-files` API, which
  the service ships first (section 11).

## Anti-goals (failure even if it technically works)

- The tablet shows an order that is not the order on disk, or keeps showing an
  old order after the disk changed.
- A sheet disappears from the tablet while the CNC still cuts it (unmatched
  entries hidden).
- An FRZ save goes through while the tablet and the FRZ silently disagree about
  what is done.
- An edit sends a revision from a different file or material (the cause of the
  live 2026-09-21 failure).
- An unsaved draft is silently overwritten by live refresh, or silently
  overwrites someone else's newer change.
- The screen looks like a different app from Settings.

## Owner decisions (from this brainstorm)

- **Layout.** Landscape uses option A: a Settings-style left rail of materials
  grouped by machine, with the selected material on the right. Portrait uses
  option P2: materials as a strip of cards across the top, grouped by machine,
  and the sheet list at full width below. Portrait is what CNC operators
  normally use.
- **Second-pass controls** stay on each sheet row where they are now. Each
  material also gets a dropdown of the same checkboxes that applies them to
  every file in the material.
- **Save/Apply button.** Edits collect in a draft and are written only when the
  user presses Apply.
- **Second pass has its own Apply button**, separate from the mix Apply.
- **Second-pass edits must be easy to revert.** The 2nd-pass tool already
  makes backups, so unticking a box should undo the edit (section 6a).
- **Drag materials between machines** is wanted eventually. It is done by the
  service later: the files move from one CNC to the other, PUNLOAD is stripped
  going to CNC 2 and put back going to CNC 1.
- The owner accepted the playback of the screen and of the draft/refresh
  behaviour (section 6), including the conflict banner.

Carried in from the service spec (already approved): full edit control,
companion pairs edited together, locked states `COMPLETE`, `HAS_BAD_PARTS` and
`RE_NESTED` (`SKIPPED` is not locked), an A/Z page locks both PGMs, per-sheet
mismatch choice, ~3 s foreground polling with in-place viewer updates, strict
matching that blocks FRZ saves, and per-file `expectedRevision`.

## Design

### 1. Where it lives

- The Job Detail "Manage code" pill opens the new Mixes screen (both
  `NavGraph.kt` call sites that build `ManageCodeScreen` today). With a material
  argument it opens with that material selected.
- `ManageCodeScreen`, `ExistingMixChoiceDialog`, `MixActionDialog` and
  `MixChoiceState` are replaced. Keep any logic worth reusing (thumbnail
  loading, `buildManageCodeRows`, `defaultMixName`, edit-history-derived
  checkbox state).

### 2. Layout

**Landscape (≥ ~1000dp wide; the tablet is 1318dp).** Mockup `landscape.html`.

- Top bar: back, job name, and a CNC status chip from the existing
  `MachineStatusStore` (online/idle/cutting).
- Left rail (~210–240dp, the Settings rail look): a card with a caption per
  machine ("SCM CNC") and one item per job material. Each item shows a status
  chip: sheets left (orange if the mix is interrupted), "done", "no mix", or
  "no PGMs" (greyed, like today's "No PGMs on this CNC").
- Right pane: material header, mix tabs, mix card, sheet list (sections 3–5).
- The revisions panel is a right-side drawer.

**Portrait (the tablet is 824dp wide).** Mockup `portrait.html`, option P2.

- The same top bar.
- A material strip card under it: a caption per machine and the materials as
  small tiles (name + status chip), wrapping onto more rows if needed.
- Below it, the same material header, mix tabs, mix card and sheet list at full
  width, with bigger thumbnails and checkboxes.
- The revisions panel is a bottom sheet.

Both layouts sit above the app's floating navbar, as Settings does. Follow
`LocalLowEndMode` and the theme tokens (`KKCThemeTokens`, `LocalKKCStatusColors`,
`kkcCardDepth`), and work in dark mode and with the team themes.

### 3. Material header and second-pass dropdown

- Material name with a subtitle ("15 sheets · 1 mix"), a **Second pass ▾**
  button, and an overflow menu.
- The dropdown lists **Remove P-unload**, **2nd pass** and **Super pass**,
  captioned "Apply to all N files". Each checkbox shows its state across the
  material's files: on, off, or partial with a count ("4 of 15"). Super pass is
  only available with 2nd pass on, as on the rows today.
- Changing it sets the matching checkbox on every unlocked row. Locked
  (completed) rows are not changed. The change joins the second-pass draft
  (section 6a). Unticking reverts the edit on every unlocked file.

### 4. Mix tabs and the mix card

- One tab per active mix in the material folder, plus **+ New mix**. A mix and
  its companion FRZ are one tab with an orange **Interrupted** badge. An FRZ
  without a same-name `.mix` is its own interrupted tab.
- A file the service could not decode (`error`) shows as a tab with an error
  badge. It opens to the error message with Delete and Restore-from-revision
  only.
- A companion pair whose entry lists disagree (`companionMismatch`) shows a
  warning on the tab and in the card. Show both lists side by side on request.
  Editing it is allowed, and the pair edit makes both files match again.
- Mix card: a progress bar and "6 of 15 cut · 9 remaining · updated just now",
  then **Rename**, **Split**, **Delete**, **Revisions · n**, **Discard** and
  **Apply** (the last two only while a draft exists; Apply shows the number of
  changes). In portrait, the less-used actions go into an overflow menu.
- "Cut" comes from the tablet's sheet status, the owner's source of truth. For
  an interrupted mix, the FRZ's remaining count is shown on each row when it
  differs (section 7).

### 5. Sheet list

One card with up to four groups, each with an uppercase caption:

1. **Remaining · drag to reorder.** Each row has a drag handle, a thumbnail,
   the PGM name(s) (A/Z pages show both, "R870108N + R870109N"), "Sheet n · qty
   q", the PUN/2ND/SUP checkboxes, and an overflow menu (**Edit quantity and
   parameters**, **Remove from mix**, **Move to new mix…**).
2. **Unmatched.** This is an entry whose PGM matches no sheet in the job's
   sidecar. It is shown in its mix position with a red "Unmatched" chip and the
   PGM name, never dropped. It can be reordered and removed. While any unmatched
   entry exists in an interrupted mix, Apply is disabled with the reason shown.
   A `.mix`-only save is still allowed. Show `pgmExists == false` ("PGM file
   missing") and `headerDrift` ("needs refresh") as chips on the row.
3. **Not in this mix.** These are the material's sheets that are in no entry of
   this mix, each with **Add**. A sheet that is already in another active mix of
   the same material says so ("in 19mm Pre_FinishedMix 2").
4. **Completed · locked.** These are sheets whose tablet status is `COMPLETE`,
   `HAS_BAD_PARTS` or `RE_NESTED`, each with a lock icon and a status chip. They
   can't be dragged. In an interrupted mix they can't be edited at all. On save
   the service writes every locked entry first, in its original relative order,
   followed by the pending entries in the new order (service plan Task 5
   `apply_edit`). After the save the tablet shows exactly what the catalog
   returns. In a plain `.mix` they stay in the file at their positions, and the
   row menu offers only **Remove from mix**.

**Edit quantity and parameters** opens a bottom sheet with quantity (`R`) and
every other entry parameter as a text field, in source spelling, saved into the
draft. Unknown parameters are shown and kept as they are. This is for a plain
`.mix`. On an interrupted mix the service API takes only order, membership,
`quantity` and `remaining` (service plan Task 5), so the sheet there shows
**Quantity** and **Left** only. Changing Quantity sets Left to the same value
unless Left was edited, and Left can't exceed Quantity. Add, remove and count
changes on an interrupted mix are behind the service's switches. When the
service rejects one with `frz_edit_disabled`, the draft is kept (section 6).
Changing other parameters of an interrupted mix needs a later service change.

**A/Z pages.** Matching is per entry, so an A entry and a Z entry each match
the same page. When the two sit next to each other with the same quantity
(how the tablet writes them), they show and drag as one row. Otherwise, as can
happen in a CNC-made mix, each shows as its own row labelled "Sheet 9 A" /
"Sheet 9 Z". The tablet never regroups them unless the user moves them.

**Split.** A multi-select mode on the remaining rows, then **Move to new
mix…**. That asks for a name (default `<Material>Mix 2`, the existing
`defaultMixName` rule) and stages a draft for both the new and the reduced
mix. Apply sends one `mix-files/split`.

**New mix.** A new tab starts with every sheet in the material that is not in
another active mix, in natural sheet order. The user removes, reorders and
names it, then presses Apply (`mix-files/write` with `expectedRevision: null`).
The default name for the first mix is `defaultMixName(material)`.

### 6. Draft, Apply and live refresh

- **Mix draft.** Reorder, add, remove, quantity and parameter edits, and split
  collect in a draft for **one mix** (the open tab). A split draft spans the
  original mix and the new mixes. **Apply** lights up with a change count, and
  Discard drops the draft. Leaving the screen, or switching tab or material,
  with a draft asks first. Second-pass changes are not part of this draft;
  they have their own button (section 6a).
- **Apply** sends one mix write:
  - `mix-files/write`
  - `mix-files/frz` for an interrupted mix (for a companion pair the service
    writes both)
  - or `mix-files/split`

  It carries the `revision` of that exact file from the catalog the draft was
  built on. FRZ and pair writes also carry the tablet's completion
  (`completedPgms` at PGM level, with both PGMs of a locked A/Z page) and any
  `resolutions`.
- After a successful FRZ or pair save, show "Reload this mix in PanelMac".
- **Errors** (codes from the service plan, Tasks 4, 5 and 7):
  - `revision_conflict`: the conflict banner below.
  - `frz_completion_mismatch`: the mismatch dialog (section 7).
  - `machine_busy_with_mix`: "The CNC is cutting this mix. Pause it first."
  - `frz_edit_disabled`: "Adding / removing / count changes on interrupted mixes
    are switched off on the CNC". Name the edit, and keep the draft.
  - `missing_program`, `name_exists`, `invalid_entries`, `mix_unreadable`,
    `companion_write_failed`, `not_found`: a plain message, and keep the
    draft.
- **Live refresh.** While the Mixes screen, the Job Detail screen or a sheet
  viewer is in the foreground, poll `GET mix-files/revision` for each material
  on screen about every 3 s. Fetch `GET mix-files` only when `folderRevision`
  changes, and stop polling in the background.
  - With no draft, the screen updates in place and keeps the scroll position.
  - **With a draft**, a yellow banner says "This mix changed on the CNC" with
    **Load theirs** (drop the draft) and **Re-apply my changes on top** (rebuild
    the draft against the new file, for example the same order of remaining
    PGMs). Apply is blocked until the user picks one. The same banner shows
    after a `revision_conflict`.
- **Revisions panel.** Lists the current file and then every `OLDn_` and legacy
  revision of the mix, newest first: `OLDn_` by `revisionNumber` descending,
  then legacy `name — stamp` archives by stamp. For a pair it has two lists,
  **.mix** and **.FRZ**, because their numbers drift apart (`.mix`-only edits,
  and PanelMac's own FRZ writes make no `OLDn_` copy). Each shows its file time
  when the catalog provides one (an optional `modifiedAt` the service may add;
  otherwise the `OLDn` number or the legacy stamp)
  and a short summary the tablet computes by comparing it with the next newer
  one ("reordered", "2 sheets added", "made at CNC" for the oldest). **View**
  shows the revision's order beside the current order.
- **Restore loads the revision into the draft.** The user then presses Apply,
  so restore follows the normal write rules.
  - For a plain `.mix` the draft is the revision's entries, exactly as stored.
  - For a pair, a `.mix` revision's order is applied to the pending sheets
    through the normal pair edit. Locks and completion stay current, so an old
    revision can never bring back a sheet that is already cut. `.FRZ`
    revisions are view-only.
  - `mix-files/restore` (a raw file copy) is used only when the current file
    can't be decoded (`error`).

  Permanently deleting a revision is a separate, confirmed action in the
  overflow menu.

### 6a. Second-pass edits and revert (owner decision: separate button)

- **Second-pass draft.** The PUN/2ND/SUP checkboxes on the rows and the
  material dropdown (section 3) collect in a second-pass draft for the
  material. It is separate from the mix draft and has its own **Apply 2nd
  pass (n)** button and Discard, shown in the sheet-list header. Switching
  material or leaving with this draft asks first. Mix conflicts and live
  refresh of the mix never block it.
- **Checkboxes work both ways.** A box shows the PGM's current state from the
  edit history (`pgm-edits` read, `current.mode` / `punloadRemoved`). Ticking
  adds an edit, and unticking takes it off. Once applied, the row shows what
  the PGM now is.
- **How Apply 2nd pass works.** For each changed PGM, compare the wanted state
  with the current one:
  - **Only additions** (for example none → 2nd pass, standard → super, or
    P-unload removed on top): send `pgm-edits` as today.
  - **Anything taken off** (for example 2nd pass → none, super → standard, or
    put P-unload back): revert the PGM to its **original** (the backup taken
    before its first recorded edit). If the wanted state still has edits, send
    `pgm-edits` for them.
- **Revert needs a new service route.** The 2nd-pass tool already keeps what
  revert needs. Each API edit copies the PGM to
  `BACKUP_<time>_<id>\<pgm>` in the material folder, and it records `backup`,
  the before/after signatures and the result in `.pgm_edit_history.json`
  (`G_Code_2nd_Pass\edit_gcode_cnc_bundled.py:_api_backup_dir`,
  `pgm_edit_history.py`). There is no route that copies a backup back today.
  The tablet needs one, for example:
  `POST /jobs/{job}/materials/{material}/pgm-edits/revert` with
  `{requestId, files: [{name, to: "original"}]}`. It returns the same outcome
  shape as `pgm-edits`. The service must:
  - copy the backup over the PGM (keeping a fresh backup of the current
    bytes first)
  - append a history event, so the ledger's `current` goes back to the
    original state
  - refuse with a clear code (for example `pgm_changed`) when the PGM's
    current signature doesn't match the ledger's last `afterSignature`,
    meaning the PGM was re-exported since. Restoring then would bring back
    stale code.
  - refuse with `no_backup` when there is no recorded original

  That route is service work (`PGM_BCR_Loader`, and possibly the 2nd-pass
  ledger code). It is outside this tablet build and is a dependency of it.
  Until it exists, the tablet shows applied edits ticked and greyed out with
  "Revert needs a service update".
- **Per-row history.** The row menu gets **2nd-pass history**: the ledger
  events (time, before → after, actor) and **Revert to original**.
- Locked (completed) rows show their second-pass state but can't be changed,
  as today.

### 7. Interrupted mixes (FRZ)

- Locked rows come from the tablet's sheet status (`COMPLETE`, `HAS_BAD_PARTS`
  and `RE_NESTED`). A locked A/Z page locks both PGMs, and a locked PGM locks
  every repeat in the mix.
- Each row of an interrupted mix also shows the FRZ's `remaining`. When the
  tablet and the FRZ disagree, the row shows both ("Tablet: Complete · FRZ: 1
  left") before any save.
- **Mismatch dialog.** It opens on `frz_completion_mismatch`, or earlier if the
  tablet already sees mismatches when Apply is pressed. It lists each sheet with
  its tablet state and FRZ state side by side and a per-sheet choice
  (**Tablet** / **FRZ**). Confirming resends with `resolutions`.
- Choosing **FRZ** for a sheet only affects that save. It does not change the
  sheet's status on the tablet.
- Apply is blocked while any entry in the pair is unmatched (section 5).

### 8. One matching rule, and the job screen and viewer

- Matching considers every page that has sheet files, including pages hidden
  in the app, excluded from tracking, or part-list continuations. Those pages
  show a "hidden page" chip. They lock only if their tablet status is a locked
  state, and otherwise count as not cut. That keeps a hidden page from showing
  as unmatched and blocking FRZ saves. When several pages carry the same PGM
  (a part-list continuation repeats its head's files), the entry matches the
  visible head page first, and continuation pages are used only when no other
  page matches.
- One function maps catalog entries to sheet pages for the whole app:
  - An entry matches a page when the entry's PGM file name equals one of the
    page's `pgmFiles` (case-insensitive, the `buildManageCodeRows` rule).
  - The A/Z pair is one row, keyed on both files.
  - The result keeps the mix order, with repeats, and returns unmatched entries
    as their own items.
- It replaces `pagesForMix`, `resolveMixPageOrder` and `reorderVisiblePages`
  for mix use.
- **Job Detail.** It lists one row per active mix per material, as today. It
  refreshes live (section 6), shows `error`, unmatched and needs-refresh states
  on the row, and stops treating an empty match as "unavailable". It shows the
  unmatched count instead.
- **Sheet viewer.** The route carries the job, material and mix file name, not
  a frozen page list (`mixPages` in `viewerRoute`). While open, the viewer
  observes that mix's catalog and updates its page order in place, keeping the
  current page. If the mix is deleted or renamed while open, the viewer falls
  back to the material's natural order and says so. Unmatched entries appear in
  the viewer's sheet navigator as unmatched rows.

### 9. Room for CNC 2 (structure only)

- The data layer is keyed by machine: a `CncMachine` (id, display name, service
  endpoint, capabilities) and per-machine repositories. Today there is one
  machine, the SCM, built from the current mix-service endpoint.
- The rail and the portrait strip group materials under a machine caption. With
  one machine only one group shows, and no empty "CNC 2" placeholder.
- Machine capabilities gate features (for example `frzEditing`, `secondPass`,
  `moveMaterial`). Drag-a-material-to-another-machine is not built now. The
  layout keeps a drop target per machine group, so it can be added once the
  service supports moves.

### 10. Data layer

- A new client for the `mix-files` API (service plan Task 7), returning typed
  models for the catalog (`files`, `entries`, `revision`, `companion`,
  `companionMismatch`, `error`, `remaining`, `pgmExists`, `headerDrift`,
  revision fields) and typed errors for each code.
- The writes are synchronous, so `MixOperationCoordinator` and its operation
  polling are no longer needed for mix writes. Second-pass PGM edits keep using
  their existing path.
- One catalog repository per (machine, job, material) holds the last catalog
  and its `folderRevision`, runs the foreground poller, and exposes a flow that
  the Mixes screen, Job Detail and the viewer all observe. Keep an offline copy
  so screens still show the last known order when the service is unreachable,
  marked as offline.
- Remove the tablet code paths that only serve definitions-era routes once
  nothing calls them (`listMixes`, `getMix`, `getPgmConflicts`, `submitMix`,
  catalog create/replace, external delete and their models), along with the
  coordinator's mix-operation session store if nothing else uses it.

### 11. Rollout

- The service ships first with both the `mix-files` API and the legacy routes,
  so the current tablet keeps working (service plan Task 8). This tablet build
  only talks `mix-files`.
- If the service answers 404 on `mix-files` (old service), the Mixes screen
  shows "The CNC mix service needs updating" and stays read-only from the last
  cached data. Job Detail falls back to natural order.
- Second-pass revert needs the service's `pgm-edits/revert` route (section
  6a). The tablet reads `GET /status` and treats revert as available only when
  its `features` list contains `"pgm-edits-revert"`, which the service adds with
  the route. Probing with a POST is unsafe once the route exists. Until then it
  greys out applied edits, so the tablet can ship before the route does.
- Ships through the usual updater after the service's CNC acceptance check.

## Constraints

- Kotlin + Jetpack Compose (Material 3) in `C:\Scripts\KKCSheetTracker`, the
  existing theme system, and the reorderable list library the current screen
  uses.
- Tablet: 824dp wide in portrait and 1318dp in landscape. Low-end mode must
  stay smooth (no heavy blur or shadow work in long lists).
- LAN only, no auth on the service. The CNC may be offline, and the tablet must
  never block the UI on the network.
- Machine source is Latin-1. The tablet treats PGM names and parameters as
  opaque strings and sends parameters back unchanged unless the user edited
  them.

## Left to the builder

- Exact Compose structure, file names, and how much of the old screen's code is
  reused.
- The breakpoint between the two layouts, and the exact rail and tile sizes.
- The poll interval around 3 s, and whether polling is shared per job.
- Wording of messages and empty states.
- How the revision summaries are computed and worded.
- Whether "Re-apply my changes on top" can always rebuild the draft. If it
  can't (for example the reordered PGMs were removed), show what was dropped.

## Calls made by Claude, not by the owner

- The new screen is called **Mixes** and replaces Manage Code entirely.
- Multiple mixes in one material show as tabs. An FRZ-only file is its own
  interrupted tab.
- The material-level second-pass dropdown skips locked rows.
- A mix draft covers one mix at a time (a split covers its mixes). The
  second-pass draft covers the material.
- Taking off any second-pass edit reverts the PGM to its original, then
  re-applies whatever edits are still wanted. There is no step-by-step undo.
- The revert route's shape and its `pgm_changed` and `no_backup` refusals.
  Until the route exists, applied edits are shown greyed out.
- On an interrupted mix the row editor offers Quantity and Left only, because
  the service's FRZ edit takes nothing else.
- A/Z entries that are adjacent with the same quantity show as one row.
  Otherwise each shows on its own row.
- Hidden, excluded and continuation pages count for matching.
- Restore loads a revision into the draft. For a pair, a `.mix` revision's
  order goes through the normal pair edit, and `.FRZ` revisions are view-only.
  The raw `mix-files/restore` is used only for undecodable files.
- The tablet shows the order the service writes after an FRZ save: locked
  entries first, then pending.
- Unmatched entries block only FRZ and pair saves. A plain `.mix` save is
  allowed.
- For a plain `.mix`, completed sheets are listed as locked but stay in the
  file at their positions. They can't be dragged; the row menu can remove them.
- Choosing "FRZ" in the mismatch dialog doesn't change tablet sheet status.
- A new mix starts with every sheet not in another active mix, in natural
  order.
- The revision summaries are computed on the tablet by comparing revisions.
- The viewer route carries the mix file name and observes the catalog, instead
  of a frozen page list.
- The new tablet build talks only to the `mix-files` API. On an old service it
  shows "needs updating" and stays read-only.
- The CNC 2 placeholder is hidden while only one machine exists.
