package com.open.skolab

import android.app.Application
import com.open.skolab.di.AppDependencies
import com.open.skolab.network.ServerLocator

/**
 * SkoLabApplication — application entry-point.
 *
 * Responsibilities:
 *  - Initialize [AppDependencies] so all screens share singleton services.
 *  - Start [ServerLocator] so backend discovery runs from the very first frame.
 */
class SkoLabApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialize singleton services BEFORE any screen or ViewModel is created.
        AppDependencies.init(this)
        ServerLocator.start(this)
    }

    override fun onTerminate() {
        ServerLocator.stop()
        super.onTerminate()
    }
}
