package com.kkc.sheettracker.update

import java.io.File

/** One app in an Update All run: install [apkFile] until [packageName] reports [targetVersionCode]. */
internal data class UpdateStep(
    val packageName: String,
    val label: String,
    val apkFile: File,
    val targetVersionCode: Long,
)

/**
 * Update All order: installed companion apps first (feed order), then this app last — installing
 * over ourselves restarts the process, so nothing queued after it would ever run. Apps that aren't
 * installed on the tablet are offers, not updates, and are never part of Update All.
 */
internal fun updateAllSteps(selfStep: UpdateStep?, externalUpdates: List<ExternalAppUpdate>): List<UpdateStep> =
    splitExternalOffers(externalUpdates).updates.map {
        UpdateStep(it.packageName, it.appName, it.apkFile, it.versionCode)
    } + listOfNotNull(selfStep)

internal enum class StepCheck { WAIT, ADVANCE, ABORT }

/**
 * Called after the system installer closes. The new version must be installed before the next app
 * is prompted; the grace period covers PackageManager lagging the installer UI by a moment. Still
 * old (cancelled or failed) after that → stop the run.
 */
internal fun checkStep(installedVersionCode: Long?, targetVersionCode: Long, elapsedMs: Long, graceMs: Long): StepCheck =
    when {
        installedVersionCode != null && installedVersionCode >= targetVersionCode -> StepCheck.ADVANCE
        elapsedMs < graceMs -> StepCheck.WAIT
        else -> StepCheck.ABORT
    }

internal class UpdateAllQueue(private val steps: List<UpdateStep>) {
    private var index = 0

    /** True once the current step's installer actually opened (not just the install-permission detour). */
    var launched = false

    /** The app went to the background after the installer opened, i.e. the installer is in front. */
    private var leftForeground = false

    /**
     * A resume means "the installer closed" only if the app was paused after the installer opened.
     * After the install-permission detour the installer opens from the settings result callback,
     * just before the activity's own onResume — that resume must not start the confirmation.
     */
    val readyToConfirm: Boolean get() = launched && leftForeground

    fun onPaused() {
        if (launched) leftForeground = true
    }

    val current: UpdateStep? get() = steps.getOrNull(index)

    /** 1-based position of [current], for "Updating X (2 of 3)". */
    val position: Int get() = index + 1

    val total: Int get() = steps.size

    fun advance(): UpdateStep? {
        index++
        launched = false
        leftForeground = false
        return current
    }
}
