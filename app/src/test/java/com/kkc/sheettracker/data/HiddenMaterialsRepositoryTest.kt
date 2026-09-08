package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.HiddenMaterialEntry
import com.kkc.sheettracker.data.models.HiddenMaterialsDocument
import com.kkc.sheettracker.data.models.HiddenMaterialsGlobalDoc
import com.kkc.sheettracker.data.models.HiddenMaterialsJobDoc
import com.kkc.sheettracker.data.models.HiddenMaterialsMode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenMaterialsRepositoryTest {

    @Test
    fun globalAndJobPathsAreNamespacedByMode() {
        val baseDir = File("Y:/Ready Jobs")

        assertEquals(
            File(baseDir, ".metadata/hardwoods/hidden_materials_global.json"),
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS)
        )
        assertEquals(
            File(baseDir, ".metadata/specialty/hidden_materials_global.json"),
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.SPECIALTY)
        )
        assertEquals(
            File(baseDir, "123 - Job/.metadata/hardwoods/hidden_materials.json"),
            hiddenMaterialsJobPath(baseDir, HiddenMaterialsMode.HARDWOODS, "123 - Job")
        )
        assertEquals(
            File(baseDir, "123 - Job/.metadata/specialty/hidden_materials.json"),
            hiddenMaterialsJobPath(baseDir, HiddenMaterialsMode.SPECIALTY, "123 - Job")
        )
    }

    @Test
    fun fetchDocument_defaultsToEmptyWhenNeitherFileExists() {
        val baseDir = Files.createTempDirectory("hidden-materials-repo-empty").toFile()
        val repository = HiddenMaterialsRepository(baseDir)

        val document = repository.fetchDocument(HiddenMaterialsMode.HARDWOODS, "123 - Job")

        assertEquals(HiddenMaterialsDocument(), document)
    }

    @Test
    fun fetchDocument_readsGlobalListAndTheJobsOwnOverrideFile() {
        val baseDir = Files.createTempDirectory("hidden-materials-repo-valid").toFile()
        writeJson(
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS),
            """{"entries":[{"docType":"NAILER_CUT_LIST","material":"Maple","hiddenAt":"t1","tabletId":"tablet-1"}]}"""
        )
        writeJson(
            hiddenMaterialsJobPath(baseDir, HiddenMaterialsMode.HARDWOODS, "123 - Job"),
            """{"hides":[{"docType":"DOOR_LIST","material":"Oak","hiddenAt":"t2","tabletId":"tablet-1"}],"unhides":[]}"""
        )
        val repository = HiddenMaterialsRepository(baseDir)

        val document = repository.fetchDocument(HiddenMaterialsMode.HARDWOODS, "123 - Job")

        assertEquals("Maple", document.global.entries.single().material)
        assertEquals("Oak", document.jobs.getValue("123 - Job").hides.single().material)
    }

    @Test
    fun fetchDocument_omitsTheJobEntryWhenItsOverrideFileIsMissing() {
        val baseDir = Files.createTempDirectory("hidden-materials-repo-no-job-file").toFile()
        writeJson(
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS),
            """{"entries":[]}"""
        )
        val repository = HiddenMaterialsRepository(baseDir)

        val document = repository.fetchDocument(HiddenMaterialsMode.HARDWOODS, "123 - Job")

        assertTrue(document.jobs.isEmpty())
    }

    @Test
    fun fetchDocument_degradesToEmptyOnCorruptJson() {
        val baseDir = Files.createTempDirectory("hidden-materials-repo-corrupt").toFile()
        val globalFile = hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS)
        globalFile.parentFile?.mkdirs()
        globalFile.writeText("not json")
        val repository = HiddenMaterialsRepository(baseDir)

        val document = repository.fetchDocument(HiddenMaterialsMode.HARDWOODS, "123 - Job")

        assertEquals(HiddenMaterialsGlobalDoc(), document.global)
    }

    @Test
    fun fetchDocument_sanitizesExplicitNullFieldsInsteadOfCrashing() {
        // Gson can populate a non-null Kotlin field with an actual null when the JSON key is
        // explicitly present with a null value -- a document read off the shared drive must
        // degrade gracefully rather than NPE on first use (e.g. isHiddenIn's material.trim()).
        val baseDir = Files.createTempDirectory("hidden-materials-repo-null-fields").toFile()
        writeJson(
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS),
            """{"entries":[{"docType":"NAILER_CUT_LIST","material":null,"hiddenAt":null,"tabletId":null}]}"""
        )
        val repository = HiddenMaterialsRepository(baseDir)

        val document = repository.fetchDocument(HiddenMaterialsMode.HARDWOODS, "123 - Job")

        val entry = document.global.entries.single()
        assertEquals("NAILER_CUT_LIST", entry.docType)
        assertEquals("", entry.material)
        assertEquals("", entry.hiddenAt)
        assertEquals("", entry.tabletId)
    }

    @Test
    fun fetchDocument_hardwoodsAndSpecialtyNeverShareState() {
        val baseDir = Files.createTempDirectory("hidden-materials-repo-segregation").toFile()
        writeJson(
            hiddenMaterialsGlobalPath(baseDir, HiddenMaterialsMode.HARDWOODS),
            """{"entries":[{"docType":"DOOR_CUT_LIST","material":"Oak","hiddenAt":"t1","tabletId":"tablet-1"}]}"""
        )
        val repository = HiddenMaterialsRepository(baseDir)

        val hardwoods = repository.fetchDocument(HiddenMaterialsMode.HARDWOODS, "123 - Job")
        val specialty = repository.fetchDocument(HiddenMaterialsMode.SPECIALTY, "123 - Job")

        assertEquals(1, hardwoods.global.entries.size)
        assertTrue(specialty.global.entries.isEmpty())
    }

    @Test
    fun isHiddenIn_trueWhenGloballyHidden() {
        val document = HiddenMaterialsDocument(
            global = HiddenMaterialsGlobalDoc(
                entries = listOf(HiddenMaterialEntry(docType = "NAILER_CUT_LIST", material = "Maple"))
            )
        )

        assertTrue(isHiddenIn(document, "123 - Job", "NAILER_CUT_LIST", "maple"))
    }

    @Test
    fun isHiddenIn_falseWhenJobUnhideOverridesGlobalHide() {
        val document = HiddenMaterialsDocument(
            global = HiddenMaterialsGlobalDoc(
                entries = listOf(HiddenMaterialEntry(docType = "NAILER_CUT_LIST", material = "Maple"))
            ),
            jobs = mapOf(
                "123 - Job" to HiddenMaterialsJobDoc(
                    unhides = listOf(HiddenMaterialEntry(docType = "NAILER_CUT_LIST", material = "Maple"))
                )
            )
        )

        assertFalse(isHiddenIn(document, "123 - Job", "NAILER_CUT_LIST", "Maple"))
        // A different job is unaffected by the first job's override.
        assertTrue(isHiddenIn(document, "456 - Other Job", "NAILER_CUT_LIST", "Maple"))
    }

    @Test
    fun isHiddenIn_trueWhenJobOnlyHideWithoutGlobal() {
        val document = HiddenMaterialsDocument(
            jobs = mapOf(
                "123 - Job" to HiddenMaterialsJobDoc(
                    hides = listOf(HiddenMaterialEntry(docType = "DOOR_LIST", material = "Cherry"))
                )
            )
        )

        assertTrue(isHiddenIn(document, "123 - Job", "DOOR_LIST", "Cherry"))
        assertFalse(isHiddenIn(document, "456 - Other Job", "DOOR_LIST", "Cherry"))
    }

    @Test
    fun isHiddenIn_falseByDefault() {
        assertFalse(isHiddenIn(HiddenMaterialsDocument(), "123 - Job", "DOOR_LIST", "Cherry"))
    }

    private fun writeJson(file: File, json: String) {
        file.parentFile?.mkdirs()
        file.writeText(json)
    }
}
