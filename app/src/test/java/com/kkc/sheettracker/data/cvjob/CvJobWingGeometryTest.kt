package com.kkc.sheettracker.data.cvjob

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CvJobWingGeometryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private fun fixture(): String = requireNotNull(
        javaClass.classLoader?.getResource("cvjob/verified-wing-export.json")
    ).readText()

    @Test
    fun parsesCapturedWings_keepsSourceNodesAndHidesOnlySupportsFromPresentation() {
        val result = parseCvJobWings(fixture())
        assertTrue("expected loaded wings, got $result", result is CvJobWings.Loaded)
        val wings = (result as CvJobWings.Loaded).wings

        assertEquals(listOf("3", "4", "5", "6"), wings.map { it.cabinet })
        assertEquals(listOf(11, 11, 10, 10), wings.map { it.nodes.size })
        assertEquals(listOf(5, 5, 5, 5), wings.map { it.visibleNodes().size })
        assertTrue(wings.flatMap { it.visibleNodes() }.none { it.code in setOf("TS", "DE") })
        assertTrue(wings.flatMap { it.nodes }.any { it.code == "TS" })
        assertTrue(wings.flatMap { it.nodes }.any { it.code == "DE" })

        val paneled = wings.first { it.cabinet == "3" }
        assertEquals("53738", paneled.nodeForDoor("53738")?.doorId)
        assertEquals("y", paneled.nodes.first { it.code == "RF" }.material?.grainAxis)
        assertTrue(paneled.nodes.first { it.code == "RF" }.material?.materialId?.isNotBlank() == true)
    }

    @Test
    fun noOrMalformedWingGeometry_isUnavailableWithoutThrowing() {
        assertEquals(CvJobWings.Missing, parseCvJobWings("{\"schemaVersion\":2}"))
        assertTrue(parseCvJobWings("{not json") is CvJobWings.Unreadable)
        assertTrue(parseCvJobWings("{\"wingGeometry\":[{\"cabinet\":\"3\",\"nodes\":[]}]}") is CvJobWings.Unreadable)
    }

    @Test
    fun loadWings_readsOnlyTheCanonicalPublishedJobDataFile() {
        val job = temporaryFolder.newFolder("688 - TEST")
        val metadata = java.io.File(job, ".metadata/cv_job").apply { mkdirs() }
        java.io.File(metadata, "job_data.json").writeText(fixture())

        val loaded = loadCvJobWings(temporaryFolder.root.absolutePath, job.name)

        assertTrue(loaded is CvJobWings.Loaded)
        assertEquals(CvJobWings.Missing, loadCvJobWings("", job.name))
    }
}
