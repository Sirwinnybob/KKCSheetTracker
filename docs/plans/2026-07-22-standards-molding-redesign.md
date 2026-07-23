# Standards & Molding UI Modernization Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Modernize the Standards & Molding UI (`StandardsHubScreen`, `MoldingListScreen`, `MoldingDetailOverlay`) with rich visual depth matching Job Board and Supply Kanban, dark/light theme aware SVG previews (pure white background in light mode, pure black in dark mode with white vector lines), default Crown category selection, top AppScaffold navbar search, and an interactive full-screen molding detail popup.

**Architecture:** Jetpack Compose, Material 3, Coil SVG decoder, shared element overlay transition using `SharedTransitionLayout` / `AnimatedVisibility`, and `LocalNavBarDecoration` top navbar search.

**Tech Stack:** Kotlin, Jetpack Compose, Coil, Material 3, Android SDK.

---

### Task 1: Update `MoldingLibraryScreenLogic` (Default Crown & Search Helper)

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/standards/MoldingLibraryScreenLogic.kt`

**Step 1: Write logic to prioritize "Crown" category as default**

```kotlin
fun defaultCategory(library: MoldingLibrary): String? {
    if (library.categories.isEmpty()) return null
    return library.categories.firstOrNull { 
        it.equals("Crown", ignoreCase = true) || it.contains("Crown", ignoreCase = true)
    } ?: library.categories.firstOrNull()
}
```

**Step 2: Add `searchMoldings` helper function for query filtering**

```kotlin
fun searchMoldings(
    library: MoldingLibrary,
    selectedCategory: String?,
    query: String
): List<MoldingLibraryItem> {
    val trimmed = query.trim()
    return if (trimmed.isEmpty() && selectedCategory != null) {
        moldingsForCategory(library, selectedCategory)
    } else if (trimmed.isEmpty()) {
        library.moldings
    } else {
        library.moldings.filter { item ->
            item.name.contains(trimmed, ignoreCase = true) ||
            item.fileId.contains(trimmed, ignoreCase = true) ||
            item.category.contains(trimmed, ignoreCase = true)
        }
    }
}
```

**Step 3: Verify compilation**
Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/standards/MoldingLibraryScreenLogic.kt
git commit -m "feat(standards): add default Crown category selection and molding search helper"
```

---

### Task 2: Create `MoldingDetailOverlay` Component

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/standards/MoldingDetailOverlay.kt`

**Step 1: Implement full-screen overlay composable**
- Top 2/3rds: Interactive large profile preview container (`Color.White` in Light Mode, `Color.Black` in Dark Mode, `ColorFilter.tint(Color.White)` in Dark Mode for inverted lines). Support pinch-to-zoom, pan gestures, and double-tap zoom reset.
- Floating top-right controls: Close button + Measurements toggle button (defaulting to `showMeasurements = true`).
- Bottom 1/3rd: Elevated scrollable card sheet showing job usage list ("Used on X jobs").

**Step 2: Verify compilation**
Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/standards/MoldingDetailOverlay.kt
git commit -m "feat(standards): create interactive full-screen MoldingDetailOverlay component"
```

---

### Task 3: Modernize `MoldingListScreen` with Top Navbar Search & Shared Transition

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/standards/MoldingListScreen.kt`

**Step 1: Add `LocalNavBarDecoration` search integration (`NavBarSearchDecoration`)**
- Register `searchDecoration` when active, allowing search by profile name or file ID.

**Step 2: Update default `showMeasurements` state in grid view to `false`**

**Step 3: Enhance `MoldingCard` styling**
- Pure white background in Light mode (`Color.White`), pure black background in Dark mode (`Color.Black`).
- SVG line color filter tinting to pure white in dark mode.
- Elevated surface cards with subtle borders (`1.dp`) and rounded corners (`14.dp`).

**Step 4: Connect `MoldingDetailOverlay` via `SharedTransitionLayout` / `AnimatedVisibility`**

**Step 5: Verify compilation**
Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/standards/MoldingListScreen.kt
git commit -m "feat(standards): integrate top navbar search, theme-aware previews, and molding overlay in MoldingListScreen"
```

---

### Task 4: Upgrade `StandardsHubScreen` Visual Depth & Styling

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/standards/StandardsHubScreen.kt`

**Step 1: Redesign `StandardsTileCard`**
- Circular icon badge container with primary container colors.
- 16dp rounded corners, elevated card surface, subtle border outline, and press feedback matching Job Board and Supply Kanban.

**Step 2: Verify full build**
Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/standards/StandardsHubScreen.kt
git commit -m "feat(standards): redesign StandardsHubScreen tile cards to match Job Board and Supply Kanban styling"
```
