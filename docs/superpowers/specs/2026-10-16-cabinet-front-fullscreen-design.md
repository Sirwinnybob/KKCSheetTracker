# Cabinet front in the full-screen door preview — design

Date: 2026-10-16. Status: implemented and automatically verified; field rollout checks remain in
`docs/superpowers/verification/2026-10-16-cabinet-front-fullscreen.md`.
Spans three programs: CV (a UCS script), CV Cutlist Query (Rust), KKCSheetTracker (Kotlin).

## Goal

Tapping a door in Door Assembly opens the full-screen preview. Today it shows that one door. The
new default view shows the whole cabinet front: the face frame, the cabinet's other doors, drawer
fronts and false fronts, retaining their textures and detail with slight transparency, with the selected door solid and in
full colour. A "Cabinet / Door only" toggle returns to today's view.

Frameless cabinets show the same view without a frame: the face outline and their fronts.

## Rule that shapes everything: real data or nothing

Every rectangle drawn comes from a value CV itself reports. The tablet never works out a
position, an overlay, a gap or a missing piece. A cabinet that cannot be proven complete gets no
cabinet view, and the tablet keeps showing today's door-only view for its doors, with no message
that implies something is wrong. The checks below decide "proven".

## What CV can and cannot give us (verified on job 688, 2026-10-16)

- The report database (`Cabinets`, `Doors`, `Drawers`, `Parts`, `Notes`) holds no position for any
  door, drawer front, opening or frame member. `Parts.Parameters` carries only allowances such as
  `EDX`/`EDY`. The 3D export (`3d.dae`) has positions but its node names do not map to cabinet or
  door numbers. The job note "Overlay" is free text and the hinge catalog overlay applies only to
  hinged doors.
- CV's own UCS scripting does expose the geometry. The existing hinge-notes script already writes
  per-door notes that reach the tablet. A UCS probe read, in decimal inches and unrotated:
  `Cab.Face` (x, y, width, height), every opening `Cab.Face.DOR_OPEN@n` and `DWR_OPEN@n`
  (x, y, width, height), the front inside it (`DOR`, or `DBOX.DWR` for a drawer front, or `DWR`
  directly for a false front) with its offset inside the opening, and every frame member
  (`LS RS TR BR MR MS`, x, y, width, length, three rotations).
- Rails report a rotation of exactly -90 degrees, stiles 0. A member with zero width is CV's
  placeholder for "no such piece"; it has no cut-list row and is ignored.
- Front position in the cabinet = opening position + drawer-box offset (drawer fronts only) +
  the front's own offset. Nothing is rotated in any observed case.

Validation run on job 688 (28 cabinets, 66 doors, 20 cabinets whose fronts carry notes):

| Check | Result |
|---|---|
| Front size equals the report's door size | 65 of 65 |
| No two fronts overlap; no front passes the face by more than 0.7 in | 65 of 65 |
| Frame parts equal the report's face frame parts (type, width less `EDX`, length less `EDY`) | 20 of 20 cabinets |
| Cabinets passing every check below | 18 of 20 |
| Rejected: cabinet 2 | left stile lies outside the face (a 87.5 in stile on a 14.5 in face) |
| Rejected: cabinet 27 | door 53784, a hingeless slab, is in no readable opening, so a door row has no front |
| Cabinets with real empty areas inside the face | 3 (cabinets 8, 13, 21) |

The empty areas are real: cabinet 13 has an open frame opening above its mid rail
(33.75 x 40.875 in) with no door in it. They stay blank, which is correct.

Overlay differs per door and per side, and the notes carry it exactly because each front's
rectangle is reported directly. Job 690 cabinet 9 (an upper, 57 x 38.75 in): the opening is
33.5 in tall at y 2.25, the front is 36.125 in tall at y 0.3125, so the overlay is 1.9375 in
at the bottom and 0.6875 in on the other three sides; the pair of doors beside it show 0.6875
on the outer edges and -0.0625 at the centre (the 1/8 in gap split between two doors). Nothing
in the design assumes an overlay, so mixed overlays need no special case.

Job 690 (13 cabinets with fronts, 32 doors): all 32 doors carry both notes, no errors, 12 of 13
cabinets pass. Cabinet 15 is rejected by rule 4: its top rail is rotated 233.31 degrees and its
right stile is 40.12 in tall, a sloped cabinet. No front overlaps another and none passes the face.

## Components

### 1. CV UCS script: "AI Front Placement Notes v1.3"

Replaces the probe scripts. One entry applied to library parts `DOR` and `DWR`. It writes two
notes on each front's `Doors` row (`Notes`, ParentType 5, ParentID = the door id). The final script
is kept in the CV Cutlist Query repo at `docs/ucs/ai-front-placement-notes.js` as the reference
copy; CV holds the live one.

- `Front Placement v1`: `kind=<OPEN@n>;face=x,y,w,h;front=x,y,w,h;open=x,y,w,h;fronts=<OPEN@n>:<pid>:x,y,w,h|...;openings=<OPEN@n>=x,y,w,h|...`
  The `fronts` list holds every front in the cabinet, so one note describes the whole cabinet and
  a front with no note of its own still appears in the others'. Rectangles are in face
  coordinates, origin at the face's lower left, y up.
- `Frame Members v1`: `order=X,Y,DX,DY,AX,AY,AZ;<CODE>#<pid>=...|...`, raw values, one entry per
  member of `LS RS TR BR MR MS`, de-duplicated by object id.
- On any failure (missing value, a rotation other than zero on a front, opening or the face) it writes
  `Front Placement Error` and no placement note. It never shows a dialog.

### 2. CV Cutlist Query (Rust): validate and publish

Reads the two notes per door and publishes an additive top-level array `cabinetLayouts[]` in
`job_data.json`. `schemaVersion` stays 2. Hours Tracker's reader keeps unknown fields
(`cv_job_contract.py`), so it needs no change. A cabinet is published only if every rule holds
(tolerance 0.002 in):

1. Every front note of the cabinet carries the same face, openings, fronts and frame members.
2. The cabinet's `Doors` rows and its fronts list match one to one by finished width and height,
   so no door is missing and none is invented.
3. Every front lies in a listed opening, and each opening holds at most one front.
4. Frame members have no tilt and a rotation of 0 or -90 degrees only; members of zero width are dropped.
   The one exception is a sloped cabinet: a lone top rail at another angle is accepted only when the
   slope reproduces three separate CV numbers (stile lengths, the rail's origin and its length), and
   then the stiles, rail and openings under it are published as polygons. See the plan's Task 3b and
   `2026-10-16-sloped-door-drawing-design.md`. Job 690 cabinet 15 is the test case.
5. Frame members match the report's face frame parts one to one by type, width less `EDX` and
   length less `EDY` (stiles `LS RS MS`, rails `TR BR MR`).
6. No frame member or opening overlaps another, and each lies inside the cabinet outline: inside the
   face, or for a stile running down through the toe, no lower than the toe height (the face's own
   y). Job 592 cabinets 3 and 15 need this (stiles of 119.5 in and 35.25 in over toes of 73 in and
   3.5 in); their stile lengths equal the report's part lengths.
7. No two fronts overlap.
8. Any rule failing, or any note missing or malformed, publishes nothing for that cabinet.

Frame member rectangles are computed here from the raw values: an unrotated member is
`(X, Y, DX, DY)`; a member at -90 degrees is `(X, Y - DX, DY, DX)`. A member is linked to its
`parts[].id` by the rule 5 match; identical-size parts are interchangeable, assigned in a
stable order. The contract document (`job-data-contract-v2.md`) gets a `cabinetLayouts` section.

```json
"cabinetLayouts": [{
  "cabinet": "4", "room": "1",
  "face": { "width": "65.75", "height": "31.75" },
  "openings": [{ "id": "DWR_OPEN@1", "rect": { "x": "1.5", "y": "25.5", "width": "10.875", "height": "5" } }],
  "fronts": [{ "opening": "DWR_OPEN@1", "doorId": "53732",
               "rect": { "x": "0.8125", "y": "24.8125", "width": "12.25", "height": "6.375" } }],
  "frameMembers": [{ "code": "LS", "part": "489100",
                     "rect": { "x": "0", "y": "0", "width": "1.5", "height": "31.75" } }]
}]
```

`doorId` is set only for fronts that have their own note; others are `null` and are drawn but
cannot be selected. An empty `frameMembers` array means a frameless cabinet.

### 3. KKCSheetTracker (Kotlin)

- Data: `CvJobDoorGeometry.kt` (or a new `CvJobCabinetLayout.kt`) parses `cabinetLayouts` and
  attaches the cabinet's layout to each `DoorPreviewData` whose `cabinet` and `doorId` match a
  front. A layout is dropped if its own numbers fail a light re-check (rectangles finite and
  positive); the exporter is the authority.
- View: `DoorPreviewFullscreen` gains a "Cabinet / Door only" toggle beside "Hidden lines",
  shown only when a layout exists. Default is Cabinet. A new cabinet canvas fits the bounding box of
  the face and every front to the viewport (a front with a large overlay can pass the face edge),
  then draws, back to front: face outline (hairline), frame members (neutral grey, about 45% alpha),
  other proven doors using the full door renderer at 72% whole-door opacity (grain, construction,
  hinges and hidden lines retained), then the selected door using the existing
  `buildDoorDrawing` at full colour at its front rectangle. Empty areas are not painted. Pinch,
  pan and double-tap reset behave as today.
- Tapping: a frame member opens the same panel as a door part, showing its cut-list row (cut size
  and final size via `doorPartSizeLines`, quantity, material). Tapping another front that has a
  `doorId` switches the preview to that door; one without is inert. The selected door's own parts
  can be inspected directly in cabinet mode, with translated rectangle/polygon highlights and the
  same cut/final-size panel as door-only mode. Tapping another door selects it; the next tap inspects
  its parts. Zoom and pan are reversed before both cabinet and door-part hit testing.
- Hidden doors are still real fronts of the cabinet and retain their proven drawing detail at the
  same partial opacity; they remain unavailable for selection. A front without usable door data
  keeps its previous inert outline/fill fallback, and an unproved shape stays blank.
- No change to the navbar, haze or theme code.

## Related change already made (separate, uncommitted)

The tap panel on a door part now shows the cut size, then the final size from the finished
geometry, with tenons on rails and the visible opening for panels. The cabinet view reuses that
panel for frame members.

## Testing

- CV Cutlist Query: fixtures built from job 688 for the passing cabinets 1, 4, 10, 13 and 22 and
  the rejected cabinets 2 and 27, a snapshot per rule 1 to 7 that must fail, and a frameless case.
  `cargo test` plus a round trip through `parseCvJobDoorPreviews`.
- KKCSheetTracker: parser tests against the same published JSON; canvas geometry tests (fit,
  hit-testing of members and fronts); `doorPartSizeLines` for frame members; instrumented
  test for the toggle and for the default view.
- Manual: tablet check on job 688 after re-export, cabinets 1, 4, 13 and 22.

## Rollout

1. Install the v1.3 script in CV (one entry, `DOR` and `DWR`). Disable the probe entries.
2. Release CV Cutlist Query. Re-export each job that should show the view; old exports have no
   `cabinetLayouts` and keep today's view.
3. Release KKCSheetTracker.
4. Update the KKC metadata map and the Hours Tracker audit document to record `cabinetLayouts`
   and the two notes as CV Cutlist Query-owned.

## Open items

- Door 53784 (cabinet 27) sits in a door opening but matches neither `DOR` nor `DWR`. Cabinet 27
  is rejected until its object name is found.
- Roll-out fronts (`ROF`) and drawer boxes other than `DBOX` were not seen on job 688. Any such
  cabinet fails rule 2 and is rejected, so this fails safe.
- Cabinet 2 is rejected because its stile lies outside its face; clipping would be a guess.
- Sloped cabinets now accept the single proved top-rail construction from Task 3b (job 690 cabinet
  15). Other rotations or unproved shapes still omit the cabinet; arbitrary rotations are not inferred.
- Reveal and overlay values are never read; positions already include them.
- Notes stay under 800 characters on this job. A cabinet with very many openings could exceed what
  CV stores in one note; the script then errors and the cabinet gets no view.
