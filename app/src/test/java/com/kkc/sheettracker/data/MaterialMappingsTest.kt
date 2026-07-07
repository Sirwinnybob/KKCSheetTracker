package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MaterialMappingsTest {

    @Test
    fun realNameAndSanitizedNameCanonicalizeEqual() {
        val baseDir = createTempDir()
        writeMappingsFile(
            baseDir,
            """{ "1/4 2s Hickory Rustic": "1_4 2s Rustic Hickory" }"""
        )
        val mappings = MaterialMappings.load(baseDir)

        assertEquals(
            mappings.canonical("1/4 2s Hickory Rustic"),
            mappings.canonical("1_4 2s Rustic Hickory")
        )
    }

    @Test
    fun caseAndWhitespaceInsensitive() {
        val mappings = MaterialMappings.of(
            mapOf("1/4 2s Hickory Rustic" to "1_4 2s Rustic Hickory")
        )

        assertEquals(
            mappings.canonical("  1/4 2S HICKORY RUSTIC  "),
            mappings.canonical("1_4 2s rustic hickory")
        )
    }

    @Test
    fun missingFileFallsBackToIdentity() {
        val mappings = MaterialMappings.load(createTempDir())

        assertEquals(
            mappings.canonical("Foo Bar"),
            mappings.canonical(" foo bar ")
        )
    }

    @Test
    fun unknownNameUsesItself() {
        val mappings = MaterialMappings.of(
            mapOf("1/4 2s Hickory Rustic" to "1_4 2s Rustic Hickory")
        )

        assertEquals(
            mappings.canonical("Unknown Material"),
            mappings.canonical(" unknown material ")
        )
    }

    @Test
    fun blankMappedValueFallsBackToNormalizedInput() {
        val mappings = MaterialMappings.of(
            mapOf("3/4 Prefinished Maple" to "   ")
        )

        // A blank mapped value must not collapse the material to "", which would make
        // it match every other blank-mapped material during auto-complete.
        assertEquals("3/4 prefinished maple", mappings.canonical("3/4 Prefinished Maple"))
    }

    private fun createTempDir(): File =
        kotlin.io.path.createTempDirectory("material-mappings-test").toFile()

    private fun writeMappingsFile(baseDir: File, json: String) {
        val metadataDir = baseDir.resolve(".metadata")
        metadataDir.mkdirs()
        metadataDir.resolve("material_mappings.json").writeText(json)
    }
}
