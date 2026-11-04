package com.kkc.sheettracker.data.mixservice

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun client(machine: com.kkc.sheettracker.data.cnc.CncMachine = com.kkc.sheettracker.data.cnc.CncMachines.scm) =
        MixServiceClient(machine.copy(baseUrl = server.url("/").toString()))
    private fun status(body: String = """{"ok":true,"features":["pgm-edits-revert"]}""") = server.enqueue(MockResponse().setBody(body))

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
    fun `submitPgmEdits unwraps a 202 operation and preserves request body`() = runBlocking {
        status()
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
        assertEquals("/status", server.takeRequest().path)
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
    fun `submitPgmEdits rejects unaccepted or unidentified operations`() {
        val responses = listOf(
            MockResponse().setResponseCode(200).setBody("""{"ok":true,"operation":{"id":"op"}}"""),
            MockResponse().setResponseCode(202).setBody("""{"ok":true,"operation":{"id":""}}"""),
            MockResponse().setResponseCode(202).setBody("""{"ok":true,"operation":{}}"""),
        )
        responses.forEach { response ->
            status()
            server.enqueue(response)
            assertThrows(MixOperationClientException::class.java) {
                runBlocking { client().submitPgmEdits("J", "M", "r", listOf(PgmEditRow("R1.pgm", "standard", false))) }
            }
        }
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `submitPgmEdits refuses a mismatched or read-only service before posting`() {
        val blue = com.kkc.sheettracker.data.cnc.CncMachines.scmNew
        listOf(
            """{"ok":true,"features":[],"machineId":"scm","machineName":"Red SCM","capabilities":["MIX_FILES","SECOND_PASS"],"readOnly":false}""",
            """{"ok":true,"features":[],"machineId":"scm-new","machineName":"Blue SCM","capabilities":["MIX_FILES","SECOND_PASS"],"readOnly":true}""",
            """{"ok":true,"features":[]}""",
        ).forEach { body ->
            status(body)
            val failure = assertThrows(MixOperationClientException::class.java) {
                runBlocking { client(blue).submitPgmEdits("J", "M", "r", listOf(PgmEditRow("R1.pgm", "standard", true))) }
            }
            assertTrue(failure.message!!.contains("Blue SCM"))
            assertEquals("/status", server.takeRequest().path)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `submitPgmEdits posts to a verified Blue service`() = runBlocking {
        status("""{"ok":true,"features":["pgm-edits-revert"],"machineId":"scm-new","machineName":"Blue SCM","capabilities":["MIX_FILES","SECOND_PASS"],"readOnly":false}""")
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"operation":{"id":"blue-op","state":"queued"}}"""))
        assertEquals("blue-op", client(com.kkc.sheettracker.data.cnc.CncMachines.scmNew).submitPgmEdits("J", "M", "r", listOf(PgmEditRow("R1.pgm", "standard", true))).id)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `operation polling preserves structured warning recoveries`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"operation":{"id":"op1","kind":"mix_write","job":"648","material":"M","state":"completed","stage":"completed","completedPrograms":1,"totalPrograms":1,"warning":{"code":"history_sync_failed","message":"history unavailable","recoveries":[{"url":"/jobs/648/materials/M/mix-history/sync","method":"POST","change":{"historyFile":".pgm_edit_history.json","attempt":2}}]}}}"""
            )
        )
        val operation = client().getOperation("op1")

        assertEquals("history_sync_failed", operation.warning?.code)
        val recovery = operation.warning?.recoveries?.single()
        assertEquals("/jobs/648/materials/M/mix-history/sync", recovery?.url)
        assertEquals("POST", recovery?.method)
        assertEquals(".pgm_edit_history.json", recovery?.change?.get("historyFile"))
        assertEquals(2, (recovery?.change?.get("attempt") as Number).toInt())
        assertEquals("/operations/op1", server.takeRequest().requestUrl?.encodedPath)
    }
}
