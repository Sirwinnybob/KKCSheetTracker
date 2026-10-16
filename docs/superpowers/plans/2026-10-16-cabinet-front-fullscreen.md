# Cabinet Front in the Full-Screen Door Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapping a door in Door Assembly shows its whole cabinet front (frame, other fronts greyed, selected door solid), built only from positions CV itself reports.

**Architecture:** A CV UCS script writes two notes per front. CV Cutlist Query (Rust) parses and strictly validates them, publishing an additive `cabinetLayouts[]` array in `job_data.json` only for cabinets that pass every rule. KKCSheetTracker parses that array and draws the cabinet view; a cabinet without a layout keeps today's door-only view.

**Tech Stack:** CV UCS (JavaScript), Rust (`serde`, `cargo test`) in `C:\Scripts\CV Cutlist Query`, Kotlin/Jetpack Compose (JUnit, Compose UI tests) in `C:\Scripts\KKCSheetTracker`.

**Spec:** `docs/superpowers/specs/2026-10-16-cabinet-front-fullscreen-design.md` (read it first; this plan only records decisions it leaves open).

## Global Constraints

- Tolerance for every geometry comparison: **0.002 in**. All numbers are decimal inches.
- The contract stays additive: `schemaVersion` stays **2**; new top-level key `cabinetLayouts`; numbers published as decimal-inch **strings** like the rest of the contract.
- Face coordinates: origin at the face's lower left, y up. Member rotations **0** and **-90** (equivalently 270) are accepted as plain rectangles. The one other case accepted is a **sloped cabinet** (Task 3b): exactly one top rail `TR` at another angle, proven by the checks there. Any tilt (AX/AY), any other angle, or a sloped cabinet that fails a Task 3b check rejects the cabinet.
- Frame codes and CV part ids: `RS`=1, `LS`=2, `TR`=3, `BR`=4, `MS`=5, `MR`=6. A member of zero width is CV's placeholder and is ignored.
- Frame part match: type, `width - EDX`, `length - EDY` from the part's `parameters` (`EDX=#M0.062500` form), compared to the member's `DX`, `DY`.
- Never infer: a cabinet that fails any rule publishes nothing; the tablet then shows only today's door-only view, with no warning text.
- Rendering: frame members and non-selected fronts grey at about **45%** alpha, no inner detail; selected door solid at full colour; empty areas unpainted; canvas fits the bounding box of the face and all fronts (fronts can pass the face edge).
- Tablets run release builds; unit tests via `.\gradlew.bat testDebugUnitTest`. Rust via `cargo test`.

## Review Focus

- An operator-hidden door still counts as a door of its cabinet (its row and front stay in the checks) — test in Task 4.
- A cabinet with a `Front Placement Error` note, a missing `Frame Members v1` note, or only an old-version note (`Front Placement v0`) is omitted, never half-published — tests in Task 3.
- Fronts of one cabinet whose notes disagree (a regeneration caught half way) omit the cabinet — Task 3.
- A phase export contains layouts only for the phase's cabinets — Task 4.
- The tablet meets an old file with no `cabinetLayouts`, a layout naming a door that is not in the list, or a non-finite rectangle — no crash, no toggle (or an inert front), door-only view — Tasks 5 and 8.
- A sloped cabinet is published only when the slope reproduces three separate CV numbers (stile lengths, top rail origin, top rail length); a tampered number omits it — Task 3b.
- The cabinet's angle and each door's angle are independent real data: the cabinet's comes from its face frame, a door's from its own parts. A door may be straight in an angled cabinet, angled in a straight one, or angled differently from the cabinet. No rule compares them — Tasks 5, 7 and 8.
- A front's shape comes only from its own door (rectangle when the door's stiles match, the verified outline when the door is angled). A front whose shape cannot be proven (a refused angled door, or a front with no door row inside a sloped cabinet) is **left blank**, never drawn as a rectangle, and the rest of the cabinet still shows — Tasks 5 and 7.
- An opening with no door (including a sloped one) is real empty space: it is not painted, and the frame members around it show its shape — Tasks 3b and 7.

## Prerequisites and working-tree notes

- Part A and Part B only share the JSON contract below, so Part A can ship before Part B.
- The KKCSheetTracker tree holds uncommitted door-preview work. The **sloped-door preview is built** (spec `2026-10-16-sloped-door-drawing-design.md`, 20 tests, uncommitted) and Part B builds on it: `DoorSlope` (`None`, `Refused(reason)`, `Verified(outline, members, highOnLeft, slopeDegrees)`) on `DoorGeometry.slope`; `DoorShape.Poly`; `DoorDrawing.outline` and `.refused`; `DoorPartRegion.polygon`; `pointInDoorPolygon` and `boundsOf` in `DoorPartInspection.kt`; `drawGrainAlong` and `polygonPath` in `DoorPreviewCanvas.kt`. It also edits `DoorPreviewFullscreen.kt`, `DoorPartInspection.kt` and `DoorPreviewCanvas.kt`. Start Part B only after that work is committed (or from a worktree cut from it), and `git add` only the files each task names.
- Fixtures: real job 690 data is saved in this repo at `docs/superpowers/plans/fixtures/job690_front_notes.json` (per door: id, cabinet, type, label, width, height, `placement` and `frame` note text) and `job690_frame_parts.json` (report face frame parts with `parameters`). Cabinets 4-14 and 17 are rectangular and pass every plain rule. **Cabinet 15 is sloped** (top rail at 233.3103 degrees, stiles 58 and 40.1176, doors 1A sloped, 1B and 1C rectangular) and must **publish as a sloped cabinet** under Task 3b; its numbers are in the sloped-door spec. Cabinets 8, 13 and 17 have real empty areas; cabinet 9 has a 1.9375 in bottom overlay. If cabinet 15 fails a Task 3b rule when the fixture test first runs, the test names the rule: report it, do not loosen a tolerance.

## Contract published by Part A and read by Part B

```json
"cabinetLayouts": [{
  "cabinet": "9", "room": "1",
  "face": { "width": "57", "height": "38.75" },
  "openings": [{ "id": "DOR_OPEN@1", "rect": { "x": "1.5", "y": "2.25", "width": "17", "height": "33.5" } }],
  "fronts": [{ "opening": "DOR_OPEN@1", "doorId": "53737",
               "rect": { "x": "0.8125", "y": "0.3125", "width": "18.375", "height": "36.125" } }],
  "frameMembers": [{ "code": "LS", "part": "<parts[].id>",
                     "rect": { "x": "0", "y": "0", "width": "1.5", "height": "38.75" } }]
}]
```
`doorId` is null for a front with no note of its own. Empty `frameMembers` means frameless.

**Sloped cabinets (Task 3b)** add, only for a cabinet that passed the slope checks:
```json
"slope": { "degrees": "36.6897", "highOnLeft": true },
"frameMembers": [{ "code": "TR", "part": "<parts[].id>",
                   "rect": { "x": "0.6015", "y": "38.2", "width": "24.26", "height": "17.48" },
                   "polygon": [ { "x": "1.5", "y": "55.0117" }, { "x": "24", "y": "38.0447" },
                                { "x": "24", "y": "39.9153" }, { "x": "1.5", "y": "56.8824" } ] }],
"openings": [{ "id": "DOR_OPEN@1", "rect": { "...": "bounding box as CV reports it" },
               "polygon": [ "... four corners, top edge on the rail's lower line ..." ] }]
```
`rect` is always the bounding box of the piece; `polygon` (four corners, door inches, face coordinates) is present only for a piece that is not an upright rectangle: the two stiles (top cut along the slope), the sloped top rail (a parallelogram with vertical ends) and any opening whose top touches that rail. Fronts keep only their bounding `rect`; the tablet draws a sloped front from that door's own verified outline (sloped-door spec), never from the bounding box. The numbers in the example are illustrative; the fixture test pins the real ones.

## File Structure

CV Cutlist Query (`C:\Scripts\CV Cutlist Query`):
- Create `docs/ucs/ai-front-placement-notes.js` — reference copy of the live UCS script.
- Create `src/cabinet_layout/mod.rs` — output types, `evaluate`, `build`, rules.
- Create `src/cabinet_layout/notes.rs` — note text parsing only.
- Create `src/cabinet_layout/slope.rs` — Task 3b: slope proof, member and opening polygons, convex polygon overlap area.
- Modify `src/lib.rs` (add `pub mod cabinet_layout;`), `src/job_data.rs` (field + wiring), `src/door_geometry.rs` (`parameters` and `inches` become `pub(crate)`), `docs/job-data-contract-v2.md`.
- Create `tests/cabinet_layout.rs`; copy the two fixtures into `tests/fixtures/`.

KKCSheetTracker (`C:\Scripts\KKCSheetTracker\app\src`):
- Create `main/.../data/cvjob/CvJobCabinetLayout.kt` — model and parser.
- Modify `main/.../data/cvjob/CvJobDoorGeometry.kt` — `DoorPreviewData.cabinetLayout`, call parser.
- Create `main/.../ui/doorpreview/CabinetFrontGeometry.kt` — fit and hit-testing (pure).
- Create `main/.../ui/doorpreview/CabinetFrontCanvas.kt` — drawing.
- Modify `DoorPreviewCanvas.kt` (`drawDoor` becomes `internal`), `DoorPartInspection.kt`, `DoorPreviewFullscreen.kt`, `DoorAssemblyList.kt`.
- Tests: `test/.../data/cvjob/CvJobCabinetLayoutTest.kt`, `test/.../ui/doorpreview/CabinetFrontGeometryTest.kt`, additions to `DoorPartInspectionTest.kt`, `androidTest/.../DoorPreviewFullscreenTest.kt`; fixture `test/resources/cvjob/job_data_690_cabinet9_layout.json`.

---

## Part A — CV and CV Cutlist Query

### Task 1: Reference copy of the UCS script

**Files:** Create `docs/ucs/ai-front-placement-notes.js` (CV Cutlist Query repo); modify `docs/usage.md` (one section).

**Interfaces:** Produces the note text formats parsed in Task 2.

- [ ] **Step 1:** Export the live script text, read-only: `sqlcmd -S .\CV24 -U claude -P SuperSecure -d CVData` selecting `Code` from `dbo.UCS` where `Name LIKE '%Front Placement%'` (it begins `// AI Front Placement Notes v1.3`). Save it byte for byte as `docs/ucs/ai-front-placement-notes.js`. Do not edit CV.
- [ ] **Step 2:** Add a "Front placement notes" section to `docs/usage.md`: install as one UCS entry (Add, Part ID, library parts `DOR` and `DWR`), what the two notes mean, and that jobs must be regenerated and re-exported.
- [ ] **Step 3:** Run `node --check docs/ucs/ai-front-placement-notes.js`. Expected: no output (syntax valid).
- [ ] **Step 4:** Commit: `git add docs/ucs docs/usage.md && git commit -m "docs: keep a reference copy of the front placement UCS"`.

### Task 2: Parse the two notes

**Files:** Create `src/cabinet_layout/notes.rs`, a stub `src/cabinet_layout/mod.rs` (`pub mod notes;`); modify `src/lib.rs`; test in `tests/cabinet_layout.rs`.

**Interfaces — Produces** (all in `cabinet_layout::notes`):
```rust
pub const FRONT_PLACEMENT: &str = "Front Placement v1";
pub const FRAME_MEMBERS: &str = "Frame Members v1";
pub const FRONT_ERROR: &str = "Front Placement Error";
#[derive(Debug, Clone, Copy, PartialEq)] pub struct FRect { pub x: f64, pub y: f64, pub w: f64, pub h: f64 }
#[derive(Debug, Clone, PartialEq)] pub struct FrontEntry { pub opening: String, pub pid: String, pub rect: FRect }
#[derive(Debug, Clone, PartialEq)] pub struct FrontNote {
    pub opening: String, pub face: FRect, pub front: FRect, pub open: FRect,
    pub fronts: Vec<FrontEntry>, pub openings: Vec<(String, FRect)> }
#[derive(Debug, Clone, PartialEq)] pub struct RawMember {
    pub code: String, pub pid: String,
    pub x: f64, pub y: f64, pub dx: f64, pub dy: f64, pub ax: f64, pub ay: f64, pub az: f64 }
pub fn parse_front_note(text: &str) -> Option<FrontNote>;
pub fn parse_frame_note(text: &str) -> Option<Vec<RawMember>>;
```

- [ ] **Step 1: Write failing tests** in `tests/cabinet_layout.rs`:
  - `parses_a_front_note`: `"kind=DWR_OPEN@1;face=0,3.5,65.75,31.75;front=0.8125,24.8125,12.25,6.375;open=1.5,25.5,10.875,5;fronts=DWR_OPEN@1:20502:0.8125,24.8125,12.25,6.375|DOR_OPEN@1:20201:1,2,3,4;openings=DWR_OPEN@1=1.5,25.5,10.875,5|DOR_OPEN@1=1,2,3,4"` gives `opening == "DWR_OPEN@1"`, `fronts.len() == 2`, `fronts[0].pid == "20502"`, `openings[1].0 == "DOR_OPEN@1"`.
  - `parses_a_frame_note`: `"order=X,Y,DX,DY,AX,AY,AZ;LS#230=0,0,1.5,31.75,0,0,0|TR#220=1.5,31.75,1.25,60.75,0,0,-90"` gives two members, `members[1].code == "TR"`, `members[1].az == -90.0`.
  - `empty_frame_note_is_a_frameless_cabinet`: `"order=X,Y,DX,DY,AX,AY,AZ;"` gives `Some(vec![])`.
  - `rejects_garbage`: `parse_front_note` returns `None` for `""`, a note missing `fronts=`, a number `NaN` or `inf`, and a rect with three numbers; `parse_frame_note` returns `None` for a member with six numbers.
- [ ] **Step 2:** Run `cargo test --test cabinet_layout`. Expected: FAIL (module missing).
- [ ] **Step 3:** Implement the two functions in `notes.rs`. Keys may appear in any order; unknown keys are ignored; all six keys of the front note are required; every number must parse as a finite `f64`.
- [ ] **Step 4:** Run `cargo test --test cabinet_layout`. Expected: PASS.
- [ ] **Step 5:** Commit: `git add src/cabinet_layout src/lib.rs tests/cabinet_layout.rs && git commit -m "feat(cabinet-layout): parse front placement and frame member notes"`.

### Task 3: Validate a cabinet and build the layout

**Files:** Modify `src/cabinet_layout/mod.rs`, `src/door_geometry.rs` (make `parameters` and `inches` `pub(crate)`); test in `tests/cabinet_layout.rs`; copy fixtures to `tests/fixtures/`.

**Interfaces — Consumes:** Task 2 types; `domain::{SourceSnapshot, SourcePart, DoorNote, Door}`; `door_geometry::Rect` (string rect with `x,y,width,height`).
**Produces:**
```rust
#[derive(Debug, Clone, Serialize, PartialEq)] #[serde(rename_all = "camelCase")]
pub struct CabinetLayout { pub cabinet: String, pub room: String, pub face: Size,
    pub openings: Vec<OpeningOut>, pub fronts: Vec<FrontOut>, pub frame_members: Vec<MemberOut> }
pub struct Size { pub width: String, pub height: String }
pub struct OpeningOut { pub id: String, pub rect: door_geometry::Rect }
pub struct FrontOut { pub opening: String, pub door_id: Option<String>, pub rect: door_geometry::Rect }
pub struct MemberOut { pub code: String, pub part: String, pub rect: door_geometry::Rect }
#[derive(Debug, Clone, PartialEq)]
pub enum Reject { ErrorNote, NotesDisagree, DoorsDoNotMatchFronts, FrontOutsideOpenings,
    UnsupportedRotation, FramePartsDiffer, OutsideFace, Overlap }
// A cabinet is considered only when some door carries a `Front Placement v1` note.
// A door with that note but no `Frame Members v1` note is `NotesDisagree`.
pub fn evaluate(source: &SourceSnapshot) -> Vec<(String, Result<CabinetLayout, Reject>)>;
pub fn build(source: &SourceSnapshot) -> Vec<CabinetLayout>;   // the Ok ones, ordered by cabinet number
```

- [ ] **Step 1:** Copy the two job 690 fixtures to `tests/fixtures/job690_front_notes.json` and `job690_frame_parts.json`. In `tests/cabinet_layout.rs` add `fn snapshot_from_dump() -> SourceSnapshot` (start from `common::source()`): one `Door` per dump row (id, cabinet, width, height), two `DoorNote`s per door that has `placement`/`frame` text (prompts `FRONT_PLACEMENT`, `FRAME_MEMBERS`), and one `SourcePart` per frame part row (`door_id: None`, `part_id`, `description`, `width`, `length`, `parameters`, `cabinet`, `id` unique).
- [ ] **Step 2: Write failing tests** (each builds on `snapshot_from_dump()` and mutates one thing):
  - `real_job_690_publishes_thirteen_cabinets_including_the_sloped_one`: `build` returns cabinets `4,5,6,7,8,9,10,11,12,13,14,15,17` (compared as a set), and cabinet `"15"` carries `slope` (this test is written in Task 3 and goes green in Task 3b; until then `evaluate` gives `Err(Reject::UnsupportedRotation)` for `"15"` and the other twelve pass).
  - `cabinet_9_keeps_its_exact_overlay`: front for door `53737` has rect `x 0.8125 y 0.3125 width 18.375 height 36.125` and its opening (`DOR_OPEN@1`) is `1.5, 2.25, 17, 33.5`; the pair's fronts (doors `53738`, `53739`) start at x `19.3125` and `37.8125`, both width `18.375`.
  - `empty_areas_are_kept`: cabinet `13` is published and its `frame_members` include one `MR`.
  - `a_front_without_its_own_note_has_a_null_door_id`: remove the notes of one door of cabinet 9; the layout still publishes and that front's `door_id` is `None`.
  - `a_door_row_with_no_front_rejects` (change one door's `width` by 1.0 → `DoorsDoNotMatchFronts`) and `a_front_with_no_door_row_rejects` (remove a door row).
  - `disagreeing_notes_reject` (alter the `frame` text of one door → `NotesDisagree`); `error_note_rejects` (add a `FRONT_ERROR` note to a door → `ErrorNote`); `old_version_only_is_omitted` (rename every prompt of cabinet 9 to `Front Placement v0` → cabinet absent from both `evaluate` and `build`).
  - `missing_frame_note_rejects` (remove cabinet 9's `Frame Members v1` notes but keep its placement notes → `Err(Reject::NotesDisagree)`, absent from `build`).
  - `frame_parts_must_match_after_the_allowance`: dropping one frame part row → `FramePartsDiffer`; a part with width `1.5625` and `parameters "EDX=#M0.062500"` matches a member `DX` 1.5.
  - `zero_width_members_are_ignored`: add `BR#9=0,0,0,30,0,0,-90` to a note set → still publishes.
  - `members_must_stay_inside_the_face_and_not_overlap`: shift a member by 100 in x → `OutsideFace`; copy a member → `Overlap`.
  - `frameless_cabinet_with_no_frame_parts_publishes_empty_members`: hand-built one-door cabinet, empty frame note, no frame part rows → `frame_members` empty.
- [ ] **Step 3:** Run `cargo test --test cabinet_layout`. Expected: FAIL.
- [ ] **Step 4:** Implement in `mod.rs`: group `source.door_notes` by door, map door → cabinet via `source.doors`; per cabinet apply the spec's rules 1-8 in this order, returning the first `Reject`. Doors-vs-fronts compares sorted `(width, height)` rounded to 3 decimals. Member rect: angle 0 → `(x, y, dx, dy)`; angle -90/270 → `(x, y - dx, dy, dx)`; AX/AY nonzero → `UnsupportedRotation`; any other angle → `UnsupportedRotation` here, and Task 3b replaces that outcome with the slope proof for a lone sloped `TR`. Link each member to a part id by matching the sorted `(code, round3(width-EDX), round3(length-EDY))` keys, assigning identical keys in part-id order. A front's `door_id` is set when a door's own note has the same opening tag and rect. Format numbers with `inches()`.
- [ ] **Step 5:** Run `cargo test`. Expected: PASS, no other test regresses.
- [ ] **Step 6:** Commit: `git add src tests && git commit -m "feat(cabinet-layout): validate cabinets and build verified layouts"`.

### Task 3b: Sloped cabinets

**Files:** Create `src/cabinet_layout/slope.rs`; modify `src/cabinet_layout/mod.rs` (call it when a member's angle is not a multiple of 90) and the output types (`slope: Option<SlopeOut>`, `polygon: Option<Vec<PointOut>>` on `MemberOut` and `OpeningOut`, both `skip_serializing_if = "Option::is_none"`); tests in `tests/cabinet_layout.rs`.

**Interfaces — Produces:** `pub struct SlopeOut { pub degrees: String, pub high_on_left: bool }`, `pub struct PointOut { pub x: String, pub y: String }`. `Reject` gains `SlopeNotProven(String)` (the reason names the failed check).

The geometry is the same as the sloped door (spec `2026-10-16-sloped-door-drawing-design.md`): a straight top line, stiles upright with their top cut along it, the low stile's long point at its inner edge, the top rail a parallelogram with vertical ends whose published length runs along its axis. Face coordinates, `W` = face width, `x1` = left stile's right edge, `x2` = right stile's left edge, `hh`/`hl` = high/low stile length (`DY`), `wl` = low stile width (`DX`), `s` = slope.

- [ ] **Step 1: Write failing tests** (on `snapshot_from_dump()` and small hand-built cabinets, each changing one number):
  - `cabinet_15_is_proven_and_published_with_its_slope`: `slope.degrees` rounds to `36.6897`, `high_on_left` true; members `LS`, `RS`, `TR`, `BR`, `MR` (two of them) present; `TR` has a four-point polygon whose upper-left corner is `(1.5, 56.8824)`; the two openings under the rail have polygons whose top edge lies on the rail's lower line (the first opening's bounding top is `55.0118`).
  - Rejects, each `Err(Reject::SlopeNotProven(..))` naming the check: a top rail `DY` changed by 0.01 (`top rail length`); a top rail `Y` changed by 0.01 (`top rail origin`); `RS` `DY` changed by 0.01 (`stile lengths`, because the angle no longer reproduces); equal stile lengths with a rotated top rail (`stiles`); a second rotated member (`only the top rail may slope`); `AX` nonzero (`UnsupportedRotation`, as before); a rotated member that is not `TR` (`only the top rail may slope`).
  - `plain_cabinets_are_unchanged`: the twelve rectangular cabinets publish exactly as before (no `slope`, no `polygon`).
  - `a_sloped_opening_with_no_door_publishes`: remove door 1A's row and its note entries from the cabinet 15 fixture; the cabinet still publishes with its slope, the opening (`DOR_OPEN@1`) keeps its polygon and has no front.
  - `only_a_top_rail_slope_is_accepted_for_now`: a sloped bottom rail, a sloped mid rail, or two sloped members give `SlopeNotProven("only the top rail may slope")`. These shapes have not been seen in CV data; they are added when a real job shows one, with their own proof.
  - `polygons_must_not_overlap_or_leave_the_face`: shift an opening polygon so it overlaps the rail → `Overlap`; a member polygon outside the face → `OutsideFace`.
- [ ] **Step 2:** Run `cargo test --test cabinet_layout`. Expected: FAIL.
- [ ] **Step 3:** Implement `slope.rs`: `prove(face, members) -> Result<Slope, String>` checks, in order, with tolerance 0.002 (angles via `tan`): (1) `LS` and `RS` present, unrotated, `DY` differ by more than 0.002; (2) exactly one rotated member, and it is `TR`; (3) `s_az = |cot(az)|`-style slope from the top rail's `AZ` (the rail's length axis direction is `(-sin az, cos az)`; `s` is the absolute ratio of its y to x component), and `hh - s_az * (W - wl) - hl` is within 0.002 (the stile lengths reproduce the angle); (4) top rail origin `(X, Y)` satisfies `X` = `x1` (high on left) or `x2` side per orientation and `Y` = `top(X)` within 0.002; (5) top rail `DY` equals `(x2 - x1) * sqrt(1 + s^2) + DX * s` within 0.002. Then build polygons: stile tops on the line (high stile long point at its outer edge, low stile at its inner edge), the rail parallelogram between `x1` and `x2` with vertical thickness `DX * sqrt(1 + s^2)`, and for each opening whose bounding top equals the rail's lower line at its high edge within 0.002 a four-point polygon (flat bottom, sloped top); an opening whose top is neither on that line nor under a rectangular rail edge rejects (`OutsideFace`). Overlap between polygons uses convex clipping (Sutherland-Hodgman) with an area threshold of 0.002 × the shorter side; rectangles are polygons too.
- [ ] **Step 4:** Run `cargo test`. Expected: PASS, cabinet 15 published, the twelve others unchanged.
- [ ] **Step 5:** Commit: `git add src tests && git commit -m "feat(cabinet-layout): prove and publish sloped cabinets"`.

### Task 4: Publish in job_data and document the contract

**Files:** Modify `src/job_data.rs` (field after `door_geometry`, built from the scoped `source`), `docs/job-data-contract-v2.md`; tests in `tests/cabinet_layout.rs`.

**Interfaces — Consumes:** `cabinet_layout::build`. **Produces:** `JobData.cabinet_layouts: Vec<CabinetLayout>`, serialized as `cabinetLayouts`, omitted when empty.

- [ ] **Step 1: Write failing tests:** `job_data_serializes_cabinet_layouts` (build `JobData` through `job_data::build` from the dump snapshot; JSON has `cabinetLayouts` with 13 entries, cabinet `15` carrying `slope`, and `schemaVersion == 2`); `no_layouts_means_no_key` (a snapshot without notes: the key is absent); `hidden_door_still_counts` (hide one cabinet-9 door through the session edits as `tests/visibility.rs` does; cabinet 9 still published); `phase_export_only_has_its_cabinets` (a phase owning assembly `9` only: one layout, cabinet `9`).
- [ ] **Step 2:** Run `cargo test --test cabinet_layout`. Expected: FAIL.
- [ ] **Step 3:** Add the field with `#[serde(skip_serializing_if = "Vec::is_empty")]` and set it with `cabinet_layout::build(source)` next to `door_geometry::build(source)`. Add a `cabinetLayouts` section to the contract doc using the JSON above, the nine rules in plain words, and "omitted means door-only".
- [ ] **Step 4:** Run `cargo test`. Expected: all pass.
- [ ] **Step 5:** Commit: `git add src docs tests && git commit -m "feat(job-data): publish verified cabinetLayouts"`.

---

## Part B — KKCSheetTracker

### Task 5: Parse cabinet layouts

**Files:** Create `data/cvjob/CvJobCabinetLayout.kt`, fixture `test/resources/cvjob/job_data_690_cabinet9_layout.json` (a minimal `job_data` for cabinet 9: three doors with simple slab `doorGeometry`, their parts, and one `cabinetLayouts` entry from the contract above with all three fronts, openings `DOR_OPEN@1..2`, frame members LS/RS/TR/BR/MR with their `parts[]` rows); modify `CvJobDoorGeometry.kt`; test `CvJobCabinetLayoutTest.kt`.

**Interfaces — Produces:**
```kotlin
data class CabinetOpening(val id: String, val rect: DoorRect, val polygon: List<DoorPoint>? = null)
data class CabinetFront(val opening: String, val doorId: String?, val rect: DoorRect)
data class CabinetFrameMember(val code: String, val rect: DoorRect, val part: DoorPart, val polygon: List<DoorPoint>? = null)
data class CabinetSlope(val degrees: Double, val highOnLeft: Boolean)
data class CabinetFrontLayout(val faceWidth: Double, val faceHeight: Double,
    val openings: List<CabinetOpening>, val fronts: List<CabinetFront>, val frameMembers: List<CabinetFrameMember>,
    val slope: CabinetSlope? = null)
internal fun parseCabinetLayouts(root: JsonObject, partsById: Map<String, JsonObject>): Map<String, CabinetFrontLayout>  // key = cabinet id
// DoorPreviewData gains:  val cabinetLayout: CabinetFrontLayout? = null   (last parameter)
sealed interface FrontShape { data class Rect(val rect: DoorRect) : FrontShape
    data class Outline(val points: List<DoorPoint>) : FrontShape; data object Blank : FrontShape }
internal fun frontShape(layout: CabinetFrontLayout, front: CabinetFront, doorsById: Map<String, DoorPreviewData>): FrontShape
```

- [ ] **Step 1: Write failing tests:** `cabinet9LayoutAttachesToEachOfItsDoors` (all three doors get a layout with 3 fronts, 5 members, `faceWidth == 57.0`); `selectedFrontIsFoundByDoorId` (`layout.fronts.single { it.doorId == "53737" }.rect.height == 36.125`); `oldFileWithoutCabinetLayoutsHasNone`; `layoutIsDroppedWhenARectIsNotFinite` (`"width": "NaN"`); `layoutIsDroppedWhenAMemberHasNoPartRow`; `unknownDoorIdFrontIsKeptAsAnInertFront` (a front whose `doorId` is in no door row stays in `fronts`; no door gets it as selected, **unless the cabinet has a `slope`**, see below); `doorsOfOtherCabinetsGetNone`. Sloped cabinets (fixture `job_data_690_cabinet15_layout.json`: doors 1A sloped, 1B and 1C rectangular, `slope` 36.6897 left high, polygons on both stiles, the top rail and the two openings under it): `cabinet15LayoutAttachesWithItsSlope`; `frontShapeFollowsItsOwnDoor` (1B and 1C `Rect`, 1A `Outline`); `doorAngleNeedNotMatchTheCabinet` (the fixture's door 1A given a different but verified slope: the layout stays and 1A draws that slope); `straightDoorInAnAngledCabinetStaysARectangle`; `angledDoorInAStraightCabinetUsesItsOutline`; `refusedDoorIsBlankButTheCabinetStays` (1A's top rail changed so `DoorSlope.Refused`: shape `Blank`, layout kept); `frontWithNoDoorRowIsBlankInASlopedCabinetButARectangleInAStraightOne`; `emptySlopedOpeningStaysInTheLayoutWithNoFront` (1A removed from the fixture: the opening and its polygon stay, no front for it); `polygonWithNonFinitePointDropsTheLayout`.
- [ ] **Step 2:** Run `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.cvjob.CvJobCabinetLayoutTest"`. Expected: FAIL (does not compile).
- [ ] **Step 3:** Implement the parser (strings or numbers via the file's existing `inches()` helper; reject on any non-finite or non-positive size; `part` built from `partsById[member.part]` as `DoorPart(partId, "frame", description, material, width, length, quantity)`). In `parseCvJobDoorPreviews`, set `cabinetLayout = layouts[door.cabinet]` only when that layout has a front whose `doorId` equals the door's id. The layout is kept whatever the doors' own angles are: door slope and cabinet slope are never compared. Add `frontShape(layout, front, doors)` returning `FrontShape.Rect` (the front's `rect`), `FrontShape.Outline(points)` (the door's `DoorSlope.Verified.outline` placed at the front's origin) or `FrontShape.Blank`. Rules in order: a front with a `doorId` found in the door list → the door's `DoorSlope` decides (`None` → `Rect`, `Verified` → `Outline`, `Refused` → `Blank`); a front with no door row → `Rect` in a cabinet without `slope`, `Blank` in a cabinet with `slope` (its shape cannot be proven). A front's bounding `rect` is never drawn for a shape that is not proven (Tasks 7 and 8).
- [ ] **Step 4:** Run the test class, then `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.data.cvjob.*"`. Expected: PASS.
- [ ] **Step 5:** Commit: `git add app/src && git commit -m "feat(doors): parse verified cabinet layouts"` (only the named files).

### Task 6: Cabinet fit and hit-testing (pure)

**Files:** Create `ui/doorpreview/CabinetFrontGeometry.kt`; test `CabinetFrontGeometryTest.kt`.

**Interfaces — Consumes:** Task 5 model, `DoorRect`. **Produces:**
```kotlin
internal fun cabinetBounds(layout: CabinetFrontLayout): DoorRect   // union of (0,0,face) and every front
internal data class CabinetFit(val scale: Float, val offsetX: Float, val offsetY: Float, val boundsTop: Double, val boundsLeft: Double) {
    fun rect(r: DoorRect): FloatArray            // left, top, right, bottom in px, y flipped
}
internal fun cabinetFit(layout: CabinetFrontLayout, canvasWidth: Float, canvasHeight: Float, padding: Float): CabinetFit
internal sealed interface CabinetTarget {
    data class Front(val front: CabinetFront) : CabinetTarget
    data class Member(val member: CabinetFrameMember) : CabinetTarget }
internal fun hitCabinetTarget(layout: CabinetFrontLayout, fit: CabinetFit, viewportWidth: Float, viewportHeight: Float,
    x: Float, y: Float, zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f): CabinetTarget?
```

- [ ] **Step 1: Write failing tests:** `boundsIncludeAnOverhangingFront` (a front at y -1.9375 widens the bounds to start there); `fitCentersAndKeepsAspect` (face 57 x 38.75 in a 570 x 600 viewport with padding 0 → scale 10, rect of the face spans x 0..570); `frontsWinOverTheFrameWhereTheyOverlap` (a tap on a front's overlap with a stile returns `Front`); `tapOnFrameMemberReturnsMember`; `tapInAVoidReturnsNull`; `slopedMemberUsesItsPolygonForHits` (a tap in the bounding box but outside a stile's cut-off top returns null, using `pointInDoorPolygon`); `slopedFrontHitUsesTheDoorOutline` (a tap above the sloped door's top edge but inside its bounding box is not a hit); `boundsUseTheSlopedOutline`; `zoomAndPanAreReversedBeforeHitTesting` (same numbers pattern as `hitDoorPart` in `DoorPartInspection.kt`); `nonFiniteInputReturnsNull`.
- [ ] **Step 2:** Run `.\gradlew.bat testDebugUnitTest --tests "*CabinetFrontGeometryTest"`. Expected: FAIL.
- [ ] **Step 3:** Implement, reusing the zoom/pan inverse from `hitDoorPart`; test fronts first, then members. `hitCabinetTarget` also needs a front's real shape: add a parameter `frontShape: (CabinetFront) -> FrontShape` (Task 5's `Rect`, `Outline` or `Blank`); a `Blank` front is not hit-testable and is skipped. Members and openings use their `polygon` when present.
- [ ] **Step 4:** Run the class. Expected: PASS.
- [ ] **Step 5:** Commit: `git add app/src && git commit -m "feat(doors): cabinet fit and hit-testing"`.

### Task 7: Draw the cabinet

**Files:** Create `ui/doorpreview/CabinetFrontCanvas.kt`; modify `DoorPreviewCanvas.kt` (`private fun DrawScope.drawDoor` becomes `internal`); test additions in `CabinetFrontGeometryTest.kt`.

**Interfaces — Consumes:** Task 6, `buildDoorDrawing`, `DoorPreviewColors`, `drawDoor`. **Produces:**
```kotlin
internal const val CABINET_GHOST_ALPHA = 0.45f
internal fun ghostColor(base: Color): Color            // base with alpha CABINET_GHOST_ALPHA
@Composable internal fun CabinetFrontCanvas(layout: CabinetFrontLayout, selectedDoorId: String,
    selectedDrawing: DoorDrawing, selectedMember: CabinetFrameMember?, modifier: Modifier, padding: Dp = 24.dp,
    showHiddenLines: Boolean = true, colors: DoorPreviewColors = doorPreviewColors())
```

- [ ] **Step 1:** Add a failing test `ghostColorKeepsHueAndSetsAlpha` (`ghostColor(Color(0xFF808080)).alpha` is 0.45 within 0.001; red/green/blue unchanged).
- [ ] **Step 2:** Run it. Expected: FAIL.
- [ ] **Step 3:** Implement `ghostColor` and the canvas: fit via `cabinetFit`; draw the face outline hairline, frame members and every non-selected front as rounded-corner-free filled rects in `ghostColor(colors.member)` with a hairline `colors.outline` edge; then draw the selected door with `drawDoor` inside `inset(left, top, right, bottom)` around its front rect with padding 0; highlight `selectedMember` with the existing selection style (primary colour fill 0.14, stroke 2 dp). Nothing is drawn for empty areas. Sloped cabinets: members with a `polygon` are filled as paths (reuse `polygonPath`), the face outline is the polygon of the stiles and rail rather than the face rectangle, and each non-selected front is filled by its `FrontShape` (`Rect`, `Outline`, or nothing for `Blank`); the selected door is drawn by `drawDoor` as in phase 1 (its own outline, no rectangle underneath; a selected door that is refused has no cabinet view because it has nothing to draw). Empty openings, sloped or not, are not painted.
- [ ] **Step 4:** Run the class; run `.\gradlew.bat assembleDebug` to confirm everything compiles. Expected: PASS and BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit: `git add app/src && git commit -m "feat(doors): draw the cabinet front"`.

### Task 8: Fullscreen integration

**Files:** Modify `DoorPartInspection.kt`, `DoorPreviewFullscreen.kt`, `DoorAssemblyList.kt`; tests in `DoorPartInspectionTest.kt` and `androidTest/.../DoorPreviewFullscreenTest.kt`.

**Interfaces — Consumes:** Tasks 5-7, `doorPartSizeLines(DoorPartRegion)`. **Produces:**
```kotlin
internal fun frameMemberRegion(member: CabinetFrameMember): DoorPartRegion   // verticalLength true for LS RS MS, false for TR BR MR; tenon 0; polygon = member.polygon, and a member with a polygon shows the cut size only (as sloped door pieces do)
// DoorPreviewFullscreen(door, onDismiss, onSelectDoor: (String) -> Boolean = { false })
```

- [ ] **Step 1: Write failing tests:** in `DoorPartInspectionTest`: `frameMemberShowsCutThenFinalSize` (an `LS` member, rect `1 x 84`, part width `1.0625` length `84` → `["Cut size 1.0625 × 84 in", "Final size 1 × 84 in"]`) and `railMemberLengthAxisIsHorizontal` (a `TR` rect `60.75 x 1.25`, cut `1.25 × 60.75` → `Final size 1.25 × 60.75 in`). In the androidTest: `cabinetToggleShownOnlyWithALayout` (a door with the cabinet 9 fixture layout shows "Cabinet" and "Door only"; a door without shows neither); `doorOnlyReturnsToTheDoorHint` ("Pinch to zoom · Double-tap to reset" still displayed).
- [ ] **Step 2:** Run `.\gradlew.bat testDebugUnitTest --tests "*DoorPartInspectionTest"`. Expected: FAIL; then `.\gradlew.bat compileDebugAndroidTestKotlin` after the UI edit.
- [ ] **Step 3:** Implement `frameMemberRegion`. In `DoorPreviewFullscreen` add `showCabinet` state (`rememberSaveable(door.doorId)`, default `door.cabinetLayout != null`), a two-option "Cabinet / Door only" control beside "Hidden lines" shown only with a layout, and in cabinet mode render `CabinetFrontCanvas` with the same pinch/pan transform; taps use `hitCabinetTarget`: `Member` sets the inspected member (panel shows `doorPartSizeLines(frameMemberRegion(member))` and quantity), `Front` with a `doorId` calls `onSelectDoor(id)` (inert when it returns false or `doorId` is null or equals the current door). The existing door-part inspection stays in "Door only" mode. In `DoorAssemblyList` pass `onSelectDoor = { id -> (allDoors.any { it.doorId == id }).also { if (it) fullscreenDoorId = id } }`.
- [ ] **Step 4:** Run `.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.doorpreview.*"` and `.\gradlew.bat compileDebugAndroidTestKotlin`. Expected: PASS and BUILD SUCCESSFUL. (The instrumented tests need a device; run them with `.\gradlew.bat connectedDebugAndroidTest --tests "*DoorPreviewFullscreenTest"` when one is attached, per the `debug-android-tablet` skill.)
- [ ] **Step 5:** Commit: `git add app/src && git commit -m "feat(doors): show the cabinet front in the full-screen preview"`.

---

## Part C — Documentation and rollout

### Task 9: Metadata docs and the field checklist

**Files:** Modify `C:\Users\chadc\.sync-skills\skills\kkc-metadata-map\SKILL.md` (canonical copy only; mirrors sync automatically), and `C:\Scripts\Hours Tracker\METADATA_AUDIT.md` per its section 1.4.

- [ ] **Step 1:** In the `cv_job\job_data.json` row of the metadata map add: new top-level `cabinetLayouts[]` (CV Cutlist Query-owned, validated, omitted when a cabinet cannot be proven), the two UCS notes `Front Placement v1` and `Frame Members v1` written on each front's door row, and that KKCSheetTracker is a read-only consumer. Add the same entry to the audit document.
- [ ] **Step 2:** Run through this checklist, report each line pass or fail, and fix nothing silently: (1) the UCS entry is installed on the CV PC with `DOR` and `DWR`; (2) regenerate job 690 reports and export from CV Cutlist Query; (3) `job_data.json` contains `cabinetLayouts` with 13 entries, cabinet 15 carrying `slope` 36.6897; (4) on a tablet build, open cabinet 9 and a door of cabinet 13: toggle present, overlay correct, tapping a stile shows cut and final size; (5) open door 1A, 1B and 1C of cabinet 15: toggle present, the frame and the greyed fronts follow the slope, door 1A drawn as its trapezoid, tapping the cut-off top of a stile does nothing, a front tap switches to that door; (6) cabinet 15 in a job whose export is re-run with one top rail length altered by hand in the notes: no toggle, door-only view (do this on a copy, never on the live share); (7) a sloped cabinet whose door is straight, one whose door has a different angle, and one with a sloped opening and no door (build or find them in CV, regenerate and re-export): the frame follows the cabinet's own angle, each door keeps its own shape, and the doorless opening shows as empty space.
- [ ] **Step 3:** Commit the Hours Tracker doc in its own repo if it changed (`git -C "C:\Scripts\Hours Tracker" ...`); the skill file needs no commit.
