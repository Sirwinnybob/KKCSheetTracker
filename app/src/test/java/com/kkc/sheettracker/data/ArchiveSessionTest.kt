package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.SpecialtyItemCategory
import com.kkc.sheettracker.data.models.SpecialtyStation
import com.kkc.sheettracker.data.models.TabletSpecialtyItem
import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.SheetStatus
import com.kkc.sheettracker.data.models.ScanStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ArchiveSessionTest {
    @Test
    fun `session is constructed with readOnly stores pointed at the cache job dir`() {
        val cacheRoot = Files.createTempDirectory("archive-session-test").toFile()
        cacheRoot.resolve("100 - Alpha").also { it.mkdirs() }

        val session = ArchiveSession.create(
            archiveJobId = "100 - Alpha",
            contentVersion = "v1",
            cacheJobParentDir = cacheRoot,
            folderName = "100 - Alpha",
            tabletId = "tablet-7",
            isDebugBuild = true,
        )

        try {
            assertEquals("100 - Alpha", session.archiveJobId)
            assertEquals("v1", session.contentVersion)
            assertTrue(session.readOnly)
            assertEquals(cacheRoot, session.baseDir)
            assertEquals("100 - Alpha", session.folderName)
        } finally {
            session.close()
        }
    }

    @Test
    fun `folderName and baseDir resolve correctly across repeated create calls for the same parent dir`() {
        // Regression test for the bug where folderName was re-derived by listing
        // cacheJobParentDir's children: ProgressStore's own init{} creates a second directory
        // (".state") under that same parent as a side effect of construction, so a second
        // ArchiveSession.create call for the same cacheJobParentDir (e.g. user navigates away
        // from the archive job screen and back) previously risked non-deterministically
        // resolving folderName to ".state" instead of the real job folder, since File.listFiles()
        // order is not guaranteed. Passing folderName explicitly eliminates the directory scan
        // entirely, so this must hold on every call, not just the first.
        val cacheRoot = Files.createTempDirectory("archive-session-test-repeat").toFile()
        cacheRoot.resolve("200 - Beta").also { it.mkdirs() }

        val first = ArchiveSession.create(
            archiveJobId = "200 - Beta",
            contentVersion = "v1",
            cacheJobParentDir = cacheRoot,
            folderName = "200 - Beta",
            tabletId = "tablet-7",
            isDebugBuild = true,
        )
        try {
            assertEquals("200 - Beta", first.folderName)
            assertEquals(cacheRoot, first.baseDir)

            // Read-only archive stores must not create a local state directory in the extracted
            // cache slot; passing folderName explicitly still eliminates directory re-discovery.
            assertFalse(cacheRoot.resolve(".state").exists())
        } finally {
            first.close()
        }

        val second = ArchiveSession.create(
            archiveJobId = "200 - Beta",
            contentVersion = "v1",
            cacheJobParentDir = cacheRoot,
            folderName = "200 - Beta",
            tabletId = "tablet-7",
            isDebugBuild = true,
        )
        try {
            assertEquals("200 - Beta", second.folderName)
            assertEquals(cacheRoot, second.baseDir)
        } finally {
            second.close()
        }
    }

    @Test
    fun `session exposes archive scoped dependencies and all writes are no ops`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("archive-session-graph").toFile()
        cacheRoot.resolve("1234 - Test Job").mkdirs()

        val session = ArchiveSession.create(
            archiveJobId = "archive-1234",
            contentVersion = "v1",
            cacheJobParentDir = cacheRoot,
            folderName = "1234 - Test Job",
            tabletId = "tablet-7",
            isDebugBuild = true,
        )

        try {
            val folderName = session.folderName
            val expectedBasePath = cacheRoot.absolutePath

            assertEquals(cacheRoot, session.jobRepository.getJobDirectory(folderName).parentFile)
            assertEquals(expectedBasePath, session.hardwoodsRepository.currentBasePath())
            assertEquals(expectedBasePath, session.specialtyRepository.currentBasePath())
            assertEquals(expectedBasePath, session.scanCoordinator.state.value.snapshot.basePath)
            assertEquals(expectedBasePath, session.assemblyScanCoordinator.state.value.snapshot.basePath)
            assertEquals(expectedBasePath, session.specialtyScanCoordinator.state.value.snapshot.basePath)

            session.hardwoodsScanCoordinator.refresh(RefreshReason.APP_START, force = true)
            withTimeout(2_000L) {
                while (session.hardwoodsScanCoordinator.state.value.status != ScanStatus.READY &&
                    session.hardwoodsScanCoordinator.state.value.status != ScanStatus.ERROR
                ) {
                    delay(10L)
                }
            }
            assertEquals(expectedBasePath, session.hardwoodsScanCoordinator.state.value.snapshot.basePath)

            session.progressStore.markSheetComplete(folderName, "sheet.pdf", page = 1, fileFingerprint = "fp")
            session.hardwoodsProgressStore.setDoneCount(folderName, "CUTLIST", "row-1", qty = 1, doneCount = 1)
            session.specialtyProgressStore.setCompletion(folderName, "item-1", "ITEM", completed = true)
            session.pdfMarkupStore.savePageMarkup(
                jobFolderName = folderName,
                pdfFilename = "sheet.pdf",
                page = 1,
                strokes = emptyList(),
                deletedStrokeIds = emptyList(),
            )
            session.sheetRipProgressStore.setDone(folderName, "rip-1", done = true)
            session.tabletSpecialtyItemsStore.saveItem(
                folderName,
                TabletSpecialtyItem(
                    id = "item-1",
                    name = "Test Item",
                    category = SpecialtyItemCategory.CUSTOM,
                    stations = listOf(SpecialtyStation.SPECIALTY),
                    createdAt = "2026-08-20T00:00:00Z",
                    createdByDevice = "tablet-7",
                ),
            )

            assertTrue(session.progressStore.loadAllProgress(folderName).isEmpty())
            assertEquals(0, session.hardwoodsProgressStore.getRowProgress(folderName, "CUTLIST", "row-1").doneCount)
            assertTrue(session.specialtyProgressStore.loadResolvedItems(folderName).isEmpty())
            assertTrue(session.pdfMarkupStore.loadTabletPageMarkup(folderName, "sheet.pdf", page = 1) == null)
            assertTrue(session.sheetRipProgressStore.loadDone(folderName).isEmpty())
            assertTrue(session.tabletSpecialtyItemsStore.loadAllItems(folderName).isEmpty())
            assertTrue(session.assemblyStateStore.getJobs().isEmpty())
            assertTrue(session.specialtyStateStore.getJobs().isEmpty())

            assertFalse(cacheRoot.resolve("$folderName/CNC/.tracker/events/tablet-7.ndjson").exists())
            assertFalse(cacheRoot.resolve("$folderName/.metadata/hardwoods/.tracker/events/tablet-7.ndjson").exists())
            assertFalse(cacheRoot.resolve("$folderName/.metadata/admin/.tracker/tablet-7.json").exists())
            assertFalse(cacheRoot.resolve("$folderName/.metadata/pdf_markup/.tracker/tablet-7.markup.json").exists())
            assertFalse(cacheRoot.resolve("$folderName/.metadata/admin/sheet_rip_done.json").exists())
            assertFalse(cacheRoot.resolve("$folderName/.metadata/admin/tablet_items_tablet-7.json").exists())
        } finally {
            session.close()
            session.close()
        }
    }

    @Test
    fun `session enables archive fingerprint compatibility without local state writes`() {
        val cacheRoot = Files.createTempDirectory("archive-session-fingerprint").toFile()
        val folderName = "123 - Archive Job"
        val historicalFingerprint = "1623305_1777664058047"
        cacheRoot.resolve("$folderName/CNC/.tracker").also { trackerDir ->
            trackerDir.mkdirs()
            trackerDir.resolve("tablet-7.json").writeText(
                """{"tabletId":"tablet-7","actions":[{"file":"A.pdf","page":1,"action":"complete","timestamp":"2026-08-20T10:00:00Z","fileFingerprint":"$historicalFingerprint"}]}"""
            )
        }

        val session = ArchiveSession.create(
            archiveJobId = "archive-123",
            contentVersion = "v1",
            cacheJobParentDir = cacheRoot,
            folderName = folderName,
            tabletId = "tablet-7",
            isDebugBuild = true,
        )

        try {
            assertTrue(session.readOnly)
            assertEquals(
                SheetStatus.COMPLETE,
                session.progressStore.getSheetStatus(
                    folderName,
                    "A.pdf",
                    page = 1,
                    fileFingerprint = "1623305_1777664059000",
                )
            )
            assertFalse(cacheRoot.resolve(".state").exists())
        } finally {
            session.close()
        }
    }
}
