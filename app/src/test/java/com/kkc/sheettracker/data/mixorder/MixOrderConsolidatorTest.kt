package com.kkc.sheettracker.data.mixorder

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MixOrderConsolidatorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clockMs = 1_793_000_000_000L
    private val nowMs = 1_793_100_000_000L
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

    private fun newStore(pending: File = tmp.newFolder()) = MixOrderLearningStore(pending) { clockMs++ }

    private fun root(base: File) = File(base, LEARNING_DIR)
    private fun bundlesDir(base: File) = File(root(base), "bundles")
    private fun badDir(base: File) = File(root(base), "bad")

    private fun bundleJson(vararg records: MixOrderRecord, version: Int = 1) =
        MixOrderJson.gson.toJson(MixOrderBundle(version = version, records = records.toList()))

    private fun writeBundle(base: File, name: String, json: String): File {
        val dir = bundlesDir(base).apply { mkdirs() }
        return File(dir, name).apply { writeText(json) }
    }

    private fun writeSingle(base: File, name: String, text: String): File {
        val dir = root(base).apply { mkdirs() }
        return File(dir, name).apply { writeText(text) }
    }

    private fun singles(base: File) = root(base).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
    private fun bundles(base: File) = bundlesDir(base).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }

    /** Name, size and timestamp of every file under the learning folder, to prove a run wrote nothing. */
    private fun snapshot(base: File): List<String> =
        root(base).walkTopDown().filter { it.isFile }.map { "${it.relativeTo(root(base)).path}|${it.length()}|${it.lastModified()}" }.sorted().toList()

    @Test
    fun readsPowerShellBundle() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/mixorder/pc_bundle.json")) {
            "Missing checked-in bundle produced by tools/consolidate-mix-learning.ps1"
        }.use { it.readBytes() }
        assertFalse("PowerShell bundle must be UTF-8 without BOM", bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
        val base = tmp.newFolder()
        val dir = bundlesDir(base).apply { mkdirs() }
        File(dir, "pc_bundle.json").writeBytes(bytes)

        val records = newStore().readAll(base)

        assertEquals(setOf("pc-1", "pc-2", "pc-3", "pc-4"), records.map { it.recordId }.toSet())
        assertEquals(4, records.size)
        records.forEach { record ->
            assertEquals("720", record.job)
            assertEquals("smart", record.mode)
            assertEquals(PRIOR_WEIGHTS.values, record.weightsUsed)
            assertEquals(listOf("2|R2A.pgm", "1|R1A.pgm"), record.finalOrder)
            assertEquals(SheetFeatures(1, 2, 0.125, 3.0, 9), record.sheets["1|R1A.pgm"])
            assertEquals(SheetFeatures(4, 0, 0.25, 2.0, 12), record.sheets["2|R2A.pgm"])
            assertTrue(record.lockedSheets.isEmpty())
        }
    }

    @Test
    fun mergesSinglesAndBundlesDedupe() {
        val base = tmp.newFolder()
        val store = newStore()
        store.record(base, rec("r1"))
        store.record(base, rec("r2"))
        writeBundle(base, "2026-11-03T10-00-00-000_pc_bundle.json", bundleJson(rec("r2"), rec("r3")))
        writeBundle(base, "2026-11-03T11-00-00-000_tab-2_bundle.json", bundleJson(rec("r3"), rec("r4")))

        val result = consolidateMixOrderLearning(base, "tab 1", store, nowMs)

        assertEquals(4, result.records)
        assertEquals(4, result.filesAbsorbed)
        assertEquals(0, result.badMoved)
        assertEquals(0, result.leftUnknownVersion)
        val bundle = result.bundle
        assertNotNull(bundle)
        assertEquals(listOf(bundle), bundles(base))
        assertEquals(emptyList<File>(), singles(base))
        assertTrue(bundle!!.name, Regex("""\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}-\d{3}_tab-1_bundle\.json""").matches(bundle.name))
        assertTrue(bundlesDir(base).listFiles()!!.none { it.name.endsWith(".tmp") })

        val json = MixOrderJson.gson.fromJson(bundle.readText(), JsonObject::class.java)
        assertEquals(1, json.get("version").asInt)
        assertEquals("bundle", json.get("kind").asString)
        assertEquals("tab 1", json.get("source").asString)
        assertTrue(json.get("createdAt").asString.isNotBlank())
        assertEquals(4, json.getAsJsonArray("records").size())
        assertEquals(setOf("r1", "r2", "r3", "r4"), store.readAll(base).map { it.recordId }.toSet())
    }

    @Test
    fun pendingRecordsAreFlushedAndAbsorbed() {
        val base = tmp.newFolder()
        val store = newStore()
        store.record(null, rec("p1"))
        store.record(base, rec("r1"))
        store.record(null, rec("p2"))

        val result = consolidateMixOrderLearning(base, "tab-1", store, nowMs)

        assertEquals(3, result.records)
        assertEquals(setOf("p1", "r1", "p2"), store.readAll(base).map { it.recordId }.toSet())
        assertEquals(1, bundles(base).size)
        assertEquals(emptyList<File>(), singles(base))
    }

    @Test
    fun verifyFailureDeletesNothing() {
        val base = tmp.newFolder()
        val store = newStore()
        store.record(base, rec("r1"))
        store.record(base, rec("r2"))
        val oldBundle = writeBundle(base, "2026-11-03T10-00-00-000_pc_bundle.json", bundleJson(rec("r3")))
        val before = snapshot(base)

        val result = runMixOrderConsolidation(base, "tab-1", store, nowMs, verify = { _, _ -> false })

        assertNull(result.bundle)
        assertEquals(0, result.filesAbsorbed)
        assertEquals(before, snapshot(base))
        assertEquals(listOf(oldBundle), bundles(base))
        assertEquals(2, singles(base).size)
    }

    @Test
    fun verifyThatThrowsDeletesNothing() {
        val base = tmp.newFolder()
        val store = newStore()
        store.record(base, rec("r1"))
        store.record(base, rec("r2"))
        val before = snapshot(base)

        val result = runMixOrderConsolidation(base, "tab-1", store, nowMs, verify = { _, _ -> error("boom") })

        assertNull(result.bundle)
        assertEquals(before, snapshot(base))
    }

    @Test
    fun defaultVerifyRequiresEveryRecordId() {
        val dir = tmp.newFolder()
        val bundle = File(dir, "b.json").apply { writeText(bundleJson(rec("a"), rec("b"))) }

        assertTrue(verifyBundleContains(bundle, setOf("a", "b")))
        assertTrue(verifyBundleContains(bundle, setOf("a")))
        assertFalse(verifyBundleContains(bundle, setOf("a", "b", "c")))
        assertFalse(verifyBundleContains(File(dir, "missing.json"), setOf("a")))
        bundle.writeText("{\"version\":1,\"records\":[")
        assertFalse(verifyBundleContains(bundle, setOf("a")))
    }

    @Test
    fun missingFileSkipped() {
        val base = tmp.newFolder()
        val store = newStore()
        store.record(base, rec("r1"))
        store.record(base, rec("r2"))
        store.record(base, rec("r3"))
        val victim = singles(base)[1]

        val result = runMixOrderConsolidation(base, "tab-1", store, nowMs, afterListing = { victim.delete() })

        assertEquals(2, result.records)
        assertEquals(2, result.filesAbsorbed)
        assertEquals(0, result.badMoved)
        assertFalse(badDir(base).exists())
        assertEquals(setOf("r1", "r3"), store.readAll(base).map { it.recordId }.toSet())
    }

    @Test
    fun fileDisappearingBeforeDeleteIsSkipped() {
        val base = tmp.newFolder()
        val store = newStore()
        store.record(base, rec("r1"))
        store.record(base, rec("r2"))
        val victim = singles(base)[0]

        val result = runMixOrderConsolidation(
            base, "tab-1", store, nowMs,
            verify = { bundle, ids -> victim.delete(); verifyBundleContains(bundle, ids) }
        )

        assertEquals(2, result.records)
        assertNotNull(result.bundle)
        assertEquals(emptyList<File>(), singles(base))
    }

    @Test
    fun fileChangedAfterReadIsNotDeleted() {
        val base = tmp.newFolder()
        val store = newStore()
        store.record(base, rec("r1"))
        store.record(base, rec("r2"))
        val changed = singles(base)[0]

        val result = runMixOrderConsolidation(
            base, "tab-1", store, nowMs,
            verify = { bundle, ids -> changed.appendText("\n"); verifyBundleContains(bundle, ids) }
        )

        assertNotNull(result.bundle)
        assertEquals(listOf(changed), singles(base))
    }

    @Test
    fun filesArrivingAfterListingAreUntouched() {
        val base = tmp.newFolder()
        val store = newStore()
        store.record(base, rec("r1"))
        store.record(base, rec("r2"))
        var late: File? = null

        runMixOrderConsolidation(base, "tab-1", store, nowMs, afterListing = {
            late = writeSingle(base, "9999-late_tab-3_mixorder.json", MixOrderJson.gson.toJson(rec("late")))
        })

        assertTrue(late!!.exists())
        assertEquals(listOf(late), singles(base))
    }

    @Test
    fun unknownVersionLeftInPlace() {
        val base = tmp.newFolder()
        val store = newStore()
        store.record(base, rec("r1"))
        store.record(base, rec("r2"))
        val futureSingle = writeSingle(base, "2026-11-03T09-00-00-000_tab-9_mixorder.json", MixOrderJson.gson.toJson(rec("f1", version = 2)))
        val futureBundle = writeBundle(base, "a_bundle.json", bundleJson(rec("b1"), version = 2))
        val futureInside = writeBundle(base, "b_bundle.json", bundleJson(rec("b2"), rec("b3", version = 2)))
        val futureTexts = listOf(futureSingle, futureBundle, futureInside).associateWith { it.readText() }

        val result = consolidateMixOrderLearning(base, "tab-1", store, nowMs)

        assertEquals(3, result.leftUnknownVersion)
        assertEquals(2, result.records)
        assertEquals(2, result.filesAbsorbed)
        assertEquals(0, result.badMoved)
        futureTexts.forEach { (file, text) ->
            assertTrue("${file.name} should stay", file.exists())
            assertEquals(text, file.readText())
        }
        assertFalse(badDir(base).exists())
    }

    @Test
    fun badFileMovedToBad() {
        val base = tmp.newFolder()
        val store = newStore()
        store.record(base, rec("r1"))
        store.record(base, rec("r2"))
        val full = MixOrderJson.gson.toJson(rec("cut"))
        val garbage = mapOf(
            "2026-11-03T09-00-00-000_tab-9_mixorder.json" to full.take(full.length / 2),
            "empty.json" to "",
            "array.json" to "[1,2,3]",
            "notrecord.json" to "{}",
            "text.json" to "not json at all"
        )
        garbage.forEach { (name, text) -> writeSingle(base, name, text) }
        writeBundle(base, "torn_bundle.json", "{\"version\":1,\"records\":[")
        File(root(base), "ignored.txt").writeText("x")
        File(root(base), "left.json.tmp").writeText("x")

        val result = consolidateMixOrderLearning(base, "tab-1", store, nowMs)

        assertEquals(6, result.badMoved)
        assertEquals(2, result.records)
        assertEquals(2, result.filesAbsorbed)
        val bad = badDir(base).listFiles()!!.associate { it.name to it.readText() }
        assertEquals(garbage.keys + "torn_bundle.json", bad.keys)
        garbage.forEach { (name, text) -> assertEquals(text, bad[name]) }
        assertEquals(setOf("ignored.txt", "left.json.tmp"), singles(base).map { it.name }.toSet())
        assertEquals(1, bundles(base).size)
        assertEquals(setOf("r1", "r2"), store.readAll(base).map { it.recordId }.toSet())
    }

    @Test
    fun bundleWithSomeUnusableRecordsKeepsGoodOnesAndRetiresFileToBad() {
        val base = tmp.newFolder()
        val store = newStore()
        val goodJson = MixOrderJson.gson.toJsonTree(rec("g1")).toString()
        val mixed = writeBundle(base, "mixed_bundle.json", "{\"version\":1,\"records\":[$goodJson,{},7,null]}")
        val original = mixed.readText()

        val result = consolidateMixOrderLearning(base, "tab-1", store, nowMs)

        assertEquals(1, result.records)
        assertEquals(1, result.badMoved)
        assertEquals(listOf(result.bundle), bundles(base))
        assertEquals(listOf(original), badDir(base).listFiles()!!.map { it.readText() })
        assertEquals(listOf("g1"), store.readAll(base).map { it.recordId })
    }

    @Test
    fun badFileWithSameNameDoesNotOverwriteEarlierBadFile() {
        val base = tmp.newFolder()
        val store = newStore()
        store.record(base, rec("r1"))
        badDir(base).mkdirs()
        File(badDir(base), "x.json").writeText("first")
        writeSingle(base, "x.json", "second")

        consolidateMixOrderLearning(base, "tab-1", store, nowMs)

        assertEquals(setOf("first", "second"), badDir(base).listFiles()!!.map { it.readText() }.toSet())
    }

    @Test
    fun onlyGarbageMovesToBadWithoutWritingBundle() {
        val base = tmp.newFolder()
        val store = newStore()
        writeSingle(base, "junk.json", "nope")

        val result = consolidateMixOrderLearning(base, "tab-1", store, nowMs)

        assertEquals(1, result.badMoved)
        assertEquals(0, result.records)
        assertNull(result.bundle)
        assertFalse(bundlesDir(base).exists())
    }

    @Test
    fun nothingToDoWritesNothing() {
        val empty = tmp.newFolder()
        assertNull(consolidateMixOrderLearning(empty, "tab-1", newStore(), nowMs).bundle)
        assertFalse(root(empty).exists())

        val base = tmp.newFolder()
        val store = newStore()
        store.record(base, rec("r1"))
        val before = snapshot(base)

        val result = consolidateMixOrderLearning(base, "tab-1", store, nowMs)

        assertNull(result.bundle)
        assertEquals(0, result.filesAbsorbed)
        assertEquals(0, result.badMoved)
        assertEquals(before, snapshot(base))
        assertFalse(bundlesDir(base).exists())
        assertFalse(badDir(base).exists())
    }

    @Test
    fun idempotent() {
        val base = tmp.newFolder()
        val store = newStore()
        store.record(base, rec("r1"))
        store.record(base, rec("r2"))
        val first = consolidateMixOrderLearning(base, "tab-1", store, nowMs)
        assertNotNull(first.bundle)
        val after = snapshot(base)

        val second = consolidateMixOrderLearning(base, "tab-1", store, nowMs + 60_000)

        assertNull(second.bundle)
        assertEquals(0, second.filesAbsorbed)
        assertEquals(after, snapshot(base))
    }

    @Test
    fun newRecordsAfterConsolidationFoldIntoNextBundle() {
        val base = tmp.newFolder()
        val store = newStore()
        store.record(base, rec("r1"))
        store.record(base, rec("r2"))
        consolidateMixOrderLearning(base, "tab-1", store, nowMs)
        store.record(base, rec("r3"))

        val result = consolidateMixOrderLearning(base, "tab-1", store, nowMs + 60_000)

        assertEquals(3, result.records)
        assertEquals(2, result.filesAbsorbed)
        assertEquals(listOf(result.bundle), bundles(base))
        assertEquals(setOf("r1", "r2", "r3"), store.readAll(base).map { it.recordId }.toSet())
    }

    @Test
    fun unmountedShareIsNeitherCreatedNorTouched() {
        val missing = File(tmp.root, "unmounted-share")
        val pending = tmp.newFolder()
        val store = MixOrderLearningStore(pending) { clockMs++ }
        store.record(null, rec("p1"))

        val result = consolidateMixOrderLearning(missing, "tab-1", store, nowMs)

        assertNull(result.bundle)
        assertFalse(missing.exists())
        assertEquals(1, pending.listFiles()!!.size)
    }
}
