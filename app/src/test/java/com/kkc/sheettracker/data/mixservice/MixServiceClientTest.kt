package com.kkc.sheettracker.data.mixservice

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
    }
}
