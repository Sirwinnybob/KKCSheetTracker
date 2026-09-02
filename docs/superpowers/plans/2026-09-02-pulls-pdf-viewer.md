# Pulls PDF Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add "Pulls" (a marked-up delivery-sheet PDF at job root, `<job> - PULLS.pdf`, no parsing) as a viewable reference document: a button on all three job-details screens (CNC/Hardwoods/Specialty), a chip in the Assembly PDF viewer, plus a rename of the existing Cover Sheet button/title to "Delivery" and a "View " prefix strip on the other reference-doc buttons.

**Architecture:** `ReferenceDocType` (shared enum) gains a `PULLS` case, detected purely by filename substring match (`"pulls"`) the same way `"delivery sheets"` already is, surfaced through the existing `JobPdfCatalog` → `findReferencePdfFilename` → `ReferencePdfViewerScreen` pipeline. Because Kotlin requires exhaustive `when` over enums, adding the case is one atomic module-wide change (Task 1) touching every existing `when (docType)` — most get a no-op branch, only the ones tied to this feature get real behavior. UI wiring (job-details buttons, Assembly viewer chip) follows in separate tasks once the enum change compiles clean.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit (local unit tests under `app/src/test`).

Spec: `docs/superpowers/specs/2026-09-02-pulls-pdf-viewer-design.md`

---

## Task 1: `PULLS` enum case + detection logic + module-wide compile fix

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/data/models/Models.kt:93-109`
- Modify: `app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt:539-571` (`findReferencePdfFilename`)
- Modify: `app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt:1052-1086` (`buildPdfCatalog`)
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferenceViewerData.kt:37-44` and `:95-108`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt:636-643`, `:828-833`, `:2169-2176`, `:2225-2236`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt:138-143`
- Test: `app/src/test/java/com/kkc/sheettracker/data/unified/UnifiedMetadataEngineTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `UnifiedMetadataEngineTest.kt` (after the existing `resolvesReferenceDocsAndCabinetJump` test at line 170):

```kotlin
    @Test
    fun resolvesPullsPdfByFilenameOnly() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        File(baseDir, "$jobFolder/1234 - PULLS.pdf").writeText("pdf")
        val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)

        val pullsRef = engine.findReferencePdfFilename(
            jobFolderName = jobFolder,
            query = UnifiedReferenceQuery(ReferenceDocType.PULLS)
        ).pdfFilename
        assertEquals("1234 - PULLS.pdf", pullsRef)

        assertTrue(
            engine.hasReferenceDocument(
                jobFolderName = jobFolder,
                query = UnifiedReferenceQuery(ReferenceDocType.PULLS)
            ).exists
        )

        val catalog = engine.getPdfCatalog(jobFolder).catalog
        assertEquals("1234 - PULLS.pdf", catalog.pullsSheet?.pdfFilename)
    }

    @Test
    fun hasReferenceDocumentIsFalseForPullsWhenFileMissing() {
        val baseDir = createTempBaseDir()
        seedJob(baseDir)
        val engine = FileBackedUnifiedMetadataEngine(baseDir.absolutePath, isDebugBuild = true)

        assertFalse(
            engine.hasReferenceDocument(
                jobFolderName = jobFolder,
                query = UnifiedReferenceQuery(ReferenceDocType.PULLS)
            ).exists
        )
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.unified.UnifiedMetadataEngineTest"`
Expected: FAIL to compile — `ReferenceDocType.PULLS` and `catalog.pullsSheet` don't exist yet.

- [ ] **Step 3: Add `PULLS` to the enum and `pullsSheet` to the catalog**

In `Models.kt`, replace lines 93-109:

```kotlin
enum class ReferenceDocType {
    ASSEMBLY,
    PLANS_ELEVATIONS,
    DELIVERY_SHEETS,
    SHEET,
    PULLS
}

data class JobPdfRef(
    val pdfFilename: String,
    val label: String = ""
)

data class JobPdfCatalog(
    val deliverySheet: JobPdfRef? = null,
    val pullsSheet: JobPdfRef? = null,
    val managedDocs: List<JobPdfRef> = emptyList(),
    val otherDocs: List<JobPdfRef> = emptyList()
)
```

- [ ] **Step 4: Detect Pulls files in `buildPdfCatalog`**

In `FileBackedUnifiedMetadataEngine.kt`, replace the `buildPdfCatalog` body (lines 1052-1086):

```kotlin
    private fun buildPdfCatalog(jobFolderName: String): JobPdfCatalog {
        val jobDir = File(baseDir, jobFolderName)
        if (!jobDir.isDirectory) return JobPdfCatalog()
        val rootPdfs = jobDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.lowercase(Locale.US) == "pdf" }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.toList()
            .orEmpty()

        val managed = mutableListOf<JobPdfRef>()
        val other = mutableListOf<JobPdfRef>()
        var deliverySheet: JobPdfRef? = null
        var pullsSheet: JobPdfRef? = null
        rootPdfs.forEach { file ->
            val lower = file.name.lowercase(Locale.US)
            val managedLabel = when {
                lower.contains("delivery sheets") -> "Delivery Sheets"
                lower.contains("pulls") -> "Pulls"
                lower.contains("assembly sheets") -> "Assembly Sheets"
                lower.contains("plans & elevations") || lower.contains("plans and elevations") -> "Plans & Elevations"
                lower.contains("door list") -> "Door List"
                lower.contains("cut list") || lower.contains("cutlist") -> "Cut List"
                else -> null
            }
            if (managedLabel != null) {
                val ref = JobPdfRef(pdfFilename = file.name, label = managedLabel)
                managed += ref
                if (managedLabel == "Delivery Sheets" && deliverySheet == null) {
                    deliverySheet = ref
                }
                if (managedLabel == "Pulls" && pullsSheet == null) {
                    pullsSheet = ref
                }
            } else {
                other += JobPdfRef(pdfFilename = file.name, label = file.nameWithoutExtension)
            }
        }
        return JobPdfCatalog(deliverySheet = deliverySheet, pullsSheet = pullsSheet, managedDocs = managed, otherDocs = other)
    }
```

- [ ] **Step 5: Special-case `PULLS` in `findReferencePdfFilename`, keep the rest exhaustive**

In `FileBackedUnifiedMetadataEngine.kt`, replace lines 539-571:

```kotlin
    override fun findReferencePdfFilename(jobFolderName: String, query: UnifiedReferenceQuery): UnifiedReferenceLookup {
        val docType = query.docType
        if (docType == ReferenceDocType.DELIVERY_SHEETS) {
            return UnifiedReferenceLookup(getPdfCatalog(jobFolderName).catalog.deliverySheet?.pdfFilename)
        }
        if (docType == ReferenceDocType.PULLS) {
            return UnifiedReferenceLookup(getPdfCatalog(jobFolderName).catalog.pullsSheet?.pdfFilename)
        }
        val staticData = loadStaticJobData(jobFolderName)
        val sheetIndex = staticData?.cabinetSheetIndex
        val fromIndex = when (docType) {
            ReferenceDocType.ASSEMBLY -> sheetIndex?.documents?.assembly?.pdfFilename
            ReferenceDocType.PLANS_ELEVATIONS -> sheetIndex?.documents?.plansElevations?.pdfFilename
            ReferenceDocType.DELIVERY_SHEETS -> null
            ReferenceDocType.SHEET -> null
            ReferenceDocType.PULLS -> null
        }?.takeIf { it.isNotBlank() }
        if (fromIndex != null) return UnifiedReferenceLookup(fromIndex)

        val target = when (docType) {
            ReferenceDocType.ASSEMBLY -> "assembly sheets"
            ReferenceDocType.PLANS_ELEVATIONS -> "plans & elevations"
            ReferenceDocType.DELIVERY_SHEETS -> "delivery sheets"
            ReferenceDocType.SHEET -> "sheet"
            ReferenceDocType.PULLS -> "pulls"
        }
        val jobDir = File(baseDir, jobFolderName)
        if (!jobDir.isDirectory) return UnifiedReferenceLookup(null)
        fun findIn(dir: File): String? {
            val files = dir.listFiles() ?: return null
            return files.firstOrNull { file ->
                file.isFile &&
                    file.extension.lowercase(Locale.US) == "pdf" && !file.name.contains(".sync-conflict-") &&
                    file.name.lowercase(Locale.US).contains(target)
            }?.name
        }
        return UnifiedReferenceLookup(findIn(jobDir) ?: findIn(File(jobDir, "DARK MODE")))
    }
```

- [ ] **Step 6: Fix the remaining exhaustive `when (docType)` blocks (no-op `PULLS` branch — not user-facing surfaces for Pulls)**

In `ReferenceViewerData.kt`, lines 37-44, add the branch:

```kotlin
    val documentIndex = remember(sheetIndex, docType) {
        when (docType) {
            ReferenceDocType.ASSEMBLY -> sheetIndex?.documents?.assembly
            ReferenceDocType.PLANS_ELEVATIONS -> sheetIndex?.documents?.plansElevations
            ReferenceDocType.DELIVERY_SHEETS -> null
            ReferenceDocType.SHEET -> null
            ReferenceDocType.PULLS -> null
        }
    }
```

Same file, lines 95-108:

```kotlin
    val navigatorCabinetToPages = remember(docType, documentIndex, assemblyVirtualSanitized, virtualMapping) {
        when (docType) {
            ReferenceDocType.ASSEMBLY -> {
                if (virtualMapping != null) {
                    assemblyVirtualSanitized.cabinetToPages
                } else {
                    documentIndex?.cabinetToPages.orEmpty()
                }
            }
            ReferenceDocType.PLANS_ELEVATIONS -> documentIndex?.cabinetToPages.orEmpty()
            ReferenceDocType.DELIVERY_SHEETS -> emptyMap()
            ReferenceDocType.SHEET -> emptyMap()
            ReferenceDocType.PULLS -> emptyMap()
        }
    }
```

In `HardwoodsWorkspaceScreen.kt`, lines 636-643:

```kotlin
    fun mappedPage(docType: ReferenceDocType, cab: String): Int? {
        val map = when (docType) {
            ReferenceDocType.ASSEMBLY -> assemblyCabinetToPages
            ReferenceDocType.PLANS_ELEVATIONS -> cabinetIndex?.documents?.plansElevations?.cabinetToPages
            ReferenceDocType.DELIVERY_SHEETS -> null
            ReferenceDocType.SHEET -> null
            ReferenceDocType.PULLS -> null
        }
        return map?.get(cab)?.firstOrNull()
    }
```

Same file, lines 828-833:

```kotlin
            when (referenceDocType) {
                ReferenceDocType.ASSEMBLY -> assemblyTarget?.let { referencePage = it }
                ReferenceDocType.PLANS_ELEVATIONS -> plansTarget?.let { referencePage = it }
                ReferenceDocType.DELIVERY_SHEETS -> Unit
                ReferenceDocType.SHEET -> Unit
                ReferenceDocType.PULLS -> Unit
            }
```

Same file, lines 2169-2176:

```kotlin
    val docIndex = remember(cabinetIndex, referenceDocType) {
        when (referenceDocType) {
            ReferenceDocType.ASSEMBLY -> cabinetIndex?.documents?.assembly
            ReferenceDocType.PLANS_ELEVATIONS -> cabinetIndex?.documents?.plansElevations
            ReferenceDocType.DELIVERY_SHEETS -> null
            ReferenceDocType.SHEET -> null
            ReferenceDocType.PULLS -> null
        }
    }
```

Same file, lines 2225-2236:

```kotlin
    val navigatorCabinetToPages = remember(referenceDocType, docIndex, assemblyVirtualSanitized, virtualMapping) {
        when (referenceDocType) {
            ReferenceDocType.ASSEMBLY -> if (virtualMapping != null) {
                assemblyVirtualSanitized.cabinetToPages
            } else {
                docIndex?.cabinetToPages.orEmpty()
            }
            ReferenceDocType.PLANS_ELEVATIONS -> docIndex?.cabinetToPages.orEmpty()
            ReferenceDocType.DELIVERY_SHEETS -> emptyMap()
            ReferenceDocType.SHEET -> emptyMap()
            ReferenceDocType.PULLS -> emptyMap()
        }
    }
```

- [ ] **Step 7: Rename the Delivery title and add the Pulls title in the full-screen viewer**

In `ReferencePdfViewerScreen.kt`, replace lines 138-143:

```kotlin
                        when (docType) {
                            ReferenceDocType.ASSEMBLY -> "Assembly Sheets"
                            ReferenceDocType.PLANS_ELEVATIONS -> "Plans & Elevations"
                            ReferenceDocType.DELIVERY_SHEETS -> "Delivery"
                            ReferenceDocType.SHEET -> "Sheet"
                            ReferenceDocType.PULLS -> "Pulls"
                        },
```

- [ ] **Step 8: Run tests to verify they pass, and confirm the whole module compiles**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.data.unified.UnifiedMetadataEngineTest"`
Expected: PASS (all tests in the file, including the two new ones)

Run: `./gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL — confirms every other `when (docType)`/`when (referenceDocType)` in the module is exhaustive with the new `PULLS` case.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/data/models/Models.kt app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferenceViewerData.kt app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsWorkspaceScreen.kt app/src/main/java/com/kkc/sheettracker/ui/viewer/ReferencePdfViewerScreen.kt app/src/test/java/com/kkc/sheettracker/data/unified/UnifiedMetadataEngineTest.kt
git commit -m "feat(pulls): add PULLS reference doc type with filename-based detection"
```

---

## Task 2: Thread `hasPullsSheet` through `SpecialtyAvailability`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/SpecialtyAvailability.kt`
- Test: `app/src/test/java/com/kkc/sheettracker/navigation/SpecialtyAvailabilityTest.kt`

- [ ] **Step 1: Write the failing test**

Replace the test body in `SpecialtyAvailabilityTest.kt`:

```kotlin
class SpecialtyAvailabilityTest {
    @Test
    fun resolverCombinesTheSixAvailabilityChecks() {
        val result = resolveSpecialtyAvailability(
            hasDeliverySheet = { true },
            hasPullsSheet = { true },
            hasAssemblySheet = { false },
            hasPlansElevations = { true },
            hasThreeDAssets = { true },
            hasClosetRods = { false }
        )

        assertEquals(
            SpecialtyAvailability(
                hasDeliverySheet = true,
                hasPullsSheet = true,
                hasAssemblySheet = false,
                hasPlansElevations = true,
                hasThreeDAssets = true,
                hasClosetRods = false
            ),
            result
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.SpecialtyAvailabilityTest"`
Expected: FAIL to compile — `hasPullsSheet` parameter doesn't exist yet.

- [ ] **Step 3: Add `hasPullsSheet` to the data class, resolver, and loader**

Replace the full contents of `SpecialtyAvailability.kt`:

```kotlin
package com.kkc.sheettracker.navigation

import com.kkc.sheettracker.data.JobRepository
import com.kkc.sheettracker.data.models.ReferenceDocType
import com.kkc.sheettracker.ui.specialty.hasClosetRodCutList

internal data class SpecialtyAvailability(
    val hasDeliverySheet: Boolean = false,
    val hasPullsSheet: Boolean = false,
    val hasAssemblySheet: Boolean = false,
    val hasPlansElevations: Boolean = false,
    val hasThreeDAssets: Boolean = false,
    val hasClosetRods: Boolean = false
)

internal fun resolveSpecialtyAvailability(
    hasDeliverySheet: () -> Boolean,
    hasPullsSheet: () -> Boolean,
    hasAssemblySheet: () -> Boolean,
    hasPlansElevations: () -> Boolean,
    hasThreeDAssets: () -> Boolean,
    hasClosetRods: () -> Boolean
): SpecialtyAvailability = SpecialtyAvailability(
    hasDeliverySheet = hasDeliverySheet(),
    hasPullsSheet = hasPullsSheet(),
    hasAssemblySheet = hasAssemblySheet(),
    hasPlansElevations = hasPlansElevations(),
    hasThreeDAssets = hasThreeDAssets(),
    hasClosetRods = hasClosetRods()
)

internal fun loadSpecialtyAvailability(
    jobRepository: JobRepository,
    folderName: String
): SpecialtyAvailability = resolveSpecialtyAvailability(
    hasDeliverySheet = {
        jobRepository.getJobPdfCatalog(folderName).deliverySheet != null
    },
    hasPullsSheet = {
        jobRepository.getJobPdfCatalog(folderName).pullsSheet != null
    },
    hasAssemblySheet = {
        jobRepository.hasReferenceDocument(
            folderName,
            ReferenceDocType.ASSEMBLY
        )
    },
    hasPlansElevations = {
        jobRepository.hasReferenceDocument(
            folderName,
            ReferenceDocType.PLANS_ELEVATIONS
        )
    },
    hasThreeDAssets = { jobRepository.hasThreeDAssets(folderName) },
    hasClosetRods = { hasClosetRodCutList(jobRepository.loadHardwoodsIndex(folderName)) }
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.navigation.SpecialtyAvailabilityTest"`
Expected: PASS

Note: this leaves `NavGraph.kt`'s two `SpecialtyJobDetailScreen(...)` call sites (lines ~1605 and ~2919) not passing `hasPullsSheet` yet — that won't fail this test (it's not compiled against `SpecialtyJobDetailScreen`'s signature here), but the app module won't compile until Task 5 adds the parameter to `SpecialtyJobDetailScreen` and updates both call sites together. Don't run `compileDebugKotlin` between Task 2 and Task 5.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/navigation/SpecialtyAvailability.kt app/src/test/java/com/kkc/sheettracker/navigation/SpecialtyAvailabilityTest.kt
git commit -m "feat(pulls): add hasPullsSheet to SpecialtyAvailability"
```

---

## Task 3: CNC job details screen — rename buttons, add Pulls button

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/detail/JobDetailScreen.kt:252-264` and `:417-476`

- [ ] **Step 1: Add `hasPullsSheet` state and fetch it alongside the other flags**

Replace lines 252-264:

```kotlin
    // Document availability loaded async — avoids blocking the composition thread on I/O
    var hasDeliverySheet by remember(jobFolderName) { mutableStateOf(false) }
    var hasPullsSheet by remember(jobFolderName) { mutableStateOf(false) }
    var hasAssemblySheet by remember(jobFolderName) { mutableStateOf(false) }
    var hasPlansElevations by remember(jobFolderName) { mutableStateOf(false) }
    var hasThreeDAssets by remember(jobFolderName) { mutableStateOf(false) }
    LaunchedEffect(jobFolderName) {
        withContext(Dispatchers.IO) {
            val catalog = jobRepository.getJobPdfCatalog(jobFolderName)
            hasDeliverySheet = catalog.deliverySheet != null
            hasPullsSheet = catalog.pullsSheet != null
            hasAssemblySheet = jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.ASSEMBLY)
            hasPlansElevations = jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.PLANS_ELEVATIONS)
            hasThreeDAssets = jobRepository.hasThreeDAssets(jobFolderName)
        }
    }
```

- [ ] **Step 2: Rename buttons and add the Pulls button**

Replace lines 417-476 (the `item(key = "reference-doc-buttons")` block):

```kotlin
                item(key = "reference-doc-buttons") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (hasAssemblySheet) {
                            Button(
                                onClick = {
                                    suppressLeavePrompt = true
                                    onOpenReferenceDocument(ReferenceDocType.ASSEMBLY, 1)
                                }
                            ) {
                                Text("Assembly")
                            }
                        }
                        if (hasPlansElevations) {
                            Button(
                                onClick = {
                                    suppressLeavePrompt = true
                                    onOpenReferenceDocument(ReferenceDocType.PLANS_ELEVATIONS, 1)
                                }
                            ) {
                                Text("Plans & Elevations")
                            }
                        }
                        if (hasDeliverySheet) {
                            Button(
                                onClick = {
                                    suppressLeavePrompt = true
                                    onOpenReferenceDocument(ReferenceDocType.DELIVERY_SHEETS, 1)
                                }
                            ) {
                                Text("Delivery")
                            }
                        }
                        if (hasPullsSheet) {
                            Button(
                                onClick = {
                                    suppressLeavePrompt = true
                                    onOpenReferenceDocument(ReferenceDocType.PULLS, 1)
                                }
                            ) {
                                Text("Pulls")
                            }
                        }
                        if (hasThreeDAssets) {
                            Button(
                                onClick = {
                                    suppressLeavePrompt = true
                                    onOpenThreeD()
                                }
                            ) {
                                Text("3D")
                            }
                        }
                        Button(
                            onClick = { showPrintDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Print,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Print")
                        }
                    }
                }
```

- [ ] **Step 3: Compile check**

Run: `./gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/detail/JobDetailScreen.kt
git commit -m "feat(pulls): add Pulls button to CNC job details screen, rename reference buttons"
```

---

## Task 4: Hardwoods job details screen — rename buttons, add Pulls button

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobDetailScreen.kt:180-192` and `:267-298`

- [ ] **Step 1: Add `hasPullsSheet` state and fetch it alongside the other flags**

Replace lines 180-192:

```kotlin
    // Document availability loaded async — avoids blocking the composition thread on I/O
    var hasDeliverySheet by remember(jobFolderName) { mutableStateOf(false) }
    var hasPullsSheet by remember(jobFolderName) { mutableStateOf(false) }
    var hasAssemblySheet by remember(jobFolderName) { mutableStateOf(false) }
    var hasPlansElevations by remember(jobFolderName) { mutableStateOf(false) }
    var hasThreeDAssets by remember(jobFolderName) { mutableStateOf(false) }
    LaunchedEffect(jobFolderName) {
        withContext(Dispatchers.IO) {
            val catalog = jobRepository.getJobPdfCatalog(jobFolderName)
            hasDeliverySheet = catalog.deliverySheet != null
            hasPullsSheet = catalog.pullsSheet != null
            hasAssemblySheet = jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.ASSEMBLY)
            hasPlansElevations = jobRepository.hasReferenceDocument(jobFolderName, ReferenceDocType.PLANS_ELEVATIONS)
            hasThreeDAssets = jobRepository.hasThreeDAssets(jobFolderName)
        }
    }
```

- [ ] **Step 2: Rename buttons and add the Pulls button**

Replace lines 267-298:

```kotlin
                if (hasAssemblySheet) {
                    Button(onClick = {
                        suppressLeavePrompt = true
                        onOpenReferenceDocument(ReferenceDocType.ASSEMBLY, 1)
                    }) {
                        Text("Assembly")
                    }
                }
                if (hasPlansElevations) {
                    Button(onClick = {
                        suppressLeavePrompt = true
                        onOpenReferenceDocument(ReferenceDocType.PLANS_ELEVATIONS, 1)
                    }) {
                        Text("Plans & Elevations")
                    }
                }
                if (hasDeliverySheet) {
                    Button(onClick = {
                        suppressLeavePrompt = true
                        onOpenReferenceDocument(ReferenceDocType.DELIVERY_SHEETS, 1)
                    }) {
                        Text("Delivery")
                    }
                }
                if (hasPullsSheet) {
                    Button(onClick = {
                        suppressLeavePrompt = true
                        onOpenReferenceDocument(ReferenceDocType.PULLS, 1)
                    }) {
                        Text("Pulls")
                    }
                }
                if (hasThreeDAssets) {
                    Button(onClick = {
                        suppressLeavePrompt = true
                        onOpenThreeD()
                    }) {
                        Text("3D")
                    }
                }
```

- [ ] **Step 3: Compile check**

Run: `./gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/hardwoods/HardwoodsJobDetailScreen.kt
git commit -m "feat(pulls): add Pulls button to Hardwoods job details screen, rename reference buttons"
```

---

## Task 5: Specialty job details screen — rename buttons, add Pulls button, wire `NavGraph.kt`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt:130-149` and `:286-305`
- Modify: `app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt:1605-1614` and `:2919-2928`

- [ ] **Step 1: Add `hasPullsSheet` parameter**

In `SpecialtyJobDetailScreen.kt`, replace lines 130-149 (the function signature) — insert `hasPullsSheet: Boolean,` right after `hasDeliverySheet: Boolean,`:

```kotlin
fun SpecialtyJobDetailScreen(
    jobFolderName: String,
    specialtyStateStore: SpecialtyStateStore,
    specialtyViewerDefaultsStore: SpecialtyViewerDefaultsStore,
    jobRepository: JobRepository,
    hasAssemblySheet: Boolean,
    hasPlansElevations: Boolean,
    hasDeliverySheet: Boolean,
    hasPullsSheet: Boolean,
    hasThreeDAssets: Boolean,
    hasClosetRods: Boolean,
    onOpenReferenceDocument: (ReferenceDocType, Int) -> Unit,
    onOpenThreeD: () -> Unit,
    onOpenDoorPanels: () -> Unit,
    onOpenSawRipList: () -> Unit,
    onOpenClosetRods: () -> Unit,
    onOpenSplitView: () -> Unit,
    onJumpToCabinet: ((String) -> Unit)? = null,
    tabletId: String,
    archiveClientFactory: suspend () -> ArchiveLifecycleClient?,
    onArchiveCompleted: () -> Unit,
```

- [ ] **Step 2: Rename buttons and add the Pulls button**

Same file, replace lines 286-305:

```kotlin
                    if (hasAssemblySheet) {
                        Button(onClick = { onOpenReferenceDocument(ReferenceDocType.ASSEMBLY, 1) }) {
                            Text("Assembly")
                        }
                    }
                    if (hasPlansElevations) {
                        Button(onClick = { onOpenReferenceDocument(ReferenceDocType.PLANS_ELEVATIONS, 1) }) {
                            Text("Plans & Elevations")
                        }
                    }
                    if (hasDeliverySheet) {
                        Button(onClick = { onOpenReferenceDocument(ReferenceDocType.DELIVERY_SHEETS, 1) }) {
                            Text("Delivery")
                        }
                    }
                    if (hasPullsSheet) {
                        Button(onClick = { onOpenReferenceDocument(ReferenceDocType.PULLS, 1) }) {
                            Text("Pulls")
                        }
                    }
                    if (hasThreeDAssets) {
                        Button(onClick = onOpenThreeD) {
                            Text("3D")
                        }
                    }
```

- [ ] **Step 3: Pass `hasPullsSheet` from both `NavGraph.kt` call sites**

In `NavGraph.kt`, line 1610-1614, insert `hasPullsSheet = availability.hasPullsSheet,` after `hasDeliverySheet = availability.hasDeliverySheet,`:

```kotlin
                hasAssemblySheet = availability.hasAssemblySheet,
                hasPlansElevations = availability.hasPlansElevations,
                hasDeliverySheet = availability.hasDeliverySheet,
                hasPullsSheet = availability.hasPullsSheet,
                hasThreeDAssets = availability.hasThreeDAssets,
                hasClosetRods = availability.hasClosetRods,
```

Same file, line 2924-2928, same edit:

```kotlin
                        hasAssemblySheet = availability.hasAssemblySheet,
                        hasPlansElevations = availability.hasPlansElevations,
                        hasDeliverySheet = availability.hasDeliverySheet,
                        hasPullsSheet = availability.hasPullsSheet,
                        hasThreeDAssets = availability.hasThreeDAssets,
                        hasClosetRods = availability.hasClosetRods,
```

- [ ] **Step 4: Compile check**

Run: `./gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/specialty/SpecialtyJobDetailScreen.kt app/src/main/java/com/kkc/sheettracker/navigation/NavGraph.kt
git commit -m "feat(pulls): add Pulls button to Specialty job details screen, rename reference buttons"
```

---

## Task 6: Assembly PDF viewer — `PaneSource.PULLS` and chip

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt`

- [ ] **Step 1: Add `PULLS` to the local `PaneSource` enum and `isPdfSource()`**

Replace lines 157-176:

```kotlin
private enum class PaneSource {
    PLANS,
    ASSEMBLY,
    DELIVERY,
    PULLS,
    OTHER,
    THREE_D,
    CHECKLIST
}

private enum class PaneSlot {
    FIRST,
    SECOND
}

private fun PaneSource.isPdfSource(): Boolean {
    return this == PaneSource.PLANS ||
        this == PaneSource.ASSEMBLY ||
        this == PaneSource.DELIVERY ||
        this == PaneSource.PULLS ||
        this == PaneSource.OTHER
}
```

- [ ] **Step 2: Add `pullsFilename`, sourced from the PDF catalog**

Replace lines 316-318:

```kotlin
    val deliveryFilename = remember(pdfCatalog?.deliverySheet) {
        pdfCatalog?.deliverySheet?.pdfFilename.orEmpty()
    }
    val pullsFilename = remember(pdfCatalog?.pullsSheet) {
        pdfCatalog?.pullsSheet?.pdfFilename.orEmpty()
    }
```

- [ ] **Step 3: Add per-pane Pulls page state**

Replace lines 388-389:

```kotlin
    var firstPaneDeliveryPage by rememberSaveable { mutableIntStateOf(1) }
    var secondPaneDeliveryPage by rememberSaveable { mutableIntStateOf(1) }
    var firstPanePullsPage by rememberSaveable { mutableIntStateOf(1) }
    var secondPanePullsPage by rememberSaveable { mutableIntStateOf(1) }
```

- [ ] **Step 4: Wire `PULLS` into `sourceLabel`, `sourceFilename`, `sourcePage`, `setSourcePage`**

Replace lines 588-637:

```kotlin
    fun sourceLabel(source: PaneSource): String = when (source) {
        PaneSource.PLANS -> "Plans"
        PaneSource.ASSEMBLY -> "Assembly"
        PaneSource.DELIVERY -> "Delivery"
        PaneSource.PULLS -> "Pulls"
        PaneSource.OTHER -> "Other"
        PaneSource.THREE_D -> "3D"
        PaneSource.CHECKLIST -> "Checklist"
    }

    fun sourceFilename(source: PaneSource, otherFilename: String?): String? = when (source) {
        PaneSource.PLANS -> plansFilename.takeIf { it.isNotBlank() }
        PaneSource.ASSEMBLY -> assemblyFilename.takeIf { it.isNotBlank() }
        PaneSource.DELIVERY -> deliveryFilename.takeIf { it.isNotBlank() }
        PaneSource.PULLS -> pullsFilename.takeIf { it.isNotBlank() }
        PaneSource.OTHER -> otherFilename?.takeIf { it.isNotBlank() }
        PaneSource.THREE_D -> null
        PaneSource.CHECKLIST -> null
    }

    fun sourcePage(source: PaneSource, assemblyPageVal: Int, plansPageVal: Int, otherPage: Int, deliveryPage: Int, pullsPage: Int): Int = when (source) {
        PaneSource.PLANS -> plansPageVal
        PaneSource.ASSEMBLY -> assemblyPageVal
        PaneSource.DELIVERY -> deliveryPage
        PaneSource.PULLS -> pullsPage
        PaneSource.OTHER -> otherPage
        PaneSource.THREE_D -> 1
        PaneSource.CHECKLIST -> 1
    }

    fun setSourcePage(
        source: PaneSource,
        nextPage: Int,
        setPlans: (Int) -> Unit,
        setAssembly: (Int) -> Unit,
        setOther: (Int) -> Unit,
        setDelivery: (Int) -> Unit,
        setPulls: (Int) -> Unit
    ) {
        when (source) {
            PaneSource.PLANS -> setPlans(nextPage)
            PaneSource.ASSEMBLY -> {
                if (hasVirtualAssembly) {
                    setAssembly(nextPage.coerceIn(1, assemblyVirtualTotalPages.coerceAtLeast(1)))
                } else {
                    setAssembly(nextPage.coerceAtLeast(1))
                }
            }
            PaneSource.DELIVERY -> setDelivery(nextPage)
            PaneSource.PULLS -> setPulls(nextPage)
            PaneSource.OTHER -> setOther(nextPage)
            PaneSource.THREE_D -> Unit
            PaneSource.CHECKLIST -> Unit
        }
    }
```

- [ ] **Step 5: Update both `sourcePage(...)` call sites to pass the pulls page**

Line 786 (first pane) — find:

```kotlin
                        currentPage = sourcePage(firstPaneSource, firstPaneAssemblyPage, firstPanePlansPage, firstPaneOtherPage, firstPaneDeliveryPage),
```

Replace with:

```kotlin
                        currentPage = sourcePage(firstPaneSource, firstPaneAssemblyPage, firstPanePlansPage, firstPaneOtherPage, firstPaneDeliveryPage, firstPanePullsPage),
```

Line 894 (second pane) — find:

```kotlin
                        currentPage = sourcePage(secondPaneSource, secondPaneAssemblyPage, secondPanePlansPage, secondPaneOtherPage, secondPaneDeliveryPage),
```

Replace with:

```kotlin
                        currentPage = sourcePage(secondPaneSource, secondPaneAssemblyPage, secondPanePlansPage, secondPaneOtherPage, secondPaneDeliveryPage, secondPanePullsPage),
```

Also update the two matching page-count `Text` uses. First pane (originally line 1008) — find:

```kotlin
                                text = "${sourcePage(firstPaneSource, firstPaneAssemblyPage, firstPanePlansPage, firstPaneOtherPage, firstPaneDeliveryPage)}/${firstPaneTotalPages.coerceAtLeast(0)}",
```

Replace with:

```kotlin
                                text = "${sourcePage(firstPaneSource, firstPaneAssemblyPage, firstPanePlansPage, firstPaneOtherPage, firstPaneDeliveryPage, firstPanePullsPage)}/${firstPaneTotalPages.coerceAtLeast(0)}",
```

Second pane (originally line 1044) — find:

```kotlin
                                text = "${sourcePage(secondPaneSource, secondPaneAssemblyPage, secondPanePlansPage, secondPaneOtherPage, secondPaneDeliveryPage)}/${secondPaneTotalPages.coerceAtLeast(0)}",
```

Replace with:

```kotlin
                                text = "${sourcePage(secondPaneSource, secondPaneAssemblyPage, secondPanePlansPage, secondPaneOtherPage, secondPaneDeliveryPage, secondPanePullsPage)}/${secondPaneTotalPages.coerceAtLeast(0)}",
```

- [ ] **Step 6: Update both `setSourcePage(...)` call sites to pass `setPulls`**

First pane (originally lines 792-795) — find:

```kotlin
                                setPlans = { firstPanePlansPage = it },
                                setAssembly = { firstPaneAssemblyPage = it },
```
```kotlin
                                setDelivery = { firstPaneDeliveryPage = it }
```

Replace the closing block with:

```kotlin
                                setPlans = { firstPanePlansPage = it },
                                setAssembly = { firstPaneAssemblyPage = it },
```
```kotlin
                                setDelivery = { firstPaneDeliveryPage = it },
                                setPulls = { firstPanePullsPage = it }
```

Second pane (originally lines 900-903) — same edit using `secondPane...`:

```kotlin
                                setPlans = { secondPanePlansPage = it },
                                setAssembly = { secondPaneAssemblyPage = it },
```
```kotlin
                                setDelivery = { secondPaneDeliveryPage = it },
                                setPulls = { secondPanePullsPage = it }
```

- [ ] **Step 7: Add the "Pulls" chip**

In `PaneSourceControlsInline` (originally lines 1398-1403), insert immediately after the `DELIVERY` chip:

```kotlin
    FilterChip(
        selected = selectedSource == PaneSource.DELIVERY,
        onClick = { onSelectSource(PaneSource.DELIVERY) },
        label = { Text("Delivery") },
        shape = MaterialTheme.shapes.small
    )
    FilterChip(
        selected = selectedSource == PaneSource.PULLS,
        onClick = { onSelectSource(PaneSource.PULLS) },
        label = { Text("Pulls") },
        shape = MaterialTheme.shapes.small
    )
```

- [ ] **Step 8: Compile check**

Run: `./gradlew.bat compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If it fails, search the file for any other `sourcePage(` / `setSourcePage(` call the grep in this plan's research missed (there were exactly 2 of each in this file as of this plan's writing) and apply the same 6th-argument / `setPulls` addition.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/assembly/AssemblyViewerScreen.kt
git commit -m "feat(pulls): add Pulls chip to Assembly PDF viewer"
```

---

## Task 7: Build and manual verification

**Files:** none (verification only)

- [ ] **Step 1: Full debug build**

Run: `./gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Full unit test suite**

Run: `./gradlew.bat testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (including the two new `UnifiedMetadataEngineTest` cases and the updated `SpecialtyAvailabilityTest` case)

- [ ] **Step 3: Install on a connected tablet and manually verify**

Use the project's `debug-android-tablet` skill / `adb-install-release.ps1` (or `adb install -r app\build\outputs\apk\debug\app-debug.apk` for a debug build) per `CLAUDE.md`. Verify:
- A job with `<job> - PULLS.pdf` at its root shows a `Pulls` button on the CNC, Hardwoods, and Specialty job-details screens, and tapping it opens the PDF full-screen with title "Pulls".
- A job without that file shows no `Pulls` button on any of the three screens.
- The `Delivery` button (renamed from "View Cover Sheet") still opens the delivery-sheet PDF, titled "Delivery" (not "Cover Sheet").
- The `Assembly`, `Plans & Elevations`, and `3D` buttons show the new shorter text and still work.
- In the Assembly PDF viewer (opened for a job with no job-details screen, or via the Assembly button), the `Pulls` chip appears next to `Delivery`, `3D`, `Checklist`; selecting it in either pane loads the Pulls PDF; on a job without a Pulls file it shows the existing "PDF not found" empty state rather than crashing.

- [ ] **Step 4: No commit for this task** (verification only — if any issue is found, fix it in the relevant task above and re-commit there).

---

## Task 8: Hours Tracker handoff prompt

**Files:** none (this produces a message to the user, not a repository change)

- [ ] **Step 1: Write and deliver the handoff prompt**

Compose a self-contained prompt for a session opened in `C:\Scripts\Hours Tracker` covering:
- The Pulls filename convention: `<job> - PULLS.pdf` at job root (e.g. `Y:\Ready Jobs\596 - HARSHBARGER 2793 TOMAHAWK\596 - PULLS.pdf`), detected by lowercase filename containing `"pulls"`. No parsing — pure PDF view.
- Reference implementation pointers into this repo: `FileBackedUnifiedMetadataEngine.buildPdfCatalog` (`app/src/main/java/com/kkc/sheettracker/data/unified/FileBackedUnifiedMetadataEngine.kt`) for the detection pattern, and `findReferencePdfFilename`'s `PULLS` special-case for the lookup pattern.
- Per the KKC metadata map, Hours Tracker already reads job-root PDFs from the same shared `Y:\Ready Jobs` tree as KKCSheetTracker for other doc types — this is an additive second reader, no ownership conflict.
- Deliver this prompt directly to the user in chat (not a repo file) — they'll paste it into a new session opened against the Hours Tracker repo.

---

## Self-Review Notes

- **Spec coverage:** File detection (Task 1) — done. Job-details rename + Pulls button, all three screens (Tasks 3-5) — done. Assembly viewer chip (Task 6) — done. Hours Tracker handoff (Task 8) — done, correctly scoped as a prompt, not code. Testing section from the spec — covered by Tasks 1, 2, 7.
- **Type consistency checked:** `pullsSheet: JobPdfRef?` name matches across `Models.kt`, `buildPdfCatalog`, `findReferencePdfFilename`, `AssemblyViewerScreen.kt`'s `pullsFilename`, and both `hasPullsSheet` booleans (screen-local state and `SpecialtyAvailability` field) — same name used everywhere, no `pullSheet`/`pullsDoc` drift.
- **Placeholder scan:** no TBD/TODO; every step shows exact code, exact file:line, exact commands.
