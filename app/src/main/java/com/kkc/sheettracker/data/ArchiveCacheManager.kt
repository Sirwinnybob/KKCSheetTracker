package com.kkc.sheettracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

sealed class ArchiveCacheResult {
    data class Success(val jobDir: File) : ArchiveCacheResult()
    data class Failure(val reason: String) : ArchiveCacheResult()
}

data class ArchiveCacheEntry(
    val archiveJobId: String,
    val folderName: String,
    val contentVersion: String,
    val jobDir: File,
    val lastAccessMs: Long,
)

private const val CACHE_EXPIRY_MS = 24L * 60 * 60 * 1000
private const val LOCAL_CACHE_MANIFEST_NAME = ".archive_cache_manifest.json"
private const val PACKAGE_MANIFEST_ARCNAME = "manifest.json"
private const val SCRATCH_SUFFIX_INCOMPLETE = ".incomplete"
private const val SCRATCH_SUFFIX_STAGING = ".staging"
private const val SCRATCH_SUFFIX_BACKUP = ".backup"

// A single leading Windows drive letter, e.g. "C:" in "C:\evil.txt" or "C:evil.txt".
private val DRIVE_LETTER_PREFIX = Regex("^[A-Za-z]:")

/**
 * Downloads the ZIP package for one archived job from
 * `GET /api/ready-jobs-archive/library/{archiveJobId}/package` (see the backend's
 * `routes/ready_jobs_archive_lifecycle.py::download_package`, which builds the ZIP via
 * `ready_jobs_worker_core/adapters/archive_package.py::build_archive_package`), validates
 * it against its `manifest.json` (exact SHA-256 match for every declared file, no missing
 * and no unexpected extra files), extracts to a private per-download scratch directory, and
 * only after full validation atomically promotes that directory into the cache.
 *
 * manifest.json shape, confirmed against the real backend module:
 * `{"files": [{"path": "...", "size": N, "sha256": "..."}, ...]}`. The generated
 * manifest.json entry is written to the ZIP *after* the file loop and is never one of its
 * own `files` entries (the backend fixed a self-reference collision bug for this), so a
 * literal top-level `manifest.json` in the extracted output is always this package's own
 * manifest, never job content -- matched here by comparing the raw entry name against
 * "manifest.json" exactly (a nested "sub/manifest.json" is ordinary content and is hashed
 * and verified like any other file, per the backend's own carve-out).
 *
 * Every step of this class treats `manifest.json` and the ZIP's file content as fully
 * untrusted, attacker-controllable input (a corrupted download, a compromised/misbehaving
 * server, or a truncated transfer are all in scope) and is expected to yield a clean
 * [ArchiveCacheResult.Failure] rather than an uncaught exception for any of it.
 */
class ArchiveCacheManager(
    private val cacheRoot: File,
    private val serverUrl: String,
    // Test-only fault-injection seam for the promotion step's three atomic renames (previous
    // entry -> backup, staging -> finalDir, backup -> finalDir restore). Production callers never
    // pass this -- the default performs exactly the same java.nio Files.move(..., ATOMIC_MOVE) as
    // before this parameter existed. It exists only so a unit test can deterministically force
    // the promotion move and the restore-from-backup move to both fail (the double-I/O-failure
    // scenario that must leave the backup directory on disk, see the regression test in
    // ArchiveCacheManagerTest) -- that failure combination cannot be produced portably by
    // manipulating real filesystem permissions/locks from a JVM unit test.
    private val atomicMove: (source: File, target: File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    },
) {
    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        // Keyed by the promoted cache slot's absolute path (cacheRoot/archiveJobId), so two
        // concurrent downloadAndExtract calls for the same archiveJobId -- even across separate
        // ArchiveCacheManager instances pointed at the same cacheRoot, e.g. a UI double-tap that
        // races two coroutines each constructing their own manager -- serialize on the same
        // Mutex and can never interleave their promotion steps. Left unbounded: one Mutex per
        // distinct archiveJobId ever downloaded on this device is a trivial memory cost.
        private val promotionLocks = ConcurrentHashMap<String, Mutex>()

        private fun promotionLock(key: String): Mutex = promotionLocks.getOrPut(key) { Mutex() }
    }

    init {
        cacheRoot.mkdirs()
    }

    suspend fun downloadAndExtract(
        archiveJobId: String, folderName: String, contentVersion: String,
    ): ArchiveCacheResult = withContext(Dispatchers.IO) {
        val baseUrl = serverUrl.trimEnd('/')
        val url = "$baseUrl/api/ready-jobs-archive/library/".toHttpUrl().newBuilder()
            .addPathSegment(archiveJobId)
            .addPathSegment("package")
            .build()
        val request = Request.Builder().url(url).get().build()

        val incomplete = File(cacheRoot, "${UUID.randomUUID()}$SCRATCH_SUFFIX_INCOMPLETE")
        // Everything built while promoting a successful download lives under `staging` until
        // the very last step -- the real cache slot at cacheRoot/archiveJobId (`finalDir`
        // below) is never touched until staging holds a fully-extracted, fully-hash-verified,
        // manifest-written entry ready to become the new content. That ordering is what keeps
        // a failed re-download from destroying a previously-good cached entry: every failure
        // path below returns before finalDir is deleted or modified.
        val staging = File(cacheRoot, "${UUID.randomUUID()}$SCRATCH_SUFFIX_STAGING")
        val finalDir = File(cacheRoot, archiveJobId)
        // Scratch slot for the promotion step's backup-swap-restore sequence below. Keyed by
        // archiveJobId (not a UUID) because it must be re-findable to restore if the final move
        // fails -- safe to reuse across calls because it only ever exists while promotionLock
        // for this archiveJobId is held, and is always cleaned up (deleted or restored) before
        // that lock is released.
        val backup = File(cacheRoot, "$archiveJobId$SCRATCH_SUFFIX_BACKUP")
        // Set true only in the double-I/O-failure exit path below: the promotion move
        // (staging -> finalDir) failed AND the subsequent restore-from-backup move
        // (backup -> finalDir) also failed. In that case backup is the only remaining copy of
        // the previously-good entry, so the outer `finally` block below must not delete it even
        // though this call correctly returns Failure. Every other exit path leaves this false,
        // and the `finally` block's cleanup is unconditional in those cases -- which is correct
        // because backup either never existed, was already explicitly deleted after a successful
        // promotion, or was consumed (moved away) by a successful restore.
        var backupMustBePreserved = false
        incomplete.mkdirs()
        try {
            val response = runCatching { client.newCall(request).execute() }.getOrElse {
                return@withContext ArchiveCacheResult.Failure("network error: ${it.message}")
            }
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext ArchiveCacheResult.Failure("http ${resp.code}")
                val body = resp.body ?: return@withContext ArchiveCacheResult.Failure("empty response body")

                // path -> (declared size, declared sha256), parsed from manifest.json.
                val declaredHashes = mutableMapOf<String, Pair<Long, String>>()
                // path -> actual sha256 computed while streaming each entry to disk. Hashing
                // happens exactly once per file, during extraction; every check below compares
                // against these already-computed strings rather than re-reading any file from
                // disk, so there is no second read/hash pass over extracted content.
                val extractedHashes = mutableMapOf<String, String>()
                var sawManifest = false

                ZipInputStream(body.byteStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val destination = resolveSafeEntryPath(incomplete, name)
                            ?: return@withContext ArchiveCacheResult.Failure("unsafe zip entry: $name")

                        if (entry.isDirectory) {
                            entry = zip.nextEntry
                            continue
                        }

                        if (name == PACKAGE_MANIFEST_ARCNAME) {
                            sawManifest = true
                            val manifestJson = zip.readBytes().toString(Charsets.UTF_8)
                            val parsed = runCatching { JSONObject(manifestJson) }.getOrNull()
                                ?: return@withContext ArchiveCacheResult.Failure("manifest.json is not valid JSON")
                            val filesArray = parsed.optJSONArray("files")
                                ?: return@withContext ArchiveCacheResult.Failure("manifest.json missing \"files\" array")
                            // manifest.json comes from inside the untrusted ZIP, so a truncated
                            // or malicious files[] entry (missing "sha256", "size" as a string
                            // instead of a number, an array element that isn't even an object,
                            // etc.) must not crash this coroutine via org.json's throwing
                            // accessors -- it must collapse to a clean Failure like every other
                            // malformed-input case here.
                            val filesParsed = runCatching {
                                for (i in 0 until filesArray.length()) {
                                    val fileEntry = filesArray.getJSONObject(i)
                                    declaredHashes[fileEntry.getString("path")] =
                                        fileEntry.getLong("size") to fileEntry.getString("sha256")
                                }
                            }
                            if (filesParsed.isFailure) {
                                return@withContext ArchiveCacheResult.Failure(
                                    "manifest.json \"files\" entry malformed: ${filesParsed.exceptionOrNull()?.message}"
                                )
                            }
                        } else {
                            destination.parentFile?.mkdirs()
                            val digest = MessageDigest.getInstance("SHA-256")
                            destination.outputStream().use { out ->
                                val buffer = ByteArray(8192)
                                var read = zip.read(buffer)
                                while (read >= 0) {
                                    out.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    read = zip.read(buffer)
                                }
                            }
                            extractedHashes[name] = digest.digest().joinToString("") { "%02x".format(it) }
                        }
                        entry = zip.nextEntry
                    }
                }

                if (!sawManifest) {
                    return@withContext ArchiveCacheResult.Failure("package is missing manifest.json")
                }
                val missing = declaredHashes.keys - extractedHashes.keys
                if (missing.isNotEmpty()) {
                    return@withContext ArchiveCacheResult.Failure("manifest declared files missing from archive: $missing")
                }
                val extra = extractedHashes.keys - declaredHashes.keys
                if (extra.isNotEmpty()) {
                    return@withContext ArchiveCacheResult.Failure("archive contains files not declared in manifest: $extra")
                }
                for ((path, expected) in declaredHashes) {
                    if (extractedHashes[path] != expected.second) {
                        return@withContext ArchiveCacheResult.Failure("hash mismatch for $path")
                    }
                }
            }

            // Build the new entry entirely under `staging` -- cacheRoot/archiveJobId (the real
            // cache slot) is not touched by anything above or below this point until the final
            // swap, so a failure anywhere in this block leaves any previously-cached entry for
            // archiveJobId exactly as it was before this call.
            staging.mkdirs()
            val stagedJobDir = File(staging, folderName)
            try {
                Files.move(incomplete.toPath(), stagedJobDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                return@withContext ArchiveCacheResult.Failure("could not stage extracted download: ${e.message}")
            }
            try {
                writeManifest(staging, ArchiveCacheEntry(archiveJobId, folderName, contentVersion, stagedJobDir, System.currentTimeMillis()))
            } catch (e: IOException) {
                return@withContext ArchiveCacheResult.Failure("could not write cache manifest: ${e.message}")
            }

            // Staging now holds a fully-extracted, fully-verified, manifest-written entry. Only
            // now is it safe to touch the real cache slot. This promotion runs under a
            // per-archiveJobId lock (see promotionLock) so two concurrent downloadAndExtract
            // calls for the same archiveJobId -- e.g. a UI double-tap -- can never interleave
            // their steps here, and uses a backup-swap-restore sequence rather than
            // delete-then-move: the previous entry (if any) is moved aside first and only
            // deleted once the new entry has successfully landed, so that even an unrelated IO
            // failure partway through promotion can never leave finalDir empty -- the previous
            // good entry (if there was one) is always either still in place or restorable.
            // incomplete/staging/backup/finalDir are all directly under cacheRoot (private app
            // storage, guaranteed to be a single filesystem/volume), so each NIO move is a
            // single filesystem metadata operation -- atomic, not a copy -- and ATOMIC_MOVE will
            // not throw AtomicMoveNotSupportedException here.
            promotionLock(finalDir.absolutePath).withLock {
                var backedUp = false
                if (finalDir.exists()) {
                    if (backup.exists()) backup.deleteRecursively()
                    try {
                        atomicMove(finalDir, backup)
                        backedUp = true
                    } catch (e: IOException) {
                        return@withContext ArchiveCacheResult.Failure(
                            "could not back up previous cache entry for $archiveJobId: ${e.message}"
                        )
                    }
                }
                try {
                    atomicMove(staging, finalDir)
                } catch (e: IOException) {
                    // Put the previous good entry back rather than leaving the slot empty.
                    if (backedUp) {
                        val restored = runCatching { atomicMove(backup, finalDir) }.isSuccess
                        if (!restored) {
                            // Both the promotion move and the restore-from-backup move failed
                            // (e.g. two consecutive transient I/O errors, low disk space). backup
                            // is now the only remaining copy of the previously-good entry -- do
                            // not let the outer `finally` block delete it.
                            backupMustBePreserved = true
                        }
                    }
                    return@withContext ArchiveCacheResult.Failure("could not promote staged download: ${e.message}")
                }
                if (backedUp) backup.deleteRecursively()
            }
            ArchiveCacheResult.Success(File(finalDir, folderName))
        } finally {
            if (incomplete.exists()) incomplete.deleteRecursively()
            if (staging.exists()) staging.deleteRecursively()
            if (backup.exists() && !backupMustBePreserved) backup.deleteRecursively()
        }
    }

    /**
     * Resolves a ZIP entry name against [root], rejecting anything that would land outside
     * it or that even looks like it is trying to.
     *
     * Entry names are attacker-controlled strings with no cross-platform notion of "valid
     * path", so this rejects the dangerous absolute-path shapes *explicitly*, by pattern,
     * rather than relying only on `File`/`canonicalFile()`'s OS-specific resolution
     * behavior -- that behavior genuinely differs by platform and this app's build/dev
     * environment (Windows) is not its runtime deployment target (Android/Linux). Concretely,
     * on a real JVM on each platform (a portable Linux JDK run under WSL for the Linux case):
     *
     * - A relative traversal like "../../evil.txt" (or "sub/../../evil.txt") canonicalizes to
     *   a path outside root and is caught by the startsWith containment check below, the same
     *   way on both Windows and Linux.
     * - A Windows drive-letter-absolute entry such as "C:\evil.txt" or "C:evil.txt" throws
     *   `IOException: The filename, directory name, or volume label syntax is incorrect` from
     *   `canonicalFile()` on Windows (appending a second drive onto root is not a well-formed
     *   Windows path) -- but on Linux the exact same entry name is just a legal, if odd,
     *   relative filename (colons and backslashes are ordinary bytes there): it resolves
     *   *inside* root without any exception. The explicit checks below (leading "/" or "\",
     *   an embedded "\", or a leading drive-letter pattern) are what actually stop this case
     *   on the real deployment target -- canonicalFile()'s exception on Windows was never a
     *   cross-platform guarantee, only a Windows-dev-machine coincidence.
     * - A leading-slash entry such as "/etc/evil.txt" is genuinely absolute on Linux but is
     *   only *drive-relative to root* on Windows (no drive letter to be absolute against), so
     *   it resolves harmlessly inside root there. The explicit leading-"/" check below rejects
     *   it uniformly on both platforms instead of depending on which OS is running.
     *
     * Zip itself has no concept of an "invalid" entry name -- a hostile ZIP can carry any of
     * the above literally (confirmed by round-tripping such names through
     * ZipOutputStream/ZipInputStream) -- and even without the explicit checks, the
     * declared-vs-extracted "unexpected file not in manifest" check in [downloadAndExtract]
     * would independently reject an entry resolved to a bogus path like this (it would never
     * match a real manifest-declared path). The explicit checks here exist so that backstop
     * isn't the *only* thing standing between an odd entry name and disk, and so a caller
     * reading this function doesn't have to trace that connection to trust it.
     */
    private fun resolveSafeEntryPath(root: File, entryName: String): File? {
        if (entryName.isBlank()) return null
        if (entryName.startsWith('/') || entryName.startsWith('\\')) return null
        if (entryName.contains('\\')) return null
        if (DRIVE_LETTER_PREFIX.containsMatchIn(entryName)) return null
        return runCatching {
            val rootCanonical = root.canonicalFile
            val candidate = File(root, entryName).canonicalFile
            if (candidate == rootCanonical || !candidate.path.startsWith(rootCanonical.path + File.separator)) {
                null
            } else {
                candidate
            }
        }.getOrNull()
    }

    /** Throws [IOException] on write failure; callers decide whether that's fatal for them. */
    private fun writeManifest(finalDir: File, entry: ArchiveCacheEntry) {
        val manifest = JSONObject().apply {
            put("archiveJobId", entry.archiveJobId)
            put("folderName", entry.folderName)
            put("contentVersion", entry.contentVersion)
            put("completedAtMs", entry.lastAccessMs)
            put("lastAccessMs", entry.lastAccessMs)
        }
        File(finalDir, LOCAL_CACHE_MANIFEST_NAME).writeText(manifest.toString())
    }

    fun getCachedEntry(archiveJobId: String): ArchiveCacheEntry? {
        val finalDir = File(cacheRoot, archiveJobId)
        val manifestFile = File(finalDir, LOCAL_CACHE_MANIFEST_NAME)
        if (!manifestFile.exists()) return null
        val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrNull() ?: return null
        val folderName = manifest.optString("folderName").takeIf { it.isNotBlank() } ?: return null
        val jobDir = File(finalDir, folderName)
        if (!jobDir.isDirectory) return null
        return ArchiveCacheEntry(
            archiveJobId = archiveJobId,
            folderName = folderName,
            contentVersion = manifest.optString("contentVersion"),
            jobDir = jobDir,
            lastAccessMs = manifest.optLong("lastAccessMs"),
        )
    }

    fun touchLastAccess(archiveJobId: String) {
        val entry = getCachedEntry(archiveJobId) ?: return
        // Best-effort bookkeeping only -- a failed write here must not surface as an error to
        // whatever caller merely wanted to note that a cache entry was accessed.
        runCatching { writeManifest(File(cacheRoot, archiveJobId), entry.copy(lastAccessMs = System.currentTimeMillis())) }
    }

    fun pruneExpiredEntries(nowMs: Long = System.currentTimeMillis()) {
        val entries = cacheRoot.listFiles { file ->
            file.isDirectory &&
                !file.name.endsWith(SCRATCH_SUFFIX_INCOMPLETE) &&
                !file.name.endsWith(SCRATCH_SUFFIX_STAGING) &&
                !file.name.endsWith(SCRATCH_SUFFIX_BACKUP)
        } ?: return
        for (dir in entries) {
            val entry = getCachedEntry(dir.name) ?: continue
            if (nowMs - entry.lastAccessMs > CACHE_EXPIRY_MS) {
                dir.deleteRecursively()
            }
        }
    }

    fun removeCachedEntry(archiveJobId: String) {
        File(cacheRoot, archiveJobId).deleteRecursively()
    }
}
