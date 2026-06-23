package com.kkc.sheettracker.crash

import android.content.Context
import android.os.Build
import android.util.Log
import com.kkc.sheettracker.BuildConfig
import java.io.File
import kotlin.system.exitProcess

object CrashReporter {
    private const val TAG = "KKC_CRASH_REPORTER"
    private const val PREFS_NAME = "kkc_tracker"

    @Volatile
    private var contextSnapshot = CrashReportContext()

    @Volatile
    private var installed = false

    private lateinit var appContext: Context
    private lateinit var store: CrashReportStore
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            appContext = context.applicationContext
            store = CrashReportStore(
                pendingDir = File(appContext.filesDir, "crash_reports/pending")
            )
            refreshFromPreferences(appContext)
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                recordFatalCrash(throwable)
                val handler = previousHandler
                if (handler != null) {
                    handler.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(10)
                }
            }
            installed = true
        }
    }

    fun refreshFromPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        updateContext(
            tabletId = prefs.getString("tablet_id", null),
            basePath = prefs.getString("base_path", null),
            workMode = prefs.getString("work_mode", null)
        )
    }

    fun updateContext(
        tabletId: String? = null,
        workMode: String? = null,
        currentTab: String? = null,
        currentRoute: String? = null,
        activeJobFolderName: String? = null,
        basePath: String? = null
    ) {
        synchronized(this) {
            contextSnapshot = contextSnapshot.copy(
                tabletId = tabletId ?: contextSnapshot.tabletId,
                workMode = workMode ?: contextSnapshot.workMode,
                currentTab = currentTab ?: contextSnapshot.currentTab,
                currentRoute = currentRoute ?: contextSnapshot.currentRoute,
                activeJobFolderName = activeJobFolderName ?: contextSnapshot.activeJobFolderName,
                basePath = basePath ?: contextSnapshot.basePath
            )
        }
    }

    fun updateNavigationContext(
        currentTab: String,
        currentRoute: String?,
        activeJobFolderName: String?
    ) {
        synchronized(this) {
            contextSnapshot = contextSnapshot.copy(
                currentTab = currentTab,
                currentRoute = currentRoute,
                activeJobFolderName = activeJobFolderName
            )
        }
    }

    fun flushPending(basePath: String) {
        if (!::store.isInitialized) return
        runCatching {
            store.flushPending(File(basePath))
        }.onFailure { error ->
            Log.w(TAG, "Unable to flush pending crash reports", error)
        }
    }

    private fun recordFatalCrash(throwable: Throwable) {
        if (!::store.isInitialized) return
        val snapshot = contextSnapshot
        runCatching {
            store.recordCrash(
                baseDir = snapshot.basePath?.let(::File),
                context = snapshot,
                environment = CrashEnvironment(
                    appVersionName = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE,
                    androidRelease = Build.VERSION.RELEASE,
                    androidSdk = Build.VERSION.SDK_INT,
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL
                ),
                throwable = throwable
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to write crash report", error)
        }
    }
}
