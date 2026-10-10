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

    private fun setup(): Pair<File, HandoffFlagRequestStore> {
        val base = Files.createTempDirectory("handoff-req").toFile()
        return base to HandoffFlagRequestStore(base, "T1")
    }

    private fun sidecar(base: File) = File(base, "$job/.metadata/admin/handoff_flags_request.T1.json")

    @Test
    fun writesExactContractShapeWithOnlyTheTouchedFlag() = runBlocking<Unit> {
        val (base, store) = setup()
        assertTrue(store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00.123Z"))
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
    fun twoConcurrentTapsBothLand() = runBlocking {
        val (base, store) = setup()
        coroutineScope {
            (0 until 20).forEach { i ->
                launch(Dispatchers.IO) {
                    store.appendTruckRequest(job, HandoffFlagRef("board_stock", "b$i"), HandoffTruck.LATER, Instant.now().toString())
                }
            }
        }
        assertEquals(20, JSONObject(sidecar(base).readText()).getJSONArray("requests").length())
    }

    @Test
    fun invalidRequestsAreNotWritten() = runBlocking {
        val (base, store) = setup()
        assertFalse(store.appendTruckRequest(job, HandoffFlagRef("bogus", "x"), HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        assertFalse(store.appendTruckRequest(job, HandoffFlagRef("checklist", " "), HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        assertFalse(store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "1728396000000"))
        assertFalse(store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00"))
        assertFalse(sidecar(base).exists())
    }

    @Test
    fun unreadableFileIsPreservedThenANewFileStarted() = runBlocking {
        val (base, store) = setup()
        sidecar(base).apply { parentFile.mkdirs() }.writeText("{broken")
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00Z")
        val admin = sidecar(base).parentFile
        val backup = admin.listFiles()!!.single { it.name.startsWith("handoff_flags_request.T1.json.unreadable-") }
        assertEquals("{broken", backup.readText())
        assertEquals(1, JSONObject(sidecar(base).readText()).getJSONArray("requests").length())
    }

    @Test
    fun unreadableFileIsLeftUntouchedWhenItCannotBeBackedUp() = runBlocking {
        val base = Files.createTempDirectory("handoff-req").toFile()
        val store = HandoffFlagRequestStore(base, "T1", clockMillis = { 1000L })
        val file = sidecar(base).apply { parentFile.mkdirs() }
        file.writeText("{broken")
        // A directory squatting on the backup name makes the copy fail.
        File(file.parentFile, "handoff_flags_request.T1.json.unreadable-1000").mkdir()
        assertFalse(store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        assertEquals("{broken", file.readText())
    }

    @Test
    fun fileThatCannotBeReadIsNotOverwritten() = runBlocking {
        val base = Files.createTempDirectory("handoff-req").toFile()
        val store = HandoffFlagRequestStore(base, "T1")
        // A directory at the sidecar path exists() but readText() throws IOException.
        val file = sidecar(base)
        file.mkdirs()
        assertFalse(store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00Z"))
        assertTrue(file.isDirectory)
        assertEquals(0, file.parentFile.listFiles()!!.count { it.name.contains("unreadable") })
    }

    @Test
    fun pendingIsLastRequestPerRefWithinTtl() = runBlocking {
        val (_, store) = setup()
        val t0 = Instant.parse("2026-10-08T14:00:00Z")
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, t0.toString())
        store.appendTruckRequest(job, ref, null, t0.plusSeconds(5).toString())
        store.appendTruckRequest(job, HandoffFlagRef("board_stock", "b"), HandoffTruck.SHOP, t0.minusSeconds(300).toString())
        val pending = store.pendingTruckFlags(job, now = t0.plusSeconds(10))
        assertEquals(setOf(ref), pending.keys)
        assertEquals(null, pending.getValue(ref).truck)
        assertTrue(store.pendingTruckFlags(job, now = t0.plusSeconds(200)).isEmpty())
    }

    @Test
    fun rejectedFileIsLeftAlone() = runBlocking {
        val (base, store) = setup()
        val rejected = File(sidecar(base).parentFile.apply { mkdirs() }, "handoff_flags_request.T1.json.rejected").apply { writeText("x") }
        store.appendTruckRequest(job, ref, HandoffTruck.LOOSE, "2026-10-08T14:00:00Z")
        assertEquals("x", rejected.readText())
    }
}
