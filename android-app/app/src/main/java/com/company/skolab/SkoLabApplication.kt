package com.company.skolab

import android.app.Application
import android.os.StrictMode
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.company.skolab.analytics.SkoLabAnalytics
import com.company.skolab.di.AppDependencies
import com.company.skolab.network.ServerLocator

/**
 * SkoLabApplication — application entry-point.
 *
 * Responsibilities:
 *  - Initialize [AppDependencies] so all screens share singleton services.
 *  - Start [ServerLocator] so backend discovery runs from the very first frame.
 *  - Initialize [SkoLabAnalytics] for product-level event tracking.
 *  - Configure [FirebaseCrashlytics] for real-time crash reporting.
 *  - Enable [StrictMode] in DEBUG builds to surface ANR-risk operations early.
 */
class SkoLabApplication : Application() {

    companion object {
        lateinit var instance: SkoLabApplication
            private set
    }

    override fun onCreate() {
        instance = this

        // Enable StrictMode BEFORE any other initialization in DEBUG builds.
        // This surfaces disk/network IO on the main thread, leaked closeable objects,
        // and untagged network sockets during development — not in release builds.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()              // Detect disk reads/writes + network on main thread
                    .penaltyLog()             // Log violations to Logcat (tag: StrictMode)
                    .penaltyFlashScreen()     // Flash screen red to make violations unmissable
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }

        super.onCreate()
        // Initialize singleton services BEFORE any screen or ViewModel is created.
        AppDependencies.init(this)
        ServerLocator.start(this)

        // Analytics: initialize so logEvent calls work from any screen.
        SkoLabAnalytics.init(this)

        // Crashlytics: enable crash collection on release builds only.
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
    }

    override fun onTerminate() {
        ServerLocator.stop()
        super.onTerminate()
    }
}
