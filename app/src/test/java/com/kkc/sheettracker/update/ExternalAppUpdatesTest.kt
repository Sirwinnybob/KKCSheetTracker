package com.kkc.sheettracker.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExternalAppUpdatesTest {
    @get:Rule val temporary = TemporaryFolder()

    private val apps = listOf(
        ExternalApp("com.anandmuralidhar.assimpandroid", "Assimp"),
        ExternalApp("com.example.timecard", "Hours Tracker"),
        ExternalApp("com.kkc.vnccast", "VNC Cast")
    )

    private fun apk(packageName: String, code: Long, modified: Long = 1_000L): ApkInfo {
        val file = temporary.newFile()
        assertTrue(file.setLastModified(modified))
        return ApkInfo(file, packageName, code, "version-$code")
    }

    @Test fun allThreeMissingAppsOfferTheirLatestMatchingApk() {
        val feeds = mapOf(
            apps[0].packageName to listOf(apk(apps[0].packageName, 149), apk(apps[0].packageName, 150)),
            apps[1].packageName to listOf(apk(apps[1].packageName, 163)),
            apps[2].packageName to listOf(apk(apps[2].packageName, 5), apk("com.unrelated.app", 999))
        )

        val offers = findExternalAppUpdates(apps, { -1L }, { feeds.getValue(it) })

        assertEquals(listOf("com.anandmuralidhar.assimpandroid", "com.example.timecard", "com.kkc.vnccast"), offers.map { it.packageName })
        assertEquals(listOf(150L, 163L, 5L), offers.map { it.versionCode })
        offers.forEach { assertFalse(it.isInstalled) }
        assertEquals(feeds.getValue(apps[0].packageName)[1].file, offers[0].apkFile)
    }

    @Test fun missingAppWithoutAnApkHasNoInstallOffer() {
        assertTrue(findExternalAppUpdates(apps, { -1L }, { emptyList() }).isEmpty())
    }

    @Test fun installedAppOffersOnlyANewerVersionAsAnUpdate() {
        val candidate = apk(apps[1].packageName, 163)
        val offers = findExternalAppUpdates(listOf(apps[1]), { 162L }, { listOf(candidate) })

        assertEquals(1, offers.size)
        assertTrue(offers.single().isInstalled)
        assertEquals(163L, offers.single().versionCode)
        assertEquals(candidate.file, offers.single().apkFile)
    }

    @Test fun currentOrNewerInstalledAppHasNoOffer() {
        val candidates = listOf(apk(apps[1].packageName, 162), apk(apps[1].packageName, 163))

        assertTrue(findExternalAppUpdates(listOf(apps[1]), { 163L }, { candidates }).isEmpty())
        assertTrue(findExternalAppUpdates(listOf(apps[1]), { 164L }, { candidates }).isEmpty())
    }

    @Test fun missingAppDoesNotOfferAnotherPackagesApk() {
        val unrelated = apk(apps[1].packageName, 163)
        assertTrue(findExternalAppUpdates(listOf(apps[0]), { -1L }, { listOf(unrelated) }).isEmpty())
    }

    @Test fun failedInstalledVersionLookupDoesNotOfferAnInstallOrReadApks() {
        var feedReads = 0
        val candidate = apk(apps[0].packageName, 150)

        val offers = findExternalAppUpdates(listOf(apps[0]), { null }, {
            feedReads++
            listOf(candidate)
        })

        assertTrue(offers.isEmpty())
        assertEquals(0, feedReads)
    }

    @Test fun sameVersionCandidatesPreferTheMostRecentlyPublishedApk() {
        val older = apk(apps[2].packageName, 5, modified = 1_000L)
        val newer = apk(apps[2].packageName, 5, modified = 3_000L)

        val offer = findExternalAppUpdates(listOf(apps[2]), { -1L }, { listOf(newer, older) }).single()

        assertEquals(newer.file, offer.apkFile)
    }

    @Test fun missingAppInstallOffersDoNotShowTheNotificationDot() {
        val offers = findExternalAppUpdates(apps, { -1L }, { listOf(apk(it, 5)) })

        assertEquals(3, offers.size)
        assertFalse(hasPendingUpdateNotification(false, offers))
    }

    @Test fun anInstalledAppUpdateShowsTheNotificationDot() {
        val offers = findExternalAppUpdates(listOf(apps[1]), { 162L }, { listOf(apk(it, 163)) })

        assertTrue(hasPendingUpdateNotification(false, offers))
    }

    @Test fun aSelfUpdateShowsTheDotEvenWhenAllCompanionAppsAreMissing() {
        val offers = findExternalAppUpdates(apps, { -1L }, { listOf(apk(it, 5)) })

        assertTrue(hasPendingUpdateNotification(true, offers))
    }

    @Test fun anInstalledUpdateStillShowsTheDotAlongsideAMissingAppOffer() {
        val offers = findExternalAppUpdates(apps.take(2), {
            if (it == apps[0].packageName) -1L else 162L
        }, { listOf(apk(it, 163)) })

        assertEquals(2, offers.size)
        assertTrue(hasPendingUpdateNotification(false, offers))
    }

    @Test fun noUpdatesOrInstallOffersDoNotShowTheNotificationDot() {
        assertFalse(hasPendingUpdateNotification(false, emptyList()))
    }
}
