package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialEntry
import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import com.kkc.sheettracker.data.models.HiddenMaterialsGlobalDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenMaterialsLifecycleGateTest {

    private fun documentWith(material: String): HiddenMaterialsDocument =
        HiddenMaterialsDocument(
            global = HiddenMaterialsGlobalDoc(
                entries = listOf(HiddenMaterialEntry(docType = "NAILER_CUT_LIST", material = material))
            )
        )

    @Test
    fun startTokenBecomesStaleAfterStopAndSourceReplacement() {
        val gate = HiddenMaterialsLifecycleGate()
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
        val gate = HiddenMaterialsLifecycleGate()
        val source = gate.bindSource()
        val cleanup = gate.dispose(source)

        assertTrue(gate.isCleanupCurrent(cleanup))

        gate.bindSource()
        assertFalse(gate.isCleanupCurrent(cleanup))
    }

    @Test
    fun staleClientDocumentAndConnectionCallbacksCannotMutateReplacementStore() {
        val gate = HiddenMaterialsLifecycleGate()
        val oldBinding = HiddenMaterialsClientBinding(gate)
        val store = HiddenMaterialsStateStore(
            initialDocument = documentWith("replacement"),
            fallbackLoader = { documentWith("fallback") }
        )
        val oldSource = gate.bindSource()
        oldBinding.bind(oldSource)
        val oldDocumentCallback = oldBinding.documentCallback(store)
        val oldConnectionCallback = oldBinding.connectionCallback(store)

        gate.dispose(oldSource)
        val replacementBinding = HiddenMaterialsClientBinding(gate)
        replacementBinding.bind(gate.bindSource())
        store.markLiveDisconnected()

        oldDocumentCallback(documentWith("stale"))
        oldConnectionCallback(true)

        assertEquals("replacement", store.document.value.global.entries.single().material)
        assertFalse(store.liveConnected)
    }

    @Test
    fun capturedCallbacksAfterStopCannotRestoreLiveStateOrReplaceFallback() {
        val gate = HiddenMaterialsLifecycleGate()
        val binding = HiddenMaterialsClientBinding(gate)
        val store = HiddenMaterialsStateStore(
            initialDocument = documentWith("initial"),
            fallbackLoader = { documentWith("fallback") }
        )
        val source = gate.bindSource()
        binding.bind(source)
        val documentCallback = binding.documentCallback(store)
        val connectionCallback = binding.connectionCallback(store)
        gate.begin(source)

        documentCallback(documentWith("live"))
        assertTrue(store.liveConnected)

        gate.stop(source)
        store.markLiveDisconnected()
        store.refreshFallback()
        documentCallback(documentWith("stale-after-stop"))
        connectionCallback(true)

        assertEquals("fallback", store.document.value.global.entries.single().material)
        assertFalse(store.liveConnected)
    }
}
