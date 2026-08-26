package com.kkc.sheettracker.data.mixservice

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MixServiceClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = MixServiceClient(server.url("/").toString())

    @Test
    fun `isReachable true on 200`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        assertTrue(client().isReachable())
    }

    @Test
    fun `isReachable false on connection failure`() = runBlocking {
        server.shutdown()
        assertFalse(client().isReachable())
    }

    @Test
    fun `listPgms parses inventory items from the pgms envelope`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"pgms":[{"name":"R1.pgm","size":100,"mtime":1798000000}]}"""
            )
        )
        val items = client().listPgms("648 - WIECHERT", "19mm Pre_Finished")
        assertEquals(listOf("R1.pgm"), items.map { it.name })
        assertEquals(1798000000L, items.single().mtime)
        val recorded = server.takeRequest()
        assertEquals("/jobs/648%20-%20WIECHERT/materials/19mm%20Pre_Finished/pgms", recorded.path)
    }

    @Test
    fun `listPgms returns empty list on 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(emptyList<PgmInventoryItem>(), client().listPgms("648", "Unknown"))
    }

    @Test
    fun `listMixes returns null on failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(client().listMixes("648", "19mm Pre_Finished"))
    }

    @Test
    fun `listMixes returns empty list when the envelope has no mixes`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"mixes":[]}"""))
        assertEquals(emptyList<MixDefinition>(), client().listMixes("648", "19mm Pre_Finished"))
    }

    @Test
    fun `listMixes parses definitions from the mixes envelope and filters by job and material query params`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"mixes":[{"name":"KkcMix","job":"648","material":"19mm Pre_Finished","programs":["R1.pgm","R2.pgm"],"mixFilename":"KkcMix.mix","status":"compiled"}]}"""
            )
        )
        val mixes = client().listMixes("648", "19mm Pre_Finished")
        assertEquals("KkcMix", mixes?.single()?.name)
        assertEquals(listOf("R1.pgm", "R2.pgm"), mixes?.single()?.programs)
        val recorded = server.takeRequest()
        assertTrue(recorded.path?.contains("job=648") == true)
        assertTrue(recorded.path?.contains("material=19mm") == true)
    }

    @Test
    fun `listMixes without material queries only by job`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"mixes":[]}"""))
        client().listMixes("648")
        val recorded = server.takeRequest()
        assertTrue(recorded.path?.contains("job=648") == true)
        assertFalse(recorded.path?.contains("material=") == true)
    }

    @Test
    fun `createMix posts name job material programs and unwraps the mix envelope`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(
                    """{"ok":true,"mix":{"name":"KkcMix","job":"648","material":"19mm Pre_Finished","programs":["R1.pgm"],"mixFilename":"KkcMix.mix"},"status":"never"}"""
                )
        )
        val result = client().createMix("648", "19mm Pre_Finished", "KkcMix", listOf("R1.pgm"))
        check(result is MixWriteResult.Success)
        assertEquals("KkcMix", result.definition.name)
        assertEquals("never", result.definition.status)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path?.endsWith("/mixes") == true)
        val sentBody = JSONObject(recorded.body.readUtf8())
        assertEquals("648", sentBody.getString("job"))
        assertEquals("19mm Pre_Finished", sentBody.getString("material"))
        assertEquals("KkcMix", sentBody.getString("name"))
        assertFalse(sentBody.getBoolean("overwrite"))
        val sentPrograms = sentBody.getJSONArray("programs")
        assertEquals(1, sentPrograms.length())
        assertEquals("R1.pgm", sentPrograms.getString(0))
    }

    @Test
    fun `createMix with overwrite true sends overwrite true on the wire`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(
                    """{"ok":true,"mix":{"name":"KkcMix","job":"648","material":"19mm Pre_Finished","programs":["R1.pgm"],"mixFilename":"KkcMix.mix"},"status":"never"}"""
                )
        )
        val result = client().createMix("648", "19mm Pre_Finished", "KkcMix", listOf("R1.pgm"), overwrite = true)
        check(result is MixWriteResult.Success)
        val recorded = server.takeRequest()
        val sentBody = JSONObject(recorded.body.readUtf8())
        assertTrue(sentBody.getBoolean("overwrite"))
    }

    @Test
    fun `createMix maps 409, 404, 400, 503, and 504 to their sealed results`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409))
        check(client().createMix("648", "19mm Pre_Finished", "Dup", emptyList()) is MixWriteResult.DuplicateName)

        server.enqueue(MockResponse().setResponseCode(404))
        check(client().createMix("648", "Unknown", "X", emptyList()) is MixWriteResult.UnknownJobOrMaterial)

        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"ok":false,"code":"bad_request","error":"invalid mix name"}""")
        )
        val badRequest = client().createMix("648", "19mm Pre_Finished", "bad*name", emptyList())
        check(badRequest is MixWriteResult.BadRequest)
        assertEquals("invalid mix name", badRequest.message)

        server.enqueue(MockResponse().setResponseCode(503))
        check(client().createMix("648", "19mm Pre_Finished", "X", emptyList()) is MixWriteResult.CompileBusy)

        server.enqueue(MockResponse().setResponseCode(504))
        check(client().createMix("648", "19mm Pre_Finished", "X", emptyList()) is MixWriteResult.WinxisoTimeout)
    }

    @Test
    fun `createMix maps a history-sync failure to SyncFailed, unwrapping the nested completed mix`() = runBlocking {
        // Verified shape: the error body's "mix" field is itself a full {ok, mix, status}
        // envelope, the same object the 200 success path would have returned -- not a bare
        // MixDefinition. See mix_service/service.py's _history_sync_error_body/_create_mix.
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody(
                    """{"ok":false,"code":"edit_busy","error":"ledger locked",""" +
                        """"mix":{"ok":true,"mix":{"name":"KkcMix","job":"648",""" +
                        """"material":"19mm Pre_Finished","programs":["R1.pgm"],""" +
                        """"mixFilename":"KkcMix.mix"},"status":"compiled"},""" +
                        """"recoveryUrl":"/jobs/648/materials/19mm Pre_Finished/mix-history/sync"}"""
                )
        )
        val result = client().createMix("648", "19mm Pre_Finished", "KkcMix", listOf("R1.pgm"))
        check(result is MixWriteResult.SyncFailed)
        assertEquals("KkcMix", result.definition.name)
        assertEquals("compiled", result.definition.status)
        assertEquals("edit_busy", result.code)
        assertEquals("/jobs/648/materials/19mm Pre_Finished/mix-history/sync", result.recoveryUrl)
    }

    @Test
    fun `createMix maps 500 with a completed mix to SyncFailed, and plain 500 to NetworkError`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500)
                .setBody(
                    """{"ok":false,"code":"history_sync_failed","error":"disk full",""" +
                        """"mix":{"ok":true,"mix":{"name":"KkcMix","job":"648",""" +
                        """"material":"19mm Pre_Finished","programs":["R1.pgm"]},"status":"never"}}"""
                )
        )
        val syncFailed = client().createMix("648", "19mm Pre_Finished", "KkcMix", listOf("R1.pgm"))
        check(syncFailed is MixWriteResult.SyncFailed)
        assertEquals("history_sync_failed", syncFailed.code)

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"ok":false,"code":"internal","error":"boom"}"""))
        val plainError = client().createMix("648", "19mm Pre_Finished", "KkcMix", listOf("R1.pgm"))
        check(plainError is MixWriteResult.NetworkError)
    }

    @Test
    fun `createMix maps 422 missing-program error text to MissingProgram`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("""{"ok":false,"code":"missing_program","error":"missing program: R9.pgm"}""")
        )
        val result = client().createMix("648", "19mm Pre_Finished", "X", listOf("R9.pgm"))
        check(result is MixWriteResult.MissingProgram)
        assertEquals("R9.pgm", result.pgm)
    }

    @Test
    fun `createMix maps a non-missing-program 422 to BadRequest`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("""{"ok":false,"code":"missing_field","error":"missing field: programs"}""")
        )
        val result = client().createMix("648", "19mm Pre_Finished", "X", listOf("R9.pgm"))
        check(result is MixWriteResult.BadRequest)
        assertEquals("missing field: programs", result.message)
    }

    @Test
    fun `updateMix puts to the named mix path and preserves program order from the response`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(
                    """{"ok":true,"mix":{"name":"KkcMix","job":"648","material":"19mm Pre_Finished","programs":["R2.pgm","R1.pgm"],"mixFilename":"KkcMix.mix"},"status":"stale"}"""
                )
        )
        val result = client().updateMix("648", "19mm Pre_Finished", "KkcMix", listOf("R2.pgm", "R1.pgm"))
        check(result is MixWriteResult.Success)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertTrue(recorded.path?.endsWith("/mixes/KkcMix") == true)
        assertEquals(listOf("R2.pgm", "R1.pgm"), result.definition.programs)
        val sentBody = JSONObject(recorded.body.readUtf8())
        assertEquals("648", sentBody.getString("job"))
        assertEquals("19mm Pre_Finished", sentBody.getString("material"))
        val sentPrograms = sentBody.getJSONArray("programs")
        assertEquals(2, sentPrograms.length())
        assertEquals("R2.pgm", sentPrograms.getString(0))
        assertEquals("R1.pgm", sentPrograms.getString(1))
        assertFalse(sentBody.has("name"))
        assertFalse(sentBody.has("overwrite"))
    }

    @Test
    fun `listPgmEdits parses the ledger view`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"stateSource":"ledger","error":null,"files":{"R1.pgm":{"current":{"mode":"standard","punloadRemoved":true,"mixFiles":["KkcMix.mix"]},"stateSource":"ledger","history":[]}}}"""
            )
        )
        val view = client().listPgmEdits("648", "19mm Pre_Finished")
        assertEquals("standard", view?.files?.get("R1.pgm")?.current?.mode)
        assertEquals(true, view?.files?.get("R1.pgm")?.current?.punloadRemoved)
        val recorded = server.takeRequest()
        assertTrue(recorded.path?.contains("pgm-edits?historyLimit=20") == true)
    }

    @Test
    fun `listPgmEdits returns null on failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(null, client().listPgmEdits("648", "19mm Pre_Finished"))
    }

    @Test
    fun `submitPgmEdits posts requestId and files with name identity, parses success`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"ok":true,"requestId":"r1","backupDir":"BACKUP_1","files":[{"name":"R1.pgm","status":"succeeded"}]}""")
        )
        val result = client().submitPgmEdits(
            "648", "19mm Pre_Finished", "r1",
            listOf(PgmEditRow(name = "R1.pgm", secondPass = "standard", removePUnload = false))
        )
        check(result is PgmEditSubmitResult.Success)
        assertEquals("succeeded", result.response.files.single().status)
        assertEquals("R1.pgm", result.response.files.single().name)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val sentBody = org.json.JSONObject(recorded.body.readUtf8())
        assertEquals("r1", sentBody.getString("requestId"))
        val sentFiles = sentBody.getJSONArray("files")
        assertEquals(1, sentFiles.length())
        assertEquals("R1.pgm", sentFiles.getJSONObject(0).getString("name"))
        assertEquals("standard", sentFiles.getJSONObject(0).getString("secondPass"))
        assertEquals(false, sentFiles.getJSONObject(0).getBoolean("removePUnload"))
    }

    @Test
    fun `submitPgmEdits maps status codes to sealed results`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        check(client().submitPgmEdits("648", "M", "r1", emptyList()) is PgmEditSubmitResult.Disabled)

        server.enqueue(MockResponse().setResponseCode(409))
        check(client().submitPgmEdits("648", "M", "r2", emptyList()) is PgmEditSubmitResult.EditBusy)

        server.enqueue(MockResponse().setResponseCode(503))
        check(client().submitPgmEdits("648", "M", "r3", emptyList()) is PgmEditSubmitResult.CompileBusy)

        server.enqueue(MockResponse().setResponseCode(504))
        check(client().submitPgmEdits("648", "M", "r4", emptyList()) is PgmEditSubmitResult.WinxisoTimeout)
    }
}
