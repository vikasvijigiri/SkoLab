package com.company.skolab.network

import android.util.Log
import com.company.skolab.di.AppDependencies
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Attaches a fresh Firebase ID token as `Authorization: Bearer <token>` to
 * requests bound for the SkoLab gateway ([ServerLocator.baseUrl]).
 *
 * Behaviour:
 * - No header when signed out (`AppDependencies.authManager.currentUser == null`).
 *   The gateway's `/recommendations/peers*` routes run under transitional
 *   optional verification until the hard-auth flip, so the request still proceeds.
 * - The token is fetched per request via `FirebaseUser.getIdToken(false)`; the
 *   Firebase SDK caches it and refreshes it near expiry. It is never stored here.
 * - Only requests whose host and port match the configured gateway get the
 *   header, so a stray third-party call can never carry the token.
 *
 * This runs on an OkHttp dispatcher thread, so the `runBlocking` bridge to the
 * `Task` await is safe — the call is already off the main thread.
 */
class AuthInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!isGatewayHost(request)) {
            return chain.proceed(request)
        }

        val user = try {
            AppDependencies.authManager.currentUser
        } catch (e: Exception) {
            Log.w(TAG, "AuthManager unavailable; sending request without Authorization header", e)
            null
        } ?: return chain.proceed(request)

        val token = try {
            runBlocking {
                withTimeoutOrNull(TOKEN_TIMEOUT_MS) {
                    user.getIdToken(false).await().token
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch Firebase ID token; sending request without Authorization header", e)
            null
        }

        if (token.isNullOrBlank()) {
            return chain.proceed(request)
        }

        val authed = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(authed)
    }

    private fun isGatewayHost(request: Request): Boolean {
        val gateway = ServerLocator.baseUrl.value?.toHttpUrlOrNull() ?: return false
        val target = request.url
        return target.host.equals(gateway.host, ignoreCase = true) && target.port == gateway.port
    }

    private companion object {
        const val TAG = "AuthInterceptor"
        const val TOKEN_TIMEOUT_MS = 10_000L
    }
}
