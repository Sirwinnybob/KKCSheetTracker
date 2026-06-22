# CNC Dashboard Skeleton Loading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the CNC dashboard's empty→populated layout jump on initial load, and remove its now-unnecessary loading bar.

**Architecture:** `CncRecentMaterialCard` and `CncRemakeMaterialCard` accept a nullable `item` so a single skeleton instance can reuse the exact same layout (guaranteeing identical height to a real card, no hardcoded numbers). `CncRecentMaterialsSection` and `CncRemakesSection` gain a `hasLoadedOnce` flag — sourced from the existing `AppUiState.lastUpdatedAt` field, which is `0L` until the first derivation completes and never reverts afterward — to decide whether to show a skeleton card, real cards, or the empty state. `Modifier.animateContentSize()` smooths the Recent section's height changes; `AnimatedVisibility` smooths the Remakes section's show/hide.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), `androidx.compose.animation` (already a transitive dependency, used elsewhere in this codebase — see `AppScaffold.kt`, `JobBoardGrid.kt`).

**Spec:** `docs/superpowers/specs/2026-06-19-cnc-dashboard-skeleton-loading-design.md`

**Single file touched:** `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt`

No automated test infrastructure exists for Compose UI in this project (confirmed in the design spec). Verification is: a Gradle compile check after each task, and a full manual on-device pass at the end (Task 6).

---

### Task 1: Add animation imports

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt:6-7`

- [ ] **Step 1: Add the six `androidx.compose.animation` imports**

Find these two lines (currently lines 6-7):

```kotlin
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
```

Replace with:

```kotlin
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
```

- [ ] **Step 2: Compile to verify the imports resolve**

Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL` (these symbols are already used elsewhere in the project, e.g. `app/src/main/java/com/kkc/sheettracker/ui/components/AppScaffold.kt`, so they resolve without any `build.gradle.kts` dependency change).

---

### Task 2: Make `CncRecentMaterialCard` render a skeleton when `item` is null

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt` (function `CncRecentMaterialCard`, currently lines 527-623)

- [ ] **Step 1: Replace the function body**

Find the existing function:

```kotlin
@Composable
private fun CncRecentMaterialCard(
    item: DashboardRecentMaterialItem,
    thumbnail: Bitmap?,
    onClick: () -> Unit
) {
    val tileAccent = recentMaterialAccent(item.counts)
    val tileShape = DashboardSurfaceDefaults.sectionShape
    DashboardSurfaceCard(
        modifier = Modifier
            .width(268.dp)
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = DashboardSurfaceDefaults.outlineColor(tileAccent).copy(alpha = 0.45f),
                shape = tileShape
            ),
        accent = tileAccent,
        shape = tileShape,
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Recent material preview",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    androidx.compose.material3.Icon(
                        Icons.Default.Description,
                        contentDescription = "Description icon",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                item.materialName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                "${item.jobFolderName} • Next sheet ${item.nextIncompletePage}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${item.counts.complete}/${item.counts.total} complete",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ProgressPill(
                    done = item.counts.complete,
                    total = item.counts.total,
                    state = if (item.counts.skipped >= item.counts.total && item.counts.total > 0) {
                        ProgressState.SKIPPED
                    } else {
                        ProgressState.from(item.counts.complete, item.counts.total)
                    }
                )
            }
            LinearProgressIndicator(
                progress = { item.completionFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = KKCThemeColors.statusColors.completeBorder,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DashboardAccentPill("C ${item.counts.complete}", DashboardAccent.SUCCESS)
                DashboardAccentPill("B ${item.counts.bad}", DashboardAccent.DANGER)
                DashboardAccentPill("S ${item.counts.skipped}", DashboardAccent.WARNING)
                DashboardAccentPill("R ${item.counts.notStarted}", DashboardAccent.INFO)
            }
        }
    }
}
```

Replace it with:

```kotlin
@Composable
private fun CncRecentMaterialCard(
    item: DashboardRecentMaterialItem?,
    thumbnail: Bitmap?,
    onClick: () -> Unit
) {
    val tileAccent = item?.let { recentMaterialAccent(it.counts) } ?: DashboardAccent.NEUTRAL
    val tileShape = DashboardSurfaceDefaults.sectionShape
    DashboardSurfaceCard(
        modifier = Modifier
            .width(268.dp)
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = DashboardSurfaceDefaults.outlineColor(tileAccent).copy(alpha = 0.45f),
                shape = tileShape
            ),
        accent = tileAccent,
        shape = tileShape,
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Recent material preview",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    androidx.compose.material3.Icon(
                        Icons.Default.Description,
                        contentDescription = "Description icon",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                item?.materialName ?: " ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                item?.let { "${it.jobFolderName} • Next sheet ${it.nextIncompletePage}" } ?: " ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item?.let { "${it.counts.complete}/${it.counts.total} complete" } ?: " ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ProgressPill(
                    done = item?.counts?.complete ?: 0,
                    total = item?.counts?.total ?: 0,
                    state = if (item != null && item.counts.skipped >= item.counts.total && item.counts.total > 0) {
                        ProgressState.SKIPPED
                    } else {
                        ProgressState.from(item?.counts?.complete ?: 0, item?.counts?.total ?: 0)
                    }
                )
            }
            LinearProgressIndicator(
                progress = { item?.completionFraction?.coerceIn(0f, 1f) ?: 0f },
                modifier = Modifier.fillMaxWidth(),
                color = KKCThemeColors.statusColors.completeBorder,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DashboardAccentPill(
                    item?.let { "C ${it.counts.complete}" } ?: "C",
                    if (item != null) DashboardAccent.SUCCESS else DashboardAccent.NEUTRAL
                )
                DashboardAccentPill(
                    item?.let { "B ${it.counts.bad}" } ?: "B",
                    if (item != null) DashboardAccent.DANGER else DashboardAccent.NEUTRAL
                )
                DashboardAccentPill(
                    item?.let { "S ${it.counts.skipped}" } ?: "S",
                    if (item != null) DashboardAccent.WARNING else DashboardAccent.NEUTRAL
                )
                DashboardAccentPill(
                    item?.let { "R ${it.counts.notStarted}" } ?: "R",
                    if (item != null) DashboardAccent.INFO else DashboardAccent.NEUTRAL
                )
            }
        }
    }
}
```

(Note: the only call site so far, in `CncRecentMaterialsSection`, still passes a non-null `DashboardRecentMaterialItem` — that's valid against a nullable parameter type, so this compiles standalone before Task 4 updates the call site.)

- [ ] **Step 2: Compile to verify**

Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`

---

### Task 3: Make `CncRemakeMaterialCard` render a skeleton when `item` is null

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt` (function `CncRemakeMaterialCard`, currently lines 357-446)

- [ ] **Step 1: Replace the function body**

Find the existing function:

```kotlin
@Composable
private fun CncRemakeMaterialCard(
    item: DashboardRecentMaterialItem,
    remakeColor: androidx.compose.ui.graphics.Color,
    thumbnail: Bitmap?,
    onClick: () -> Unit
) {
    val tileShape = DashboardSurfaceDefaults.sectionShape
    DashboardSurfaceCard(
        modifier = Modifier
            .width(268.dp)
            .clickable(onClick = onClick)
            .border(width = 2.dp, color = remakeColor, shape = tileShape),
        shape = tileShape,
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Remake material preview",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    androidx.compose.material3.Icon(
                        Icons.Default.Description,
                        contentDescription = "Description icon",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                item.materialName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = remakeColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${item.jobFolderName} • Next sheet ${item.nextIncompletePage}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${item.counts.complete}/${item.counts.total} complete",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ProgressPill(
                    done = item.counts.complete,
                    total = item.counts.total,
                    state = ProgressState.from(item.counts.complete, item.counts.total)
                )
            }
            LinearProgressIndicator(
                progress = { item.completionFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = remakeColor,
                trackColor = remakeColor.copy(alpha = 0.2f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DashboardAccentPill("C ${item.counts.complete}", DashboardAccent.SUCCESS)
                DashboardAccentPill("B ${item.counts.bad}", DashboardAccent.DANGER)
                DashboardAccentPill("S ${item.counts.skipped}", DashboardAccent.WARNING)
                DashboardAccentPill("R ${item.counts.notStarted}", DashboardAccent.INFO)
            }
        }
    }
}
```

Replace it with:

```kotlin
@Composable
private fun CncRemakeMaterialCard(
    item: DashboardRecentMaterialItem?,
    remakeColor: androidx.compose.ui.graphics.Color,
    thumbnail: Bitmap?,
    onClick: () -> Unit
) {
    val tileShape = DashboardSurfaceDefaults.sectionShape
    DashboardSurfaceCard(
        modifier = Modifier
            .width(268.dp)
            .clickable(onClick = onClick)
            .border(width = 2.dp, color = remakeColor, shape = tileShape),
        shape = tileShape,
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Remake material preview",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    androidx.compose.material3.Icon(
                        Icons.Default.Description,
                        contentDescription = "Description icon",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                item?.materialName ?: " ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = remakeColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                item?.let { "${it.jobFolderName} • Next sheet ${it.nextIncompletePage}" } ?: " ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item?.let { "${it.counts.complete}/${it.counts.total} complete" } ?: " ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ProgressPill(
                    done = item?.counts?.complete ?: 0,
                    total = item?.counts?.total ?: 0,
                    state = ProgressState.from(item?.counts?.complete ?: 0, item?.counts?.total ?: 0)
                )
            }
            LinearProgressIndicator(
                progress = { item?.completionFraction?.coerceIn(0f, 1f) ?: 0f },
                modifier = Modifier.fillMaxWidth(),
                color = remakeColor,
                trackColor = remakeColor.copy(alpha = 0.2f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DashboardAccentPill(
                    item?.let { "C ${it.counts.complete}" } ?: "C",
                    if (item != null) DashboardAccent.SUCCESS else DashboardAccent.NEUTRAL
                )
                DashboardAccentPill(
                    item?.let { "B ${it.counts.bad}" } ?: "B",
                    if (item != null) DashboardAccent.DANGER else DashboardAccent.NEUTRAL
                )
                DashboardAccentPill(
                    item?.let { "S ${it.counts.skipped}" } ?: "S",
                    if (item != null) DashboardAccent.WARNING else DashboardAccent.NEUTRAL
                )
                DashboardAccentPill(
                    item?.let { "R ${it.counts.notStarted}" } ?: "R",
                    if (item != null) DashboardAccent.INFO else DashboardAccent.NEUTRAL
                )
            }
        }
    }
}
```

- [ ] **Step 2: Compile to verify**

Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt
git commit -m "feat(dashboard): support nullable item on CNC card composables for skeleton rendering"
```

---

### Task 4: Add `hasLoadedOnce` skeleton/empty/populated branching to `CncRecentMaterialsSection`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt` (function `CncRecentMaterialsSection`, currently lines 262-306)

- [ ] **Step 1: Replace the function body**

Find:

```kotlin
@Composable
private fun CncRecentMaterialsSection(
    items: List<DashboardRecentMaterialItem>,
    jobRepository: JobRepository,
    onOpenSheet: (jobFolderName: String, pdfFilename: String, page: Int) -> Unit
) {
    DashboardSurfaceCard {
        DashboardSectionHeader(
            title = "Recent In-Progress Materials",
            subtitle = if (items.isEmpty()) null else "${items.size} recent material${if (items.size == 1) "" else "s"}"
        )
        if (items.isEmpty()) {
            Text(
                "Nothing is in progress right now.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items.forEach { item ->
                    val thumbnail by produceState<Bitmap?>(
                        initialValue = null,
                        item.jobFolderName,
                        item.pdfFilename,
                        item.thumbnailPath,
                        item.nextIncompletePage
                    ) {
                        value = withContext(Dispatchers.IO) {
                            loadRecentMaterialThumbnail(jobRepository, item)
                        }
                    }
                    CncRecentMaterialCard(
                        item = item,
                        thumbnail = thumbnail,
                        onClick = {
                            onOpenSheet(item.jobFolderName, item.pdfFilename, item.nextIncompletePage)
                        }
                    )
                }
            }
        }
    }
}
```

Replace it with:

```kotlin
@Composable
private fun CncRecentMaterialsSection(
    items: List<DashboardRecentMaterialItem>,
    hasLoadedOnce: Boolean,
    jobRepository: JobRepository,
    onOpenSheet: (jobFolderName: String, pdfFilename: String, page: Int) -> Unit
) {
    DashboardSurfaceCard {
        DashboardSectionHeader(
            title = "Recent In-Progress Materials",
            subtitle = if (items.isEmpty()) null else "${items.size} recent material${if (items.size == 1) "" else "s"}"
        )
        Box(modifier = Modifier.animateContentSize()) {
            when {
                !hasLoadedOnce -> {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CncRecentMaterialCard(item = null, thumbnail = null, onClick = {})
                    }
                }
                items.isEmpty() -> {
                    Text(
                        "Nothing is in progress right now.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items.forEach { item ->
                            val thumbnail by produceState<Bitmap?>(
                                initialValue = null,
                                item.jobFolderName,
                                item.pdfFilename,
                                item.thumbnailPath,
                                item.nextIncompletePage
                            ) {
                                value = withContext(Dispatchers.IO) {
                                    loadRecentMaterialThumbnail(jobRepository, item)
                                }
                            }
                            CncRecentMaterialCard(
                                item = item,
                                thumbnail = thumbnail,
                                onClick = {
                                    onOpenSheet(item.jobFolderName, item.pdfFilename, item.nextIncompletePage)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Compile to verify**

This will fail until Task 6 updates the call site to pass the new `hasLoadedOnce` parameter — that's expected.

Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: FAIL — `No value passed for parameter 'hasLoadedOnce'` at the `CncRecentMaterialsSection(...)` call site in `CncDashboardContent`. Confirms the new required parameter is wired into the function signature correctly. Continue to Task 5; Task 6 fixes the call site.

---

### Task 5: Add `hasLoadedOnce` skeleton branching to `CncRemakesSection`

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt` (function `CncRemakesSection`, currently lines 308-355)

- [ ] **Step 1: Replace the function body**

Find:

```kotlin
@Composable
private fun CncRemakesSection(
    items: List<DashboardRecentMaterialItem>,
    jobRepository: JobRepository,
    onOpenSheet: (jobFolderName: String, pdfFilename: String, page: Int) -> Unit
) {
    val remakeColor = KKCThemeColors.statusColors.remakeBg
    DashboardSurfaceCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(remakeColor, CircleShape)
            )
            DashboardSectionHeader(
                title = "Incomplete Remakes",
                subtitle = "${items.size} remake${if (items.size == 1) "" else "s"} pending"
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items.forEach { item ->
                val thumbnail by produceState<Bitmap?>(
                    initialValue = null,
                    item.jobFolderName,
                    item.pdfFilename,
                    item.thumbnailPath,
                    item.nextIncompletePage
                ) {
                    value = withContext(Dispatchers.IO) {
                        loadRecentMaterialThumbnail(jobRepository, item)
                    }
                }
                CncRemakeMaterialCard(
                    item = item,
                    remakeColor = remakeColor,
                    thumbnail = thumbnail,
                    onClick = { onOpenSheet(item.jobFolderName, item.pdfFilename, item.nextIncompletePage) }
                )
            }
        }
    }
}
```

Replace it with:

```kotlin
@Composable
private fun CncRemakesSection(
    items: List<DashboardRecentMaterialItem>,
    hasLoadedOnce: Boolean,
    jobRepository: JobRepository,
    onOpenSheet: (jobFolderName: String, pdfFilename: String, page: Int) -> Unit
) {
    val remakeColor = KKCThemeColors.statusColors.remakeBg
    DashboardSurfaceCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(remakeColor, CircleShape)
            )
            DashboardSectionHeader(
                title = "Incomplete Remakes",
                subtitle = "${items.size} remake${if (items.size == 1) "" else "s"} pending"
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasLoadedOnce) {
                CncRemakeMaterialCard(item = null, remakeColor = remakeColor, thumbnail = null, onClick = {})
            } else {
                items.forEach { item ->
                    val thumbnail by produceState<Bitmap?>(
                        initialValue = null,
                        item.jobFolderName,
                        item.pdfFilename,
                        item.thumbnailPath,
                        item.nextIncompletePage
                    ) {
                        value = withContext(Dispatchers.IO) {
                            loadRecentMaterialThumbnail(jobRepository, item)
                        }
                    }
                    CncRemakeMaterialCard(
                        item = item,
                        remakeColor = remakeColor,
                        thumbnail = thumbnail,
                        onClick = { onOpenSheet(item.jobFolderName, item.pdfFilename, item.nextIncompletePage) }
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Compile to verify**

Still expected to FAIL for the same reason as Task 4 (call site not yet updated). Continue to Task 6, which fixes both call sites at once.

---

### Task 6: Wire `hasLoadedOnce` into `CncDashboardContent`, wrap Remakes in `AnimatedVisibility`, remove the loading bar

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt` (function `CncDashboardContent`, currently lines 157-260)

- [ ] **Step 1: Replace the `DashboardShell(...)` call and its body**

Find (currently lines 198-230):

```kotlin
    DashboardShell(
        title = "Dashboard",
        subtitle = "CNC",
        loading = scanState.status == ScanStatus.LOADING || appUiState.isRefreshing,
        errorMessage = scanState.errorMessage ?: appUiState.errorMessage,
        emptyMessage = "No CNC dashboard widgets are available yet.",
        hasContent = widgets.isNotEmpty(),
        onRefresh = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }
    ) {
        DashboardWidgetRenderer(
            widgets = nonRecentWidgets,
            onStatAction = {
                when (it) {
                    DashboardStatAction.BAD_PARTS -> showBadList = true
                    DashboardStatAction.SKIPPED -> showSkippedList = true
                }
            },
            onAlertAction = { showBadList = true }
        )
        CncRecentMaterialsSection(
            items = dashboard.recentInProgressMaterials,
            jobRepository = jobRepository,
            onOpenSheet = onOpenSheet
        )
        if (dashboard.incompleteRemakeMaterials.isNotEmpty()) {
            CncRemakesSection(
                items = dashboard.incompleteRemakeMaterials,
                jobRepository = jobRepository,
                onOpenSheet = onOpenSheet
            )
        }
        TextButton(onClick = onNavigateToJobs) { Text("View All Jobs") }
    }
```

Replace it with:

```kotlin
    val hasLoadedOnce = appUiState.lastUpdatedAt > 0L

    DashboardShell(
        title = "Dashboard",
        subtitle = "CNC",
        loading = false,
        errorMessage = scanState.errorMessage ?: appUiState.errorMessage,
        emptyMessage = "No CNC dashboard widgets are available yet.",
        hasContent = widgets.isNotEmpty(),
        onRefresh = { scanCoordinator.refresh(RefreshReason.USER_REFRESH, force = true) }
    ) {
        DashboardWidgetRenderer(
            widgets = nonRecentWidgets,
            onStatAction = {
                when (it) {
                    DashboardStatAction.BAD_PARTS -> showBadList = true
                    DashboardStatAction.SKIPPED -> showSkippedList = true
                }
            },
            onAlertAction = { showBadList = true }
        )
        CncRecentMaterialsSection(
            items = dashboard.recentInProgressMaterials,
            hasLoadedOnce = hasLoadedOnce,
            jobRepository = jobRepository,
            onOpenSheet = onOpenSheet
        )
        AnimatedVisibility(
            visible = !hasLoadedOnce || dashboard.incompleteRemakeMaterials.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            CncRemakesSection(
                items = dashboard.incompleteRemakeMaterials,
                hasLoadedOnce = hasLoadedOnce,
                jobRepository = jobRepository,
                onOpenSheet = onOpenSheet
            )
        }
        TextButton(onClick = onNavigateToJobs) { Text("View All Jobs") }
    }
```

- [ ] **Step 2: Compile to verify**

Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: `BUILD SUCCESSFUL` (this resolves the two pending-parameter failures from Tasks 4 and 5).

- [ ] **Step 3: Run the full unit test suite to confirm no regression**

Run: `.\gradlew.bat :app:testDebugUnitTest --console=plain`
Expected: Same pass/fail counts as before this change (this file has no unit tests directly, but `UnifiedDashboardFactoriesTest` exercises an adjacent, unmodified function — `buildCncDashboardWidgets` — and should be unaffected either way).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/dashboard/UnifiedModeDashboardScreen.kt
git commit -m "feat(dashboard): skeleton-load CNC recent/remakes sections, drop loading bar"
```

---

### Task 7: Manual on-device verification

**Prerequisite:** A debug or release build installed on a device/emulator, matching the steps used earlier in this project (see `CLAUDE.md` → Build). If installing over an existing release-signed build, build `assembleRelease` with the project's `keystore.properties` rather than uninstalling, to avoid wiping local app data.

- [ ] **Step 1: Build and install**

```bash
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

- [ ] **Step 2: Cold-launch verification**

```bash
adb shell am force-stop com.kkc.sheettracker
adb shell monkey -p com.kkc.sheettracker -c android.intent.category.LAUNCHER 1
```

Navigate to CNC mode → Dashboard (or confirm it's the landing screen). Confirm:
- No visible height jump in the "Recent In-Progress Materials" or "Incomplete Remakes" sections — cards (or empty-state text / collapsed section) appear directly in their final position.
- No loading bar appears at the top of the screen, including right after launch.

- [ ] **Step 3: Populated-remakes verification**

On a job board with at least one incomplete remake, confirm the Remakes section is present from the first frame and the real card fills in without the section resizing.

- [ ] **Step 4: Empty-remakes verification**

On a job board with zero incomplete remakes, confirm the section briefly appears (skeleton) then collapses smoothly via the shrink animation, rather than never appearing or vanishing abruptly.

- [ ] **Step 5: Pull-to-refresh verification**

Trigger a manual refresh (the refresh icon in the top bar). Confirm no loading bar appears during the refresh.

- [ ] **Step 6: Background re-derive verification**

While the dashboard is open, mark a sheet complete from another tablet on the same job board (or, if no second device is available, mark a sheet complete from the Job Browser on this device and navigate back to the dashboard). Confirm the Recent/Remakes sections update with the new data directly — no skeleton replay.

- [ ] **Step 7: Re-visit verification**

Navigate away from the dashboard (e.g., to Job Browser) and back. Confirm no skeleton replay on the second visit, since `hasLoadedOnce` is already `true` from the first visit this session.

If any step fails, note which one and stop — do not proceed to further changes without re-diagnosing against the design spec.
