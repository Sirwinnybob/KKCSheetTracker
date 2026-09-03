package com.kkc.sheettracker.data.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class MaterialMetadataMiscTest {
    private val gson = Gson()

    @Test
    fun materialMetadataDecodesMiscLabelFromSidecarJson() {
        val metadata = gson.fromJson(
            """{"jobNumber":"100","material":"Misc Maple","miscLabel":"001 MISC"}""",
            MaterialMetadata::class.java
        )

        assertEquals("001 MISC", metadata.miscLabel)
    }

    @Test
    fun materialMetadataMiscLabelDefaultsToNullWhenAbsent() {
        val metadata = gson.fromJson(
            """{"jobNumber":"100","material":"Plain Birch"}""",
            MaterialMetadata::class.java
        )

        assertEquals(null, metadata.miscLabel)
    }
}
