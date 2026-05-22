package com.open.entropy

import android.app.Application
import com.open.entropy.network.ServerLocator

/**
 * ResQitApplication — application entry-point.
 *
 * Responsibilities:
 *  - Start [ServerLocator] so backend discovery runs from the very first frame.
 *  - Any other app-wide singletons or DI initialisation belong here.
 */
class ResQitApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ServerLocator.start(this)
    }

    override fun onTerminate() {
        ServerLocator.stop()
        super.onTerminate()
    }
}
