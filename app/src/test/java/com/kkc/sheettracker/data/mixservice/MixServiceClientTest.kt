package com.kkc.sheettracker.data.mixservice

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `listPgms parses inventory items`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""[{"name":"R1.pgm","size":100,"mtime":"2026-08-25T10:00:00Z"}]""")
        )
        val items = client().listPgms("648 - WIECHERT", "19mm Pre_Finished")
        assertEquals(listOf("R1.pgm"), items.map { it.name })
        val recorded = server.takeRequest()
        assertTrue(recorded.path?.endsWith("/jobs/648%20-%20WIECHERT/materials/19mm%20Pre_Finished/pgms") == true)
    }

    @Test
    fun `listPgms returns empty list on 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(emptyList<PgmInventoryItem>(), client().listPgms("648", "Unknown"))
    }

    @Test
    fun `listMixes returns null on failure, empty list on no mixes`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(null, client().listMixes("648", "19mm Pre_Finished"))

        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(emptyList<MixDefinition>(), client().listMixes("648", "19mm Pre_Finished"))
    }

    @Test
    fun `listMixes parses definitions and filters by job and material query params`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""[{"name":"KkcMix","job":"648","material":"19mm Pre_Finished","programs":["R1.pgm","R2.pgm"],"mixFilename":"KkcMix.mix","status":"compiled"}]""")
        )
        val mixes = client().listMixes("648", "19mm Pre_Finished")
        assertEquals("KkcMix", mixes?.single()?.name)
        assertEquals(listOf("R1.pgm", "R2.pgm"), mixes?.single()?.programs)
        val recorded = server.takeRequest()
        assertTrue(recorded.path?.contains("job=648") == true)
        assertTrue(recorded.path?.contains("material=19mm") == true)
    }
}
