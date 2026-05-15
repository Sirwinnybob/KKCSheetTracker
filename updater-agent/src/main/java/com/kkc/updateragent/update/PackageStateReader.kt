package com.kkc.updateragent.update

import android.content.Context
import android.os.Build

class PackageStateReader(private val context: Context) {
    fun installedVersionCode(packageName: String): Long? {
        return try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (_: Exception) {
            null
        }
    }
}
