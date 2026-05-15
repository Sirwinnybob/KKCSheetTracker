package com.kkc.updateragent.update

import android.app.admin.DevicePolicyManager
import android.content.Context

class DeviceOwnerState(private val context: Context) {
    fun isDeviceOwner(): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }
}
