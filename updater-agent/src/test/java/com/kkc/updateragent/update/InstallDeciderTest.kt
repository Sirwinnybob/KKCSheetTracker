package com.kkc.updateragent.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallDeciderTest {
    private val decider = InstallDecider()

    @Test
    fun decidesInstallWhenNewerVersionExists() {
        val update = AppUpdateEntry(
            packageName = "com.kkc.sheettracker",
            versionCode = 5,
            versionName = "5.0.0",
            apkFile = "app.apk",
            sha256 = "abc",
            minRequiredVersionCode = 0,
            rolloutChannel = "stable",
            publishedAt = "2026-05-14T10:00:00Z"
        )
        val policy = ManagedPackagePolicy(packageName = "com.kkc.sheettracker")

        val decision = decider.decide(installedVersionCode = 4, update = update, packagePolicy = policy)

        assertTrue(decision.shouldInstall)
        assertFalse(decision.forced)
    }

    @Test
    fun decidesForcedInstallWhenBelowMinimumVersion() {
        val update = AppUpdateEntry(
            packageName = "com.kkc.sheettracker",
            versionCode = 5,
            versionName = "5.0.0",
            apkFile = "app.apk",
            sha256 = "abc",
            minRequiredVersionCode = 5,
            rolloutChannel = "stable",
            publishedAt = "2026-05-14T10:00:00Z"
        )
        val policy = ManagedPackagePolicy(packageName = "com.kkc.sheettracker", minRequiredVersionCode = 5)

        val decision = decider.decide(installedVersionCode = 4, update = update, packagePolicy = policy)

        assertTrue(decision.shouldInstall)
        assertTrue(decision.forced)
    }

    @Test
    fun skipsWhenCurrentVersionAlreadyMatches() {
        val update = AppUpdateEntry(
            packageName = "com.kkc.sheettracker",
            versionCode = 5,
            versionName = "5.0.0",
            apkFile = "app.apk",
            sha256 = "abc",
            minRequiredVersionCode = 0,
            rolloutChannel = "stable",
            publishedAt = "2026-05-14T10:00:00Z"
        )
        val policy = ManagedPackagePolicy(packageName = "com.kkc.sheettracker")

        val decision = decider.decide(installedVersionCode = 5, update = update, packagePolicy = policy)

        assertFalse(decision.shouldInstall)
    }
}
