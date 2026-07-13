package com.kkc.updateragent.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateFeedRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val repository = UpdateFeedRepository()

    @Test
    fun readPolicySanitizesMissingAndNullValuesForValidPackage() {
        val paths = updatePaths()
        paths.policyFile.writeText(
            """
            {
              "schemaVersion": null,
              "updaterAgentPackage": null,
              "retryPolicy": null,
              "managedPackages": [
                {
                  "packageName": "com.kkc.sheettracker",
                  "rolloutChannel": null,
                  "installMode": null,
                  "expectedSignerSha256": ["AA:BB:CC"]
                }
              ]
            }
            """.trimIndent()
        )

        val policy = repository.readPolicy(paths)!!

        assertEquals("v1", policy.schemaVersion)
        assertEquals("com.kkc.updateragent", policy.updaterAgentPackage)
        assertEquals(RetryPolicy(), policy.retryPolicy)
        assertEquals(1, policy.managedPackages.size)
        assertEquals("com.kkc.sheettracker", policy.managedPackages.single().packageName)
        assertEquals("stable", policy.managedPackages.single().rolloutChannel)
        assertEquals("normal", policy.managedPackages.single().installMode)
        assertEquals(listOf("AA:BB:CC"), policy.managedPackages.single().expectedSignerSha256)
    }

    @Test
    fun readPolicyRejectsEmptySignerAllowlist() {
        val paths = updatePaths()
        paths.policyFile.writeText(
            """
            {
              "managedPackages": [
                { "packageName": "com.kkc.sheettracker", "expectedSignerSha256": [] }
              ]
            }
            """.trimIndent()
        )

        assertNull(repository.readPolicy(paths))
    }

    @Test
    fun readPolicyRejectsBlankSignerEntries() {
        val paths = updatePaths()
        paths.policyFile.writeText(
            """
            {
              "managedPackages": [
                { "packageName": "com.kkc.sheettracker", "expectedSignerSha256": ["  "] }
              ]
            }
            """.trimIndent()
        )

        assertNull(repository.readPolicy(paths))
    }

    @Test
    fun readPolicyRejectsBlankPackageName() {
        val paths = updatePaths()
        paths.policyFile.writeText(
            """
            {
              "managedPackages": [
                { "packageName": "  ", "expectedSignerSha256": ["AA"] }
              ]
            }
            """.trimIndent()
        )

        assertNull(repository.readPolicy(paths))
    }

    @Test
    fun readPolicyRejectsDuplicatePackages() {
        val paths = updatePaths()
        paths.policyFile.writeText(
            """
            {
              "managedPackages": [
                { "packageName": "com.kkc.sheettracker", "expectedSignerSha256": ["AA"] },
                { "packageName": "com.kkc.sheettracker", "expectedSignerSha256": ["BB"] }
              ]
            }
            """.trimIndent()
        )

        assertNull(repository.readPolicy(paths))
    }

    @Test
    fun readManifestSanitizesMissingAndNullValues() {
        val paths = updatePaths()
        paths.manifestFile.writeText(
            """
            {
              "schemaVersion": null,
              "apps": [
                {
                  "packageName": "com.kkc.sheettracker",
                  "versionCode": 42,
                  "versionName": null,
                  "apkFile": null,
                  "sha256": null,
                  "rolloutChannel": null,
                  "publishedAt": null
                }
              ]
            }
            """.trimIndent()
        )

        val manifest = repository.readManifest(paths)!!

        assertEquals("v1", manifest.schemaVersion)
        assertEquals(1, manifest.apps.size)
        assertEquals("com.kkc.sheettracker", manifest.apps.single().packageName)
        assertEquals(42, manifest.apps.single().versionCode)
        assertEquals("", manifest.apps.single().versionName)
        assertEquals("", manifest.apps.single().apkFile)
        assertEquals("", manifest.apps.single().sha256)
        assertEquals("stable", manifest.apps.single().rolloutChannel)
        assertEquals("", manifest.apps.single().publishedAt)
    }

    @Test
    fun readManifestRejectsDuplicatePackages() {
        val paths = updatePaths()
        paths.manifestFile.writeText(
            """
            {
              "apps": [
                { "packageName": "com.kkc.sheettracker", "versionCode": 1, "apkFile": "a.apk", "sha256": "x", "publishedAt": "" },
                { "packageName": "com.kkc.sheettracker", "versionCode": 2, "apkFile": "b.apk", "sha256": "y", "publishedAt": "" }
              ]
            }
            """.trimIndent()
        )

        assertNull(repository.readManifest(paths))
    }

    @Test
    fun readManifestRejectsBlankPackageName() {
        val paths = updatePaths()
        paths.manifestFile.writeText(
            """
            {
              "apps": [
                { "packageName": "  ", "versionCode": 1, "apkFile": "a.apk", "sha256": "x", "publishedAt": "" }
              ]
            }
            """.trimIndent()
        )

        assertNull(repository.readManifest(paths))
    }

    @Test
    fun resolveApkFileAllowsFileInsideArtifactDir() {
        val paths = updatePaths()
        val pkg = "com.kkc.sheettracker"
        paths.appArtifactsDir(pkg).mkdirs()
        val entry = entry(pkg, apkFile = "app-release.apk")

        val resolved = repository.resolveApkFile(paths, entry)

        assertNotNull(resolved)
        assertEquals(
            File(paths.appArtifactsDir(pkg), "app-release.apk").canonicalPath,
            resolved!!.canonicalPath
        )
    }

    @Test
    fun resolveApkFileRejectsParentTraversal() {
        val paths = updatePaths()
        val pkg = "com.kkc.sheettracker"
        paths.appArtifactsDir(pkg).mkdirs()
        val entry = entry(pkg, apkFile = "../../evil.apk")

        assertNull(repository.resolveApkFile(paths, entry))
    }

    @Test
    fun resolveApkFileRejectsBlankApkFile() {
        val paths = updatePaths()
        val pkg = "com.kkc.sheettracker"
        paths.appArtifactsDir(pkg).mkdirs()
        val entry = entry(pkg, apkFile = "")

        assertNull(repository.resolveApkFile(paths, entry))
    }

    private fun entry(packageName: String, apkFile: String): AppUpdateEntry =
        AppUpdateEntry(
            packageName = packageName,
            versionCode = 1,
            versionName = "1.0",
            apkFile = apkFile,
            sha256 = "abc",
            publishedAt = ""
        )

    private fun updatePaths(): UpdatePaths {
        val root = tempFolder.newFolder("Ready Jobs")
        val paths = UpdatePaths(root.absolutePath)
        paths.appsRoot.mkdirs()
        return paths
    }
}
