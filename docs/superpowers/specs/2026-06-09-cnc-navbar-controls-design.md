# CNC Sheet Viewer — Navbar Controls & Part List Scroll Fix

**Date:** 2026-06-09  
**Branch:** theme-spacing-upgrade

---

## Context

The app has a floating pill navbar (Dashboard, Jobs, Search, Calc, Hours, Settings). In Assembly/PDF viewer mode the navbar already expands upward to expose a search bar above the icon row, using the `NavBarSearchDecoration` / `LocalNavBarDecoration` CompositionLocal pattern.

The CNC sheet viewer (`SheetViewerScreen`) currently uses a `Scaffold` `bottomBar` slot (`BottomActionBar`) for sheet navigation (← / Sheet X of Y / →), Skip, and Complete. This bar sits behind/below the floating navbar, making it partially unreachable. The part list modal (sheet TOC) also lacks enough bottom padding for the last item to scroll clear of the navbar.

**Goal:** Remove `BottomActionBar`, move all its controls into the main navbar expansion (same pattern as Assembly search), add a CNC part-search modal, and fix the part list scroll padding.

---

## Design

### 1. `NavBarCncDecoration` data class

**File:** `app/src/main/java/com/kkc/sheettracker/ui/components/NavBarDecoration.kt`

Add alongside `NavBarSearchDecoration`:

```kotlin
data class NavBarCncDecoration(
    val currentPage: Int,
    val totalPages: Int,
    val sheetStatus: SheetStatus,
    val onPrevPage: () -> Unit,
    val onNextPage: () -> Unit,
    val onOpenToc: () -> Unit,
    val onToggleSkip: () -> Unit,
    val onToggleComplete: () -> Unit,
    val onOpenSearch: () -> Unit
)
```

Add to `NavBarDecorationState`:
```kotlin
var cncDecoration: NavBarCncDecoration? by mutableStateOf(null)
```

---

### 2. SheetViewerScreen — push decoration, remove bottom bar

**File:** `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`

- Remove `bottomBar = { BottomActionBar(...) }` from the `Scaffold`.
- Remove the `BottomActionBar` private composable (lines ~2707–2771).
- Add a `SideEffect` that sets/clears `navBarDeco.cncDecoration` based on `showUi`, wiring in the same callbacks that were passed to `BottomActionBar`.
- Add a `CncSearchModal` composable (new, see §4) and show it when `showCncSearch` state is true; pass `onOpenSearch = { showCncSearch = true }` into the decoration.

---

### 3. AppScaffold navbar expansion

**File:** `app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt`

Extend the `AnimatedContent` in the navbar to handle a third state: `cncDecoration != null`. When active, render above the icon row:

```
┌─────────────────────────────────────────────┐
│  [←]   Sheet 3 of 12   [→]  [🔍]  [Skip] [✓Done] │
│  ────────────────────────────────────────── │
│   🏠    💼    🔍    🧮    ⏱    ⚙️           │
└─────────────────────────────────────────────┘
```

- **← →** call `onPrevPage` / `onNextPage`; disabled at boundaries.
- **Sheet X of Y** label is a tappable button → `onOpenToc`.
- **🔍** icon button → `onOpenSearch`.
- **Skip** button: amber fill when `sheetStatus == SKIPPED`, label toggles "Skip"/"Unskip".
- **Complete / Done** button: green fill when `isComplete` (`COMPLETE` or `HAS_BAD_PARTS`), label toggles "Complete"/"Done".
- Use the same padding, corner radius animation, and `AnimatedContent` transition as the search expansion (`tween(220)` fade).

The existing search decoration branch is unchanged; `cncDecoration` and `searchDecoration` are mutually exclusive in practice (different screens).

---

### 4. CNC Search Modal

**File:** `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`

New private composable `CncSearchModal`, shown as a `ModalBottomSheet` (same style as `SheetNavigatorToc`).

- Text field to type a cabinet number, name, or room.
- Live-filters the current material's `pageMeta` list: a sheet matches if any of its parts contain the query string (case-insensitive) in `cabinetNumber`, `cabinetName`, or `room`.
- Results list: each row shows sheet number + matched part info; tap → jump to that page and dismiss modal.
- "No matches" empty state when nothing found.
- Search state (`searchText`, `filteredPages`) is local to `SheetViewerScreen` and reset when the modal closes.

---

### 5. Part list scroll fix

**File:** `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`

The `LazyColumn` inside `SheetNavigatorToc` (line ~1509) uses `contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)`. Update to:

```kotlin
contentPadding = PaddingValues(
    horizontal = 12.dp,
    top = 8.dp,
    bottom = 112.dp   // matches assembly viewer — clears floating navbar + gesture bar
)
```

Apply the same fix to any other `LazyColumn` in `SheetViewerScreen` that renders a list visible behind the navbar.

---

## Files to Modify

| File | Change |
|------|--------|
| `ui/components/NavBarDecoration.kt` | Add `NavBarCncDecoration`, extend `NavBarDecorationState` |
| `ui/components/AppScaffold.kt` | Extend navbar `AnimatedContent` for CNC decoration |
| `ui/viewer/SheetViewerScreen.kt` | Remove `BottomActionBar`, add `SideEffect`, add `CncSearchModal`, fix scroll padding |

---

## Verification

1. Open a CNC job → navigate to a sheet. Confirm the bottom action bar is gone and the navbar pill expands to show navigation + Skip + Complete controls.
2. Tap ← / → to step through sheets. Confirm disabled state at first/last sheet.
3. Tap "Sheet X of Y" — sheet navigator TOC opens.
4. Tap 🔍 — search modal opens. Type a cabinet number or room. Confirm filtered results appear and tapping a result jumps to that sheet.
5. Tap Skip — button turns amber and label becomes "Unskip". Tap again — reverts.
6. Tap Complete — button turns green and label becomes "Done". Tap again — reverts.
7. Open the sheet TOC modal and scroll to the last sheet — confirm it is fully visible above the navbar.
8. Open Assembly viewer — confirm search decoration still works and is unaffected.
