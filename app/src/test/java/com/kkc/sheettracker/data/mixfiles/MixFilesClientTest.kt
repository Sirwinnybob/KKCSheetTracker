package com.kkc.sheettracker.data.mixfiles

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkc.sheettracker.data.cnc.CncMachines
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class MixFilesClientTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }
    private fun client(http: OkHttpClient? = null): MixFilesClient {
        val machine = CncMachines.scm.copy(baseUrl = server.url("/").toString())
        return if (http == null) MixFilesClient(machine) else MixFilesClient(machine, http)
    }
    private fun success() { server.enqueue(MockResponse().setBody("""{"ok":true,"catalog":{"job":"J","material":"M","folderRevision":"new","files":[]}}""")) }
    private fun body(): JsonObject = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject

    @Test fun synchronousMutationDoesNotReplayAfterLostResponse() = runBlocking {
        val api = client()
        server.enqueue(MockResponse().setBody(MixFilesJsonTest.SAMPLE))
        assertTrue(api.catalog("J", "M") is MixFilesResult.Ok) // Reuse the pooled socket.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        success() // A replay would incorrectly look like a confirmed save.
        assertEquals(MixFilesResult.Offline, api.write("J", "M", "A.mix", "r", emptyList(), null))
        assertEquals(2, server.requestCount)
    }

    @Test fun encodesJobAndMaterialAsPathSegments() = runBlocking {
        server.enqueue(MockResponse().setBody(MixFilesJsonTest.SAMPLE))
        assertTrue(client().catalog("530a - DC BIGLEY 2", "3_4 Elegant Black Super Matte") is MixFilesResult.Ok)
        assertEquals("/jobs/530a%20-%20DC%20BIGLEY%202/materials/3_4%20Elegant%20Black%20Super%20Matte/mix-files", server.takeRequest().path)
        server.enqueue(MockResponse().setBody(MixFilesJsonTest.SAMPLE))
        client().catalog("J/#?%", "M/+")
        assertEquals("/jobs/J%2F%23%3F%25/materials/M%2F+/mix-files", server.takeRequest().path)
    }

    @Test fun revisionConflictCarriesCurrentRow() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"ok":false,"code":"revision_conflict","error":"changed","current":{"file":"A.mix","kind":"mix","revision":"current-sha","companionMismatch":false,"entries":[]}}"""))
        val result = client().write("J", "M", "A.mix", "old", emptyList(), null) as MixFilesResult.Rejected
        assertEquals(409, result.error.status)
        assertEquals("revision_conflict", result.error.code)
        assertEquals("changed", result.error.message)
        assertEquals("current-sha", result.error.current?.revision)
    }

    @Test fun frzMismatchCarriesMismatches() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"ok":false,"code":"frz_completion_mismatch","error":"mismatch","mismatches":[{"pgm":"R870107N.pgm","index":3,"tabletLocked":true,"frzRemaining":1}]}"""))
        val result = client().frz("J", "M", "A.frz", "r", Completion(emptyList(), emptyMap(), null), emptyList()) as MixFilesResult.Rejected
        assertEquals(listOf(FrzMismatch("R870107N.pgm", 3, true, 1)), result.error.mismatches)
    }

    @Test fun unknownRouteIsServiceOutdated() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"code":"not_found","error":"unknown route: /jobs/..."}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"code":"not_found","error":"folder missing"}"""))
        assertEquals(MixFilesResult.ServiceOutdated, client().catalog("J", "M"))
        assertEquals("not_found", (client().catalog("J", "M") as MixFilesResult.Rejected).error.code)
    }

    @Test fun serverBusyAndSocketErrorAreOffline() = runBlocking {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(503).setBody("""{"ok":false,"code":"server_busy","error":"try later"}""")) }
        assertEquals(MixFilesResult.Offline, client().catalog("J", "M"))
        server.shutdown()
        assertEquals(MixFilesResult.Offline, client().catalog("J", "M"))
    }

    @Test fun busySaveRetriesOnlyAfterExplicitRefusal() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"ok":false,"code":"server_busy","error":"server is busy"}"""))
        success()
        assertTrue(client().write("J", "M", "A.mix", "r", emptyList(), null) is MixFilesResult.Ok)
        assertEquals(2, server.requestCount)
    }

    @Test fun busyReadRetriesAndRecovers() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"ok":false,"code":"server_busy","error":"server is busy"}"""))
        server.enqueue(MockResponse().setBody(MixFilesJsonTest.SAMPLE))
        assertTrue(client().catalog("J", "M") is MixFilesResult.Ok)
        assertEquals(2, server.requestCount)
    }

    @Test fun saveIsNotRetriedAfterBusyThenLostResponse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"ok":false,"code":"server_busy","error":"server is busy"}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        success()
        assertEquals(MixFilesResult.Offline, client().write("J", "M", "A.mix", "r", emptyList(), null))
        assertEquals(2, server.requestCount)
    }

    @Test fun saveWaitsLongerThanReadTimeoutForSlowCnc() = runBlocking {
        val http = OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build()
        server.enqueue(MockResponse().setHeadersDelay(300, TimeUnit.MILLISECONDS)
            .setBody("""{"ok":true,"catalog":{"job":"J","material":"M","folderRevision":"new","files":[]}}"""))
        assertTrue(client(http).write("J", "M", "A.mix", "r", emptyList(), null) is MixFilesResult.Ok)
        assertEquals(1, server.requestCount)
    }

    @Test fun timeoutAndUnparsableBodiesAreOffline() = runBlocking {
        listOf("not json", "{}", """{"ok":true,"catalog":{}}""").forEach {
            server.enqueue(MockResponse().setBody(it))
            assertEquals(MixFilesResult.Offline, client().catalog("J", "M"))
        }
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"ok":false,"code":123,"error":"bad"}"""))
        assertEquals(MixFilesResult.Offline, client().catalog("J", "M"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
        assertEquals(MixFilesResult.Offline, client(http).catalog("J", "M"))
    }

    @Test fun writeSendsNullExpectedRevisionForCreate() = runBlocking {
        success()
        val result = client().write("J", "M", "A.mix", null, listOf(EntrySpec.NewPgm("R1.pgm")), null)
        assertEquals("new", (result as MixFilesResult.Ok).value.folderRevision)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/jobs/J/materials/M/mix-files/write", request.path)
        val sent = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertTrue(sent.has("expectedRevision"))
        assertTrue(sent["expectedRevision"].isJsonNull)
        assertFalse(sent.has("completion"))
        assertEquals("""[{"pgm":"R1.pgm"}]""", sent["entries"].toString())
    }

    @Test fun pairWriteSendsCompanionExpectedRevision() = runBlocking {
        success()
        val path = "Z:\\legacy\\R1.pgm"
        client().write("J", "M", "A.mix", "mix-sha", listOf(EntrySpec.Existing(path, linkedMapOf("T" to "0016973824"), "AD")), Completion(listOf("R2.pgm"), mapOf("R1.pgm" to "tablet"), "frz-sha"))
        val sent = body()
        assertEquals("mix-sha", sent["expectedRevision"].asString)
        assertEquals(JsonParser.parseString("""{"completedPgms":["R2.pgm"],"resolutions":{"R1.pgm":"tablet"},"companionExpectedRevision":"frz-sha"}"""), sent["completion"])
        val entry = sent.getAsJsonArray("entries")[0].asJsonObject
        assertEquals(path, entry["path"].asString)
        assertEquals("0016973824", entry.getAsJsonObject("parameters")["T"].asString)
        assertFalse(entry.has("pgm"))
    }

    @Test fun featuresReadsStatusAndIsEmptyOnFailure() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"features":["pgm-edits-revert","mix-files"]}"""))
        assertEquals(setOf("pgm-edits-revert", "mix-files"), client().features())
        assertEquals("/status", server.takeRequest().path)
        listOf("{}", "bad json", """{"ok":true,"features":[4]}""", """{"ok":false,"features":["bad"]}""").forEach {
            server.enqueue(MockResponse().setBody(it)); assertTrue(client().features().isEmpty())
        }
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"ok":true,"features":["bad"]}"""))
        assertTrue(client().features().isEmpty())
        server.shutdown()
        assertTrue(client().features().isEmpty())
    }

    @Test fun folderRevisionReadsCheapRouteAndRejectsMissingToken() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"folderRevision":"folder-sha"}"""))
        assertEquals(MixFilesResult.Ok("folder-sha"), client().folderRevision("J", "M"))
        assertEquals("/jobs/J/materials/M/mix-files/revision", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertEquals(MixFilesResult.Offline, client().folderRevision("J", "M"))
    }

    @Test fun mutationsUseSynchronousPostActionsAndExactBodies() = runBlocking {
        val api = client()
        val completion = Completion(emptyList(), emptyMap(), null)
        val cases: List<Triple<String, String, suspend () -> MixFilesResult<MixFilesCatalog>>> = listOf(
            Triple("frz", """{"file":"A.frz","expectedRevision":"r","completion":{"completedPgms":[],"resolutions":{}},"pending":[{"pgm":"R1.pgm","quantity":3,"remaining":1}]}""") { api.frz("J", "M", "A.frz", "r", completion, listOf(EntrySpec.Pending("R1.pgm", 3, 1))) },
            Triple("rename", """{"file":"A.mix","newFile":"B.mix","expectedRevision":"r"}""") { api.rename("J", "M", "A.mix", "B.mix", "r") },
            Triple("delete", """{"file":"A.mix","expectedRevision":"r"}""") { api.delete("J", "M", "A.mix", "r") },
            Triple("delete-revision", """{"file":"OLD1_A.mix","expectedRevision":"r"}""") { api.deleteRevision("J", "M", "OLD1_A.mix", "r") },
            Triple("restore", """{"revisionFile":"OLD1_A.mix","targetFile":"A.mix","expectedTargetRevision":null}""") { api.restore("J", "M", "OLD1_A.mix", "A.mix", null) },
            Triple("restore", """{"revisionFile":"OLD1_A.mix","targetFile":"A.mix","expectedTargetRevision":"target-r"}""") { api.restore("J", "M", "OLD1_A.mix", "A.mix", "target-r") },
            Triple("split", """{"file":"A.mix","expectedRevision":"r","entries":[],"newMixes":[{"file":"B.mix","entries":[{"pgm":"R2.pgm"}]}]}""") { api.split("J", "M", "A.mix", "r", emptyList(), listOf(NewMix("B.mix", listOf(EntrySpec.NewPgm("R2.pgm")))), null) },
            Triple("split", """{"file":"A.mix","expectedRevision":"r","entries":[],"newMixes":[],"completion":{"completedPgms":[],"resolutions":{}}}""") { api.split("J", "M", "A.mix", "r", emptyList(), emptyList(), completion) },
        )
        cases.forEach { (action, expected, call) ->
            success()
            assertEquals(MixFilesResult.Ok(MixFilesCatalog("J", "M", "new", emptyList())), call())
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/jobs/J/materials/M/mix-files/$action", request.path)
            assertEquals(JsonParser.parseString(expected), JsonParser.parseString(request.body.readUtf8()))
        }
    }

    @Test fun frzRestoreSendsCompletionForCreateAndOverwrite() = runBlocking {
        val api: MixFilesApi = client()
        val completion = Completion(listOf("R1.pgm", "R2.pgm"), linkedMapOf("R3.pgm" to "frz"), null)
        listOf(null, "target-sha").forEach { targetRevision ->
            success()
            val result = api.restore("J", "M", "OLD1_A.frz", "A.frz", targetRevision, completion)
            assertEquals(MixFilesResult.Ok(MixFilesCatalog("J", "M", "new", emptyList())), result)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/jobs/J/materials/M/mix-files/restore", request.path)
            val sent = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("OLD1_A.frz", sent["revisionFile"].asString)
            assertEquals("A.frz", sent["targetFile"].asString)
            assertTrue(sent.has("expectedTargetRevision"))
            if (targetRevision == null) assertTrue(sent["expectedTargetRevision"].isJsonNull)
            else assertEquals("target-sha", sent["expectedTargetRevision"].asString)
            assertEquals(JsonParser.parseString("""{"completedPgms":["R1.pgm","R2.pgm"],"resolutions":{"R3.pgm":"frz"}}"""), sent["completion"])
        }
    }

    @Test fun rejectionRetainsOptionalRecoveryDetails() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"ok":false,"code":"companion_write_failed","error":"disk failure","current":null,"edit":"count","pgm":"R1.pgm","reason":"details","frzRestored":false}"""))
        val error = (client().catalog("J", "M") as MixFilesResult.Rejected).error
        assertEquals("count", error.edit)
        assertEquals("R1.pgm", error.pgm)
        assertEquals("details", error.reason)
        assertEquals(false, error.frzRestored)
        assertNull(error.current)
        assertTrue(error.mismatches.isEmpty())
    }
}
