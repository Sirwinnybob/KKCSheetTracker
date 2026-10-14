package com.kkc.sheettracker.ui.jobs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryScheduleWiringTest {
    @Test
    fun jobsScreenRoutesDialogEditsThroughTheEditor() {
        val src = source("ui/jobs/UnifiedJobsScreen.kt")
        assertTrue(src.contains("DeliveryScheduleEditor("))
        // The editor's remote resolves the server URL per edit; nothing may hand it a client that
        // composition state had to produce first (that queued the very first edit).
        assertTrue(src.contains("lazyDeliveryRemote { adminSyncConfig.getServerUrl()"))
        assertFalse(src.contains("adminSyncClient?.asDeliveryRemote()"))
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
        // ONE process-wide mutex feeds the ONE process-wide runner; no screen builds its own.
        assertEquals(1, Regex("""\bMutex\(\)""").findAll(screen).count())
        assertTrue(screen.contains("DeliveryEditRunner(deliveryEditScope, deliveryEditMutex)"))
        val runner = source("ui/jobs/DeliveryEditRunner.kt")
        // Serialization is the runner's job (and its behavior is covered in DeliveryEditRunnerTest):
        // the whole edit, including the fresh read from the store, runs inside the lock of run().
        val runBody = runner.substringAfter("fun run(").substringBefore("private companion object")
        val locked = runBody.substringAfter("scope.launch {").substringAfter("mutex.withLock {")
        assertTrue(locked.contains("overlayPendingDeliveryRequest(currentSchedule(), readPending())"))
        assertTrue(locked.contains("edit(editor(), current)"))
        assertTrue(locked.contains("onResult("))
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
        // Both the Add button and the drop highlight use the one tested rule (deliveryDropAllowed).
        assertTrue(dialog.contains("isDated = schedule.isDated"))
        assertTrue(dialog.contains("val canAdd = deliveryDropAllowed(schedule, day, slotKey, null, isDated) == DropDecision.Allowed"))
        assertTrue(dialog.contains("deliveryDropAllowed(editableSchedule, day, toSlot, fromSlot, !editableSchedule.isDated, job)"))
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
    fun dialogKeepsAbsoluteWeekKeyedDaysAndDerivedEditStatus() {
        val dialog = source("ui/components/DeliveryScheduleDialog.kt")
        // C1: an absolute selected week, resolved without writing state during composition.
        assertTrue(dialog.contains("var selectedWeek by remember { mutableStateOf<LocalDate?>(null) }"))
        assertTrue(dialog.contains("resolveDeliveryWeekStart(schedule, today, selectedWeek)"))
        assertFalse(dialog.contains("weekOffset"))
        // C4: columns are keyed by date.
        assertTrue(dialog.contains("key(day.date) {"))
        // C10: no local edit counter; the status comes from facts.
        assertFalse(dialog.contains("pendingEdits"))
        assertTrue(dialog.contains("deliveryEditStatusText(inFlightEdits, pendingSlotKeys.size)"))
        assertTrue(dialog.contains("isAdminMode && day.state != DeliveryDayState.PAST"))
        // C2: the reset button names the dates.
        assertTrue(dialog.contains("deliveryResetConfirmLabel(week.weekStart)"))
        val screen = source("ui/jobs/UnifiedJobsScreen.kt")
        assertTrue(screen.contains("inFlightEdits = deliveryInFlightEdits"))
        assertTrue(screen.contains("deliveryEditRunner.inFlight.collectAsState()"))
    }

    @Test
    fun pendingMarkersAreReadBeforeTheDialogShowsAndSessionStateIsClearedOnDismiss() {
        val screen = source("ui/jobs/UnifiedJobsScreen.kt")
        assertTrue(screen.contains("if (showScheduleDialog && deliveryPendingLoaded)"))
        val dismiss = screen.substringAfter("DeliveryScheduleDialog(").substringAfter("onDismiss = {")
            .substringBefore("isAdminMode = adminMode")
        assertTrue(dismiss.contains("deliveryPendingRequest = null"))
        assertTrue(dismiss.contains("deliveryHeldRequest = null"))
        assertTrue(dismiss.contains("deliveryPendingLoaded = false"))
        assertTrue(screen.contains("DELIVERY_OVERLAY_HOLD_MILLIS"))
        assertTrue(screen.contains("effectiveDeliveryOverlayRequest(deliveryPendingRequest, deliveryHeldRequest"))
        // Results from the runner go through the same hold bookkeeping as the poll.
        assertTrue(screen.contains("updateDeliveryPending(result.pending)"))
    }

    @Test
    fun bannerHasScrollCueAndWeekendListWiring() {
        val banner = source("ui/components/DeliveryScheduleBanner.kt")
        // C7: bottom fade while the fixed-height segment can scroll further (drawn, no shadow surface).
        assertTrue(banner.contains("scrollState.canScrollForward"))
        assertTrue(banner.contains("drawWithContent"))
        assertTrue(banner.contains("Brush.verticalGradient"))
        assertTrue(banner.contains("containerColor.copy(alpha = contentAlpha)"))
        // C11: the list from weekendDays(), not a single day.
        assertTrue(banner.contains("hiddenWeekendDays(schedule, today, week.weekStart)"))
        assertTrue(banner.contains("weekendDays.sumOf { it.count }"))
        assertFalse(banner.contains("weekendTodayDay"))
        assertTrue(banner.contains("Deliveries This Weekend & Next Week"))
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
        assertTrue(banner.contains("val scrollState = rememberScrollState()"))
        assertTrue(banner.contains(".verticalScroll(scrollState)"))
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
