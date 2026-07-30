# Specialty Plywood Crown Label Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Identify plywood-crown checkbox rows in Specialty job details as `Plywood Crown — <name>`.

**Architecture:** Keep the decision in a pure sheet-rip label helper next to the existing Specialty helpers. The Compose row consumes that helper, while unit tests cover crown and non-crown inputs; no data, tally, or completion behavior changes.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit 4, Android Gradle unit tests.

## Global Constraints

- Apply the special label only when `AdminBoardStockItem.mode` is `sheet` and `type` is `crown`, case-insensitively.
- Crown secondary text is exactly `Plywood Crown — <name>`.
- All other rows retain their existing `item.name` secondary text.
- Do not change material text, completion state, tally logic, molding preview, persisted data, or any other Specialty UI.

---

## File Map

- `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt`: supplies the checkbox secondary text and owns the new pure label helper.
- `app/src/test/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreenLogicTest.kt`: verifies crown and non-crown labels without Compose UI setup.

### Task 1: Label plywood crown rows

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt:447-452, 1123-1130`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreenLogicTest.kt`

**Interfaces:**
- Consumes: `AdminBoardStockItem.mode`, `AdminBoardStockItem.type`, and `AdminBoardStockItem.name`.
- Produces: `specialtySheetRipItemLabel(item: AdminBoardStockItem): String`.

- [ ] **Step 1: Write the failing label tests**

Add these tests to `SpecialtyJobDetailScreenLogicTest`:

```kotlin
@Test
fun specialtySheetRipItemLabel_identifiesPlywoodCrown() {
    val crown = AdminBoardStockItem(
        id = "crown",
        material = "Maple Plywood",
        name = "Crown 151",
        feet = 18.0,
        mode = "sheet",
        type = "crown"
    )

    assertEquals("Plywood Crown — Crown 151", specialtySheetRipItemLabel(crown))
}

@Test
fun specialtySheetRipItemLabel_keepsNonCrownName() {
    val sheetItem = AdminBoardStockItem(
        id = "panel",
        material = "Maple Plywood",
        name = "Toe Kick",
        feet = 18.0,
        mode = "sheet",
        type = "panel"
    )

    assertEquals("Toe Kick", specialtySheetRipItemLabel(sheetItem))
}
```

- [ ] **Step 2: Run the focused test class and verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.specialty.SpecialtyJobDetailScreenLogicTest"
```

Expected: compilation failure because `specialtySheetRipItemLabel` does not yet exist.

- [ ] **Step 3: Add the pure label helper and connect the row**

Add beside `specialtySheetRipLengthLabel`:

```kotlin
internal fun specialtySheetRipItemLabel(item: AdminBoardStockItem): String =
    if (item.mode.equals("sheet", ignoreCase = true) && item.type.equals("crown", ignoreCase = true)) {
        "Plywood Crown — ${item.name}"
    } else {
        item.name
    }
```

Replace the sheet-rip row secondary text:

```kotlin
text = item.name
```

with:

```kotlin
text = specialtySheetRipItemLabel(item)
```

- [ ] **Step 4: Run focused tests and full unit verification**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.specialty.SpecialtyJobDetailScreenLogicTest"
.\gradlew.bat :app:testDebugUnitTest
```

Expected: both commands pass.

- [ ] **Step 5: Commit only the implementation and its tests**

```powershell
git add app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt app/src/test/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreenLogicTest.kt
git commit -m "fix: label specialty plywood crown rows"
```

## Self-Review

- Spec coverage: Task 1 implements the exact crown label, preserves non-crown text, and protects both with pure unit tests.
- Placeholder scan: no TBD, TODO, or unscoped implementation steps.
- Type consistency: the helper accepts the existing `AdminBoardStockItem` type and is called from the existing sheet-rip row.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-29-specialty-plywood-crown-label.md`.

1. Subagent-Driven recommended: fresh subagent per task and review between tasks.
2. Inline Execution: execute in this session with checkpoints.
