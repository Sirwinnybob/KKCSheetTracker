package com.kkc.sheettracker.data

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

class AdminSyncClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: AdminSyncClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AdminSyncClient(server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `applyProductionOrder returns canonical order on success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"order":["Job-A","Job-B"]}"""))

        val result = client.applyProductionOrder(listOf("Job-A", "Job-B"), "tablet-1")

        assertEquals(listOf("Job-A", "Job-B"), result)
        val recorded = server.takeRequest()
        assertEquals("/api/admin-sync/production-order", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"tabletId\":\"tablet-1\""))
    }

    @Test
    fun `applyProductionOrder returns null on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = client.applyProductionOrder(listOf("Job-A"), "tablet-1")

        assertNull(result)
    }

    @Test
    fun `applyJobBoardEdits returns true on success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"appliedCount":1}"""))

        val result = client.applyJobBoardEdits(
            listOf(JobBoardEdit(folderName = "Job-A", labelIds = listOf(1, 2))),
            "tablet-1"
        )

        assertTrue(result)
        val recorded = server.takeRequest()
        assertEquals("/api/admin-sync/job-board-edits", recorded.path)
        val bodyText = recorded.body.readUtf8()
        assertTrue(bodyText.contains("\"tabletId\":\"tablet-1\""))
        assertTrue(bodyText.contains("\"folderName\":\"Job-A\""))
    }

    @Test
    fun `applyJobBoardEdits returns false on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = client.applyJobBoardEdits(
            listOf(JobBoardEdit(folderName = "Job-A", boardSection = 1)),
            "tablet-1"
        )

        assertFalse(result)
    }

    @Test
    fun `applyDeliverySchedule returns parsed schedule on success`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"schemaVersion":1,"slots":{"monday_am":{"jobs":[{"jobNumber":"123","description":"Test Job"}]}}}"""
            )
        )

        val result = client.applyDeliverySchedule(
            DeliveryScheduleEditRequest(
                tabletId = "tablet-1",
                requestedAt = "2026-07-21T00:00:00Z",
                slotEdits = listOf(
                    DeliveryScheduleSlotEdit(
                        slot = "monday_am",
                        jobs = listOf(com.kkc.sheettracker.data.models.DeliveryJob(jobNumber = "123", description = "Test Job"))
                    )
                )
            )
        )

        assertEquals("123", result?.slot("monday", "am")?.jobs?.single()?.jobNumber)
        val recorded = server.takeRequest()
        assertEquals("/api/admin-sync/delivery-schedule", recorded.path)
        val bodyText = recorded.body.readUtf8()
        assertTrue(bodyText.contains("\"tabletId\":\"tablet-1\""))
        assertTrue(bodyText.contains("\"slot\":\"monday_am\""))
    }

    @Test
    fun `applyDeliverySchedule returns null when server is unreachable`() = runBlocking {
        val unreachableUrl = server.url("/").toString().trimEnd('/')
        server.shutdown()

        val result = AdminSyncClient(unreachableUrl).applyDeliverySchedule(
            DeliveryScheduleEditRequest(tabletId = "tablet-1", requestedAt = "2026-07-21T00:00:00Z", resetAll = true)
        )

        assertNull(result)
    }

    @Test
    fun `serverUrl with trailing slash is normalized correctly`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"order":["Job-A"]}"""))
        val clientWithTrailingSlash = AdminSyncClient(server.url("/").toString())

        val result = clientWithTrailingSlash.applyProductionOrder(listOf("Job-A"), "tablet-1")

        assertEquals(listOf("Job-A"), result)
        val recorded = server.takeRequest()
        assertEquals("/api/admin-sync/production-order", recorded.path)
    }
}
