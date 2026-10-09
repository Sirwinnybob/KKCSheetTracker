package com.kkc.sheettracker.update

internal fun hasPendingUpdateNotification(
    hasSelfUpdate: Boolean,
    externalOffers: List<ExternalAppUpdate>
): Boolean = hasSelfUpdate || externalOffers.any { it.isInstalled }

/**
 * Scan results split into [updates] for apps already on the tablet and [available] companion apps
 * that aren't installed. Only [updates] count as pending (notification dot, chips, Update All).
 */
internal data class ExternalOffers(
    val updates: List<ExternalAppUpdate>,
    val available: List<ExternalAppUpdate>,
)

internal fun splitExternalOffers(offers: List<ExternalAppUpdate>): ExternalOffers {
    val (updates, available) = offers.partition { it.isInstalled }
    return ExternalOffers(updates, available)
}

/** A version of -1 means absent; null means the installed version could not be determined. */
internal fun findExternalAppUpdates(
    apps: List<ExternalApp>,
    installedVersionFor: (String) -> Long?,
    apkInfosFor: (String) -> List<ApkInfo>
): List<ExternalAppUpdate> = apps.mapNotNull { app ->
    val installedVersion = installedVersionFor(app.packageName) ?: return@mapNotNull null
    val newest = apkInfosFor(app.packageName)
        .filter { it.packageName == app.packageName && it.versionCode > installedVersion }
        .maxWithOrNull(compareBy<ApkInfo> { it.versionCode }.thenBy { it.file.lastModified() })
        ?: return@mapNotNull null
    ExternalAppUpdate(
        app.packageName, app.appName, newest.file, newest.versionCode, newest.versionName,
        isInstalled = installedVersion != -1L
    )
}
