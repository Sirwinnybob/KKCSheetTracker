package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MoldingLibraryRepositoryTest {

    @Test
    fun fetchLibrary_returnsEmptyLibrary_whenFileDoesNotExist() {
        val baseDir = Files.createTempDirectory("molding-repo-test-empty").toFile()
        val repo = MoldingLibraryRepository(baseDir)
        val library = repo.fetchLibrary()
        assertNotNull(library)
        assertTrue(library.isEmpty)
        assertTrue(library.categories.isEmpty())
    }

    @Test
    fun fetchLibrary_parsesValidJsonSuccessfully() {
        val baseDir = Files.createTempDirectory("molding-repo-test-valid").toFile()
        val cacheDir = File(baseDir, ".metadata/moldings/_cache").apply { mkdirs() }
        val jsonFile = File(cacheDir, "library.json")

        val json = """
            {
              "categories": ["Crown", "Base"],
              "moldings": [
                {"id": "Crown:105", "category": "Crown", "fileId": "105", "name": "3 1/4\" Flat"},
                {"id": "Base:7", "category": "Base", "fileId": "7", "name": "Standard Base"}
              ]
            }
        """.trimIndent()
        jsonFile.writeText(json)

        val repo = MoldingLibraryRepository(baseDir)
        val library = repo.fetchLibrary()

        assertEquals(listOf("Crown", "Base"), library.categories)
        assertEquals(2, library.moldings.size)
        assertEquals("Crown:105", library.moldings[0].id)
        assertEquals("3 1/4\" Flat", library.moldings[0].name)
    }

    @Test
    fun fetchLibrary_handlesMalformedJsonGracefully() {
        val baseDir = Files.createTempDirectory("molding-repo-test-malformed").toFile()
        val cacheDir = File(baseDir, ".metadata/moldings/_cache").apply { mkdirs() }
        File(cacheDir, "library.json").writeText("not json")

        val repo = MoldingLibraryRepository(baseDir)
        val library = repo.fetchLibrary()

        assertTrue(library.isEmpty)
    }

    @Test
    fun fetchLibrary_handlesNullFieldsGracefully() {
        val baseDir = Files.createTempDirectory("molding-repo-test-nulls").toFile()
        val cacheDir = File(baseDir, ".metadata/moldings/_cache").apply { mkdirs() }
        val json = """
            {
              "categories": ["Crown"],
              "moldings": [
                {"id": "Crown:105", "category": "Crown", "fileId": null, "name": null}
              ]
            }
        """.trimIndent()
        File(cacheDir, "library.json").writeText(json)

        val repo = MoldingLibraryRepository(baseDir)
        val library = repo.fetchLibrary()

        assertEquals(1, library.moldings.size)
        assertEquals("Crown:105", library.moldings[0].id)
        assertEquals("", library.moldings[0].fileId)
        assertEquals("", library.moldings[0].name)
    }
}
