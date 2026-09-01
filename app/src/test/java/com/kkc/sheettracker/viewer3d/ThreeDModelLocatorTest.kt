package com.kkc.sheettracker.viewer3d

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ThreeDModelLocatorTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("threeDModelLocatorTest").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `findMediumGlbForRoom returns the model only when it exists`() {
        val model = File(root, "JOB-1/3D/KITCHEN/3d_medium.glb").apply {
            parentFile!!.mkdirs()
            writeText("GLB")
        }

        assertEquals(model, findMediumGlbForRoom(root, "JOB-1", "KITCHEN"))
    }

    @Test
    fun `findMediumGlbForRoom returns null when the selected room has no model`() {
        File(root, "JOB-1/3D/KITCHEN").mkdirs()

        assertNull(findMediumGlbForRoom(root, "JOB-1", "KITCHEN"))
    }
}
