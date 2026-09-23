package com.kkc.sheettracker.ui.markup

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.kkc.sheettracker.data.models.PdfInkStroke
import com.kkc.sheettracker.data.models.PdfMarkupPageKey
import com.kkc.sheettracker.data.models.PdfPageMarkup
import java.util.Locale

/** Same normalization `PdfMarkupStore` applies to filenames, so keys match store keys. */
fun pdfMarkupPageKey(pdfFilename: String, page: Int): PdfMarkupPageKey =
    PdfMarkupPageKey(pdfFilename.trim().lowercase(Locale.US), page)

private fun PdfMarkupPageKey.isValid(): Boolean = pdfFilename.isNotBlank() && page > 0

/**
 * Everything the viewer knows about one PDF page's markup.
 *
 * [strokes] includes strokes merged in from other tablets; [ownStrokeIds] is the subset this
 * tablet authored, which is all that undo is allowed to remove. [deletedIds] is this tablet's own
 * deletion list, persisted so the deletion wins when tablets merge.
 */
data class PdfMarkupPageSnapshot(
    val strokes: List<PdfInkStroke> = emptyList(),
    val deletedIds: List<String> = emptyList(),
    val ownStrokeIds: Set<String> = emptySet()
) {
    val visibleStrokes: List<PdfInkStroke> = strokes.filter { it.id !in deletedIds }
}

/**
 * Builds the initial per-page state from the store: [merged] is
 * `PdfMarkupStore.getMergedActiveStrokesByPage`, [ownPages] is this tablet's own file
 * (`PdfMarkupStore.loadTabletMarkup(job).pages`).
 */
fun buildPdfMarkupSnapshots(
    merged: Map<PdfMarkupPageKey, List<PdfInkStroke>>,
    ownPages: List<PdfPageMarkup>
): Map<PdfMarkupPageKey, PdfMarkupPageSnapshot> {
    val ownByKey = ownPages.associateBy { pdfMarkupPageKey(it.pdfFilename, it.page) }
    return (merged.keys + ownByKey.keys).associateWith { key ->
        val own = ownByKey[key]
        PdfMarkupPageSnapshot(
            strokes = merged[key].orEmpty(),
            deletedIds = own?.deletedStrokeIds.orEmpty(),
            ownStrokeIds = own?.strokes?.map { it.id }?.toSet().orEmpty()
        )
    }
}

/** Captured by [PdfMarkupPageStates.beginReload] before a reload starts reading the disk. */
class PdfMarkupReloadGuard internal constructor(
    internal val seqAtStart: Long,
    internal val unsavedAtStart: Set<PdfMarkupPageKey>
)

/**
 * Snapshot-backed markup state for every page of one job, so the continuous viewer can draw and
 * show strokes on any page. Each mutation calls [persist] with just the page it changed; the
 * caller must invoke `onSaved` on the main thread once that write has landed on disk.
 *
 * Not thread-safe: call everything on the main thread.
 */
@Stable
class PdfMarkupPageStates(
    private val persist: (PdfMarkupPageKey, PdfMarkupPageSnapshot, onSaved: () -> Unit) -> Unit
) {
    private data class UndoEntry(val key: PdfMarkupPageKey, val strokeId: String)

    private val pages = mutableStateMapOf<PdfMarkupPageKey, PdfMarkupPageSnapshot>()
    private val undoStack = mutableStateListOf<UndoEntry>()

    // Plain (non-snapshot) bookkeeping of which local edits have reached disk, so a reload that
    // read the file before a save landed can't replace newer in-memory strokes with stale ones.
    private var mutationSeq = 0L
    private val lastMutation = HashMap<PdfMarkupPageKey, Long>()
    private val lastSaved = HashMap<PdfMarkupPageKey, Long>()

    fun strokesFor(key: PdfMarkupPageKey): List<PdfInkStroke> =
        pages[key]?.visibleStrokes.orEmpty()

    private fun commit(key: PdfMarkupPageKey, next: PdfMarkupPageSnapshot) {
        pages[key] = next
        val seq = ++mutationSeq
        lastMutation[key] = seq
        persist(key, next) {
            if (seq > (lastSaved[key] ?: 0L)) lastSaved[key] = seq
        }
    }

    fun add(key: PdfMarkupPageKey, stroke: PdfInkStroke) {
        if (!key.isValid()) return
        val current = pages[key] ?: PdfMarkupPageSnapshot()
        val next = current.copy(
            strokes = current.strokes + stroke,
            ownStrokeIds = current.ownStrokeIds + stroke.id
        )
        undoStack.add(UndoEntry(key, stroke.id))
        commit(key, next)
    }

    /** Returns true if a visible stroke was removed. */
    fun erase(key: PdfMarkupPageKey, strokeId: String): Boolean {
        if (!key.isValid()) return false
        val current = pages[key] ?: return false
        if (strokeId in current.deletedIds || current.strokes.none { it.id == strokeId }) return false
        commit(key, current.copy(deletedIds = current.deletedIds + strokeId))
        return true
    }

    private fun isVisible(entry: UndoEntry): Boolean =
        pages[entry.key]?.visibleStrokes?.any { it.id == entry.strokeId } == true

    private fun undoTarget(fallbackKey: PdfMarkupPageKey): UndoEntry? {
        undoStack.asReversed().firstOrNull { isVisible(it) }?.let { return it }
        val snapshot = pages[fallbackKey] ?: return null
        val id = snapshot.visibleStrokes.lastOrNull { it.id in snapshot.ownStrokeIds }?.id
            ?: return null
        return UndoEntry(fallbackKey, id)
    }

    fun hasUndo(fallbackKey: PdfMarkupPageKey): Boolean = undoTarget(fallbackKey) != null

    /**
     * Removes the most recent stroke drawn this session (any page). With nothing drawn this
     * session, falls back to this tablet's last persisted stroke on [fallbackKey].
     */
    fun undoLast(fallbackKey: PdfMarkupPageKey): Boolean {
        val target = undoTarget(fallbackKey) ?: return false
        return erase(target.key, target.strokeId)
    }

    /** Call on the main thread immediately before a reload starts reading the store. */
    fun beginReload(): PdfMarkupReloadGuard = PdfMarkupReloadGuard(
        seqAtStart = mutationSeq,
        unsavedAtStart = lastMutation.filter { (key, seq) -> seq > (lastSaved[key] ?: 0L) }.keys.toSet()
    )

    /**
     * Swaps in freshly loaded pages. With a [guard], pages that had an unsaved edit when the
     * reload started, or were edited since, keep their in-memory state: the disk copy may predate
     * them. The save that lands for such a page triggers another reload that picks up remote
     * strokes for it.
     */
    fun replaceAll(
        snapshots: Map<PdfMarkupPageKey, PdfMarkupPageSnapshot>,
        guard: PdfMarkupReloadGuard? = null
    ) {
        val keep = if (guard == null) {
            emptyMap()
        } else {
            pages.filterKeys { key ->
                key in guard.unsavedAtStart || (lastMutation[key] ?: 0L) > guard.seqAtStart
            }
        }
        pages.clear()
        pages.putAll(snapshots)
        pages.putAll(keep)
    }
}
