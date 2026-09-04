package com.kkc.sheettracker.data.mixservice

import java.io.File
import java.nio.file.Files
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
    fun `publisher treats an equal revision as durably available`() {
        val cache = MixCatalogCache(root)
        cache.write(snapshot(7L, "Current"))

        assertTrue(cache.publish(snapshot(7L, "Changed")))
        assertEquals("Current", cache.read("100 - Alpha", "Mat")?.entries?.single()?.name)
    }

    @Test
    fun `publisher never regresses a newer current cache with an older completed snapshot`() {
        val cache = MixCatalogCache(root)
        cache.write(snapshot(12L, "Current"))

        assertTrue(cache.publish(snapshot(8L, "Completed")))

        assertEquals(12L, cache.read("100 - Alpha", "Mat")?.revision)
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
            """{"fetchedAtMillis":1,"snapshot":{"job":"100 - Alpha","material":"Mat","revision":0,"entries":[]}}""",
            """{"fetchedAtMillis":1,"snapshot":{"job":"100 - Alpha","material":"Mat","entries":[]}}""",
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

    @Test
    fun `out of order same key refreshes cannot overwrite the latest request`() = runBlocking {
        val cache = MixCatalogCache(root)
        val reader = SequencedCatalogReader()
        val repository = MixCatalogRepository(reader, cache)

        val first = async(Dispatchers.Default) {
            repository.refresh("100 - Alpha", "Mat")
        }
        reader.firstStarted.await()
        val second = async(Dispatchers.Default) {
            repository.refresh("100 - Alpha", "Mat")
        }
        reader.secondStarted.await()

        reader.secondResponse.complete(MixCatalogFetchResult.Success(snapshot(20L, "Latest")))
        assertTrue(second.await() is MixCatalogFetchResult.Success)
        reader.firstResponse.complete(MixCatalogFetchResult.Success(snapshot(10L, "Stale")))

        assertEquals(MixCatalogFetchResult.NetworkError, first.await())
        assertEquals(20L, repository.cached("100 - Alpha", "Mat")?.revision)
        assertEquals("Latest", repository.cached("100 - Alpha", "Mat")?.entries?.single()?.name)
    }

    @Test
    fun `external cache publication supersedes an in flight refresh`() = runBlocking {
        val cache = MixCatalogCache(root)
        cache.write(snapshot(7L, "Existing"))
        val reader = SequencedCatalogReader()
        val repository = MixCatalogRepository(reader, cache)

        val refresh = async(Dispatchers.Default) {
            repository.refresh("100 - Alpha", "Mat")
        }
        reader.firstStarted.await()
        assertTrue(cache.write(snapshot(9L, "ExternallyPublished")))
        reader.firstResponse.complete(MixCatalogFetchResult.Success(snapshot(8L, "Stale")))

        assertEquals(MixCatalogFetchResult.NetworkError, refresh.await())
        assertEquals(9L, repository.cached("100 - Alpha", "Mat")?.revision)
        assertEquals(
            "ExternallyPublished",
            repository.cached("100 - Alpha", "Mat")?.entries?.single()?.name,
        )
    }

    @Test
    fun `failed refresh publication retains the acknowledged snapshot and delivers contained callback`() = runBlocking {
        val cache = MixCatalogCache(root)
        cache.write(snapshot(7L, "Existing"))
        assertTrue(root.deleteRecursively())
        assertTrue(root.createNewFile())
        val repository = MixCatalogRepository(
            FakeCatalogReader(MixCatalogFetchResult.Success(snapshot(8L, "Fresh"))),
            cache,
        )
        val scope = CoroutineScope(Dispatchers.Default)
        val callback = CompletableDeferred<MixCatalogFetchResult>()

        try {
            assertEquals(7L, repository.cachedAndRefresh("100 - Alpha", "Mat", scope) {
                callback.complete(it)
            }?.revision)
            assertEquals(MixCatalogFetchResult.NetworkError, callback.await())
            assertEquals(8L, repository.cached("100 - Alpha", "Mat")?.revision)
            assertEquals("Fresh", repository.cached("100 - Alpha", "Mat")?.entries?.single()?.name)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed catalog persistence publishes an observable snapshot and records recovery state`() {
        val cache = MixCatalogCache(root)
        cache.write(snapshot(7L, "Existing"))
        assertTrue(root.deleteRecursively())
        assertTrue(root.createNewFile())

        assertFalse(cache.write(snapshot(8L, "Completed")))

        assertEquals(8L, cache.read("100 - Alpha", "Mat")?.revision)
        assertEquals("Completed", cache.read("100 - Alpha", "Mat")?.entries?.single()?.name)
        assertEquals(listOf(8L), cache.durabilityState.value.pending.map { it.revision })
    }

    @Test
    fun `pending acknowledged snapshot cannot be overwritten by an older refresh`() {
        val cache = MixCatalogCache(root)
        cache.write(snapshot(7L, "Existing"))
        assertTrue(root.deleteRecursively())
        assertTrue(root.createNewFile())
        assertFalse(cache.write(snapshot(8L, "Completed")))

        val expected = cache.readCached("100 - Alpha", "Mat")
        assertEquals(
            MixCatalogCacheWriteResult.SUPERSEDED,
            cache.writeIfUnchanged(snapshot(7L, "Stale"), expected),
        )
        assertEquals("Completed", cache.read("100 - Alpha", "Mat")?.entries?.single()?.name)
        assertEquals(listOf(8L), cache.durabilityState.value.pending.map { it.revision })
    }

    @Test
    fun `pending acknowledged snapshot can be durably retried without mutation replay`() {
        val cache = MixCatalogCache(root)
        cache.write(snapshot(7L, "Existing"))
        assertTrue(root.deleteRecursively())
        assertTrue(root.createNewFile())
        assertFalse(cache.write(snapshot(8L, "Completed")))

        assertTrue(root.delete())
        assertTrue(root.mkdirs())
        assertTrue(cache.retryPendingWrites())

        assertEquals(8L, MixCatalogCache(root).read("100 - Alpha", "Mat")?.revision)
        assertTrue(cache.durabilityState.value.pending.isEmpty())
    }

    @Test
    fun `retrying pending snapshot never regresses a newer revision from another cache instance`() {
        val firstCache = MixCatalogCache(root)
        firstCache.write(snapshot(7L, "Existing"))
        assertTrue(root.deleteRecursively())
        assertTrue(root.createNewFile())
        assertFalse(firstCache.write(snapshot(8L, "Acknowledged")))

        assertTrue(root.delete())
        assertTrue(root.mkdirs())
        val secondCache = MixCatalogCache(root)
        assertTrue(secondCache.write(snapshot(9L, "Newer")))

        assertTrue(firstCache.retryPendingWrites())

        assertEquals(9L, MixCatalogCache(root).read("100 - Alpha", "Mat")?.revision)
        assertEquals("Newer", MixCatalogCache(root).read("100 - Alpha", "Mat")?.entries?.single()?.name)
        assertTrue(firstCache.durabilityState.value.pending.isEmpty())
    }

    @Test
    fun `publisher retries an in-memory pending snapshot after persistence recovers`() {
        val cache = MixCatalogCache(root)
        assertTrue(root.deleteRecursively())
        assertTrue(root.createNewFile())
        val acknowledged = snapshot(8L, "Completed")
        assertFalse(cache.publish(acknowledged))

        assertTrue(root.delete())
        assertTrue(root.mkdirs())
        assertTrue(cache.publish(acknowledged))

        assertEquals(8L, MixCatalogCache(root).read("100 - Alpha", "Mat")?.revision)
        assertTrue(cache.durabilityState.value.pending.isEmpty())
    }

    private class SequencedCatalogReader : MixCatalogReader {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val firstResponse = CompletableDeferred<MixCatalogFetchResult>()
        val secondResponse = CompletableDeferred<MixCatalogFetchResult>()
        private var calls = 0

        override suspend fun getMixCatalog(job: String, material: String): MixCatalogFetchResult {
            return when (synchronized(this) { calls++ }) {
                0 -> {
                    firstStarted.complete(Unit)
                    firstResponse.await()
                }
                1 -> {
                    secondStarted.complete(Unit)
                    secondResponse.await()
                }
                else -> error("unexpected catalog request")
            }
        }
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
