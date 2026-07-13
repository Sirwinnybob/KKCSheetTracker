package com.kkc.updateragent.update

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * AUD-12: feed classification distinguishes missing, corrupt, offline, and valid feeds.
 */
class UpdateFeedClassifyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val repository = UpdateFeedRepository()

    private val validPolicy = """
        { "managedPackages": [
            { "packageName": "com.kkc.sheettracker", "rolloutChannel": "stable", "expectedSignerSha256": ["AA"] }
        ] }
    """.trimIndent()

    private val validManifest = """
        { "apps": [
            { "packageName": "com.kkc.sheettracker", "versionCode": 5, "apkFile": "app.apk", "sha256": "x", "rolloutChannel": "stable", "publishedAt": "" }
        ] }
    """.trimIndent()

    @Test
    fun shareUnavailableWhenBaseDirMissing() {
        val paths = UpdatePaths(File(tempFolder.root, "does-not-exist").absolutePath)
        assertTrue(repository.classifyFeed(paths) is FeedState.ShareUnavailable)
    }

    @Test
    fun noPolicyWhenShareMountedButPolicyAbsent() {
        val paths = mountedPaths()
        assertTrue(repository.classifyFeed(paths) is FeedState.NoPolicy)
    }

    @Test
    fun invalidPolicyWhenPolicyCorrupt() {
        val paths = mountedPaths()
        paths.policyFile.writeText("{ not valid json ")
        assertTrue(repository.classifyFeed(paths) is FeedState.InvalidPolicy)
    }

    @Test
    fun invalidPolicyWhenSignerAllowlistEmpty() {
        val paths = mountedPaths()
        paths.policyFile.writeText(
            """{ "managedPackages": [ { "packageName": "com.kkc.sheettracker", "expectedSignerSha256": [] } ] }"""
        )
        assertTrue(repository.classifyFeed(paths) is FeedState.InvalidPolicy)
    }

    @Test
    fun noManifestWhenPolicyValidButManifestAbsent() {
        val paths = mountedPaths()
        paths.policyFile.writeText(validPolicy)
        assertTrue(repository.classifyFeed(paths) is FeedState.NoManifest)
    }

    @Test
    fun invalidManifestWhenManifestCorrupt() {
        val paths = mountedPaths()
        paths.policyFile.writeText(validPolicy)
        paths.manifestFile.writeText("{ broken ")
        assertTrue(repository.classifyFeed(paths) is FeedState.InvalidManifest)
    }

    @Test
    fun readyWhenPolicyAndManifestValid() {
        val paths = mountedPaths()
        paths.policyFile.writeText(validPolicy)
        paths.manifestFile.writeText(validManifest)
        val state = repository.classifyFeed(paths)
        assertTrue(state is FeedState.Ready)
        state as FeedState.Ready
        assertTrue(state.policy.managedPackages.isNotEmpty())
        assertTrue(state.manifest.apps.isNotEmpty())
    }

    private fun mountedPaths(): UpdatePaths {
        val root = tempFolder.newFolder("Ready Jobs")
        val paths = UpdatePaths(root.absolutePath)
        paths.appsRoot.mkdirs()
        return paths
    }
}
