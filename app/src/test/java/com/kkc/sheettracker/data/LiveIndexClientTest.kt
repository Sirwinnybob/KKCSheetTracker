package com.kkc.sheettracker.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveIndexClientTest {

    @Test
    fun `backoff doubles from 1s and caps at 30s`() {
        assertEquals(1_000L, nextBackoffDelayMs(0))
        assertEquals(2_000L, nextBackoffDelayMs(1))
        assertEquals(4_000L, nextBackoffDelayMs(2))
        assertEquals(8_000L, nextBackoffDelayMs(3))
        assertEquals(16_000L, nextBackoffDelayMs(4))
        assertEquals(30_000L, nextBackoffDelayMs(5))
        assertEquals(30_000L, nextBackoffDelayMs(6))
        assertEquals(30_000L, nextBackoffDelayMs(100))
    }
}

class LiveIndexEnvelopeParsingTest {

    private val gson = Gson()

    @Test
    fun `parses a snapshot envelope`() {
        val json = """
            {"type":"snapshot","serverInstanceId":"abc123","revision":7,
             "jobs":{"1234 - Test Job":{"jobInfo":{"folderName":"1234 - Test Job",
             "jobNumber":"1234","jobName":"Test Job","hiddenFromProduction":false,
             "lineupPosition":2},"progressSummary":{"cnc":null,"hardwoods":null,
             "hasDeliverySheet":false,"has3DAssets":false}}}}
        """.trimIndent()

        val envelope = gson.fromJson(json, LiveIndexEnvelope::class.java)

        assertEquals("snapshot", envelope.type)
        assertEquals(7L, envelope.revision)
        val job = envelope.jobs?.get("1234 - Test Job")
        assertEquals("1234", job?.jobInfo?.jobNumber)
    }

    @Test
    fun `parses an upsert delta envelope`() {
        val json = """
            {"type":"delta","delta":{"type":"upsert","folderName":"1234 - Test Job",
             "revision":8,"index":{"jobInfo":{"folderName":"1234 - Test Job",
             "jobNumber":"1234","jobName":"Test Job","hiddenFromProduction":false,
             "lineupPosition":2},"progressSummary":null}}}
        """.trimIndent()

        val envelope = gson.fromJson(json, LiveIndexEnvelope::class.java)

        assertEquals("delta", envelope.type)
        assertEquals("upsert", envelope.delta?.type)
        assertEquals("1234 - Test Job", envelope.delta?.folderName)
        assertEquals("1234", envelope.delta?.index?.jobInfo?.jobNumber)
    }

    @Test
    fun `parses a remove delta envelope with a null index`() {
        val json = """{"type":"delta","delta":{"type":"remove","folderName":"1234 - Test Job","revision":9,"index":null}}"""

        val envelope = gson.fromJson(json, LiveIndexEnvelope::class.java)

        assertEquals("remove", envelope.delta?.type)
        assertEquals(null, envelope.delta?.index)
    }

    @Test
    fun `parses not_running and error envelopes`() {
        val notRunning = gson.fromJson("""{"type":"not_running"}""", LiveIndexEnvelope::class.java)
        assertEquals("not_running", notRunning.type)

        val error = gson.fromJson("""{"type":"error","message":"expected a hello message first"}""", LiveIndexEnvelope::class.java)
        assertEquals("error", error.type)
        assertEquals("expected a hello message first", error.message)
    }
}
