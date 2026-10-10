package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HandoffCount
import com.kkc.sheettracker.data.models.HandoffFlagRef
import com.kkc.sheettracker.data.models.HandoffItemFlags
import com.kkc.sheettracker.data.models.HandoffTruck
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HandoffFlagsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HandoffFlagsClient
    private val folder = "670 - ESCHRICH 1364 VICTORIAN"

    @Before fun setUp() { server = MockWebServer().apply { start() }; client = HandoffFlagsClient(server.url("/").toString()) }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun putSendsOnlyTruckAndEncodesFolder() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"source":"checklist","id":"c-1","flags":{"truck":"loose","count":"qty"},"skipped":false}"""))
        val result = client.putTruck(folder, HandoffFlagRef("checklist", "c-1"), HandoffTruck.LOOSE, "2026-10-08T14:00:00Z")
        assertEquals(HandoffFlagPutResult.Applied(HandoffItemFlags(HandoffTruck.LOOSE, HandoffCount.QTY)), result)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/api/jobs/670%20-%20ESCHRICH%201364%20VICTORIAN/handoff/flags", recorded.path)
        val body = JSONObject(recorded.body.readUtf8())
        // keys() rather than keySet(): the Android stub on the compile classpath lacks keySet().
        val keys = mutableSetOf<String>().also { set -> body.keys().forEach { set.add(it) } }
        assertEquals(setOf("source", "id", "truck", "requestedAt"), keys)
        assertEquals("loose", body.getString("truck"))
    }

    @Test
    fun putAutoSendsExplicitNull() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"source":"checklist","id":"c-1","flags":null,"skipped":false}"""))
        val result = client.putTruck(folder, HandoffFlagRef("checklist", "c-1"), null, "2026-10-08T14:00:00Z")
        assertEquals(HandoffFlagPutResult.Applied(HandoffItemFlags()), result)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"truck\":null"))
    }

    @Test
    fun skippedAndFailures() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"source":"checklist","id":"c-1","flags":null,"skipped":true}"""))
        server.enqueue(MockResponse().setResponseCode(422))
        server.enqueue(MockResponse().setResponseCode(503))
        val ref = HandoffFlagRef("checklist", "c-1")
        assertEquals(HandoffFlagPutResult.Skipped, client.putTruck(folder, ref, HandoffTruck.SHOP, "2026-10-08T14:00:00Z"))
        assertEquals(HandoffFlagPutResult.Failed(422), client.putTruck(folder, ref, HandoffTruck.SHOP, "2026-10-08T14:00:00Z"))
        assertEquals(HandoffFlagPutResult.Failed(503), client.putTruck(folder, ref, HandoffTruck.SHOP, "2026-10-08T14:00:00Z"))
        server.shutdown()
        assertEquals(HandoffFlagPutResult.Failed(null), client.putTruck(folder, ref, HandoffTruck.SHOP, "2026-10-08T14:00:00Z"))
    }

    @Test
    fun truckListParsesAutoEffectiveAndExcluded() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"mode":"frameless","items":[
                {"source":"board_stock","sourceLabel":"Board stock","id":"5f0c","label":"Crown","detail":"","auto":{"truck":"loose","count":null},"flags":{},"effective":{"truck":"loose","count":null},"excluded":false},
                {"source":"checklist","id":"c-1","label":"Bumps","auto":{"truck":"shop","count":"qty"},"flags":{"truck":"later"},"effective":{"truck":"later","count":"qty"},"excluded":false},
                {"source":"specialty_items","id":"tablet:9a1e","label":"X","auto":{"truck":"loose"},"flags":{},"effective":{"truck":null},"excluded":true}
            ]}"""
        ))
        val list = client.fetchTruckList(folder)!!
        assertEquals("/api/jobs/670%20-%20ESCHRICH%201364%20VICTORIAN/handoff/truck-list", server.takeRequest().path)
        assertEquals(HandoffTruck.LOOSE, list.getValue(HandoffFlagRef("board_stock", "5f0c")).effectiveTruck)
        assertEquals(HandoffCount.QTY, list.getValue(HandoffFlagRef("checklist", "c-1")).effectiveCount)
        assertTrue(list.getValue(HandoffFlagRef("specialty_items", "tablet:9a1e")).excluded)
    }

    @Test
    fun truckListNullOnFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client.fetchTruckList(folder))
    }

    @Test
    fun malformedServerUrlDegradesInsteadOfThrowing() = runBlocking {
        val bad = HandoffFlagsClient("not a url")
        assertEquals(
            HandoffFlagPutResult.Failed(null),
            bad.putTruck(folder, HandoffFlagRef("checklist", "c-1"), HandoffTruck.SHOP, "2026-10-08T14:00:00Z")
        )
        assertNull(bad.fetchTruckList(folder))
    }
}
