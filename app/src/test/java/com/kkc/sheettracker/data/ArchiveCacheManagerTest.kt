package com.kkc.sheettracker.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun buildTestZip(files: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        val manifestEntries = files.entries.map { (path, data) ->
            val sha256 = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            """{"path":"$path","size":${data.size},"sha256":"$sha256"}"""
        }
        files.forEach { (path, data) ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(data)
            zip.closeEntry()
        }
        val manifestJson = """{"files":[${manifestEntries.joinToString(",")}]}"""
        zip.putNextEntry(ZipEntry("manifest.json"))
        zip.write(manifestJson.toByteArray())
        zip.closeEntry()
    }
    return out.toByteArray()
}

class ArchiveCacheManagerTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `downloadAndExtract validates manifest and promotes a complete cache entry`() = runBlocking {
        val zipBytes = buildTestZip(mapOf("cover.pdf" to "pdf-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(zipBytes)).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        assertTrue(result is ArchiveCacheResult.Success)
        val jobDir = (result as ArchiveCacheResult.Success).jobDir
        assertEquals("pdf-bytes", jobDir.resolve("cover.pdf").readText())
    }

    @Test
    fun `downloadAndExtract fails when a file's hash does not match the manifest`() = runBlocking {
        // NOTE: the originally-specified approach here built a valid zip via buildTestZip and
        // then regex-replaced the sha256 hex string directly on the raw zip bytes (treated as
        // ISO-8859-1). That does not work: ZipOutputStream deflate-compresses manifest.json's
        // content by default, so the literal ASCII "sha256":"..." text never appears verbatim
        // in the zip's byte stream to match against -- verified against a real JVM (0 regex
        // matches on the raw bytes of a zip built this way). The "corrupted" bytes were
        // therefore byte-identical to the original, so that version of this test could never
        // actually exercise the hash-mismatch path. Building the corrupted manifest directly
        // (real file content, wrong declared hash) avoids depending on DEFLATE internals.
        val fileData = "pdf-bytes".toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("cover.pdf"))
            zip.write(fileData)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"files":[{"path":"cover.pdf","size":${fileData.size},"sha256":"deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"}]}""".toByteArray())
            zip.closeEntry()
        }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(out.toByteArray())).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        assertTrue(result is ArchiveCacheResult.Failure)
    }

    @Test
    fun `downloadAndExtract rejects a zip-slip entry`() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("../../evil.txt"))
            zip.write("evil".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"files":[]}""".toByteArray())
            zip.closeEntry()
        }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(out.toByteArray())).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        assertTrue(result is ArchiveCacheResult.Failure)
        assertTrue(!cacheRoot.resolve("../../evil.txt").exists())
    }

    @Test
    fun `downloadAndExtract rejects an absolute drive-letter zip entry`() = runBlocking {
        // "/etc/evil.txt" (no drive letter) resolves harmlessly *inside* root on Windows via
        // File(root, name) -- it is not the dangerous case there. A genuinely absolute entry
        // name supplies its own drive letter, e.g. "C:\evil.txt". zip has no concept of an
        // "invalid" entry name, so a hostile ZIP can contain this literal entry (confirmed by
        // round-tripping it through ZipOutputStream/ZipInputStream).
        //
        // Verified on real JVMs on both platforms (Windows directly, Linux via a portable JDK
        // under WSL): on Windows, File(root, "C:\\evil.txt").canonicalFile() throws
        // IOException rather than landing outside root; on Linux the *same* entry name throws
        // nothing and resolves harmlessly *inside* root (no drive-letter concept there at
        // all) -- neither platform's canonicalFile() behavior is a reliable defense on its
        // own. resolveSafeEntryPath() therefore rejects this pattern explicitly (leading "/"
        // or "\", any embedded "\", or a leading drive-letter prefix) before canonicalization
        // ever runs, so this is caught identically on every platform, not by incidental
        // Windows-only exception behavior.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("C:\\evil.txt"))
            zip.write("evil".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"files":[]}""".toByteArray())
            zip.closeEntry()
        }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(out.toByteArray())).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        assertTrue(result is ArchiveCacheResult.Failure)
    }

    @Test
    fun `downloadAndExtract fails when the archive contains a file not declared in the manifest`() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("cover.pdf"))
            zip.write("pdf-bytes".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("sneaky-extra.pdf"))
            zip.write("extra-bytes".toByteArray())
            zip.closeEntry()
            val sha256 = MessageDigest.getInstance("SHA-256").digest("pdf-bytes".toByteArray())
                .joinToString("") { "%02x".format(it) }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"files":[{"path":"cover.pdf","size":9,"sha256":"$sha256"}]}""".toByteArray())
            zip.closeEntry()
        }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(out.toByteArray())).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        assertTrue(result is ArchiveCacheResult.Failure)
    }

    @Test
    fun `downloadAndExtract fails cleanly instead of crashing when a manifest files entry is missing sha256`() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("cover.pdf"))
            zip.write("pdf-bytes".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            // "sha256" is missing entirely -- JSONObject.getString("sha256") throws
            // org.json.JSONException if this loop isn't wrapped.
            zip.write("""{"files":[{"path":"cover.pdf","size":9}]}""".toByteArray())
            zip.closeEntry()
        }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(out.toByteArray())).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        assertTrue(result is ArchiveCacheResult.Failure)
    }

    @Test
    fun `downloadAndExtract fails cleanly instead of crashing when a manifest files entry has size as a string`() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("cover.pdf"))
            zip.write("pdf-bytes".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            // "size" is a string, not a number -- JSONObject.getLong("size") throws
            // org.json.JSONException if this loop isn't wrapped.
            zip.write("""{"files":[{"path":"cover.pdf","size":"nine","sha256":"deadbeef"}]}""".toByteArray())
            zip.closeEntry()
        }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(out.toByteArray())).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        assertTrue(result is ArchiveCacheResult.Failure)
    }

    @Test
    fun `downloadAndExtract fails cleanly instead of crashing when a manifest files array element is not an object`() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("cover.pdf"))
            zip.write("pdf-bytes".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            // The array element is a plain string, not an object -- filesArray.getJSONObject(i)
            // throws org.json.JSONException if this loop isn't wrapped.
            zip.write("""{"files":["cover.pdf"]}""".toByteArray())
            zip.closeEntry()
        }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(out.toByteArray())).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        assertTrue(result is ArchiveCacheResult.Failure)
    }

    @Test
    fun `downloadAndExtract fails cleanly instead of crashing when the manifest write collides with the folder name`() = runBlocking {
        // folderName intentionally reuses ArchiveCacheManager's private local-cache-manifest
        // file name (".archive_cache_manifest.json") so that a real directory is extracted
        // at the exact path writeManifest() later tries to write a plain-text manifest file
        // to. Writing a regular file over an existing directory throws a real IOException on
        // every platform, so this deterministically exercises writeManifest()'s failure path
        // without relying on filesystem-permission hacks.
        val zipBytes = buildTestZip(mapOf("cover.pdf" to "pdf-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(zipBytes)).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(
            archiveJobId = "100 - Alpha",
            folderName = ".archive_cache_manifest.json",
            contentVersion = "v1",
        )

        assertTrue(result is ArchiveCacheResult.Failure)
    }

    @Test
    fun `a failed re-download does not destroy a previously cached good entry`() = runBlocking {
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val goodZip = buildTestZip(mapOf("cover.pdf" to "good-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(goodZip)).setResponseCode(200))
        val first = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")
        assertTrue(first is ArchiveCacheResult.Success)

        // Second attempt: a corrupted hash, which must fail -- but only after the point where
        // the previous good entry would have been at risk.
        val newFileData = "new-bytes".toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("cover.pdf"))
            zip.write(newFileData)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                """{"files":[{"path":"cover.pdf","size":${newFileData.size},"sha256":"deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"}]}""".toByteArray()
            )
            zip.closeEntry()
        }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(out.toByteArray())).setResponseCode(200))
        val second = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v2")
        assertTrue(second is ArchiveCacheResult.Failure)

        // The original good entry must still be fully intact and readable.
        val cached = manager.getCachedEntry("100 - Alpha")
        assertTrue(cached != null)
        assertEquals("good-bytes", cached!!.jobDir.resolve("cover.pdf").readText())
        assertEquals("v1", cached.contentVersion)
    }

    @Test
    fun `downloadAndExtract on http failure returns Failure without leaving a partial entry`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        assertTrue(result is ArchiveCacheResult.Failure)
        assertEquals(0, cacheRoot.listFiles()?.count { it.name == "100 - Alpha" } ?: 0)
    }

    @Test
    fun `getCachedEntry returns null when nothing is cached`() {
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, "http://unused")
        assertNull(manager.getCachedEntry("100 - Alpha"))
    }

    @Test
    fun `pruneExpiredEntries removes an entry whose last access is older than 24 hours`() = runBlocking {
        val zipBytes = buildTestZip(mapOf("cover.pdf" to "pdf-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(zipBytes)).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())
        manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        manager.pruneExpiredEntries(nowMs = System.currentTimeMillis() + 25L * 60 * 60 * 1000)

        assertNull(manager.getCachedEntry("100 - Alpha"))
    }

    @Test
    fun `downloadAndExtract requests the package path with the job id as a single encoded path segment`() = runBlocking {
        val zipBytes = buildTestZip(mapOf("cover.pdf" to "pdf-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(zipBytes)).setResponseCode(200))
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val result = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")

        assertTrue(result is ArchiveCacheResult.Success)
        val recorded = server.takeRequest()
        // addPathSegment percent-encodes the space as %20, never as '+' (the classic
        // URLEncoder.encode()-in-a-path-segment bug that Task 3 hit and fixed).
        assertEquals(
            "/api/ready-jobs-archive/library/100%20-%20Alpha/package",
            recorded.path,
        )
    }
}
