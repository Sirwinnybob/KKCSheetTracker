package com.kkc.sheettracker.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class SyncthingInstallTarget(
    val packageName: String,
    val startAction: String,
    val stopAction: String
)

object SyncthingInstallResolver {
    private val knownPackages = listOf(
        "com.github.catfriend1.syncthingfork",
        "com.nutomic.syncthingandroid"
    )

    fun resolve(context: Context): SyncthingInstallTarget {
        val packageName = knownPackages.firstOrNull { isInstalled(context, it) }
            ?: knownPackages.last()
        return SyncthingInstallTarget(
            packageName = packageName,
            startAction = "$packageName.action.START",
            stopAction = "$packageName.action.STOP"
        )
    }

    private fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
