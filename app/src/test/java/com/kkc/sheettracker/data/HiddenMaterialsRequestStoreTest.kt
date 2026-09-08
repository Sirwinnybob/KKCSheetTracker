package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenMaterialsRequestStoreTest {

    @Test
    fun writeRequest_globalScope_writesToTheModesGlobalDirWithExpectedContents() {
        val baseDir = Files.createTempDirectory("hidden-materials-request-global").toFile()
        val store = HiddenMaterialsRequestStore(baseDir)

        store.writeRequest(
            mode = HiddenMaterialsMode.HARDWOODS,
            action = "hide",
            scope = "global",
            jobId = null,
            docType = "NAILER_CUT_LIST",
            material = "Maple",
            tabletId = "tablet-1",
            requestedAt = "2026-09-08T18:00:00Z"
        )

        val file = File(
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS).parentFile,
            "hidden_materials_request.tablet-1.json"
        )
        assertTrue("expected per-tablet request file to exist", file.exists())
        val payload = Gson().fromJson(file.readText(), HiddenMaterialsRequest::class.java)
        assertEquals("HARDWOODS", payload.mode)
        assertEquals("hide", payload.action)
        assertEquals("global", payload.scope)
        assertEquals("NAILER_CUT_LIST", payload.docType)
        assertEquals("Maple", payload.material)
        assertEquals("tablet-1", payload.tabletId)
        assertEquals(null, payload.jobId)
        assertEquals("2026-09-08T18:00:00Z", payload.requestedAt)
    }

    @Test
    fun writeRequest_jobScope_writesUnderThatJobsModeDir() {
        val baseDir = Files.createTempDirectory("hidden-materials-request-job").toFile()
        val store = HiddenMaterialsRequestStore(baseDir)

        store.writeRequest(
            mode = HiddenMaterialsMode.SPECIALTY,
            action = "hide",
            scope = "job",
            jobId = "123 - Job",
            docType = "DOOR_LIST",
            material = "Oak",
            tabletId = "tablet-1",
            requestedAt = "2026-09-08T18:00:00Z"
        )

        val file = File(
            hiddenMaterialsJobPath(baseDir, HiddenMaterialsMode.SPECIALTY, "123 - Job").parentFile,
            "hidden_materials_request.tablet-1.json"
        )
        assertTrue("expected per-tablet request file to exist", file.exists())
        val payload = Gson().fromJson(file.readText(), HiddenMaterialsRequest::class.java)
        assertEquals("123 - Job", payload.jobId)
    }

    @Test
    fun writeRequest_overwritesThisTabletsOwnPriorRequest() {
        val baseDir = Files.createTempDirectory("hidden-materials-request-overwrite").toFile()
        val store = HiddenMaterialsRequestStore(baseDir)

        store.writeRequest(
            mode = HiddenMaterialsMode.HARDWOODS, action = "hide", scope = "global", jobId = null,
            docType = "NAILER_CUT_LIST", material = "Maple", tabletId = "tablet-1", requestedAt = "t1"
        )
        store.writeRequest(
            mode = HiddenMaterialsMode.HARDWOODS, action = "unhide", scope = "global", jobId = null,
            docType = "NAILER_CUT_LIST", material = "Maple", tabletId = "tablet-1", requestedAt = "t2"
        )

        val file = File(
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS).parentFile,
            "hidden_materials_request.tablet-1.json"
        )
        val payload = Gson().fromJson(file.readText(), HiddenMaterialsRequest::class.java)
        assertEquals("unhide", payload.action)
        assertEquals("t2", payload.requestedAt)
    }

    @Test
    fun writeRequest_twoTabletsQueuingProduceDistinctRequestFiles() {
        val baseDir = Files.createTempDirectory("hidden-materials-request-two-tablets").toFile()
        val store = HiddenMaterialsRequestStore(baseDir)

        store.writeRequest(
            mode = HiddenMaterialsMode.HARDWOODS, action = "hide", scope = "global", jobId = null,
            docType = "NAILER_CUT_LIST", material = "Maple", tabletId = "tablet-a", requestedAt = "t1"
        )
        store.writeRequest(
            mode = HiddenMaterialsMode.HARDWOODS, action = "hide", scope = "global", jobId = null,
            docType = "DOOR_LIST", material = "Oak", tabletId = "tablet-b", requestedAt = "t2"
        )

        val dir = hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS).parentFile
        assertTrue(File(dir, "hidden_materials_request.tablet-a.json").exists())
        assertTrue(File(dir, "hidden_materials_request.tablet-b.json").exists())
    }
}
