package com.kkc.sheettracker.data.cnc

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MaterialTransferClientTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }
    private fun client() = MaterialTransferClient(server.url("/").toString())
    private val snapshot = SheetProgressSnapshot(1, 1_000L, listOf(
        SheetProgressEntry("711 - WIECHERT", "19mm.pdf", 1, "fp", "COMPLETE", listOf("R1.pgm")),
        SheetProgressEntry("711 - WIECHERT", "19mm.pdf", 2, "fp", "NOT_STARTED", listOf("R2A.pgm", "R2Z.pgm"))))
    private fun request(policy: PunloadPolicy = PunloadPolicy.REMOVE) = TransferPreviewRequest(
        CncMachines.scm, CncMachines.scmNew, "711 - WIECHERT", "19mm", "folder-r", snapshot, "19mmMix.mix", policy)

    @Test fun previewSendsBoundIdentitiesSnapshotAndPolicy() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"preview":{"previewId":"pv-1","completed":["R1.pgm"],"remaining":["R2A.pgm"],
            "conflicts":[],"warnings":["Blue SCM removes PUNLOAD"],"canStart":true}}"""))
        val preview = (client().preview(request(PunloadPolicy.KEEP_REMOVED)) as TransferResult.Ok).value
        assertEquals(TransferPreview("pv-1", listOf("R1.pgm"), listOf("R2A.pgm"), emptyList(), listOf("Blue SCM removes PUNLOAD"), true), preview)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/material-transfers/preview", sent.path)
        val body = JsonParser.parseString(sent.body.readUtf8()).asJsonObject
        assertEquals("scm", body["sourceMachineId"].asString)
        assertEquals("scm-new", body["destinationMachineId"].asString)
        assertEquals("folder-r", body["expectedSourceRevision"].asString)
        assertEquals("keepRemoved", body["punloadPolicy"].asString)
        assertEquals("19mmMix.mix", body["worklistFile"].asString)
        val progress = body.getAsJsonObject("progressSnapshot")
        assertEquals(1, progress["version"].asInt)
        val entry = progress.getAsJsonArray("entries")[1].asJsonObject
        assertEquals("NOT_STARTED", entry["status"].asString)
        assertEquals("fp", entry["fileFingerprint"].asString)
        assertEquals(2, entry.getAsJsonArray("pgms").size())
    }

    @Test fun missingRoutesAreUnsupportedAndRefusalsCarryCodes() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"code":"not_found","error":"unknown route: /material-transfers/preview"}"""))
        assertEquals(TransferResult.Unsupported, client().preview(request()))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"ok":false,"code":"destination_exists","error":"exists"}"""))
        assertEquals(TransferResult.Rejected("destination_exists", "exists"), client().preview(request()))
        server.enqueue(MockResponse().setBody("not json"))
        assertEquals(TransferResult.Offline, client().preview(request()))
    }

    @Test fun startIsNeverReplayedAfterALostResponse() = runBlocking {
        val api = client()
        server.enqueue(MockResponse().setBody("""{"ok":true,"operation":{"id":"op-0","phase":"done","outcome":"completed"}}"""))
        assertTrue(api.operation("op-0") is TransferResult.Ok) // Pool a read connection first.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"operation":{"id":"dup","phase":"preparing"}}"""))
        assertEquals(TransferResult.Offline, api.start("pv-1", "req-1"))
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val sent = server.takeRequest()
        assertEquals("/material-transfers", sent.path)
        assertEquals(JsonParser.parseString("""{"previewId":"pv-1","requestId":"req-1"}"""), JsonParser.parseString(sent.body.readUtf8()))
    }

    @Test fun startAndOperationParseStablePhaseAndOutcome() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"operation":{"id":"op-1","phase":"preparing"}}"""))
        assertEquals(TransferResult.Ok(TransferOperation("op-1", "preparing", null, null)), client().start("pv-1", "req-1"))
        server.enqueue(MockResponse().setBody("""{"ok":true,"operation":{"id":"op-1","phase":"activated","outcome":"completed"}}"""))
        val done = (client().operation("op 1/x") as TransferResult.Ok).value
        assertTrue(done.isTerminal); assertTrue(done.succeeded)
        server.takeRequest()
        assertEquals("/material-transfers/op%201%2Fx", server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"operation":{"id":""}}"""))
        assertEquals(TransferResult.Offline, client().start("pv-1", "req-2")) // Accepted without an ID is still uncertain.
    }
}
