# Island-wing 3D viewer design

## Purpose and scope

Add one shared, interactive island-wing viewer to KKCSheetTracker. A Specialty CUSTOM
item opens only the wing selected through its current job/phase linkage; a paneled end
opens the same full wing through its published `doorId` link. A list thumbnail is not a
WebView or GL context.

## Data and resolution

`wingGeometry` remains optional schema-v2 input. A new CV-job parser owns the immutable
wing tree, decimal-inch conversion, optional `materialId`/`grainAxis`, and validation.
It preserves parent-local transforms and treats Cab, Case, Face2, Face4, and DOR_OPEN as
containers. `sourcePid` is retained as diagnostic source evidence and is never joined to
`parts[].id`.

The specialty resolver receives the loaded export and the item's existing linkage. It
normalizes and intersects that item's `cabinetNumbers` with captured wing `cabinet`
identities inside the already-selected job/phase. It does not inspect the title and does
not select a first candidate. No candidate means no 3D action; one opens that wing;
multiple display an operator picker. The picker sends exactly one selected wing to the
viewer.

The paneled-end resolver maps a displayed door's `doorId` to the unique captured wing
node containing that `doorId`. No match leaves the existing door preview unchanged.

## Presentation and rendering

A reusable `WingScene` creates one Three.js scene from the selected wing, fitting and
resetting a single assembled model. It uses real parent-matrix composition with the
measured local X/Y/Z rotation order verified against the fixture. Explode offsets are
temporary display transforms. TS and DE are excluded from thumbnail/fullscreen,
exploded, labels, selection, and part lists, but never removed from parsed/exported data.

The scene has touch orbit/pinch controls, fit/reset, standard views, selection and
measured dimensions. A transparent-cap or joint-section mode makes calculated board-to-
cap engagement inspectable without claiming unexported groove profiles. The normal mode
is assembled, opaque, and shows a single resolved wing.

The existing `Model3DPane`, `ViewerServer`, bundled Three.js, and orbit controls are
extended for a wing-specific local payload; it requires no room GLB, CDN, or service and
releases its WebView/resources on navigation.

## Materials

Door nodes use their linked `DoorPreviewData` geometry/components and existing door
material resolution. Other boards use only published node `materialId` and `grainAxis`.
The local server gets a texture bridge backed by the existing native JXL decoder/cache:
it decodes and hash-checks through the established library, exposes a cache-local image
to WebView, and supplies metadata for the same physical tiling/clamping and grain
orientation rules. Missing or invalid material/textures render solid white. It retries
late texture arrival without blocking navigation.

Current Cutlist Query source already has additive `materialId`/`grainAxis` fields, but
the checked fixture lacks them; tablet parsing must support both forms. The exporter
remains the only `job_data.json` writer.

## UI integration

Specialty gains a clear `3D` action beside the existing View action only when resolution
can establish at least one captured wing. Progress/editing behavior remains unchanged.
Door Assembly's Paneled Ends and Misc category uses a small full-wing preview only for a
uniquely linked captured wing; tapping it opens the shared viewer. Ordinary end panels
continue to use the existing preview.

## Verification

Copy the four-wing export into Android test resources. Tests cover absent/malformed
exports, parent transforms, visible-five-of-seven support filtering, exact specialty and
door resolution, no unrelated fallback, material fallback/new field behavior, and
calculated 0.375-inch engagement. Add UI tests for the 3D action/picker and paneled-end
handoff without weakening existing Door Assembly/Specialty tests. Run relevant unit
tests, `testDebugUnitTest`, and `assembleDebug`; no installation or deployment.

## Live-data follow-up

Before relying on production wood textures, commit/deploy the existing Cutlist Query
material export work and regenerate each job's `job_data.json` using the UCS. The app
remains compatible with older exports but will show the documented white fallback for
unproven boards.
