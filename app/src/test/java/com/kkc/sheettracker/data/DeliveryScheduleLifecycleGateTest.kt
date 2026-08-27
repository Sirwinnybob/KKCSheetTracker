package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryScheduleLifecycleGateTest {
    @Test
    fun startTokenBecomesStaleAfterStopAndSourceReplacement() {
        val gate = DeliveryScheduleLifecycleGate()
        val source = gate.bindSource()
        val start = gate.begin(source)

        assertTrue(gate.isCurrent(source, start))
        var starts = 0
        assertTrue(gate.runIfCurrent(source, start) { starts += 1 })
        assertEquals(1, starts)

        gate.stop(source)
        assertFalse(gate.isCurrent(source, start))
        assertFalse(gate.runIfCurrent(source, start) { starts += 1 })
        assertEquals(1, starts)

        val replacement = gate.bindSource()
        assertFalse(gate.isCurrent(source, start))
        val replacementStart = gate.begin(replacement)
        assertTrue(gate.isCurrent(replacement, replacementStart))
    }

    @Test
    fun disposalCleanupTokenIsInvalidatedByReplacement() {
        val gate = DeliveryScheduleLifecycleGate()
        val source = gate.bindSource()
        val cleanup = gate.dispose(source)

        assertTrue(gate.isCleanupCurrent(cleanup))

        gate.bindSource()
        assertFalse(gate.isCleanupCurrent(cleanup))
    }
}
