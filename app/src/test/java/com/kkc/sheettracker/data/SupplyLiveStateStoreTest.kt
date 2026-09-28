package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.StoredSupplyItem
import com.kkc.sheettracker.data.models.SupplyCategory
import com.kkc.sheettracker.data.models.SupplyComment
import com.kkc.sheettracker.data.models.SupplyItem
import com.kkc.sheettracker.data.models.SupplyStatusRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplyLiveStateStoreTest {

    private var now = 1_000_000L
    private val store = SupplyLiveStateStore(nowMs = { now })

    private fun item(
        id: String = "i1",
        name: String = "Screws",
        status: String = "IN STOCK",
        statusAt: String = "",
        updatedAt: String = "2026-01-01T00:00:00Z"
    ) = SupplyItem(
        id = id, categoryId = "c1", name = name, status = status, statusBy = "", statusAt = statusAt,
        notes = null, fields = emptyMap(), customFields = emptyMap(), attachmentIds = emptyList(),
        barcodes = emptyList(), createdAt = "2026-01-01T00:00:00Z", updatedAt = updatedAt
    )

    private fun stored(id: String = "i1", name: String = "Screws", updatedAt: String) = StoredSupplyItem(
        id = id, categoryId = "c1", name = name, notes = null, fields = emptyMap(),
        customFields = emptyMap(), attachmentIds = emptyList(), barcodes = emptyList(),
        createdAt = "2026-01-01T00:00:00Z", updatedAt = updatedAt
    )

    private fun comment(id: String, createdAt: String = "2026-01-01T00:00:00Z") =
        SupplyComment(id, "Sam", "text $id", createdAt)

    private fun snapshot(
        revision: Long = 1L,
        items: List<SupplyItem> = listOf(item()),
        comments: Map<String, List<SupplyComment>> = emptyMap(),
        categories: List<SupplyCategory> = listOf(SupplyCategory("c1", "Hardware", 0))
    ) = SupplyLiveSnapshot(revision, categories, emptyList(), items.associateBy { it.id }, comments)

    @Test
    fun `view is null until a live snapshot arrives`() {
        assertNull(store.view())
        store.recordStatus("i1", SupplyStatusRecord("OUT", "Sam", "2026-01-02T00:00:00Z"))
        assertNull(store.view())
        assertFalse(store.liveConnected)
    }

    @Test
    fun `applyLive exposes the snapshot and bumps version`() {
        val before = store.version.value
        store.applyLive(snapshot())
        assertTrue(store.liveConnected)
        assertTrue(store.version.value > before)
        val view = store.view()!!
        assertEquals("Screws", view.item("i1")!!.name)
        assertEquals(listOf("c1"), view.categories.map { it.id })
    }

    @Test
    fun `setDisconnected clears the view`() {
        store.applyLive(snapshot())
        store.setDisconnected()
        assertFalse(store.liveConnected)
        assertNull(store.view())
    }

    @Test
    fun `second disconnect does not bump version`() {
        store.applyLive(snapshot())
        store.setDisconnected()
        val afterFirst = store.version.value
        store.setDisconnected()
        store.setDisconnected()
        assertEquals(afterFirst, store.version.value)
    }

    @Test
    fun `local status shows until live statusAt catches up`() {
        store.applyLive(snapshot(items = listOf(item(statusAt = "2026-01-01T00:00:00Z"))))
        store.recordStatus("i1", SupplyStatusRecord("OUT", "Sam", "2026-01-02T00:00:00Z"))
        assertEquals("OUT", store.view()!!.item("i1")!!.status)

        // Stale live snapshot: overlay still wins.
        store.applyLive(snapshot(revision = 2, items = listOf(item(statusAt = "2026-01-01T00:00:00Z"))))
        assertEquals("OUT", store.view()!!.item("i1")!!.status)

        // Server caught up: overlay entry is pruned.
        store.applyLive(snapshot(revision = 3, items = listOf(item(status = "OUT", statusAt = "2026-01-02T00:00:00Z"))))
        // Prove the entry is gone: an older live state now shows through.
        store.applyLive(snapshot(revision = 4, items = listOf(item(status = "IN STOCK", statusAt = "2026-01-01T00:00:00Z"))))
        assertEquals("IN STOCK", store.view()!!.item("i1")!!.status)
    }

    @Test
    fun `fractional live statusAt satisfies whole-second local status`() {
        store.applyLive(snapshot(items = listOf(item(statusAt = "2026-01-01T00:00:00Z"))))
        store.recordStatus("i1", SupplyStatusRecord("OUT", "Sam", "2026-01-02T00:00:00Z"))
        store.applyLive(snapshot(revision = 2, items = listOf(item(status = "LOW", statusAt = "2026-01-02T00:00:00.5Z"))))
        assertEquals("LOW", store.view()!!.item("i1")!!.status)
    }

    @Test
    fun `local comment add shows until live contains it`() {
        store.applyLive(snapshot(comments = mapOf("i1" to listOf(comment("k1", "2026-01-01T00:00:00Z")))))
        store.recordCommentAdded("i1", comment("k2", "2026-01-01T00:00:00.5Z"))
        assertEquals(listOf("k1", "k2"), store.view()!!.comments("i1").map { it.id })

        store.applyLive(snapshot(revision = 2, comments = mapOf("i1" to listOf(comment("k1"), comment("k2", "2026-01-01T00:00:00.5Z")))))
        store.applyLive(snapshot(revision = 3, comments = mapOf("i1" to listOf(comment("k1")))))
        assertEquals(listOf("k1"), store.view()!!.comments("i1").map { it.id })
    }

    @Test
    fun `local comment delete hides until live drops it`() {
        store.applyLive(snapshot(comments = mapOf("i1" to listOf(comment("k1")))))
        store.recordCommentDeleted("i1", "k1")
        assertTrue(store.view()!!.comments("i1").isEmpty())
        store.applyLive(snapshot(revision = 2, comments = mapOf("i1" to listOf(comment("k1")))))
        assertTrue(store.view()!!.comments("i1").isEmpty())
    }

    @Test
    fun `local comment add then delete is not resurrected`() {
        store.applyLive(snapshot())
        store.recordCommentAdded("i1", comment("k9"))
        store.recordCommentDeleted("i1", "k9")
        store.applyLive(snapshot(revision = 2))
        assertTrue(store.view()!!.comments("i1").isEmpty())
    }

    @Test
    fun `local item edit shows until live updatedAt catches up`() {
        store.applyLive(snapshot(items = listOf(item(name = "Screws", updatedAt = "2026-01-01T00:00:00Z", status = "LOW"))))
        store.recordItemUpserted(stored(name = "Wood screws", updatedAt = "2026-01-02T00:00:00Z"))
        val edited = store.view()!!.item("i1")!!
        assertEquals("Wood screws", edited.name)
        assertEquals("LOW", edited.status)

        store.applyLive(snapshot(revision = 2, items = listOf(item(name = "Wood screws", updatedAt = "2026-01-02T00:00:00Z"))))
        store.applyLive(snapshot(revision = 3, items = listOf(item(name = "Screws", updatedAt = "2026-01-01T00:00:00Z"))))
        assertEquals("Screws", store.view()!!.item("i1")!!.name)
    }

    @Test
    fun `new local item appears with its local status`() {
        store.applyLive(snapshot(items = emptyList()))
        store.recordItemUpserted(stored(id = "new", name = "Glue", updatedAt = "2026-01-02T00:00:00Z"))
        assertEquals("IN STOCK", store.view()!!.item("new")!!.status)
        store.recordStatus("new", SupplyStatusRecord("OUT", "Sam", "2026-01-02T00:00:00Z"))
        assertEquals("OUT", store.view()!!.item("new")!!.status)
        assertEquals(listOf("new"), store.view()!!.items.map { it.id })
    }

    @Test
    fun `local item delete hides until live drops it`() {
        store.applyLive(snapshot(comments = mapOf("i1" to listOf(comment("k1")))))
        store.recordItemDeleted("i1")
        assertNull(store.view()!!.item("i1"))
        assertTrue(store.view()!!.comments("i1").isEmpty())
        store.applyLive(snapshot(revision = 2, items = emptyList()))
        assertNull(store.view()!!.item("i1"))
    }

    @Test
    fun `local item create then delete is not resurrected`() {
        store.applyLive(snapshot(items = emptyList()))
        store.recordItemUpserted(stored(id = "tmp", updatedAt = "2026-01-02T00:00:00Z"))
        store.recordStatus("tmp", SupplyStatusRecord("OUT", "Sam", "2026-01-02T00:00:00Z"))
        store.recordItemDeleted("tmp")
        store.applyLive(snapshot(revision = 2, items = emptyList()))
        assertNull(store.view()!!.item("tmp"))
    }

    @Test
    fun `local category shows until live contains it`() {
        store.applyLive(snapshot())
        store.recordCategoryCreated(SupplyCategory("c2", "Glue", 1))
        assertEquals(listOf("c1", "c2"), store.view()!!.categories.map { it.id })
    }

    @Test
    fun `overlay entries expire after the TTL`() {
        store.applyLive(snapshot(items = listOf(item(statusAt = "2026-01-01T00:00:00Z"))))
        store.recordStatus("i1", SupplyStatusRecord("OUT", "Sam", "2026-01-02T00:00:00Z"))
        now += SupplyLiveStateStore.OVERLAY_TTL_MS
        assertEquals("IN STOCK", store.view()!!.item("i1")!!.status)
    }

    @Test
    fun `record calls bump version`() {
        val before = store.version.value
        store.recordCategoryCreated(SupplyCategory("c2", "Glue", 1))
        assertTrue(store.version.value > before)
        assertNotNull(SupplyLiveStateStore.shared)
    }
}
