package com.kkc.sheettracker.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.kkc.sheettracker.logging.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val TAG = "TimecardDiscovery"
private const val SERVICE_TYPE = "_timeclock._tcp."

class TimecardDiscovery(private val context: Context) {

    /**
     * Discovers the timeclock hub via mDNS. Returns "http://<host>:<port>" on success,
     * null if no service is found within the timeout.
     */
    suspend fun discover(timeoutMs: Long = 1500L): String? {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var discoveryListener: NsdManager.DiscoveryListener? = null

                val discoveryStarted = AtomicBoolean(false)
                val cancelPending = AtomicBoolean(false)

                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                        Log.w(TAG, "Resolve failed: $code")
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val hostAddress = info.host?.hostAddress
                        val host = hostAddress ?: run {
                            if (cont.isActive) cont.resume(null)
                            return
                        }
                        val port = info.port
                        val url = "http://$host:$port"
                        AppLog.i(TAG, "Discovered timeclock hub at $url")
                        if (cont.isActive) cont.resume(url)
                    }
                }

                val resolving = AtomicBoolean(false)

                discoveryListener = object : NsdManager.DiscoveryListener {
                    override fun onStartDiscoveryFailed(type: String, code: Int) {
                        Log.e(TAG, "Discovery start failed: $code")
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onStopDiscoveryFailed(type: String, code: Int) {
                        Log.w(TAG, "Discovery stop failed: $code")
                    }

                    override fun onDiscoveryStarted(type: String) {
                        AppLog.d(TAG, "Discovery started for $type")
                        discoveryStarted.set(true)
                        if (cancelPending.get()) {
                            try {
                                nsdManager.stopServiceDiscovery(this)
                            } catch (e: Exception) {
                                Log.w(TAG, "Error stopping discovery on deferred cancel: ${e.message}")
                            }
                        }
                    }

                    override fun onDiscoveryStopped(type: String) {
                        AppLog.d(TAG, "Discovery stopped for $type")
                    }

                    override fun onServiceFound(info: NsdServiceInfo) {
                        if (!resolving.compareAndSet(false, true)) return
                        AppLog.d(TAG, "Found service: ${info.serviceName}")
                        @Suppress("DEPRECATION")
                        nsdManager.resolveService(info, resolveListener)
                    }

                    override fun onServiceLost(info: NsdServiceInfo) {
                        AppLog.d(TAG, "Service lost: ${info.serviceName}")
                    }
                }

                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

                cont.invokeOnCancellation {
                    if (discoveryStarted.get()) {
                        try {
                            nsdManager.stopServiceDiscovery(discoveryListener)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error stopping discovery: ${e.message}")
                        }
                    } else {
                        cancelPending.set(true)
                    }
                }
            }
        }
    }
}
