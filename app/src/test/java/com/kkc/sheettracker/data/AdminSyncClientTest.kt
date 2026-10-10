package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialsMode
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
    fun `applyDeliveryScheduleEdits surfaces failures`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"schemaVersion":2,"weekOf":"2026-10-12","slots":{},"datedSlots":{},
                "failures":[{"slot":"2026-10-21_pm","jobNumber":"670","folderName":"670 - E","status":409,"reason":"full"}]}"""
        ))
        val result = client.applyDeliveryScheduleEdits(
            DeliveryScheduleEditRequest("T1", "2026-10-09T17:00:00Z", slotEdits = listOf(
                DeliveryScheduleSlotEdit("2026-10-21_pm", listOf(com.kkc.sheettracker.data.models.DeliveryJob("670", "E", folderName = "670 - E")))
            ))
        )
        assertEquals(409, result!!.failures.single().status)
        assertTrue(result.schedule.isDated)
        val body = org.json.JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("2026-10-21_pm", body.getJSONArray("slotEdits").getJSONObject(0).getString("slot"))
    }

    @Test
    fun `applyDeliveryScheduleEdits returns null on 503 so caller falls back to request file`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertNull(client.applyDeliveryScheduleEdits(DeliveryScheduleEditRequest("T1", "2026-10-09T17:00:00Z")))
    }

    @Test
    fun `setDeliveryJobDate puts date and window with encoded folder`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"schedule":{},"datedSlots":{"2026-10-21_pm":{"jobs":[{"jobNumber":"670","description":"E","folderName":"670 - ESCHRICH 1364 VICTORIAN"}]}},"weekOf":"2026-10-19","failures":[]}"""
        ))
        val result = client.setDeliveryJobDate("670 - ESCHRICH 1364 VICTORIAN", java.time.LocalDate.of(2026, 10, 21), "pm", "T1")
        assertTrue(result is DeliveryJobDateResult.Applied)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/api/delivery-schedule/jobs/670%20-%20ESCHRICH%201364%20VICTORIAN", recorded.path)
        val body = org.json.JSONObject(recorded.body.readUtf8())
        assertEquals("2026-10-21", body.getString("date"))
        assertEquals("pm", body.getString("window"))
        assertEquals("T1", body.getString("tabletId"))
    }

    @Test
    fun `setDeliveryJobDate without a half-day omits window and keeps tabletId`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"schedule":{},"datedSlots":{},"weekOf":"2026-10-12","failures":[]}"""))
        client.setDeliveryJobDate("670 - E", java.time.LocalDate.of(2026, 10, 21), null, "T1")
        val body = org.json.JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("2026-10-21", body.getString("date"))
        assertFalse(body.has("window"))
        assertEquals("T1", body.getString("tabletId"))
    }

    @Test
    fun `setDeliveryJobDate clear sends object with null date and tabletId`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"schedule":{},"datedSlots":{},"weekOf":"2026-10-12","failures":[]}"""))
        client.setDeliveryJobDate("670 - E", null, null, "T1")
        val body = org.json.JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(body.has("date"))
        assertTrue(body.isNull("date"))
        assertFalse(body.has("window"))
        assertEquals("T1", body.getString("tabletId"))
    }

    @Test
    fun `setDeliveryJobDate clear retries once with bare null body when object form is 422`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":[{"loc":["body"],"msg":"bad","type":"x"}]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"schedule":{},"datedSlots":{},"weekOf":"2026-10-12","failures":[]}"""
        ))
        val result = client.setDeliveryJobDate("670 - E", null, null, "T1")
        assertTrue(result is DeliveryJobDateResult.Applied)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest().body.readUtf8()
        assertTrue(org.json.JSONObject(first).isNull("date"))
        assertEquals("null", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `setDeliveryJobDate clear retry maps the second response with the normal rules`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"unknown job folder"}"""))
        val result = client.setDeliveryJobDate("670 - E", null, null, "T1")
        assertEquals(DeliveryJobDateResult.Refused(404, "unknown job folder"), result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `setDeliveryJobDate set that gets 422 is not retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422))
        val result = client.setDeliveryJobDate("670 - E", java.time.LocalDate.of(2026, 10, 21), "am", "T1")
        assertTrue(result is DeliveryJobDateResult.Refused)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `setDeliveryJobDate maps refusals and unavailability`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Fri 10/16 AM already has 3 deliveries"}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Folder not found"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"Job is closed"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        val d = java.time.LocalDate.of(2026, 10, 16)
        assertEquals(DeliveryJobDateResult.Refused(409, "Fri 10/16 AM already has 3 deliveries"), client.setDeliveryJobDate("a", d, null, "T1"))
        assertEquals(DeliveryJobDateResult.Refused(404, "Folder not found"), client.setDeliveryJobDate("a", d, null, "T1"))
        assertEquals(DeliveryJobDateResult.Refused(400, "Job is closed"), client.setDeliveryJobDate("a", d, null, "T1"))
        assertEquals(DeliveryJobDateResult.Unavailable, client.setDeliveryJobDate("a", d, null, "T1"))
    }

    @Test
    fun `setDeliveryJobDate normalizes FastAPI 422 list detail to msg text`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody(
            """{"detail":[{"loc":["body","date"],"msg":"invalid date","type":"value_error"},{"loc":["body","window"],"msg":"bad window","type":"value_error"}]}"""
        ))
        val d = java.time.LocalDate.of(2026, 10, 16)
        assertEquals(DeliveryJobDateResult.Refused(422, "invalid date; bad window"), client.setDeliveryJobDate("a", d, null, "T1"))
    }

    @Test
    fun `setDeliveryJobDate uses status fallback for empty or non-JSON error bodies`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(MockResponse().setResponseCode(404).setBody("<html>Not Found</html>"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":""}"""))
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":[{"loc":["body"]}]}"""))
        val d = java.time.LocalDate.of(2026, 10, 16)
        assertEquals(DeliveryJobDateResult.Refused(409, "That day already has 6 deliveries"), client.setDeliveryJobDate("a", d, null, "T1"))
        assertEquals(DeliveryJobDateResult.Refused(404, "No office job for this folder"), client.setDeliveryJobDate("a", d, null, "T1"))
        assertEquals(DeliveryJobDateResult.Refused(400, "The office job is closed or the request was invalid"), client.setDeliveryJobDate("a", d, null, "T1"))
        assertEquals(DeliveryJobDateResult.Refused(422, "The office job is closed or the request was invalid"), client.setDeliveryJobDate("a", d, null, "T1"))
    }

    @Test
    fun `setDeliveryJobDate 2xx without a v2 document is AcceptedWithoutDocument`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"schemaVersion":1,"slots":{}}"""))
        val d = java.time.LocalDate.of(2026, 10, 16)
        repeat(4) {
            assertEquals(DeliveryJobDateResult.AcceptedWithoutDocument, client.setDeliveryJobDate("a", d, null, "T1"))
        }
    }

    @Test
    fun `setDeliveryJobDate with malformed server url is Unavailable`() = runBlocking {
        val result = AdminSyncClient("not a url").setDeliveryJobDate("a", java.time.LocalDate.of(2026, 10, 16), null, "T1")
        assertEquals(DeliveryJobDateResult.Unavailable, result)
    }

    @Test
    fun `applyDeliveryScheduleEdits returns null on 2xx without any schedule object`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"failures":[]}"""))
        val req = DeliveryScheduleEditRequest("T1", "2026-10-09T17:00:00Z")
        assertNull(client.applyDeliveryScheduleEdits(req))
        assertNull(client.applyDeliveryScheduleEdits(req))
        assertNull(client.applyDeliveryScheduleEdits(req))
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

    @Test
    fun `applyHiddenMaterialsAction returns true on success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"applied":true}"""))

        val result = client.applyHiddenMaterialsAction(
            mode = HiddenMaterialsMode.HARDWOODS,
            action = "hide",
            scope = "global",
            docType = "NAILER_CUT_LIST",
            material = "Maple",
            tabletId = "tablet-1",
            jobId = null,
            requestedAt = "2026-09-08T18:00:00Z"
        )

        assertTrue(result)
        val recorded = server.takeRequest()
        assertEquals("/api/admin-sync/hardwoods-hidden-materials", recorded.path)
        val bodyText = recorded.body.readUtf8()
        assertTrue(bodyText.contains("\"mode\":\"HARDWOODS\""))
        assertTrue(bodyText.contains("\"tabletId\":\"tablet-1\""))
        assertTrue(bodyText.contains("\"requestedAt\":\"2026-09-08T18:00:00Z\""))
        assertFalse(bodyText.contains("jobId"))
    }

    @Test
    fun `applyHiddenMaterialsAction includes jobId when scope is job`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"applied":true}"""))

        client.applyHiddenMaterialsAction(
            mode = HiddenMaterialsMode.SPECIALTY,
            action = "hide",
            scope = "job",
            docType = "DOOR_LIST",
            material = "Oak",
            tabletId = "tablet-1",
            jobId = "123 - Job",
            requestedAt = "2026-09-08T18:00:00Z"
        )

        val bodyText = server.takeRequest().body.readUtf8()
        assertTrue(bodyText.contains("\"jobId\":\"123 - Job\""))
    }

    @Test
    fun `applyHiddenMaterialsAction returns false on server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))

        val result = client.applyHiddenMaterialsAction(
            mode = HiddenMaterialsMode.HARDWOODS,
            action = "hide",
            scope = "global",
            docType = "NAILER_CUT_LIST",
            material = "Maple",
            tabletId = "tablet-1",
            jobId = null,
            requestedAt = "2026-09-08T18:00:00Z"
        )

        assertFalse(result)
    }
}
