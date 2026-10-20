package com.kkc.sheettracker.viewer3d

import com.kkc.sheettracker.data.cvjob.parseCvMaterialTextures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WingTextureBridgeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun descriptor_prefersPublishedMaterialId_andPreservesPhysicalTextureMetadata() {
        tmp.newFile("oak.jxl")
        val library = parseCvMaterialTextures("""
            {"schemaVersion":"1","textures":[{"id":"tex","name":"Oak","file":"oak.jxl","worldWidthMm":"508","worldHeightMm":"254","tile":"true"}],"materials":[{"id":"42","name":"Oak","layers":[{"typeId":"1","textureId":"tex"}]}]}
        """.trimIndent(), tmp.root)

        val descriptor = WingTextureBridge.descriptor(library, "42", "Wrong name")
        assertEquals(20.0, descriptor!!.worldWidthInches!!, 0.0001)
        assertEquals(10.0, descriptor.worldHeightInches!!, 0.0001)
        assertEquals(true, descriptor.tile)
    }

    @Test
    fun descriptor_returnsNull_whenNoVerifiedTextureExists() {
        assertNull(WingTextureBridge.descriptor(parseCvMaterialTextures("{}", tmp.root), "42", "Oak"))
    }

    @Test
    fun materialTextureRoot_isSharedByAllJobs() {
        assertEquals(
            File(tmp.root, ".metadata/material_textures").canonicalFile,
            wingMaterialTextureRoot(tmp.root.absolutePath).canonicalFile
        )
    }
}
