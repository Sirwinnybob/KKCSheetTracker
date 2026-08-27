package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.DeliveryJob
import com.kkc.sheettracker.data.models.DeliverySchedule
import com.kkc.sheettracker.data.models.DeliverySlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryScheduleStateStoreTest {

    private fun scheduleWith(jobNumber: String): DeliverySchedule =
        DeliverySchedule(
            slots = mapOf(
                "friday_am" to DeliverySlot(
                    jobs = listOf(DeliveryJob(jobNumber = jobNumber))
                )
            )
        )

    @Test
    fun coldStart_seedsFromFallbackLoader() {
        var fallback = scheduleWith("100")
        val store = DeliveryScheduleStateStore { fallback }

        assertEquals("100", store.schedule.value.slot("friday", "am").jobs.single().jobNumber)
        assertFalse(store.liveConnected)
    }

    @Test
    fun applyLive_replacesScheduleAndSetsConnected() {
        var fallback = scheduleWith("100")
        val store = DeliveryScheduleStateStore { fallback }

        store.applyLive(scheduleWith("200"))

        assertEquals("200", store.schedule.value.slot("friday", "am").jobs.single().jobNumber)
        assertTrue(store.liveConnected)
    }

    @Test
    fun refreshFallback_isNoOpWhileLiveConnected() {
        var fallback = scheduleWith("100")
        val store = DeliveryScheduleStateStore { fallback }

        store.applyLive(scheduleWith("200"))
        fallback = scheduleWith("stale")
        store.refreshFallback()

        assertEquals("200", store.schedule.value.slot("friday", "am").jobs.single().jobNumber)
        assertTrue(store.liveConnected)
    }

    @Test
    fun setLiveConnectedFalse_clearsFlagAndReloadsFallbackImmediately() {
        var fallback = scheduleWith("100")
        val store = DeliveryScheduleStateStore { fallback }

        store.applyLive(scheduleWith("200"))
        fallback = scheduleWith("300")
        store.setLiveConnected(false)

        assertFalse(store.liveConnected)
        assertEquals("300", store.schedule.value.slot("friday", "am").jobs.single().jobNumber)
    }

    @Test
    fun refreshFallback_afterDisconnectReloadsAgain() {
        var fallback = scheduleWith("100")
        val store = DeliveryScheduleStateStore { fallback }

        store.applyLive(scheduleWith("200"))
        store.setLiveConnected(false)
        fallback = scheduleWith("400")
        store.refreshFallback()

        assertEquals("400", store.schedule.value.slot("friday", "am").jobs.single().jobNumber)
        assertFalse(store.liveConnected)
    }

    @Test
    fun applyLive_afterDisconnectReplacesFallbackAndReconnects() {
        var fallback = scheduleWith("100")
        val store = DeliveryScheduleStateStore { fallback }

        store.applyLive(scheduleWith("200"))
        store.setLiveConnected(false)
        store.applyLive(scheduleWith("500"))

        assertEquals("500", store.schedule.value.slot("friday", "am").jobs.single().jobNumber)
        assertTrue(store.liveConnected)
    }

    @Test
    fun applyImmediate_replacesScheduleWithoutChangingConnectionState() {
        var fallback = scheduleWith("100")
        val store = DeliveryScheduleStateStore { fallback }

        // While disconnected.
        store.applyImmediate(scheduleWith("edit-1"))
        assertEquals("edit-1", store.schedule.value.slot("friday", "am").jobs.single().jobNumber)
        assertFalse(store.liveConnected)

        // While connected.
        store.applyLive(scheduleWith("200"))
        store.applyImmediate(scheduleWith("edit-2"))
        assertEquals("edit-2", store.schedule.value.slot("friday", "am").jobs.single().jobNumber)
        assertTrue(store.liveConnected)
    }

    @Test
    fun setLiveConnectedTrue_doesNotDeliverSchedule() {
        var fallback = scheduleWith("100")
        val store = DeliveryScheduleStateStore { fallback }

        store.setLiveConnected(true)

        assertTrue(store.liveConnected)
        assertEquals("100", store.schedule.value.slot("friday", "am").jobs.single().jobNumber)
    }
}
