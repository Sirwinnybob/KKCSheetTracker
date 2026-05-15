package com.kkc.updateragent.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.kkc.updateragent.update.UpdatePaths
import com.kkc.updateragent.update.UpdateScheduler

class KkcDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "KKC Updater Agent enabled", Toast.LENGTH_SHORT).show()
        val basePath = UpdatePaths.defaultBasePath()
        UpdateScheduler.runNow(context, basePath)
    }
}
