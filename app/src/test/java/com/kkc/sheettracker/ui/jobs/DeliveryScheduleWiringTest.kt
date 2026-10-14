package com.kkc.sheettracker.ui.jobs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryScheduleWiringTest {
    @Test
    fun jobsScreenRoutesDialogEditsThroughTheEditor() {
        val src = source("ui/jobs/UnifiedJobsScreen.kt")
        assertTrue(src.contains("DeliveryScheduleEditor("))
        assertTrue(src.contains("asDeliveryRemote()"))
        assertTrue(src.contains("overlayPendingDeliveryRequest("))
        assertTrue(src.contains("onPlaceJob = "))
        assertTrue(src.contains("onRemoveJob = "))
        assertTrue(src.contains("onResetWeek = "))
        assertFalse(src.contains("onQueueSlotEdit = "))
    }

    @Test
    fun dragGesturesReadLatestCallbacksAndEditsAreSerializedFromFreshSchedule() {
        val dialog = source("ui/components/DeliveryScheduleDialog.kt")
        assertTrue(dialog.contains("rememberUpdatedState(onDragStart)"))
        assertTrue(dialog.contains("rememberUpdatedState(onDragEnd)"))
        assertTrue(dialog.contains("currentOnDragEnd()"))
        // gesture lambdas must not call the captured parameters directly
        assertFalse(dialog.contains("onDragEnd = { onDragEnd() }"))
        assertFalse(dialog.contains("onDragStart = { onDragStart(job"))
        val screen = source("ui/jobs/UnifiedJobsScreen.kt")
        assertTrue(screen.contains("private val deliveryEditMutex = Mutex()"))
        val runner = source("ui/jobs/DeliveryEditRunner.kt")
        assertTrue(runner.contains("mutex.withLock"))
        // The fresh schedule is read from the store (not composition) inside the lock.
        assertTrue(runner.contains("overlayPendingDeliveryRequest(currentSchedule(), readPending())"))
    }

    @Test
    fun deliveryEditsOutliveTheJobsRouteAndNeverCrashOnQueueFailure() {
        val screen = source("ui/jobs/UnifiedJobsScreen.kt")
        val runDeliveryEdit = screen.substringAfter("fun runDeliveryEdit(").substringBefore("val deliveryPickerJobs")
        assertFalse(runDeliveryEdit.contains("withFrameNanos"))
        assertFalse(runDeliveryEdit.contains("saveScope"))
        assertTrue(runDeliveryEdit.contains("deliveryEditRunner.run("))
        assertTrue(runDeliveryEdit.contains("currentSchedule = { deliveryScheduleForJobsScreen(latestCurrentDeliverySchedule()) }"))
        assertTrue(screen.contains("rememberUpdatedState(currentDeliverySchedule)"))
        assertTrue(screen.contains("hasPendingRequest = { deliveryScheduleRequestStore.readPending(tabletId) != null }"))
        assertFalse(screen.contains("rememberUpdatedState(deliveryScheduleBinding.dialogSchedule)"))
        // Process-lifetime scope, not the composition's rememberCoroutineScope().
        assertTrue(screen.contains("CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)"))
        assertTrue(screen.contains("DeliveryEditRunner(deliveryEditScope, deliveryEditMutex)"))
        assertTrue(screen.contains("currentDeliverySchedule: () -> DeliverySchedule = { deliverySchedule }"))
        val runner = source("ui/jobs/DeliveryEditRunner.kt")
        assertTrue(runner.contains("catch (e: CancellationException)"))
        assertTrue(runner.contains("throw e"))
        assertTrue(runner.contains("catch (e: Exception)"))
        // Every Jobs-screen call site reads the live store value, not a composed snapshot.
        val nav = source("navigation/NavGraph.kt")
        val callSites = nav.split("ui.jobs.UnifiedJobsScreen(").drop(1)
        assertTrue(callSites.isNotEmpty())
        callSites.forEach { assertTrue(it.substringBefore("onJobClick").contains("currentDeliverySchedule = currentDeliverySchedule")) }
        assertTrue(nav.contains("currentDeliverySchedule = { deliveryScheduleStore.schedule.value }"))
    }

    @Test
    fun legacyScheduleCapsEachHalfDayAtThreeInTheDialog() {
        val dialog = source("ui/components/DeliveryScheduleDialog.kt")
        assertTrue(dialog.contains("MAX_DELIVERY_JOBS_PER_LEGACY_SLOT"))
        assertTrue(dialog.contains("legacyHalfDayCap = !schedule.isDated"))
        assertTrue(dialog.contains("canAdd = dayHasRoom && slotHasRoom"))
    }

    @Test
    fun dragHitTestOnlyConsidersTheVisibleWeek() {
        val dialog = source("ui/components/DeliveryScheduleDialog.kt")
        assertFalse(dialog.contains("LaunchedEffect(weekStart) { slotBounds.clear() }"))
        val updateDrag = dialog.substringAfter("fun updateDrag(").substringBefore("fun endDrag(")
        assertTrue(updateDrag.contains("week.days"))
        assertTrue(updateDrag.contains("amKey"))
        assertTrue(updateDrag.contains("pmKey"))
    }

    @Test
    fun refusedEditsRevertTheDialogAndManualAddIsHiddenOnDatedSchedules() {
        val dialog = source("ui/components/DeliveryScheduleDialog.kt")
        assertTrue(dialog.contains("revision: Int"))
        assertTrue(dialog.contains("remember(schedule, revision)"))
        assertTrue(dialog.contains("LaunchedEffect(schedule, revision)"))
        assertTrue(dialog.contains("allowManualAdd = !schedule.isDated"))
        val screen = source("ui/jobs/UnifiedJobsScreen.kt")
        assertTrue(screen.contains("deliveryEditRevision += 1"))
        assertTrue(screen.contains("revision = deliveryEditRevision"))
    }

    @Test
    fun bannerAndDialogUseShopZoneNotDeviceZone() {
        listOf("ui/components/DeliveryScheduleBanner.kt", "ui/components/DeliveryScheduleDialog.kt").forEach { path ->
            val src = source(path)
            assertTrue(path, src.contains("shopToday("))
            assertFalse(path, src.contains("LocalDate.now()"))
        }
    }

    @Test
    fun noPerHalfDayCapSurvivesAnywhereInMain() {
        val offenders = mainSourceFiles().filter { it.readText().contains("MAX_DELIVERY_JOBS_PER_SLOT") }
        assertTrue("Per-half-day cap still referenced in: $offenders", offenders.isEmpty())
        val dialog = source("ui/components/DeliveryScheduleDialog.kt")
        assertTrue(dialog.contains("deliveryDayJobs("))
        assertTrue(dialog.contains("MAX_DELIVERY_JOBS_PER_DAY"))
        assertFalse(dialog.contains("jobs.take("))
        // Six jobs plus both period labels must scroll inside the banner's fixed-height day segment.
        val banner = source("ui/components/DeliveryScheduleBanner.kt")
        assertTrue(banner.contains("Column(Modifier.verticalScroll(rememberScrollState()))"))
        assertTrue(banner.contains(".height(DeliveryDaySegmentHeight)"))
    }

    private fun mainSourceFiles(): List<File> {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val root = File(dir, "app/src/main").takeIf { it.isDirectory } ?: File(dir, "src/main").takeIf { it.isDirectory }
            if (root != null) return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            dir = dir.parentFile ?: return@repeat
        }
        error("Missing app/src/main")
    }

    private fun source(relativePath: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val candidate = File(dir, "app/src/main/java/com/kkc/sheettracker/$relativePath")
            if (candidate.exists()) return candidate.readText()
            val direct = File(dir, "src/main/java/com/kkc/sheettracker/$relativePath")
            if (direct.exists()) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("Missing source $relativePath")
    }
}
