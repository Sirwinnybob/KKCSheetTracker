package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryScheduleLifecycleGateTest {
    private fun scheduleWith(jobNumber: String): DeliverySchedule =
        DeliverySchedule(
            slots = mapOf(
                "friday_am" to DeliverySlot(jobs = listOf(DeliveryJob(jobNumber = jobNumber)))
            )
        )

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

    @Test
    fun staleClientScheduleAndConnectionCallbacksCannotMutateReplacementStore() {
        val gate = DeliveryScheduleLifecycleGate()
        val oldBinding = DeliveryScheduleClientBinding(gate)
        val store = DeliveryScheduleStateStore(
            initialSchedule = scheduleWith("replacement"),
            fallbackLoader = { scheduleWith("fallback") }
        )
        val oldSource = gate.bindSource()
        oldBinding.bind(oldSource)
        val oldScheduleCallback = oldBinding.scheduleCallback(store)
        val oldConnectionCallback = oldBinding.connectionCallback(store)

        gate.dispose(oldSource)
        val replacementBinding = DeliveryScheduleClientBinding(gate)
        replacementBinding.bind(gate.bindSource())
        store.markLiveDisconnected()

        oldScheduleCallback(scheduleWith("stale"))
        oldConnectionCallback(true)

        assertEquals(
            "replacement",
            store.schedule.value.slot("friday", "am").jobs.single().jobNumber
        )
        assertFalse(store.liveConnected)
    }
}
