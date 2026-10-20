package com.kkc.sheettracker.viewer3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WingServerSessionSlotTest {
    @Test
    fun replace_keepsCurrentSessionAliveUntilReplacementOrScreenDisposal() {
        val stopped = mutableListOf<String>()
        val slot = WingServerSessionSlot<String> { stopped += it }

        slot.replace("first")

        assertSame("first", slot.current)
        assertEquals(emptyList<String>(), stopped)

        slot.replace("second")

        assertSame("second", slot.current)
        assertEquals(listOf("first"), stopped)

        slot.dispose()

        assertEquals(null, slot.current)
        assertEquals(listOf("first", "second"), stopped)
    }
}
