package com.kkc.sheettracker.data.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheIndexModelsTest {
    private val gson = Gson()

    @Test
    fun cncRenestedCountsDecodeFromCacheIndexAndStaySeparateFromSkipped() {
        val root = gson.fromJson(
            """{"progressSummary":{"cnc":{"totalSheets":90,"done":77,"skipped":0,"renested":13,"materials":[{"materialName":"Armor Core","totalSheets":15,"done":2,"skipped":0,"renested":13}]}}}""",
            CacheIndexRoot::class.java
        )

        val cnc = requireNotNull(root.progressSummary?.cnc)
        assertEquals(13, cnc.renested)
        assertEquals(0, cnc.skipped)
        assertEquals(13, cnc.materials.single().renested)
        assertEquals(13, cnc.materials.single().toStatusCounts().reNested)
    }

    @Test
    fun cacheIndexWithoutRenestedRemainsBackwardCompatible() {
        val root = gson.fromJson(
            """{"progressSummary":{"cnc":{"totalSheets":1,"materials":[{"materialName":"Legacy","totalSheets":1}]}}}""",
            CacheIndexRoot::class.java
        )

        assertEquals(0, root.progressSummary?.cnc?.renested)
        assertEquals(0, root.progressSummary?.cnc?.materials?.single()?.renested)
    }

    @Test
    fun cncIsMiscDecodesFromCacheIndexAndStaysSeparateFromIsRemake() {
        val root = gson.fromJson(
            """{"progressSummary":{"cnc":{"totalSheets":1,"materials":[{"materialName":"Misc Maple","totalSheets":1,"isMisc":true},{"materialName":"Remake Oak","totalSheets":1,"isRemake":true}]}}}""",
            CacheIndexRoot::class.java
        )

        val materials = requireNotNull(root.progressSummary?.cnc?.materials)
        val misc = materials.single { it.materialName == "Misc Maple" }
        val remake = materials.single { it.materialName == "Remake Oak" }
        assertTrue(misc.isMisc)
        assertTrue(!misc.isRemake)
        assertTrue(remake.isRemake)
        assertTrue(!remake.isMisc)
    }
}
