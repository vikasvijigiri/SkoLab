package com.company.skolab.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.pow

object ServerLocator {
    private const val TAG = "ServerLocator"

    private val _baseUrl = MutableStateFlow<String?>(null)
    private val _isBackendAvailable = MutableStateFlow(false)

    val baseUrl: StateFlow<String?> = _baseUrl.asStateFlow()
    val isBackendAvailable: StateFlow<Boolean> = _isBackendAvailable.asStateFlow()

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null
    private var resolveInProgress = false
    internal var appContext: Context? = null
    private var lastFailureReportTime = 0L

    // Coroutine Scope for local background tasks
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var probeJob: Job? = null
    
    // Connectivity
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isNetworkSuitable = false

    private fun updateBaseUrl(url: String) {
        if (_baseUrl.value == url) return
        _baseUrl.value = url
        _isBackendAvailable.value = true
        appContext?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences("server_locator_prefs", Context.MODE_PRIVATE)
                prefs.edit().putString("last_resolved_url", url).apply()
            } catch (e: Exception) {}
        }
        GlobalSocketManager.connect(url)
    }

    fun start(context: Context) {
        if (appContext != null) return // already started

        appContext = context.applicationContext

        val isEmulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator")
        if (com.company.skolab.BuildConfig.DEBUG && isEmulator) {
            updateBaseUrl(com.company.skolab.BuildConfig.BASE_URL)
            return
        }
        
        // If we are in production, always use the defined prod url
        if (!com.company.skolab.BuildConfig.DEBUG) {
            updateBaseUrl(com.company.skolab.BuildConfig.BASE_URL)
            return
        }

        try {
            val prefs = context.applicationContext.getSharedPreferences("server_locator_prefs", Context.MODE_PRIVATE)
            val cachedUrl = prefs.getString("last_resolved_url", null)
            if (cachedUrl != null && _baseUrl.value == null) {
                _baseUrl.value = cachedUrl
                _isBackendAvailable.value = true
                GlobalSocketManager.connect(cachedUrl)
            }
        } catch (e: Exception) {}

        setupNetworkCallback(context.applicationContext)
    }

    private fun setupNetworkCallback(context: Context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Suitable local network connected. Starting discovery.")
                isNetworkSuitable = true
                startDiscovery()
            }
            override fun onLost(network: Network) {
                Log.w(TAG, "Suitable local network lost. Pausing discovery.")
                isNetworkSuitable = false
                stopDiscovery()
                // Do NOT reset baseUrl immediately, rely on WebSocket dropping to handle actual disconnects
            }
        }
        connectivityManager.registerNetworkCallback(request, networkCallback!!)

        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
            isNetworkSuitable = true
            startDiscovery()
        }
    }

    private fun startDiscovery() {
        if (!isNetworkSuitable || _baseUrl.value != null) return

        appContext?.let { ctx ->
            if (nsdManager == null) {
                nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager
                discoveryListener = buildDiscoveryListener()
                try {
                    nsdManager?.discoverServices(AppConfig.MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                } catch (e: Exception) {
                    Log.e(TAG, "NsdManager discover failed", e)
                }
            }
        }

        probeJob?.cancel()
        probeJob = scope.launch {
            val candidates = mutableListOf<String>()
            appContext?.let { ctx ->
                try {
                    ctx.getSharedPreferences("server_locator_prefs", Context.MODE_PRIVATE)
                        .getString("last_resolved_url", null)?.let { candidates.add(it) }
                } catch (e: Exception) {}
            }
            candidates.add(com.company.skolab.BuildConfig.BASE_URL)
            if (com.company.skolab.BuildConfig.DEBUG) {
                candidates.addAll(listOf("http://10.0.2.2:8080", "http://127.0.0.1:8080"))
            }
            val uniqueCandidates = candidates.distinct()

            var attempts = 0
            while (isActive && _baseUrl.value == null) {
                for (url in uniqueCandidates) {
                    if (!isActive || _baseUrl.value != null) break
                    if (probeUrl(url)) {
                        updateBaseUrl(url)
                        break
                    }
                }

                attempts++
                // Exponential backoff: 2s, 4s, 8s, 16s, 32s
                val delayMs = min(32000.0, 2000.0 * 2.0.pow(attempts - 1)).toLong()

                // If we reach 6 attempts (approx 1 min), stop mDNS to save battery
                if (attempts >= 6) {
                    Log.w(TAG, "Max probe attempts reached. Stopping mDNS.")
                    stopMdns()
                }

                delay(delayMs)
            }
        }
    }

    private fun stopMdns() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {}
        nsdManager = null
        discoveryListener = null
    }

    private fun stopDiscovery() {
        stopMdns()
        probeJob?.cancel()
        probeJob = null
    }

    fun stop() {
        stopDiscovery()
        appContext?.let { ctx ->
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cb -> cm.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        appContext = null
        GlobalSocketManager.disconnect()
        Log.i(TAG, "Service discovery stopped entirely")
    }

    fun reportFailure(url: String) {
        val isEmulator = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator")
        if (com.company.skolab.BuildConfig.DEBUG && isEmulator) return

        val current = _baseUrl.value ?: return
        if (url.startsWith(current) || current.startsWith(url)) {
            Log.w(TAG, "Reported failure for active backend URL: $url. Resetting baseUrl to null.")
            _isBackendAvailable.value = false
            _baseUrl.value = null
            GlobalSocketManager.disconnect()

            appContext?.let { ctx ->
                try {
                    val prefs = ctx.getSharedPreferences("server_locator_prefs", Context.MODE_PRIVATE)
                    prefs.edit().remove("last_resolved_url").apply()
                } catch (e: Exception) {}

                val now = System.currentTimeMillis()
                if (now - lastFailureReportTime > 15_000) {
                    lastFailureReportTime = now
                    if (isNetworkSuitable) {
                        Log.i(TAG, "Restarting server discovery...")
                        stopDiscovery()
                        startDiscovery()
                    }
                }
            }
        }
    }

    private fun probeUrl(urlString: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildDiscoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery start failed: error $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Discovery stop failed: error $errorCode")
        }
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceName == AppConfig.MDNS_SERVICE_NAME) {
                resolveService(service)
            }
        }
        override fun onServiceLost(service: NsdServiceInfo) {}
    }

    @Suppress("DEPRECATION")
    private fun resolveService(service: NsdServiceInfo) {
        if (resolveInProgress) return
        resolveInProgress = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nsdManager?.registerServiceInfoCallback(
                service,
                Executors.newSingleThreadExecutor(),
                object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        resolveInProgress = false
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
                                if (probeUrl(url)) {
                                    resolvedUrl = url
                                    break
                                }
                            }
                            resolveInProgress = false
                            if (resolvedUrl != null) {
                                updateBaseUrl(resolvedUrl)
                                try {
                                    nsdManager?.unregisterServiceInfoCallback(callbackInstance)
                                } catch (e: Exception) {}
                            }
                        }
                    }
                    override fun onServiceLost() {}
                    override fun onServiceInfoCallbackUnregistered() {}
                }
            )
        } else {
            resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolveInProgress = false
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolveInProgress = false
                    val port = serviceInfo.port
                    @Suppress("DEPRECATION")
                    val hostAddresses = listOfNotNull(serviceInfo.host)
                    Executors.newSingleThreadExecutor().execute {
                        var resolvedUrl: String? = null
                        for (addr in hostAddresses) {
                            val host = addr.hostAddress ?: continue
                            val url = "http://$host:$port"
                            if (probeUrl(url)) {
                                resolvedUrl = url
                                break
                            }
                        }
                        if (resolvedUrl != null) {
                            updateBaseUrl(resolvedUrl)
                        }
                    }
                }
            }
            nsdManager?.resolveService(service, resolveListener)
        }
    }
}
