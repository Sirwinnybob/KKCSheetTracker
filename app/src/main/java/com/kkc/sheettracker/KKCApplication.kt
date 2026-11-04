package com.kkc.sheettracker

import android.app.Application
import com.kkc.sheettracker.crash.CrashReporter
import com.kkc.sheettracker.perf.CpuSpikeMonitor
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
            com.kkc.sheettracker.data.cnc.MachineStateClient(com.kkc.sheettracker.data.cnc.CncMachines.scm.baseUrl),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
            android.os.SystemClock::elapsedRealtime,
        ) { available -> android.util.Log.i("MachineStatus", if (available) "available" else "unavailable") }
    }

    val newMachineStatusStore: com.kkc.sheettracker.data.cnc.MachineStatusStore by lazy {
        com.kkc.sheettracker.data.cnc.MachineStatusStore(
            com.kkc.sheettracker.data.cnc.MachineStateClient(com.kkc.sheettracker.data.cnc.CncMachines.scmNew.baseUrl),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
            android.os.SystemClock::elapsedRealtime,
            offlineGraceMs = 15_000L,
        )
    }

    /** Durable material-move journal in app-private storage (never a synced root). */
    val materialTransfers: com.kkc.sheettracker.data.cnc.MaterialTransferStore by lazy {
        com.kkc.sheettracker.data.cnc.MaterialTransferStore(java.io.File(filesDir, "state/material_transfers.json"))
    }

    /** The live-status store of an explicit machine; unknown machines fall back to Red. */
    fun statusStoreFor(machine: com.kkc.sheettracker.data.cnc.CncMachine): com.kkc.sheettracker.data.cnc.MachineStatusStore =
        if (machine.id == com.kkc.sheettracker.data.cnc.CncMachines.scmNew.id) newMachineStatusStore else machineStatusStore

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        CpuSpikeMonitor.install(this)
    }
}
