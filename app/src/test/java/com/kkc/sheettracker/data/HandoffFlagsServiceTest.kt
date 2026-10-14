package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HandoffCount
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffItemFlags
import com.kkc.sheettracker.data.models.HandoffTruck
import com.kkc.sheettracker.data.models.HandoffTruckListEntry
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit

class HandoffFlagsServiceTest {
    private val ref = HandoffFlagRef("board_stock", "5f0c")
    private val now = Instant.parse("2026-10-08T14:00:00Z")

    // Every REST scope and server a test opens is torn down here, even when an assertion fails.
    private val restJobs = mutableListOf<CompletableJob>()
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        restJobs.forEach { it.cancel() }
        servers.forEach { runCatching { it.shutdown() } }
    }

    private fun restScope(extra: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext): Pair<CompletableJob, CoroutineScope> {
        val job = SupervisorJob().also { restJobs += it }
        return job to CoroutineScope(job + Dispatchers.IO + extra)
    }

    private fun server(): MockWebServer = MockWebServer().apply { start() }.also { servers += it }

    @Test
    fun offlineAutoNeverClaimsShipLoose() {
        val d = handoffTruckDisplay(ref, HandoffFlagsSnapshot())
        assertEquals("Auto", d.label)
        assertTrue(d.isAuto)
        assertNull(d.shipsLoose)
        assertNull(d.selected)
    }

    @Test
    fun onlineAutoShowsEffectivePlacement() {
        val list = mapOf(ref to HandoffTruckListEntry(ref, HandoffTruck.LOOSE, HandoffTruck.LOOSE, null, excluded = false))
        val d = handoffTruckDisplay(ref, HandoffFlagsSnapshot(truckList = list))
        assertEquals("Auto · Ship loose", d.label)
        assertEquals(true, d.shipsLoose)
    }

    @Test
    fun excludedRowSaysSo() {
        val list = mapOf(ref to HandoffTruckListEntry(ref, HandoffTruck.LOOSE, null, null, excluded = true))
        assertEquals("Auto · Excluded by office", handoffTruckDisplay(ref, HandoffFlagsSnapshot(truckList = list)).label)
    }

    @Test
    fun pinnedExcludedRowShowsThePinAndSaysExcluded() {
        val list = mapOf(ref to HandoffTruckListEntry(ref, HandoffTruck.SHOP, null, null, excluded = true))
        val d = handoffTruckDisplay(
            ref,
            HandoffFlagsSnapshot(stored = mapOf(ref to HandoffItemFlags(HandoffTruck.LOOSE)), truckList = list)
        )
        assertEquals("Ship loose · Excluded by office", d.label)
        assertEquals(HandoffTruck.LOOSE, d.selected)
        assertFalse(d.isAuto)
        assertEquals(false, d.shipsLoose)
        // A pin on a row that is not excluded is unchanged.
        val plain = list.mapValues { it.value.copy(excluded = false) }
        assertEquals(
            "Ship loose",
            handoffTruckDisplay(ref, HandoffFlagsSnapshot(stored = mapOf(ref to HandoffItemFlags(HandoffTruck.LOOSE)), truckList = plain)).label
        )
    }

    @Test
    fun storedFlagAndReadOnlyCount() {
        val d = handoffTruckDisplay(ref, HandoffFlagsSnapshot(stored = mapOf(ref to HandoffItemFlags(HandoffTruck.SHOP, HandoffCount.LINE))))
        assertEquals("Shop install", d.label)
        assertEquals(HandoffTruck.SHOP, d.selected)
        assertEquals(false, d.shipsLoose)
        assertEquals("Count by line", d.countLabel)
    }

    @Test
    fun pendingOverridesStoredIncludingBackToAuto() {
        val snapshot = HandoffFlagsSnapshot(
            stored = mapOf(ref to HandoffItemFlags(HandoffTruck.LOOSE)),
            pending = mapOf(ref to PendingTruckFlag(null, now))
        )
        val d = handoffTruckDisplay(ref, snapshot)
        assertTrue(d.isPending)
        assertTrue(d.isAuto)
        assertEquals("Auto", d.label)
    }

    @Test
    fun autoRightAfterTapBackUsesAutoTruckNotStaleEffective() {
        val list = mapOf(ref to HandoffTruckListEntry(ref, HandoffTruck.LOOSE, HandoffTruck.LATER, null, excluded = false))
        val snapshot = HandoffFlagsSnapshot(
            stored = mapOf(ref to HandoffItemFlags(HandoffTruck.LATER)),
            pending = mapOf(ref to PendingTruckFlag(null, now)),
            truckList = list
        )
        val d = handoffTruckDisplay(ref, snapshot)
        assertEquals("Auto · Ship loose", d.label)
        assertEquals(true, d.shipsLoose)
        assertTrue(d.isAuto)
        assertTrue(d.isPending)
    }

    @Test
    fun throwingClientProviderDoesNotCrashOrLoseTheTap() = runBlocking {
        val base = liveBase("670 - E", "handoff-svc-throw")
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, t -> uncaught += t }
        val (restJob, scope) = restScope(handler)
        val service = HandoffFlagsService(
            baseDir = base,
            tabletId = "T1",
            clientProvider = { throw IllegalStateException("datastore boom") },
            restScope = scope
        )
        assertEquals(HandoffFlagWriteResult.Written, service.setTruck("670 - E", ref, HandoffTruck.SHOP))
        // Let the launched REST body run and fail.
        restJob.children.toList().forEach { it.join() }
        assertTrue("uncaught: $uncaught", uncaught.isEmpty())
        assertNull(service.fetchTruckList("670 - E"))
        assertEquals(HandoffTruck.SHOP, service.loadLocal("670 - E").pending.getValue(ref).truck)
    }

    @Test
    fun setTruckWritesSidecarThenPutsAndSkipBumpsSignal() = runBlocking {
        val server = server()
        server.enqueue(MockResponse().setBody("""{"source":"board_stock","id":"5f0c","flags":null,"skipped":true}"""))
        val job = "670 - E"
        val base = liveBase(job, "handoff-svc")
        val (restJob, scope) = restScope()
        val service = HandoffFlagsService(
            baseDir = base,
            tabletId = "T1",
            clientProvider = { HandoffFlagsClient(server.url("/").toString()) },
            restScope = scope,
            clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)
        )
        val before = HandoffFlagsChangeSignal.version.value
        assertEquals(HandoffFlagWriteResult.Written, service.setTruck(job, ref, HandoffTruck.LOOSE))
        val sidecar = JSONObject(File(base, "$job/.metadata/admin/handoff_flags_request.T1.json").readText())
        val written = sidecar.getJSONArray("requests").getJSONObject(0)
        val put = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        // Same requestedAt in both writes.
        assertEquals(written.getString("requestedAt"), put.getString("requestedAt"))
        assertEquals(now.toString(), put.getString("requestedAt"))
        restJob.children.toList().forEach { it.join() }
        assertEquals(before + 2, HandoffFlagsChangeSignal.version.value) // sidecar write + skipped refresh
    }

    @Test
    fun appliedReplyDoesNotBumpTheSignalAgain() = runBlocking {
        val server = server()
        server.enqueue(MockResponse().setBody("""{"source":"board_stock","id":"5f0c","flags":{"truck":"loose"},"skipped":false}"""))
        val job = "670 - E"
        val (restJob, scope) = restScope()
        val service = HandoffFlagsService(
            liveBase(job, "handoff-svc-applied"), "T1",
            clientProvider = { HandoffFlagsClient(server.url("/").toString()) },
            restScope = scope
        )
        val before = HandoffFlagsChangeSignal.version.value
        assertEquals(HandoffFlagWriteResult.Written, service.setTruck(job, ref, HandoffTruck.LOOSE))
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        restJob.children.toList().forEach { it.join() }
        assertEquals("only the sidecar write bumps", before + 1, HandoffFlagsChangeSignal.version.value)
    }

    @Test
    fun rejectedBodyIsLoggedWithoutCrashOrExtraBump() = runBlocking {
        val server = server()
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(MockResponse().setResponseCode(422))
        val job = "670 - E"
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, t -> uncaught += t }
        val (restJob, scope) = restScope(handler)
        val service = HandoffFlagsService(
            liveBase(job, "handoff-svc-reject"), "T1",
            clientProvider = { HandoffFlagsClient(server.url("/").toString()) },
            restScope = scope
        )
        val before = HandoffFlagsChangeSignal.version.value
        assertEquals(HandoffFlagWriteResult.Written, service.setTruck(job, ref, HandoffTruck.LOOSE))
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        restJob.children.toList().forEach { it.join() }
        assertEquals(HandoffFlagWriteResult.Written, service.setTruck(job, ref, HandoffTruck.SHOP))
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        restJob.children.toList().forEach { it.join() }
        assertTrue("uncaught: $uncaught", uncaught.isEmpty())
        assertEquals("two sidecar writes, nothing else", before + 2, HandoffFlagsChangeSignal.version.value)
        assertEquals(HandoffTruck.SHOP, service.loadLocal(job).pending.getValue(ref).truck)
    }

    @Test
    fun setTruckWithNoServerStillWritesSidecar() = runBlocking {
        val base = liveBase("670 - E", "handoff-svc-offline")
        val service = HandoffFlagsService(base, "T1", clientProvider = { null }, restScope = restScope().second)
        assertEquals(HandoffFlagWriteResult.Written, service.setTruck("670 - E", ref, HandoffTruck.LATER))
        val snapshot = service.loadLocal("670 - E")
        assertEquals(HandoffTruck.LATER, snapshot.pending.getValue(ref).truck)
        assertEquals(HandoffFlagWriteResult.Invalid, service.setTruck("670 - E", HandoffFlagRef("bogus", "x"), HandoffTruck.LATER))
    }

    @Test
    fun loadLocalDropsPendingOnceHandoffJsonShowsItApplied() = runBlocking {
        val job = "670 - E"
        val base = liveBase(job, "handoff-svc-setat")
        val service = HandoffFlagsService(
            base, "T1", clientProvider = { null }, restScope = restScope().second,
            clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)
        )
        assertEquals(HandoffFlagWriteResult.Written, service.setTruck(job, ref, HandoffTruck.LOOSE))
        assertEquals(setOf(ref), service.loadLocal(job).pending.keys)
        val admin = File(base, "$job/.metadata/admin")
        File(admin, "handoff.json").writeText(
            """{"itemFlags":{"board_stock:5f0c":{"truck":"shop"}},"itemFlagsSetAt":{"board_stock:5f0c":{"truck":"${now.plusSeconds(5)}"}}}"""
        )
        val snapshot = service.loadLocal(job)
        assertTrue("office changed it after the tap", snapshot.pending.isEmpty())
        assertEquals(HandoffTruck.SHOP, snapshot.stored.getValue(ref).truck)
    }

    @Test
    fun readOnlyServiceRefusesWithoutSignalOrPut() = runBlocking {
        val server = server()
        val job = "670 - E"
        val service = HandoffFlagsService(
            liveBase(job, "handoff-svc-ro"), "T1",
            clientProvider = { HandoffFlagsClient(server.url("/").toString()) },
            restScope = restScope().second,
            readOnly = true
        )
        val before = HandoffFlagsChangeSignal.version.value
        assertEquals(HandoffFlagWriteResult.ReadOnly, service.setTruck(job, ref, HandoffTruck.LOOSE))
        Thread.sleep(200)
        assertEquals(0, server.requestCount)
        assertEquals(before, HandoffFlagsChangeSignal.version.value)
    }

    @Test
    fun missingJobSendsNoPutDoesNotBumpSignalAndCreatesNoFolder() = runBlocking {
        val server = server()
        server.enqueue(MockResponse().setBody("""{"source":"board_stock","id":"5f0c","flags":null}"""))
        val base = Files.createTempDirectory("handoff-svc-gone").toFile()
        val service = HandoffFlagsService(
            baseDir = base,
            tabletId = "T1",
            clientProvider = { HandoffFlagsClient(server.url("/").toString()) },
            restScope = restScope().second,
            clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)
        )
        val before = HandoffFlagsChangeSignal.version.value
        assertEquals(HandoffFlagWriteResult.JobMissing, service.setTruck("670 - GONE", ref, HandoffTruck.LOOSE))
        Thread.sleep(300)
        assertEquals(0, server.requestCount)
        assertEquals(before, HandoffFlagsChangeSignal.version.value)
        assertFalse(File(base, "670 - GONE").exists())
    }

    @Test
    fun invalidRefSendsNoPutAndDoesNotBumpSignal() = runBlocking {
        val server = server()
        val base = liveBase("670 - E", "handoff-svc-invalid")
        val service = HandoffFlagsService(
            base, "T1",
            clientProvider = { HandoffFlagsClient(server.url("/").toString()) },
            restScope = restScope().second
        )
        val before = HandoffFlagsChangeSignal.version.value
        assertEquals(HandoffFlagWriteResult.Invalid, service.setTruck("670 - E", HandoffFlagRef("bogus", "x"), HandoffTruck.LATER))
        Thread.sleep(300)
        assertEquals(0, server.requestCount)
        assertEquals(before, HandoffFlagsChangeSignal.version.value)
    }

    @Test
    fun storageUnavailableSendsNoPutAndDoesNotBumpSignal() = runBlocking {
        val server = server()
        val job = "670 - E"
        val base = liveBase(job, "handoff-svc-storage")
        // A directory at the sidecar path makes the read fail.
        File(base, "$job/.metadata/admin/handoff_flags_request.T1.json").mkdirs()
        val service = HandoffFlagsService(
            base, "T1",
            clientProvider = { HandoffFlagsClient(server.url("/").toString()) },
            restScope = restScope().second
        )
        val before = HandoffFlagsChangeSignal.version.value
        assertEquals(HandoffFlagWriteResult.StorageUnavailable, service.setTruck(job, ref, HandoffTruck.LATER))
        Thread.sleep(300)
        assertEquals(0, server.requestCount)
        assertEquals(before, HandoffFlagsChangeSignal.version.value)
    }

    @Test
    fun userMessagesAreExact() {
        assertNull(HandoffFlagWriteResult.Written.userMessage())
        assertEquals("This job is no longer in Ready Jobs", HandoffFlagWriteResult.JobMissing.userMessage())
        assertEquals("Couldn't save on this tablet. Try again.", HandoffFlagWriteResult.StorageUnavailable.userMessage())
        assertEquals("Couldn't save this change.", HandoffFlagWriteResult.Invalid.userMessage())
        assertEquals("This screen is read-only", HandoffFlagWriteResult.ReadOnly.userMessage())
    }

    private fun liveBase(job: String, prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { File(it, "$job/.metadata").mkdirs() }
}
