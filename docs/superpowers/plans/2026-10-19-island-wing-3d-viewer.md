# Island-wing 3D Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let Specialty CUSTOM items and paneled-end doors open their uniquely resolved, textured, interactive full island wing.

**Architecture:** A shared snapshot loader reads each job export once and parses doors and optional wings together. Pure resolvers and scene/projection helpers own selection, transforms, material evidence, and native list thumbnails. One fullscreen WebView/Three.js renderer receives the selected scene through the existing localhost server; existing native material decoding produces cache-local browser-safe images.

**Tech Stack:** Kotlin, Jetpack Compose/Material 3, Gson, NanoHTTPD, Android WebView, bundled Three.js/OrbitControls, existing JXL texture decoder/cache.

**Spec:** `docs/superpowers/specs/2026-10-19-island-wing-3d-viewer-design.md`

## Global Constraints

- Preserve user-owned uncommitted door-preview work; do not amend or reset it.
- Show exactly one explicitly resolved wing per viewer session; never choose an unrelated fallback.
- `wingGeometry` is optional schema-v2 input; all measurements are decimal-inch strings and source trees retain local parent transforms.
- Cab, Case, Face2, Face4, and DOR_OPEN are containers; TS/DE remain source data but are hidden in every presentation and selection surface.
- Reuse the established material ID/name lookup, native JXL/hash/cache/retry path, physical texture scale, grain rules, and white fallback.
- Do not join `sourcePid` to report parts; consume only published `materialId`, `grainAxis`, and door component material evidence.
- No CDN, remote service, room GLB, install, deployment, or metadata write from the tablet.
- Preserve the current KKC visual system and custom themes; the UI contract below applies to Tasks 4–6. Do not introduce a separate viewer theme or restyle surrounding screens.
- Compilation is not runtime verification. Record unit/JavaScript results, Android test compilation, Android execution, and visual acceptance separately; pending Android acceptance prevents claiming the feature fully verified.

## Verified inputs and prerequisites

- Authoritative fixture source: `C:\Scripts\CV Cutlist Query\target\verified-wing-export\job_data.json`, located through this worktree's `PRODUCT.md` and inspected on 2026-10-19.
- SHA-256: `1e6f3b36f2b49cdec14c57f0f25e9a49523c7eb646696faaf619ccedb2e3afb5`; schemaVersion `2`; sourceFingerprint `b594f7d80f37f348abbacb52edc96953387e38bb6e7935fed546e749e2c96b8e`; generatedAt `2026-10-19T20:29:29.119204200+00:00`.
- Expected captures in source order: cabinet `3` left paneled, door `53738`; `4` right paneled, door `53739`; `5` left flat; `6` right flat. Each has seven source solids and five visible solids after TS/DE filtering. Paneled trees have eleven nodes; flat trees have ten.
- This inspected export includes `materialId` and `grainAxis` on 26 board nodes. The spec's earlier observation that the fixture lacks appearance fields is superseded by this pinned evidence. Test legacy compatibility by removing those optional fields from an in-memory fixture copy, without changing the pinned original.
- Copy the exact bytes into the repository in Task 1; later tests must read the committed copy, never the external `target/` directory. Verify its hash before copying. If missing or changed, stop fixture-dependent implementation and reconcile the source; do not silently substitute the raw diagnostic `tests/fixtures/wing-live-tree-v1_4.json` or fabricate a corrected export.
- The user's Paneled Ends and Misc category changes are not present in this worktree at plan revision time (`DoorAssemblyCategory.kt` is absent). Before Task 6 door integration, identify the actual source branch/worktree and integrate the authorized changes without resetting or broadly staging user work. Record the integrated revision in the task's implementation handoff. If unavailable, leave that integration explicitly pending; do not recreate the missing category work under this plan.

## UI contract: match the current app

**Visual authority:** `ui/doorpreview/DoorPreviewFullscreen.kt`, `ui/doorpreview/DoorAssemblyList.kt`, `ui/specialty/SpecialtyJobDetailScreen.kt`, `ui/components/KKCSlidingPill.kt`, and `ui/theme/{Theme,Spacing,KKCThemeTokens}.kt` (all under `app/src/main/java/com/kkc/sheettracker/`). These existing surfaces and tokens govern appearance; Material 3 alone is not a sufficient styling specification.

- **Fullscreen shell:** follow `DoorPreviewFullscreen`: a fullscreen `Dialog` with `usePlatformDefaultWidth=false`, `decorFitsSystemWindows=false`, root `MaterialTheme.colorScheme.background`, and safe-drawing insets. Use its compact native header layout with the wing's cabinet/name, measured overall dimensions below, and a trailing close icon labelled `Close wing preview`. System Back uses the same dismissal/cleanup path. Do not add the app-wide `KKCTopAppBar` here: it also inserts battery/settings controls, while this viewer is an inspection dialog like the existing door preview.
- **Typography, color, spacing:** inherit `MaterialTheme.typography` (the app's configured font), `MaterialTheme.shapes`, `LocalKKCIsDarkTheme`, and the active KKC theme. Use `titleMedium` for the header, `bodyMedium` for dimensions, `labelMedium` for controls, and `titleSmall`/`bodySmall` for inspection details. Use `KKCSpacing.screenHorizontal` (16dp), `inCardSpacing` (8dp), and `cardPaddingSmall` (12dp) where appropriate. Surfaces use `background`, `surfaceContainerLow`, `surfaceVariant`, and their matching content roles; outlines use `outlineVariant`. No hardcoded brand blues, new fonts, gradients, or decorative glass layers in the viewer.
- **Controls:** native Compose controls above the model, with the same primary-track/secondary-pill appearance as existing KKC pill rows, driven by `rememberKKCPillStyle`. Provide `Fit`, `Reset`, a standard-view selector (`3D`, `Front`, `Back`, `Left`, `Right`, `Top`), and labelled `Explode` / `Inspect joints` switches. Use shared `KKCPillContainer` / `KKCSlidingTabRow` primitives where their size parameters allow 48dp targets; fixed-height header helpers are not sufficient just because the modifier requests a larger height. Keep viewer-specific control composition in `WingViewerControls.kt`; do not change shared control defaults. Reset restores the assembled opaque 3D view, clears selection, and fits the model; Fit changes framing only. All modes start with explode/joint inspection off.
- **Layout:** the model fills remaining space and is the main visual focus. Controls wrap into additional rows on narrow/portrait layouts and at increased font size; no clipped labels, overlaying the model's touch region, or permanently expanded tool sidebar. Every new interactive target is at least 48×48dp, with 8dp between adjacent targets. The picker list scrolls if necessary; the inspection panel can scroll for long content so controls and dismissal remain reachable.
- **Model and selection:** pass resolved theme colors from Compose into the WebView for canvas background, selection outline, and dimension annotations; retain white for untextured boards in both themes. Browser content owns geometry and gestures only: no second HTML toolbar, room selector, room zoom joystick, or competing browser typography. Reuse `WebViewBlurGate` and existing viewer interaction signaling while the host is active. Do not modify bottom-navbar appearance or Haze wiring.
- **Inspection panel:** match the existing door preview's bottom `surfaceContainerLow` panel, with 16dp horizontal/12dp vertical padding and polite live-region semantics. Empty selection says `Tap a part to inspect it`; selection shows the published part label, measured dimensions in inches using `formatDoorInches`, and known material. Joint mode shows the computed engagement with units, without claiming an exported groove. Missing textures show a subdued, non-blocking `Some materials are unavailable; shown in white` status. A scene-load failure offers an explicit `Retry` action and keeps Close/Back usable.
- **Specialty:** add `3D` beside the existing `View` action inside the current `CompactSpecialtyProgressCard`, using the same compact filled-button type, typography, padding, and theme colors, with a 48dp effective touch target. Keep existing card borders, progress/station colors, View behavior, and edit controls. If actions do not fit, wrap the action group instead of shrinking touch targets. Multiple candidates open a Material 3 `AlertDialog` titled `Choose island wing`, with cabinet, handedness, and construction per 48dp-minimum row plus `Cancel`; no default selection, and tapping a row opens only that wing.
- **Door Assembly:** retain the existing card/header/body arrangement, status borders, checkbox actions, sort/filter, and component details. Replace only a uniquely linked wing's 48dp header thumbnail and expanded drawing preview with the native full-wing drawing; use `Open wing preview` semantics. Ordinary or ambiguously linked ends retain the current door drawing and semantics. Thumbnail fills/material fallback and projection must agree with fullscreen geometry; no row creates a WebView, server, or GL context.
- **UI acceptance:** compare the new UI beside current Door Assembly and Specialty on the same build/theme. Cover light/dark, one existing custom theme, portrait/landscape tablet layouts, font scale 1.3, long labels, the picker, selected part/joint details, and missing textures. No production progress/status color may be repurposed for viewer modes.

## Verification policy

Run JVM tests, existing Node test infrastructure (`node --test app/src/test/js`), APK builds, and Android test compilation without a device. For Android runtime checks, use the direct instrumentation command documented in Tasks 5–7 only when a designated test device already has the exact application and test APKs for the changes installed. Confirm installed artifact identity against the built APK hashes; versionName/versionCode alone is insufficient. Do not run `connectedDebugAndroidTest`, installation helpers, or any install/uninstall/deployment command under this plan. Preserve `android.injected.androidTest.leaveApksInstalledAfterRun=true`.

If exact installed artifacts or a suitable device are unavailable, record Android tests as **compiled, not executed**, and native/WebView visual acceptance as **pending**. This is an explicit verification limitation, not a passing runtime result. Future installation/testing requires separate authorization; it is outside this plan's execution scope.

## Review Focus

- Old/malformed exports: unavailable state without a crash or a guessed model (Task 1).
- Multiple specialty cabinet links: picker requires an explicit wing choice (Task 2/5).
- A stale/missing door link: ordinary decorative-end preview stays unchanged (Task 2/6).
- Syncthing-delayed/invalid JXL: retry or render white without WebView navigation failure (Task 4).
- A transformed 0.375-inch cap engagement: preserve and expose computed geometry, not an invented groove (Task 3).
- A rescan/job change during loading: doors and wings refresh atomically and never show a previous job's links (Task 1/6).

---

### Task 1: Parse and validate wing geometry

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/cvjob/CvJobWingGeometry.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/data/cvjob/CvJobWingGeometryTest.kt`
- Create: `app/src/test/resources/cvjob/verified-wing-export.json`
- Create: `app/src/test/resources/cvjob/verified-wing-export.provenance.md`
- Create: `app/src/main/java/com/kkc/sheettracker/data/cvjob/CvJobViewerSnapshot.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/data/cvjob/CvJobViewerSnapshotTest.kt`

**Interfaces:**
- Produces: `data class CvWingGeometry`, `CvWingNode`, `CvWingNodeMaterial`, `sealed interface CvJobWings`, and `fun parseCvJobWings(json: String): CvJobWings`.
- Produces: `fun CvWingGeometry.visibleNodes(): List<CvWingNode>` and `fun CvWingGeometry.nodeForDoor(doorId: String): CvWingNode?`.
- Produces: `data class CvJobViewerSnapshot(val doors: CvJobDoorPreviews, val wings: CvJobWings)`, `fun parseCvJobViewerSnapshot(json: String): CvJobViewerSnapshot`, and `fun loadCvJobViewerSnapshot(basePath: String, jobFolderName: String): CvJobViewerSnapshot`. The loader reads exactly `cvJobDataFile(basePath, jobFolderName)` once and sends the same string to both parsers; missing/unreadable data maps to the corresponding unavailable results for both. Retain the existing `loadCvJobDoorPreviews` API for other callers.

- [ ] **Step 1: Copy the corrected four-wing export into test resources and write failing parser tests**

Verify the source hash and copy the export identified in Verified inputs. Record that source path, hash, sourceFingerprint, timestamp, and expected cabinet/door identities in the provenance file. Assert the pinned four captures and tree counts; paneled wings have the expected unique door links; flats have no door; all source nodes remain present; `visibleNodes()` produces five visible solids from each seven-solid source. Assert absent/malformed wing data is non-crashing and does not discard otherwise valid doors, and legacy copies without material/grain fields remain parseable. Snapshot tests assert both parsers receive one identical export revision, missing/unreadable results are paired, and loading performs no metadata writes.

- [ ] **Step 2: Run the parser tests to verify they fail**

Run: `./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.cvjob.CvJobWingGeometryTest --tests com.kkc.sheettracker.data.cvjob.CvJobViewerSnapshotTest`

Expected: FAIL because the wing parser does not exist.

- [ ] **Step 3: Implement the immutable parser and presentation filter**

Parse all numeric strings only when finite; validate parent-before-child paths and known container/solid semantics; retain optional material/grain and never use `sourcePid` as a part identifier. Hide only code `TS` and `DE` through `visibleNodes()`.

Implement the shared read-once snapshot loader separately from the pure wing parser. Keep UI refresh keys out of the data parser; Task 6 owns scan-generation cancellation and publication.

- [ ] **Step 4: Run the parser tests to verify they pass**

Run: `./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.cvjob.CvJobWingGeometryTest --tests com.kkc.sheettracker.data.cvjob.CvJobViewerSnapshotTest`

Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -m "feat(cvjob): parse island wing geometry"`

### Task 2: Resolve exact Specialty and paneled-end wings

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/data/cvjob/WingGeometryResolver.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/data/cvjob/WingGeometryResolverTest.kt`

**Interfaces:**
- Consumes: `CvJobWings`, `SpecialtyItem`, `DoorPreviewData`.
- Produces: `fun resolveSpecialtyWings(item: SpecialtyItem, wings: CvJobWings): List<CvWingGeometry>` and `fun resolvePaneledEndWing(door: DoorPreviewData, wings: CvJobWings): CvWingGeometry?`.

- [ ] **Step 1: Write failing resolver tests**

Assert cabinet-number matches only, rejects non-CUSTOM/no-cabinet/no-geometry cases, returns every valid candidate without selecting one, and accepts a paneled end only through exactly one `nodes[].doorId` match.

- [ ] **Step 2: Run the resolver tests to verify they fail**

Run: `./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.cvjob.WingGeometryResolverTest`

Expected: FAIL because resolver functions do not exist.

- [ ] **Step 3: Implement pure resolvers**

Normalize cabinet identifiers conservatively, preserve source order for the picker, require CUSTOM category for Specialty, and return null for ambiguous/missing door mappings instead of title-, first-cabinet-, or sourcePid-based inference.

- [ ] **Step 4: Run the resolver tests to verify they pass**

Run: `./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.cvjob.WingGeometryResolverTest`

Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -m "feat(cvjob): resolve linked island wings"`

### Task 3: Build a measured single-wing scene payload

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/viewer3d/WingScenePayload.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/viewer3d/WingScenePayloadTest.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/viewer3d/WingThumbnailProjection.kt`
- Create: `app/src/test/java/com/kkc/sheettracker/viewer3d/WingThumbnailProjectionTest.kt`
- Create: `app/src/test/resources/cvjob/verified-wing-world-measurements.json`

**Interfaces:**
- Consumes: one `CvWingGeometry` plus `Map<String, DoorPreviewData>`.
- Produces: `fun buildWingScenePayload(wing: CvWingGeometry, doorsById: Map<String, DoorPreviewData>): WingScenePayload` and `fun WingScenePayload.capEngagements(): List<WingCapEngagement>`.
- Produces: `data class WingThumbnailProjection` and `fun projectWingThumbnail(scene: WingScenePayload): WingThumbnailProjection`: orthographic 3D-view faces, depth order, bounds, and material/grain references for native Canvas drawing. It consumes the same assembled scene as fullscreen, not a second interpretation of raw CV nodes.

- [ ] **Step 1: Write failing transform/material/joint tests**

Create a checked-in table of independently calculated world corners/bounds and the sixteen expected board/cap pairs from the pinned export, with the derivation recorded in fixture provenance; do not generate expected values with the production transform helper. One hand-check anchor is cabinet 3's DOR world box: min `(123, 0, -10.375)`, max `(123.75, 35.25, 24.5)` in the exported parent frame. Its intersections with `Cab.Case.FILLER` and `Cab.FILLER` each engage 0.375 inches along the cap-depth axis. Assert the exact pair identities, positive volume, and per-pair depth with a stated 1e-6-inch tolerance, rather than only a total count.

Assert parent composition uses local X→Y→Z rotations with a separate synthetic noncommuting multi-axis example; the live visible boards alone do not prove every Euler-order case. Assert containers create no render meshes, support meshes/labels/selectables are absent, linked DOR uses actual door component geometry/materials, and non-door material IDs/grain remain explicit. Projection tests assert the same visible solids and extents as the scene, handedness is preserved, depth sorting handles the opaque assembled view, and no WebView/Android dependency is needed.

- [ ] **Step 2: Run the scene-payload tests to verify they fail**

Run: `./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.viewer3d.WingScenePayloadTest --tests com.kkc.sheettracker.viewer3d.WingThumbnailProjectionTest`

Expected: FAIL because payload generation does not exist.

- [ ] **Step 3: Implement payload, world matrices, and box-intersection measurements**

Use matrix multiplication rather than ancestor-offset addition. Encode intrinsic size, dimensions, material references, grain axes, selection labels, and transparent-cap inspection data; explode displacement belongs only in JavaScript display state.

Use column vectors with `local = T * Rz * Ry * Rx`, then `world = parentWorld * local`, in inches and degrees converted to radians. Boxes occupy local `[0,width] × [0,height] × [0,depth]`; Three.js centered box geometry must be offset accordingly. Preserve the root's exported parent-frame transform, then recenter only for display fitting. Send the composed matrices directly to Three.js, avoiding a second Euler interpretation. Measure intersections in oriented board/cap coordinates, not expanded world AABBs for arbitrarily rotated boards. Implement the pure, fixed-view thumbnail projection with face depth ordering from this payload.

- [ ] **Step 4: Run the scene-payload tests to verify they pass**

Run: `./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.viewer3d.WingScenePayloadTest --tests com.kkc.sheettracker.viewer3d.WingThumbnailProjectionTest`

Expected: PASS.

- [ ] **Step 5: Commit**

`git commit -m "feat(viewer): build island wing scene payloads"`

### Task 4: Extend the local renderer and texture bridge

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/viewer3d/WingTextureBridge.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/viewer3d/ViewerServer.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/viewer3d/Model3DPane.kt`
- Modify: `app/src/main/assets/viewer/viewer.html`
- Create: `app/src/main/assets/viewer/js/wing-viewer.js`
- Create: `app/src/main/assets/viewer/js/wing-scene.js`
- Create: `app/src/test/js/wingScene.test.mjs`
- Create: `app/src/test/java/com/kkc/sheettracker/viewer3d/WingTextureBridgeTest.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/viewer3d/ViewerServerTest.kt`
- Create: `app/src/androidTest/java/com/kkc/sheettracker/viewer3d/WingTextureBridgeDeviceTest.kt` (executed with Task 5's runtime gate)

**Interfaces:**
- Consumes: `WingScenePayload`, `CvMaterialTextureLibrary`, and the existing `loadDoorTextureBitmap` implementation.
- Produces: localhost payload/texture routes and `Model3DPane` wing mode with callbacks for fit/reset, view, explode, selection, and joint inspection.
- Produces: testable scene/controller logic in `wing-scene.js`; `wing-viewer.js` owns DOM/bridge bootstrap. Import the bundled Three.js module by a local relative path in testable code so Node tests need no new package, CDN, or service. Wing mode receives resolved canvas/selection/annotation theme colors from Compose and reports ready/error, selection, and texture status to the native host.

- [ ] **Step 1: Write failing bridge/server tests**

Assert safe payload/texture paths cannot escape the cache root; valid material ID is preferred over name fallback; invalid hash/missing JXL produces no texture route and white metadata; physical width/height/tile/grain metadata is preserved; late arrival triggers a bounded reload rather than failing the scene.

Add executable Node tests for single-scene creation, the exact selected wing, payload matrices, hidden supports, component materials, texture failure/late replacement, Fit versus Reset, standard views, display-only explode, joint mode, and renderer disposal. Use injected loading/render scheduling dependencies rather than requiring Android or a GPU for controller tests. Assert wing bootstrap does not start the existing room renderer or fetch any GLB. Keep native JXL success/hash/decode checks in Android instrumentation; JVM tests use an injected decoder and must not treat Android bitmap stubs as successful native decoding.

- [ ] **Step 2: Run the bridge/server tests to verify they fail**

Run: `./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.viewer3d.WingTextureBridgeTest --tests com.kkc.sheettracker.viewer3d.ViewerServerTest`

Expected: FAIL because wing routes/bridge do not exist.

Run separately: `node --test app/src/test/js/wingScene.test.mjs`

Expected: FAIL because scene/controller exports do not exist.

- [ ] **Step 3: Implement the native-to-WebView bridge and single-scene renderer**

Reuse the native decoder/cache to render cache-local browser-safe bitmaps; server routes must be read-only, scoped, and cleaned on dispose. In `wing-viewer.js`, use one scene/camera/OrbitControls instance; construct depth-correct meshes, physical repeat/clamp textures, transparent-cap mode, measured selection labels, standard views, fit/reset, and display-only explode offsets.

Choose either room or wing bootstrap in `viewer.html` before constructing any renderer. Preserve existing room mode and its tests. Wing mode hides room-only HTML controls; all inspection controls/text live in Compose per the UI contract. Native decode/encoding runs on IO, and changing available textures replaces material maps without resetting the camera or selected wing. Reuse the existing demand-driven render loop; dispose geometry/materials/textures, controls, listeners, scheduled work, and session routes when closing or replacing the scene.

- [ ] **Step 4: Run the bridge/server tests to verify they pass**

Run: `./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.viewer3d.WingTextureBridgeTest --tests com.kkc.sheettracker.viewer3d.ViewerServerTest`

Run separately: `node --test app/src/test/js/wingScene.test.mjs`

Expected: both suites PASS. Android decoder tests remain subject to the runtime gate; a passing mock decoder does not prove native JXL success.

- [ ] **Step 5: Commit**

`git commit -m "feat(viewer): render textured island wings locally"`

### Task 5: Add the shared Compose wing viewer host

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/viewer3d/WingViewerScreen.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/viewer3d/WingViewerControls.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/viewer3d/WingThumbnail.kt`
- Create: `app/src/androidTest/java/com/kkc/sheettracker/viewer3d/WingViewerScreenTest.kt`
- Create: `app/src/androidTest/java/com/kkc/sheettracker/viewer3d/WingThumbnailTest.kt`

**Interfaces:**
- Consumes: one `CvWingGeometry`, parsed doors from the same snapshot, Ready Jobs base path, Task 3's payload/projection helpers, and `onDismiss`.
- Produces: `@Composable fun WingViewerScreen(wing: CvWingGeometry, doorsById: Map<String, DoorPreviewData>, readyJobsBasePath: String, onDismiss: () -> Unit)`.
- Produces: `@Composable fun WingThumbnail(scene: WingScenePayload, readyJobsBasePath: String, onOpen: () -> Unit, modifier: Modifier = Modifier)`. Pure projection is remembered by scene; texture work uses the existing IO/cache path. The composable draws depth-sorted faces on native Canvas and contains no WebView, server, GL context, or animation loop. Use it for both the compact and expanded wing preview.

- [ ] **Step 1: Write failing Compose tests**

Assert a single selected wing name is announced, close/system Back dismiss identically, all standard controls have labels and 48dp targets, and texture status is non-blocking. Assert controls issue real renderer commands after readiness, Fit preserves view/modes, Reset restores defaults, part selection updates the native inspection panel, errors permit Retry/dismissal, and closing destroys the WebView/server/session resources and releases interaction/blur-gate state. Use a test host/factory seam to count resource acquisition and release rather than inferring cleanup from the dialog disappearing.

Thumbnail tests cover all four fixture wings, depth ordering, identical visible supports/material fallback to fullscreen, 48dp click semantics, and zero renderer/server creations while composing and scrolling rows. Add Android decoder coverage for valid JXL, hash mismatch, invalid bytes, and late arrival. Verify UI-contract color roles, theme updates, long names, portrait/landscape, and font scale 1.3 without clipping. Save native screenshots for comparison with existing Door Assembly/Specialty surfaces.

- [ ] **Step 2: Compile the new test references to verify the host is absent**

Run: `./gradlew.bat assembleDebugAndroidTest`

Expected: compilation FAIL because the new host/thumbnail references do not exist. This is a compile check, not execution of the assertions.

- [ ] **Step 3: Implement the Material 3 host**

Implement the fullscreen inspection dialog, native controls, thumbnail, error/status text, and bottom inspection panel exactly as the UI contract specifies. Create no WebView until the fullscreen viewer is opened, retain one explicitly selected wing, and dispose the old scene before replacing its identity. Synchronize controls only after renderer readiness and forward disposal to server/WebView cleanup. Preserve the opaque assembled default; keep theme and display-mode state out of canonical geometry.

- [ ] **Step 4: Compile the Android tests**

Run: `./gradlew.bat assembleDebugAndroidTest`

Expected: compilation PASS; runtime status remains separate.

- [ ] **Step 5: Execute Android assertions only when exact artifacts are already installed**

After satisfying Verification policy, run on the designated test device (replace `<test-serial>`):

`adb -s <test-serial> shell am instrument -w -e class com.kkc.sheettracker.viewer3d.WingViewerScreenTest,com.kkc.sheettracker.viewer3d.WingThumbnailTest,com.kkc.sheettracker.viewer3d.WingTextureBridgeDeviceTest com.kkc.sheettracker.test/androidx.test.runner.AndroidJUnitRunner`

Expected: instrumentation reports `OK` with the intended tests executed, no ignored failures, and native/WebView evidence for rendering, texture decode, selection, and disposal. Without the required artifacts/device, mark this step **pending**, not passed. Do not install either APK to satisfy it.

- [ ] **Step 6: Commit**

`git commit -m "feat(viewer): add shared island wing screen"`

### Task 6: Wire Specialty and Door Assembly entry points

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt`
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/CvJobViewerSnapshotState.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreenLogicTest.kt`
- Modify after integrating current user work: `app/src/main/java/com/kkc/sheettracker/ui/doorpreview/DoorAssemblyList.kt`
- Modify after integrating current user work: `app/src/main/java/com/kkc/sheettracker/ui/doorpreview/DoorPreviewFullscreen.kt`
- Modify after integrating current user work: `app/src/main/java/com/kkc/sheettracker/ui/doorpreview/DoorAssemblyCategory.kt`
- Modify: `app/src/androidTest/java/com/kkc/sheettracker/ui/doorpreview/DoorPreviewFullscreenTest.kt`
- Create: `app/src/androidTest/java/com/kkc/sheettracker/ui/specialty/SpecialtyWingViewerTest.kt`
- Create: `app/src/androidTest/java/com/kkc/sheettracker/ui/components/CvJobViewerSnapshotStateTest.kt`

**Interfaces:**
- Consumes: `loadCvJobViewerSnapshot`, `resolveSpecialtyWings`, `resolvePaneledEndWing`, `WingThumbnail`, and `WingViewerScreen`.
- Produces: `@Composable fun rememberCvJobViewerSnapshot(basePath: String, jobFolderName: String, generation: Long): State<CvJobViewerSnapshot?>`. Keep a keyed internal loader seam for tests; create a fresh state for each full key, start IO work in a keyed coroutine, and publish only to that key's state. A previous request completing after cancellation cannot replace a newer snapshot.
- Extends `DoorAssemblyList` with `wings: CvJobWings` while preserving existing callers through an unavailable default. Its existing `previews` and new `wings` must come from the same snapshot in `HardwoodsWorkspaceScreen`.
- Produces: clear `3D` Specialty action, explicit wing picker, and paneled-end full-wing handoff.

- [ ] **Step 1: Write failing specialty and paneled-end integration tests**

Keep pure resolution/action-state assertions in the existing Specialty logic tests. Put actual Compose button/picker assertions in `SpecialtyWingViewerTest`: linked CUSTOM exposes `3D` beside unchanged View, one candidate opens it, multiple require tapping a named row, Cancel opens nothing, and unavailable geometry adds no action. Assert touch targets and wrapping against the UI contract.

Extend door instrumentation to assert a unique link replaces both the header thumbnail and expanded drawing with the full wing and opens that same wing. Missing/ambiguous links retain the ordinary preview; progress/filter/sort/component/PDF actions remain usable. Snapshot-state tests use a controlled suspending loader to complete generation A after generation B, change job/base path while loading, and transition valid-to-malformed/absent wing data; only the current paired result may reach the UI. A refresh invalidates any old picker/open wing tied to the previous snapshot, dismissing it rather than substituting another wing.

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run sequentially as separate commands:

`./gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.ui.specialty.SpecialtyJobDetailScreenLogicTest`

`./gradlew.bat assembleDebugAndroidTest`

Expected: new pure assertions fail or new Android references fail compilation because handoffs/state wiring do not exist. Android assertions have not executed at this stage.

- [ ] **Step 3: Implement both handoffs without altering existing workflows**

Replace the door-only `produceState` loader in `HardwoodsWorkspaceScreen` with `rememberCvJobViewerSnapshot`, keyed by `scanState.snapshot.basePath`, `jobFolderName`, and `scanState.snapshot.generation`. Derive door previews and wings from that one result and pass both to `DoorAssemblyList`; do not independently reopen the export inside a row/fullscreen host. Use the same keyed loading helper in Specialty and derive its candidates/door materials from that snapshot. On job/rescan changes publish a fresh loading state, clear obsolete picker/selection state, and cancel old IO; malformed wings must still permit valid ordinary door previews.

Specialty adds only the styled 3D action/picker and retains View/progress/edit. After satisfying the category-work integration prerequisite, substitute `WingThumbnail` and the shared fullscreen host only for a unique `doorId` linkage; retain ordinary `DoorPreviewFullscreen` behavior otherwise. Do not load or create render resources during row resolution.

- [ ] **Step 4: Run targeted tests to verify they pass**

Run the same two commands from Step 2 sequentially.

Expected: JVM suite PASS and Android test compilation PASS. Under the same exact-installed-artifact gate as Task 5, execute:

`adb -s <test-serial> shell am instrument -w -e class com.kkc.sheettracker.ui.specialty.SpecialtyWingViewerTest,com.kkc.sheettracker.ui.components.CvJobViewerSnapshotStateTest,com.kkc.sheettracker.ui.doorpreview.DoorPreviewFullscreenTest com.kkc.sheettracker.test/androidx.test.runner.AndroidJUnitRunner`

Expected: intended instrumentation tests execute and report `OK`. Otherwise record **compiled, not executed** and keep Android integration acceptance pending. Never satisfy this gate by installing or deploying.

- [ ] **Step 5: Commit**

`git commit -m "feat: open island wings from specialty and doors"`

### Task 7: Run the complete regression/build validation

**Files:**
- Modify only if verification exposes an implementation defect: owning source/test from Tasks 1–6.

- [ ] **Step 1: Run all unit and JavaScript regression tests**

Run: `./gradlew.bat testDebugUnitTest`

Run separately: `node --test app/src/test/js`

Expected: both suites PASS with no weakened assertions, including existing room-viewer render-loop/status tests.

- [ ] **Step 2: Build the debug APK**

Run: `./gradlew.bat assembleDebug`

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 3: Compile Android tests and reconcile runtime evidence**

Run: `./gradlew.bat assembleDebugAndroidTest`

Expected: compilation PASS and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` exists. Collect Task 5/6 instrumentation results against the exact final build; rerun those direct instrumentation commands only if that final application/test pair is already installed on the designated device. Record executed count, failures, device identity, artifact identity, and pending checks. Compiled test APKs or screenshots of an older build do not satisfy runtime acceptance.

- [ ] **Step 4: Run the native visual acceptance matrix when the same runtime gate is satisfied**

Capture native tablet evidence for the UI contract: light/dark plus a custom theme, portrait/landscape, font scale 1.3, current-app comparisons, all four wing thumbnails, paneled-end handoff, Specialty picker/Cancel, selected part/joint measurements, errors/Retry, and texture fallback/late arrival. Use one batched visual pass and, if defects require it, one confirmation pass. Restore test-device display/theme settings changed for verification. Do not install, uninstall, or deploy. Without the exact installed build, mark visual acceptance **pending**.

- [ ] **Step 5: Commit any verification fixes separately and report acceptance accurately**

`git commit -m "fix(viewer): address island wing validation"`

Report JVM/Node tests, APK build, Android compilation, Android execution, and native/WebView visual acceptance as separate results. Task 7 remains incomplete while required runtime/visual acceptance is pending; do not report a fully verified feature from build success alone.

## Plan self-review

- Pinned fixture/provenance, parser/material/backward compatibility: Tasks 1 and 4.
- Atomic export loading, job/scan refresh, stale selection cancellation: Tasks 1 and 6.
- Exact specialty/door linking and single-wing behavior: Tasks 2 and 6.
- Parent transforms, hidden supports, dimensions, and joinery: Task 3.
- Existing texture contract and offline behavior: Task 4.
- Native full-wing thumbnail/projection with no per-row WebView: Tasks 3, 5, and 6.
- Current-app visual system, accessibility, theme/inset/overflow behavior, and lifecycle: Tasks 4–7 and the UI contract.
- Existing specialty/door workflows and final build: Tasks 6 and 7.
- Executable JavaScript/controller tests versus Android compile/runtime/visual gates: Tasks 4–7 and Verification policy.

All spec requirements are assigned to tasks. Fixture-dependent tests use the pinned local copy and independent transform goldens. The absent category-work integration source and availability of an exact installed test build are explicit prerequisites, not assumed completed work. No implementation, installation, or runtime verification is authorized merely by editing this plan.
