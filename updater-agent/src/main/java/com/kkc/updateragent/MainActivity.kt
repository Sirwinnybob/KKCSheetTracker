package com.kkc.updateragent

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.kkc.updateragent.update.UpdateFeedRepository
import com.kkc.updateragent.update.UpdatePaths
import com.kkc.updateragent.update.UpdateScheduler

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermissions()
        val basePath = UpdatePaths.defaultBasePath()
        val paths = UpdatePaths(basePath)
        val policy = UpdateFeedRepository().readPolicy(paths)
        val resolvedBasePath = policy?.basePath ?: basePath
        val interval = policy?.pollIntervalMinutes ?: 15L
        UpdateScheduler.schedule(this, resolvedBasePath, interval)
        UpdateScheduler.runNow(this, resolvedBasePath)

        val isDeviceOwner = isDeviceOwner()
        val status = if (isDeviceOwner) {
            "Updater scheduled (Device Owner active)"
        } else {
            "Updater scheduled (not Device Owner yet)"
        }
        Toast.makeText(this, status, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun isDeviceOwner(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(packageName)
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            return
        }

        val legacyPerms = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        permissionLauncher.launch(legacyPerms)
    }
}
