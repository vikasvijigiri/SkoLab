package com.company.skolab.di

import android.content.Context
import com.company.skolab.auth.AuthManager
import com.company.skolab.network.ApiService

/**
 * AppDependencies — lightweight application-scoped singleton holder.
 *
 * This provides a single instance of heavyweight services (AuthManager, ApiService)
 * shared across the entire app. All Composables and ViewModels should reference
 * these singletons instead of constructing new instances with `remember { ... }`.
 *
 * Initialized once in [com.company.skolab.SkoLabApplication.onCreate].
 *
 * Industry note: A full Hilt/Dagger DI setup is the canonical next step,
 * but this approach provides all the correctness benefits with minimal overhead.
 */
object AppDependencies {

    private lateinit var _authManager: AuthManager
    private lateinit var _apiService: ApiService

    /** Application-scoped [AuthManager]. Single Firebase Auth + Firestore instance. */
    val authManager: AuthManager
        get() {
            check(::_authManager.isInitialized) {
                "AppDependencies not initialized. Call AppDependencies.init(context) in Application.onCreate()."
            }
            return _authManager
        }

    /** Application-scoped [ApiService]. Single OkHttp client shared across all screens. */
    val apiService: ApiService
        get() {
            check(::_apiService.isInitialized) {
                "AppDependencies not initialized. Call AppDependencies.init(context) in Application.onCreate()."
            }
            return _apiService
        }

    /**
     * Initialize all singleton dependencies.
     * Must be called once from [android.app.Application.onCreate] before any
     * screen or ViewModel accesses these instances.
     *
     * @param context Application context — never pass Activity context here.
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        _authManager = AuthManager(appContext)
        _apiService = ApiService()
    }
}
