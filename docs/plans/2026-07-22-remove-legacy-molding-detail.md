# Remove Legacy Molding Detail Route & Unified Overlay Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove legacy `MoldingDetailScreen.kt` and `standards/molding/detail` route in `NavGraph.kt`, unifying all molding profile viewing inside `MoldingListScreen` via the modern full-screen `MoldingDetailOverlay`.

**Architecture:** In-place overlay transition using `SharedTransitionLayout` / `AnimatedVisibility` in `MoldingListScreen`, eliminating route navigation pushes.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Android SDK.

---

### Task 1: Clean up `MoldingListScreen.kt` Nav Callback Signature

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/standards/MoldingListScreen.kt`

**Step 1: Simplify `MoldingListScreen` parameters**
- Remove unused `onOpenMolding` parameter (or make it optional defaulted to null).
- Ensure card click ONLY sets `expandedItem = item` to trigger the in-place `MoldingDetailOverlay`.

**Step 2: Verify compilation**
Run: `.\gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/ui/standards/MoldingListScreen.kt
git commit -m "refactor(standards): remove onOpenMolding route trigger from MoldingListScreen"
```

---

### Task 2: Remove Legacy `MoldingDetailScreen.kt` File

**Files:**
- Delete: `app/src/main/java/com/kkc/sheettracker/ui/standards/MoldingDetailScreen.kt`

**Step 1: Delete `MoldingDetailScreen.kt`**
Run: `git rm app/src/main/java/com/kkc/sheettracker/ui/standards/MoldingDetailScreen.kt`

**Step 2: Commit**
```bash
git commit -m "refactor(standards): remove legacy MoldingDetailScreen.kt"
```

---

### Task 3: Remove Legacy Detail Route from `NavGraph.kt`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt`

**Step 1: Update `standards/molding` composable route in `NavGraph.kt`**
- Remove `onOpenMolding` parameter from `MoldingListScreen` call.
- Remove the `composable("standards/molding/detail")` block entirely.
- Remove any secondary `MoldingDetailScreen` calls (such as line 3010).

**Step 2: Run full build verification**
Run: `.\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**
```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "refactor(standards): remove standards/molding/detail route from NavGraph"
```
