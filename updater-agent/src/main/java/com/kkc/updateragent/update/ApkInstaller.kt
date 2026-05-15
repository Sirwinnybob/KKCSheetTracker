package com.kkc.updateragent.update

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class InstallOutcome(
    val success: Boolean,
    val status: Int,
    val message: String?
)

class ApkInstaller(private val context: Context) {

    fun install(packageName: String, apkFile: File, allowDowngrade: Boolean): InstallOutcome {
        if (!apkFile.isFile) {
            return InstallOutcome(false, PackageInstaller.STATUS_FAILURE, "APK not found")
        }

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        try {
            FileInputStream(apkFile).use { input ->
                session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val action = "${context.packageName}.INSTALL_COMMIT.${UUID.randomUUID()}"
            val latch = CountDownLatch(1)
            var resultStatus = PackageInstaller.STATUS_FAILURE
            var resultMessage: String? = "No result"
            var pendingUserActionIntent: Intent? = null

            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent == null) return
                    resultStatus = intent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE
                    )
                    resultMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    if (resultStatus == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        pendingUserActionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                    }
                    latch.countDown()
                }
            }

            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            val callbackIntent = Intent(action).setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                flags
            )

            try {
                session.commit(pendingIntent.intentSender)
                val completed = latch.await(2, TimeUnit.MINUTES)
                if (!completed) {
                    return InstallOutcome(false, PackageInstaller.STATUS_FAILURE_TIMEOUT, "Install timed out")
                }

                if (resultStatus == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    if (pendingUserActionIntent != null) {
                        pendingUserActionIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(pendingUserActionIntent)
                    }
                    return InstallOutcome(
                        success = false,
                        status = resultStatus,
                        message = "User action required"
                    )
                }

                return InstallOutcome(
                    success = resultStatus == PackageInstaller.STATUS_SUCCESS,
                    status = resultStatus,
                    message = resultMessage
                )
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        } finally {
            try {
                session.close()
            } catch (_: Exception) {
            }
        }
    }
}
