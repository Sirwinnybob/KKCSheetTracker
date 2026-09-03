package com.kkc.sheettracker.data.mixservice

import java.io.File
import java.nio.file.Files
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
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
        assertNull(MixCatalogCache(root).read("100 - Alpha", "Mat"))

        cache.write(snapshot(7L))
        dataFile.writeText(
            """{"fetchedAtMillis":1,"snapshot":{"job":"other","material":"Mat","revision":7,"entries":[]}}"""
        )
        assertNull(MixCatalogCache(root).read("100 - Alpha", "Mat"))
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
    fun `cache replaces a snapshot when a different revision is fetched`() {
        val cache = MixCatalogCache(root)
        cache.write(snapshot(7L, "Current"))

        assertTrue(cache.write(snapshot(6L, "Different")))
        assertTrue(cache.write(snapshot(8L, "Newer")))
        assertEquals(8L, cache.read("100 - Alpha", "Mat")?.revision)
        assertEquals("Newer", cache.read("100 - Alpha", "Mat")?.entries?.single()?.name)
    }

    @Test
    fun `cache rejects snapshots with invalid required fields and lifecycles`() {
        val cache = MixCatalogCache(root)
        cache.write(snapshot(7L))
        val dataFile = root.listFiles()!!.single()

        listOf(
            """{"fetchedAtMillis":1,"snapshot":{"job":"","material":"Mat","revision":7,"entries":[]}}""",
            """{"fetchedAtMillis":1,"snapshot":{"job":"100 - Alpha","material":"Mat","revision":"7","entries":[]}}""",
            """{"fetchedAtMillis":1,"snapshot":{"job":"100 - Alpha","material":"Mat","revision":7.5,"entries":[]}}""",
            """{"fetchedAtMillis":1,"snapshot":{"job":"100 - Alpha","material":"Mat","revision":7,"entries":null}}""",
            """{"fetchedAtMillis":1,"snapshot":{"job":"100 - Alpha","material":"Mat","revision":7,"entries":[null]}}""",
            """{"fetchedAtMillis":1,"snapshot":{"job":"100 - Alpha","material":"Mat","revision":7,"entries":[{"name":"","mixFilename":"Current.mix","lifecycle":"active"}]}}""",
            """{"fetchedAtMillis":1,"snapshot":{"job":"100 - Alpha","material":"Mat","revision":7,"entries":[{"name":"Current","mixFilename":"Current.mix","lifecycle":"unknown"}]}}"""
        ).forEach { invalid ->
            dataFile.writeText(invalid)
            assertNull(MixCatalogCache(root).read("100 - Alpha", "Mat"))
        }
    }

    @Test
    fun `cache keeps a fetched timestamp with the in-memory entry`() {
        val cache = MixCatalogCache(root, nowMillis = { 1234L })
        cache.write(snapshot(7L))

        assertEquals(1234L, cache.readCached("100 - Alpha", "Mat")?.fetchedAtMillis)
    }

    @Test
    fun `cache reads the fresh in-memory entry before the backing file`() {
        val cache = MixCatalogCache(root, nowMillis = { 1234L })
        cache.write(snapshot(7L))
        root.listFiles()!!.single().delete()

        val cached = cache.readCached("100 - Alpha", "Mat")

        assertEquals(7L, cached?.snapshot?.revision)
        assertEquals(1234L, cached?.fetchedAtMillis)
    }

    @Test
    fun `offline refresh keeps the last valid catalog visible`() = runBlocking {
        val cache = MixCatalogCache(root)
        val reader = FakeCatalogReader(MixCatalogFetchResult.NetworkError)
        val repository = MixCatalogRepository(reader, cache)
        cache.write(snapshot(7L))

        assertEquals(7L, repository.cached("100 - Alpha", "Mat")!!.revision)
        assertEquals(MixCatalogFetchResult.NetworkError, repository.refresh("100 - Alpha", "Mat"))
        assertEquals(7L, repository.cached("100 - Alpha", "Mat")!!.revision)
    }

    @Test
    fun `malformed successful refresh is rejected and preserves the previous cache`() = runBlocking {
        val cache = MixCatalogCache(root)
        cache.write(snapshot(7L))
        val malformed = Gson().fromJson<MixCatalogSnapshot>(
            """{"job":"100 - Alpha","material":"Mat","revision":8,"entries":[{"name":"Broken","mixFilename":"Broken.mix","lifecycle":null,"programs":["R1.pgm"]}]}""",
            MixCatalogSnapshot::class.java,
        )
        val repository = MixCatalogRepository(
            FakeCatalogReader(MixCatalogFetchResult.Success(malformed)),
            cache,
        )

        assertEquals(MixCatalogFetchResult.NetworkError, repository.refresh("100 - Alpha", "Mat"))
        assertEquals(7L, repository.cached("100 - Alpha", "Mat")?.revision)
        assertEquals("Current", repository.cached("100 - Alpha", "Mat")?.entries?.single()?.name)
    }

    @Test
    fun `cache write rejects malformed deserialized snapshots without throwing`() {
        val cache = MixCatalogCache(root)
        cache.write(snapshot(7L))
        val malformed = Gson().fromJson<MixCatalogSnapshot>(
            """{"job":"100 - Alpha","material":"Mat","revision":8,"entries":[{"name":"Broken","mixFilename":"Broken.mix","lifecycle":null,"programs":["R1.pgm"]}]}""",
            MixCatalogSnapshot::class.java,
        )

        assertFalse(cache.write(malformed))
        assertEquals(7L, cache.read("100 - Alpha", "Mat")?.revision)
    }

    private class FakeCatalogReader(private val result: MixCatalogFetchResult) : MixCatalogReader {
        override suspend fun getMixCatalog(job: String, material: String): MixCatalogFetchResult = result
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
