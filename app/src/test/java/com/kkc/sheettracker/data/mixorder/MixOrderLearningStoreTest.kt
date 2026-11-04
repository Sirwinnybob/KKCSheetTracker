package com.kkc.sheettracker.data.mixorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MixOrderLearningStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clockMs = 1_793_000_000_000L
    private val features = SheetFeatures(2, 1, 0.1, 3.0, 9)

    private fun rec(id: String, tablet: String = "tab-1", version: Int = 1) = MixOrderRecord(
        version = version,
        recordId = id,
        job = "530",
        material = "Maple",
        mixFile = "MapleMix.mix",
        tabletId = tablet,
        appliedAt = "2026-11-03T10:00:00.000-08:00",
        appVersion = "9.0.17",
        mode = "smart",
        edgeBandIn = EDGE_BAND_IN,
        smallPartIn = SMALL_PART_IN,
        weightsUsed = PRIOR_WEIGHTS.values,
        defaultOrder = listOf("1|A", "2|B"),
        suggestedOrder = listOf("2|B", "1|A"),
        finalOrder = listOf("2|B", "1|A"),
        sheets = mapOf("1|A" to features, "2|B" to features),
        lockedSheets = emptyList()
    )

    private fun newStore(pending: File = tmp.newFolder()) =
        MixOrderLearningStore(pending) { clockMs++ } to pending

    private fun learningDir(base: File) = File(base, LEARNING_DIR)

    private fun bundle(vararg records: MixOrderRecord, version: Int = 1) =
        MixOrderJson.gson.toJson(MixOrderBundle(version = version, records = records.toList()))

    private fun writeBundle(base: File, name: String, json: String): File {
        val dir = File(learningDir(base), "bundles").apply { mkdirs() }
        return File(dir, name).apply { writeText(json) }
    }

    @Test
    fun writesSharedWithTabletIdInName() {
        val base = tmp.newFolder()
        val (store, pending) = newStore()
        store.record(base, rec("r1", tablet = "tab-7"))

        val files = learningDir(base).listFiles()!!.filter { it.isFile }
        assertEquals(1, files.size)
        assertTrue(files[0].name, Regex("""\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}-\d{3}_tab-7_mixorder\.json""").matches(files[0].name))
        assertEquals(0, pending.listFiles()!!.size)
        assertEquals(listOf("r1"), store.readAll(base).map { it.recordId })
    }

    @Test
    fun noTempFileLeftBehind() {
        val base = tmp.newFolder()
        val (store, _) = newStore()
        store.record(base, rec("r1"))
        assertTrue(learningDir(base).listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun offlineGoesPendingThenFlushes() {
        val base = tmp.newFolder()
        val (store, pending) = newStore()
        store.record(null, rec("r1"))
        assertEquals(1, pending.listFiles()!!.size)
        assertTrue(!learningDir(base).exists())

        store.record(base, rec("r2"))

        assertEquals(0, pending.listFiles()!!.size)
        val shared = learningDir(base).listFiles()!!.filter { it.isFile }
        assertEquals(2, shared.size)
        assertEquals(setOf("r1", "r2"), store.readAll(base).map { it.recordId }.toSet())
    }

    @Test
    fun unreachableSharedFallsBackToPending() {
        // A regular file where the base dir should be makes the shared folder impossible to create.
        val blocker = tmp.newFile("not-a-dir")
        val (store, pending) = newStore()
        store.record(blocker, rec("r1"))
        assertEquals(1, pending.listFiles()!!.size)
    }

    @Test
    fun recordNeverThrows() {
        val blockedPending = tmp.newFile("pending-is-a-file")
        val store = MixOrderLearningStore(blockedPending) { clockMs++ }
        store.record(null, rec("r1"))
        store.record(tmp.newFile("also-a-file"), rec("r2"))
    }

    @Test
    fun flushPendingReturnsCount() {
        val base = tmp.newFolder()
        val (store, pending) = newStore()
        store.record(null, rec("r1"))
        store.record(null, rec("r2"))
        assertEquals(2, store.flushPending(base))
        assertEquals(0, store.flushPending(base))
        assertEquals(0, pending.listFiles()!!.size)
    }

    @Test
    fun readAllDedupesSinglesAndBundles() {
        val base = tmp.newFolder()
        val (store, _) = newStore()
        store.record(base, rec("r1"))
        store.record(base, rec("r2"))
        writeBundle(base, "2026-11-03T10-00-00-000_pc_bundle.json", bundle(rec("r2"), rec("r3")))
        writeBundle(base, "2026-11-03T11-00-00-000_tab-2_bundle.json", bundle(rec("r3"), rec("r4")))

        val ids = store.readAll(base).map { it.recordId }
        assertEquals(4, ids.size)
        assertEquals(setOf("r1", "r2", "r3", "r4"), ids.toSet())
    }

    @Test
    fun readAllMissingFolderIsEmpty() {
        val (store, _) = newStore()
        assertEquals(emptyList<MixOrderRecord>(), store.readAll(tmp.newFolder()))
    }

    @Test
    fun readAllSkipsGarbage() {
        val base = tmp.newFolder()
        val (store, _) = newStore()
        store.record(base, rec("r1"))
        val dir = learningDir(base)
        val good = dir.listFiles()!!.single { it.isFile }
        val full = good.readText()
        File(dir, "2026-11-03T09-00-00-000_tab-9_mixorder.json").writeText(full.take(full.length / 2))
        File(dir, "x.sync-conflict-20261103-ABC.json").writeText(full)
        File(dir, "notes.txt").writeText(full)
        File(dir, "2026-11-03T09-30-00-000_tab-9_mixorder.json.tmp").writeText(MixOrderJson.gson.toJson(rec("r-tmp")))
        File(dir, "empty.json").writeText("")
        File(dir, "array.json").writeText("[1,2,3]")
        File(dir, "subdir.json").mkdirs()
        writeBundle(base, "bad_bundle.json", "{\"version\":1,\"records\":[")

        val records = store.readAll(base)
        assertEquals(listOf("r1"), records.map { it.recordId })
    }

    @Test
    fun readAllSkipsNewerVersion() {
        val base = tmp.newFolder()
        val (store, _) = newStore()
        store.record(base, rec("r1"))
        store.record(base, rec("future", version = 2))
        writeBundle(base, "a_bundle.json", bundle(rec("b1"), rec("b-future", version = 2)))
        writeBundle(base, "b_bundle.json", bundle(rec("b2"), version = 2))

        assertEquals(setOf("r1", "b1"), store.readAll(base).map { it.recordId }.toSet())
    }

    @Test
    fun readAllUsesCache() {
        val base = tmp.newFolder()
        val (store, _) = newStore()
        store.record(base, rec("r1"))
        val file = learningDir(base).listFiles()!!.single { it.isFile }
        assertEquals(listOf("r1"), store.readAll(base).map { it.recordId })

        val modified = file.lastModified()
        val length = file.length()
        file.writeText("x".repeat(length.toInt()))
        file.setLastModified(modified)
        assertEquals(length, file.length())
        assertEquals(modified, file.lastModified())

        assertEquals(listOf("r1"), store.readAll(base).map { it.recordId })

        // A changed length invalidates the entry, so the garbage is now seen and skipped.
        file.writeText("garbage")
        assertEquals(emptyList<String>(), store.readAll(base).map { it.recordId })
    }
}
