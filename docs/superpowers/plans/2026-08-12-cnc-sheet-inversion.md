# CNC Sheet Inversion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Invert only the displayed CNC sheet bitmap when the tablet has timed out or when dark mode is active with standard sheets disabled.

**Architecture:** Keep the decision in `SheetViewerScreen` as a pure helper that receives idle phase, theme state, and the standard-sheets preference. Pass its Boolean result into both existing bitmap viewers, where an image-only color matrix applies inversion without changing the rendered bitmap, caches, markup, or controls.

**Tech Stack:** Kotlin, Jetpack Compose, Android graphics color filters, JUnit JVM tests, Gradle.

## Global Constraints

- Invert only CNC sheet and diagram images; do not invert controls, selection highlights, or markup strokes.
- Enable inversion for `DIMMED` and `SYNC_PAUSED` idle phases regardless of preferences.
- During `ACTIVE`, enable inversion only when `isDarkTheme` is true and `useStandardSheets` is false.
- Do not create dark PDF files, additional bitmap cache variants, or add dependencies.
- Preserve the user’s unrelated working-tree edits.

---

### Task 1: CNC sheet inversion rule and viewer rendering

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt:235-239,263-280,1582-1680,2617-2735,2739-2860`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:1503-1525,2682-2705`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreenTest.kt:1-42`

**Interfaces:**
- Consumes: `IdlePhase`, `isDarkTheme: Boolean`, and `useStandardSheets: Boolean`.
- Produces: `internal fun shouldInvertCncSheetBitmap(idlePhase: IdlePhase, isDarkTheme: Boolean, useStandardSheets: Boolean): Boolean`.
- Consumes: `invertSheetBitmap: Boolean` in `MarkupPdfPageView` and `DiagramView`.

- [ ] **Step 1: Write the failing test**

Add a table-driven test using literal expected values. It catches removal of the timeout branch, inversion in light mode, or ignoring the standard-sheets preference.

```kotlin
@Test
fun shouldInvertCncSheetBitmap_matchesTimeoutAndDarkSheetRules() {
    assertTrue(shouldInvertCncSheetBitmap(IdlePhase.DIMMED, false, true))
    assertTrue(shouldInvertCncSheetBitmap(IdlePhase.SYNC_PAUSED, false, true))
    assertTrue(shouldInvertCncSheetBitmap(IdlePhase.ACTIVE, true, false))
    assertFalse(shouldInvertCncSheetBitmap(IdlePhase.ACTIVE, true, true))
    assertFalse(shouldInvertCncSheetBitmap(IdlePhase.ACTIVE, false, false))
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest.shouldInvertCncSheetBitmap_matchesTimeoutAndDarkSheetRules"
```

Expected: compilation failure because `shouldInvertCncSheetBitmap` does not yet exist.

- [ ] **Step 3: Write the minimal implementation**

1. Add the helper beside `resolveSheetDisplayBitmap`:

```kotlin
internal fun shouldInvertCncSheetBitmap(
    idlePhase: IdlePhase,
    isDarkTheme: Boolean,
    useStandardSheets: Boolean
): Boolean = idlePhase != IdlePhase.ACTIVE || (isDarkTheme && !useStandardSheets)
```

2. Add `useStandardSheets: Boolean` to `SheetViewerScreen` and pass it from both existing CNC sheet navigation destinations.
3. Read the existing `LocalIdlePhase` state in `SheetViewerScreen`, calculate `invertSheetBitmap`, and pass it to `MarkupPdfPageView` and `DiagramView`.
4. Give each viewer an `invertSheetBitmap: Boolean` parameter. Use `ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(...)))` on its `Image`, with the standard RGB inversion matrix and unchanged alpha, only when the parameter is true.

- [ ] **Step 4: Run the focused test to verify it passes**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest"
```

Expected: `SheetViewerScreenTest` passes, including the new behavior test.

- [ ] **Step 5: Build the debug APK**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/app-debug.apk` is produced.

- [ ] **Step 6: Commit the focused change only after verification**

Stage only the inversion implementation, its test, and its approved spec/plan; do not stage the pre-existing PDF viewer edits or temporary PNG files.

```powershell
git add docs/superpowers/specs/2026-08-12-cnc-sheet-inversion-design.md docs/superpowers/plans/2026-08-12-cnc-sheet-inversion.md app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt app/src/test/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreenTest.kt
git commit -m "feat(viewer): invert CNC sheets in dark mode"
```
