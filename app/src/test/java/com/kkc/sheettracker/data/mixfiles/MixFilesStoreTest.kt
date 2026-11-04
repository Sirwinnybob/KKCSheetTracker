package com.kkc.sheettracker.data.mixfiles

import com.kkc.sheettracker.data.cnc.CncMachines
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class MixFilesStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val key = MaterialKey("scm", "Job / #1", "Maple ? 3/4")
    private val first = MixFilesCatalog(key.job, key.material, "folder-1", emptyList())
    private val second = first.copy(folderRevision = "folder-2")
    private fun cache() = MixFilesCatalogCache(temp.root)
    private fun TestScope.store(api: FakeApi) = MixFilesStore(key, api, cache(), backgroundScope)

    @Test fun firstAcquireFetchesCatalogAndPublishesLive() = runTest(StandardTestDispatcher()) {
        val api = FakeApi(first)
        val store = store(api)
        assertEquals(CatalogState.Loading, store.state.value)
        advanceTimeBy(9_000); runCurrent()
        assertEquals(0, api.catalogCalls)
        val lease = store.acquire(); runCurrent()
        assertEquals(CatalogState.Live(first), store.state.value)
        assertEquals(listOf(key.job to key.material), api.catalogArguments)
        lease.close()
    }

    @Test fun unchangedRevisionDoesNotRefetchCatalog() = runTest(StandardTestDispatcher()) {
        val api = FakeApi(first)
        val lease = store(api).acquire(); runCurrent()
        repeat(3) { advanceTimeBy(3_000); runCurrent() }
        assertEquals(1, api.catalogCalls)
        assertEquals(3, api.revisionCalls)
        assertEquals(List(3) { key.job to key.material }, api.revisionArguments)
        lease.close()
    }

    @Test fun changedRevisionRefetches() = runTest(StandardTestDispatcher()) {
        val api = FakeApi(first)
        val store = store(api)
        val lease = store.acquire(); runCurrent()
        api.catalogResult = MixFilesResult.Ok(second)
        api.revisionResult = MixFilesResult.Ok("folder-2")
        advanceTimeBy(3_000); runCurrent()
        assertEquals(2, api.catalogCalls)
        assertEquals(CatalogState.Live(second), store.state.value)
        lease.close()
    }

    @Test fun lastLeaseClosedStopsPolling() = runTest(StandardTestDispatcher()) {
        val api = FakeApi(first)
        val store = store(api)
        val a = store.acquire(); val b = store.acquire(); runCurrent()
        a.close(); a.close()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, api.revisionCalls)
        b.close()
        advanceTimeBy(12_000); runCurrent()
        assertEquals(1, api.revisionCalls)
        assertEquals(1, api.catalogCalls)
    }

    @Test fun offlineKeepsLastCatalog() = runTest(StandardTestDispatcher()) {
        val api = FakeApi(first)
        val store = store(api)
        val lease = store.acquire(); runCurrent()
        api.revisionResult = MixFilesResult.Offline
        advanceTimeBy(3_000); runCurrent()
        assertEquals(CatalogState.Live(first), store.state.value)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(CatalogState.Live(first), store.state.value)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(CatalogState.Offline(first), store.state.value)
        api.revisionResult = MixFilesResult.Ok("folder-1")
        advanceTimeBy(3_000); runCurrent()
        assertEquals(CatalogState.Live(first), store.state.value)
        assertEquals(1, api.catalogCalls)
        lease.close()
    }

    @Test fun cachedCatalogShownBeforeFirstResponse() = runTest(StandardTestDispatcher()) {
        cache().write(key, first)
        val response = CompletableDeferred<MixFilesResult<MixFilesCatalog>>()
        val api = FakeApi(second).apply { catalogAction = { response.await() } }
        val store = store(api)
        val lease = store.acquire(); runCurrent()
        assertEquals(CatalogState.Offline(first), store.state.value)
        response.complete(MixFilesResult.Ok(second)); runCurrent()
        assertEquals(CatalogState.Live(second), store.state.value)
        assertEquals(second, cache().read(key))
        lease.close()
    }

    @Test fun successfulPollResetsOfflineGrace() = runTest(StandardTestDispatcher()) {
        val api = FakeApi(first)
        val store = store(api)
        val lease = store.acquire(); runCurrent()
        api.revisionResult = MixFilesResult.Offline
        repeat(2) { advanceTimeBy(3_000); runCurrent() }
        api.revisionResult = MixFilesResult.Ok(first.folderRevision)
        advanceTimeBy(3_000); runCurrent()
        api.revisionResult = MixFilesResult.Offline
        repeat(2) { advanceTimeBy(3_000); runCurrent() }
        assertEquals(CatalogState.Live(first), store.state.value)
        lease.close()
    }

    @Test fun changedRevisionWithRepeatedCatalogFailuresEventuallyGoesOffline() = runTest(StandardTestDispatcher()) {
        val api = FakeApi(first)
        val store = store(api)
        val lease = store.acquire(); runCurrent()
        api.revisionResult = MixFilesResult.Ok(second.folderRevision)
        api.catalogResult = MixFilesResult.Offline
        repeat(3) { advanceTimeBy(3_000); runCurrent() }
        assertEquals(CatalogState.Offline(first), store.state.value)
        lease.close()
    }

    @Test fun outdatedServiceIsOutdatedState() = runTest(StandardTestDispatcher()) {
        cache().write(key, first)
        val api = FakeApi(first).apply { catalogResult = MixFilesResult.ServiceOutdated }
        val store = store(api)
        val lease = store.acquire(); runCurrent()
        assertEquals(CatalogState.Outdated(first), store.state.value)
        lease.close()
    }

    @Test fun publishReplacesStateWithoutFetch() = runTest(StandardTestDispatcher()) {
        val api = FakeApi(first)
        val store = store(api)
        store.publish(second)
        assertEquals(CatalogState.Live(second), store.state.value)
        runCurrent()
        assertEquals(second, cache().read(key))
        assertEquals(0, api.catalogCalls)
    }

    @Test fun oneRequestInFlight() = runTest(StandardTestDispatcher()) {
        val api = FakeApi(first).apply { catalogAction = { delay(10_000); MixFilesResult.Ok(first) } }
        val lease = store(api).acquire(); runCurrent()
        advanceTimeBy(9_000); runCurrent()
        assertEquals(1, api.catalogCalls)
        assertEquals(0, api.revisionCalls)
        assertEquals(1, api.maximumInFlight)
        lease.close()
    }

    @Test fun refreshNowAlwaysFetchesAndSerializesWithPolls() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi(first).apply { catalogAction = { gate.await(); MixFilesResult.Ok(first) } }
        val store = store(api)
        val lease = store.acquire(); runCurrent()
        val refresh = launch { store.refreshNow() }; runCurrent()
        advanceTimeBy(6_000); runCurrent()
        assertEquals(1, api.catalogCalls)
        gate.complete(Unit); runCurrent(); refresh.join()
        assertEquals(2, api.catalogCalls)
        assertEquals(1, api.maximumInFlight)
        lease.close()
    }

    @Test fun publishWinsAgainstInFlightCatalogAndFailure() = runTest(StandardTestDispatcher()) {
        for (stale in listOf(MixFilesResult.Ok(first), MixFilesResult.Offline, MixFilesResult.ServiceOutdated)) {
            val response = CompletableDeferred<MixFilesResult<MixFilesCatalog>>()
            val api = FakeApi(first).apply { catalogAction = { response.await() } }
            val store = store(api)
            val lease = store.acquire(); runCurrent()
            store.publish(second); runCurrent()
            response.complete(stale); runCurrent()
            assertEquals(CatalogState.Live(second), store.state.value)
            assertEquals(second, cache().read(key))
            lease.close()
        }
    }

    @Test fun publishWinsAgainstInFlightRevision() = runTest(StandardTestDispatcher()) {
        val response = CompletableDeferred<MixFilesResult<String>>()
        val api = FakeApi(first).apply { revisionAction = { response.await() } }
        val store = store(api)
        val lease = store.acquire(); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        store.publish(second)
        response.complete(MixFilesResult.Ok("folder-1")); runCurrent()
        assertEquals(CatalogState.Live(second), store.state.value)
        assertEquals(1, api.catalogCalls)
        lease.close()
    }

    @Test fun oldGenerationCannotPublishAfterCloseAndReacquire() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi(first).apply {
            catalogAction = {
                if (catalogCalls == 1) withContext(NonCancellable) { gate.await(); MixFilesResult.Ok(first) }
                else MixFilesResult.Offline
            }
        }
        val store = store(api)
        val old = store.acquire(); runCurrent(); old.close()
        val current = store.acquire(); runCurrent(); old.close()
        assertEquals(1, api.catalogCalls)
        gate.complete(Unit); runCurrent()
        assertEquals(CatalogState.Offline(null), store.state.value)
        assertNull(cache().read(key))
        assertEquals(1, api.maximumInFlight)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, api.revisionCalls)
        current.close()
    }

    @Test fun notFoundCatalogIsNoFolderAndOtherRejectionsRetainCatalog() = runTest(StandardTestDispatcher()) {
        val api = FakeApi(first)
        val store = store(api)
        val lease = store.acquire(); runCurrent()
        api.catalogResult = rejected("not_found")
        store.refreshNow()
        assertEquals(CatalogState.NoFolder, store.state.value)
        api.catalogResult = rejected("other")
        store.refreshNow()
        assertEquals(CatalogState.Offline(first), store.state.value)
        lease.close()
    }

    @Test fun registrySharesOneStorePerMaterialEvenWithConcurrentLookups() = runTest(StandardTestDispatcher()) {
        val registry = MixFilesStoreRegistry({ FakeApi(first) }, cache(), backgroundScope)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val stores = executor.invokeAll(List(32) { Callable { registry.store(CncMachines.scm, key.job, key.material) } }).map { it.get() }
            stores.forEach { assertSame(stores.first(), it) }
            assertNotSame(stores.first(), registry.store(CncMachines.scm, "other", key.material))
            assertNotSame(stores.first(), registry.store(CncMachines.scm, key.job, "other"))
            assertNotSame(stores.first(), registry.store(CncMachines.scm.copy(id = "other"), key.job, key.material))
        } finally { executor.shutdownNow() }
    }

    @Test fun cacheRoundTripsOpaqueRawCatalogAndKeepsMaterialKeysSeparate() {
        val entry = MixFileEntry("../Å/part.pgm", "Å? #1", linkedMapOf("X" to " 01.000 ", "z" to "a\\b\"c"), " ;001; ", 2, 1, false, true)
        val row = MixFileRow("interrupted.frz", MixFileKind.FRZ, null, null, "opaque:r1", "other.mix", true, null, "2026-10-30", listOf(entry))
        val catalog = first.copy(files = listOf(row, row.copy(file = "backup.rev2", kind = MixFileKind.REVISION, revisionOf = "other.mix", revisionNumber = 2)))
        val cache = cache()
        cache.write(key, catalog)
        cache.write(MaterialKey("scm", "a/b", "c"), second)
        cache.write(MaterialKey("scm", "a", "b/c"), first)
        assertEquals(catalog, cache.read(key))
        assertEquals(second, cache.read(MaterialKey("scm", "a/b", "c")))
        assertEquals(first, cache.read(MaterialKey("scm", "a", "b/c")))
        val files = temp.root.listFiles()!!
        assertEquals(3, files.size)
        assertTrue(files.all { it.name.matches(Regex("[a-f0-9]{40}\\.json")) })
        assertTrue(files.all { it.readText().contains("\"folderRevision\"") })
    }

    @Test fun identicalNamesOnTwoMachinesUseSeparateStoresAndCaches() = runTest(StandardTestDispatcher()) {
        val redCatalog = first.copy(folderRevision = "red")
        val blueCatalog = first.copy(folderRevision = "blue")
        val apis = mapOf(CncMachines.scm.id to FakeApi(redCatalog), CncMachines.scmNew.id to FakeApi(blueCatalog))
        val registry = MixFilesStoreRegistry({ apis.getValue(it.id) }, cache(), backgroundScope)
        val red = registry.store(CncMachines.scm, key.job, key.material)
        val blue = registry.store(CncMachines.scmNew, key.job, key.material)
        assertNotSame(red, blue)
        val leases = listOf(red.acquire(), blue.acquire()); runCurrent()
        assertEquals(CatalogState.Live(redCatalog), red.state.value)
        assertEquals(CatalogState.Live(blueCatalog), blue.state.value)
        runCurrent()
        assertEquals(redCatalog, cache().read(MaterialKey("scm", key.job, key.material)))
        assertEquals(blueCatalog, cache().read(MaterialKey("scm-new", key.job, key.material)))
        // One machine going offline never changes the other's live state.
        apis.getValue("scm-new").revisionResult = MixFilesResult.Offline
        apis.getValue("scm-new").catalogResult = MixFilesResult.Offline
        repeat(3) { advanceTimeBy(3_000); runCurrent() }
        assertEquals(CatalogState.Offline(blueCatalog), blue.state.value)
        assertEquals(CatalogState.Live(redCatalog), red.state.value)
        leases.forEach { it.close() }
    }

    @Test fun redCacheFileNameIsUnchangedSoPersistedCatalogsStayValid() {
        cache().write(key, first)
        assertEquals("9072c0a9d52a4d3cd3ed16f8432366d553ac6694.json", temp.root.listFiles()!!.single().name)
    }

    private class RecordingApi : MixFilesApi {
        val calls = mutableListOf<String>()
        private val ok = MixFilesResult.Ok(MixFilesCatalog("J", "M", "r", emptyList()))
        override suspend fun catalog(job: String, material: String) = ok.also { calls += "catalog" }
        override suspend fun folderRevision(job: String, material: String) = MixFilesResult.Ok("r").also { calls += "revision" }
        override suspend fun write(job: String, material: String, file: String, expectedRevision: String?, entries: List<EntrySpec>, completion: Completion?) = ok.also { calls += "write" }
        override suspend fun frz(job: String, material: String, file: String, expectedRevision: String, completion: Completion, pending: List<EntrySpec>) = ok.also { calls += "frz" }
        override suspend fun rename(job: String, material: String, file: String, newFile: String, expectedRevision: String) = ok.also { calls += "rename" }
        override suspend fun delete(job: String, material: String, file: String, expectedRevision: String) = ok.also { calls += "delete" }
        override suspend fun deleteRevision(job: String, material: String, file: String, expectedRevision: String) = ok.also { calls += "deleteRevision" }
        override suspend fun restore(job: String, material: String, revisionFile: String, targetFile: String, expectedTargetRevision: String?, completion: Completion?) = ok.also { calls += "restore" }
        override suspend fun split(job: String, material: String, file: String, expectedRevision: String, entries: List<EntrySpec>, newMixes: List<NewMix>, completion: Completion?) = ok.also { calls += "split" }
        override suspend fun features() = emptySet<String>()
    }
    private fun guarded(machine: com.kkc.sheettracker.data.cnc.CncMachine, status: com.kkc.sheettracker.data.cnc.MixServiceStatus?, api: RecordingApi) =
        MachineGuardedMixFilesApi(machine, api) { status }
    private fun blueStatus(id: String? = "scm-new", readOnly: Boolean = false) =
        com.kkc.sheettracker.data.cnc.MixServiceStatus(emptySet(), id, "SCM $id", setOf("MIX_FILES", "FRZ_EDITING", "SECOND_PASS"), readOnly)
    private fun MixFilesResult<*>.code() = (this as MixFilesResult.Rejected).error.code

    @Test fun guardRefusesMutationsForMismatchedReadOnlyOrUnverifiedService() = runTest {
        listOf(blueStatus("scm") to "machine_mismatch", blueStatus(readOnly = true) to "machine_read_only",
            null to "machine_unverified", blueStatus(id = null) to "machine_unverified").forEach { (status, code) ->
            val api = RecordingApi()
            val guard = guarded(CncMachines.scmNew, status, api)
            assertEquals(code, guard.write("J", "M", "A.mix", "r", emptyList(), null).code())
            assertEquals(code, guard.rename("J", "M", "A.mix", "B.mix", "r").code())
            assertEquals(code, guard.delete("J", "M", "A.mix", "r").code())
            assertTrue((guard.write("J", "M", "A.mix", "r", emptyList(), null) as MixFilesResult.Rejected).error.message.isNotBlank())
            assertTrue(guard.catalog("J", "M") is MixFilesResult.Ok) // Reads are never blocked.
            assertEquals(listOf("catalog"), api.calls)
        }
    }

    @Test fun guardPassesVerifiedMutationsAndOldRedServices() = runTest {
        val blueApi = RecordingApi()
        val blue = guarded(CncMachines.scmNew, blueStatus(), blueApi)
        assertTrue(blue.write("J", "M", "A.mix", "r", emptyList(), null) is MixFilesResult.Ok)
        assertTrue(blue.split("J", "M", "A.mix", "r", emptyList(), emptyList(), null) is MixFilesResult.Ok)
        assertEquals(listOf("write", "split"), blueApi.calls)
        val redApi = RecordingApi()
        val red = guarded(CncMachines.scm, com.kkc.sheettracker.data.cnc.MixServiceStatus(setOf("pgm-edits-revert")), redApi)
        assertTrue(red.frz("J", "M", "A.frz", "r", Completion(emptyList(), emptyMap(), null), emptyList()) is MixFilesResult.Ok)
        assertEquals(listOf("frz"), redApi.calls)
    }

    @Test fun guardRefusesEveryFrzActionOnBlueWithoutNetwork() = runTest {
        val api = RecordingApi()
        var statusReads = 0
        val guard = MachineGuardedMixFilesApi(CncMachines.scmNew, api) { statusReads++; blueStatus() }
        val completion = Completion(emptyList(), emptyMap(), null)
        listOf(
            guard.frz("J", "M", "A.frz", "r", completion, emptyList()),
            guard.write("J", "M", "A.mix", "r", emptyList(), completion),
            guard.split("J", "M", "A.mix", "r", emptyList(), emptyList(), completion),
            guard.restore("J", "M", "OLD1_A.frz", "A.frz", null, null),
            guard.restore("J", "M", "OLD1_A.mix", "A.mix", null, completion),
            guard.rename("J", "M", "A.FRZ", "B.frz", "r"),
            guard.delete("J", "M", "A.frz", "r"),
            guard.deleteRevision("J", "M", "OLD2_A.frz", "r"),
        ).forEach { assertEquals("frz_unsupported", it.code()) }
        assertTrue(api.calls.isEmpty())
        assertEquals(0, statusReads)
    }

    @Test fun corruptCacheIsIgnoredAndReplacementLeavesNoTemporaryFiles() {
        val cache = cache()
        assertNull(cache.read(key))
        cache.write(key, first)
        temp.root.listFiles()!!.single().writeText("{bad")
        assertNull(cache.read(key))
        cache.write(key, second)
        assertEquals(second, cache.read(key))
        assertEquals(1, temp.root.listFiles()!!.size)
    }

    private fun rejected(code: String) = MixFilesResult.Rejected(MixFilesError(404, code, "Missing", null, emptyList(), null, null, null, null))

    private class FakeApi(catalog: MixFilesCatalog) : MixFilesApi {
        var catalogResult: MixFilesResult<MixFilesCatalog> = MixFilesResult.Ok(catalog)
        var revisionResult: MixFilesResult<String> = MixFilesResult.Ok(catalog.folderRevision)
        var catalogAction: (suspend () -> MixFilesResult<MixFilesCatalog>)? = null
        var revisionAction: (suspend () -> MixFilesResult<String>)? = null
        var catalogCalls = 0
        var revisionCalls = 0
        var inFlight = 0
        var maximumInFlight = 0
        val catalogArguments = mutableListOf<Pair<String, String>>()
        val revisionArguments = mutableListOf<Pair<String, String>>()
        private suspend fun <T> request(action: suspend () -> T): T {
            inFlight++; maximumInFlight = maxOf(maximumInFlight, inFlight)
            return try { action() } finally { inFlight-- }
        }
        override suspend fun catalog(job: String, material: String) = request {
            catalogCalls++; catalogArguments += job to material
            catalogAction?.invoke() ?: catalogResult
        }
        override suspend fun folderRevision(job: String, material: String) = request {
            revisionCalls++; revisionArguments += job to material
            revisionAction?.invoke() ?: revisionResult
        }
        override suspend fun write(job: String, material: String, file: String, expectedRevision: String?, entries: List<EntrySpec>, completion: Completion?) = error("unused")
        override suspend fun frz(job: String, material: String, file: String, expectedRevision: String, completion: Completion, pending: List<EntrySpec>) = error("unused")
        override suspend fun rename(job: String, material: String, file: String, newFile: String, expectedRevision: String) = error("unused")
        override suspend fun delete(job: String, material: String, file: String, expectedRevision: String) = error("unused")
        override suspend fun deleteRevision(job: String, material: String, file: String, expectedRevision: String) = error("unused")
        override suspend fun restore(job: String, material: String, revisionFile: String, targetFile: String, expectedTargetRevision: String?, completion: Completion?) = error("unused")
        override suspend fun split(job: String, material: String, file: String, expectedRevision: String, entries: List<EntrySpec>, newMixes: List<NewMix>, completion: Completion?) = error("unused")
        override suspend fun features() = emptySet<String>()
    }
}
