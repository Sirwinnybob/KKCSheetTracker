package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HiddenMaterialsActionSubmitterTest {

    private lateinit var server: MockWebServer
    private lateinit var requestStore: HiddenMaterialsRequestStore
    private lateinit var baseDir: java.io.File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseDir = Files.createTempDirectory("hidden-materials-submitter").toFile()
        requestStore = HiddenMaterialsRequestStore(baseDir)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `writes the sidecar and posts the REST fast path when a server URL is available`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"applied":true}"""))

        submitHiddenMaterialsAction(
            serverUrl = server.url("/").toString().trimEnd('/'),
            requestStore = requestStore,
            mode = HiddenMaterialsMode.HARDWOODS,
            action = "hide",
            scope = "job",
            jobId = "123 - Job",
            docType = "NAILER_CUT_LIST",
            material = "Maple",
            tabletId = "tablet-1",
            requestedAt = "2026-09-08T18:00:00Z"
        )

        val sidecar = java.io.File(
            hiddenMaterialsJobPath(baseDir, HiddenMaterialsMode.HARDWOODS, "123 - Job").parentFile,
            "hidden_materials_request.tablet-1.json"
        )
        assertTrue("expected sidecar request file to exist", sidecar.exists())
        val recorded = server.takeRequest()
        assertTrue(recorded.path == "/api/admin-sync/hardwoods-hidden-materials")
    }

    @Test
    fun `writes the sidecar without attempting REST when no server URL is available`() = runBlocking {
        submitHiddenMaterialsAction(
            serverUrl = null,
            requestStore = requestStore,
            mode = HiddenMaterialsMode.SPECIALTY,
            action = "hide",
            scope = "global",
            jobId = null,
            docType = "DOOR_LIST",
            material = "Oak",
            tabletId = "tablet-1",
            requestedAt = "2026-09-08T18:00:00Z"
        )

        val sidecar = java.io.File(
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.SPECIALTY).parentFile,
            "hidden_materials_request.tablet-1.json"
        )
        assertTrue("expected sidecar request file to exist even with no server URL", sidecar.exists())
        assertTrue(server.requestCount == 0)
    }

    @Test
    fun `writes the sidecar even when the REST call fails`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        submitHiddenMaterialsAction(
            serverUrl = server.url("/").toString().trimEnd('/'),
            requestStore = requestStore,
            mode = HiddenMaterialsMode.HARDWOODS,
            action = "unhide",
            scope = "job",
            jobId = "123 - Job",
            docType = "NAILER_CUT_LIST",
            material = "Maple",
            tabletId = "tablet-1",
            requestedAt = "2026-09-08T18:00:00Z"
        )

        val sidecar = java.io.File(
            hiddenMaterialsJobPath(baseDir, HiddenMaterialsMode.HARDWOODS, "123 - Job").parentFile,
            "hidden_materials_request.tablet-1.json"
        )
        assertTrue("expected sidecar request file to exist despite REST failure", sidecar.exists())
    }

    @Test
    fun `still posts the REST fast path when the sidecar write throws`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"applied":true}"""))

        // scope = "job" with jobId = null makes HiddenMaterialsRequestStore.writeRequest's own
        // requireNotNull(jobId) throw before any file I/O -- a deterministic stand-in for a
        // transient write failure (VPN blip, disk-full, permission error).
        submitHiddenMaterialsAction(
            serverUrl = server.url("/").toString().trimEnd('/'),
            requestStore = requestStore,
            mode = HiddenMaterialsMode.HARDWOODS,
            action = "hide",
            scope = "job",
            jobId = null,
            docType = "NAILER_CUT_LIST",
            material = "Maple",
            tabletId = "tablet-1",
            requestedAt = "2026-09-08T18:00:00Z"
        )

        val recorded = server.takeRequest()
        assertTrue(recorded.path == "/api/admin-sync/hardwoods-hidden-materials")
    }
}
