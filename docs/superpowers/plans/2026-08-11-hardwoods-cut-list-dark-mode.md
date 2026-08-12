# Hardwoods Cut List Dark Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Hardwood Cut List controls honor dark mode and make skipped List View rows use a very faint yellow/orange wash.

**Architecture:** Keep Material theme surface selection inside `HardwoodsWorkspaceScreen.kt`. Extract the List View row-background selection into a pure helper in `HardwoodsRowVisuals.kt`, so the skip-opacity contract can be tested without a Compose runtime. The existing composable delegates to that helper and continues to own the remaining row visual properties.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, JUnit 4, Gradle Android plugin.

## Global Constraints

- Modify only the Cut List selector surfaces and the skipped List View row wash; preserve all existing interactions and semantic status borders, buttons, and progress colors.
- Unselected selectors use `MaterialTheme.colorScheme.surface`; the mode menu uses `MaterialTheme.colorScheme.surfaceContainer`; the selected mode remains `secondaryContainer`.
- Both `SKIPPED` and `PARTIAL_SKIP` rows use `status.skipBg.copy(alpha = 0.08f)`.
- Do not install or uninstall anything on connected tablets.

---

### Task 1: Theme Cut List controls and correct skipped-row wash

**Files:**

- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsRowVisuals.kt:61-78`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt:1048-1053, 1224-1228, 1252-1255, 2318-2323`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsRowHelpersTest.kt`

**Interfaces:**

- Consumes: `HardwoodsRowState`, `KKCStatusColors`, and `Color`.
- Produces: `hardwoodsRowBackgroundTint(state: HardwoodsRowState, widthBand: Color, status: KKCStatusColors): Color`, used by `hardwoodsRowVisualStyle` and its JUnit regression test.

- [ ] **Step 1: Write the failing regression test**

Add these imports and test to `HardwoodsRowHelpersTest.kt`:

```kotlin
import androidx.compose.ui.graphics.Color
import com.kkc.sheettracker.ui.theme.LightStatusColors

@Test
fun hardwoodsRowBackgroundTint_usesVeryFaintSkipWashForSkippedStates() {
    val expected = LightStatusColors.skipBg.copy(alpha = 0.08f)

    assertEquals(
        expected,
        hardwoodsRowBackgroundTint(
            state = HardwoodsRowState.SKIPPED,
            widthBand = Color.Red,
            status = LightStatusColors
        )
    )
    assertEquals(
        expected,
        hardwoodsRowBackgroundTint(
            state = HardwoodsRowState.PARTIAL_SKIP,
            widthBand = Color.Red,
            status = LightStatusColors
        )
    )
}
```

This test catches a regression where a skipped state uses `skipBgRow` at a high replacement alpha or a non-skip status color.

- [ ] **Step 2: Run the test to verify the expected red state**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.hardwoods.HardwoodsRowHelpersTest.hardwoodsRowBackgroundTint_usesVeryFaintSkipWashForSkippedStates"
```

Expected: compilation fails because `hardwoodsRowBackgroundTint` does not yet exist.

- [ ] **Step 3: Implement the minimal row-wash helper and theme surfaces**

In `HardwoodsRowVisuals.kt`, add the pure helper and delegate the existing `backgroundTint` assignment to it:

```kotlin
fun hardwoodsRowBackgroundTint(
    state: HardwoodsRowState,
    widthBand: Color,
    status: KKCStatusColors
): Color = when (state) {
    HardwoodsRowState.COMPLETE -> Color.Transparent
    HardwoodsRowState.SKIPPED,
    HardwoodsRowState.PARTIAL_SKIP -> status.skipBg.copy(alpha = 0.08f)
    HardwoodsRowState.IN_PROGRESS -> status.inProgressBorder.copy(alpha = 0.22f)
    HardwoodsRowState.NOT_STARTED -> widthBand.copy(alpha = 0.11f)
}
```

In `HardwoodsWorkspaceScreen.kt`, replace the scoped `Color.White` control container colors with `MaterialTheme.colorScheme.surface`, and replace the mode `DropdownMenu` container color with `MaterialTheme.colorScheme.surfaceContainer`. Do not change the selected mode's `secondaryContainer` color.

- [ ] **Step 4: Run the focused regression test to verify green**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.kkc.sheettracker.ui.hardwoods.HardwoodsRowHelpersTest.hardwoodsRowBackgroundTint_usesVeryFaintSkipWashForSkippedStates"
```

Expected: PASS.

- [ ] **Step 5: Build the debug app and inspect the scoped diff**

Run:

```powershell
.\gradlew.bat assembleDebug
git diff --check
git diff -- app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsRowVisuals.kt app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt app/src/test/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsRowHelpersTest.kt
```

Expected: the debug build succeeds, `git diff --check` reports no whitespace errors, selector containers use Material theme colors, and skipped List View rows use the shared 8% skip wash.

- [ ] **Step 6: Commit the implementation**

```powershell
git add -- app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsRowVisuals.kt app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt app/src/test/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsRowHelpersTest.kt
git commit -m "fix(hardwoods): theme cut list controls"
```
