# CNC Re-Nested Sheets Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a "Re-Nested" marking option for CNC sheets on the tablet that shows as faded green in sheet progress grids and excludes the sheet from job and dashboard progress tracking, while remaining fully compatible with external scripts that will see it as skipped.

**Architecture:** Extend the `SheetStatus` enum with a new `RE_NESTED` state. Store the re-nested action as `"action": "skip"` but with an extra property `"reNested": true` in `TrackerAction` to keep external scripts (which check action strings) happy. Update app-state derivation, progress store, and UI screens to recognize this flag, adjust progress counts accordingly, and render faded green progress elements.

**Tech Stack:** Kotlin, Jetpack Compose, Gson, JUnit

---

### Task 1: Extend Models and SharedPreferences Representation

**Files:**
- Modify: [Models.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/models/Models.kt)

**Step 1: Add RE_NESTED to SheetStatus enum and reNested field to TrackerAction**

Modify the `SheetStatus` enum class and `TrackerAction` class:
```kotlin
enum class SheetStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETE,
    SKIPPED,
    HAS_BAD_PARTS,
    RE_NESTED
}

data class TrackerAction(
    val file: String,
    val page: Int,
    val part: Int? = null,
    val action: String,
    val timestamp: String,
    val fileFingerprint: String? = null,
    val reNested: Boolean? = null
)
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/models/Models.kt
git commit -m "chore: add RE_NESTED status and reNested field to TrackerAction"
```

---

### Task 2: Implement ProgressStore Handling for Re-Nested Sheets

**Files:**
- Modify: [ProgressStore.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/ProgressStore.kt)

**Step 1: Extend SheetIndexEntry and applyActionToSheets**

Modify the `SheetIndexEntry` definition and `applyActionToSheets` inside `ProgressStore.kt`:
```kotlin
private data class SheetIndexEntry(
    val completeByFingerprint: MutableMap<String, Boolean> = mutableMapOf(),
    var completeLegacy: Boolean = false,
    var completeHasFingerprint: Boolean = false,
    val skippedByFingerprint: MutableMap<String, Boolean> = mutableMapOf(),
    var skippedLegacy: Boolean = false,
    var skippedHasFingerprint: Boolean = false,
    val badPartsByFingerprint: MutableMap<String, MutableMap<Int, Boolean>> = mutableMapOf(),
    val badPartsLegacy: MutableMap<Int, Boolean> = mutableMapOf(),
    var badPartsHasFingerprint: Boolean = false,
    val renestedByFingerprint: MutableMap<String, Boolean> = mutableMapOf(),
    var renestedLegacy: Boolean = false,
    var renestedHasFingerprint: Boolean = false
)
```

In `applyActionToSheets` (inside the `"skip", "unskip"` case, and reset on complete):
```kotlin
            "complete", "uncomplete" -> {
                val value = action.action == "complete"
                if (fp == null) {
                    entry.completeLegacy = value
                    if (value) {
                        entry.skippedLegacy = false
                        entry.renestedLegacy = false
                    }
                } else {
                    entry.completeHasFingerprint = true
                    entry.completeByFingerprint[fp] = value
                    if (value) {
                        entry.skippedByFingerprint[fp] = false
                        entry.renestedByFingerprint[fp] = false
                    }
                }
            }
            "skip", "unskip" -> {
                val value = action.action == "skip"
                val renestedValue = value && (action.reNested == true)
                if (fp == null) {
                    entry.skippedLegacy = value
                    entry.renestedLegacy = renestedValue
                } else {
                    entry.skippedHasFingerprint = true
                    entry.skippedByFingerprint[fp] = value
                    entry.renestedHasFingerprint = true
                    entry.renestedByFingerprint[fp] = renestedValue
                }
            }
```

**Step 2: Add mark, unmark, and status retrieval functions**

Add these methods to `ProgressStore`:
```kotlin
    fun markSheetRenested(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String) {
        if (readOnly) return
        appendActions(
            jobFolderName,
            listOf(
                TrackerAction(
                    file = pdfFilename,
                    page = page,
                    action = "skip",
                    timestamp = java.time.Instant.now().toString(),
                    fileFingerprint = fileFingerprint,
                    reNested = true
                )
            )
        )
    }

    fun unmarkSheetRenested(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String) {
        if (readOnly) return
        appendActions(
            jobFolderName,
            listOf(
                TrackerAction(
                    file = pdfFilename,
                    page = page,
                    action = "unskip",
                    timestamp = java.time.Instant.now().toString(),
                    fileFingerprint = fileFingerprint,
                    reNested = false
                )
            )
        )
    }

    fun isSheetRenested(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String): Boolean {
        val entry = resolveSheetEntry(jobFolderName, pdfFilename, page)
        return resolveRenested(entry, fileFingerprint)
    }

    private fun resolveRenested(entry: SheetIndexEntry?, fileFingerprint: String): Boolean {
        if (entry == null) return false
        return if (entry.renestedHasFingerprint) {
            entry.renestedByFingerprint[fileFingerprint] ?: false
        } else {
            entry.renestedLegacy
        }
    }
```

**Step 3: Update getSheetStatus**

```kotlin
    fun getSheetStatus(jobFolderName: String, pdfFilename: String, page: Int, fileFingerprint: String): SheetStatus {
        val entry = resolveSheetEntry(jobFolderName, pdfFilename, page)
        val isComplete = resolveComplete(entry, fileFingerprint)
        val isSkipped = resolveSkipped(entry, fileFingerprint)
        val isRenested = resolveRenested(entry, fileFingerprint)
        val hasCommittedBadParts = isComplete && resolveCommittedBadParts(entry, fileFingerprint).isNotEmpty()

        return when {
            hasCommittedBadParts -> SheetStatus.HAS_BAD_PARTS
            isComplete -> SheetStatus.COMPLETE
            isRenested -> SheetStatus.RE_NESTED
            isSkipped -> SheetStatus.SKIPPED
            else -> SheetStatus.NOT_STARTED
        }
    }
```

**Step 4: Exclude Re-Nested from getMaterialStatusCounts and getJobStatusCounts**

Update `getMaterialStatusCounts`:
```kotlin
    fun getMaterialStatusCounts(jobFolderName: String, material: Material): StatusCounts {
        var complete = 0
        var bad = 0
        var skipped = 0
        var notStarted = 0
        var reNested = 0

        val index = ensureJobIndex(jobFolderName)
        val visiblePages = getMaterialTrackablePages(material)
        for (page in visiblePages) {
            val entry = index.sheets[SheetKey(material.pdfFilename, page)]
            val isComplete = resolveComplete(entry, material.fileFingerprint)
            val isSkipped = resolveSkipped(entry, material.fileFingerprint)
            val isRenested = resolveRenested(entry, material.fileFingerprint)
            val hasBad = isComplete && resolveCommittedBadParts(entry, material.fileFingerprint).isNotEmpty()

            when {
                hasBad -> {
                    complete++
                    bad++
                }
                isComplete -> complete++
                isRenested -> reNested++
                isSkipped -> skipped++
                else -> notStarted++
            }
        }

        return StatusCounts(
            total = visiblePages.size - reNested,
            complete = complete,
            bad = bad,
            skipped = skipped,
            notStarted = notStarted
        )
    }
```

Update `getJobStatusCounts`:
```kotlin
    fun getJobStatusCounts(jobFolderName: String, materials: List<Material>): StatusCounts {
        var total = 0
        var complete = 0
        var bad = 0
        var skipped = 0
        var notStarted = 0

        val index = ensureJobIndex(jobFolderName)
        materials.forEach { material ->
            for (page in getMaterialTrackablePages(material)) {
                val entry = index.sheets[SheetKey(material.pdfFilename, page)]
                val isComplete = resolveComplete(entry, material.fileFingerprint)
                val isSkipped = resolveSkipped(entry, material.fileFingerprint)
                val isRenested = resolveRenested(entry, material.fileFingerprint)
                val hasBad = isComplete && resolveCommittedBadParts(entry, material.fileFingerprint).isNotEmpty()

                if (isRenested) continue

                total++
                when {
                    hasBad -> {
                        complete++
                        bad++
                    }
                    isComplete -> complete++
                    isSkipped -> skipped++
                    else -> notStarted++
                }
            }
        }

        return StatusCounts(total, complete, bad, skipped, notStarted)
    }
```

**Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/ProgressStore.kt
git commit -m "feat: implement re-nested status and count exclusions in ProgressStore"
```

---

### Task 3: Exclude Re-Nested Sheets from Overall Tracking in AppStateStore

**Files:**
- Modify: [AppStateStore.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/data/AppStateStore.kt)

**Step 1: Exclude RE_NESTED from deriveMaterial counts and total**

Modify `deriveMaterial` in `AppStateStore.kt`:
```kotlin
        var complete = 0
        var bad = 0
        var skipped = 0
        var notStarted = 0
        var reNestedCount = 0

        for (page in visiblePages) {
            val status = progressStore.getSheetStatus(
                jobFolderName = jobFolderName,
                pdfFilename = material.pdfFilename,
                page = page,
                fileFingerprint = fileFingerprint
            )
            val committedBadCount = progressStore.getBadParts(
                jobFolderName = jobFolderName,
                pdfFilename = material.pdfFilename,
                page = page,
                fileFingerprint = fileFingerprint,
                includeDraft = false
            ).size
            val hasDraftBadParts = progressStore.getDraftBadParts(
                jobFolderName = jobFolderName,
                pdfFilename = material.pdfFilename,
                page = page,
                fileFingerprint = fileFingerprint
            ).isNotEmpty()

            val snapshot = SheetStatusSnapshot(
                status = status,
                committedBadCount = committedBadCount,
                hasDraftBadParts = hasDraftBadParts
            )
            pageStatuses += snapshot
            pageStatusByNumber[page] = snapshot
            keyedStatuses[SheetStatusKey(jobFolderName, material.pdfFilename, page, fileFingerprint)] = snapshot

            when (status) {
                SheetStatus.HAS_BAD_PARTS -> {
                    complete++
                    bad++
                }
                SheetStatus.SKIPPED -> skipped++
                SheetStatus.COMPLETE -> complete++
                SheetStatus.RE_NESTED -> reNestedCount++
                else -> notStarted++
            }
        }

        val total = visiblePages.size - reNestedCount
```

**Step 2: Update nextIncompletePage to exhaustively cover RE_NESTED**

Modify `nextIncompletePage`:
```kotlin
    private fun nextIncompletePage(
        trackablePages: List<Int>,
        pageStatusByNumber: Map<Int, SheetStatusSnapshot>,
        fallbackPage: Int
    ): Int {
        return trackablePages.firstOrNull { page ->
            when (pageStatusByNumber[page]?.status ?: SheetStatus.NOT_STARTED) {
                SheetStatus.NOT_STARTED, SheetStatus.IN_PROGRESS -> true
                SheetStatus.COMPLETE, SheetStatus.SKIPPED, SheetStatus.HAS_BAD_PARTS, SheetStatus.RE_NESTED -> false
            }
        } ?: fallbackPage
    }
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/AppStateStore.kt
git commit -m "feat: update AppStateStore to ignore re-nested sheets from tracking totals"
```

---

### Task 4: Add Toggle Action to UI State/Viewer

**Files:**
- Modify: [NavBarDecoration.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/components/NavBarDecoration.kt)
- Modify: [SheetViewerScreen.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt)

**Step 1: Add onToggleRenested to NavBarCncDecoration**

Modify `NavBarCncDecoration` in `NavBarDecoration.kt`:
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
    val onOpenSearch: () -> Unit,
    val onToggleRenested: () -> Unit
)
```

**Step 2: Instantiate onToggleRenested and set faded green topBarColor**

In `SheetViewerScreen.kt` (creation of `NavBarCncDecoration` inside the SideEffect block):
```kotlin
            NavBarCncDecoration(
                currentPage = displayPageNumber,
                totalPages = visibleTotalPages,
                sheetStatus = sheetStatus,
                onPrevPage = {
                    if (effectiveVisiblePages.isNotEmpty() && currentVisibleIndex > 0) {
                        currentPage = effectiveVisiblePages[currentVisibleIndex - 1]
                        selectedPartNumber = null
                        selectedCabinetNumber = null
                    }
                },
                onNextPage = {
                    if (effectiveVisiblePages.isNotEmpty() && currentVisibleIndex < effectiveVisiblePages.lastIndex) {
                        currentPage = effectiveVisiblePages[currentVisibleIndex + 1]
                        selectedPartNumber = null
                        selectedCabinetNumber = null
                    }
                },
                onOpenToc = { showSheetToc = true },
                onToggleSkip = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val page = currentPage
                    val fp = fileFingerprint
                    val identityBefore = currentMaterial?.let { "${it.pdfFilename}|${it.fileFingerprint}" }
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val skipped = progressStore.isSheetSkipped(jobFolderName, pdfFilename, page, fp)
                            if (skipped) progressStore.unmarkSheetSkipped(jobFolderName, pdfFilename, page, fp)
                            else progressStore.markSheetSkipped(jobFolderName, pdfFilename, page, fp)
                        }
                        if (BuildConfig.DEBUG) {
                            val identityAfter = currentMaterial?.let { "${it.pdfFilename}|${it.fileFingerprint}" }
                            check(identityBefore == identityAfter) { "CACHE_IDENTITY_CHANGED during skip toggle" }
                        }
                    }
                },
                onToggleComplete = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val page = currentPage
                    val fp = fileFingerprint
                    val remakeParts = currentPageRemakeParts
                    val identityBefore = currentMaterial?.let { "${it.pdfFilename}|${it.fileFingerprint}" }
                    scope.launch {
                        val wasComplete = withContext(Dispatchers.IO) {
                            progressStore.isSheetComplete(jobFolderName, pdfFilename, page, fp)
                        }
                        if (wasComplete) {
                            withContext(Dispatchers.IO) {
                                progressStore.unmarkSheetComplete(jobFolderName, pdfFilename, page, fp)
                            }
                            snackbarHostState.showSnackbar("Sheet $displayPageNumber marked incomplete")
                        } else {
                            val (wasSkipped, resolvedRemakeCount) = withContext(Dispatchers.IO) {
                                val skipped = progressStore.isSheetSkipped(jobFolderName, pdfFilename, page, fp)
                                progressStore.markSheetComplete(jobFolderName, pdfFilename, page, fp)
                                val resolved = progressStore.resolveSpecificBadParts(
                                    jobFolderName = jobFolderName,
                                    pdfFilename = pdfFilename,
                                    page = page,
                                    fileFingerprint = fp,
                                    partNumbers = remakeParts
                                )
                                skipped to resolved
                            }
                            val baseMessage =
                                if (wasSkipped) "Sheet $displayPageNumber marked complete (skip removed)"
                                else "Sheet $displayPageNumber marked complete"
                            snackbarHostState.showSnackbar(
                                if (resolvedRemakeCount > 0) "$baseMessage, $resolvedRemakeCount bad parts resolved"
                                else baseMessage
                            )
                        }
                    }
                },
                onOpenSearch = { showCncSearch = true },
                onToggleRenested = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val page = currentPage
                    val fp = fileFingerprint
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val renested = progressStore.isSheetRenested(jobFolderName, pdfFilename, page, fp)
                            if (renested) progressStore.unmarkSheetRenested(jobFolderName, pdfFilename, page, fp)
                            else progressStore.markSheetRenested(jobFolderName, pdfFilename, page, fp)
                        }
                    }
                }
            )
```

And update `topBarColor` around line 944:
```kotlin
    val topBarColor = when (sheetStatus) {
        SheetStatus.COMPLETE -> KKCThemeColors.statusColors.complete
        SheetStatus.SKIPPED -> KKCThemeColors.statusColors.skip
        SheetStatus.HAS_BAD_PARTS -> KKCThemeColors.statusColors.bad
        SheetStatus.RE_NESTED -> KKCThemeColors.statusColors.complete.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surface
    }
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/NavBarDecoration.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/SheetViewerScreen.kt
git commit -m "feat: hook up onToggleRenested callback and update topBarColor in viewer"
```

---

### Task 5: Add Re-Nested Toggle Button to AppScaffold Bottom Bar

**Files:**
- Modify: [AppScaffold.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt)

**Step 1: Import Cached icon and render Re-Nest and Skip buttons with custom colors**

Import `androidx.compose.material.icons.filled.Cached` and insert the "Re-Nested" button (pale purple using `remakeBg`) right next to the "Skip" button (colored orange using `skip` when skipped):
```kotlin
                                            val isRenested = dec.sheetStatus == SheetStatus.RE_NESTED
                                            val isSkipped = dec.sheetStatus == SheetStatus.SKIPPED
                                            Button(
                                                onClick = dec.onToggleSkip,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isSkipped) KKCThemeColors.statusColors.skip
                                                                     else MaterialTheme.colorScheme.surfaceVariant,
                                                    contentColor   = if (isSkipped) Color.White
                                                                     else MaterialTheme.colorScheme.onSurface
                                                ),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = MaterialTheme.shapes.extraLarge
                                            ) {
                                                Icon(
                                                    Icons.Default.Flag,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(Modifier.width(3.dp))
                                                Text(
                                                    if (isSkipped) "Unskip" else "Skip",
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            Button(
                                                onClick = dec.onToggleRenested,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isRenested) KKCThemeColors.statusColors.remakeBg
                                                                     else MaterialTheme.colorScheme.surfaceVariant,
                                                    contentColor   = if (isRenested) Color.White
                                                                     else MaterialTheme.colorScheme.onSurface
                                                ),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = MaterialTheme.shapes.extraLarge
                                            ) {
                                                Icon(
                                                    Icons.Default.Cached,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(Modifier.width(3.dp))
                                                Text(
                                                    if (isRenested) "Re-Nested" else "Re-Nest",
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                            }
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt
git commit -m "feat: add Re-Nested toggle button next to Skip button in AppScaffold"
```

---

### Task 6: Exhaustively Map RE_NESTED in Status Components and Screens

**Files:**
- Modify: [StatusBorderedCard.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/components/StatusBorderedCard.kt)
- Modify: [StatusComponents.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/components/StatusComponents.kt)
- Modify: [HardwoodsDashboardScreen.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsDashboardScreen.kt)
- Modify: [HardwoodsSearchScreen.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsSearchScreen.kt)
- Modify: [AssemblyViewerScreen.kt](file:///c:/Scripts/KKCSheetTracker/app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt)

**Step 1: Map RE_NESTED to faded green styling**

In `StatusBorderedCard.kt` line 36:
```kotlin
        SheetStatus.RE_NESTED -> StatusCardColors(
            borderColor = colors.completeBorder.copy(alpha = 0.5f),
            backgroundTint = colors.completeBgRow.copy(alpha = 0.5f)
        )
```

In `StatusComponents.kt` `SheetStatusBadge` line 152:
```kotlin
        SheetStatus.RE_NESTED -> "Re-Nested" to colors.complete.copy(alpha = 0.5f)
```

In `StatusComponents.kt` `PageStatusBar` line 690:
```kotlin
                color = when (getStatus(page)) {
                    SheetStatus.COMPLETE -> colors.completeBg
                    SheetStatus.HAS_BAD_PARTS -> colors.badBg
                    SheetStatus.SKIPPED -> colors.skipBg
                    SheetStatus.IN_PROGRESS -> colors.inProgress
                    SheetStatus.NOT_STARTED -> MaterialTheme.colorScheme.outlineVariant
                    SheetStatus.RE_NESTED -> colors.completeBg.copy(alpha = 0.35f)
                },
```

In `HardwoodsDashboardScreen.kt` line 573:
```kotlin
        SheetStatus.RE_NESTED -> colors.completeBorder.copy(alpha = 0.5f)
```

In `HardwoodsSearchScreen.kt` line 267:
```kotlin
        SheetStatus.RE_NESTED -> colors.completeBorder.copy(alpha = 0.5f)
```

In `AssemblyViewerScreen.kt` `CncPartRow` line 1492:
```kotlin
        part.sheetStatus == SheetStatus.RE_NESTED ->
            Triple("CNC - Re-Nested", MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
```

In `AssemblyViewerScreen.kt` `BomPartRow` line 1438:
```kotlin
            val allComplete = entry.cncParts.all { it.sheetStatus == SheetStatus.COMPLETE || it.sheetStatus == SheetStatus.RE_NESTED }
```

**Step 2: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/StatusBorderedCard.kt app/src/main/java/com/kkc/sheettracker/ui/components/StatusComponents.kt app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsDashboardScreen.kt app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsSearchScreen.kt app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt
git commit -m "feat: exhaustively cover SheetStatus.RE_NESTED in status mapping when-blocks"
```

---

### Task 7: Add Unit Tests for ProgressStore and AppStateStore

**Files:**
- Modify: [ProgressStoreTest.kt](file:///C:/Scripts/KKCSheetTracker/app/src/test/java/com/kkc/sheettracker/data/ProgressStoreTest.kt)

**Step 1: Write tests for Re-Nested status tracking and exclusions**

Add unit tests inside `ProgressStoreTest.kt`:
```kotlin
    @Test
    fun reNestedSheetHasCorrectStatus() {
        val baseDir = createTempBaseDir()
        val store = ProgressStore(baseDir, tabletId, File(baseDir, ".local"))

        store.markSheetRenested(jobFolderName, "A.pdf", 3, "fp1")

        assertEquals(SheetStatus.RE_NESTED, store.getSheetStatus(jobFolderName, "A.pdf", 3, "fp1"))
        
        // Assert that external trackers see it as skipped (isSheetSkipped returns true)
        assertEquals(true, store.isSheetSkipped(jobFolderName, "A.pdf", 3, "fp1"))
    }
```

**Step 2: Run test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests com.kkc.sheettracker.data.ProgressStoreTest`
Expected: PASS

**Step 3: Commit**

```bash
git add app/src/test/java/com/kkc/sheettracker/data/ProgressStoreTest.kt
git commit -m "test: add unit test for re-nested sheets status tracking and backward compatibility"
```

---

## Verification Plan

### Automated Tests
- Run unit tests: `.\gradlew.bat testDebugUnitTest`

### Manual Verification
- Build and run the app: `.\gradlew.bat assembleDebug` and install on tablet.
- Open CNC mode, navigate to sheet, click "Re-Nest" button. Verify sheet status changes to "Re-Nested" (faded green color in progress bar, navbar topBarColor, and list badge).
- Verify job status count on detail/dashboard decreases by 1 (e.g. 5/9 instead of 5/10).
- Click "Re-Nested" again to unmark it, verify it goes back to "Not Started".
- Check that tracker JSON contains `"action": "skip"` and `"reNested": true`.
