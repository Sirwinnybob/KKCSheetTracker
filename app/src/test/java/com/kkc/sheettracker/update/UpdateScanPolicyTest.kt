package com.kkc.sheettracker.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateScanPolicyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun gateAllowsOnlyOneScan() {
        val gate = UpdateScanGate()

        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())
        gate.leave()
        assertTrue(gate.tryEnter())
    }

    @Test
    fun fingerprintChangesWhenPathChanges() {
        val first = ApkArchiveFingerprint.from(File(temporaryFolder.root, "first.apk"))
        val second = ApkArchiveFingerprint.from(File(temporaryFolder.root, "second.apk"))

        assertNotEquals(first, second)
    }

    @Test
    fun fingerprintChangesWhenLengthChanges() {
        val apk = temporaryFolder.newFile("update.apk")
        val first = ApkArchiveFingerprint.from(apk)

        apk.writeText("apk")
        val second = ApkArchiveFingerprint.from(apk)

        assertNotEquals(first, second)
    }

    @Test
    fun fingerprintChangesWhenLastModifiedChanges() {
        val apk = temporaryFolder.newFile("update.apk")
        val first = ApkArchiveFingerprint.from(apk)
        val changed = first.lastModified + 2_000L
        assertTrue(apk.setLastModified(changed))

        val second = ApkArchiveFingerprint.from(apk)

        assertNotEquals(first, second)
    }
}
