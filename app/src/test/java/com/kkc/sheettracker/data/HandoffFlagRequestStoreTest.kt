package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffTruck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class HandoffFlagRequestStoreTest {
    private val job = "670 - ESCHRICH"
    private val ref = HandoffFlagRef("checklist", "c-123")

    /** A live job: `<job>/.metadata` already exists, as it does for every real Ready Jobs folder. */
    private fun liveBase(): File =
        Files.createTempDirectory("handoff-req").toFile().also { File(it, "$job/.metadata").mkdirs() }

    private fun setup(): Pair<File, HandoffFlagRequestStore> {
        val base = liveBase()
        return base to HandoffFlagRequestStore(base, "T1")
    }

    private fun sidecar(base: File) = File(base, "$job/.metadata/admin/handoff_flags_request.T1.json")
    private fun pendingClock(base: File) = File(sidecar(base).parentFile, sidecar(base).name + ".pending-clock")

    @Test
    fun writesExactContractShapeWithOnlyTheTouchedFlag() = runBlocking<Unit> {
        val (base, store) = setup()
        assertEquals(
            HandoffFlagWriteResult.Written,
            store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00.123Z")
        )
        val bytes = sidecar(base).readBytes()
        assertFalse("no BOM", bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte())
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(1, root.getInt("schemaVersion"))
        assertEquals("T1", root.getString("tabletId"))
        val req = root.getJSONArray("requests").getJSONObject(0)
        assertEquals(setOf("requestId", "requestedAt", "source", "id", "truck"), req.keys().asSequence().toSet())
        assertEquals("2026-10-08T14:00:00.123Z", req.getString("requestedAt"))
        assertEquals("checklist", req.getString("source"))
        assertEquals("c-123", req.getString("id"))
        assertEquals("loose", req.getString("truck"))
        Instant.parse(req.getString("requestedAt"))
    }

    @Test
    fun autoIsAnExplicitNull() = runBlocking {
        val (base, store) = setup()
        store.appendTruckRequest(job, ref, null, "2026-10-08T14:00:00Z")
        val req = JSONObject(sidecar(base).readText()).getJSONArray("requests").getJSONObject(0)
        assertTrue(req.has("truck"))
        assertTrue(req.isNull("truck"))
        assertTrue(sidecar(base).readText().contains("\"truck\":null"))
    }

    @Test
    fun appendsToExistingFileAndKeepsForeignEntries() = runBlocking {
        val (base, store) = setup()
        sidecar(base).apply { parentFile.mkdirs() }.writeText(
            """{"schemaVersion":1,"tabletId":"T1","requests":[{"requestId":"old","requestedAt":"2026-10-08T13:00:00Z","source":"board_stock","id":"x","count":"qty"}]}"""
        )
        store.appendTruckRequest(job, ref, HandoffTruck.SHOP, "2026-10-08T14:00:00Z")
        val requests = JSONObject(sidecar(base).readText()).getJSONArray("requests")
        assertEquals(2, requests.length())
        assertEquals("qty", requests.getJSONObject(0).getString("count"))
        assertEquals("shop", requests.getJSONObject(1).getString("truck"))
    }

    @Test
    fun twentyConcurrentTapsAllLandAndLeaveNoTempFiles() = runBlocking {
        val (base, store) = setup()
        coroutineScope {
            (0 until 20).forEach { i ->
                launch(Dispatchers.IO) {
                    store.appendTruckRequest(job, HandoffFlagRef("board_stock", "b$i"), HandoffTruck.LATER, Instant.now().toString())
                }
            }
        }
        assertEquals(20, JSONObject(sidecar(base).readText()).getJSONArray("requests").length())
        val leftovers = sidecar(base).parentFile.listFiles()!!.filter { it.name.contains(".tmp-") }
        assertTrue("temp files left behind: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun readOnlyStoreRefusesWithoutAnyIo() = runBlocking {
        val base = Files.createTempDirectory("handoff-req-ro").toFile() // not even a live job
        val store = HandoffFlagRequestStore(base, "T1", readOnly = true)
        assertEquals(HandoffFlagWriteResult.ReadOnly, store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        assertEquals(0, base.listFiles()!!.size)
        assertEquals("This screen is read-only", HandoffFlagWriteResult.ReadOnly.userMessage())
    }

    @Test
    fun invalidRequestsAreNotWritten() = runBlocking {
        val (base, store) = setup()
        assertEquals(HandoffFlagWriteResult.Invalid, store.appendTruckRequest(job, HandoffFlagRef("bogus", "x"), HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        assertEquals(HandoffFlagWriteResult.Invalid, store.appendTruckRequest(job, HandoffFlagRef("checklist", " "), HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        assertEquals(HandoffFlagWriteResult.Invalid, store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "1728396000000"))
        assertEquals(HandoffFlagWriteResult.Invalid, store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00"))
        assertFalse(sidecar(base).exists())
    }

    @Test
    fun invalidWinsOverMissingJob() = runBlocking {
        val base = Files.createTempDirectory("handoff-req").toFile()
        val store = HandoffFlagRequestStore(base, "T1")
        assertEquals(HandoffFlagWriteResult.Invalid, store.appendTruckRequest(job, HandoffFlagRef("bogus", "x"), HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
    }

    @Test
    fun missingJobFolderIsRefusedAndNoFolderIsCreated() = runBlocking {
        val base = Files.createTempDirectory("handoff-req").toFile()
        val store = HandoffFlagRequestStore(base, "T1")
        assertEquals(HandoffFlagWriteResult.JobMissing, store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        assertFalse(File(base, job).exists())
        assertEquals(0, base.listFiles()!!.size)
    }

    @Test
    fun jobFolderWithoutMetadataIsRefusedAndMetadataIsNotCreated() = runBlocking {
        val base = Files.createTempDirectory("handoff-req").toFile()
        File(base, job).mkdirs()
        val store = HandoffFlagRequestStore(base, "T1")
        assertEquals(HandoffFlagWriteResult.JobMissing, store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        assertFalse(File(base, "$job/.metadata").exists())
        assertEquals(0, File(base, job).listFiles()!!.size)
    }

    @Test
    fun missingJobDoesNotReportTheGuardEventBecauseTheTapShowsItsOwnMessage() = runBlocking {
        val base = Files.createTempDirectory("handoff-req").toFile()
        val store = HandoffFlagRequestStore(base, "T1")
        val received = mutableListOf<String>()
        val collector = launch(Dispatchers.Unconfined) { JobFolderGuardEvents.skipped.collect { received += it } }
        assertEquals(HandoffFlagWriteResult.JobMissing, store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        Thread.sleep(100)
        collector.cancel()
        assertTrue("unexpected guard events: $received", received.isEmpty())
    }

    @Test
    fun unreadableFileIsPreservedThenANewFileStarted() = runBlocking {
        val (base, store) = setup()
        sidecar(base).apply { parentFile.mkdirs() }.writeText("{broken")
        assertEquals(HandoffFlagWriteResult.Written, store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        val admin = sidecar(base).parentFile
        val backup = admin.listFiles()!!.single { it.name.startsWith("handoff_flags_request.T1.json.unreadable-") }
        assertEquals("{broken", backup.readText())
        assertEquals(1, JSONObject(sidecar(base).readText()).getJSONArray("requests").length())
    }

    @Test
    fun unreadableFileIsLeftUntouchedWhenItCannotBeBackedUp() = runBlocking {
        val base = liveBase()
        val store = HandoffFlagRequestStore(base, "T1", clockMillis = { 1000L })
        val file = sidecar(base).apply { parentFile.mkdirs() }
        file.writeText("{broken")
        // A directory squatting on the backup name makes the copy fail.
        File(file.parentFile, "handoff_flags_request.T1.json.unreadable-1000").mkdir()
        assertEquals(HandoffFlagWriteResult.StorageUnavailable, store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        assertEquals("{broken", file.readText())
    }

    @Test
    fun fileThatCannotBeReadIsNotOverwritten() = runBlocking {
        val base = liveBase()
        val store = HandoffFlagRequestStore(base, "T1")
        // A directory at the sidecar path exists() but readText() throws IOException.
        val file = sidecar(base)
        file.mkdirs()
        assertEquals(HandoffFlagWriteResult.StorageUnavailable, store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        assertTrue(file.isDirectory)
        assertEquals(0, file.parentFile.listFiles()!!.count { it.name.contains("unreadable") })
    }

    private val t0 = Instant.parse("2026-10-08T14:00:00Z")
    private val other = HandoffFlagRef("board_stock", "b")

    @Test
    fun pendingIsLastRequestPerRef() = runBlocking {
        val (_, store) = setup()
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, t0.toString())
        store.appendTruckRequest(job, ref, null, t0.plusSeconds(5).toString())
        store.appendTruckRequest(job, other, HandoffTruck.SHOP, t0.minusSeconds(300).toString())
        val pending = store.pendingTruckFlags(job, now = t0.plusSeconds(10))
        assertEquals(setOf(ref, other), pending.keys)
        assertEquals(null, pending.getValue(ref).truck)
        assertEquals(HandoffTruck.SHOP, pending.getValue(other).truck)
    }

    @Test
    fun offlineRequestTenMinutesOldIsStillPending() = runBlocking {
        val (_, store) = setup()
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, t0.toString())
        val pending = store.pendingTruckFlags(job, now = t0.plusSeconds(600))
        assertEquals(HandoffTruck.LOOSE, pending.getValue(ref).truck)
    }

    @Test
    fun truckSetAtAtOrAfterTheRequestMeansItWasApplied() = runBlocking {
        val (_, store) = setup()
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, t0.toString())
        val now = t0.plusSeconds(60)
        assertTrue("HT applied it", store.pendingTruckFlags(job, now, mapOf(ref to t0)).isEmpty())
        assertTrue("office overrode it later", store.pendingTruckFlags(job, now, mapOf(ref to t0.plusSeconds(30))).isEmpty())
        assertEquals(
            "an older setAt does not apply",
            setOf(ref), store.pendingTruckFlags(job, now, mapOf(ref to t0.minusSeconds(1))).keys
        )
        assertEquals(
            "setAt of another item does not apply",
            setOf(ref), store.pendingTruckFlags(job, now, mapOf(other to t0.plusSeconds(30))).keys
        )
    }

    @Test
    fun setAtOnlyJudgesTheLastRequest() = runBlocking {
        val (_, store) = setup()
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, t0.toString())
        store.appendTruckRequest(job, ref, null, t0.plusSeconds(20).toString())
        // HT applied the first tap but not yet the second (back to Auto).
        val pending = store.pendingTruckFlags(job, t0.plusSeconds(30), mapOf(ref to t0))
        assertEquals(null, pending.getValue(ref).truck)
        assertEquals(t0.plusSeconds(20), pending.getValue(ref).requestedAt)
    }

    @Test
    fun backstopDropsRequestsOlderThan24Hours() = runBlocking {
        val (_, store) = setup()
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, t0.toString())
        assertEquals(setOf(ref), store.pendingTruckFlags(job, t0.plusSeconds(24 * 3600)).keys) // exactly 24 h: kept
        assertTrue(store.pendingTruckFlags(job, t0.plusSeconds(24 * 3600 + 1)).isEmpty())
        assertTrue(store.pendingTruckFlags(job, t0.plusSeconds(25 * 3600)).isEmpty())
    }

    @Test
    fun requestedAtFarInTheFutureIsTreatedAsNow() = runBlocking {
        val (_, store) = setup()
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, t0.plusSeconds(3600).toString())
        store.appendTruckRequest(job, other, HandoffTruck.SHOP, t0.plusSeconds(240).toString())
        val pending = store.pendingTruckFlags(job, now = t0)
        assertEquals("clamped to now", t0, pending.getValue(ref).requestedAt)
        assertEquals("within 5 min skew: left alone", t0.plusSeconds(240), pending.getValue(other).requestedAt)
        // A clamped request is judged against setAt as "now".
        assertTrue(store.pendingTruckFlags(job, t0, mapOf(ref to t0)).keys.none { it == ref })
    }

    @Test
    fun futureRequestKeepsFirstSeenAnchorAndExpiresAfterBackstop() = runBlocking {
        val (base, store) = setup()
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, t0.plusSeconds(365L * 24 * 3600).toString())
        assertFalse("observation file is created by pending reads", pendingClock(base).exists())

        assertEquals(t0, store.pendingTruckFlags(job, t0).getValue(ref).requestedAt)
        assertTrue(pendingClock(base).isFile)
        assertFalse("observation file keeps a non-JSON suffix", pendingClock(base).name.endsWith(".json"))
        assertEquals(t0, store.pendingTruckFlags(job, t0.plusSeconds(60)).getValue(ref).requestedAt)
        assertTrue(store.pendingTruckFlags(job, t0.plusSeconds(25 * 3600)).isEmpty())
    }

    @Test
    fun officeOverrideRemainsAppliedAfterRepeatedReads() = runBlocking {
        val (_, store) = setup()
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, t0.plusSeconds(3600).toString())
        store.pendingTruckFlags(job, t0)

        assertTrue(store.pendingTruckFlags(job, t0.plusSeconds(60), mapOf(ref to t0.plusSeconds(30))).isEmpty())
        assertTrue(store.pendingTruckFlags(job, t0.plusSeconds(120), mapOf(ref to t0.plusSeconds(30))).isEmpty())
    }

    @Test
    fun anchorSurvivesStoreRecreationAndSidecarAppend() = runBlocking {
        val (base, firstStore) = setup()
        firstStore.appendTruckRequest(job, ref, HandoffTruck.LOOSE, t0.plusSeconds(3600).toString())
        assertEquals(t0, firstStore.pendingTruckFlags(job, t0).getValue(ref).requestedAt)

        val recreatedStore = HandoffFlagRequestStore(base, "T1")
        recreatedStore.appendTruckRequest(job, other, HandoffTruck.SHOP, t0.plusSeconds(7200).toString())
        val pending = recreatedStore.pendingTruckFlags(job, t0.plusSeconds(60))

        assertEquals(t0, pending.getValue(ref).requestedAt)
        assertEquals(t0.plusSeconds(60), pending.getValue(other).requestedAt)
    }

    @Test
    fun readOnlyFutureRequestDoesNotCreateClockAnchor() = runBlocking {
        val (base, _) = setup()
        sidecar(base).apply { parentFile.mkdirs() }.writeText(
            """{"schemaVersion":1,"tabletId":"T1","requests":[{"requestId":"future","requestedAt":"2026-10-08T15:00:00Z","source":"checklist","id":"c-123","truck":"loose"}]}"""
        )
        val store = HandoffFlagRequestStore(base, "T1", readOnly = true)

        assertTrue(store.pendingTruckFlags(job, t0).isEmpty())
        assertFalse(pendingClock(base).exists())
    }

    @Test
    fun failedClockAnchorWriteKeepsSidecarAndNormalPendingFlags() = runBlocking {
        val (base, store) = setup()
        val requestFile = sidecar(base).apply { parentFile.mkdirs() }
        requestFile.writeText(
            """{"schemaVersion":1,"tabletId":"T1","requests":[
              {"requestId":"future","requestedAt":"2026-10-08T15:00:00Z","source":"checklist","id":"c-123","truck":"loose"},
              {"requestId":"normal","requestedAt":"2026-10-08T14:00:00Z","source":"board_stock","id":"b","truck":"shop"}
            ]}"""
        )
        val originalBytes = requestFile.readBytes()
        assertTrue(pendingClock(base).mkdirs())

        val pending = store.pendingTruckFlags(job, t0)

        assertEquals(setOf(other), pending.keys)
        assertEquals(originalBytes.toList(), requestFile.readBytes().toList())
        assertTrue(pendingClock(base).isDirectory)
    }

    @Test
    fun identicalRequestedAtLaterEntryWins() = runBlocking {
        val (_, store) = setup()
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, t0.toString())
        store.appendTruckRequest(job, ref, HandoffTruck.LATER, t0.toString())
        assertEquals(HandoffTruck.LATER, store.pendingTruckFlags(job, t0.plusSeconds(1)).getValue(ref).truck)
    }

    @Test
    fun countOnlyAndUnknownTruckEntriesAreIgnored() = runBlocking {
        val (base, store) = setup()
        sidecar(base).apply { parentFile.mkdirs() }.writeText(
            """{"schemaVersion":1,"tabletId":"T1","requests":[
              {"requestId":"a","requestedAt":"2027-10-08T14:00:00Z","source":"board_stock","id":"b","count":"qty"},
              {"requestId":"b","requestedAt":"2027-10-08T14:00:01Z","source":"checklist","id":"c-123","truck":"teleport"},
              {"requestId":"c","requestedAt":"not-a-time","source":"checklist","id":"c-9","truck":"shop"}
            ]}"""
        )
        assertTrue(store.pendingTruckFlags(job, t0.plusSeconds(5)).isEmpty())
        assertFalse("count-only and unknown trucks do not get clock anchors", pendingClock(base).exists())
    }

    @Test
    fun rejectedFileIsLeftAlone() = runBlocking {
        val (base, store) = setup()
        val rejected = File(sidecar(base).parentFile.apply { mkdirs() }, "handoff_flags_request.T1.json.rejected").apply { writeText("x") }
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00Z")
        assertEquals("x", rejected.readText())
    }
}
