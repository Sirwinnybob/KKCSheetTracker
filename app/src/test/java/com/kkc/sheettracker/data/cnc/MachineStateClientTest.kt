package com.kkc.sheettracker.data.cnc

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MachineStateClientTest {
    private fun withServer(block: (MockWebServer, MachineStateClient) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            block(server, MachineStateClient(server.url("/").toString()))
        }
    }
    @Test fun parsesMaestroDisplayStateWithoutChangingGuardContract() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"ok":true,"producing":null,"displayProducing":true}"""))
        assertEquals(true, runBlocking { client.fetch().getOrThrow().producing })
    }
    @Test fun parsesRealLoadedProgram() = withServer { server, client ->
        val body = javaClass.getResource("/cnc/machine-state.json")!!.readText()
        server.enqueue(MockResponse().setBody(body))
        val state = runBlocking { client.fetch().getOrThrow() }
        assertEquals("R82010BN", state.currentProgram?.name)
        assertEquals("709 - BRAD TAYLOR", state.currentProgram?.jobFolder)
        assertEquals(true, state.currentProgram?.running)
        assertEquals("2026-10-29", state.reportDate)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/machine/state", request.path)
    }
    @Test fun nullAndMissingFieldsAreSafe() = withServer { server, client ->
        listOf("""{"ok":true}""", """{"ok":true,"currentProgram":null,"lastProgram":null}""",
            """{"ok":true,"currentProgram":{"name":null}}""").forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            val state = runBlocking { client.fetch().getOrThrow() }
            assertNull(state.currentProgram)
            assertNull(state.lastProgram)
        }
    }
    @Test fun extraAndMalformedOptionalFieldsAreIgnored() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"ok":true,"future":{"x":3},"producing":{},"currentProgram":{"name":"R1","running":[],"candidates":null},"lastProgram":[]}"""))
        val state = runBlocking { client.fetch().getOrThrow() }
        assertEquals("R1", state.currentProgram?.name)
        assertNull(state.currentProgram?.running)
        assertTrue(state.currentProgram!!.candidates.isEmpty())
        assertNull(state.producing)
        assertNull(state.lastProgram)
    }
    @Test fun parsesAmbiguousCandidates() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"ok":true,"currentProgram":{"name":"R1","ambiguous":true,"candidates":[null,{},{"jobFolder":"709 - BRAD TAYLOR","path":"C:\\jobs\\709 - BRAD TAYLOR\\R1.pgm"}]}}"""))
        val program = runBlocking { client.fetch().getOrThrow().currentProgram!! }
        assertTrue(program.ambiguous)
        assertEquals(1, program.candidates.size)
        assertEquals("709 - BRAD TAYLOR", program.candidates.single().jobFolder)
    }
    @Test fun finishedProgramNeedsNoJobFolder() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"ok":true,"lastProgram":{"name":"R82010BN.PGM","path":"C:\\jobs\\709 - BRAD TAYLOR\\R82010BN.pgm","start":"07:00:00","end":"07:04:59"}}"""))
        val program = runBlocking { client.fetch().getOrThrow().lastProgram!! }
        assertEquals("R82010BN.PGM", program.name)
        assertEquals("07:04:59", program.end)
    }
    @Test fun invalidEnvelopesFail() = withServer { server, client ->
        listOf(201 to """{"ok":true}""", 500 to "{}", 200 to "", 200 to "null",
            200 to "{", 200 to """{"ok":false}""", 200 to "{}", 200 to """{"ok":null}""",
            200 to """{"ok":"true"}""").forEach { (code, body) ->
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
            assertTrue("$code $body", runBlocking { client.fetch().isFailure })
        }
    }
    @Test fun cancellationCancelsCall() = withServer { server, _ ->
        val cancelled = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        runBlocking {
            val work = launch(Dispatchers.IO) { MachineStateClient(server.url("/").toString(), http).fetch() }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            work.cancelAndJoin()
        }
        assertTrue(cancelled.await(3, TimeUnit.SECONDS))
    }
}

