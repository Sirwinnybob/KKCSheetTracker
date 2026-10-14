package com.kkc.sheettracker.ui.handoff

import com.kkc.sheettracker.data.HandoffFlagsSnapshot
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffItemFlags
import com.kkc.sheettracker.data.models.HandoffTruck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HandoffFlagsUiStateTest {
    private val ref = HandoffFlagRef("board_stock", "5f0c")
    private val snapshot = HandoffFlagsSnapshot(stored = mapOf(ref to HandoffItemFlags(HandoffTruck.SHOP)))

    @Test
    fun messageQueueShowsEachInOrder() {
        val q = HandoffMessageQueue()
        assertNull(q.head)
        q.offer("A")
        q.offer("B")
        assertEquals("A", q.head)
        q.offer("B") // identical neighbour at the tail adds nothing
        q.consume()
        assertEquals("B", q.head)
        q.consume()
        assertNull(q.head)
        q.consume() // consuming an empty queue is harmless
        assertNull(q.head)
    }

    @Test
    fun messageQueueKeepsNonAdjacentRepeats() {
        val q = HandoffMessageQueue()
        listOf("A", "B", "A").forEach(q::offer)
        val seen = mutableListOf<String?>()
        repeat(3) { seen += q.head; q.consume() }
        assertEquals(listOf<String?>("A", "B", "A"), seen)
    }

    @Test
    fun uiForCachesOneItemPerRefAndSharesStableSelectors() {
        val taps = mutableListOf<Pair<HandoffFlagRef, HandoffTruck?>>()
        val selectors = HandoffSelectorCache { r, t -> taps += r to t }
        val queue = HandoffMessageQueue()
        val first = HandoffFlagsUiState(snapshot, editable = true, messages = queue, selectors = selectors)
        assertSame(first.uiFor(ref), first.uiFor(ref))
        // A refreshed state (new snapshot object, same content) yields an equal item: rows can skip.
        val second = HandoffFlagsUiState(snapshot.copy(), editable = true, messages = queue, selectors = selectors)
        assertEquals(first.uiFor(ref), second.uiFor(ref))
        first.uiFor(ref).onSelectTruck!!.invoke(HandoffTruck.LATER)
        assertEquals(listOf(ref to HandoffTruck.LATER), taps)
    }

    @Test
    fun readOnlyStateHasNoSelectCallback() {
        val state = HandoffFlagsUiState(snapshot, editable = false, messages = HandoffMessageQueue(), selectors = HandoffSelectorCache { _, _ -> })
        assertNull(state.uiFor(ref).onSelectTruck)
        assertEquals("Shop install", state.uiFor(ref).display.label)
    }

    @Test
    fun stateExposesAndConsumesTheQueueHead() {
        val queue = HandoffMessageQueue().apply { offer("one"); offer("two") }
        val state = HandoffFlagsUiState(snapshot, editable = true, messages = queue, selectors = HandoffSelectorCache { _, _ -> })
        assertEquals("one", state.message)
        state.consumeMessage()
        assertEquals("two", state.message)
        assertNotNull(state.uiFor(ref))
    }
}
