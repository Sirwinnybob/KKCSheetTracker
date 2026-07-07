package com.kkc.sheettracker.ui.assembly

import com.kkc.sheettracker.data.models.AssemblyJobCard
import com.kkc.sheettracker.data.models.AssemblyVirtualCombinedIndex
import com.kkc.sheettracker.data.models.CabinetSheetIndex
import com.kkc.sheettracker.data.models.CabinetSheetIndexDocuments
import com.kkc.sheettracker.data.models.ReferenceDocumentIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class AssemblyDashboardScreenTest {
    @Test
    fun `dashboard cabinet count uses virtual assembly cabinets when available`() {
        val cards = listOf(
            AssemblyJobCard(folderName = "job-with-virtual", jobNumber = "1", jobName = "Virtual"),
            AssemblyJobCard(folderName = "job-with-base", jobNumber = "2", jobName = "Base"),
            AssemblyJobCard(folderName = "missing-index", jobNumber = "3", jobName = "Missing")
        )
        val indexes = mapOf(
            "job-with-virtual" to cabinetIndex(
                baseCabinets = listOf("A"),
                virtualCabinets = listOf("A", "B", "C")
            ),
            "job-with-base" to cabinetIndex(
                baseCabinets = listOf("10", "11"),
                virtualCabinets = emptyList()
            )
        )

        val total = calculateAssemblyDashboardCabinetCount(cards) { folderName ->
            indexes[folderName]
        }

        assertEquals(5, total)
    }

    private fun cabinetIndex(
        baseCabinets: List<String>,
        virtualCabinets: List<String>
    ): CabinetSheetIndex {
        val virtual = virtualCabinets
            .takeIf { it.isNotEmpty() }
            ?.let { cabinets ->
                AssemblyVirtualCombinedIndex(
                    cabinetToPages = cabinets.associateWith { listOf(1) }
                )
            }
        return CabinetSheetIndex(
            documents = CabinetSheetIndexDocuments(
                assembly = ReferenceDocumentIndex(
                    cabinetToPages = baseCabinets.associateWith { listOf(1) },
                    virtualCombined = virtual
                )
            )
        )
    }
}
