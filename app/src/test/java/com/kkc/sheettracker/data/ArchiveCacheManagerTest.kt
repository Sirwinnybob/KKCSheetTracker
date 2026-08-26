package com.kkc.sheettracker.data

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Serves the same response body to every request, but blocks each one in [dispatch] until
 * [expectedRequests] requests have arrived before releasing any of them. Used to force two
 * concurrent [ArchiveCacheManager.downloadAndExtract] calls to actually be in flight at the same
 * time -- and therefore to reach the promotion step (and its per-archiveJobId lock) at roughly
 * the same moment -- rather than one completing (and promoting) before the other has even
 * started, which a plain sequential `enqueue`/`await` pair would not exercise.
 */
private class SynchronizingDispatcher(
    private val responseBytes: ByteArray,
    expectedRequests: Int,
) : Dispatcher() {
    private val arrived = CountDownLatch(expectedRequests)

    override fun dispatch(request: RecordedRequest): MockResponse {
        arrived.countDown()
        arrived.await()
        return MockResponse().setBody(okio.Buffer().write(responseBytes)).setResponseCode(200)
    }
}

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
    fun `downloadAndExtract reports received package bytes with the HTTP content length`() = runBlocking {
        val zipBytes = buildTestZip(mapOf("cover.pdf" to ByteArray(32 * 1024) { it.toByte() }))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Length", zipBytes.size)
                .setBody(okio.Buffer().write(zipBytes))
                .setResponseCode(200),
        )
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())
        val reports = mutableListOf<ArchiveDownloadProgress>()

        val result = manager.downloadAndExtract(
            archiveJobId = "100 - Alpha",
            folderName = "100 - Alpha",
            contentVersion = "v1",
            onDownloadProgress = reports::add,
        )

        assertTrue(result is ArchiveCacheResult.Success)
        assertTrue("expected real byte progress callbacks", reports.isNotEmpty())
        assertEquals(0L, reports.first().bytesRead)
        assertEquals(zipBytes.size.toLong(), reports.last().bytesRead)
        assertEquals(zipBytes.size.toLong(), reports.last().totalBytes)
        assertTrue(reports.zipWithNext().all { (previous, next) -> previous.bytesRead <= next.bytesRead })
    }

    @Test
    fun `zero content length is treated as an unknown progress total`() {
        assertNull(archiveProgressTotalBytes(0L))
    }

    @Test
    fun `downloadAndExtract reports an unknown total for a chunked package response`() = runBlocking {
        val zipBytes = buildTestZip(mapOf("cover.pdf" to ByteArray(8 * 1024) { it.toByte() }))
        server.enqueue(
            MockResponse()
                .setChunkedBody(okio.Buffer().write(zipBytes), 1024)
                .setResponseCode(200),
        )
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())
        val reports = mutableListOf<ArchiveDownloadProgress>()

        val result = manager.downloadAndExtract(
            archiveJobId = "100 - Alpha",
            folderName = "100 - Alpha",
            contentVersion = "v1",
            onDownloadProgress = reports::add,
        )

        assertTrue(result is ArchiveCacheResult.Success)
        assertEquals(null, reports.first().totalBytes)
        assertEquals(null, reports.last().totalBytes)
        assertEquals(zipBytes.size.toLong(), reports.last().bytesRead)
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
    fun `a double promotion failure preserves the backup directory and its content on disk`() = runBlocking {
        // Regression test for the bug where the outer `finally` block unconditionally deleted
        // the `.backup` scratch directory even when BOTH the promotion move (staging ->
        // finalDir) and the restore-from-backup move (backup -> finalDir) failed -- e.g. two
        // consecutive transient I/O errors on the same volume (low disk space). In that exact
        // double-failure case, the previously-good entry is left ONLY in the backup directory
        // (finalDir itself ends up empty/missing), so deleting the backup too was a genuine
        // data-loss bug: the function correctly returned Failure, but the one remaining copy
        // of the last-known-good content was destroyed anyway.
        //
        // Forcing this deterministically requires the atomicMove seam: real filesystem
        // permissions/locks cannot portably force exactly "the second and third renames fail,
        // the first one succeeds" from a JVM unit test without flaky timing tricks.
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val goodZip = buildTestZip(mapOf("cover.pdf" to "good-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(goodZip)).setResponseCode(200))
        val first = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")
        assertTrue(first is ArchiveCacheResult.Success)

        val secondZip = buildTestZip(mapOf("cover.pdf" to "new-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(secondZip)).setResponseCode(200))

        val failingManager = ArchiveCacheManager(
            cacheRoot,
            server.url("/").toString(),
            atomicMove = { source, target ->
                if (target.name == "100 - Alpha") {
                    // Both the promotion move and the restore-from-backup move target finalDir
                    // ("100 - Alpha") -- force both to fail. The backup-creation move (finalDir
                    // -> "100 - Alpha.backup") targets a different name and is left to succeed
                    // for real, so the double-failure scenario is reached (backedUp == true).
                    throw IOException("forced failure for regression test")
                }
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            },
        )
        val second = failingManager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v2")
        assertTrue(second is ArchiveCacheResult.Failure)

        // The cache slot itself may correctly report as unavailable now (finalDir is
        // empty/missing) -- that is expected and correct for this call's Failure result.
        assertNull(manager.getCachedEntry("100 - Alpha"))

        // But the DATA must not be gone: the backup directory must still exist on disk, with the
        // original good content still recoverable from it.
        val backupDir = File(cacheRoot, "100 - Alpha.backup")
        assertTrue("expected the backup directory to survive a double promotion failure", backupDir.exists())
        assertEquals("good-bytes", backupDir.resolve("100 - Alpha").resolve("cover.pdf").readText())
    }

    @Test
    fun `a SecurityException from the promotion move is caught cleanly instead of crashing`() = runBlocking {
        // Regression test: java.nio.file.Files.move is documented to also throw SecurityException
        // and UnsupportedOperationException, neither of which is an IOException. The promotion
        // move used to be guarded only by catch(IOException), so an exception of this shape
        // propagated UNCAUGHT out of downloadAndExtract instead of collapsing to a clean Failure
        // -- breaking this class's own documented "never throws" contract, and (with the old,
        // now-fixed unconditional finally-block backup delete) losing data on top of it, since
        // backupMustBePreserved never got set when that catch branch never ran at all.
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val goodZip = buildTestZip(mapOf("cover.pdf" to "good-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(goodZip)).setResponseCode(200))
        val first = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")
        assertTrue(first is ArchiveCacheResult.Success)

        val secondZip = buildTestZip(mapOf("cover.pdf" to "new-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(secondZip)).setResponseCode(200))

        val failingManager = ArchiveCacheManager(
            cacheRoot,
            server.url("/").toString(),
            atomicMove = { source, target ->
                // Only the promotion move (source is the staging root, target is finalDir) throws
                // here -- the backup-creation move (finalDir -> "100 - Alpha.backup") and the
                // restore-from-backup move (source is the backup dir) are both left to succeed
                // for real, so this isolates the promotion move's exception handling specifically.
                if (target.name == "100 - Alpha" && source.name.endsWith(".staging")) {
                    throw SecurityException("forced SecurityException for regression test")
                }
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            },
        )
        val second = failingManager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v2")
        assertTrue("expected a clean Failure, not an uncaught exception escaping downloadAndExtract", second is ArchiveCacheResult.Failure)

        // The restore-from-backup succeeded for real (it was not intercepted), so the previous
        // good entry must be back in place, and no backup directory should linger.
        val cached = manager.getCachedEntry("100 - Alpha")
        assertTrue(cached != null)
        assertEquals("good-bytes", cached!!.jobDir.resolve("cover.pdf").readText())
        assertTrue("expected the now-redundant backup to be cleaned up", !File(cacheRoot, "100 - Alpha.backup").exists())
    }

    @Test
    fun `an unrelated later failure does not delete a backup preserved by an earlier double failure`() = runBlocking {
        // Regression test: backup-preservation used to be tracked by a flag local to a single
        // downloadAndExtract call. Call 1 succeeds (good entry cached). Call 2 double-fails (both
        // the promotion move and the restore-from-backup move fail), correctly preserving backup
        // on disk. Call 3 is an ORDINARY, UNRELATED failure -- a plain HTTP 500, no fault
        // injection at all -- for the very next tap of "Open". It must not delete the backup call
        // 2 left behind, even though call 3 never reaches the promotion step at all and so (under
        // the old per-call-flag design) would have a freshly-false "preserve" flag of its own.
        // Deletion must be decided from the ACTUAL on-disk state (is finalDir currently valid?)
        // at the moment of deletion, never from anything remembered about one call's own history.
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        // Call 1: succeeds.
        val goodZip = buildTestZip(mapOf("cover.pdf" to "good-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(goodZip)).setResponseCode(200))
        val first = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")
        assertTrue(first is ArchiveCacheResult.Success)

        // Call 2: double failure (same technique as the double-promotion-failure test above).
        val secondZip = buildTestZip(mapOf("cover.pdf" to "new-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(secondZip)).setResponseCode(200))
        val doubleFailManager = ArchiveCacheManager(
            cacheRoot,
            server.url("/").toString(),
            atomicMove = { source, target ->
                if (target.name == "100 - Alpha") {
                    throw IOException("forced double failure for regression test")
                }
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            },
        )
        val second = doubleFailManager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v2")
        assertTrue(second is ArchiveCacheResult.Failure)
        val backupDir = File(cacheRoot, "100 - Alpha.backup")
        assertTrue("expected backup to survive call 2's double failure", backupDir.exists())

        // Call 3: an ordinary, completely unrelated failure -- plain HTTP 500, using the
        // ORIGINAL manager with no fault injection at all. Must not touch call 2's backup.
        server.enqueue(MockResponse().setResponseCode(500))
        val third = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v3")
        assertTrue(third is ArchiveCacheResult.Failure)

        assertTrue("expected call 2's backup to still survive an unrelated call 3 failure", backupDir.exists())
        assertEquals("good-bytes", backupDir.resolve("100 - Alpha").resolve("cover.pdf").readText())
    }

    @Test
    fun `a concurrent prune never corrupts or loses an entry actively being promoted by a download`() = runBlocking {
        // Regression test: pruneExpiredEntries() used to walk and delete cache directories with
        // no locking at all, completely uncoordinated with downloadAndExtract's per-archiveJobId
        // promotionLock. A tablet left open across shifts is exactly the situation that makes an
        // entry prune-eligible (last access >24h ago) at the same moment a user finally taps back
        // in and starts re-downloading it -- an unlocked, concurrent prune walk could see the old
        // entry as a normal aged-out candidate and delete it out from under an in-progress
        // promotion. Forces genuine overlap: the download's atomicMove is made to block, mid-lock,
        // right after finalDir's old content is confirmed still fully present on disk and right
        // before it gets moved aside -- exactly the window an unlocked prune could have raced into.
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        // Seed a cached entry, then backdate its last-access far past the 24h expiry window so it
        // is genuinely prune-eligible -- directly rewriting the manifest this class itself writes
        // (there's no public API to backdate it).
        val goodZip = buildTestZip(mapOf("cover.pdf" to "good-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(goodZip)).setResponseCode(200))
        val first = manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v1")
        assertTrue(first is ArchiveCacheResult.Success)
        val manifestFile = File(File(cacheRoot, "100 - Alpha"), ".archive_cache_manifest.json")
        val staleManifest = JSONObject(manifestFile.readText())
        staleManifest.put("lastAccessMs", System.currentTimeMillis() - 48L * 60 * 60 * 1000)
        manifestFile.writeText(staleManifest.toString())

        val promotionHoldingLock = CountDownLatch(1)
        val releasePromotion = CountDownLatch(1)
        val secondZip = buildTestZip(mapOf("cover.pdf" to "new-bytes".toByteArray()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(secondZip)).setResponseCode(200))
        val delayedManager = ArchiveCacheManager(
            cacheRoot,
            server.url("/").toString(),
            atomicMove = { source, target ->
                if (source.name == "100 - Alpha" && target.name == "100 - Alpha.backup") {
                    // The download now holds the per-archiveJobId lock and is about to move
                    // finalDir's old (stale) content aside -- finalDir is still fully present on
                    // disk right now, which is exactly what an unlocked prune walk would see as a
                    // normal aged-out candidate.
                    promotionHoldingLock.countDown()
                    releasePromotion.await(5, TimeUnit.SECONDS)
                }
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            },
        )

        val download = async(Dispatchers.IO) {
            delayedManager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v2")
        }
        assertTrue(promotionHoldingLock.await(5, TimeUnit.SECONDS))
        val prune = async(Dispatchers.IO) {
            // Default nowMs (real "now") -- the entry backdated above is genuinely >24h old
            // against real current time, so this is a realistic, not artificially-triggered, prune.
            manager.pruneExpiredEntries()
        }
        // Give the prune coroutine a chance to actually reach and block on the same per-
        // archiveJobId lock the download is currently holding, before releasing the download to
        // finish its promotion.
        Thread.sleep(100)
        releasePromotion.countDown()

        val result = download.await()
        prune.await()

        assertTrue("expected the download to succeed despite a concurrent prune", result is ArchiveCacheResult.Success)
        val cached = manager.getCachedEntry("100 - Alpha")
        assertTrue("expected the freshly-promoted entry to survive the concurrent prune", cached != null)
        assertEquals("v2", cached!!.contentVersion)
        assertEquals("new-bytes", cached.jobDir.resolve("cover.pdf").readText())

        // No scratch/backup directories should linger once both calls have finished.
        val leftoverScratch = cacheRoot.listFiles()?.filter { it.name != "100 - Alpha" }.orEmpty()
        assertTrue("expected no leftover scratch/backup dirs, found: ${leftoverScratch.map { it.name }}", leftoverScratch.isEmpty())
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

    @Test
    fun `two concurrent downloadAndExtract calls for the same archiveJobId never leave the cache slot empty or broken`() = runBlocking {
        // Regression test for the promotion race the double-tap scenario could hit: without the
        // per-archiveJobId promotionLock (and the backup-swap-restore sequence replacing the old
        // delete-then-move), two concurrent promotions for the same archiveJobId could interleave
        // their steps and, on an unlucky failure partway through, leave cacheRoot/archiveJobId
        // deleted with nothing to replace it. Both calls here serve genuinely valid content (via
        // SynchronizingDispatcher, which holds each request open until both have arrived, so
        // neither call's HTTP round trip -- and therefore neither promotion attempt -- completes
        // before the other has started) to isolate the promotion-step race itself: whichever call
        // wins, the cache slot afterward must always be a single complete, valid entry, never
        // empty or partially overwritten.
        val zipBytes = buildTestZip(mapOf("cover.pdf" to "good-bytes".toByteArray()))
        server.dispatcher = SynchronizingDispatcher(zipBytes, expectedRequests = 2)
        val cacheRoot = Files.createTempDirectory("archive-cache-test").toFile()
        val manager = ArchiveCacheManager(cacheRoot, server.url("/").toString())

        val resultA = async(Dispatchers.IO) {
            manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v-a")
        }
        val resultB = async(Dispatchers.IO) {
            manager.downloadAndExtract(archiveJobId = "100 - Alpha", folderName = "100 - Alpha", contentVersion = "v-b")
        }
        val a = resultA.await()
        val b = resultB.await()

        // Both downloads were independently valid, so both must have succeeded -- the lock
        // serializes the promotion step, it does not fail either caller.
        assertTrue("expected first concurrent download to succeed, was: $a", a is ArchiveCacheResult.Success)
        assertTrue("expected second concurrent download to succeed, was: $b", b is ArchiveCacheResult.Success)

        // The cache slot must hold exactly one complete, valid, readable entry afterward --
        // whichever of v-a/v-b won the race -- never empty and never a mix of both.
        val cached = manager.getCachedEntry("100 - Alpha")
        assertTrue("cache slot must not be empty/broken after a concurrent promotion race", cached != null)
        assertTrue(cached!!.contentVersion == "v-a" || cached.contentVersion == "v-b")
        assertEquals("good-bytes", cached.jobDir.resolve("cover.pdf").readText())

        // No scratch/backup directories should linger once both calls have finished.
        val leftoverScratch = cacheRoot.listFiles()?.filter { it.name != "100 - Alpha" }.orEmpty()
        assertTrue("expected no leftover scratch/backup dirs, found: ${leftoverScratch.map { it.name }}", leftoverScratch.isEmpty())
    }
}
