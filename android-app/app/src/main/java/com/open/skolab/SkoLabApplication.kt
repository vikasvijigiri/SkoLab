package com.open.skolab

import android.app.Application
import com.open.skolab.network.ServerLocator

/**
 * SkoLabApplication — application entry-point.
 *
 * Responsibilities:
 *  - Start [ServerLocator] so backend discovery runs from the very first frame.
 *  - Any other app-wide singletons or DI initialisation belong here.
 */
class SkoLabApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ServerLocator.start(this)
    }

    override fun onTerminate() {
        ServerLocator.stop()
        super.onTerminate()
    }
}
