package com.kkc.sheettracker.data.mixservice

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MixCatalogCacheTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("mix-catalog-cache").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `cache persists snapshot across a new instance`() {
        MixCatalogCache(root).write(snapshot(7L))

        val restored = MixCatalogCache(root).read("100 - Alpha", "Mat")

        assertEquals(7L, restored?.revision)
        assertEquals("Current", restored?.entries?.single()?.name)
        assertEquals(
            "862e4acf38fe31333534bbf6e63a2961121a9d290ecb9a8459c0592b7a9178de.json",
            root.listFiles()!!.single().name
        )
    }

    @Test
    fun `cache rejects corrupt and mismatched embedded snapshot keys`() {
        val cache = MixCatalogCache(root)
        cache.write(snapshot(7L))
        val dataFile = root.listFiles()!!.single()
        dataFile.writeText("not json")
        assertNull(cache.read("100 - Alpha", "Mat"))

        cache.write(snapshot(7L))
        dataFile.writeText("""{"job":"other","material":"Mat","revision":7,"entries":[]}""")
        assertNull(cache.read("100 - Alpha", "Mat"))
    }

    @Test
    fun `cache does not rewrite an equal revision`() {
        val cache = MixCatalogCache(root)
        cache.write(snapshot(7L, "Current"))

        val wrote = cache.write(snapshot(7L, "Changed"))

        assertFalse(wrote)
        assertEquals("Current", cache.read("100 - Alpha", "Mat")?.entries?.single()?.name)
    }

    @Test
    fun `cache replaces a snapshot only when the revision is higher`() {
        val cache = MixCatalogCache(root)
        cache.write(snapshot(7L, "Current"))

        assertFalse(cache.write(snapshot(6L, "Older")))
        assertTrue(cache.write(snapshot(8L, "Newer")))
        assertEquals(8L, cache.read("100 - Alpha", "Mat")?.revision)
        assertEquals("Newer", cache.read("100 - Alpha", "Mat")?.entries?.single()?.name)
    }

    private fun snapshot(revision: Long, name: String = "Current") = MixCatalogSnapshot(
        job = "100 - Alpha",
        material = "Mat",
        revision = revision,
        entries = listOf(
            MixCatalogEntry(
                name = name,
                mixFilename = "$name.mix",
                lifecycle = MixLifecycle.ACTIVE,
                programs = listOf("R1.pgm")
            )
        )
    )
}
