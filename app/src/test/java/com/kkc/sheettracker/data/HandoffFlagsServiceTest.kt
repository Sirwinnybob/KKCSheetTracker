package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HandoffCount
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffItemFlags
import com.kkc.sheettracker.data.models.HandoffTruck
import com.kkc.sheettracker.data.models.HandoffTruckListEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
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
        val base = Files.createTempDirectory("handoff-svc-throw").toFile()
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, t -> uncaught += t }
        val restJob = SupervisorJob()
        val service = HandoffFlagsService(
            baseDir = base,
            tabletId = "T1",
            clientProvider = { throw IllegalStateException("datastore boom") },
            restScope = CoroutineScope(restJob + Dispatchers.IO + handler)
        )
        assertTrue(service.setTruck("670 - E", ref, HandoffTruck.SHOP))
        // Let the launched REST body run and fail.
        restJob.children.toList().forEach { it.join() }
        assertTrue("uncaught: $uncaught", uncaught.isEmpty())
        assertNull(service.fetchTruckList("670 - E"))
        assertEquals(HandoffTruck.SHOP, service.loadLocal("670 - E").pending.getValue(ref).truck)
    }

    @Test
    fun setTruckWritesSidecarThenPutsAndSkipBumpsSignal() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody("""{"source":"board_stock","id":"5f0c","flags":null,"skipped":true}"""))
        val base = Files.createTempDirectory("handoff-svc").toFile()
        val job = "670 - E"
        val service = HandoffFlagsService(
            baseDir = base,
            tabletId = "T1",
            clientProvider = { HandoffFlagsClient(server.url("/").toString()) },
            restScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)
        )
        val before = HandoffFlagsChangeSignal.version.value
        assertTrue(service.setTruck(job, ref, HandoffTruck.LOOSE))
        val sidecar = JSONObject(File(base, "$job/.metadata/admin/handoff_flags_request.T1.json").readText())
        val written = sidecar.getJSONArray("requests").getJSONObject(0)
        val put = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
        // Same requestedAt in both writes.
        assertEquals(written.getString("requestedAt"), put.getString("requestedAt"))
        assertEquals(now.toString(), put.getString("requestedAt"))
        val deadline = System.currentTimeMillis() + 5_000
        while (HandoffFlagsChangeSignal.version.value < before + 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(HandoffFlagsChangeSignal.version.value >= before + 2) // sidecar write + skipped refresh
        server.shutdown()
    }

    @Test
    fun setTruckWithNoServerStillWritesSidecar() = runBlocking {
        val base = Files.createTempDirectory("handoff-svc-offline").toFile()
        val service = HandoffFlagsService(base, "T1", clientProvider = { null }, restScope = CoroutineScope(Dispatchers.IO))
        assertTrue(service.setTruck("670 - E", ref, HandoffTruck.LATER))
        val snapshot = service.loadLocal("670 - E")
        assertEquals(HandoffTruck.LATER, snapshot.pending.getValue(ref).truck)
        assertFalse(service.setTruck("670 - E", HandoffFlagRef("bogus", "x"), HandoffTruck.LATER))
    }
}
