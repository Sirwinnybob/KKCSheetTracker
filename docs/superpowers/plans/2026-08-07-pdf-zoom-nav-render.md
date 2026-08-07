# PDF Zoom Limit + Fast-Nav Low-Res Render Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Raise PDF viewer max zoom from 10x/14x to 20x, and stop firing a full-resolution page render for every page landed on during fast navigation — show a cheap thumbnail immediately, defer the full render until navigation settles.

**Architecture:** Two independent, additive changes to `ReferencePdfPane.kt` (paged mode) and `ContinuousReferencePdfPane.kt` (continuous-scroll mode). Zoom limit is a pure constant bump (3 spots). Fast-nav render reuses the existing `renderThumbnail()` function as an instant placeholder and defers the existing `renderBasePage()` call behind a settle signal — no new render paths, no changes to the already-debounced zoom/pan detail-tile logic.

**Tech Stack:** Kotlin, Jetpack Compose, `android.graphics.pdf.PdfRenderer`, `android.util.LruCache`, Kotlin coroutines (`LaunchedEffect`, `delay`, `snapshotFlow`).

**Spec:** `docs/superpowers/specs/2026-08-07-pdf-zoom-nav-render-design.md`

---

### Task 1: Zoom limit bump

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt:82`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt:188`
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt:889`

- [ ] **Step 1: Bump continuous-mode max zoom**

In `ContinuousReferencePdfPane.kt`, line 82:

```kotlin
private const val CONTINUOUS_MAX_ZOOM = 10f
```
becomes
```kotlin
private const val CONTINUOUS_MAX_ZOOM = 20f
```

- [ ] **Step 2: Bump paged-mode render-side zoom clamp**

In `ReferencePdfPane.kt`, line 188 (inside `renderViewportTile`):

```kotlin
            val zoom = viewport.zoom.coerceIn(1f, 14f)
```
becomes
```kotlin
            val zoom = viewport.zoom.coerceIn(1f, 20f)
```

- [ ] **Step 3: Bump paged-mode gesture-side max zoom**

In `ReferencePdfPane.kt`, line 889:

```kotlin
    val maxZoom = 14f
```
becomes
```kotlin
    val maxZoom = 20f
```

- [ ] **Step 4: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt
git commit -m "feat(viewer): raise PDF pinch-zoom limit to 20x"
```

---

### Task 2: Paged mode — thumbnail placeholder + debounced base render

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt`

- [ ] **Step 1: Add the `delay` import**

Add this import alongside the other `kotlinx.coroutines` imports (near line 86, after `import kotlinx.coroutines.SupervisorJob`):

```kotlin
import kotlinx.coroutines.delay
```

- [ ] **Step 2: Add a thumbnail cache and thumbnail state**

In `ReferencePdfPane.kt`, right after the existing `basePageCache` declaration (line 427):

```kotlin
    val basePageCache = remember(pdfIdentityKey) { LruCache<Int, Bitmap>(6) }
```

add:

```kotlin
    // Cheap low-res placeholder cache, keyed the same way as basePageCache. Shown immediately
    // on page entry while the full-res base render is debounced (see below) — avoids firing a
    // full PdfRenderer decode for every page landed on during a fast tap-through.
    val thumbnailCache = remember(pdfIdentityKey) { LruCache<Int, Bitmap>(20) }
```

Then extend the existing `DisposableEffect(engine)` block (lines 428-440) to also evict the new cache — change:

```kotlin
    DisposableEffect(engine) {
        onDispose {
            basePageCache.evictAll()
```

to:

```kotlin
    DisposableEffect(engine) {
        onDispose {
            basePageCache.evictAll()
            thumbnailCache.evictAll()
```

Next, add the thumbnail bitmap state var right after the existing `baseBitmap` declaration (line 448):

```kotlin
    var baseBitmap by remember(engine, currentPage) { mutableStateOf<Bitmap?>(null) }
```

add immediately after:

```kotlin
    var thumbnailBitmap by remember(engine, currentPage) { mutableStateOf<Bitmap?>(null) }
```

- [ ] **Step 3: Add the immediate thumbnail-render effect**

Add this new `LaunchedEffect` right after the existing base-render effect (after line 528, the closing brace of the `LaunchedEffect(engine, currentPage, totalPages, viewportState.viewSize, matteColorArgb)` block):

```kotlin

    // Instant low-res placeholder: no debounce, no viewSize dependency (renderThumbnail uses a
    // fixed default width), so it can start decoding before layout even completes.
    LaunchedEffect(engine, currentPage, totalPages) {
        if (engine == null || totalPages <= 0) return@LaunchedEffect
        val cachedThumb = thumbnailCache.get(currentPage)
        if (cachedThumb != null && !cachedThumb.isRecycled) {
            thumbnailBitmap = cachedThumb
            return@LaunchedEffect
        }
        val thumb = withContext(Dispatchers.IO) {
            engine.renderThumbnail(pageIndex = (currentPage - 1).coerceAtLeast(0))
        }
        if (thumb != null) {
            thumbnailCache.put(currentPage, thumb)
            thumbnailBitmap = thumb
        }
    }
```

- [ ] **Step 4: Debounce the full base-render effect**

The existing base-render effect (lines 505-528) already restarts on every `currentPage` change (it's a key), which cancels any in-flight work for the previous page — the fix is adding a short `delay` before the expensive work, so a rapid sequence of page changes cancels-and-restarts the delay each time and only the page the user actually lands on survives long enough to render.

Change:

```kotlin
    LaunchedEffect(engine, currentPage, totalPages, viewportState.viewSize, matteColorArgb) {
        if (engine == null || totalPages <= 0) {
            renderState = PdfRenderUiState.Error(unreadableText)
            return@LaunchedEffect
        }
        val viewSize = viewportState.viewSize
        if (viewSize == IntSize.Zero) return@LaunchedEffect
        val cached = basePageCache.get(currentPage)
        if (cached != null && !cached.isRecycled) {
            baseBitmap = cached
            return@LaunchedEffect
        }
        val renderedBase = withContext(Dispatchers.IO) {
            engine.renderBasePage(
                pageIndex = (currentPage - 1).coerceAtLeast(0),
                viewSize = viewSize,
                matteColorArgb = matteColorArgb
            )
        }
        if (renderedBase != null) {
            basePageCache.put(currentPage, renderedBase)
            baseBitmap = renderedBase
        }
    }
```

to:

```kotlin
    LaunchedEffect(engine, currentPage, totalPages, viewportState.viewSize, matteColorArgb) {
        if (engine == null || totalPages <= 0) {
            renderState = PdfRenderUiState.Error(unreadableText)
            return@LaunchedEffect
        }
        val viewSize = viewportState.viewSize
        if (viewSize == IntSize.Zero) return@LaunchedEffect
        val cached = basePageCache.get(currentPage)
        if (cached != null && !cached.isRecycled) {
            baseBitmap = cached
            return@LaunchedEffect
        }
        // Debounce: a rapid run of page changes cancels-and-restarts this effect (currentPage
        // is a key), so only the page the user actually settles on survives long enough to
        // reach the real decode below. The thumbnail effect above has no such delay, so the
        // page is never blank during the wait.
        delay(120)
        val renderedBase = withContext(Dispatchers.IO) {
            engine.renderBasePage(
                pageIndex = (currentPage - 1).coerceAtLeast(0),
                viewSize = viewSize,
                matteColorArgb = matteColorArgb
            )
        }
        if (renderedBase != null) {
            basePageCache.put(currentPage, renderedBase)
            baseBitmap = renderedBase
        }
    }
```

- [ ] **Step 5: Wire the thumbnail into the draw fallback chain**

In the `ZoomablePdfImage` call (around line 674), change:

```kotlin
                            baseBitmap = baseBitmap ?: if (!isSliding) fallbackBitmap else null,
```

to:

```kotlin
                            baseBitmap = baseBitmap ?: (if (!isSliding) (thumbnailBitmap ?: fallbackBitmap) else null),
```

This keeps the existing behavior (full base bitmap wins once loaded; nothing shown mid-slide-animation) and adds the thumbnail as the preferred stand-in over the previous page's leftover `fallbackBitmap` — the thumbnail is the actual current page, just lower-res.

- [ ] **Step 6: Include the thumbnail in the nav-arrow visibility check**

Around line 738, change:

```kotlin
                            val arrowAlpha = if ((baseBitmap ?: fallbackBitmap) != null) 0.55f else 0f
```

to:

```kotlin
                            val arrowAlpha = if ((baseBitmap ?: thumbnailBitmap ?: fallbackBitmap) != null) 0.55f else 0f
```

- [ ] **Step 7: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ReferencePdfPane.kt
git commit -m "feat(viewer): show low-res thumbnail during fast page nav, debounce full render"
```

---

### Task 3: Continuous mode — thumbnail placeholder + settle-gated base render

**Files:**
- Modify: `app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt`

- [ ] **Step 1: Add the `LruCache` import**

In the import block, add (alphabetically between `android.graphics.Bitmap` at line 3 and `android.util.Log` at line 4 — actual position doesn't matter functionally):

```kotlin
import android.util.LruCache
```

- [ ] **Step 2: Add a thumbnail cache at the pane's outer scope**

Right after the existing `engineCache` declaration and its disposal effect (after line 334, i.e. after the closing brace of the `DisposableEffect(engineCache) { ... }` block):

```kotlin
    val engineCache = remember(fileIdentitySeed) { PdfEngineCache(maxOpen = 3) }
    DisposableEffect(engineCache) {
        onDispose { continuousPdfEngineDisposalScope.launch { withContext(NonCancellable) { engineCache.closeAll() } } }
    }
```

add immediately after:

```kotlin
    // Cheap low-res placeholder cache, keyed by "filename#page" (a plain Int page number isn't
    // enough — this pane can stream pages from multiple source PDFs). Shown immediately on page
    // entry while the full-res base render waits for scroll/fling to settle (see settled below).
    val thumbnailCache = remember(fileIdentitySeed) { LruCache<String, Bitmap>(30) }
    DisposableEffect(thumbnailCache) {
        onDispose { thumbnailCache.evictAll() }
    }
```

- [ ] **Step 3: Add per-item thumbnail state**

Find the per-item state declarations (around line 508-511):

```kotlin
        var baseBitmap by remember(displayPage, resolved, fileIdentitySeed) { mutableStateOf<Bitmap?>(null) }
        var cropBitmap by remember(displayPage, resolved, fileIdentitySeed) { mutableStateOf<Bitmap?>(null) }
```

add a new line right after `baseBitmap`'s declaration:

```kotlin
        var baseBitmap by remember(displayPage, resolved, fileIdentitySeed) { mutableStateOf<Bitmap?>(null) }
        var thumbnailBitmap by remember(displayPage, resolved, fileIdentitySeed) { mutableStateOf<Bitmap?>(null) }
        var cropBitmap by remember(displayPage, resolved, fileIdentitySeed) { mutableStateOf<Bitmap?>(null) }
```

- [ ] **Step 4: Add the immediate thumbnail-render effect**

Add this new `LaunchedEffect` right after the existing base-render effect (after line 540, the closing brace of the `LaunchedEffect(displayPage, resolved, file, inWindow, matteColorArgb, fileIdentitySeed)` block that calls `renderBasePage`):

```kotlin

        // Instant low-res placeholder: unconditional on settled (unlike the base render below),
        // so a page that's merely scrolled past during a fling still shows something recognizable.
        LaunchedEffect(displayPage, resolved, file, inWindow, fileIdentitySeed) {
            if (file == null || !inWindow) return@LaunchedEffect
            val cacheKey = "${resolved.pdfFilename}#${resolved.sourcePage}"
            val cachedThumb = thumbnailCache.get(cacheKey)
            if (cachedThumb != null && !cachedThumb.isRecycled) {
                thumbnailBitmap = cachedThumb
                return@LaunchedEffect
            }
            val thumb = withContext(Dispatchers.IO) {
                engineCache.get(file).renderThumbnail(pageIndex = (resolved.sourcePage - 1).coerceAtLeast(0))
            }
            if (thumb != null) {
                thumbnailCache.put(cacheKey, thumb)
                thumbnailBitmap = thumb
            }
        }
```

- [ ] **Step 5: Gate the full base-render effect behind `settled`**

The existing effect (lines 530-540) is:

```kotlin
        // Base (fit-to-box) bitmap: loaded once per page while in window, cheap + cached.
        // Doubles as the fallback shown under the sharp crop tile and as the whole picture
        // when not zoomed.
        LaunchedEffect(displayPage, resolved, file, inWindow, matteColorArgb, fileIdentitySeed) {
            if (file == null || !inWindow || baseBitmap != null) return@LaunchedEffect
            val viewSize = boxSize.takeIf { it != IntSize.Zero } ?: IntSize(1080, 1400)
            baseBitmap = withContext(Dispatchers.IO) {
                engineCache.get(file).renderBasePage(
                    pageIndex = (resolved.sourcePage - 1).coerceAtLeast(0),
                    viewSize = viewSize,
                    matteColorArgb = matteColorArgb
                )
            }
        }
```

Change to add `settled` as both a key and a guard condition:

```kotlin
        // Base (fit-to-box) bitmap: loaded once per page while in window, cheap + cached.
        // Doubles as the fallback shown under the sharp crop tile and as the whole picture
        // when not zoomed. Gated on settled (same signal the crop tile below already uses) so
        // flinging past many pages doesn't fire a full decode for each one — only the page(s)
        // still in the window once scrolling/pinching actually stops get the full render.
        LaunchedEffect(displayPage, resolved, file, inWindow, settled, matteColorArgb, fileIdentitySeed) {
            if (file == null || !inWindow || !settled || baseBitmap != null) return@LaunchedEffect
            val viewSize = boxSize.takeIf { it != IntSize.Zero } ?: IntSize(1080, 1400)
            baseBitmap = withContext(Dispatchers.IO) {
                engineCache.get(file).renderBasePage(
                    pageIndex = (resolved.sourcePage - 1).coerceAtLeast(0),
                    viewSize = viewSize,
                    matteColorArgb = matteColorArgb
                )
            }
        }
```

- [ ] **Step 6: Wire the thumbnail into `PageBitmapLayers`**

Change the function signature (lines 272-278):

```kotlin
@Composable
private fun PageBitmapLayers(
    baseBitmap: Bitmap?,
    cropBitmap: Bitmap?,
    cropFrac: UnitRect?,
    boxSize: IntSize,
    contentDescription: String
) {
```

to:

```kotlin
@Composable
private fun PageBitmapLayers(
    baseBitmap: Bitmap?,
    thumbnailBitmap: Bitmap?,
    cropBitmap: Bitmap?,
    cropFrac: UnitRect?,
    boxSize: IntSize,
    contentDescription: String
) {
```

Change the base-bitmap-or-placeholder block (lines 280-289):

```kotlin
        if (baseBitmap != null) {
            Image(
                bitmap = baseBitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
```

to:

```kotlin
        if (baseBitmap != null) {
            Image(
                bitmap = baseBitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else if (thumbnailBitmap != null) {
            Image(
                bitmap = thumbnailBitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
```

- [ ] **Step 7: Update the `PageBitmapLayers` call site**

Around line 625-631:

```kotlin
                PageBitmapLayers(
                    baseBitmap = baseBitmap,
                    cropBitmap = cropBitmap,
                    cropFrac = cropFrac,
                    boxSize = boxSize,
                    contentDescription = "Page $displayPage"
                )
```

to:

```kotlin
                PageBitmapLayers(
                    baseBitmap = baseBitmap,
                    thumbnailBitmap = thumbnailBitmap,
                    cropBitmap = cropBitmap,
                    cropFrac = cropFrac,
                    boxSize = boxSize,
                    contentDescription = "Page $displayPage"
                )
```

- [ ] **Step 8: Build**

Run: `.\gradlew.bat assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/kkc/sheettracker/ui/components/ContinuousReferencePdfPane.kt
git commit -m "feat(viewer): show low-res thumbnail during fast fling, settle-gate full render"
```

---

### Task 4: Manual verification on device

**Files:** none (verification only)

- [ ] **Step 1: Run existing unit tests (regression check)**

Run: `.\gradlew.bat testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, `PdfLabelScrollbarTest` still passing (untouched by this plan, but confirms nothing else broke).

- [ ] **Step 2: Build and install debug APK**

```bash
cd C:\Scripts\KKCSheetTracker
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

- [ ] **Step 3: Verify 20x zoom, paged mode**

Open a reference PDF in paged (non-continuous) mode. Pinch-zoom in as far as it goes. Expected: zoom reaches roughly 20x (not 14x), image stays sharp (no blur/pixelation) at max zoom.

- [ ] **Step 4: Verify 20x zoom, continuous mode**

Switch to continuous-scroll mode (toggle in top bar per `7abbd850`/`1d45a55` continuous-scroll work). Pinch-zoom in as far as it goes. Expected: zoom reaches roughly 20x (not 10x), image stays sharp.

- [ ] **Step 5: Verify fast-nav placeholder, paged mode**

In paged mode, rapidly tap the next-page arrow through 10+ pages as fast as possible. Expected: each page shows a recognizable (if slightly soft) thumbnail immediately on arrival, no long blank/frozen frame; once tapping stops, the current page sharpens to full resolution within ~150ms.

- [ ] **Step 6: Verify fast-nav placeholder, continuous mode**

In continuous-scroll mode, fling hard through the document across many pages. Expected: pages show thumbnails while flying past (not blank white boxes), full-res render only kicks in for the page(s) still on screen once the fling settles.

- [ ] **Step 7: Verify thumbnail cache on revisit**

In either mode, navigate away from a page that has already been fully rendered, then rapidly navigate back to it during a fast scroll/tap sequence. Expected: the page's thumbnail appears instantly (no re-decode delay) since it was cached on first visit.

- [ ] **Step 8: Deploy to tablet**

Per `CLAUDE.md`, tablets run release builds:

```bash
.\adb-install-release.ps1
```

(If this fails under `powershell -File` due to a known encoding issue — see memory `adb_release_script_encoding` — run `.\gradlew.bat assembleRelease` then `adb install -r app\build\outputs\apk\release\app-release.apk` directly instead.)
