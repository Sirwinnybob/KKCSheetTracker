package com.kkc.updateragent.update

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateFeedRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val repository = UpdateFeedRepository()

    @Test
    fun readPolicySanitizesMissingAndNullValues() {
        val paths = updatePaths()
        paths.policyFile.writeText(
            """
            {
              "schemaVersion": null,
              "updaterAgentPackage": null,
              "retryPolicy": null,
              "managedPackages": [
                {
                  "packageName": null,
                  "rolloutChannel": null,
                  "installMode": null,
                  "expectedSignerSha256": null
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
        assertEquals("", policy.managedPackages.single().packageName)
        assertEquals("stable", policy.managedPackages.single().rolloutChannel)
        assertEquals("normal", policy.managedPackages.single().installMode)
        assertEquals(emptyList<String>(), policy.managedPackages.single().expectedSignerSha256)
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
                  "packageName": null,
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
        assertEquals("", manifest.apps.single().packageName)
        assertEquals(42, manifest.apps.single().versionCode)
        assertEquals("", manifest.apps.single().versionName)
        assertEquals("", manifest.apps.single().apkFile)
        assertEquals("", manifest.apps.single().sha256)
        assertEquals("stable", manifest.apps.single().rolloutChannel)
        assertEquals("", manifest.apps.single().publishedAt)
    }

    private fun updatePaths(): UpdatePaths {
        val root = tempFolder.newFolder("Ready Jobs")
        val paths = UpdatePaths(root.absolutePath)
        paths.appsRoot.mkdirs()
        return paths
    }
}
