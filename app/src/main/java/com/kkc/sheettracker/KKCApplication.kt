package com.kkc.sheettracker

import android.app.Application
import com.kkc.sheettracker.crash.CrashReporter
import com.kkc.sheettracker.perf.CpuSpikeMonitor
import com.kkc.sheettracker.data.mixservice.MixCatalogCache
import com.kkc.sheettracker.data.mixservice.MixCatalogRepository
import com.kkc.sheettracker.data.mixservice.MixOperationCoordinator
import com.kkc.sheettracker.data.mixservice.MixServiceClient
import com.kkc.sheettracker.data.mixservice.mixOperationSessionStore

class KKCApplication : Application() {
    /** One process-scoped client is shared by catalog reads and durable mutations. */
    val mixServiceClient: MixServiceClient by lazy { MixServiceClient() }

    /** Durable catalog state shared by every screen and the operation coordinator. */
    val mixCatalogCache: MixCatalogCache by lazy { MixCatalogCache.inAppFiles(this) }

    /** Read-only, cache-first catalog boundary for UI consumers. */
    val mixCatalogRepository: MixCatalogRepository by lazy {
        MixCatalogRepository(mixServiceClient, mixCatalogCache)
    }

    val mixOperationCoordinator: MixOperationCoordinator by lazy {
        MixOperationCoordinator(
            service = mixServiceClient,
            store = mixOperationSessionStore(),
            catalogPublisher = mixCatalogCache,
        )
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        CpuSpikeMonitor.install(this)
        mixOperationCoordinator.restore()
    }
}
