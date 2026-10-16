package com.kkc.sheettracker.data.cvjob

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CvMaterialTexturesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun library(textureName: String = "Brown Hickory", source: String = "Brown Hickory.jpg",
        image: String = "images/wood.jxl", width: Double = 508.0, height: Double = 1016.0,
        materialName: String = "Catalog name") = parseCvMaterialTextures("""{
        "schemaVersion":1,
        "textures":[{"id":1355,"name":"$textureName","sourcePath":"$source","file":"$image",
          "worldWidthMm":$width,"worldHeightMm":$height,"tile":true}],
        "materials":[{"id":1185,"name":"$materialName","layers":[
          {"typeId":2,"textureId":999},{"typeId":1,"textureId":1355}]}]
        }""", temporary.root)

    private fun asset() = File(temporary.root, "images/wood.jxl").apply { parentFile!!.mkdirs(); writeText("stub") }

    @Test fun materialIdWinsOverNamesAndOnlyTheFaceLayerSuppliesThePhysicalRepeat() {
        asset()
        val catalog = library()
        val texture = catalog.faceTexture("1185", "Different report name")!!
        assertEquals(20.0, texture.worldWidthInches!!, 0.00001)
        assertEquals(40.0, texture.worldHeightInches!!, 0.00001)
        assertTrue(texture.tile)
        assertEquals(asset().canonicalFile, texture.file)
        assertEquals(texture, catalog.faceTexture(null, "Catalog name"))
        assertNull("An unknown published ID must not guess a material by name", catalog.faceTexture("777", "Catalog name"))
    }

    @Test fun paintGradeAndCabinetsFilenameAlwaysUseWhiteButMaterialNamesDoNotOverrideRealWood() {
        asset()
        assertNull(library(textureName = "pAiNt GrAdE").faceTexture("1185", ""))
        assertNull(library(source = "System/CABINETS.JPG").faceTexture("1185", ""))
        assertNull(library(textureName = "Blank").faceTexture("1185", ""))
        assertNull(library(textureName = "No Texture").faceTexture("1185", ""))
        assertNotNull(library(materialName = "Paint Grade Wood").faceTexture("1185", "Paint Grade Wood"))
        assertNotNull(library(materialName = "1/4 MDF").faceTexture("1185", "1/4 MDF"))
    }

    @Test fun absentFilesAssignmentsAndUnsafePathsHaveNoTexture() {
        assertNull(library().faceTexture("1185", ""))
        asset()
        for (path in listOf("../outside.jxl", "/outside.jxl", "C:/outside.jxl", "images/../../wood.jxl")) {
            assertNull(path, library(image = path).faceTexture("1185", ""))
        }
        assertNull(library().faceTexture(null, "Unknown"))
        assertTrue(parseCvMaterialTextures("broken", temporary.root).isEmpty)
        assertTrue(parseCvMaterialTextures("""{"schemaVersion":99}""", temporary.root).isEmpty)
    }

    @Test fun missingPhysicalSizeStaysUnspecifiedAndAnAmbiguousNameIsNeverGuessed() {
        asset()
        val texture = library(width = 0.0, height = -1.0).faceTexture("1185", "")!!
        assertNull(texture.worldWidthInches)
        assertNull(texture.worldHeightInches)
        val catalog = parseCvMaterialTextures("""{"schemaVersion":1,"textures":[],"materials":[
            {"id":1,"name":"Duplicate","layers":[]},{"id":2,"name":"Duplicate","layers":[]}]}""", temporary.root)
        assertNull(catalog.faceTexture(null, "Duplicate"))
    }
}
