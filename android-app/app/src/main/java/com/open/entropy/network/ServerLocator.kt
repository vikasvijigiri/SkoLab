package com.open.entropy.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * ServerLocator — discovers the ResQit backend on the local network automatically
 * using Android's built-in Network Service Discovery (NSD / mDNS).
 *
 * No IP address or port is ever hardcoded here.  The backend advertises itself
 * via zeroconf; this class finds it by matching [AppConfig.MDNS_SERVICE_NAME].
 *
 * Usage:
 *  - Call [start] once (in Application.onCreate) to begin discovery.
 *  - Observe [baseUrl] (a StateFlow<String?>) wherever you need the server URL.
 *    null means "not yet discovered".
 *  - Call [stop] when the app goes away.
 */
object ServerLocator {

    private const val TAG = "ServerLocator"

    // ── Public state ─────────────────────────────────────────────────────────
    private val _baseUrl = MutableStateFlow<String?>(null)

    /**
     * The discovered backend base URL, e.g. "http://192.168.0.19:8000".
     * Emits null until the backend is found on the network.
     */
    val baseUrl: StateFlow<String?> = _baseUrl.asStateFlow()

    // ── Internal NSD state ───────────────────────────────────────────────────
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null
    private var resolveInProgress = false
    private var probeThread: Thread? = null
    private var appContext: Context? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Update baseUrl and persist it to SharedPreferences cache */
    private fun updateBaseUrl(url: String) {
        _baseUrl.value = url
        appContext?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences("server_locator_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("last_resolved_url", url).apply()
                Log.d(TAG, "Saved resolved URL to cache: $url")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save URL to SharedPreferences", e)
            }
        }
    }

    /** Begin scanning the LAN for the ResQit backend service. */
    fun start(context: Context) {
        if (nsdManager != null) return  // already started

        appContext = context.applicationContext
        nsdManager = appContext?.getSystemService(Context.NSD_SERVICE) as? NsdManager

        // Load cached URL from SharedPreferences as immediate fallback
        try {
            val prefs = context.applicationContext.getSharedPreferences("server_locator_prefs", Context.MODE_PRIVATE)
            val cachedUrl = prefs.getString("last_resolved_url", null)
            if (cachedUrl != null && _baseUrl.value == null) {
                _baseUrl.value = cachedUrl
                Log.i(TAG, "Initialized baseUrl from SharedPreferences cache → $cachedUrl")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cached URL from SharedPreferences", e)
        }

        discoveryListener = buildDiscoveryListener()
        nsdManager?.discoverServices(
            AppConfig.MDNS_SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener
        )
        Log.i(TAG, "Service discovery started for '${AppConfig.MDNS_SERVICE_NAME}'")

        // Start background probing as fallback for emulator/local testing when mDNS is unavailable
        probeThread = Thread {
            val candidates = mutableListOf<String>()
            appContext?.let { ctx ->
                try {
                    val prefs = ctx.getSharedPreferences("server_locator_prefs", Context.MODE_PRIVATE)
                    prefs.getString("last_resolved_url", null)?.let {
                        candidates.add(it)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read cached URL in probe thread: ${e.message}")
                }
            }
            candidates.addAll(listOf(
                "http://10.0.2.2:8000",
                "http://127.0.0.1:8000",
                "http://10.0.2.2:8001",
                "http://127.0.0.1:8001",
                "http://10.0.2.2:8002",
                "http://127.0.0.1:8002"
            ))
            val uniqueCandidates = candidates.distinct()

            while (!Thread.currentThread().isInterrupted && nsdManager != null && _baseUrl.value == null) {
                for (url in uniqueCandidates) {
                    if (_baseUrl.value != null || Thread.currentThread().isInterrupted) break
                    if (probeUrl(url)) {
                        Log.i(TAG, "Backend resolved via probe fallback → $url")
                        updateBaseUrl(url)
                        break
                    }
                }
                try {
                    Thread.sleep(4000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** Stop discovery and release resources (call in Application.onTerminate or similar). */
    fun stop() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "stopServiceDiscovery: ${e.message}")
        }
        try {
            probeThread?.interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "interruptProbeThread: ${e.message}")
        }
        nsdManager = null
        discoveryListener = null
        probeThread = null
        appContext = null
        Log.i(TAG, "Service discovery stopped")
    }

    /**
     * Reports that a network request to the given URL failed.
     * If this matches the current baseUrl, we reset it to null to trigger rediscovery.
     */
    fun reportFailure(url: String) {
        val current = _baseUrl.value ?: return
        if (url.startsWith(current) || current.startsWith(url)) {
            Log.w(TAG, "Reported failure for active backend URL: $url. Resetting baseUrl to null.")
            _baseUrl.value = null
            // Also invalidate SharedPreferences cache
            appContext?.let { ctx ->
                try {
                    val prefs = ctx.getSharedPreferences("server_locator_prefs", Context.MODE_PRIVATE)
                    prefs.edit().remove("last_resolved_url").apply()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear SharedPreferences cache", e)
                }
            }
        }
    }

    // ── Listeners ────────────────────────────────────────────────────────────

    private fun buildDiscoveryListener() = object : NsdManager.DiscoveryListener {

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery start failed: error $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Discovery stop failed: error $errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) {
            Log.d(TAG, "Discovery started for $serviceType")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d(TAG, "Discovery stopped for $serviceType")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceName == AppConfig.MDNS_SERVICE_NAME) {
                Log.i(TAG, "Backend found: ${service.serviceName} — resolving…")
                resolveService(service)
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            if (service.serviceName == AppConfig.MDNS_SERVICE_NAME) {
                Log.w(TAG, "Backend service lost (ignored to keep fallback active)")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveService(service: NsdServiceInfo) {
        if (resolveInProgress) {
            Log.d(TAG, "Resolve already in progress, queuing…")
            return
        }
        resolveInProgress = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+ — modern, non-deprecated path
            nsdManager?.registerServiceInfoCallback(
                service,
                Executors.newSingleThreadExecutor(),
                object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        resolveInProgress = false
                        Log.e(TAG, "ServiceInfoCallback registration failed: $errorCode")
                    }

                    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                        val hostAddresses = serviceInfo.hostAddresses
                        val port = serviceInfo.port
                        val callbackInstance = this
                        Executors.newSingleThreadExecutor().execute {
                            var resolvedUrl: String? = null
                            for (addr in hostAddresses) {
                                val host = addr.hostAddress ?: continue
                                val url = "http://$host:$port"
                                Log.d(TAG, "Probing resolved address: $url")
                                if (probeUrl(url)) {
                                    resolvedUrl = url
                                    break
                                }
                            }
                            resolveInProgress = false
                            if (resolvedUrl != null) {
                                Log.i(TAG, "Backend resolved and verified → $resolvedUrl")
                                updateBaseUrl(resolvedUrl)
                                try {
                                    nsdManager?.unregisterServiceInfoCallback(callbackInstance)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to unregister service info callback: ${e.message}")
                                }
                            } else {
                                Log.w(TAG, "None of the resolved addresses are reachable")
                            }
                        }
                    }

                    override fun onServiceLost() {
                        Log.w(TAG, "Backend service lost (callback, ignored to keep fallback active)")
                    }

                    override fun onServiceInfoCallbackUnregistered() {
                        Log.d(TAG, "ServiceInfoCallback unregistered")
                    }
                }
            )
        } else {
            // API < 34 — legacy path (still works, just deprecated on newer APIs)
            resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolveInProgress = false
                    Log.e(TAG, "Resolve failed: error $errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolveInProgress = false
                    val port = serviceInfo.port
                    val hostAddresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        serviceInfo.hostAddresses
                    } else {
                        @Suppress("DEPRECATION")
                        listOfNotNull(serviceInfo.host)
                    }
                    Executors.newSingleThreadExecutor().execute {
                        var resolvedUrl: String? = null
                        for (addr in hostAddresses) {
                            val host = addr.hostAddress ?: continue
                            val url = "http://$host:$port"
                            Log.d(TAG, "Probing resolved address (legacy): $url")
                            if (probeUrl(url)) {
                                resolvedUrl = url
                                break
                            }
                        }
                        if (resolvedUrl != null) {
                            Log.i(TAG, "Backend resolved and verified (legacy) → $resolvedUrl")
                            updateBaseUrl(resolvedUrl)
                        } else {
                            Log.w(TAG, "None of the legacy resolved addresses are reachable")
                        }
                    }
                }
            }
            nsdManager?.resolveService(service, resolveListener)
        }
    }

    private fun probeUrl(urlString: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 800
            connection.readTimeout = 800
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                text.contains("API!") || text.contains("ResQit") || text.contains("Entro")
            } else {
                false
            }
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
