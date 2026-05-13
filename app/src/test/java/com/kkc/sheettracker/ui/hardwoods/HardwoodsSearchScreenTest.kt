package com.kkc.sheettracker.ui.hardwoods

import com.kkc.sheettracker.data.models.HardwoodDocType
import com.kkc.sheettracker.data.models.HardwoodSearchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwoodsSearchScreenTest {

    @Test
    fun computeHardwoodsSearchMatches_returnsEmptyForBlankQuery() {
        val output = computeHardwoodsSearchMatches(
            allEntries = listOf(sampleEntry(description = "Maple Rail")),
            rawQuery = "   "
        )

        assertTrue(output.results.isEmpty())
        assertEquals(0, output.totalMatches)
    }

    @Test
    fun computeHardwoodsSearchMatches_countsAllMatchesAndLimitsVisibleResults() {
        val matches = (1..130).map { idx ->
            sampleEntry(
                rowId = "row-$idx",
                description = "Rail $idx"
            )
        }
        val nonMatch = sampleEntry(
            rowId = "non-match",
            description = "Stile"
        )

        val output = computeHardwoodsSearchMatches(
            allEntries = matches + nonMatch,
            rawQuery = "  rail ",
            maxResults = 120
        )

        assertEquals(130, output.totalMatches)
        assertEquals(120, output.results.size)
    }

    @Test
    fun computeHardwoodsSearchMatches_matchesExactCabinetValue() {
        val output = computeHardwoodsSearchMatches(
            allEntries = listOf(
                sampleEntry(rowId = "a", cabinetNumbers = listOf("CAB-10", "CAB-20"), description = "Alpha"),
                sampleEntry(rowId = "b", cabinetNumbers = listOf("CAB-210"), description = "Beta")
            ),
            rawQuery = "CAB-10"
        )

        assertEquals(listOf("a"), output.results.map { it.rowId })
        assertEquals(1, output.totalMatches)
    }

    private fun sampleEntry(
        rowId: String = "row",
        description: String = "desc",
        cabinetNumbers: List<String> = emptyList()
    ): HardwoodSearchEntry {
        return HardwoodSearchEntry(
            jobFolderName = "J-100",
            jobNumber = "100",
            jobName = "Sample Job",
            docType = HardwoodDocType.FACE_FRAME_CUT_LIST,
            pdfFilename = "doc.pdf",
            rowId = rowId,
            description = description,
            width = "2",
            length = "3",
            cabinetNumbers = cabinetNumbers
        )
    }
}
