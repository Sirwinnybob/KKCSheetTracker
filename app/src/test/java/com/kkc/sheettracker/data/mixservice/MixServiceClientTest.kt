package com.kkc.sheettracker.data.mixservice

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun `getMix parses the single definition and requests the singular route`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"mix":{"name":"KkcMix","job":"648","material":"19mm Pre_Finished","programs":["R1.pgm"],"mixFilename":"KkcMix.mix"},"status":"compiled"}"""
            )
        )
        val result = client().getMix("648", "19mm Pre_Finished")
        check(result is MixLookupResult.Found)
        assertEquals("KkcMix", result.definition.name)
        assertEquals("compiled", result.definition.status)
        val recorded = server.takeRequest()
        assertEquals("/jobs/648/materials/19mm%20Pre_Finished/mix", recorded.path)
    }

    @Test
    fun `getMix maps 404 to NotFound`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        check(client().getMix("648", "Unknown") is MixLookupResult.NotFound)
    }

    @Test
    fun `getMix maps 409 to Conflict with the sorted mix names`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"ok":false,"code":"multiple_mixes","names":["First","Second"]}""")
        )
        val result = client().getMix("648", "19mm Pre_Finished")
        check(result is MixLookupResult.Conflict)
        assertEquals(listOf("First", "Second"), result.names)
    }

    @Test
    fun `getPgmConflicts parses conflicts and requests job scope with programs joined by commas`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"conflicts":[{"pgm":"R1.pgm","mixName":"OtherMix"}]}"""
            )
        )
        val result = client().getPgmConflicts("648", "19mm Pre_Finished", listOf("R1.pgm", "R2.pgm"), exclude = "ThisMix")
        assertEquals(listOf(DuplicateMixWarning("R1.pgm", "OtherMix")), result)
        val recorded = server.takeRequest()
        assertTrue(recorded.path?.contains("programs=R1.pgm%2CR2.pgm") == true)
        assertTrue(recorded.path?.contains("scope=job") == true)
        assertTrue(recorded.path?.contains("exclude=ThisMix") == true)
    }

    @Test
    fun `getPgmConflicts returns empty list without a request when programs is empty`() = runBlocking {
        val result = client().getPgmConflicts("648", "19mm Pre_Finished", emptyList())
        assertEquals(emptyList<DuplicateMixWarning>(), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `getPgmConflicts returns null on failure so callers can fall back`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(client().getPgmConflicts("648", "19mm Pre_Finished", listOf("R1.pgm")))
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
    fun `submitMix unwraps a 202 operation`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody(
                """{"ok":true,"operation":{"id":"op1","kind":"mix_write","job":"648","material":"M","state":"queued","stage":"queued","completedPrograms":0,"totalPrograms":1}}"""
            )
        )

        val operation = client().submitMix("648", "M", "Mix", listOf("R1.pgm"))

        assertEquals("op1", operation.id)
        assertEquals("queued", operation.state)
        assertEquals("M", operation.material)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/mixes", recorded.requestUrl?.encodedPath)
    }

    @Test
    fun `submitMix rejects an accepted envelope with a blank operation id`() {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody(
                """{"ok":true,"operation":{"id":"  ","kind":"mix_write","job":"648","material":"M","state":"queued","stage":"queued"}}"""
            )
        )

        assertThrows(MixOperationClientException::class.java) {
            runBlocking { client().submitMix("648", "M", "Mix", listOf("R1.pgm")) }
        }
    }

    @Test
    fun `submitMix rejects an accepted envelope with a missing operation id`() {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody(
                """{"ok":true,"operation":{"kind":"mix_write","job":"648","material":"M","state":"queued","stage":"queued"}}"""
            )
        )

        assertThrows(MixOperationClientException::class.java) {
            runBlocking { client().submitMix("648", "M", "Mix", listOf("R1.pgm")) }
        }
    }

    @Test
    fun `submitMix replaces an existing named mix through its operation route`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody(
                """{"ok":true,"operation":{"id":"op-replace","kind":"mix_write","job":"648","material":"M","state":"queued","stage":"queued"}}"""
            )
        )

        val operation = client().submitMix("648", "M", "Existing", listOf("R1.pgm"), replaceExisting = true)

        assertEquals("op-replace", operation.id)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/mixes/Existing", recorded.requestUrl?.encodedPath)
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("648", body.getString("job"))
        assertFalse(body.has("name"))
        assertFalse(body.has("overwrite"))
    }

    @Test
    fun `submitPgmEdits unwraps a 202 operation and preserves request body`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody(
                """{"ok":true,"operation":{"id":"op2","kind":"pgm_edit","job":"648","material":"M","state":"queued","stage":"queued","completedPrograms":0,"totalPrograms":1}}"""
            )
        )
        val result = client().submitPgmEdits(
            "648", "M", "r1",
            listOf(PgmEditRow(name = "R1.pgm", secondPass = "standard", removePUnload = false))
        )
        assertEquals("op2", result.id)
        assertEquals("queued", result.state)
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
    fun `operation reads preserve structured warning recoveries and job operation list`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"operation":{"id":"op1","kind":"mix_write","job":"648","material":"M","state":"completed","stage":"completed","completedPrograms":1,"totalPrograms":1,"warning":{"code":"history_sync_failed","message":"history unavailable","recoveries":[{"url":"/jobs/648/materials/M/mix-history/sync","method":"POST","change":{"historyFile":".pgm_edit_history.json","attempt":2}}]}}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"operations":[{"id":"op1","kind":"mix_write","job":"648","material":"M","state":"completed","stage":"completed","completedPrograms":1,"totalPrograms":1}]}"""
            )
        )

        val operation = client().getOperation("op1")
        val operations = client().listJobOperations("648")

        assertEquals("history_sync_failed", operation.warning?.code)
        val recovery = operation.warning?.recoveries?.single()
        assertEquals("/jobs/648/materials/M/mix-history/sync", recovery?.url)
        assertEquals("POST", recovery?.method)
        assertEquals(".pgm_edit_history.json", recovery?.change?.get("historyFile"))
        assertEquals(2, (recovery?.change?.get("attempt") as Number).toInt())
        assertEquals(listOf("op1"), operations.map { it.id })
        assertEquals("/operations/op1", server.takeRequest().requestUrl?.encodedPath)
        assertEquals("/jobs/648/operations", server.takeRequest().requestUrl?.encodedPath)
    }
}
