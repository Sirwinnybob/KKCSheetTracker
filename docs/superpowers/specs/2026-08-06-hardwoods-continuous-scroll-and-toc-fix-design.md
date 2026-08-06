# Hardwoods Continuous-Scroll Parity + Reference Viewer TOC Fix

## Context

`UnifiedReferenceViewer` ([UnifiedReferenceViewer.kt](../../../app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt)) has a `continuousScrollEnabled: Boolean = false` param that switches between two internal panes: the single-page `ReferencePdfPane` branch (`if (!continuousScrollEnabled) {...}`, line 712) and the `ContinuousReferencePdfPane` branch (the `else` branch, line 778 onward), which is also where `PdfLabelScrollbar` renders (line 821). `AssemblyViewerScreen` and `ReferencePdfViewerScreen` each own a `continuousScrollDefault`-seeded, `rememberSaveable` `continuousScrollEnabled` state plus a toggle `IconButton`, letting the user switch modes and reach the scrollbar.

Sub-agent investigation (multi-agent verification of jump/search behavior across modes, requested alongside this fix) surfaced two separate issues:

1. **`HardwoodsWorkspaceScreen`** never received this plumbing at all — no `continuousScrollDefault` param, no local state, no toggle button. Its `UnifiedReferenceViewer` call ([HardwoodsWorkspaceScreen.kt:2397](../../../app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt#L2397)) always falls through to the `continuousScrollEnabled = false` default, so continuous mode — and the scrollbar — is structurally unreachable on the hardwoods cut list screen, regardless of the global "Continuous scroll" Appearance setting.
2. **`ReferencePdfViewerScreen`** loses its Sheet Navigator (TOC) button and prev/next arrows entirely once continuous mode is toggled on. Root cause: `onOpenSheetNavigator` ([UnifiedReferenceViewer.kt:754](../../../app/src/main/java/com/kkc/sheettracker/ui/viewer/UnifiedReferenceViewer.kt#L754)) — the callback that opens the Sheet Navigator sheet — is only passed to `ReferencePdfPane`, which is also the only place the TOC button and paging arrows are drawn (`ReferencePdfPane.kt:802-859`). `AssemblyViewerScreen` avoids this because it drives the navigator externally via its own `tocRequestToken`/`onOpenToc` counter in its TopAppBar, bypassing the broken internal path entirely — `ReferencePdfViewerScreen` never adopted that pattern and has no fallback.

(Same investigation also flagged `SheetViewerScreen` and `ReferenceModalOverlay` as similarly missing the `continuousScrollDefault` thread-through — out of scope here, noted for a future pass.)

Everything else checked out clean: the shared `onDisplayPageChange` callback and `clampedDisplayPage` computation feed both `ReferencePdfPane` and `ContinuousReferencePdfPane` identically, so in-viewer search (`searchFilteredRows`, cabinet/room lookup, tap-to-jump) and `scrollToPage` already work correctly in both modes wherever they're reachable at all.

## Part A — Hardwoods continuous-scroll parity

Mirror the existing `AssemblyViewerScreen`/`ReferencePdfViewerScreen` pattern:

1. **`HardwoodsWorkspaceScreen`** ([HardwoodsWorkspaceScreen.kt:346](../../../app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt#L346)) gains a new param `continuousScrollDefault: Boolean = false`, and a local `var continuousScrollEnabled by rememberSaveable(jobFolderName) { mutableStateOf(continuousScrollDefault) }`, matching [AssemblyViewerScreen.kt:373](../../../app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt#L373).
2. **Toggle button**: a new `IconButton` in the `KKCTopAppBar` `actions` block ([HardwoodsWorkspaceScreen.kt:993-1021](../../../app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt#L993-L1021)), next to the existing "Show/Hide PDF" `TextButton`, using the same `Icons.Default.ViewDay` / `Icons.AutoMirrored.Filled.MenuBook` pair as Assembly. Rendered only when `showReferencePane` is `true` — toggling scroll mode for a hidden pane has no visible effect and would be confusing to expose.
3. **Wire into the viewer call**: `continuousScrollEnabled = continuousScrollEnabled` added to the `UnifiedReferenceViewer(...)` call at [HardwoodsWorkspaceScreen.kt:2397](../../../app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt#L2397). `hazeState` stays unset (component already falls back to a solid translucent panel per its own doc comment — this screen has no existing shared `HazeState` to plumb in, and adding one is out of scope). `isSplitPaneActive` stays default `false` — this screen has no split-pane layout.
4. **Thread the default through `NavGraph.kt`** at both `HardwoodsWorkspaceScreen(...)` call sites — [NavGraph.kt:1630](../../../app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt#L1630) (inside `JobsTabHost`, which already receives `continuousScrollDefault` as a param at line 1097) and [NavGraph.kt:2790](../../../app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt#L2790) (inside the function whose `continuousScrollDefault` param is declared at line 1977) — passing `continuousScrollDefault = continuousScrollDefault` in both. No new param needs to be threaded further up; both enclosing functions already receive it from `MainActivity`'s existing `continuous_scroll_default` shared-preference-backed state.

No new setting is needed — the existing global "Continuous scroll" switch in `SettingsScreen`'s Appearance card now also seeds the hardwoods screen, same as it already does for Assembly and the single-doc reference viewer.

## Part B — Restore Sheet Navigator access on `ReferencePdfViewerScreen` in continuous mode

Adopt `AssemblyViewerScreen`'s external-token pattern instead of changing `UnifiedReferenceViewer` itself (the shared component's `tocRequestToken` param is already plumbed correctly into both branches — confirmed by the jump/TOC investigation — so no change is needed there):

1. **`ReferencePdfViewerScreen`** ([ReferencePdfViewerScreen.kt](../../../app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt)) gains a local `var tocRequestToken by remember { mutableIntStateOf(0) }`.
2. **New TOC `IconButton`** in its top bar (alongside the existing continuous-scroll toggle at line 129), reusing `Icons.Default.UnfoldMore` with `contentDescription = "Sheet list"` — the same icon/label `AssemblyViewerScreen`'s per-pane floating nav bar uses for its own `onOpenToc` button ([AssemblyViewerScreen.kt:1190-1196](../../../app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt#L1190-L1196)), for visual/semantic consistency across the app. `onClick` increments `tocRequestToken`.
3. **Pass `tocRequestToken = tocRequestToken`** into the `UnifiedReferenceViewer(...)` call at [ReferencePdfViewerScreen.kt:141](../../../app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt#L141) (currently not passed at all). The existing `LaunchedEffect(tocRequestToken) { if (tocRequestToken > 0) showSheetNavigator = true }` inside `UnifiedReferenceViewer` (line 835-837) already sits outside the single-page/continuous branch split, so this reaches both modes with no further change.
4. **Single-page mode stays unaffected**: the internal `onOpenSheetNavigator`-driven button in `ReferencePdfPane` continues to work exactly as before; the new external button is simply an always-available alternative entry point, matching how Assembly's screen behaves in both modes today.

Prev/next paging arrows remain single-page-mode-only, by design — continuous scroll is inherently free-scroll, and restoring dedicated arrows there is out of scope (YAGNI) unless a future request calls for it.

## Edge cases

- **Hardwoods 3D pane**: `HardwoodsWorkspaceScreen`'s `jumpTarget == HardwoodsJumpTarget.THREE_D` branch renders `Model3DPane` instead of `UnifiedReferenceViewer` ([HardwoodsWorkspaceScreen.kt:2384-2395](../../../app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt#L2384-L2395)) — the new toggle button has no effect while 3D is showing; it only needs to be visible/relevant when `showReferencePane` is true and a PDF doc type is active, which the existing `showReferencePane` gate already covers.
- **`jobFolderName` key on the `rememberSaveable`**: matches Assembly's pattern — navigating to a different job resets the toggle to the global default rather than leaking the previous job's per-session choice.
- **`ReferencePdfViewerScreen` token collision**: `tocRequestToken` is purely a monotonically-incrementing trigger (matches Assembly's `onOpenToc` semantics) — no reset logic needed, `LaunchedEffect(tocRequestToken)` fires on every increment including repeats of the same nonzero value only if the value actually changes, which `+= 1` on each click guarantees.

## Testing

No existing unit tests cover `HardwoodsWorkspaceScreen` or `ReferencePdfViewerScreen` UI wiring at this level (both are Compose screens driven by device/manual verification per project convention — see [PdfLabelScrollbarTest.kt](../../../app/src/test/java/com/kkc/sheettracker/ui/components/PdfLabelScrollbarTest.kt) for the one existing test, which covers `PdfLabelScrollbar`'s internal logic, not screen wiring). Manual on-device verification:

1. Open a hardwoods job's cut list with the global "Continuous scroll" Appearance setting OFF — confirm the toggle button appears in the top bar (when PDF pane shown) and starts in single-page mode.
2. Tap the toggle — confirm the view switches to continuous scroll and the scrollbar appears on the right edge.
3. Toggle the global Appearance setting ON, reopen a hardwoods job — confirm it now opens directly in continuous mode.
4. Hide the PDF pane ("Hide PDF") — confirm the toggle button disappears; show it again — confirm it reappears in its last state.
5. On `ReferencePdfViewerScreen` (single-doc reference viewer, e.g. from a CNC job), toggle continuous scroll on — confirm the new TOC button opens the Sheet Navigator sheet and tapping a result jumps to the correct page with a smooth animated scroll.
6. Toggle continuous scroll off on the same screen — confirm the original internal TOC button and prev/next arrows still work as before (no regression to single-page mode).
7. Repeat step 5 with a job that has plan-view pages / bucketed display — confirm navigator entries and jump targets match single-page mode's behavior.
