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
        val cacheDir = File(baseDir, ".metadata/moldings_cache").apply { mkdirs() }
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
        val cacheDir = File(baseDir, ".metadata/moldings_cache").apply { mkdirs() }
        File(cacheDir, "library.json").writeText("not json")

        val repo = MoldingLibraryRepository(baseDir)
        val library = repo.fetchLibrary()

        assertTrue(library.isEmpty)
    }

    @Test
    fun fetchLibrary_handlesNullFieldsGracefully() {
        val baseDir = Files.createTempDirectory("molding-repo-test-nulls").toFile()
        val cacheDir = File(baseDir, ".metadata/moldings_cache").apply { mkdirs() }
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

    @Test
    fun profileSvgFile_returnsPlainSvg_whenMeasurementsHidden() {
        val baseDir = Files.createTempDirectory("molding-repo-test-svg").toFile()
        val categoryDir = File(baseDir, ".metadata/moldings_cache/Crown").apply { mkdirs() }
        File(categoryDir, "105.svg").writeText("<svg>plain</svg>")
        File(categoryDir, "105_dim.svg").writeText("<svg>dimensioned</svg>")

        val repo = MoldingLibraryRepository(baseDir)
        val file = repo.profileSvgFile("Crown", "105", showMeasurements = false)

        assertNotNull(file)
        assertEquals("<svg>plain</svg>", file!!.readText())
    }

    @Test
    fun profileSvgFile_returnsDimensionedSvg_whenMeasurementsShown() {
        val baseDir = Files.createTempDirectory("molding-repo-test-svg-dim").toFile()
        val categoryDir = File(baseDir, ".metadata/moldings_cache/Crown").apply { mkdirs() }
        File(categoryDir, "105.svg").writeText("<svg>plain</svg>")
        File(categoryDir, "105_dim.svg").writeText("<svg>dimensioned</svg>")

        val repo = MoldingLibraryRepository(baseDir)
        val file = repo.profileSvgFile("Crown", "105", showMeasurements = true)

        assertNotNull(file)
        assertEquals("<svg>dimensioned</svg>", file!!.readText())
    }

    @Test
    fun profileSvgFile_returnsNull_whenMissing() {
        val baseDir = Files.createTempDirectory("molding-repo-test-svg-missing").toFile()
        val repo = MoldingLibraryRepository(baseDir)
        assertEquals(null, repo.profileSvgFile("Crown", "999", showMeasurements = false))
    }

    @Test
    fun fetchUsage_returnsJobsForKnownMoldingId() {
        val baseDir = Files.createTempDirectory("molding-repo-test-usage").toFile()
        val cacheDir = File(baseDir, ".metadata/moldings_cache").apply { mkdirs() }
        File(cacheDir, "usage_index.json").writeText(
            """{"Crown:105": [{"job": "616b - Kevin Janni", "type": "crown", "estimatedFeet": 80.0}]}"""
        )

        val repo = MoldingLibraryRepository(baseDir)
        val usage = repo.fetchUsage("Crown:105")

        assertEquals(1, usage.size)
        assertEquals("616b - Kevin Janni", usage[0].job)
        assertEquals("crown", usage[0].type)
        assertEquals(80.0, usage[0].estimatedFeet)
    }

    @Test
    fun fetchUsage_returnsEmptyList_forUnknownMoldingId() {
        val baseDir = Files.createTempDirectory("molding-repo-test-usage-empty").toFile()
        val cacheDir = File(baseDir, ".metadata/moldings_cache").apply { mkdirs() }
        File(cacheDir, "usage_index.json").writeText("""{"Crown:105": []}""")

        val repo = MoldingLibraryRepository(baseDir)
        assertTrue(repo.fetchUsage("Base:7").isEmpty())
    }

    @Test
    fun fetchUsageCounts_returnsSizePerMoldingId() {
        val baseDir = Files.createTempDirectory("molding-repo-test-usage-counts").toFile()
        val cacheDir = File(baseDir, ".metadata/moldings_cache").apply { mkdirs() }
        File(cacheDir, "usage_index.json").writeText(
            """
            {
              "Crown:105": [{"job": "616b - Kevin Janni", "type": "crown", "estimatedFeet": 80.0}],
              "Base:7": [{"job": "601a - Smith", "type": "base"}, {"job": "602b - Jones", "type": "base"}],
              "Casing:12": []
            }
            """.trimIndent()
        )

        val repo = MoldingLibraryRepository(baseDir)
        val counts = repo.fetchUsageCounts()

        assertEquals(3, counts.size)
        assertEquals(1, counts["Crown:105"])
        assertEquals(2, counts["Base:7"])
        assertEquals(0, counts["Casing:12"])
    }

    @Test
    fun fetchUsageCounts_returnsEmptyMap_whenFileMissing() {
        val baseDir = Files.createTempDirectory("molding-repo-test-usage-counts-missing").toFile()
        val repo = MoldingLibraryRepository(baseDir)

        assertTrue(repo.fetchUsageCounts().isEmpty())
    }

    @Test
    fun processSvgForDarkMode_invertsBlackPathsAndWhiteTextBackgrounds_leavingDimensionColorsIntact() {
        val originalSvg = """
            <svg viewBox="0 0 100 100">
                <path d="M 0 0 L 10 10" fill="none" stroke="#000000" stroke-width="0.3"/>
                <line x1="0" y1="0" x2="10" y2="0" stroke="#2563eb" stroke-width="0.5"/>
                <rect x="5" y="5" width="20" height="10" fill="#ffffff"/>
                <text x="15" y="10" fill="#2563eb">4 1/2"</text>
                <rect x="50" y="50" width="20" height="10" rx="2" fill="#dc2626"/>
            </svg>
        """.trimIndent()

        val processed = processSvgForDarkMode(originalSvg)

        assertTrue(processed.contains("""stroke="#ffffff""""))
        assertTrue(processed.contains("""stroke="#2563eb""""))
        assertTrue(processed.contains("""fill="#2563eb""""))
        assertTrue(processed.contains("""rect x="5" y="5" width="20" height="10" fill="#000000""""))
        assertTrue(processed.contains("""rect x="50" y="50" width="20" height="10" rx="2" fill="#dc2626""""))
    }
}
