package com.kkc.sheettracker.ui.doorpreview

import com.kkc.sheettracker.data.cvjob.CvJobDoorPreviews
import com.kkc.sheettracker.data.cvjob.parseCvJobDoorPreviews
import org.junit.Assert.assertEquals
import org.junit.Test

class DoorAssemblyCategoryTest {
    private fun previews(): CvJobDoorPreviews.Loaded = parseCvJobDoorPreviews("""
        {
          "assemblies": [{"id":"4","name":"Island Back"},{"id":"5","name":"Right Island Wing DOOR PNL"}],
          "doors": [
            {"id":"door","cabinet":"1","label":"Door","doorType":"P","hinge":"N"},
            {"id":"drawer","cabinet":"1","label":"Drawer","doorType":"DF","hinge":"N"},
            {"id":"baseEnd","cabinet":"1","label":"Base End","doorType":"BE"},
            {"id":"tallEnd","cabinet":"2","label":"Tall End","doorType":" te "},
            {"id":"wallEnd","cabinet":"3","label":"Wall End","doorType":"WE"},
            {"id":"back","cabinet":"4","label":"Back","doorType":"S"},
            {"id":"wing","cabinet":"5","label":"Wing","doorType":"S"},
            {"id":"legacy","cabinet":"6","label":"Legacy"},
            {"id":"unknown","cabinet":"6","label":"Unknown","doorType":"FUTURE"},
            {"id":"hidden","cabinet":"7","label":"Hidden","doorType":"BE","hidden":true}
          ],
          "doorGeometry": [
            {"doorId":"door","width":"18","height":"30"},
            {"doorId":"drawer","width":"36","height":"6"},
            {"doorId":"baseEnd","width":"24","height":"30"},
            {"doorId":"tallEnd","width":"26","height":"90"},
            {"doorId":"wallEnd","width":"12","height":"36"},
            {"doorId":"back","width":"116","height":"35"},
            {"doorId":"wing","width":"35","height":"35"},
            {"doorId":"legacy","width":"20","height":"30"},
            {"doorId":"unknown","width":"20","height":"30"},
            {"doorId":"hidden","width":"24","height":"30"}
          ]
        }
    """.trimIndent()) as CvJobDoorPreviews.Loaded

    @Test fun readerRetainsCvTypeAndParentAssemblyNameWithoutRequiringNewMetadata() {
        val loaded = previews()
        assertEquals("BE", loaded.forDoor("baseEnd")!!.doorType)
        assertEquals("Island Back", loaded.forDoor("back")!!.assemblyName)
        assertEquals("", loaded.forDoor("legacy")!!.doorType)
        assertEquals("", loaded.forDoor("legacy")!!.assemblyName)
    }

    @Test fun groupsPartitionVisibleDoorsAndKeepUnhingedDoorsAndDrawersInTheFirstGroup() {
        val all = doorAssemblyDoors(previews())
        val ordinary = filterDoorAssemblyCategory(all, DoorAssemblyCategory.DOORS_DRAWERS)
        val panels = filterDoorAssemblyCategory(all, DoorAssemblyCategory.PANELED_ENDS_MISC)
        assertEquals(setOf("door", "drawer", "legacy", "unknown"), ordinary.map { it.doorId }.toSet())
        assertEquals(setOf("baseEnd", "tallEnd", "wallEnd", "back", "wing"), panels.map { it.doorId }.toSet())
        assertEquals(all.size, ordinary.size + panels.size)
        assertEquals(all, doorAssemblyDoors(previews()))
    }

    @Test fun categoryFilteringPreservesSortOrderAndWorksWithSearchAndEmptyGroups() {
        val sorted = doorAssemblyDoors(previews(), DoorAssemblySort.SMALLEST_FIRST)
        val panels = filterDoorAssemblyCategory(sorted, DoorAssemblyCategory.PANELED_ENDS_MISC)
        assertEquals(listOf("wallEnd", "baseEnd", "tallEnd", "wing", "back"), panels.map { it.doorId })
        assertEquals(listOf("tallEnd"), filterDoorAssemblyDoors(panels, "TALL").map { it.doorId })
        assertEquals(emptyList<Any>(), filterDoorAssemblyCategory(emptyList(), DoorAssemblyCategory.PANELED_ENDS_MISC))
    }

    @Test fun existingIslandBackStyleIsRecognizedWhenOlderDataOmitsAssemblies() {
        val legacy = previews().forDoor("door")!!.copy(
            doorType = "S", assemblyName = "", style = "INSET - ISLAND BACK 4 PNL 4 1/2 Stiles"
        )
        assertEquals(listOf(legacy), filterDoorAssemblyCategory(listOf(legacy), DoorAssemblyCategory.PANELED_ENDS_MISC))
        val drawer = legacy.copy(doorType = "DF")
        assertEquals(listOf(drawer), filterDoorAssemblyCategory(listOf(drawer), DoorAssemblyCategory.DOORS_DRAWERS))
    }
}
