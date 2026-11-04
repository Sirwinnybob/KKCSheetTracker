package com.kkc.sheettracker.data.mixservice

import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class PgmPreviewClientTest {
    @Test fun previewPostsEncodedPathAndShaInventoryIsOptIn() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"pgms":[{"name":"P.pgm","sha256":"${"a".repeat(64)}"}]}"""))
            val api = MixServiceClient(com.kkc.sheettracker.data.cnc.CncMachines.scm.copy(baseUrl = server.url("/").toString()))
            assertEquals("a".repeat(64), api.listPgms("Job +","M",withSha256=true).single().sha256)
            assertEquals("true",server.takeRequest().requestUrl!!.queryParameter("withSha256"))
            server.enqueue(MockResponse().setBody("""{"ok":true,"features":["pgm-edit-preview"]}"""))
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"operation":{"id":"preview-1","kind":"pgm_preview"}}"""))
            api.submitPgmPreview("Job +","M","r1",PgmEditRow("P.pgm","standard",false),false)
            assertEquals("/status",server.takeRequest().path)
            val request=server.takeRequest()
            assertEquals(listOf("jobs","Job +","materials","M","pgm-edits","preview"),request.requestUrl!!.pathSegments)
            assertFalse(request.body.readUtf8().contains("expectedSourceSha256"))
        }
    }

    @Test fun latin1SourceAndCrLfSurviveJsonAndHashValidation() {
        val source="; café  \r\n"
        val hash=MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }
        val json="""{"ok":true,"operation":"preview","file":{"name":"P.pgm","originalSha256":"${"a".repeat(64)}","baseSha256":"${"a".repeat(64)}","proposedSourceSha256":"$hash","currentSource":${Gson().toJson(source)},"proposedSource":${Gson().toJson(source)},"diff":"","requested":{"mode":"standard","removePUnload":false,"restoreOriginal":false},"before":{"mode":"none","punloadRemoved":false},"proposed":{"mode":"standard","punloadRemoved":false},"conversion":{"decode":"rust","encode":"rust","fallbackReasons":[]},"notices":[]}}"""
        assertEquals(source,decodePgmPreview(Gson().fromJson(json,Any::class.java)).file.proposedSource)
        assertThrows(IllegalStateException::class.java) { decodePgmPreview(Gson().fromJson(json.replace(hash,"f".repeat(64)),Any::class.java)) }
    }

    @Test fun legacyEditRowsOmitOptionalRevisionFields() {
        val json=Gson().toJson(PgmEditRow("P.pgm","standard",false))
        assertFalse(json.contains("expectedSha256"))
        assertFalse(json.contains("expectedSourceSha256"))
    }

    @Test fun oldServiceReceivesNoPreviewMutation() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"features":[]}"""))
            val result=runCatching { MixServiceClient(com.kkc.sheettracker.data.cnc.CncMachines.scm.copy(baseUrl = server.url("/").toString()))
                .submitPgmPreview("J","M","id",PgmEditRow("P.pgm","standard",false)) }
            assertTrue(result.isFailure)
            assertEquals(1,server.requestCount)
            assertEquals("/status",server.takeRequest().path)
        }
    }

    @Test fun previewRefusesAServiceIdentifyingAsAnotherMachine() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"features":["pgm-edit-preview"],"machineId":"scm","machineName":"Red SCM"}"""))
            val result=runCatching { MixServiceClient(com.kkc.sheettracker.data.cnc.CncMachines.scmNew.copy(baseUrl = server.url("/").toString()))
                .submitPgmPreview("J","M","id",PgmEditRow("P.pgm","standard",false)) }
            assertTrue(result.exceptionOrNull()!!.message!!.contains("Blue SCM"))
            assertEquals(1,server.requestCount)
        }
    }
}
