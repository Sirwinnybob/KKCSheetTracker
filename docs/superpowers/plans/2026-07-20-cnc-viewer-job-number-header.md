# CNC Viewer Job Number Header Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the current CNC job number before the material name in the sheet viewer header.

**Architecture:** Keep material-name derivation unchanged. Read the matching `Job` from `scanState.snapshot.jobs` using `jobFolderName`, pass its structured `jobNumber` through a small pure title-formatting helper, and render that result in the existing `TopAppBar` title. If the job or number is unavailable, retain the current material-only title.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, JUnit 4, Gradle Android build.

## Global Constraints

- Change only the CNC `SheetViewerScreen` top app bar title.
- Preserve the existing material-name derivation.
- Do not change PDF filenames, navigation routes, job lists, or other screen headers.
- Preserve unrelated working-tree files.

---

### Task 1: Add and verify the CNC viewer title format

**Files:**
- Modify: `app/src/test/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreenTest.kt`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt`

**Interfaces:**
- Consumes: nullable `jobNumber` from the matching `scanState.snapshot.jobs` entry and the existing `materialName` string.
- Produces: `internal fun cncSheetViewerTitle(jobNumber: String?, materialName: String): String`, returning `"<number> - <material>"` for a nonblank number and `materialName` otherwise.

- [ ] **Step 1: Write the failing unit tests**

Add these tests to `SheetViewerScreenTest`:

```kotlin
@Test
fun cncSheetViewerTitle_putsJobNumberBeforeMaterialName() {
    assertEquals("12345 - Maple", cncSheetViewerTitle("12345", "Maple"))
}

@Test
fun cncSheetViewerTitle_fallsBackToMaterialWhenJobNumberMissing() {
    assertEquals("Maple", cncSheetViewerTitle(null, "Maple"))
    assertEquals("Maple", cncSheetViewerTitle("   ", "Maple"))
}
```

- [ ] **Step 2: Run the focused test to confirm it fails**

Run from `C:\Scripts\KKCSheetTracker`:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest
```

Expected: compilation/test failure because `cncSheetViewerTitle` does not yet exist.

- [ ] **Step 3: Implement the minimal pure formatter**

Near the existing CNC viewer helpers in `SheetViewerScreen.kt`, add:

```kotlin
internal fun cncSheetViewerTitle(jobNumber: String?, materialName: String): String {
    val number = jobNumber?.trim().orEmpty()
    return if (number.isBlank()) materialName else "$number - $materialName"
}
```

- [ ] **Step 4: Wire the formatter to the existing job list and top bar**

After the existing `materialName` derivation, resolve the current job and title:

```kotlin
val currentJobNumber = scanState.snapshot.jobs
    .firstOrNull { it.folderName == jobFolderName }
    ?.jobNumber
val viewerTitle = cncSheetViewerTitle(currentJobNumber, materialName)
```

In the existing `TopAppBar` title block, change only the title `Text` value from:

```kotlin
Text(materialName, ...)
```

to:

```kotlin
Text(viewerTitle, ...)
```

Keep the current style, ellipsis, status badge, actions, and layout unchanged.

- [ ] **Step 5: Run the focused tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.kkc.sheettracker.ui.viewer.SheetViewerScreenTest
```

Expected: PASS, including both title-format tests and the existing viewer tests.

- [ ] **Step 6: Run the Android build gate**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Review the diff and commit only the implementation files**

Run:

```powershell
git diff --check
git diff -- app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt app/src/test/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreenTest.kt
git status --short
```

Confirm unrelated files are not staged, then commit:

```powershell
git add -- app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt app/src/test/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreenTest.kt
git commit -m "feat: show job number in CNC viewer header"
```

