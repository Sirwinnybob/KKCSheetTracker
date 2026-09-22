package com.kkc.sheettracker.update

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for a race found by adversarial review: installApk() used to copy every APK
 * to the same fixed cache filename ("update.apk"), so Update All's back-to-back
 * installExternalUpdate() + installPendingUpdate() calls could have the second copy clobber the
 * first's cached file before its async install intent had read it.
 */
class UpdateManagerCacheFilenameWiringTest {

    @Test
    fun installApkUsesAPerSourceFileCacheName() {
        val source = SourceFiles.mainSource("com/kkc/sheettracker/update/UpdateManager.kt").readText()

        assertFalse("installApk must not use a single fixed cache filename", source.contains("File(cacheDir, \"update.apk\")"))
        assertTrue("installApk must derive the cache filename from the source file", source.contains("File(cacheDir, \"update_\${apkFile.name}\")"))
    }
}
