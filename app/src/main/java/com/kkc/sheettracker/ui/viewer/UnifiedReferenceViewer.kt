package com.kkc.sheettracker.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.PdfMarkupStore
import com.kkc.sheettracker.data.models.PdfInkStroke
import com.kkc.sheettracker.ui.components.ImmersiveDialogDecor
import com.kkc.sheettracker.ui.components.LocalNavBarDecoration
import com.kkc.sheettracker.ui.components.NavBarPenDecoration
import com.kkc.sheettracker.ui.components.PdfViewportState
import com.kkc.sheettracker.ui.components.ReferencePdfPane
import com.kkc.sheettracker.ui.markup.PdfMarkupToolState
import com.kkc.sheettracker.ui.markup.PdfMarkupToolbar
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

data class UnifiedVirtualPageSource(
    val pdfFilename: String,
    val page: Int,
    val cabinet: String? = null,
    val sourceVariant: String? = null
)

data class UnifiedVirtualPageMapping(
    val totalDisplayPages: Int,
    val defaultPdfFilename: String,
    val sourceByDisplayPage: Map<Int, UnifiedVirtualPageSource>
)

data class UnifiedVirtualSanitizationResult(
    val mapping: UnifiedVirtualPageMapping?,
    val cabinetToPages: Map<String, List<Int>> = emptyMap(),
    val warningMessage: String? = null
)

data class UnifiedCabinetSearchResult(
    val cabinet: String,
    val pages: List<Int>
)

private data class NavigatorRowModel(
    val page: Int,
    val cabinets: List<String>,
    val roomKey: String?,
    val source: UnifiedVirtualPageSource?,
    val isPlanView: Boolean,
    val matchedCabinets: List<String>,
    val primaryLabel: String,
    val secondaryLabel: String
)

internal data class NavigatorSearchRow(
    val page: Int,
    val cabinets: List<String>,
    val roomKey: String?,
    val isPlanView: Boolean
)

internal data class NavigatorSearchFilteredRow(
    val page: Int,
    val matchedCabinets: List<String>
)

private data class ThumbnailRequest(
    val file: File,
    val pageIndex: Int
)

internal fun resolveDisplayPageFromSource(
    reverseIndex: Map<Pair<String, Int>, Int>,
    sourceFilename: String,
    sourcePage: Int
): Int? {
    if (sourceFilename.isBlank() || sourcePage <= 0) return null
    return reverseIndex[sourceFilename.lowercase(Locale.US) to sourcePage]
}

internal fun buildVirtualReverseIndex(mapping: UnifiedVirtualPageMapping): Map<Pair<String, Int>, Int> {
    return mapping.sourceByDisplayPage.entries
        .mapNotNull { (displayPage, source) ->
            val filename = source.pdfFilename.trim()
            if (displayPage <= 0 || filename.isBlank() || source.page <= 0) return@mapNotNull null
            (filename.lowercase(Locale.US) to source.page) to displayPage
        }
        .toMap()
}

internal fun buildPageToCabinets(cabinetToPages: Map<String, List<Int>>): Map<Int, List<String>> {
    if (cabinetToPages.isEmpty()) return emptyMap()
    val out = linkedMapOf<Int, MutableList<String>>()
    cabinetToPages.entries.sortedWith(compareBy({ cabinetSortKey(it.key).first }, { cabinetSortKey(it.key).second }))
        .forEach { (cabinet, pages) ->
        val cleanCab = cabinet.trim()
        if (cleanCab.isBlank()) return@forEach
        pages.filter { it > 0 }.distinct().sorted().forEach { page ->
            val list = out.getOrPut(page) { mutableListOf() }
            if (cleanCab !in list) list += cleanCab
        }
    }
    return out.mapValues { (_, cabinets) -> cabinets.toList() }
}

private fun cabinetSortKey(value: String): Pair<Int, String> {
    val clean = value.trim()
    val numeric = clean.toIntOrNull()
    return if (numeric != null) numeric to ""
    else Int.MAX_VALUE to clean.lowercase(Locale.US)
}

private fun compressCabinetList(cabinets: List<String>): String {
    val ordered = cabinets.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (ordered.isEmpty()) return ""

    data class Group(val original: String, val numeric: Int?)
    val groups = ordered.map { Group(it, it.toIntOrNull()) }
        .sortedWith(compareBy<Group>({ it.numeric ?: Int.MAX_VALUE }, { it.original.lowercase(Locale.US) }))

    val numeric = groups.filter { it.numeric != null }.mapNotNull { it.numeric }.distinct()
    val nonNumeric = groups.filter { it.numeric == null }.map { it.original }

    val numericParts = mutableListOf<String>()
    var i = 0
    while (i < numeric.size) {
        var j = i
        while (j + 1 < numeric.size && numeric[j + 1] == numeric[j] + 1) j++
        val runLength = j - i + 1
        if (runLength >= 3) {
            numericParts += "${numeric[i]}-${numeric[j]}"
        } else {
            for (k in i..j) numericParts += numeric[k].toString()
        }
        i = j + 1
    }

    return (numericParts + nonNumeric).joinToString(", ")
}

internal fun prefixCabinetMatches(
    cabinetToPages: Map<String, List<Int>>,
    query: String
): List<UnifiedCabinetSearchResult> {
    val normalizedQuery = query.trim().lowercase(Locale.US)
    if (normalizedQuery.isBlank()) return emptyList()
    return cabinetToPages.entries
        .asSequence()
        .map { it.key.trim() to it.value.filter { page -> page > 0 }.distinct().sorted() }
        .filter { (cabinet, pages) ->
            cabinet.isNotBlank() &&
                pages.isNotEmpty() &&
                cabinet.lowercase(Locale.US).startsWith(normalizedQuery)
        }
        .sortedBy { it.first.lowercase(Locale.US) }
        .map { (cabinet, pages) -> UnifiedCabinetSearchResult(cabinet = cabinet, pages = pages) }
        .toList()
}

fun sanitizeVirtualAssemblyData(
    totalVirtualPages: Int,
    defaultPdfFilename: String,
    sourceByDisplayPage: Map<Int, UnifiedVirtualPageSource>,
    cabinetToPages: Map<String, List<Int>>
): UnifiedVirtualSanitizationResult {
    if (totalVirtualPages <= 0 || sourceByDisplayPage.isEmpty()) {
        return UnifiedVirtualSanitizationResult(mapping = null)
    }

    val sortedEntries = sourceByDisplayPage.entries
        .asSequence()
        .filter { (page, source) -> page > 0 && source.pdfFilename.isNotBlank() && source.page > 0 }
        .sortedBy { it.key }
        .toList()
    if (sortedEntries.isEmpty()) return UnifiedVirtualSanitizationResult(mapping = null)

    fun normalizedVariant(source: UnifiedVirtualPageSource): String {
        return source.sourceVariant
            ?.trim()
            ?.uppercase(Locale.US)
            .orEmpty()
    }

    val variants = sortedEntries.map { normalizedVariant(it.value) }.filter { it.isNotBlank() }.toSet()
    val hasFaceFrame = variants.contains("FACE_FRAME")
    val hasFrameless = variants.contains("FRAMELESS")
    val hasBase = variants.contains("BASE")

    val warningMessage = if (hasFaceFrame && hasFrameless && hasBase) {
        "Assembly index includes BASE + FF/FL. BASE was ignored for stable BOTH-mode navigation."
    } else {
        null
    }

    val filteredEntries = if (hasFaceFrame && hasFrameless) {
        sortedEntries.filter { (_, source) ->
            val variant = normalizedVariant(source)
            variant.isBlank() || variant == "FACE_FRAME" || variant == "FRAMELESS"
        }
    } else {
        sortedEntries
    }

    val oldToNewDisplayPage = mutableMapOf<Int, Int>()
    val sanitizedSources = linkedMapOf<Int, UnifiedVirtualPageSource>()
    filteredEntries.forEachIndexed { index, (oldDisplayPage, source) ->
        val newDisplayPage = index + 1
        oldToNewDisplayPage[oldDisplayPage] = newDisplayPage
        sanitizedSources[newDisplayPage] = source
    }

    val fallbackPageToCabinets = buildPageToCabinets(cabinetToPages)
    val sourceWithCabinets = sanitizedSources.mapValues { (displayPage, source) ->
        if (!source.cabinet.isNullOrBlank()) source
        else {
            source.copy(cabinet = fallbackPageToCabinets[displayPage]?.firstOrNull())
        }
    }

    val sanitizedCabinetToPages = cabinetToPages
        .mapValues { (_, pages) ->
            pages.mapNotNull { oldToNewDisplayPage[it] }.distinct().sorted()
        }
        .filterValues { it.isNotEmpty() }

    val mapping = UnifiedVirtualPageMapping(
        totalDisplayPages = sourceWithCabinets.size,
        defaultPdfFilename = defaultPdfFilename,
        sourceByDisplayPage = sourceWithCabinets
    )

    return UnifiedVirtualSanitizationResult(
        mapping = mapping,
        cabinetToPages = sanitizedCabinetToPages,
        warningMessage = warningMessage
    )
}

internal fun defaultNavigatorPrimaryLabel(
    page: Int,
    cabinets: List<String>,
    source: UnifiedVirtualPageSource?
): String {
    if (cabinets.isNotEmpty()) {
        val compressed = compressCabinetList(cabinets)
        return if (cabinets.size == 1) "Cabinet $compressed" else "Cabinets $compressed"
    }
    return if (source != null) "Sheet $page" else "Page $page"
}

internal fun defaultNavigatorSecondaryLabel(
    page: Int,
    cabinets: List<String>,
    source: UnifiedVirtualPageSource?
): String {
    if (source != null) {
        val variantLabel = when (source.sourceVariant?.trim()?.uppercase(Locale.US)) {
            "FACE_FRAME" -> "FF"
            "FRAMELESS" -> "FL"
            "BASE" -> "BASE"
            else -> source.pdfFilename.substringBeforeLast(".pdf")
        }
        return "Sheet $page • $variantLabel p${source.page}"
    }
    if (cabinets.isNotEmpty()) return "Sheet $page"
    return "Page $page"
}

fun extractRoomDisplayName(roomRaw: String?): String? {
    val raw = roomRaw?.trim().orEmpty()
    if (raw.isBlank()) return null
    val fromParens = Regex("""\(([^)]+)\)""").find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    val candidate = if (fromParens.isNotBlank()) fromParens else raw
    return candidate.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
}

fun buildPlanViewLabelsFromPageToRoom(pageToRoom: Map<Int, String>): Map<Int, String> {
    if (pageToRoom.isEmpty()) return emptyMap()
    val firstPageByRoom = mutableMapOf<String, Int>()
    pageToRoom.entries.sortedBy { it.key }.forEach { (page, room) ->
        if (page <= 0 || room.isBlank()) return@forEach
        firstPageByRoom[room] = minOf(firstPageByRoom[room] ?: Int.MAX_VALUE, page)
    }
    val out = linkedMapOf<Int, String>()
    firstPageByRoom.entries.sortedBy { it.value }.forEach { (room, firstPage) ->
        val planPage = (firstPage - 1).coerceAtLeast(1)
        if (planPage !in out) {
            out[planPage] = "$room - PLAN VIEW"
        }
    }
    return out
}

internal fun findPrefixCabinetMatches(cabinets: List<String>, query: String): List<String> {
    val normalized = query.trim().lowercase(Locale.US)
    if (normalized.isBlank()) return emptyList()
    return cabinets
        .map { it.trim() }
        .filter { it.isNotBlank() && it.lowercase(Locale.US).startsWith(normalized) }
        .distinct()
}

internal fun buildPageToRoomKey(
    totalPages: Int,
    navigatorPlanViewLabels: Map<Int, String>
): Map<Int, String> {
    if (totalPages <= 0 || navigatorPlanViewLabels.isEmpty()) return emptyMap()

    val planPages = navigatorPlanViewLabels.entries
        .mapNotNull { (page, label) ->
            val room = label
                .trim()
                .removeSuffix(" - PLAN VIEW")
                .trim()
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            page.takeIf { it > 0 }?.let { it to room }
        }
        .sortedBy { it.first }

    if (planPages.isEmpty()) return emptyMap()

    val out = mutableMapOf<Int, String>()
    planPages.forEachIndexed { index, (planPage, room) ->
        val endPage = if (index + 1 < planPages.size) {
            planPages[index + 1].first - 1
        } else {
            totalPages
        }
        val start = planPage.coerceAtLeast(1)
        val end = endPage.coerceAtMost(totalPages)
        if (start > end) return@forEachIndexed
        for (page in start..end) {
            out[page] = room
        }
    }
    return out
}

internal fun filterNavigatorRowsForSearch(
    rows: List<NavigatorSearchRow>,
    query: String
): List<NavigatorSearchFilteredRow> {
    val normalized = query.trim()
    if (normalized.isBlank()) {
        return rows.map { NavigatorSearchFilteredRow(page = it.page, matchedCabinets = emptyList()) }
    }

    val matchedRooms = mutableSetOf<String>()
    val rowMatchesByPage = mutableMapOf<Int, List<String>>()

    rows.forEach { row ->
        if (row.isPlanView) return@forEach
        val matches = findPrefixCabinetMatches(row.cabinets, normalized)
        if (matches.isNotEmpty()) {
            rowMatchesByPage[row.page] = matches
            row.roomKey?.trim()?.takeIf { it.isNotBlank() }?.let { matchedRooms += it }
        }
    }

    return rows.mapNotNull { row ->
        val directMatches = rowMatchesByPage[row.page]
        if (directMatches != null) {
            NavigatorSearchFilteredRow(page = row.page, matchedCabinets = directMatches)
        } else if (row.isPlanView && row.roomKey != null && row.roomKey in matchedRooms) {
            NavigatorSearchFilteredRow(page = row.page, matchedCabinets = emptyList())
        } else {
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedReferenceViewer(
    modifier: Modifier = Modifier,
    displayPage: Int,
    onDisplayPageChange: (Int) -> Unit,
    defaultPdfFilename: String,
    pdfFileForFilename: (String) -> File?,
    fileIdentitySeed: Long = 0L,
    preferDarkMode: Boolean = false,
    virtualMapping: UnifiedVirtualPageMapping? = null,
    navigatorCabinetToPages: Map<String, List<Int>> = emptyMap(),
    navigatorPlanViewLabels: Map<Int, String> = emptyMap(),
    navigatorWarningMessage: String? = null,
    navigatorTitle: String = "Sheet Navigator",
    navigatorPrimaryLabel: (page: Int, cabinets: List<String>, source: UnifiedVirtualPageSource?) -> String =
        ::defaultNavigatorPrimaryLabel,
    navigatorSecondaryLabel: (page: Int, cabinets: List<String>, source: UnifiedVirtualPageSource?) -> String =
        ::defaultNavigatorSecondaryLabel,
    showDocControls: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    missingText: String = "Reference PDF not found",
    unreadableText: String = "Unable to read PDF",
    onTotalPagesChanged: (Int) -> Unit = {},
    onViewportStateChange: (PdfViewportState) -> Unit = {},
    showHeaderRow: Boolean = true,
    showNavigationButtons: Boolean = true,
    innerPadding: androidx.compose.ui.unit.Dp = 8.dp,
    tocRequestToken: Int = 0,
    onSingleTap: (() -> Unit)? = null,
    compactArrows: Boolean = false,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    pdfMarkupStore: PdfMarkupStore? = null,
    pdfMarkupJobFolderName: String = "",
    markupEnabled: Boolean = false,
    onToggleMarkupEnabled: (() -> Unit)? = null,
    markupToolState: PdfMarkupToolState? = null,
    ownsNavBarMarkupControls: Boolean = true,
    // When true, pen controls slide in as a decoration tab (against the search tab) in the
    // minimized nav bar instead of swapping the whole bar to the full extendedControls layout.
    markupControlsAsSlidingTab: Boolean = false
) {
    var sourceTotalPages by remember(defaultPdfFilename, virtualMapping) { mutableIntStateOf(0) }
    val effectiveTotalPages = if (virtualMapping != null) {
        virtualMapping.totalDisplayPages
    } else {
        sourceTotalPages
    }
    val clampedDisplayPage = displayPage.coerceIn(1, effectiveTotalPages.coerceAtLeast(1))

    val reverseIndex = remember(virtualMapping) {
        virtualMapping?.let(::buildVirtualReverseIndex).orEmpty()
    }

    val activeVirtualSource = virtualMapping?.sourceByDisplayPage?.get(clampedDisplayPage)
    val resolvedPdfFilename = activeVirtualSource?.pdfFilename?.takeIf { it.isNotBlank() }
        ?: defaultPdfFilename
    val sourcePage = activeVirtualSource?.page?.takeIf { it > 0 } ?: clampedDisplayPage
    val pdfFile = remember(resolvedPdfFilename, fileIdentitySeed, preferDarkMode) {
        resolvedPdfFilename.takeIf { it.isNotBlank() }?.let(pdfFileForFilename)
    }
    val localMarkupStrokes = remember(pdfMarkupStore, pdfMarkupJobFolderName) { mutableStateListOf<PdfInkStroke>() }
    val localDeletedIds = remember(pdfMarkupStore, pdfMarkupJobFolderName) { mutableStateListOf<String>() }
    var markupStrokesVisible by remember(pdfMarkupStore, pdfMarkupJobFolderName) { mutableStateOf(true) }
    var markupContentVersion by remember(pdfMarkupStore, pdfMarkupJobFolderName) { mutableStateOf(0L) }

    LaunchedEffect(pdfMarkupStore, pdfMarkupJobFolderName) {
        if (pdfMarkupStore == null || pdfMarkupJobFolderName.isBlank()) {
            markupContentVersion = 0L
            return@LaunchedEffect
        }
        while (isActive) {
            markupContentVersion = pdfMarkupStore.trackerContentVersion(pdfMarkupJobFolderName)
            delay(1000)
        }
    }

    LaunchedEffect(pdfMarkupStore, pdfMarkupJobFolderName, resolvedPdfFilename, sourcePage, markupContentVersion) {
        if (pdfMarkupStore == null || pdfMarkupJobFolderName.isBlank() || resolvedPdfFilename.isBlank() || sourcePage <= 0) {
            localMarkupStrokes.clear()
            localDeletedIds.clear()
            return@LaunchedEffect
        }
        localMarkupStrokes.clear()
        localMarkupStrokes.addAll(
            pdfMarkupStore.getMergedActiveStrokes(
                jobFolderName = pdfMarkupJobFolderName,
                pdfFilename = resolvedPdfFilename,
                page = sourcePage
            )
        )
        localDeletedIds.clear()
        localDeletedIds.addAll(
            pdfMarkupStore.loadTabletPageMarkup(pdfMarkupJobFolderName, resolvedPdfFilename, sourcePage)
                ?.deletedStrokeIds
                .orEmpty()
        )
        Log.d(
            "PdfMarkupDebug",
            "UnifiedReferenceViewer reload job=$pdfMarkupJobFolderName pdf=$resolvedPdfFilename page=$sourcePage strokes=${localMarkupStrokes.size} deleted=${localDeletedIds.size}"
        )
    }

    var showSheetNavigator by remember(virtualMapping, defaultPdfFilename) { mutableStateOf(false) }
    var searchQuery by remember(virtualMapping, defaultPdfFilename) { mutableStateOf("") }
    val tocThumbCache = remember(virtualMapping, defaultPdfFilename) { mutableStateMapOf<Int, Bitmap?>() }
    val tocLruOrder = remember(virtualMapping, defaultPdfFilename) { mutableListOf<Int>() }
    val tocLoadedCount by remember { derivedStateOf { tocThumbCache.count { it.value != null } } }
    val pageToCabinets = remember(navigatorCabinetToPages) { buildPageToCabinets(navigatorCabinetToPages) }
    val pageToRoomKey = remember(effectiveTotalPages, navigatorPlanViewLabels) {
        buildPageToRoomKey(
            totalPages = effectiveTotalPages,
            navigatorPlanViewLabels = navigatorPlanViewLabels
        )
    }

    LaunchedEffect(navigatorWarningMessage) {
        if (!navigatorWarningMessage.isNullOrBlank()) {
            Log.w("UnifiedReferenceViewer", navigatorWarningMessage)
        }
    }
    val visibleMarkupStrokes = remember(localMarkupStrokes.size, localDeletedIds.size) {
        localMarkupStrokes.filter { it.id !in localDeletedIds }
    }
    val hasMarkupHistory = remember(localMarkupStrokes.size, localDeletedIds.size) {
        visibleMarkupStrokes.isNotEmpty()
    }
    fun persistMarkupState() {
        pdfMarkupStore?.takeIf { pdfMarkupJobFolderName.isNotBlank() && resolvedPdfFilename.isNotBlank() && sourcePage > 0 }?.savePageMarkup(
            jobFolderName = pdfMarkupJobFolderName,
            pdfFilename = resolvedPdfFilename,
            page = sourcePage,
            strokes = localMarkupStrokes.filter { it.id !in localDeletedIds },
            deletedStrokeIds = localDeletedIds.toList()
        )
    }
    val navBarDeco = LocalNavBarDecoration.current
    SideEffect {
        if (ownsNavBarMarkupControls) {
            val toolbar: (@Composable RowScope.() -> Unit)? =
                if (markupEnabled && markupToolState != null) {
                    {
                        PdfMarkupToolbar(
                            state = markupToolState,
                            hasUndo = hasMarkupHistory,
                            onUndo = {
                                val latestVisible = pdfMarkupStore
                                    ?.loadTabletPageMarkup(pdfMarkupJobFolderName, resolvedPdfFilename, sourcePage)
                                    ?.strokes
                                    ?.lastOrNull { it.id !in localDeletedIds }
                                if (latestVisible != null) {
                                    localDeletedIds.add(latestVisible.id)
                                    persistMarkupState()
                                }
                            },
                            strokesVisible = markupStrokesVisible,
                            onToggleVisibility = { markupStrokesVisible = !markupStrokesVisible },
                            onHide = onToggleMarkupEnabled
                        )
                    }
                } else {
                    null
                }
            if (markupControlsAsSlidingTab) {
                // Pen slides in as a tab against the search decoration — no full-bar swap.
                navBarDeco.penDecoration = toolbar?.let { NavBarPenDecoration(content = it) }
            } else {
                navBarDeco.extendedControls = toolbar
            }
        }
    }
    DisposableEffect(navBarDeco) {
        onDispose {
            if (ownsNavBarMarkupControls) {
                navBarDeco.extendedControls = null
                navBarDeco.penDecoration = null
            }
        }
    }

    ReferencePdfPane(
        modifier = modifier,
        pdfFile = pdfFile,
        currentPage = sourcePage,
        onCurrentPageChange = { nextSourcePage ->
            if (virtualMapping != null) {
                val mapped = resolveDisplayPageFromSource(
                    reverseIndex = reverseIndex,
                    sourceFilename = resolvedPdfFilename,
                    sourcePage = nextSourcePage
                )
                if (mapped != null && mapped != clampedDisplayPage) {
                    onDisplayPageChange(mapped)
                }
            } else {
                onDisplayPageChange(nextSourcePage)
            }
        },
        showDocControls = showDocControls,
        missingText = missingText,
        unreadableText = unreadableText,
        onTotalPagesChanged = { totalPages ->
            sourceTotalPages = totalPages
            if (virtualMapping != null) {
                onTotalPagesChanged(virtualMapping.totalDisplayPages)
            } else {
                onTotalPagesChanged(totalPages)
            }
        },
        onViewportStateChange = onViewportStateChange,
        showHeaderRow = showHeaderRow,
        showNavigationButtons = showNavigationButtons,
        innerPadding = innerPadding,
        tocRequestToken = tocRequestToken,
        displayPageOverride = clampedDisplayPage,
        displayTotalPagesOverride = effectiveTotalPages,
        onStepPage = {
            onDisplayPageChange(
                (clampedDisplayPage + it).coerceIn(1, effectiveTotalPages.coerceAtLeast(1))
            )
        },
        onOpenSheetNavigator = { showSheetNavigator = true },
        onSingleTap = onSingleTap,
        compactArrows = compactArrows,
        preferDarkMode = preferDarkMode,
        contentPadding = contentPadding,
        markupEnabled = markupEnabled,
        onToggleMarkupEnabled = onToggleMarkupEnabled,
        markupToolState = markupToolState,
        markupStrokes = if (markupStrokesVisible) visibleMarkupStrokes else emptyList(),
        onMarkupStrokeAdded = { stroke ->
            localMarkupStrokes.add(stroke)
            Log.d(
                "PdfMarkupDebug",
                "UnifiedReferenceViewer addStroke job=$pdfMarkupJobFolderName pdf=$resolvedPdfFilename page=$sourcePage local=${localMarkupStrokes.size}"
            )
            pdfMarkupStore?.takeIf { pdfMarkupJobFolderName.isNotBlank() && resolvedPdfFilename.isNotBlank() && sourcePage > 0 }?.savePageMarkup(
                jobFolderName = pdfMarkupJobFolderName,
                pdfFilename = resolvedPdfFilename,
                page = sourcePage,
                strokes = localMarkupStrokes.filter { it.id !in localDeletedIds },
                deletedStrokeIds = localDeletedIds.toList()
            )
        },
        onMarkupStrokeErased = { strokeId ->
            if (strokeId !in localDeletedIds) {
                localDeletedIds.add(strokeId)
            }
            pdfMarkupStore?.takeIf { pdfMarkupJobFolderName.isNotBlank() && resolvedPdfFilename.isNotBlank() && sourcePage > 0 }?.savePageMarkup(
                jobFolderName = pdfMarkupJobFolderName,
                pdfFilename = resolvedPdfFilename,
                page = sourcePage,
                strokes = localMarkupStrokes.filter { it.id !in localDeletedIds },
                deletedStrokeIds = localDeletedIds.toList()
            )
        }
    )

    LaunchedEffect(tocRequestToken) {
        if (tocRequestToken > 0) showSheetNavigator = true
    }

    val pages = remember(effectiveTotalPages) {
        if (effectiveTotalPages <= 0) emptyList() else (1..effectiveTotalPages).toList()
    }
    val rowModels = remember(
        pages,
        pageToCabinets,
        pageToRoomKey,
        virtualMapping,
        navigatorPrimaryLabel,
        navigatorSecondaryLabel,
        navigatorPlanViewLabels
    ) {
        pages.map { page ->
            val source = virtualMapping?.sourceByDisplayPage?.get(page)
            val cabinets = buildList {
                val sourceCabinet = source?.cabinet?.trim().orEmpty()
                if (sourceCabinet.isNotBlank()) add(sourceCabinet)
                pageToCabinets[page].orEmpty().forEach { cabinet ->
                    if (cabinet !in this) add(cabinet)
                }
            }
            val planViewLabel = navigatorPlanViewLabels[page]?.trim().orEmpty()
            val isPlanView = planViewLabel.isNotBlank()
            val primaryLabel = if (isPlanView) {
                planViewLabel
            } else {
                navigatorPrimaryLabel(page, cabinets, source)
            }
            val secondaryLabel = if (isPlanView) {
                "Sheet $page • Plan View"
            } else {
                navigatorSecondaryLabel(page, cabinets, source)
            }
            NavigatorRowModel(
                page = page,
                cabinets = cabinets,
                roomKey = pageToRoomKey[page],
                source = source,
                isPlanView = isPlanView,
                matchedCabinets = emptyList(),
                primaryLabel = primaryLabel,
                secondaryLabel = secondaryLabel
            )
        }
    }
    val searchFilteredRows = remember(rowModels, searchQuery) {
        if (searchQuery.trim().isBlank()) {
            rowModels
        } else {
            val filteredByPage = filterNavigatorRowsForSearch(
                rows = rowModels.map { row ->
                    NavigatorSearchRow(
                        page = row.page,
                        cabinets = row.cabinets,
                        roomKey = row.roomKey,
                        isPlanView = row.isPlanView
                    )
                },
                query = searchQuery
            ).associateBy { it.page }

            rowModels.mapNotNull { row ->
                val match = filteredByPage[row.page] ?: return@mapNotNull null
                row.copy(matchedCabinets = match.matchedCabinets)
            }
        }
    }

    fun thumbnailRequestForPage(page: Int): ThumbnailRequest? {
        if (page <= 0) return null
        if (virtualMapping != null) {
            val source = virtualMapping.sourceByDisplayPage[page] ?: return null
            val sourceFile = source.pdfFilename.takeIf { it.isNotBlank() }?.let(pdfFileForFilename) ?: return null
            val sourceIndex = (source.page - 1).coerceAtLeast(0)
            return ThumbnailRequest(file = sourceFile, pageIndex = sourceIndex)
        }
        val sourceFile = pdfFile ?: return null
        return ThumbnailRequest(file = sourceFile, pageIndex = (page - 1).coerceAtLeast(0))
    }

    if (showSheetNavigator) {
        LaunchedEffect(showSheetNavigator, virtualMapping, clampedDisplayPage, effectiveTotalPages, resolvedPdfFilename) {
            if (!showSheetNavigator || effectiveTotalPages <= 0) return@LaunchedEffect
            for (page in buildVirtualTocLoadOrder(effectiveTotalPages, clampedDisplayPage)) {
                if (!isActive) break
                if (!tocThumbCache.containsKey(page)) {
                    val request = thumbnailRequestForPage(page)
                    val thumb = if (request != null) {
                        withContext(Dispatchers.IO) {
                            renderVirtualThumbnail(request.file, request.pageIndex)
                        }
                    } else {
                        null
                    }
                    tocThumbCache[page] = thumb
                    tocLruOrder.remove(page)
                    tocLruOrder.add(page)
                    trimVirtualTocCache(tocThumbCache, tocLruOrder, maxEntries = 120)
                }
                yield()
            }
        }

        ModalBottomSheet(onDismissRequest = { showSheetNavigator = false }) {
            ImmersiveDialogDecor()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(620.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(navigatorTitle, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Loading thumbnails $tocLoadedCount/$effectiveTotalPages",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!navigatorWarningMessage.isNullOrBlank()) {
                    Text(
                        navigatorWarningMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (navigatorCabinetToPages.isNotEmpty()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search cabinet") },
                        placeholder = { Text("Prefix match, e.g. 12") }
                    )
                }
                if (searchQuery.isNotBlank()) {
                    if (searchFilteredRows.isEmpty()) {
                        Text(
                            "No cabinet matches",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(searchFilteredRows, key = { it.page }) { row ->
                        val selected = row.page == clampedDisplayPage
                        val thumb = tocThumbCache[row.page]
                        val backgroundColor = when {
                            selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            row.isPlanView -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.30f)
                            else -> MaterialTheme.colorScheme.surface
                        }
                        Surface(
                            tonalElevation = if (selected) 3.dp else 1.dp,
                            color = backgroundColor,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDisplayPageChange(row.page)
                                    showSheetNavigator = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 120.dp, height = 90.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    when {
                                        thumb != null -> Image(
                                            bitmap = thumb.asImageBitmap(),
                                            contentDescription = "Page ${row.page}",
                                            modifier = Modifier.size(width = 120.dp, height = 90.dp)
                                        )
                                        else -> CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        row.primaryLabel,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        row.secondaryLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (row.matchedCabinets.isNotEmpty()) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        row.matchedCabinets.take(4).forEach { matchedCabinet ->
                                            Surface(
                                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                                shape = MaterialTheme.shapes.small
                                            ) {
                                                Text(
                                                    text = matchedCabinet,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                        val extraMatches = row.matchedCabinets.size - 4
                                        if (extraMatches > 0) {
                                            Text(
                                                text = "+$extraMatches",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                } else {
                                    Spacer(Modifier.size(1.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildVirtualTocLoadOrder(totalPages: Int, currentPage: Int): List<Int> {
    if (totalPages <= 0) return emptyList()
    val pages = mutableListOf<Int>()
    pages += currentPage.coerceIn(1, totalPages)
    var radius = 1
    while (pages.size < totalPages) {
        val left = currentPage - radius
        val right = currentPage + radius
        if (left >= 1) pages += left
        if (right <= totalPages) pages += right
        radius++
    }
    return pages.distinct()
}

private fun trimVirtualTocCache(
    cache: MutableMap<Int, Bitmap?>,
    lruOrder: MutableList<Int>,
    maxEntries: Int
) {
    while (lruOrder.size > maxEntries) {
        val stale = lruOrder.removeAt(0)
        cache.remove(stale)?.let { bmp ->
            runCatching { if (!bmp.isRecycled) bmp.recycle() }
        }
    }
}

private fun renderVirtualThumbnail(pdfFile: File, pageIndex: Int): Bitmap? {
    if (!pdfFile.exists()) return null
    var fd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    var page: PdfRenderer.Page? = null
    return try {
        fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(fd)
        if (pageIndex !in 0 until renderer.pageCount) return null
        page = renderer.openPage(pageIndex)
        val maxWidth = 420
        val scale = maxWidth.toFloat() / page.width.toFloat().coerceAtLeast(1f)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { page?.close() }
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
    }
}
