package com.kkc.sheettracker.update

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
