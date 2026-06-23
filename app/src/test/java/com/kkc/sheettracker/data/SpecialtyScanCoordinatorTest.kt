package com.kkc.sheettracker.data

import com.kkc.sheettracker.data.models.RefreshReason
import com.kkc.sheettracker.data.models.ScanStatus
import com.kkc.sheettracker.data.models.SpecialtyJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SpecialtyScanCoordinatorTest {
    private val jobFolderName = "1234 - Test Job"

    @Test
    fun refreshWithMissingSpecialtyFiles_listsJobWithZeroSpecialtyItems() {
        val baseDir = createTempBaseDir()
        File(baseDir, jobFolderName).mkdirs()
        writeDeploymentGate(baseDir, jobFolderName)

        val progressStore = SpecialtyProgressStore(baseDir = baseDir, tabletId = "tablet-a")
        val repository = SpecialtyRepository(baseDir = baseDir, progressStore = progressStore)
        val coordinator = SpecialtyScanCoordinator(repository)

        coordinator.refresh(RefreshReason.USER_REFRESH, force = true)
        waitUntilReady { coordinator.state.value.status }

        val state = coordinator.state.value
        assertEquals(ScanStatus.READY, state.status)
        val job = state.snapshot.jobs.single()
        assertEquals(jobFolderName, job.folderName)
        assertEquals(0, job.totalItems)
        assertEquals(0, job.completedItems)
    }

    @Test
    fun refreshLoadsSpecialtyJobsAndCompletionCounts() = runBlocking {
        val baseDir = createTempBaseDir()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "item-order",
                      "name": "To Order Item",
                      "cabinetNumbers": ["C1"],
                      "category": "TO_ORDER",
                      "stations": []
                    }
                  ]
                }
            """.trimIndent()
        )
        writeTrackerFile(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            tabletId = "tablet-a",
            body = """
                {
                  "tabletId": "tablet-a",
                  "schemaVersion": 2,
                  "completions": {
                    "item-order": {
                      "completion": {
                        "completed": true,
                        "completedAt": "2026-05-10T00:00:00Z",
                        "completedBy": "tablet-a"
                      }
                    }
                  }
                }
            """.trimIndent()
        )

        val progressStore = SpecialtyProgressStore(baseDir = baseDir, tabletId = "tablet-a")
        val repository = SpecialtyRepository(baseDir = baseDir, progressStore = progressStore)
        val coordinator = SpecialtyScanCoordinator(repository)

        coordinator.refresh(RefreshReason.USER_REFRESH, force = true)
        waitUntilReady { coordinator.state.value.status }

        val job = coordinator.state.value.snapshot.jobs.single()
        assertEquals(jobFolderName, job.folderName)
        assertEquals(1, job.totalItems)
        assertEquals(1, job.completedItems)
        assertEquals(0, job.remainingItems)
    }

    @Test
    fun refreshChecklistOnlyJobProducesSpecialtyCounts() {
        val baseDir = createTempBaseDir()
        writeChecklistItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "c1",
                      "text": "Checklist specialty task",
                      "modes": ["SPECIALTY"],
                      "category": "TO_ORDER"
                    }
                  ]
                }
            """.trimIndent()
        )

        val progressStore = SpecialtyProgressStore(baseDir = baseDir, tabletId = "tablet-a")
        val repository = SpecialtyRepository(baseDir = baseDir, progressStore = progressStore)
        val coordinator = SpecialtyScanCoordinator(repository)

        coordinator.refresh(RefreshReason.USER_REFRESH, force = true)
        waitUntilReady { coordinator.state.value.status }

        val job = coordinator.state.value.snapshot.jobs.single()
        assertEquals(1, job.totalItems)
        assertEquals(0, job.completedItems)
    }

    @Test
    fun refreshMergesSpecialtyItemsAndChecklistItems() {
        val baseDir = createTempBaseDir()
        writeSpecialtyItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "item-order",
                      "name": "To Order Item",
                      "cabinetNumbers": ["C1"],
                      "category": "TO_ORDER",
                      "stations": []
                    }
                  ]
                }
            """.trimIndent()
        )
        writeChecklistItems(
            baseDir = baseDir,
            jobFolderName = jobFolderName,
            body = """
                {
                  "items": [
                    {
                      "id": "c1",
                      "text": "Checklist specialty task",
                      "modes": ["SPECIALTY"],
                      "category": "CUSTOM"
                    }
                  ]
                }
            """.trimIndent()
        )

        val progressStore = SpecialtyProgressStore(baseDir = baseDir, tabletId = "tablet-a")
        val repository = SpecialtyRepository(baseDir = baseDir, progressStore = progressStore)
        val coordinator = SpecialtyScanCoordinator(repository)

        coordinator.refresh(RefreshReason.USER_REFRESH, force = true)
        waitUntilReady { coordinator.state.value.status }

        val job = coordinator.state.value.snapshot.jobs.single()
        assertEquals(2, job.totalItems)
    }

    @Test
    fun refreshLatestRequestWinsWhenEarlierRefreshCompletesLater() {
        val baseDir = createTempBaseDir()
        val progressStore = SpecialtyProgressStore(baseDir = baseDir, tabletId = "tablet-a")
        val repository = SpecialtyRepository(baseDir = baseDir, progressStore = progressStore)

        val scanCallCount = AtomicInteger(0)
        val allowFirstScanToFinish = CountDownLatch(1)

        val coordinator = SpecialtyScanCoordinator(
            repository = repository,
            scanJobsProvider = {
                when (scanCallCount.incrementAndGet()) {
                    1 -> {
                        allowFirstScanToFinish.await(2, TimeUnit.SECONDS)
                        listOf(
                            SpecialtyJob(
                                folderName = "older-result",
                                jobNumber = "1000",
                                jobName = "Older"
                            )
                        )
                    }
                    else -> {
                        listOf(
                            SpecialtyJob(
                                folderName = "latest-result",
                                jobNumber = "2000",
                                jobName = "Latest"
                            )
                        )
                    }
                }
            }
        )

        coordinator.refresh(RefreshReason.APP_FOREGROUND, force = false)
        waitUntil { scanCallCount.get() >= 1 }

        coordinator.refresh(RefreshReason.USER_REFRESH, force = true)
        allowFirstScanToFinish.countDown()

        waitUntil { scanCallCount.get() >= 2 }
        waitUntilReady { coordinator.state.value.status }

        val finalState = coordinator.state.value
        assertEquals(RefreshReason.USER_REFRESH, finalState.lastRefreshReason)
        assertEquals("latest-result", finalState.snapshot.jobs.single().folderName)
    }

    @Test
    fun refreshQueuedDuringIdleTransition_isNotDropped() {
        val baseDir = createTempBaseDir()
        val progressStore = SpecialtyProgressStore(baseDir = baseDir, tabletId = "tablet-a")
        val repository = SpecialtyRepository(baseDir = baseDir, progressStore = progressStore)
        val scanCallCount = AtomicInteger(0)
        val queuedOnce = AtomicInteger(0)
        lateinit var coordinator: SpecialtyScanCoordinator

        coordinator = SpecialtyScanCoordinator(
            repository = repository,
            scanJobsProvider = {
                when (scanCallCount.incrementAndGet()) {
                    1 -> listOf(
                        SpecialtyJob(
                            folderName = "first-run",
                            jobNumber = "1000",
                            jobName = "First"
                        )
                    )
                    else -> listOf(
                        SpecialtyJob(
                            folderName = "queued-run",
                            jobNumber = "2000",
                            jobName = "Queued"
                        )
                    )
                }
            },
            onBeforeIdleTransition = {
                if (queuedOnce.compareAndSet(0, 1)) {
                    coordinator.refresh(RefreshReason.WATCHER_CHANGE, force = true)
                }
            }
        )

        coordinator.refresh(RefreshReason.APP_FOREGROUND, force = false)
        waitUntil { scanCallCount.get() >= 2 }
        waitUntilReady { coordinator.state.value.status }

        val finalState = coordinator.state.value
        assertEquals(RefreshReason.WATCHER_CHANGE, finalState.lastRefreshReason)
        assertEquals("queued-run", finalState.snapshot.jobs.single().folderName)
    }

    private fun waitUntilReady(status: () -> ScanStatus) {
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            val current = status()
            if (current == ScanStatus.READY || current == ScanStatus.ERROR) return
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for specialty scan status to settle")
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for condition")
    }

    private fun createTempBaseDir(): File = Files.createTempDirectory("specialty-scan-coordinator-test").toFile()

    private fun writeDeploymentGate(baseDir: File, jobFolderName: String) {
        val file = File(baseDir, "$jobFolderName/.metadata/deployment_gate.json")
        file.parentFile?.mkdirs()
        file.writeText("""{"deployed": true}""")
    }

    private fun writeSpecialtyItems(baseDir: File, jobFolderName: String, body: String) {
        writeDeploymentGate(baseDir, jobFolderName)
        val file = File(baseDir, "$jobFolderName/.metadata/admin/specialty_items.json")
        file.parentFile?.mkdirs()
        file.writeText(body)
    }

    private fun writeTrackerFile(baseDir: File, jobFolderName: String, tabletId: String, body: String) {
        val file = File(baseDir, "$jobFolderName/.metadata/admin/.tracker/$tabletId.json")
        file.parentFile?.mkdirs()
        file.writeText(body)
    }

    private fun writeChecklistItems(baseDir: File, jobFolderName: String, body: String) {
        writeDeploymentGate(baseDir, jobFolderName)
        val file = File(baseDir, "$jobFolderName/.metadata/admin/checklist.json")
        file.parentFile?.mkdirs()
        file.writeText(body)
    }
}
