package com.kkc.sheettracker.data.mixfiles

import com.kkc.sheettracker.data.cnc.inferSheetFiles
import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.data.models.SheetStatus
import java.util.Locale

fun isLockedStatus(status: SheetStatus): Boolean = when (status) {
    SheetStatus.COMPLETE, SheetStatus.HAS_BAD_PARTS, SheetStatus.RE_NESTED -> true
    else -> false
}

data class SheetPage(
    val pageNumber: Int,
    val pgmFiles: List<String>,
    val hidden: Boolean,
    val continuation: Boolean,
    val thumbnailPath: String?,
)

fun sheetPages(pages: List<PageMetadata>): List<SheetPage> = pages.sortedBy { it.pageNumber }.mapNotNull { page ->
    val files = orderedPgmFiles(inferSheetFiles(page))
    if (files.isEmpty()) null else SheetPage(page.pageNumber, files, page.hiddenInApp || page.trackingExcluded, page.isPartListContinuation, page.thumbnailPath)
}

/** Continuation PDFs repeat the head's programs; offer one physical sheet, preferring its visible head. */
fun physicalSheetPages(pages: List<SheetPage>): List<SheetPage> = pages
    .sortedWith(compareBy<SheetPage> { if (it.continuation) 2 else if (it.hidden) 1 else 0 }.thenBy { it.pageNumber })
    .distinctBy { page -> page.pgmFiles.map { it.lowercase(Locale.ROOT) }.toSet() }
    .sortedBy { it.pageNumber }

// inferSheetFiles can return [Z, A] from its sheetId fallback. Keep the shared
// business order A before Z regardless of which metadata representation supplied it.
internal fun orderedPgmFiles(stems: List<String>): List<String> {
    val files = stems.map { "$it.pgm" }
    if (files.size != 2) return files
    return files.sortedBy { if (it.substringBeforeLast('.').endsWith("Z", ignoreCase = true)) 1 else 0 }
}

sealed interface MixItem {
    val entryIndices: List<Int>
    data class Sheet(
        val page: SheetPage,
        override val entryIndices: List<Int>,
        val pgms: List<String>,
        val quantity: Int,
        val remaining: Int?,
        val zOnly: Boolean?,
        val aOnly: Boolean?,
    ) : MixItem
    data class Unmatched(override val entryIndices: List<Int>, val pgm: String) : MixItem
}

fun matchEntries(entries: List<MixFileEntry>, pages: List<SheetPage>): List<MixItem> {
    val preferredPages = pages.sortedBy { if (it.continuation) 2 else if (it.hidden) 1 else 0 }
    val pageByPgm = mutableMapOf<String, SheetPage>()
    preferredPages.forEach { page -> page.pgmFiles.forEach { pageByPgm.putIfAbsent(it.lowercase(Locale.ROOT), page) } }
    val result = mutableListOf<MixItem>()
    var index = 0
    while (index < entries.size) {
        val entry = entries[index]
        val page = pageByPgm[entry.pgm.lowercase(Locale.ROOT)]
        if (page == null) {
            result += MixItem.Unmatched(listOf(index), entry.pgm)
            index++
            continue
        }
        val next = entries.getOrNull(index + 1)
        val isPair = page.pgmFiles.size == 2 &&
            page.pgmFiles[0].substringBeforeLast('.').endsWith("A", true) &&
            page.pgmFiles[1].substringBeforeLast('.').endsWith("Z", true)
        val collapse = isPair && entry.pgm.equals(page.pgmFiles[0], true) && next != null &&
            next.pgm.equals(page.pgmFiles[1], true) && pageByPgm[next.pgm.lowercase(Locale.ROOT)] == page &&
            entry.quantity == next.quantity && entry.remaining == next.remaining
        result += MixItem.Sheet(
            page, if (collapse) listOf(index, index + 1) else listOf(index),
            if (collapse) listOf(entry.pgm, next.pgm) else listOf(entry.pgm),
            entry.quantity, entry.remaining,
            if (isPair) !collapse && entry.pgm.equals(page.pgmFiles[1], true) else null,
            if (isPair) !collapse && entry.pgm.equals(page.pgmFiles[0], true) else null,
        )
        index += if (collapse) 2 else 1
    }
    return result
}

fun completedPgms(items: List<MixItem>, statusOf: (Int) -> SheetStatus): List<String> = items
    .filterIsInstance<MixItem.Sheet>()
    .filter { isLockedStatus(statusOf(it.page.pageNumber)) }
    .flatMap { it.page.pgmFiles }
    .distinctBy { it.lowercase(Locale.ROOT) }

fun viewerPageOrder(items: List<MixItem>): List<Int> = items.filterIsInstance<MixItem.Sheet>()
    .map { it.page.pageNumber }.distinct()
