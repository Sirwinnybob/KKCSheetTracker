package com.kkc.sheettracker.update

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateAllQueueTest {

    private fun offer(pkg: String, name: String, code: Long, installed: Boolean = true) =
        ExternalAppUpdate(pkg, name, File("$pkg.apk"), code, "v$code", isInstalled = installed)

    private val self = UpdateStep("com.kkc.sheettracker", "KKC Sheet Tracker", File("kst.apk"), 80716)

    @Test
    fun companionAppsComeFirstInFeedOrderAndSheetTrackerLast() {
        val steps = updateAllSteps(
            self,
            listOf(offer("com.example.timecard", "Hours Tracker", 163), offer("com.kkc.vnccast", "VNC Cast", 14))
        )
        assertEquals(listOf("com.example.timecard", "com.kkc.vnccast", "com.kkc.sheettracker"), steps.map { it.packageName })
        assertEquals(listOf(163L, 14L, 80716L), steps.map { it.targetVersionCode })
    }

    @Test
    fun appsNotInstalledOnTheTabletAreNotPartOfUpdateAll() {
        val steps = updateAllSteps(self, listOf(offer("com.kkc.vnccast", "VNC Cast", 14, installed = false)))
        assertEquals(listOf("com.kkc.sheettracker"), steps.map { it.packageName })
    }

    @Test
    fun withoutASelfUpdateOnlyCompanionAppsAreQueued() {
        val steps = updateAllSteps(null, listOf(offer("com.example.timecard", "Hours Tracker", 163)))
        assertEquals(listOf("com.example.timecard"), steps.map { it.packageName })
    }

    @Test
    fun stepAdvancesOnlyOnceTheTargetVersionIsInstalled() {
        assertEquals(StepCheck.ADVANCE, checkStep(installedVersionCode = 163, targetVersionCode = 163, elapsedMs = 0, graceMs = 5000))
        assertEquals(StepCheck.ADVANCE, checkStep(installedVersionCode = 170, targetVersionCode = 163, elapsedMs = 0, graceMs = 5000))
    }

    @Test
    fun oldVersionWaitsThroughTheGracePeriodThenStops() {
        // Installer can close a moment before PackageManager reports the new version.
        assertEquals(StepCheck.WAIT, checkStep(installedVersionCode = 160, targetVersionCode = 163, elapsedMs = 1000, graceMs = 5000))
        assertEquals(StepCheck.ABORT, checkStep(installedVersionCode = 160, targetVersionCode = 163, elapsedMs = 5000, graceMs = 5000))
    }

    @Test
    fun unknownInstalledVersionNeverAdvances() {
        assertEquals(StepCheck.WAIT, checkStep(installedVersionCode = null, targetVersionCode = 163, elapsedMs = 0, graceMs = 5000))
        assertEquals(StepCheck.ABORT, checkStep(installedVersionCode = null, targetVersionCode = 163, elapsedMs = 6000, graceMs = 5000))
    }

    @Test
    fun queueWalksStepsOneAtATimeAndResetsTheLaunchedFlag() {
        val queue = UpdateAllQueue(updateAllSteps(self, listOf(offer("com.example.timecard", "Hours Tracker", 163))))
        assertEquals("com.example.timecard", queue.current?.packageName)
        assertEquals(1, queue.position)
        queue.launched = true

        assertEquals("com.kkc.sheettracker", queue.advance()?.packageName)
        assertEquals(2, queue.position)
        assertEquals(false, queue.launched)

        assertNull(queue.advance())
    }

    @Test
    fun stepIsConfirmedOnlyAfterTheAppWentBehindTheInstaller() {
        val queue = UpdateAllQueue(listOf(self))
        assertFalse("nothing launched yet", queue.readyToConfirm)

        // Install-permission detour: the installer opens from the settings result callback, then the
        // activity finishes resuming — that resume must not be treated as "installer closed".
        queue.launched = true
        assertFalse(queue.readyToConfirm)

        queue.onPaused() // installer now covers the app
        assertTrue(queue.readyToConfirm)
    }

    @Test
    fun pauseBeforeTheInstallerOpensDoesNotCount() {
        val queue = UpdateAllQueue(listOf(self))
        queue.onPaused() // e.g. leaving for the install-permission settings screen
        queue.launched = true
        assertFalse(queue.readyToConfirm)
    }

    @Test
    fun advancingResetsTheForegroundGate() {
        val queue = UpdateAllQueue(listOf(self, self.copy(packageName = "other")))
        queue.launched = true
        queue.onPaused()
        queue.advance()
        assertFalse(queue.readyToConfirm)
    }

    @Test
    fun updateAllIsWiredToTheSequentialQueue() {
        val activity = SourceFiles.mainSource("com/kkc/sheettracker/MainActivity.kt").readText()
        val manager = SourceFiles.mainSource("com/kkc/sheettracker/update/UpdateManager.kt").readText()
        assertTrue("Update All delegates to the queue", activity.contains("onInstallAll = { updateManager.installAll() }"))
        assertTrue("resume drives the next step", activity.contains("updateManager.onActivityResumed()"))
        assertTrue("pause marks the installer as in front", activity.contains("updateManager.onActivityPaused()"))
        assertTrue("a destroyed activity stops the run", activity.contains("updateManager.cancelUpdateAll()"))
        assertTrue("queue only advances on a confirmed install", manager.contains("checkStep("))
        assertTrue("a step counts as started only when the installer actually opened", manager.contains("onLaunched"))
    }
}
