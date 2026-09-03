package com.company.skolab.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The single shared [OkHttpClient] for calls to the SkoLab gateway.
 *
 * It carries [AuthInterceptor], which attaches a fresh Firebase ID token to
 * gateway-bound requests when a user is signed in. Call sites that need a
 * tighter per-call timeout (e.g. autocomplete) should derive one with
 * `GatewayClient.instance.newBuilder()` so the interceptor and connection pool
 * are retained.
 */
object GatewayClient {

    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
