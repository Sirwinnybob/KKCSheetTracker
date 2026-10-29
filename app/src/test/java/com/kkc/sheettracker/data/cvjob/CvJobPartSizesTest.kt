package com.kkc.sheettracker.data.cvjob

import com.kkc.sheettracker.data.models.Job
import com.kkc.sheettracker.data.models.Material
import com.kkc.sheettracker.data.models.MaterialMetadata
import com.kkc.sheettracker.data.models.PageMetadata
import com.kkc.sheettracker.data.models.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Job 711 remake sheet 2: the CNC export carried w=0 l=0 for cab 8 "Finished Right End". */
class CvJobPartSizesTest {
    private fun cvPart(
        cabinet: String,
        description: String,
        width: String,
        length: String,
        hidden: Boolean = false,
    ) = """{"id":"1","cabinet":"$cabinet","description":"$description","width":"$width","length":"$length","hidden":$hidden}"""

    private fun index(vararg parts: String) =
        parseCvPartSizeIndex("""{"parts":[${parts.joinToString(",")}]}""")

    private fun job(vararg parts: Part) = Job(
        folderName = "711 - WIECHERT",
        jobNumber = "711",
        jobName = "WIECHERT",
        materials = listOf(
            Material(
                pdfFilename = "711 - 001 REMAKE - 3_4 PG Armor Core.pdf",
                materialName = "001 REMAKE - 3_4 PG Armor Core",
                pageCount = 1,
                metadata = MaterialMetadata(pages = listOf(PageMetadata(pageNumber = 2, parts = parts.toList()))),
            )
        ),
    )

    private fun Job.parts() = materials.single().metadata!!.pages.single().parts

    private val zeroSized = Part(number = 1, width = 0.0, length = 0.0, name = "Finished Right End", cabNumber = 8)

    @Test
    fun fillsAZeroSizedPartFromCvJobData() {
        val idx = index(cvPart("8", "Finished Right End", "11.25", "37.5"), cvPart("15", "Finished Right End", "23.25", "31.75"))

        val filled = fillMissingCncPartSizes(job(zeroSized)) { idx }

        assertEquals(11.25, filled.parts().single().width, 0.0)
        assertEquals(37.5, filled.parts().single().length, 0.0)
        assertEquals("Finished Right End", filled.parts().single().name)
        assertEquals(1, filled.parts().single().number)
    }

    @Test
    fun matchesNameCaseInsensitivelyAndPrefersVisibleParts() {
        val idx = index(
            cvPart("8", "finished right end", "11.25", "37.5"),
            cvPart("8", "Finished Right End", "1", "1", hidden = true),
        )

        assertEquals(11.25, fillMissingCncPartSizes(job(zeroSized)) { idx }.parts().single().width, 0.0)
    }

    @Test
    fun leavesAmbiguousPartsAlone() {
        val idx = index(cvPart("8", "Finished Right End", "11.25", "37.5"), cvPart("8", "Finished Right End", "12", "30"))

        assertEquals(0.0, fillMissingCncPartSizes(job(zeroSized)) { idx }.parts().single().width, 0.0)
    }

    @Test
    fun ignoresCvEntriesWithoutAUsableSize() {
        val idx = index(cvPart("8", "Finished Right End", "0", "0"), cvPart("8", "Finished Right End", "bad", "5"))

        assertEquals(0.0, fillMissingCncPartSizes(job(zeroSized)) { idx }.parts().single().width, 0.0)
    }

    @Test
    fun keepsPartsThatAlreadyHaveSizes() {
        val sized = Part(number = 2, width = 20.25, length = 31.75, name = "Finished Left End", cabNumber = 14)
        val j = job(sized)

        assertSame(j, fillMissingCncPartSizes(j) { error("job data must not be read when no part is missing a size") })
    }

    @Test
    fun missingOrBrokenJobDataChangesNothing() {
        val j = job(zeroSized)

        assertSame(j, fillMissingCncPartSizes(j) { null })
        assertNull(parseCvPartSizeIndex("not json"))
        assertNull(parseCvPartSizeIndex("""{"parts":"bad"}"""))
        assertNull(parseCvPartSizeIndex("""{"other":[]}"""))
    }

    @Test
    fun aPartWithoutACabinetNumberIsNotGuessed() {
        val idx = index(cvPart("0", "Finished Right End", "11.25", "37.5"))
        val noCab = zeroSized.copy(cabNumber = 0)

        assertEquals(0.0, fillMissingCncPartSizes(job(noCab)) { idx }.parts().single().width, 0.0)
    }
}
