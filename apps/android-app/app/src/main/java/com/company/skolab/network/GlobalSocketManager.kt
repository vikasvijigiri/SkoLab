package com.company.skolab.network

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.util.concurrent.TimeUnit

object GlobalSocketManager {
    private const val TAG = "GlobalSocketManager"
    
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
        
    private var webSocket: WebSocket? = null
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    fun connect(baseUrl: String) {
        val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "/ws/system/health"
        Log.i(TAG, "Connecting to Health WebSocket: $wsUrl")
        
        val request = Request.Builder()
            .url(wsUrl)
            .build()
            
        webSocket?.cancel()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Health WebSocket Connected!")
                _isConnected.value = true
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Health WebSocket Closed: $reason")
                _isConnected.value = false
                ServerLocator.reportFailure(baseUrl)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Health WebSocket Failure", t)
                _isConnected.value = false
                ServerLocator.reportFailure(baseUrl)
            }
        })
    }
    
    fun disconnect() {
        webSocket?.close(1000, "Disconnect requested")
        webSocket = null
        _isConnected.value = false
    }
}
