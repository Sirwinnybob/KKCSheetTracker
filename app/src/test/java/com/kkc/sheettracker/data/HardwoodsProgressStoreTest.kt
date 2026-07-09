package com.kkc.sheettracker.data

import com.google.gson.Gson
import com.kkc.sheettracker.data.models.HardwoodCutlistIndex
import com.kkc.sheettracker.data.models.HardwoodCutlistRow
import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodDocumentIndex
import com.kkc.sheettracker.data.models.HardwoodTabletProgress
import com.kkc.sheettracker.data.models.HardwoodTrackerAction
import com.kkc.sheettracker.data.models.HardwoodTrackerActions
import com.kkc.sheettracker.data.unified.UnifiedMetadataEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HardwoodsProgressStoreTest {
    private val gson = Gson()
    private val jobFolderName = "1234 - Test Job"
    private val tabletId = "tablet-test"

    @Test
    fun compactsStaleRowActionsAndPersistsCompactedLocalActions() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            index = HardwoodCutlistIndex(
                documents = listOf(
                    HardwoodDocumentIndex(
                        docType = HardwoodDocType.FACE_FRAME_CUT_LIST,
                        rows = listOf(
                            HardwoodCutlistRow(rowId = "row-keep", qty = 6),
                            HardwoodCutlistRow(rowId = "row-other", qty = 2)
                        )
                    )
                )
            )
        )
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = tabletId,
            progress = HardwoodTabletProgress(
                tabletId = tabletId,
                actions = listOf(
                    trackerAction("FACE_FRAME_CUT_LIST", "row-keep", HardwoodTrackerActions.SET_DONE_COUNT, 4, timestamp = "2026-05-01T00:00:01Z"),
                    trackerAction("FACE_FRAME_CUT_LIST", "row-missing", HardwoodTrackerActions.SET_DONE_COUNT, 2, timestamp = "2026-05-01T00:00:02Z"),
                    trackerAction(
                        docType = "BOARD_STOCK",
                        rowId = "",
                        action = HardwoodTrackerActions.ADD_TOTALS_RIP10_DONE_COUNT,
                        value = 3,
                        totalsKey = "board_stock|RED OAK|2.5|FRAME",
                        timestamp = "2026-05-01T00:00:03Z"
                    )
                )
            )
        )

        val store = HardwoodsProgressStore(baseDir, tabletId)
        val rowMap = store.getRowProgressMap(jobFolderName)

        assertEquals(4, rowMap["FACE_FRAME_CUT_LIST" to "row-keep"]?.doneCount)
        assertNull(rowMap["FACE_FRAME_CUT_LIST" to "row-missing"])

        val persisted = readTabletProgress(baseDir, jobFolderName, tabletId)
        val persistedRowActions = persisted.actions.filter { it.action == HardwoodTrackerActions.SET_DONE_COUNT }
        assertEquals(1, persistedRowActions.size)
        assertEquals("row-keep", persistedRowActions.first().rowId)
        assertEquals(3, store.getBoardStockRipDone(jobFolderName, "red oak", 2.5, "frame"))
    }

    @Test
    fun preservesLocalRowActionsWhenCutlistLookupIsEmpty() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            index = HardwoodCutlistIndex(documents = emptyList())
        )
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = tabletId,
            progress = HardwoodTabletProgress(
                tabletId = tabletId,
                actions = listOf(
                    trackerAction("FACE_FRAME_CUT_LIST", "row-keep", HardwoodTrackerActions.SET_DONE_COUNT, 4, timestamp = "2026-05-01T00:00:01Z"),
                    trackerAction("FACE_FRAME_CUT_LIST", "row-other", HardwoodTrackerActions.SET_DONE_COUNT, 2, timestamp = "2026-05-01T00:00:02Z")
                )
            )
        )

        val store = HardwoodsProgressStore(baseDir, tabletId)
        val rowMap = store.getRowProgressMap(jobFolderName)

        assertEquals(4, rowMap["FACE_FRAME_CUT_LIST" to "row-keep"]?.doneCount)
        assertEquals(2, rowMap["FACE_FRAME_CUT_LIST" to "row-other"]?.doneCount)

        val persisted = readTabletProgress(baseDir, jobFolderName, tabletId)
        val persistedRowIds = persisted.actions.map { it.rowId }
        assertEquals(listOf("row-keep", "row-other"), persistedRowIds)
    }

    @Test
    fun keepsOnlyCabinetSkipActionsForCabinetsStillOnMatchingRow() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            index = HardwoodCutlistIndex(
                documents = listOf(
                    HardwoodDocumentIndex(
                        docType = HardwoodDocType.FACE_FRAME_CUT_LIST,
                        rows = listOf(
                            HardwoodCutlistRow(
                                rowId = "row-keep",
                                qty = 5,
                                cabinets = listOf("C1", "C2")
                            )
                        )
                    )
                )
            )
        )
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = tabletId,
            progress = HardwoodTabletProgress(
                tabletId = tabletId,
                actions = listOf(
                    trackerAction(
                        docType = "FACE_FRAME_CUT_LIST",
                        rowId = encodeCabinetSkipRowId("row-keep", " c1 "),
                        action = HardwoodTrackerActions.SET_SKIPPED,
                        timestamp = "2026-05-01T00:00:01Z"
                    ),
                    trackerAction(
                        docType = "FACE_FRAME_CUT_LIST",
                        rowId = encodeCabinetSkipRowId("row-keep", "C9"),
                        action = HardwoodTrackerActions.SET_SKIPPED,
                        timestamp = "2026-05-01T00:00:02Z"
                    ),
                    trackerAction(
                        docType = "FACE_FRAME_CUT_LIST",
                        rowId = encodeCabinetSkipRowId("row-missing", "C1"),
                        action = HardwoodTrackerActions.SET_SKIPPED,
                        timestamp = "2026-05-01T00:00:03Z"
                    )
                )
            )
        )

        val store = HardwoodsProgressStore(baseDir, tabletId)
        val skippedMap = store.getSkippedCabinetMap(jobFolderName)
        val rowSkipped = skippedMap["FACE_FRAME_CUT_LIST" to "row-keep"].orEmpty()

        assertEquals(setOf("C1"), rowSkipped)
        assertFalse(skippedMap.containsKey("FACE_FRAME_CUT_LIST" to "row-missing"))

        val persisted = readTabletProgress(baseDir, jobFolderName, tabletId)
        assertEquals(1, persisted.actions.size)
        assertEquals(encodeCabinetSkipRowId("row-keep", "C1"), persisted.actions.first().rowId)
    }

    @Test
    fun migratesBoardStockKeysToCanonicalFormatOnceAndPreservesDoneCounts() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            index = HardwoodCutlistIndex()
        )
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = tabletId,
            progress = HardwoodTabletProgress(
                tabletId = tabletId,
                actions = listOf(
                    trackerAction(
                        docType = "BOARD_STOCK",
                        rowId = "",
                        action = HardwoodTrackerActions.ADD_TOTALS_RIP10_DONE_COUNT,
                        value = 2,
                        totalsKey = "board_stock| Maple   Select |2.5000| frame ",
                        timestamp = "2026-05-01T00:00:01Z"
                    ),
                    trackerAction(
                        docType = "BOARD_STOCK",
                        rowId = "",
                        action = HardwoodTrackerActions.ADD_TOTALS_RIP10_DONE_COUNT,
                        value = 1,
                        totalsKey = "board_stock|MAPLE SELECT|2.5|FRAME",
                        timestamp = "2026-05-01T00:00:02Z"
                    ),
                    trackerAction(
                        docType = "BOARD_STOCK_SKIP",
                        rowId = "",
                        action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
                        value = 1,
                        totalsKey = "board_stock_skip| maple   select |2.500|frame ",
                        timestamp = "2026-05-01T00:00:03Z"
                    ),
                    trackerAction(
                        docType = "BOARD_STOCK_SKIP",
                        rowId = "",
                        action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
                        value = 1,
                        totalsKey = "board_stock_material_skip| maple   select ",
                        timestamp = "2026-05-01T00:00:04Z"
                    )
                )
            )
        )

        val store = HardwoodsProgressStore(baseDir, tabletId)
        val canonicalTally = store.makeBoardStockTallyKey("maple select", 2.5, "frame")
        val canonicalSkip = store.makeBoardStockRipSkipKey("maple select", 2.5, "frame")
        val canonicalMaterialSkip = store.makeBoardStockMaterialSkipKey("maple select")
        val totals = store.getTotalsRip10DoneMap(jobFolderName)

        assertEquals(3, totals[canonicalTally])
        assertEquals(1, totals[canonicalSkip])
        assertEquals(1, totals[canonicalMaterialSkip])

        val persisted = readTabletProgress(baseDir, jobFolderName, tabletId)
        assertTrue(persisted.actions.all { action ->
            val key = action.totalsKey ?: return@all true
            !key.contains("  ") && key == key.trim()
        })

        val marker = File(baseDir, "$jobFolderName/.metadata/hardwoods/.tracker/.board_stock_canonical_migration_${tabletId}.json")
        assertTrue(marker.exists())
        val markerBody = marker.readText()
        assertTrue(markerBody.contains("\"migratedCount\""))
        assertFalse(markerBody.contains("\"migratedCount\": 0"))

        val storeReloaded = HardwoodsProgressStore(baseDir, tabletId)
        assertEquals(3, storeReloaded.getTotalsRip10DoneMap(jobFolderName)[canonicalTally])
        assertNotNull(storeReloaded.getTotalsRip10DoneMap(jobFolderName)[canonicalSkip])
    }

    @Test
    fun setBoardStockRipDoneWritesFinalSetAction() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(baseDir, jobFolderName, HardwoodCutlistIndex())

        val store = HardwoodsProgressStore(baseDir, tabletId)
        val totalsKey = store.makeBoardStockTallyKey("Maple Select", 2.5, "FRAME")

        store.setBoardStockRipDone(jobFolderName, "Maple Select", 2.5, "FRAME", 4)
        store.awaitPendingWrites()

        val action = readTabletProgress(baseDir, jobFolderName, tabletId).actions.single()
        assertEquals("BOARD_STOCK", action.docType)
        assertEquals("", action.rowId)
        assertEquals(totalsKey, action.totalsKey)
        assertEquals(HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT, action.action)
        assertEquals(4, action.value)
    }

    @Test
    fun saveTabletProgressWritesAtomicallyWithNoLeftoverTempFile() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(baseDir, jobFolderName, HardwoodCutlistIndex())

        val store = HardwoodsProgressStore(baseDir, tabletId)
        store.setBoardStockRipDone(jobFolderName, "Maple Select", 2.5, "FRAME", 4)
        store.awaitPendingWrites()

        val trackerDir = File(baseDir, "$jobFolderName/.metadata/hardwoods/.tracker")
        val trackerFiles = trackerDir.listFiles().orEmpty().map { it.name }

        // The write must land as the final "<tabletId>.json" with no ".tmp-*" artifact left
        // behind, and the file itself must contain fully-formed, parseable JSON (i.e. it was
        // never observed mid-write via a truncating writeText()).
        assertTrue(trackerFiles.contains("$tabletId.json"))
        assertTrue(trackerFiles.none { it.contains(".tmp-") })

        val persisted = readTabletProgress(baseDir, jobFolderName, tabletId)
        assertEquals(1, persisted.actions.size)
        assertEquals(HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT, persisted.actions.first().action)
        assertEquals(4, persisted.actions.first().value)
    }

    @Test
    fun setTotalsRip10DoneWritesFinalSetAction() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(baseDir, jobFolderName, HardwoodCutlistIndex())

        val store = HardwoodsProgressStore(baseDir, tabletId)
        val totalsKey = store.makeTotalsRip10LineKey("FACE_FRAME_CUT_LIST", 1, 2)

        store.setTotalsRip10Done(jobFolderName, "FACE_FRAME_CUT_LIST", 1, 2, 3)
        store.awaitPendingWrites()

        val action = readTabletProgress(baseDir, jobFolderName, tabletId).actions.single()
        assertEquals("FACE_FRAME_CUT_LIST", action.docType)
        assertEquals("", action.rowId)
        assertEquals(totalsKey, action.totalsKey)
        assertEquals(HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT, action.action)
        assertEquals(3, action.value)
    }

    @Test
    fun setAdminBoardStockDoneWritesFinalSetAction() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(baseDir, jobFolderName, HardwoodCutlistIndex())

        val store = HardwoodsProgressStore(baseDir, tabletId)
        val totalsKey = store.makeAdminBoardStockTallyKey("Maple Select", "item-7")

        store.setAdminBoardStockDone(jobFolderName, "Maple Select", "item-7", 5)
        store.awaitPendingWrites()

        val action = readTabletProgress(baseDir, jobFolderName, tabletId).actions.single()
        assertEquals("BOARD_STOCK", action.docType)
        assertEquals("", action.rowId)
        assertEquals(totalsKey, action.totalsKey)
        assertEquals(HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT, action.action)
        assertEquals(5, action.value)
    }

    @Test
    fun mergedFinalBoardStockSetActionsDoNotDoubleCount() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(baseDir, jobFolderName, HardwoodCutlistIndex())
        val totalsKey = HardwoodsProgressStore(baseDir, tabletId)
            .makeBoardStockTallyKey("Maple Select", 2.5, "FRAME")
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-a",
            progress = HardwoodTabletProgress(
                tabletId = "tablet-a",
                actions = listOf(
                    trackerAction(
                        docType = "BOARD_STOCK",
                        rowId = "",
                        action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
                        value = 1,
                        totalsKey = totalsKey,
                        timestamp = "2026-05-01T00:00:01Z"
                    )
                )
            )
        )
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-b",
            progress = HardwoodTabletProgress(
                tabletId = "tablet-b",
                actions = listOf(
                    trackerAction(
                        docType = "BOARD_STOCK",
                        rowId = "",
                        action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
                        value = 1,
                        totalsKey = totalsKey,
                        timestamp = "2026-05-01T00:00:02Z"
                    )
                )
            )
        )

        val store = HardwoodsProgressStore(baseDir, tabletId)

        assertEquals(1, store.getBoardStockRipDone(jobFolderName, "Maple Select", 2.5, "FRAME"))
    }

    @Test
    fun sourceScopedMaterialSkipFallsBackToLegacyKeyWhenSourceKeyMissing() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            index = HardwoodCutlistIndex()
        )
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = tabletId,
            progress = HardwoodTabletProgress(
                tabletId = tabletId,
                actions = listOf(
                    trackerAction(
                        docType = "BOARD_STOCK_SKIP",
                        rowId = "",
                        action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
                        value = 1,
                        totalsKey = "board_stock_material_skip| maple   select ",
                        timestamp = "2026-05-01T00:00:01Z"
                    )
                )
            )
        )

        val store = HardwoodsProgressStore(baseDir, tabletId)
        assertTrue(store.isBoardStockMaterialSkipped(jobFolderName, "Maple Select", "FRAME"))
        assertTrue(store.isBoardStockMaterialSkipped(jobFolderName, "Maple Select", "NAILER"))
    }

    @Test
    fun sourceScopedMaterialSkipFalseOverridesLegacyTrue() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            index = HardwoodCutlistIndex()
        )
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = tabletId,
            progress = HardwoodTabletProgress(
                tabletId = tabletId,
                actions = listOf(
                    trackerAction(
                        docType = "BOARD_STOCK_SKIP",
                        rowId = "",
                        action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
                        value = 1,
                        totalsKey = "board_stock_material_skip|MAPLE SELECT",
                        timestamp = "2026-05-01T00:00:01Z"
                    ),
                    trackerAction(
                        docType = "BOARD_STOCK_SKIP",
                        rowId = "",
                        action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
                        value = 0,
                        totalsKey = "board_stock_material_skip|MAPLE SELECT| FRAME ",
                        timestamp = "2026-05-01T00:00:02Z"
                    )
                )
            )
        )

        val store = HardwoodsProgressStore(baseDir, tabletId)
        assertFalse(store.isBoardStockMaterialSkipped(jobFolderName, "Maple Select", "FRAME"))
        assertTrue(store.isBoardStockMaterialSkipped(jobFolderName, "Maple Select", "NAILER"))
    }

    @Test
    fun sourceScopedMaterialSkipDoesNotAffectOtherSources() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            index = HardwoodCutlistIndex()
        )

        val store = HardwoodsProgressStore(baseDir, tabletId)
        store.setBoardStockMaterialSkipped(jobFolderName, "Maple Select", "FRAME", true)

        assertTrue(store.isBoardStockMaterialSkipped(jobFolderName, "Maple Select", "FRAME"))
        assertFalse(store.isBoardStockMaterialSkipped(jobFolderName, "Maple Select", "DOOR"))
    }

    @Test
    fun canonicalizesSourceScopedBoardStockMaterialSkipKeys() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            index = HardwoodCutlistIndex()
        )
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = tabletId,
            progress = HardwoodTabletProgress(
                tabletId = tabletId,
                actions = listOf(
                    trackerAction(
                        docType = "BOARD_STOCK_SKIP",
                        rowId = "",
                        action = HardwoodTrackerActions.SET_TOTALS_RIP10_DONE_COUNT,
                        value = 1,
                        totalsKey = "board_stock_material_skip| maple   select | frame ",
                        timestamp = "2026-05-01T00:00:01Z"
                    )
                )
            )
        )

        val store = HardwoodsProgressStore(baseDir, tabletId)
        val canonical = store.makeBoardStockMaterialSkipKey("Maple Select", "FRAME")
        assertEquals(1, store.getTotalsRip10DoneMap(jobFolderName)[canonical])
    }

    private fun trackerAction(
        docType: String,
        rowId: String,
        action: String,
        value: Int? = null,
        totalsKey: String? = null,
        timestamp: String
    ): HardwoodTrackerAction {
        return HardwoodTrackerAction(
            docType = docType,
            rowId = rowId,
            totalsKey = totalsKey,
            action = action,
            value = value,
            timestamp = timestamp
        )
    }

    @Test
    fun concurrentRowWritesAndReadsDoNotCorruptCache() {
        val baseDir = createTempBaseDir()
        val store = HardwoodsProgressStore(baseDir, tabletId)
        val docType = HardwoodDocType.FACE_FRAME_CUT_LIST.name

        val threads = 6
        val iterations = 300
        val pool = Executors.newFixedThreadPool(threads)
        val errors = CopyOnWriteArrayList<Throwable>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        for (t in 0 until threads) {
            pool.execute {
                try {
                    start.await()
                    for (i in 0 until iterations) {
                        if (t % 2 == 0) {
                            // Writers mutate the JobCache maps via appendAction/applyActionToCache.
                            store.setDoneCount(jobFolderName, docType, "row-${i % 20}", qty = 5, doneCount = i % 5)
                        } else {
                            // Readers snapshot the same maps; without a shared lock this races the
                            // writers' mutations and throws ConcurrentModificationException.
                            store.getRowProgressMap(jobFolderName)
                            store.getSkippedCabinetMap(jobFolderName)
                            store.getTotalsRip10DoneMap(jobFolderName)
                        }
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("concurrent access did not finish in time", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        store.awaitPendingWrites()

        assertTrue("concurrent cache access threw: ${errors.firstOrNull()}", errors.isEmpty())
    }

    @Test
    fun setDoneCountThenImmediateInvalidateDoesNotReloadZeroProgress() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            index = HardwoodCutlistIndex(
                documents = listOf(
                    HardwoodDocumentIndex(
                        docType = HardwoodDocType.FACE_FRAME_CUT_LIST,
                        rows = listOf(HardwoodCutlistRow(rowId = "row-keep", qty = 6))
                    )
                )
            )
        )
        val store = HardwoodsProgressStore(baseDir, tabletId)
        val docType = HardwoodDocType.FACE_FRAME_CUT_LIST.name

        store.setDoneCount(jobFolderName, docType, "row-keep", qty = 6, doneCount = 4)
        store.invalidateJobCache(jobFolderName)

        val rowMap = store.getRowProgressMap(jobFolderName)

        assertEquals(4, rowMap[docType to "row-keep"]?.doneCount)
    }

    @Test
    fun compactionDuringPendingAsyncSaveDoesNotRestorePrunedActions() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            index = HardwoodCutlistIndex(
                documents = listOf(
                    HardwoodDocumentIndex(
                        docType = HardwoodDocType.FACE_FRAME_CUT_LIST,
                        rows = listOf(HardwoodCutlistRow(rowId = "row-keep", qty = 6))
                    )
                )
            )
        )
        val store = HardwoodsProgressStore(baseDir, tabletId)
        val docType = HardwoodDocType.FACE_FRAME_CUT_LIST.name

        store.setDoneCount(jobFolderName, docType, "row-stale", qty = 6, doneCount = 2)
        store.setDoneCount(jobFolderName, docType, "row-keep", qty = 6, doneCount = 4)
        store.invalidateJobCache(jobFolderName)

        val rowMap = store.getRowProgressMap(jobFolderName)
        store.awaitPendingWrites()
        val persisted = readTabletProgress(baseDir, jobFolderName, tabletId)

        assertNull(rowMap[docType to "row-stale"])
        assertEquals(4, rowMap[docType to "row-keep"]?.doneCount)
        assertEquals(listOf("row-keep"), persisted.actions.map { it.rowId })
    }

    @Test
    fun concurrentIncrementInvalidateAndReloadDoesNotLoseProgress() {
        val baseDir = createTempBaseDir()
        writeCutlistIndex(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            index = HardwoodCutlistIndex(
                documents = listOf(
                    HardwoodDocumentIndex(
                        docType = HardwoodDocType.FACE_FRAME_CUT_LIST,
                        rows = listOf(HardwoodCutlistRow(rowId = "row-keep", qty = 500))
                    )
                )
            )
        )
        val store = HardwoodsProgressStore(baseDir, tabletId)
        val docType = HardwoodDocType.FACE_FRAME_CUT_LIST.name
        val increments = 180
        val invalidations = 360
        val errors = CopyOnWriteArrayList<Throwable>()
        val start = CountDownLatch(1)
        val writersDone = CountDownLatch(1)
        val invalidatorsDone = CountDownLatch(1)

        val writer = Thread {
            try {
                start.await()
                repeat(increments) {
                    store.incrementDoneCount(jobFolderName, docType, "row-keep", qty = 500)
                    Thread.yield()
                }
            } catch (e: Throwable) {
                errors.add(e)
            } finally {
                writersDone.countDown()
            }
        }
        val invalidator = Thread {
            try {
                start.await()
                repeat(invalidations) {
                    store.invalidateJobCache(jobFolderName)
                    store.getRowProgressMap(jobFolderName)
                    Thread.yield()
                }
            } catch (e: Throwable) {
                errors.add(e)
            } finally {
                invalidatorsDone.countDown()
            }
        }

        writer.start()
        invalidator.start()
        start.countDown()

        assertTrue("writer did not finish", writersDone.await(30, TimeUnit.SECONDS))
        assertTrue("invalidator did not finish", invalidatorsDone.await(30, TimeUnit.SECONDS))
        store.awaitPendingWrites()

        val finalDone = store.getRowProgressMap(jobFolderName)[docType to "row-keep"]?.doneCount
        assertTrue("concurrent workers threw: ${errors.firstOrNull()}", errors.isEmpty())
        assertEquals(increments, finalDone)
    }

    @Test
    fun incrementDoneCountUsesCurrentStoreProgressInsteadOfStaleCallerValue() {
        val baseDir = createTempBaseDir()
        val store = HardwoodsProgressStore(baseDir, tabletId)
        val docType = HardwoodDocType.FACE_FRAME_CUT_LIST.name

        store.setDoneCount(jobFolderName, docType, "row-keep", qty = 6, doneCount = 4)
        store.incrementDoneCount(jobFolderName, docType, "row-keep", qty = 6)

        val rowMap = store.getRowProgressMap(jobFolderName)

        assertEquals(5, rowMap[docType to "row-keep"]?.doneCount)
    }

    @Test
    fun incrementDoneCountFallsBackToDiskWhenMetadataCacheRebuildFails() {
        val baseDir = createTempBaseDir()
        val docType = HardwoodDocType.FACE_FRAME_CUT_LIST.name
        writeTabletProgress(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = tabletId,
            progress = HardwoodTabletProgress(
                tabletId = tabletId,
                actions = listOf(
                    trackerAction(docType, "row-keep", HardwoodTrackerActions.SET_DONE_COUNT, 4, timestamp = "2026-05-01T00:00:01Z")
                )
            )
        )
        val store = HardwoodsProgressStore(
            baseDir = baseDir,
            tabletId = tabletId,
            unifiedEngine = throwingHardwoodsSnapshotEngine()
        )

        store.incrementDoneCount(jobFolderName, docType, "row-keep", qty = 6)
        store.awaitPendingWrites()

        val persisted = readTabletProgress(baseDir, jobFolderName, tabletId)
        assertEquals(5, persisted.actions.last().value)
    }

    @Test
    fun concurrentFallbackIncrementsPreserveEveryTapWhenMetadataCacheRebuildFails() {
        val baseDir = createTempBaseDir()
        val docType = HardwoodDocType.FACE_FRAME_CUT_LIST.name
        val store = HardwoodsProgressStore(
            baseDir = baseDir,
            tabletId = tabletId,
            unifiedEngine = throwingHardwoodsSnapshotEngine()
        )
        val taps = 80
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)

        try {
            val futures = (0 until taps).map {
                pool.submit {
                    start.await(5, TimeUnit.SECONDS)
                    store.incrementDoneCount(jobFolderName, docType, "row-keep", qty = taps + 5)
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        store.awaitPendingWrites()

        val persisted = readTabletProgress(baseDir, jobFolderName, tabletId)
        assertEquals(taps, persisted.actions.size)
        assertEquals(taps, persisted.actions.last().value)
    }

    @Test
    fun decrementDoneCountUsesCurrentStoreProgressInsteadOfStaleCallerValue() {
        val baseDir = createTempBaseDir()
        val store = HardwoodsProgressStore(baseDir, tabletId)
        val docType = HardwoodDocType.FACE_FRAME_CUT_LIST.name

        store.setDoneCount(jobFolderName, docType, "row-keep", qty = 6, doneCount = 4)
        store.decrementDoneCount(jobFolderName, docType, "row-keep", qty = 6)

        val rowMap = store.getRowProgressMap(jobFolderName)

        assertEquals(3, rowMap[docType to "row-keep"]?.doneCount)
    }

    @Test
    fun incrementBoardStockRipDoneUsesCurrentStoreTotalInsteadOfStaleCallerValue() {
        val baseDir = createTempBaseDir()
        val store = HardwoodsProgressStore(baseDir, tabletId)

        store.setBoardStockRipDone(jobFolderName, "Maple", 2.5, "frame", doneCount = 4)
        store.incrementBoardStockRipDone(jobFolderName, "Maple", 2.5, "frame", maxCount = 6)

        assertEquals(5, store.getBoardStockRipDone(jobFolderName, "Maple", 2.5, "frame"))
    }

    @Test
    fun incrementAdminBoardStockDoneUsesCurrentStoreTotalInsteadOfStaleCallerValue() {
        val baseDir = createTempBaseDir()
        val store = HardwoodsProgressStore(baseDir, tabletId)
        val key = store.makeAdminBoardStockTallyKey("Walnut", "item-1")

        store.setAdminBoardStockDone(jobFolderName, "Walnut", "item-1", doneCount = 2)
        store.incrementAdminBoardStockDone(jobFolderName, "Walnut", "item-1", maxCount = 4)

        assertEquals(3, store.getTotalsRip10DoneMap(jobFolderName)[key])
    }

    private fun createTempBaseDir(): File {
        return Files.createTempDirectory("hardwoods-progress-store-test").toFile()
    }

    private fun writeCutlistIndex(baseDir: File, jobFolderName: String, index: HardwoodCutlistIndex) {
        val path = File(baseDir, "$jobFolderName/.metadata/hardwoods/cutlist_index.json")
        path.parentFile?.mkdirs()
        path.writeText(gson.toJson(index))
    }

    private fun writeTabletProgress(
        baseDir: File,
        jobFolderName: String,
        tabletId: String,
        progress: HardwoodTabletProgress
    ) {
        val trackerDir = File(baseDir, "$jobFolderName/.metadata/hardwoods/.tracker")
        trackerDir.mkdirs()
        File(trackerDir, "$tabletId.json").writeText(gson.toJson(progress))
    }

    private fun readTabletProgress(baseDir: File, jobFolderName: String, tabletId: String): HardwoodTabletProgress {
        val file = File(baseDir, "$jobFolderName/.metadata/hardwoods/.tracker/$tabletId.json")
        return gson.fromJson(file.readText(), HardwoodTabletProgress::class.java)
    }

    private fun throwingHardwoodsSnapshotEngine(): UnifiedMetadataEngine {
        return Proxy.newProxyInstance(
            UnifiedMetadataEngine::class.java.classLoader,
            arrayOf(UnifiedMetadataEngine::class.java)
        ) { _, method, _ ->
            if (method.name == "getHardwoodsSnapshot") {
                throw IllegalStateException("metadata unavailable")
            }
            defaultReturnValue(method.returnType)
        } as UnifiedMetadataEngine
    }

    private fun defaultReturnValue(type: Class<*>): Any? {
        return when {
            type == Boolean::class.javaPrimitiveType -> false
            type == Int::class.javaPrimitiveType -> 0
            type == Long::class.javaPrimitiveType -> 0L
            type == Void.TYPE -> null
            List::class.java.isAssignableFrom(type) -> emptyList<Any>()
            else -> null
        }
    }

    private fun encodeCabinetSkipRowId(rowId: String, cabinet: String): String {
        return "$rowId|@cab:$cabinet"
    }
}
