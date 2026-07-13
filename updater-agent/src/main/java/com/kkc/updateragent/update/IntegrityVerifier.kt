package com.kkc.updateragent.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class IntegrityVerifier(private val context: Context) {

    fun verifySha256(file: File, expectedHash: String): Boolean {
        if (!file.isFile) return false
        val expected = expectedHash.trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual == expected
    }

    fun verifySigner(
        packageName: String,
        apkFile: File,
        expectedSignerHashes: List<String>
    ): Pair<Boolean, String?> {
        val apkSigners = loadSignerDigestsFromApk(apkFile)
        val installed = loadInstalledSignerDigests(packageName)
        return evaluateSignerPolicy(apkSigners, expectedSignerHashes, installed)
    }

    companion object {
        /**
         * Pure signer-policy decision, extracted so it can be unit tested without a
         * PackageManager. AUD-02: an empty/blank expected-signer list is a hard failure —
         * every managed package must carry a real signer allowlist. The installed-signer
         * check preserves continuity for already-installed packages.
         */
        fun evaluateSignerPolicy(
            apkSigners: Set<String>,
            expectedSignerHashes: List<String>,
            installedSigners: Set<String>
        ): Pair<Boolean, String?> {
            if (apkSigners.isEmpty()) {
                return false to "Unable to read APK signing certificate"
            }

            val expected = expectedSignerHashes.map { normalizeHash(it) }.filter { it.isNotEmpty() }.toSet()
            if (expected.isEmpty()) {
                return false to "No expected signer policy configured for package"
            }
            if (apkSigners.intersect(expected).isEmpty()) {
                return false to "APK signer does not match expected signer policy"
            }

            if (installedSigners.isNotEmpty() && apkSigners.intersect(installedSigners).isEmpty()) {
                return false to "APK signer does not match currently installed app"
            }
            return true to null
        }

        private fun normalizeHash(value: String): String {
            return value.trim().lowercase().replace(":", "")
        }
    }

    private fun loadInstalledSignerDigests(packageName: String): Set<String> {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signers = info.signingInfo?.apkContentsSigners ?: emptyArray()
                signers.map { sha256Hex(it.toByteArray()) }.toSet()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.map { sha256Hex(it.toByteArray()) }?.toSet() ?: emptySet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun loadSignerDigestsFromApk(apkFile: File): Set<String> {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signers = info?.signingInfo?.apkContentsSigners ?: emptyArray()
                signers.map { sha256Hex(it.toByteArray()) }.toSet()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info?.signatures?.map { sha256Hex(it.toByteArray()) }?.toSet() ?: emptySet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
