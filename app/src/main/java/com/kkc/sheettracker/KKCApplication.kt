package com.kkc.sheettracker

import android.app.Application
import com.kkc.sheettracker.crash.CrashReporter
import com.kkc.sheettracker.data.mixservice.MixOperationCoordinator
import com.kkc.sheettracker.data.mixservice.MixServiceClient
import com.kkc.sheettracker.data.mixservice.mixOperationSessionStore

class KKCApplication : Application() {
    val mixOperationCoordinator: MixOperationCoordinator by lazy {
        MixOperationCoordinator(MixServiceClient(), mixOperationSessionStore())
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        mixOperationCoordinator.restore()
    }
}
