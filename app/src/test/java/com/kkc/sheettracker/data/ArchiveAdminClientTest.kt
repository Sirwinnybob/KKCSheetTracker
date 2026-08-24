package com.kkc.sheettracker.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ArchiveAdminClientTest {
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

    @Test
    fun `triggerArchive posts fail-only request and returns the operationId`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"operationId":"op-123"}""").setResponseCode(202))
        val client = ArchiveAdminClient(server.url("/").toString())
        val operationId = client.triggerArchive(folderName = "100 - Alpha", initiator = "tablet-7")
        assertEquals("op-123", operationId)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path?.contains("archive/100%20-%20Alpha") == true)
        assertEquals("fail", org.json.JSONObject(recorded.body.readUtf8()).getString("collisionChoice"))
    }

    @Test
    fun `triggerRestore posts fail-only request and returns the operationId`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"operationId":"op-456"}""").setResponseCode(202))
        val client = ArchiveAdminClient(server.url("/").toString())
        val operationId = client.triggerRestore(folderName = "100 - Alpha", initiator = "tablet-7")
        assertEquals("op-456", operationId)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path?.contains("restore/100%20-%20Alpha") == true)
        assertEquals("fail", org.json.JSONObject(recorded.body.readUtf8()).getString("collisionChoice"))
    }

    @Test
    fun `listArchivedFolderNames returns names only from the archive snapshot`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"archives":{"100 - Alpha":{"archiveJobId":"100 - Alpha","folderName":"100 - Alpha","jobNumber":"100","jobName":"Alpha","archivedAt":"2026-08-24T10:00:00Z"},"200 - Bravo":{"archiveJobId":"200 - Bravo","folderName":"200 - Bravo","jobNumber":"200","jobName":"Bravo","archivedAt":"2026-08-24T11:00:00Z"}}}"""
            ).setResponseCode(200)
        )
        val client = ArchiveAdminClient(server.url("/").toString())

        assertEquals(listOf("100 - Alpha", "200 - Bravo"), client.listArchivedFolderNames())

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/ready-jobs-archive/library", recorded.path)
    }

    @Test
    fun `getOperationStatus parses state`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"operationId":"op-123","state":"succeeded"}""").setResponseCode(200))
        val client = ArchiveAdminClient(server.url("/").toString())
        val status = client.getOperationStatus("op-123")
        assertEquals("succeeded", status?.state)
    }
}
