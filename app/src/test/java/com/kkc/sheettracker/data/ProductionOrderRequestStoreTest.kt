package com.kkc.sheettracker.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProductionOrderRequestStoreTest {

    @Test
    fun writeRequest_writesPerTabletFileWithExpectedContents() {
        val baseDir = Files.createTempDirectory("production-order-request-store").toFile()
        val store = ProductionOrderRequestStore(baseDir)

        store.writeRequest(listOf("Job-1", "Job-2", "Job-3"), "tablet-1")

        // METADATA_AUDIT.md M-04: the request file is now keyed on tabletId, not a fixed name.
        val file = File(baseDir, "production_order_request.tablet-1.json")
        assertTrue("expected per-tablet request file to exist", file.exists())
        val payload = Gson().fromJson(file.readText(), ProductionOrderRequest::class.java)
        assertEquals(listOf("Job-1", "Job-2", "Job-3"), payload.order)
        assertEquals("tablet-1", payload.tabletId)
    }

    @Test
    fun writeRequest_overwritesThisTabletsOwnPriorRequest() {
        val baseDir = Files.createTempDirectory("production-order-request-store-overwrite").toFile()
        val store = ProductionOrderRequestStore(baseDir)

        store.writeRequest(listOf("Job-1"), "tablet-1")
        store.writeRequest(listOf("Job-1", "Job-2"), "tablet-1")

        val file = File(baseDir, "production_order_request.tablet-1.json")
        val payload = Gson().fromJson(file.readText(), ProductionOrderRequest::class.java)
        assertEquals(listOf("Job-1", "Job-2"), payload.order)
    }

    // ==================== M-04 regression: per-tablet filenames don't collide ====================

    @Test
    fun twoTabletsQueuingBeforeAPollBothProduceDistinctRequestFiles() {
        // Regression for METADATA_AUDIT.md M-04: two tablets reordering the job lineup before
        // the backend's next poll cycle used to collide on one shared
        // `production_order_request.json` (the second tablet's write silently clobbered the
        // first's, or Syncthing quarantined one copy as an unread `.sync-conflict-*` file). Each
        // tablet must now write its own file so both edits survive until the backend consumes
        // them (applying the newer one last, per the poller's timestamp ordering).
        val baseDir = Files.createTempDirectory("production-order-request-two-tablets").toFile()
        val store = ProductionOrderRequestStore(baseDir)

        store.writeRequest(listOf("Job-A", "Job-B"), "tablet-a")
        store.writeRequest(listOf("Job-B", "Job-A"), "tablet-b")

        val tabletAFile = File(baseDir, "production_order_request.tablet-a.json")
        val tabletBFile = File(baseDir, "production_order_request.tablet-b.json")
        assertTrue("tablet-a's request file is missing", tabletAFile.exists())
        assertTrue("tablet-b's request file is missing", tabletBFile.exists())

        val tabletAPayload = Gson().fromJson(tabletAFile.readText(), ProductionOrderRequest::class.java)
        val tabletBPayload = Gson().fromJson(tabletBFile.readText(), ProductionOrderRequest::class.java)
        assertEquals(listOf("Job-A", "Job-B"), tabletAPayload.order)
        assertEquals(listOf("Job-B", "Job-A"), tabletBPayload.order)
    }
}
