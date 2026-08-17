# Continuous PDF Refresh and Gesture Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent unrelated scan generations from refreshing an unchanged continuous PDF or leaving its gesture handler attached to obsolete zoom state.

**Architecture:** Derive one immutable identity from the resolved page mapping and actual PDF file metadata. The global scan generation only triggers recomputation; Compose render, interaction, and pointer-input lifetimes use the derived identity so they either remain together or reset together.

**Tech Stack:** Kotlin, Jetpack Compose, `java.io.File`, JUnit 4, Gradle, Android ADB.

## Global Constraints

- Never run `adb uninstall`; tablet deployment uses `adb install -r` only.
- Do not add dependencies, services, pollers, or persistent state.
- Preserve paged viewer behavior, virtual mappings, dark-file selection, markup exclusion, scrollbar navigation, page resume, and offline operation.
- Preserve the user's unrelated working-tree changes; stage only files owned by each task.
- Remove `PdfRenderTrace` and routine `PdfFlingDebug` calls entirely; do not move them behind another logger.
- Remove page-coordinate and crop-overlay entries when a lazy page leaves composition; do not recycle shared bitmaps.

---

### Task 1: Resolved continuous-document identity

**Files:**
- Create: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousPdfDocumentIdentity.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt`

**Interfaces:**
- Consumes: `ResolvedPageSource(pdfFilename: String, sourcePage: Int)` and the existing `resolvePage` / `pdfFileForFilename` callbacks.
- Produces: `ContinuousPdfDocumentIdentity`, `ContinuousPdfPageIdentity`, `ContinuousPdfSourceIdentity`, and `resolveContinuousPdfDocumentIdentity(Int, (Int) -> ResolvedPageSource, (String) -> File?): ContinuousPdfDocumentIdentity`.

- [ ] **Step 1: Write the failing identity tests**

Add focused tests to `ContinuousReferencePdfPaneTest`:

```kotlin
@Test
fun continuousDocumentIdentity_isStableAcrossUnrelatedRecomputation() {
    val dir = Files.createTempDirectory("continuous-pdf-identity").toFile()
    val pdf = File(dir, "assembly.pdf").apply {
        writeBytes(byteArrayOf(1, 2, 3))
        setLastModified(1_700_000_000_000L)
    }
    val resolve = { page: Int -> ResolvedPageSource("assembly.pdf", page) }
    val files = { _: String -> pdf }

    val before = resolveContinuousPdfDocumentIdentity(2, resolve, files)
    val afterUnrelatedRefresh = resolveContinuousPdfDocumentIdentity(2, resolve, files)

    assertEquals(before, afterUnrelatedRefresh)
}

@Test
fun continuousDocumentIdentity_changesWhenMappingChanges() {
    val pdf = File(Files.createTempDirectory("continuous-pdf-map").toFile(), "assembly.pdf")
        .apply { writeBytes(byteArrayOf(1)) }
    val files = { _: String -> pdf }
    val first = resolveContinuousPdfDocumentIdentity(
        totalPages = 2,
        resolvePage = { page -> ResolvedPageSource("assembly.pdf", page) },
        pdfFileForFilename = files
    )
    val remapped = resolveContinuousPdfDocumentIdentity(
        totalPages = 2,
        resolvePage = { page -> ResolvedPageSource("assembly.pdf", 3 - page) },
        pdfFileForFilename = files
    )

    assertNotEquals(first, remapped)
}

@Test
fun continuousDocumentIdentity_changesWhenSourceFileChanges() {
    val pdf = File(Files.createTempDirectory("continuous-pdf-file").toFile(), "assembly.pdf")
        .apply { writeBytes(byteArrayOf(1)) }
    val resolve = { page: Int -> ResolvedPageSource("assembly.pdf", page) }
    val before = resolveContinuousPdfDocumentIdentity(1, resolve) { pdf }

    pdf.appendBytes(byteArrayOf(2))
    pdf.setLastModified(pdf.lastModified() + 1_000L)
    val after = resolveContinuousPdfDocumentIdentity(1, resolve) { pdf }

    assertNotEquals(before, after)
}

@Test
fun continuousDocumentIdentity_recordsMissingSource() {
    val identity = resolveContinuousPdfDocumentIdentity(
        totalPages = 1,
        resolvePage = { ResolvedPageSource("missing.pdf", 1) },
        pdfFileForFilename = { null }
    )

    assertEquals(false, identity.sources.single().exists)
    assertEquals("missing.pdf", identity.sources.single().pdfFilename)
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest"
```

Expected: compilation fails only because the new identity types/function do not exist.

- [ ] **Step 3: Implement the pure identity helper**

Create `ContinuousPdfDocumentIdentity.kt` with this interface and behavior:

```kotlin
package com.kkc.sheettracker.ui.components

import com.kkc.sheettracker.ui.viewer.ResolvedPageSource
import java.io.File

internal data class ContinuousPdfPageIdentity(
    val displayPage: Int,
    val pdfFilename: String,
    val sourcePage: Int
)

internal data class ContinuousPdfSourceIdentity(
    val pdfFilename: String,
    val absolutePath: String?,
    val exists: Boolean,
    val length: Long,
    val lastModified: Long
)

internal data class ContinuousPdfDocumentIdentity(
    val pages: List<ContinuousPdfPageIdentity>,
    val sources: List<ContinuousPdfSourceIdentity>
)

internal fun resolveContinuousPdfDocumentIdentity(
    totalPages: Int,
    resolvePage: (Int) -> ResolvedPageSource,
    pdfFileForFilename: (String) -> File?
): ContinuousPdfDocumentIdentity {
    val pages = (1..totalPages.coerceAtLeast(0)).map { displayPage ->
        val resolved = resolvePage(displayPage)
        ContinuousPdfPageIdentity(displayPage, resolved.pdfFilename, resolved.sourcePage)
    }
    val sources = pages.asSequence()
        .map { it.pdfFilename }
        .distinct()
        .sorted()
        .map { filename ->
            val file = runCatching { pdfFileForFilename(filename) }.getOrNull()
            val exists = runCatching { file?.isFile == true }.getOrDefault(false)
            ContinuousPdfSourceIdentity(
                pdfFilename = filename,
                absolutePath = file?.absolutePath,
                exists = exists,
                length = if (exists) runCatching { file?.length() ?: 0L }.getOrDefault(0L) else 0L,
                lastModified = if (exists) runCatching { file?.lastModified() ?: 0L }.getOrDefault(0L) else 0L
            )
        }
        .toList()
    return ContinuousPdfDocumentIdentity(pages, sources)
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Step 2 command. Expected: all `ContinuousReferencePdfPaneTest` tests pass.

- [ ] **Step 5: Commit the identity helper**

```powershell
git add -- app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousPdfDocumentIdentity.kt app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt
git commit -m "fix(viewer): scope PDF refresh identity"
```

### Task 2: Coordinate render, state, and pointer lifetimes

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt`
- Modify: `app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt`

**Interfaces:**
- Consumes: `resolveContinuousPdfDocumentIdentity(...)` from Task 1.
- Produces: a continuous pane whose render and interaction state use the same `ContinuousPdfDocumentIdentity` key.

- [ ] **Step 1: Add the state-key regression assertion**

Extend the geometry identity test so two separately recomputed but equal document identities produce equal geometry keys:

```kotlin
@Test
fun continuousPageGeometryIdentity_staysStableAcrossUnrelatedRefresh() {
    val file = File("plans.pdf")
    val resolved = ResolvedPageSource("plans.pdf", 3)
    val firstDocument = resolveContinuousPdfDocumentIdentity(1, { resolved }) { file }
    val secondDocument = resolveContinuousPdfDocumentIdentity(1, { resolved }) { file }
    val render = continuousPageRenderIdentity(5, resolved, file, preferDarkMode = false)

    assertEquals(
        continuousPageGeometryIdentity(render, firstDocument, docKey = "plans"),
        continuousPageGeometryIdentity(render, secondDocument, docKey = "plans")
    )
}
```

Change the production signature only after observing the old `fileIdentitySeed`-based test fail to compile against this desired API.

- [ ] **Step 2: Run the focused test and verify RED**

Run the Task 1 focused command. Expected: compilation fails because `continuousPageGeometryIdentity` still accepts `fileIdentitySeed: Long`.

- [ ] **Step 3: Derive the identity at the Compose boundary**

At the start of `ContinuousReferencePdfPane`, add:

```kotlin
val documentIdentity = remember(fileIdentitySeed, totalPages, docKey, preferDarkMode) {
    resolveContinuousPdfDocumentIdentity(totalPages, resolvePage, pdfFileForFilename)
}
```

Keep `fileIdentitySeed` only in that `remember` key. Replace every other render/interaction/cache `remember` and `LaunchedEffect` use of `fileIdentitySeed` with `documentIdentity`. Update `ContinuousPageGeometryIdentity` and `continuousPageGeometryIdentity` to carry `documentIdentity` instead of the global `Long` seed.

- [ ] **Step 4: Restart gesture infrastructure only for a real document change**

Make the single pointer owner and its delta handoff share the document key:

```kotlin
val scrollDeltaChannel = remember(listState, documentIdentity) {
    CoalescingMainAxisDeltaChannel()
}

Modifier.pointerInput(orientation, documentIdentity) {
    awaitEachGesture {
        // existing gesture body unchanged
    }
}
```

Key `programmaticScrollGuard`, zoom/pan/overscroll state, interaction/fling state, `lastReportedPage`, and `settled` to `documentIdentity`. Do not key `rememberLazyListState`; preserving the current display page is existing behavior.

- [ ] **Step 5: Remove high-frequency viewer diagnostics**

Delete the `android.util.Log` import, `CONTINUOUS_PDF_RENDER_TRACE_TAG`, and every `PdfFlingDebug` / `PdfRenderTrace` call. Remove debug-only counters that become unused, but preserve control flow and comments.

Verify source cleanup:

```powershell
rg -n "PdfFlingDebug|PdfRenderTrace|android\.util\.Log" app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt
```

Expected: no output.

- [ ] **Step 6: Dispose per-page retained render state**

Inside `pageContent`, add cleanup keyed to the page and document identity:

```kotlin
DisposableEffect(displayPage, documentIdentity) {
    onDispose {
        pageCoordinatesByDisplayPage.remove(displayPage)
        cropOverlays.remove(displayPage)
    }
}
```

Keep the existing removal when a page becomes non-visible. Do not call `Bitmap.recycle()` because
Compose images can still share the buffer during disposal.

- [ ] **Step 7: Run focused viewer tests and verify GREEN**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.kkc.sheettracker.ui.components.ContinuousReferencePdfPaneTest" --tests "com.kkc.sheettracker.ui.viewer.UnifiedReferenceViewerTest" --tests "com.kkc.sheettracker.ui.viewer.ReferencePdfViewerScreenTest"
```

Expected: PASS.

- [ ] **Step 8: Commit coordinated state ownership**

```powershell
git add -- app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt app/src/test/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPaneTest.kt
git commit -m "fix(viewer): keep PDF gestures refresh-safe"
```

### Task 3: Build and connected-tablet verification

**Files:**
- No source changes expected; if verification exposes a defect, return to Task 2 with a new failing test.

**Interfaces:**
- Consumes: the finished viewer fix.
- Produces: build evidence and live-device confirmation on `R52W209W6RA` / SM-X800.

- [ ] **Step 1: Run the full unit suite and debug build**

Run sequentially because both use Gradle build outputs:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Expected: both commands exit 0.

- [ ] **Step 2: Install without removing app data**

```powershell
adb -s R52W209W6RA install -r app\build\outputs\apk\debug\app-debug.apk
```

Expected: `Success`. Never run `adb uninstall`.

- [ ] **Step 3: Verify the live failure scenario**

Open the same Assembly Sheets document in continuous mode. Pinch above 2x and scroll while watcher generations advance. Confirm visually that zoom remains applied, pinch remains responsive, full-screen swipes travel full distance, and pages do not blank on unrelated generations.

- [ ] **Step 4: Verify log and CPU cleanup**

```powershell
adb -s R52W209W6RA logcat -c
```

Exercise continuous zoom/scroll for at least 30 seconds, then run:

```powershell
adb -s R52W209W6RA logcat -d -v threadtime -s PdfFlingDebug:D PdfRenderTrace:D '*:S'
adb -s R52W209W6RA shell top -b -n 1 -m 20
adb -s R52W209W6RA shell dumpsys meminfo com.kkc.sheettracker
```

Expected: no viewer trace lines, `logd` is not consuming abnormal CPU, and the app remains responsive without an ANR or crash.
