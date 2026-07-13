package com.kkc.updateragent.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-02: exercises the pure signer-policy decision without a PackageManager.
 */
class IntegrityVerifierSignerPolicyTest {

    @Test
    fun rejectsWhenApkSignerUnreadable() {
        val (ok, _) = IntegrityVerifier.evaluateSignerPolicy(
            apkSigners = emptySet(),
            expectedSignerHashes = listOf("aa"),
            installedSigners = emptySet()
        )
        assertFalse(ok)
    }

    @Test
    fun rejectsEmptyExpectedSignerPolicy() {
        val (ok, reason) = IntegrityVerifier.evaluateSignerPolicy(
            apkSigners = setOf("aa"),
            expectedSignerHashes = emptyList(),
            installedSigners = emptySet()
        )
        assertFalse(ok)
        assertEquals("No expected signer policy configured for package", reason)
    }

    @Test
    fun rejectsBlankOnlyExpectedSignerPolicy() {
        val (ok, _) = IntegrityVerifier.evaluateSignerPolicy(
            apkSigners = setOf("aa"),
            expectedSignerHashes = listOf("   ", ""),
            installedSigners = emptySet()
        )
        assertFalse(ok)
    }

    @Test
    fun rejectsSignerNotInAllowlist() {
        val (ok, _) = IntegrityVerifier.evaluateSignerPolicy(
            apkSigners = setOf("cc"),
            expectedSignerHashes = listOf("AA", "BB"),
            installedSigners = emptySet()
        )
        assertFalse(ok)
    }

    @Test
    fun acceptsSignerInAllowlistWithColonAndCaseNormalization() {
        val (ok, reason) = IntegrityVerifier.evaluateSignerPolicy(
            apkSigners = setOf("aabbcc"),
            expectedSignerHashes = listOf("AA:BB:CC"),
            installedSigners = emptySet()
        )
        assertTrue(ok)
        assertEquals(null, reason)
    }

    @Test
    fun existingPackageSignerContinuityStillEnforced() {
        // Signer is in the allowlist but does not match the already-installed signer.
        val (ok, reason) = IntegrityVerifier.evaluateSignerPolicy(
            apkSigners = setOf("aa"),
            expectedSignerHashes = listOf("aa"),
            installedSigners = setOf("bb")
        )
        assertFalse(ok)
        assertEquals("APK signer does not match currently installed app", reason)
    }

    @Test
    fun acceptsWhenSignerMatchesBothAllowlistAndInstalled() {
        val (ok, _) = IntegrityVerifier.evaluateSignerPolicy(
            apkSigners = setOf("aa"),
            expectedSignerHashes = listOf("aa"),
            installedSigners = setOf("aa")
        )
        assertTrue(ok)
    }
}
