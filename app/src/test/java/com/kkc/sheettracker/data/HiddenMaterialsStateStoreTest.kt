package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialEntry
import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import com.kkc.sheettracker.data.models.HiddenMaterialsGlobalDoc
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenMaterialsStateStoreTest {

    private fun documentWith(material: String): HiddenMaterialsDocument =
        HiddenMaterialsDocument(
            global = HiddenMaterialsGlobalDoc(
                entries = listOf(HiddenMaterialEntry(docType = "NAILER_CUT_LIST", material = material))
            )
        )

    @Test
    fun coldStart_seedsFromFallbackLoader() {
        var fallback = documentWith("Maple")
        val store = HiddenMaterialsStateStore { fallback }

        assertEquals("Maple", store.document.value.global.entries.single().material)
        assertFalse(store.liveConnected)
    }

    @Test
    fun explicitInitialDocument_doesNotInvokeFallbackLoaderDuringConstruction() {
        var fallbackLoads = 0
        val store = HiddenMaterialsStateStore(
            fallbackLoader = { fallbackLoads += 1; documentWith("fallback") },
            initialDocument = documentWith("initial")
        )

        assertEquals(0, fallbackLoads)
        assertEquals("initial", store.document.value.global.entries.single().material)
    }

    @Test
    fun applyLive_replacesDocumentAndSetsConnected() {
        var fallback = documentWith("Maple")
        val store = HiddenMaterialsStateStore { fallback }

        store.applyLive(documentWith("Walnut"))

        assertEquals("Walnut", store.document.value.global.entries.single().material)
        assertTrue(store.liveConnected)
    }

    @Test
    fun refreshFallback_isNoOpWhileLiveConnected() {
        var fallback = documentWith("Maple")
        val store = HiddenMaterialsStateStore { fallback }

        store.applyLive(documentWith("Walnut"))
        fallback = documentWith("stale")
        store.refreshFallback()

        assertEquals("Walnut", store.document.value.global.entries.single().material)
        assertTrue(store.liveConnected)
    }

    @Test
    fun setLiveConnectedFalse_clearsFlagAndReloadsFallbackImmediately() {
        var fallback = documentWith("Maple")
        val store = HiddenMaterialsStateStore { fallback }

        store.applyLive(documentWith("Walnut"))
        fallback = documentWith("reloaded")
        store.setLiveConnected(false)

        assertFalse(store.liveConnected)
        assertEquals("reloaded", store.document.value.global.entries.single().material)
    }

    @Test
    fun refreshFallback_afterDisconnectReloadsAgain() {
        var fallback = documentWith("Maple")
        val store = HiddenMaterialsStateStore { fallback }

        store.applyLive(documentWith("Walnut"))
        store.setLiveConnected(false)
        fallback = documentWith("second reload")
        store.refreshFallback()

        assertEquals("second reload", store.document.value.global.entries.single().material)
        assertFalse(store.liveConnected)
    }

    @Test
    fun applyLive_afterDisconnectReplacesFallbackAndReconnects() {
        var fallback = documentWith("Maple")
        val store = HiddenMaterialsStateStore { fallback }

        store.applyLive(documentWith("Walnut"))
        store.setLiveConnected(false)
        store.applyLive(documentWith("live again"))

        assertEquals("live again", store.document.value.global.entries.single().material)
        assertTrue(store.liveConnected)
    }

    /**
     * Regression test for the atomicity fix in DeliveryScheduleStateStore, which this class
     * mirrors: a slow refreshFallback that is already mid-flight when a live update lands must
     * not clobber the live document once it finally commits.
     */
    @Test
    fun refreshFallback_doesNotOverwriteDocumentFromConcurrentApplyLive() {
        val fallbackThreadStartedLoading = CountDownLatch(1)
        val liveUpdateApplied = CountDownLatch(1)
        val fallback = documentWith("Maple")

        val store = HiddenMaterialsStateStore {
            if (Thread.currentThread().name == "fallback-refresh-thread") {
                fallbackThreadStartedLoading.countDown()
                assertTrue(liveUpdateApplied.await(5, TimeUnit.SECONDS))
            }
            fallback
        }

        val fallbackThread = Thread({ store.refreshFallback() }, "fallback-refresh-thread")
        fallbackThread.start()

        assertTrue(fallbackThreadStartedLoading.await(5, TimeUnit.SECONDS))
        store.applyLive(documentWith("live"))
        liveUpdateApplied.countDown()
        fallbackThread.join(5000)

        assertEquals("live", store.document.value.global.entries.single().material)
        assertTrue(store.liveConnected)
    }
}
