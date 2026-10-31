package com.kkc.sheettracker

import android.app.Application
import com.kkc.sheettracker.crash.CrashReporter
import com.kkc.sheettracker.perf.CpuSpikeMonitor
import com.kkc.sheettracker.data.mixservice.MixCatalogCache
import com.kkc.sheettracker.data.mixservice.MixCatalogRepository
import com.kkc.sheettracker.data.mixservice.MixOperationCoordinator
import com.kkc.sheettracker.data.mixservice.MixServiceClient
import com.kkc.sheettracker.data.mixservice.mixOperationSessionStore
import com.kkc.sheettracker.data.mixfiles.MixFilesCatalogCache
import com.kkc.sheettracker.data.mixfiles.MixFilesClient
import com.kkc.sheettracker.data.mixfiles.MixFilesStoreRegistry

class KKCApplication : Application() {
    /** Shared per-machine/material catalogs; foreground screens own their polling leases. */
    val mixFilesStores: MixFilesStoreRegistry by lazy {
        MixFilesStoreRegistry(
            api = { machine -> MixFilesClient(machine) },
            cache = MixFilesCatalogCache(java.io.File(filesDir, "mix-files-cache")),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
        )
    }

    /** No network activity until a foreground CNC surface acquires a lease. */
    val machineStatusStore: com.kkc.sheettracker.data.cnc.MachineStatusStore by lazy {
        com.kkc.sheettracker.data.cnc.MachineStatusStore(
            com.kkc.sheettracker.data.cnc.MachineStateClient(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
            android.os.SystemClock::elapsedRealtime,
        ) { available -> android.util.Log.i("MachineStatus", if (available) "available" else "unavailable") }
    }

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
