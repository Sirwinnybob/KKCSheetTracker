# Specialty Section Animation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore a smooth expand/collapse animation for specialty job sections without collapsed rows adding blank space.

**Architecture:** Keep checklist and sheet-rip rows as individual lazy items so stable keys and large-section behavior remain unchanged. Remove global lazy-list spacing and make visible top-level content and section headers provide their own spacing; the existing per-row `AnimatedVisibility` then shrinks to zero without a framework-added interval remaining.

**Tech Stack:** Kotlin, Jetpack Compose `LazyColumn`, `AnimatedVisibility`, Compose animation transitions, JUnit 4.

## Global Constraints

- Change only `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt`.
- Preserve sticky headers, saved expanded-section IDs, item keys, progress controls, and row content.
- Use a 300 ms vertical-size and fade animation for both expanding and collapsing rows.
- Do not uninstall the tablet app to work around APK signature incompatibility.

---

### Task 1: Animate specialty section rows without ghost spacing

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt:238-540`
- Test: `app/src/test/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreenLogicTest.kt`

**Interfaces:**
- Consumes: `sheetExpanded: Boolean`, `sectionExpanded: Boolean`, `specialtySheetRipLazyRowEntries`, and `specialtyChecklistLazyRowEntries`.
- Produces: Existing `LazyColumn` content where collapsed rows report zero height and each visible section header remains separated by exactly 12 dp.

- [ ] **Step 1: Establish the regression check**

Run the existing specialty logic suite before changing code to protect stable row keys and expanded-section state:

```powershell
.\gradlew.bat app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.specialty.SpecialtyJobDetailScreenLogicTest"
```

Expected: PASS. The suite verifies the lazy row entry keys and `toggleSpecialtySection` behavior that the layout change must retain.

- [ ] **Step 2: Remove lazy-list global spacing**

Change the `LazyColumn` from:

```kotlin
verticalArrangement = Arrangement.spacedBy(12.dp)
```

to no global vertical arrangement. Add bottom padding to the summary and action items and top padding to each sticky section header so visible high-level items remain 12 dp apart.

- [ ] **Step 3: Restore per-row transitions**

Wrap each sheet-rip and checklist row in:

```kotlin
AnimatedVisibility(
    visible = sectionExpanded,
    enter = expandVertically(tween(300)) + fadeIn(tween(300)),
    exit = shrinkVertically(tween(300)) + fadeOut(tween(300))
)
```

Apply the same pattern with `sheetExpanded` to sheet-rip rows. Keep `flushWithHeader()` off the animated row because there is no global lazy-item gap to cancel.

- [ ] **Step 4: Verify the focused suite**

Run:

```powershell
.\gradlew.bat app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.specialty.SpecialtyJobDetailScreenLogicTest"
```

Expected: PASS with stable entry and expanded-section state behavior unchanged.

- [ ] **Step 5: Build and visually verify**

Run:

```powershell
.\gradlew.bat assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If a signature-compatible APK is available, open the same specialty job on the tablet and verify that all collapsed headers have a 12 dp gap and row bodies expand and collapse with a short fade/height transition.

- [ ] **Step 6: Commit**

```powershell
git add -- app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt
git commit -m "fix(specialty): animate section rows"
```
