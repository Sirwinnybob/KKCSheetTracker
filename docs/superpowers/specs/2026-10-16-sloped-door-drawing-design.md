# Verified sloped-door drawing — design

Date: 2026-10-16. Status: approved in chat; phase 1 (door preview) built with it.
Programs: KKCSheetTracker only for phase 1. Phase 2 (cabinet front) changes the cabinet-front
spec (`2026-10-16-cabinet-front-fullscreen-design.md`) and touches CV Cutlist Query.

## Goal

A door whose top is cut at an angle (a sloped cabinet, job 690 cabinet 15, door 1A) is drawn as the
trapezoid it really is, not as a rectangle. Phase 1 is the door preview (Door Assembly list,
expanded card, full-screen). Phase 2 uses the same shapes inside the cabinet front.

## Rule: real data, verified, or nothing

The outline is built only from numbers CV published, and is drawn only if three independent
published numbers reproduce. Any door that is clearly not rectangular but fails a check is **not
drawn** (no rectangle, no outline), with a plain note saying why. The tablet never shows a
rectangle as the shape of a door it knows is not one.

## What is verified (job 690, door 1A, 23.875 x 24.5689)

CV publishes per-part cut sizes plus the allowances (`EDX`, `EDY`). Finished stile length = cut
length less the stile allowance; finished rail length = cut length less two tenons.

- Left stile 24.6314 cut, right stile 8.5186 cut, so finished 24.5689 and 8.4561 (the left one is
  the door height). The stiles differ, which is the signal that the door is shaped.
- Slope from the two stiles: the high stile's long point is at its outer edge, the low stile's long
  point at its inner edge, so `s = (high - low) / (width - low stile width)` = 0.7451 (36.69 deg).
- Check 1, top rail: finished length must equal `span * sqrt(1 + s^2) + railWidth * s` (a
  parallelogram with vertical ends, whose cut length is measured along its axis). 25.8383 against
  published 25.8384.
- Check 2, bottom rail: finished length must equal the span between stiles. 19.375 against 19.375.
- Check 3, panel: cut width must equal `span + 2 * insetX` (20.311) and cut height the panel's
  vertical extent `(top at the high edge - bottom)` with insets applied along the slope. 19.2368
  against published 19.2368.
- Independent confirmation: the cabinet's own top rail is rotated 233.3103 deg (53.3103 from
  vertical, the cabinet's `MITT` parameter) and `58 - 24 * tan(36.6897) = 40.1176` equals the
  cabinet's right stile. Used in phase 2.

## Phase 1 design (KKCSheetTracker)

Data. `DoorFrame` gains `stileLengthAllowance` (already published, not yet read). A pure function
`deriveDoorSlope(geometry, parts)` returns `None` (stiles equal or no stile data: ordinary door),
`Refused(reason)`, or `Verified(outline, members, opening, highOnLeft, slopeDegrees)`. It runs in
`parseCvJobDoorPreviews`, where door parts are joined, and is stored on `DoorGeometry.slope`.

Refusal reasons: tall stile does not equal the door height, a needed part or allowance missing,
top rail, bottom rail or panel size does not reproduce, or the outline does not close (low stile
height not positive, rail overlaps the bottom rail).

Drawing. `DoorShape.Poly` (points, role) joins `Rect`. `DoorDrawing` gains `outline` (polygon
for the face, shadow and outline) and `refused`. A verified door draws the stiles, rails, panel
and outline as polygons; hinge cups are unchanged (CV's door coordinates). A refused door
draws nothing and carries a note. The exporter's two false warnings for a verified door ("Rail
length does not match the door width", "Panel size does not fit the frame opening") are removed
from the badge only when the door verified; any other warning stays.

Tap panel. Part regions carry an optional polygon, hit-tested by point in polygon and highlighted
as a polygon. Sloped pieces show the cut size only (no invented final size or visible opening).

Not changed. Doors with equal stiles, slabs, multi-panel doors and everything in the exporter.

## Phase 2 (cabinet front, after phase 1)

The cabinet-front spec's rule 4 (rotation 0 or -90 only) gains one case: a lone sloped top rail,
accepted only if its angle agrees with the slope derived from the cabinet's own stile lengths and
two more numbers reproduce (see the plan's Task 3b). The cabinet's angle and a door's angle are
independent: a door is drawn from its own verified outline (or as a rectangle when its stiles are
equal), never from the cabinet's angle, and a door's angle is never compared with the cabinet's.
A front whose shape cannot be proven is left blank. An opening with no door, sloped or not, is
real empty space. Cabinet 15 is the test case.

## Testing

- `CvJobDoorSlopeTest`: door 1A verifies and gives exact corner points; the mirror image
  (high on the right) verifies; each of top rail, bottom rail, panel, door height, a missing
  allowance, a missing part and a closing failure is refused; equal stiles give `None`.
- `DoorPreviewLayoutTest` additions: verified draws polygons and a polygon outline; refused draws
  no shapes; the two false warnings are dropped only when verified.
- Hit-test and highlight with polygons; end-to-end parse of a fixture holding door 1A's published
  numbers (part ids are synthetic, sizes are job 690's).
- Manual: tablet check on a job 690 export once CV Cutlist Query publishes it.
