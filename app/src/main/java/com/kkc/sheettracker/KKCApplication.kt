package com.kkc.sheettracker

import android.app.Application
import com.kkc.sheettracker.crash.CrashReporter

class KKCApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
