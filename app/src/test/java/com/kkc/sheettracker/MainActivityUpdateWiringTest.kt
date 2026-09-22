package com.kkc.sheettracker

import com.kkc.sheettracker.testutil.SourceFiles
import org.junit.Assert.assertFalse
import org.junit.Test

class MainActivityUpdateWiringTest {

    @Test
    fun silentSelfUpdatePathIsRemoved() {
        val mainActivitySource = SourceFiles.mainSource("com/kkc/sheettracker/MainActivity.kt").readText()
        val updateManagerSource = SourceFiles.mainSource("com/kkc/sheettracker/update/UpdateManager.kt").readText()

        assertFalse("MainActivity.kt must not reference DeviceOwnerUpdateFallback", mainActivitySource.contains("DeviceOwnerUpdateFallback"))
        assertFalse("MainActivity.kt must not reference ClockForUpdateOverlay", mainActivitySource.contains("ClockForUpdateOverlay"))
        assertFalse("MainActivity.kt must not track showClockForUpdate", mainActivitySource.contains("showClockForUpdate"))
        assertFalse("UpdateManager.kt must not expose isSilentUpdateSupported", updateManagerSource.contains("isSilentUpdateSupported"))
        assertFalse("UpdateManager.kt must not expose installPendingUpdateSilently", updateManagerSource.contains("installPendingUpdateSilently"))
    }

    @Test
    fun externalUpdateSkipLogicIsRemoved() {
        val mainActivitySource = SourceFiles.mainSource("com/kkc/sheettracker/MainActivity.kt").readText()
        val updateManagerSource = SourceFiles.mainSource("com/kkc/sheettracker/update/UpdateManager.kt").readText()

        assertFalse("MainActivity.kt must not render the external-update AlertDialog", mainActivitySource.contains("pendingExternalUpdate"))
        assertFalse("UpdateManager.kt must not expose canSkip", updateManagerSource.contains("canSkip"))
        assertFalse("UpdateManager.kt must not expose skipExternalUpdate", updateManagerSource.contains("skipExternalUpdate"))
        assertFalse("UpdateManager.kt must not persist skipped versions", updateManagerSource.contains("skipped_version_"))
    }
}
