package com.kkc.sheettracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobFolderNamingTest {

    @Test
    fun parseJobFolderName_acceptsAlphaAndRevisionSuffixes() {
        val parsedA = parseJobFolderName("340a - NORDIC REMAKE CABS")
        val parsedR = parseJobFolderName("340r - NORDIC REMAKE CABS")
        val parsedRev = parseJobFolderName("340a-2 - NORDIC REMAKE CABS")

        assertNotNull(parsedA)
        assertNotNull(parsedR)
        assertNotNull(parsedRev)
        assertEquals("340a", parsedA?.jobNumber)
        assertEquals("340r", parsedR?.jobNumber)
        assertEquals("340a-2", parsedRev?.jobNumber)
    }

    @Test
    fun parseJobFolderName_rejectsMissingPrefix() {
        assertNull(parseJobFolderName("NORDIC REMAKE CABS"))
    }

    @Test
    fun compareJobNumbersDesc_ordersByMajorThenSuffixAndRevision() {
        val ordered = listOf("341", "340", "340a", "340a-2", "340b", "340r", "339")
        val shuffled = ordered.shuffled().sortedWith(::compareJobNumbersDesc)
        assertEquals(ordered, shuffled)
    }

    @Test
    fun compareJobNumbersDesc_placesParsableValuesBeforeUnknownValues() {
        val sorted = listOf("340a", "340", "ABC").sortedWith(::compareJobNumbersDesc)
        assertTrue(sorted.indexOf("ABC") > sorted.indexOf("340"))
    }
}
