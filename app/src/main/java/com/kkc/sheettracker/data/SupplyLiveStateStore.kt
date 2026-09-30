package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.StoredSupplyItem
import com.kkc.sheettracker.data.models.SupplyCategory
import com.kkc.sheettracker.data.models.SupplyComment
import com.kkc.sheettracker.data.models.SupplyItem
import com.kkc.sheettracker.data.models.SupplySchemaField
import com.kkc.sheettracker.data.models.SupplyStatusRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Merged supply read model served to [SupplyRepository] while the live socket is connected. */
class SupplyView internal constructor(
    val categories: List<SupplyCategory>,
    val schema: List<SupplySchemaField>,
    val items: List<SupplyItem>,
    private val commentsByItemId: Map<String, List<SupplyComment>>
) {
    private val itemsById = items.associateBy { it.id }

    fun item(itemId: String): SupplyItem? = itemsById[itemId]

    fun comments(itemId: String): List<SupplyComment> = commentsByItemId[itemId].orEmpty()
}

/**
 * Process-wide supply read model fed by [SupplyLiveClient]
 * (spec docs/superpowers/specs/2026-09-28-supply-live-websocket-design.md).
 *
 * [view] returns null whenever the socket is not live; callers then read `.supply` files as before.
 * Local writes are recorded in a pending overlay so this tablet's own change stays visible until the
 * server's document contains it (Syncthing has to deliver the file to the server first) or
 * [OVERLAY_TTL_MS] passes.
 */
class SupplyLiveStateStore(private val nowMs: () -> Long = System::currentTimeMillis) {

    private sealed class Pending {
        abstract val itemId: String?
        abstract val createdAtMs: Long

        data class Status(override val itemId: String, val record: SupplyStatusRecord, override val createdAtMs: Long) : Pending()
        data class CommentAdded(override val itemId: String, val comment: SupplyComment, override val createdAtMs: Long) : Pending()
        data class CommentDeleted(override val itemId: String, val commentId: String, override val createdAtMs: Long) : Pending()
        data class ItemUpserted(val item: StoredSupplyItem, override val createdAtMs: Long) : Pending() {
            override val itemId: String get() = item.id
        }
        data class ItemDeleted(override val itemId: String, override val createdAtMs: Long) : Pending()
        data class CategoryCreated(val category: SupplyCategory, override val createdAtMs: Long) : Pending() {
            override val itemId: String? get() = null
        }
    }

    private val lock = Any()
    private var live: SupplyLiveSnapshot? = null
    private val pending = mutableListOf<Pending>()

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    @Volatile
    var liveConnected: Boolean = false
        private set

    fun applyLive(snapshot: SupplyLiveSnapshot) {
        synchronized(lock) {
            live = snapshot
            liveConnected = true
            pending.removeAll { isSatisfied(it, snapshot) }
            _version.value += 1
        }
    }

    /** Idempotent: only a real live-to-disconnected transition bumps [version]. */
    fun setDisconnected() {
        synchronized(lock) {
            if (!liveConnected && live == null) return
            liveConnected = false
            live = null
            _version.value += 1
        }
    }

    fun view(): SupplyView? {
        synchronized(lock) {
            if (!liveConnected) return null
            val snapshot = live ?: return null
            val now = nowMs()
            pending.removeAll { now - it.createdAtMs >= OVERLAY_TTL_MS }
            return merge(snapshot, pending)
        }
    }

    fun recordStatus(itemId: String, record: SupplyStatusRecord) {
        add(Pending.Status(itemId, record, nowMs()))
    }

    fun recordCommentAdded(itemId: String, comment: SupplyComment) {
        add(Pending.CommentAdded(itemId, comment, nowMs()))
    }

    /** Also drops a pending add of the same comment, so a local add-then-delete is not resurrected. */
    fun recordCommentDeleted(itemId: String, commentId: String) {
        synchronized(lock) {
            pending.removeAll { it is Pending.CommentAdded && it.itemId == itemId && it.comment.id == commentId }
            pending += Pending.CommentDeleted(itemId, commentId, nowMs())
            _version.value += 1
        }
    }

    fun recordItemUpserted(item: StoredSupplyItem) {
        add(Pending.ItemUpserted(item, nowMs()))
    }

    /** Also drops every pending entry for the item, so a local create-then-delete is not resurrected. */
    fun recordItemDeleted(itemId: String) {
        synchronized(lock) {
            pending.removeAll { it.itemId == itemId }
            pending += Pending.ItemDeleted(itemId, nowMs())
            _version.value += 1
        }
    }

    fun recordCategoryCreated(category: SupplyCategory) {
        add(Pending.CategoryCreated(category, nowMs()))
    }

    private fun add(entry: Pending) {
        synchronized(lock) {
            pending += entry
            _version.value += 1
        }
    }

    private fun instant(value: String) = SupplyRepository.parseInstantOrMin(value)

    private fun isSatisfied(entry: Pending, snapshot: SupplyLiveSnapshot): Boolean = when (entry) {
        is Pending.Status ->
            snapshot.items[entry.itemId]?.let { instant(it.statusAt) >= instant(entry.record.at) } ?: false
        is Pending.CommentAdded ->
            snapshot.comments[entry.itemId].orEmpty().any { it.id == entry.comment.id }
        is Pending.CommentDeleted ->
            snapshot.comments[entry.itemId].orEmpty().none { it.id == entry.commentId }
        is Pending.ItemUpserted ->
            snapshot.items[entry.item.id]?.let { instant(it.updatedAt) >= instant(entry.item.updatedAt) } ?: false
        is Pending.ItemDeleted ->
            !snapshot.items.containsKey(entry.itemId)
        is Pending.CategoryCreated ->
            snapshot.categories.any { it.id == entry.category.id }
    }

    /** Applies pending entries oldest-first, so a later local write wins over an earlier one. */
    private fun merge(snapshot: SupplyLiveSnapshot, entries: List<Pending>): SupplyView {
        val items = LinkedHashMap(snapshot.items)
        val comments = HashMap(snapshot.comments)
        val categories = snapshot.categories.toMutableList()
        for (entry in entries) {
            when (entry) {
                is Pending.ItemUpserted -> {
                    val current = items[entry.item.id]
                    val status = if (current != null) {
                        SupplyStatusRecord(current.status, current.statusBy, current.statusAt)
                    } else {
                        SupplyStatusRecord("IN STOCK")
                    }
                    items[entry.item.id] = SupplyRepository.resolveStoredItem(entry.item, status)
                }
                is Pending.Status -> items[entry.itemId]?.let { current ->
                    items[entry.itemId] = current.copy(
                        status = entry.record.status,
                        statusBy = entry.record.by,
                        statusAt = entry.record.at
                    )
                }
                is Pending.ItemDeleted -> {
                    items.remove(entry.itemId)
                    comments.remove(entry.itemId)
                }
                is Pending.CommentAdded -> {
                    val list = comments[entry.itemId].orEmpty()
                    if (list.none { it.id == entry.comment.id }) {
                        comments[entry.itemId] = (list + entry.comment).sortedWith(SUPPLY_COMMENT_ORDER)
                    }
                }
                is Pending.CommentDeleted -> comments[entry.itemId]?.let { list ->
                    comments[entry.itemId] = list.filterNot { it.id == entry.commentId }
                }
                is Pending.CategoryCreated ->
                    if (categories.none { it.id == entry.category.id }) categories += entry.category
            }
        }
        return SupplyView(categories, snapshot.schema, items.values.toList(), comments)
    }

    companion object {
        const val OVERLAY_TTL_MS = 120_000L

        /** App-wide instance; every [SupplyRepository] reads through it by default. */
        val shared = SupplyLiveStateStore()
    }
}
